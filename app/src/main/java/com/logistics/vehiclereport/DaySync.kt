package com.logistics.vehiclereport

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Date-wise copy of the daily vehicle report in the cloud, kept up to date in real time:
 * users/{uid}/days/{yyyy-MM-dd} = that day's vehicles with status, reported / departed times, place, vendor,
 * driver, mobile and the counts. A few seconds after any new WhatsApp post, gate mark or Drishti gate log the
 * day's copy is written again. At 12 am a new day's copy starts; the day just ended is written once more, final.
 * Back days can be opened from the cloud copy on Today (date picker) even when this phone has no data for them.
 */
object DaySync {
    private const val DELAY_MS = 5_000L
    /** Days copied once when this starts for the first time (older data already on the phone). */
    private const val BACKFILL_DAYS = 45
    private const val K_BACKFILLED = "day_sync_backfilled"
    private const val K_LAST_DAY = "day_sync_last_day"

    /** Settings whose change means the day's report changed. */
    private val WATCH = setOf(VehicleStore.KEY_RECORDS, GateMarks.KEY, DrishtiStore.KEY_LOG, DrishtiStore.KEY_GATE_ONLY, VehicleStore.KEY_DEDICATED)

    private val main = Handler(Looper.getMainLooper())
    private var watched: SharedPreferences? = null
    private var app: Context? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key !in WATCH) return@OnSharedPreferenceChangeListener
        main.removeCallbacks(push); main.postDelayed(push, DELAY_MS)
    }
    private val push = Runnable { app?.let { c -> Thread { safe(c) { pushToday(c) } }.start() } }
    private val midnight = Runnable { app?.let { c -> Thread { safe(c) { rollOver(c) } }.start(); scheduleMidnight() } }

    private fun safe(c: Context, go: () -> Unit) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try { go() } catch (t: Throwable) { try { CrashLog.save(c, "Cloud day copy", t) } catch (e: Throwable) { } }
    }

    fun dayKey(dayStart: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(dayStart))

    private fun enabled(c: Context) = Account.isSignedIn(c) && !Account.isLocal(c) && Account.cloudReady(c)
    private fun days(uid: String) = FirebaseFirestore.getInstance().collection("users").document(uid).collection("days")

    fun start(c: Context) {
        stop()
        if (!enabled(c)) return
        app = c.applicationContext
        watched = VehicleStore.prefs(c).also { it.registerOnSharedPreferenceChangeListener(listener) }
        scheduleMidnight()
        // A minute after start (the app opens first), at low priority: missed days, then the one-time copy of older days.
        main.postDelayed({
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    val ctx = c.applicationContext
                    rollOver(ctx)
                    val p = VehicleStore.prefs(ctx)
                    if (!p.getBoolean(K_BACKFILLED, false)) {
                        val data = Data.load(ctx)
                        val today = ReportBuilder.startOfDay()
                        for (i in BACKFILL_DAYS downTo 1) {
                            pushDay(ctx, ReportBuilder.startOfDay(today - i * ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2), final = true, data = data)
                            Thread.sleep(150)
                        }
                        p.edit().putBoolean(K_BACKFILLED, true).apply()
                    }
                } catch (t: Throwable) { try { CrashLog.save(c, "Cloud day copy", t) } catch (e: Throwable) { } }
            }.start()
        }, 60_000)
    }

    /** Everything a day's report is made from, read once. */
    private class Data(val vehicles: List<DedicatedVehicle>, val records: List<VehicleRecord>, val gates: ReportBuilder.Gates) {
        companion object { fun load(c: Context) = Data(VehicleStore.getVehicles(c), VehicleStore.getAll(c), GateMarks.gates(c)) }
    }

    fun stop() {
        watched?.unregisterOnSharedPreferenceChangeListener(listener)
        watched = null
        main.removeCallbacks(push); main.removeCallbacks(midnight)
    }

    private fun scheduleMidnight() {
        main.removeCallbacks(midnight)
        val next = ReportBuilder.startOfDay() + ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2
        val wait = ReportBuilder.startOfDay(next) - System.currentTimeMillis() + 2_000
        main.postDelayed(midnight, wait.coerceAtLeast(1_000))
    }

    /** A new day: the days since the last copy (the day that just ended, or days the phone was off) are written final. */
    private fun rollOver(c: Context) {
        val p = VehicleStore.prefs(c)
        val today = ReportBuilder.startOfDay()
        val last = p.getLong(K_LAST_DAY, 0L)
        if (last in 1 until today) {
            val data = Data.load(c)
            var d = last
            var n = 0
            while (d < today && n < BACKFILL_DAYS) {
                pushDay(c, d, final = true, data = data)
                d = ReportBuilder.startOfDay(d + ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2); n++
            }
        }
        pushToday(c)
    }

    fun pushToday(c: Context) {
        val today = ReportBuilder.startOfDay()
        pushDay(c, today, final = false)
        VehicleStore.prefs(c).edit().putLong(K_LAST_DAY, today).apply()
    }

    /** The day's report as rows, same as Today shows. */
    private fun rows(dayStart: Long, d: Data): List<VehicleDay> {
        val vehicles = d.vehicles
        val records = d.records
        val gates = d.gates
        val extras = ReportBuilder.notInList(vehicles, records, dayStart, gates.forDay(dayStart), gates.legacy(dayStart),
            gates.gateLog(dayStart), gates.gateOnly)
        return ReportBuilder.dayStatus(vehicles, records, dayStart, gates) + extras
    }

    private fun pushDay(c: Context, dayStart: Long, final: Boolean, data: Data? = null) {
        if (!enabled(c)) return
        val uid = Account.uid(c) ?: return
        val list = try { rows(dayStart, data ?: Data.load(c)) } catch (t: Throwable) { return }
        // A back day with nothing posted and nothing marked is not copied (no empty days before the app was used).
        if (final && list.none { it.hits.isNotEmpty() || it.mark != null || it.gate != null }) return
        val rows = list.map { d ->
            val h = d.hits
            val first = h.minByOrNull { it.time }
            val latest = h.maxByOrNull { it.lastTime }
            mapOf(
                "vehicle" to d.vehicle.number,
                "state" to d.state.name,
                "inList" to d.inList,
                "dedicated" to d.vehicle.dedicated,
                "type" to d.vehicle.type,
                "transporter" to d.vehicle.transporter,
                "typed" to (first?.typed ?: ""),
                "replacement" to (h.firstOrNull { it.replacement.isNotBlank() }?.replacement ?: ""),
                "city" to (h.lastOrNull { it.city.isNotBlank() }?.city ?: d.vehicle.route),
                "vendor" to (h.lastOrNull { it.vendor.isNotBlank() }?.vendor ?: ""),
                "driver" to (h.lastOrNull { it.driver.isNotBlank() }?.driver ?: d.vehicle.driver),
                "mobile" to (h.lastOrNull { it.mobile.isNotBlank() }?.mobile ?: d.vehicle.mobile),
                "size" to (h.lastOrNull { it.size.isNotBlank() }?.size ?: ""),
                "posts" to h.sumOf { it.count },
                "firstPost" to (first?.time ?: 0L),
                "lastPost" to (latest?.lastTime ?: 0L),
                "reportedAt" to (d.mark?.reportedAt?.takeIf { it > 0 } ?: d.gate?.checkIn ?: 0L),
                "departedAt" to (d.mark?.departedAt?.takeIf { it > 0 } ?: d.gate?.checkOut ?: 0L)
            )
        }
        val counts = TodayState.values().associate { st -> st.name to list.count { it.state == st } }
        val doc = mapOf(
            "date" to dayKey(dayStart),
            "dayStart" to dayStart,
            "dc" to Masters.dispatchName,
            "total" to list.size,
            "counts" to counts,
            "rows" to rows,
            "final" to final,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try { days(uid).document(dayKey(dayStart)).set(doc) } catch (t: Throwable) { }
    }

    /** One back day read from the cloud copy (for a day this phone has no data for). [done] runs on the main thread. */
    fun fetch(c: Context, dayStart: Long, done: (List<Map<String, Any?>>?) -> Unit) {
        if (!enabled(c)) { done(null); return }
        val uid = Account.uid(c) ?: run { done(null); return }
        days(uid).document(dayKey(dayStart)).get()
            .addOnSuccessListener { s ->
                @Suppress("UNCHECKED_CAST")
                done((s.get("rows") as? List<Map<String, Any?>>))
            }
            .addOnFailureListener { done(null) }
    }
}
