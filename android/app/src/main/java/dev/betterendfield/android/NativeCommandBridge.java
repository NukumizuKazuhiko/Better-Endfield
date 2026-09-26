package dev.betterendfield.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Panel-to-runtime channel. This used to be a JNI bridge, but the runtime
 * library registers under the game's classloader while these classes belong to
 * the LSPosed module classloader — and Android scopes JNI symbol lookup (and
 * .so openings) per classloader, so every call threw UnsatisfiedLinkError while
 * the native modules themselves ran fine. Plain files in the game's own files
 * directory have no such boundary: the native side (input_relay.cpp) tails the
 * input file and publishes the status file.
 *
 * Protocol, one event per line:
 *   "&lt;vk&gt; &lt;action&gt;\n"  latch a virtual key
 *   "c &lt;payload&gt;\n"       submit a runtime command
 *   "r\n"               release every latched key
 */
final class NativeCommandBridge {
    /** Matches betterendfield::VirtualKeyAction in native/shared/android_compat. */
    static final int KEY_RELEASE = 0;
    static final int KEY_PRESS = 1;
    static final int KEY_PULSE = 2;

    private static final Object LOCK = new Object();
    private static volatile File inputFile;
    private static volatile File statusFile;
    private static volatile File nativeLogFile;

    private NativeCommandBridge() {}

    /** Called from the game process before the native runtime loads. */
    static void configure(File input, File status, File nativeLog) {
        inputFile = input;
        statusFile = status;
        nativeLogFile = nativeLog;
        // Start every session with an empty stream so the native tail cannot
        // replay events from a previous run of the game.
        try (FileOutputStream fresh = new FileOutputStream(input)) {
            fresh.getChannel().force(false);
        } catch (Throwable ignored) {
            // The panel reports "unable to send" until the directory exists.
        }
        try (FileOutputStream fresh = new FileOutputStream(nativeLog)) {
            fresh.getChannel().force(false);
        } catch (Throwable ignored) {
            // The journal simply stays empty until the runtime creates it.
        }
    }

    static boolean submit(String payload) {
        if (payload == null || payload.isEmpty()) return false;
        return append("c " + payload.trim() + "\n");
    }

    /**
     * Presses a Windows virtual-key code in the latch the ported desktop
     * modules poll through GetAsyncKeyState. Returns false while the channel
     * is not configured (no native runtime expected yet).
     */
    static boolean key(int virtualKey, int action) {
        if (action < KEY_RELEASE || action > KEY_PULSE) return false;
        return append(virtualKey + " " + action + "\n");
    }

    static boolean releaseKeys() {
        return append("r\n");
    }

    /**
     * The runtime command status the native relay publishes. Empty until the
     * native runtime has loaded — the same "not ready yet" signal the JNI
     * version used to give through exceptions.
     */
    static String status() {
        File file = statusFile;
        if (file == null) return "";
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] bytes = stream.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (IOException notYet) {
            return "";
        }
    }

    /**
     * Appends native log lines written since the previous call to {@code into}
     * (complete lines only; a trailing partial line waits for its newline).
     * {@code offset[0]} is the read cursor, owned by the caller. Empty when the
     * runtime has not produced anything (or has not loaded).
     */
    static void tailNativeLog(StringBuilder into, long[] offset) {
        File file = nativeLogFile;
        if (file == null) return;
        try (FileInputStream stream = new FileInputStream(file)) {
            long size = stream.getChannel().size();
            if (size < offset[0]) offset[0] = 0;  // the relay truncated the file
            if (size == offset[0]) return;
            stream.getChannel().position(offset[0]);
            byte[] chunk = new byte[(int) Math.min(size - offset[0], 65536)];
            int got = stream.read(chunk);
            if (got <= 0) return;
            offset[0] += got;
            into.append(new String(chunk, 0, got, StandardCharsets.UTF_8));
        } catch (IOException notYet) {
            // The relay creates the file when the runtime loads.
        }
    }

    private static boolean append(String line) {
        File file = inputFile;
        if (file == null) return false;
        synchronized (LOCK) {
            try (FileOutputStream stream = new FileOutputStream(file, true)) {
                stream.write(line.getBytes(StandardCharsets.UTF_8));
                stream.flush();
                return true;
            } catch (IOException broken) {
                return false;
            }
        }
    }
}
