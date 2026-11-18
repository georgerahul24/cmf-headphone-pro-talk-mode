# Agent instructions

## Live EQ shortcut

For EQ-only tuning changes, edit [`eq.json`](eq.json) rather than rebuilding the apps.

- Keep `"schema": 1`.
- Keep the required preset IDs: `movie`, `pop`, `rock`, and `classical`.
- Keep exactly 8 bands per preset.
- Optional `"factory"` (1 Rock, 2 Electronic, 3 Pop, 4 Enhance vocals, 5 Classical) makes a preset use Nothing's own built-in preset instead of its bands. Pop, Rock and Classical use it now. The bands are still required and are what apps before 2.13.8 use.
- Update `"version"` and the UTC `"updatedAt"` timestamp for every tuning change.
- Keep preamp between -6 and +3 dB, gain between -12 and +12 dB, frequency between 20 and 20,000 Hz, and Q between 0.1 and 10.
- Push `eq.json` to `main`; Android and macOS validate and cache it, and fall back to bundled values if it is unavailable or invalid.
- The apps display the active tuning revision and timestamp in their EQ screens.

For source-code, UI, or protocol changes, update the platform implementations and use the tagged GitHub Actions release workflow. Keep Android [`Eq.kt`](android/app/src/main/java/dev/georgerahul/cmftalk/Eq.kt) and macOS [`Eq.swift`](mac/Sources/Eq.swift) synchronized with the fallback values in `eq.json`, including which presets use `factory`.

## Platform build and release shortcut

- **Android phone and Fire TV:** build in GitHub Actions, not with an arbitrary locally installed Gradle version. The workflow pins Gradle 8.14.3 and Java 17, and produces both APKs.
- **macOS:** build natively with [`mac/build.sh`](mac/build.sh), which creates the universal app, DMG, and ZIP.
- **Release:** bump Android `versionName`/`versionCode` and macOS `Info.plist` versions, update [`CHANGELOG.md`](CHANGELOG.md), commit, create a matching `vX.Y.Z` tag, and push the branch and tag. The tag workflow publishes the phone APK, Fire TV APK, macOS DMG, and macOS ZIP.
- After tagging, verify the GitHub Actions run and the published release assets before reporting completion.
