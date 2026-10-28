import com.pragon.mobile.TimeExpr
import java.util.Calendar

fun main() {
    var fail = 0
    // Sunday 25 Oct 2026, 15:00 local
    val c = Calendar.getInstance(); c.clear(); c.set(2026, Calendar.OCTOBER, 25, 15, 0, 0)
    val now = c.timeInMillis
    fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long { val x = Calendar.getInstance(); x.clear(); x.set(y, mo, d, h, mi, 0); return x.timeInMillis }
    fun chk(inp: String, want: Long?) {
        val got = TimeExpr.parse(inp, now)
        if (got != want) { fail++; println("FAIL '$inp' -> $got (want $want)") }
    }
    chk("in:1200", now + 1_200_000L)
    chk("in 20 minutes", now + 1_200_000L)
    chk("in an hour", now + 3_600_000L)
    chk("in half an hour", now + 1_800_000L)
    chk("in 1.5 hours", now + 5_400_000L)
    chk("in 2 days", now + 172_800_000L)
    chk("2026-10-26 18:30", at(2026, Calendar.OCTOBER, 26, 18, 30))
    chk("2026-10-26T07:05", at(2026, Calendar.OCTOBER, 26, 7, 5))
    chk("7:30 pm", at(2026, Calendar.OCTOBER, 25, 19, 30))
    chk("7 am", at(2026, Calendar.OCTOBER, 26, 7, 0))          // already past today -> tomorrow
    chk("19:45", at(2026, Calendar.OCTOBER, 25, 19, 45))
    chk("12 am", at(2026, Calendar.OCTOBER, 26, 0, 0))
    chk("12 pm", at(2026, Calendar.OCTOBER, 26, 12, 0))        // 12:00 today is before 15:00 -> tomorrow
    chk("tomorrow at 8 am", at(2026, Calendar.OCTOBER, 26, 8, 0))
    chk("Tomorrow 8:15 PM", at(2026, Calendar.OCTOBER, 26, 20, 15))
    chk("tomorrow", at(2026, Calendar.OCTOBER, 26, 9, 0))
    chk("day after tomorrow 9:15", at(2026, Calendar.OCTOBER, 27, 9, 15))
    chk("today 5pm", at(2026, Calendar.OCTOBER, 25, 17, 0))
    chk("at 6 p.m.", at(2026, Calendar.OCTOBER, 25, 18, 0))
    chk("", null)
    chk("whenever", null)
    chk("25:00", null)
    chk("7:75", null)

    fun w(ms: Long, want: String) { val g = TimeExpr.words(ms, now); if (g != want) { fail++; println("FAIL words -> '$g' (want '$want')") } }
    w(now + 10 * 60_000L, "in 10 minutes")
    w(now + 30_000L, "in a minute")
    w(at(2026, Calendar.OCTOBER, 25, 19, 30), "today at 7:30 PM")
    w(at(2026, Calendar.OCTOBER, 26, 9, 0), "tomorrow at 9:00 AM")
    w(at(2026, Calendar.OCTOBER, 28, 18, 30), "Wed 28 Oct at 6:30 PM")
    // day boundary: 23:50 now, reminder 00:20 is "tomorrow", not "in 30 minutes"-style confusion
    val late = at(2026, Calendar.OCTOBER, 25, 23, 50)
    val g2 = TimeExpr.words(at(2026, Calendar.OCTOBER, 26, 0, 20), late)
    if (g2 != "in 30 minutes") { fail++; println("FAIL late -> $g2") }
    val g3 = TimeExpr.words(at(2026, Calendar.OCTOBER, 26, 7, 0), late)
    if (g3 != "tomorrow at 7:00 AM") { fail++; println("FAIL late2 -> $g3") }
    check(TimeExpr.startOfDay(1, now) == at(2026, Calendar.OCTOBER, 26, 0, 0)) { "startOfDay" }
    println(if (fail == 0) "TimeExpr: all passed" else "TimeExpr: $fail FAILED")
    if (fail != 0) System.exit(1)
}
