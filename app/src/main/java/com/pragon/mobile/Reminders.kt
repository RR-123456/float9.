package com.pragon.mobile

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shared notification helper for reminders and topic alerts. */
object Notes {
    fun post(c: Context, channel: String, channelName: String, title: String, text: String, id: Int) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(open)
            .build()
        try { nm.notify(id, n) } catch (e: Exception) { }
    }

    fun notificationsAllowed(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

/**
 * Reminders (the PC's `reminder` tool). They survive a reboot (BatteryBootReceiver calls [rearm]) and fire as a
 * notification even when Pragon is closed. Repeats: daily, weekdays, weekly.
 */
object Reminders {
    private const val FILE = "pragon_reminders"
    private const val CH = "pragon_reminders"

    class R(val id: Int, val text: String, var at: Long, val repeat: String)

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context): MutableList<R> {
        val out = mutableListOf<R>()
        try {
            val a = JSONArray(sp(c).getString("list", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(R(o.optInt("id"), o.optString("text"), o.optLong("at"), o.optString("repeat", "none")))
            }
        } catch (e: Exception) { }
        return out
    }

    @Synchronized
    private fun save(c: Context, l: List<R>) {
        val a = JSONArray()
        for (r in l) a.put(JSONObject().put("id", r.id).put("text", r.text).put("at", r.at).put("repeat", r.repeat))
        sp(c).edit().putString("list", a.toString()).apply()
    }

    @Synchronized
    private fun nextId(c: Context): Int {
        val n = sp(c).getInt("next", 1)
        sp(c).edit().putInt("next", n + 1).apply()
        return n
    }

    fun exactAllowed(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    private fun pending(c: Context, id: Int, create: Boolean): PendingIntent? {
        val i = Intent(c, ReminderReceiver::class.java).setAction("com.pragon.mobile.REMINDER").putExtra("id", id)
        val flags = PendingIntent.FLAG_IMMUTABLE or (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(c, 7000 + id, i, flags)
    }

    private fun arm(c: Context, r: R) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(c, r.id, true) ?: return
        try {
            if (exactAllowed(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        }
    }

    private fun disarm(c: Context, id: Int) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        pending(c, id, false)?.let { am.cancel(it); it.cancel() }
    }

    private fun nextAfter(at: Long, repeat: String, now: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = at
        var guard = 0
        do {
            when (repeat) {
                "weekly" -> c.add(Calendar.DAY_OF_YEAR, 7)
                "weekdays" -> {
                    c.add(Calendar.DAY_OF_YEAR, 1)
                    while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) c.add(Calendar.DAY_OF_YEAR, 1)
                }
                else -> c.add(Calendar.DAY_OF_YEAR, 1)
            }
            guard++
        } while (c.timeInMillis <= now && guard < 400)
        return c.timeInMillis
    }

    /** Called by the receiver when the time comes (or after a reboot for ones that were missed). */
    fun fire(c: Context, id: Int, missed: Boolean = false) {
        val list = load(c)
        val r = list.firstOrNull { it.id == id } ?: return
        Notes.post(c, CH, "Reminders", if (missed) "Missed reminder" else "Reminder", r.text, 9000 + r.id)
        if (r.repeat != "none") {
            r.at = nextAfter(r.at, r.repeat, System.currentTimeMillis())
            save(c, list)
            arm(c, r)
        } else {
            list.remove(r)
            save(c, list)
        }
    }

    /** After a reboot or an app update: put every alarm back, and show the ones that came due while the phone was off. */
    fun rearm(c: Context) {
        val now = System.currentTimeMillis()
        for (r in load(c)) {
            if (r.at <= now) fire(c, r.id, missed = true) else arm(c, r)
        }
    }

    private fun describe(r: R, now: Long): String =
        r.text + " " + TimeExpr.words(r.at, now) + (if (r.repeat != "none") ", repeating ${r.repeat}" else "")

    fun act(ctx: Context, a: JSONObject): ToolOut {
        val c = ctx.applicationContext
        val now = System.currentTimeMillis()
        when (a.optString("action").lowercase().ifEmpty { "list" }) {
            "add", "set", "create" -> {
                val text = a.optString("text").trim()
                if (text.isEmpty()) return ToolOut(false, "What should I remind you about?", false)
                val at = TimeExpr.parse(a.optString("when")) ?: return ToolOut(false, "I couldn't work out when. Say a time like 'in 20 minutes' or 'tomorrow at 8 am'.", false)
                if (at <= now) return ToolOut(false, "That time has already passed.", false)
                val rep = a.optString("repeat").lowercase().let { if (it in listOf("daily", "weekdays", "weekly")) it else "none" }
                val r = R(nextId(c), text.take(200), at, rep)
                val list = load(c)
                if (list.size >= 100) return ToolOut(false, "You already have 100 reminders. Clear some first.", false)
                list.add(r)
                save(c, list)
                arm(c, r)
                var note = ""
                if (!Notes.notificationsAllowed(c)) {
                    Perm.request(c, android.Manifest.permission.POST_NOTIFICATIONS)
                    note += " Allow notifications on the phone or the reminder will not show."
                }
                if (!exactAllowed(c)) note += " For exact timing, allow 'Alarms and reminders' for Pragon in Settings; until then it may be a few minutes late."
                return ToolOut(true, "Reminder set: ${describe(r, now)}.$note", false, "Reminders")
            }
            "list", "show" -> {
                val l = load(c).sortedBy { it.at }
                if (l.isEmpty()) return ToolOut(true, "You have no reminders.", false, "Reminders")
                val body = l.take(8).joinToString("; ") { describe(it, now) }
                val more = if (l.size > 8) " And ${l.size - 8} more." else ""
                return ToolOut(true, "You have ${l.size} reminder${if (l.size == 1) "" else "s"}: $body.$more", false, "Reminders")
            }
            "cancel", "delete", "remove", "done" -> {
                val key = a.optString("text").trim().lowercase()
                val list = load(c)
                val hit = if (key == "all" || key == "everything") list.toList()
                    else if (key.isEmpty()) listOf()
                    else list.filter { it.text.lowercase().contains(key) || key.contains(it.text.lowercase()) }
                if (hit.isEmpty()) return ToolOut(false, if (key.isEmpty()) "Which reminder? Say part of what it says, or 'all'." else "I couldn't find a reminder matching '$key'.", false)
                for (r in hit) { disarm(c, r.id); list.remove(r) }
                save(c, list)
                return ToolOut(true, if (hit.size == 1) "Cancelled: ${hit[0].text}." else "Cancelled ${hit.size} reminders.", false, "Reminders")
            }
            "allow_exact" -> {
                if (exactAllowed(c)) return ToolOut(true, "Exact reminder timing is already allowed.", false)
                if (Build.VERSION.SDK_INT >= 31) {
                    val i = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(android.net.Uri.parse("package:${c.packageName}"))
                    val err = Launch.start(c, i, "Settings")
                    return if (err == null) ToolOut(true, "Opened the 'Alarms and reminders' setting. Switch Pragon on.", true) else ToolOut(false, err, false)
                }
                return ToolOut(true, "Exact timing needs no extra permission on this Android version.", false)
            }
        }
        return ToolOut(false, "Unknown reminder action. Use add, list or cancel.", false)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (id < 0) return
        try { Reminders.fire(context.applicationContext, id) } catch (e: Exception) { }
    }
}

/** To-do list (the PC's `todo_list`). Plain local list, nothing leaves the phone. */
object Todos {
    private const val FILE = "pragon_todos"

    class T(val id: Int, val text: String, var done: Boolean)

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context): MutableList<T> {
        val out = mutableListOf<T>()
        try {
            val a = JSONArray(sp(c).getString("list", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(T(o.optInt("id"), o.optString("text"), o.optBoolean("done")))
            }
        } catch (e: Exception) { }
        return out
    }

    @Synchronized
    private fun save(c: Context, l: List<T>) {
        val a = JSONArray()
        for (t in l) a.put(JSONObject().put("id", t.id).put("text", t.text).put("done", t.done))
        sp(c).edit().putString("list", a.toString()).apply()
    }

    fun open(c: Context): List<String> = load(c).filter { !it.done }.map { it.text }

    private fun pick(list: List<T>, key: String): T? {
        val k = key.trim().lowercase()
        if (k.isEmpty()) return null
        k.toIntOrNull()?.let { n -> val open = list.filter { !it.done }; if (n in 1..open.size) return open[n - 1] }
        return list.firstOrNull { it.text.lowercase().contains(k) } ?: list.firstOrNull { k.contains(it.text.lowercase()) }
    }

    fun act(ctx: Context, a: JSONObject): ToolOut {
        val c = ctx.applicationContext
        val list = load(c)
        when (a.optString("action").lowercase().ifEmpty { "list" }) {
            "add", "create" -> {
                val items = a.optString("text").split(";", "\n").map { it.trim() }.filter { it.isNotEmpty() }
                if (items.isEmpty()) return ToolOut(false, "What should I add to the list?", false)
                if (list.size + items.size > 200) return ToolOut(false, "The list is full. Clear the finished items first.", false)
                var id = (list.maxOfOrNull { it.id } ?: 0) + 1
                for (t in items) list.add(T(id++, t.take(200), false))
                save(c, list)
                return ToolOut(true, if (items.size == 1) "Added to your list: ${items[0]}." else "Added ${items.size} items to your list.", false, "To-do")
            }
            "list", "show" -> {
                val open = list.filter { !it.done }
                if (open.isEmpty()) return ToolOut(true, "Your to-do list is empty.", false, "To-do")
                val body = open.take(10).mapIndexed { i, t -> "${i + 1}, ${t.text}" }.joinToString("; ")
                val more = if (open.size > 10) " And ${open.size - 10} more." else ""
                return ToolOut(true, "You have ${open.size} thing${if (open.size == 1) "" else "s"} to do: $body.$more", false, "To-do")
            }
            "done", "complete", "finish" -> {
                val t = pick(list, a.optString("text")) ?: return ToolOut(false, "I couldn't find that on your list.", false)
                t.done = true
                save(c, list)
                return ToolOut(true, "Ticked off: ${t.text}.", false, "To-do")
            }
            "remove", "delete" -> {
                val t = pick(list, a.optString("text")) ?: return ToolOut(false, "I couldn't find that on your list.", false)
                list.remove(t)
                save(c, list)
                return ToolOut(true, "Removed: ${t.text}.", false, "To-do")
            }
            "clear_done", "clear" -> {
                val n = list.count { it.done }
                list.removeAll { it.done }
                save(c, list)
                return ToolOut(true, if (n == 0) "Nothing was finished yet." else "Cleared $n finished item${if (n == 1) "" else "s"}.", false, "To-do")
            }
        }
        return ToolOut(false, "Unknown to-do action. Use add, list, done, remove or clear_done.", false)
    }
}
