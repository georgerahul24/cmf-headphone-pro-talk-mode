# CMF Talk Mode: one-press Conversation Mode for CMF Headphone Pro (Android + macOS)

**Give your CMF Headphone Pro the "talk to someone" feature that premium headphones charge extra for.**

Press one button on your headphones. Your music drops to 10% and Transparency mode turns on, so you can hear the person in front of you. Press it again and everything goes back: your old volume and your old noise mode (ANC, Off, or Transparency).

Flagship headphones sell this as Speak-to-Chat, Conversation Awareness or Conversation Boost. With CMF Talk Mode, your budget-friendly CMF Headphone Pro gets it for free. It works on **Android** and **macOS**.

> Free and open source. No account, no cloud, no tracking. Unofficial, not made by or linked to Nothing or CMF.

---

## Features

- **One-press talk mode.** A single press drops music volume and turns on Transparency (ambient sound). This is the same idea as Sony Speak-to-Chat or AirPods Conversation Awareness.
- **Remembers your exact noise mode.** If you were in Noise Cancelling (High, Mid, Low or Adaptive), Off or Transparency, it goes back to that exact mode when you press again.
- **Restores your exact volume.** Not a guess. The same level you had before.
- **Your normal buttons still work.** Talk mode uses its own button. Your usual noise control buttons stay the way they are and never touch your volume.
- **No voice assistant pop-up.** It does not hijack Siri or Google Assistant.
- **A full control app on Android.** Switch noise control (Off, Transparency, Noise Cancelling Low, Mid, High or Adaptive) and Spatial Audio, and see your battery, in a clean dark dot-matrix design. For these everyday controls, you don't need Nothing X.
- **Works on Android and Mac.** A small menu bar app on macOS and a background app on Android.
- **Set your own talk volume.** 10% by default. Pick anything from 0% to 100%.
- **Turn it off any time.** One switch puts the button back to normal Spatial Audio.
- **Built to stay running on Android.** Runs as a foreground service, asks for unrestricted battery use, starts after a reboot and reconnects when your headphones connect. Made to hold up on custom ROMs like Evolution X.
- **Opens at login on macOS.** Lives in the menu bar, uses almost no resources, and macOS restarts it if it ever crashes.
- **Safe for your headphones.** It only sends the same settings commands the official Nothing X app sends. It never touches firmware or the update channel, so your warranty and future firmware updates are not affected.

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
5. Done. You will see a small "CMF Talk Mode" notification. It keeps the app alive, and it also has a **Talk** button.

### 3. macOS

1. Open the `.dmg` and drag **CMF Talk Mode** to Applications.
2. The app is not signed with a paid Apple account. The first time, right-click the app and pick **Open**. Or run:
   `xattr -dr com.apple.quarantine "/Applications/CMF Talk Mode.app"`
3. Allow Bluetooth when macOS asks.
4. Click the **speech bubbles** icon in the menu bar and turn on **Open at login**. The icon fills in while talk mode is on.

## FAQ

**Will this void my warranty or block firmware updates?**
No. The app only uses normal settings commands (noise mode, Spatial Audio, battery). It never uses the firmware update channel.

**I hear "concert mode" (a voice prompt) when I press the button.**
That prompt plays inside the headphones the moment Spatial Audio switches on, before the app gets the message. There is no known setting to turn it off yet.

**Does it drain my battery?**
No. While connected, the app just waits for the headphones to send a message. There is no polling. While the headphones are off, it waits for the system's "device connected" event and only checks again every few minutes as a backup.

**Does it start again if it is closed?**
On Android, it starts after a reboot and after updates, and Android restarts the service if it gets killed. Swiping it away from recent apps does not stop it. Only "Force stop" stops it, until you open the app again. On macOS, it opens at login and restarts after a crash. Picking Quit keeps it closed until you open it again.

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

Protocol details come from the open-source community, mainly [BudsLink](https://github.com/maniacx/BudsLink) and [EarA](https://github.com/oneydef/EarA-linux). The code in this repo is written from scratch.

## License

MIT. Nothing, CMF and Nothing X are trademarks of their owners. This project is not affiliated with them.

---

**Keywords:** CMF Headphone Pro app, CMF Headphone Pro transparency shortcut, conversation mode, speak to chat for Android, conversation awareness for Mac, auto lower volume when talking, ambient sound shortcut, Nothing X alternative, CMF by Nothing headphones, ANC toggle app, open source headphone companion app.
