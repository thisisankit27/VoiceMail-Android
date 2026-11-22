# Moto G60 baseline inspection (2026-11-22)

This is the raw output of the read-only ADB inspection. The repository is **public**, so the device serial, ICCIDs, phone numbers, IMEI, and cell or location identifiers (LAC/CID) have been **removed on purpose**. No setting on the device was changed.

## Device / build
```text
$ adb devices -l
<serial-redacted>   device usb:3-5 product:hanoip_retailnq model:moto_g_60_ device:hanoip transport_id:1

ro.product.model              = moto g(60)
ro.product.device             = hanoip
ro.product.manufacturer       = motorola
ro.boot.hardware.sku          = XT2135-2
ro.build.version.release      = 12
ro.build.version.sdk          = 31
ro.build.version.security_patch = 2023-07-01
ro.build.fingerprint          = motorola/hanoip_retailnq/hanoip:12/S2RI32.32-20-9-9-2/1591b-5bf47:user/release-keys
ro.vendor.build.fingerprint   = motorola/hanoip_retail/hanoip:11/S2RI32.32-20-9-9-2/1591b:user/release-keys
gsm.version.baseband          = M7150_25.333.01.85R HANOIP_EUAPEMDSDS_CUST
ro.board.platform             = sm6150
ro.product.cpu.abilist        = arm64-v8a,armeabi-v7a,armeabi
ro.carrier                    = retin
MemTotal                      = 5779244 kB
/data                         = 108G total, 90G free
```

## Radio / SIM
```text
persist.radio.multisim.config = dsds
ro.telephony.default_network  = 10,10
gsm.sim.state                 = ABSENT,ABSENT
dumpsys telephony.registry    : Phone Id 0 and 1 → mVoiceRegState OUT_OF_SERVICE; phone 0 camped emergency-only (EMERGENCY service) on an Indian operator cell
dumpsys isub                  : ActiveSubInfoList empty; 4 historical subscriptions (3 Indian operators); defaultSubId=-1
```

## Telecom / roles
```text
$ adb shell dumpsys telecom   (excerpt)
mDefaultDialerCache: User 0: com.google.android.dialer
mRoleManager: DefaultCallRedirectionApp: <none>, DefaultCallScreeningApp: <none>
PhoneAccounts:
  com.android.phone/com.android.services.telephony.TelephonyConnectionService  Capabilities: CallProvider MultiUser PlaceEmerg SimSub  Schemes: tel voicemail
  (+ self-managed Jetpack ConnectionService accounts registered by Gmail/Meet and Duo/Meet)

$ adb shell cmd role get-role-holders android.app.role.DIALER          → com.google.android.dialer
$ adb shell cmd role get-role-holders android.app.role.CALL_SCREENING  → (empty)
$ adb shell dumpsys role  : android.app.role.SMS → com.google.android.apps.messaging; CALL_REDIRECTION → (none)
$ adb shell telecom get-default-dialer → com.google.android.dialer
$ adb shell telecom get-system-dialer  → com.google.android.dialer
```

## Dialer privileges (key evidence)
```text
$ adb shell dumpsys package com.google.android.dialer
versionName=247.0.1016877637  versionCode=20245838 minSdk=30 targetSdk=37
flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA UPDATED_SYSTEM_APP ALLOW_BACKUP KILL_AFTER_RESTORE RESTORE_ANY_VERSION ]
android.permission.CAPTURE_AUDIO_OUTPUT: granted=true
android.permission.CONTROL_INCALL_EXPERIENCE: granted=true
android.permission.MODIFY_PHONE_STATE: granted=true
```

## Features
```text
$ adb shell pm list features  (filtered)
android.hardware.audio.output
android.hardware.microphone
android.hardware.telephony
android.hardware.telephony.cdma
android.hardware.telephony.gsm
android.hardware.telephony.ims
android.software.connectionservice
com.google.android.apps.dialer.call_recording_audio
com.motorola.software.audiofx
```

## Speech / TTS
```text
settings secure tts_default_synth        = null  (system default → Google TTS)
settings secure tts_default_locale       = null
settings secure voice_recognition_service = com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService
com.google.android.tts versionName       = googletts.google-speech-apk_20261026.00_p0.1007071969
Android System Intelligence (com.google.android.as) installed
```

## Audio
```text
$ adb shell dumpsys audio (filtered)
mScoAudioMode: SCO_MODE_VIRTUAL_CALL
ringer mode muted streams = STREAM_SYSTEM, STREAM_RING, STREAM_NOTIFICATION, STREAM_SYSTEM_ENFORCED, STREAM_DTMF  (device was in silent/vibrate at capture time)
```

## Host toolchain
```text
adb: ~/Android/Sdk/platform-tools/adb
SDK platforms: android-35, android-36, android-37.0 ; build-tools 36.0.0, 37.0.0
Java: OpenJDK 25.0.2 (Android Studio JBR)
git 2.53.0, gh 2.103.3
```
