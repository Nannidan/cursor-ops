#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="/home/droid/android-sdk"
ANDROID_JAR="$SDK_DIR/platforms/android-37.1/android.jar"
AAPT2="/usr/local/bin/aapt2"
D8="$SDK_DIR/cmdline-tools/latest/bin/d8"
ZIPALIGN="/usr/bin/zipalign"
APKSIGNER="/usr/bin/apksigner"

BUILD_DIR="$PROJECT_DIR/build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/res_compiled" "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/apk"

SHIZUKU_VERSION="13.1.5"
mkdir -p "$PROJECT_DIR/libs"
for pkg in api provider aidl shared; do
  jar_path="$PROJECT_DIR/libs/shizuku-${pkg}.jar"
  if ! test -s "$jar_path"; then
    echo "Downloading dev.rikka.shizuku:${pkg}:${SHIZUKU_VERSION}..."
    tmp_aar="$BUILD_DIR/shizuku-${pkg}-${SHIZUKU_VERSION}.aar"
    curl -fsSL "https://repo1.maven.org/maven2/dev/rikka/shizuku/${pkg}/${SHIZUKU_VERSION}/${pkg}-${SHIZUKU_VERSION}.aar" -o "$tmp_aar"
    unzip -p "$tmp_aar" classes.jar > "$jar_path"
    rm -f "$tmp_aar"
  fi
done

LIBS_CP=""
LIBS_JARS=()
for j in "$PROJECT_DIR/libs"/*.jar; do
  test -f "$j" || continue
  LIBS_CP="${LIBS_CP}:${j}"
  LIBS_JARS+=("$j")
done

echo "[1/6] Compiling resources..."
"$AAPT2" compile --dir "$PROJECT_DIR/res" -o "$BUILD_DIR/res_compiled/resources.zip"

echo "[2/6] Linking resources..."
"$AAPT2" link \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT_DIR/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version 34 \
  --target-sdk-version 35 \
  -o "$BUILD_DIR/apk/unaligned.apk" \
  "$BUILD_DIR/res_compiled/resources.zip"

echo "[3/6] Compiling Java..."
mapfile -t JAVA_FILES < <(find "$PROJECT_DIR/src" "$BUILD_DIR/gen" -name "*.java" | sort)
javac --release 11 -cp "${ANDROID_JAR}${LIBS_CP}" -d "$BUILD_DIR/classes" "${JAVA_FILES[@]}"

echo "[4/6] Dexing..."
mapfile -t CLASS_FILES < <(find "$BUILD_DIR/classes" -name "*.class" | sort)
"$D8" --min-api 34 --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" "${CLASS_FILES[@]}" "${LIBS_JARS[@]}"

echo "[5/6] Packaging & zipaligning..."
cp "$BUILD_DIR/apk/unaligned.apk" "$BUILD_DIR/apk/with_dex.apk"
(cd "$BUILD_DIR/dex" && zip -q -u "$BUILD_DIR/apk/with_dex.apk" classes*.dex)
"$ZIPALIGN" -f -p 4 "$BUILD_DIR/apk/with_dex.apk" "$BUILD_DIR/apk/aligned.apk"

echo "[6/6] Signing APK..."
KEYSTORE_DIR="$HOME/.android"
KEYSTORE="$KEYSTORE_DIR/cursorops_debug.jks"
PASS_FILE="$KEYSTORE_DIR/.cursorops_pass"
mkdir -p "$KEYSTORE_DIR"

if ! test -s "$PASS_FILE"; then
  (umask 077 && python3 -c 'import secrets,string; print("".join(secrets.choice(string.ascii_letters+string.digits) for _ in range(24)), end="")' > "$PASS_FILE")
fi
chmod 600 "$PASS_FILE"
export CURSOROPS_KS_PASS
CURSOROPS_KS_PASS="$(cat "$PASS_FILE")"

if ! test -f "$KEYSTORE"; then
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass:env CURSOROPS_KS_PASS \
    -keypass:env CURSOROPS_KS_PASS \
    -alias cursorops \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=CursorOps,OU=Android,O=Nannidan,L=Local,ST=Local,C=US" >/dev/null 2>&1
fi

OUT_APK="$BUILD_DIR/cursor-ops.apk"
"$APKSIGNER" sign \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --ks "$KEYSTORE" \
  --ks-key-alias cursorops \
  --ks-pass env:CURSOROPS_KS_PASS \
  --key-pass env:CURSOROPS_KS_PASS \
  --out "$OUT_APK" \
  "$BUILD_DIR/apk/aligned.apk"

unset CURSOROPS_KS_PASS
cp -f "$OUT_APK" "$BUILD_DIR/CursorOps.apk"

ls -lh "$OUT_APK"
echo "BUILD SUCCESS: $OUT_APK"
