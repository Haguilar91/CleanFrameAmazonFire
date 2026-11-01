package com.hector.cleanlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import static com.hector.cleanlauncher.MainActivity.ADD;
import static com.hector.cleanlauncher.MainActivity.AppEntry;
import static com.hector.cleanlauncher.MainActivity.EXTRA_OPEN;
import static com.hector.cleanlauncher.MainActivity.OPEN_LIBRARY;
import static com.hector.cleanlauncher.MainActivity.OPEN_MENU;
import static com.hector.cleanlauncher.MainActivity.OPEN_SETTINGS;
import static com.hector.cleanlauncher.MainActivity.OPEN_WALLPAPERS;

/**
 * Fire OS can't change the default launcher, so when Amazon's home screen comes to the front
 * we put Clean Launcher back, per the "Home button" setting.
 *
 * Fire OS holds back activity starts for about 5 s after Home is pressed, so we can't simply
 * start MainActivity. Instead a cover (a HomeView in an accessibility overlay, which needs no
 * extra permission) goes up over Amazon's home at once and works like the real Home screen.
 * Once the hold is over MainActivity takes over and the cover is removed. An app picked on
 * the cover is started too (Fire OS queues it), and the cover stays until it appears.
 *
 * HomeKeyWatcher, when READ_LOGS has been granted, reports Home presses earlier and also those
 * made while Amazon's home is already in front, which change no window.
 */
public class HomeWatcherService extends AccessibilityService implements HomeView.Host {

    static final String MODE_CLEAN = "clean", MODE_TWICE = "twice", MODE_AMAZON = "amazon";

    static final String[] KNOWN_HOMES = {
            "com.amazon.tv.launcher", "com.amazon.firehomestarter",
            "com.amazon.tv.leanbacklauncher", "com.amazon.tv.ui.launcher"};

    /** Ignored while learning: overlays and keyboards, not home screens. */
    static final String[] NOT_HOMES = {
            "com.android.systemui", "android", "com.amazon.tv.inputpreference.service",
            "com.amazon.vizzini", "com.amazon.tv.notificationcenter"};

    private static final String TAG = "CleanLauncher";

    /** Fire OS's hold lasts ~5 s from the last Home press. */
    private static final long HANDOFF_MS = 5200;
    /** If MainActivity hasn't come up, start it again this often, this many times. */
    private static final long RETRY_MS = 3000;
    private static final int RETRIES = 4;
    /** Give up covering for a picked app that never appears. */
    private static final long PICK_TIMEOUT_MS = 10000;
    /** Let a newly arrived app draw before uncovering it. */
    private static final long REVEAL_MS = 400;
    /** Amazon's home can report one press as two window changes. */
    private static final long DEBOUNCE_MS = 350;
    private static final long DOUBLE_PRESS_MS = 1500;

    private static HomeWatcherService instance;
    /** Set by "Open Amazon home": leave Amazon's home alone until Home is pressed or an app opens. */
    private static boolean stayOnAmazon;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private HomeView cover;
    private WindowManager.LayoutParams coverLp;
    private boolean coverUp, picked, keyWatchBound;
    private String pendingOpen;
    private int retriesLeft;
    private long lastShowAt, lastHomeKeyAt;

    private final Runnable handoff = new Runnable() {
        @Override public void run() {
            if (!coverUp || picked) return;
            openLauncher(pendingOpen);
            if (retriesLeft-- > 0) handler.postDelayed(this, RETRY_MS);
        }
    };
    private final Runnable uncover = this::removeCover;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (cover != null && !coverUp) cover.reloadApps();
        }
    };

    // ------------------------------------------------------- MainActivity API

    /** MainActivity is in front. */
    static void onLauncherResumed() {
        stayOnAmazon = false;
    }

    /** Fast Home key detection is on unless the user switched it to Basic. */
    static boolean keyFast(Context c) {
        return c.getSharedPreferences("home", MODE_PRIVATE).getBoolean("keyFast", true);
    }

    /** Apply the Fast/Basic choice now. */
    static void refreshKeyWatch() {
        HomeWatcherService s = instance;
        if (s == null) return;
        if (keyFast(s)) {
            s.bindKeyWatcher();
        } else if (s.keyWatchBound) {
            try { s.unbindService(s.keyWatch); } catch (Exception ignored) { }
            s.keyWatchBound = false;
        }
    }

    /** Opening Fire TV's own screens: some live in Amazon's home package, so don't cover them. */
    static void leaveForAmazon() {
        stayOnAmazon = true;
        hideCover();
    }

    /** The Home item focused on the cover, so MainActivity can carry on from it. */
    static String coverFocus() {
        HomeWatcherService s = instance;
        return s != null && s.coverUp ? s.cover.focusedPkg() : null;
    }

    static void hideCover() {
        HomeWatcherService s = instance;
        if (s != null) s.removeCover();
    }

    /** Start Amazon's home without the HOME category, and don't redirect away from it. */
    static boolean openAmazonHome(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) {
            if (!contains(KNOWN_HOMES, ri.activityInfo.packageName)) continue;
            try {
                stayOnAmazon = true;
                hideCover();
                c.startActivity(new Intent(Intent.ACTION_MAIN)
                        .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception e) {
                stayOnAmazon = false;
                Log.w(TAG, "couldn't open Amazon home: " + e);
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            cover = new HomeView(this, this);
        } catch (Exception e) {
            Log.w(TAG, "couldn't build the cover: " + e);
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        bindKeyWatcher();
    }

    @Override
    public void onDestroy() {
        removeCover();
        try { unregisterReceiver(packages); } catch (Exception ignored) { }
        if (keyWatchBound) {
            try { unbindService(keyWatch); } catch (Exception ignored) { }
            keyWatchBound = false;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() { }

    // ------------------------------------------------------------------ events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (pkg.equals(getPackageName())) return;

        SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
        long now = SystemClock.uptimeMillis();

        // "Detect Home screen": the first other app to appear is the home screen.
        long learnUntil = prefs.getLong("learnUntil", 0);
        if (learnUntil > 0) {
            if (System.currentTimeMillis() > learnUntil) {
                prefs.edit().remove("learnUntil").apply();
            } else if (!contains(NOT_HOMES, pkg)) {
                prefs.edit().remove("learnUntil").putString("homePkg", pkg).apply();
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("learned", pkg));
                return;
            }
        }

        boolean isHome = contains(KNOWN_HOMES, pkg) || pkg.equals(prefs.getString("homePkg", ""));
        if (!isHome) {
            if (contains(NOT_HOMES, pkg)) return;
            // Another app is in front: it's what was picked, or the user moved on.
            if (cover != null && cover.apps.containsKey(pkg)) stayOnAmazon = false;
            if (coverUp) {
                handler.removeCallbacks(uncover);
                handler.postDelayed(uncover, REVEAL_MS);
            }
            return;
        }

        if (stayOnAmazon || MODE_AMAZON.equals(mode(prefs))) return;
        if (now - lastShowAt < DEBOUNCE_MS) return;
        lastShowAt = now;
        showCover();
    }

    /** From HomeKeyWatcher: the system started the HOME intent, i.e. Home was pressed. */
    private void onHomeKey(int uid) {
        if (uid != android.os.Process.SYSTEM_UID && uid != 0) return;
        long now = SystemClock.uptimeMillis();
        boolean second = now - lastHomeKeyAt < DOUBLE_PRESS_MS;
        lastHomeKeyAt = now;

        String mode = mode(getSharedPreferences("home", MODE_PRIVATE));
        if (MODE_AMAZON.equals(mode)) return;
        if (MODE_TWICE.equals(mode) && second && coverUp) {
            stayOnAmazon = true;               // second press: show Amazon's home
            removeCover();
            return;
        }
        stayOnAmazon = false;
        lastShowAt = now;
        showCover();
    }

    private static String mode(SharedPreferences prefs) {
        return prefs.getString("homeMode", MODE_CLEAN);
    }

    // ------------------------------------------------------------------- cover

    private void showCover() {
        bindKeyWatcher();                      // in case READ_LOGS was granted since
        if (cover == null) {
            openLauncher(null);
            return;
        }
        if (coverUp) {
            // Home again while covering: Fire OS restarts its hold, so restart ours.
            if (picked) return;
            cover.focusDock(true);
            restartHandoff();
            return;
        }
        cover.refresh();
        coverLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,   // focusable: takes the D-pad
                PixelFormat.OPAQUE);
        try {
            wm.addView(cover, coverLp);
        } catch (Exception e) {
            Log.w(TAG, "cover refused, starting the launcher instead: " + e);
            openLauncher(null);
            return;
        }
        coverUp = true;
        picked = false;
        pendingOpen = null;
        cover.startClock();
        cover.focusDock(true);
        restartHandoff();
    }

    private void restartHandoff() {
        handler.removeCallbacks(handoff);
        retriesLeft = RETRIES;
        handler.postDelayed(handoff, HANDOFF_MS);
    }

    private void removeCover() {
        handler.removeCallbacks(handoff);
        handler.removeCallbacks(uncover);
        if (!coverUp) return;
        try {
            wm.removeView(cover);
        } catch (Exception e) {
            Log.w(TAG, "couldn't remove the cover: " + e);
        }
        cover.stopClock();
        coverUp = false;
        picked = false;
        pendingOpen = null;
    }

    private void setCoverFocusable(boolean focusable) {
        if (!coverUp) return;
        if (focusable) coverLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else coverLp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        try {
            wm.updateViewLayout(cover, coverLp);
        } catch (Exception ignored) { }
    }

    private void openLauncher(String open) {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (open != null) i.putExtra(EXTRA_OPEN, open);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "couldn't start the launcher: " + e);
        }
    }

    // ----------------------------------------------- HomeView.Host (the cover)

    @Override
    public void launch(AppEntry a) {
        if (ADD.equals(a.pkg)) {
            openPanel(MainActivity.OPEN_ADD);
            return;
        }
        // Fire OS queues this start until its hold is over. Keep covering Amazon's home until
        // the app appears, but stop taking keys so its window changes reach us.
        picked = true;
        handler.removeCallbacks(handoff);
        setCoverFocusable(false);
        try {
            startActivity(a.intent);
        } catch (Exception e) {
            Log.w(TAG, "couldn't open " + a.pkg + ": " + e);
        }
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, PICK_TIMEOUT_MS);
    }

    @Override public void openLibrary() { openPanel(OPEN_LIBRARY); }
    @Override public void openSettings() { openPanel(OPEN_SETTINGS); }
    @Override public void openWallpapers() { openPanel(OPEN_WALLPAPERS); }
    @Override public void showMenu(AppEntry a, boolean inDock) { openPanel(OPEN_MENU + a.pkg); }

    /** Panels live in MainActivity: ask for one now, and it opens when MainActivity can start. */
    private void openPanel(String what) {
        pendingOpen = what;
        handler.removeCallbacks(handoff);
        retriesLeft = RETRIES;
        handler.post(handoff);
    }

    // ------------------------------------------------------------ Home key watch

    private final Messenger fromWatcher = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message m) {
            if (m.what == HomeKeyWatcher.MSG_HOME) onHomeKey(m.arg1);
        }
    });

    private final ServiceConnection keyWatch = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            try {
                Message m = Message.obtain(null, HomeKeyWatcher.MSG_REGISTER);
                m.replyTo = fromWatcher;
                new Messenger(b).send(m);
            } catch (RemoteException ignored) { }
        }
        // If its process dies the system restarts it and calls onServiceConnected again.
        @Override public void onServiceDisconnected(ComponentName n) { }
    };

    private void bindKeyWatcher() {
        if (keyWatchBound || !keyFast(this) || !HomeKeyWatcher.permitted(this)) return;
        try {
            keyWatchBound = bindService(new Intent(this, HomeKeyWatcher.class), keyWatch,
                    BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.w(TAG, "couldn't start Home key detection: " + e);
        }
    }

    private static boolean contains(String[] list, String s) {
        for (String x : list) if (x.equals(s)) return true;
        return false;
    }
}
