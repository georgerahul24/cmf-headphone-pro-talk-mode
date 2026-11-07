# Changelog

All versions are on the [Releases page](https://github.com/georgerahul24/cmf-headphone-pro-talk-mode/releases). Android and Mac share version numbers.

## 2.11.1

- **Much lighter on battery.**
  - Mac: when no window is open, the app used to wake the processor about 900 times a second. It's now about once a second, at 0 to 0.5% CPU. The closed window is removed instead of updating in the background, the menu's clock and animations only run while it's open, and the Dock icon only animates while audio plays.
  - Mac: which app is playing is checked every 6 seconds, the song name every 20 seconds or when the app changes, and the hearing dose is saved once a minute.
  - Android: animations (logo, music bars, talk pulse, connecting dot) only run while they're visible and needed, and the hearing dose is saved once a minute.

## 2.11.0

- **Auto remembers your picks.** On the Now playing card, tap Movie, Pop, Rock or Classical to remember it for what's playing, by its name and its artist or channel. Episode numbers are ignored, so picking Movie on one Mr. Robot episode covers the whole series, in any app, browsers included. Tap again to forget. Your picks sync between the phone and the Mac.
- **The online genre lookup is gone.** Apple's search labels most Indian songs by language (Tamil, Bollywood), not style, so it couldn't tell rock from pop. Nothing is looked up online anymore. The Classical preset stays.
- **Mac reads what's playing in any app, including YouTube Music or a show in Zen.** macOS 15.4 and newer stopped sharing this with apps, so the Mac app bundles a small, reviewed, read-only part of the open-source [mediaremote-adapter](https://github.com/ungive/mediaremote-adapter) (BSD license, pinned version).
  - Safety: only the read part is built; the parts that control playback or start other programs are left out. It has no network access and writes no files. The app runs it with Apple's own `/usr/bin/perl`, fixed paths inside the app, no shell, an empty environment, a time limit and an output size limit. Details in `mac/vendor/mediaremote-adapter/README.md`.
- **Battery forecast: when to charge next.** Each noise mode (noise cancelling, Transparency, Off) gets its own drain rate learned from your history, blended with CMF's figures until there's enough data. Your real daily use over the last 14 days (counting days you don't use them) turns that into a next charge day, like "Next charge around Fri 13 Nov (in ~6 days)", next to the hours of listening left.
- **Mac:** the EQ buttons in the menu bar always highlight the current choice.

## 2.10.0

- **Battery time left.** Learned from your own history (how fast the battery drops while connected over the last week), shown like "About 29 h left · from your use". Uses CMF's figures (about 50 h with noise control on, 100 h off) until there's enough history.
- **Low battery reminder.** One notification when the headphones drop below 10%, again only after they've charged.
- **Hearing safety.** A weekly "sound dose" ring following the WHO guideline (80 dB for 40 hours a week; every 3 dB louder halves the safe time), with today's listening time and level. Phone and Mac add up through sync. A notice after 30 minutes of loud listening and when you pass the week's safe amount. Loudness is estimated from the volume, since the headphones can't measure it.
- **Classical preset.** Natural and open: clean low strings, clear soloists, soft violins and the air of the hall. Auto picks it when the genre lookup finds classical, opera or orchestral music.
- **Now playing on Mac shows up for browsers too.** For browsers like Zen the card shows the app and the EQ it picked.
- Both reminders have switches and sync between devices.
- **Mac:** the EQ picker always shows the current choice, also right after it changes on the phone.

## 2.9.0

- **EQ by song genre.** In Auto, each song is looked up on Apple's iTunes Search (only the title and artist are sent). Rock-type songs get Rock, everything else (Bollywood, Regional Indian, Pop, R&B...) gets Pop. Videos still get Movie. On by default, with a switch to turn it off; the setting syncs.
- **Now playing card** with bouncing music bars: the song, the artist, the app, the genre that was found and the EQ it picked, like "Bollywood → Pop EQ".
  - Android: works with YouTube Music, Spotify, JioSaavn and others (needs Notification access).
  - Mac: works with Apple Music and Spotify (macOS asks once), and with browsers like Zen when macOS shares the song.

## 2.8.0

- **8-band EQ, measured from Flat.** A listening test changed only bands 4 to 8 (with Nothing's own 3 bands at 0) and the change was clearly audible, so the headphones really do use all 8 bands. The presets are the bold 8-band versions:
  - Movie: big rumble and boom for the Energy Slider at full, with clear dialogue and detail
  - Pop: big bass shelf, vocals up front, softer "s" sounds, shimmer on top
  - Rock: punchy kick, crunchy guitars, bright cymbals
- **Flat instead of Stock.** Stock was the factory Pop preset, which already adds a lot of bass, so the presets were hard to judge against it. Now there is **Flat** (bass, mid and treble at 0), and every preset is measured from it. Earlier "no difference" results were mostly because Stock already adds a lot of bass.
- **New app icon** on Android and Mac: a frosted glass panel with a sound wave over soft violet glows. The Mac app now has an icon at all.
- **Animated logo on Android.** The new icon sits at the top of the Home screen, and its sound wave bounces while audio plays.
- **Live Dock icon on Mac.** While the app window is open, the sound wave in the Dock icon breathes gently, bounces when the Mac plays audio, and bounces faster while talking.
- **Mac:** clicking the app in Applications, Launchpad or the Dock while it runs now opens its window.

## 2.7.2

- **Presets with real character.** A listening test confirmed the headphones apply all 8 EQ bands, but the presets were too alike to tell apart. Now each one stands out: Movie has much deeper rumble and boom with clear dialogue, Pop has a big warm bass shelf with vocals up front and sparkle on top, and Rock has guitar crunch, a punchy kick and bright cymbals.

## 2.7.1

- **See the EQ.** Each preset now shows its curve with a soft shadow under it, a dot on every band, and the frequency and value of all 8 bands. Small curves of Movie, Pop and Rock sit side by side so you can compare them, and tapping one switches to it. On Mac, hover a dot to see its value.
- **EQ in the Mac menu bar**, with the curve, next to noise control and spatial audio.

## 2.7.0

- **Sync between your phone and Mac.** History, stats and every setting (talk mode, volume, lower or pause, battery saver and its wait time, EQ mode and music preset, auto-update) sync over your Wi-Fi. Nothing goes to the internet, and everything is encrypted.
  - Pair once: click **Pair a phone** on the Mac and type the 6-digit code on the phone.
  - While both are on the same Wi-Fi, changes go across within seconds. Anything that piled up while apart goes across as soon as they meet again.
  - Each side knows exactly what the other has, and Settings shows it: "Everything is synced", or what is still waiting, like "34 history events and 2 settings waiting to sync".
  - Charts and stats show both devices together. Time when both were connected to the headphones counts once.
  - For settings changed on both sides, the newest change wins. Resetting stats resets both.
- The CSV export has a new "device" column.

## 2.6.3

- **New EQ tuning, louder and fuller.** All presets now play at the same level as the factory sound. Earlier versions turned the volume down to leave headroom, which made them sound quieter and thinner than stock. The factory presets and the Energy Slider boost bass a lot with no volume drop, so the headphones handle it fine.
- **Movie** is built for the Energy Slider at full bass. Big rumble and boom for fights and explosions. Dialogue, faint sounds and small details like clinking glass or a train's rattle stay clear.
- **Pop** has a big bass shelf like the factory Pop, with vocals clearly in front and no harsh "s" sounds. Tuned for English, Hindi and Malayalam songs.
- **Rock** has weighty kick and bass guitar, less mud, vocals that cut through, and smoother cymbals.
- **Android updater:** shows the real reason when an update fails, offers the download page as a backup, and falls back to github.com if api.github.com can't be reached.

## 2.6.2

- Pop louder with fuller bass, Movie keeps more boom. (Test build, replaced by 2.6.3.)

## 2.6.1

- **Android:** the notification shows the EQ in use, like "EQ Auto (Movie)".

## 2.6.0

- **EQ with 8 bands.** Movie, Pop and Rock presets tuned for these headphones, plus Stock (the factory sound). The headphones take up to 8 EQ filters, so each preset fixes problems one by one instead of using plain bass, mid and treble.
- **Auto EQ.** Picks Movie when you watch something and your music preset (Pop or Rock) when you listen. Shows what it picked, like "Auto (Movie)". On Android it can see which app is playing if you allow Notification access. On Mac it needs no permission.
- **Updates itself.** Both apps check GitHub Releases and install new versions.
- **Full Mac app.** The menu bar keeps quick controls (talk, noise control, spatial audio, battery saver). The full window has Home, Sound, Activity and Settings.
- **Talk mode can pause music** instead of lowering it.

## 2.4.0

- **Connect button** when the headphones aren't connected.

## 2.3.0 and 2.3.1

- **Headphone history:** time per day, a 24 hour noise mode strip and a battery chart. Kept forever. Export as CSV on Android.
- **Android:** fixed "Playing" staying on after music paused.

## 2.2.0

- **New design** on Android and Mac, with tabs on Android.
- **Battery saver:** pick the wait time (5 to 60 minutes) and see a live countdown.
- **Usage stats:** talk count, talk time, how often the saver turned ANC off.
- Spatial Audio mode 3 is named Cinema.

## 2.1.0

- **Battery saver:** turns noise control off after 10 minutes with nothing playing and brings it back when audio starts.

## 2.0.0

- **New Android app** with noise control (including ANC levels), Spatial Audio and talk mode.

## 1.1.0

- First public version: one-press talk mode on Android and Mac.
