# PragonMobile v9: AirPods + speaker/earpiece, voice calling, Pragon PA

Built on v8.
**Checked:** the whole app was compiled against the Android 33 API jar with stand-ins for okhttp/androidx/zxing/WorkManager: **zero errors** in
all files, the only 4 messages left are the old `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` lines that need the API-34 jar (Gradle has it). Parser tests pass.
**Not checked:** a real Gradle build and a real phone. Build it with the GitHub Action and use the checklist at the bottom.

## 0. Wording (UI + spoken)
Gemini -> **Pragon**, Ollama -> **Ghost** everywhere you read or hear it (engine buttons, API key box, errors, hints). "Connecting to Gemini Live" ->
**PRAGON INITIALIZING...**. Internals (hosts, model ids, saved settings) are unchanged. "ghost pull llama3.2", "ghost list" work as voice commands too;
the real server variable is still `OLLAMA_HOST`.

## 1. Audio: AirPods, speaker, earpiece (`AudioRoute.kt`)
- AirPods / any Bluetooth headset connected: Pragon's voice AND the headset mic are used (float mode, Gemini Live, on-phone voice).
  Android only gives a headset's mic over the call profile, so earbuds run in call quality while Pragon listens. Say or tick
  "use the phone's microphone" (`audio_route btmic_off`) to keep music quality.
- No headset: **speaker is the default**; "use the earpiece" switches to the normal phone-call speaker.
- Voice: "use the earpiece", "use the speaker", "what audio am I on". Also Settings > CALLS, AIRPODS & PRAGON PA.
- Edited: `LiveSession.kt` (routing now via AudioRoute), `Speaker.kt` (TTS follows the route), `FloatService.kt` (holds the route while float mode runs).

## 2. Voice calling and Pragon contacts (`CallTools.kt`)
- Tools `pragon_contacts` (add / remove / list) and `pragon_call`.
- **By name: only people in the Pragon contacts list.** "add Ravi 98765 43210 to Pragon contacts", or "add Ravi" with no digits to copy
  the number from the phone's contacts. A name that is not saved is refused and Pragon offers to add it.
- **By number: "call 98765 43210" dials exactly what you said**, right away (no dialer screen). `*` and `#` are never auto-dialled.
- Works offline too: the on-phone parser understands "call Ravi" and "call 98765 43210" with no AI key.
- Edited: `CommandExecutor.kt` (`call` now dials; new `call_contact`), `VoiceParser.kt`, `Tools.kt`, `ExtraTools.kt`, `Persona.kt`.

## 3. Pragon PA (`PragonPA.kt`)
Turn on: "turn on Pragon PA" (asks for phone, call-log and mic permission once).
1. A call rings. Pragon says who it is and asks. You say **"OK"** (or tap *Attend with Pragon*) to let it answer; **"ignore"** leaves it ringing; **"reject"** hangs up.
   No answer in 25 s = it does nothing. It never answers without your approval.
2. Pragon answers, puts the call on the loudspeaker and talks to the caller through Gemini Live: says it is your AI assistant, finds out who,
   why, how urgent and a callback, gives nothing personal about you, refuses OTP / bank / payment requests and flags likely scams.
3. It hangs up politely and tells you the summary (spoken + notification). "what were my last calls" reads the log (last 20 kept on the phone).
4. **Take over any time:** notification *TAKE OVER*, one tap on the float bubble, or say "Pragon takeover". Pragon goes silent, audio goes back to
   normal, the call stays connected and a note shows what it learned so far.
- If the caller hangs up first, Pragon summarizes the conversation anyway. Transcripts are kept in memory only during the call.

### Limits you should know (Android, not this app)
- Apps cannot read or play into call audio. Pragon hears the caller through the **loudspeaker -> microphone** and the caller hears Pragon the same
  way. Quiet room, phone flat. Expect worse quality than a normal call.
- Android 10+ can silence a third-party mic during a call; apps with an enabled accessibility service (Pragon's) are exempt, so keep that service ON.
- Needs a Gemini key (Gemini Live), internet and Privacy Shield trusting Google. It checks all this BEFORE answering and leaves the phone ringing if something is missing.
- Android 14+ restricts starting a microphone service from the background; the notification button always works, voice approval works while float mode is running.
- With AirPods connected the PA still forces the loudspeaker (its mic must hear the caller).

## Files
New: `AudioRoute.kt`, `CallTools.kt`, `PragonPA.kt` (PA state machine, `PaService`, receivers). Changed: `LiveSession.kt` (PA mode, 3 PA tools, AudioRoute),
`Persona.kt` (`PaPersona`, tool docs), `Speaker.kt`, `FloatService.kt`, `ExtraTools.kt`, `CommandExecutor.kt`, `VoiceParser.kt`, `Tools.kt`,
`SettingsActivity.kt` + `assets/settings.html` (new card), `AndroidManifest.xml` (MODIFY_AUDIO_SETTINGS, BLUETOOTH_CONNECT, CALL_PHONE, ANSWER_PHONE_CALLS,
READ_PHONE_STATE, READ_CALL_LOG, PHONE_STATE receiver, PaService), `app/build.gradle.kts` (version 3.1).

## Test in this order
1. Connect AirPods, float mode on, say a command: reply and mic both on the AirPods. Disconnect: back to the speaker. "use the earpiece": voice at your ear.
2. "add Ravi 98xxxxxxxx to Pragon contacts", "call Ravi" (dials). "call Kumar" (not saved: refused). "call 98xxxxxxxx" (dials). Test with your own second number first.
3. Settings > allow phone permissions, "turn on Pragon PA". Call yourself from another phone: you hear the question; say OK. Speak as the caller. Check the summary.
4. During that call tap TAKE OVER, then again say "Pragon takeover" on a second test. Hang up from the other side during a PA call: summary should still arrive.
5. If Pragon cannot hear the caller: check Accessibility is ON for Pragon, volume up, phone flat, and tell me the phone model.
