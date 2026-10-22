package com.logistics.vehiclereport

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What the WhatsApp posts say for a vehicle on a day: posted, not posted, or posted only as a shared last 4 ("check"). */
enum class DayStatus { REPORTED, PENDING, CHECK }

/** What the DC marks by hand on Today: still to come, arrived at the DC, left the DC. */
enum class Gate(val code: String) {
    YET("Y"), REPORTED("R"), DEPARTED("D");
    companion object { fun of(code: String?) = values().firstOrNull { it.code == code } }
}

/** A hand mark with the times it was set (0 = not set). */
data class GateMark(val gate: Gate, val reportedAt: Long = 0, val departedAt: Long = 0)

/** What Today shows for a vehicle. A post alone means "yet to report" until the DC marks it reported. */
enum class TodayState { NOT_POSTED, CHECK, YET, REPORTED, DEPARTED }

data class VehicleDay(
    val vehicle: DedicatedVehicle,
    val status: DayStatus,
    val hits: List<VehicleRecord>,
    /** false = reported with its full number in the group but not in your vehicle list. */
    val inList: Boolean = true,
    /** The DC's hand mark for the day, if any. */
    val mark: GateMark? = null,
    /** Days before the marks existed: a post counts as reported, as it always did. */
    val legacy: Boolean = false
) {
    val state: TodayState get() = when (mark?.gate) {
        Gate.REPORTED -> TodayState.REPORTED
        Gate.DEPARTED -> TodayState.DEPARTED
        Gate.YET -> TodayState.YET
        null -> when (status) {
            DayStatus.PENDING -> TodayState.NOT_POSTED
            DayStatus.CHECK -> TodayState.CHECK
            DayStatus.REPORTED -> if (legacy) TodayState.REPORTED else TodayState.YET
        }
    }

    /** At the DC (or already left): what the day's "reported" count means. */
    val arrived: Boolean get() = state == TodayState.REPORTED || state == TodayState.DEPARTED

    /** Posted today with only the last 4 digits (or a last 4 shared by several vehicles): the full number is not confirmed. */
    val last4Only: Boolean get() = hits.isNotEmpty() && hits.none { it.match == MatchType.FULL }

    /** When the camera read this vehicle's number plate today (0 = not scanned). */
    val scannedAt: Long get() = hits.maxOfOrNull { it.scannedAt } ?: 0L
}

object ReportBuilder {

    const val DAY_MS = 24L * 60 * 60 * 1000

    fun dateFmt() = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    fun timeFmt() = SimpleDateFormat("hh:mm a", Locale.US)

    fun startOfDay(time: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /**
     * The DC's hand marks: [forDay] gives a day's marks by vehicle number; days before [since] had no marks,
     * so there a post still counts as reported. The default (no marks, every day before) is the old behaviour.
     */
    class Gates(val since: Long = Long.MAX_VALUE, val forDay: (Long) -> Map<String, GateMark> = { emptyMap() }) {
        fun legacy(dayStart: Long) = dayStart < since
    }

    /** Status of every dedicated vehicle for the day starting at [dayStart], with the DC's [marks] for that day. */
    fun dayStatus(
        vehicles: List<DedicatedVehicle>, records: List<VehicleRecord>, dayStart: Long,
        marks: Map<String, GateMark> = emptyMap(), legacy: Boolean = false
    ): List<VehicleDay> {
        val day = records.filter { it.time >= dayStart && it.time < dayStart + DAY_MS }.sortedBy { it.time }
        // Record-only vehicles appear only on days they reported (or were marked); they are never "Not posted".
        return vehicles.mapNotNull { v ->
            val hits = day.filter { v.number in it.candidates }
            val mark = marks[v.number]
            if (!v.dedicated && hits.isEmpty() && mark == null) return@mapNotNull null
            val status = when {
                hits.isEmpty() -> DayStatus.PENDING
                hits.any { it.match != MatchType.MULTI } -> DayStatus.REPORTED
                else -> DayStatus.CHECK
            }
            VehicleDay(v, status, hits, mark = mark, legacy = legacy)
        }
    }

    fun dayStatus(vehicles: List<DedicatedVehicle>, records: List<VehicleRecord>, dayStart: Long, gates: Gates) =
        dayStatus(vehicles, records, dayStart, gates.forDay(dayStart), gates.legacy(dayStart))

    /** Words for a state, as shown in the app and written in the Excel reports. */
    fun stateLabel(st: TodayState) = when (st) {
        TodayState.NOT_POSTED -> "Not posted"
        TodayState.CHECK -> "Check – last 4 shared with another vehicle"
        TodayState.YET -> "Yet to report"
        TodayState.REPORTED -> "Reported"
        TodayState.DEPARTED -> "Departed"
    }

    /** Reported vehicles in the order they reported (earliest first), then Check, then Not Reported. */
    /**
     * Vehicles reported with their full number that day but missing from the vehicle list,
     * with the details written in the message (type, mobile, vendor, location).
     */
    fun notInList(
        vehicles: List<DedicatedVehicle>, records: List<VehicleRecord>, dayStart: Long,
        marks: Map<String, GateMark> = emptyMap(), legacy: Boolean = false
    ): List<VehicleDay> {
        val known = vehicles.map { it.number }.toSet()
        return records.filter {
            it.time >= dayStart && it.time < dayStart + DAY_MS && (it.match == MatchType.FULL || it.match == MatchType.CODE) && it.vehicle !in known
        }.sortedBy { it.time }.groupBy { it.vehicle }.map { (num, hits) ->
            fun pick(f: (VehicleRecord) -> String) = hits.lastOrNull { f(it).isNotBlank() }?.let(f).orEmpty()
            VehicleDay(
                DedicatedVehicle(num, type = pick { it.size }, driver = pick { it.driver }, mobile = pick { it.mobile }, transporter = pick { it.vendor }, route = pick { it.city }),
                DayStatus.REPORTED, hits, inList = false, mark = marks[num], legacy = legacy
            )
        }
    }

    /**
     * Every vehicle number appears once per date. Entries of one day are folded together when they are
     * the same vehicle: the same number; a "check" entry (last 4 shared by several vehicles) once one of
     * those vehicles is reported by itself; a replacement vehicle posted on its own and also as the
     * replacement of a dedicated vehicle. The earliest report is kept, counts add up, blank details are filled.
     */
    fun uniquePerDay(records: List<VehicleRecord>): List<VehicleRecord> {
        val out = mutableListOf<VehicleRecord>()
        for ((_, day) in records.groupBy { startOfDay(it.time) }) {
            val list = day.sortedBy { it.time }.toMutableList()
            fun fold(into: VehicleRecord, other: VehicleRecord): VehicleRecord {
                val first = if (other.time < into.time) other else into
                return into.copy(
                    time = first.time, message = first.message, sender = first.sender, chat = first.chat,
                    count = into.count + other.count, lastTime = maxOf(into.lastTime, other.lastTime),
                    city = into.city.ifBlank { other.city }, mobile = into.mobile.ifBlank { other.mobile },
                    vendor = into.vendor.ifBlank { other.vendor }, size = into.size.ifBlank { other.size },
                    driver = into.driver.ifBlank { other.driver }, replacement = into.replacement.ifBlank { other.replacement },
                    senders = (into.senders + other.senders).distinct(),
                    scannedAt = maxOf(into.scannedAt, other.scannedAt)
                )
            }
            // 1. Same vehicle number
            val byVehicle = LinkedHashMap<String, VehicleRecord>()
            for (r in list) byVehicle[r.vehicle] = byVehicle[r.vehicle]?.let { fold(it, r) } ?: r
            val merged = byVehicle.values.toMutableList()
            // 2a. A code that was not in the list, once the vehicle is matched (list updated later)
            for (c in merged.filter { it.match == MatchType.CODE }) {
                val target = merged.firstOrNull { it !== c && it.match != MatchType.CODE && (it.typed == c.vehicle || (it.match != MatchType.FULL && VehicleParser.last4(it.vehicle) == c.vehicle)) } ?: continue
                merged[merged.indexOf(target)] = fold(target, c); merged.remove(c)
            }
            // 2. "Check" entries whose vehicle was also reported on its own
            for (m in merged.filter { it.match == MatchType.MULTI }) {
                val target = merged.firstOrNull { it !== m && it.match != MatchType.MULTI && it.vehicle in m.candidates } ?: continue
                merged[merged.indexOf(target)] = fold(target, m); merged.remove(m)
            }
            // 3. A replacement vehicle posted on its own -> into the dedicated vehicle it replaced
            for (x in merged.toList()) {
                if (x !in merged || x.replacement.isNotBlank()) continue
                val owner = merged.firstOrNull { it !== x && it.replacement == x.vehicle } ?: continue
                merged[merged.indexOf(owner)] = fold(owner, x); merged.remove(x)
            }
            out += merged
        }
        return out
    }

    fun timeWise(days: List<VehicleDay>): List<VehicleDay> =
        days.sortedWith(
            compareBy<VehicleDay> { if (it.state == TodayState.NOT_POSTED) 1 else 0 }
                .thenBy { it.hits.firstOrNull()?.time ?: it.mark?.reportedAt?.takeIf { t -> t > 0 } ?: Long.MAX_VALUE }
        )

    private val DETAIL_HEADERS = listOf("Vehicle Type", "Driver Name", "Driver Mobile", "Transporter", "Route")
    private fun details(v: DedicatedVehicle?) =
        listOf(v?.type.orEmpty(), v?.driver.orEmpty(), v?.mobile.orEmpty(), v?.transporter.orEmpty(), v?.route.orEmpty())

    /** Dispatch From, Location, Store Code, Store Name, Region, Driver Mobile, Vendor (msg), Vendor (list), Vendor Code, Matched */
    /** Masters and rate card used to fill the store, vendor and cost columns. */
    class Lookups(val masters: Masters?, val rates: RateCard?)

    private fun place(
        city: String, mobile: String, vendor: String, lk: Lookups?,
        chat: String = "", message: String = "", transporter: String = "", size: String = "", listType: String = "",
        driver: String = ""
    ): Array<String> {
        val st = lk?.masters?.matchStore(city)
        val vd = lk?.masters?.resolveVendor(vendor, message, chat, transporter)
        val storeName = st?.let { if (it.others > 0) "${it.store.name} (+${it.others} more – check)" else it.store.name }.orEmpty()
        // Vehicle type: as written in the message ("22 FT"), else the vehicle list's type.
        val type = size.ifBlank { listType }
        val rate = st?.let { lk.rates?.lookup(it.store.code, type) }
        return arrayOf(Masters.DISPATCH_FROM, city, st?.store?.code.orEmpty(), storeName, st?.store?.region.orEmpty(),
            mobile, driver, vendor, vd?.name.orEmpty(), vd?.code.orEmpty(), type, if (rate == null) "" else rate.vehicleType, rate?.amount.orEmpty(),
            Masters.matchedLabel(st != null, vd != null))
    }

    private val PLACE_HEADERS = listOf("Dispatch From", "Location", "Store Code", "Store Name", "Region",
        "Driver Mobile (msg)", "Driver Name (msg)", "Vendor (msg)", "Vendor (list)", "Vendor Code", "Vehicle Type", "Rate Card Type", "Rate (₹)", "Matched")

    /** "Total" row under a report: sums the Rate column. */
    private fun totalRow(header: List<String>, rows: List<List<String>>, rateCol: Int): List<String> {
        val vals = rows.mapNotNull { it.getOrNull(rateCol)?.toDoubleOrNull() }
        val total = vals.sum()
        return header.indices.map { i ->
            when (i) {
                0 -> "TOTAL"
                rateCol - 1 -> "${vals.size} trip(s) with rate"
                rateCol -> if (total == Math.floor(total)) total.toLong().toString() else "%.2f".format(total)
                else -> ""
            }
        }
    }

    // ---------- 1. Daily dedicated report ----------

    val DAILY_WIDTHS = listOf(6, 12, 16, 8, 13, 16, 14, 22, 24, 22, 12, 12, 20, 18, 10, 30, 18, 15, 18, 22, 34, 12, 12, 12, 14, 11, 18, 12, 12, 18, 22, 9, 45, 26)

    /** Daily report for every day from [from] to [to] (inclusive), numbered straight through, with a total. */
    fun dailyRows(
        vehicles: List<DedicatedVehicle>, records: List<VehicleRecord>, from: Long, to: Long = from, lk: Lookups? = null,
        gates: Gates = Gates()
    ): List<List<String>> {
        val header = listOf("S.No", "Date", "Vehicle Number", "Last 4") + DETAIL_HEADERS +
            listOf("Status", "Reported At", "Departed At") + PLACE_HEADERS + listOf("Replacement Vehicle No", "First Report", "Last Report", "Reported By", "WhatsApp Group", "Times Reported", "Message",
                "Number Check")
        val rows = mutableListOf(header)
        var sn = 1
        var day = startOfDay(from)
        val last = startOfDay(to)
        while (day <= last) {
            sn = oneDay(vehicles, records, day, lk, rows, sn, gates)
            day = startOfDay(day + DAY_MS + DAY_MS / 2)
        }
        val rateCol = header.indexOf("Rate (₹)")
        rows += totalRow(header, rows.drop(1), rateCol)
        return rows
    }

    private fun oneDay(
        vehicles: List<DedicatedVehicle>, records: List<VehicleRecord>, dayStart: Long, lk: Lookups?,
        rows: MutableList<List<String>>, firstSn: Int, gates: Gates
    ): Int {
        val date = dateFmt().format(Date(dayStart))
        val t = timeFmt()
        fun at(ms: Long) = if (ms > 0) t.format(Date(ms)) else ""
        val marks = gates.forDay(dayStart)
        val legacy = gates.legacy(dayStart)
        fun check(d: VehicleDay) = when {
            d.hits.isEmpty() -> ""
            d.scannedAt > 0 -> "Plate scanned ${at(d.scannedAt)}"
            d.last4Only -> "Last 4 only – full number not checked"
            else -> "Full number posted"
        }
        var sn = firstSn
        for (d in timeWise(dayStatus(vehicles, records, dayStart, marks, legacy))) {
            val v = d.vehicle
            val base = listOf(sn++.toString(), date, v.number, VehicleParser.last4(v.number)) + details(v)
            val times = listOf(at(d.mark?.reportedAt ?: 0), at(d.mark?.departedAt ?: 0))
            rows += if (d.hits.isEmpty()) {
                val label = stateLabel(d.state) + if (!v.dedicated) " (dedicated storage)" else ""
                base + listOf(label) + times + listOf(Masters.DISPATCH_FROM) + List(PLACE_HEADERS.size - 2) { "" } +
                    listOf("Not posted") + List(5) { "" } + listOf("0", "", "")
            } else {
                val f = d.hits.first()
                val repl = d.hits.lastOrNull { it.replacement.isNotBlank() }?.replacement.orEmpty()
                val status = stateLabel(d.state) + when {
                    d.state == TodayState.CHECK -> ""
                    repl.isNotBlank() -> " (Replacement)"
                    !v.dedicated -> " (dedicated storage)"
                    else -> ""
                }
                base + listOf(status) + times + listOf(
                    *place(d.hits.lastOrNull { it.city.isNotBlank() }?.city.orEmpty(),
                        d.hits.lastOrNull { it.mobile.isNotBlank() }?.mobile.orEmpty(),
                        d.hits.lastOrNull { it.vendor.isNotBlank() }?.vendor.orEmpty(), lk,
                        f.chat, d.hits.last().message, v.transporter,
                        d.hits.lastOrNull { it.size.isNotBlank() }?.size.orEmpty(), v.type,
                        d.hits.lastOrNull { it.driver.isNotBlank() }?.driver.orEmpty()),
                    repl,
                    t.format(Date(f.time)), t.format(Date(d.hits.maxOf { it.lastTime })),
                    f.sender, f.chat, d.hits.sumOf { it.count }.toString(), f.message, check(d)
                )
            }
        }
        // Full numbers posted that day that are not in the list
        val known = vehicles.map { it.number }.toSet()
        records.filter {
            it.time >= dayStart && it.time < dayStart + DAY_MS && (it.match == MatchType.FULL || it.match == MatchType.CODE) && it.vehicle !in known
        }.sortedBy { it.time }.groupBy { it.vehicle }.forEach { (num, hits) ->
            val f = hits.first()
            val day = VehicleDay(DedicatedVehicle(num), DayStatus.REPORTED, hits, inList = false, mark = marks[num], legacy = legacy)
            rows += listOf(sn++.toString(), date, num, VehicleParser.last4(num)) + details(null) + listOf(
                stateLabel(day.state) + " (not in dedicated list)", at(day.mark?.reportedAt ?: 0), at(day.mark?.departedAt ?: 0), *place(f.city, f.mobile, f.vendor, lk, f.chat, f.message, "", f.size, "", f.driver),
                f.replacement, t.format(Date(f.time)), t.format(Date(hits.maxOf { it.lastTime })),
                f.sender, f.chat, hits.sumOf { it.count }.toString(), f.message, check(day)
            )
        }
        return sn
    }

    // ---------- 2. Vehicle details ----------

    val DETAILS_WIDTHS = listOf(6, 16, 8, 13, 16, 14, 22, 24, 14, 20, 16)

    fun detailRows(vehicles: List<DedicatedVehicle>, records: List<VehicleRecord>, gates: Gates = Gates()): List<List<String>> {
        val today = dayStatus(vehicles, records, startOfDay(), gates).associateBy { it.vehicle.number }
        val d = dateFmt(); val t = timeFmt()
        val rows = mutableListOf(
            listOf("S.No", "Vehicle Number", "Last 4") + DETAIL_HEADERS + listOf("Today's Status", "Last Posted", "Last City")
        )
        vehicles.forEachIndexed { i, v ->
            val last = records.filter { v.number in it.candidates }.maxByOrNull { it.time }
            val todayText = today[v.number]?.let { stateLabel(it.state) } ?: stateLabel(TodayState.NOT_POSTED)
            rows += listOf((i + 1).toString(), v.number, VehicleParser.last4(v.number)) + details(v) +
                listOf(
                    todayText,
                    last?.let { d.format(Date(it.time)) + " " + t.format(Date(it.time)) }.orEmpty(),
                    records.filter { v.number in it.candidates && it.city.isNotBlank() }.maxByOrNull { it.time }?.city.orEmpty()
                )
        }
        return rows
    }

    // ---------- 3. Vehicle-wise summary for a date range ----------

    val SUMMARY_WIDTHS = listOf(6, 16, 13, 16, 14, 22, 24, 12, 12, 10, 13, 15, 14, 13, 18, 12)

    fun summaryRows(
        allVehicles: List<DedicatedVehicle>, records: List<VehicleRecord>, from: Long, to: Long, gates: Gates = Gates()
    ): List<List<String>> {
        val vehicles = allVehicles.filter { it.dedicated }   // attendance is for dedicated vehicles
        val d = dateFmt()
        val days = ((startOfDay(to) - startOfDay(from)) / DAY_MS + 1).toInt().coerceAtLeast(1)
        val perDay = (0 until days).map { k ->
            dayStatus(vehicles, records, startOfDay(from + k * DAY_MS + DAY_MS / 2), gates)
                .associate { it.vehicle.number to it.state }
        }
        val rows = mutableListOf(
            listOf("S.No", "Vehicle Number") + DETAIL_HEADERS +
                listOf("From", "To", "Total Days", "Days Reported", "Days Yet to Report", "Days Not Posted", "Days To Check", "Days With Replacement", "Attendance %")
        )
        vehicles.forEachIndexed { i, v ->
            val reported = perDay.count { it[v.number] == TodayState.REPORTED || it[v.number] == TodayState.DEPARTED }
            val yet = perDay.count { it[v.number] == TodayState.YET }
            val check = perDay.count { it[v.number] == TodayState.CHECK }
            val replDays = records.filter {
                v.number in it.candidates && it.replacement.isNotBlank() && it.time >= startOfDay(from) && it.time < startOfDay(to) + DAY_MS
            }.map { startOfDay(it.time) }.distinct().size
            rows += listOf((i + 1).toString(), v.number) + details(v) + listOf(
                d.format(Date(from)), d.format(Date(to)), days.toString(), reported.toString(), yet.toString(),
                (days - reported - yet - check).toString(), check.toString(), replDays.toString(), "${reported * 100 / days}%"
            )
        }
        return rows
    }

    // ---------- 4. WhatsApp log ----------

    val LOG_WIDTHS = listOf(6, 12, 11, 11, 9, 24, 12, 16, 18, 20, 10, 30, 18, 15, 18, 22, 34, 12, 12, 12, 14, 11, 18, 16, 22, 24, 18, 50)

    fun logRows(vehicles: List<DedicatedVehicle>, records: List<VehicleRecord>, lk: Lookups? = null): List<List<String>> {
        val byNumber = vehicles.associateBy { it.number }
        val d = dateFmt(); val t = timeFmt()
        val header = listOf("S.No", "Date", "First Report", "Last Report", "Times Reported", "Vehicle Number", "Sent As", "Match") + PLACE_HEADERS +
            listOf("Replacement Vehicle No", "Driver (list)", "Transporter", "WhatsApp Group", "Sender", "Message")
        val rows = mutableListOf(header)
        records.sortedBy { it.time }.forEachIndexed { i, r ->
            val v = if (r.match == MatchType.MULTI) null else byNumber[r.candidates.firstOrNull()]
            val match = when (r.match) {
                MatchType.FULL -> if (r.scannedAt > 0) "Last 4 + plate scanned" else "Full number"
                MatchType.LAST4 -> "Last 4 digits"
                MatchType.CODE -> "Last 4 – not in vehicle list"
                else -> "Check – multiple"
            }
            rows += listOf(
                (i + 1).toString(), d.format(Date(r.time)), t.format(Date(r.time)), t.format(Date(r.lastTime)), r.count.toString(), r.vehicle, r.typed, match, *place(r.city, r.mobile, r.vendor, lk, r.chat, r.message, v?.transporter.orEmpty(), r.size, v?.type.orEmpty(), r.driver), r.replacement,
                v?.driver.orEmpty(), v?.transporter.orEmpty(), r.chat, r.sender, r.message
            )
        }
        rows += totalRow(header, rows.drop(1), header.indexOf("Rate (₹)"))
        return rows
    }
}
