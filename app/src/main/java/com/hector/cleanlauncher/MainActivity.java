package com.hector.cleanlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements HomeView.Host {

    static final int INK = 0xFFF4F4F1;
    static final int MUTE = 0xCCF4F4F1;
    static final int SOLID = 0xFFF3F3EF;
    static final int SOLID_INK = 0xFF141618;

    static final String ADD = "__add";
    static final int SHOW_BOTH = 0, SHOW_ICONS = 1, SHOW_NAMES = 2;

    /** Intent extra from HomeWatcherService: a panel to open once we are in front. */
    static final String EXTRA_OPEN = "open";
    static final String OPEN_LIBRARY = "library", OPEN_SETTINGS = "settings",
            OPEN_WALLPAPERS = "wallpapers", OPEN_MENU = "menu:", OPEN_ADD = "add";

    /** Monochrome service marks, keyed by Fire TV package name. */
    static final Map<String, Integer> BRANDS = new HashMap<>();
    /** Default Home order: {package names..., name to match if none of those is installed}. */
    static final String[][] DEFAULT_DOCK = {
            {"com.amazon.firebat", "com.amazon.avod", "Prime Video"},
            {"com.wbd.stream", "com.hbo.hbonow", "Max"},
            {"com.netflix.ninja", "Netflix"},
            {"com.disney.disneyplus", "Disney+"},
            {"com.stremio.one", "Stremio"},
            {"TizenTube"},
            {"org.jellyfin.androidtv", "Jellyfin"},
    };

    /** Monochrome marks matched by app name, for apps without a fixed package. */
    static final String[][] BRAND_NAMES = {
            {"tizentube", "youtube"}, {"youtube", "youtube"}, {"jellyfin", "jellyfin"},
            {"prime video", "primevideo"}, {"hbo max", "hbomax"}, {"netflix", "netflix"},
            {"disney", "disneyplus"}, {"stremio", "stremio"},
    };

    static {
        BRANDS.put("com.netflix.ninja", R.drawable.brand_netflix);
        BRANDS.put("com.amazon.firetv.youtube", R.drawable.brand_youtube);
        BRANDS.put("com.google.android.youtube.tv", R.drawable.brand_youtube);
        BRANDS.put("com.amazon.avod", R.drawable.brand_primevideo);
        BRANDS.put("com.amazon.avod.thirdpartyclient", R.drawable.brand_primevideo);
        BRANDS.put("com.amazon.firebat", R.drawable.brand_primevideo);
        BRANDS.put("com.disney.disneyplus", R.drawable.brand_disneyplus);
        BRANDS.put("com.stremio.one", R.drawable.brand_stremio);
        BRANDS.put("com.spotify.tv.android", R.drawable.brand_spotify);
        BRANDS.put("com.tubitv", R.drawable.brand_tubi);
        BRANDS.put("com.wbd.stream", R.drawable.brand_hbomax);
        BRANDS.put("com.hbo.hbonow", R.drawable.brand_hbomax);
        BRANDS.put("com.apple.atve.amazon.appletv", R.drawable.brand_appletv);
        BRANDS.put("com.apple.atve.androidtv.appletv", R.drawable.brand_appletv);
        BRANDS.put("com.apple.android.music", R.drawable.brand_applemusic);
        BRANDS.put("org.jellyfin.androidtv", R.drawable.brand_jellyfin);
    }

    static final class AppEntry {
        String pkg, label;
        Intent intent;
        Drawable icon;
        boolean brand;
    }

    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private HomeView hv;

    private FrameLayout overlay;          // the open panel, if any
    private boolean overlayIsWallpaper;
    private GridLayout libraryGrid;
    /** The library was opened from "Add": selecting an app toggles it on Home instead of opening it. */
    private boolean pickingForHome;
    private TextView pickerName, pickerCredit;

    private boolean resumed;
    /** The shared app list changed: rebuild now if we're showing, else on the next resume. */
    private final Runnable appsChanged = () -> {
        if (resumed) syncApps();
    };

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        root = new FrameLayout(this);
        setContentView(root);
        hv = new HomeView(this, this);
        root.addView(hv, new FrameLayout.LayoutParams(-1, -1));
        hv.focusDock(true);

        AppCatalog.get(this).addListener(appsChanged);

        handleOpen(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        HomeWatcherService.onLauncherResumed();
        syncApps();
        hv.updateNetwork();
        // Pick up where the cover left off, then take it down.
        String focus = HomeWatcherService.coverFocus();
        if (focus != null && overlay == null) {
            hv.lastDockPkg = focus;
            hv.focusDock(false);
        }
        HomeWatcherService.hideCover();
        hv.startClock();
        if (overlay == null && getCurrentFocus() == null) hv.focusDock(false);
    }

    /** Coming back from behind the cover can leave nothing focused; pick the last Home item. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && overlay == null && getCurrentFocus() == null) hv.focusDock(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        hv.stopClock();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppCatalog.get(this).removeListener(appsChanged);
    }

    private void syncApps() {
        if (!hv.syncApps(true) || libraryGrid == null) return;
        View f = getCurrentFocus();
        fillLibrary(f != null && f.getTag() instanceof AppEntry ? ((AppEntry) f.getTag()).pkg : null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String learned = intent.getStringExtra("learned");
        if (learned != null) {
            openSettings();
            Toast.makeText(this, "Home screen detected: " + learned, Toast.LENGTH_LONG).show();
            return;
        }
        // Home was pressed: back to a clean Home (the cover's focus is applied in onResume).
        closeOverlay(false);
        hv.refresh();
        hv.focusDock(true);
        handleOpen(intent);
    }

    /** Open a panel the cover asked for while Fire OS was holding us back. */
    private void handleOpen(Intent intent) {
        String open = intent != null ? intent.getStringExtra(EXTRA_OPEN) : null;
        if (open == null) return;
        intent.removeExtra(EXTRA_OPEN);
        if (OPEN_LIBRARY.equals(open)) openLibrary();
        else if (OPEN_ADD.equals(open)) {
            openLibrary();
            pickingForHome = true;
        }
        else if (OPEN_SETTINGS.equals(open)) openSettings();
        else if (OPEN_WALLPAPERS.equals(open)) openWallpapers();
        else if (open.startsWith(OPEN_MENU)) {
            AppEntry a = hv.apps.get(open.substring(OPEN_MENU.length()));
            if (a != null) {
                hv.lastDockPkg = a.pkg;
                hv.focusDock(false);
                showMenu(a, true);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) closeOverlay(true);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof AppEntry) {
                showMenu((AppEntry) f.getTag(), f.getParent() == hv.homeItems());
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------ HomeView.Host

    @Override
    public void launch(AppEntry a) {
        if (ADD.equals(a.pkg)) {
            openLibrary();
            pickingForHome = true;
            return;
        }
        if (pickingForHome && libraryGrid != null) {
            // Opened from "Add": selecting an app adds it to (or removes it from) Home.
            hv.toggleHome(a.pkg);
            fillLibrary(a.pkg);
            return;
        }
        try {
            startActivity(a.intent);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void showMenu(final AppEntry a, final boolean inDock) {
        if (ADD.equals(a.pkg)) return;
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final boolean onHome = hv.dockPkgs.contains(a.pkg);
        if (inDock) {
            int i = hv.dockPkgs.indexOf(a.pkg);
            if (i > 0) {
                labels.add(hv.homeAsList() ? "Move up" : "Move left");
                actions.add(() -> hv.moveInDock(a.pkg, -1));
            }
            if (i >= 0 && i < hv.dockPkgs.size() - 1) {
                labels.add(hv.homeAsList() ? "Move down" : "Move right");
                actions.add(() -> hv.moveInDock(a.pkg, 1));
            }
        }
        labels.add(onHome ? "Remove from Home" : "Add to Home");
        actions.add(() -> {
            hv.toggleHome(a.pkg);
            if (inDock) hv.focusDock(true);
            else if (libraryGrid != null) fillLibrary(a.pkg);
        });
        labels.add("App info");
        actions.add(() -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        });
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
                .show();
    }

    // ------------------------------------------------------------- overlays

    private FrameLayout openOverlay(boolean fullGlass) {
        closeOverlay(false);
        overlay = new FrameLayout(this);
        if (fullGlass) {
            GlassView g = new GlassView(this, 0, false);
            g.setVeil(0x8C080E12, 0x99080E12);
            overlay.addView(g, new FrameLayout.LayoutParams(-1, -1));
        }
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        hv.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(140).start();
        return overlay;
    }

    private void closeOverlay(boolean refocus) {
        if (overlay == null) return;
        if (pendingPreview != null) handler.removeCallbacks(pendingPreview);
        if (overlayIsWallpaper) hv.applyWallpaper(hv.currentWall());
        root.removeView(overlay);
        overlay = null;
        overlayIsWallpaper = false;
        libraryGrid = null;
        pickingForHome = false;
        hv.applyHomeLayout();
        hv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (refocus) hv.focusDock(false);
    }

    private TextView title(FrameLayout parent, String s) {
        TextView t = text(s, 24, true, INK);
        t.setLetterSpacing(-0.03f);
        t.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, dp(32));
        lp.leftMargin = dp(56);
        lp.topMargin = dp(36);
        parent.addView(t, lp);
        return t;
    }

    // App library ------------------------------------------------------------

    @Override
    public void openLibrary() {
        FrameLayout o = openOverlay(true);
        pickingForHome = false;
        title(o, "Apps");
        TextView hint = text("Hold select or press ☰ to add to Home", 14, false, MUTE);
        hint.setTag("hint");
        hint.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, dp(32), Gravity.TOP | Gravity.END);
        hp.rightMargin = dp(56);
        hp.topMargin = dp(36);
        o.addView(hint, hp);

        // No padding on the scroll view itself, so rows fade at its edges instead of being cut
        // at a padding line. In list mode HomeView.listRow() keeps the focused row centered,
        // so the default scroll-into-view is switched off.
        final boolean asList = libraryAsList();
        ScrollView sv = !asList ? new ScrollView(this) : new ScrollView(this) {
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;
            }
        };
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(dp(asList ? 72 : 32));
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        libraryGrid = new GridLayout(this);
        libraryGrid.setColumnCount(asList ? 1 : 6);
        libraryGrid.setPadding(0, dp(8), 0, dp(asList ? 48 : 32));
        sv.addView(libraryGrid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        lp.topMargin = dp(84);
        o.addView(sv, lp);
        fillLibrary(null);
    }

    private boolean libraryAsList() {
        return prefs.getBoolean("libList", false);
    }

    private void fillLibrary(String focusPkg) {
        if (libraryGrid == null) return;
        libraryGrid.removeAllViews();
        boolean asList = libraryAsList();
        for (AppEntry a : hv.sortedApps()) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(asList ? 640 : 136);
            lp.height = dp(asList ? 56 : 100);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            libraryGrid.addView(asList ? hv.listRow(a) : hv.tile(a, false), lp);
        }
        View t = focusPkg != null ? hv.findTile(libraryGrid, focusPkg) : null;
        if (t == null && libraryGrid.getChildCount() > 0) t = libraryGrid.getChildAt(0);
        if (t != null) t.requestFocus();
    }

    // Settings ---------------------------------------------------------------

    @Override
    public void openSettings() {
        FrameLayout o = openOverlay(true);
        title(o, "Settings");
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setPadding(0, 0, 0, dp(24));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list, new FrameLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(520), -1);
        lp.leftMargin = dp(48);
        lp.topMargin = dp(92);
        lp.bottomMargin = dp(64);
        o.addView(sv, lp);

        list.addView(settingRow("Home layout", hv.homeAsList() ? "List" : "Dock", v -> {
            boolean asList = !hv.homeAsList();
            prefs.edit().putBoolean("homeList", asList).apply();
            ((TextView) v.findViewWithTag("value")).setText(asList ? "List" : "Dock");
            hv.applyHomeLayout();
        }));
        final String[] showNames = {"Icons and names", "Icons", "Names"};
        list.addView(settingRow("Home shows", showNames[prefs.getInt("show", SHOW_BOTH)], v -> {
            int next = (prefs.getInt("show", SHOW_BOTH) + 1) % 3;
            prefs.edit().putInt("show", next).apply();
            ((TextView) v.findViewWithTag("value")).setText(showNames[next]);
            hv.buildDock();
        }));
        list.addView(settingRow("Clock", prefs.getBoolean("clock24", false) ? "24-hour" : "12-hour", v -> {
            boolean c24 = !prefs.getBoolean("clock24", false);
            prefs.edit().putBoolean("clock24", c24).apply();
            ((TextView) v.findViewWithTag("value")).setText(c24 ? "24-hour" : "12-hour");
            hv.updateClock();
        }));
        final String[] darkModes = {HomeView.DARK_OFF, HomeView.DARK_ON, HomeView.DARK_NIGHT};
        final String[] darkNames = {"Off", "On", "At night (7 PM–7 AM)"};
        list.addView(settingRow("Dark mode", darkNames[darkIndex(darkModes)], v -> {
            int next = (darkIndex(darkModes) + 1) % darkModes.length;
            prefs.edit().putString(HomeView.PREF_DARK, darkModes[next]).apply();
            ((TextView) v.findViewWithTag("value")).setText(darkNames[next]);
            hv.applyTheme();
        }));
        list.addView(settingRow("Wi-Fi network", netSettingValue(), v -> {
            boolean show = !prefs.getBoolean(HomeView.PREF_SHOW_NET, true);
            prefs.edit().putBoolean(HomeView.PREF_SHOW_NET, show).apply();
            if (show && !canReadWifiName()) askForWifiName();
            ((TextView) v.findViewWithTag("value")).setText(netSettingValue());
            hv.updateNetwork();
        }));
        list.addView(settingRow("Apps layout", libraryAsList() ? "List" : "Grid", v -> {
            boolean asList = !libraryAsList();
            prefs.edit().putBoolean("libList", asList).apply();
            ((TextView) v.findViewWithTag("value")).setText(asList ? "List" : "Grid");
        }));
        list.addView(settingRow("Wallpaper", hv.currentWall() != null ? hv.currentWall().name : "",
                v -> openWallpapers()));

        final String[] modes = {HomeWatcherService.MODE_CLEAN, HomeWatcherService.MODE_TWICE,
                HomeWatcherService.MODE_AMAZON};
        final String[] modeNames = {"Opens Clean Launcher", "Press twice for Amazon", "Opens Amazon home"};
        list.addView(settingRow("Home button", modeNames[modeIndex(modes)], v -> {
            int next = (modeIndex(modes) + 1) % modes.length;
            prefs.edit().putString("homeMode", modes[next]).apply();
            ((TextView) v.findViewWithTag("value")).setText(modeNames[next]);
            if (HomeWatcherService.MODE_TWICE.equals(modes[next]) && !HomeKeyWatcher.permitted(this)) {
                Toast.makeText(this, "Press twice needs Home key detection (see below)",
                        Toast.LENGTH_LONG).show();
            }
        }));
        list.addView(settingRow("Open Amazon home", "", v -> openAmazonHome()));
        final boolean on = watcherEnabled();
        list.addView(settingRow("Home button service", on ? "On" : "Off", v -> showServiceHelp(on)));
        final boolean allowed = HomeKeyWatcher.permitted(this);
        list.addView(settingRow("Home key detection",
                allowed && HomeWatcherService.keyFast(this) ? "Fast" : "Basic", v -> {
            if (!allowed) {
                showKeyHelp(false);
                return;
            }
            boolean fast = !HomeWatcherService.keyFast(this);
            prefs.edit().putBoolean("keyFast", fast).apply();
            ((TextView) v.findViewWithTag("value")).setText(fast ? "Fast" : "Basic");
            HomeWatcherService.refreshKeyWatch();
        }));
        String homePkg = prefs.getString("homePkg", null);
        list.addView(settingRow("Detect Home screen", homePkg != null ? homePkg : "Automatic", v -> {
            if (!watcherEnabled()) {
                showServiceHelp(false);
                return;
            }
            prefs.edit().putLong("learnUntil", System.currentTimeMillis() + 15000).apply();
            Toast.makeText(this, "Press Home on the remote now", Toast.LENGTH_LONG).show();
        }));
        list.addView(settingRow("Edit Home apps", "", v -> {
            openLibrary();
            pickingForHome = true;
        }));
        list.addView(settingRow("Fire TV settings", "", v -> {
            try {
                HomeWatcherService.leaveForAmazon();
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }));

        Wallpapers.Item w = hv.currentWall();
        TextView credit = text(w != null ? "Photo by " + w.credit + " on Unsplash" : "", 13, false, MUTE);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        cp.leftMargin = dp(56);
        cp.bottomMargin = dp(28);
        o.addView(credit, cp);

        list.getChildAt(0).requestFocus();
    }

    private int darkIndex(String[] modes) {
        String m = prefs.getString(HomeView.PREF_DARK, HomeView.DARK_OFF);
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(m)) return i;
        return 0;
    }

    private String netSettingValue() {
        return prefs.getBoolean(HomeView.PREF_SHOW_NET, true) ? "Shown" : "Hidden";
    }

    /** Android 9 only reveals the Wi-Fi name to apps holding the location permission. */
    private boolean canReadWifiName() {
        return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void askForWifiName() {
        try {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } catch (Exception ignored) { }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        hv.updateNetwork();
    }

    /** Show Amazon's home and stay there until Home is pressed again or an app is opened. */
    private void openAmazonHome() {
        if (!HomeWatcherService.openAmazonHome(this)) {
            Toast.makeText(this, "Couldn't open Amazon's home screen", Toast.LENGTH_SHORT).show();
        }
    }

    private int modeIndex(String[] modes) {
        String m = prefs.getString("homeMode", HomeWatcherService.MODE_CLEAN);
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(m)) return i;
        return 0;
    }

    private boolean watcherEnabled() {
        String s = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private void showServiceHelp(boolean on) {
        String msg = on
                ? "The service is on. If Home still opens Amazon's screen, use Detect Home screen, "
                + "then press Home once."
                : "Fire OS has turned the service off (this can happen after an update or reboot). "
                + "Run install.sh again from your computer, or:\n\n"
                + "adb shell settings put secure enabled_accessibility_services "
                + getPackageName() + "/.HomeWatcherService\n"
                + "adb shell settings put secure accessibility_enabled 1";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Home button service")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showKeyHelp(boolean fast) {
        String msg = fast
                ? "Clean Launcher reads Home presses from the system log, so it appears before "
                + "Amazon's home draws, catches Home pressed on Amazon's home, and can tell a "
                + "double press. Only activity-start lines are read; nothing is stored."
                : "Basic: Clean Launcher only notices Amazon's home once it is on screen, so it "
                + "may flash briefly, and pressing Home while Amazon's home is showing is missed.\n\n"
                + "For fast detection, run install.sh again, or:\n\n"
                + "adb shell pm grant " + getPackageName() + " android.permission.READ_LOGS\n\n"
                + "then restart the stick.";
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Home key detection")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show();
    }

    private View settingRow(String label, String value, View.OnClickListener click) {
        final LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), 0, dp(16), 0);
        r.setFocusable(true);
        r.setClickable(true);
        r.setBackground(hv.pill(SOLID));
        final TextView l = text(label, 16, true, INK);
        final TextView v = text(value, 16, false, MUTE);
        v.setTag("value");
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        r.setOnFocusChangeListener((view, f) -> {
            l.setTextColor(f ? SOLID_INK : INK);
            v.setTextColor(f ? 0xB3141618 : MUTE);
        });
        r.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
        lp.bottomMargin = dp(4);
        r.setLayoutParams(lp);
        return r;
    }

    // Wallpaper picker -------------------------------------------------------

    @Override
    public void openWallpapers() {
        FrameLayout o = openOverlay(false);
        overlayIsWallpaper = true;
        hv.hideItems();

        GlassView panel = new GlassView(this, dp(24), true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(752), dp(150),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(64);
        o.addView(panel, lp);

        pickerName = text("", 17, true, INK);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2);
        np.leftMargin = dp(22);
        np.topMargin = dp(14);
        panel.addView(pickerName, np);
        pickerCredit = text("", 13, false, MUTE);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.rightMargin = dp(22);
        cp.topMargin = dp(17);
        panel.addView(pickerCredit, cp);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setHorizontalFadingEdgeEnabled(true);
        hs.setFadingEdgeLength(dp(40));
        hs.setClipToPadding(false);
        hs.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(strip, new FrameLayout.LayoutParams(-2, -1));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, dp(104), Gravity.BOTTOM);
        hp.bottomMargin = dp(4);
        panel.addView(hs, hp);

        View current = null;
        Wallpapers.Item saved = hv.currentWall();
        for (final Wallpapers.Item w : hv.walls) {
            View th = thumb(w);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(136), dp(77));
            tp.leftMargin = tp.rightMargin = dp(6);
            strip.addView(th, tp);
            if (saved != null && saved.id.equals(w.id)) current = th;
        }
        if (current == null && strip.getChildCount() > 0) current = strip.getChildAt(0);
        if (current != null) current.requestFocus();
    }

    private View thumb(final Wallpapers.Item w) {
        final FrameLayout f = new FrameLayout(this);
        f.setFocusable(true);
        f.setClickable(true);
        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageBitmap(hv.decode("wallpapers/" + w.id + "-thumb.jpg", 2));
        img.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        img.setClipToOutline(true);
        f.addView(img, new FrameLayout.LayoutParams(-1, -1));

        StateListDrawable ring = new StateListDrawable();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setStroke(dp(2), SOLID);
        ring.addState(new int[]{android.R.attr.state_focused}, g);
        ring.addState(new int[]{}, new ColorDrawable(0));
        f.setForeground(ring);

        final Runnable preview = () -> hv.applyWallpaper(w);
        f.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.06f : 1f).scaleY(focused ? 1.06f : 1f).setDuration(140).start();
            if (focused) {
                pickerName.setText(w.name);
                pickerCredit.setText("Photo by " + w.credit + " on Unsplash");
                handler.removeCallbacks(pendingPreview);
                pendingPreview = preview;
                handler.postDelayed(preview, 180);
            }
        });
        f.setOnClickListener(v -> {
            prefs.edit().putString("wall", w.id).apply();
            overlayIsWallpaper = false;   // keep it, don't revert
            hv.applyWallpaper(w);
            closeOverlay(true);
        });
        return f;
    }

    private Runnable pendingPreview;

    // ---------------------------------------------------------------- misc

    private TextView text(String s, float sp, boolean medium, int color) {
        return hv.text(s, sp, medium, color);
    }

    private int dp(float v) {
        return hv.dp(v);
    }
}
