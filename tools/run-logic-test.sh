#!/data/data/com.termux/files/usr/bin/bash
# 在 JVM 上跑纯逻辑单测（复用本地构建出来的 classes）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="${ANDROID_TOOLCHAIN:-$HOME/android-toolchain}"
AJ="${ANDROID_JAR:-$TC/sdk/android.jar}"
CLASSES="$ROOT/build-local/classes"
[ -d "$CLASSES" ] || { echo "先跑 tools/build-local.sh 生成 classes"; exit 1; }
mkdir -p "$ROOT/build-local/test"
javac -nowarn -encoding UTF-8 -source 17 -target 17 -cp "$AJ:$CLASSES" \
  -d "$ROOT/build-local/test" "$ROOT/tools/LogicTest.java"
java -cp "$AJ:$CLASSES:$ROOT/build-local/test" com.dsh.launcher.LogicTest
