package com.hector.cleanlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.hector.cleanlauncher.MainActivity.ADD;
import static com.hector.cleanlauncher.MainActivity.AppEntry;
import static com.hector.cleanlauncher.MainActivity.EXTRA_OPEN;
import static com.hector.cleanlauncher.MainActivity.OPEN_LIBRARY;
import static com.hector.cleanlauncher.MainActivity.OPEN_MENU;
import static com.hector.cleanlauncher.MainActivity.OPEN_SETTINGS;
import static com.hector.cleanlauncher.MainActivity.OPEN_TASKS;
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
 *
 * The cover is an overlay, so it doesn't pause the app beneath it: if it were ever left up
 * over a playing app, the screen would show the cover (or, before it draws, nothing) while the
 * app kept playing sound. So while it is up we check which app window is really in front
 * (getWindows) and take the cover down as soon as that isn't Amazon's home, and it never
 * outlives MAX_COVER_MS.
 */
public class HomeWatcherService extends AccessibilityService implements HomeView.Host {

    static final String MODE_CLEAN = "clean", MODE_TWICE = "twice", MODE_AMAZON = "amazon";

    static final String[] KNOWN_HOMES = {
            "com.amazon.tv.launcher", "com.amazon.firehomestarter",
            "com.amazon.tv.leanbacklauncher", "com.amazon.tv.ui.launcher",
            // Google TV / Android TV (emulators and other boxes)
            "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher"};

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
    /** While covering, check the front app this often; first check after Amazon's home is up. */
    private static final long WATCH_FIRST_MS = 1200, WATCH_MS = 1000;
    /** No cover outlives this unless an app pick is pending (that has PICK_TIMEOUT_MS). */
    private static final long MAX_COVER_MS = 20000;

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
    private long lastShowAt, lastHomeKeyAt, coverSince;
    /** Bumped each time the cover goes up, so stale window checks are ignored. */
    private int coverGen;
    /** getWindows() can wait on other apps, so it runs off the main thread. */
    private Handler checker;
    private final Map<String, Boolean> activityClasses = new HashMap<>();

    private final Runnable handoff = new Runnable() {
        @Override public void run() {
            if (!coverUp || picked) return;
            openLauncher(pendingOpen);
            if (retriesLeft-- > 0) handler.postDelayed(this, RETRY_MS);
        }
    };
    private final Runnable uncover = () -> removeCover("app in front");
    private final Runnable watch = this::checkFront;

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
        if (s != null) s.removeCover("launcher resumed");
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
            cover = new HomeView(this, this, true);   // wallpaper loads in refresh(), off-thread
        } catch (Exception e) {
            Log.w(TAG, "couldn't build the cover: " + e);
        }
        HandlerThread t = new HandlerThread("windows");
        t.start();
        checker = new Handler(t.getLooper());
        bindKeyWatcher();
        handler.postDelayed(memCheck, MEM_CHECK_MS);
    }

    // ------------------------------------------------------- low-memory watchdog

    private static final long MEM_CHECK_MS = 30000;
    private static final long MEM_LOW_BYTES = 350L * 1024 * 1024;
    /** Set by the watchdog, cleared by a cleanup: memory was low since the last one. */
    private static boolean memLow;
    private final Runnable memCheck = new Runnable() {
        @Override public void run() {
            handler.postDelayed(this, MEM_CHECK_MS);   // plain Handler: no wakelock
            watchMemory();
        }
    };

    /**
     * Only notes that memory is low. Force-stopping needs the Settings screen, which would flash
     * over whatever is playing, so the cleanup waits for the next Home press (see onHomeShown).
     */
    private void watchMemory() {
        if (!getSharedPreferences("home", MODE_PRIVATE).getBoolean("freeMem", true)) return;
        android.app.ActivityManager.MemoryInfo mi = Tasks.memory(this);
        if (!mi.lowMemory && mi.availMem >= MEM_LOW_BYTES) return;
        if (!memLow) {
            Log.d(TAG, "cleanup: memory is low (" + (mi.availMem / (1024 * 1024)) + " MB free, lowMemory="
                    + mi.lowMemory + "); will clean up on the next Home press");
        }
        memLow = true;
    }

    // ------------------------------------------------- cleanup when Home is shown

    private static final long CLEANUP_GAP_MS = 3 * 60 * 1000, CLEANUP_DELAY_MS = 1000;
    private static final long CLEANUP_LOW_BYTES = 400L * 1024 * 1024, CLEANUP_TARGET_BYTES = 550L * 1024 * 1024;
    private static final int CLEANUP_MAX_APPS = 3;
    private long lastCleanupAt;

    /** MainActivity came to the front (Home press or return from an app), not the first start. */
    static void onHomeShown() {
        HomeWatcherService s = instance;
        if (s != null) s.scheduleCleanup();
    }

    /** The user launched an app or opened a panel: no cleanup now. */
    static void cancelCleanup() {
        HomeWatcherService s = instance;
        if (s != null) s.handler.removeCallbacks(s.cleanupRun);
    }

    private void scheduleCleanup() {
        handler.removeCallbacks(cleanupRun);
        if (fsActive) return;   // returning from our own force-stop flow
        if (!getSharedPreferences("home", MODE_PRIVATE).getBoolean("freeMem", true)) return;
        long now = SystemClock.uptimeMillis();
        if (lastCleanupAt != 0 && now - lastCleanupAt < CLEANUP_GAP_MS) return;
        if (!memLow && Tasks.memory(this).availMem >= CLEANUP_LOW_BYTES) return;
        Log.d(TAG, "cleanup: scheduled (memLow=" + memLow + ")");
        handler.postDelayed(cleanupRun, CLEANUP_DELAY_MS);
    }

    private final Runnable cleanupRun = new Runnable() {
        @Override public void run() {
            if (fsActive) return;
            lastCleanupAt = SystemClock.uptimeMillis();
            memLow = false;
            final Map<String, AppEntry> known = new HashMap<>(AppCatalog.get(HomeWatcherService.this).apps());
            new Thread(() -> {
                Tasks.Snapshot snap = null;
                try {
                    snap = Tasks.processes(HomeWatcherService.this, known);
                } catch (Throwable ignored) { }
                if (snap == null) {
                    Log.w(TAG, "cleanup: couldn't read running apps (DUMP / usage access?)");
                    return;
                }
                long avail = Tasks.memory(HomeWatcherService.this).availMem;
                List<Map.Entry<String, Long>> cand = new ArrayList<>();
                for (Map.Entry<String, Long> e : snap.kb.entrySet()) {
                    String p = e.getKey();
                    Integer k = snap.kind.get(p);
                    if (AppCatalog.isProtected(HomeWatcherService.this, p) || p.equals(snap.top)
                            || (k != null && k == Tasks.KIND_FOREGROUND) || !known.containsKey(p)) continue;
                    cand.add(e);
                }
                Collections.sort(cand, (x, y) -> Long.compare(y.getValue(), x.getValue()));
                final List<String[]> pick = new ArrayList<>();
                for (Map.Entry<String, Long> e : cand) {
                    if (avail >= CLEANUP_TARGET_BYTES || pick.size() >= CLEANUP_MAX_APPS) break;
                    pick.add(new String[]{e.getKey(), known.get(e.getKey()).label, String.valueOf(e.getValue())});
                    avail += e.getValue() * 1024;
                }
                Log.i(TAG, "cleanup: " + cand.size() + " candidates, stopping " + pick.size()
                        + ", estimated free " + (avail / (1024 * 1024)) + " MB");
                if (pick.isEmpty()) return;
                handler.post(() -> {
                    // Only announce once there is something to close.
                    Toast.makeText(HomeWatcherService.this, "Freeing memory…", Toast.LENGTH_SHORT).show();
                    if (!forceStop(pick, true)) Log.w(TAG, "cleanup: force-stop flow unavailable");
                });
            }, "cleanup").start();
        }
    };

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN && !coverUp && cover != null) {
            Log.d(TAG, "onTrimMemory " + level + ": releasing cover bitmaps");
            cover.releaseBitmaps();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(memCheck);
        removeCover("service stopped");
        if (checker != null) checker.getLooper().quitSafely();
        if (keyWatchBound) {
            try { unbindService(keyWatch); } catch (Exception ignored) { }
            keyWatchBound = false;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() { }

    // -------------------------------------------------------- force stop (Tasks)

    private static final long FS_PAGE_MS = 6000, FS_DIALOG_MS = 2000, FS_POLL_MS = 200;
    /** True while the App info automation runs (and shortly after): Home logic stays quiet. */
    private boolean fsActive;
    private final List<String[]> fsQueue = new ArrayList<>();   // {pkg, label}
    private String fsPkg, fsLabel;
    private long fsDeadline, fsConfirmUntil;
    
    private int fsPageWindow = -1, fsDone, fsTotal;
    private boolean fsHome;
    private long fsFreedKb, fsKb;
    private final List<String> fsFreed = new ArrayList<>();

    /**
     * Force-stop each {pkg, label} through the system App info page, one after another, then
     * return to the Tasks panel. Returns false if the service isn't running or is busy.
     */
    static boolean forceStop(List<String[]> items) {
        return forceStop(items, false);
    }

    /** returnHome: automatic cleanup, which ends on the home screen instead of the Tasks panel. */
    static boolean forceStop(List<String[]> items, boolean returnHome) {
        HomeWatcherService s = instance;
        if (s == null || s.fsActive || items.isEmpty()) return false;
        for (String[] it : items) {
            if (!AppCatalog.isProtected(s, it[0])) s.fsQueue.add(it);
        }
        if (s.fsQueue.isEmpty()) return false;
        s.fsActive = true;
        s.fsHome = returnHome;
        s.fsFreedKb = 0;
        s.fsFreed.clear();
        s.fsDone = 0;
        s.fsTotal = s.fsQueue.size();
        s.fsNext();
        return true;
    }

    private void fsNext() {
        if (fsQueue.isEmpty()) {
            fsFinish();
            return;
        }
        String[] it = fsQueue.remove(0);
        fsPkg = it[0];
        fsLabel = it[1];
        try {
            fsKb = it.length > 2 ? Long.parseLong(it[2]) : 0;
        } catch (NumberFormatException e) {
            fsKb = 0;
        }
        fsStage = S_PAGE;
        fsAttempt = 0;
        fsGen++;
        fsPageWindow = -1;
        fsDeadline = SystemClock.uptimeMillis() + FS_PAGE_MS;
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + fsPkg)).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));   // fresh page, never a stale one
            Log.i(TAG, "forcestop: opened App info for " + fsPkg);
        } catch (Exception e) {
            Log.w(TAG, "forcestop: couldn't open App info for " + fsPkg + ": " + e);
            fsItemDone(false);
            return;
        }
        handler.removeCallbacks(fsTick);
        handler.postDelayed(fsTick, FS_POLL_MS);
    }

    private void fsItemDone(boolean ok) {
        handler.removeCallbacks(fsTick);
        if (ok) {
            fsDone++;
            fsFreedKb += fsKb;
            fsFreed.add(fsLabel);
        }
        fsPkg = null;
        fsNext();
    }

    private void fsFinish() {
        handler.removeCallbacks(fsTick);
        fsPkg = null;
        String msg = fsTotal == 1
                ? (fsDone == 1 ? "Closed " + fsLabel : "Couldn't close " + fsLabel)
                : "Closed " + fsDone + " of " + fsTotal + " apps";
        if (fsHome) {
            msg = fsFreed.isEmpty() ? null : "Freed ~" + (fsFreedKb / 1024) + " MB ("
                    + TextUtils.join(", ", fsFreed) + ")";
            Log.i(TAG, "cleanup: done, " + (msg == null ? "nothing closed" : msg));
        } else {
            Log.i(TAG, "forcestop: returned to Tasks (" + msg + ")");
        }
        if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        try {
            // Don't leave the last App info page behind in the Settings task.
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && isSettingsPkg(r.getPackageName())) {
                boolean back = performGlobalAction(GLOBAL_ACTION_BACK);
                Log.i(TAG, "forcestop: back out of Settings (" + back + ")");
            }
        } catch (Exception e) {
            Log.w(TAG, "forcestop: couldn't go back: " + e);
        }
        try {
            Intent back = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            if (!fsHome) back.putExtra(EXTRA_OPEN, OPEN_TASKS);
            startActivity(back);
        } catch (Exception e) {
            Log.w(TAG, "forcestop: couldn't return to Tasks: " + e);
        }
        // Keep Home handling quiet while our own return settles.
        handler.postDelayed(() -> {
            if (fsPkg == null) fsActive = false;
        }, 1500);
    }

    private final Runnable fsTick = new Runnable() {
        @Override public void run() {
            if (fsPkg == null) return;
            try {
                fsStep();
            } catch (Throwable t) {
                Log.w(TAG, "forcestop: step failed: " + t);
            }
            if (fsPkg != null) {
                handler.removeCallbacks(this);   // fsNext() may have queued a tick already
                handler.postDelayed(this, FS_POLL_MS);
            }
        }
    };

    private static boolean isSettingsPkg(CharSequence p) {
        if (p == null) return false;
        String s = p.toString();
        return s.startsWith("com.amazon.tv.settings") || s.equals("com.android.settings")
                || s.equals("com.android.tv.settings");
    }

    private static final int S_PAGE = 0, S_SETTLE = 1, S_GESTURE = 2, S_CONFIRM = 3, S_VERIFY = 4;
    private int fsStage = S_PAGE, fsAttempt, fsGen;
    private long fsStageAt;

    /** The page's Force stop node, after the safety check; null (and a reason) if not yet. */
    private AccessibilityNodeInfo fsFindButton(String[] why) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        CharSequence wp = root != null ? root.getPackageName() : null;
        if (root == null) {
            why[0] = "no active window";
            return null;
        }
        if (!isSettingsPkg(wp)) {
            why[0] = "window is " + wp;
            return null;
        }
        StringBuilder text = new StringBuilder();
        AccessibilityNodeInfo fs = fsCollect(root, text);
        String t = text.toString();
        if (!t.contains(fsLabel.toLowerCase()) && !t.contains(fsPkg.toLowerCase())) {
            why[0] = "page doesn't show " + fsLabel;
            return null;
        }
        if (fs == null) {
            why[0] = "no Force stop button yet";
            return null;
        }
        try {
            AccessibilityWindowInfo w = root.getWindow();
            fsPageWindow = w != null ? w.getId() : -1;
        } catch (Exception ignored) { fsPageWindow = -1; }
        why[0] = "passed (" + wp + ")";
        return fs;
    }

    private static AccessibilityNodeInfo clickableOf(AccessibilityNodeInfo n) {
        while (n != null && !n.isClickable()) n = n.getParent();
        return n;
    }

    /** A real touch at the centre of the node (ACTION_CLICK is ignored by the Fire TV Settings rows). */
    private boolean fsTap(AccessibilityNodeInfo n, final int gen, final boolean verifyAfter) {
        if (android.os.Build.VERSION.SDK_INT < 24) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        android.graphics.Rect r = new android.graphics.Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(r.centerX(), r.centerY());
        android.accessibilityservice.GestureDescription g =
                new android.accessibilityservice.GestureDescription.Builder().addStroke(
                        new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)).build();
        Log.i(TAG, "forcestop: tapped at " + r.centerX() + "," + r.centerY());
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(android.accessibilityservice.GestureDescription d) {
                Log.i(TAG, "forcestop: gesture completed");
                fsAfterGesture(gen, verifyAfter);
            }
            @Override public void onCancelled(android.accessibilityservice.GestureDescription d) {
                Log.w(TAG, "forcestop: gesture cancelled");
                fsAfterGesture(gen, verifyAfter);
            }
        }, handler);
        if (!sent) Log.w(TAG, "forcestop: dispatchGesture refused");
        return sent;
    }

    private void fsAfterGesture(int gen, boolean verifyAfter) {
        if (gen != fsGen || fsPkg == null) return;
        if (verifyAfter) {
            fsVerify();
        } else {
            fsStage = S_CONFIRM;   // a confirmation dialog may follow (Fire OS showed none)
            fsStageAt = SystemClock.uptimeMillis() + 1000;
        }
    }

    /** ~800 ms after the tap: is the app really gone? Only then report success. */
    private void fsVerify() {
        fsStage = S_VERIFY;
        final int gen = fsGen;
        final String pkg = fsPkg;
        handler.postDelayed(() -> {
            if (gen != fsGen || fsPkg == null) return;
            final java.util.Map<String, AppEntry> known = new HashMap<>(AppCatalog.get(this).apps());
            new Thread(() -> {
                Tasks.Snapshot snap = null;
                try {
                    snap = Tasks.processes(this, known);
                } catch (Throwable ignored) { }
                final Tasks.Snapshot r = snap;
                handler.post(() -> {
                    if (gen != fsGen || fsPkg == null) return;
                    if (r != null && !r.kb.containsKey(pkg)) {
                        Log.i(TAG, "forcestop: verified stopped: " + pkg);
                        fsItemDone(true);
                    } else if (fsAttempt == 0) {
                        Log.i(TAG, "forcestop: still running, retrying: " + pkg
                                + (r == null ? " (couldn't read the process list)" : ""));
                        fsAttempt = 1;
                        fsGen++;
                        fsStage = S_SETTLE;
                        fsStageAt = SystemClock.uptimeMillis() + 300;
                        fsDeadline = SystemClock.uptimeMillis() + 4000;
                    } else {
                        Log.w(TAG, "forcestop: still running, giving up: " + pkg
                                + (r == null ? " (couldn't read the process list)" : ""));
                        fsItemDone(false);
                    }
                });
            }, "fs-verify").start();
        }, 800);
    }

    private void fsStep() {
        long now = SystemClock.uptimeMillis();
        final String pkg = fsPkg;
        switch (fsStage) {
            case S_PAGE: {
                String[] why = new String[1];
                AccessibilityNodeInfo fs = fsFindButton(why);
                if (fs == null) {
                    if (now > fsDeadline) {
                        Log.w(TAG, "forcestop: timeout (page check failed: " + why[0] + ") for " + pkg);
                        fsItemDone(false);
                    }
                    return;   // still loading: never click anything until the check passes
                }
                Log.i(TAG, "forcestop: page check " + why[0] + " for " + pkg);
                if (!fs.isEnabled()) {
                    Log.i(TAG, "forcestop: button disabled, " + pkg + " is already stopped");
                    fsItemDone(true);
                    return;
                }
                fsStage = S_SETTLE;   // let the list settle before tapping
                fsStageAt = now + 400;
                return;
            }
            case S_SETTLE: {
                if (now < fsStageAt) return;
                String[] why = new String[1];
                AccessibilityNodeInfo fs = fsFindButton(why);   // same safety check, again
                if (fs == null) {
                    if (now > fsDeadline) {
                        Log.w(TAG, "forcestop: timeout (page check failed: " + why[0] + ") for " + pkg);
                        fsItemDone(false);
                    }
                    return;
                }
                if (!fs.isEnabled()) {
                    Log.i(TAG, "forcestop: button disabled, " + pkg + " is already stopped");
                    fsItemDone(true);
                    return;
                }
                AccessibilityNodeInfo box = clickableOf(fs);
                if (box == null) box = fs;
                if (fsAttempt > 0) {
                    boolean f = box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    boolean c = box.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Log.i(TAG, "forcestop: retry focus=" + f + " click=" + c);
                }
                fsGen++;
                fsStage = S_GESTURE;
                fsStageAt = now + 2500;   // if the callback never comes
                if (!fsTap(box, fsGen, false)) {
                    fsStage = S_CONFIRM;
                    fsStageAt = now + 1000;
                }
                return;
            }
            case S_GESTURE:
                if (now > fsStageAt) {
                    Log.w(TAG, "forcestop: no gesture result, going on");
                    fsStage = S_CONFIRM;
                    fsStageAt = now + 1000;
                }
                return;
            case S_CONFIRM:
                if (fsConfirm()) {
                    Log.i(TAG, "forcestop: dialog confirmed for " + pkg);
                    fsStage = S_VERIFY;   // fsConfirm's tap verifies after its gesture
                    return;
                }
                if (now > fsStageAt) {
                    Log.i(TAG, "forcestop: no dialog for " + pkg);
                    fsVerify();
                }
                return;
            default:
                return;   // S_VERIFY: waiting for the scan
        }
    }

    /** Walk the page: gather its text, and return the "Force stop" node if there is one. */
    private AccessibilityNodeInfo fsCollect(AccessibilityNodeInfo n, StringBuilder text) {
        AccessibilityNodeInfo found = null;
        java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(n);
        int seen = 0;
        while (!q.isEmpty() && seen++ < 800) {
            AccessibilityNodeInfo x = q.poll();
            CharSequence t = x.getText(), d = x.getContentDescription();
            if (t != null) text.append(t.toString().toLowerCase()).append('\n');
            if (d != null) text.append(d.toString().toLowerCase()).append('\n');
            if (found == null && (isForceStop(t) || isForceStop(d))) found = x;
            for (int i = 0; i < x.getChildCount(); i++) {
                AccessibilityNodeInfo c = x.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return found;
    }

    private static boolean isForceStop(CharSequence s) {
        if (s == null) return false;
        String t = s.toString().trim().toLowerCase();
        return t.equals("force stop") || t.equals("forzar detención") || t.equals("forzar detencion")
                || t.equals("forzar cierre");
    }

    /** Click a confirmation button in a Settings window; the app page's own button is skipped. */
    private boolean fsConfirm() {
        List<AccessibilityWindowInfo> wins;
        try {
            wins = getWindows();
        } catch (Exception e) {
            return false;
        }
        if (wins == null) return false;
        for (AccessibilityWindowInfo w : wins) {
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || !isSettingsPkg(root.getPackageName())) continue;
            boolean pageWindow = w.getId() == fsPageWindow;
            java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
            q.add(root);
            int seen = 0;
            while (!q.isEmpty() && seen++ < 800) {
                AccessibilityNodeInfo x = q.poll();
                CharSequence t = x.getText();
                String tl = t == null ? "" : t.toString().trim().toLowerCase();
                boolean hit = "android:id/button1".equals(x.getViewIdResourceName())
                        || tl.equals("ok") || tl.equals("aceptar")
                        || (!pageWindow && isForceStop(t));
                if (hit && x.isEnabled()) {
                    AccessibilityNodeInfo n = clickableOf(x);
                    if (n != null && fsTap(n, fsGen, true)) return true;
                }
                for (int i = 0; i < x.getChildCount(); i++) {
                    AccessibilityNodeInfo c = x.getChild(i);
                    if (c != null) q.add(c);
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ events

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (fsActive) return;   // force-stop flow: Settings and our own return are not Home
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

        if (!isHome(pkg, prefs)) {
            if (contains(NOT_HOMES, pkg)) return;
            // Another app is in front: it's what was picked, or the user moved on.
            if (AppCatalog.get(this).apps().containsKey(pkg)) stayOnAmazon = false;
            if (coverUp) {
                handler.removeCallbacks(uncover);
                handler.postDelayed(uncover, REVEAL_MS);
            }
            return;
        }

        if (stayOnAmazon || MODE_AMAZON.equals(mode(prefs))) return;
        if (now - lastShowAt < DEBOUNCE_MS) return;
        if (isActivity(pkg, e.getClassName())) {
            lastShowAt = now;
            showCover();
        } else {
            // A popup or a view inside Amazon's home, not its home screen arriving: it can
            // happen while another app is in front. Cover only if the home really is in front.
            final String cls = String.valueOf(e.getClassName());
            frontApp(top -> {
                if (top != null && isHome(top, getSharedPreferences("home", MODE_PRIVATE))
                        && !coverUp && !stayOnAmazon) {
                    Log.i(TAG, "home in front after " + cls);
                    lastShowAt = SystemClock.uptimeMillis();
                    showCover();
                }
            });
        }
    }

    private boolean isHome(String pkg, SharedPreferences prefs) {
        return contains(KNOWN_HOMES, pkg) || pkg.equals(prefs.getString("homePkg", ""));
    }

    /** Whether a window-state event names an activity (a screen), not a dialog or view. */
    private boolean isActivity(String pkg, CharSequence cls) {
        if (cls == null) return true;
        String name = cls.toString();
        if (name.startsWith(".")) name = pkg + name;   // relative class names, e.g. ".home.HomeActivity"
        String key = pkg + "/" + name;
        Boolean known = activityClasses.get(key);
        if (known == null) {
            try {
                getPackageManager().getActivityInfo(new ComponentName(pkg, name), 0);
                known = true;
            } catch (PackageManager.NameNotFoundException e) {
                known = false;
            } catch (Exception e) {
                known = true;
            }
            activityClasses.put(key, known);
        }
        return known;
    }

    /** From HomeKeyWatcher: the system started the HOME intent, i.e. Home was pressed. */
    private void onHomeKey(int uid) {
        if (fsActive) return;
        if (uid != android.os.Process.SYSTEM_UID && uid != 0) return;
        long now = SystemClock.uptimeMillis();
        boolean second = now - lastHomeKeyAt < DOUBLE_PRESS_MS;
        lastHomeKeyAt = now;

        String mode = mode(getSharedPreferences("home", MODE_PRIVATE));
        if (MODE_AMAZON.equals(mode)) return;
        if (MODE_TWICE.equals(mode) && second && coverUp) {
            stayOnAmazon = true;               // second press: show Amazon's home
            removeCover("second Home press");
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
            coverSince = SystemClock.uptimeMillis();
            restartHandoff();
            return;
        }
        cover.refresh();
        // Focusable, so it takes the D-pad. Not touchable (a TV has no touch), which also
        // keeps the windows beneath it visible to getWindows(). Translucent, so if it is ever
        // up before it has drawn, Amazon's home shows through instead of a black screen.
        coverLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        try {
            wm.addView(cover, coverLp);
        } catch (Exception e) {
            Log.w(TAG, "cover refused, starting the launcher instead: " + e);
            cover.releaseBitmaps();
            openLauncher(null);
            return;
        }
        coverUp = true;
        coverGen++;
        coverSince = SystemClock.uptimeMillis();
        picked = false;
        pendingOpen = null;
        cover.startClock();
        cover.focusDock(true);
        restartHandoff();
        handler.removeCallbacks(watch);
        handler.postDelayed(watch, WATCH_FIRST_MS);
        Log.i(TAG, "cover up");
    }

    // ------------------------------------------------------------ front app check

    interface FrontApp { void on(String pkg); }

    /** Looks up the front app off the main thread and reports back on it (null if unknown). */
    private void frontApp(final FrontApp cb) {
        if (checker == null) {
            cb.on(null);
            return;
        }
        checker.post(() -> {
            final String top = topAppPackage();
            handler.post(() -> cb.on(top));
        });
    }

    /** While covering: is Amazon's home (still) the app in front? If not, uncover. */
    private void checkFront() {
        if (!coverUp) return;
        final int gen = coverGen;
        frontApp(top -> {
            if (!coverUp || gen != coverGen) return;
            Log.i(TAG, "front app under the cover: " + top);
            SharedPreferences prefs = getSharedPreferences("home", MODE_PRIVATE);
            if (top != null && !isHome(top, prefs)) {
                if (picked) {
                    handler.removeCallbacks(uncover);
                    handler.postDelayed(uncover, REVEAL_MS);   // let the picked app draw first
                } else {
                    removeCover(top + " is in front");
                }
                return;
            }
            if (!picked && SystemClock.uptimeMillis() - coverSince > MAX_COVER_MS) {
                removeCover("launcher didn't take over in time");
                return;
            }
            handler.postDelayed(watch, WATCH_MS);
        });
    }

    /**
     * The package of the topmost app window, skipping ours and system overlays, or null if
     * the system won't say. Runs on the checker thread.
     */
    private String topAppPackage() {
        List<AccessibilityWindowInfo> all;
        try {
            all = getWindows();
        } catch (Exception e) {
            return null;
        }
        if (all == null || all.isEmpty()) return null;
        List<AccessibilityWindowInfo> apps = new ArrayList<>();
        for (AccessibilityWindowInfo w : all) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) apps.add(w);
        }
        Collections.sort(apps, (a, b) -> b.getLayer() - a.getLayer());
        String found = null;
        for (AccessibilityWindowInfo w : apps) {
            AccessibilityNodeInfo root = null;
            try {
                root = w.getRoot();
            } catch (Exception ignored) { }
            if (root == null) continue;
            CharSequence p = root.getPackageName();
            root.recycle();
            if (p == null) continue;
            String pkg = p.toString();
            if (pkg.equals(getPackageName()) || contains(NOT_HOMES, pkg)) continue;
            found = pkg;
            break;
        }
        for (AccessibilityWindowInfo w : all) w.recycle();
        return found;
    }

    private void restartHandoff() {
        handler.removeCallbacks(handoff);
        retriesLeft = RETRIES;
        handler.postDelayed(handoff, HANDOFF_MS);
    }

    private void removeCover(String why) {
        handler.removeCallbacks(handoff);
        handler.removeCallbacks(uncover);
        handler.removeCallbacks(watch);
        if (!coverUp) return;
        try {
            wm.removeView(cover);
        } catch (Exception e) {
            Log.w(TAG, "couldn't remove the cover: " + e);
        }
        Log.i(TAG, "cover down: " + why);
        cover.stopClock();
        cover.hideLaunching();
        cover.releaseBitmaps();
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
        cover.showLaunching(a.label);
        try {
            AppCatalog.get(this).freeMemory(a.pkg, "launch from cover");
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
    @Override public void openTasks() { openPanel(OPEN_TASKS); }
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
