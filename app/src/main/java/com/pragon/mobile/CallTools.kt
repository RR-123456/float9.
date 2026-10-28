package com.pragon.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pragon contacts: a short list you keep inside Pragon. Voice calling by NAME only works for people on this list
 * (so a mis-heard name can never ring a stranger). A number you say yourself is dialled as spoken.
 */
object PragonContacts {
    class P(val name: String, val number: String)

    private fun sp(c: Context) = c.getSharedPreferences("pragon_contacts", Context.MODE_PRIVATE)

    fun digits(s: String): String {
        val plus = s.trim().startsWith("+")
        val d = s.filter { it.isDigit() }
        return if (plus) "+$d" else d
    }

    fun all(c: Context): List<P> {
        val arr = try { JSONArray(sp(c).getString("list", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val n = o.optString("name"); val num = o.optString("number")
            if (n.isBlank() || num.isBlank()) null else P(n, num)
        }
    }

    private fun save(c: Context, list: List<P>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("number", it.number)) }
        sp(c).edit().putString("list", arr.toString()).apply()
    }

    /** Adds, or updates the number when the name is already there. Returns an error sentence, or null. */
    fun add(c: Context, name: String, number: String): String? {
        val n = name.trim()
        val d = digits(number)
        if (n.isEmpty()) return "Whose number is it?"
        if (d.filter { it.isDigit() }.length < 3) return "That doesn't look like a phone number."
        val list = all(c).filterNot { it.name.equals(n, true) } + P(n, d)
        save(c, list.sortedBy { it.name.lowercase() })
        return null
    }

    fun remove(c: Context, name: String): Boolean {
        val list = all(c)
        val keep = list.filterNot { it.name.equals(name.trim(), true) }
        if (keep.size == list.size) return false
        save(c, keep); return true
    }

    /** Best match for a spoken name: exact, then starts-with, then contains. Several equal matches = ambiguous. */
    fun find(c: Context, query: String): List<P> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val l = all(c)
        l.filter { it.name.lowercase() == q }.let { if (it.isNotEmpty()) return it }
        l.filter { it.name.lowercase().startsWith(q) || it.name.lowercase().split(" ").any { w -> w == q } }.let { if (it.isNotEmpty()) return it }
        return l.filter { it.name.lowercase().contains(q) }
    }

    fun byNumber(c: Context, number: String?): P? {
        val d = digits(number ?: "").filter { it.isDigit() }
        if (d.length < 6) return null
        return all(c).firstOrNull { digits(it.number).filter { ch -> ch.isDigit() }.takeLast(10) == d.takeLast(10) }
    }
}

/** Tools: pragon_contacts (manage the list), pragon_call (place a call), pragon_pa (call-attending assistant), audio_route. */
object CallTools {
    val NAMES = setOf("pragon_contacts", "pragon_call", "pragon_pa", "audio_route")
    /** These touch the phone's calls, so Assistant mode (talk only) does not get them. */
    val ACTING = setOf("pragon_call", "pragon_pa")

    private fun fn(upper: Boolean, name: String, desc: String, required: List<String>, vararg p: Pair<String, String>): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        val props = JSONObject()
        for ((k, v) in p) props.put(k, JSONObject().put("type", if (upper) "STRING" else "string").put("description", v))
        val params = JSONObject().put("type", if (upper) "OBJECT" else "object").put("properties", props)
        if (required.isNotEmpty()) params.put("required", JSONArray(required))
        return o.put("parameters", params)
    }

    fun decls(mode: PMode, upper: Boolean): List<JSONObject> = listOf(
        fn(upper, "pragon_contacts",
            "The Pragon contacts list: people the user can call by name. add: name + number (the user says the digits), or name only to copy that person's number from the phone's contacts. remove: name. list.",
            listOf("action"),
            "action" to "add, remove or list",
            "name" to "The person's name.",
            "number" to "Digits as spoken, e.g. 9876543210 or +919876543210. Leave empty to copy it from the phone's contacts."),
        fn(upper, "pragon_call",
            "Place a phone call right now. By name: only people in the Pragon contacts list can be called by name. By number: any number the user says, dialled exactly as spoken. Never guess a number.",
            emptyList(),
            "name" to "A person in the Pragon contacts list.",
            "number" to "A phone number the user said, digits only (a leading + is fine)."),
        fn(upper, "pragon_pa",
            "Pragon PA: when ON, Pragon asks the user first whenever a call comes in, and only if the user approves does it answer, talk to the caller, find out who it is and why they are calling, and then summarize it to the user. The user can take over at any moment. on / off / status / history (last call summaries).",
            listOf("action"),
            "action" to "on, off, status or history"),
        fn(upper, "audio_route",
            "Where Pragon's voice plays. speaker (default) or earpiece (the normal phone-call speaker). A connected AirPods / Bluetooth headset is used automatically. status tells what is in use. btmic_on / btmic_off: use or ignore the headset's microphone.",
            listOf("action"),
            "action" to "speaker, earpiece, status, btmic_on or btmic_off"),
    )

    fun run(ctx: Context, name: String, a: JSONObject, mode: PMode): ToolOut = when (name) {
        "pragon_contacts" -> contacts(ctx, a)
        "pragon_call" -> call(ctx, a, mode)
        "pragon_pa" -> PragonPA.tool(ctx, a)
        "audio_route" -> route(ctx, a)
        else -> ToolOut(false, "Unknown tool '$name'.", false)
    }

    // ── audio route ──────────────────────────────────────────────────────

    private fun route(ctx: Context, a: JSONObject): ToolOut {
        if (Build.VERSION.SDK_INT >= 31 && !Perm.has(ctx, Manifest.permission.BLUETOOTH_CONNECT)) Perm.request(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        val msg = when (a.optString("action").lowercase()) {
            "speaker" -> { AudioRoute.setOutput(ctx, "speaker"); "I'll speak through the speaker." }
            "earpiece", "normal" -> { AudioRoute.setOutput(ctx, "earpiece"); "I'll speak through the earpiece." }
            "btmic_on" -> { AudioRoute.setBtMic(ctx, true); "I'll use your Bluetooth headset's microphone when it's connected." }
            "btmic_off" -> { AudioRoute.setBtMic(ctx, false); "I'll use the phone's microphone even with a headset connected." }
            else -> "Right now I'm using ${AudioRoute.describe(ctx)}. Your saved choice for the phone itself is the ${AudioRoute.output(ctx)}."
        }
        Speaker.get(ctx).reload()
        return ToolOut(true, msg, false, "Audio")
    }

    // ── contacts ─────────────────────────────────────────────────────────

    private fun spoken(n: String) = n.toCharArray().joinToString(" ")

    private fun contacts(ctx: Context, a: JSONObject): ToolOut {
        val name = a.optString("name").trim()
        return when (a.optString("action").lowercase()) {
            "list" -> {
                val l = PragonContacts.all(ctx)
                if (l.isEmpty()) ToolOut(true, "Your Pragon contacts list is empty. Say add, then a name and the number.", false, "Pragon contacts")
                else ToolOut(true, "Pragon contacts: " + l.joinToString(", ") { it.name }, false, "Pragon contacts")
            }
            "remove", "delete" -> {
                if (name.isEmpty()) ToolOut(false, "Whom should I remove?", false)
                else if (PragonContacts.remove(ctx, name)) ToolOut(true, "Removed $name from the Pragon contacts.", false, "Pragon contacts")
                else ToolOut(false, "$name isn't in the Pragon contacts.", false)
            }
            "add", "save" -> {
                if (name.isEmpty()) return ToolOut(false, "Whose number should I save?", false)
                var number = a.optString("number").trim()
                if (number.isEmpty()) {
                    // no digits given: copy from the phone's contacts
                    Perm.need(ctx, Manifest.permission.READ_CONTACTS, "read your contacts")?.let { return ToolOut(false, it, false) }
                    val hit = try { ContactsTool.find(ctx, name) } catch (e: SecurityException) { emptyList() }
                    val top = hit.firstOrNull() ?: return ToolOut(false, "I couldn't find $name in your phone contacts. Tell me the number and I'll save it.", false)
                    val n = top.numbers.firstOrNull() ?: return ToolOut(false, "${top.name} has no number saved on the phone.", false)
                    PragonContacts.add(ctx, top.name, n)
                    return ToolOut(true, "Saved ${top.name}, ${spoken(n.takeLast(4))} at the end, to the Pragon contacts.", false, "Pragon contacts")
                }
                val err = PragonContacts.add(ctx, name, number)
                if (err != null) ToolOut(false, err, false)
                else ToolOut(true, "Saved $name, ${spoken(PragonContacts.digits(number))}, to the Pragon contacts.", false, "Pragon contacts")
            }
            else -> ToolOut(false, "Use add, remove or list.", false)
        }
    }

    // ── calling ──────────────────────────────────────────────────────────

    /** Only digits (and a leading +). * and # are dropped on purpose: they can start network service codes. */
    private fun dialable(raw: String): String? {
        val d = PragonContacts.digits(raw)
        return if (d.count { it.isDigit() } in 3..15) d else null
    }

    private fun call(ctx: Context, a: JSONObject, mode: PMode): ToolOut {
        if (mode == PMode.ASSISTANT) return ToolOut(false, "Assistant mode can only talk. Say master mode or agent mode first.", false)
        val name = a.optString("name").trim()
        val numRaw = a.optString("number").trim()
        val label: String
        val number: String
        if (numRaw.isNotEmpty()) {
            number = dialable(numRaw) ?: return ToolOut(false, "That doesn't sound like a phone number I can dial. Say the digits again.", false)
            label = spoken(number)
        } else if (name.isNotEmpty()) {
            val hit = PragonContacts.find(ctx, name)
            if (hit.isEmpty()) return ToolOut(false, "$name isn't in your Pragon contacts, so I won't call. Say add $name, then the number, and I'll save it.", false)
            if (hit.size > 1) return ToolOut(false, "I have ${hit.take(3).joinToString(" and ") { it.name }}. Which one?", false)
            number = hit[0].number; label = hit[0].name
        } else return ToolOut(false, "Whom should I call?", false)

        Perm.need(ctx, Manifest.permission.CALL_PHONE, "place calls")?.let { return ToolOut(false, it, false) }
        if (Build.VERSION.SDK_INT >= 31 && AudioRoute.bluetoothConnected(ctx) && !Perm.has(ctx, Manifest.permission.BLUETOOTH_CONNECT))
            Perm.request(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        val err = Launch.start(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))), "The phone")
        return if (err == null) ToolOut(true, "Calling $label.", true, "Phone") else ToolOut(false, err, false)
    }
}
