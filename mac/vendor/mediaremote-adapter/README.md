# mediaremote-adapter (vendored, read-only subset)

Lets CMF Talk Mode read what's playing on the Mac (title, artist, app), including songs in
browsers like Zen, which macOS 15.4 and newer no longer share with apps directly.

- Source: https://github.com/ungive/mediaremote-adapter
- Pinned commit: `0e82bf1c44ec39554fbfc402ec88a48c6ae2a0fd` (2026-10-17), reviewed before vendoring
- License: BSD 3-Clause (see LICENSE)

How it works: Apple's own `/usr/bin/perl` is still allowed to read the system's Now Playing
information, so `bin/mediaremote-adapter.pl` loads `MediaRemoteAdapter.framework` into it and
prints the current song as JSON.

What is built (see `mac/build.sh`): only the files needed for the `get` command. The commands
that control playback (send, seek, shuffle, repeat, speed), the `stream` command and the `test`
command (the only part that starts another program) are left out, so the framework can only
read. The code has no network access and writes no files.

How the app runs it (`mac/Sources/NowPlaying.swift`): `/usr/bin/perl` with fixed paths inside
the app bundle, no shell, an empty environment (so nothing like PERL5OPT can change it), the
`get --no-artwork` command only, a 3 second time limit and a size limit on the output.
