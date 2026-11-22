# telecom-probe (Phase 0 spike)

> **This is a throwaway experiment app. It is not the product, and nothing in the product will depend on it.**

It answers the questions the telephony decision is gated on ([ADR-0001](../../docs/decisions/ADR-0001-telephony-architecture.md)), running on the real Moto G60. The experiment IDs refer to [DEVICE_TESTING §4](../../docs/DEVICE_TESTING.md#4-phase-0-experiments).

| Component | Purpose |
|---|---|
| `MainActivity` | Control panel: role requests, experiment settings, no-SIM experiments, timeline |
| `DialActivity` | Minimal `ACTION_DIAL` handler, required for `ROLE_DIALER` |
| `ProbeInCallService` + `AutoAnswerSession` | E1/E2: callbacks and ring timer → `answer()`. E3: speaker + greeting. E4: capture. E6: `disconnect()` |
| `InCallActivity` | Minimal incoming / in-call UI (answer, decline, end, speaker, manual E4 capture) |
| `ProbeCallService` | Foreground service (`phoneCall\|microphone`) while a call exists. E5 logs whether it may start |
| `ProbeScreeningService` | E10: optionally rejects incoming calls |
| `CaptureProbe` | Records one `AudioSource` and logs peak dBFS plus the framework's `isClientSilenced` flag every second |
| `GreetingPlayer`, `TtsProbe` | E3 playback with a chosen `AudioAttributes` usage; E7 voice listing and `synthesizeToFile` |

## Build and install
```bash
cd spikes/telecom-probe
./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb logcat -s TelecomProbe            # every experiment step is logged here
```

## Loopback calls (no SIM, no network)
`LoopbackConnectionService` is a managed calling account. `fake_incoming` injects an incoming call through `TelecomManager.addNewIncomingCall()`. Telecom rings it, binds the probe's InCallService, and switches to `MODE_IN_CALL`, exactly as it does for a carrier call. There is no far end, so E3 (does the caller hear the greeting?) **cannot** be tested this way. See [DEVICE_TESTING §4.1](../../docs/DEVICE_TESTING.md#41-loopback-calls-network-free-method).
```bash
P=io.github.thisisankit27.voicemail.probe
B="adb shell am broadcast -n $P/.ProbeCommandReceiver --es probe_action"   # works while locked / in background
$B register_loopback
adb shell telecom set-phone-account-enabled  $P/.LoopbackConnectionService loopback 0
$B render_caller
$B fake_incoming --es scenario message          # | hangup_ringing | silent
$B capture_long --ei value 40                   # capture started before a call (loophole check)
adb shell telecom set-phone-account-disabled $P/.LoopbackConnectionService loopback 0   # revert
```
`ProbeCommandReceiver` requires `android.permission.DUMP`, which the ADB shell holds and third-party apps cannot get.

## Shell-identity audio helper (Option 4, personal build)
`ShellAudioMain` is a command-line program, not an app component. It runs as the **ADB shell user (uid 2000)**, which holds `CAPTURE_AUDIO_OUTPUT` and `MODIFY_PHONE_STATE` on the G60. No root is needed.
```bash
APK=$(adb shell pm path io.github.thisisankit27.voicemail.probe | head -1 | cut -d: -f2 | tr -d '\r')
SH="adb shell CLASSPATH=$APK app_process /system/bin io.github.thisisankit27.voicemail.probe.ShellAudioMain"
$SH devices                                   # Telephony Tx/Rx show up as type 18
$SH capture 3 20 /data/local/tmp/downlink.wav # VOICE_DOWNLINK (3), VOICE_UPLINK (2), VOICE_CALL (4), MIC (1)
$SH play /data/local/tmp/greeting.wav 2       # into the call uplink (Telephony TX)
$SH tone 600                                  # beep into the call
```
The real-call experiment is one command: `scripts/realcall-shell-test.sh` (dry-run it with `LOOPBACK=1`).

## Drive it from ADB
```bash
A="adb shell am start -n io.github.thisisankit27.voicemail.probe/.MainActivity --es probe_action"
$A status                    # roles, default dialer, config
$A tts_voices                # E7 voice inventory
$A render_greeting           # E7 render the greeting WAV used by E3
$A play_greeting             # local playback check
$A mic_baseline_all          # E4 no-call baseline, 3 s per source
$A set_auto_answer --ei value 20   # E2: answer after 20 s (0 = off)
$A set_source --ei value 6         # E4: AudioSource constant (1 MIC, 5 CAMCORDER, 6 VOICE_RECOGNITION, 7 VOICE_COMMUNICATION, 9 UNPROCESSED)
$A set_usage --ei value 2          # E3: AudioAttributes usage (1 MEDIA, 2 VOICE_COMMUNICATION, 16 ASSISTANT)
$A set_speaker --ez value true
$A set_app_greeting --ez value false   # leave call audio to the shell helper
$A set_probe_capture --ez value false  # don't compete with a shell-helper capture
```
Captured WAVs land in `/sdcard/Android/data/io.github.thisisankit27.voicemail.probe/files/captures/` (fetch them with `adb pull`). They are **gitignored and must never be committed**, because they can contain people's voices.

## User actions (👤) and how to revert them
- **Become the default phone app:** tap *Become dialer* and confirm the system dialog. To revert: *Revert: default apps* → Phone app → Phone (Google).
- **Become the call screener (E10 only):** tap *Become screener*. Revert in the same settings screen (Caller ID & spam app).
- The probe never changes call forwarding or system settings.

## Known limits (by design)
- No multi-call, hold, conference or Bluetooth UI. Emergency calls are always handled by the system dialer.
- Speaker routing uses `InCallService.setAudioRoute`, which is deprecated in API 34 but correct for the Android 12 G60.
