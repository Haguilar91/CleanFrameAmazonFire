package com.hector.cleanlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.InputStream;

/**
 * Decodes the wallpaper, its pre-blurred glass copy and the picker thumbnails at no more than
 * the size they are drawn at, and keeps one shared copy of the wallpaper and blur for every
 * HomeView (activity and cover). Holders count themselves in and out; when the last one lets
 * go the bitmaps are dropped (never recycled: a view may still be drawing one). A failed
 * decode, OutOfMemoryError included, returns null and the views fall back to a solid colour.
 */
final class Bitmaps {

    private static final String TAG = "CleanLauncher";

    private static Bitmap wall, blur;
    private static String id;
    private static int holders;

    private Bitmaps() {}

    /** True if the wallpaper for id is already decoded (so wall() returns at once). */
    static synchronized boolean cached(String wid) {
        return wid.equals(id) && wall != null;
    }

    private static String loading;
    private static Bitmap[] lastResult;
    private static String lastResultId;
    private static int waiters;

    /**
     * The wallpaper and its blur for wid: {wall, blur}, either may be null if it can't be
     * decoded. A second caller for an id already being decoded waits for that decode and
     * reuses its result. The decode itself runs outside the lock (waiting releases it), so
     * release() on the main thread never blocks on it. Only cached while someone holds it.
     */
    static Bitmap[] get(Context c, String wid) {
        synchronized (Bitmaps.class) {
            long deadline = System.currentTimeMillis() + 10000;
            while (true) {
                if (wid.equals(id) && wall != null) return new Bitmap[]{wall, blur};
                if (!wid.equals(loading)) break;
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;   // the other decode is stuck: do our own
                waiters++;
                try {
                    Bitmaps.class.wait(left);
                } catch (InterruptedException e) {
                    waiters--;
                    break;
                }
                waiters--;
                if (!wid.equals(loading) && wid.equals(lastResultId) && lastResult != null) {
                    Bitmap[] r = lastResult;
                    if (waiters == 0) {
                        lastResult = null;
                        lastResultId = null;
                    }
                    return r;
                }
            }
            wall = null;   // drop the old wallpaper first so both are never in memory together
            blur = null;
            id = null;
            lastResult = null;
            lastResultId = null;
            loading = wid;
        }
        Bitmap w = null, b = null;
        try {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int sw = Math.max(dm.widthPixels, dm.heightPixels);
            int sh = Math.min(dm.widthPixels, dm.heightPixels);
            w = decode(c, "wallpapers/" + wid + ".jpg", Bitmap.Config.ARGB_8888, sw, sh, false);
            b = decode(c, "materials/" + wid + "-blur.jpg", Bitmap.Config.RGB_565, sw / 4, sh / 4, true);
        } finally {
            synchronized (Bitmaps.class) {
                if (holders > 0 && w != null) {
                    wall = w;
                    blur = b;
                    id = wid;
                }
                if (wid.equals(loading)) loading = null;
                if (waiters > 0) {
                    lastResult = new Bitmap[]{w, b};
                    lastResultId = wid;
                }
                Bitmaps.class.notifyAll();
            }
        }
        return new Bitmap[]{w, b};
    }

    /** A picker thumbnail: heavily subsampled. */
    static Bitmap thumb(Context c, String wid) {
        return decode(c, "wallpapers/" + wid + "-thumb.jpg", Bitmap.Config.RGB_565, 0, 0, true, 2);
    }

    static synchronized boolean inUse() {
        return holders > 0;
    }

    static synchronized void hold() {
        holders++;
    }

    /** The last holder drops the shared bitmaps. */
    static synchronized void release() {
        if (holders > 0) holders--;
        if (holders == 0 && (wall != null || blur != null)) {
            Log.d(TAG, "released wallpaper bitmaps");
            wall = null;
            blur = null;
            id = null;
        }
    }

    private static Bitmap decode(Context c, String asset, Bitmap.Config cfg, int w, int h, boolean atMost) {
        return decode(c, asset, cfg, w, h, atMost, 0);
    }

    /**
     * atMost: the largest sample that keeps the result within w x h (or a fixed sample if
     * fixed &gt; 0); otherwise the largest sample that keeps it at least w x h.
     */
    private static Bitmap decode(Context c, String asset, Bitmap.Config cfg, int w, int h,
                                 boolean atMost, int fixed) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inPreferredConfig = cfg;
            if (fixed > 0) {
                o.inSampleSize = fixed;
            } else {
                o.inJustDecodeBounds = true;
                InputStream in = c.getAssets().open(asset);
                try {
                    BitmapFactory.decodeStream(in, null, o);
                } finally {
                    in.close();
                }
                int s = 1;
                if (atMost) {
                    while (o.outWidth / s > w && o.outHeight / s > h) s *= 2;
                } else {
                    while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2;
                }
                o.inJustDecodeBounds = false;
                o.inSampleSize = s;
            }
            InputStream in = c.getAssets().open(asset);
            try {
                return BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "out of memory decoding " + asset);
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
