#!/bin/sh
# Builds "CMF Talk Mode.app" (and a .dmg with --dmg) into mac/build.
# Universal binary, ad-hoc signed (no Apple developer account needed).
set -e
cd "$(dirname "$0")"
APP="build/CMF Talk Mode.app"
rm -rf build && mkdir -p "$APP/Contents/MacOS"
for arch in arm64 x86_64; do
  swiftc -O -swift-version 5 -parse-as-library -target $arch-apple-macos14.0 \
    Sources/*.swift -o build/CMFTalkMode-$arch
done
lipo -create build/CMFTalkMode-arm64 build/CMFTalkMode-x86_64 -output "$APP/Contents/MacOS/CMFTalkMode"
rm build/CMFTalkMode-*
cp Info.plist "$APP/Contents/Info.plist"
mkdir -p "$APP/Contents/Library/LaunchAgents"
cp LaunchAgent.plist "$APP/Contents/Library/LaunchAgents/dev.georgerahul.cmftalkmode.agent.plist"
codesign --force --deep -s - "$APP"
if [ "$1" = "--dmg" ]; then
  hdiutil create -volname "CMF Talk Mode" -srcfolder "$APP" -ov -format UDZO "build/CMF-Talk-Mode.dmg"
fi
echo "Built $APP"
