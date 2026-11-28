package com.hector.cleanlauncher;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.hector.cleanlauncher.MainActivity.AppEntry;
import static com.hector.cleanlauncher.MainActivity.BRANDS;
import static com.hector.cleanlauncher.MainActivity.BRAND_NAMES;

/**
 * The launchable apps, loaded once per process and shared by MainActivity and the cover.
 *
 * Fire OS sends package-changed broadcasts about once a minute (Amazon's own services toggling
 * components). Reloading every icon on the main thread for each one, once per HomeView, made
 * the UI stutter, even while another app was playing. Now a burst of broadcasts causes a
 * single background scan, icons are reused unless the app itself changed, and the screens
 * only rebuild when the list really differs ({@link #version()} changes).
 */
final class AppCatalog {

    private static final String TAG = "CleanLauncher";
    /** Wait for a burst of package broadcasts to settle before scanning. */
    private static final long SETTLE_MS = 1500;

    private static AppCatalog instance;

    static synchronized AppCatalog get(Context c) {
        if (instance == null) instance = new AppCatalog(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler bg;
    /** Icons by {@link #iconKey}; touched only inside synchronized scan(). */
    private final Map<String, Drawable> icons = new HashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private volatile Map<String, AppEntry> apps = Collections.emptyMap();
    private String signature = "";
    private int version;

    private final Runnable rescan = new Runnable() {
        @Override public void run() {
            final Scan s = scan();
            main.post(() -> publish(s));
        }
    };

    private AppCatalog(Context c) {
        ctx = c;
        HandlerThread t = new HandlerThread("apps", Process.THREAD_PRIORITY_BACKGROUND);
        t.start();
        bg = new Handler(t.getLooper());
        publish(scan());   // first load in line, so the first frame already has the apps

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent i) {
                bg.removeCallbacks(rescan);
                bg.postDelayed(rescan, SETTLE_MS);
            }
        }, f);
    }

    /** Never listed or killed: Amazon, system and Android packages (and us). */
    static boolean isProtected(Context c, String p) {
        return p.equals(c.getPackageName()) || p.equals("android")
                || p.startsWith("com.amazon.") || p.startsWith("amazon.")
                || p.startsWith("com.android.");
    }

    /**
     * Ask Android to drop the cached background processes of the other launchable apps, so the
     * app being opened has the memory. killBackgroundProcesses only touches processes that are
     * already in the background (never the one in front), so it is safe. Protected packages
     * are never targeted. Runs on the apps thread.
     */
    void freeMemory(final String keep, final String why) {
        if (!ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("freeMem", true)) return;
        final List<String> pkgs = new ArrayList<>(apps.keySet());
        bg.post(() -> killAll(ctx, pkgs, keep, why));
    }

    /** Synchronous: the caller picks the thread. Returns how many packages were targeted. */
    static int killAll(Context ctx, List<String> pkgs, String keep, String why) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 0;
        int n = 0;
        for (String p : pkgs) {
            if (p.equals(keep) || isProtected(ctx, p)) continue;
            try {
                am.killBackgroundProcesses(p);
                n++;
            } catch (Exception e) {
                // no permission or gone: nothing to free
            }
        }
        Log.d(TAG, "killBackgroundProcesses for " + n + " apps (" + why + ", keeping " + keep + ")");
        return n;
    }

    List<String> packages() {
        return new ArrayList<>(apps.keySet());
    }

    /** Current apps by package. Treat as read-only. */
    Map<String, AppEntry> apps() {
        return apps;
    }

    /** Bumped whenever the app list, a label or an icon changes. */
    int version() {
        return version;
    }

    /** Called on the main thread after each change. */
    void addListener(Runnable r) {
        listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    // --------------------------------------------------------------------- scan

    private static final class Scan {
        Map<String, AppEntry> apps;
        String signature;
    }

    private void publish(Scan s) {
        if (s.signature.equals(signature)) return;   // the usual case: nothing we show changed
        apps = s.apps;
        signature = s.signature;
        version++;
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    private synchronized Scan scan() {
        long t0 = System.currentTimeMillis();
        PackageManager pm = ctx.getPackageManager();
        Map<String, AppEntry> found = new HashMap<>();
        Map<String, Drawable> keep = new HashMap<>();
        List<String> sig = new ArrayList<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            List<ResolveInfo> list;
            try {
                list = pm.queryIntentActivities(q, 0);
            } catch (Exception e) {
                Log.w(TAG, "couldn't list apps: " + e);
                continue;
            }
            for (ResolveInfo ri : list) {
                ActivityInfo ai = ri.activityInfo;
                String pkg = ai.packageName;
                if (pkg.equals(ctx.getPackageName()) || found.containsKey(pkg)
                        || pkg.equals("com.amazon.tv.launcher")
                        || pkg.equals("com.amazon.firehomestarter")) continue;
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.label = String.valueOf(ri.loadLabel(pm));
                Integer brand = BRANDS.get(pkg);
                if (brand == null) brand = brandByName(e.label);
                String key = iconKey(ri, brand);
                Drawable icon = icons.get(key);
                if (icon == null) {
                    try {
                        icon = brand != null ? ctx.getDrawable(brand) : ri.loadIcon(pm);
                    } catch (Exception ex) {
                        icon = null;
                    }
                }
                if (icon != null && brand == null && !icons.containsKey(key)) icon = shrink(icon);
                if (icon != null) keep.put(key, icon);
                e.icon = icon;
                e.brand = brand != null;
                e.intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(cat)
                        .setComponent(new ComponentName(pkg, ai.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                found.put(pkg, e);
                sig.add(pkg + "|" + ai.name + "|" + e.label + "|" + key);
            }
        }
        icons.clear();
        icons.putAll(keep);   // drop icons of apps that are gone
        Collections.sort(sig);
        Scan s = new Scan();
        s.apps = Collections.unmodifiableMap(found);
        s.signature = TextUtils.join("\n", sig);
        Log.d(TAG, "scanned " + found.size() + " apps in " + (System.currentTimeMillis() - t0) + " ms");
        return s;
    }

    private static final int ICON_MAX = 128;

    /** Render a big icon once into a bitmap of at most 128 px (aspect kept) and drop the original. */
    private Drawable shrink(Drawable d) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w > 0 && h > 0 && w <= ICON_MAX && h <= ICON_MAX) return d;
        try {
            if (w <= 0 || h <= 0) {
                w = h = ICON_MAX;
            } else {
                float k = ICON_MAX / (float) Math.max(w, h);
                w = Math.max(1, Math.round(w * k));
                h = Math.max(1, Math.round(h * k));
            }
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(b));
            return new BitmapDrawable(ctx.getResources(), b);
        } catch (Throwable e) {   // OutOfMemoryError included: keep the original
            Log.w(TAG, "couldn't shrink an icon: " + e);
            return d;
        }
    }

    /** Changes when the app is updated (new install path) or its icon resource changes. */
    private static String iconKey(ResolveInfo ri, Integer brand) {
        if (brand != null) return "brand:" + brand;
        ActivityInfo ai = ri.activityInfo;
        return ai.applicationInfo.sourceDir + "|" + ai.name + "|" + ri.getIconResource();
    }

    private Integer brandByName(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (l.equals("max")) return R.drawable.brand_hbomax;
        for (String[] b : BRAND_NAMES) {
            if (l.contains(b[0])) {
                int id = ctx.getResources().getIdentifier("brand_" + b[1], "drawable",
                        ctx.getPackageName());
                return id != 0 ? id : null;
            }
        }
        return null;
    }
}
