# PragonMobile v6

## Fixes
- **YouTube (and other media) stuttering / pausing while Pragon listens.** Google's speech recognizer takes audio focus every time
  it restarts, so a video that is playing keeps getting paused and resumed. New *Media-friendly listening* (Settings > FLOAT & TYPER, ON by default):
  while music/video is playing, float mode keeps the microphone **closed** (the bubble dims). Tap the bubble once to say one command, or just
  pause the video and it listens again by itself. Typer mode is exempt (dictation needs the mic). Turn the setting off to listen all the time.
- **".N" dropped to a second line** in the P.R.A.G.O.N title: the title is now one line and shrinks to fit; chips are slightly tighter.
- **Macro DELETE did nothing.** The settings page used JavaScript `confirm()`, which an Android WebView without a WebChromeClient always answers "no".
  Added the missing client (a real OK/Cancel dialog). Also fixes the other two DELETE / UNINSTALL buttons.

## New
- **Play on YouTube really plays.** "play despacito" / "play despacito on youtube" / "watch lofi on youtube" search, wait for the results, and tap the first video.
  ("search X on youtube" still only shows the results.)
- **Click by position.** "click first video", "play the second video", "open the third result", "tap video 2", "open the last video", "click the first one".
  Videos are found by their accessibility description ("... play video") with a card-shape fallback for other apps; "result/item/link/post" use a generic list.
- **Read and explain anything on screen**, including photos: "what is this picture", "describe this image", "what does this say", "what is this number",
  "explain this paragraph", "what's on my screen". Pragon sends the visible text **plus a screenshot** (Android 11+) to your chosen engine
  (Gemini, Ollama vision model such as gemma3 / llava / qwen2.5vl, or an OpenAI-compatible vision model).
  Needs *Let Pragon read my screen* (Settings > Security). Not sent while a password box is visible. Gemini is refused while Privacy Shield is ON.
  It does not name real people from their faces.
- **Typer auto text-box detection.** "typer mode" now finds the box itself: the one with the cursor, else a search / message / comment box,
  else a chat composer at the bottom, else the top one; never a password box. Works again later in the session if you change screens.
  Setting: FLOAT & TYPER > *Typer mode finds the text box by itself* (ON by default).

## Notes
- The accessibility service config gained `canTakeScreenshot`. After installing, if "describe" says it couldn't get a screenshot, switch the Pragon accessibility service OFF and ON once.
- Compile-checked here against android.jar with okhttp stubbed (all changed Kotlin files compile; androidx/R unavailable in that check). ParserTest and ParserTest2 pass.
  Not run on a phone: please try  play despacito / click second video / what is this picture / typer mode in a search box / video playing + float bubble.
