# Probe run, 2026-11-22 (no usable SIM)

- **Build:** `spikes/telecom-probe` 0.1. Installed with `adb install -r -g`.
- **Device:** Moto G60, Android 12.
- **SIM:** BSNL in slot 2, `LOADED`, with registration DENIED (emergency calls only).
- **Network:** Wi-Fi connected.
- **Default dialer:** unchanged (`com.google.android.dialer`).

The log below is verbatim `adb logcat -s TelecomProbe` output with the logcat prefix stripped. The probe redacts phone numbers itself, and this run contains none.

```text
process started
adb action: status
E1 status: defaultDialer=com.google.android.dialer systemDialer=com.google.android.dialer holdsDialer=false holdsScreening=false inCallServiceBound=false
config: autoAnswer=0s speaker=true usage=VOICE_COMMUNICATION source=MIC capture=30s screeningReject=false
adb action: tts_voices
adb action: mic_baseline_all
E4(baseline) capture started source=MIC file=baseline-MIC.wav
E4(baseline) t=1s peak=-34.4 dBFS silenced=false
E7 engine=com.google.android.tts engines=com.google.android.tts voices=473 offlineInstalled=22
E7 en-IN available=1 voices=9
E7   en-IN-language quality=400 latency=200 network=false notInstalled=false
E7   en-in-x-ena-local quality=400 latency=200 network=false notInstalled=false
E7   en-in-x-ena-network quality=400 latency=200 network=true notInstalled=false
E7   en-in-x-enc-local quality=400 latency=200 network=false notInstalled=false
E7   en-in-x-enc-network quality=400 latency=200 network=true notInstalled=false
E7   en-in-x-end-local quality=400 latency=200 network=false notInstalled=false
E7   en-in-x-end-network quality=400 latency=200 network=true notInstalled=false
E7   en-in-x-ene-local quality=400 latency=200 network=false notInstalled=false
E7   en-in-x-ene-network quality=400 latency=200 network=true notInstalled=false
E7 hi-IN available=1 voices=9
E7   hi-IN-language quality=400 latency=200 network=false notInstalled=false
E7   hi-in-x-hia-local quality=400 latency=200 network=false notInstalled=false
E7   hi-in-x-hia-network quality=400 latency=200 network=true notInstalled=false
E7   hi-in-x-hic-local quality=400 latency=200 network=false notInstalled=false
E7   hi-in-x-hic-network quality=400 latency=200 network=true notInstalled=false
E7   hi-in-x-hid-local quality=400 latency=200 network=false notInstalled=false
E7   hi-in-x-hid-network quality=400 latency=200 network=true notInstalled=false
E7   hi-in-x-hie-local quality=400 latency=200 network=false notInstalled=false
E7   hi-in-x-hie-network quality=400 latency=200 network=true notInstalled=false
E7 en-US available=1 voices=17
E7   en-US-language quality=400 latency=200 network=false notInstalled=false
E7   en-us-x-iob-local quality=400 latency=200 network=false notInstalled=false
E7   en-us-x-iob-network quality=400 latency=200 network=true notInstalled=false
E7   en-us-x-iog-local quality=400 latency=200 network=false notInstalled=false
E7   en-us-x-iog-network quality=400 latency=200 network=true notInstalled=false
E7   en-us-x-iol-local quality=400 latency=200 network=false notInstalled=false
E7   en-us-x-iol-network quality=400 latency=200 network=true notInstalled=false
E7   en-us-x-iom-local quality=400 latency=200 network=false notInstalled=false
E7   en-us-x-iom-network quality=400 latency=200 network=true notInstalled=false
E7   en-us-x-sfg-local quality=400 latency=200 network=false notInstalled=false
E7   en-us-x-sfg-network quality=400 latency=200 network=true notInstalled=false
E7   en-us-x-tpc-local quality=400 latency=200 network=false notInstalled=false
E7   en-us-x-tpc-network quality=400 latency=200 network=true notInstalled=false
E7   en-us-x-tpd-local quality=400 latency=200 network=false notInstalled=false
E7   en-us-x-tpd-network quality=400 latency=200 network=true notInstalled=false
E7   en-us-x-tpf-local quality=400 latency=200 network=false notInstalled=false
E7   en-us-x-tpf-network quality=400 latency=200 network=true notInstalled=false
E7 en-GB available=1 voices=13
E7   en-GB-language quality=400 latency=200 network=false notInstalled=true
E7   en-gb-x-gba-local quality=400 latency=200 network=false notInstalled=true
E7   en-gb-x-gba-network quality=400 latency=200 network=true notInstalled=true
E7   en-gb-x-gbb-local quality=400 latency=200 network=false notInstalled=true
E7   en-gb-x-gbb-network quality=400 latency=200 network=true notInstalled=true
E7   en-gb-x-gbc-local quality=400 latency=200 network=false notInstalled=true
E7   en-gb-x-gbc-network quality=400 latency=200 network=true notInstalled=true
E7   en-gb-x-gbd-local quality=400 latency=200 network=false notInstalled=true
E7   en-gb-x-gbd-network quality=400 latency=200 network=true notInstalled=true
E7   en-gb-x-gbg-local quality=400 latency=200 network=false notInstalled=true
E7   en-gb-x-gbg-network quality=400 latency=200 network=true notInstalled=true
E7   en-gb-x-rjs-local quality=400 latency=200 network=false notInstalled=true
E7   en-gb-x-rjs-network quality=400 latency=200 network=true notInstalled=true
E4(baseline) t=2s peak=-34.3 dBFS silenced=false
E4(baseline) RESULT source=MIC seconds=2.9 loudest=-34.3 dBFS digitalSilence=6% silencedEver=false file=baseline-MIC.wav bytes=92800
E4(baseline) capture started source=VOICE_RECOGNITION file=baseline-VOICE_RECOGNITION.wav
E4(baseline) t=1s peak=-37.0 dBFS silenced=false
E4(baseline) t=2s peak=-33.4 dBFS silenced=false
E4(baseline) t=3s peak=-46.9 dBFS silenced=false
E4(baseline) RESULT source=VOICE_RECOGNITION seconds=3.0 loudest=-33.4 dBFS digitalSilence=10% silencedEver=false file=baseline-VOICE_RECOGNITION.wav bytes=96000
E4(baseline) capture started source=VOICE_COMMUNICATION file=baseline-VOICE_COMMUNICATION.wav
E4(baseline) t=1s peak=-59.5 dBFS silenced=false
E4(baseline) t=2s peak=-64.9 dBFS silenced=false
E4(baseline) RESULT source=VOICE_COMMUNICATION seconds=2.9 loudest=-59.5 dBFS digitalSilence=55% silencedEver=false file=baseline-VOICE_COMMUNICATION.wav bytes=92800
E4(baseline) capture started source=CAMCORDER file=baseline-CAMCORDER.wav
E4(baseline) t=1s peak=-45.5 dBFS silenced=false
E4(baseline) t=2s peak=-43.7 dBFS silenced=false
E4(baseline) t=3s peak=-49.2 dBFS silenced=false
E4(baseline) RESULT source=CAMCORDER seconds=3.0 loudest=-43.7 dBFS digitalSilence=10% silencedEver=false file=baseline-CAMCORDER.wav bytes=96000
E4(baseline) capture started source=UNPROCESSED file=baseline-UNPROCESSED.wav
E4(baseline) t=1s peak=-55.9 dBFS silenced=false
E4(baseline) t=2s peak=-57.2 dBFS silenced=false
E4(baseline) t=3s peak=-57.4 dBFS silenced=false
E4(baseline) RESULT source=UNPROCESSED seconds=3.0 loudest=-55.9 dBFS digitalSilence=10% silencedEver=false file=baseline-UNPROCESSED.wav bytes=96000
E4(baseline) all sources done
E7 synthesizeToFile queued result=0 voice=en-in-x-enc-local
E7 rendered greeting voice=en-in-x-enc-local bytes=347030 in 1236 ms
```

Check of the rendered `files/greeting.wav` header: sample rate 24000, channels 1, bits per sample 16.

## Network registration with the BSNL SIM (excerpt, identifiers removed)
```text
gsm.sim.state         = ABSENT,LOADED
gsm.sim.operator.alpha= ,BSNL MOBILE
Phone Id=1  CS registrationState=DENIED  PS registrationState=DENIED  availableServices=[EMERGENCY]
ActiveSubInfoList: simSlotIndex=1 carrierId=1549 displayName=BSNL MOBILE carrierName="Emergency calls only" mcc=404 mnc=55
```
