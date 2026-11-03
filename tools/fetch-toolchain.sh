#!/usr/bin/env bash
# 一键拉取构建工具链（约 800MB）：JDK17 + Kotlin 编译器 + 调试签名
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$ROOT/tools"
JDK_DIR="$TOOLS/jdk17"
KOTLIN_DIR="$TOOLS/kotlinc"
KOTLIN_VER="${KOTLIN_VER:-2.0.21}"
mkdir -p "$TOOLS"

ARCH=$(uname -m)
case "$ARCH" in
  x86_64|amd64) JDK_ARCH="x64" ;;
  aarch64|arm64) JDK_ARCH="aarch64" ;;
  *) echo "unsupported arch: $ARCH"; exit 1 ;;
esac

if [ ! -x "$JDK_DIR/bin/javac" ]; then
  echo "==> JDK 17"
  JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/${JDK_ARCH}/jdk/hotspot/normal/eclipse"
  curl -L --fail -o /tmp/jdk17.tgz "$JDK_URL"
  mkdir -p "$JDK_DIR"
  tar xzf /tmp/jdk17.tgz -C "$JDK_DIR" --strip-components=1
  rm -f /tmp/jdk17.tgz
fi

if [ ! -x "$KOTLIN_DIR/bin/kotlinc" ]; then
  echo "==> Kotlin $KOTLIN_VER"
  curl -L --fail -o /tmp/kotlinc.zip \
    "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VER}/kotlin-compiler-${KOTLIN_VER}.zip"
  unzip -q -o /tmp/kotlinc.zip -d "$TOOLS"
  rm -f /tmp/kotlinc.zip
fi

if [ ! -f "$TOOLS/debug.keystore" ]; then
  echo "==> debug keystore"
  "$JDK_DIR/bin/keytool" -genkeypair -keystore "$TOOLS/debug.keystore" \
    -storepass android -keypass android -alias androiddebugkey \
    -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

if [ ! -f "$TOOLS/shellbridge.jks" ]; then
  echo "==> shellbridge debug key"
  "$JDK_DIR/bin/keytool" -genkeypair -keystore "$TOOLS/shellbridge.jks" \
    -storepass 123456 -keypass 123456 -alias shellbridge \
    -dname "CN=ShellBridge Debug" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

# shellbridge 的 build.sh 期望 $SCRIPT_DIR/tools/{jdk17,android-sdk,shellbridge.jks}
mkdir -p "$ROOT/software/shellbridge/tools"
for n in jdk17 android-sdk shellbridge.jks; do
  [ -e "$ROOT/software/shellbridge/tools/$n" ] || ln -s "$TOOLS/$n" "$ROOT/software/shellbridge/tools/$n" 2>/dev/null || true
done

echo
echo "工具链就绪："
"$JDK_DIR/bin/java" -version 2>&1 | head -1
"$KOTLIN_DIR/bin/kotlinc" -version 2>&1 | head -1 || true
echo
echo "请先执行："
echo "  export JAVA_HOME=$JDK_DIR"
echo "  export ANDROID_TOOLS=$TOOLS"
echo "  export PATH=\$JAVA_HOME/bin:\$PATH"