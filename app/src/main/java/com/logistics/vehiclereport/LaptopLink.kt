package com.logistics.vehiclereport

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Office laptop link: the Chrome add-on on the laptop where you are signed in to Drishti reads Vehicle Summary and
 * sends it here through ntfy.sh, encrypted (AES-GCM) with a key that only this app and that add-on have.
 * The link code ("DVR1-<topic>-<key>") is made here and pasted into the add-on. The relay sees only scrambled data.
 */
object LaptopLink {
    private const val KEY_TOPIC = "laptop_topic"
    private const val KEY_KEY = "laptop_key"
    private const val KEY_SINCE = "laptop_since_id"
    private const val RELAY = "https://ntfy.sh/"

    /** What came from the laptop: same as one read of the Drishti page. */
    data class Read(val status: String, val cells: List<List<String>>, val pageDay: Long, val at: Long,
                    /** Standing / Transit / At DC now tables, sent by the office Excel (null from the Chrome add-on). */
                    val sheets: JSONObject? = null)

    // The link is per phone, not per account (kept out of the account data and its cloud copy).
    private fun p(c: Context) = c.getSharedPreferences("laptop_link", Context.MODE_PRIVATE)

    fun linked(c: Context) = p(c).getString(KEY_TOPIC, null) != null

    /** The link code, made on first use. */
    fun code(c: Context): String {
        val pr = p(c)
        var topic = pr.getString(KEY_TOPIC, null)
        var key = pr.getString(KEY_KEY, null)
        if (topic == null || key == null) {
            val rnd = SecureRandom()
            val abc = "abcdefghijkmnpqrstuvwxyz23456789"
            topic = "dvr" + (1..24).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
            key = Base64.encodeToString(ByteArray(32).also { rnd.nextBytes(it) }, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            pr.edit().putString(KEY_TOPIC, topic).putString(KEY_KEY, key).remove(KEY_SINCE).apply()
        }
        return "DVR1-$topic-$key"
    }

    fun unlink(c: Context) = p(c).edit().clear().apply()

    /**
     * The newest gate log the laptop sent since the last one taken (null = nothing new). Network: call off the main
     * thread. Throws on a network error.
     */
    fun fetch(c: Context): Read? {
        val pr = p(c)
        val topic = pr.getString(KEY_TOPIC, null) ?: return null
        val key = Base64.decode(pr.getString(KEY_KEY, null) ?: return null, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val since = pr.getString(KEY_SINCE, null) ?: "12h"
        val lines = get("$RELAY$topic/json?poll=1&since=$since").toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
        val msgs = lines.mapNotNull { try { JSONObject(it) } catch (e: Exception) { null } }.filter { it.optString("event") == "message" }
        // Newest first: take the first one that opens with our key.
        for (m in msgs.asReversed()) {
            val box = try {
                val att = m.optJSONObject("attachment")?.optString("url").orEmpty()
                if (att.startsWith("https://")) get(att) else Base64.decode(m.optString("message"), Base64.DEFAULT)
            } catch (e: Exception) { continue }
            val read = try { open(box, key) } catch (e: Exception) { null } ?: continue
            pr.edit().putString(KEY_SINCE, m.optString("id")).apply()
            return read
        }
        msgs.lastOrNull()?.optString("id")?.takeIf { it.isNotBlank() }?.let { pr.edit().putString(KEY_SINCE, it).apply() }
        return null
    }

    /** iv (12 bytes) + AES-GCM text -> gzip -> JSON {v, status, cells, pageDate, at}. */
    fun open(box: ByteArray, key: ByteArray): Read? {
        if (box.size < 29) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, box, 0, 12))
        val plain = cipher.doFinal(box, 12, box.size - 12)
        val json = GZIPInputStream(ByteArrayInputStream(plain)).use { it.readBytes() }.toString(Charsets.UTF_8)
        return parse(JSONObject(json))
    }

    fun parse(o: JSONObject): Read {
        val status = when (o.optString("status")) {
            "ok" -> DrishtiStore.OK
            "login" -> DrishtiStore.LOGIN
            else -> DrishtiStore.NO_TABLE
        }
        val arr = o.optJSONArray("cells") ?: JSONArray()
        val cells = (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONArray(i) ?: return@mapNotNull null
            (0 until r.length()).map { r.optString(it) }
        }
        val day = DrishtiData.parseTime(o.optString("pageDate") + " 00:00", 0L)
            .takeIf { it > 0 }?.let { ReportBuilder.startOfDay(it) } ?: ReportBuilder.startOfDay()
        return Read(status, cells, day, o.optLong("at", System.currentTimeMillis()), o.optJSONObject("sheets"))
    }

    private fun get(url: String): ByteArray {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 20_000
        con.readTimeout = 30_000
        con.setRequestProperty("User-Agent", "DailyVehicleReport")
        try {
            if (con.responseCode !in 200..299) throw java.io.IOException("relay ${con.responseCode}")
            return con.inputStream.use { it.readBytes() }
        } finally { con.disconnect() }
    }

    /**
     * Takes what the laptop sent and applies it like a Drishti read. Returns the result, or null when nothing new
     * came. A sign-in that expired on the laptop is saved as the Drishti status.
     */
    fun pullAndApply(c: Context): DrishtiResult? {
        val r = fetch(c) ?: return null
        r.sheets?.let { YardStore.save(c, it, r.at) }
        if (r.status != DrishtiStore.OK) { DrishtiStore.setStatus(c, r.status); return null }
        return DrishtiStore.apply(c, r.cells, r.pageDay, readAt = r.at)
    }
}
