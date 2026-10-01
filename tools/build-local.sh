#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
#  在 Termux 本地直接编译 APK（不依赖 Gradle / Android Studio / CI）
#     aapt2 编译资源 -> javac 编译 Java -> d8 转 dex -> 打包 -> apksigner 签名
#  用法： bash tools/build-local.sh [输出目录]
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/build-local}"
TC="${ANDROID_TOOLCHAIN:-$HOME/android-toolchain}"
AJ="${ANDROID_JAR:-$TC/sdk/android.jar}"
SZ="${SHIZUKU_JAR:-$TC/sdk/shizuku-api.jar}"

APP="$ROOT/app/src/main"
PKG="com.dsh.launcher"
VER_CODE="${VER_CODE:-2}"
VER_NAME="${VER_NAME:-2.0}"
MIN_SDK=26
TARGET_SDK=34

log() { printf '\n\033[1m== %s ==\033[0m\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "缺少命令：$1"; exit 1; }; }

for c in aapt2 javac d8 apksigner keytool python3; do need "$c"; done
[ -s "$AJ" ] || { echo "缺少 android.jar：$AJ"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/res"

log "1/6 aapt2 compile 资源"
aapt2 compile --dir "$APP/res" -o "$OUT/res.zip"

log "2/6 aapt2 link（生成资源表 + R.java）"
# 源码 manifest 走 AGP 的 namespace 写法，没有 package 属性，也带 ${applicationId} 占位符。
# 本地用 aapt2 直编时补上这两样，生成一份等价副本，不动源码。
python3 - "$APP/AndroidManifest.xml" "$OUT/AndroidManifest.xml" "$PKG" <<'PYEOF'
import sys
src, dst, pkg = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(src, encoding='utf-8').read()
s = s.replace('${applicationId}', pkg)
s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android">',
              '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="%s">' % pkg, 1)
open(dst, 'w', encoding='utf-8').write(s)
print('manifest 已生成：', dst)
PYEOF
aapt2 link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest "$OUT/AndroidManifest.xml" \
  -A "$APP/assets" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VER_CODE" \
  --version-name "$VER_NAME" \
  --no-version-vectors \
  "$OUT/res.zip"

log "3/6 javac 编译 Java"
CP="$AJ"
[ -s "$SZ" ] && CP="$CP:$SZ"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -cp "$CP" -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v '警告\|warning' || true
[ -f "$OUT/classes/${PKG//.//}/R.class" ] || { echo "javac 产物缺失"; exit 1; }

log "4/6 d8 转 dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
d8 --lib "$AJ" --min-api "$MIN_SDK" --output "$OUT/dex" @"$OUT/classes.txt"

log "5/6 打包 APK"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('classes.dex 已写入 APK')
PYEOF

log "6/6 签名"
KS="$ROOT/tools/debug.keystore"
if [ ! -s "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
APK="$OUT/dsh-launcher-$VER_NAME.apk"
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APK" "$OUT/unsigned.apk"
apksigner verify --print-certs "$APK" | head -3

log "完成"
ls -lh "$APK"
