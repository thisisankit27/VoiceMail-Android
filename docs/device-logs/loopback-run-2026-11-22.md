# Loopback-call run, 2026-11-22

- **Device:** Moto G60, Android 12 (API 31).
- **Probe:** `spikes/telecom-probe`, holding `ROLE_DIALER` (granted by the owner through the system dialog).
- **Calls:** Every call was a **network-free loopback call**. The probe's own `LoopbackConnectionService` (a managed `PhoneAccount` with `CAPABILITY_CALL_PROVIDER`) injected them with `TelecomManager.addNewIncomingCall()`. The calling account was enabled for the session with `adb shell telecom set-phone-account-enabled …` and **disabled again afterwards** with `set-phone-account-disabled`.

The excerpts below are `adb logcat -s TelecomProbe` with the prefix stripped. The number is a fake (`+91 00000 00042`), shown redacted.

## 1. Mid-call audio mode (`dumpsys audio`, ~12 s into the first call)
```text
- Requested mode = MODE_IN_CALL
- Actual mode = MODE_IN_CALL
```
Telecom treats the loopback call like a carrier call, so the concurrent-capture policy applies to it just as it would to a real call.

## 2. Full timeline: auto-answer, speaker, greeting, capture (source MIC)
```text
LOOPBACK addNewIncomingCall scenario=message
LOOPBACK onCreateIncomingConnection scenario=message number=••42
E10 onScreenCall incoming=true number=••42 -> ALLOW
InCallService created (bound by Telecom)
onCallAdded state=RINGING dir=IN number=••42 presentation=ALLOWED accountHash=8694d98b | autoAnswer=5s speaker=true usage=VOICE_COMMUNICATION source=MIC capture=25s screeningReject=false
E2 armed: auto-answer in 5 s
audio route=EARPIECE muted=false
E5 foreground service started (phoneCall|microphone)
InCallActivity shown
E2 timer fired at 5001 ms -> answer(STATE_AUDIO_ONLY)
LOOPBACK onAnswer -> setActive (scenario=message)
onStateChanged -> ACTIVE | state=ACTIVE dir=IN number=••42 ...
E2 call ACTIVE at 5194 ms
requested audio route SPEAKER
audio route=SPEAKER muted=false
volumes voiceCall=6/8 music=12/15 ring=4/7 mode=2 speakerphone=true
E3 greeting playing usage=VOICE_COMMUNICATION durationMs=7229
E3 greeting playback completed
E4 capture started source=MIC file=call-MIC-1795363487426.wav
LOOPBACK caller speaking (proxy downlink) durationMs=8145
E4 t=1s peak=-120.0 dBFS silenced=true
   … identical every second …
E4 t=11s peak=-120.0 dBFS silenced=true
LOOPBACK remote hangup
onStateChanged -> DISCONNECTED ...
session stopped: disconnected at 25643 ms (answeredByProbe=true)
E5 foreground service stopped
E4 RESULT source=MIC seconds=11.3 loudest=-120.0 dBFS digitalSilence=100% silencedEver=true
InCallService destroyed (unbound)
```

## 3. Every capture source during the call
```text
E4 RESULT source=MIC                 seconds=11.3 loudest=-120.0 dBFS digitalSilence=100% silencedEver=true
E4 RESULT source=VOICE_RECOGNITION   seconds=11.3 loudest=-120.0 dBFS digitalSilence=100% silencedEver=true
E4 RESULT source=VOICE_COMMUNICATION seconds=11.2 loudest=-120.0 dBFS digitalSilence=100% silencedEver=true
E4 RESULT source=CAMCORDER           seconds=11.2 loudest=-120.0 dBFS digitalSilence=100% silencedEver=true
E4 RESULT source=UNPROCESSED         seconds=11.2 loudest=-120.0 dBFS digitalSilence=100% silencedEver=true
```
Each source logged `silenced=true` for 11 of 11 seconds.

## 4. Pre-call capture (loophole check)
A 40 s MIC capture was started **5 s before** the call, then a fake call was placed and auto-answered at 5 s:
```text
E4(pre-call) t=1s..5s   peak −39.6 … −51.4 dBFS  silenced=false     (no call)
onCallAdded state=RINGING …
E4(pre-call) t=6s..11s  peak −36.3 … −50.7 dBFS  silenced=false     (RINGING: not yet "in call")
E2 call ACTIVE at 5173 ms
E4(pre-call) t=12s      peak −51.1 dBFS          silenced=true      (switch happens here)
E4(pre-call) t=13s..31s peak −120.0 dBFS         silenced=true      (ACTIVE: all zeros)
LOOPBACK remote hangup / DISCONNECTED
E4(pre-call) t=32s..39s peak −37.7 … −50.5 dBFS  silenced=false     (call over: live again)
```

## 5. Locked screen, probe in background (E5)
Before the call: `input keyevent KEYCODE_HOME` then `KEYCODE_SLEEP`. The device reported `mWakefulness=Dozing` and `mDreamingLockscreen=true`, and the call was triggered by a shell-only broadcast.
```text
onCallAdded state=RINGING …
E5 foreground service started (phoneCall|microphone)
InCallActivity shown
E2 timer fired at 5006 ms -> answer(STATE_AUDIO_ONLY)
E2 call ACTIVE at 5127 ms
audio route=SPEAKER muted=false
E3 greeting playing usage=VOICE_COMMUNICATION durationMs=7229
LOOPBACK remote hangup
E4 RESULT source=MIC seconds=11.2 loudest=-120.0 dBFS digitalSilence=100% silencedEver=true
```

## 6. Caller hangs up while ringing (Test 4), auto-answer set to 20 s
```text
LOOPBACK onCreateIncomingConnection scenario=hangup_ringing number=••42
E2 armed: auto-answer in 20 s
LOOPBACK caller hangs up while ringing (8 s)
onStateChanged -> DISCONNECTED ...
session stopped: disconnected at 7621 ms (answeredByProbe=false)
```
An earlier attempt at this run is **void**. The scenario extra did not reach the connection, so the call ran the default `message` script. The probe was fixed to hand the scenario over in-process, and the run above is the valid one.

## 7. Caller stays silent (Test 10)
```text
LOOPBACK onCreateIncomingConnection scenario=silent number=••42
E2 call ACTIVE at 5184 ms
E3 greeting playing ...
E4 capture started source=MIC ...
LOOPBACK remote hangup
session stopped: disconnected at 19238 ms (answeredByProbe=true)
```

## 8. Independent check of the WAV files
The captures were pulled with `adb pull` and checked with Python's `wave` module. This check does not use the probe's own RMS code.
```text
baseline-MIC.wav                     2.9s  nonzero= 89.8%  peak=2481
baseline-VOICE_RECOGNITION.wav       3.0s  nonzero= 89.4%  peak=2142
baseline-VOICE_COMMUNICATION.wav     2.9s  nonzero= 10.1%  peak=147
baseline-CAMCORDER.wav               3.0s  nonzero= 88.6%  peak=659
baseline-UNPROCESSED.wav             3.0s  nonzero= 89.2%  peak=166
call-MIC-…426.wav                   11.3s  nonzero=  0.0%  peak=0
call-VOICE_RECOGNITION-…476.wav     11.3s  nonzero=  0.0%  peak=0
call-VOICE_COMMUNICATION-…091.wav   11.2s  nonzero=  0.0%  peak=0
call-CAMCORDER-…337.wav             11.2s  nonzero=  0.0%  peak=0
call-UNPROCESSED-…495.wav           11.2s  nonzero=  0.0%  peak=0
long-MIC-…830.wav (pre-call)        39.7s  nonzero= 47.3%  peak=1550
call-MIC (locked, E5)               11.2s  nonzero=  0.0%  peak=0
```
The audio files themselves are not committed.
