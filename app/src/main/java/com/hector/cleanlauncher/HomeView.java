package com.hector.cleanlauncher;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.hector.cleanlauncher.MainActivity.ADD;
import static com.hector.cleanlauncher.MainActivity.AppEntry;
import static com.hector.cleanlauncher.MainActivity.DEFAULT_DOCK;
import static com.hector.cleanlauncher.MainActivity.INK;
import static com.hector.cleanlauncher.MainActivity.MUTE;
import static com.hector.cleanlauncher.MainActivity.SHOW_BOTH;
import static com.hector.cleanlauncher.MainActivity.SHOW_ICONS;
import static com.hector.cleanlauncher.MainActivity.SHOW_NAMES;
import static com.hector.cleanlauncher.MainActivity.SOLID;
import static com.hector.cleanlauncher.MainActivity.SOLID_INK;

/**
 * The Home screen itself: wallpaper, clock, and the dock or list of Home apps. Built in code
 * so both MainActivity and HomeWatcherService (as a cover over Amazon's home while Fire OS
 * holds back our activity) can show the same screen.
 */
final class HomeView extends FrameLayout {

    /** What the screen asks its owner to do. */
    interface Host {
        void launch(AppEntry a);
        void openLibrary();
        void openSettings();
        void openWallpapers();
        void openTasks();
        void showMenu(AppEntry a, boolean inDock);
    }

    private static Typeface fReg, fMed;

    final SharedPreferences prefs;
    private final Host host;
    private final AppCatalog catalog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** What the Home items were last built from; refresh() skips the rebuild if unchanged. */
    private String builtFor;
    private int appsVersion = -1;

    private final ImageView wall;
    private final View dim;
    private Boolean darkApplied;
    private final TextView clock;
    private final LinearLayout net;
    private final ImageView netIcon;
    private final TextView netText;
    private FrameLayout launching;
    private boolean netRegistered;
    private final BroadcastReceiver netChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            updateNetwork();
        }
    };
    private final GlassView dock;
    private final HorizontalScrollView shelf;
    private final LinearLayout row, utils, listCol, listUtils;
    private final ScrollView listScroll;

    final Map<String, AppEntry> apps = new HashMap<>();
    final List<String> dockPkgs = new ArrayList<>();
    final List<Wallpapers.Item> walls;
    String lastDockPkg;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            applyTheme();   // "At night" switches on its own
            handler.postDelayed(this, 5000);
        }
    };

    HomeView(Context c, Host host) {
        this(c, host, false);
    }

    /** asyncWall: never decode the wallpaper on the calling thread (the cover). */
    HomeView(Context c, Host host, boolean asyncWall) {
        super(c);
        this.asyncWall = asyncWall;
        this.host = host;
        prefs = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        catalog = AppCatalog.get(c);
        if (fReg == null) {
            fReg = Typeface.createFromAsset(c.getAssets(), "fonts/Manrope-Regular.ttf");
            fMed = Typeface.createFromAsset(c.getAssets(), "fonts/Manrope-Medium.ttf");
        }
        walls = Wallpapers.load(c);
        setBackgroundColor(0xFF1D2A30);

        wall = new ImageView(c);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(wall, new LayoutParams(-1, -1));

        // Dark mode: a black veil over the photo (the glass darkens too, see GlassView.dark).
        dim = new View(c);
        dim.setBackgroundColor(0xFF000000);
        dim.setVisibility(View.GONE);
        addView(dim, new LayoutParams(-1, -1));

        View topShade = new View(c);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x40000000, 0x00000000}));
        addView(topShade, new LayoutParams(-1, dp(150)));

        clock = text("", 24, true, INK);
        clock.setLetterSpacing(-0.03f);
        clock.setGravity(Gravity.CENTER_VERTICAL);
        clock.setShadowLayer(dp(1.5f), 0, dp(0.5f), 0x38000000);
        LayoutParams lp = new LayoutParams(-2, dp(32));
        lp.leftMargin = dp(56);
        lp.topMargin = dp(36);
        addView(clock, lp);

        // Network name under the clock (follows the clock to the right in the list layout).
        net = new LinearLayout(c);
        net.setGravity(Gravity.CENTER_VERTICAL);
        netIcon = new ImageView(c);
        netIcon.setAlpha(0.8f);
        net.addView(netIcon, new LinearLayout.LayoutParams(dp(15), dp(15)));
        netText = text("", 14, false, MUTE);
        netText.setSingleLine();
        netText.setEllipsize(TextUtils.TruncateAt.END);
        netText.setMaxWidth(dp(260));
        netText.setShadowLayer(dp(1.5f), 0, dp(0.5f), 0x38000000);
        LinearLayout.LayoutParams ntp = new LinearLayout.LayoutParams(-2, -2);
        ntp.setMarginStart(dp(6));
        net.addView(netText, ntp);
        lp = new LayoutParams(-2, dp(20));
        lp.leftMargin = dp(57);
        lp.topMargin = dp(72);
        addView(net, lp);

        utils = new LinearLayout(c);
        utils.setGravity(Gravity.CENTER_VERTICAL);
        utils.addView(util(R.drawable.ic_wallpaper, "Wallpaper", v -> host.openWallpapers()));
        utils.addView(util(R.drawable.ic_apps, "Apps", v -> host.openLibrary()));
        utils.addView(util(R.drawable.ic_memory, "Tasks", v -> host.openTasks()));
        utils.addView(util(R.drawable.ic_settings, "Settings", v -> host.openSettings()));
        lp = new LayoutParams(-2, dp(32), Gravity.TOP | Gravity.END);
        lp.rightMargin = dp(52);
        lp.topMargin = dp(36);
        addView(utils, lp);

        dock = new GlassView(c, dp(24), true);
        lp = new LayoutParams(dp(752), dp(116), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(86);
        addView(dock, lp);

        shelf = new HorizontalScrollView(c);
        shelf.setHorizontalScrollBarEnabled(false);
        shelf.setHorizontalFadingEdgeEnabled(true);
        shelf.setFadingEdgeLength(dp(56));
        shelf.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LayoutParams sp = new LayoutParams(-1, -1);
        sp.leftMargin = sp.rightMargin = dp(8);
        dock.addView(shelf, sp);

        row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        shelf.addView(row, new LayoutParams(-2, -1));

        // The scroll view spans the full height with no padding of its own, so rows fade out
        // at the screen edge instead of being cut at an inner padding line. Scrolling is done
        // by centerInList() below, so the default scroll-into-view is switched off.
        listScroll = new ScrollView(c) {
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
                return 0;
            }
        };
        listScroll.setVerticalScrollBarEnabled(false);
        listScroll.setVerticalFadingEdgeEnabled(true);
        listScroll.setFadingEdgeLength(dp(96));
        listScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listCol = new LinearLayout(c);
        listCol.setOrientation(LinearLayout.VERTICAL);
        listCol.setPadding(0, dp(56), 0, dp(56));
        listScroll.addView(listCol, new LayoutParams(-1, -2));
        lp = new LayoutParams(dp(600), -1, Gravity.START);
        lp.leftMargin = dp(40);
        addView(listScroll, lp);

        listUtils = new LinearLayout(c);
        listUtils.setOrientation(LinearLayout.VERTICAL);
        listUtils.setGravity(Gravity.END);
        listUtils.addView(listUtil(R.drawable.ic_apps, "All apps", v -> host.openLibrary()));
        listUtils.addView(listUtil(R.drawable.ic_memory, "Tasks", v -> host.openTasks()));
        listUtils.addView(listUtil(R.drawable.ic_settings, "Settings", v -> host.openSettings()));
        lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = dp(48);
        lp.bottomMargin = dp(44);
        addView(listUtils, lp);

        applyTheme();
        if (!asyncWall) applyWallpaper(currentWall());   // the cover gets its wallpaper in refresh()
        reloadApps();
        loadDockPrefs();
        buildDock();
        applyHomeLayout();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!netRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            getContext().registerReceiver(netChanged, f);
            netRegistered = true;
        }
        updateNetwork();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (netRegistered) {
            try { getContext().unregisterReceiver(netChanged); } catch (Exception ignored) { }
            netRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    /** ☰ on a Home item opens its menu (the app library handles its own in MainActivity). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (ev.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                View f = findFocus();
                if (f != null && f.getTag() instanceof AppEntry) host.showMenu((AppEntry) f.getTag(), true);
            }
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    /** Re-read everything the activity may have changed: dock, layout, wallpaper, clock. */
    void refresh() {
        hideLaunching();
        loadDockPrefs();
        syncApps(false);
        if (!homeKey().equals(builtFor)) buildDock();
        applyHomeLayout();
        applyTheme();
        applyWallpaper(currentWall());
        updateClock();
        updateNetwork();
        resetScroll();
    }

    /** Pick up a changed app list; rebuild the Home items only if something we show changed. */
    boolean syncApps(boolean rebuild) {
        if (appsVersion == catalog.version()) return false;
        reloadApps();
        if (rebuild) buildDock();
        return true;
    }

    /** Everything the Home items depend on. */
    private String homeKey() {
        return appsVersion + "|" + TextUtils.join(",", dockPkgs) + "|" + prefs.getInt("show", SHOW_BOTH);
    }

    /** Feedback while Fire OS holds an app start back (up to ~5 s after Home): "Opening X…". */
    void showLaunching(String label) {
        hideLaunching();
        launching = new FrameLayout(getContext());
        launching.setBackgroundColor(0x99060A0C);
        TextView t = text("Opening " + label + "…", 22, true, INK);
        t.setShadowLayer(dp(2), 0, dp(1), 0x59000000);
        launching.addView(t, new LayoutParams(-2, -2, Gravity.CENTER));
        addView(launching, new LayoutParams(-1, -1));
        launching.setAlpha(0f);
        launching.animate().alpha(1f).setDuration(120).start();
    }

    void hideLaunching() {
        if (launching == null) return;
        removeView(launching);
        launching = null;
    }

    void startClock() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void stopClock() {
        handler.removeCallbacks(tick);
    }

    void resetScroll() {
        shelf.scrollTo(0, 0);
        listScroll.scrollTo(0, 0);
    }

    /** The package of the focused Home item, if any. */
    String focusedPkg() {
        View f = findFocus();
        if (f != null && f.getTag() instanceof AppEntry) return ((AppEntry) f.getTag()).pkg;
        return null;
    }

    /** Hide the Home items, leaving the wallpaper and clock (used by the wallpaper picker). */
    void hideItems() {
        dock.setVisibility(View.INVISIBLE);
        listScroll.setVisibility(View.INVISIBLE);
        listUtils.setVisibility(View.INVISIBLE);
    }

    // ----------------------------------------------------------------- apps

    /** Copy the shared app list (AppCatalog scans; this only takes a snapshot). */
    void reloadApps() {
        appsVersion = catalog.version();
        apps.clear();
        apps.putAll(catalog.apps());
    }

    /**
     * A drawable of its own for each view: the catalog's icons are shared by the activity and
     * the cover, and one Drawable can't be in two views at once (bounds and callbacks clash).
     */
    Drawable iconFor(AppEntry a) {
        Drawable d = a.icon;
        if (d == null) return null;
        Drawable.ConstantState s = d.getConstantState();
        return s != null ? s.newDrawable(getResources()) : d;
    }

    List<AppEntry> sortedApps() {
        List<AppEntry> list = new ArrayList<>(apps.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    private void loadDockPrefs() {
        dockPkgs.clear();
        String saved = prefs.getString("dock_v2", null);
        if (saved != null) {
            if (!saved.isEmpty()) dockPkgs.addAll(Arrays.asList(saved.split(",")));
            return;
        }
        for (String[] want : DEFAULT_DOCK) {
            String p = findApp(want);
            if (p != null && !dockPkgs.contains(p)) dockPkgs.add(p);
        }
        if (dockPkgs.size() < 4) {
            for (AppEntry a : sortedApps()) {
                if (dockPkgs.size() >= 6) break;
                if (!dockPkgs.contains(a.pkg)) dockPkgs.add(a.pkg);
            }
        }
        saveDock();
    }

    /** Each entry: package names, the last one also tried as an app name. */
    private String findApp(String[] want) {
        for (String w : want) if (apps.containsKey(w)) return w;
        String name = want[want.length - 1].toLowerCase(Locale.ROOT);
        for (AppEntry a : sortedApps()) {
            String l = a.label.toLowerCase(Locale.ROOT);
            if (l.equals(name)) return a.pkg;
        }
        for (AppEntry a : sortedApps()) {
            if (name.length() > 3 && a.label.toLowerCase(Locale.ROOT).contains(name)) return a.pkg;
        }
        return null;
    }

    private void saveDock() {
        prefs.edit().putString("dock_v2", TextUtils.join(",", dockPkgs)).apply();
    }

    // ----------------------------------------------------------------- dock

    void buildDock() {
        // Fire OS sends package changes every few seconds (e.g. com.amazon.ceviche); keep focus
        // on the same app across the rebuild instead of losing it.
        View had = findFocus();
        String keep = had != null && had.getTag() instanceof AppEntry
                && had.getParent() == homeItems() ? ((AppEntry) had.getTag()).pkg : null;

        row.removeAllViews();
        for (String pkg : dockPkgs) {
            AppEntry a = apps.get(pkg);
            if (a == null) continue;
            row.addView(tile(a, true), new LinearLayout.LayoutParams(dp(144), dp(100)));
        }
        AppEntry add = new AppEntry();
        add.pkg = ADD;
        add.label = row.getChildCount() == 0 ? "Add apps" : "Add";
        add.icon = getContext().getDrawable(R.drawable.ic_add);
        add.brand = true;
        row.addView(tile(add, true), new LinearLayout.LayoutParams(dp(144), dp(100)));

        listCol.removeAllViews();
        for (String pkg : dockPkgs) {
            AppEntry a = apps.get(pkg);
            if (a == null) continue;
            listCol.addView(homeRow(a), new LinearLayout.LayoutParams(-2, dp(68)));
        }
        listCol.addView(homeRow(add), new LinearLayout.LayoutParams(-2, dp(68)));
        builtFor = homeKey();

        if (keep != null) {
            View t = findTile(homeItems(), keep);
            if (t != null) t.requestFocus();
            else focusDock(true);
        }
    }

    boolean homeAsList() {
        return prefs.getBoolean("homeList", false);
    }

    /** The container holding the focusable Home items in the current layout. */
    LinearLayout homeItems() {
        return homeAsList() ? listCol : row;
    }

    /** Show the dock + top pills, or the left-hand list + bottom-right links. */
    void applyHomeLayout() {
        boolean l = homeAsList();
        dock.setVisibility(l ? View.GONE : View.VISIBLE);
        utils.setVisibility(l ? View.GONE : View.VISIBLE);
        listScroll.setVisibility(l ? View.VISIBLE : View.GONE);
        listUtils.setVisibility(l ? View.VISIBLE : View.GONE);
        LayoutParams cp = (LayoutParams) clock.getLayoutParams();
        cp.gravity = Gravity.TOP | (l ? Gravity.END : Gravity.START);
        cp.leftMargin = l ? 0 : dp(56);
        cp.rightMargin = l ? dp(56) : 0;
        clock.setLayoutParams(cp);
        LayoutParams np = (LayoutParams) net.getLayoutParams();
        np.gravity = cp.gravity;
        np.leftMargin = l ? 0 : dp(57);
        np.rightMargin = l ? dp(56) : 0;
        net.setLayoutParams(np);
        // Icon after the name when right-aligned, so the name lines up under the clock.
        net.setLayoutDirection(l ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        netText.setTextDirection(View.TEXT_DIRECTION_LTR);
    }

    void focusDock(boolean first) {
        LinearLayout items = homeItems();
        if (items.getChildCount() == 0) return;
        View target = items.getChildAt(0);
        if (!first && lastDockPkg != null) {
            View t = findTile(items, lastDockPkg);
            if (t != null) target = t;
        }
        target.requestFocus();
    }

    View findTile(ViewGroup parent, String pkg) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View v = parent.getChildAt(i);
            if (v.getTag() instanceof AppEntry && ((AppEntry) v.getTag()).pkg.equals(pkg)) return v;
        }
        return null;
    }

    void moveInDock(String pkg, int delta) {
        int i = dockPkgs.indexOf(pkg), j = i + delta;
        if (i < 0 || j < 0 || j >= dockPkgs.size()) return;
        Collections.swap(dockPkgs, i, j);
        saveDock();
        buildDock();
        View t = findTile(homeItems(), pkg);
        if (t != null) t.requestFocus();
    }

    void toggleHome(String pkg) {
        if (dockPkgs.contains(pkg)) dockPkgs.remove(pkg);
        else dockPkgs.add(pkg);
        saveDock();
        buildDock();
    }

    // ---------------------------------------------------------------- tiles

    View tile(final AppEntry a, final boolean inDock) {
        final FrameLayout t = new FrameLayout(getContext());
        t.setFocusable(true);
        t.setClickable(true);
        t.setTag(a);
        t.setBackground(plate());

        int mode = prefs.getInt("show", SHOW_BOTH);
        boolean showIcon = a.icon != null && mode != SHOW_NAMES;
        boolean showName = mode != SHOW_ICONS || a.icon == null;

        final ImageView ic = new ImageView(getContext());
        final TextView lb = text(a.label, mode == SHOW_NAMES ? 20 : 17, true, INK);
        lb.setSingleLine();
        lb.setEllipsize(TextUtils.TruncateAt.END);
        lb.setGravity(Gravity.CENTER);
        lb.setLetterSpacing(-0.02f);

        if (showIcon) {
            int size = showName ? 34 : 44;
            ic.setImageDrawable(iconFor(a));
            ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LayoutParams ip = new LayoutParams(
                    dp(a.brand ? size * 1.7f : size), dp(size),
                    Gravity.CENTER_HORIZONTAL | (showName ? Gravity.TOP : Gravity.CENTER_VERTICAL));
            if (showName) ip.topMargin = dp(18);
            t.addView(ic, ip);
        }
        if (showName) {
            LayoutParams llp = new LayoutParams(-1, -2,
                    showIcon ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL);
            if (showIcon) llp.bottomMargin = dp(15);
            llp.leftMargin = llp.rightMargin = dp(6);
            t.addView(lb, llp);
        }
        if (!inDock && dockPkgs.contains(a.pkg)) {
            View dot = new View(getContext());
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(INK);
            dot.setBackground(g);
            LayoutParams dlp = new LayoutParams(dp(6), dp(6), Gravity.TOP | Gravity.END);
            dlp.topMargin = dlp.rightMargin = dp(10);
            t.addView(dot, dlp);
        }

        ic.setAlpha(0.84f);
        lb.setAlpha(0.84f);
        t.setOnFocusChangeListener((v, focused) -> {
            float al = focused ? 1f : 0.84f;
            ic.animate().alpha(al).scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                    .translationY(focused ? -dp(1.5f) : 0).setDuration(160).start();
            lb.animate().alpha(al).setDuration(160).start();
            if (focused && inDock) lastDockPkg = a.pkg;
        });
        t.setOnClickListener(v -> host.launch(a));
        t.setOnLongClickListener(v -> {
            host.showMenu(a, inDock);
            return true;
        });
        return t;
    }

    /** Home "List" layout row: small icon, large name, left aligned. */
    private View homeRow(final AppEntry a) {
        final LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), 0, dp(24), 0);
        r.setFocusable(true);
        r.setClickable(true);
        r.setTag(a);
        r.setBackground(plate());

        FrameLayout slot = new FrameLayout(getContext());
        final ImageView ic = new ImageView(getContext());
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ic.setImageDrawable(iconFor(a));
        slot.addView(ic, new LayoutParams(dp(a.brand ? 44 : 32), dp(32), Gravity.CENTER));
        r.addView(slot, new LinearLayout.LayoutParams(dp(56), dp(40)));

        final TextView lb = text(a.label, 32, true, INK);
        lb.setSingleLine();
        lb.setEllipsize(TextUtils.TruncateAt.END);
        lb.setLetterSpacing(-0.03f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.leftMargin = dp(10);
        r.addView(lb, llp);

        // Dim the label through its colours, not View.setAlpha: a TextView with a shadow and
        // alpha < 1 is drawn through an offscreen layer every frame, one per row.
        ic.setAlpha(0.78f);
        setTextAlpha(lb, 0.78f);
        r.setOnFocusChangeListener((v, focused) -> {
            float al = focused ? 1f : 0.78f;
            ic.animate().alpha(al).setDuration(160).start();
            fadeText(lb, al);
            if (focused) {
                lastDockPkg = a.pkg;
                centerInList(r);
            }
        });
        // Move focus ourselves: the ScrollView's own arrow handling scrolls instead of moving
        // focus while the centering animation is running, which skips or drops rows.
        r.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            int i = listCol.indexOfChild(v);
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (i < listCol.getChildCount() - 1) listCol.getChildAt(i + 1).requestFocus();
                else listUtils.getChildAt(0).requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (i > 0) listCol.getChildAt(i - 1).requestFocus();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                listUtils.getChildAt(0).requestFocus();
                return true;
            }
            return false;
        });
        r.setOnClickListener(v -> host.launch(a));
        r.setOnLongClickListener(v -> {
            host.showMenu(a, true);
            return true;
        });
        return r;
    }

    /** Text and shadow at the given opacity, keeping the view itself opaque. */
    private void setTextAlpha(TextView t, float a) {
        t.setTag(R.id.text_alpha, a);
        t.setTextColor(withAlpha(INK, a));
        t.setShadowLayer(dp(2), 0, dp(1), withAlpha(0x59000000, a));
    }

    private void fadeText(final TextView t, float to) {
        Object tag = t.getTag(R.id.text_alpha);
        float from = tag instanceof Float ? (Float) tag : 1f;
        Object old = t.getTag(R.id.text_anim);
        if (old instanceof ValueAnimator) ((ValueAnimator) old).cancel();
        ValueAnimator va = ValueAnimator.ofFloat(from, to).setDuration(160);
        va.addUpdateListener(an -> setTextAlpha(t, (Float) an.getAnimatedValue()));
        t.setTag(R.id.text_anim, va);
        va.start();
    }

    private static int withAlpha(int color, float a) {
        return (Math.round((color >>> 24) * a) << 24) | (color & 0xFFFFFF);
    }

    /** Smoothly scroll the Home list so the focused row sits mid-screen (clamped at the ends). */
    private void centerInList(final View row) {
        listScroll.post(() -> listScroll.smoothScrollTo(0,
                row.getTop() + row.getHeight() / 2 - listScroll.getHeight() / 2));
    }

    /** Bottom-right link in the Home list layout: label then icon, pill when focused. */
    private View listUtil(int icon, String label, View.OnClickListener click) {
        final LinearLayout u = new LinearLayout(getContext());
        u.setGravity(Gravity.CENTER_VERTICAL);
        u.setPadding(dp(16), 0, dp(14), 0);
        u.setFocusable(true);
        u.setClickable(true);
        u.setBackground(pill(SOLID));
        final TextView tv = text(label, 16, true, INK);
        tv.setShadowLayer(dp(1.5f), 0, dp(0.5f), 0x38000000);
        u.addView(tv, new LinearLayout.LayoutParams(-2, -2));
        final ImageView ic = new ImageView(getContext());
        ic.setImageResource(icon);
        ic.setColorFilter(INK, PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(17), dp(17));
        ip.leftMargin = dp(8);
        u.addView(ic, ip);
        u.setOnFocusChangeListener((v, f) -> {
            tv.setTextColor(f ? SOLID_INK : INK);
            ic.setColorFilter(f ? SOLID_INK : INK, PorterDuff.Mode.SRC_IN);
        });
        u.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT || ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            View t = lastDockPkg != null ? findTile(listCol, lastDockPkg) : null;
            if (t == null && listCol.getChildCount() > 0) t = listCol.getChildAt(0);
            if (t != null) t.requestFocus();
            return true;
        });
        u.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
        lp.topMargin = dp(6);
        u.setLayoutParams(lp);
        return u;
    }

    /** App library row used by the "List" layout: icon, name, and a marker if it's on Home. */
    View listRow(final AppEntry a) {
        final LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), 0, dp(18), 0);
        r.setFocusable(true);
        r.setClickable(true);
        r.setTag(a);
        r.setBackground(plate());

        // Fixed-width slot so names line up whether the icon is square or a wide brand mark.
        FrameLayout slot = new FrameLayout(getContext());
        final ImageView ic = new ImageView(getContext());
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        ic.setImageDrawable(iconFor(a));
        slot.addView(ic, new LayoutParams(dp(a.brand ? 44 : 32), dp(32), Gravity.CENTER));
        r.addView(slot, new LinearLayout.LayoutParams(dp(48), dp(40)));

        final TextView lb = text(a.label, 18, true, INK);
        lb.setSingleLine();
        lb.setEllipsize(TextUtils.TruncateAt.END);
        lb.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, -2, 1f);
        llp.leftMargin = dp(14);
        r.addView(lb, llp);

        final TextView tag = text(dockPkgs.contains(a.pkg) ? "On Home" : "", 14, false, MUTE);
        r.addView(tag, new LinearLayout.LayoutParams(-2, -2));

        ic.setAlpha(0.84f);
        lb.setAlpha(0.84f);
        r.setOnFocusChangeListener((v, focused) -> {
            float al = focused ? 1f : 0.84f;
            ic.animate().alpha(al).setDuration(160).start();
            lb.animate().alpha(al).setDuration(160).start();
            if (focused) centerInScroll(r);
        });
        // One row per press, as on the Home list (the ScrollView's own handling skips rows
        // while the centering animation runs).
        r.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code != KeyEvent.KEYCODE_DPAD_DOWN && code != KeyEvent.KEYCODE_DPAD_UP) return false;
            ViewGroup parent = (ViewGroup) v.getParent();
            int i = parent.indexOfChild(v) + (code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            if (i >= 0 && i < parent.getChildCount()) parent.getChildAt(i).requestFocus();
            return true;
        });
        r.setOnClickListener(v -> host.launch(a));
        r.setOnLongClickListener(v -> {
            host.showMenu(a, false);
            return true;
        });
        return r;
    }

    /** Smoothly scroll the nearest ScrollView so this row sits mid-screen (clamped at the ends). */
    private static void centerInScroll(final View row) {
        View p = (View) row.getParent();
        if (p == null || !(p.getParent() instanceof ScrollView)) return;
        final View content = p;
        final ScrollView sv = (ScrollView) p.getParent();
        sv.post(() -> sv.smoothScrollTo(0, content.getTop() + row.getTop()
                + row.getHeight() / 2 - sv.getHeight() / 2));
    }

    Drawable plate() {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x45FFFFFF, 0x1AFFFFFF, 0x1FFFFFFF});
        g.setCornerRadius(dp(16));
        g.setStroke(Math.max(1, dp(0.5f)), 0x2EFFFFFF);
        s.addState(new int[]{android.R.attr.state_focused}, g);
        s.addState(new int[]{}, new ColorDrawable(0));
        s.setEnterFadeDuration(120);
        s.setExitFadeDuration(120);
        return s;
    }

    /** Top-right pill: icon only, label appears when focused. */
    private View util(int icon, String label, View.OnClickListener click) {
        final LinearLayout u = new LinearLayout(getContext());
        u.setGravity(Gravity.CENTER_VERTICAL);
        u.setPadding(dp(8), 0, dp(8), 0);
        u.setFocusable(true);
        u.setClickable(true);
        u.setBackground(pill(SOLID));
        final ImageView ic = new ImageView(getContext());
        ic.setImageResource(icon);
        ic.setColorFilter(INK, PorterDuff.Mode.SRC_IN);
        u.addView(ic, new LinearLayout.LayoutParams(dp(17), dp(17)));
        final TextView tv = text(label, 15, true, SOLID_INK);
        tv.setVisibility(View.GONE);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = dp(6);
        u.addView(tv, tp);
        u.setOnFocusChangeListener((v, f) -> {
            ic.setColorFilter(f ? SOLID_INK : INK, PorterDuff.Mode.SRC_IN);
            tv.setVisibility(f ? View.VISIBLE : View.GONE);
            u.setPadding(dp(f ? 10 : 8), 0, dp(f ? 13 : 8), 0);
        });
        u.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && ev.getAction() == KeyEvent.ACTION_DOWN) {
                focusDock(false);
                return true;
            }
            return false;
        });
        u.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
        lp.leftMargin = dp(4);
        u.setLayoutParams(lp);
        return u;
    }

    Drawable pill(int color) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(color);
        s.addState(new int[]{android.R.attr.state_focused}, g);
        s.addState(new int[]{}, new ColorDrawable(0));
        s.setEnterFadeDuration(140);
        s.setExitFadeDuration(140);
        return s;
    }

    // ------------------------------------------------------------ wallpaper

    Wallpapers.Item currentWall() {
        String id = prefs.getString("wall", "cove");
        for (Wallpapers.Item w : walls) if (w.id.equals(id)) return w;
        return walls.isEmpty() ? null : walls.get(0);
    }

    private boolean holdingBitmaps;
    /** The cover decodes off the main thread: solid colour first, then the photo. */
    final boolean asyncWall;
    private String wantedWall;

    void applyWallpaper(Wallpapers.Item w) {
        if (w == null) return;
        if (!holdingBitmaps) {
            holdingBitmaps = true;
            Bitmaps.hold();
        }
        GlassView.lightTone = w.light;
        wantedWall = w.id;
        final String id = w.id;
        if (!Bitmaps.cached(id)) {
            // A different (or released) wallpaper: drop the old one from the views BEFORE decoding.
            wall.setImageDrawable(null);
            GlassView.blur = null;
            invalidateGlass(getRootView());
        }
        if (asyncWall && !Bitmaps.cached(id)) {
            final Context c = getContext().getApplicationContext();
            new Thread(() -> {
                final Bitmap[] r = Bitmaps.get(c, id);
                handler.post(() -> {
                    if (!holdingBitmaps || !id.equals(wantedWall) || r[0] == null) return;
                    GlassView.blur = r[1];
                    wall.setImageBitmap(r[0]);
                    invalidateGlass(getRootView());
                });
            }, "wallpaper").start();
            return;
        }
        Bitmap[] r = Bitmaps.get(getContext(), id);
        GlassView.blur = r[1];
        wall.setImageBitmap(r[0]);   // null (decode failed) leaves the solid background colour
        invalidateGlass(getRootView());
    }

    /** Let go of the big bitmaps while hidden; refresh()/applyWallpaper() brings them back. */
    void releaseBitmaps() {
        wall.setImageDrawable(null);
        if (holdingBitmaps) {
            holdingBitmaps = false;
            Bitmaps.release();
        }
        if (!Bitmaps.inUse()) GlassView.blur = null;
        // onDraw doesn't run while hidden, so clear every glass panel's cached shader here.
        clearGlass(this);
        View rootView = getRootView();
        if (rootView != this) clearGlass(rootView);   // panels in the activity's root
    }

    private void clearGlass(View v) {
        if (v instanceof GlassView) ((GlassView) v).clearShader();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) clearGlass(g.getChildAt(i));
        }
    }

    private void invalidateGlass(View v) {
        if (v instanceof GlassView) {
            if (GlassView.blur == null) ((GlassView) v).clearShader();
            v.invalidate();
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateGlass(g.getChildAt(i));
        }
    }

    // ---------------------------------------------------------------- misc

    void updateClock() {
        boolean c24 = prefs.getBoolean("clock24", false);
        clock.setText(new SimpleDateFormat(c24 ? "H:mm" : "h:mm", Locale.getDefault()).format(new Date()));
    }

    static final String PREF_DARK = "dark";
    static final String DARK_OFF = "off", DARK_ON = "on", DARK_NIGHT = "night";

    /** Dark mode now: always, never, or 7 PM to 7 AM. */
    static boolean isDark(SharedPreferences prefs) {
        String m = prefs.getString(PREF_DARK, DARK_OFF);
        if (DARK_ON.equals(m)) return true;
        if (!DARK_NIGHT.equals(m)) return false;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h >= 19 || h < 7;
    }

    /** Dim the photo and darken the glass in dark mode; no-op if nothing changed. */
    void applyTheme() {
        boolean d = isDark(prefs);
        if (darkApplied != null && darkApplied == d && GlassView.dark == d) return;
        darkApplied = d;
        GlassView.dark = d;
        dim.setAlpha(0.5f);
        dim.setVisibility(d ? View.VISIBLE : View.GONE);
        invalidateGlass(getRootView());
    }

    static final String PREF_SHOW_NET = "showWifi";

    /** Network row under the clock: Wi-Fi name and signal, Ethernet, or not connected. */
    void updateNetwork() {
        if (!prefs.getBoolean(PREF_SHOW_NET, true)) {
            net.setVisibility(View.GONE);
            return;
        }
        int icon;
        String label;
        NetworkInfo ni = null;
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            ni = cm.getActiveNetworkInfo();
        } catch (Exception ignored) { }
        if (ni == null || !ni.isConnected()) {
            icon = R.drawable.ic_wifi_off;
            label = "Not connected";
        } else if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
            icon = R.drawable.ic_ethernet;
            label = "Ethernet";
        } else if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
            WifiInfo wi = null;
            try {
                WifiManager wm = (WifiManager)
                        getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                wi = wm.getConnectionInfo();
            } catch (Exception ignored) { }
            int level = wi != null ? WifiManager.calculateSignalLevel(wi.getRssi(), 3) : 2;
            icon = level >= 2 ? R.drawable.ic_wifi_3 : level == 1 ? R.drawable.ic_wifi_2 : R.drawable.ic_wifi_1;
            String ssid = ssid(wi);
            // Android 9 hides the name unless we hold the location permission (install.sh
            // grants it, or Settings › Wi-Fi network asks for it).
            label = ssid != null ? ssid : "Wi-Fi";
        } else {
            icon = R.drawable.ic_wifi_3;
            label = "Connected";
        }
        netIcon.setImageResource(icon);
        netText.setText(label);
        net.setVisibility(View.VISIBLE);
    }

    static String ssid(WifiInfo wi) {
        if (wi == null) return null;
        String s = wi.getSSID();
        if (s == null || s.isEmpty() || s.equals("<unknown ssid>") || s.equals("0x")) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s.isEmpty() ? null : s;
    }

    TextView text(String s, float sp, boolean medium, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(medium ? fMed : fReg);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
