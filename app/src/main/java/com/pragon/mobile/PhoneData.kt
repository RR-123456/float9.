package com.pragon.mobile

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.util.Locale

/** Transparent screen whose only job is to show Android's permission prompt for the voice tools (a service cannot). */
class PermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = intent.getStringArrayExtra("perms")
        if (p == null || p.isEmpty()) { finish(); return }
        try { requestPermissions(p, 91) } catch (e: Exception) { finish() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}

object Perm {
    fun has(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Opens Android's permission prompt. Returns false if it could not even be shown. */
    fun request(c: Context, vararg perms: String): Boolean = try {
        c.startActivity(
            Intent(c, PermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("perms", perms.toList().toTypedArray())
        )
        true
    } catch (e: Exception) { false }

    /** null when [p] is granted; otherwise the prompt is opened and this is what to tell the user. */
    fun need(c: Context, p: String, what: String): String? {
        if (has(c, p)) return null
        val shown = request(c, p)
        return if (shown) "I need permission to $what. A prompt just opened on the phone: allow it, then ask me again."
        else "I need permission to $what. Allow it in Pragon's settings (Settings > Allow calendar and contacts), then ask me again."
    }
}

/** Starts an activity the way the rest of Pragon does: respects the allowed-apps list and wakes the screen. */
object Launch {
    @Suppress("DEPRECATION")
    private fun wake(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "pragon:wake2").acquire(3000)
                Thread.sleep(300)
            }
        } catch (e: Exception) { }
    }

    /** null = started; otherwise what went wrong, ready to be spoken. */
    fun start(ctx: Context, intent: Intent, label: String, guard: Boolean = true): String? {
        if (guard && Guard.allowlistOn(ctx)) {
            val pkg = intent.`package` ?: try {
                ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            } catch (e: Exception) { null }
            if (!Guard.canOpen(ctx, pkg)) return "$label isn't on your allowed apps list, so I won't open it. Add it in Settings > Allowed apps."
        }
        wake(ctx)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "No app on the phone can handle that."
        } catch (e: Exception) {
            "Android blocked the launch (${e.message}). In Pragon, allow 'Display over other apps'."
        }
    }

    fun installed(ctx: Context, pkg: String): Boolean = try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }

    fun copy(ctx: Context, text: String): Boolean = try {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Pragon", text))
        true
    } catch (e: Exception) { false }
}

/** The PC's `calendar_check`: read what's on the phone's calendar, and open a pre-filled new event. */
object CalendarTool {
    private class Ev(val title: String, val begin: Long, val allDay: Boolean, val where: String)

    private fun query(ctx: Context, from: Long, to: Long): List<Ev> {
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, from)
        ContentUris.appendId(b, to)
        val out = mutableListOf<Ev>()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        ctx.contentResolver.query(b.build(), cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < 15) {
                out.add(Ev(c.getString(0)?.ifBlank { null } ?: "(no title)", c.getLong(1), c.getInt(2) == 1, c.getString(3) ?: ""))
            }
        }
        return out
    }

    private fun line(e: Ev, withDay: Boolean): String {
        val whenPart = when {
            e.allDay -> "all day"
            withDay -> TimeExpr.words(e.begin)
            else -> "at " + TimeExpr.clock(e.begin)
        }
        return "${e.title} $whenPart" + (if (e.where.isNotBlank()) " at ${e.where.take(40)}" else "")
    }

    /** For the morning brief: today's remaining events, or null when the calendar can't be read. */
    fun todayLine(ctx: Context): String? {
        if (!Perm.has(ctx, android.Manifest.permission.READ_CALENDAR)) return null
        return try {
            val now = System.currentTimeMillis()
            val ev = query(ctx, now, TimeExpr.startOfDay(1, now) - 1)
            if (ev.isEmpty()) "Nothing else on your calendar today."
            else "On your calendar today: " + ev.take(6).joinToString("; ") { line(it, false) } + "."
        } catch (e: Exception) { null }
    }

    fun act(ctx: Context, a: JSONObject, mode: PMode): ToolOut {
        val action = a.optString("action").lowercase().ifEmpty { "today" }
        if (action == "add" || action == "create") {
            if (mode == PMode.ASSISTANT) return ToolOut(false, "Assistant mode cannot open apps. Say master mode first.", false)
            val title = a.optString("title").trim()
            if (title.isEmpty()) return ToolOut(false, "What should the event be called?", false)
            val begin = TimeExpr.parse(a.optString("when")) ?: return ToolOut(false, "When should it start? Say something like 'tomorrow at 4 pm'.", false)
            val mins = a.optString("minutes").toIntOrNull()?.coerceIn(5, 24 * 60) ?: 60
            val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + mins * 60_000L)
            val err = Launch.start(ctx, i, "Calendar")
            return if (err == null) ToolOut(true, "The calendar is open with '$title' ${TimeExpr.words(begin)}. Tap save to add it.", true, "Calendar")
            else ToolOut(false, err, false)
        }
        Perm.need(ctx, android.Manifest.permission.READ_CALENDAR, "read your calendar")?.let { return ToolOut(false, it, false) }
        val now = System.currentTimeMillis()
        return try {
            when (action) {
                "today" -> {
                    val ev = query(ctx, now, TimeExpr.startOfDay(1, now) - 1)
                    ToolOut(true, if (ev.isEmpty()) "Nothing else on your calendar today."
                        else "Today: " + ev.joinToString("; ") { line(it, false) } + ".", false, "Calendar")
                }
                "tomorrow" -> {
                    val ev = query(ctx, TimeExpr.startOfDay(1, now), TimeExpr.startOfDay(2, now) - 1)
                    ToolOut(true, if (ev.isEmpty()) "Nothing on your calendar tomorrow."
                        else "Tomorrow: " + ev.joinToString("; ") { line(it, false) } + ".", false, "Calendar")
                }
                "upcoming", "week", "next" -> {
                    val days = a.optString("days").toIntOrNull()?.coerceIn(1, 30) ?: 7
                    val ev = query(ctx, now, now + days * 86_400_000L)
                    ToolOut(true, if (ev.isEmpty()) "Nothing on your calendar in the next $days days."
                        else "Coming up: " + ev.joinToString("; ") { line(it, true) } + ".", false, "Calendar")
                }
                else -> ToolOut(false, "Unknown calendar action. Use today, tomorrow, upcoming or add.", false)
            }
        } catch (e: SecurityException) {
            ToolOut(false, "Android refused calendar access. Allow it in Pragon's settings.", false)
        }
    }
}

/** Find people in the phone's contacts, then call or message them (the PC's `send_message`, made phone-shaped). */
object ContactsTool {
    class C(val name: String, val numbers: List<String>, val score: Int)

    private fun clean(n: String) = n.replace(Regex("[^0-9+]"), "")

    private fun raw(ctx: Context, q: String): MutableMap<String, MutableList<Pair<Int, String>>> {
        val m = LinkedHashMap<String, MutableList<Pair<Int, String>>>()
        ctx.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE),
            "${Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = clean(c.getString(1) ?: continue)
                if (num.length < 3) continue
                m.getOrPut(name) { mutableListOf() }.add(c.getInt(2) to num)
            }
        }
        return m
    }

    fun find(ctx: Context, query: String): List<C> {
        val q = query.trim()
        val m = raw(ctx, q)
        if (m.isEmpty()) {
            for (w in q.split(" ").filter { it.length >= 2 }) {
                for ((k, v) in raw(ctx, w)) m.getOrPut(k) { mutableListOf() }.addAll(v)
            }
        }
        val low = q.lowercase()
        return m.map { (name, nums) ->
            val n = name.lowercase()
            val score = when {
                n == low -> 0
                n.startsWith(low) -> 1
                n.contains(low) -> 2
                else -> 3
            }
            // mobile numbers first, and the same number saved in two formats only once
            val ordered = nums.sortedBy { if (it.first == Phone.TYPE_MOBILE) 0 else 1 }
            val seen = HashSet<String>()
            C(name, ordered.map { it.second }.filter { seen.add(it.takeLast(10)) }, score)
        }.sortedWith(compareBy({ it.score }, { it.name }))
    }

    private fun spoken(n: String): String = n.toCharArray().joinToString(" ")

    fun e164(ctx: Context, num: String): String {
        if (num.startsWith("+")) return num
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = (tm?.simCountryIso?.ifBlank { null } ?: tm?.networkCountryIso?.ifBlank { null } ?: Locale.getDefault().country).uppercase()
        return try { PhoneNumberUtils.formatNumberToE164(num, iso) } catch (e: Exception) { null } ?: num
    }

    private class Pick(val name: String, val number: String?, val problem: String?)

    private fun pick(ctx: Context, who: String): Pick {
        val digits = clean(who)
        if (who.isNotBlank() && digits.length >= 5 && who.count { it.isLetter() } == 0) return Pick(who.trim(), digits, null)
        Perm.need(ctx, android.Manifest.permission.READ_CONTACTS, "read your contacts")?.let { return Pick(who, null, it) }
        val list = try { find(ctx, who) } catch (e: SecurityException) { return Pick(who, null, "Android refused contacts access. Allow it in Pragon's settings.") }
        if (list.isEmpty()) return Pick(who, null, "I couldn't find '$who' in your contacts.")
        val top = list[0]
        val tied = list.filter { it.score == top.score }
        if (tied.size > 1 && tied.map { it.name }.distinct().size > 1)
            return Pick(who, null, "I found ${tied.take(3).joinToString(" and ") { it.name }}. Which one do you mean?")
        if (top.numbers.isEmpty()) return Pick(top.name, null, "${top.name} has no phone number saved.")
        return Pick(top.name, top.numbers[0], null)
    }

    fun act(ctx: Context, a: JSONObject, mode: PMode): ToolOut {
        val action = a.optString("action").lowercase().ifEmpty { "find" }
        val who = a.optString("name").trim()
        if (who.isEmpty()) return ToolOut(false, "Who do you mean?", false)
        if (action == "find" || action == "lookup" || action == "number") {
            val p = pick(ctx, who)
            if (p.problem != null) return ToolOut(false, p.problem, false)
            return ToolOut(true, "${p.name}: ${spoken(p.number ?: "")}", false, "Contacts")
        }
        if (mode == PMode.ASSISTANT) return ToolOut(false, "Assistant mode cannot open apps. Say master mode first.", false)
        val p = pick(ctx, who)
        if (p.problem != null || p.number == null) return ToolOut(false, p.problem ?: "No number found.", false)
        when (action) {
            "call", "dial" -> {
                val err = Launch.start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + p.number)), "The dialer")
                return if (err == null) ToolOut(true, "The dialer is ready with ${p.name}. Tap call to place it.", true, "Phone") else ToolOut(false, err, false)
            }
            "message", "text", "send", "sms", "whatsapp", "telegram" -> {
                val body = a.optString("text").trim()
                if (body.isEmpty()) return ToolOut(false, "What should the message say?", false)
                val ch = a.optString("channel").lowercase().ifEmpty { if (action in listOf("sms", "whatsapp", "telegram")) action else "sms" }
                val e = e164(ctx, p.number)
                val digits = e.removePrefix("+").filter { it.isDigit() }
                return when (ch) {
                    "whatsapp", "wa" -> {
                        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { Launch.installed(ctx, it) }
                            ?: return ToolOut(false, "WhatsApp isn't installed on this phone.", false)
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(body))).setPackage(pkg)
                        val err = Launch.start(ctx, i, "WhatsApp")
                        if (err == null) ToolOut(true, "WhatsApp is open with your message to ${p.name}. Tap send to deliver it.", true, "WhatsApp") else ToolOut(false, err, false)
                    }
                    "telegram", "tg" -> {
                        val pkg = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram").firstOrNull { Launch.installed(ctx, it) }
                            ?: return ToolOut(false, "Telegram isn't installed on this phone.", false)
                        Launch.copy(ctx, body)
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=$digits")).setPackage(pkg)
                        val err = Launch.start(ctx, i, "Telegram")
                        if (err == null) ToolOut(true, "Telegram is open on ${p.name}'s chat. Your message is copied: paste it and send.", true, "Telegram") else ToolOut(false, err, false)
                    }
                    else -> {
                        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$e")).putExtra("sms_body", body)
                        val err = Launch.start(ctx, i, "Messages")
                        if (err == null) ToolOut(true, "Messages is open with your text to ${p.name}. Tap send to deliver it.", true, "Messages") else ToolOut(false, err, false)
                    }
                }
            }
        }
        return ToolOut(false, "Unknown contacts action. Use find, call or message.", false)
    }
}
