# Silly Macro

An Android macro for Roblox **Bee Swarm Simulator**: it walks to a field, farms with a pattern, goes back and converts at your hive, and repeats. Routes and patterns follow [Natro Macro](https://github.com/NatroTeam/NatroMacro).

All the source is here, so you can read exactly what it does and build the APK yourself.

## Requirements

- An Android **phone or tablet with Android 13 or newer** (a real device: cloud phones and PC emulators block the virtual keyboard the macro needs).
- **4 GB of RAM or more** (6 GB+ is best). Silly Macro itself uses about 200 MB; Roblox uses most of the rest.
- Wi-Fi, to start Shizuku with Wireless debugging.
- [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) (free, Play Store).
- Bee Swarm Simulator. A private server is optional (it's used for the "Open Bee Swarm" button and overnight rejoins).

Not supported: iPhone/iPad, Windows, Android 12 or older, cloud phones. Huawei, Honor and other brands are untested.

## Install and first-time setup

There's a full video and text tutorial in the Discord's #setup-guide. In short:

1. **Download** `SillyMacro-x.y.z.apk` from [Releases](../../releases/latest) (under *Assets*, not "Source code") and install it. The browser and Play Protect warn about any app that isn't from the Play Store: choose *Download anyway* / *Install*.
2. **Shizuku:** install it from the Play Store. Turn on Developer options (Settings → About phone → Software information → tap *Build number* 7 times), then Developer options → *Wireless debugging* → on. In Shizuku tap *Pairing*, then in Wireless debugging *Pair device with pairing code*, type the code into Shizuku's notification, and press *Start* in Shizuku. After every phone restart, open Shizuku and press Start again.
3. **Open Silly Macro**, sign in or create an account, and follow the numbered **Setup & ready check** list on Home, top to bottom:
   1. *Turn on Silly Macro* (Accessibility). If the switch is greyed out ("Controlled by Restricted setting"): Settings → Apps → Silly Macro → ⋮ → *Allow restricted settings*.
   2. *Start the keyboard + mouse* → allow Silly Macro in Shizuku's prompt (*Allow all the time*).
   3. *Allow screen capture* → choose **Share entire screen** (not "A single app").
   4. *Pick your hive slot* in the Hive tab → *Confirm slot* (slot 3, straight ahead of spawn, is the most tested). Set your move speed there too.
   5. *Open Bee Swarm*.
4. **Roblox:** in the game menu → Settings → View & Controls → *Camera Mode: Classic*.
5. Hold the device sideways and press **▶ Start** on the floating panel. The first time, give it a few seconds: it finds the Honey/Pollen counters and measures your screen by itself.

Stop with *Stop* on the panel; the red button is an emergency stop that releases every key.

## Troubleshooting

| Problem | Fix |
|---|---|
| "Bee Swarm HUD not recognized" / UNKNOWN | Update to the latest version. Screen capture on with *Share entire screen*, Bee Swarm in landscape with the Honey/Pollen counters showing, wait a few seconds. Don't change Developer options → *Smallest width*. |
| "Dark" / black screen | Settings → Stop screen capture, then Home step 3 → *Share entire screen*. |
| Start does nothing | Read the ⚠ line on the floating panel: it says what's missing. |
| Keeps opening the menu / respawn tab, or won't move | The keyboard + mouse isn't running: start Shizuku, then Home step 2. If it still doesn't move, close Roblox completely and reopen it. |
| Spins or keeps resetting at the hive | Camera Mode → Classic, and confirm your hive slot (it must be free). |
| Floating panel gone | Home step 5 brings it back (or Settings → Show floating overlay). |
| "Started, but no answer yet" | Restart Shizuku, press step 2 again. On cloud phones the keyboard can't be created at all. |
| Slows down or stops on long runs | Settings → Overnight → *Battery settings* → Unrestricted. Keep the device cool and plugged in. |

Reporting a bug: Settings → Debug → **Share log**, and paste it with a screenshot in #bug-reports. The log hides your email.

## Known limitations

- All 17 of Natro's gathering fields can be picked, but **Pine Tree Forest is the only one tested live**; the others use Natro's routes and are untested. Quests, Wealth Clock and token collection aren't in yet.
- Phones are measured automatically; on small tablets and 16:9 phones the honey digits are too small to measure, so a default scale based on the screen height is used.
- Reading the game's own "…from Pine Tree Forest" messages is tablet-only so far.
- The sprinkler and the tool rely on the keyboard + mouse (Shizuku); there's no touch-only mode.

## What it does and doesn't do

- **Reads the screen** through Android's normal screen capture. Android asks you to allow it each time. Frames are analysed on the phone and never uploaded, except screenshots you choose to send to your own Discord webhook.
- **Presses keys and moves the mouse** through a virtual keyboard and mouse (Android's `hid` tool or Linux uhid), the same way a Bluetooth keyboard would. It runs as the `shell` user, started either by [Shizuku](https://github.com/RikkaApps/Shizuku) or by `start_keys.ps1` over adb. It listens only on `127.0.0.1` and needs a per-install token.
- **Does not** inject code into Roblox, modify Roblox files, root the device, or get around Android or Roblox security.
- **Does not** buy anything or spend in-game currency.
- **Network use:** your Discord webhook (if you set one), and GitHub only when you tap "Install Shizuku". Nothing else.

Using macros may be against Roblox's or the game's rules. Use it at your own risk.

## Accounts (official builds)

Official builds ask you to sign in, using Firebase Authentication. The admin can disable accounts that are used for anything bad.

- **What's stored:** your email, when you last used the app, your device model and the app version.
- **What's never stored:** your password. It goes only to Firebase.

A build made from this source without `assets/firebase.properties` has no login.

The admin app is in `admin/`, and the Firestore access rules are in `firestore.rules`.

## Permissions (AndroidManifest.xml)

| Permission | Why |
|---|---|
| Accessibility service (you turn it on) | The floating panel and a few menu taps |
| Screen capture (asked at runtime) | See the game |
| Display over other apps | The in-game overlay |
| Foreground service | Keep running while Roblox is in front |
| Internet | Local key sender on 127.0.0.1, Discord webhook, Shizuku download |
| Install packages | Only for the optional "Install Shizuku" button |

## Building

Requirements: Windows, JDK 17+, and the Android SDK with `build-tools/36.0.0` and `platforms/android-36`. Gradle isn't needed.

```powershell
.\build.ps1            # builds BeeMacro.apk
.\build.ps1 -Install   # builds and installs over adb
```

The APK is signed with your local debug key.

Two folders stay out of this repo:
- The Bee Swarm Simulator icon catalog. The art is © Onett, from the BSS wiki.
- Gameplay screenshots used by the self-test.

`build.ps1` skips both when they're missing. Without the catalog, the app runs without buff and token icon recognition. `catalog/catalog_src.tsv` lists every icon's wiki source if you want to rebuild it.

## Layout

- `src/com/iipxp/beemacro/`: the app.
  - `Engine`: the macro loop.
  - `Monitor`, `Vision`, `NavGuard`, `FieldMap`: screen reading.
  - `KeyServer`: the virtual keyboard and mouse.
  - `MainActivity`, `MacroService`: UI and overlay.
- `assets/patterns`, `assets/paths`: Natro Macro patterns and walking paths (GPL-3.0, see their NOTICE files).
- `test/`: desktop self-test and catalog builder.

## License

GPL-3.0, see `LICENSE`. It includes GPL-3.0 content from Natro Macro.

The Shizuku API jars in `libs/` are Apache-2.0.
