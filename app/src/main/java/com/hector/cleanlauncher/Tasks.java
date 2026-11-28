package com.hector.cleanlauncher;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Memory numbers for the Tasks panel and the low-memory watchdog. */
final class Tasks {

    private static final String TAG = "CleanLauncher";
    static final String DUMP = "android.permission.DUMP";

    private Tasks() {}

    static ActivityManager.MemoryInfo memory(Context c) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) am.getMemoryInfo(mi);
        return mi;
    }

    static boolean canDump(Context c) {
        if (c.checkSelfPermission(DUMP) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Running apps: package to PSS in KB (processes summed), and the app in front (or null). */
    static final class Snapshot {
        final Map<String, Long> kb = new HashMap<>();
        String top;
    }

    private static final Pattern RECORD = Pattern.compile("ProcessRecord\\{\\w+ (\\d+):([^/\\s]+)/");
    private static final Pattern PSS = Pattern.compile("lastPss=([0-9]*\\.?[0-9]+)\\s*([A-Za-z]*)");
    private static final Pattern TOP = Pattern.compile("(\\d+):([^/\\s]+)/\\S+\\s+\\(top-activity\\)");

    /**
     * Running apps from `dumpsys activity processes` (the "activity" service is reachable by
     * apps; the "meminfo" one is blocked by SELinux). Needs DUMP. Blocking: call off the main
     * thread. Tries the service binder by reflection first, then the dumpsys binary; the output
     * is parsed line by line as it streams in. Returns null if both fail, and logs which step
     * failed and why. Only packages in {@code known} are kept.
     */
    static Snapshot processes(Context c, Map<String, ?> known) {
        if (!canDump(c)) {
            Log.w(TAG, "tasks: DUMP or usage access (GET_USAGE_STATS app op) not granted");
            return null;
        }
        Parser p = new Parser(c, known);
        try {
            viaBinder(p);
            if (p.sawRecord()) {
                Log.d(TAG, "tasks: source=binder");
                return p.result();
            }
            Log.w(TAG, "tasks: binder failed: no ProcessRecord lines (" + p.lines() + " lines, first 200 chars: \"" + p.head() + "\"), trying dumpsys");
        } catch (Throwable e) {
            Log.w(TAG, "tasks: binder failed: " + e + ", trying dumpsys");
        }
        p = new Parser(c, known);
        try {
            viaDumpsys(p);
            if (p.sawRecord()) {
                Log.d(TAG, "tasks: source=dumpsys");
                return p.result();
            }
            Log.w(TAG, "tasks: dumpsys failed: no ProcessRecord lines (" + p.lines() + " lines, first 200 chars: \"" + p.head() + "\"; " + p.exitInfo + ")");
        } catch (Throwable e) {
            Log.w(TAG, "tasks: dumpsys failed: " + e);
        }
        return null;
    }

    /** Line-by-line parser of the dump; thread-safe so an abandoned reader can't corrupt it. */
    static final class Parser {
        private final Context c;
        private final Map<String, ?> known;
        private final Snapshot out = new Snapshot();
        private final Set<Integer> seen = new HashSet<>();
        private String cur;
        private boolean wantPss, inLru, saw, done;
        private int lines;
        private final StringBuilder head = new StringBuilder();
        String exitInfo = "";

        Parser(Context c, Map<String, ?> known) {
            this.c = c;
            this.known = known;
        }

        synchronized boolean sawRecord() { return saw; }
        synchronized int lines() { return lines; }
        /** The first 200 characters read (lines joined by " | "), for diagnosing failures. */
        synchronized String head() { return head.toString(); }
        synchronized Snapshot result() {
            done = true;   // ignore anything an abandoned reader still delivers
            return out;
        }

        synchronized void feed(String line) {
            if (done) return;
            lines++;
            if (head.length() < 200) {
                if (head.length() > 0) head.append(" | ");
                head.append(line, 0, Math.min(line.length(), 200 - Math.min(head.length(), 200)));
                if (head.length() > 200) head.setLength(200);
            }
            if (line.contains("ProcessRecord")) saw = true;
            if (line.contains("Process LRU list")) {
                inLru = true;
                cur = null;
                return;
            }
            if (inLru) {
                if (out.top == null && line.contains("(top-activity)")) {
                    Matcher m = TOP.matcher(line);
                    if (m.find()) out.top = pkgOf(m.group(2));
                }
                return;
            }
            boolean app = line.contains("*APP*"), pers = line.contains("*PERS*");
            if (app || pers) {
                cur = null;
                wantPss = false;
                Matcher m = RECORD.matcher(line);
                if (!app || !m.find()) return;
                int pid = Integer.parseInt(m.group(1));
                String pkg = pkgOf(m.group(2));
                if (!seen.add(pid)) return;   // the same process can be listed twice
                if (!known.containsKey(pkg) || AppCatalog.isProtected(c, pkg)) return;
                cur = pkg;
                wantPss = true;
                if (!out.kb.containsKey(pkg)) out.kb.put(pkg, 0L);   // live, size not sampled yet
            } else if (wantPss && cur != null && line.contains("lastPss=")) {
                Matcher m = PSS.matcher(line);
                if (m.find()) {
                    wantPss = false;
                    out.kb.put(cur, out.kb.get(cur) + toKb(m.group(1), m.group(2)));
                }
            }
        }
    }

    private static String pkgOf(String proc) {
        int colon = proc.indexOf(':');
        return colon > 0 ? proc.substring(0, colon) : proc;
    }

    private static long toKb(String num, String unit) {
        double v = Double.parseDouble(num);
        unit = unit.toUpperCase(java.util.Locale.US);
        if (unit.equals("GB")) v *= 1024 * 1024;
        else if (unit.equals("MB")) v *= 1024;
        else if (unit.equals("B") || unit.isEmpty()) v /= 1024;
        return Math.round(v);
    }

    private static void viaDumpsys(Parser parser) throws Exception {
        Process p = new ProcessBuilder("dumpsys", "activity", "processes")
                .redirectErrorStream(true).start();
        try {
            readLines(p.getInputStream(), parser, 5000);   // stderr is merged into stdout
            int code = -999;
            for (int i = 0; i < 10; i++) {
                try {
                    code = p.exitValue();
                    break;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(100);
                }
            }
            parser.exitInfo = code == -999 ? "exit code: still running" : "exit code: " + code;
        } finally {
            p.destroy();   // also on timeout
        }
    }

    private static void viaBinder(Parser parser) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        final IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "activity");
        if (binder == null) throw new IllegalStateException("no activity service");
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        Thread dumper = new Thread(() -> {
            try {
                binder.dump(pipe[1].getFileDescriptor(), new String[]{"processes"});
            } catch (Throwable e) {
                Log.w(TAG, "tasks: binder.dump failed: " + e);
            } finally {
                try { pipe[1].close(); } catch (Exception ignored) { }
            }
        }, "dump");
        dumper.start();
        try {
            readLines(new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]), parser, 5000);
        } finally {
            try { pipe[0].close(); } catch (Exception ignored) { }
            try { pipe[1].close(); } catch (Exception ignored) { }   // unblocks a stuck dumper
            dumper.interrupt();
        }
    }

    /**
     * Feed the stream to the parser line by line on a helper thread. After ms the stream is
     * closed and the helper interrupted, so it ends instead of lingering.
     */
    private static void readLines(final InputStream in, final Parser parser, long ms) throws Exception {
        final Exception[] err = new Exception[1];
        Thread t = new Thread(() -> {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(in));
                String line;
                while ((line = r.readLine()) != null) parser.feed(line);
            } catch (Exception e) {
                err[0] = e;
            }
        }, "dump-read");
        t.start();
        t.join(ms);
        if (t.isAlive()) {
            Log.w(TAG, "tasks: dump read timed out after " + ms + " ms");
            try { in.close(); } catch (Exception ignored) { }
            t.interrupt();
        } else if (err[0] != null && parser.lines() == 0) {
            throw err[0];
        }
    }
}
