# Changelog

All versions are on the [Releases page](https://github.com/georgerahul24/cmf-headphone-pro-talk-mode/releases). Android and Mac share version numbers.

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
