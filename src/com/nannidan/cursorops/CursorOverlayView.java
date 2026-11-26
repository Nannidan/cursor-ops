package com.nannidan.cursorops;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Full-screen transparent non-touchable overlay View that draws sharp vector neon
 * cursors, optional motion trails, and expanding click ripples without intercepting input.
 */
public class CursorOverlayView extends View {
    private static final String TAG = "CursorOpsOverlay";

    public static final int COLOR_BLACK = 0xFF000000;
    public static final int COLOR_CYAN = 0xFF00F0FF;
    public static final int COLOR_MAGENTA = 0xFFFF00AA;
    public static final int COLOR_GREEN = 0xFF39FF14;

    private static final float BASE_CURSOR_DP = 26f;
    private static final float MAX_RIPPLE_DP = 28f;
    private static final long RIPPLE_DURATION_MS = 260L;
    private static final int MAX_ACTIVE_RIPPLES = 8;
    private static final int TRAIL_CAPACITY = 10;
    private static final long TRAIL_DECAY_MS = 200L;
    private static final float MIN_TRAIL_STEP_SQ = 4f;

    private static final class Ripple {
        long startMs;
        float x;
        float y;
        int color;

        Ripple(long startMs, float x, float y, int color) {
            this.startMs = startMs;
            this.x = x;
            this.y = y;
            this.color = color;
        }
    }

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();

    private final float[] trailX = new float[TRAIL_CAPACITY];
    private final float[] trailY = new float[TRAIL_CAPACITY];
    private final long[] trailTimeMs = new long[TRAIL_CAPACITY];
    private final int[] locationInScreen = new int[2];
    private int trailHead;
    private int trailCount;
    private float offsetX;
    private float offsetY;

    private final List<Ripple> activeRipples = new ArrayList<>(MAX_ACTIVE_RIPPLES);

    private boolean attached;
    private boolean cursorVisible;
    private int cursorStyle = SystemPointerController.STYLE_NEON_ARROW;
    private int cursorColor = CursorConfig.COLOR_CYAN;
    private float cursorScale = 1.25f;
    private boolean clickRippleEnabled = true;
    private boolean trailEnabled;

    private float cursorX = -1000f;
    private float cursorY = -1000f;

    /** Constructs the non-touchable AMOLED vector cursor overlay view. */
    public CursorOverlayView(Context context) {
        super(context);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setStyle(Paint.Style.FILL);

        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setStrokeCap(Paint.Cap.ROUND);

        trailPaint.setStyle(Paint.Style.FILL);
        setLayerType(LAYER_TYPE_NONE, null);
    }

    /** Attaches this overlay window via TYPE_ACCESSIBILITY_OVERLAY with SYSTEM_ALERT_WINDOW fallback. */
    public boolean attach(WindowManager wm) {
        if (attached || wm == null) {
            return attached;
        }
        WindowManager.LayoutParams params = buildLayoutParams(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        try {
            wm.addView(this, params);
            attached = true;
            return true;
        } catch (WindowManager.BadTokenException | SecurityException primaryFailure) {
            Log.w(TAG, "TYPE_ACCESSIBILITY_OVERLAY attach failed; checking SYSTEM_ALERT_WINDOW",
                    primaryFailure);
        }

        if (Settings.canDrawOverlays(getContext())) {
            WindowManager.LayoutParams fallbackParams = buildLayoutParams(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            try {
                wm.addView(this, fallbackParams);
                attached = true;
                return true;
            } catch (Throwable fallbackFailure) {
                Log.e(TAG, "TYPE_APPLICATION_OVERLAY fallback attach failed", fallbackFailure);
            }
        }
        return false;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        clearTrail();
        activeRipples.clear();
    }

    /** Detaches this overlay view from the WindowManager if currently attached to a window. */
    public void detach(WindowManager wm) {
        if (wm != null) {
            try {
                if (isAttachedToWindow() || attached) {
                    wm.removeView(this);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Overlay detach error", t);
            }
        }
        attached = false;
        clearTrail();
        activeRipples.clear();
    }

    /** Returns true while this overlay view is attached to a WindowManager. */
    public boolean isAttached() {
        return attached;
    }

    /** Updates cursor visual style, scale, and animation preferences using the current color. */
    public void applyConfig(
            int style, float scale, boolean clickRippleEnabled, boolean trailEnabled) {
        applyConfig(style, this.cursorColor, scale, clickRippleEnabled, trailEnabled);
    }

    /** Updates cursor visual style, color preset, scale, and animation preferences. */
    public void applyConfig(
            int style,
            int colorPreset,
            float scale,
            boolean clickRippleEnabled,
            boolean trailEnabled) {
        this.cursorStyle = style;
        this.cursorColor = colorPreset;
        this.cursorScale = SystemPointerController.clampScale(scale);
        this.clickRippleEnabled = clickRippleEnabled;
        if (this.trailEnabled != trailEnabled) {
            this.trailEnabled = trailEnabled;
            if (!trailEnabled) {
                clearTrail();
            }
        }
        requestRedraw();
    }

    /** Shows or hides the custom overlay cursor shape and trail. */
    public void setCursorVisible(boolean visible) {
        if (this.cursorVisible == visible) {
            return;
        }
        this.cursorVisible = visible;
        if (!visible) {
            clearTrail();
        }
        requestRedraw();
    }

    /** Returns true if the custom overlay cursor is currently visible. */
    public boolean isCursorVisible() {
        return cursorVisible;
    }

    /** Updates pointer coordinates in display pixels and records optional trail samples. */
    public void updateCursorPosition(float x, float y) {
        this.cursorX = x;
        this.cursorY = y;
        if (trailEnabled && cursorVisible) {
            recordTrailSample(x, y);
        }
        requestRedraw();
    }

    /** Spawns an expanding click-ripple animation centered at (x, y) if enabled. */
    public void spawnRipple(float x, float y, int color) {
        if (!clickRippleEnabled) {
            return;
        }
        if (activeRipples.size() >= MAX_ACTIVE_RIPPLES) {
            activeRipples.remove(0);
        }
        activeRipples.add(new Ripple(SystemClock.uptimeMillis(), x, y, color));
        requestRedraw();
    }

    private void requestRedraw() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invalidate();
        } else {
            postInvalidate();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        getLocationOnScreen(locationInScreen);
        offsetX = locationInScreen[0];
        offsetY = locationInScreen[1];
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;

        canvas.save();
        canvas.translate(-offsetX, -offsetY);

        boolean needsTrailFrames = false;
        if (trailEnabled && cursorVisible && trailCount > 0) {
            needsTrailFrames = drawMotionTrail(canvas, density);
        }

        boolean needsRippleFrames = !activeRipples.isEmpty() && drawActiveRipples(canvas, density);

        if (cursorVisible && cursorX >= 0f && cursorY >= 0f) {
            drawVectorCursor(canvas, density);
        }

        canvas.restore();

        if (needsTrailFrames || needsRippleFrames) {
            postInvalidateOnAnimation();
        }
    }

    private boolean drawMotionTrail(Canvas canvas, float density) {
        long now = SystemClock.uptimeMillis();
        int count = trailCount;
        int startIndex = (trailHead - count + TRAIL_CAPACITY) % TRAIL_CAPACITY;
        float maxRadius = Math.max(2f, 4.5f * density * cursorScale);
        int primaryColor = CursorConfig.resolvePrimaryColor(cursorColor);
        int accentColor = CursorConfig.resolveAccentColor(cursorColor);
        boolean anyActive = false;

        for (int i = 0; i < count; i++) {
            int idx = (startIndex + i) % TRAIL_CAPACITY;
            long age = now - trailTimeMs[idx];
            if (age < 0L || age >= TRAIL_DECAY_MS) {
                continue;
            }
            anyActive = true;
            float decay = 1f - ((float) age / (float) TRAIL_DECAY_MS);
            float frac = (float) (i + 1) / (float) (count + 1);
            int color = (i % 2 == 0) ? accentColor : primaryColor;
            int alpha = Math.round(135f * frac * frac * decay);
            trailPaint.setColor((color & 0x00FFFFFF) | (alpha << 24));
            canvas.drawCircle(trailX[idx], trailY[idx], maxRadius * frac, trailPaint);
        }
        if (!anyActive) {
            clearTrail();
        }
        return anyActive;
    }

    private boolean drawActiveRipples(Canvas canvas, float density) {
        long now = SystemClock.uptimeMillis();
        float maxRadius = MAX_RIPPLE_DP * density * cursorScale;
        float startRadius = 4f * density;
        int fallbackAccent = CursorConfig.resolveAccentColor(cursorColor);

        Iterator<Ripple> it = activeRipples.iterator();
        while (it.hasNext()) {
            Ripple ripple = it.next();
            long elapsed = now - ripple.startMs;
            if (elapsed >= RIPPLE_DURATION_MS) {
                it.remove();
                continue;
            }
            float t = Math.max(0f, (float) elapsed / (float) RIPPLE_DURATION_MS);
            float inv = 1f - t;
            float ease = 1f - (inv * inv * inv);
            float radius = startRadius + (maxRadius - startRadius) * ease;

            int primaryAlpha = Math.round(235f * inv);
            int backingAlpha = Math.round(180f * inv);
            int innerAlpha = Math.round(165f * inv);

            ripplePaint.setStrokeWidth(Math.max(3.5f, 4.2f * density * inv));
            ripplePaint.setColor((COLOR_BLACK & 0x00FFFFFF) | (backingAlpha << 24));
            canvas.drawCircle(ripple.x, ripple.y, radius, ripplePaint);

            ripplePaint.setStrokeWidth(Math.max(1.8f, 2.4f * density * inv));
            ripplePaint.setColor((ripple.color & 0x00FFFFFF) | (primaryAlpha << 24));
            canvas.drawCircle(ripple.x, ripple.y, radius, ripplePaint);

            float innerRadius = radius * 0.58f;
            int secondaryRingColor = (ripple.color == fallbackAccent)
                    ? CursorConfig.resolvePrimaryColor(cursorColor)
                    : fallbackAccent;
            ripplePaint.setStrokeWidth(Math.max(1.2f, 1.5f * density * inv));
            ripplePaint.setColor((secondaryRingColor & 0x00FFFFFF) | (innerAlpha << 24));
            canvas.drawCircle(ripple.x, ripple.y, innerRadius, ripplePaint);
        }
        return !activeRipples.isEmpty();
    }

    private void drawVectorCursor(Canvas canvas, float density) {
        float sizePx = BASE_CURSOR_DP * density * cursorScale;
        float strokeScale = density * cursorScale;
        int primaryColor = CursorConfig.resolvePrimaryColor(cursorColor);
        int accentColor = CursorConfig.resolveAccentColor(cursorColor);

        switch (cursorStyle) {
            case SystemPointerController.STYLE_CYBER_CROSSHAIR:
                drawCrosshair(canvas, sizePx, strokeScale, primaryColor, accentColor);
                break;
            case SystemPointerController.STYLE_HOLO_RING:
                drawHoloRing(canvas, sizePx, strokeScale, primaryColor, accentColor);
                break;
            case SystemPointerController.STYLE_PRECISION_DOT:
                drawPrecisionDot(canvas, sizePx, strokeScale, primaryColor, accentColor);
                break;
            case SystemPointerController.STYLE_NEON_ARROW:
            default:
                drawNeonArrow(canvas, sizePx, strokeScale, primaryColor, accentColor);
                break;
        }
    }

    private void drawNeonArrow(
            Canvas canvas, float s, float strokeScale, int primaryColor, int accentColor) {
        arrowPath.rewind();
        arrowPath.moveTo(cursorX, cursorY);
        arrowPath.lineTo(cursorX, cursorY + s * 0.82f);
        arrowPath.lineTo(cursorX + s * 0.23f, cursorY + s * 0.60f);
        arrowPath.lineTo(cursorX + s * 0.42f, cursorY + s * 0.90f);
        arrowPath.lineTo(cursorX + s * 0.55f, cursorY + s * 0.83f);
        arrowPath.lineTo(cursorX + s * 0.36f, cursorY + s * 0.53f);
        arrowPath.lineTo(cursorX + s * 0.68f, cursorY + s * 0.53f);
        arrowPath.close();

        strokePaint.setColor(COLOR_BLACK);
        strokePaint.setStrokeWidth(Math.max(2.6f, 3.0f * strokeScale));
        canvas.drawPath(arrowPath, strokePaint);

        fillPaint.setColor(primaryColor);
        canvas.drawPath(arrowPath, fillPaint);

        strokePaint.setColor(accentColor);
        strokePaint.setStrokeWidth(Math.max(1.2f, 1.25f * strokeScale));
        canvas.drawPath(arrowPath, strokePaint);
    }

    private void drawCrosshair(
            Canvas canvas, float s, float strokeScale, int primaryColor, int accentColor) {
        float gap = s * 0.14f;
        float arm = s * 0.46f;

        strokePaint.setColor(COLOR_BLACK);
        strokePaint.setStrokeWidth(Math.max(3.2f, 3.6f * strokeScale));
        drawCrosshairArms(canvas, gap, arm);

        strokePaint.setColor(primaryColor);
        strokePaint.setStrokeWidth(Math.max(1.6f, 1.8f * strokeScale));
        drawCrosshairArms(canvas, gap, arm);

        fillPaint.setColor(COLOR_BLACK);
        canvas.drawCircle(cursorX, cursorY, Math.max(2.8f, 3.2f * strokeScale), fillPaint);
        fillPaint.setColor(accentColor);
        canvas.drawCircle(cursorX, cursorY, Math.max(1.8f, 2.1f * strokeScale), fillPaint);
    }

    private void drawCrosshairArms(Canvas canvas, float gap, float arm) {
        canvas.drawLine(cursorX - arm, cursorY, cursorX - gap, cursorY, strokePaint);
        canvas.drawLine(cursorX + gap, cursorY, cursorX + arm, cursorY, strokePaint);
        canvas.drawLine(cursorX, cursorY - arm, cursorX, cursorY - gap, strokePaint);
        canvas.drawLine(cursorX, cursorY + gap, cursorX, cursorY + arm, strokePaint);
    }

    private void drawHoloRing(
            Canvas canvas, float s, float strokeScale, int primaryColor, int accentColor) {
        float rOuter = s * 0.36f;
        float rInner = s * 0.22f;

        strokePaint.setColor(COLOR_BLACK);
        strokePaint.setStrokeWidth(Math.max(3.2f, 3.6f * strokeScale));
        canvas.drawCircle(cursorX, cursorY, rOuter, strokePaint);

        strokePaint.setColor(primaryColor);
        strokePaint.setStrokeWidth(Math.max(1.8f, 2.0f * strokeScale));
        canvas.drawCircle(cursorX, cursorY, rOuter, strokePaint);

        strokePaint.setColor(accentColor);
        strokePaint.setStrokeWidth(Math.max(1.2f, 1.4f * strokeScale));
        canvas.drawCircle(cursorX, cursorY, rInner, strokePaint);

        fillPaint.setColor(primaryColor);
        canvas.drawCircle(cursorX, cursorY, Math.max(1.8f, 2.0f * strokeScale), fillPaint);
    }

    private void drawPrecisionDot(
            Canvas canvas, float s, float strokeScale, int primaryColor, int accentColor) {
        float r = s * 0.16f;

        fillPaint.setColor(COLOR_BLACK);
        canvas.drawCircle(cursorX, cursorY, r + Math.max(1.8f, 2.0f * strokeScale), fillPaint);

        fillPaint.setColor(primaryColor);
        canvas.drawCircle(cursorX, cursorY, r, fillPaint);

        strokePaint.setColor(accentColor);
        strokePaint.setStrokeWidth(Math.max(1.2f, 1.4f * strokeScale));
        canvas.drawCircle(cursorX, cursorY, r + Math.max(2.6f, 2.8f * strokeScale), strokePaint);
    }

    private void recordTrailSample(float x, float y) {
        if (trailCount > 0) {
            int lastIdx = (trailHead - 1 + TRAIL_CAPACITY) % TRAIL_CAPACITY;
            float dx = x - trailX[lastIdx];
            float dy = y - trailY[lastIdx];
            if ((dx * dx) + (dy * dy) < MIN_TRAIL_STEP_SQ) {
                return;
            }
        }
        trailX[trailHead] = x;
        trailY[trailHead] = y;
        trailTimeMs[trailHead] = SystemClock.uptimeMillis();
        trailHead = (trailHead + 1) % TRAIL_CAPACITY;
        if (trailCount < TRAIL_CAPACITY) {
            trailCount++;
        }
    }

    private void clearTrail() {
        trailHead = 0;
        trailCount = 0;
    }

    private static WindowManager.LayoutParams buildLayoutParams(int windowType) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle("CursorOpsOverlay");
        return params;
    }
}