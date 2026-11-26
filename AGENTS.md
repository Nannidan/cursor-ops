# Project Constraints
- Target: Unrooted Google Pixel 8 Pro (Android 17 QPR3 Beta). NO root, NO su, NO Magisk.
- Build: CLI-only in Debian AVF (`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`). No Gradle/Android Studio.
- Architecture:
  1. Primary: Shizuku privileged binder service (UID 2000) using reflection on `IInputManager.setCustomPointerIcon()`.
  2. Fallback/Features: `AccessibilityService` + `SYSTEM_ALERT_WINDOW` overlay tracking `MotionEvent` (`InputDevice.SOURCE_MOUSE`) for custom shapes, trails, click-ripples, and button remapping.
  3. Reliability: Foreground service + Shizuku `addBinderDeadListener` + `InputManager.InputDeviceListener` for auto-reapply on Bluetooth mouse reconnect.
- UI: Pure `#000000` AMOLED background, 1px neon cyan/magenta borders, high-contrast text.
- Rules: Atomic edits (1–2 files per step). No placeholders or elisions. Run `./build.sh` after every edit.
