package com.nannidan.cursorops;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates high-contrast neon cursor bitmaps and drives hidden IInputManager APIs over Shizuku
 * with automatic signature probing and clean fallback when hidden pointer APIs shift.
 */
public final class SystemPointerController {
    private static final String TAG = "CursorOpsPointer";

    public static final int STYLE_NEON_ARROW = 0;
    public static final int STYLE_CYBER_CROSSHAIR = 1;
    public static final int STYLE_HOLO_RING = 2;
    public static final int STYLE_PRECISION_DOT = 3;

    public static final float MIN_SCALE = 0.50f;
    public static final float MAX_SCALE = 2.50f;
    public static final int MIN_POINTER_SPEED = -7;
    public static final int MAX_POINTER_SPEED = 7;

    private static final float BASE_BITMAP_DP = 28f;

    private static final int COLOR_BLACK = 0xFF000000;
    private static final int COLOR_CYAN = 0xFF00F0FF;
    private static final int COLOR_MAGENTA = 0xFFFF00AA;
    private static final int COLOR_GREEN = 0xFF39FF14;

    private static final int INJECT_MODE_ASYNC = 0;
    private static final int INVALID_UID = -1;

    /** Result of attempting to push a custom cursor icon through IInputManager. */
    public enum ApplyResult {
        APPLIED_CUSTOM_ICON,
        APPLIED_SCOPED_ICON,
        FALLBACK_OVERLAY_REQUIRED,
        SHIZUKU_NOT_READY,
        ERROR
    }

    /** Active pointer engine mode discovered by probing the device's IInputManager AIDL stub. */
    public enum EngineMode {
        UNPROBED,
        LEGACY_CUSTOM_ICON,
        MODERN_SCOPED_ICON,
        OVERLAY_FALLBACK
    }

    private static final Object LOCK = new Object();

    private static volatile Object cachedInputManager;
    private static volatile boolean methodsProbed;
    private static volatile Method customPointerIconMethod;
    private static volatile Method scopedPointerIconMethod;
    private static volatile Method tryPointerSpeedMethod;
    private static volatile Method injectInputEventMethod;
    private static volatile Method injectInputEventToTargetMethod;
    private static volatile Method motionEventSetDisplayIdMethod;
    private static volatile boolean motionSetDisplayIdChecked;

    private static volatile EngineMode lastEngineMode = EngineMode.UNPROBED;
    private static volatile ApplyResult lastApplyResult = ApplyResult.SHIZUKU_NOT_READY;

    private SystemPointerController() {}

    /** Returns the human-readable name for a cursor style ID. */
    public static String styleLabel(int style) {
        switch (style) {
            case STYLE_CYBER_CROSSHAIR:
                return "Crosshair";
            case STYLE_HOLO_RING:
                return "Neon Ring";
            case STYLE_PRECISION_DOT:
                return "Precision Dot";
            case STYLE_NEON_ARROW:
            default:
                return "Arrow";
        }
    }

    /** Returns normalized [hotX, hotY] fractions in [0..1] for the given cursor style. */
    public static float[] getHotspotFraction(int style) {
        if (style == STYLE_NEON_ARROW) {
            return new float[]{0.16f, 0.12f};
        }
        return new float[]{0.50f, 0.50f};
    }

    /** Clamps cursor scale to supported [0.50..2.50] bounds. */
    public static float clampScale(float scale) {
        if (Float.isNaN(scale)) {
            return 1.0f;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /** Clamps system pointer speed to [-7..+7]. */
    public static int clampPointerSpeed(int speed) {
        return Math.max(MIN_POINTER_SPEED, Math.min(MAX_POINTER_SPEED, speed));
    }

    /** Returns the most recently detected pointer engine mode. */
    public static EngineMode getLastEngineMode() {
        return lastEngineMode;
    }

    /** Returns the most recent icon application result. */
    public static ApplyResult getLastApplyResult() {
        return lastApplyResult;
    }

    /** Summarizes the live pointer rendering engine state for notification and UI display. */
    public static String describeActiveEngine(
            boolean serviceEnabled, int overlayMode, boolean a11yOverlayActive) {
        if (!serviceEnabled) {
            return "Paused";
        }
        if (overlayMode == CursorConfig.MODE_ALWAYS_OVERLAY) {
            return a11yOverlayActive ? "AMOLED Overlay" : "Overlay Forced";
        }
        if (a11yOverlayActive) {
            return "AMOLED Overlay";
        }
        if (!ShizukuEngine.isReady()) {
            return "Standby";
        }
        EngineMode mode = getLastEngineMode();
        if (mode == EngineMode.LEGACY_CUSTOM_ICON
                || getLastApplyResult() == ApplyResult.APPLIED_CUSTOM_ICON) {
            return "Native Icon";
        }
        if (mode == EngineMode.MODERN_SCOPED_ICON
                || getLastApplyResult() == ApplyResult.APPLIED_SCOPED_ICON) {
            return "Scoped Icon";
        }
        if (overlayMode == CursorConfig.MODE_NATIVE_ONLY) {
            return "Native Only";
        }
        switch (getLastApplyResult()) {
            case SHIZUKU_NOT_READY:
                return "Standby";
            case ERROR:
                return "Recovering";
            case FALLBACK_OVERLAY_REQUIRED:
            default:
                return mode == EngineMode.OVERLAY_FALLBACK ? "AMOLED Overlay" : "Standby";
        }
    }

    /** Clears cached IInputManager binder proxies and reflected methods after binder death. */
    public static void invalidateCache() {
        synchronized (LOCK) {
            cachedInputManager = null;
            methodsProbed = false;
            customPointerIconMethod = null;
            scopedPointerIconMethod = null;
            tryPointerSpeedMethod = null;
            injectInputEventMethod = null;
            injectInputEventToTargetMethod = null;
            lastEngineMode = EngineMode.UNPROBED;
            lastApplyResult = ApplyResult.SHIZUKU_NOT_READY;
        }
    }

    /** Builds a high-contrast neon cursor Bitmap at 28dp-equivalent density base multiplied by scale. */
    public static Bitmap buildCursorBitmap(int style, float scale) {
        return buildCursorBitmap(style, scale, CursorConfig.COLOR_CYAN);
    }

    /** Builds a high-contrast neon cursor Bitmap with the chosen style, scale, and color preset. */
    public static Bitmap buildCursorBitmap(int style, float scale, int colorPreset) {
        float density = 2.75f;
        try {
            density = Math.max(1.0f, Resources.getSystem().getDisplayMetrics().density);
        } catch (Throwable ignored) {
        }
        float safeScale = clampScale(scale);
        int size = Math.max(24, Math.min(256, Math.round(BASE_BITMAP_DP * density * safeScale)));
        float strokeScale = size / 48f;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int primaryColor = CursorConfig.resolvePrimaryColor(colorPreset);
        int accentColor = CursorConfig.resolveAccentColor(colorPreset);
        drawCursorToCanvas(canvas, style, size, strokeScale, primaryColor, accentColor);
        return bitmap;
    }

    private static void drawCursorToCanvas(
            Canvas canvas, int style, int size, float scale, int primaryColor, int accentColor) {
        Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        switch (style) {
            case STYLE_CYBER_CROSSHAIR:
                drawCrosshair(canvas, size, scale, strokePaint, fillPaint, primaryColor, accentColor);
                break;
            case STYLE_HOLO_RING:
                drawHoloRing(canvas, size, scale, strokePaint, fillPaint, primaryColor, accentColor);
                break;
            case STYLE_PRECISION_DOT:
                drawPrecisionDot(canvas, size, scale, strokePaint, fillPaint, primaryColor, accentColor);
                break;
            case STYLE_NEON_ARROW:
            default:
                drawNeonArrow(canvas, size, scale, strokePaint, fillPaint, primaryColor, accentColor);
                break;
        }
    }

    private static void drawNeonArrow(
            Canvas canvas,
            int size,
            float scale,
            Paint strokePaint,
            Paint fillPaint,
            int primaryColor,
            int accentColor) {
        Path arrow = new Path();
        arrow.moveTo(size * 0.16f, size * 0.12f);
        arrow.lineTo(size * 0.16f, size * 0.84f);
        arrow.lineTo(size * 0.36f, size * 0.64f);
        arrow.lineTo(size * 0.54f, size * 0.90f);
        arrow.lineTo(size * 0.66f, size * 0.84f);
        arrow.lineTo(size * 0.48f, size * 0.58f);
        arrow.lineTo(size * 0.78f, size * 0.58f);
        arrow.close();

        strokePaint.setColor(COLOR_BLACK);
        strokePaint.setStrokeWidth(Math.max(2.6f, 3.2f * scale));
        canvas.drawPath(arrow, strokePaint);

        fillPaint.setColor(primaryColor);
        canvas.drawPath(arrow, fillPaint);

        strokePaint.setColor(accentColor);
        strokePaint.setStrokeWidth(Math.max(1.2f, 1.4f * scale));
        canvas.drawPath(arrow, strokePaint);
    }

    private static void drawCrosshair(
            Canvas canvas,
            int size,
            float scale,
            Paint strokePaint,
            Paint fillPaint,
            int primaryColor,
            int accentColor) {
        float c = size * 0.50f;
        float gap = size * 0.14f;
        float arm = size * 0.40f;

        strokePaint.setColor(COLOR_BLACK);
        strokePaint.setStrokeWidth(Math.max(3.2f, 4.4f * scale));
        drawCrosshairArms(canvas, c, gap, arm, strokePaint);

        strokePaint.setColor(primaryColor);
        strokePaint.setStrokeWidth(Math.max(1.6f, 2.2f * scale));
        drawCrosshairArms(canvas, c, gap, arm, strokePaint);

        fillPaint.setColor(COLOR_BLACK);
        canvas.drawCircle(c, c, Math.max(2.6f, 3.4f * scale), fillPaint);
        fillPaint.setColor(accentColor);
        canvas.drawCircle(c, c, Math.max(1.6f, 2.0f * scale), fillPaint);
    }

    private static void drawCrosshairArms(
            Canvas canvas, float c, float gap, float arm, Paint paint) {
        canvas.drawLine(c - arm, c, c - gap, c, paint);
        canvas.drawLine(c + gap, c, c + arm, c, paint);
        canvas.drawLine(c, c - arm, c, c - gap, paint);
        canvas.drawLine(c, c + gap, c, c + arm, paint);
    }

    private static void drawHoloRing(
            Canvas canvas,
            int size,
            float scale,
            Paint strokePaint,
            Paint fillPaint,
            int primaryColor,
            int accentColor) {
        float c = size * 0.50f;
        float rOuter = size * 0.34f;
        float rInner = size * 0.22f;

        strokePaint.setColor(COLOR_BLACK);
        strokePaint.setStrokeWidth(Math.max(3.2f, 4.2f * scale));
        canvas.drawCircle(c, c, rOuter, strokePaint);

        strokePaint.setColor(primaryColor);
        strokePaint.setStrokeWidth(Math.max(1.8f, 2.4f * scale));
        canvas.drawCircle(c, c, rOuter, strokePaint);

        strokePaint.setColor(accentColor);
        strokePaint.setStrokeWidth(Math.max(1.2f, 1.6f * scale));
        canvas.drawCircle(c, c, rInner, strokePaint);

        fillPaint.setColor(primaryColor);
        canvas.drawCircle(c, c, Math.max(1.8f, 2.2f * scale), fillPaint);
    }

    private static void drawPrecisionDot(
            Canvas canvas,
            int size,
            float scale,
            Paint strokePaint,
            Paint fillPaint,
            int primaryColor,
            int accentColor) {
        float c = size * 0.50f;
        float r = size * 0.16f;

        fillPaint.setColor(COLOR_BLACK);
        canvas.drawCircle(c, c, r + Math.max(1.8f, 2.2f * scale), fillPaint);

        fillPaint.setColor(primaryColor);
        canvas.drawCircle(c, c, r, fillPaint);

        strokePaint.setColor(accentColor);
        strokePaint.setStrokeWidth(Math.max(1.2f, 1.6f * scale));
        canvas.drawCircle(c, c, r + Math.max(2.8f, 3.6f * scale), strokePaint);
    }

    /** Convenience overload applying a cursor style globally when supported by the system API. */
    public static ApplyResult applyStyle(int style, float scale) {
        return applyStyle(style, scale, CursorConfig.COLOR_CYAN, 0, null);
    }

    /** Applies a cursor style using the default neon cyan preset. */
    public static ApplyResult applyStyle(int style, float scale, int deviceId, IBinder inputToken) {
        return applyStyle(style, scale, CursorConfig.COLOR_CYAN, deviceId, inputToken);
    }

    /**
     * Attempts to apply a custom PointerIcon via Shizuku IInputManager reflection, cleanly
     * falling back to overlay signaling when Android 15-17 requires a window inputToken.
     */
    public static ApplyResult applyStyle(
            int style, float scale, int colorPreset, int deviceId, IBinder inputToken) {
        if (!ShizukuEngine.isReady()) {
            lastApplyResult = ApplyResult.SHIZUKU_NOT_READY;
            return lastApplyResult;
        }
        if (!ensureInputManagerProxy()) {
            lastEngineMode = EngineMode.OVERLAY_FALLBACK;
            lastApplyResult = ApplyResult.FALLBACK_OVERLAY_REQUIRED;
            return lastApplyResult;
        }

        try {
            Bitmap bitmap = buildCursorBitmap(style, scale, colorPreset);
            float[] hot = getHotspotFraction(style);
            PointerIcon icon = PointerIcon.create(
                    bitmap, bitmap.getWidth() * hot[0], bitmap.getHeight() * hot[1]);

            return applyPointerIconInternal(icon, deviceId, inputToken);
        } catch (Throwable t) {
            Log.w(TAG, "PointerIcon creation/apply failed; falling back to overlay", t);
            lastEngineMode = EngineMode.OVERLAY_FALLBACK;
            lastApplyResult = ApplyResult.FALLBACK_OVERLAY_REQUIRED;
            return lastApplyResult;
        }
    }

    /** Restores the system arrow PointerIcon for the target device/token when supported. */
    public static ApplyResult resetDefaultIcon(Context context, int deviceId, IBinder inputToken) {
        if (!ShizukuEngine.isReady()) {
            return ApplyResult.SHIZUKU_NOT_READY;
        }
        if (context == null || !ensureInputManagerProxy()) {
            return ApplyResult.FALLBACK_OVERLAY_REQUIRED;
        }
        try {
            PointerIcon defaultIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW);
            return applyPointerIconInternal(defaultIcon, deviceId, inputToken);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to restore default PointerIcon; using overlay fallback", t);
            lastEngineMode = EngineMode.OVERLAY_FALLBACK;
            lastApplyResult = ApplyResult.FALLBACK_OVERLAY_REQUIRED;
            return lastApplyResult;
        }
    }

    private static ApplyResult applyPointerIconInternal(
            PointerIcon icon, int deviceId, IBinder inputToken) {
        Object im = cachedInputManager;
        if (im == null) {
            lastEngineMode = EngineMode.OVERLAY_FALLBACK;
            lastApplyResult = ApplyResult.FALLBACK_OVERLAY_REQUIRED;
            return lastApplyResult;
        }

        if (customPointerIconMethod != null) {
            try {
                customPointerIconMethod.invoke(im, icon);
                lastEngineMode = EngineMode.LEGACY_CUSTOM_ICON;
                lastApplyResult = ApplyResult.APPLIED_CUSTOM_ICON;
                return lastApplyResult;
            } catch (InvocationTargetException ite) {
                if (isDeadBinderCause(ite.getCause())) {
                    invalidateCache();
                    lastApplyResult = ApplyResult.ERROR;
                    return lastApplyResult;
                }
                Log.w(TAG, "setCustomPointerIcon rejected, checking fallback", ite.getCause());
            } catch (Throwable t) {
                Log.w(TAG, "setCustomPointerIcon invocation failed", t);
            }
        }

        if (scopedPointerIconMethod != null) {
            if (inputToken == null) {
                lastEngineMode = EngineMode.OVERLAY_FALLBACK;
                lastApplyResult = ApplyResult.FALLBACK_OVERLAY_REQUIRED;
                return lastApplyResult;
            }
            try {
                Object ok = scopedPointerIconMethod.invoke(
                        im, icon, Display.DEFAULT_DISPLAY, deviceId, 0, inputToken);
                boolean applied = Boolean.TRUE.equals(ok);
                lastEngineMode = applied
                        ? EngineMode.MODERN_SCOPED_ICON
                        : EngineMode.OVERLAY_FALLBACK;
                lastApplyResult = applied
                        ? ApplyResult.APPLIED_SCOPED_ICON
                        : ApplyResult.FALLBACK_OVERLAY_REQUIRED;
                return lastApplyResult;
            } catch (InvocationTargetException ite) {
                if (isDeadBinderCause(ite.getCause())) {
                    invalidateCache();
                    lastApplyResult = ApplyResult.ERROR;
                    return lastApplyResult;
                }
                Log.w(TAG, "setPointerIcon rejected by system_server", ite.getCause());
                lastEngineMode = EngineMode.OVERLAY_FALLBACK;
                lastApplyResult = ApplyResult.FALLBACK_OVERLAY_REQUIRED;
                return lastApplyResult;
            } catch (Throwable t) {
                Log.w(TAG, "setPointerIcon invocation failed", t);
                lastEngineMode = EngineMode.OVERLAY_FALLBACK;
                lastApplyResult = ApplyResult.FALLBACK_OVERLAY_REQUIRED;
                return lastApplyResult;
            }
        }

        lastEngineMode = EngineMode.OVERLAY_FALLBACK;
        lastApplyResult = ApplyResult.FALLBACK_OVERLAY_REQUIRED;
        return lastApplyResult;
    }

    /** Sets system pointer speed (-7 to +7) directly through IInputManager.tryPointerSpeed. */
    public static boolean setPointerSpeedDirect(int speed) {
        if (!ShizukuEngine.isReady() || !ensureInputManagerProxy()) {
            return false;
        }
        Method m = tryPointerSpeedMethod;
        Object im = cachedInputManager;
        if (m == null || im == null) {
            return false;
        }
        int clamped = clampPointerSpeed(speed);
        try {
            m.invoke(im, clamped);
            return true;
        } catch (InvocationTargetException ite) {
            if (isDeadBinderCause(ite.getCause())) {
                invalidateCache();
            }
            Log.w(TAG, "tryPointerSpeed failed", ite.getCause());
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "tryPointerSpeed invocation error", t);
            return false;
        }
    }

    /** Injects a synthetic touchscreen MotionEvent via Shizuku for zero-lag click forwarding. */
    public static boolean injectTouchEvent(
            int action, float x, float y, long downTime, int displayId) {
        if (!ShizukuEngine.isReady() || !ensureInputManagerProxy()) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerProperties prop = new MotionEvent.PointerProperties();
        prop.id = 0;
        prop.toolType = MotionEvent.TOOL_TYPE_FINGER;
        props[0] = prop;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        MotionEvent.PointerCoords coord = new MotionEvent.PointerCoords();
        coord.x = x;
        coord.y = y;
        coord.pressure = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                ? 0.0f : 1.0f;
        coord.size = 1.0f;
        coords[0] = coord;

        MotionEvent event = MotionEvent.obtain(
                downTime, now, action, 1, props, coords,
                0, 0, 1.0f, 1.0f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        if (event == null) {
            return false;
        }
        try {
            applyDisplayIdIfNeeded(event, displayId);
            return dispatchInjectedEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static boolean dispatchInjectedEvent(InputEvent event) {
        Object im = cachedInputManager;
        if (im == null) {
            return false;
        }
        try {
            if (injectInputEventMethod != null) {
                Object result = injectInputEventMethod.invoke(im, event, INJECT_MODE_ASYNC);
                return Boolean.TRUE.equals(result);
            }
            if (injectInputEventToTargetMethod != null) {
                Object result = injectInputEventToTargetMethod.invoke(
                        im, event, INJECT_MODE_ASYNC, INVALID_UID);
                return Boolean.TRUE.equals(result);
            }
        } catch (InvocationTargetException ite) {
            if (isDeadBinderCause(ite.getCause())) {
                invalidateCache();
            }
            Log.w(TAG, "injectInputEvent failed", ite.getCause());
        } catch (Throwable t) {
            Log.w(TAG, "injectInputEvent error", t);
        }
        return false;
    }

    private static void applyDisplayIdIfNeeded(MotionEvent event, int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY || displayId < 0) {
            return;
        }
        if (!motionSetDisplayIdChecked) {
            motionSetDisplayIdChecked = true;
            try {
                motionEventSetDisplayIdMethod =
                        MotionEvent.class.getMethod("setDisplayId", int.class);
            } catch (Throwable ignored) {
                motionEventSetDisplayIdMethod = null;
            }
        }
        Method m = motionEventSetDisplayIdMethod;
        if (m != null) {
            try {
                m.invoke(event, displayId);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean ensureInputManagerProxy() {
        if (cachedInputManager != null && methodsProbed) {
            return true;
        }
        synchronized (LOCK) {
            if (cachedInputManager != null && methodsProbed) {
                return true;
            }
            IBinder wrapped = ShizukuEngine.wrapSystemBinder("input");
            if (wrapped == null) {
                return false;
            }
            try {
                Class<?> ifaceClass = Class.forName("android.hardware.input.IInputManager");
                Class<?> stubClass = Class.forName("android.hardware.input.IInputManager$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                Object proxy = asInterface.invoke(null, wrapped);
                if (proxy == null) {
                    return false;
                }
                probeInputManagerMethods(ifaceClass);
                cachedInputManager = proxy;
                methodsProbed = true;
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "Failed to bind IInputManager proxy", t);
                cachedInputManager = null;
                methodsProbed = false;
                return false;
            }
        }
    }

    private static void probeInputManagerMethods(Class<?> targetClass) {
        Method customIcon = null;
        Method scopedIcon = null;
        Method pointerSpeed = null;
        Method inject = null;
        Method injectToTarget = null;
        List<String> pointerMethodSignatures = new ArrayList<>();

        for (Method m : targetClass.getMethods()) {
            String name = m.getName();
            Class<?>[] params = m.getParameterTypes();
            if ("setCustomPointerIcon".equals(name)
                    && params.length == 1
                    && PointerIcon.class.equals(params[0])) {
                customIcon = makeAccessible(m);
            } else if ("setPointerIcon".equals(name)
                    && params.length == 5
                    && PointerIcon.class.equals(params[0])
                    && int.class.equals(params[1])
                    && int.class.equals(params[2])
                    && int.class.equals(params[3])
                    && IBinder.class.equals(params[4])) {
                scopedIcon = makeAccessible(m);
            } else if ("tryPointerSpeed".equals(name)
                    && params.length == 1
                    && int.class.equals(params[0])) {
                pointerSpeed = makeAccessible(m);
            } else if ("injectInputEvent".equals(name)
                    && params.length == 2
                    && InputEvent.class.equals(params[0])
                    && int.class.equals(params[1])) {
                inject = makeAccessible(m);
            } else if ("injectInputEventToTarget".equals(name)
                    && params.length == 3
                    && InputEvent.class.equals(params[0])
                    && int.class.equals(params[1])
                    && int.class.equals(params[2])) {
                injectToTarget = makeAccessible(m);
            }

            if (name.toLowerCase().contains("pointer")) {
                pointerMethodSignatures.add(name + "(" + params.length + ")");
            }
        }

        customPointerIconMethod = customIcon;
        scopedPointerIconMethod = scopedIcon;
        tryPointerSpeedMethod = pointerSpeed;
        injectInputEventMethod = inject;
        injectInputEventToTargetMethod = injectToTarget;

        if (customIcon == null && scopedIcon == null) {
            Log.w(TAG, "No compatible pointer icon method on IInputManager; available: "
                    + pointerMethodSignatures + ". Using overlay fallback.");
        } else {
            Log.i(TAG, "Probed IInputManager methods: custom=" + (customIcon != null)
                    + ", scoped=" + (scopedIcon != null)
                    + ", inject=" + (inject != null || injectToTarget != null));
        }
    }

    private static Method makeAccessible(Method method) {
        if (method != null) {
            try {
                method.setAccessible(true);
            } catch (Throwable ignored) {
            }
        }
        return method;
    }

    private static boolean isDeadBinderCause(Throwable cause) {
        return cause instanceof DeadObjectException
                || (cause instanceof RemoteException && cause.getCause() instanceof DeadObjectException);
    }
}
