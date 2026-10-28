# PragonMobile v8: the PC's everyday tools, rebuilt for the phone

Built on v7 (Pragon Live). Ten new tools, available in the same three places the old ones are: typed chat (Gemini),
Pragon Live voice, and Ollama/OpenAI-style engines. Float mode needs no extra setup: just ask.

## PC -> phone mapping
| PC tool (`tool_declarations.py` / plugins)         | Phone tool      | What changed for a phone |
|-----------------------------------------------------|-----------------|--------------------------|
| `reminder`                                          | `reminder`      | Real Android alarms + notification; survives reboot; repeat daily/weekdays/weekly; missed ones show after a restart |
| `todo_list`, `open_scheduler`                       | `todo_list`     | Local list you manage by voice (add several with ";", tick off by name or number) |
| `calendar_check`                                    | `calendar`      | Reads the phone calendar (today / tomorrow / next N days); "add" opens the calendar pre-filled so you tap Save |
| `send_message` (WhatsApp/Telegram) + contacts       | `contacts`      | Find a number, open the dialer, or open SMS / WhatsApp / Telegram with the text filled in. **You tap send.** |
| `weather_report`                                    | `weather`       | Open-Meteo (no key); empty place = your remembered home city; Fahrenheit for US phones |
| `web_search` (answering, not just opening a page)   | `web_answer`    | Gemini + Google Search grounding, answered in short spoken sentences. `phone_control web_search` still opens the browser |
| `watch_topics`, `topic_monitor`                     | `watch_topics`  | Follows up to 10 topics; WorkManager checks about every 6 h and notifies only on news |
| `nightly_recap` / morning brief                     | `daily_brief`   | Date, weather, calendar, reminders, to-dos, battery in one spoken briefing |
| `conversation_export`, clipboard                    | `share_text`    | copy / share sheet / export this chat as a .txt through the share sheet |
| `field_ops` (asset history, log_job, recall, safety, escalate) | `field_ops` | Job log you build by talking; history per asset, "similar faults before", safety checklist, escalate. No dataset needed. Dispatch and parts lookup not ported |
| `system_status`                                     | `phone_control status` | Now also: charging, storage free, memory free, Wi-Fi / mobile data |

Assistant mode (talk only) does not get `contacts` or `share_text` (they open other apps).

## Permissions (asked when needed, never at install)
Calendar, contacts, notifications (Android 13+) and exact alarms. When a tool needs one, a prompt opens on the phone and
Pragon says so; ask again after allowing. **Settings > REMINDERS, CALENDAR & CONTACTS** shows the state and has the buttons.

## Privacy notes
- Everything is stored on the phone (reminders, list, topics, job log). Nothing is uploaded by these tools themselves.
- With the Gemini engine, what a tool *returns* goes to Gemini as part of the conversation (a contact's number you asked for, your calendar lines). Use Ollama/local if that is not acceptable.
- Weather, web answers, topic checks and the brief's weather are network calls and go through Privacy Shield. With it ON they are blocked and Pragon says which host to trust. Settings has a one-tap "trust weather server"; web answers and topics need Google trusted (or Shield off), same as Pragon Live.
- Messages are never sent for you: the app opens with the text ready.

## Files
New: `TimeExpr.kt`, `Reminders.kt` (reminders, to-dos, receiver), `PhoneData.kt` (permission prompt, calendar, contacts, messaging),
`Online.kt` (weather, web answers, topic watcher), `ExtraTools.kt` (declarations, dispatcher, brief, share, field log), `tests/TimeExprTest.kt`.
Changed: `Tools.kt`, `LiveSession.kt` (route new tools), `Persona.kt` (when to use each), `Memory.kt` (`find`), `CommandExecutor.kt` (status),
`BatteryReminder.kt` (boot receiver re-arms reminders), `SettingsActivity.kt`, `assets/settings.html`, `AndroidManifest.xml`
(READ_CALENDAR, READ_CONTACTS, SCHEDULE_EXACT_ALARM, receiver, permission activity), `res/xml/file_paths.xml`, `app/build.gradle.kts` (+ `androidx.work:work-runtime-ktx:2.9.1`).

## What was verified, and what was not
Done here: the **whole app compiles** with kotlinc 2.0 against the Android 34 API jar, using hand-written stand-ins for okhttp,
androidx and WorkManager (their real jars were not reachable), so signatures against those libraries were matched by hand;
`TimeExprTest` (23 time phrases) and the existing Calc / Parser tests pass; manifest, file_paths and settings.html script parse.
**Not done: nothing was run on a phone or emulator.** Alarms, permission prompts, the contact/calendar queries, WhatsApp/Telegram
links, WorkManager and the notification behaviour are untested. Try, in order:
1. "remind me in two minutes to stretch": notification arrives (screen off too). Reboot with one pending: it still fires.
2. "add milk and eggs to my list", "what's on my list", "tick off milk".
3. "what's on my calendar today": permission prompt, allow, ask again. "add dentist tomorrow at 4 pm": calendar opens filled in.
4. "text <contact> I'm running late": SMS app opens with the text. Then the same on WhatsApp.
5. Settings: trust the weather server, say "my city is Chennai", then "what's the weather". With Shield off: "who won the match last night".
6. "follow AI chips", then "check my topics". "good morning" for the brief. "share this chat".
Known limits: Android may delay a reminder by minutes unless "Alarms and reminders" is allowed (Pragon says so when it matters);
the first call after a permission prompt needs you to ask again; the on-phone (non-AI) voice parser does not know these
commands, so they need an AI engine and key.

## Not ported (desktop-only, no phone equivalent)
Hotkeys and keyboard shortcuts, window/desktop control, file organizer/processor/generator, code builders (Compass, CustomForge,
pragon_agent), PC firewall/WAF and CPU/resource balancers, Steam/Epic updater, n8n, MOSS/LiveKit, PRAGONCALL, flight/IRCTC agents.
## Good next steps
Offline voice patterns for reminders/lists (no AI call), an optional "send without tapping" via the accessibility service,
a scheduled morning-brief notification, email as a messaging channel.
