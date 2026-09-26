package dev.betterendfield.android;

import android.content.SharedPreferences;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * In-process runtime journal for the hooked game process. Every milestone of
 * the load pipeline (LSPosed attach, settings schema, module selection, Unity
 * frame trigger, native library load attempts) records here. The ring is
 * mirrored to the remote preference "runtime_log" — the same transport the
 * settings snapshot already proves works in both directions — and echoed to
 * logcat under one tag for adb capture. The diagnostics page in the module
 * app reads the preference back through the LSPosed service.
 */
public final class RuntimeLog {
    private static final int CAPACITY = 150;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final long FLUSH_INTERVAL_MS = 1500L;
    private static volatile SharedPreferences remote;
    private static volatile long lastFlushAt;

    private RuntimeLog() {}

    static void bind(SharedPreferences remoteLogPrefs) {
        remote = remoteLogPrefs;
        flush();
    }

    public static synchronized void record(String message) {
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        if (LINES.size() >= CAPACITY) LINES.pollFirst();
        LINES.addLast(stamp + " " + message);
        Log.i("BetterEndfield.Runtime", message);
        long now = System.currentTimeMillis();
        if (now - lastFlushAt >= FLUSH_INTERVAL_MS) flush();
    }

    /** Force a rewrite of the remote preference; returns false when unbound. */
    public static synchronized boolean flush() {
        lastFlushAt = System.currentTimeMillis();
        SharedPreferences target = remote;
        if (target == null || LINES.isEmpty()) return false;
        StringBuilder all = new StringBuilder();
        for (String line : LINES) all.append(line).append('\n');
        try {
            target.edit().putString("log", all.toString()).apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * The newest {@code max} lines, oldest first. Read by the overlay panel —
     * the same process, so this path cannot lose a race with any transport.
     */
    public static synchronized String tail(int max) {
        if (LINES.isEmpty()) return "";
        int skip = Math.max(0, LINES.size() - max);
        java.util.Iterator<String> it = LINES.iterator();
        for (int i = 0; i < skip; i++) it.next();
        StringBuilder out = new StringBuilder();
        while (it.hasNext()) out.append(it.next()).append('\n');
        return out.toString();
    }

    /**
     * Mirrors the native command bridge status into the same remote preference
     * as the journal. The game process has no {@code XposedService} binder, so
     * {@code FrameworkSettings.writeRemoteStatus} could never deliver this.
     */
    public static synchronized boolean setStatus(String status) {
        SharedPreferences target = remote;
        if (target == null || status == null || status.isEmpty()) return false;
        try {
            target.edit().putString("status", status).apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
