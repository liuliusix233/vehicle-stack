#!/usr/bin/env bash
# ============================================================
# Vox HUD Dashboard — Build Script
# ============================================================
# Builds HudDashboard.apk from source.
# Downloads missing tools automatically (one-time).
#
# Usage: ./build.sh [--clean]
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/app/src/main"
BUILD="$SCRIPT_DIR/build"
DIST="$SCRIPT_DIR/dist"
TOOLS="$SCRIPT_DIR/tools"
SDK_DIR="$TOOLS/android-sdk"
PLATFORM_DIR="$SDK_DIR/platforms/android-27"
BUILD_TOOLS="$SDK_DIR/build-tools/29.0.3"
KOTLIN_DIR="$TOOLS/kotlinc"
KOTLINC="$KOTLIN_DIR/bin/kotlinc"
ANDROID_JAR="$PLATFORM_DIR/android.jar"
D8="$BUILD_TOOLS/d8"

# Colors
GREEN='\033[0;32m'; NC='\033[0m'
info() { echo -e "${GREEN}[✓]${NC} $1"; }

# === Step 0: Download dependencies (one-time) ===
mkdir -p "$TOOLS"

# Kotlin
if [ ! -f "$KOTLINC" ]; then
    echo "Downloading Kotlin compiler..."
    curl -#L -o /tmp/kotlin.zip \
        "https://github.com/JetBrains/kotlin/releases/download/v1.9.20/kotlin-compiler-1.9.20.zip"
    unzip -q -o /tmp/kotlin.zip -d /tmp/kextract/
    mv /tmp/kextract/kotlinc "$KOTLIN_DIR"
    rm -rf /tmp/kotlin.zip /tmp/kextract/
    info "Kotlin installed"
fi

# Android platform 27
if [ ! -f "$ANDROID_JAR" ]; then
    echo "Downloading android-27 platform..."
    mkdir -p "$PLATFORM_DIR"
    curl -#L -o /tmp/p27.zip \
        "https://dl.google.com/android/repository/platform-27_r03.zip"
    unzip -q -o /tmp/p27.zip -d /tmp/p27extract/
    cp "$(find /tmp/p27extract/ -name android.jar | head -1)" "$ANDROID_JAR"
    rm -rf /tmp/p27.zip /tmp/p27extract/
    info "android-27.jar installed"
fi

# Build-tools (for d8)
if [ ! -f "$D8" ]; then
    echo "Downloading build-tools 29.0.3..."
    SDKMAN="$TOOLS/cmdline-tools/latest/bin/sdkmanager"
    if [ ! -f "$SDKMAN" ]; then
        mkdir -p "$TOOLS/cmdline-tools/latest"
        curl -#L -o /tmp/ct.zip \
            "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
        unzip -q -o /tmp/ct.zip -d /tmp/ctextract/
        mv /tmp/ctextract/cmdline-tools/* "$TOOLS/cmdline-tools/latest/"
        rm -rf /tmp/ct.zip /tmp/ctextract/
    fi
    yes | "$SDKMAN" --sdk_root="$SDK_DIR" "build-tools;29.0.3" 2>&1 | tail -3
    info "Build-tools installed"
fi

# Verify
for f in "$KOTLINC" "$ANDROID_JAR" "$D8"; do
    [ -f "$f" ] || { echo "Missing: $f"; exit 1; }
done

# === Clean ===
rm -rf "$BUILD" "$DIST"
mkdir -p "$BUILD" "$DIST"

# === 1/5: Compile Kotlin → JAR (with runtime) ===
echo ""
info "Compiling Kotlin..."
JAVA_OPTS="-Xmx256M -Xms128M -XX:CompressedClassSpaceSize=128M -XX:+UseCompressedOops" \
    $KOTLINC -cp "$ANDROID_JAR" -d "$BUILD/classes.jar" -include-runtime \
    "$SRC/java/com/vox/hud/"*.kt 2>&1

# === 2/5: DEX ===
info "Converting to DEX..."
mkdir -p "$BUILD/obj" && "$D8" --release --lib "$ANDROID_JAR" --output "$BUILD/obj" \
    "$BUILD/classes.jar" 2>&1
[ -f "$BUILD/obj/classes.dex" ] || { echo "DEX failed"; exit 1; }

# === 3/5: Package resources + inject DEX ===
info "Packaging resources..."
"$BUILD_TOOLS/aapt" package -f -M "$SRC/AndroidManifest.xml" -S "$SRC/res" -A "$SRC/assets" \
    -I "$ANDROID_JAR" -F "$BUILD/unsigned.apk" \
    --target-sdk-version 27 --min-sdk-version 27 2>&1

python3 -c "
import zipfile
with zipfile.ZipFile('$BUILD/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as zf:
    zf.write('$BUILD/obj/classes.dex', 'classes.dex')
"
mv "$BUILD/unsigned.apk" "$BUILD/with-dex.apk"

# === 4/5: Align ===
info "Aligning..."
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD/with-dex.apk" "$DIST/HudDashboard-aligned.apk" 2>&1

# === 5/5: Sign ===
info "Signing..."
KEYSTORE="$TOOLS/debug.keystore"
[ -f "$KEYSTORE" ] || keytool -genkey -v -keystore "$KEYSTORE" \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Debug" 2>&1

"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" \
    --ks-pass pass:android --ks-key-alias androiddebugkey \
    --key-pass pass:android \
    --out "$DIST/HudDashboard.apk" \
    "$DIST/HudDashboard-aligned.apk" 2>&1

# === Done ===
echo ""
echo "========================================"
info "HudDashboard.apk ($(du -h "$DIST/HudDashboard.apk" | cut -f1))"
echo ""
echo "  Install:"
echo "  adb connect <HUD_IP>:5555"
echo "  adb install -r $DIST/HudDashboard.apk"
echo "========================================"
