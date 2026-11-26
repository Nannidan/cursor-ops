package com.nannidan.cursorops;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Persists user-facing cursor, overlay, and shortcut settings and defines internal
 * action constants shared across CursorService, accessibility overlay, and UI.
 */
public final class CursorConfig {
    private static final String PREFS_NAME = "cursor_ops_prefs";

    public static final int MODE_AUTO = 0;
    public static final int MODE_ALWAYS_OVERLAY = 1;
    public static final int MODE_NATIVE_ONLY = 2;

    public static final int SHORTCUT_NONE = 0;
    public static final int SHORTCUT_BACK = 1;
    public static final int SHORTCUT_HOME = 2;
    public static final int SHORTCUT_RECENTS = 3;

    public static final int COLOR_CYAN = 0;
    public static final int COLOR_MAGENTA = 1;
    public static final int COLOR_ELECTRIC_GREEN = 2;

    public static final String ACTION_STATE_CHANGED = "com.nannidan.cursorops.ACTION_STATE_CHANGED";
    public static final String ACTION_CONFIG_CHANGED = "com.nannidan.cursorops.ACTION_CONFIG_CHANGED";
    public static final String ACTION_REAPPLY_NOW = "com.nannidan.cursorops.ACTION_REAPPLY_NOW";
    public static final String ACTION_TOGGLE_SERVICE = "com.nannidan.cursorops.ACTION_TOGGLE_SERVICE";
    public static final String ACTION_STOP_SERVICE = "com.nannidan.cursorops.ACTION_STOP_SERVICE";

    private static final String KEY_SERVICE_ENABLED = "service_enabled";
    private static final String KEY_CURSOR_STYLE = "cursor_style";
    private static final String KEY_CURSOR_COLOR = "cursor_color";
    private static final String KEY_CURSOR_SCALE = "cursor_scale";
    private static final String KEY_OVERLAY_MODE = "overlay_mode";
    private static final String KEY_CLICK_RIPPLE = "click_ripple_enabled";
    private static final String KEY_TRAIL_ENABLED = "trail_enabled";
    private static final String KEY_RIGHT_CLICK_ACTION = "right_click_action";
    private static final String KEY_MIDDLE_CLICK_ACTION = "middle_click_action";
    private static final String KEY_POINTER_SPEED = "pointer_speed";
    private static final String KEY_POINTER_SPEED_SET = "pointer_speed_set";

    private static final boolean DEFAULT_SERVICE_ENABLED = true;
    private static final int DEFAULT_CURSOR_STYLE = SystemPointerController.STYLE_NEON_ARROW;
    private static final int DEFAULT_CURSOR_COLOR = COLOR_CYAN;
    private static final float DEFAULT_CURSOR_SCALE = 1.25f;
    private static final int DEFAULT_OVERLAY_MODE = MODE_AUTO;
    private static final boolean DEFAULT_CLICK_RIPPLE = true;
    private static final boolean DEFAULT_TRAIL_ENABLED = false;
    private static final int DEFAULT_RIGHT_CLICK_ACTION = SHORTCUT_BACK;
    private static final int DEFAULT_MIDDLE_CLICK_ACTION = SHORTCUT_HOME;

    /** Immutable snapshot of persisted CursorOps settings. */
    public static final class Settings {
        public final boolean serviceEnabled;
        public final int cursorStyle;
        public final int cursorColor;
        public final float cursorScale;
        public final int overlayMode;
        public final boolean clickRippleEnabled;
        public final boolean trailEnabled;
        public final int rightClickAction;
        public final int middleClickAction;
        public final int pointerSpeed;
        public final boolean pointerSpeedSet;

        Settings(
                boolean serviceEnabled,
                int cursorStyle,
                int cursorColor,
                float cursorScale,
                int overlayMode,
                boolean clickRippleEnabled,
                boolean trailEnabled,
                int rightClickAction,
                int middleClickAction,
                int pointerSpeed,
                boolean pointerSpeedSet) {
            this.serviceEnabled = serviceEnabled;
            this.cursorStyle = cursorStyle;
            this.cursorColor = cursorColor;
            this.cursorScale = cursorScale;
            this.overlayMode = overlayMode;
            this.clickRippleEnabled = clickRippleEnabled;
            this.trailEnabled = trailEnabled;
            this.rightClickAction = rightClickAction;
            this.middleClickAction = middleClickAction;
            this.pointerSpeed = pointerSpeed;
            this.pointerSpeedSet = pointerSpeedSet;
        }
    }

    private CursorConfig() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Loads a consistent snapshot of all persisted cursor configuration values. */
    public static Settings load(Context context) {
        SharedPreferences sp = prefs(context);
        boolean speedSet = sp.getBoolean(KEY_POINTER_SPEED_SET, false);
        int defaultSpeed = readSystemPointerSpeed(context);
        int pointerSpeed = SystemPointerController.clampPointerSpeed(
                sp.getInt(KEY_POINTER_SPEED, defaultSpeed));
        return new Settings(
                sp.getBoolean(KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED),
                clampStyle(sp.getInt(KEY_CURSOR_STYLE, DEFAULT_CURSOR_STYLE)),
                clampColor(sp.getInt(KEY_CURSOR_COLOR, DEFAULT_CURSOR_COLOR)),
                SystemPointerController.clampScale(
                        sp.getFloat(KEY_CURSOR_SCALE, DEFAULT_CURSOR_SCALE)),
                clampOverlayMode(sp.getInt(KEY_OVERLAY_MODE, DEFAULT_OVERLAY_MODE)),
                sp.getBoolean(KEY_CLICK_RIPPLE, DEFAULT_CLICK_RIPPLE),
                sp.getBoolean(KEY_TRAIL_ENABLED, DEFAULT_TRAIL_ENABLED),
                clampShortcut(sp.getInt(KEY_RIGHT_CLICK_ACTION, DEFAULT_RIGHT_CLICK_ACTION)),
                clampShortcut(sp.getInt(KEY_MIDDLE_CLICK_ACTION, DEFAULT_MIDDLE_CLICK_ACTION)),
                pointerSpeed,
                speedSet);
    }

    /** Returns whether the cursor daemon is configured to apply custom styling. */
    public static boolean isServiceEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED);
    }

    public static void setServiceEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply();
        notifyConfigChanged(context);
    }

    public static void setCursorStyle(Context context, int style) {
        prefs(context).edit().putInt(KEY_CURSOR_STYLE, clampStyle(style)).apply();
        notifyConfigChanged(context);
    }

    public static void setCursorColor(Context context, int color) {
        prefs(context).edit().putInt(KEY_CURSOR_COLOR, clampColor(color)).apply();
        notifyConfigChanged(context);
    }

    public static void setCursorScale(Context context, float scale) {
        prefs(context).edit()
                .putFloat(KEY_CURSOR_SCALE, SystemPointerController.clampScale(scale))
                .apply();
        notifyConfigChanged(context);
    }

    public static void setOverlayMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_OVERLAY_MODE, clampOverlayMode(mode)).apply();
        notifyConfigChanged(context);
    }

    public static void setClickRippleEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CLICK_RIPPLE, enabled).apply();
        notifyConfigChanged(context);
    }

    public static void setTrailEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_TRAIL_ENABLED, enabled).apply();
        notifyConfigChanged(context);
    }

    public static void setRightClickAction(Context context, int shortcut) {
        prefs(context).edit().putInt(KEY_RIGHT_CLICK_ACTION, clampShortcut(shortcut)).apply();
        notifyConfigChanged(context);
    }

    public static void setMiddleClickAction(Context context, int shortcut) {
        prefs(context).edit().putInt(KEY_MIDDLE_CLICK_ACTION, clampShortcut(shortcut)).apply();
        notifyConfigChanged(context);
    }

    /** Persists system pointer speed [-7..+7] and marks it explicitly user-configured. */
    public static void setPointerSpeed(Context context, int speed) {
        int clamped = SystemPointerController.clampPointerSpeed(speed);
        prefs(context).edit()
                .putInt(KEY_POINTER_SPEED, clamped)
                .putBoolean(KEY_POINTER_SPEED_SET, true)
                .apply();
        notifyConfigChanged(context);
    }

    private static int readSystemPointerSpeed(Context context) {
        try {
            int sysSpeed = android.provider.Settings.System.getInt(
                    context.getApplicationContext().getContentResolver(), "pointer_speed", 0);
            return SystemPointerController.clampPointerSpeed(sysSpeed);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Immediately notifies in-process services and broadcasts within this package that config changed. */
    public static void notifyConfigChanged(Context context) {
        CursorAccessibilityService.onConfigChangedLive();
        CursorService.onConfigChangedLive();
        Context app = context.getApplicationContext();
        app.sendBroadcast(new Intent(ACTION_CONFIG_CHANGED)
                .setPackage(app.getPackageName())
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
    }

    /** Broadcasts within this package that live service, Shizuku, or mouse state changed. */
    public static void notifyStateChanged(Context context) {
        Context app = context.getApplicationContext();
        app.sendBroadcast(new Intent(ACTION_STATE_CHANGED)
                .setPackage(app.getPackageName())
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
    }

    /** Broadcasts an explicit foreground re-apply request to all internal cursor services. */
    public static void notifyReapplyNow(Context context) {
        Context app = context.getApplicationContext();
        app.sendBroadcast(new Intent(ACTION_REAPPLY_NOW)
                .setPackage(app.getPackageName())
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
    }

    /** Returns a human-readable label for an overlay mode constant. */
    public static String overlayModeLabel(int mode) {
        switch (mode) {
            case MODE_ALWAYS_OVERLAY:
                return "Always Overlay";
            case MODE_NATIVE_ONLY:
                return "Native Only";
            case MODE_AUTO:
            default:
                return "Auto";
        }
    }

    /** Returns a human-readable label for a mouse button shortcut constant. */
    public static String shortcutLabel(int shortcut) {
        switch (shortcut) {
            case SHORTCUT_BACK:
                return "Back";
            case SHORTCUT_HOME:
                return "Home";
            case SHORTCUT_RECENTS:
                return "Recents";
            case SHORTCUT_NONE:
            default:
                return "Disabled";
        }
    }

    /** Returns a human-readable label for a cursor color preset. */
    public static String colorLabel(int color) {
        switch (color) {
            case COLOR_MAGENTA:
                return "Magenta";
            case COLOR_ELECTRIC_GREEN:
                return "Electric Green";
            case COLOR_CYAN:
            default:
                return "Cyan";
        }
    }

    /** Resolves the primary ARGB color for a cursor color preset. */
    public static int resolvePrimaryColor(int color) {
        switch (color) {
            case COLOR_MAGENTA:
                return 0xFFFF00AA;
            case COLOR_ELECTRIC_GREEN:
                return 0xFF39FF14;
            case COLOR_CYAN:
            default:
                return 0xFF00F0FF;
        }
    }

    /** Resolves the contrasting neon accent ARGB color for a cursor color preset. */
    public static int resolveAccentColor(int color) {
        if (color == COLOR_CYAN) {
            return 0xFFFF00AA;
        }
        return 0xFF00F0FF;
    }

    private static int clampStyle(int style) {
        if (style < SystemPointerController.STYLE_NEON_ARROW
                || style > SystemPointerController.STYLE_PRECISION_DOT) {
            return DEFAULT_CURSOR_STYLE;
        }
        return style;
    }

    private static int clampOverlayMode(int mode) {
        if (mode < MODE_AUTO || mode > MODE_NATIVE_ONLY) {
            return DEFAULT_OVERLAY_MODE;
        }
        return mode;
    }

    private static int clampShortcut(int shortcut) {
        if (shortcut < SHORTCUT_NONE || shortcut > SHORTCUT_RECENTS) {
            return SHORTCUT_NONE;
        }
        return shortcut;
    }

    private static int clampColor(int color) {
        if (color < COLOR_CYAN || color > COLOR_ELECTRIC_GREEN) {
            return DEFAULT_CURSOR_COLOR;
        }
        return color;
    }
}
