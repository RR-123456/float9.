package com.pragon.mobile

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The PC's everyday tools, rebuilt for a phone: reminders, to-do list, calendar, contacts and messaging, weather, live web
 * answers, topic watching, a daily brief, a small field-job log, and sharing/exporting. One declaration list feeds Gemini,
 * Gemini Live and Ollama through [Tools].
 */
object ExtraTools {

    val NAMES = setOf(
        "reminder", "todo_list", "calendar", "contacts", "weather", "web_answer",
        "watch_topics", "daily_brief", "field_ops", "share_text",
        "pragon_contacts", "pragon_call", "pragon_pa", "audio_route",
    )

    /** These open other apps, so Assistant mode (which only talks) doesn't get them. */
    private val ACTING = setOf("contacts", "share_text") + CallTools.ACTING

    private fun fn(upper: Boolean, name: String, desc: String, required: List<String>, vararg p: Pair<String, String>): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (p.isNotEmpty()) {
            val props = JSONObject()
            for ((k, v) in p) props.put(k, JSONObject().put("type", if (upper) "STRING" else "string").put("description", v))
            val params = JSONObject().put("type", if (upper) "OBJECT" else "object").put("properties", props)
            if (required.isNotEmpty()) params.put("required", JSONArray(required))
            o.put("parameters", params)
        }
        return o
    }

    fun decls(mode: PMode, upper: Boolean): List<JSONObject> {
        val all = listOf(
            fn(upper, "reminder",
                "Reminders that notify the user at a time, even when Pragon is closed. add: text=what to remind, when=time. list. cancel: text=part of the reminder, or 'all'. allow_exact: opens the setting for exact timing.",
                listOf("action"),
                "action" to "add, list, cancel or allow_exact",
                "text" to "What to remind about (add), or part of it (cancel).",
                "when" to "For add. Either in:<seconds> (e.g. in:1200 for 20 minutes) or a local date and time like 2026-10-26 18:30 (24 hour). Work it out from the current time in the system prompt.",
                "repeat" to "Optional: daily, weekdays or weekly."),
            fn(upper, "todo_list",
                "The user's to-do list. add: text=item (several can be separated by semicolons). list. done: text=item or its number. remove: text. clear_done.",
                listOf("action"),
                "action" to "add, list, done, remove or clear_done",
                "text" to "Item text or number."),
            fn(upper, "calendar",
                "The phone's calendar. today / tomorrow / upcoming (days=how many, default 7) read events aloud. add opens the calendar app with a new event filled in for the user to save.",
                listOf("action"),
                "action" to "today, tomorrow, upcoming or add",
                "days" to "For upcoming: number of days.",
                "title" to "For add: the event name.",
                "when" to "For add: in:<seconds> or local date and time like 2026-10-26 16:00.",
                "minutes" to "For add: length in minutes, default 60."),
            fn(upper, "contacts",
                "Look up a person in the phone's contacts, call them (opens the dialer) or message them. Messages open the chosen app with the text filled in; the user taps send.",
                listOf("action", "name"),
                "action" to "find, call or message",
                "name" to "Contact name, or a phone number.",
                "text" to "For message: what to send.",
                "channel" to "For message: sms (default), whatsapp or telegram."),
            fn(upper, "weather",
                "Current weather and the forecast for today and tomorrow. Leave place empty to use the user's home city.",
                listOf(),
                "place" to "City name, or empty for the home city."),
            fn(upper, "web_answer",
                "Looks something up on the live web and returns a short spoken answer: news, scores, prices, facts that change, anything recent. Use instead of web_search when the user wants the answer, not a page.",
                listOf("query"),
                "query" to "The question, in plain words."),
            fn(upper, "watch_topics",
                "Follow topics and be notified when there is news. add / remove: topic. list. check: topic (optional) looks now.",
                listOf("action"),
                "action" to "add, remove, list or check",
                "topic" to "The topic, e.g. Chennai Super Kings or AI chips."),
            fn(upper, "daily_brief",
                "A spoken briefing: date and time, weather, calendar, reminders, to-dos and battery. Use for 'brief me', 'good morning', 'what's my day like'.",
                listOf()),
            fn(upper, "field_ops",
                "Job log for technicians. log_job: asset + text. lookup_asset: asset history. recall: text=symptom, finds similar past jobs. safety_check: text=task, reads the lockout and isolation steps. escalate: text=why a human is needed.",
                listOf("action"),
                "action" to "log_job, lookup_asset, recall, safety_check or escalate",
                "asset" to "Asset name or number, e.g. pump 182.",
                "text" to "Summary, symptom, task or reason."),
            fn(upper, "share_text",
                "copy: put text on the clipboard. share: open the share sheet with text. export_chat: share this conversation as a text file.",
                listOf("action"),
                "action" to "copy, share or export_chat",
                "text" to "For copy and share: the text."),
        )
        val full = all + CallTools.decls(mode, upper)
        return if (mode == PMode.ASSISTANT) full.filter { it.optString("name") !in ACTING } else full
    }

    fun run(ctx: Context, name: String, args: JSONObject, mode: PMode): ToolOut {
        if (mode == PMode.ASSISTANT && name in ACTING)
            return ToolOut(false, "Assistant mode can only talk. Say master mode or agent mode first.", false)
        return try {
            when (name) {
                "reminder" -> Reminders.act(ctx, args)
                "todo_list" -> Todos.act(ctx, args)
                "calendar" -> CalendarTool.act(ctx, args, mode)
                "contacts" -> ContactsTool.act(ctx, args, mode)
                in CallTools.NAMES -> CallTools.run(ctx, name, args, mode)
                "weather" -> Weather.act(ctx, args)
                "web_answer" -> WebAnswer.act(ctx, args)
                "watch_topics" -> Topics.act(ctx, args)
                "daily_brief" -> ToolOut(true, brief(ctx), false, "Daily brief")
                "field_ops" -> FieldLog.act(ctx, args)
                "share_text" -> share(ctx, args)
                else -> ToolOut(false, "Unknown tool '$name'.", false)
            }
        } catch (e: SecurityException) {
            ToolOut(false, "Android refused that. Check Pragon's permissions in Settings.", false)
        } catch (e: Exception) {
            ToolOut(false, "That failed: ${e.message ?: e.javaClass.simpleName}", false)
        }
    }

    // ── daily brief ──────────────────────────────────────────────────────

    fun brief(ctx: Context): String {
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.append("It is ").append(SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)))
            .append(", ").append(TimeExpr.clock(now)).append(". ")
        Weather.homePlace(ctx)?.let { place ->
            try { sb.append(Weather.report(ctx, place)).append(' ') } catch (e: Exception) { sb.append("Weather isn't available right now. ") }
        }
        CalendarTool.todayLine(ctx)?.let { sb.append(it).append(' ') }
        val todos = Todos.open(ctx)
        if (todos.isNotEmpty()) sb.append("You have ${todos.size} to-do item${if (todos.size == 1) "" else "s"}: ")
            .append(todos.take(3).joinToString(", ")).append(if (todos.size > 3) ", and more. " else ". ")
        val rem = Reminders.act(ctx, JSONObject().put("action", "list")).msg
        if (!rem.startsWith("You have no")) sb.append(rem).append(' ')
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            sb.append("Battery is at ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} percent.")
        } catch (e: Exception) { }
        return sb.toString().trim()
    }

    // ── sharing ──────────────────────────────────────────────────────────

    private fun chatText(): String {
        val sb = StringBuilder()
        fun add(who: String, t: String) { if (t.isNotBlank()) sb.append(who).append(": ").append(t.trim()).append("\n\n") }
        synchronized(Conversation) {
            if (Conversation.gemini.isNotEmpty()) {
                for (m in Conversation.gemini) {
                    val role = if (m.optString("role") == "user") "You" else "Pragon"
                    val parts = m.optJSONArray("parts") ?: continue
                    val t = StringBuilder()
                    for (i in 0 until parts.length()) t.append(parts.optJSONObject(i)?.optString("text") ?: "")
                    add(role, t.toString())
                }
            } else {
                for (m in Conversation.local) {
                    val role = when (m.optString("role")) { "user" -> "You"; "assistant" -> "Pragon"; else -> continue }
                    add(role, m.optString("content"))
                }
            }
        }
        return sb.toString().trim()
    }

    private fun share(ctx: Context, a: JSONObject): ToolOut {
        when (a.optString("action").lowercase()) {
            "copy" -> {
                val t = a.optString("text")
                if (t.isBlank()) return ToolOut(false, "What should I copy?", false)
                return if (Launch.copy(ctx, t)) ToolOut(true, "Copied to the clipboard.", false, "Clipboard") else ToolOut(false, "I couldn't use the clipboard.", false)
            }
            "share" -> {
                val t = a.optString("text")
                if (t.isBlank()) return ToolOut(false, "What should I share?", false)
                val i = Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share")
                val err = Launch.start(ctx, i, "Share", guard = false)
                return if (err == null) ToolOut(true, "The share sheet is open.", true, "Share") else ToolOut(false, err, false)
            }
            "export_chat" -> {
                val text = chatText()
                if (text.isBlank()) return ToolOut(false, "There is no conversation to export yet.", false)
                val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                dir.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
                val f = File(dir, "pragon_chat_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".txt")
                f.writeText(text)
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val err = Launch.start(ctx, Intent.createChooser(send, "Share chat").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share", guard = false)
                return if (err == null) ToolOut(true, "The share sheet is open with this conversation as a text file.", true, "Share") else ToolOut(false, err, false)
            }
        }
        return ToolOut(false, "Unknown share action. Use copy, share or export_chat.", false)
    }
}

/** The PC's field_ops, trimmed to what works with no dataset: a job log you build by talking, plus a safety checklist. */
object FieldLog {
    private const val FILE = "pragon_field"
    private const val MAX = 300

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context): JSONArray = try { JSONArray(sp(c).getString("jobs", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }

    @Synchronized
    private fun save(c: Context, a: JSONArray) {
        val keep = if (a.length() > MAX) JSONArray().also { n -> for (i in a.length() - MAX until a.length()) n.put(a.get(i)) } else a
        sp(c).edit().putString("jobs", keep.toString()).apply()
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun day(ms: Long) = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))

    private val STOP = setOf("the", "a", "an", "is", "are", "was", "it", "this", "that", "and", "or", "of", "to", "in", "on", "with", "for", "not", "no", "has", "have")

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP }.toSet()

    fun act(ctx: Context, a: JSONObject): ToolOut {
        val c = ctx.applicationContext
        val asset = a.optString("asset").trim()
        val text = a.optString("text").trim()
        when (a.optString("action").lowercase()) {
            "log_job", "log" -> {
                if (text.isEmpty()) return ToolOut(false, "What was the job?", false)
                val jobs = load(c)
                jobs.put(JSONObject().put("t", System.currentTimeMillis()).put("asset", asset).put("text", text.take(400)).put("esc", false))
                save(c, jobs)
                val n = (0 until jobs.length()).count { norm(jobs.getJSONObject(it).optString("asset")) == norm(asset) && asset.isNotEmpty() }
                return ToolOut(true, "Logged" + (if (asset.isNotEmpty()) " for $asset" else "") + "." +
                    (if (n >= 3) " That's job number $n on this asset, so it may be recurring." else ""), false, "Job log")
            }
            "lookup_asset", "lookup", "history" -> {
                if (asset.isEmpty()) return ToolOut(false, "Which asset?", false)
                val jobs = load(c)
                val hits = (0 until jobs.length()).map { jobs.getJSONObject(it) }.filter { norm(it.optString("asset")).contains(norm(asset)) }
                if (hits.isEmpty()) return ToolOut(true, "I have no jobs logged for $asset yet.", false, "Job log")
                val recent = hits.takeLast(3).reversed().joinToString("; ") { "${day(it.optLong("t"))}, ${it.optString("text")}" }
                return ToolOut(true, "${hits.size} job${if (hits.size == 1) "" else "s"} on $asset. Latest: $recent.", false, "Job log")
            }
            "recall", "similar" -> {
                if (text.isEmpty()) return ToolOut(false, "Describe the symptom.", false)
                val q = words(text)
                val jobs = load(c)
                val scored = (0 until jobs.length()).map { jobs.getJSONObject(it) }
                    .map { it to words(it.optString("text")).intersect(q).size }.filter { it.second > 0 }
                    .sortedByDescending { it.second }.take(3)
                if (scored.isEmpty()) return ToolOut(true, "I've no similar job on record. Consider escalating if you're unsure.", false, "Job log")
                return ToolOut(true, "Similar before: " + scored.joinToString("; ") {
                    (if (it.first.optString("asset").isNotEmpty()) it.first.optString("asset") + ", " else "") + day(it.first.optLong("t")) + ", " + it.first.optString("text")
                } + ".", false, "Job log")
            }
            "safety_check", "safety" -> {
                val task = if (text.isNotEmpty()) " for $text" else ""
                return ToolOut(true, "Safety steps$task: one, tell the control room or your supervisor. two, isolate the energy source. " +
                    "three, lock out and tag out, and keep your own key. four, release stored energy and test that it's dead. " +
                    "five, wear the right protective gear. six, confirm the area is clear. Say which steps you've done and I'll note them.", false, "Safety")
            }
            "escalate" -> {
                val jobs = load(c)
                jobs.put(JSONObject().put("t", System.currentTimeMillis()).put("asset", asset).put("text", "ESCALATED: " + text.take(380)).put("esc", true))
                save(c, jobs)
                return ToolOut(true, "Flagged for a human: not enough evidence to proceed. Call your supervisor or senior technician" +
                    (if (asset.isNotEmpty()) " about $asset" else "") + ".", false, "Job log")
            }
        }
        return ToolOut(false, "Unknown field action. Use log_job, lookup_asset, recall, safety_check or escalate.", false)
    }
}
