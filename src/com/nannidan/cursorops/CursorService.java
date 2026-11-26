package com.nannidan.cursorops;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;

/**
 * Persistent reliability foreground service that monitors external SOURCE_MOUSE devices
 * and Shizuku binder health to automatically apply and recover custom pointer styling.
 */
public class CursorService extends Service
        implements InputManager.InputDeviceListener, ShizukuEngine.StateListener {
    private static final String TAG = "CursorOpsService";
    private static final String CHANNEL_ID = "cursor_ops_status";
    private static final int NOTIF_ID = 1001;
    private static final int NEON_CYAN = 0xFF00F0FF;

    private static final int REQ_OPEN_APP = 100;
    private static final int REQ_TOGGLE = 101;
    private static final int REQ_REAPPLY = 102;
    private static final int REQ_STOP = 103;

    private static final long DELAY_DEVICE_SETTLE_MS = 350L;
    private static final long DELAY_DEVICE_CHANGE_MS = 150L;
    private static final long DELAY_ACL_FAST_MS = 350L;
    private static final long DELAY_ACL_SLOW_MS = 1000L;
    private static final long DELAY_SHIZUKU_SETTLE_MS = 400L;
    private static final long DELAY_NOTIF_DEBOUNCE_MS = 120L;
    private static final long THROTTLE_CONFIG_MS = 60L;

    private static final Object REAPPLY_TOKEN = new Object();

    private static volatile CursorService instance;
    private static volatile boolean running;
    private static volatile boolean mouseConnected;
    private static volatile int connectedMouseCount;
    private static volatile int activeMouseDeviceId = -1;
    private static volatile String connectedMouseName = "";
    private static volatile long lastApplyTimestampMs;

    private Handler mainHandler;
    private InputManager inputManager;
    private NotificationManager notificationManager;
    private boolean bluetoothReceiverRegistered;
    private boolean internalReceiverRegistered;
    private boolean shizukuListenerRegistered;
    private long lastConfigApplyUptimeMs;
    private boolean pendingThrottledReapply;
    private String pendingThrottledReason = "live_config";

    private final Runnable notifUpdateRunnable = () -> {
        if (notificationManager != null && running) {
            notificationManager.notify(NOTIF_ID, buildNotification());
        }
    };

    private final Runnable throttledReapplyRunnable = () -> {
        pendingThrottledReapply = false;
        lastConfigApplyUptimeMs = SystemClock.uptimeMillis();
        if (running) {
            reapplyCursor(pendingThrottledReason);
        }
    };

    private final BroadcastReceiver bluetoothAclReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                Log.i(TAG, "Bluetooth ACL connected; scheduling mouse scan & reapply");
                scheduleDelayedReapply("bt_acl_fast", DELAY_ACL_FAST_MS);
                scheduleDelayedReapply("bt_acl_slow", DELAY_ACL_SLOW_MS);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                Log.i(TAG, "Bluetooth ACL disconnected; scheduling mouse rescan");
                scheduleDelayedReapply("bt_acl_disconnect", DELAY_ACL_FAST_MS);
            }
        }
    };

    private final BroadcastReceiver internalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (CursorConfig.ACTION_CONFIG_CHANGED.equals(action)) {
                requestThrottledReapply("broadcast_config");
            } else if (CursorConfig.ACTION_REAPPLY_NOW.equals(action)) {
                SystemPointerController.invalidateCache();
                reapplyCursor("broadcast_reapply");
            }
        }
    };

    /** Returns the active service instance when running, or null otherwise. */
    public static CursorService getInstance() {
        return instance;
    }

    /** Returns true while CursorService is alive. */
    public static boolean isRunning() {
        return running;
    }

    /** Returns true if at least one non-virtual SOURCE_MOUSE device is currently attached. */
    public static boolean isMouseConnected() {
        return mouseConnected;
    }

    /** Returns the primary attached external mouse name, or empty string if none. */
    public static String getConnectedMouseName() {
        String name = connectedMouseName;
        return name != null ? name : "";
    }

    /** Returns the count of currently attached external mouse devices. */
    public static int getConnectedMouseCount() {
        return connectedMouseCount;
    }

    /** Returns the device ID of the primary attached mouse, or -1 if none. */
    public static int getActiveMouseDeviceId() {
        return activeMouseDeviceId;
    }

    /** Returns SystemClock.elapsedRealtime() of the last pointer style apply attempt, or 0. */
    public static long getLastApplyTimestampMs() {
        return lastApplyTimestampMs;
    }

    /** Pushes a live configuration update to the active CursorService with leading+trailing throttle. */
    public static void onConfigChangedLive() {
        CursorService svc = instance;
        if (svc == null || !running) {
            return;
        }
        Handler h = svc.mainHandler;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            svc.requestThrottledReapply("live_config");
        } else if (h != null) {
            h.post(() -> svc.requestThrottledReapply("live_config"));
        }
    }

    /** Enables the cursor daemon preference and starts CursorService in the foreground. */
    public static void start(Context context) {
        Context app = context.getApplicationContext();
        CursorConfig.setServiceEnabled(app, true);
        try {
            app.startForegroundService(new Intent(app, CursorService.class));
        } catch (Throwable t) {
            Log.e(TAG, "Unable to start CursorService in foreground", t);
        }
    }

    /** Stops the running CursorService instance. */
    public static void stop(Context context) {
        Context app = context.getApplicationContext();
        app.stopService(new Intent(app, CursorService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        running = true;
        mainHandler = new Handler(Looper.getMainLooper());
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        ShizukuEngine.init();
        createSilentChannel();
        scanMouseDevices();
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, mainHandler);
        }
        registerBluetoothAclReceiver();
        registerInternalReceiver();
        ShizukuEngine.addListener(this);
        shizukuListenerRegistered = true;

        if (ShizukuEngine.isReady()) {
            ShizukuEngine.grantSelfPrivileges(this);
        }
        reapplyCursor("on_create");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (CursorConfig.ACTION_STOP_SERVICE.equals(action)) {
            Log.i(TAG, "Stopping CursorService via explicit stop action");
            CursorConfig.setServiceEnabled(this, false);
            if (ShizukuEngine.isReady()) {
                SystemPointerController.resetDefaultIcon(this, Math.max(0, activeMouseDeviceId), null);
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (CursorConfig.ACTION_TOGGLE_SERVICE.equals(action)) {
            boolean nextEnabled = !CursorConfig.isServiceEnabled(this);
            CursorConfig.setServiceEnabled(this, nextEnabled);
            reapplyCursor("notif_toggle");
            return START_STICKY;
        }
        if (CursorConfig.ACTION_REAPPLY_NOW.equals(action)) {
            SystemPointerController.invalidateCache();
            reapplyCursor("manual_reapply");
            return START_STICKY;
        }

        if (intent != null && !CursorConfig.isServiceEnabled(this)) {
            CursorConfig.setServiceEnabled(this, true);
        }
        reapplyCursor(intent != null ? "service_start" : "sticky_restart");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mainHandler != null) {
            mainHandler.removeCallbacks(notifUpdateRunnable);
            mainHandler.removeCallbacks(throttledReapplyRunnable);
            mainHandler.removeCallbacksAndMessages(REAPPLY_TOKEN);
        }
        pendingThrottledReapply = false;
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(this);
        }
        unregisterReceivers();
        if (shizukuListenerRegistered) {
            ShizukuEngine.removeListener(this);
            shizukuListenerRegistered = false;
        }

        instance = null;
        running = false;
        mouseConnected = false;
        connectedMouseCount = 0;
        activeMouseDeviceId = -1;
        connectedMouseName = "";
        CursorConfig.notifyStateChanged(this);
        super.onDestroy();
    }

    @Override
    public void onBinderReady(boolean permissionGranted) {
        Log.i(TAG, "Shizuku binder received (uid=" + ShizukuEngine.getServerUid()
                + ", granted=" + permissionGranted + ")");
        SystemPointerController.invalidateCache();
        if (permissionGranted && ShizukuEngine.isReady()) {
            ShizukuEngine.grantSelfPrivileges(this);
            reapplyCursor("shizuku_binder_ready");
            scheduleDelayedReapply("shizuku_binder_settle", DELAY_SHIZUKU_SETTLE_MS);
        } else {
            updateNotification();
            CursorConfig.notifyStateChanged(this);
        }
    }

    @Override
    public void onBinderDead() {
        Log.w(TAG, "Shizuku binder died; waiting for auto-reconnect");
        updateNotification();
        CursorConfig.notifyStateChanged(this);
    }

    @Override
    public void onPermissionResult(boolean granted) {
        Log.i(TAG, "Shizuku permission callback in service: granted=" + granted);
        if (granted && ShizukuEngine.isReady()) {
            ShizukuEngine.grantSelfPrivileges(this);
            reapplyCursor("shizuku_permission_granted");
        } else {
            updateNotification();
            CursorConfig.notifyStateChanged(this);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        boolean wasConnected = mouseConnected;
        scanMouseDevices();
        if (mouseConnected || !wasConnected) {
            Log.i(TAG, "Input device added (id=" + deviceId + ", mouseConnected=" + mouseConnected + ")");
        }
        if (mouseConnected) {
            reapplyCursor("device_added:" + deviceId);
            scheduleDelayedReapply("device_added_settle:" + deviceId, DELAY_DEVICE_SETTLE_MS);
        } else {
            updateNotification();
            CursorConfig.notifyStateChanged(this);
        }
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        boolean prevConnected = mouseConnected;
        int prevActiveId = activeMouseDeviceId;
        scanMouseDevices();
        InputDevice changed = inputManager != null ? inputManager.getInputDevice(deviceId) : null;
        if (isExternalMouse(changed) || prevConnected != mouseConnected || prevActiveId != activeMouseDeviceId) {
            scheduleDelayedReapply("device_changed:" + deviceId, DELAY_DEVICE_CHANGE_MS);
        }
        updateNotification();
        CursorConfig.notifyStateChanged(this);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        scanMouseDevices();
        Log.i(TAG, "Input device removed (id=" + deviceId + ", remainingMice=" + connectedMouseCount + ")");
        updateNotification();
        CursorConfig.notifyStateChanged(this);
    }

    private void registerBluetoothAclReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(bluetoothAclReceiver, filter, Context.RECEIVER_EXPORTED);
        bluetoothReceiverRegistered = true;
    }

    private void registerInternalReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(CursorConfig.ACTION_CONFIG_CHANGED);
        filter.addAction(CursorConfig.ACTION_REAPPLY_NOW);
        registerReceiver(internalReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        internalReceiverRegistered = true;
    }

    private void unregisterReceivers() {
        if (bluetoothReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothAclReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            bluetoothReceiverRegistered = false;
        }
        if (internalReceiverRegistered) {
            try {
                unregisterReceiver(internalReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            internalReceiverRegistered = false;
        }
    }

    private void scheduleDelayedReapply(String reason, long delayMs) {
        if (mainHandler == null) {
            return;
        }
        mainHandler.postDelayed(() -> reapplyCursor(reason), REAPPLY_TOKEN, delayMs);
    }

    private void requestThrottledReapply(String reason) {
        if (mainHandler == null || !running) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long elapsed = now - lastConfigApplyUptimeMs;
        if (elapsed >= THROTTLE_CONFIG_MS) {
            lastConfigApplyUptimeMs = now;
            pendingThrottledReapply = false;
            mainHandler.removeCallbacks(throttledReapplyRunnable);
            reapplyCursor(reason);
            return;
        }
        pendingThrottledReason = reason;
        if (!pendingThrottledReapply) {
            pendingThrottledReapply = true;
            mainHandler.postDelayed(throttledReapplyRunnable, THROTTLE_CONFIG_MS - elapsed);
        }
    }

    /** Scans all attached input devices and updates live external mouse connection metrics. */
    private void scanMouseDevices() {
        if (inputManager == null) {
            mouseConnected = false;
            connectedMouseCount = 0;
            activeMouseDeviceId = -1;
            connectedMouseName = "";
            return;
        }
        int[] ids = inputManager.getInputDeviceIds();
        int count = 0;
        int primaryId = -1;
        String primaryName = "";

        for (int id : ids) {
            InputDevice device = inputManager.getInputDevice(id);
            if (!isExternalMouse(device)) {
                continue;
            }
            count++;
            if (primaryId == -1) {
                primaryId = id;
                CharSequence rawName = device.getName();
                primaryName = rawName != null ? rawName.toString().trim() : "";
                if (primaryName.isEmpty()) {
                    primaryName = "Mouse #" + id;
                }
            }
        }

        mouseConnected = count > 0;
        connectedMouseCount = count;
        activeMouseDeviceId = primaryId;
        connectedMouseName = primaryName;
    }

    private static boolean isExternalMouse(InputDevice device) {
        if (device == null || device.isVirtual()) {
            return false;
        }
        return device.supportsSource(InputDevice.SOURCE_MOUSE)
                || device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE);
    }

    /** Re-scans attached mice and applies or resets custom pointer styling cleanly. */
    private void reapplyCursor(String reason) {
        scanMouseDevices();
        CursorConfig.Settings cfg = CursorConfig.load(this);

        if (!cfg.serviceEnabled || cfg.overlayMode == CursorConfig.MODE_ALWAYS_OVERLAY) {
            if (ShizukuEngine.isReady()) {
                SystemPointerController.resetDefaultIcon(this, Math.max(0, activeMouseDeviceId), null);
                if (cfg.serviceEnabled && cfg.pointerSpeedSet) {
                    SystemPointerController.setPointerSpeedDirect(cfg.pointerSpeed);
                }
            }
            Log.d(TAG, "Cursor reset/skipped (" + reason + "): enabled=" + cfg.serviceEnabled
                    + ", overlayMode=" + cfg.overlayMode);
        } else if (ShizukuEngine.isReady()) {
            SystemPointerController.ApplyResult result = SystemPointerController.applyStyle(
                    cfg.cursorStyle,
                    cfg.cursorScale,
                    cfg.cursorColor,
                    Math.max(0, activeMouseDeviceId),
                    null);
            if (cfg.pointerSpeedSet) {
                SystemPointerController.setPointerSpeedDirect(cfg.pointerSpeed);
            }
            lastApplyTimestampMs = SystemClock.elapsedRealtime();
            Log.i(TAG, "Reapplied cursor (" + reason + "): style="
                    + SystemPointerController.styleLabel(cfg.cursorStyle)
                    + ", color=" + CursorConfig.colorLabel(cfg.cursorColor)
                    + ", scale=" + cfg.cursorScale
                    + ", speed=" + cfg.pointerSpeed
                    + ", mouseConnected=" + mouseConnected
                    + ", result=" + result);
        } else {
            Log.d(TAG, "Shizuku not ready during reapply (" + reason + ")");
        }

        updateNotification();
        CursorConfig.notifyStateChanged(this);
    }

    private void createSilentChannel() {
        if (notificationManager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.service_label),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Silent background status for CursorOps Bluetooth mouse engine");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        notificationManager.createNotificationChannel(channel);
    }

    private void updateNotification() {
        if (mainHandler == null || notificationManager == null || !running) {
            return;
        }
        mainHandler.removeCallbacks(notifUpdateRunnable);
        mainHandler.postDelayed(notifUpdateRunnable, DELAY_NOTIF_DEBOUNCE_MS);
    }

    private Notification buildNotification() {
        CursorConfig.Settings cfg = CursorConfig.load(this);

        String titleState = cfg.serviceEnabled ? "Active" : "Paused";
        String title = getString(R.string.app_name) + " \u2022 " + titleState;

        String shizukuStatus;
        if (ShizukuEngine.isReady()) {
            shizukuStatus = "Shizuku UID " + ShizukuEngine.getServerUid();
        } else if (ShizukuEngine.isBinderAlive()) {
            shizukuStatus = "Shizuku perm needed";
        } else {
            shizukuStatus = "Shizuku offline";
        }

        String mouseStatus = mouseConnected
                ? connectedMouseName + (connectedMouseCount > 1 ? " (+" + (connectedMouseCount - 1) + ")" : "")
                : "Waiting for Bluetooth mouse";

        String engineLabel = SystemPointerController.describeActiveEngine(
                cfg.serviceEnabled, cfg.overlayMode, CursorAccessibilityService.isOverlayActive());
        String summary = mouseStatus + " \u2022 " + shizukuStatus + " \u2022 " + engineLabel;

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cursor_notif)
                .setColor(NEON_CYAN)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(new Notification.BigTextStyle().bigText(
                        summary + "\nStyle: "
                                + SystemPointerController.styleLabel(cfg.cursorStyle)
                                + " \u2022 "
                                + CursorConfig.colorLabel(cfg.cursorColor)
                                + String.format(java.util.Locale.US, " (%.2fx)", cfg.cursorScale)))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);

        PendingIntent openPending = buildLaunchPendingIntent();
        if (openPending != null) {
            builder.setContentIntent(openPending);
        }

        String toggleLabel = cfg.serviceEnabled ? "Pause" : "Resume";
        builder.addAction(buildServiceAction(toggleLabel, CursorConfig.ACTION_TOGGLE_SERVICE, REQ_TOGGLE));
        builder.addAction(buildServiceAction("Re-apply", CursorConfig.ACTION_REAPPLY_NOW, REQ_REAPPLY));
        builder.addAction(buildServiceAction("Stop", CursorConfig.ACTION_STOP_SERVICE, REQ_STOP));

        return builder.build();
    }

    private PendingIntent buildLaunchPendingIntent() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) {
            return null;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                this,
                REQ_OPEN_APP,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification.Action buildServiceAction(String label, String action, int requestCode) {
        Intent intent = new Intent(this, CursorService.class);
        intent.setAction(action);
        PendingIntent pi = PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_cursor_notif),
                label,
                pi).build();
    }
}
