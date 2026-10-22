package com.logistics.vehiclereport

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object MatchType {
    /** Full number was written in the message. */
    const val FULL = "FULL"
    /** Last 4 digits matched exactly one dedicated vehicle. */
    const val LAST4 = "LAST4"
    /** Last 4 digits matched more than one dedicated vehicle – needs checking. */
    const val MULTI = "MULTI"
    /** Only a 4-digit code that matches no vehicle in the list (reported, but the full number is unknown). */
    const val CODE = "CODE"
}

data class VehicleRecord(
    val id: String,
    val vehicle: String,
    val chat: String,
    val sender: String,
    val message: String,
    val time: Long,
    val match: String,
    /** What the driver actually typed, e.g. "1234". */
    val typed: String,
    /** For MULTI: every dedicated vehicle ending with the typed digits. */
    val candidates: List<String>,
    /** City written next to the vehicle number. */
    val city: String = "",
    /** Replacement vehicle sent instead of the dedicated one ("" if none). */
    val replacement: String = "",
    /** Driver mobile written with the vehicle in the message ("" if none). */
    val mobile: String = "",
    /** Vendor / transporter written with the vehicle in the message ("" if none). */
    val vendor: String = "",
    /** Vehicle size / type written with it ("22 FT"), "" if none. */
    val size: String = "",
    /** How many different messages reported this vehicle that day (one entry per vehicle per day). */
    val count: Int = 1,
    /** Driver name written with the vehicle ("" if none). */
    val driver: String = "",
    /** Everyone who posted this vehicle that day. */
    val senders: List<String> = listOf(sender),
    /** Time of the latest of those messages. */
    val lastTime: Long = time,
    /** > 0: posted with only the last 4 digits; the full number was read from the number plate by the camera at this time. */
    val scannedAt: Long = 0
)

/** A dedicated vehicle with its details. Only [number] is required. */
data class DedicatedVehicle(
    val number: String,
    val type: String = "",
    val driver: String = "",
    val mobile: String = "",
    val transporter: String = "",
    val route: String = "",
    /**
     * true = dedicated: expected every day, shown as Not Reported when missing.
     * false = record only: seen in the group (added automatically) – used to match last 4 digits,
     * fill details and cost, never shown as Not Reported.
     */
    val dedicated: Boolean = true
) {
    fun mergedWith(newer: DedicatedVehicle) = DedicatedVehicle(
        number = number,
        type = newer.type.ifBlank { type },
        driver = newer.driver.ifBlank { driver },
        mobile = newer.mobile.ifBlank { mobile },
        transporter = newer.transporter.ifBlank { transporter },
        route = newer.route.ifBlank { route },
        dedicated = dedicated || newer.dedicated
    )
}

/** A message with 4-digit codes that matched no dedicated vehicle (kept 3 days, re-checked when the list changes). */
data class UnmatchedMessage(
    val chat: String,
    val sender: String,
    val text: String,
    val time: Long,
    val codes: List<String>
)

/** One WhatsApp message read from a notification. */
data class IncomingMessage(
    val chat: String,
    val sender: String,
    val text: String,
    val time: Long,
    /** true when the timestamp comes from WhatsApp itself (MessagingStyle), so duplicates can be matched exactly. */
    val exactTime: Boolean,
    val isGroup: Boolean = false
)

/** A WhatsApp chat the app has seen in notifications (offered in the group picker). */
data class KnownChat(val name: String, val lastSeen: Long, val isGroup: Boolean)

/** Someone who posted in WhatsApp (offered in the sender picker). */
data class KnownSender(val name: String, val chat: String, val lastSeen: Long)

/** Local storage. Everything stays on the phone; no login, no server. */
object VehicleStore {

    const val KEY_RECORDS = "records_json"
    private const val KEY_RECORDS_VERSION = "records_version"
    /** 2 = mobile + vendor, 3 = vehicle size, 4 = one entry per vehicle per day, 5 = re-read with the improved reader,
     *  6 = codes not in the vehicle list are entries too, 7 = full numbers seen become record-only vehicles,
     *  8 = driver mobile written as "+91…" straight after the place ("VIZAG+919908764329"). */
    private const val RECORDS_VERSION = 8
    private var rememberSeenVehiclesLater = false
    const val KEY_DEDICATED = "dedicated_json"
    const val KEY_UNMATCHED = "unmatched_json"
    private const val KEY_SEEN = "seen_keys_json"
    private const val KEY_SELECTED_GROUP = "selected_group"
    private const val KEY_CHATS = "known_chats_json"
    private const val KEY_SENDERS = "known_senders_json"
    private const val KEY_ALLOWED_SENDERS = "allowed_senders"
    private const val MAX_CHATS = 60

    private const val MAX_RECORDS = 5000
    private const val MAX_SEEN = 1000
    private const val MAX_UNMATCHED = 500
    private const val SEEN_TTL_MS = 48L * 60 * 60 * 1000
    private const val UNMATCHED_TTL_MS = 3L * 24 * 60 * 60 * 1000
    /** For notifications without a WhatsApp timestamp, ignore the identical message again within this window. */
    private const val FALLBACK_DUP_WINDOW_MS = 10L * 60 * 1000

    private val lock = Any()
    private var idCounter = 0L

    /** The signed-in account's own storage (each account has a separate file; see [Account]). */
    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(Account.storeName(context), Context.MODE_PRIVATE)

    // ---------- Group selection ----------

    /** The one WhatsApp group to read from. Empty = read all chats. */
    fun getSelectedGroup(context: Context): String =
        prefs(context).getString(KEY_SELECTED_GROUP, "") ?: ""

    fun setSelectedGroup(context: Context, name: String) {
        prefs(context).edit().putString(KEY_SELECTED_GROUP, name.trim()).apply()
    }

    /** Chats seen in WhatsApp notifications, groups first, most recent first. */
    fun getKnownChats(context: Context): List<KnownChat> = synchronized(lock) {
        val o = loadChats(prefs(context))
        o.keys().asSequence().mapNotNull { name ->
            val c = o.optJSONObject(name) ?: return@mapNotNull null
            KnownChat(name, c.optLong("t"), c.optBoolean("g"))
        }.sortedWith(compareByDescending<KnownChat> { it.isGroup }.thenByDescending { it.lastSeen }).toList()
    }

    private fun loadChats(p: SharedPreferences): JSONObject =
        try { JSONObject(p.getString(KEY_CHATS, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun rememberChats(chats: JSONObject, messages: List<IncomingMessage>, now: Long): JSONObject {
        for (m in messages) {
            if (m.chat.isBlank() || m.chat == "WhatsApp") continue
            val old = chats.optJSONObject(m.chat)
            chats.put(m.chat, JSONObject().apply {
                put("t", now)
                put("g", m.isGroup || (old?.optBoolean("g") ?: false))
            })
        }
        if (chats.length() <= MAX_CHATS) return chats
        val newest = chats.keys().asSequence()
            .map { it to (chats.optJSONObject(it)?.optLong("t") ?: 0L) }
            .sortedByDescending { it.second }.take(MAX_CHATS).map { it.first }.toSet()
        return JSONObject().apply { newest.forEach { put(it, chats.get(it)) } }
    }

    /** Group names match exactly, or by start when one was cut short on screen ("1041 DC Transport Te…"). */
    fun sameChat(a: String, b: String): Boolean {
        val x = a.trim(); val y = b.trim()
        if (x.equals(y, ignoreCase = true)) return true
        fun cut(s: String) = s.endsWith("…") || s.endsWith("...")
        fun stem(s: String) = s.removeSuffix("…").removeSuffix("...").trim()
        return when {
            cut(x) && stem(x).length >= 6 -> y.startsWith(stem(x), ignoreCase = true)
            cut(y) && stem(y).length >= 6 -> x.startsWith(stem(y), ignoreCase = true)
            else -> false
        }
    }

    // ---------- Sender filter ----------

    /** Only messages from these people/numbers are read. Empty = anyone. */
    fun getAllowedSenders(context: Context): List<String> =
        (prefs(context).getString(KEY_ALLOWED_SENDERS, "") ?: "")
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun setAllowedSenders(context: Context, list: List<String>) {
        prefs(context).edit().putString(KEY_ALLOWED_SENDERS, list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")).apply()
    }

    /** People seen posting, most recent first. Only from the selected group when one is chosen. */
    fun getKnownSenders(context: Context): List<KnownSender> = synchronized(lock) {
        val p = prefs(context)
        val group = p.getString(KEY_SELECTED_GROUP, "") ?: ""
        val o = loadSenders(p)
        o.keys().asSequence().mapNotNull { name ->
            val e = o.optJSONObject(name) ?: return@mapNotNull null
            KnownSender(name, e.optString("c"), e.optLong("t"))
        }.filter { group.isBlank() || sameChat(it.chat, group) }
            .sortedByDescending { it.lastSeen }.toList()
    }

    /** Matches by phone number (last 10 digits) when the entry has digits, otherwise by name. */
    fun senderMatches(sender: String, entry: String): Boolean {
        val ed = entry.filter { it.isDigit() }
        if (ed.length >= 6) {
            val sd = sender.filter { it.isDigit() }
            return sd.length >= 6 && sd.takeLast(10) == ed.takeLast(10)
        }
        fun clean(x: String) = x.trim().removePrefix("~").trim()
        return clean(sender).equals(clean(entry), ignoreCase = true)
    }

    private fun loadSenders(p: SharedPreferences): JSONObject =
        try { JSONObject(p.getString(KEY_SENDERS, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun rememberSenders(senders: JSONObject, messages: List<IncomingMessage>, now: Long): JSONObject {
        for (m in messages) {
            if (m.sender.isBlank() || m.sender == "You") continue
            senders.put(m.sender, JSONObject().apply { put("t", now); put("c", m.chat) })
        }
        if (senders.length() <= MAX_CHATS * 2) return senders
        val newest = senders.keys().asSequence()
            .map { it to (senders.optJSONObject(it)?.optLong("t") ?: 0L) }
            .sortedByDescending { it.second }.take(MAX_CHATS * 2).map { it.first }.toSet()
        return JSONObject().apply { newest.forEach { put(it, senders.get(it)) } }
    }

    // ---------- Dedicated vehicle list ----------

    fun getVehicles(context: Context): List<DedicatedVehicle> = synchronized(lock) {
        loadVehicles(prefs(context))
    }

    fun getDedicated(context: Context): List<String> = getVehicles(context).map { it.number }

    fun findVehicle(context: Context, number: String): DedicatedVehicle? =
        getVehicles(context).firstOrNull { it.number == number }

    /** Adds or updates one vehicle. Returns true if it was new. */
    fun saveVehicle(context: Context, vehicleIn: DedicatedVehicle): Boolean = synchronized(lock) {
        val vehicle = vehicleIn.copy(number = canonical(vehicleIn.number))
        val list = loadVehicles(prefs(context)).toMutableList()
        val i = list.indexOfFirst { it.number == vehicle.number }
        if (i >= 0) list[i] = vehicle else list += vehicle
        saveDedicatedAndRematch(context, list)
        i < 0
    }

    /** Imports many vehicles. Existing ones are updated (blank cells keep the old value). Returns how many were new. */
    fun importVehicles(context: Context, vehicles: List<DedicatedVehicle>, replace: Boolean): Int = synchronized(lock) {
        val list = if (replace) mutableListOf<DedicatedVehicle>() else loadVehicles(prefs(context)).toMutableList()
        var added = 0
        for (v in mergeDuplicates(vehicles)) {
            val i = list.indexOfFirst { it.number == v.number }
            if (i >= 0) list[i] = list[i].mergedWith(v) else { list += v; added++ }
        }
        saveDedicatedAndRematch(context, list)
        added
    }

    /**
     * Imports [vehicles] as one kind: [tracked] = for reporting (can be Pending), else dedicated storage only.
     * The chosen kind wins for vehicles already in the list. [replace] replaces only the vehicles of that kind.
     * Returns how many were new.
     */
    fun importAs(context: Context, vehicles: List<DedicatedVehicle>, tracked: Boolean, replace: Boolean): Int = synchronized(lock) {
        val incoming = mergeDuplicates(vehicles).map { it.copy(dedicated = tracked) }
        val current = loadVehicles(prefs(context))
        val list = (if (replace) current.filter { it.dedicated != tracked } else current).toMutableList()
        var added = 0
        for (v in incoming) {
            val i = list.indexOfFirst { it.number == v.number }
            if (i >= 0) list[i] = list[i].mergedWith(v).copy(dedicated = tracked) else { list += v; added++ }
        }
        saveDedicatedAndRematch(context, list)
        added
    }

    /**
     * Captures details from WhatsApp into the vehicle list: driver mobile, driver name, vendor, vehicle size and
     * store fill the fields that are still empty. Returns how many vehicles got new details.
     */
    fun fillBlanksFromPosts(context: Context, records: List<VehicleRecord>? = null): Int = synchronized(lock) {
        val p = prefs(context)
        val list = loadVehicles(p)
        val needing = list.filter { PostedDetails.hasBlanks(it) }
        if (needing.isEmpty()) return@synchronized 0
        val posted = PostedDetails.forVehicles(context, needing, records ?: getAll(context))
        if (posted.isEmpty()) return@synchronized 0
        var changed = 0
        val out = list.map { v ->
            val seen = posted[v.number] ?: return@map v
            PostedDetails.fillBlanks(v, seen).also { if (it != v) changed++ }
        }
        if (changed > 0) p.edit().putString(KEY_DEDICATED, vehiclesJson(out).toString()).apply()
        changed
    }

    /**
     * Vehicles from the DC gate log (Drishti): a vehicle already in the list only gets its empty details filled
     * (type, driver, mobile); a new one is added as record only (never Not Reported), so its last 4 digits match
     * WhatsApp posts. Returns (added, updated).
     */
    fun addFromGate(context: Context, rows: List<DrishtiRow>): Pair<Int, Int> = synchronized(lock) {
        val list = loadVehicles(prefs(context)).toMutableList()
        val at = HashMap<String, Int>().apply { list.forEachIndexed { i, v -> put(v.number, i) } }
        var added = 0; var updated = 0
        for (r in rows) {
            val i = at[r.vehicle]
            if (i == null) {
                list += DedicatedVehicle(r.vehicle, type = r.type, driver = r.driver, mobile = r.mobile, dedicated = false)
                at[r.vehicle] = list.size - 1
                added++
            } else {
                val v = list[i]
                val n = v.copy(type = v.type.ifBlank { r.type }, driver = v.driver.ifBlank { r.driver }, mobile = v.mobile.ifBlank { r.mobile })
                if (n != v) { list[i] = n; updated++ }
            }
        }
        if (added + updated > 0) saveDedicatedAndRematch(context, list)
        added to updated
    }

    fun removeVehicle(context: Context, number: String) = synchronized(lock) {
        saveDedicatedAndRematch(context, loadVehicles(prefs(context)).filter { it.number != number })
    }

    /** One spelling per vehicle: "ts 09 ub-1234" and "TS09UB1234" are the same vehicle. */
    fun canonical(number: String): String =
        VehicleParser.extract(number).firstOrNull() ?: number.uppercase().replace(Regex("[^A-Z0-9]"), "")

    /** Same vehicle twice in the list -> one entry, details merged. */
    private fun mergeDuplicates(list: List<DedicatedVehicle>): List<DedicatedVehicle> {
        val out = LinkedHashMap<String, DedicatedVehicle>()
        for (v in list) {
            val n = canonical(v.number)
            if (n.isBlank()) continue
            val c = v.copy(number = n)
            out[n] = out[n]?.mergedWith(c) ?: c
        }
        return out.values.toList()
    }

    private fun loadVehicles(p: SharedPreferences): List<DedicatedVehicle> = mergeDuplicates(loadVehiclesRaw(p))

    private fun loadVehiclesRaw(p: SharedPreferences): List<DedicatedVehicle> {
        val arr = try { JSONArray(p.getString(KEY_DEDICATED, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            when (val item = arr.opt(i)) {
                is JSONObject -> DedicatedVehicle(
                    number = item.optString("number"),
                    type = item.optString("type"),
                    driver = item.optString("driver"),
                    mobile = item.optString("mobile"),
                    transporter = item.optString("transporter"),
                    route = item.optString("route"),
                    dedicated = item.optBoolean("ded", true)
                )
                is String -> DedicatedVehicle(item) // list saved by older version
                else -> null
            }
        }.filter { it.number.isNotBlank() }
    }

    private fun vehiclesJson(list: List<DedicatedVehicle>): JSONArray = JSONArray().apply {
        list.forEach { v ->
            put(JSONObject().apply {
                put("number", v.number); put("type", v.type); put("driver", v.driver)
                put("mobile", v.mobile); put("transporter", v.transporter); put("route", v.route)
                put("ded", v.dedicated)
            })
        }
    }

    /** last4 -> dedicated vehicles ending with those digits */
    private fun last4Map(numbers: List<String>): Map<String, List<String>> =
        numbers.groupBy { VehicleParser.last4(it) }

    /** Saves the list, then re-checks recent unmatched 4-digit codes against it. */
    private fun saveDedicatedAndRematch(context: Context, vehicles: List<DedicatedVehicle>) {
        val p = prefs(context)
        val list = mergeDuplicates(vehicles)
        val byLast4 = last4Map(list.map { it.number })
        val records = loadRecords(p)
        val index = dayIndex(records)
        val stillUnmatched = JSONArray()
        var added = 0
        val now = System.currentTimeMillis()

        val dedicatedSet = list.map { it.number }.toSet()
        for (u in loadUnmatched(p, now)) {
            val result = MessageParser.parse(u.text, byLast4, dedicatedSet, MasterData.get(context).asHints())
            val found = result.captures.filter { !it.full && it.inList && it.typed in u.codes }
            found.forEach { c ->
                addOrMerge(records, index, recordJson(u.chat, u.sender, u.text, u.time, c))
                added++
            }
            val left = u.codes - found.map { it.typed }.toSet()
            if (left.isNotEmpty()) stillUnmatched.put(unmatchedJson(u.copy(codes = left)))
        }

        val editor = p.edit()
            .putString(KEY_DEDICATED, vehiclesJson(list).toString())
            .putString(KEY_UNMATCHED, stillUnmatched.toString())
        if (added > 0) editor.putString(KEY_RECORDS, trimRecords(records).toString())
        editor.apply()
    }

    // ---------- Read ----------

    fun getAll(context: Context): List<VehicleRecord> = synchronized(lock) {
        val arr = loadRecords(prefs(context))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val cands = o.optJSONArray("candidates")
            VehicleRecord(
                id = o.optString("id"),
                vehicle = o.optString("vehicle"),
                chat = o.optString("chat"),
                sender = o.optString("sender"),
                message = o.optString("message"),
                time = o.optLong("time"),
                match = o.optString("match", MatchType.FULL),
                typed = o.optString("typed", o.optString("vehicle")),
                candidates = if (cands == null) emptyList() else (0 until cands.length()).map { cands.optString(it) },
                city = o.optString("city"),
                replacement = o.optString("replacement"),
                mobile = o.optString("mobile"),
                vendor = o.optString("vendor"),
                size = o.optString("size"),
                count = o.optInt("count", 1).coerceAtLeast(1),
                driver = o.optString("driver"),
                senders = o.optJSONArray("ss")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.ifEmpty { null }
                    ?: listOf(o.optString("sender")),
                lastTime = o.optLong("last", o.optLong("time"))
            )
        }.let { resolveCodes(it, loadVehicles(prefs(context))) }
            .let { PlateScans.apply(it, PlateScans.all(context)) }
            .let { ReportBuilder.uniquePerDay(it) }.let { list ->
            // "Posted by": only vehicles posted by the chosen people (also hides what came in before the choice).
            val allowed = getAllowedSenders(context)
            if (allowed.isEmpty()) list
            else list.filter { r -> r.senders.any { s -> allowed.any { senderMatches(s, it) } } }
                .map { r -> r.senders.firstOrNull { s -> allowed.any { senderMatches(s, it) } }?.let { r.copy(sender = it) } ?: r }
        }.sortedByDescending { it.time }
    }

    /**
     * A 4-digit code saved before its vehicle was in the list ("5090 Khammam") is matched to the list as it
     * is now, so a vehicle added or imported later shows as reported (not Pending) for those days.
     */
    private fun resolveCodes(records: List<VehicleRecord>, vehicles: List<DedicatedVehicle>): List<VehicleRecord> {
        val byLast4 = last4Map(vehicles.map { it.number })
        return records.map { r ->
            if (r.match != MatchType.CODE) return@map r
            val cands = byLast4[r.typed].orEmpty()
            when {
                cands.isEmpty() -> r
                cands.size == 1 -> r.copy(match = MatchType.LAST4, candidates = cands, vehicle = cands[0])
                else -> r.copy(match = MatchType.MULTI, candidates = cands, vehicle = cands.joinToString(" / "))
            }
        }
    }

    /** Names / numbers of everyone who posted a captured vehicle (for the "Posted by" picker). */
    fun recordSenders(context: Context): List<String> = synchronized(lock) {
        val arr = loadRecords(prefs(context))
        val out = LinkedHashSet<String>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            o.optJSONArray("ss")?.let { a -> for (k in 0 until a.length()) out += a.optString(k) }
            out += o.optString("sender")
            if (out.size > 150) break
        }
        out.filter { it.isNotBlank() }
    }

    fun getUnmatched(context: Context): List<UnmatchedMessage> = synchronized(lock) {
        val allowed = getAllowedSenders(context)
        loadUnmatched(prefs(context), System.currentTimeMillis())
            .filter { u -> allowed.isEmpty() || allowed.any { senderMatches(u.sender, it) } }
            .sortedByDescending { it.time }
    }

    // ---------- Write ----------

    /** Saves every vehicle found in [messages]. Returns how many entries were added. */
    fun ingest(context: Context, messages: List<IncomingMessage>, ignoreGroupFilter: Boolean = false): Int = synchronized(lock) {
        // Nobody signed in on this phone: nothing is kept.
        if (!Account.isSignedIn(context)) return@synchronized 0
        if (messages.isEmpty()) return 0
        val p = prefs(context)
        val selected = p.getString(KEY_SELECTED_GROUP, "") ?: ""
        val byLast4 = last4Map(loadVehicles(p).map { it.number })
        val now = System.currentTimeMillis()
        val seen = pruneSeen(loadSeen(p), now)
        val records = loadRecords(p)
        val index = dayIndex(records)
        var changed = false
        val unmatched = loadUnmatched(p, now).toMutableList()
        var added = 0
        var unmatchedChanged = false

        val allowed = getAllowedSenders(context)
        val hints = MasterData.get(context).asHints()
        val dedicatedSet = loadVehicles(p).map { it.number }.toSet()

        // Same message seen from another source (notification vs. screen): same text in the same minute.
        fun sameKey(text: String, time: Long) = text.trim().replace(Regex("\\s+"), " ").lowercase() + "|" + (time / 60_000)
        val known = HashSet<String>()
        for (i in 0 until records.length()) {
            val o = records.optJSONObject(i) ?: continue
            known += sameKey(o.optString("message"), o.optLong("time"))
        }
        unmatched.forEach { known += sameKey(it.text, it.time) }

        for (m in messages) {
            if (!ignoreGroupFilter && selected.isNotBlank() && !sameChat(m.chat, selected)) continue
            if (allowed.isNotEmpty() && allowed.none { senderMatches(m.sender, it) }) continue
            val result = MessageParser.parse(m.text, byLast4, dedicatedSet, hints)
            if (result.captures.isEmpty() && result.unmatchedCodes.isEmpty()) continue

            val key = hash(
                if (m.exactTime) "${m.chat}|${m.sender}|${m.time}|${m.text}"
                else "${m.chat}|${m.sender}|${m.text}"
            )
            val lastSeen = seen.optLong(key, -1L)
            if (lastSeen >= 0 && (m.exactTime || now - lastSeen < FALLBACK_DUP_WINDOW_MS)) continue
            seen.put(key, now)
            if (!known.add(sameKey(m.text, m.time))) continue

            for (c in result.captures) {
                if (addOrMerge(records, index, recordJson(m.chat, m.sender, m.text, m.time, c))) added++
                changed = true
            }
            if (result.unmatchedCodes.isNotEmpty()) {
                unmatched += UnmatchedMessage(m.chat, m.sender, m.text, m.time, result.unmatchedCodes)
                unmatchedChanged = true
            }
        }

        val editor = p.edit()
            .putString(KEY_SEEN, capSeen(seen).toString())
            .putString(KEY_CHATS, rememberChats(loadChats(p), messages, now).toString())
            .putString(KEY_SENDERS, rememberSenders(loadSenders(p),
                messages.filter { selected.isBlank() || sameChat(it.chat, selected) }, now).toString())
        if (changed) editor.putString(KEY_RECORDS, trimRecords(records).toString())
        if (unmatchedChanged) {
            val arr = JSONArray()
            unmatched.sortedBy { it.time }.takeLast(MAX_UNMATCHED).forEach { arr.put(unmatchedJson(it)) }
            editor.putString(KEY_UNMATCHED, arr.toString())
        }
        editor.apply()
        rememberSeenVehicles(context, p)
        added
    }

    /**
     * Full numbers posted in the group that are not in the list are added as "record only" vehicles
     * (with type, driver, mobile, vendor, location from the post). Later posts with only their last 4
     * digits then match them, and earlier 4-digit posts are matched again.
     */
    private fun rememberSeenVehicles(context: Context, p: SharedPreferences) {
        val list = loadVehicles(p)
        val known = list.map { it.number }.toSet()
        val arr = loadRecords(p)
        val seen = LinkedHashMap<String, DedicatedVehicle>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("match") != MatchType.FULL) continue
            val n = o.optString("vehicle")
            if (n in known || n.length < 6) continue
            val v = DedicatedVehicle(n, type = o.optString("size"), driver = o.optString("driver"), mobile = o.optString("mobile"),
                transporter = o.optString("vendor"), route = o.optString("city"), dedicated = false)
            seen[n] = seen[n]?.mergedWith(v)?.copy(dedicated = false) ?: v
        }
        if (seen.isNotEmpty()) saveDedicatedAndRematch(context, list + seen.values)
    }

    /**
     * Entries saved by older versions have no driver mobile or vendor, and block posts had no location.
     * Their message is kept, so read it again once and fill those in.
     */
    fun upgradeOldRecords(context: Context) = synchronized(lock) {
        val p = prefs(context)
        if (p.getInt(KEY_RECORDS_VERSION, 1) >= RECORDS_VERSION) return@synchronized
        val numbers = loadVehicles(p).map { it.number }
        val byLast4 = last4Map(numbers)
        val dedicatedSet = numbers.toSet()
        val arr = loadRecords(p)
        val parsed = HashMap<String, List<Capture>>()
        val hints = MasterData.get(context).asHints()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val msg = o.optString("message")
            val c = parsed.getOrPut(msg) { MessageParser.parse(msg, byLast4, dedicatedSet, hints).captures }
                .firstOrNull { it.typed == o.optString("typed") && it.replacement == o.optString("replacement") } ?: continue
            // The reader has improved: its reading of the saved message replaces the old one.
            for ((k, v) in listOf("city" to c.city, "mobile" to c.mobile, "vendor" to c.vendor, "size" to c.size, "driver" to c.driver)) {
                if (v.isNotBlank()) o.put(k, v)
            }
        }
        rememberSeenVehiclesLater = true
        // Codes that matched no vehicle in the list: add them as reported vehicles.
        for (u in loadUnmatched(p, System.currentTimeMillis())) {
            MessageParser.parse(u.text, byLast4, dedicatedSet, hints).captures
                .filter { !it.inList && it.typed in u.codes }
                .forEach { arr.put(recordJson(u.chat, u.sender, u.text, u.time, it)) }
        }
        // Collapse repeats of the same vehicle on the same day into one entry.
        val sorted = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.sortedBy { it.optLong("time") }
        val unique = JSONArray()
        val idx = HashMap<String, Int>()
        for (o in sorted) addOrMerge(unique, idx, o)
        p.edit().putString(KEY_RECORDS, unique.toString()).putInt(KEY_RECORDS_VERSION, RECORDS_VERSION).apply()
        if (rememberSeenVehiclesLater) rememberSeenVehicles(context, p)
    }

    fun delete(context: Context, id: String) = synchronized(lock) {
        val p = prefs(context)
        val arr = loadRecords(p)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) out.put(o)
        }
        p.edit().putString(KEY_RECORDS, out.toString()).apply()
    }

    /** Clears captured vehicles. Already-seen messages are remembered so they don't come back. */
    fun clear(context: Context) = synchronized(lock) {
        prefs(context).edit()
            .putString(KEY_RECORDS, "[]")
            .putString(KEY_UNMATCHED, "[]")
            .apply()
    }

    // ---------- Helpers ----------

    // ---------- One entry per vehicle per day ----------

    private fun msgHash(text: String) = hash(text.trim().replace(Regex("\\s+"), " ").lowercase()).take(12)

    private fun dayKey(o: JSONObject) = o.optString("vehicle") + "|" + ReportBuilder.startOfDay(o.optLong("time"))

    /** vehicle|day -> index in [records] */
    private fun dayIndex(records: JSONArray): HashMap<String, Int> {
        val m = HashMap<String, Int>()
        for (i in 0 until records.length()) records.optJSONObject(i)?.let { m.putIfAbsent(dayKey(it), i) }
        return m
    }

    /**
     * Adds [rec] unless the same vehicle is already there for that day; then the existing entry is kept
     * (earliest time), blank details are filled from the new message, and the report count goes up
     * only when the message is different (the same message seen twice counts once). Returns true if new.
     */
    private fun addOrMerge(records: JSONArray, index: HashMap<String, Int>, rec: JSONObject): Boolean {
        val key = dayKey(rec)
        val h = msgHash(rec.optString("message"))
        val at = index[key]
        if (at == null) {
            if (!rec.has("count")) rec.put("count", 1)
            if (!rec.has("last")) rec.put("last", rec.optLong("time"))
            if (!rec.has("mh")) rec.put("mh", JSONArray().put(h))
            if (!rec.has("ss")) rec.put("ss", JSONArray().put(rec.optString("sender")))
            records.put(rec)
            index[key] = records.length() - 1
            return true
        }
        val o = records.getJSONObject(at)
        val hashes = o.optJSONArray("mh") ?: JSONArray().put(msgHash(o.optString("message"))).also { o.put("mh", it) }
        val seenText = (0 until hashes.length()).any { hashes.optString(it) == h }
        if (!seenText) {
            hashes.put(h)
            o.put("count", o.optInt("count", 1) + 1)
        }
        val ss = o.optJSONArray("ss") ?: JSONArray().put(o.optString("sender")).also { o.put("ss", it) }
        val incoming = rec.optJSONArray("ss")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf(rec.optString("sender"))
        for (x in incoming) if ((0 until ss.length()).none { ss.optString(it) == x }) ss.put(x)
        val t = rec.optLong("time")
        if (t < o.optLong("time")) {
            // keep the earliest report as the entry's time, message and sender
            for (k in listOf("time", "message", "sender", "chat")) o.put(k, rec.opt(k))
        }
        o.put("last", maxOf(o.optLong("last", o.optLong("time")), rec.optLong("last", t)))
        for (k in listOf("city", "mobile", "vendor", "size", "driver", "replacement")) {
            if (o.optString(k).isBlank() && rec.optString(k).isNotBlank()) o.put(k, rec.optString(k))
        }
        return false
    }

    private fun recordJson(chat: String, sender: String, text: String, time: Long, c: Capture): JSONObject {
        val match = when {
            c.full -> MatchType.FULL
            !c.inList -> MatchType.CODE
            c.candidates.size == 1 -> MatchType.LAST4
            else -> MatchType.MULTI
        }
        return JSONObject().apply {
            put("id", "wa-${System.currentTimeMillis()}-${idCounter++}")
            put("vehicle", c.candidates.joinToString(" / "))
            put("chat", chat)
            put("sender", sender)
            put("message", text)
            put("time", time)
            put("match", match)
            put("typed", c.typed)
            put("candidates", JSONArray(c.candidates))
            put("city", c.city)
            put("replacement", c.replacement)
            put("mobile", c.mobile)
            put("vendor", c.vendor)
            put("size", c.size)
            put("driver", c.driver)
        }
    }

    private fun unmatchedJson(u: UnmatchedMessage) = JSONObject().apply {
        put("chat", u.chat)
        put("sender", u.sender)
        put("text", u.text)
        put("time", u.time)
        put("codes", JSONArray(u.codes))
    }

    private fun loadUnmatched(p: SharedPreferences, now: Long): List<UnmatchedMessage> {
        val arr = try { JSONArray(p.getString(KEY_UNMATCHED, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val codes = o.optJSONArray("codes") ?: return@mapNotNull null
            UnmatchedMessage(
                chat = o.optString("chat"),
                sender = o.optString("sender"),
                text = o.optString("text"),
                time = o.optLong("time"),
                codes = (0 until codes.length()).map { codes.optString(it) }
            )
        }.filter { now - it.time < UNMATCHED_TTL_MS }
    }

    private fun loadRecords(p: SharedPreferences): JSONArray =
        try { JSONArray(p.getString(KEY_RECORDS, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }

    private fun loadSeen(p: SharedPreferences): JSONObject =
        try { JSONObject(p.getString(KEY_SEEN, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun pruneSeen(seen: JSONObject, now: Long): JSONObject {
        val out = JSONObject()
        seen.keys().forEach { k ->
            val t = seen.optLong(k)
            if (now - t < SEEN_TTL_MS) out.put(k, t)
        }
        return out
    }

    private fun capSeen(seen: JSONObject): JSONObject {
        if (seen.length() <= MAX_SEEN) return seen
        val newest = seen.keys().asSequence()
            .map { it to seen.optLong(it) }
            .sortedByDescending { it.second }
            .take(MAX_SEEN)
        return JSONObject().apply { newest.forEach { (k, v) -> put(k, v) } }
    }

    private fun trimRecords(arr: JSONArray): JSONArray {
        if (arr.length() <= MAX_RECORDS) return arr
        val out = JSONArray()
        for (i in arr.length() - MAX_RECORDS until arr.length()) out.put(arr.get(i))
        return out
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
