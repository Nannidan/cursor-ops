package com.nannidan.cursorops;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lightweight AccessibilityService that attaches a non-touchable vector cursor overlay,
 * renders click ripples, forwards primary clicks/scrolls, and maps right/middle clicks
 * and mouse key events to Back, Home, and Recents global actions.
 */
public class CursorAccessibilityService extends AccessibilityService
        implements InputManager.InputDeviceListener {
    private static final String TAG = "CursorOpsA11y";

    private static final long MIDDLE_LONG_PRESS_MS = 420L;
    private static final long MIN_GESTURE_MS = 24L;
    private static final long MAX_DRAG_GESTURE_MS = 300L;
    private static final long LONG_PRESS_THRESHOLD_MS = 450L;
    private static final long MAX_LONG_PRESS_MS = 800L;
    private static final long SCROLL_GESTURE_MS = 140L;
    private static final float DRAG_DISTANCE_SQ_THRESHOLD = 36f;
    private static final float SCROLL_STEP_DP = 72f;
    private static final float SCROLL_SCREEN_MARGIN_PX = 12f;

    private static volatile CursorAccessibilityService instance;
    private static volatile boolean running;
    private static volatile boolean overlayActive;

    private Handler mainHandler;
    private WindowManager windowManager;
    private InputManager inputManager;
    private CursorOverlayView overlayView;
    private boolean internalReceiverRegistered;

    private int screenWidth = 1080;
    private int screenHeight = 2400;
    private float cursorX = 540f;
    private float cursorY = 1200f;

    private int lastButtonState;
    private boolean primaryHeld;
    private boolean primaryInjectedViaShizuku;
    private long primaryDownTime;
    private float primaryDownX;
    private float primaryDownY;

    private boolean middleHeld;
    private boolean middleLongPressConsumed;
    private boolean scrollGestureInFlight;
    private float pendingScrollV;
    private float pendingScrollH;

    private final Runnable middleLongPressRunnable = () -> {
        if (!middleHeld || middleLongPressConsumed) {
            return;
        }
        middleLongPressConsumed = true;
        if (overlayView != null) {
            overlayView.spawnRipple(cursorX, cursorY, CursorOverlayView.COLOR_GREEN);
        }
        performGlobalAction(GLOBAL_ACTION_RECENTS);
    };

    private final BroadcastReceiver internalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            String action = intent.getAction();
            if (CursorConfig.ACTION_CONFIG_CHANGED.equals(action)
                    || CursorConfig.ACTION_STATE_CHANGED.equals(action)
                    || CursorConfig.ACTION_REAPPLY_NOW.equals(action)) {
                syncServiceMode("broadcast:" + action);
            }
        }
    };

    /** Returns the active accessibility service instance, or null if not bound. */
    public static CursorAccessibilityService getInstance() {
        return instance;
    }

    /** Returns true while CursorAccessibilityService is bound and running. */
    public static boolean isRunning() {
        return running;
    }

    /** Returns true when SOURCE_MOUSE interception and custom overlay rendering are active. */
    public static boolean isOverlayActive() {
        return overlayActive;
    }

    /** Immediately pushes live configuration updates to the active accessibility overlay. */
    public static void onConfigChangedLive() {
        CursorAccessibilityService svc = instance;
        if (svc == null || !running) {
            return;
        }
        Handler h = svc.mainHandler;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            svc.syncServiceMode("live_push");
        } else if (h != null) {
            h.post(() -> svc.syncServiceMode("live_push"));
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        running = true;

        mainHandler = new Handler(Looper.getMainLooper());
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);

        ShizukuEngine.init();
        refreshScreenBounds();
        cursorX = screenWidth * 0.5f;
        cursorY = screenHeight * 0.5f;

        overlayView = new CursorOverlayView(this);
        overlayView.attach(windowManager);
        overlayView.updateCursorPosition(cursorX, cursorY);

        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, mainHandler);
        }
        registerInternalReceiver();
        syncServiceMode("service_connected");
        CursorConfig.notifyStateChanged(this);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshScreenBounds();
        cursorX = clampCoord(cursorX, screenWidth);
        cursorY = clampCoord(cursorY, screenHeight);
        if (overlayView != null) {
            overlayView.updateCursorPosition(cursorX, cursorY);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Deliberately no-op; eventTypes is kept at 0 for minimal overhead.
    }

    @Override
    public void onInterrupt() {
        cancelHeldButtonState();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        cleanup();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        syncServiceMode("device_added:" + deviceId);
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        syncServiceMode("device_changed:" + deviceId);
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        syncServiceMode("device_removed:" + deviceId);
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        if (event == null || event.getDeviceId() <= 0 || !running || !overlayActive) {
            return;
        }
        if (screenWidth <= 1 || screenHeight <= 1) {
            refreshScreenBounds();
        }

        updateCursorCoordinates(event);
        if (overlayView != null) {
            overlayView.updateCursorPosition(cursorX, cursorY);
        }

        CursorConfig.Settings cfg = CursorConfig.load(this);
        int action = event.getActionMasked();
        int buttonState = (action == MotionEvent.ACTION_CANCEL) ? 0 : event.getButtonState();

        handlePrimaryButton(action, buttonState, cfg);
        handleSecondaryButton(buttonState, cfg);
        handleTertiaryButton(action, buttonState, cfg);

        lastButtonState = buttonState;

        if (action == MotionEvent.ACTION_SCROLL) {
            float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            float hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (Math.abs(vScroll) > 0.01f || Math.abs(hScroll) > 0.01f) {
                enqueueScrollGesture(vScroll, hScroll);
            }
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null || !running || overlayActive) {
            return false;
        }
        CursorConfig.Settings cfg = CursorConfig.load(this);
        if (!cfg.serviceEnabled || !isExternalMouseDeviceId(event.getDeviceId())) {
            return false;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && cfg.rightClickAction != CursorConfig.SHORTCUT_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                performMappedShortcut(cfg.rightClickAction);
            }
            return true;
        }
        return false;
    }

    private void updateCursorCoordinates(MotionEvent event) {
        boolean isRelativeSource = (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE)
                == InputDevice.SOURCE_MOUSE_RELATIVE;
        float relX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
        float relY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
        float absX = event.getRawX();
        float absY = event.getRawY();

        if (isRelativeSource || (absX == 0f && absY == 0f && (relX != 0f || relY != 0f))) {
            cursorX = clampCoord(cursorX + relX, screenWidth);
            cursorY = clampCoord(cursorY + relY, screenHeight);
        } else {
            cursorX = clampCoord(absX, screenWidth);
            cursorY = clampCoord(absY, screenHeight);
        }
    }

    private void handlePrimaryButton(int action, int buttonState, CursorConfig.Settings cfg) {
        boolean primaryNow = (buttonState & MotionEvent.BUTTON_PRIMARY) != 0;

        if (!primaryHeld && primaryNow) {
            primaryHeld = true;
            primaryDownTime = SystemClock.uptimeMillis();
            primaryDownX = cursorX;
            primaryDownY = cursorY;
            if (overlayView != null) {
                overlayView.spawnRipple(
                        cursorX, cursorY, CursorConfig.resolvePrimaryColor(cfg.cursorColor));
            }
            primaryInjectedViaShizuku = SystemPointerController.injectTouchEvent(
                    MotionEvent.ACTION_DOWN, cursorX, cursorY, primaryDownTime, Display.DEFAULT_DISPLAY);
            return;
        }

        if (primaryHeld && primaryNow) {
            if (primaryInjectedViaShizuku
                    && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE)) {
                SystemPointerController.injectTouchEvent(
                        MotionEvent.ACTION_MOVE, cursorX, cursorY, primaryDownTime, Display.DEFAULT_DISPLAY);
            }
            return;
        }

        if (primaryHeld && (!primaryNow || action == MotionEvent.ACTION_CANCEL)) {
            primaryHeld = false;
            boolean cancelled = (action == MotionEvent.ACTION_CANCEL);
            if (primaryInjectedViaShizuku) {
                SystemPointerController.injectTouchEvent(
                        cancelled ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP,
                        cursorX, cursorY, primaryDownTime, Display.DEFAULT_DISPLAY);
                primaryInjectedViaShizuku = false;
            } else if (!cancelled) {
                long durationMs = SystemClock.uptimeMillis() - primaryDownTime;
                dispatchTapOrDragGesture(primaryDownX, primaryDownY, cursorX, cursorY, durationMs);
            }
        }
    }

    private void handleSecondaryButton(int buttonState, CursorConfig.Settings cfg) {
        boolean secondaryNow = (buttonState & MotionEvent.BUTTON_SECONDARY) != 0;
        boolean secondaryPrev = (lastButtonState & MotionEvent.BUTTON_SECONDARY) != 0;
        if (secondaryNow && !secondaryPrev) {
            if (overlayView != null) {
                overlayView.spawnRipple(cursorX, cursorY, CursorOverlayView.COLOR_MAGENTA);
            }
            performMappedShortcut(cfg.rightClickAction);
        }
    }

    private void handleTertiaryButton(int action, int buttonState, CursorConfig.Settings cfg) {
        if (cfg.middleClickAction == CursorConfig.SHORTCUT_NONE) {
            if (mainHandler != null) {
                mainHandler.removeCallbacks(middleLongPressRunnable);
            }
            middleHeld = false;
            middleLongPressConsumed = false;
            return;
        }
        boolean middleNow = (buttonState & MotionEvent.BUTTON_TERTIARY) != 0;

        if (!middleHeld && middleNow) {
            middleHeld = true;
            middleLongPressConsumed = false;
            if (mainHandler != null) {
                mainHandler.removeCallbacks(middleLongPressRunnable);
                mainHandler.postDelayed(middleLongPressRunnable, MIDDLE_LONG_PRESS_MS);
            }
            return;
        }

        if (middleHeld && (!middleNow || action == MotionEvent.ACTION_CANCEL)) {
            middleHeld = false;
            if (mainHandler != null) {
                mainHandler.removeCallbacks(middleLongPressRunnable);
            }
            if (!middleLongPressConsumed && action != MotionEvent.ACTION_CANCEL) {
                if (overlayView != null) {
                    overlayView.spawnRipple(cursorX, cursorY, CursorOverlayView.COLOR_GREEN);
                }
                performMappedShortcut(cfg.middleClickAction);
            }
            middleLongPressConsumed = false;
        }
    }

    private void performMappedShortcut(int shortcut) {
        switch (shortcut) {
            case CursorConfig.SHORTCUT_BACK:
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case CursorConfig.SHORTCUT_HOME:
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case CursorConfig.SHORTCUT_RECENTS:
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case CursorConfig.SHORTCUT_NONE:
            default:
                break;
        }
    }

    private void dispatchTapOrDragGesture(
            float startX, float startY, float endX, float endY, long heldDurationMs) {
        float sx = clampCoord(startX, screenWidth);
        float sy = clampCoord(startY, screenHeight);
        float ex = clampCoord(endX, screenWidth);
        float ey = clampCoord(endY, screenHeight);

        Path path = new Path();
        path.moveTo(sx, sy);
        float dx = ex - sx;
        float dy = ey - sy;
        boolean isDrag = (dx * dx + dy * dy) >= DRAG_DISTANCE_SQ_THRESHOLD;
        if (isDrag) {
            path.lineTo(ex, ey);
        }

        long duration;
        if (isDrag) {
            duration = Math.max(MIN_GESTURE_MS, Math.min(MAX_DRAG_GESTURE_MS, heldDurationMs));
        } else if (heldDurationMs >= LONG_PRESS_THRESHOLD_MS) {
            duration = Math.min(MAX_LONG_PRESS_MS, heldDurationMs);
        } else {
            duration = MIN_GESTURE_MS;
        }
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, duration))
                .build();
        dispatchGesture(gesture, null, null);
    }

    private void enqueueScrollGesture(float vScroll, float hScroll) {
        pendingScrollV = Math.max(-6f, Math.min(6f, pendingScrollV + vScroll));
        pendingScrollH = Math.max(-6f, Math.min(6f, pendingScrollH + hScroll));
        drainPendingScroll();
    }

    private void drainPendingScroll() {
        if (scrollGestureInFlight || !running || !overlayActive) {
            return;
        }
        if (Math.abs(pendingScrollV) <= 0.01f && Math.abs(pendingScrollH) <= 0.01f) {
            pendingScrollV = 0f;
            pendingScrollH = 0f;
            return;
        }

        float v = Math.max(-3f, Math.min(3f, pendingScrollV));
        float h = Math.max(-3f, Math.min(3f, pendingScrollH));
        pendingScrollV -= v;
        pendingScrollH -= h;

        float stepPx = SCROLL_STEP_DP * getResources().getDisplayMetrics().density;
        float dx = -h * stepPx;
        float dy = v * stepPx;
        float margin = SCROLL_SCREEN_MARGIN_PX;
        float minX = margin;
        float maxX = Math.max(minX + 1f, screenWidth - margin);
        float minY = margin;
        float maxY = Math.max(minY + 1f, screenHeight - margin);

        float sx = Math.max(minX, Math.min(maxX, cursorX));
        float sy = Math.max(minY, Math.min(maxY, cursorY));
        if (sx + dx < minX) {
            sx = Math.min(maxX, minX - dx);
        } else if (sx + dx > maxX) {
            sx = Math.max(minX, maxX - dx);
        }
        if (sy + dy < minY) {
            sy = Math.min(maxY, minY - dy);
        } else if (sy + dy > maxY) {
            sy = Math.max(minY, maxY - dy);
        }

        float ex = Math.max(minX, Math.min(maxX, sx + dx));
        float ey = Math.max(minY, Math.min(maxY, sy + dy));
        if (Math.abs(ex - sx) < 2f && Math.abs(ey - sy) < 2f) {
            return;
        }

        Path path = new Path();
        path.moveTo(sx, sy);
        path.lineTo(ex, ey);

        scrollGestureInFlight = true;
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, SCROLL_GESTURE_MS))
                .build();
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                scrollGestureInFlight = false;
                drainPendingScroll();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                scrollGestureInFlight = false;
                pendingScrollV = 0f;
                pendingScrollH = 0f;
            }
        }, mainHandler);
        if (!dispatched) {
            scrollGestureInFlight = false;
            pendingScrollV = 0f;
            pendingScrollH = 0f;
        }
    }

    private void syncServiceMode(String reason) {
        if (!running) {
            return;
        }
        boolean hasExternalMouse = hasAttachedExternalMouse();
        CursorConfig.Settings cfg = CursorConfig.load(this);

        if (overlayView != null) {
            overlayView.applyConfig(
                    cfg.cursorStyle,
                    cfg.cursorColor,
                    cfg.cursorScale,
                    cfg.clickRippleEnabled,
                    cfg.trailEnabled);
        }

        boolean nativeCustomIconActive = ShizukuEngine.isReady()
                && cfg.overlayMode != CursorConfig.MODE_ALWAYS_OVERLAY
                && SystemPointerController.getLastApplyResult()
                        == SystemPointerController.ApplyResult.APPLIED_CUSTOM_ICON;
        boolean needOverlayFeatures = cfg.clickRippleEnabled
                || cfg.trailEnabled
                || cfg.rightClickAction != CursorConfig.SHORTCUT_NONE
                || cfg.middleClickAction != CursorConfig.SHORTCUT_NONE;
        boolean wantInterceptMouse = cfg.serviceEnabled
                && hasExternalMouse
                && cfg.overlayMode != CursorConfig.MODE_NATIVE_ONLY
                && (!nativeCustomIconActive || needOverlayFeatures);

        if (wantInterceptMouse && overlayView != null && !overlayView.isAttached()) {
            overlayView.attach(windowManager);
        }

        boolean interceptMouse = wantInterceptMouse
                && overlayView != null
                && overlayView.isAttached();

        if (interceptMouse && !overlayActive) {
            refreshScreenBounds();
            cursorX = clampCoord(cursorX, screenWidth);
            cursorY = clampCoord(cursorY, screenHeight);
            overlayView.updateCursorPosition(cursorX, cursorY);
        }

        updateMotionEventInterception(interceptMouse ? InputDevice.SOURCE_MOUSE : 0);

        boolean changed = (overlayActive != interceptMouse);
        overlayActive = interceptMouse;
        if (overlayView != null) {
            overlayView.setCursorVisible(interceptMouse);
        }
        if (!interceptMouse) {
            cancelHeldButtonState();
        }
        if (changed) {
            Log.i(TAG, "Overlay mode updated (" + reason + "): overlayActive=" + overlayActive
                    + ", hasMouse=" + hasExternalMouse + ", mode=" + cfg.overlayMode);
            CursorConfig.notifyStateChanged(this);
        }
    }

    private void updateMotionEventInterception(int desiredSources) {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) {
                return;
            }
            int desiredFlags = info.flags | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            if (info.getMotionEventSources() != desiredSources
                    || info.eventTypes != 0
                    || info.flags != desiredFlags) {
                info.eventTypes = 0;
                info.flags = desiredFlags;
                info.setMotionEventSources(desiredSources);
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to update AccessibilityServiceInfo motion sources", t);
        }
    }

    private void cancelHeldButtonState() {
        if (mainHandler != null) {
            mainHandler.removeCallbacks(middleLongPressRunnable);
        }
        if (primaryHeld && primaryInjectedViaShizuku) {
            SystemPointerController.injectTouchEvent(
                    MotionEvent.ACTION_CANCEL, cursorX, cursorY, primaryDownTime, Display.DEFAULT_DISPLAY);
        }
        primaryHeld = false;
        primaryInjectedViaShizuku = false;
        middleHeld = false;
        middleLongPressConsumed = false;
        lastButtonState = 0;
        pendingScrollV = 0f;
        pendingScrollH = 0f;
    }

    private void refreshScreenBounds() {
        if (windowManager == null) {
            return;
        }
        try {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            if (bounds.width() > 0 && bounds.height() > 0) {
                screenWidth = bounds.width();
                screenHeight = bounds.height();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read WindowMetrics bounds", t);
        }
    }

    private boolean hasAttachedExternalMouse() {
        if (inputManager == null) {
            return CursorService.isMouseConnected();
        }
        int[] ids = inputManager.getInputDeviceIds();
        for (int id : ids) {
            if (isExternalMouseDevice(inputManager.getInputDevice(id))) {
                return true;
            }
        }
        return false;
    }

    private boolean isExternalMouseDeviceId(int deviceId) {
        if (deviceId <= 0 || inputManager == null) {
            return false;
        }
        return isExternalMouseDevice(inputManager.getInputDevice(deviceId));
    }

    private static boolean isExternalMouseDevice(InputDevice device) {
        if (device == null || device.isVirtual()) {
            return false;
        }
        return device.supportsSource(InputDevice.SOURCE_MOUSE)
                || device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE);
    }

    private static float clampCoord(float value, int maxExtent) {
        float limit = Math.max(1f, (float) (maxExtent - 1));
        return Math.max(0f, Math.min(limit, value));
    }

    private void registerInternalReceiver() {
        if (internalReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(CursorConfig.ACTION_CONFIG_CHANGED);
        filter.addAction(CursorConfig.ACTION_STATE_CHANGED);
        filter.addAction(CursorConfig.ACTION_REAPPLY_NOW);
        registerReceiver(internalReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        internalReceiverRegistered = true;
    }

    private void cleanup() {
        if (!running && instance == null) {
            return;
        }
        cancelHeldButtonState();
        updateMotionEventInterception(0);
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        if (inputManager != null) {
            inputManager.unregisterInputDeviceListener(this);
        }
        if (internalReceiverRegistered) {
            try {
                unregisterReceiver(internalReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            internalReceiverRegistered = false;
        }
        if (overlayView != null) {
            overlayView.detach(windowManager);
            overlayView = null;
        }
        running = false;
        overlayActive = false;
        instance = null;
        CursorConfig.notifyStateChanged(this);
    }
}