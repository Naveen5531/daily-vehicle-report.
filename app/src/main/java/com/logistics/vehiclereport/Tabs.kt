package com.logistics.vehiclereport

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun afterTextChanged(block: (String) -> Unit) = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) = block(s?.toString().orEmpty())
}

// =====================================================================
// TODAY
// =====================================================================
class TodayTab(private val a: MainActivity, root: View) {
    private val ring: CircularProgressIndicator = root.findViewById(R.id.progressRing)
    private val ringText: TextView = root.findViewById(R.id.ringText)
    private val heroDone: TextView = root.findViewById(R.id.heroDone)
    private val heroTotal: TextView = root.findViewById(R.id.heroTotal)
    private val heroText: TextView = root.findViewById(R.id.heroText)
    private val heroUpdated: TextView = root.findViewById(R.id.heroUpdated)
    private val vendorSection: View = root.findViewById(R.id.vendorSection)
    private val vendorRow: android.widget.LinearLayout = root.findViewById(R.id.vendorRow)
    private val clock = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val banner: View = root.findViewById(R.id.accessBanner)
    private val drishtiStatus: TextView = root.findViewById(R.id.drishtiCardStatus)
    private val chips: ChipGroup = root.findViewById(R.id.todayChips)
    private val chipAll: Chip = root.findViewById(R.id.chipAll)
    /** Vehicles posted with only their last 4 digits (full number not confirmed): scan the plate. */
    private val chipLast4: Chip = root.findViewById(R.id.chipLast4)
    private var last4Filter = false
    private val chipFor: Map<TodayState, Chip> = mapOf(
        TodayState.NOT_POSTED to root.findViewById<Chip>(R.id.chipNotPosted),
        TodayState.YET to root.findViewById<Chip>(R.id.chipYet),
        TodayState.REPORTED to root.findViewById<Chip>(R.id.chipReported),
        TodayState.DEPARTED to root.findViewById<Chip>(R.id.chipDeparted),
        TodayState.CHECK to root.findViewById<Chip>(R.id.chipCheck)
    )
    private val empty: TextView = root.findViewById(R.id.todayEmpty)
    private val adapter = StatusAdapter(
        onOpen = { VehicleDetailSheet.show(a, it.vehicle.number) },
        // Marks are saved in the account's storage; the change refreshes every tab.
        onGate = { d, gate -> hold = true; GateMarks.set(a, ReportBuilder.startOfDay(), d.vehicle.number, gate) },
        onScan = { d -> a.scanPlate(d.vehicle.number) }
    )
    /**
     * Vehicle numbers in the order last shown. After a tap on Yet to report / Reported / Departed every row stays
     * where it is (none moves away or drops out of the chosen filter, so the next row never slides under the finger)
     * until the filter, search or order is changed, or Today is opened again.
     */
    private var shown: List<String> = emptyList()
    private var hold = false

    fun holdOrder() { hold = true }
    fun releaseOrder() { hold = false }
    private val tiles: Map<TodayState, View> = mapOf(
        TodayState.NOT_POSTED to root.findViewById<View>(R.id.statNotPosted),
        TodayState.YET to root.findViewById<View>(R.id.statYet),
        TodayState.REPORTED to root.findViewById<View>(R.id.statReported),
        TodayState.DEPARTED to root.findViewById<View>(R.id.statDeparted)
    )
    private var filter: TodayState? = null
    private var last: Snapshot? = null
    private val sortButton: MaterialButton = root.findViewById(R.id.sortButton)
    private val addExtras: MaterialButton = root.findViewById(R.id.addExtrasButton)
    private var timeWise = VehicleStore.prefs(a).getBoolean("sort_time_wise", true)
    private val search: TextInputEditText = root.findViewById(R.id.todaySearch)
    private var query = ""

    init {
        updateSortLabel()
        search.addTextChangedListener(afterTextChanged {
            hold = false
            query = it.trim()
            last?.let { s -> refresh(s) }
        })
        search.setOnEditorActionListener { v, _, _ ->
            (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(v.windowToken, 0)
            true
        }
        sortButton.setOnClickListener {
            hold = false
            timeWise = !timeWise
            VehicleStore.prefs(a).edit().putBoolean("sort_time_wise", timeWise).apply()
            updateSortLabel()
            last?.let { refresh(it) }
        }
        root.findViewById<RecyclerView>(R.id.todayList).apply {
            layoutManager = LinearLayoutManager(a)
            adapter = this@TodayTab.adapter
        }
        root.findViewById<MaterialButton>(R.id.enableButton).setOnClickListener { a.openAccessSettings() }
        root.findViewById<View>(R.id.drishtiCard).setOnClickListener { a.openDrishti() }
        root.findViewById<MaterialButton>(R.id.drishtiCardOpen).setOnClickListener { a.openDrishti() }

        val labels = mapOf(TodayState.NOT_POSTED to R.string.st_not_posted, TodayState.YET to R.string.gate_yet,
            TodayState.REPORTED to R.string.st_reported, TodayState.DEPARTED to R.string.gate_departed)
        // Counts sit on the emerald card: peach = not posted, light blue = yet to report, mint = reported, white = departed.
        val colors = mapOf(TodayState.NOT_POSTED to R.color.peach, TodayState.YET to R.color.sky,
            TodayState.REPORTED to R.color.mint, TodayState.DEPARTED to R.color.surface)
        tiles.forEach { (state, tile) ->
            tile.findViewById<TextView>(R.id.statLabel).setText(labels.getValue(state))
            tile.findViewById<TextView>(R.id.statNumber).setTextColor(Ui.color(a, colors.getValue(state)))
            tile.setOnClickListener { chipOf(if (filter == state) null else state).isChecked = true }
        }
        chips.setOnCheckedStateChangeListener { _, ids ->
            hold = false
            val id = ids.firstOrNull()
            last4Filter = id == R.id.chipLast4
            filter = chipFor.entries.firstOrNull { it.value.id == id }?.key
            last?.let { refresh(it) }
        }
    }

    private fun updateSortLabel() {
        sortButton.setText(if (timeWise) R.string.sort_time else R.string.sort_status)
    }

    private fun chipOf(s: TodayState?): Chip = s?.let { chipFor.getValue(it) } ?: chipAll

    private fun showVendors(days: List<VehicleDay>) {
        val stats = vendorStats(days, MasterData.get(a), a.getString(R.string.vendor_other))
        val named = stats.count { it.first != a.getString(R.string.vendor_other) }
        vendorSection.visibility = if (named == 0) View.GONE else View.VISIBLE
        vendorRow.removeAllViews()
        if (named == 0) return
        val inflater = android.view.LayoutInflater.from(a)
        for ((name, done, total) in stats) {
            val card = inflater.inflate(R.layout.item_vendor, vendorRow, false) as com.google.android.material.card.MaterialCardView
            card.findViewById<TextView>(R.id.vendorName).text = name
            card.findViewById<TextView>(R.id.vendorCount).text = android.text.SpannableStringBuilder().apply {
                append(done.toString())
                val st = length
                append("/$total")
                setSpan(android.text.style.ForegroundColorSpan(Ui.color(a, R.color.faint)), st, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(0.7f), st, length, 0)
            }
            card.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.vendorBar)
                .progress = if (total == 0) 0 else done * 100 / total
            // Tap a vendor to list only its vehicles; tap again to show all.
            val chosen = query.equals(name, ignoreCase = true)
            card.strokeWidth = if (chosen) (2 * a.resources.displayMetrics.density).toInt() else 0
            card.strokeColor = Ui.color(a, R.color.emerald)
            card.setOnClickListener { search.setText(if (chosen) "" else name) }
            vendorRow.addView(card)
        }
    }

    fun refresh(s: Snapshot) {
        last = s
        banner.visibility = if (s.hasAccess) View.GONE else View.VISIBLE
        drishtiStatus.text = DrishtiActivity.statusText(a)
        // Your vehicle list, plus vehicles posted with their full number that are not in it yet.
        val today = ReportBuilder.startOfDay()
        val gates = GateMarks.gates(a)
        val marks = gates.forDay(today)
        val extras = ReportBuilder.notInList(s.vehicles, s.records, today, marks, gates.legacy(today))
        val days = ReportBuilder.dayStatus(s.vehicles, s.records, today, marks, gates.legacy(today)) + extras
        val counts = TodayState.values().associateWith { st -> days.count { it.state == st } }
        val total = days.size
        // Only full numbers can be added to the list; a 4-digit code alone is not enough.
        // Full numbers are added to the list automatically (record only); codes alone cannot be added.
        val addable = emptyList<VehicleDay>()
        addExtras.visibility = if (addable.isEmpty()) View.GONE else View.VISIBLE
        addExtras.text = a.resources.getQuantityString(R.plurals.add_extras, addable.size, addable.size)
        addExtras.setOnClickListener {
            MaterialAlertDialogBuilder(a)
                .setTitle(a.resources.getQuantityString(R.plurals.add_extras, addable.size, addable.size))
                .setMessage(addable.joinToString("\n") { Ui.joinNonBlank(it.vehicle.number, it.vehicle.type, it.vehicle.transporter) })
                .setPositiveButton(R.string.import_add) { _, _ ->
                    val added = VehicleStore.importVehicles(a, addable.map { it.vehicle }, replace = false)
                    android.widget.Toast.makeText(a, a.getString(R.string.extras_added, added), android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        // "Reported" on the card = marked Reported or Departed at the DC.
        val done = counts.getValue(TodayState.REPORTED) + counts.getValue(TodayState.DEPARTED)
        val pending = total - done
        val toCheck = counts.getValue(TodayState.CHECK)

        val pct = if (total == 0) 0 else done * 100 / total
        ring.setProgressCompat(pct, true)
        ringText.text = "$pct%"
        heroDone.text = done.toString()
        heroTotal.text = "/$total"
        heroText.text = a.getString(when {
            total == 0 -> R.string.hero_no_vehicles
            pending == 0 -> R.string.hero_all_done
            else -> R.string.hero_sub_reported
        }) + if (toCheck > 0) " · " + a.getString(R.string.hero_to_check, toCheck) else ""
        val lastPost = s.records.filter { it.time >= ReportBuilder.startOfDay() }.maxOfOrNull { it.time }
        heroUpdated.text = if (lastPost == null) a.getString(R.string.no_posts_yet)
            else a.getString(R.string.updated_at, clock.format(Date(lastPost)))

        tiles.forEach { (st, tile) ->
            tile.findViewById<TextView>(R.id.statNumber).text = counts.getValue(st).toString()
            tile.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (filter == st) 0x26FFFFFF else android.graphics.Color.TRANSPARENT)
        }
        showVendors(days)
        chipAll.text = a.getString(R.string.chip_all, total)
        val chipText = mapOf(TodayState.NOT_POSTED to R.string.chip_not_posted, TodayState.YET to R.string.chip_yet,
            TodayState.REPORTED to R.string.chip_reported, TodayState.DEPARTED to R.string.chip_departed, TodayState.CHECK to R.string.chip_check)
        chipFor.forEach { (st, chip) -> chip.text = a.getString(chipText.getValue(st), counts.getValue(st)) }
        val last4 = days.count { it.last4Only }
        chipLast4.text = a.getString(R.string.chip_last4, last4)
        chipLast4.visibility = if (last4 > 0 || last4Filter) View.VISIBLE else View.GONE
        // "Check" only when a shared last 4 needs a look (or it is the chosen filter).
        chipFor.getValue(TodayState.CHECK).visibility = if (toCheck > 0 || filter == TodayState.CHECK) View.VISIBLE else View.GONE

        val m = MasterData.get(a)
        val filtered = days.filter {
            (if (last4Filter) it.last4Only else filter == null || it.state == filter) && matchesSearch(it, query, m)
        }
        val list = if (timeWise) ReportBuilder.timeWise(filtered) else {
            // Status-wise: what still needs action first.
            val order = mapOf(TodayState.NOT_POSTED to 0, TodayState.CHECK to 1, TodayState.YET to 2, TodayState.REPORTED to 3, TodayState.DEPARTED to 4)
            filtered.sortedWith(compareBy<VehicleDay> { order.getValue(it.state) }.thenBy { it.hits.firstOrNull()?.time ?: Long.MAX_VALUE })
        }
        val rows = if (hold && shown.isNotEmpty()) {
            // Keep the rows on screen in their places (with their new status); anything new goes below.
            val byNumber = days.associateBy { it.vehicle.number }
            val kept = shown.mapNotNull { byNumber[it] }
            val keptSet = kept.mapTo(HashSet()) { it.vehicle.number }
            kept + list.filter { it.vehicle.number !in keptSet }
        } else list
        shown = rows.map { it.vehicle.number }
        adapter.submit(rows)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            total == 0 -> a.getString(R.string.today_empty_no_vehicles)
            query.isNotEmpty() -> a.getString(R.string.today_empty_search, query)
            else -> a.getString(R.string.today_empty_filter)
        }
    }
}


/**
 * Vendor of a vehicle for the day: the vendor saved in the vehicle list, else the one written in today's posts,
 * spelled as in the vendor list ("Happy road lines" and "HAPPY ROAD LINES" are one vendor).
 */
private fun vendorOf(d: VehicleDay, m: Masters): String {
    val written = d.vehicle.transporter.trim().ifBlank { d.hits.lastOrNull { it.vendor.isNotBlank() }?.vendor?.trim().orEmpty() }
    if (written.isBlank()) return ""
    return m.resolveVendor(written, "", "", written)?.name?.trim()?.ifBlank { null } ?: written
}

/** "By vendor": every vehicle on today's list (the same ones counted at the top), reported (or departed) / total per vendor. */
private fun vendorStats(days: List<VehicleDay>, m: Masters, other: String): List<Triple<String, Int, Int>> {
    val groups = LinkedHashMap<String, MutableList<VehicleDay>>()
    val names = HashMap<String, String>()
    for (d in days) {
        val name = vendorOf(d, m).ifBlank { other }
        val key = name.lowercase().filter { it.isLetterOrDigit() }
        names.putIfAbsent(key, name)
        groups.getOrPut(key) { ArrayList() } += d
    }
    return groups.map { (key, list) -> Triple(names.getValue(key), list.count { it.arrived }, list.size) }
        .sortedWith(compareBy<Triple<String, Int, Int>> { if (it.first == other) 1 else 0 }
            .thenByDescending { it.third }.thenBy { it.first })
}

/** Today's quick search: vehicle number or last 4, vendor, driver, mobile, type, store or place, who posted. */
private fun matchesSearch(d: VehicleDay, query: String, m: Masters): Boolean {
    if (query.isBlank()) return true
    fun sq(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
    val words = query.split(Regex("\\s+")).map { sq(it) }.filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val v = d.vehicle
    val hay = sq(buildString {
        listOf(v.number, v.type, v.driver, v.mobile, v.transporter, v.route, vendorOf(d, m)).forEach { append(it).append(' ') }
        d.hits.forEach { h -> listOf(h.city, h.vendor, h.mobile, h.driver, h.size, h.sender, h.typed, h.replacement).forEach { append(it).append(' ') } }
    })
    return words.all { hay.contains(it) }
}

// =====================================================================
// WHATSAPP
// =====================================================================
class WhatsAppTab(private val a: MainActivity, root: View) {
    private enum class Filter { TODAY, CHECK, FULL, ALL }

    private val chipToday: Chip = root.findViewById(R.id.waChipToday)
    private val chipCheck: Chip = root.findViewById(R.id.waChipCheck)
    private val chipFull: Chip = root.findViewById(R.id.waChipFull)
    private val unmatchedCard: View = root.findViewById(R.id.unmatchedCard)
    private val unmatchedTitle: TextView = root.findViewById(R.id.unmatchedTitle)
    private val unmatchedText: TextView = root.findViewById(R.id.unmatchedText)
    private val empty: TextView = root.findViewById(R.id.feedEmpty)
    private val adapter = FeedAdapter { VehicleDetailSheet.showForRecord(a, it) }
    private val groupName: TextView = root.findViewById(R.id.groupName)
    private val groupHint: TextView = root.findViewById(R.id.groupHint)
    private val senderName: TextView = root.findViewById(R.id.senderName)
    private val senderHint: TextView = root.findViewById(R.id.senderHint)
    private var filter = Filter.TODAY
    private var last: Snapshot? = null
    private var lastUnmatchedCode: String? = null
    private val time = SimpleDateFormat("hh:mm a", Locale.getDefault())

    init {
        root.findViewById<RecyclerView>(R.id.feedList).apply {
            layoutManager = LinearLayoutManager(a)
            adapter = this@WhatsAppTab.adapter
        }
        val openPicker = View.OnClickListener { pickGroup() }
        root.findViewById<View>(R.id.groupCard).setOnClickListener(openPicker)
        root.findViewById<MaterialButton>(R.id.changeGroup).setOnClickListener(openPicker)
        val openSenders = View.OnClickListener { pickSenders() }
        root.findViewById<View>(R.id.senderCard).setOnClickListener(openSenders)
        root.findViewById<MaterialButton>(R.id.changeSender).setOnClickListener(openSenders)
        root.findViewById<ChipGroup>(R.id.waChips).setOnCheckedStateChangeListener { _, ids ->
            filter = when (ids.firstOrNull()) {
                R.id.waChipCheck -> Filter.CHECK
                R.id.waChipFull -> Filter.FULL
                R.id.waChipAll -> Filter.ALL
                else -> Filter.TODAY
            }
            last?.let { refresh(it) }
        }
        root.findViewById<MaterialButton>(R.id.unmatchedAdd).setOnClickListener {
            VehicleFormSheet.show(a, null, lastUnmatchedCode)
        }
    }

    private fun showGroup() {
        val g = VehicleStore.getSelectedGroup(a)
        groupName.text = g.ifBlank { a.getString(R.string.all_chats) }
        groupHint.setText(if (g.isBlank()) R.string.group_hint_all else R.string.group_hint_one)
        val senders = VehicleStore.getAllowedSenders(a)
        senderName.text = if (senders.isEmpty()) a.getString(R.string.anyone) else senders.joinToString(", ")
        senderHint.text = if (senders.isEmpty()) a.getString(R.string.sender_hint_any) else a.getString(R.string.sender_hint_some, senders.size)
    }

    /** Pick which numbers/people to read. Lists people seen posting in the chosen group. */
    private fun pickSenders() {
        val current = VehicleStore.getAllowedSenders(a)
        // Everyone seen posting (notifications, screen, imported chats), plus the ones already chosen.
        val names = (VehicleStore.getKnownSenders(a).map { it.name } + VehicleStore.recordSenders(a) + current)
            .filter { it.isNotBlank() && it != "You" }
            .distinctBy { it.trim().lowercase() }
        val checked = BooleanArray(names.size) { i -> current.any { VehicleStore.senderMatches(names[i], it) } }

        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.pick_sender_title)
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(R.string.save) { _, _ ->
                saveSenders(names.filterIndexed { i, _ -> checked[i] })
            }
            .setNeutralButton(R.string.type_sender) { _, _ -> typeSender(names.filterIndexed { i, _ -> checked[i] }) }
            .setNegativeButton(R.string.clear_anyone) { _, _ -> saveSenders(emptyList()) }
            .show()
    }

    private fun typeSender(keep: List<String>) {
        val input = android.widget.EditText(a).apply {
            setSingleLine()
            hint = a.getString(R.string.sender_hint_input)
        }
        val box = android.widget.FrameLayout(a).apply {
            val pad = (20 * a.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.type_sender)
            .setMessage(R.string.type_sender_help)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val v = input.text.toString().trim()
                saveSenders(if (v.isEmpty()) keep else keep + v)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveSenders(list: List<String>) {
        VehicleStore.setAllowedSenders(a, list)
        showGroup()
        a.refreshAll()   // the lists and reports follow the choice straight away
        android.widget.Toast.makeText(
            a,
            if (list.isEmpty()) a.getString(R.string.senders_cleared) else a.getString(R.string.senders_saved, list.joinToString(", ")),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /** One group only, or all chats. Lists groups seen in WhatsApp notifications. */
    private fun pickGroup() {
        val current = VehicleStore.getSelectedGroup(a)
        val chats = VehicleStore.getKnownChats(a)
        val labels = mutableListOf(a.getString(R.string.all_chats))
        val values = mutableListOf("")
        chats.forEach { c ->
            labels += if (c.isGroup) "👥  ${c.name}" else "👤  ${c.name}"
            values += c.name
        }
        if (current.isNotBlank() && values.none { it.equals(current, ignoreCase = true) }) {
            labels += "👥  $current"; values += current
        }
        labels += a.getString(R.string.type_group_name)
        val checked = values.indexOfFirst { it.equals(current, ignoreCase = true) }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(a)
            .setTitle(if (chats.isEmpty()) R.string.pick_group_empty else R.string.pick_group_title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                d.dismiss()
                if (which == labels.lastIndex) typeGroupName(current) else select(values[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun typeGroupName(current: String) {
        val input = android.widget.EditText(a).apply {
            setText(current)
            setSingleLine()
            hint = a.getString(R.string.group_name_hint)
        }
        val box = android.widget.FrameLayout(a).apply {
            val pad = (20 * a.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.type_group_name)
            .setMessage(R.string.type_group_help)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> select(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun select(name: String) {
        VehicleStore.setSelectedGroup(a, name)
        showGroup()
        android.widget.Toast.makeText(
            a,
            if (name.isBlank()) a.getString(R.string.reading_all) else a.getString(R.string.reading_only, name.trim()),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    fun refresh(s: Snapshot) {
        last = s
        showGroup()
        val start = ReportBuilder.startOfDay()
        val today = s.records.filter { it.time >= start }
        chipToday.text = a.getString(R.string.wa_chip_today, today.size)
        chipCheck.text = a.getString(R.string.chip_check, today.count { it.match == MatchType.MULTI })
        chipFull.text = a.getString(R.string.wa_chip_full, today.count { it.match == MatchType.FULL })

        val um = s.unmatched.filter { it.time >= start }
        unmatchedCard.visibility = if (um.isEmpty()) View.GONE else View.VISIBLE
        unmatchedTitle.text = a.getString(R.string.unmatched_title, um.size)
        unmatchedText.text = um.take(6).joinToString("\n") {
            "• ${it.codes.joinToString(", ")} — ${it.sender} at ${time.format(Date(it.time))}"
        }
        lastUnmatchedCode = um.firstOrNull()?.codes?.firstOrNull()

        val list = when (filter) {
            Filter.TODAY -> today
            Filter.CHECK -> today.filter { it.match == MatchType.MULTI }
            Filter.FULL -> today.filter { it.match == MatchType.FULL }
            Filter.ALL -> s.records
        }
        adapter.submit(list, s.vehicles.associateBy { it.number }, filter == Filter.ALL)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (s.hasAccess) R.string.feed_empty else R.string.feed_empty_no_access)
    }
}

// =====================================================================
// VEHICLES
// =====================================================================
class VehiclesTab(private val a: MainActivity, root: View) {
    private val clashCard: View = root.findViewById(R.id.clashCard)
    private val clashText: TextView = root.findViewById(R.id.clashText)
    private val count: TextView = root.findViewById(R.id.vehicleCount)
    private val empty: TextView = root.findViewById(R.id.vehicleEmpty)
    private val adapter = VehicleDetailAdapter(onEdit = { VehicleFormSheet.show(a, it) }, onOpen = { VehicleDetailSheet.show(a, it.number) })
    private var query = ""
    private var last: Snapshot? = null
    private lateinit var mastersButton: MaterialButton

    init {
        root.findViewById<RecyclerView>(R.id.vehicleList).apply {
            layoutManager = LinearLayoutManager(a)
            adapter = this@VehiclesTab.adapter
        }
        root.findViewById<TextInputEditText>(R.id.searchInput).addTextChangedListener(afterTextChanged {
            query = it.trim().lowercase()
            last?.let { s -> refresh(s) }
        })
        root.findViewById<MaterialButton>(R.id.importButton).setOnClickListener { a.pickImportFile() }
        root.findViewById<MaterialButton>(R.id.ratesButton).setOnClickListener { a.showTab(R.id.nav_rates) }
        mastersButton = root.findViewById<MaterialButton>(R.id.mastersButton).apply {
            setOnClickListener {
                val m = MasterData.get(a)
                MaterialAlertDialogBuilder(a)
                    .setTitle(R.string.masters_title)
                    .setMessage(a.getString(R.string.masters_help, m.stores.size, m.vendors.size))
                    .setPositiveButton(R.string.masters_import) { _, _ -> a.pickMastersFile() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        root.findViewById<MaterialButton>(R.id.templateButton).apply {
            setText(R.string.vehicle_template_dl)
            setOnClickListener { pickTemplate() }
        }
        root.findViewById<ExtendedFloatingActionButton>(R.id.addVehicleFab).setOnClickListener {
            VehicleFormSheet.show(a, null)
        }
    }

    /** Three templates: reporting vehicles, dedicated (storage only) vehicles, rate card. */
    private fun pickTemplate() {
        val items = arrayOf(a.getString(R.string.tpl_reporting), a.getString(R.string.tpl_dedicated), a.getString(R.string.tpl_rates))
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.tpl_pick_title)
            .setItems(items) { _, which ->
                val help = when (which) { 0 -> R.string.tpl_reporting_help; 1 -> R.string.tpl_dedicated_help; else -> R.string.tpl_rates_help }
                MaterialAlertDialogBuilder(a)
                    .setTitle(items[which])
                    .setMessage(help)
                    .setPositiveButton(R.string.vehicle_template_get) { _, _ ->
                        when (which) {
                            0 -> Exporter.deliver(a, "Reporting_Vehicles_Template.xlsx", VehicleTemplate.rows, VehicleTemplate.widths)
                            1 -> Exporter.deliver(a, "Dedicated_Vehicles_Template.xlsx", VehicleTemplate.rows, VehicleTemplate.widths)
                            else -> Exporter.deliver(a, "Rate_Card_Template.xlsx", RateCard.templateRows(MasterData.get(a)), RateCard.TEMPLATE_WIDTHS)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }

    fun refresh(s: Snapshot) {
        MasterData.get(a).let { m -> mastersButton.text = a.getString(R.string.masters_button, m.stores.size, m.vendors.size) }
        last = s
        val list = s.vehicles.filter { v ->
            query.isEmpty() || listOf(v.number, v.type, v.driver, v.mobile, v.transporter, v.route)
                .any { it.lowercase().contains(query) }
        }
        adapter.submit(list, VehicleDetailSheet.postedDetails(a, list, s.records))
        count.text = a.getString(R.string.vehicle_count, list.size, s.vehicles.size)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        val clashes = s.vehicles.groupBy { VehicleParser.last4(it.number) }.filter { it.value.size > 1 }
        clashCard.visibility = if (clashes.isEmpty()) View.GONE else View.VISIBLE
        clashText.text = a.getString(R.string.clash_warning) + "\n" +
            clashes.entries.joinToString("\n") { (code, vs) -> "• $code → ${vs.joinToString(", ") { it.number }}" }
    }
}

// =====================================================================
// EXPORT
// =====================================================================
class ExportTab(private val a: MainActivity, private val root: View) {
    private val dateLabel = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val fileDate = SimpleDateFormat("dd-MM-yyyy", Locale.US)
    private var dailyFrom = ReportBuilder.startOfDay()
    private var dailyTo = ReportBuilder.startOfDay()
    private var logFrom = ReportBuilder.startOfDay()
    private var logTo = ReportBuilder.startOfDay()
    private var sumTo = ReportBuilder.startOfDay()
    private var sumFrom = ReportBuilder.startOfDay(sumTo - 6 * ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2)

    init {
        // Mail has no bottom-bar button: it opens from here (Back returns here).
        root.findViewById<View>(R.id.exportMail).setOnClickListener { a.showTab(R.id.nav_mail) }
        // 1. Daily dedicated report
        val daily = card(root.findViewById(R.id.repDaily), R.drawable.ic_calendar, R.color.brand_primary, R.color.ok_bg,
            R.string.rep_daily_title, R.string.rep_daily_desc)
        rangeButtons(daily, { dailyFrom }, { dailyFrom = it }, { dailyTo }, { dailyTo = it })
        exportButton(daily) {
            if (dailyTo < dailyFrom) { bad(); return@exportButton }
            val s = snapshot()
            Exporter.deliver(a, "Dedicated_Vehicle_Report_${rangeName(dailyFrom, dailyTo)}.xlsx",
                ReportBuilder.dailyRows(s.vehicles, s.records, dailyFrom, dailyTo, lookups(), GateMarks.gates(a)), ReportBuilder.DAILY_WIDTHS)
        }

        // 2. Vehicle details
        val details = card(root.findViewById(R.id.repDetails), R.drawable.ic_truck, R.color.chk, R.color.chk_bg,
            R.string.rep_details_title, R.string.rep_details_desc)
        exportButton(details) {
            val s = snapshot()
            Exporter.deliver(a, "Vehicle_Details_${fileDate.format(Date())}.xlsx",
                ReportBuilder.detailRows(s.vehicles, s.records, GateMarks.gates(a)), ReportBuilder.DETAILS_WIDTHS)
        }

        // 3. Vehicle-wise summary
        val summary = card(root.findViewById(R.id.repSummary), R.drawable.ic_chart, R.color.blue, R.color.blue_bg,
            R.string.rep_summary_title, R.string.rep_summary_desc)
        dateButton(summary) { sumFrom }.also { b -> b.setOnClickListener { pick(sumFrom) { sumFrom = it; b.text = dateLabel.format(Date(it)) } } }
        dateButton(summary) { sumTo }.also { b -> b.setOnClickListener { pick(sumTo) { sumTo = it; b.text = dateLabel.format(Date(it)) } } }
        exportButton(summary) {
            if (sumTo < sumFrom) {
                android.widget.Toast.makeText(a, R.string.range_invalid, android.widget.Toast.LENGTH_SHORT).show()
                return@exportButton
            }
            val s = snapshot()
            Exporter.deliver(a, "Vehicle_Summary_${fileDate.format(Date(sumFrom))}_to_${fileDate.format(Date(sumTo))}.xlsx",
                ReportBuilder.summaryRows(s.vehicles, s.records, sumFrom, sumTo, GateMarks.gates(a)), ReportBuilder.SUMMARY_WIDTHS)
        }

        // 4. WhatsApp log
        val log = card(root.findViewById(R.id.repLog), R.drawable.ic_chat, R.color.wa_green, R.color.wa_bg,
            R.string.rep_log_title, R.string.rep_log_desc)
        rangeButtons(log, { logFrom }, { logFrom = it }, { logTo }, { logTo = it })
        exportButton(log) {
            if (logTo < logFrom) { bad(); return@exportButton }
            val s = snapshot()
            val range = s.records.filter { it.time >= logFrom && it.time < logTo + ReportBuilder.DAY_MS }
            Exporter.deliver(a, "WhatsApp_Log_${rangeName(logFrom, logTo)}.xlsx",
                ReportBuilder.logRows(s.vehicles, range, lookups()), ReportBuilder.LOG_WIDTHS)
        }

        root.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.lockSwitch).apply {
            isChecked = AppLock.lockEnabled(a)
            setOnCheckedChangeListener { sw, on ->
                if (on && !AppLock.available(a)) {
                    android.widget.Toast.makeText(a, R.string.lock_unavailable, android.widget.Toast.LENGTH_LONG).show()
                }
                AppLock.setLockEnabled(a, on)
            }
        }
        root.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.secureSwitch).apply {
            isChecked = AppLock.blockScreens(a)
            setOnCheckedChangeListener { _, on ->
                AppLock.setBlockScreens(a, on)
                AppLock.applyScreenBlock(a)
            }
        }

        updateAccount()
        root.findViewById<MaterialButton>(R.id.dcNameButton).setOnClickListener { a.editDcName() }
        root.findViewById<MaterialButton>(R.id.signOutButton).setOnClickListener { a.signOut() }
        root.findViewById<MaterialButton>(R.id.deleteAccountButton).apply {
            visibility = if (Account.isLocal(a)) View.GONE else View.VISIBLE
            setOnClickListener { a.deleteAccount() }
        }

        root.findViewById<MaterialButton>(R.id.clearButton).setOnClickListener {
            MaterialAlertDialogBuilder(a)
                .setTitle(R.string.clear_title)
                .setMessage(R.string.clear_message)
                .setPositiveButton(R.string.clear) { _, _ -> VehicleStore.clear(a) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun snapshot() = a.loadSnapshot()

    /** Who is signed in, where the data is kept, and the DC name. */
    fun updateAccount() {
        root.findViewById<TextView>(R.id.accountName).text =
            if (Account.isLocal(a)) a.getString(R.string.sign_local_name)
            else Account.email(a).ifBlank { Account.name(a) }
        root.findViewById<TextView>(R.id.accountNote).setText(
            if (Account.isLocal(a)) R.string.account_note_local else R.string.account_note_cloud)
        root.findViewById<TextView>(R.id.dcNameText).text =
            a.getString(R.string.dc_name_line, Masters.dispatchName.ifBlank { a.getString(R.string.dc_name_unset) })
    }

    private fun lookups() = ReportBuilder.Lookups(MasterData.get(a), RateCard.get(a))

    private fun rangeName(from: Long, to: Long) =
        if (from == to) fileDate.format(Date(from)) else "${fileDate.format(Date(from))}_to_${fileDate.format(Date(to))}"

    private fun bad() = android.widget.Toast.makeText(a, R.string.range_invalid, android.widget.Toast.LENGTH_SHORT).show()

    /** "From" and "To" date buttons, plus quick Today / Last 7 days / This month chips. */
    private fun rangeButtons(group: ViewGroup, from: () -> Long, setFrom: (Long) -> Unit, to: () -> Long, setTo: (Long) -> Unit) {
        val f = dateButton(group, from)
        val t = dateButton(group, to)
        fun show() { f.text = a.getString(R.string.from_date, dateLabel.format(Date(from()))); t.text = a.getString(R.string.to_date, dateLabel.format(Date(to()))) }
        f.setOnClickListener { pick(from()) { setFrom(it); if (to() < it) setTo(it); show() } }
        t.setOnClickListener { pick(to()) { setTo(it); if (from() > it) setFrom(it); show() } }
        val today = ReportBuilder.startOfDay()
        val quick = listOf(
            R.string.quick_today to (today to today),
            R.string.quick_week to (ReportBuilder.startOfDay(today - 6 * ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2) to today),
            R.string.quick_month to (Calendar.getInstance().apply { timeInMillis = today; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis.let { ReportBuilder.startOfDay(it) } to today)
        )
        for ((label, range) in quick) {
            val c = Chip(a)
            c.setText(label)
            c.setOnClickListener { setFrom(range.first); setTo(range.second); show() }
            group.addView(c)
        }
        show()
    }

    private fun card(view: View, icon: Int, fg: Int, bg: Int, title: Int, desc: Int): ChipGroup {
        view.findViewById<View>(R.id.reportIconBox).backgroundTintList = ColorStateList.valueOf(Ui.color(a, bg))
        view.findViewById<ImageView>(R.id.reportIcon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Ui.color(a, fg))
        }
        view.findViewById<TextView>(R.id.reportTitle).setText(title)
        view.findViewById<TextView>(R.id.reportDesc).setText(desc)
        return view.findViewById(R.id.reportControls)
    }

    private fun dateButton(group: ViewGroup, value: () -> Long): MaterialButton {
        val b = MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        b.text = dateLabel.format(Date(value()))
        b.setIconResource(R.drawable.ic_calendar)
        b.iconSize = (16 * a.resources.displayMetrics.density).toInt()
        b.cornerRadius = (24 * a.resources.displayMetrics.density).toInt()
        group.addView(b, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return b
    }

    private fun exportButton(group: ViewGroup, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(a)
        b.setText(R.string.export)
        b.setIconResource(R.drawable.ic_download)
        b.iconSize = (16 * a.resources.displayMetrics.density).toInt()
        b.cornerRadius = (24 * a.resources.displayMetrics.density).toInt()
        b.setOnClickListener { onClick() }
        group.addView(b, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return b
    }

    private fun pick(current: Long, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = current }
        DatePickerDialog(a, { _, y, m, d ->
            val picked = Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis
            onPicked(ReportBuilder.startOfDay(picked))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }
}
