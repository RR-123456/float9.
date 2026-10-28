package com.pragon.mobile

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Time phrases for voice. The model normally sends "in:<seconds>" or "yyyy-MM-dd HH:mm" (it knows the time from
 * the system prompt), but plain "7:30 pm", "tomorrow 8am" and "in 20 minutes" are understood too.
 */
object TimeExpr {
    private val UNIT = mapOf(
        "sec" to 1000L, "second" to 1000L, "min" to 60_000L, "minute" to 60_000L,
        "hour" to 3_600_000L, "hr" to 3_600_000L, "day" to 86_400_000L,
    )

    /** Epoch millis for [raw], or null when it can't be understood. A bare clock time that already passed means tomorrow. */
    fun parse(raw: String, now: Long = System.currentTimeMillis()): Long? {
        var s = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        Regex("^in:?\\s*(\\d+)$").find(s)?.let { return now + it.groupValues[1].toLong() * 1000 }
        Regex("^in (an?|half an?|\\d+(?:\\.\\d+)?) ?(sec|second|min|minute|hour|hr|day)s?$").find(s)?.let {
            val n = when (val w = it.groupValues[1]) {
                "a", "an" -> 1.0
                "half a", "half an" -> 0.5
                else -> w.toDouble()
            }
            return now + (n * UNIT.getValue(it.groupValues[2])).toLong()
        }
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})[ t](\\d{1,2}):(\\d{2})").find(s)?.let {
            val c = Calendar.getInstance()
            c.clear()
            c.set(it.groupValues[1].toInt(), it.groupValues[2].toInt() - 1, it.groupValues[3].toInt(),
                it.groupValues[4].toInt(), it.groupValues[5].toInt(), 0)
            return c.timeInMillis
        }
        var dayOffset: Int? = null
        if (s.startsWith("day after tomorrow")) { dayOffset = 2; s = s.removePrefix("day after tomorrow").trim() }
        else if (s.startsWith("tomorrow")) { dayOffset = 1; s = s.removePrefix("tomorrow").trim() }
        else if (s.startsWith("today")) { dayOffset = 0; s = s.removePrefix("today").trim() }
        s = s.removePrefix("at ").trim()
        var h: Int
        var min: Int
        if (s.isEmpty() && dayOffset != null) {
            h = 9; min = 0                                     // "tomorrow" alone: morning
        } else {
            val m = Regex("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?$").find(s) ?: return null
            h = m.groupValues[1].toInt()
            min = m.groupValues[2].ifEmpty { "0" }.toInt()
            val mer = m.groupValues[3].replace(".", "")
            if (mer == "pm" && h < 12) h += 12
            if (mer == "am" && h == 12) h = 0
        }
        if (h > 23 || min > 59) return null
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (dayOffset != null) c.add(Calendar.DAY_OF_YEAR, dayOffset)
        else if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun epochDay(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        return (ms + c.timeZone.getOffset(ms)) / 86_400_000L
    }

    fun clock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))

    /** "in 10 minutes", "today at 8:00 PM", "tomorrow at 9:00 AM", "Mon 27 Oct at 6:30 PM". */
    fun words(at: Long, now: Long = System.currentTimeMillis()): String {
        val mins = ((at - now) / 60_000L).toInt()
        if (mins in 0..89) return if (mins <= 1) "in a minute" else "in $mins minutes"
        val d = (epochDay(at) - epochDay(now)).toInt()
        return when (d) {
            0 -> "today at ${clock(at)}"
            1 -> "tomorrow at ${clock(at)}"
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(at)) + " at " + clock(at)
        }
    }

    fun startOfDay(offsetDays: Int, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, offsetDays)
        return c.timeInMillis
    }
}
