# Silly Macro: no screen recording + AI layer

## Screen reading without MediaProjection
- **`FrameServer.java` (new)** runs inside the key sender's shell process. `KeyServer.main` starts it, and it listens on `127.0.0.1:28762` (KeyServer.PORT + 1) using the same token file.
  - It mirrors the display as the shell user, the way scrcpy does. There's no consent dialog, no cast icon, and nothing is recorded or saved.
  - It tries three paths in order:
    1. Android 14+: `DisplayManager.createVirtualDisplay(name, w, h, displayToMirror, surface)`
    2. Android 9–13: `SurfaceControl.createDisplay` with a layer-stack projection
    3. `screencap` raw output, as a slow fallback
  - The mirror only receives frames while the app is asking for them. After 1.5 s with no request its surface is detached, so the compositor does no work for it.
- **Wire format:** the app sends `f MAXW\n`. The reply is 4 big-endian ints (`MAGIC`, status, w, h) followed by w·h·4 RGBA bytes. The format is defined in `FrameWire.java`.
- **`ShellFrames.java` (new)** is the app-side client.
- **`CaptureService.java`** keeps the same API (`inst`, `Frame`, `grab()`, `stopCapture`), but now gets its frames from ShellFrames.
  - It's a `specialUse` foreground service, and it no longer requires the MediaProjection permission.
  - It starts the key sender through Shizuku if it isn't running. If the running key sender is older than protocol 6, it restarts it.
- **`SenderStatus.PROTO` = 6.** The existing preflight check restarts any older key sender before a run.
- **Manifest:** `FOREGROUND_SERVICE_MEDIA_PROJECTION` is replaced by `FOREGROUND_SERVICE_SPECIAL_USE`, and the service now declares its subtype property.
- **`MainActivity.requestCapture()`** starts the service directly. The MediaProjection intent and result handler are gone.

## AI layer
- **`AiBandit.java`** learns a score for each option: an EMA of the reward, with epsilon-greedy and UCB-style exploration. Scores are saved between runs.
- **`AiBrain.java`** handles pattern learning, one learner per field. It's off by default and controlled by the `aiPatternLearn` setting.
  - Before each trip, `Engine.farm()` picks a pattern from `Profile.aiPatterns` (default `Snake,e_lol,Squares,Lines`).
  - After a normal trip, the pattern is scored in bag % per minute. Trips under a minute, an unreadable bag, or a falling bag % aren't scored.
  - The scores are stored in `files/ai_brain.properties`.
  - Engine reads the pattern through `pat()` instead of `p.pattern`, so the saved setting is never overwritten.
- **`AiPower.java`** is the heat governor: NORMAL / THROTTLED / HOT, with hysteresis. **`AiSensors.java`** feeds it battery temperature and the thermal status.
  - Monitor runs vision 2.5× slower while the phone is warm.
  - When the phone is HOT, `farmLoop` ends the trip early and `afterTrip()` rests in an `aiCoolDown()` phase. The rest lasts at most 15 min, and it sends a Scroll Lock nudge so the game doesn't treat the player as idle.
- **New Profile fields:** `aiPatternLearn`, `aiThermal`, `aiPatterns`, `aiExplore`, `aiThrottleC`, `aiPauseC`, `aiResumeC`.
- **New settings card:** Performance → "AI".
- **`test/AiSelfTest.java`** (23 checks) runs in step 1 of `build.ps1`.

## Not verified on a device
The project builds with `build.ps1` and the self-tests pass. FrameServer's hidden-API mirror paths haven't been run on a phone yet. Check the key sender's log for the `frames:` lines on the first run.
