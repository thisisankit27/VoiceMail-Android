# Product app 0.1: helper auto-start and voicemail session on the Moto G60 (2026-11-22/23)

Build: `app` 0.1.0 (debug), branch `feat/app-helper-autostart`. The device is the Moto G60 (Android 12, API 31). The SIM is in service, but no real call was made in this run: everything below used **loopback calls**, the app's network-free test calling account. Loopback calls have no far end, so recorded messages are silent by design (see the [shell-identity log](shell-identity-run-2026-11-22.md)). Real-call audio was already verified with the probe ([real-call log](realcall-shell-run-2026-11-22.md)).

## 1. The app's own ADB key
| Check | Result |
|---|---|
| The app connects to `127.0.0.1:5555` (legacy transport, opened with `adb tcpip 5555`) and sends CNXN, then a signed AUTH token, then its public key | adbd **parses the key** and the phone shows *"Allow USB debugging? The computer's RSA key fingerprint is …"* **[VERIFIED-DEVICE]** |
| Accepting the prompt | 👤 The owner ticked *Always allow* and tapped *Allow* (2026-11-22 23:58). The app then connected over ADB by itself and launched the helper. The port was closed again with `adb usb` **[VERIFIED-DEVICE]** |
| Token signature format (PKCS#1 v1.5 over a SHA-1 DigestInfo of the token) | Unit-tested: decrypting the signature yields `DigestInfo(SHA-1) ‖ token` **[VERIFIED-TEST]** |
| TLS client certificate (hand-built X.509 v1, SHA256withRSA) | Unit-tested: it parses, verifies and carries the app's key **[VERIFIED-TEST]** |

## 2. Switching Wireless debugging on from the app
| Check | Result |
|---|---|
| The app writes `adb_wifi_enabled=1` (holding `WRITE_SECURE_SETTINGS`, granted by the helper launch command) | Accepted. The system shows *"Allow wireless debugging on this network?"* with *Always allow on this network*, because this Wi-Fi network is not yet trusted **[VERIFIED-DEVICE]** |
| No port appears within 20 s (the prompt was not answered) | The app reports `WIRELESS_DEBUGGING_UNAVAILABLE` and switches the setting back to 0 **[VERIFIED-DEVICE]** |
| mDNS discovery, then TLS with the key authorised over the legacy transport | **Works.** A key accepted once through the "Allow USB debugging?" prompt is also accepted by Wireless debugging (TLS 1.3, no pairing code) **[VERIFIED-DEVICE]** |
| Full no-PC start (helper stopped, Wireless debugging off) | `adb_wifi_enabled=1` → port found over mDNS in 0.9 s → TLS → launch → helper running → `adb_wifi_enabled=0`. **2.4 s in total.** The helper keeps running with Wireless debugging off **[VERIFIED-DEVICE]** |

## 3. The helper as a daemon
Launched with the app's own launch command (`scripts/helper-usb.sh` runs the identical command over USB):
```text
shell  <pid>  1  vm-audio-helper          # uid 2000, parent = init: detached from adb
VoiceMail: result: Running(info=HelperInfo(version=3, uid=2000, sdk=31, apkPath=/data/app/…/base.apk))
VoiceMail: id=9 type=18 OUT 'moto g(60)'   # Telephony TX visible to the helper
VoiceMail: id=19 type=18 IN  'moto g(60)'  # Telephony RX
```
- The helper refuses to start without its secret **[VERIFIED-DEVICE]**.
- On reinstall, `MY_PACKAGE_REPLACED` makes the app detect the stale helper (other APK), shut it down within 1 s, and try a fresh start over Wireless debugging. That start then waits on the network-trust prompt **[VERIFIED-DEVICE]**. A helper whose APK disappears also exits by itself within 30 s (APK watchdog; code path, not observed separately).

## 4. Reboot and Wi-Fi loss
| Check | Result |
|---|---|
| **Reboot** (`adb reboot`, owner unlocked once) | `BOOT_COMPLETED` → WorkManager → Wireless debugging on → port in 11 s (Wi-Fi still settling) → TLS → helper running as uid 2000 → Wireless debugging off. **Helper up 14 s after the boot broadcast, no PC** **[VERIFIED-DEVICE]** |
| A call after the reboot | A loopback call ran end to end on the boot-started helper (greeting `routed=18`, recording) **[VERIFIED-DEVICE]** |
| **Wi-Fi off** after the helper started (`svc wifi disable`) | The helper keeps running (parent init) and answers the app. Wi-Fi is only needed to *start* it **[VERIFIED-DEVICE]** |

| **App update** (`adb install -r`) | The app detects the stale helper and stops it. The first mDNS search after an update finds **no port** (reproduced 2 of 2). Restarting Wireless debugging once makes adbd announce it, and the helper is up **23 s** after install. The launcher now does that retry automatically **[VERIFIED-DEVICE]** |

## 5. Real call through the product app (2026-11-23, Airtel, owner calling from a second phone)
Started on the helper the app had launched itself after the reboot (no PC involved):
```text
session armed: answering in 10s
session: answering after 10036 ms
VmHelper: played=7325ms of 7325ms … routed=18        # greeting into Telephony TX
VmHelper: played=450ms of 450ms … routed=18          # beep
VmHelper: capture started source=3 rate=16000        # VOICE_DOWNLINK
call -> DISCONNECTED                                 # caller hung up
VmHelper: captured 10100ms … silenced=false
session: recording stopped (caller hung up) duration=10100ms peak=-29.5 dBFS empty=false
```
- **Test 1 and Test 9 pass on a real call.** The message holds real speech (−29.5 dBFS loudest, against −96 on silent loopback calls).
- The new-voicemail notification was posted, and the owner opened the message from it.
- During the greeting the owner used **Listen in** (speaker on, then back to earpiece). Neither the greeting nor the recording was affected.
- The audio file is kept on the phone only.

## 6. Start beep, 30 s limit and caller-side copy (2026-11-23, Airtel, two real calls)
**Report from a caller:** after the greeting, the beep was not heard. In the earlier build the beep was a separate 450 ms clip played after the greeting clip had ended. The likely cause: the in-call music path reopens for each clip and loses its first moment, which for a short beep is everything. The greeting is long enough not to notice.

**Changes:**
- Greeting, 350 ms pause and a 500 ms beep (1 kHz, 10 ms fades) now go out as **one clip**.
- Every clip has a 500 ms silent lead-in.
- **Messages are capped at 30 s** (owner choice). The caller then hears two short beeps and the app hangs up after a 1.5 s grace. Silence no longer ends a message early (default off).
- Diagnostics: *Keep what callers hear* records the call uplink, which shows on the voicemail as *What the caller heard*.

**What the caller heard** (uplink copies, timeline from an offline analyser with a 1 kHz Goertzel detector):
```text
0.00–0.85 s  silence (lead-in)
0.85–7.85 s  greeting, −4 to −7 dBFS
8.80–9.30 s  BEEP, 1 kHz, ~500 ms          ← now reaches the caller
then         silence (the G60's mic is muted)
```
- **Call 2** (caller stayed on the line): recording stopped at **exactly 30.0 s** (`max length`), the end beeps were played into TX, and the app hung up. **The caller confirmed hearing both end beeps.** The first uplink copy missed them only because it stopped 70 ms after the helper finished writing; the uplink lags ~0.5–0.9 s. The 1.5 s grace now keeps the line and the copy open long enough.
- **Two in-call captures at once**: on call 1 the message came back as digital silence while an uplink copy was running. The caller had only blown into the mic, and the network does not carry that sound reliably (noise suppression), so this is **not proven** to be a capture conflict. As a precaution, the uplink copy is paused while the message is recorded (the order the probe proved). On call 2 the message recorded sound (−19.9 dBFS) with that order.

## 7. Voicemail session regression (loopback)
`scripts/loopback-regression.sh`, ring time 6 s (23 checks as of 2026-11-23, including the message limit → end beeps → hang-up):

| Scenario | Expected | Result |
|---|---|---|
| T1 Unanswered | Armed → answer at ~6 s → mic muted → greeting into Telephony TX (`routed=18`, 7.56 s) → beep → `VOICE_DOWNLINK` capture → stops when the caller hangs up | **Pass** |
| T2 Owner answers during the countdown | Session cancelled, the app does not answer | **Pass** |
| T3 Owner declines during the countdown | Session cancelled | **Pass** |
| T4 Caller hangs up while ringing | Session cancelled, no answer | **Pass** |
| T13 Messages disabled | Closing greeting, then the app hangs up, no recording | **Pass** |
| Take over during the greeting | Greeting stops at once (`played=2901ms of 7560ms … stopped=true`), mic unmuted, call stays up | **Pass** |
| Send to voicemail now | Answers immediately (< 1 s) | **Pass** |
| Helper not running | The call rings out normally and the owner gets a "Voicemail is paused" notification | **Pass** |

## Still to do
- The same checks on the Moto G05 (Android 15, MediaTek).
- Other operators (Jio), and a call with the phone locked and the screen off.
