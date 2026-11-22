# ADR-0001: Telephony architecture

- **Status:** **GATE FAILED ON E4, awaiting the owner's decision.** On 2026-11-22, loopback calls on the Moto G60 showed that every capture source is silenced by the framework during an active call ([log](../device-logs/loopback-run-2026-11-22.md)). On-device voicemail *recording* is therefore impossible on this device. Per the table below, the best outcome is a **greeting-only auto-responder**, and only if E3 passes on a real call. Option 2b stays excluded ("nothing paid"). The owner chooses: **stop**, or **build the greeting-only product** (which still needs E3 on a real call).
- **Date:** 2026-11-22
- **Deciders:** Project owner, with the planning author
- **Evidence:** [TECHNICAL_FEASIBILITY.md](../TECHNICAL_FEASIBILITY.md), [TELEPHONY_RESEARCH.md](../TELEPHONY_RESEARCH.md)

## Context
The product goal is to answer unanswered cellular calls, play a greeting, record the caller and manage those recordings. Official documentation establishes three things **[VERIFIED-DOC]**:
- Third-party apps cannot capture call audio (`CAPTURE_AUDIO_OUTPUT` is system-only).
- No API exists to inject audio into a call.
- A default-dialer app *can* answer and disconnect calls.

The project owner has ruled out a cloud backend and paid telephony (2026-11-22).

## Options considered
1. **Default dialer + acoustic answering machine.** The app answers on speakerphone, plays the greeting through the loudspeaker and records through the mic. This is on-device, unverified and device-dependent.
2. **(a)** Carrier voicemail with Visual Voicemail. This depends on the carrier and is likely unavailable in India. **(b)** Forwarding to a cloud telephony number. This is genuine and reliable, but needs a paid backend, so it is **excluded by the owner**.
3. **CallScreeningService-only.** It can reject or silence calls, but cannot answer, play or record.

## Decision (provisional)
Pursue **Option 1** as an **experimental, device-verified** feature, gated as follows:

| E3: greeting audible to caller | E4: caller captured | Result |
|---|---|---|
| Pass | Pass | **GO.** Implement roadmap Phase 4. The voicemail engine ships behind a mandatory in-app **compatibility self-test**, and unsupported devices see an honest "not supported on this device" state. |
| Pass | Fail | **STOP and escalate.** Only a greeting-only auto-responder is possible. The owner chooses between stopping and shipping a greeting-only auto-responder. Option 2b is **not** available, because the owner ruled out anything paid (2026-11-22). |
| Fail | any | **NO-GO** for on-device voicemail. Escalate to the owner. |

E3 passes if the caller, on a second phone, clearly hears and understands at least 90% of the greeting's words in each of 3 trial calls at default greeting volume.

E4 passes if the recorded caller speech is non-silent (RMS above the noise floor by at least 15 dB) and a human can transcribe it in each of 3 trials, with the G60 lying face-up on a table.

Option 3 (CallScreeningService) is kept as an optional **rules-only mode** that doesn't require replacing the dialer.

## Consequences
- **Positive:** No backend, no per-minute cost, and recordings never leave the device.
- **Negative:**
  - The app must be a **complete dialer**, which is large in scope and carries regression risk on core phone functionality.
  - Users lose Google Phone features: spam protection and native recording.
  - Speakerphone answering plays the caller aloud in the room.
  - Quality is far below carrier voicemail.
  - Behaviour varies by OEM, so the self-test is mandatory and the app must make no "works everywhere" claims.
  - It cannot work while the phone is off, out of coverage or in airplane mode, because the network delivers nothing. Carrier voicemail could.
- **Distribution:** Feasible on Play as a dialer. Marketing must not claim "call recording", and no Accessibility API is used.

## Revisit triggers
- E4 fails on the G60.
- A public Android API for call audio processing becomes available at a level the target devices run (see TELEPHONY_RESEARCH §1.3).
- The owner lifts the "nothing paid" constraint (2026-11-22), which would make Option 2b eligible again.
