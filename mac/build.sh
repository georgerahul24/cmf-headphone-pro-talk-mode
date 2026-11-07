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
mkdir -p "$APP/Contents/Resources"
cp AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"
# Now Playing reader (vendor/mediaremote-adapter): only the read-only "get" parts are built.
MRA=vendor/mediaremote-adapter
FW="$APP/Contents/Frameworks/MediaRemoteAdapter.framework"
mkdir -p "$FW/Versions/A/Resources"
clang -O2 -fobjc-arc -fvisibility=default -dynamiclib -arch arm64 -arch x86_64 -mmacosx-version-min=14.0 \
  -I$MRA/include -I$MRA/src \
  $MRA/src/adapter/env.m $MRA/src/adapter/get.m $MRA/src/adapter/globals.m $MRA/src/adapter/keys.m \
  $MRA/src/adapter/now_playing.m $MRA/src/private/MediaRemote.m $MRA/src/utility/helpers.m $MRA/src/utility/Debounce.m \
  -framework Foundation -framework AppKit -framework UniformTypeIdentifiers \
  -install_name @rpath/MediaRemoteAdapter.framework/MediaRemoteAdapter \
  -o "$FW/Versions/A/MediaRemoteAdapter"
ln -sfh A "$FW/Versions/Current"
ln -sfh Versions/Current/MediaRemoteAdapter "$FW/MediaRemoteAdapter"
ln -sfh Versions/Current/Resources "$FW/Resources"
cat > "$FW/Versions/A/Resources/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>MediaRemoteAdapter</string>
<key>CFBundleIdentifier</key><string>dev.georgerahul.cmftalkmode.MediaRemoteAdapter</string>
<key>CFBundlePackageType</key><string>FMWK</string>
<key>CFBundleVersion</key><string>0.1.0</string>
</dict></plist>
PLIST
cp $MRA/bin/mediaremote-adapter.pl "$APP/Contents/Resources/mediaremote-adapter.pl"
codesign --force --sign - "$FW"
mkdir -p "$APP/Contents/Library/LaunchAgents"
cp LaunchAgent.plist "$APP/Contents/Library/LaunchAgents/dev.georgerahul.cmftalkmode.agent.plist"
codesign --force --deep -s - "$APP"
if [ "$1" = "--dmg" ]; then
  hdiutil create -volname "CMF Talk Mode" -srcfolder "$APP" -ov -format UDZO "build/CMF-Talk-Mode.dmg"
fi
echo "Built $APP"
