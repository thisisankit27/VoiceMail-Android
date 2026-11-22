# Shell-identity audio run, 2026-11-22 (no root, no custom ROM)

- **Context:** The owner decided the app is for **personal use only**, with no Play Store distribution, and asked for "system app" powers without root or a custom ROM.
- **Approach:** Run the audio code as the **ADB shell user (uid 2000)**, using `app_process` with the probe APK on the classpath.
- **Device:** Moto G60, Android 12. Probe holds `ROLE_DIALER`.
- **Calls:** network-free loopback calls (see [loopback-run](loopback-run-2026-11-22.md)).

## 1. What the shell identity holds (`dumpsys package com.android.shell`)
```text
android.permission.CAPTURE_AUDIO_OUTPUT: granted=true
android.permission.CONTROL_INCALL_EXPERIENCE: granted=true
android.permission.MODIFY_AUDIO_SETTINGS: granted=true
android.permission.MODIFY_PHONE_STATE: granted=true
android.permission.RECORD_AUDIO: granted=true, flags=[ SYSTEM_FIXED|GRANTED_BY_DEFAULT|... ]
```
Granting the same permission to the probe is impossible:
```text
$ adb shell pm grant io.github.thisisankit27.voicemail.probe android.permission.CAPTURE_AUDIO_OUTPUT
Exception occurred while executing 'grant'
```

## 2. The audio HAL exposes an "in-call music" path into the uplink (`dumpsys media.audio_policy`)
```text
- flags: 0x10000 (AUDIO_OUTPUT_FLAG_INCALL_MUSIC)
- name: incall_music_uplink
  Selected Device: {type:AUDIO_DEVICE_OUT_TELEPHONY_TX, @:}
[sink 1] Device ID 9 AUDIO_DEVICE_OUT_TELEPHONY_TX
```
The shell helper's device list (one hidden API, `AudioManager.getDevicesStatic`):
```text
ShellAudio uid=2000 sdk=31
device id=9  type=18 OUT  (TYPE_TELEPHONY → Telephony Tx, into the call)
device id=19 type=18 IN   (TYPE_TELEPHONY → Telephony Rx, from the call)
```

## 3. Attribution fix
The first capture attempt as shell was refused:
```text
E AppOps  : Attribution not found: uid=2000 pkg=com.android.shell(null) op=RECORD_AUDIO
E AudioPolicyIntefaceImpl: startInput permission denied: recording not allowed for attribution source ... packageName: uid:2000
```
The fix was to build the `AudioRecord` with `AudioRecord.Builder.setContext()` (public, API 31), passing a minimal Context whose `AttributionSource` is (uid 2000, `com.android.shell`). After that, no-call MIC capture as shell worked:
```text
capture source=1 state=1  recordingState=3
t=1s peak=-28.2 dBFS silenced=false … RESULT loudest=-23.8 dBFS digitalSilence=10%
```

## 4. Shell MIC capture during an ACTIVE loopback call
With the probe's own capture **on**, the shell capture was live for 6 s, then silenced at the moment the probe app started its own capture. The newer capture, from the foreground app, takes the mic:
```text
t=1s..6s peak −32 … −47 dBFS silenced=false
t=7s     peak −22.6 dBFS     silenced=true
t=8s..16s peak −120.0 dBFS   silenced=true
```
With the probe's own capture **off**, the shell capture was **never silenced** for the whole active call:
```text
t=1s..18s peak −15.7 … −46.1 dBFS  silenced=false (every second)
RESULT source=1 loudest=-15.7 dBFS digitalSilence=1%
```
Whether the proxy caller's voice is *in* that recording could not be shown. Envelope cross-correlation against the caller clip in the expected window scored 0.20, against 0.22 for a time-reversed control. The proxy downlink is not a reliable signal, so this is left to a real call.

## 5. Direct call-audio capture as shell (VOICE_DOWNLINK=3, VOICE_CALL=4)
```text
capture source=3 state=1 recordingState=3  t=1..6s silenced=false  RESULT loudest=-120.0 dBFS digitalSilence=100%
capture source=4 state=1 recordingState=3  t=1..6s silenced=false  RESULT loudest=-120.0 dBFS digitalSilence=100%
```
**Permission and policy layers passed** (initialised, recording, not silenced). The audio is all zeros because a loopback call has no modem voice session to capture. This needs a real call.

## 6. Greeting played into the call uplink as shell
```text
telephony TX device: id=9 'moto g(60)'
wav rate=24000 channels=1 samples=173493
setPreferredDevice(TX)=true
routedDevice after start: type=18 'moto g(60)'
played 7.228875 s in 7488 ms; routedDevice=18
D PermissionCache: checking android.permission.MODIFY_PHONE_STATE for uid=2000 => granted
E voice_extn: voice_extn_check_and_set_incall_music_usecase: Invalid session id 0
```
**The framework allowed it and routed the track to Telephony TX.** The Qualcomm HAL then declined to start in-call music because no modem voice session exists (`session id 0`). On a real call a voice session exists. This needs a real call.

## 7. Real-call script dry run (`LOOPBACK=1`)
`spikes/telecom-probe/scripts/realcall-shell-test.sh` ran end to end: auto-answer, TX playback (routed to type 18), uplink self-check capture, downlink and voice-call captures, logs, pull, config restore. All captures were zeros, as expected without a modem.
