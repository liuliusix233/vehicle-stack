#!/usr/bin/env bash
# ============================================================
# CastSink M1 — Build Script（照抄 hud-apk/build.sh 流程）
# Kotlin → classes.jar → d8 → aapt → zipalign → apksigner
# 工具链复用 hud-apk/tools；输出 dist/CastSink.apk
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main"
BUILD="$ROOT/build"
DIST="$ROOT/dist"
TOOLS="$ANDROID_TOOLS"
KOTLINC="$TOOLS/kotlinc/bin/kotlinc"
BUILD_TOOLS="$TOOLS/android-sdk/build-tools/29.0.3"
ANDROID_JAR="$TOOLS/android-sdk/platforms/android-27/android.jar"
D8="$BUILD_TOOLS/d8"
KEYSTORE="$TOOLS/debug.keystore"

for f in "$KOTLINC" "$ANDROID_JAR" "$D8" "$KEYSTORE"; do
    [ -f "$f" ] || { echo "Missing: $f"; exit 1; }
done

rm -rf "$BUILD" "$DIST"
mkdir -p "$BUILD" "$DIST" "$SRC/assets"

# === 1/5: Compile Kotlin → JAR (with runtime) ===
echo "[1/5] Compiling Kotlin..."
JAVA_OPTS="-Xmx256M -Xms128M -XX:CompressedClassSpaceSize=128M -XX:+UseCompressedOops" \
    "$KOTLINC" -cp "$ANDROID_JAR" -d "$BUILD/classes.jar" -include-runtime \
    "$SRC/java/com/vox/castsink/"*.kt \
    "$SRC/java/com/vox/castsink/ShellService.java" \
    "$SRC/java/com/vox/castsink/CarScanner.java" \
    "$SRC/java/com/vox/castsink/VehicleReceiver.java" 2>&1

# === 1b/5: Java (merged ShellBridge) ===
echo "[1b/5] Compiling merged Java..."
JAVAC="/home/<USER>/.openclaw/workspace/jarvis/shell-bridge/tools/jdk17/bin/javac"
[ -x "$JAVAC" ] || { echo "Missing: $JAVAC"; exit 1; }
mkdir -p "$BUILD/javaclasses"
"$JAVAC" -source 1.8 -target 1.8 -encoding UTF-8 -classpath "$ANDROID_JAR:$BUILD/classes.jar" \
    -d "$BUILD/javaclasses" \
    "$SRC/java/com/vox/castsink/ShellService.java" \
    "$SRC/java/com/vox/castsink/CarScanner.java" \
    "$SRC/java/com/vox/castsink/VehicleReceiver.java" 2>&1
"/home/<USER>/.openclaw/workspace/jarvis/shell-bridge/tools/jdk17/bin/jar" cf "$BUILD/javaclasses.jar" -C "$BUILD/javaclasses" .

# === 2/5: DEX ===
echo "[2/5] Converting to DEX..."
mkdir -p "$BUILD/obj"
JAVA_OPTS="-Xmx2g" "$D8" --release --min-api 27 --lib "$ANDROID_JAR" --output "$BUILD/obj" "$BUILD/classes.jar" "$BUILD/javaclasses.jar" 2>&1
[ -f "$BUILD/obj/classes.dex" ] || { echo "DEX failed"; exit 1; }

# === 3/5: Package resources + inject DEX ===
echo "[3/5] Packaging resources..."
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
echo "[4/5] Aligning..."
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD/with-dex.apk" "$BUILD/aligned.apk" 2>&1

# === 5/5: Sign ===
echo "[5/5] Signing..."
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" \
    --ks-pass pass:android --ks-key-alias androiddebugkey \
    --key-pass pass:android \
    --out "$DIST/CastSink.apk" \
    "$BUILD/aligned.apk" 2>&1

echo ""
echo "Done: $DIST/CastSink.apk ($(du -h "$DIST/CastSink.apk" | cut -f1))"
