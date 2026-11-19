#!/usr/bin/env bash
# 下载 Android 构建工具链到 ./sdk/
# 用法: bash setup-sdk.sh
set -e
cd "$(dirname "$0")"
SDK="$PWD/sdk"
BT_URL="https://dl.google.com/android/repository/build-tools_r34-windows.zip"
PLATFORM_URL="https://dl.google.com/android/repository/platform-34-ext7_r02.zip"

mkdir -p "$SDK"
cd "$SDK"

if [ ! -d "android-14" ]; then
  echo "==> 下载 build-tools ..."
  curl -sL -o bt.zip "$BT_URL"
  unzip -q -o bt.zip
  rm -f bt.zip
fi

if [ ! -d "android-34" ]; then
  echo "==> 下载 platform-34 ..."
  curl -sL -o plat.zip "$PLATFORM_URL"
  unzip -q -o plat.zip
  rm -f plat.zip
fi

echo "SDK 就绪: $SDK"
echo "  build-tools : $SDK/android-14"
echo "  platform    : $SDK/android-34/android.jar"
