package com.nannidan.cursorops;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-screen pure #000000 AMOLED control dashboard for CursorOps.
 * Displays live Shizuku, Bluetooth mouse, Accessibility, Overlay, and Engine status,
 * and persists cursor style, color, render mode, scale, speed, ripple, trail, and shortcut settings live.
 */
public class MainActivity extends Activity
        implements InputManager.InputDeviceListener, ShizukuEngine.StateListener {
    private static final int COLOR_AMOLED_BLACK = 0xFF000000;
    private static final int COLOR_CARD_BG = 0xD9000000;
    private static final int COLOR_CHIP_BG = 0xD9000000;
    private static final int COLOR_CYAN = 0xFF00F0FF;
    private static final int COLOR_MAGENTA = 0xFFFF00AA;
    private static final int COLOR_GREEN = 0xFF39FF14;
    private static final int COLOR_ERROR = 0xFFFF5252;
    private static final int COLOR_TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xFFB0BEC5;
    private static final int COLOR_BORDER_DIM = 0xFF455A64;

    private static final int REQ_RUNTIME_PERMS = 3001;
    private static final int SCALE_SLIDER_MAX = 40;
    private static final float SCALE_STEP = 0.05f;
    private static final int SPEED_SLIDER_MAX = 14;

    private static final int[] STYLE_IDS = {
        SystemPointerController.STYLE_NEON_ARROW,
        SystemPointerController.STYLE_CYBER_CROSSHAIR,
        SystemPointerController.STYLE_HOLO_RING,
        SystemPointerController.STYLE_PRECISION_DOT
    };

    private static final int[] COLOR_IDS = {
        CursorConfig.COLOR_CYAN,
        CursorConfig.COLOR_MAGENTA,
        CursorConfig.COLOR_ELECTRIC_GREEN
    };

    private static final int[] MODE_IDS = {
        CursorConfig.MODE_AUTO,
        CursorConfig.MODE_ALWAYS_OVERLAY,
        CursorConfig.MODE_NATIVE_ONLY
    };

    private static final int[] SHORTCUT_IDS = {
        CursorConfig.SHORTCUT_BACK,
        CursorConfig.SHORTCUT_HOME,
        CursorConfig.SHORTCUT_RECENTS,
        CursorConfig.SHORTCUT_NONE
    };

    private Handler mainHandler;
    private InputManager inputManager;
    private boolean receiversRegistered;
    private boolean listenersRegistered;
    private boolean syncingUi;

    private ImageView cursorPreviewView;
    private Bitmap previewBitmap;
    private int lastPreviewStyle = -1;
    private float lastPreviewScale = -1f;
    private int lastPreviewColor = -1;

    private TextView shizukuValueView;
    private Button shizukuActionBtn;
    private TextView mouseValueView;
    private TextView a11yValueView;
    private Button a11yActionBtn;
    private TextView overlayValueView;
    private Button overlayActionBtn;
    private TextView engineValueView;

    private Switch masterSwitch;
    private Button reapplyBtn;
    private TextView masterHintView;

    private final Button[] styleButtons = new Button[STYLE_IDS.length];
    private final Button[] colorButtons = new Button[COLOR_IDS.length];
    private final Button[] modeButtons = new Button[MODE_IDS.length];
    private TextView scaleValueView;
    private SeekBar scaleSeekBar;
    private TextView speedValueView;
    private SeekBar speedSeekBar;
    private Switch rippleSwitch;
    private Switch trailSwitch;

    private final Button[] rightButtons = new Button[SHORTCUT_IDS.length];
    private final Button[] middleButtons = new Button[SHORTCUT_IDS.length];

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        ShizukuEngine.init();

        setContentView(buildRootView());

        if (CursorConfig.isServiceEnabled(this) && !CursorService.isRunning()) {
            CursorService.start(this);
        }
        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!listenersRegistered) {
            ShizukuEngine.addListener(this);
            if (inputManager != null) {
                inputManager.registerInputDeviceListener(this, mainHandler);
            }
            listenersRegistered = true;
        }
        if (!receiversRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(CursorConfig.ACTION_STATE_CHANGED);
            filter.addAction(CursorConfig.ACTION_CONFIG_CHANGED);
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            receiversRegistered = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    @Override
    protected void onStop() {
        if (listenersRegistered) {
            ShizukuEngine.removeListener(this);
            if (inputManager != null) {
                inputManager.unregisterInputDeviceListener(this);
            }
            listenersRegistered = false;
        }
        if (receiversRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiversRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (previewBitmap != null && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
            previewBitmap = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBinderReady(boolean permissionGranted) {
        refreshUi();
    }

    @Override
    public void onBinderDead() {
        refreshUi();
    }

    @Override
    public void onPermissionResult(boolean granted) {
        if (granted) {
            ShizukuEngine.grantSelfPrivileges(this);
            if (CursorConfig.isServiceEnabled(this)) {
                CursorConfig.notifyReapplyNow(this);
            }
        }
        refreshUi();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RUNTIME_PERMS) {
            refreshUi();
        }
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        refreshUi();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        refreshUi();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        refreshUi();
    }

    private View buildRootView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(COLOR_AMOLED_BLACK);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);

        final int basePadH = dp(14);
        final int basePadV = dp(12);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(COLOR_AMOLED_BLACK);
        container.setPadding(basePadH, basePadV, basePadH, basePadV);

        scrollView.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets sys = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            container.setPadding(
                    basePadH + sys.left,
                    basePadV + sys.top,
                    basePadH + sys.right,
                    basePadV + sys.bottom);
            return insets;
        });

        container.addView(buildStatusHeaderCard());
        container.addView(buildMasterServiceCard());
        container.addView(buildAppearanceCard());
        container.addView(buildShortcutsCard());

        scrollView.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        return scrollView;
    }

    private View buildStatusHeaderCard() {
        LinearLayout card = createCard(COLOR_CYAN, 12);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("CURSOR // OPS");
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        title.setTextColor(COLOR_CYAN);
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Shizuku + AMOLED Bluetooth Pointer Engine");
        subtitle.setTypeface(Typeface.SANS_SERIF);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitle.setTextColor(COLOR_TEXT_SECONDARY);
        titleCol.addView(subtitle);

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topRow.addView(titleCol, titleLp);

        cursorPreviewView = new ImageView(this);
        cursorPreviewView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int previewPad = dp(6);
        cursorPreviewView.setPadding(previewPad, previewPad, previewPad, previewPad);
        GradientDrawable previewBg = createCardDrawable(COLOR_AMOLED_BLACK, COLOR_MAGENTA, dp(8));
        cursorPreviewView.setBackground(previewBg);
        int previewSize = dp(56);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(previewSize, previewSize);
        previewLp.setMarginStart(dp(10));
        topRow.addView(cursorPreviewView, previewLp);

        card.addView(topRow);
        card.addView(createDivider(dp(10)));

        shizukuValueView = createStatusValueText();
        shizukuActionBtn = createActionButton("Request Shizuku", COLOR_CYAN, v -> onRequestShizukuClicked());
        card.addView(buildStatusRow("SHIZUKU", shizukuValueView, shizukuActionBtn));

        mouseValueView = createStatusValueText();
        card.addView(buildStatusRow("BT MOUSE", mouseValueView, null));

        a11yValueView = createStatusValueText();
        a11yActionBtn = createActionButton("Enable", COLOR_MAGENTA, v -> openAccessibilitySettings());
        card.addView(buildStatusRow("ACCESSIBILITY", a11yValueView, a11yActionBtn));

        overlayValueView = createStatusValueText();
        overlayActionBtn = createActionButton("Enable", COLOR_MAGENTA, v -> openOverlaySettings());
        card.addView(buildStatusRow("OVERLAY PERM", overlayValueView, overlayActionBtn));

        engineValueView = createStatusValueText();
        card.addView(buildStatusRow("ENGINE", engineValueView, null));

        return card;
    }

    private View buildMasterServiceCard() {
        LinearLayout card = createCard(COLOR_MAGENTA, 12);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labelCol = new LinearLayout(this);
        labelCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = createSectionLabel("DAEMON CONTROL", COLOR_MAGENTA);
        labelCol.addView(title);

        masterHintView = new TextView(this);
        masterHintView.setTypeface(Typeface.SANS_SERIF);
        masterHintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        masterHintView.setTextColor(COLOR_TEXT_SECONDARY);
        labelCol.addView(masterHintView);

        headerRow.addView(labelCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        reapplyBtn = createActionButton("Re-apply", COLOR_CYAN, v -> onReapplyClicked());
        LinearLayout.LayoutParams reapplyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        reapplyLp.setMarginEnd(dp(10));
        headerRow.addView(reapplyBtn, reapplyLp);

        masterSwitch = createNeonSwitch((buttonView, isChecked) -> {
            if (syncingUi) {
                return;
            }
            handleMasterToggle(isChecked);
        });
        headerRow.addView(masterSwitch);
        card.addView(headerRow);
        return card;
    }

    private View buildAppearanceCard() {
        LinearLayout card = createCard(COLOR_CYAN, 12);
        card.addView(createSectionLabel("CURSOR STYLE & RENDERING", COLOR_CYAN));

        card.addView(createFieldCaption("Pointer Shape"));
        LinearLayout styleRowTop = createChipRow();
        LinearLayout styleRowBottom = createChipRow();
        for (int i = 0; i < STYLE_IDS.length; i++) {
            final int styleId = STYLE_IDS[i];
            Button chip = createOptionChip(SystemPointerController.styleLabel(styleId), v -> {
                CursorConfig.setCursorStyle(this, styleId);
                refreshUi();
            });
            styleButtons[i] = chip;
            LinearLayout targetRow = (i < 2) ? styleRowTop : styleRowBottom;
            int endMargin = (i % 2 == 0) ? dp(6) : 0;
            targetRow.addView(chip, createEqualWeightParams(endMargin));
        }
        card.addView(styleRowTop);
        card.addView(styleRowBottom);

        card.addView(createFieldCaption("Neon Color"));
        LinearLayout colorRow = createChipRow();
        for (int i = 0; i < COLOR_IDS.length; i++) {
            final int colorId = COLOR_IDS[i];
            Button chip = createOptionChip(CursorConfig.colorLabel(colorId), v -> {
                CursorConfig.setCursorColor(this, colorId);
                refreshUi();
            });
            colorButtons[i] = chip;
            colorRow.addView(chip, createEqualWeightParams(i < COLOR_IDS.length - 1 ? dp(6) : 0));
        }
        card.addView(colorRow);

        card.addView(createFieldCaption("Render Mode"));
        LinearLayout modeRow = createChipRow();
        for (int i = 0; i < MODE_IDS.length; i++) {
            final int modeId = MODE_IDS[i];
            Button chip = createOptionChip(CursorConfig.overlayModeLabel(modeId), v -> {
                CursorConfig.setOverlayMode(this, modeId);
                refreshUi();
            });
            modeButtons[i] = chip;
            modeRow.addView(chip, createEqualWeightParams(i < MODE_IDS.length - 1 ? dp(6) : 0));
        }
        card.addView(modeRow);

        LinearLayout scaleHeader = new LinearLayout(this);
        scaleHeader.setOrientation(LinearLayout.HORIZONTAL);
        scaleHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        shLp.topMargin = dp(12);
        scaleHeader.setLayoutParams(shLp);

        TextView scaleLabel = new TextView(this);
        scaleLabel.setText("Cursor Scale (0.5x \u2013 2.5x)");
        scaleLabel.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        scaleLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        scaleLabel.setTextColor(COLOR_TEXT_PRIMARY);
        scaleHeader.addView(scaleLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        scaleValueView = new TextView(this);
        scaleValueView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        scaleValueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        scaleValueView.setTextColor(COLOR_CYAN);
        scaleHeader.addView(scaleValueView);
        card.addView(scaleHeader);

        scaleSeekBar = new SeekBar(this);
        scaleSeekBar.setMax(SCALE_SLIDER_MAX);
        scaleSeekBar.setProgressTintList(ColorStateList.valueOf(COLOR_CYAN));
        scaleSeekBar.setProgressBackgroundTintList(ColorStateList.valueOf(COLOR_BORDER_DIM));
        scaleSeekBar.setThumbTintList(ColorStateList.valueOf(COLOR_MAGENTA));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = dp(4);
        scaleSeekBar.setLayoutParams(sbLp);
        scaleSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (syncingUi || !fromUser) {
                    return;
                }
                float scale = progressToScale(progress);
                scaleValueView.setText(String.format(Locale.US, "%.2fx", scale));
                CursorConfig.Settings cfg = CursorConfig.load(MainActivity.this);
                updatePreviewBitmap(cfg.cursorStyle, scale, cfg.cursorColor);
                CursorConfig.setCursorScale(MainActivity.this, scale);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        card.addView(scaleSeekBar);

        LinearLayout speedHeader = new LinearLayout(this);
        speedHeader.setOrientation(LinearLayout.HORIZONTAL);
        speedHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        spLp.topMargin = dp(10);
        speedHeader.setLayoutParams(spLp);

        TextView speedLabel = new TextView(this);
        speedLabel.setText("Pointer Speed (-7 \u2013 +7)");
        speedLabel.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        speedLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        speedLabel.setTextColor(COLOR_TEXT_PRIMARY);
        speedHeader.addView(speedLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        speedValueView = new TextView(this);
        speedValueView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        speedValueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        speedValueView.setTextColor(COLOR_CYAN);
        speedHeader.addView(speedValueView);
        card.addView(speedHeader);

        speedSeekBar = new SeekBar(this);
        speedSeekBar.setMax(SPEED_SLIDER_MAX);
        speedSeekBar.setProgressTintList(ColorStateList.valueOf(COLOR_CYAN));
        speedSeekBar.setProgressBackgroundTintList(ColorStateList.valueOf(COLOR_BORDER_DIM));
        speedSeekBar.setThumbTintList(ColorStateList.valueOf(COLOR_MAGENTA));
        LinearLayout.LayoutParams ssbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ssbLp.topMargin = dp(4);
        speedSeekBar.setLayoutParams(ssbLp);
        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (syncingUi || !fromUser) {
                    return;
                }
                int speed = progressToPointerSpeed(progress);
                speedValueView.setText(formatSignedSpeed(speed));
                CursorConfig.setPointerSpeed(MainActivity.this, speed);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        card.addView(speedSeekBar);

        card.addView(createDivider(dp(10)));

        rippleSwitch = createNeonSwitch((buttonView, isChecked) -> {
            if (syncingUi) {
                return;
            }
            CursorConfig.setClickRippleEnabled(this, isChecked);
            refreshUi();
        });
        card.addView(buildToggleRow(
                "Click-Ripple Feedback",
                "Expanding neon ring on mouse button clicks",
                rippleSwitch,
                0));

        trailSwitch = createNeonSwitch((buttonView, isChecked) -> {
            if (syncingUi) {
                return;
            }
            CursorConfig.setTrailEnabled(this, isChecked);
            refreshUi();
        });
        card.addView(buildToggleRow(
                "Neon Motion Trail",
                "Fading neon trail behind moving cursor",
                trailSwitch,
                dp(8)));

        return card;
    }

    private LinearLayout buildToggleRow(
            String titleText, String subtitleText, Switch toggle, int topMarginPx) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (topMarginPx > 0) {
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = topMarginPx;
            row.setLayoutParams(rowLp);
        }

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        title.setTextColor(COLOR_TEXT_PRIMARY);
        textCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(subtitleText);
        sub.setTypeface(Typeface.SANS_SERIF);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sub.setTextColor(COLOR_TEXT_SECONDARY);
        textCol.addView(sub);

        row.addView(textCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(toggle);
        return row;
    }

    private View buildShortcutsCard() {
        LinearLayout card = createCard(COLOR_MAGENTA, 0);
        card.addView(createSectionLabel("MOUSE BUTTON SHORTCUTS", COLOR_MAGENTA));

        card.addView(createFieldCaption("Right-Click Action (BUTTON_SECONDARY)"));
        LinearLayout rightRow = createChipRow();
        for (int i = 0; i < SHORTCUT_IDS.length; i++) {
            final int shortcut = SHORTCUT_IDS[i];
            Button chip = createOptionChip(CursorConfig.shortcutLabel(shortcut), v -> {
                CursorConfig.setRightClickAction(this, shortcut);
                refreshUi();
            });
            rightButtons[i] = chip;
            rightRow.addView(chip, createEqualWeightParams(i < SHORTCUT_IDS.length - 1 ? dp(6) : 0));
        }
        card.addView(rightRow);

        card.addView(createFieldCaption("Middle-Click Action (Long-press triggers Recents)"));
        LinearLayout middleRow = createChipRow();
        for (int i = 0; i < SHORTCUT_IDS.length; i++) {
            final int shortcut = SHORTCUT_IDS[i];
            Button chip = createOptionChip(CursorConfig.shortcutLabel(shortcut), v -> {
                CursorConfig.setMiddleClickAction(this, shortcut);
                refreshUi();
            });
            middleButtons[i] = chip;
            middleRow.addView(chip, createEqualWeightParams(i < SHORTCUT_IDS.length - 1 ? dp(6) : 0));
        }
        card.addView(middleRow);

        return card;
    }

    private void handleMasterToggle(boolean enabled) {
        if (enabled) {
            requestOptionalRuntimePermissions();
            CursorService.start(this);
        } else {
            CursorConfig.setServiceEnabled(this, false);
            if (ShizukuEngine.isReady()) {
                int mouseId = Math.max(0, findConnectedMouseId());
                SystemPointerController.resetDefaultIcon(this, mouseId, null);
            }
            CursorService.stop(this);
        }
        refreshUi();
    }

    private void onReapplyClicked() {
        requestOptionalRuntimePermissions();
        CursorService.start(this);
        if (ShizukuEngine.isReady()) {
            ShizukuEngine.grantSelfPrivileges(this);
        }
        CursorConfig.notifyReapplyNow(this);
        refreshUi();
    }

    private void onRequestShizukuClicked() {
        if (ShizukuEngine.isReady()) {
            ShizukuEngine.grantSelfPrivileges(this);
            CursorConfig.notifyReapplyNow(this);
            refreshUi();
            return;
        }
        if (ShizukuEngine.isBinderAlive()) {
            ShizukuEngine.requestPermission();
            return;
        }
        if (!launchShizukuApp("af.shizuku.plus") && !launchShizukuApp("moe.shizuku.privileged.api")) {
            Toast.makeText(this, "Shizuku disconnected \u2014 start Shizuku service first", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean launchShizukuApp(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            return false;
        }
        try {
            startActivity(launch);
            return true;
        } catch (ActivityNotFoundException ignored) {
            return false;
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Unable to open Accessibility Settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "Unable to open Overlay Settings", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void requestOptionalRuntimePermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_RUNTIME_PERMS);
        }
    }

    private void refreshUi() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        CursorConfig.Settings cfg = CursorConfig.load(this);

        boolean binderAlive = ShizukuEngine.isBinderAlive();
        boolean shizukuReady = ShizukuEngine.isReady();
        shizukuActionBtn.setEnabled(true);
        shizukuActionBtn.setAlpha(1.0f);
        if (shizukuReady) {
            int uid = ShizukuEngine.getServerUid();
            shizukuValueView.setText("Connected (UID " + uid + ")");
            shizukuValueView.setTextColor(COLOR_GREEN);
            styleActionButton(shizukuActionBtn, "Re-sync", COLOR_GREEN);
        } else if (binderAlive) {
            shizukuValueView.setText("Permission Needed");
            shizukuValueView.setTextColor(COLOR_MAGENTA);
            styleActionButton(shizukuActionBtn, "Request Shizuku", COLOR_CYAN);
        } else {
            shizukuValueView.setText("Disconnected");
            shizukuValueView.setTextColor(COLOR_ERROR);
            styleActionButton(shizukuActionBtn, "Request Shizuku", COLOR_CYAN);
        }

        String mouseSummary = scanConnectedMouseLabel();
        if (mouseSummary != null) {
            mouseValueView.setText(mouseSummary);
            mouseValueView.setTextColor(COLOR_GREEN);
        } else {
            mouseValueView.setText("No Mouse Detected");
            mouseValueView.setTextColor(COLOR_TEXT_SECONDARY);
        }

        boolean a11yRunning = CursorAccessibilityService.isRunning();
        boolean a11yEnabledInSettings = isAccessibilityServiceEnabled();
        if (a11yRunning && CursorAccessibilityService.isOverlayActive()) {
            a11yValueView.setText("Enabled (Active)");
            a11yValueView.setTextColor(COLOR_GREEN);
            styleActionButton(a11yActionBtn, "Settings", COLOR_CYAN);
        } else if (a11yRunning) {
            a11yValueView.setText("Enabled");
            a11yValueView.setTextColor(COLOR_GREEN);
            styleActionButton(a11yActionBtn, "Settings", COLOR_CYAN);
        } else if (a11yEnabledInSettings) {
            a11yValueView.setText("Enabled (Unbound)");
            a11yValueView.setTextColor(COLOR_MAGENTA);
            styleActionButton(a11yActionBtn, "Settings", COLOR_MAGENTA);
        } else {
            a11yValueView.setText("Missing");
            a11yValueView.setTextColor(COLOR_MAGENTA);
            styleActionButton(a11yActionBtn, "Enable", COLOR_MAGENTA);
        }
        a11yActionBtn.setVisibility(View.VISIBLE);

        boolean overlayGranted = Settings.canDrawOverlays(this);
        overlayValueView.setText(overlayGranted ? "Granted" : "Missing");
        overlayValueView.setTextColor(overlayGranted ? COLOR_GREEN : COLOR_MAGENTA);
        styleActionButton(
                overlayActionBtn,
                overlayGranted ? "Settings" : "Enable",
                overlayGranted ? COLOR_CYAN : COLOR_MAGENTA);
        overlayActionBtn.setVisibility(View.VISIBLE);

        String engineSummary = SystemPointerController.describeActiveEngine(
                cfg.serviceEnabled, cfg.overlayMode, CursorAccessibilityService.isOverlayActive());
        engineValueView.setText(engineSummary);
        if (!cfg.serviceEnabled) {
            engineValueView.setTextColor(COLOR_TEXT_SECONDARY);
        } else if ("Recovering".equals(engineSummary)) {
            engineValueView.setTextColor(COLOR_MAGENTA);
        } else if ("Standby".equals(engineSummary)) {
            engineValueView.setTextColor(COLOR_CYAN);
        } else {
            engineValueView.setTextColor(COLOR_GREEN);
        }

        syncingUi = true;
        try {
            boolean serviceActive = cfg.serviceEnabled && CursorService.isRunning();
            masterSwitch.setChecked(cfg.serviceEnabled);
            masterHintView.setText(serviceActive
                    ? "Foreground daemon running"
                    : (cfg.serviceEnabled ? "Starting foreground daemon..." : "Service stopped"));

            scaleSeekBar.setProgress(scaleToProgress(cfg.cursorScale));
            scaleValueView.setText(String.format(Locale.US, "%.2fx", cfg.cursorScale));
            speedSeekBar.setProgress(pointerSpeedToProgress(cfg.pointerSpeed));
            speedValueView.setText(formatSignedSpeed(cfg.pointerSpeed));
            rippleSwitch.setChecked(cfg.clickRippleEnabled);
            trailSwitch.setChecked(cfg.trailEnabled);
        } finally {
            syncingUi = false;
        }

        updatePreviewBitmap(cfg.cursorStyle, cfg.cursorScale, cfg.cursorColor);

        for (int i = 0; i < STYLE_IDS.length; i++) {
            boolean selected = (cfg.cursorStyle == STYLE_IDS[i]);
            styleChip(styleButtons[i], selected, COLOR_CYAN);
        }
        for (int i = 0; i < COLOR_IDS.length; i++) {
            boolean selected = (cfg.cursorColor == COLOR_IDS[i]);
            int accent = CursorConfig.resolvePrimaryColor(COLOR_IDS[i]);
            styleChip(colorButtons[i], selected, accent);
        }
        for (int i = 0; i < MODE_IDS.length; i++) {
            boolean selected = (cfg.overlayMode == MODE_IDS[i]);
            styleChip(modeButtons[i], selected, COLOR_MAGENTA);
        }
        for (int i = 0; i < SHORTCUT_IDS.length; i++) {
            styleChip(rightButtons[i], cfg.rightClickAction == SHORTCUT_IDS[i], COLOR_MAGENTA);
            styleChip(middleButtons[i], cfg.middleClickAction == SHORTCUT_IDS[i], COLOR_CYAN);
        }
    }

    private void updatePreviewBitmap(int style, float scale, int colorPreset) {
        if (previewBitmap != null
                && !previewBitmap.isRecycled()
                && lastPreviewStyle == style
                && Math.abs(lastPreviewScale - scale) < 0.001f
                && lastPreviewColor == colorPreset) {
            return;
        }
        Bitmap next = SystemPointerController.buildCursorBitmap(style, scale, colorPreset);
        cursorPreviewView.setImageBitmap(next);
        if (previewBitmap != null && previewBitmap != next && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
        }
        previewBitmap = next;
        lastPreviewStyle = style;
        lastPreviewScale = scale;
        lastPreviewColor = colorPreset;
    }

    private String scanConnectedMouseLabel() {
        if (inputManager == null) {
            return CursorService.isMouseConnected() ? CursorService.getConnectedMouseName() : null;
        }
        int[] ids = inputManager.getInputDeviceIds();
        int count = 0;
        String firstName = null;
        for (int id : ids) {
            InputDevice device = inputManager.getInputDevice(id);
            if (!isExternalMouse(device)) {
                continue;
            }
            count++;
            if (firstName == null) {
                CharSequence raw = device.getName();
                String trimmed = raw != null ? raw.toString().trim() : "";
                firstName = trimmed.isEmpty() ? ("Mouse #" + id) : trimmed;
            }
        }
        if (count == 0) {
            return null;
        }
        return count > 1 ? (firstName + " (+" + (count - 1) + ")") : firstName;
    }

    private int findConnectedMouseId() {
        if (inputManager == null) {
            return -1;
        }
        for (int id : inputManager.getInputDeviceIds()) {
            if (isExternalMouse(inputManager.getInputDevice(id))) {
                return id;
            }
        }
        return -1;
    }

    private static boolean isExternalMouse(InputDevice device) {
        if (device == null || device.isVirtual()) {
            return false;
        }
        return device.supportsSource(InputDevice.SOURCE_MOUSE)
                || device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE);
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        ComponentName target = new ComponentName(this, CursorAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName item = ComponentName.unflattenFromString(splitter.next());
            if (target.equals(item)) {
                return true;
            }
        }
        return false;
    }

    private static int scaleToProgress(float scale) {
        float clamped = SystemPointerController.clampScale(scale);
        int progress = Math.round((clamped - SystemPointerController.MIN_SCALE) / SCALE_STEP);
        return Math.max(0, Math.min(SCALE_SLIDER_MAX, progress));
    }

    private static float progressToScale(int progress) {
        int clamped = Math.max(0, Math.min(SCALE_SLIDER_MAX, progress));
        return SystemPointerController.clampScale(
                SystemPointerController.MIN_SCALE + (clamped * SCALE_STEP));
    }

    private static int pointerSpeedToProgress(int speed) {
        int clamped = SystemPointerController.clampPointerSpeed(speed);
        return clamped - SystemPointerController.MIN_POINTER_SPEED;
    }

    private static int progressToPointerSpeed(int progress) {
        int clamped = Math.max(0, Math.min(SPEED_SLIDER_MAX, progress));
        return SystemPointerController.clampPointerSpeed(
                SystemPointerController.MIN_POINTER_SPEED + clamped);
    }

    private static String formatSignedSpeed(int speed) {
        if (speed > 0) {
            return "+" + speed;
        }
        return String.valueOf(speed);
    }

    private LinearLayout createCard(int borderColor, int bottomMarginDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.setBackground(createCardDrawable(COLOR_CARD_BG, borderColor, dp(12)));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(bottomMarginDp);
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout buildStatusRow(String keyLabel, TextView valueView, Button actionButton) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(6);
        row.setLayoutParams(rowLp);

        TextView keyView = new TextView(this);
        keyView.setText(keyLabel);
        keyView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        keyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        keyView.setTextColor(COLOR_TEXT_SECONDARY);
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(
                dp(108), LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(keyView, keyLp);

        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(valueView, valLp);

        if (actionButton != null) {
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            btnLp.setMarginStart(dp(8));
            row.addView(actionButton, btnLp);
        }
        return row;
    }

    private TextView createStatusValueText() {
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(COLOR_TEXT_PRIMARY);
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        return tv;
    }

    private Button createActionButton(
            String label, int borderColor, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setAllCaps(false);
        btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btn.setMinHeight(dp(30));
        btn.setMinimumHeight(dp(30));
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setPadding(dp(10), dp(4), dp(10), dp(4));
        btn.setStateListAnimator(null);
        styleActionButton(btn, label, borderColor);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void styleActionButton(Button btn, String label, int borderColor) {
        btn.setText(label);
        btn.setTextColor(borderColor);
        btn.setBackground(createInteractiveChipDrawable(COLOR_CHIP_BG, borderColor, dp(6)));
    }

    private Button createOptionChip(String label, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setAllCaps(false);
        btn.setText(label);
        btn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btn.setMinHeight(dp(36));
        btn.setMinimumHeight(dp(36));
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setPadding(dp(4), dp(6), dp(4), dp(6));
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setStateListAnimator(null);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void styleChip(Button chip, boolean selected, int accentColor) {
        int bgColor = selected ? ((accentColor & 0x00FFFFFF) | 0x26000000) : COLOR_CHIP_BG;
        int borderColor = selected ? accentColor : COLOR_BORDER_DIM;
        int textColor = selected ? accentColor : COLOR_TEXT_SECONDARY;
        chip.setBackground(createInteractiveChipDrawable(bgColor, borderColor, dp(8)));
        chip.setTextColor(textColor);
    }

    private LinearLayout createChipRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private LinearLayout.LayoutParams createEqualWeightParams(int endMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(endMarginPx);
        return lp;
    }

    private TextView createSectionLabel(String text, int accentColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setLetterSpacing(0.08f);
        tv.setTextColor(accentColor);
        return tv;
    }

    private TextView createFieldCaption(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTextColor(COLOR_TEXT_PRIMARY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View createDivider(int verticalMarginPx) {
        View v = new View(this);
        v.setBackgroundColor(COLOR_BORDER_DIM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = verticalMarginPx;
        lp.bottomMargin = verticalMarginPx / 2;
        v.setLayoutParams(lp);
        return v;
    }

    private Switch createNeonSwitch(CompoundButton.OnCheckedChangeListener listener) {
        Switch sw = new Switch(this);
        int[][] states = new int[][]{
            new int[]{android.R.attr.state_checked},
            new int[]{}
        };
        sw.setThumbTintList(new ColorStateList(states, new int[]{COLOR_CYAN, 0xFFCFD8DC}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{0x8800F0FF, 0xFF3E4E56}));
        sw.setOnCheckedChangeListener(listener);
        return sw;
    }

    private GradientDrawable createCardDrawable(int fillColor, int borderColor, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        int strokePx = Math.max(2, dp(1));
        d.setStroke(strokePx, borderColor);
        return d;
    }

    private StateListDrawable createInteractiveChipDrawable(
            int fillColor, int borderColor, int radiusPx) {
        int hoverFill = (borderColor & 0x00FFFFFF) | 0x33000000;
        StateListDrawable sld = new StateListDrawable();
        sld.addState(
                new int[]{android.R.attr.state_pressed},
                createCardDrawable(hoverFill, COLOR_MAGENTA, radiusPx));
        sld.addState(
                new int[]{android.R.attr.state_hovered},
                createCardDrawable(hoverFill, COLOR_CYAN, radiusPx));
        sld.addState(new int[]{}, createCardDrawable(fillColor, borderColor, radiusPx));
        return sld;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
