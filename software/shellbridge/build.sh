#!/usr/bin/env bash
# ============================================================
# ShellBridge — TCP Shell Bridge for Restricted Android
# Build: Pure Java, no Kotlin/Gradle/AndroidX dependencies
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/src"
BUILD="$SCRIPT_DIR/build"
DIST="$SCRIPT_DIR/dist"
TOOLS="$SCRIPT_DIR/tools"
SDK_DIR="$TOOLS/android-sdk"
PLATFORM_DIR="$SDK_DIR/platforms/android-31"
BUILD_TOOLS="$SDK_DIR/build-tools/34.0.0"
ANDROID_JAR="$PLATFORM_DIR/android.jar"
D8="$BUILD_TOOLS/d8"
AAPT="$BUILD_TOOLS/aapt"
APKSIGNER="$BUILD_TOOLS/apksigner"
KEYSTORE="$TOOLS/shellbridge.jks"
KEYSTORE_PASS="123456"
KEY_ALIAS="shellbridge"
APK_UNSIGNED="$DIST/ShellBridge-unsigned.apk"
APK_FINAL="$DIST/ShellBridge.apk"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[✓]${NC} $1"; }
warn()  { echo -e "${YELLOW}[!]${NC} $1"; }

cleanup() {
    rm -rf "$BUILD"
    info "Build artifacts cleaned"
}

# === Step 0: Download dependencies (one-time) ===
mkdir -p "$TOOLS" "$BUILD" "$DIST"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "Downloading android-31 platform..."
    mkdir -p "$PLATFORM_DIR"
    curl -#L -o /tmp/p31.zip \
        "https://dl.google.com/android/repository/platform-31_r02.zip"
    unzip -q -o /tmp/p31.zip -d /tmp/p31extract/
    cp "$(find /tmp/p31extract/ -name android.jar | head -1)" "$ANDROID_JAR"
    rm -rf /tmp/p31.zip /tmp/p31extract/
    info "android-31.jar installed"
fi

if [ ! -f "$D8" ]; then
    echo "Downloading build-tools 34.0.0..."
    mkdir -p "$SDK_DIR"
    curl -#L -o /tmp/bt34.zip \
        "https://dl.google.com/android/repository/build-tools_r34-linux.zip"
    unzip -q -o /tmp/bt34.zip -d "$SDK_DIR/"
    # build-tools 34 extracts as android-14
    EXTRACTED="$SDK_DIR/android-14"
    if [ -d "$EXTRACTED" ]; then
        mkdir -p "$(dirname "$BUILD_TOOLS")"
        mv "$EXTRACTED" "$BUILD_TOOLS"
    else
        # Try alternative extraction name
        mkdir -p "$BUILD_TOOLS"
        mv "$SDK_DIR"/*/d8 "$BUILD_TOOLS/" 2>/dev/null || true
    fi
    rm -rf /tmp/bt34.zip
    info "build-tools 34.0.0 installed"
fi

# === Step 1: Compile Java ===
info "Compiling Java sources..."
# Use JDK 17. JDK 21 + D8 have an InnerClass attribute incompatibility.
JAVAC="$SCRIPT_DIR/tools/jdk17/bin/javac"

find "$SRC" -name "*.java" > "$TOOLS/sources.txt"
"$JAVAC" \
    -source 1.8 -target 1.8 \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD/classes" \
    "@$TOOLS/sources.txt"

info "Java compilation done"

# === Step 2: Jar classes then DEX ===
info "Jarring classes..."
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .

info "Converting to DEX..."
mkdir -p "$BUILD/dex"
"$D8" \
    --lib "$ANDROID_JAR" \
    --min-api 28 \
    --output "$BUILD/dex" \
    "$BUILD/classes.jar"

info "DEX conversion done"

# === Step 3: Package APK ===
info "Packaging APK..."
"$AAPT" package \
    -f \
    -M "$SCRIPT_DIR/AndroidManifest.xml" \
    -I "$ANDROID_JAR" \
    -F "$APK_UNSIGNED" \
    "$BUILD/dex"

info "APK packaged: $(basename "$APK_UNSIGNED")"

# === Step 4: Generate keystore (if missing) ===
if [ ! -f "$KEYSTORE" ]; then
    warn "No keystore found, generating a new one..."
    keytool -genkey \
        -keystore "$KEYSTORE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KEYSTORE_PASS" \
        -keypass "$KEYSTORE_PASS" \
        -dname "CN=ShellBridge, O=Vox, C=CN" 2>/dev/null || true
    info "Keystore created"
fi

# === Step 5: Sign APK ===
info "Signing APK..."
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEYSTORE_PASS" \
    --out "$APK_FINAL" \
    "$APK_UNSIGNED"

info "APK signed: $(basename "$APK_FINAL")"

# === Final ===
APK_SIZE=$(stat -c%s "$APK_FINAL" 2>/dev/null || stat -f%z "$APK_FINAL" 2>/dev/null || echo "?")
echo -e "${GREEN}═══════════════════════════════════════${NC}"
echo -e "${GREEN}  ShellBridge ready!${NC}"
echo -e "${GREEN}  APK: $APK_FINAL${NC}"
echo -e "${GREEN}  Size: $((APK_SIZE / 1024)) KB${NC}"
echo -e "${GREEN}  Port: 8888${NC}"
echo -e "${GREEN}═══════════════════════════════════════${NC}"
echo ""
echo "Usage:"
echo "  1. Copy dist/ShellBridge.apk to a USB drive"
echo "  2. Install on car head unit via file manager"
echo "  3. Launch the app (icon: ShellBridge)"
echo "  4. From your PC: nc <TS_IP> 8888"
echo "  5. You now have an interactive shell!"
echo ""
echo "  Or one-liner: echo 'getprop | head -20' | nc -w 2 <TS_IP> 8888"
