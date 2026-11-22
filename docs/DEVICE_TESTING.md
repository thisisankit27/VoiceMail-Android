# Device Testing: Moto G60

## 1. Device baseline [VERIFIED-DEVICE]
The full raw output is in [device-logs/moto-g60-baseline-2026-11-22.md](device-logs/moto-g60-baseline-2026-11-22.md).

| Property | Value |
|---|---|
| Model / codename / SKU | moto g(60) / `hanoip` / XT2135-2 |
| Android / SDK | 12 / 31 |
| Build | `S2RI32.32-20-9-9-2`, security patch 2023-07-01 |
| SoC / ABI | sm6150 (Snapdragon 732G) / arm64-v8a |
| RAM / free storage | 5.5 GB / 90 GB |
| Radio | Dual-SIM dual-standby (`dsds`), GSM + IMS (VoLTE) features |
| **SIM state** | Inspection: **ABSENT, ABSENT**. Later the same day: BSNL SIM in slot 2 is `LOADED`, but the network **denies registration** (emergency calls only; prepaid plan not recharged) |
| Default dialer | `com.google.android.dialer` 247.0.x (privileged system app holding `CAPTURE_AUDIO_OUTPUT`) |
| Call screening / redirection role | none |
| TTS / recognizer | Google TTS 20261026 / GoogleTTSRecognitionService |
| Ringer at inspection | Silent/vibrate (ring stream muted) |

**Not yet inspectable (needs a SIM):**
- Carrier config (`dumpsys carrier_config`: `vvm_type`, voicemail number, forwarding UI flags).
- Call forwarding status.
- Network no-answer timeout.
- Real-call audio behaviour.

## 2. Ground rules
1. **No system change without an explicit, documented user action.** Each such step below is marked **👤 USER ACTION** and has a revert step.
2. Changing the default dialer is done only through the system role dialog or Settings, by the user. It is reverted after every session (Settings → Apps → Default apps → Phone app → Phone).
3. Call forwarding is only *queried* (`*#61#`), never set, unless the user explicitly decides to test it (Test 5).
4. No root, no `adb shell settings put`, no `appops` overrides.
5. Captured audio from experiments stays on the dev machine (gitignored) and is deleted after analysis. It contains the voice of the second-phone operator, who must consent.

## 3. Setup procedure (reproducible)
```bash
# Host
export ANDROID_HOME=~/Android/Sdk
adb devices -l                                    # expect exactly one device in state "device"
adb shell getprop ro.build.fingerprint            # must match baseline, else re-baseline
# Device (👤 USER ACTION): insert SIM in slot 1; set ringer to normal; disable Do Not Disturb.
adb shell getprop gsm.sim.state                   # expect READY[,ABSENT]
adb shell dumpsys telephony.registry | grep -m2 mVoiceRegState   # expect IN_SERVICE for the SIM slot
adb shell dumpsys carrier_config > carrier_config-$(date +%F).txt   # gitignored; inspect vvm_* and call_forwarding keys
```

### Log capture (use for every test)
```bash
TS=$(date +%Y%m%d-%H%M%S)
adb logcat -c
adb logcat -v threadtime -b main,system,radio,events,crash > logcat-$TS.txt &   # gitignored
# ... run test ...
kill %1
adb shell dumpsys telecom   > telecom-$TS.txt    # call history ("Historical Events"), audio route transitions
adb shell dumpsys audio     > audio-$TS.txt      # audio mode, active recorders/players, routes
adb shell dumpsys media.audio_flinger > af-$TS.txt   # active input streams/sources during call (may require the call to be active)
adb shell dumpsys role | grep -A3 DIALER
adb shell dumpsys notification --noredact | grep -A20 voicemail   # notification tests only
```
Useful logcat tags: `Telecom`, `InCallController`, `CallAudioRouteStateMachine`, `AudioPolicyManager`, `AudioFlinger`, `APM_AudioPolicyManager`, `TelecomProbe` (spike), and `VoiceMail` (app).

### Install / launch (spike or app)
```bash
cd spikes/telecom-probe && ./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk   # -g grants the probe's own runtime permissions
adb shell am start -n io.github.thisisankit27.voicemail.probe/.MainActivity
# ADB-driven actions (see spikes/telecom-probe/README.md):
adb shell am start -n io.github.thisisankit27.voicemail.probe/.MainActivity --es probe_action status
adb shell cmd role get-role-holders android.app.role.DIALER   # verify after the 👤 role grant
```

## 4. Phase 0 experiments
These use the throwaway `spikes/telecom-probe` app (PRs 02–05). Each row needs a second phone ("Phone B") unless noted. Results are recorded in the *Actual* columns in PR-06.

| ID | Pre-conditions | Procedure | Expected (hypothesis) | Actual | Pass/Fail | Logs |
|---|---|---|---|---|---|---|
| **E1** Role + binding | Probe installed. No SIM needed | 1. 👤 Grant ROLE_DIALER via the probe's button. 2. `cmd role get-role-holders android.app.role.DIALER`. 3. `dumpsys telecom` → `mInCallServices`. 4. 👤 Revert | Probe is the holder. InCallService is listed when a call (or emergency-test-free dummy) exists | 2026-11-22: the owner confirmed the system dialog, and `RESULT_OK` was returned. The holder is `io.github.thisisankit27.voicemail.probe`, and it survived 4 reinstalls without being revoked. Telecom bound `ProbeInCallService` for every loopback call. [loopback log](device-logs/loopback-run-2026-11-22.md) | **Pass** | role, telecom dumps |
| **E2** Ring + timer + answer | SIM in service. Probe is the dialer. Forwarding queried with `*#61#` and recorded | Phone B calls the G60. Probe logs `onCallAdded` and the state timeline. Run 1: never answer, and measure how long until the network drops or forwards. Runs 2–4: auto-answer at 10, 20 and 25 s | `answer()` succeeds before the network timeout. Network timeout is measured per operator | **App side verified by loopback:** the timer fires at 5001–5006 ms (and at 20005 ms when set to 20 s). `answer(STATE_AUDIO_ONLY)` reaches ACTIVE in about 120–190 ms. The probe correctly does not answer when the caller hangs up first. **Network no-answer timeout: pending a real call** | Partial (app ✅ / carrier pending) | logcat (Telecom), telecom dump |
| **E3** Greeting audibility | E2 passes. Probe plays a 15 s speech clip after answering, with speaker on | 9 runs: 3 usages (`VOICE_COMMUNICATION`, `MEDIA`, `ASSISTANT`) × 3 calls. G60 face-up on a table. The Phone B operator transcribes what they hear. Also record Phone B's speaker with a third device for objective scoring (optional) | **Unknown.** AEC may cancel it | Pending | Pending | logcat (AudioPolicy), audio dump during playback |
| **E4** Caller capture | E2 passes | During the answered call, the Phone B operator reads a fixed 30 s passage. The probe records in parallel, one source per run: `MIC`, `VOICE_RECOGNITION`, `VOICE_COMMUNICATION`, `CAMCORDER`, `UNPROCESSED`. It logs RMS every 100 ms and saves a WAV | **Likely silenced** (zeros) per the concurrent-capture policy. Any non-silent, intelligible source is a pass | **Loopback (2026-11-22):** with the call ACTIVE (`MODE_IN_CALL`) and on speaker, **all 5 sources returned 100% exact zeros with `isClientSilenced=true` every second**, while the proxy caller was audibly speaking. A capture started *before* the call was live during RINGING, silenced about 1 s after ACTIVE, and live again after hang-up. An independent check of the WAV files found peak sample 0. No-call baseline: all sources live (see row history in the results log). [loopback log](device-logs/loopback-run-2026-11-22.md) | **Fail** (framework-enforced; real-call confirmation advised) | logcat (AudioFlinger: "silenced"), af dump, WAVs (local only) |
| **E5** Unattended/locked | E3/E4 configuration that passed | Lock the screen, kill the probe UI task (`am kill` is not allowed while bound, so swipe it away), wait 5 min, then Phone B calls | Session runs fully while locked. No `ForegroundServiceStartNotAllowedException` and no silenced capture | **Loopback:** screen dozing on the lock screen with the probe in the background, triggered by a shell broadcast. The phoneCall + microphone FGS started, `InCallActivity` showed over the lock screen, and the call was auto-answered, put on speaker and played the greeting. Capture was still silenced (see E4) | **Pass** for the call flow | logcat (ActivityManager, FGS) |
| **E6** Disconnect + caller view | E2 passes | After 30 s of recording, the probe calls `disconnect()`. Repeat with the caller hanging up first. Note the call-log type on the G60 and what Phone B sees (call duration, any charge) | Clean disconnect. Call log shows an *incoming answered* call | **Loopback:** remote hang-up gives a DISCONNECTED → onCallRemoved → InCallService unbind → FGS stopped chain, with no leaks. Caller-side experience and billing: pending a real call | Partial | telecom dump, call-log screenshot |
| **E7** Offline TTS | No SIM. Wi-Fi off | Probe lists `tts.voices` with `isNetworkConnectionRequired`, then `synthesizeToFile` for en-IN and hi-IN | At least one offline en-IN voice renders a valid WAV | 2026-11-22: Google TTS reports 473 voices, **22 installed offline**. en-IN has 4 installed `-local` voices (`ena/enc/end/ene`) and hi-IN has 4 (`hia/hic/hid/hie`). en-GB voices are listed but `notInstalled`. `synthesizeToFile` with `en-in-x-enc-local` produced a valid WAV (24 kHz, mono, 16-bit, 347 KB, about 7.2 s) in 1.24 s. **Caveat:** Wi-Fi was connected during the run. A strict Wi-Fi-off rerun (👤 user toggles Wi-Fi) is still pending | **Pass (provisional)** | logcat (TextToSpeech), WAV |
| **E8** STT benchmark | No SIM. 20 fixture clips (10 Indian English, 5 Hindi, 5 Hinglish; 10–60 s; consented or public-domain) | Run whisper.cpp tiny/base (q5) and Vosk small en-IN/hi on the device. Record real-time factor, peak RSS and WER vs reference | whisper base RTF < 1.0 and WER clearly better than Vosk; Vosk faster | Pending | Pending | probe CSV output |
| **E9** Carrier voicemail/VVM | SIM in service | 👤 Dial `*#61#`, `*#67#`, `*#62#` and record the replies. Inspect carrier_config for `vvm_type_string`, `vvm_*` and the voicemail number. 👤 Long-press 1 in Google Phone and record what happens | Most likely no carrier voicemail or VVM **[UNVERIFIED]** | Pending | Pending | carrier_config dump, screenshots |
| **E10** Screening reject | Probe holds ROLE_CALL_SCREENING (👤), not the dialer role | Probe rejects calls from Phone B's number. Record what Phone B hears (busy, "not reachable", forwarding) | Busy or unavailable announcement. **[CARRIER-SPECIFIC]** | Pending | Pending | logcat (Telecom) |

**Decision rule:** see [ADR-0001](decisions/ADR-0001-telephony-architecture.md). E3 and E4 decide the gate.

### 4.2 Shell-identity experiments (personal build; Option 4)
| ID | Question | Loopback result (2026-11-22) | Real call |
|---|---|---|---|
| **E11** | Can a uid-2000 helper play the greeting **into the call** (Telephony TX)? | Framework: ✅ `MODIFY_PHONE_STATE` granted, routed to type 18. HAL: ⚠️ `Invalid session id 0` (no modem session) | **Pending.** Run `spikes/telecom-probe/scripts/realcall-shell-test.sh` |
| **E12** | Can a uid-2000 helper record the caller **from the call** (`VOICE_DOWNLINK`)? | ✅ initialised, recording, `silenced=false`. Audio zeros (no modem audio) | **Pending.** Same script |
| E12b | Is uid-2000 MIC capture silenced during a call? | ✅ **Not silenced** (18 s live), unless the foreground app starts a newer capture | n/a |

Evidence: [shell-identity log](device-logs/shell-identity-run-2026-11-22.md).

**Real-call procedure:** a SIM in service, the probe as default dialer, USB adb. Run `RING=10 REC=20 spikes/telecom-probe/scripts/realcall-shell-test.sh <out-dir>`, then call the G60 from another phone and let it ring. The caller should hear the greeting and a beep *in the earpiece* (no speakerphone involved), speak for about 15 s, then hang up.
- **E11 passes** if the caller hears the greeting clearly, `uplink-during-greeting.wav` contains it, **and no room audio reaches the caller.** The script mutes the G60 microphone on answer (`InCallService.setMuted(true)`; loopback check: `audio route=EARPIECE muted=true`). Test this by making noise near the G60 while the greeting plays. Then rerun with `MUTE=false` to see what would leak without the mute. Open question **[UNVERIFIED]**: does the Qualcomm in-call-music path still deliver while the mic is muted? If not, the fallback is to leave the mic live during the greeting only and mute it after the beep.
- **Recording privacy:** the message is captured from `VOICE_DOWNLINK` (the caller's side only), so the G60's room audio is not in the voicemail. `voicecall.wav` (both directions) is recorded for comparison only.
- **E12 passes** if `downlink.wav` contains the caller's speech.

Recordings stay local and are deleted after analysis.

### 4.1 Loopback calls (network-free method)
With no usable SIM, the probe's `LoopbackConnectionService` registers a managed calling account (`CAPABILITY_CALL_PROVIDER`) and injects incoming calls with `TelecomManager.addNewIncomingCall()`, which is a public API. Telecom handles these exactly like managed calls: the dialer's InCallService is bound and the audio mode becomes `MODE_IN_CALL` (verified with `dumpsys audio`).

```bash
P=io.github.thisisankit27.voicemail.probe
B="adb shell am broadcast -n $P/.ProbeCommandReceiver --es probe_action"
$B register_loopback
adb shell telecom set-phone-account-enabled $P/.LoopbackConnectionService loopback 0   # 👤-equivalent: Settings → Calls → Calling accounts
$B render_caller                                   # TTS clip the fake caller "speaks"
$B set_auto_answer --ei value 5
$B fake_incoming --es scenario message             # or hangup_ringing | silent
adb shell telecom set-phone-account-disabled $P/.LoopbackConnectionService loopback 0  # revert when done
```

| Valid for | Not valid for |
|---|---|
| E1 binding, E2 app-side timing, E4 *policy* (capture silencing during `MODE_IN_CALL`), E5, E6 app side, Tests 4 and 10 logic | E3 (no far end hears the uplink), the carrier's no-answer timer, E9, E10, real downlink acoustics |

The proxy caller's voice is played by the probe through the call audio route, so it stands in for the downlink. That is enough to show whether *any* audio reaches the app. The answer was none: Android delivers zeros.

## 5. Real-world test suite (Tests 1–18)
These run against the **product app** once Phase 4 exists. They apply to spike builds where relevant. Every test uses a G60 with a SIM, the app as default dialer (👤), and Phone B unless noted. **Actual and Pass/Fail are *Pending* for every test** until a SIM and a second phone are available.

| # | Test | Preconditions | Procedure | Expected | Logs |
|---|---|---|---|---|---|
| 1 | Normal unanswered call | Service ON, mode ALL, ring 20 s | Phone B calls and nobody touches the G60 | Rings ~20 s, app answers, greeting, beep, record, voicemail row, notification | logcat, telecom, audio |
| 2 | User answers | As in 1 | Answer at 5 s | Normal call. Session cancelled. No voicemail | logcat, telecom |
| 3 | User rejects | As in 1 | Decline at 5 s | Call rejected normally (busy/forwarding per carrier). No voicemail | logcat, telecom |
| 4 | Caller hangs up before takeover | As in 1 | Phone B ends at 8 s | Missed call logged. No session. No voicemail | logcat, telecom |
| 5 | Carrier no-reply forwarding | 👤 User *chooses* to set `**61*<PhoneC>*10#` (or via Google Phone settings); revert with `##61#` | Phone B calls | Network forwards at ~10 s, before the app's 20 s timer. The app logs a *call ended – forwarded* and does not crash. The setup screen warns about the conflict | logcat, `*#61#` reply |
| 6 | Unknown caller | Mode UNKNOWN_ONLY. Phone B not in contacts | Call | Intercepted | logcat |
| 7 | Saved contact | Mode UNKNOWN_ONLY. Phone B saved as a contact | Call | Rings normally, never intercepted | logcat |
| 8 | Selected contact rule | Mode SELECTED with Phone B selected; also a NEVER rule on another contact | Calls from Phone B, then from a NEVER contact | B intercepted. NEVER contact not intercepted | logcat (RuleEngine decisions) |
| 9 | Caller leaves a message | As in 1 | Phone B speaks for 20 s, then hangs up | Recording ~20 s, `RECORDED → ENCODED`, playable | logcat, file listing (`run-as`) |
| 10 | Caller hangs up silently | As in 1 | Phone B hangs up right after the beep | Entry with `EMPTY` status, "No message left". No transcription | logcat |
| 11 | Custom greeting | Recorded greeting active | Call | Phone B hears the recorded greeting | logcat |
| 12 | TTS greeting | TTS greeting (offline voice) active. Wi-Fi off | Call | Phone B hears the TTS greeting | logcat |
| 13 | Recording disabled | allowMessage = OFF | Call | Closing greeting, then disconnect. No recording. No voicemail row (a "call handled" log only) | logcat |
| 14 | Transcript created | Model installed | After Test 9 | Status `QUEUED → DONE` within 2× audio length. Text shown | logcat (WorkManager) |
| 15 | Notification preview | Preview ON; lock-screen preview OFF | After Test 9, view on the lock screen and then unlocked | Lock screen shows "New voicemail" only. Unlocked shows name and snippet. Tap opens the detail | `dumpsys notification --noredact` |
| 16 | Dual-SIM | 👤 SIMs in both slots; app enabled for SIM 1 only | Call SIM 1, then SIM 2 | SIM 1 intercepted. SIM 2 rings normally. SIM badge correct | logcat, telecom (PhoneAccount) |
| 17 | Process killed / reboot | `adb shell am force-stop` (not while in a call), or reboot the device | Call after each | Telecom rebinds and the session works. After a reboot the first call works without opening the app | logcat |
| 18 | Battery / background restrictions | 👤 Set the app to "Restricted" battery (Android 12: App info → Battery → Restricted); also test with Battery Saver on | Call | Record the behaviour. Expected: InCallService is still bound (system binding), and capture works or fails visibly. Document any OEM-specific breakage | logcat (ActivityManager), `dumpsys deviceidle` |

**Regression set** (run on every telephony PR): Tests 1, 2, 3, 4, 9, 13 and 17.

## 6. Results log
| Date | Build / commit | SIM / operator | Tests run | Summary | Link to logs (private) |
|---|---|---|---|---|---|
| 2026-11-22 | n/a | none (both slots ABSENT) | Baseline inspection only | See §1 | device-logs/ |
| 2026-11-22 | probe 0.3 (`spike/shell-identity`) | none (loopback) | E11/E12 permission and policy layers, E12b | **Shell identity is allowed to play into TX and capture downlink. Audio flow needs a real call** | [shell-identity log](device-logs/shell-identity-run-2026-11-22.md) |
| 2026-11-22 | probe 0.2 (`spike/loopback-calls`) | none needed (loopback calling account, network-free) | E1, E2 (app), E4, E5, E6 (app), Tests 4 and 10 (logic) | **E4 FAIL: all in-call capture silenced by the framework.** E1 and E5 pass | [loopback log](device-logs/loopback-run-2026-11-22.md) |
| 2026-11-22 | probe 0.1 (`spike/telecom-probe`) | BSNL SIM in slot 2: `LOADED`, but CS/PS registration **DENIED**, "Emergency calls only". Owner reports the prepaid plan is not recharged | E7 (provisional pass), E4 no-call baseline, E1 prep | Calls cannot be received yet. See [device-logs/probe-run-2026-11-22-nosim.md](device-logs/probe-run-2026-11-22-nosim.md) | device-logs/ |
