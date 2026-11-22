# Real-call run, shell-identity audio helper (2026-11-22)

- **Device:** Moto G60, Android 12. Probe holds `ROLE_DIALER`.
- **SIM:** **Airtel** in slot 1, IN_SERVICE.
- **Caller:** a second phone; the number is redacted by the probe.
- **Command:** `scripts/realcall-shell-test.sh` with RING=10, REC=20, MUTE=true.
- **Audio files:** kept on the owner's machine only, never committed.

## Probe timeline (`TelecomProbe`, number redacted)
```text
22:25:16.748 E10 onScreenCall incoming=true number=••05 -> ALLOW
22:25:16.803 InCallService created (bound by Telecom)
22:25:17.100 onCallAdded state=RINGING dir=IN number=••05 ... autoAnswer=10s speaker=false
22:25:17.144 E2 armed: auto-answer in 10 s
22:25:17.162 E5 foreground service started (phoneCall|microphone)
22:25:17.218 InCallActivity shown
22:25:27.148 E2 timer fired at 10004 ms -> answer(STATE_AUDIO_ONLY)
22:25:27.250 E2 call ACTIVE at 10107 ms
22:25:27.250 E11 microphone muted: the room is not sent to the caller
22:25:27.250 E11 app greeting disabled; shell helper drives call audio
22:25:27.655 audio route=EARPIECE muted=true
22:25:46.389 onStateChanged -> DISCONNECTED ...
22:25:46.391 session stopped: disconnected at 29247 ms (answeredByProbe=true)
```

## Audio HAL (Qualcomm `voice_extn`)
```text
22:25:27.416 update_calls: INACTIVE -> ACTIVE vsid:11c05000      ← a real modem voice session
22:25:46.322 update_calls: ACTIVE/HOLD/LOCAL_HOLD -> INACTIVE vsid:11c05000
```
There were **no** `incall_music` errors. Compare the loopback run, which logged `Invalid session id 0`.

## Shell helper output (uid 2000)
```text
play : telephony TX device id=9; setPreferredDevice(TX)=true; routedDevice=18; played 7.23 s in 7509 ms
tone : routedDevice=18; played 0.6 s in 840 ms
uplink   (VOICE_UPLINK, during greeting)  RESULT loudest=-3.1 dBFS digitalSilence=17%
downlink (VOICE_DOWNLINK, after the beep) RESULT loudest=-7.9 dBFS digitalSilence=74%
voicecall(VOICE_CALL, after the beep)     RESULT loudest=-7.5 dBFS digitalSilence=72%
```

## Per-second levels (RMS dBFS / non-zero samples)
```text
uplink-during-greeting.wav (9.0 s)
  0s -11.5 63% | 1s -7.5 100% | 2s -10.3 99% | 3s -7.2 100% | 4s -7.9 100% | 5s -9.8 98% | 6s -8.4 100% | 7s -14.7 57% | 8s silence 0%
downlink.wav (19.9 s; the call ended about 8 s into this capture)
  0s -24.6 72% | 1s -11.5 99% | 2s -14.4 100% | 3s -20.1 61% | 4s -18.1 72% | 5s -104.9 1% | 6s..19s silence 0%
```

## Is the greeting really in the uplink?
Log-energy envelope cross-correlation (10 ms hops) of `uplink-during-greeting.wav` against `greeting.wav`:
```text
greeting vs uplink : score=1.01 (greeting offset 0.50 s, capture lag 0.86 s)
reversed control   : score=0.48
```
The uplink contains the greeting. Once the greeting ends (second 8), the uplink is exact digital zero, so the microphone mute held and **no room audio was sent**.

## Interpretation
- **E2 (real network):** the app's timer answered a real Airtel call at 10.0 s. The network's own no-answer timeout was not measured.
- **E11:** **PASS (objective).** The greeting was injected into the call uplink with no speakerphone, and nothing else was sent. The owner still needs to confirm the greeting was intelligible on the caller's phone.
- **E12:** **PASS (provisional).** `VOICE_DOWNLINK` captured about 5 s of speech-level signal (−11 … −24 dBFS) from the caller before they went quiet and hung up. The owner still needs to listen to `downlink.wav` and confirm it is the caller's voice.
