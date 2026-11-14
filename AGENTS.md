# Agent instructions

## Live EQ shortcut

For EQ-only tuning changes, edit [`eq.json`](eq.json) rather than rebuilding the apps.

- Keep `"schema": 1`.
- Keep the required preset IDs: `movie`, `pop`, `rock`, and `classical`.
- Keep exactly 8 bands per preset.
- Update `"version"` and the UTC `"updatedAt"` timestamp for every tuning change.
- Keep preamp between -6 and +3 dB, gain between -12 and +12 dB, frequency between 20 and 20,000 Hz, and Q between 0.1 and 10.
- Push `eq.json` to `main`; Android and macOS validate and cache it, and fall back to bundled values if it is unavailable or invalid.
- The apps display the active tuning revision and timestamp in their EQ screens.

For source-code, UI, or protocol changes, update the platform implementations and use the tagged GitHub Actions release workflow. Keep Android [`Eq.kt`](android/app/src/main/java/dev/georgerahul/cmftalk/Eq.kt) and macOS [`Eq.swift`](mac/Sources/Eq.swift) synchronized with the fallback values in `eq.json`.
