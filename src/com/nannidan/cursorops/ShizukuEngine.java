package com.nannidan.cursorops;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Manages the Shizuku binder lifecycle, permission checks, system binder wrapping,
 * and asynchronous shell execution for privileged UID 2000 operations.
 */
public final class ShizukuEngine {
    private static final String TAG = "CursorOpsShizuku";
    public static final int PERM_REQUEST_CODE = 2048;

    /** Listener notified on the main thread about Shizuku binder and permission transitions. */
    public interface StateListener {
        void onBinderReady(boolean permissionGranted);
        void onBinderDead();
        void onPermissionResult(boolean granted);
    }

    private static final CopyOnWriteArraySet<StateListener> LISTENERS = new CopyOnWriteArraySet<>();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService SHELL_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CursorOps-ShizukuShell");
        t.setDaemon(true);
        return t;
    });

    private static volatile Method newProcessMethod;

    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED_LISTENER = () -> {
        boolean granted = hasPermission();
        Log.i(TAG, "Shizuku binder received (uid=" + getServerUid() + ", granted=" + granted + ")");
        MAIN_HANDLER.post(() -> {
            for (StateListener listener : LISTENERS) {
                listener.onBinderReady(granted);
            }
        });
    };

    private static final Shizuku.OnBinderDeadListener BINDER_DEAD_LISTENER = () -> {
        Log.w(TAG, "Shizuku binder died; invalidating cached system proxies");
        SystemPointerController.invalidateCache();
        MAIN_HANDLER.post(() -> {
            for (StateListener listener : LISTENERS) {
                listener.onBinderDead();
            }
        });
    };

    private static final Shizuku.OnRequestPermissionResultListener PERMISSION_RESULT_LISTENER =
            (requestCode, grantResult) -> {
                if (requestCode != PERM_REQUEST_CODE) {
                    return;
                }
                boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                Log.i(TAG, "Shizuku permission result: granted=" + granted);
                MAIN_HANDLER.post(() -> {
                    for (StateListener listener : LISTENERS) {
                        listener.onPermissionResult(granted);
                    }
                });
            };

    private ShizukuEngine() {}

    /** Idempotently registers Shizuku binder and permission lifecycle callbacks. */
    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER);
            Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER);
            Shizuku.addRequestPermissionResultListener(PERMISSION_RESULT_LISTENER);
        } catch (Throwable t) {
            INITIALIZED.set(false);
            Log.e(TAG, "Failed to register Shizuku listeners", t);
        }
    }

    /** Registers a state listener and ensures engine listeners are initialized. */
    public static void addListener(StateListener listener) {
        if (listener == null) {
            return;
        }
        init();
        LISTENERS.add(listener);
    }

    /** Unregisters a previously added state listener. */
    public static void removeListener(StateListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    /** Returns true if the Shizuku server binder currently responds to ping. */
    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Returns true if Shizuku v11+ permission is granted to this app. */
    public static boolean hasPermission() {
        try {
            return isBinderAlive()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Returns true when Shizuku binder is alive and permission is granted. */
    public static boolean isReady() {
        return hasPermission();
    }

    /** Returns the remote Shizuku server UID (typically 2000 for ADB shell), or -1 if unavailable. */
    public static int getServerUid() {
        if (!isBinderAlive()) {
            return -1;
        }
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Prompts the user for Shizuku permission if the binder is alive and ungranted. */
    public static void requestPermission() {
        init();
        try {
            if (isBinderAlive()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(PERM_REQUEST_CODE);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to request Shizuku permission: " + t.getMessage());
        }
    }

    /** Resolves a system service binder wrapped through Shizuku for privileged IPC. */
    public static synchronized IBinder wrapSystemBinder(String serviceName) {
        if (!isReady() || serviceName == null || serviceName.isEmpty()) {
            return null;
        }
        try {
            IBinder raw = SystemServiceHelper.getSystemService(serviceName);
            if (raw == null) {
                Log.w(TAG, "SystemServiceHelper returned null for " + serviceName);
                return null;
            }
            return new ShizukuBinderWrapper(raw);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to wrap system binder '" + serviceName + "'", t);
            return null;
        }
    }

    /** Grants SYSTEM_ALERT_WINDOW to the specified package via Shizuku appops. */
    public static void allowSelfOverlayAppOps(String packageName) {
        if (!isReady() || packageName == null || packageName.isEmpty()) {
            return;
        }
        runShellAsync("appops set " + packageName + " SYSTEM_ALERT_WINDOW allow", null);
    }

    /** Grants overlay app-ops and runtime permissions (Bluetooth, Notifications) via Shizuku. */
    public static void grantSelfPrivileges(Context context) {
        if (!isReady() || context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        String pkg = app.getPackageName();
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        String cmd = "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow; "
                + "pm grant " + pkg + " android.permission.BLUETOOTH_CONNECT 2>/dev/null || true; "
                + "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS 2>/dev/null || true";
        runShellAsync(cmd, () -> CursorConfig.notifyStateChanged(app));
    }

    /** Runs a shell command asynchronously through Shizuku without blocking the caller thread. */
    public static void runShellAsync(String command, Runnable onComplete) {
        if (!isReady() || command == null || command.isEmpty()) {
            if (onComplete != null) {
                MAIN_HANDLER.post(onComplete);
            }
            return;
        }
        SHELL_EXEC.execute(() -> {
            Process process = null;
            try {
                Method method = newProcessMethod;
                if (method == null) {
                    method = Shizuku.class.getDeclaredMethod(
                            "newProcess", String[].class, String[].class, String.class);
                    method.setAccessible(true);
                    newProcessMethod = method;
                }
                process = (Process) method.invoke(
                        null, new Object[]{new String[]{"sh", "-c", command}, null, null});
                if (process != null) {
                    process.waitFor();
                }
            } catch (Throwable t) {
                Log.e(TAG, "Shizuku shell command failed: " + command, t);
            } finally {
                if (process != null) {
                    try {
                        process.destroy();
                    } catch (Throwable ignored) {
                    }
                }
                if (onComplete != null) {
                    MAIN_HANDLER.post(onComplete);
                }
            }
        });
    }
}
