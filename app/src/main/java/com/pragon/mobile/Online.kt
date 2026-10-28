package com.pragon.mobile

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One shared HTTP client for the information tools. It goes through Privacy Shield like every other connection. */
object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Guard.ShieldInterceptor())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun get(url: String): String {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IOException("The server answered ${r.code}.")
            return body
        }
    }

    /** What to tell the user when a request failed. Privacy Shield's message already says exactly what to change. */
    fun why(e: Exception): String =
        if (e is IOException && (e.message ?: "").startsWith("Privacy Shield")) e.message ?: "Privacy Shield blocked it."
        else "I couldn't reach the internet (${e.message ?: e.javaClass.simpleName})."
}

/** The PC's `weather_report`, using Open-Meteo (free, no key). */
object Weather {
    private fun words(code: Int): String = when (code) {
        0 -> "clear sky"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thunderstorms"
        else -> "mixed conditions"
    }

    fun homePlace(ctx: Context): String? = PragonMemory.find(ctx, "city", "location", "home_city", "hometown", "home", "place")

    /** A spoken summary, or throws with a user-readable reason. */
    fun report(ctx: Context, place: String): String {
        val fahrenheit = Locale.getDefault().country == "US"
        val geo = JSONObject(Net.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&name=" + URLEncoder.encode(place, "UTF-8")))
        val hit = geo.optJSONArray("results")?.optJSONObject(0) ?: throw IOException("I couldn't find a place called $place.")
        val unit = if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val f = JSONObject(Net.get(
            "https://api.open-meteo.com/v1/forecast?latitude=${hit.optDouble("latitude")}&longitude=${hit.optDouble("longitude")}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&forecast_days=2&timezone=auto$unit"))
        val cur = f.optJSONObject("current") ?: throw IOException("The weather service sent nothing.")
        val d = f.optJSONObject("daily")
        val u = if (fahrenheit) "degrees" else "degrees Celsius"
        fun r(x: Double) = Math.round(x).toInt()
        val sb = StringBuilder()
        sb.append("${hit.optString("name", place)}: ${r(cur.optDouble("temperature_2m"))} $u now, feels like ${r(cur.optDouble("apparent_temperature"))}, ")
        sb.append("${words(cur.optInt("weather_code"))}, humidity ${cur.optInt("relative_humidity_2m")} percent, wind ${r(cur.optDouble("wind_speed_10m"))} kilometres an hour. ")
        if (d != null) {
            val mx = d.optJSONArray("temperature_2m_max"); val mn = d.optJSONArray("temperature_2m_min")
            val pp = d.optJSONArray("precipitation_probability_max"); val wc = d.optJSONArray("weather_code")
            if (mx != null && mn != null && mx.length() >= 2 && mn.length() >= 2) {
                sb.append("Today ${r(mn.optDouble(0))} to ${r(mx.optDouble(0))}")
                if (pp != null && pp.length() > 0) sb.append(", ${pp.optInt(0)} percent chance of rain")
                sb.append(". Tomorrow ${r(mn.optDouble(1))} to ${r(mx.optDouble(1))}")
                if (wc != null && wc.length() > 1) sb.append(", ${words(wc.optInt(1))}")
                if (pp != null && pp.length() > 1) sb.append(", ${pp.optInt(1)} percent chance of rain")
                sb.append(".")
            }
        }
        return sb.toString().trim()
    }

    fun act(ctx: Context, a: JSONObject): ToolOut {
        val place = a.optString("place").trim().ifEmpty { homePlace(ctx) ?: "" }
        if (place.isEmpty()) return ToolOut(false, "Which city? Tell me once and I'll remember it.", false)
        return try { ToolOut(true, report(ctx, place), false, "Weather") }
        catch (e: IOException) { ToolOut(false, Net.why(e), false) }
        catch (e: Exception) { ToolOut(false, "The weather lookup failed: ${e.message}", false) }
    }
}

class Ans(val ok: Boolean, val text: String)

/** Live web answers: Gemini with Google Search grounding (the PC's `web_search`, which answers instead of just opening a page). */
object WebAnswer {
    private val JSONT = "application/json; charset=utf-8".toMediaType()
    private const val SYSTEM = "You answer by voice. Use Google Search for current facts. Reply in plain spoken sentences: at most four short sentences, " +
        "no markdown, no bullet points, no web addresses. Say numbers, times and dates the way a person says them. If you cannot find it, say so."

    private fun thinking(model: String): JSONObject? = when {
        model.startsWith("gemini-3") -> JSONObject().put("thinkingLevel", "low")
        model.startsWith("gemini-2.5-flash") -> JSONObject().put("thinkingBudget", 0)
        else -> null
    }

    private fun tidy(s: String): String = s
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*#`_]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun ask(ctx: Context, question: String, system: String = SYSTEM): Ans {
        val key = Prefs.effectiveAiKey(ctx)
        if (key.isBlank()) return Ans(false, "Live web answers need a Pragon API key. Add yours in Settings.")
        val models = listOf(Prefs.aiModelWorking(ctx).ifBlank { Prefs.aiModel(ctx) }, "gemini-2.5-flash").distinct()
        var err = "No model answered."
        for (m in models) {
            val gen = JSONObject().put("temperature", 0.3).put("maxOutputTokens", 700)
            thinking(m)?.let { gen.put("thinkingConfig", it) }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", question)))))
                .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                .put("generationConfig", gen)
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody(JSONT))
                .build()
            try {
                Net.http.newCall(req).execute().use { r ->
                    val raw = r.body?.string() ?: ""
                    if (r.code == 404) { err = "That model isn't available."; return@use }
                    if (!r.isSuccessful) {
                        err = try { JSONObject(raw).getJSONObject("error").optString("message") } catch (e: Exception) { "The server answered ${r.code}." }
                        return Ans(false, err)
                    }
                    val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val sb = StringBuilder()
                    if (parts != null) for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                    val t = tidy(sb.toString())
                    return if (t.isEmpty()) Ans(false, "The search came back empty.") else Ans(true, t)
                }
            } catch (e: Exception) {
                return Ans(false, Net.why(e))
            }
        }
        return Ans(false, err)
    }

    fun act(ctx: Context, a: JSONObject): ToolOut {
        val q = a.optString("query").trim()
        if (q.isEmpty()) return ToolOut(false, "What should I look up?", false)
        val r = ask(ctx, q)
        return ToolOut(r.ok, r.text, false, "Web")
    }
}

/**
 * The PC's `watch_topics` / topic_monitor. Pragon checks the topics you follow about every six hours (WorkManager, so it
 * survives reboots and doze) and posts a notification only when something new turned up.
 */
object Topics {
    private const val FILE = "pragon_topics"
    private const val CH = "pragon_topics"
    private const val WORK = "pragon_topics"
    private const val NOTHING = "NOTHING_NEW"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context): JSONArray = try { JSONArray(sp(c).getString("list", "[]") ?: "[]") } catch (e: Exception) { JSONArray() }

    @Synchronized
    private fun save(c: Context, a: JSONArray) { sp(c).edit().putString("list", a.toString()).apply() }

    fun names(c: Context): List<String> {
        val a = load(c)
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("topic")?.ifBlank { null } }
    }

    private fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c.applicationContext)
        if (names(c).isEmpty()) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<TopicWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun prompt(topic: String) =
        "What are the most important new developments about \"$topic\" in the last 24 hours? " +
            "Answer in at most two short spoken sentences. If there is nothing notable, reply with exactly $NOTHING."

    /** Checks every topic. With [alert] true (the background job) it posts a notification for new items. Returns a spoken digest. */
    fun checkAll(c: Context, alert: Boolean): String {
        val a = load(c)
        val digest = mutableListOf<String>()
        var failure = ""
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val topic = o.optString("topic")
            val r = WebAnswer.ask(c, prompt(topic))
            if (!r.ok) { failure = r.text; continue }
            val txt = r.text
            if (txt.contains(NOTHING, true)) continue
            val sig = txt.lowercase().filter { it.isLetterOrDigit() }.take(120)
            if (alert) {
                if (sig == o.optString("sig")) continue
                Notes.post(c, CH, "Topic updates", topic, txt, 9500 + i)
            }
            o.put("sig", sig)
            digest.add("$topic: $txt")
        }
        save(c, a)
        sp(c).edit().putString("last_error", failure).apply()
        return when {
            digest.isNotEmpty() -> digest.joinToString(" ")
            failure.isNotEmpty() -> failure
            else -> "Nothing new on your topics."
        }
    }

    fun act(ctx: Context, a: JSONObject): ToolOut {
        val c = ctx.applicationContext
        val topic = a.optString("topic").trim()
        when (a.optString("action").lowercase().ifEmpty { "list" }) {
            "add", "watch", "follow" -> {
                if (topic.isEmpty()) return ToolOut(false, "Which topic should I follow?", false)
                val arr = load(c)
                if (names(c).any { it.equals(topic, true) }) return ToolOut(true, "I'm already following $topic.", false)
                if (arr.length() >= 10) return ToolOut(false, "I can follow 10 topics. Remove one first.", false)
                arr.put(JSONObject().put("topic", topic.take(80)).put("sig", ""))
                save(c, arr)
                schedule(c)
                var note = ""
                if (!Notes.notificationsAllowed(c)) { Perm.request(c, android.Manifest.permission.POST_NOTIFICATIONS); note = " Allow notifications so I can alert you." }
                if (Guard.privacyOn(c)) note += " Privacy Shield is on and blocks Google, so I can't check until you turn it off or trust generativelanguage.googleapis.com."
                return ToolOut(true, "Now following $topic. I'll check about every six hours and only notify you when there's news.$note", false, "Topics")
            }
            "remove", "stop", "unwatch", "delete" -> {
                val arr = load(c)
                val keep = JSONArray()
                var hit = false
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (topic.isNotEmpty() && o.optString("topic").lowercase().contains(topic.lowercase())) hit = true else keep.put(o)
                }
                if (!hit) return ToolOut(false, "I'm not following anything like '$topic'.", false)
                save(c, keep)
                schedule(c)
                return ToolOut(true, "Stopped following $topic.", false, "Topics")
            }
            "check", "now", "news" -> {
                if (names(c).isEmpty()) return ToolOut(false, "You aren't following any topics yet.", false)
                val one = if (topic.isNotEmpty()) {
                    val r = WebAnswer.ask(c, prompt(topic))
                    return ToolOut(r.ok, if (r.ok && r.text.contains(NOTHING, true)) "Nothing new on $topic." else r.text, false, "Topics")
                } else ""
                return ToolOut(true, one + checkAll(c, alert = false), false, "Topics")
            }
            "list", "show" -> {
                val n = names(c)
                if (n.isEmpty()) return ToolOut(true, "You aren't following any topics.", false, "Topics")
                val err = sp(c).getString("last_error", "") ?: ""
                return ToolOut(true, "Following: ${n.joinToString(", ")}." + (if (err.isNotEmpty()) " The last check failed: $err" else ""), false, "Topics")
            }
        }
        return ToolOut(false, "Unknown topic action. Use add, remove, list or check.", false)
    }
}

class TopicWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try { Topics.checkAll(applicationContext, alert = true) } catch (e: Exception) { }
        return Result.success()
    }
}
