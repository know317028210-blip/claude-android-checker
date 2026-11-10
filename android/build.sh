#!/usr/bin/env bash
# 一键构建 APK 并安装到已连接的安卓设备
set -e
cd "$(dirname "$0")"

BT="/c/Android/Sdk/build-tools/36.0.0"
JAR="/c/Android/Sdk/platforms/android-34/android.jar"
KS="keystore.jks"
PASS="claude123"

echo "[1/6] 同步页面资源"
cp ../app/index.html assets/index.html

echo "[2/6] 编译资源"
rm -rf build && mkdir -p build/dex build/classes
"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -o build/base.apk -I "$JAR" \
  --manifest AndroidManifest.xml -R build/res.zip -A assets \
  --java build/gen --auto-add-overlay

echo "[3/6] 编译 Java"
javac -source 8 -target 8 -bootclasspath "$JAR" -d build/classes \
  build/gen/com/claudedoctor/app/R.java \
  app/src/com/claudedoctor/MainActivity.java \
  > build/javac.log 2>&1 || { echo "Java 编译失败:"; grep -E "错误|error:" build/javac.log; exit 1; }

echo "[4/6] 生成 dex"
"$BT/d8.bat" --release --min-api 24 --lib "$(cygpath -m "$JAR")" \
  --output build/dex $(find build/classes -name "*.class" | tr '\n' ' ') \
  2>&1 | grep -iv "warning\|JAVA_TOOL" || true

echo "[5/6] 打包对齐"
python - <<'PY'
import zipfile, shutil
shutil.copy('build/base.apk', 'build/unsigned.apk')
with zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
PY
"$BT/zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk

echo "[6/6] 签名并安装"
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out ClaudeDoctor.apk build/aligned.apk 2>&1 | grep -iv "JAVA_TOOL" || true
ls -lh ClaudeDoctor.apk | awk '{print "  APK 体积:", $5}'

if adb devices | grep -q "device$"; then
  adb install -r ClaudeDoctor.apk | tail -1
  adb shell pm grant com.claudedoctor.app android.permission.READ_PHONE_STATE 2>/dev/null || true
  adb shell am force-stop com.claudedoctor.app
  adb shell am start -n com.claudedoctor.app/.MainActivity >/dev/null
  echo "  已启动"
else
  echo "  未检测到设备，已跳过安装"
fi
