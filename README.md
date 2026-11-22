# Silly Macro

An Android macro for Roblox **Bee Swarm Simulator**: it walks to a field, farms with a pattern, goes back and converts at your hive, and repeats. Routes and patterns follow [Natro Macro](https://github.com/NatroTeam/NatroMacro).

All the source is here, so you can read exactly what it does and build the APK yourself.

## What it does and doesn't do

- **Reads the screen** through Android's normal screen capture. Android asks you to allow it each time. Frames are analysed on the phone and never uploaded, except screenshots you choose to send to your own Discord webhook.
- **Presses keys and moves the mouse** through a virtual keyboard and mouse (Linux uhid), the same way a Bluetooth keyboard would. It runs as the `shell` user, started either by [Shizuku](https://github.com/RikkaApps/Shizuku) or by `start_keys.ps1` over adb. It listens only on `127.0.0.1` and needs a per-install token.
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
