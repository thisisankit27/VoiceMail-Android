# Technical Feasibility Report

> **Status:** Phase 0. **E4 FAILED for a normal app** (loopback, 2026-11-22). **New path:** for a *personal, non-Play* build, the owner approved running the audio code as the **ADB shell identity** (no root, no custom ROM). On loopback calls that identity passes every permission and policy layer for playing into the call and recording from it (E11/E12). **Real call (Airtel, 2026-11-22): audio flows both ways.** The greeting was injected into the call (E11 ✅) and the caller was recorded from the downlink (E12 ✅ provisional), with no speakerphone and no room audio sent ([real-call log](device-logs/realcall-shell-run-2026-11-22.md)). The on-device call experiments (E2–E6, E9–E10) are **pending**, because no SIM is installed in the test device yet.
>
> **Last updated:** 2026-11-22
>
> **Primary question:** Can a normal, Play-distributable, third-party Android app give users a *genuine* cellular voicemail? That means answering an unanswered call, playing a greeting the caller hears, and recording what the caller says.

---

## 0. Evidence labels

Every important claim in this repository is tagged with one of these labels.

| Label | Meaning |
|---|---|
| **[VERIFIED-DEVICE]** | Observed directly on the Moto G60 via ADB. The raw output is in [device-logs](device-logs/moto-g60-baseline-2026-11-22.md). |
| **[VERIFIED-DOC]** | Stated in official Android / Google documentation. A link and the quoted text are given. |
| **[DOC-ASSUMPTION]** | Based on official documentation or the API reference as recalled, but not re-quoted in this investigation. Must be re-checked before code depends on it. |
| **[UNVERIFIED]** | A plausible hypothesis. It needs an experiment, and the experiment ID is given. |
| **[DEVICE-SPECIFIC]** | Behaviour that may differ by OEM, model or firmware. |
| **[CARRIER-SPECIFIC]** | Behaviour that depends on the mobile operator (Jio, Airtel, Vi, BSNL…). |

---

## 1. Executive verdict

> **Device finding, 2026-11-22 [VERIFIED-DEVICE, loopback]:** Even while holding `ROLE_DIALER`, with `RECORD_AUDIO`, a `phoneCall|microphone` foreground service and a visible in-call screen, the app receives **only digital silence** from every capture source (`MIC`, `VOICE_RECOGNITION`, `VOICE_COMMUNICATION`, `CAMCORDER`, `UNPROCESSED`) once the call is active. Android reports this explicitly: `AudioRecordingConfiguration.isClientSilenced() == true`. A recording that was already running is silenced the moment the call becomes active. **No caller audio can be recorded on-device by this app. Recording voicemail on-device is therefore not possible on the G60.** The test used network-free calls that Telecom puts into `MODE_IN_CALL`, which is what the capture policy keys on. A single real-call confirmation is still advised. Evidence: [loopback log](device-logs/loopback-run-2026-11-22.md).

**Android's public APIs do not support a genuine, reliable on-device cellular voicemail for a third-party app.**

There are two hard blockers, both **[VERIFIED-DOC]**:

1. **Recording the caller.** The audio sources that carry the call (`VOICE_CALL`, `VOICE_DOWNLINK`, `VOICE_UPLINK`) require `CAPTURE_AUDIO_OUTPUT`. In the documentation's words, that permission is *"reserved for use by system components and is not available to third-party applications."* ([MediaRecorder.AudioSource](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource))
2. **Playing a greeting into the call.** No public API lets an app inject audio into a cellular call's uplink. `InCallService` and `Call` expose answer, disconnect, mute, routing and DTMF only. ([InCallService](https://developer.android.com/reference/android/telecom/InCallService), [Call](https://developer.android.com/reference/android/telecom/Call))

On the Moto G60, the only app holding `CAPTURE_AUDIO_OUTPUT` is the **pre-installed system Google Phone app** **[VERIFIED-DEVICE]**. That is consistent with the documentation.

**What *is* supported** for an app that holds the **default dialer role** (`ROLE_DIALER`): seeing the call ring, running its own "unanswered" timer, answering (`Call.answer`), routing audio to the speaker, and disconnecting **[VERIFIED-DOC]**.

**The closest on-device design** is an **"acoustic answering machine"**:

1. The app becomes the default dialer.
2. After N seconds of ringing it answers and turns on the speakerphone.
3. It plays the greeting out of the loudspeaker so the phone's own microphone picks it up and sends it to the caller.
4. It attempts to record the caller's voice as it comes out of the loudspeaker, using the microphone.

Whether steps 3 and 4 work at all depends on undocumented device behaviour: echo cancellation, noise suppression, and the concurrent-capture policy. That makes it **[UNVERIFIED] / [DEVICE-SPECIFIC]**. Experiments **E3** and **E4** on the Moto G60 decide it.

**The decision gate** (formal version in [ADR-0001](decisions/ADR-0001-telephony-architecture.md)):

| E3: caller hears greeting | E4: caller's voice captured | Outcome |
|---|---|---|
| ✅ | ✅ | **GO.** Build the on-device answering machine as an *experimental, device-verified* feature with an in-app compatibility self-test. |
| ✅ | ❌ | **PARTIAL.** Only a "greeting-only auto-responder" is possible, which is not voicemail. The project owner decides between stopping and a greeting-only product. Cloud forwarding is excluded ("nothing paid", 2026-11-22). |
| ❌ | any | **NO-GO** for on-device voicemail. |

> The project owner chose an **on-device-only** architecture with no backend or paid telephony (decision dated 2026-11-22). The only architecture that is *known* to deliver real voicemail is **carrier forwarding to a cloud telephony number**, Option 2b below. It is documented here for completeness only. On 2026-11-22 the owner confirmed that **nothing paid** may be used, even if E4 fails.

---

## 2. Capability matrix

The eight capabilities are independent. Supporting one does not imply supporting another.

### 2.1 Detecting an incoming call
| | |
|---|---|
| **Official API** | `CallScreeningService.onScreenCall()` (API 24+). `InCallService.onCallAdded()` with `Call.STATE_RINGING` (API 23+). |
| **Role / permission** | `ROLE_CALL_SCREENING` for screening; `ROLE_DIALER` for InCallService. Both use the `BIND_SCREENING_SERVICE` or `BIND_INCALL_SERVICE` binding permission, which only the system can hold. |
| **Restrictions** | A screening app has to respond within **5 s**, and the phone does not ring until it does **[VERIFIED-DOC]** ([CallScreeningService](https://developer.android.com/reference/android/telecom/CallScreeningService)). If the screening app is *not* the dialer, it is only consulted for numbers that aren't in contacts, unless it holds `READ_CONTACTS` **[DOC-ASSUMPTION]**. `READ_PHONE_STATE` broadcasts no longer carry the number. Per the docs, `EXTRA_INCOMING_NUMBER` is deprecated in API 29 and apps should use CallScreeningService **[VERIFIED-DOC]**. |
| **Feasibility** | ✅ Fully supported. |
| **G60 validation** | E1 (role binding) and Test 1. |

### 2.2 Detecting that a call is unanswered
| | |
|---|---|
| **Official API** | No "unanswered" event exists. A default dialer sees `STATE_RINGING` and runs its own timer. A screening service only sees the call *before* it rings. |
| **Role** | `ROLE_DIALER`. |
| **Restrictions** | The **network** has its own alerting timeout. With carrier "no-reply" forwarding active, the network forwards the call after its own `T` seconds (`**61*number*T#`, where T is typically 5–30 s) **[CARRIER-SPECIFIC]**. The app's timer must fire *before* both the network's no-answer release and any forwarding. The timeout for each Indian operator is **[UNVERIFIED]**, measured in E2. |
| **Feasibility** | ⚠️ Feasible as default dialer, but the timing is carrier-dependent. |
| **G60 validation** | E2 measures how long the network keeps the call ringing, per SIM. |

### 2.3 Answering a call automatically
| | |
|---|---|
| **Official API** | `Call.answer(VideoProfile.STATE_AUDIO_ONLY)`: "Instructs this STATE_RINGING Call to answer" **[VERIFIED-DOC]**. Available to the bound InCallService. |
| **Not used** | `TelecomManager.acceptRingingCall()` with `ANSWER_PHONE_CALLS` was deprecated in API 29 **[DOC-ASSUMPTION]**. The project rules forbid deprecated paths. |
| **Role** | `ROLE_DIALER`. |
| **Restrictions** | The app has to **be the full dialer**: it must handle `ACTION_DIAL` and "fully implement the InCallService API and provide both an incoming call UI, as well as an ongoing call UI" **[VERIFIED-DOC]** ([dialer-app](https://developer.android.com/develop/connectivity/telecom/dialer-app)). Emergency calls always go to the pre-installed dialer **[VERIFIED-DOC]**. |
| **Side effect** | Once answered, the call is *connected*, so **the caller is billed** for it, just as with carrier voicemail. On unlimited Indian plans this is usually moot **[CARRIER-SPECIFIC]**. |
| **Feasibility** | ✅ Supported, but only by replacing the user's dialer. |
| **G60 validation** | E2 and Test 1. |

### 2.4 Accessing the caller's incoming audio
| | |
|---|---|
| **Official API** | `AudioSource.VOICE_DOWNLINK` / `VOICE_CALL` require `CAPTURE_AUDIO_OUTPUT`, which is "not available to third-party applications" **[VERIFIED-DOC]**. `AudioPlaybackCaptureConfiguration` captures **only** `USAGE_MEDIA`, `USAGE_GAME` and `USAGE_UNKNOWN`; "All other usages CAN NOT be captured" **[VERIFIED-DOC]** ([link](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration)). |
| **Concurrent capture policy** | While a call is active, the only exceptions listed are an **accessibility service** and a "**privileged (pre-installed) app** with permission CAPTURE_AUDIO_OUTPUT" **[VERIFIED-DOC]** ([sharing-audio-input](https://developer.android.com/media/platform/sharing-audio-input)). An ordinary app, including a non-system default dialer, is **not** listed. The page does not say whether such an app receives silence or an error. |
| **Accessibility route** | Forbidden by project rules. Google Play has also banned it for call recording since 2022-05-11: "the Accessibility API is not designed and cannot be requested for remote call audio recording" **[VERIFIED-DOC, secondary report]**. |
| **Acoustic route** | Put the call on speakerphone and record `MIC` / `VOICE_RECOGNITION` / `UNPROCESSED`. **Tested: silenced.** All five sources return 100% zeros with `isClientSilenced=true` during an active call, including a capture started before the call **[VERIFIED-DEVICE, E4 loopback]**. |
| **Feasibility** | ❌ Not supported. The acoustic path is a device-dependent workaround at best. |

### 2.5 Playing an audio greeting into the cellular call
| | |
|---|---|
| **Official API** | None for third-party apps. `Call.playDtmfTone()` only sends DTMF digits **[VERIFIED-DOC]**. The `Call` reference also lists `STATE_AUDIO_PROCESSING` ("audio from the call is being intercepted by an app") and audio-processing use cases. Entering that state (`enterBackgroundAudioProcessing`) has historically been a **system API** for privileged call-screening implementations **[DOC-ASSUMPTION]**. It cannot be relied on, and the G60 runs API 31 in any case. |
| **Acoustic route** | Speakerphone on, then play the greeting through the loudspeaker so the phone's mic carries it to the caller. The call's echo canceller (AEC) may treat the greeting as echo and remove it, and noise suppression may degrade it **[UNVERIFIED] [DEVICE-SPECIFIC]**. **E3.** |
| **Feasibility** | ❌ No API. The acoustic route is unverified. |

### 2.6 Recording the caller's voice
This is the same problem as 2.4. Microphone permission (`RECORD_AUDIO`) is **not** permission to record cellular call audio. The two must not be confused. Starting a `microphone` foreground service from the background is also restricted: "you cannot create a `microphone` foreground service while your app is in the background" **[VERIFIED-DOC]** ([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types)). Whether the dialer's in-call UI makes the app "foreground" while the phone is locked and unattended is **[UNVERIFIED]**, tested in E5.

**Verdict:** ❌ Not supported via any documented API, and **verified impossible on the G60**: the framework silences the capture (E4).

### 2.7 Disconnecting the call
| | |
|---|---|
| **Official API** | `Call.disconnect()` **[VERIFIED-DOC]** as default dialer. A screening app can reject *before* ringing with `CallResponse.setRejectCall(true)` **[DOC-ASSUMPTION]**. |
| **Feasibility** | ✅ Supported. |

### 2.8 Carrier-based call forwarding
| | |
|---|---|
| **Mechanism** | GSM supplementary-service MMI codes the **user** dials: `**61*<num>*<T>#` (no reply), `**67*<num>#` (busy), `**62*<num>#` (unreachable), `*#61#` (status), `##61#` (cancel) **[CARRIER-SPECIFIC]** (secondary sources: [TelecomTalk](https://telecomtalk.info/how-to-start-and-stop-call-forwarding/493353/amp), [Jio FAQ](https://www.jio.com/help/faq/mobile/services/hd-voice/what-is-call-forwarding-service)). Reports say Jio has disabled some forwarding codes on government instruction and directs users to phone settings instead **[UNVERIFIED]**. |
| **Programmatic API** | `TelephonyManager.setCallForwarding()` / `getCallForwarding()` (API 33) are system APIs needing `MODIFY_PHONE_STATE` / privileged permissions **[DOC-ASSUMPTION]**. A third-party app can at most open the dialer pre-filled with the MMI code (`ACTION_DIAL`), and the user taps call. |
| **Target** | Forwarding needs a *destination number*. Under the on-device-only decision there is no number the app controls. Destinations could be (a) the operator's voicemail, if one exists **[UNVERIFIED]**, or (b) a cloud telephony number, which is Option 2b and out of scope. |
| **Feasibility** | ⚠️ The user can enable it. The app cannot. It is useless without a destination. |
| **G60 validation** | E9 (`*#61#` status and whether a carrier voicemail exists, per SIM). Test 5. |

---

## 3. Architectural options in detail

### Option 1: Android Telecom integration (default dialer, "acoustic answering machine")

**Mechanics:** The app holds `ROLE_DIALER` and implements `InCallService` plus a full incoming and in-call UI. On `STATE_RINGING` the rule engine decides whether to intercept. If it does, a timer runs, then `call.answer()`, then the audio route is switched to the speaker (`InCallService.setAudioRoute(ROUTE_SPEAKER)` / `requestCallEndpointChange` on API 34+). The greeting plays through the speaker, a beep sounds, and `AudioRecord` captures from the mic. The session stops when the remote party disconnects or the max duration is reached, then `call.disconnect()`.

| Aspect | Finding |
|---|---|
| **What works** | Detection, rule evaluation, ring timer, answer, speaker routing, disconnect, call-log integration, contact lookup **[VERIFIED-DOC]**. |
| **What does not work (by API)** | Injecting the greeting into the call; capturing call audio **[VERIFIED-DOC]**. |
| **What *might* work** | Acoustic coupling for both, if AEC/NS and the capture policy allow it on a given device **[UNVERIFIED] [DEVICE-SPECIFIC]** (E3, E4). |
| **Permissions** | Role `ROLE_DIALER`. Runtime: `RECORD_AUDIO`, `READ_CONTACTS`, `READ_CALL_LOG` (allowed for the default dialer), `READ_PHONE_STATE`, `CALL_PHONE`, `POST_NOTIFICATIONS` (API 33+). Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL` / `_MICROPHONE` (API 34+). |
| **User privileges** | The user must make this app the **default phone app**, replacing Google Phone. They lose Google Phone's spam protection, Call Screen (where offered) and native call recording. The device declares `com.google.android.apps.dialer.call_recording_audio` **[VERIFIED-DEVICE]**. |
| **Android versions** | InCallService API 23+, RoleManager API 29+, concurrent-capture policy API 29+. FGS type declaration is mandatory from API 34 **[VERIFIED-DOC]**. Planned minSdk is 29. |
| **OEM** | Audio HAL, AEC and NS behaviour differ by OEM, chipset and firmware **[DEVICE-SPECIFIC]**. MIUI, ColorOS, OneUI and others add their own background restrictions. |
| **Carrier** | Network no-answer timer and any active no-reply forwarding race the app's timer **[CARRIER-SPECIFIC]**. |
| **Play distribution** | Default dialer apps are allowed. They must pass the Phone/SMS permissions declaration (default phone handler is a permitted use) **[DOC-ASSUMPTION]**. An app marketed as "recording calls" through workarounds risks policy review. The Accessibility API is prohibited for this purpose. |
| **Audio limitations** | Speakerphone plays the caller's voice aloud in the room, which is a privacy problem. A phone in a pocket or bag muffles audio. AEC may cancel the greeting. Capture may be silenced. Quality will be well below carrier voicemail. |
| **Complexity** | **High.** It means building and maintaining a complete dialer (dial pad, incoming UI, in-call UI, multi-call, hold, conference passthrough, Bluetooth/wired routing, call log) before any voicemail feature exists. |

### Option 2a: Carrier voicemail and conditional forwarding (no backend)
| Aspect | Finding |
|---|---|
| **What works** | The user can set no-reply, busy and unreachable forwarding with MMI codes **[CARRIER-SPECIFIC]**. If the operator provides a voicemail box, the carrier plays the greeting and records the caller. That is a *genuine* voicemail, but the carrier owns it. |
| **What does not work** | Indian operators' availability of consumer voicemail is **[UNVERIFIED]**, and anecdotally rare. Visual Voicemail (`VisualVoicemailService`) "will only bind to the default dialer" and needs carrier OMTP VVM support **[VERIFIED-DOC]**. Indian operators' support for it is **[UNVERIFIED]** and believed absent. Without VVM the app cannot access the carrier's recordings. |
| **Permissions** | None for the app. The user dials the codes. |
| **Play** | No issues. |
| **Complexity** | Low, but the app adds little value without VVM. |
| **Verdict** | Only useful if E9 finds a carrier voicemail and VVM on the user's operator. |

### Option 2b: Carrier forwarding to a cloud telephony number (OUT OF SCOPE by owner decision)
The user forwards no-reply, busy and unreachable calls to a virtual number from an Indian cloud-telephony provider (Exotel / Knowlarity / Plivo / Twilio-class, licensed via UL-VNO partners). The provider plays the greeting, records the message, optionally transcribes it, and pushes it to the app.

- **Works:** Genuine greeting and recording, independent of the device's audio stack, with no dialer replacement. This is **the only option known to produce real voicemail.**
- **Costs:** Per-minute fees, DoT KYC for the number, a backend that stores sensitive recordings, DPDP obligations, and caller-ID passthrough on forwarded calls **[CARRIER-SPECIFIC]** (needed to know who called).
- **Contact rules** must be enforced server-side. On-device, "always to voicemail" becomes a reject from `CallScreeningService`, which triggers busy-forwarding **[UNVERIFIED]**.

**Rejected.** The owner ruled out anything paid, including in the case where E4 fails (2026-11-22).

### Option 4: Default dialer + shell-identity audio helper (PERSONAL BUILD ONLY; owner decision 2026-11-22)
The owner decided the app is for personal use with no Play Store, and asked for system-level powers **without root or a custom ROM**. Stock Android has one such privilege level: the **ADB shell user** (uid 2000), the identity `adb shell`, scrcpy and Shizuku run code under.

| Aspect | Finding |
|---|---|
| **Mechanism** | The app keeps `ROLE_DIALER` for ringing, the timer, `answer()`, `disconnect()` and the UI. A helper process running as uid 2000 does the audio: an `AudioTrack` routed to the **Telephony TX** device (the HAL's `incall_music_uplink`) plays the greeting *into* the call, and an `AudioRecord` on `VOICE_DOWNLINK` records the caller *from* the call. No speakerphone. |
| **Permissions** | uid 2000 holds `CAPTURE_AUDIO_OUTPUT`, `MODIFY_PHONE_STATE` and `CONTROL_INCALL_EXPERIENCE` on the G60 **[VERIFIED-DEVICE]**. `pm grant` of these to the app fails **[VERIFIED-DEVICE]**. |
| **What is verified (loopback)** | Playback: `MODIFY_PHONE_STATE … uid=2000 => granted`, track routed to type 18 (Telephony). Capture: `VOICE_DOWNLINK` and `VOICE_CALL` initialise and record with `isClientSilenced=false`. Shell MIC capture is not silenced during the call **[VERIFIED-DEVICE, loopback]**. |
| **Target device: Moto G05** (owner's primary phone; Android 15, MediaTek) | Read-only check: the shell holds `CAPTURE_AUDIO_OUTPUT`, `MODIFY_PHONE_STATE` and `WRITE_SECURE_SETTINGS`. The HAL has `incall_music_uplink` → Telephony Tx and Voice Call In ← Telephony Rx. **Structurally supported**; a real call on the G05 is still pending **[VERIFIED-DEVICE, static]** ([log](device-logs/moto-g05-check-2026-11-22.md)) |
| **Real call (Airtel)** | **Audio flows.** No HAL error. The greeting appears in the uplink capture (xcorr 1.01 vs 0.48 control). The downlink holds the caller's speech. With `setMuted(true)` the uplink is exact zero outside the greeting **[VERIFIED-DEVICE, real call]**. Still pending: the caller's subjective confirmation, other operators (Jio), and the no-PC launcher. |
| **Running without a PC** | Android 11+ *Wireless debugging* lets an on-device launcher (Shizuku, Apache-2.0) start a uid-2000 process. It must be **restarted after every reboot**, and starting it needs Wi-Fi **[DOC-ASSUMPTION/UNVERIFIED on G60]**. Until then, the helper runs over USB `adb`. |
| **Costs** | Not Play-distributable. Uses one hidden API (device lookup) and the shell identity, which is outside the normal app model. Anything holding the Shizuku grant has shell power. An OEM update could change what uid 2000 holds. It is device-specific (Qualcomm in-call music path). |
| **Complexity** | Medium. Dialer (as Option 1), plus an AIDL-connected shell-side audio service, plus a re-arm flow after reboot. |

Evidence: [shell-identity log](device-logs/shell-identity-run-2026-11-22.md).

### Option 3: Other Android-supported architectures
| Candidate | Why it doesn't solve the problem |
|---|---|
| `CallScreeningService` alone | Can allow, reject, silence or skip logging only. It cannot answer, play audio or record **[VERIFIED-DOC]**. Still useful as a **lighter role for contact rules**: rejecting a call so carrier busy-forwarding applies. |
| Self-managed `ConnectionService` / Core-Telecom `CallsManager` | For the app's *own* VoIP calls only. It cannot take over SIM calls **[DOC-ASSUMPTION]**. |
| `CallRedirectionService` (`ROLE_CALL_REDIRECTION`) | Outgoing calls only **[DOC-ASSUMPTION]**. |
| `VisualVoicemailService` | Client for carrier VVM. Requires carrier support (see 2a). |
| Accessibility service | Prohibited by project rules and Play policy. |
| Root / system / privileged app | Prohibited by project rules. It would be the only way to get `CAPTURE_AUDIO_OUTPUT`, as Google Phone has on this device. |
| Second "answering" handset | The same audio restrictions apply on that handset. |

---

## 4. Supporting features: feasibility

| Feature | Verdict | Notes |
|---|---|---|
| TTS greeting generation | ✅ | `TextToSpeech.synthesizeToFile()` renders a WAV. Voices come from `getVoices()`, with filtering on `isNetworkConnectionRequired()` and the `notInstalled` feature. On the G60, 22 offline voices are installed, including 4 en-IN and 4 hi-IN. A greeting rendered to 24 kHz mono WAV in 1.2 s **[VERIFIED-DEVICE, E7; Wi-Fi-off rerun pending]**. |
| Recording a greeting in-app | ✅ | `AudioRecord`/`MediaRecorder` with `RECORD_AUDIO`, in the foreground, while not in a call. |
| Importing a greeting file | ✅ | Storage Access Framework (`OpenDocument`), decoded with `MediaExtractor`/`MediaCodec` and normalised. |
| Offline transcription | ⚠️ | The platform `SpeechRecognizer` can only take audio *files* via `EXTRA_AUDIO_SOURCE` on API 33+ **[DOC-ASSUMPTION]**. The G60 is API 31 **[VERIFIED-DEVICE]**, so an embedded engine is needed: whisper.cpp or Vosk, benchmarked in E8. |
| Notifications | ✅ | Notification channels, `VISIBILITY_PRIVATE` with `setPublicVersion`. `POST_NOTIFICATIONS` is only a runtime permission on API 33+, and the G60 is API 31. |
| Contact matching | ✅ | `PhoneLookup` with `READ_CONTACTS`, plus libphonenumber normalisation. |
| Dual-SIM awareness | ✅ | `Call.Details.getAccountHandle()` maps to the subscription. The G60 is DSDS **[VERIFIED-DEVICE]**. |

---

## 5. Experiments required (summary)

Full procedures are in [DEVICE_TESTING.md](DEVICE_TESTING.md).

| ID | Question | Needs SIM? | Status |
|---|---|---|---|
| E1 | Can the spike obtain `ROLE_DIALER` and be bound as InCallService on the G60? | No | **Pass** |
| E2 | Ring detection, app timer, `answer()`. What is the network's no-answer timeout per operator? | Yes + 2nd phone | Partial: app timer and answer verified by loopback; network timeout pending |
| E3 | Does the caller hear a greeting played through the speaker during the call? With which `AudioAttributes` usage? | Yes + 2nd phone | Pending |
| E4 | Does `AudioRecord` capture the caller's voice (speaker → mic) during the call, per audio source? Silence or signal? | Yes + 2nd phone | **FAIL**: every source silenced during the call (loopback) |
| E5 | Can capture start while the device is locked and unattended, with the app in background before the call? | Yes + 2nd phone | **Pass** (call flow while locked; capture still silenced) |
| E6 | Disconnect, call-log entry and caller-side experience (billing, what they hear) | Yes + 2nd phone | Partial: app-side disconnect verified; caller view pending |
| E7 | Which Google TTS voices work offline? Quality of `synthesizeToFile` | No | **Pass (provisional)**: 4 en-IN and 4 hi-IN offline voices installed. Greeting rendered in 1.2 s. Wi-Fi-off rerun pending |
| E8 | STT benchmark: whisper.cpp vs Vosk on the G60 (real-time factor, word error rate on Indian English and Hindi, size) | No | Pending |
| E9 | Carrier forwarding status, operator voicemail and VVM, per SIM | Yes | Pending |
| E10 | What the caller hears when `CallScreeningService` rejects the call (busy tone or forwarding) | Yes + 2nd phone | Pending |

---

## 6. Sources

**Official**
- Telecom overview: https://developer.android.com/develop/connectivity/telecom
- Default dialer: https://developer.android.com/develop/connectivity/telecom/dialer-app
- InCallService: https://developer.android.com/reference/android/telecom/InCallService
- Call: https://developer.android.com/reference/android/telecom/Call
- CallScreeningService: https://developer.android.com/reference/android/telecom/CallScreeningService
- MediaRecorder.AudioSource: https://developer.android.com/reference/android/media/MediaRecorder.AudioSource
- Sharing audio input: https://developer.android.com/media/platform/sharing-audio-input
- AudioPlaybackCaptureConfiguration: https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration
- VisualVoicemailService: https://developer.android.com/reference/android/telephony/VisualVoicemailService
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Manifest.permission: https://developer.android.com/reference/android/Manifest.permission

**Secondary (treated as lower confidence)**
- Play's Accessibility/call-recording change (2022): https://9to5google.com/2022/04/21/google-will-block-all-third-party-call-recording-apps-on-play-store-from-may-11/
- Indian forwarding codes: https://telecomtalk.info/how-to-start-and-stop-call-forwarding/493353/amp, https://www.91mobiles.com/hub/call-forwarding-codes-jio-airtel-vi-bsnl-how-to-activate/
- Jio forwarding FAQ: https://www.jio.com/help/faq/mobile/services/hd-voice/what-is-call-forwarding-service
