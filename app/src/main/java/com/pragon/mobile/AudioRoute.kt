package com.pragon.mobile

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Where Pragon's voice comes out and which microphone it listens to.
 *
 *  - Bluetooth headset connected (AirPods, any BT earbuds): voice goes there AND the headset's mic is used.
 *    Android only offers a headset's mic over the "call" Bluetooth profile, so while Pragon listens the earbuds
 *    run in call quality (the same thing every voice assistant does). Switch it off with [setBtMic] if you
 *    would rather keep music quality and use the phone's mic.
 *  - otherwise [output]: "speaker" (default) or "earpiece" (the normal phone-call speaker at your ear).
 *
 * Several parts can hold the route at once ("live", "float", "tts", "pa"): it is applied when the first one
 * acquires it and put back when the last one lets go.
 */
object AudioRoute {
    private fun sp(c: Context) = c.getSharedPreferences("pragon_audio", Context.MODE_PRIVATE)

    fun output(c: Context): String = if (sp(c).getString("output", "speaker") == "earpiece") "earpiece" else "speaker"
    fun setOutput(c: Context, v: String) = sp(c).edit().putString("output", if (v == "earpiece") "earpiece" else "speaker").apply()
    fun btMic(c: Context): Boolean = sp(c).getBoolean("bt_mic", true)
    fun setBtMic(c: Context, on: Boolean) = sp(c).edit().putBoolean("bt_mic", on).apply()

    private val owners = HashSet<String>()
    private var applied = false
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false
    private var scoLegacy = false

    private fun am(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun isBt(t: Int) = t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun isWired(t: Int) = t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        t == AudioDeviceInfo.TYPE_USB_HEADSET

    fun bluetoothConnected(c: Context): Boolean = try {
        am(c).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isBt(it.type) }
    } catch (e: Exception) { false }

    fun wiredConnected(c: Context): Boolean = try {
        am(c).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isWired(it.type) }
    } catch (e: Exception) { false }

    /** What the user should be told: "AirPods / Bluetooth", "speaker" or "earpiece". */
    fun describe(c: Context): String = when {
        bluetoothConnected(c) && btMic(c) -> "your Bluetooth headset"
        wiredConnected(c) -> "your wired headset"
        output(c) == "earpiece" -> "the earpiece"
        else -> "the speaker"
    }

    /** Attributes for the on-phone TTS voice, so it follows the same route as everything else. */
    fun ttsAttrs(c: Context): AudioAttributes {
        val comm = output(c) == "earpiece" || (bluetoothConnected(c) && btMic(c))
        return AudioAttributes.Builder()
            .setUsage(if (comm) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }

    /** Takes the route for [who]. Returns the device Pragon's audio should prefer (null = let Android choose). */
    @Synchronized
    fun acquire(c: Context, who: String): AudioDeviceInfo? {
        owners.add(who)
        return try { applyNow(c) } catch (e: Exception) { null }
    }

    @Synchronized
    fun release(c: Context, who: String) {
        owners.remove(who)
        if (owners.isNotEmpty() || !applied) return
        applied = false
        try {
            val a = am(c)
            if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice()
            if (scoLegacy) { @Suppress("DEPRECATION") a.stopBluetoothSco(); @Suppress("DEPRECATION") a.isBluetoothScoOn = false }
            scoLegacy = false
            @Suppress("DEPRECATION")
            a.isSpeakerphoneOn = prevSpeaker
            if (a.mode != AudioManager.MODE_IN_CALL && a.mode != AudioManager.MODE_RINGTONE) a.mode = prevMode
        } catch (e: Exception) { }
    }

    private fun comm(a: AudioManager): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 31) a.availableCommunicationDevices else emptyList()

    private fun applyNow(c: Context): AudioDeviceInfo? {
        val a = am(c)
        // Never fight the phone app while a real call is going on (the PA handles that case itself),
        // and never remember a call's forced-speaker state as "how things were".
        if (a.mode == AudioManager.MODE_IN_CALL || a.mode == AudioManager.MODE_RINGTONE) return null
        if (!applied) {
            applied = true
            prevMode = a.mode
            @Suppress("DEPRECATION")
            prevSpeaker = a.isSpeakerphoneOn
        }
        val outs = a.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        if (wiredConnected(c)) return null                       // wired headset: Android already does the right thing

        if (outs.any { isBt(it.type) } && btMic(c)) {
            if (Build.VERSION.SDK_INT >= 31) {
                val d = comm(a).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                if (d != null && a.setCommunicationDevice(d)) return d
            } else {
                a.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION") a.startBluetoothSco()
                @Suppress("DEPRECATION") a.isBluetoothScoOn = true
                scoLegacy = true
                return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            }
            return null
        }
        if (outs.any { isBt(it.type) }) return null              // BT but mic not wanted: plain A2DP playback

        if (output(c) == "earpiece") {
            if (Build.VERSION.SDK_INT >= 31) {
                val d = comm(a).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (d != null) a.setCommunicationDevice(d)
                return d
            }
            a.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION") a.isSpeakerphoneOn = false
            return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
        // default: loudspeaker
        val spk = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (Build.VERSION.SDK_INT >= 31) {
            val d = comm(a).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (d != null) a.setCommunicationDevice(d)
        }
        @Suppress("DEPRECATION") a.isSpeakerphoneOn = true
        return spk
    }

    // ── a real phone call (used by Pragon PA) ─────────────────────────────

    private var callPrevSpeaker: Boolean? = null

    /** Puts the call on the loudspeaker so Pragon's microphone can hear the caller. */
    fun callOnSpeaker(c: Context) {
        try {
            val a = am(c)
            if (callPrevSpeaker == null) @Suppress("DEPRECATION") { callPrevSpeaker = a.isSpeakerphoneOn }
            if (Build.VERSION.SDK_INT >= 31) {
                comm(a).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { a.setCommunicationDevice(it) }
            }
            @Suppress("DEPRECATION") a.isSpeakerphoneOn = true
        } catch (e: Exception) { }
    }

    /** Back to how the call was routed before (earpiece, or the headset if one is connected). */
    fun callRestore(c: Context) {
        try {
            val a = am(c)
            if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice()
            @Suppress("DEPRECATION") a.isSpeakerphoneOn = callPrevSpeaker ?: false
        } catch (e: Exception) { }
        callPrevSpeaker = null
    }
}
