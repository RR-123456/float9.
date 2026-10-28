# PragonMobile v7: Pragon Live (the PC's Gemini native-audio session, on the phone)

Float mode now talks to Gemini Live exactly the way Pragon does on the PC (`pragon_main.py`): one continuous voice
conversation in the **Charon** voice, you can interrupt it, and it calls the phone tools itself.

## PC -> phone mapping
| PC (`pragon_main.py`)                                   | Phone                                                         |
|---------------------------------------------------------|---------------------------------------------------------------|
| `LIVE_MODEL = ...native-audio-preview-12-2025`          | `Prefs.LIVE_MODEL_DEFAULT` (override pref `live_model`)       |
| `client.aio.live.connect(model, config)`                | OkHttp WebSocket `BidiGenerateContent` (v1alpha, falls back to v1beta) |
| `_build_config()`: time + memory + protocol + language  | `Persona.liveSystem()`                                        |
| `pragoncore/protocol.txt` v2                            | `Persona.LIVE_STATIC` (PC sections kept; TOOL ROUTING rewritten for phone tools) |
| `voice_name="Charon"`, `GEMINI_VOICES`                  | `Prefs.liveVoice()` (default Charon), `LiveSession.VOICES`    |
| `_listen_audio` 16 kHz mic -> session                   | `AudioRecord` VOICE_COMMUNICATION (echo cancel + noise suppress) |
| `_receive_audio` / `_play_audio` 24 kHz, 50 ms slices   | `AudioTrack` on its own thread, 2400-byte slices, flushed on `interrupted` |
| input/output transcription, session resumption, sliding-window compression | same setup fields |
| affective dialog + proactive audio, dropped if rejected | same (v1alpha first, then v1beta) |
| `_execute_tool`, one-call rule                          | `Tools.run()` (same `phone_control` / `remember` / `forget`), duplicate calls skipped per turn |
| `shutdown_pragon`, voice change, language reconnect     | `shutdown_pragon`, `change_voice`, `set_mode`, `mute_pragon` tools (config changes reconnect after the spoken confirmation) |
| `[STARTUP_BRIEFING]` greeting                           | "Pragon, I'm home" -> Live greets in its own voice            |

NOT moved (PC-only): the other engines (Jarvis / Friday / Ghost), the 65 desktop tools, MOSS, LiveKit, PRAGONCALL, plugins, UI.

## Behaviour
- Say "Pragon, I'm home" (or tap the bubble): the on-phone recognizer only waits for the wake phrase; then Live takes the microphone.
- Tap the bubble while Pragon talks: it stops talking and listens. Long-press: goodbye. "Mute" / "goodbye" by voice work too.
- Spoken turns are added to the shared chat history, so the text chat knows what you said by voice.
- **Fallback:** if Live is switched off, the engine is not Gemini, there is no key, Privacy Shield blocks Google, or the
  connection fails repeatedly, float mode silently uses the old pipeline (SpeechRecognizer + Brain + phone TTS) as in v6.

## Settings (FLOAT & TYPER)
- *Pragon Live voice (Gemini native audio, Charon)*: ON by default.
- *Let me interrupt Pragon while it talks*: ON. Turn OFF if it hears and answers itself on the loudspeaker.
- Needs: Gemini engine, an API key, and **Privacy Shield OFF** (or `generativelanguage.googleapis.com` under Trusted hosts).
  Privacy Shield is ON by default and blocks Google, which is intended.

## Files
New: `LiveSession.kt`. Changed: `Persona.kt` (+Live prompt), `FloatService.kt` (start/stop Live, fallback), `Prefs.kt`,
`Guard.kt` (summary), `SettingsActivity.kt`, `assets/settings.html`. No manifest change (RECORD_AUDIO and the microphone
foreground service type were already there).

## NOT verified here: please read
This was written without an Android SDK or Kotlin compiler available, so it has **not been compiled or run**.
Brace/paren balance and the signatures it calls were checked by hand. Build it (Android Studio or the GitHub Action)
and expect to fix a compile error or two. Then try on the phone:
1. Settings: Privacy Shield OFF, Gemini key set. Float mode on, say "Pragon, I'm home": Charon should greet you.
2. "open youtube" -> YouTube opens, Pragon says what happened. "set a timer for two minutes".
3. Interrupt it mid-sentence by talking. Tap the bubble mid-sentence.
4. "agent mode" then "assistant mode" (reconnects, short pause). "change your voice".
5. "my name is ..." then, after "goodbye" and a new session, "what's my name?".
6. Turn Wi-Fi off mid-session: it should retry, then fall back to the on-phone voice with a message.
7. Loudspeaker: if it answers itself, turn off "interrupt".

Known risks: the preview model name may be retired by Google (set pref `live_model`; the error text shows what the server
said); voice calls use the call-volume stream (USAGE_VOICE_COMMUNICATION, needed for echo cancelling), so adjust call volume;
other apps' media may pause while the mic is open; a live session uses mobile data and battery continuously.
