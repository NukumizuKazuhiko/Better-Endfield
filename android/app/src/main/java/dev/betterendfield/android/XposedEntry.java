package dev.betterendfield.android;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import android.os.ParcelFileDescriptor;

import io.github.libxposed.api.XposedModule;

/** The module's only framework entry point: libxposed API 102. */
public final class XposedEntry extends XposedModule {
    private String processName;
    private final AtomicBoolean attached = new AtomicBoolean();
    private final AtomicBoolean nativeReady = new AtomicBoolean();

    /** This module instance, so helper classes in this package can reach hook(). */
    private static volatile XposedEntry instance;
    /** Set once the base Activity.onActivityResult relay hook is in place. */
    private static final AtomicBoolean resultRelayReady = new AtomicBoolean();
    /** Consumer for relayed host activity results (the overlay's save dialog). */
    private static volatile HostResultListener resultListener;
    /** Concrete activity classes whose own onActivityResult is already hooked. */
    private static final java.util.Set<String> hookedResultClasses = new java.util.HashSet<>();

    /** Receives host activity results relayed by the module's hooks. */
    interface HostResultListener {
        void onHostResult(int requestCode, int resultCode, Intent data);
    }

    /** Registers the listener that receives the host activity's results. */
    static void setActivityResultListener(HostResultListener listener) {
        resultListener = listener;
    }

    /** Whether startActivityForResult results can reach the module at all. */
    static boolean activityResultRelayReady() {
        return resultRelayReady.get();
    }

    /**
     * Hooks the concrete activity class's own onActivityResult override. The
     * base Activity relay only fires when the override calls super; games that
     * swallow the callback need this per-class hook to see the result.
     */
    static void hookConcreteActivityResult(Class<?> cls) {
        XposedEntry module = instance;
        if (module == null) return;
        synchronized (hookedResultClasses) {
            if (!hookedResultClasses.add(cls.getName())) return;
        }
        try {
            Method method = cls.getDeclaredMethod("onActivityResult",
                    int.class, int.class, Intent.class);
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    module.dispatchActivityResult(
                            (Integer) chain.getArg(0),
                            (Integer) chain.getArg(1),
                            (Intent) chain.getArg(2));
                } catch (Throwable ignored) { /* relay must never break the game */ }
                return result;
            });
            RuntimeLog.record("activity result relay: hooked " + cls.getName());
        } catch (NoSuchMethodException noOverride) {
            // The activity inherits Activity.onActivityResult; the base relay covers it.
        } catch (Throwable error) {
            RuntimeLog.record("activity result relay on " + cls.getName() + " failed: " + error);
        }
    }

    private void installActivityResultRelay() {
        try {
            hook(Activity.class.getDeclaredMethod("onActivityResult",
                    int.class, int.class, Intent.class)).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    dispatchActivityResult(
                            (Integer) chain.getArg(0),
                            (Integer) chain.getArg(1),
                            (Intent) chain.getArg(2));
                } catch (Throwable ignored) { /* relay must never break the game */ }
                return result;
            });
            resultRelayReady.set(true);
            RuntimeLog.record("activity result relay installed (Activity base)");
        } catch (Throwable error) {
            RuntimeLog.record("activity result relay failed: " + error);
        }
    }

    private void dispatchActivityResult(int requestCode, int resultCode, Intent data) {
        HostResultListener listener = resultListener;
        if (listener != null) listener.onHostResult(requestCode, resultCode, data);
    }

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        instance = this;
        // The journal must never take the entry down with it: a remote
        // preference or service hiccup here used to abort the whole hook.
        try {
            RuntimeLog.bind(getRemotePreferences("runtime_log"));
            RuntimeLog.record("journal build " + BuildConfig.VERSION_NAME
                    + " (" + BuildConfig.VERSION_CODE + ")");
            RuntimeLog.record("onPackageReady pkg=" + param.getPackageName()
                    + " process=" + processName);
        } catch (Throwable journalFailure) {
            log(Log.WARN, "BetterEndfield.Runtime",
                    "journal bind failed: " + journalFailure);
        }
        if (!RuntimeBootstrap.isTarget(param.getPackageName(), processName)) {
            RuntimeLog.record("skip: not a target process (package != process, "
                    + "or the module/system package itself)");
            return;
        }
        if (!attached.compareAndSet(false, true)) return;
        try {
            SharedPreferences settings = getRemotePreferences("module_settings");
            RuntimeLog.record("settings schemaVersion="
                    + settings.getInt("schemaVersion", 1));
            if (settings.getInt("schemaVersion", 1) != 1) {
                report("unsupported settings schema; native runtime disabled");
                return;
            }
            ModuleConfigurations configs = ModuleConfigurations.read(settings);
            RuntimeLog.record("configs read: " + (configs.none()
                    ? "NONE selected" : configs.summary()));
            hook(Application.class.getDeclaredMethod("attach", Context.class)).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    GameOverlay.install((Application) chain.getThisObject(), param.getClassLoader(),
                            () -> {
                                // The service-backed preferences proxy can be
                                // stale after the settings Activity commits a
                                // new snapshot. Re-acquire it when an Activity
                                // resumes instead of caching the old values.
                                try {
                                    return OverlayFeatures.read(
                                            getRemotePreferences("module_settings"));
                                } catch (RuntimeException unavailable) {
                                    return OverlayFeatures.read(settings);
                                }
                            });
                    Application application=(Application) chain.getThisObject();
                    Context context=(Context) chain.getArg(0);
                    new Thread(() -> {
                        try {
                            BemInstalledResources.configuration=BemInstalledResources.prepare(context,
                                settings.getString(BemInstaller.INDEX,"[]"),
                                name -> new ParcelFileDescriptor.AutoCloseInputStream(openRemoteFile(name)),this::report);
                        } catch(Exception error) {report("Installed BEM preparation failed: "+error);}
                        RuntimeBootstrap.prepare(application,context,param.getClassLoader(),configs,this::installFrames,this::report);
                    },"BetterEndfield-InstalledModels").start();
                } catch (Throwable error) { report("bootstrap failed: " + error); }
                return result;
            });
            report("attached to " + param.getPackageName());
            RuntimeLog.record("attached; Application.attach hook installed, "
                    + "waiting for game startup");
            installActivityResultRelay();
            startRemoteCommandPoller();
        } catch (Throwable error) { report("entry failed: " + error); }
    }

    private void installFrames(ClassLoader loader, java.util.function.BooleanSupplier callback) throws Throwable {
        Class<?> unity = Class.forName("com.unity3d.player.UnityPlayer", false, loader);
        CopyOnWriteArrayList<HookHandle> hooks = new CopyOnWriteArrayList<>();
        AtomicBoolean complete = new AtomicBoolean();
        AtomicBoolean announced = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger frames = new java.util.concurrent.atomic.AtomicInteger();
        try {
            for (Method method : unity.getDeclaredMethods()) {
                if (!method.getName().equals("nativeRender") || method.getReturnType() != boolean.class) continue;
                HookHandle handle = hook(method).intercept(chain -> {
                    Object result = chain.proceed();
                    // Frame probes: the pipeline previously stalled here with
                    // zero evidence. Record the first frames and a periodic
                    // heartbeat so a dead hook is visible in the journal.
                    int seen = frames.incrementAndGet();
                    if (seen <= 3 || seen == 30 || seen % 300 == 0) {
                        RuntimeLog.record("nativeRender frame " + seen
                                + " result=" + result);
                    }
                    if (Boolean.TRUE.equals(result) && !complete.get()) {
                        if (announced.compareAndSet(false, true)) {
                            RuntimeLog.record("first successful Unity frame; loading native runtime");
                        }
                        if (callback.getAsBoolean()) {
                            complete.set(true);
                            hooks.forEach(HookHandle::unhook);
                        }
                    }
                    return result;
                });
                hooks.add(handle);
                if (complete.get()) handle.unhook();
            }
            RuntimeLog.record("hooked " + hooks.size()
                    + " UnityPlayer.nativeRender method(s), waiting for frames");
            if (hooks.isEmpty()) throw new NoSuchMethodException("UnityPlayer.nativeRender");
        } catch (Throwable error) {
            hooks.forEach(HookHandle::unhook);
            throw error;
        }
    }

    private void report(String message) {
        log(Log.INFO, "BetterEndfield.Xposed", message);
        RuntimeLog.record(message);
    }

    private void startRemoteCommandPoller() {
        Thread worker = new Thread(() -> {
            String last = "";
            String lastStatus = "";
            long nativeLogOffset = 0L;
            long lastNativeSerial = 0L;
            String pendingNativeLine = "";
            long nextNativeProbeAt = 0L;
            while (attached.get()) {
                try {
                    boolean present = false;
                    for (String file : listRemoteFiles()) if ("command.next".equals(file)) { present = true; break; }
                    long now = System.currentTimeMillis();
                    // Native module logs (hook results, "Actions enabled ...",
                    // dash session events) reach the panel journal here: the
                    // relay publishes them to native.log, we tail it.
                    StringBuilder fresh = new StringBuilder();
                    long[] nativeLogCursor = {nativeLogOffset};
                    NativeCommandBridge.tailNativeLog(fresh, nativeLogCursor);
                    // tailNativeLog reports the advanced cursor through the
                    // array. Without this write-back every poll re-read the
                    // log from byte 0 (capped at 64 KB), so once the file grew
                    // past the cap the newest lines never reached the panel;
                    // the serial filter masked the replay but not the loss.
                    nativeLogOffset = nativeLogCursor[0];
                    if (fresh.length() > 0) {
                        pendingNativeLine += fresh.toString();
                        int newline;
                        while ((newline = pendingNativeLine.indexOf('\n')) >= 0) {
                            String line = pendingNativeLine.substring(0, newline).trim();
                            pendingNativeLine = pendingNativeLine.substring(newline + 1);
                            if (line.isEmpty()) continue;
                            // Native lines carry a monotonic "#<serial> " prefix.
                            // A journal truncate resets the byte offset, and the
                            // next tail would replay already-recorded lines; a
                            // serial at or below the last seen one is a replay.
                            long serial = -1L;
                            if (line.startsWith("#")) {
                                int space = line.indexOf(' ');
                                if (space > 1) {
                                    try {
                                        serial = Long.parseLong(line.substring(1, space));
                                    } catch (NumberFormatException malformed) {
                                        serial = -1L;
                                    }
                                    if (serial > 0) line = line.substring(space + 1);
                                    else serial = -1L;
                                }
                            }
                            if (serial > 0) {
                                if (serial <= lastNativeSerial) continue;
                                lastNativeSerial = serial;
                            }
                            RuntimeLog.record("[native] " + line);
                        }
                        if (pendingNativeLine.length() > 4096) pendingNativeLine = "";
                    }
                    if (present && (nativeReady.get() || now >= nextNativeProbeAt)) {
                        // status() reads the file the native relay publishes;
                        // empty means the runtime has not loaded (yet).
                        String status = NativeCommandBridge.status();
                        if (!status.isEmpty()) {
                            if (nativeReady.compareAndSet(false, true)) {
                                RuntimeLog.record("native command bridge reachable");
                            }
                            if (!status.equals(lastStatus)) {
                                // FrameworkSettings.writeRemoteStatus is dead in
                                // the game process (its service binder only binds
                                // in the module app); the journal's remote
                                // preference is the proven channel.
                                if (RuntimeLog.setStatus(status)) lastStatus = status;
                            }
                        } else if (nativeReady.compareAndSet(true, false)) {
                            RuntimeLog.record("native command bridge status empty");
                            nextNativeProbeAt = now + 10000L;
                        }
                    }
                    if (!present) { Thread.sleep(250); continue; }
                    try (ParcelFileDescriptor descriptor = openRemoteFile("command.next");
                        BufferedReader reader = new BufferedReader(new InputStreamReader(
                                new ParcelFileDescriptor.AutoCloseInputStream(descriptor),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                        StringBuilder content = new StringBuilder(); String line;
                        while ((line = reader.readLine()) != null) content.append(line).append('\n');
                        String value = content.toString();
                        if (!value.isEmpty() && !value.equals(last)) {
                            // Returns false only while the relay channel has not
                            // been configured; otherwise the command sits in the
                            // runtime's pump slot until the runtime consumes it.
                            if (NativeCommandBridge.submit(value)) last = value;
                        }
                    }
                } catch (java.io.FileNotFoundException missing) {
                    // The UI has not issued a command. Avoid repeatedly asking
                    // the framework to open a file that does not exist.
                } catch (Throwable ignored) { /* remote file may be unavailable during reload */ }
                try { Thread.sleep(250); } catch (InterruptedException stopped) { return; }
            }
        }, "BetterEndfield-Commands");
        worker.setDaemon(true); worker.start();
    }
}
