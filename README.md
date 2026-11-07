# CMF Talk Mode: one-press Conversation Mode for CMF Headphone Pro (Android + macOS)

**Give your CMF Headphone Pro the "talk to someone" feature that premium headphones charge extra for.**

Press one button on your headphones. Your music drops to 10% and Transparency mode turns on, so you can hear the person in front of you. Press it again and everything goes back: your old volume and your old noise mode (ANC, Off, or Transparency).

Flagship headphones sell this as Speak-to-Chat, Conversation Awareness or Conversation Boost. With CMF Talk Mode, your budget-friendly CMF Headphone Pro gets it for free. It works on **Android** and **macOS**.

> Free and open source. No account, no cloud, no tracking. Unofficial, not made by or linked to Nothing or CMF.

---

## Features

**Talk mode**
- **One-press talk mode.** A single press turns on Transparency (ambient sound) and lowers or pauses your music. The same idea as Sony Speak-to-Chat or AirPods Conversation Awareness.
- **Lower or pause.** Lower the music to a volume you pick (10% by default), or pause it and have it play again when you are done.
- **Puts everything back.** Your exact noise mode (Noise Cancelling High, Mid, Low or Adaptive, Off or Transparency) and your exact volume come back when you press again.
- **Your normal buttons still work.** Talk mode uses its own button. No voice assistant pop-up.

**Sound**
- **EQ tuned for these headphones.** Movie, Pop and Rock presets using all 8 EQ bands the headphones support, not copied from the factory presets. Flat turns the EQ off (every band at 0), and every preset is measured from it. [More below](#eq).
- **Auto EQ.** Switches to Movie when you watch something and to your music preset (Pop or Rock) when you listen. Shows what it picked, like "Auto (Movie)", in the app and in the Android notification. Remembers your pick per show, film, song or artist.
- **Noise control and Spatial Audio.** Off, Transparency, Noise Cancelling Low, Mid, High or Adaptive. Spatial Audio Off, Concert or Cinema. For everyday controls you don't need Nothing X.

**Battery and history**
- **Battery time left.** Learned from how fast your battery drops, like "About 29 h left".
- **Low battery reminder** below 10%.
- **Hearing safety.** A weekly sound dose ring following the WHO guideline (80 dB for 40 hours a week), with reminders after loud listening. Estimated from your volume.
- **Battery saver for noise cancelling.** If nothing plays for 10 minutes (pick 5 to 60), noise control turns Off so ANC stops draining the battery. Your old mode comes back when audio starts. A live countdown shows when it kicks in.
- **Headphone history.** Time worn per day, a 24 hour strip of when you used Noise Cancelling, Transparency or Off, and a battery chart. Kept forever on your device. Export it as CSV (Android) or open the log file (Mac).
- **Usage stats.** Times you talked, time talking, how often the battery saver turned ANC off and how long it rested.

**Apps**
- **Phone and Mac sync.** History, stats and every setting sync over your Wi-Fi, encrypted, with no cloud. Pair once with a 6-digit code. Settings shows what is synced and what is still waiting. [More below](#sync).
- **Android:** a clean dark app with Home, Sound, Stats and Settings tabs. Runs in the background, starts after a reboot and reconnects on its own. Made to hold up on custom ROMs like Evolution X.
- **Mac:** quick controls in the menu bar (talk, noise control, Spatial Audio, battery saver), plus a full window with Home, Sound, Activity and Settings. Opens at login and restarts if it crashes.
- **Updates itself.** Both apps check GitHub Releases and install new versions. On Android, the first update asks you to tap Install.
- **Connect button** when the headphones aren't connected.
- **Safe for your headphones.** Only the same settings commands the official Nothing X app sends. It never touches firmware or the update channel, so your warranty and future firmware updates are not affected.

## EQ

The Nothing X app only shows bass, mid and treble, but the CMF Headphone Pro applies up to 8 EQ filters, each with its own frequency and width (checked by ear: changing only bands 4 to 8 is clearly audible). So each preset uses all 8, measured from Flat:

| Preset | Made for | What you hear |
|---|---|---|
| **Movie** | Energy Slider at full bass | Big rumble and boom for fights and explosions. Dialogue, faint sounds and details like clinking glass stay clear. |
| **Pop** | Energy Slider at zero | Big bass like the factory Pop, vocals clearly in front and smooth. Tuned for English, Hindi and Malayalam songs. |
| **Classical** | Energy Slider at zero | Natural and open: clean low strings, clear soloists, soft violins, the air of the hall. |
| **Rock** | Energy Slider at zero | Weighty kick and bass guitar, less mud, vocals that cut through, smoother cymbals. |
| **Flat** (all 0) | Anything | No EQ. The neutral starting point every preset is measured from. |

Both apps draw each preset's curve and list all 8 bands with their values.

**Remember for this:** the Now playing card has Movie, Pop, Rock and Classical buttons. Tap one and Auto remembers it for that show, film or song and its artist or channel. Episode numbers are ignored, so one pick covers a whole series. Picks sync between your devices, and nothing is looked up online.

**Auto** picks Movie for video apps (Netflix, Prime Video, JioHotstar, YouTube, VLC and more) and for web browsers on Mac. It picks your music preset for music apps (Spotify, YouTube Music, JioSaavn, Apple Music and more). On Mac, the Zen browser counts as music. Nothing playing means no change.

The presets play at the same level as the factory sound.

## Sync

The phone and the Mac keep each other up to date over your home Wi-Fi. No account, no cloud.

- **What syncs:** headphone history, usage stats, and every setting: talk mode on or off, talk volume, lower or pause, battery saver and its wait time, EQ mode and music preset, and auto-update. ("Open at login" is Mac only.)
- **Pairing:** on the Mac, open the app, go to Settings, and click **Pair a phone**. On the phone, go to Settings, then Sync with your Mac, and type the code. Do it once.
- **When:** while both are on the same Wi-Fi, the phone keeps a link open and changes go across within seconds. If you were apart, everything that piled up goes across as soon as they meet again.
- **What's left:** each side tracks exactly how much of the other's history it has. Settings shows "Everything is synced" or what is waiting, like "34 history events and 2 settings waiting to sync".
- **Merging:** charts and stats show both devices together. Time when the headphones were connected to both counts once. For a setting changed on both, the newest change wins. Resetting stats resets both.
- **Security:** pairing uses an X25519 key exchange checked with the code, and every message after that is encrypted with AES-GCM.
- **Mac permission:** macOS asks to allow CMF Talk Mode to find devices on your local network. Allow it, or sync can't work.

## How it works

The CMF Headphone Pro has no "run my app" button action. So CMF Talk Mode borrows one you probably don't use: **Spatial Audio**.

1. In Nothing X, you set the action button's **press and hold** to **Spatial Audio**.
2. When you press it, the headphones tell your phone or Mac "Spatial Audio changed".
3. CMF Talk Mode catches that message, switches Spatial Audio straight back off, and toggles talk mode.

The app talks to the headphones over the same Bluetooth control channel Nothing X uses.

## Download

Get the latest build from the [Releases page](../../releases):

- **Android:** `CMF-Talk-Mode-x.y.z.apk` (Android 12 or newer)
- **macOS:** `CMF-Talk-Mode.dmg` (macOS 14 Sonoma or newer, Apple Silicon and Intel)

## Setup

### 1. Set the button (one time)

1. Open **Nothing X** on your phone.
2. Go to your CMF Headphone Pro, then **Controls**, then the **action button**.
3. Set **press and hold** to **Spatial Audio**.

### 2. Android

1. Install the APK.
2. Open **CMF Talk Mode** and tap **Allow Bluetooth and notifications**.
3. Tap **Allow running in background** and pick **Allow**. This sets battery use to Unrestricted, so Android doesn't kill the app.
4. **Force stop Nothing X** (Settings, Apps, Nothing X, Force stop), or turn off its background use. Only one app can hold the headphones' control link at a time. Open Nothing X again only when you want to change settings.
5. Done. You will see a small "CMF Talk Mode" notification. It keeps the app alive, shows your noise mode, battery and EQ, and has a **Talk** button.
6. Optional, for Auto EQ: on the Sound tab, tap **Let Auto see which app is playing** and turn on Notification access. It only checks which app plays and never reads your notifications. If Android says it's a restricted setting: App info, three dots, **Allow restricted settings**.
7. Optional, to sync with the Mac app: Settings, **Sync with your Mac**, then type the code the Mac shows.
8. For updates and sync: allow the app to use the network. Some ROMs (like Evolution X) block this per app under App info, **Mobile data & Wi-Fi** or **Network access**.

### 3. macOS

1. Open the `.dmg` and drag **CMF Talk Mode** to Applications.
2. The app is not signed with a paid Apple account. The first time, right-click the app and pick **Open**. Or run:
   `xattr -dr com.apple.quarantine "/Applications/CMF Talk Mode.app"`
3. Allow Bluetooth when macOS asks.
4. Click the **speech bubbles** icon in the menu bar. It has the quick controls. Click **Open app** for the full window (EQ, history, stats, settings) and turn on **Open at login** in Settings. The icon fills in while talk mode is on.

## FAQ

**Will this void my warranty or block firmware updates?**
No. The app only uses normal settings commands (noise mode, Spatial Audio, EQ, battery). It never uses the firmware update channel.

**The EQ changed my Nothing X EQ.**
The app's presets are saved as the headphones' Custom EQ. Picking a preset in Nothing X brings back the factory sound.

**Should I use the Energy Slider?**
Movie is tuned for the slider at full bass. Pop and Rock are tuned for the slider at zero.

**I hear "concert mode" (a voice prompt) when I press the button.**
That prompt plays inside the headphones the moment Spatial Audio switches on, before the app gets the message. There is no known setting to turn it off yet.

**Does it drain my battery?**
No. While connected, the app just waits for the headphones to send a message. There is no polling. While the headphones are off, it waits for the system's "device connected" event and only checks again every few minutes as a backup.

**Does it start again if it is closed?**
On Android, it starts after a reboot and after updates, and Android restarts the service if it gets killed. Swiping it away from recent apps does not stop it. Only "Force stop" stops it, until you open the app again. On macOS, it opens at login and restarts after a crash. Picking Quit keeps it closed until you open it again.

**Does sync need the internet?**
No. It only uses your local Wi-Fi, directly between the phone and the Mac. Guest networks that keep devices apart block it.

**How do updates work?**
Both apps check GitHub Releases (Android twice a day, Mac every 6 hours) and install new versions. You can also check by hand in Settings. See the [changelog](CHANGELOG.md) for what changed.

**Can I still use Spatial Audio?**
Yes. Turn off the "Talk mode button" switch in the app, and the button works as normal Spatial Audio again.

**What if my headphones are connected to both my phone and my Mac?**
Each device runs its own copy and lowers its own volume. Both put the noise mode back the same way.

**Does it work with other Nothing or CMF headphones and earbuds?**
It is built and tested for the CMF Headphone Pro (model B175). Other Nothing and CMF models use the same protocol, so they may work with small changes. Pull requests are welcome.

## Build it yourself

- **macOS:** `./mac/build.sh --dmg` (only needs the Xcode command line tools)
- **Android:** `cd android && gradle :app:assembleRelease` (JDK 17 and the Android SDK)

GitHub Actions builds both on every push, and attaches them to a release for tags like `v1.0.0`.

## Credits

Reading the Mac's now-playing song uses a reviewed, read-only part of [mediaremote-adapter](https://github.com/ungive/mediaremote-adapter) (BSD 3-Clause, in `mac/vendor`). Protocol details come from the open-source community, mainly [ear-web](https://github.com/radiance-project/ear-web), [BudsLink](https://github.com/maniacx/BudsLink) and [EarA](https://github.com/oneydef/EarA-linux). The 8-band EQ was checked on a real pair. The code in this repo is written from scratch.

## License

MIT. Nothing, CMF and Nothing X are trademarks of their owners. This project is not affiliated with them.

---

**Keywords:** CMF Headphone Pro app, CMF Headphone Pro transparency shortcut, conversation mode, speak to chat for Android, conversation awareness for Mac, auto lower volume when talking, CMF Headphone Pro EQ, best EQ for CMF Headphone Pro, movie EQ, Bollywood EQ, ambient sound shortcut, Nothing X alternative, CMF by Nothing headphones, ANC toggle app, open source headphone companion app.
