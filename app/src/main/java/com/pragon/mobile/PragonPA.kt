package com.pragon.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Who the PA is answering for, handed to the Live session. */
class PaCall(val callerLabel: String, val owner: String)

/**
 * PRAGON PA: attends your calls, with your approval.
 *
 *   ring  ->  Pragon asks you (voice "OK" / "ignore", or the notification buttons)
 *   OK    ->  answers, puts the call on the loudspeaker, talks to the caller through Gemini Live,
 *             finds out who and why, then hangs up politely and tells you the summary
 *   you can TAKE OVER at any moment (notification button, tap the bubble, or say "Pragon takeover"):
 *             Pragon goes silent and you carry on the call yourself.
 *
 * Honest limits (Android): apps cannot read or inject the call's audio. Pragon hears the caller from the
 * loudspeaker through the phone's microphone and talks to the caller the same way (speaker -> mic), one party at a
 * time. It works best in a quiet room with the phone lying flat. Android keeps the microphone alive during a call
 * for apps with an enabled accessibility service, which Pragon already is.
 */
object PragonPA {
    enum class St { IDLE, ASKING, ATTENDING, TAKEN_OVER }

    const val CHANNEL = "pragon_pa"
    const val NOTIF_ASK = 4101
    const val NOTIF_LIVE = 4102
    const val NOTIF_REPORT = 4103
    const val ACT_ATTEND = "com.pragon.mobile.PA_ATTEND"
    const val ACT_IGNORE = "com.pragon.mobile.PA_IGNORE"
    const val ACT_TAKEOVER = "com.pragon.mobile.PA_TAKEOVER"
    private const val ASK_SECONDS = 25L

    @Volatile var state = St.IDLE
        private set
    val asking: Boolean get() = state == St.ASKING
    val attending: Boolean get() = state == St.ATTENDING

    private val main = Handler(Looper.getMainLooper())
    private var callerNumber: String? = null
    private var callerName: String = "an unknown number"
    @Volatile private var offHook = false
    private var session: LiveSession? = null
    private val transcript = StringBuilder()
    private val notes = mutableListOf<String>()
    private var reported = false
    private var askTimeout: Runnable? = null

    private fun sp(c: Context) = c.getSharedPreferences("pragon_pa", Context.MODE_PRIVATE)
    fun enabled(c: Context) = sp(c).getBoolean("on", false)
    private fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("on", v).apply()

    // ── the tool: on / off / status / history ─────────────────────────────

    private fun needed(): List<String> {
        val l = mutableListOf(
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
        return l
    }

    fun missing(c: Context): List<String> = needed().filterNot { Perm.has(c, it) }

    fun tool(ctx: Context, a: JSONObject): ToolOut {
        val call = Prefs.callMe(ctx)
        return when (a.optString("action").lowercase()) {
            "on", "enable", "start" -> {
                val miss = missing(ctx)
                if (miss.isNotEmpty()) {
                    Perm.request(ctx, *miss.toTypedArray())
                    return ToolOut(false, "I need phone, call-log and microphone permission to attend calls. A prompt opened on the phone: allow it, then ask me again.", false)
                }
                if (Prefs.effectiveAiKey(ctx).isBlank())
                    return ToolOut(false, "Pragon PA talks through Pragon Live, so I need a Pragon key first. Add it in Settings.", false)
                setEnabled(ctx, true)
                val extra = if (PragonAccessibilityService.instance == null)
                    " Turn on Pragon's accessibility service too, or Android may silence my microphone during calls." else ""
                ToolOut(true, "Pragon PA is on, $call. When a call comes in I'll ask you first, and I only answer if you say OK.$extra", false, "Pragon PA")
            }
            "off", "disable", "stop" -> { setEnabled(ctx, false); ToolOut(true, "Pragon PA is off. Calls ring as normal.", false, "Pragon PA") }
            "history", "summary", "last" -> {
                val h = history(ctx)
                if (h.length() == 0) ToolOut(true, "I haven't attended any calls yet.", false, "Pragon PA")
                else {
                    val n = minOf(3, h.length())
                    ToolOut(true, (0 until n).joinToString(" ") { i ->
                        val o = h.getJSONObject(h.length() - 1 - i); "${o.optString("when")}: ${o.optString("who")}. ${o.optString("summary")}"
                    }, false, "Pragon PA")
                }
            }
            else -> ToolOut(true,
                if (enabled(ctx)) "Pragon PA is on." + (if (missing(ctx).isNotEmpty()) " Some permissions are still missing, so say 'turn on Pragon PA' to allow them." else "")
                else "Pragon PA is off. Say 'turn on Pragon PA' to start.", false, "Pragon PA")
        }
    }

    private fun history(c: Context): JSONArray = try { JSONArray(sp(c).getString("log", "[]")) } catch (e: Exception) { JSONArray() }

    private fun logCall(c: Context, who: String, summary: String) {
        val h = history(c)
        h.put(JSONObject().put("when", SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date())).put("who", who).put("summary", summary))
        val keep = JSONArray()
        for (i in maxOf(0, h.length() - 20) until h.length()) keep.put(h.get(i))
        sp(c).edit().putString("log", keep.toString()).apply()
    }

    // ── phone state (from CallStateReceiver) ──────────────────────────────

    private fun lookupName(c: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        PragonContacts.byNumber(c, number)?.let { return it.name }
        if (!Perm.has(c, Manifest.permission.READ_CONTACTS)) return null
        return try {
            c.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) { null }
    }

    fun onRinging(ctx: Context, number: String?) {
        if (!enabled(ctx)) return
        if (state == St.ATTENDING || state == St.TAKEN_OVER) return
        val first = state != St.ASKING
        if (!number.isNullOrBlank()) callerNumber = number
        else if (first) callerNumber = null
        callerName = lookupName(ctx, callerNumber) ?: callerNumber ?: "an unknown number"
        state = St.ASKING
        offHook = false
        postAsk(ctx)
        if (!first) return
        FloatService.beginPaApproval()
        val who = if (callerNumber == null && callerName == "an unknown number") "a number I can't see" else callerName
        Speaker.get(ctx).speak("Call from $who, ${Prefs.callMe(ctx)}. Shall I attend it? Say OK, or ignore.")
        askTimeout?.let { main.removeCallbacks(it) }
        val t = Runnable {
            if (state == St.ASKING) {
                state = St.IDLE
                cancel(ctx, NOTIF_ASK)
                FloatService.endPaApproval()
            }
        }
        askTimeout = t
        main.postDelayed(t, ASK_SECONDS * 1000)
    }

    fun onOffHook(ctx: Context) { offHook = true }

    fun onIdle(ctx: Context) {
        askTimeout?.let { main.removeCallbacks(it) }
        when (state) {
            St.IDLE -> return
            St.ASKING -> { state = St.IDLE; cancel(ctx, NOTIF_ASK); FloatService.endPaApproval(); return }
            St.ATTENDING -> finishAfterHangup(ctx)      // the caller hung up first
            St.TAKEN_OVER -> cleanup(ctx)
        }
    }

    // ── your decision ─────────────────────────────────────────────────────

    private val YES = Regex("\\b(ok|okay|yes|yeah|yep|sure|attend|answer|pick( it)? up|aama|aamam|haan|sari|seri|pannu|edu)\\b")
    private val REJECT = Regex("\\b(reject|cut|decline|disconnect|hang up)\\b")
    private val NO = Regex("\\b(no|nope|ignore|leave it|don'?t|vendam|venam|venda|illa|illai)\\b")

    /** Called by float mode with what it heard while a call is waiting. Always consumes the phrase. */
    fun heard(ctx: Context, alts: List<String>): Boolean {
        if (state != St.ASKING) return false
        val t = alts.joinToString(" | ").lowercase()
        when {
            REJECT.containsMatchIn(t) -> decline(ctx, hangUp = true)
            NO.containsMatchIn(t) -> decline(ctx, hangUp = false)
            YES.containsMatchIn(t) -> approve(ctx)
        }
        return true
    }

    fun approve(ctx: Context) {
        if (state != St.ASKING) return
        askTimeout?.let { main.removeCallbacks(it) }
        try {
            ctx.startForegroundService(Intent(ctx, PaService::class.java).setAction(ACT_ATTEND))
        } catch (e: Exception) {
            state = St.IDLE
            FloatService.endPaApproval()
            note(ctx, "Pragon PA", "Android wouldn't let me start in the background. Tap 'Attend with Pragon' on the call notification.")
            Speaker.get(ctx).speak("Android wouldn't let me start from here. Tap Attend on the notification.")
        }
    }

    fun decline(ctx: Context, hangUp: Boolean) {
        if (state != St.ASKING) return
        askTimeout?.let { main.removeCallbacks(it) }
        state = St.IDLE
        cancel(ctx, NOTIF_ASK)
        if (hangUp) endCall(ctx)
        FloatService.endPaApproval()
        Speaker.get(ctx).speak(if (hangUp) "Call rejected." else "Okay, I'll leave it ringing.")
    }

    // ── the session ───────────────────────────────────────────────────────

    private fun telecom(c: Context) = c.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    @Suppress("MissingPermission")
    fun endCall(c: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 28) telecom(c).endCall() else false
    } catch (e: Exception) { false }

    /** Runs on the PaService after it is in the foreground: answer, route to the loudspeaker, start the talk. */
    fun runSession(svc: PaService) {
        val ctx = svc.applicationContext
        if (state != St.ASKING) { svc.stopSelf(); return }
        // Check everything BEFORE answering, so a missing key never leaves the caller on a dead line.
        val problem = when {
            missing(ctx).any { it == Manifest.permission.ANSWER_PHONE_CALLS || it == Manifest.permission.RECORD_AUDIO } -> "I'm missing the phone or microphone permission."
            Prefs.effectiveAiKey(ctx).isBlank() -> "I need a Pragon key to talk to callers."
            Guard.checkHost(ctx, LiveSession.HOST) != null -> "Privacy Shield is blocking Pragon, so I can't talk to callers."
            else -> null
        }
        if (problem != null) {
            state = St.IDLE; cancel(ctx, NOTIF_ASK)
            note(ctx, "Pragon PA can't attend", "$problem The call is still ringing.")
            Speaker.get(ctx).speak("$problem The call is still ringing, ${Prefs.callMe(ctx)}.")
            FloatService.endPaApproval(); svc.stopSelf(); return
        }
        state = St.ATTENDING
        reported = false
        transcript.setLength(0); notes.clear()
        cancel(ctx, NOTIF_ASK)
        FloatService.pauseForExternalMic(true)
        Speaker.get(ctx).stop()
        Thread {
            var ok = false
            try {
                @Suppress("MissingPermission")
                telecom(ctx).acceptRingingCall()
                var waited = 0
                while (!offHook && waited < 5000) { Thread.sleep(100); waited += 100 }
                ok = offHook
            } catch (e: Exception) { }
            main.post {
                if (!ok || state != St.ATTENDING) {
                    if (state == St.ATTENDING) {
                        state = St.IDLE
                        note(ctx, "Pragon PA", "I couldn't answer that call. It may have stopped ringing.")
                        cleanup(ctx)
                    }
                    return@post
                }
                AudioRoute.callOnSpeaker(ctx)
                showLive(ctx)
                startTalking(ctx)
            }
        }.start()
    }

    private fun startTalking(ctx: Context) {
        val owner = Prefs.callMe(ctx)
        val s = LiveSession(ctx, object : LiveSession.Listener {
            override fun onState(st: LiveSession.State) {}
            override fun onUserText(text: String) { if (text.isNotBlank()) synchronized(transcript) { transcript.append("Caller or owner: ").append(text.trim()).append('\n') } }
            override fun onPragonText(text: String) { if (text.isNotBlank()) synchronized(transcript) { transcript.append("Pragon: ").append(text.trim()).append('\n') } }
            override fun onError(message: String) {
                main.post {
                    if (state != St.ATTENDING) return@post
                    note(ctx, "Pragon PA stopped - you have the call", message)
                    takeover(ctx, spoken = false)
                }
            }
            override fun onShutdownRequested() {}
            override fun onMuteRequested() {}
        })
        s.pa = PaCall(callerName, owner)
        session = s
        s.start("The call has just connected. Greet the caller now, say who you are and that you are answering for $owner, and ask who is calling and what it is about.")
    }

    // called from LiveSession's PA tools (any thread)
    fun paNote(text: String) { if (text.isNotBlank()) synchronized(notes) { notes.add(text.trim()) } }

    fun paEnd(ctx: Context, summary: String) {
        main.post {
            if (state != St.ATTENDING) return@post
            reported = true
            val sum = summary.ifBlank { notes.joinToString(" ").ifBlank { "The caller didn't say much." } }
            endCall(ctx)
            session?.stop(); session = null
            AudioRoute.callRestore(ctx)
            report(ctx, sum)
            state = St.TAKEN_OVER      // call is over; onIdle will clean up. (no more talking)
            main.postDelayed({ if (state == St.TAKEN_OVER) cleanup(ctx) }, 3000)
        }
    }

    /** You take the call over: Pragon goes silent, audio goes back to normal, the call stays connected. */
    fun takeover(ctx: Context, spoken: Boolean = true) {
        main.post {
            if (state != St.ATTENDING) return@post
            state = St.TAKEN_OVER
            session?.stop(); session = null
            AudioRoute.callRestore(ctx)
            val sofar = notes.joinToString(" ").ifBlank { "Nothing noted yet." }
            cancel(ctx, NOTIF_LIVE)
            note(ctx, "You have the call", "Caller: $callerName. So far: $sofar")
            FloatService.pauseForExternalMic(true)    // stays paused until the call ends
        }
    }

    private fun finishAfterHangup(ctx: Context) {
        val had = synchronized(transcript) { transcript.toString() }
        val sofar = synchronized(notes) { notes.joinToString(" ") }
        session?.stop(); session = null
        AudioRoute.callRestore(ctx)
        state = St.TAKEN_OVER
        if (reported) { cleanup(ctx); return }
        reported = true
        Thread {
            val s = summarize(ctx, had, sofar)
            main.post { report(ctx, s); cleanup(ctx) }
        }.start()
    }

    /** One-shot summary of the transcript. The chat history is put back afterwards so this call never leaks into it. */
    private fun summarize(ctx: Context, transcript: String, notes: String): String {
        val fallback = notes.ifBlank { "The caller hung up before saying much." }
        if (transcript.isBlank() || Prefs.effectiveAiKey(ctx).isBlank() || Prefs.engine(ctx) != "gemini") return fallback
        val saved = synchronized(Conversation) { Conversation.gemini.toList() }
        return try {
            val prompt = "Summarize this phone call in at most three short spoken sentences: who called, what they want, how urgent, and any callback number or message. " +
                "Flag it if it sounds like a scam. Transcript:\n" + transcript.takeLast(6000)
            val r = GeminiClient.chat(ctx, prompt, emptyList(), PMode.ASSISTANT, 300, object : ChatSink {
                override fun onText(delta: String) {}
                override fun onSentence(sentence: String) {}
            })
            if (r.error || r.text.isBlank()) fallback else r.text.trim()
        } catch (e: Exception) { fallback } finally {
            synchronized(Conversation) { Conversation.gemini.clear(); Conversation.gemini.addAll(saved) }
        }
    }

    private fun report(ctx: Context, summary: String) {
        logCall(ctx, callerName, summary)
        note(ctx, "Call summary: $callerName", summary, big = true)
        main.postDelayed({ Speaker.get(ctx).speak("Call summary, ${Prefs.callMe(ctx)}. From $callerName. $summary") }, 1500)
    }

    private fun cleanup(ctx: Context) {
        askTimeout?.let { main.removeCallbacks(it) }
        session?.stop(); session = null
        AudioRoute.callRestore(ctx)
        cancel(ctx, NOTIF_ASK); cancel(ctx, NOTIF_LIVE)
        state = St.IDLE
        offHook = false
        FloatService.pauseForExternalMic(false)
        FloatService.endPaApproval()
        try { ctx.stopService(Intent(ctx, PaService::class.java)) } catch (e: Exception) { }
    }

    // ── notifications ─────────────────────────────────────────────────────

    private fun nm(c: Context): NotificationManager {
        val m = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && m.getNotificationChannel(CHANNEL) == null)
            m.createNotificationChannel(NotificationChannel(CHANNEL, "Pragon PA (calls)", NotificationManager.IMPORTANCE_HIGH))
        return m
    }

    private fun cancel(c: Context, id: Int) { try { nm(c).cancel(id) } catch (e: Exception) { } }

    private fun act(c: Context, code: Int, action: String, foreground: Boolean = false): PendingIntent {
        val fl = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (foreground) PendingIntent.getForegroundService(c, code, Intent(c, PaService::class.java).setAction(action), fl)
        else PendingIntent.getBroadcast(c, code, Intent(c, PaActionReceiver::class.java).setAction(action), fl)
    }

    private fun postAsk(c: Context) {
        try {
            val n = Notification.Builder(c, CHANNEL)
                .setContentTitle("Call from $callerName")
                .setContentText("Let Pragon attend it? Say OK, or tap.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setCategory(Notification.CATEGORY_CALL)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_call, "Attend with Pragon", act(c, 1, ACT_ATTEND, foreground = true)).build())
                .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Ignore", act(c, 2, ACT_IGNORE)).build())
                .setTimeoutAfter(ASK_SECONDS * 1000)
                .build()
            nm(c).notify(NOTIF_ASK, n)
        } catch (e: Exception) { }
    }

    internal fun liveNotification(c: Context): Notification {
        nm(c)
        return Notification.Builder(c, CHANNEL)
            .setContentTitle("Pragon is on the call with $callerName")
            .setContentText("Tap TAKE OVER to talk yourself.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_call, "TAKE OVER", act(c, 3, ACT_TAKEOVER)).build())
            .build()
    }

    private fun showLive(c: Context) { try { nm(c).notify(NOTIF_LIVE, liveNotification(c)) } catch (e: Exception) { } }

    private fun note(c: Context, title: String, text: String, big: Boolean = false) {
        try {
            val b = Notification.Builder(c, CHANNEL).setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setAutoCancel(true)
            if (big) b.setStyle(Notification.BigTextStyle().bigText(text))
            nm(c).notify(NOTIF_REPORT, b.build())
        } catch (e: Exception) { }
    }
}

/** Keeps Pragon alive and allowed to use the microphone while it talks on a call. */
class PaService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PragonPA.ACT_ATTEND) {
            try {
                val n = PragonPA.liveNotification(this)
                if (Build.VERSION.SDK_INT >= 29) startForeground(PragonPA.NOTIF_LIVE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else startForeground(PragonPA.NOTIF_LIVE, n)
            } catch (e: Throwable) {
                Speaker.get(this).speak("Android wouldn't let me use the microphone from here. Please take the call yourself.")
                stopSelf(); return START_NOT_STICKY
            }
            PragonPA.runSession(this)
        } else stopSelf()
        return START_NOT_STICKY
    }
}

/** Notification buttons: Ignore and Take over. */
class PaActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            PragonPA.ACT_IGNORE -> PragonPA.decline(c, hangUp = false)
            PragonPA.ACT_TAKEOVER -> PragonPA.takeover(c)
        }
    }
}

/** Phone state: ringing, answered, hung up. Only does anything when Pragon PA is switched on. */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!PragonPA.enabled(c)) return
        when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> @Suppress("DEPRECATION") PragonPA.onRinging(c, i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
            TelephonyManager.EXTRA_STATE_OFFHOOK -> PragonPA.onOffHook(c)
            TelephonyManager.EXTRA_STATE_IDLE -> PragonPA.onIdle(c)
        }
    }
}
