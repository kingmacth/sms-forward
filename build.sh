#!/usr/bin/env bash
# 短信转发 App 构建脚本（无 Gradle，纯 aapt2 + javac + d8 + apksigner）
# 用法: ./build.sh
set -e
W="$(cd "$(dirname "$0")" && pwd)"
SDK="$W/sdk"
BT="$SDK/android-14"
AJ="$SDK/android-34/android.jar"

echo "=== 1/6 清理 ==="
rm -rf "$W/build"
mkdir -p "$W/build/gen" "$W/build/classes" "$W/build/dex" "$W/build/apk"

echo "=== 2/6 编译资源 ==="
"$BT/aapt2.exe" compile --dir "$W/res" -o "$W/build/gen/res.zip"

echo "=== 3/6 链接清单 ==="
VER=$(grep -oP 'versionCode="\K[0-9]+' "$W/AndroidManifest.xml" | head -1 || echo 1)
"$BT/aapt2.exe" link -o "$W/build/apk/base.apk" \
  -I "$AJ" \
  --manifest "$W/AndroidManifest.xml" \
  --java "$W/build/gen" \
  --min-sdk-version 26 --target-sdk-version 33 \
  --version-code "${VER:-1}" --version-name "build" \
  "$W/build/gen/res.zip"

echo "=== 4/6 编译 Java ==="
javac -source 11 -target 11 -d "$W/build/classes" -cp "$AJ" "$W"/src/com/smsfwd/app/*.java 2>&1 \
  | grep -viE "warning|bootstrap|obsolete|deprecat|release 11" || true

echo "=== 5/6 转 dex（必须用 find 展开，Git Bash 下 *.class 不通配） ==="
CLASSES=$(find "$W/build/classes" -name "*.class" | tr '\n' ' ')
"$BT/d8.bat" --min-api 26 --output "$W/build/dex" --lib "$AJ" $CLASSES 2>&1 | grep -viE "warning" || true
ls -la "$W/build/dex/classes.dex"

echo "=== 6/6 打包签名 ==="
cd "$W/build/apk"
cp base.apk app-unsigned.apk
unzip -q -o app-unsigned.apk
cp ../dex/classes.dex .
rm -rf META-INF
python -c "
import zipfile
zf = zipfile.ZipFile('app-unsigned.apk','a',zipfile.ZIP_DEFLATED)
zf.write('classes.dex','classes.dex')
zf.close()
"
cd "$W"
"$BT/zipalign.exe" -f 4 build/apk/app-unsigned.apk build/apk/app-aligned.apk
"$BT/apksigner.bat" sign --ks "$W/debug.keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias smsfwd --out "$W/build/apk/smsfwd.apk" "$W/build/apk/app-aligned.apk" 2>&1 | grep -viE "WARNING" || true

echo ""
echo "构建完成: $W/build/apk/smsfwd.apk"
ls -la "$W/build/apk/smsfwd.apk"
