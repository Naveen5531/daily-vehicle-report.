package com.logistics.vehiclereport

import android.content.res.ColorStateList
import android.net.Uri
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Plan tab: the day's vehicle request sheet (photos or the Excel file) turned into vehicles by volume.
 * Each store gets the smallest vehicle that carries it (full loads of the biggest first), small stores share a
 * vehicle only with stores nearby or on that vehicle's way from the DC (store places from the phone's map search),
 * and vehicles can be clubbed or split by hand.
 */
class PlanTab(private val a: MainActivity, root: View) {
    private val heroLabel: TextView = root.findViewById(R.id.planHeroLabel)
    private val vehiclesText: TextView = root.findViewById(R.id.planVehicles)
    private val sheetText: TextView = root.findViewById(R.id.planSheet)
    private val heroText: TextView = root.findViewById(R.id.planHeroText)
    private val typeChips: ChipGroup = root.findViewById(R.id.planTypes)
    private val clubSwitch: MaterialSwitch = root.findViewById(R.id.planClubSwitch)
    private val clubText: TextView = root.findViewById(R.id.planClubText)
    private val geoText: TextView = root.findViewById(R.id.planGeo)
    private val source: TextView = root.findViewById(R.id.planSource)
    private val selectBar: View = root.findViewById(R.id.planSelectBar)
    private val selectText: TextView = root.findViewById(R.id.planSelectText)
    private val empty: TextView = root.findViewById(R.id.planEmpty)
    private val list: LinearLayout = root.findViewById(R.id.planList)
    private val mapCard: View = root.findViewById(R.id.planMapCard)
    private val mapText: TextView = root.findViewById(R.id.planMapText)
    private val mapBox: android.widget.FrameLayout = root.findViewById(R.id.planMapBox)
    /** Made the first time there is something to pin (the map library loads only then). */
    private var map: org.osmdroid.views.MapView? = null
    /** Vehicles picked (long-press) to club by hand: positions in the plan. */
    private val selected = LinkedHashSet<Int>()
    private var result: PlanResult? = null
    private var dcPlace: LatLon? = null
    private var places: Map<String, LatLon> = emptyMap()
    private val zoneBar: View = root.findViewById(R.id.planZoneBar)
    private val zoneChips: ChipGroup = root.findViewById(R.id.planZoneChips)
    private val zoneText: TextView = root.findViewById(R.id.planZoneText)
    private val zoneChip = mapOf<Zone?, com.google.android.material.chip.Chip>(
        null to root.findViewById<com.google.android.material.chip.Chip>(R.id.zoneAll), Zone.LOCAL to root.findViewById<com.google.android.material.chip.Chip>(R.id.zoneLocal),
        Zone.NON_LOCAL to root.findViewById<com.google.android.material.chip.Chip>(R.id.zoneNonLocal), Zone.UPCOUNTRY to root.findViewById<com.google.android.material.chip.Chip>(R.id.zoneUpcountry),
        Zone.UNKNOWN to root.findViewById<com.google.android.material.chip.Chip>(R.id.zoneUnknown)
    )
    /** Local / Non-local / Upcountry of each store (by code), and the filter chosen (null = all). */
    private var zones: Map<String, Zone> = emptyMap()
    private var zoneFilter: Zone? = null
    /** Shown so far: the vehicles in the chosen zone, with their numbers on the whole plan. */
    private var shown: List<IndexedValue<PlanLoad>> = emptyList()

    init {
        root.findViewById<View>(R.id.planPhoto).setOnClickListener { a.addPlanPhotos() }
        root.findViewById<View>(R.id.planExcel).setOnClickListener { a.pickPlanExcel() }
        root.findViewById<View>(R.id.planSizes).setOnClickListener { editSizes() }
        root.findViewById<View>(R.id.planExport).setOnClickListener { export() }
        root.findViewById<View>(R.id.planClear).setOnClickListener {
            MaterialAlertDialogBuilder(a)
                .setTitle(R.string.plan_new_title)
                .setMessage(R.string.plan_new_message)
                .setPositiveButton(R.string.plan_new) { _, _ -> PlanData.clear(a); selected.clear(); refresh() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        clubSwitch.isChecked = PlanData.autoClub(a)
        clubSwitch.setOnCheckedChangeListener { _, on -> PlanData.setAutoClub(a, on); selected.clear(); refresh() }
        root.findViewById<View>(R.id.planSelectCancel).setOnClickListener { selected.clear(); refresh() }
        root.findViewById<View>(R.id.planSelectClub).setOnClickListener { clubSelected() }
        val d = a.resources.displayMetrics.density
        androidx.core.content.ContextCompat.getDrawable(a, R.drawable.ic_route)?.mutate()?.let {
            it.setTint(Ui.color(a, R.color.emerald)); it.setBounds(0, 0, (16 * d).toInt(), (16 * d).toInt())
            geoText.setCompoundDrawablesRelative(it, null, null, null)
        }
        geoText.setOnClickListener { editMap() }
        root.findViewById<View>(R.id.planMapExpand).setOnClickListener {
            PlanMap.openFull(a, shownResult(), places, dcPlace, mapLabels(), shown.map { it.index + 1 })
        }
        zoneFilter = PlanData.zoneFilter(a)
        (zoneChip[zoneFilter] ?: zoneChip.getValue(null)).isChecked = true
        zoneChips.setOnCheckedStateChangeListener { _, ids ->
            val z = zoneChip.entries.firstOrNull { it.value.id == ids.firstOrNull() }?.key
            if (z != zoneFilter) { zoneFilter = z; PlanData.setZoneFilter(a, z); selected.clear(); refresh() }
        }
        zoneText.setOnClickListener { editZones() }
        refresh()
    }

    /** 1070 -> "1070", 878.185 -> "878.2", 23.58 -> "23.58" */
    private fun vol(v: Double): String = when {
        abs(v - Math.round(v)) < 0.005 -> Math.round(v).toString()
        v >= 100 -> "%.1f".format(Locale.US, v)
        else -> "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')
    }
    private fun pct(f: Double) = "${Math.round(f * 100)}%"
    private fun km(v: Double) = Math.round(v).toString()

    fun refresh() {
        val plan = PlanData.load(a)
        val stops = PlanData.withPlaces(a, plan.stops)
        val opts = PlanData.options(a, plan)
        zones = PlanData.zones(a, stops, opts.places, opts.dc)
        // Small Upcountry / Local stores (below the zone's minimum volume) get no vehicle today.
        val heldCodes = DispatchZone.held(stops, zones, { PlanData.zoneMin(a, it) }, PlanData.forced(a))
        val held = stops.filter { it.code in heldCodes }
        val r = VehiclePlanner.plan(stops.filter { it.code !in heldCodes }, opts)
        result = r
        dcPlace = opts.dc
        places = opts.places
        selected.removeAll { it >= r.loads.size }

        heroLabel.text = a.getString(R.string.plan_hero_label, PlanData.dayLabel(plan.created))
        vehiclesText.text = r.vehicles.toString()
        // The sheet's own vehicle count, only when it was read for every store (a partial sum would mislead).
        val onSheet = plan.stops.map { it.sheetVehicles }
        sheetText.text = if (onSheet.isEmpty() || onSheet.any { it == null }) a.getString(R.string.plan_vehicles_word)
            else a.getString(R.string.plan_vs_sheet, onSheet.sumOf { it ?: 0 })
        val inUse = PlanData.types(a).filter { it.on && it.capacity > 0 }.maxByOrNull { it.capacity }
        heroText.text = if (r.vehicles == 0) a.getString(R.string.plan_hero_empty)
            else a.getString(R.string.plan_hero_text, vol(r.totalVolume), pct(r.fill), plan.stops.size) +
                (inUse?.let { "\n" + a.getString(R.string.plan_hero_max, it.name) } ?: "")
        typeChips.removeAllViews()
        val d = a.resources.displayMetrics.density
        for ((t, n) in r.byType) {
            typeChips.addView(TextView(a).apply {
                text = a.getString(R.string.plan_type_count, t.name, n)
                setBackgroundResource(R.drawable.pill_bg)
                backgroundTintList = ColorStateList.valueOf(0x2EFFFFFF)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12.5f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(a, R.font.jakarta_semibold)
                setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            })
        }
        typeChips.visibility = if (r.byType.isEmpty()) View.GONE else View.VISIBLE
        clubText.text = a.getString(R.string.plan_club_text, vol(PlanData.clubBelow(a)), PlanData.maxStops(a))
        showZones(r)
        showGeo(stops)
        showMap(r, stops)

        // Where the stores came from, and whether each sheet's total matches what was read.
        source.text = if (plan.sources.isEmpty()) "" else buildString {
            append(a.getString(R.string.plan_source, plan.stops.size, plan.sources.size))
            for (s in plan.sources) {
                val t = s.sheetTotal
                append("\n").append(s.label).append(": ").append(a.getString(R.string.plan_source_rows, s.rows))
                if (t != null) {
                    if (kotlin.math.abs(t - s.readTotal) < 0.6) append(" · ").append(a.getString(R.string.plan_total_ok, vol(t)))
                    else append(" · ").append(a.getString(R.string.plan_total_bad, vol(t), vol(s.readTotal)))
                }
            }
        }
        source.visibility = if (plan.sources.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (r.loads.isEmpty()) View.VISIBLE else View.GONE

        // Vehicles of the chosen zone, region by region
        list.removeAllViews()
        val inflater = LayoutInflater.from(a)
        if (zoneFilter != null && r.loads.isNotEmpty()) {
            list.addView(TextView(a).apply {
                text = a.getString(R.string.zone_showing, zoneName(zoneFilter!!), shown.size, vol(shown.sumOf { it.value.volume }))
                setTextColor(Ui.color(a, R.color.emerald)); textSize = 13f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(a, R.font.jakarta_semibold)
                val d = a.resources.displayMetrics.density
                setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), 0)
            })
        }
        shown.groupBy { it.value.cluster }.forEach { (cluster, loads) ->
            val cap = loads.sumOf { it.value.type?.capacity ?: 0.0 }
            val v = loads.sumOf { it.value.volume }
            list.addView(TextView(a).apply {
                text = a.getString(R.string.plan_cluster_line, cluster, loads.size, vol(v), if (cap > 0) pct(v / cap) else "–")
                setTextColor(Ui.color(a, R.color.ink))
                textSize = 14.5f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(a, R.font.jakarta_bold)
                val d = a.resources.displayMetrics.density
                setPadding((20 * d).toInt(), (18 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
            })
            for ((i, l) in loads) list.addView(card(inflater, i, l))
        }
        showHeld(held.filter { zoneFilter == null || zones[it.code] == zoneFilter })
        updateSelectBar()
    }

    private fun card(inflater: LayoutInflater, index: Int, l: PlanLoad): View {
        val v = inflater.inflate(R.layout.item_plan_vehicle, list, false) as MaterialCardView
        v.findViewById<TextView>(R.id.pvNo).text = (index + 1).toString()
        v.findViewById<TextView>(R.id.pvType).text = l.type?.name ?: a.getString(R.string.plan_no_type)
        v.findViewById<TextView>(R.id.pvCap).text = a.getString(R.string.plan_cap_line,
            l.type?.let { vol(it.capacity) } ?: "–", vol(l.volume), l.codes.size) +
            (l.routeKm?.let { "\n" + a.getString(R.string.plan_route_line, km(it)) } ?: "")
        val fill = v.findViewById<TextView>(R.id.pvFill)
        fill.text = pct(l.fill)
        val (fg, bg) = when {
            l.type == null -> R.color.no to R.color.no_bg
            l.fill >= 0.85 -> R.color.ok to R.color.ok_bg
            l.fill >= 0.6 -> R.color.chk to R.color.chk_bg
            else -> R.color.no to R.color.no_bg
        }
        fill.setTextColor(Ui.color(a, fg))
        fill.backgroundTintList = ColorStateList.valueOf(Ui.color(a, bg))
        v.findViewById<LinearProgressIndicator>(R.id.pvBar).apply {
            progress = Math.round(l.fill * 100).toInt().coerceIn(0, 100)
            setIndicatorColor(Ui.color(a, if (l.fill >= 0.6 || l.type == null) R.color.emerald else R.color.no))
        }
        val stops = v.findViewById<LinearLayout>(R.id.pvStops)
        for ((s, part) in l.parts) {
            val row = inflater.inflate(R.layout.row_plan_stop, stops, false)
            row.findViewById<TextView>(R.id.psName).text = "${s.code} · ${s.shownName}"
            val at = places[s.code]
            val dc = dcPlace
            row.findViewById<TextView>(R.id.psPlace).text = Ui.joinNonBlank(
                zones[s.code]?.let { zoneName(it) }.orEmpty(),
                if (at != null && dc != null) a.getString(R.string.plan_km_from_dc, km(VehiclePlanner.km(dc, at)))
                    else if (at == null && places.isNotEmpty()) a.getString(R.string.plan_not_on_map) else "",
                s.city, s.region.ifBlank { s.circle },
                if (part < s.volume - 0.01) a.getString(R.string.plan_part_of, vol(s.volume)) else "",
                s.remark,
                s.sheetVehicles?.let { a.resources.getQuantityString(R.plurals.plan_on_sheet, it, it) }.orEmpty()
            )
            row.findViewById<TextView>(R.id.psVol).text = vol(part)
            row.findViewById<View>(R.id.psPin).apply {
                visibility = if (at != null) View.VISIBLE else View.GONE
                if (at != null) setOnClickListener { PlanMap.openInMaps(a, at, "${s.code} ${s.shownName}") }
            }
            row.setOnClickListener { if (selected.isNotEmpty()) toggle(index) else editStop(s.code) }
            row.setOnLongClickListener { toggle(index); true }
            stops.addView(row)
        }
        v.findViewById<View>(R.id.pvFoot).visibility = if (l.clubbed) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.pvClubTag).apply {
            setTextColor(Ui.color(a, R.color.wait)); backgroundTintList = ColorStateList.valueOf(Ui.color(a, R.color.wait_bg))
            text = l.extraKm?.let { a.getString(R.string.plan_clubbed_km, km(it)) } ?: a.getString(R.string.plan_clubbed)
        }
        v.findViewById<MaterialButton>(R.id.pvSplit).setOnClickListener { split(l) }
        val on = index in selected
        v.strokeWidth = if (on) (2.5f * a.resources.displayMetrics.density).toInt() else 0
        v.strokeColor = Ui.color(a, R.color.emerald)
        v.setOnLongClickListener { toggle(index); true }
        v.setOnClickListener { if (selected.isNotEmpty()) toggle(index) }
        return v
    }

    private fun toggle(i: Int) {
        if (!selected.remove(i)) selected += i
        refresh()
    }

    private fun updateSelectBar() {
        val r = result
        selectBar.visibility = if (selected.isEmpty() || r == null) View.GONE else View.VISIBLE
        if (r == null || selected.isEmpty()) return
        val v = selected.mapNotNull { r.loads.getOrNull(it) }.sumOf { it.volume }
        selectText.text = a.getString(R.string.plan_selected, selected.size, vol(v))
    }

    /** The picked vehicles' stores go on one vehicle (kept from then on, until split). */
    private fun clubSelected() {
        val r = result ?: return
        val loads = selected.mapNotNull { r.loads.getOrNull(it) }
        if (loads.size < 2) { Toast.makeText(a, R.string.plan_pick_two, Toast.LENGTH_SHORT).show(); return }
        val total = loads.sumOf { it.volume }
        val max = PlanData.types(a).filter { it.on }.maxOfOrNull { it.capacity } ?: 0.0
        if (total > max + 1e-6) {
            Toast.makeText(a, a.getString(R.string.plan_too_much, vol(total), vol(max)), Toast.LENGTH_LONG).show(); return
        }
        val codes = loads.flatMap { it.codes }.toSet()
        val most = PlanData.maxStops(a)
        if (codes.size > most) {
            Toast.makeText(a, a.getString(R.string.plan_too_many_stores, codes.size, most), Toast.LENGTH_LONG).show(); return
        }
        val plan = PlanData.load(a)
        PlanData.save(a, plan.copy(
            clubs = plan.clubs.filter { it.intersect(codes).isEmpty() } + listOf(codes),
            noClub = plan.noClub - codes
        ))
        selected.clear()
        refresh()
    }

    /** A clubbed vehicle split back: each of its stores keeps its own vehicle. */
    private fun split(l: PlanLoad) {
        val codes = l.codes.toSet()
        val plan = PlanData.load(a)
        PlanData.save(a, plan.copy(clubs = plan.clubs.filter { it.intersect(codes).isEmpty() }, noClub = plan.noClub + codes))
        selected.clear()
        refresh()
    }

    /** Fix a store read wrong (volume or name), or take it off the plan. */
    private fun editStop(code: String) {
        val plan = PlanData.load(a)
        val s = plan.stops.firstOrNull { it.code == code } ?: return
        val d = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0) }
        fun field(hint: Int, value: String, type: Int): TextInputEditText {
            val l = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { this.hint = a.getString(hint) }
            val e = TextInputEditText(l.context).apply { setText(value); inputType = type; setSingleLine() }
            l.addView(e)
            box.addView(l, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
            return e
        }
        val name = field(R.string.plan_field_name, s.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val volume = field(R.string.plan_field_volume, s.volume.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val placeWas = StoreGeo.query(a, code)?.removeSuffix(", India").orEmpty()
        val place = field(R.string.plan_field_place, placeWas, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        box.addView(TextView(a).apply {
            text = places[code]?.let { at -> dcPlace?.let { a.getString(R.string.plan_place_found_km, km(VehiclePlanner.km(it, at))) }
                ?: a.getString(R.string.plan_place_found) } ?: a.getString(R.string.plan_place_missing)
            textSize = 12f; setTextColor(Ui.color(a, R.color.muted)); setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), 0)
        })
        MaterialAlertDialogBuilder(a)
            .setTitle(a.getString(R.string.plan_edit_title, s.code))
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val v = SheetReader.number(volume.text?.toString().orEmpty())
                if (v == null || v <= 0) { Toast.makeText(a, R.string.plan_bad_volume, Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                PlanData.save(a, plan.copy(stops = plan.stops.map {
                    if (it.code == code) it.copy(volume = v, name = name.text?.toString()?.trim().orEmpty().ifBlank { it.name }) else it
                }))
                val q = place.text?.toString()?.trim().orEmpty()
                if (q.isNotEmpty() && (q != placeWas || places[code] == null)) {
                    StoreGeo.setPlace(a, code, q) { found ->
                        Toast.makeText(a, if (found) a.getString(R.string.plan_place_set, q) else a.getString(R.string.plan_place_not_found, q), Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }
                refresh()
            }
            .setNeutralButton(R.string.plan_remove_store) { _, _ ->
                PlanData.save(a, plan.copy(stops = plan.stops.filter { it.code != code }))
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Vehicle sizes: capacity of each, which are used, and below what volume stores are clubbed. */
    private fun editSizes() {
        val types = PlanData.types(a)
        val d = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (6 * d).toInt(), (20 * d).toInt(), 0) }
        val rows = types.map { t ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val check = CheckBox(a).apply { isChecked = t.on; text = t.name; textSize = 14.5f }
            val cap = TextInputEditText(a).apply {
                setText(vol(t.capacity)); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setSingleLine(); textSize = 14.5f; gravity = android.view.Gravity.END
            }
            row.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(cap, LinearLayout.LayoutParams((96 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            box.addView(row)
            Triple(t, check, cap)
        }
        val clubRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        clubRow.addView(TextView(a).apply { setText(R.string.plan_club_below_label); textSize = 14.5f; setTextColor(Ui.color(a, R.color.ink)) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val clubBelow = TextInputEditText(a).apply {
            setText(vol(PlanData.clubBelow(a))); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(); textSize = 14.5f; gravity = android.view.Gravity.END
        }
        clubRow.addView(clubBelow, LinearLayout.LayoutParams((96 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(clubRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() })
        val stopsRow = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        stopsRow.addView(TextView(a).apply { setText(R.string.plan_max_stops_label); textSize = 14.5f; setTextColor(Ui.color(a, R.color.ink)) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val maxStops = TextInputEditText(a).apply {
            setText(PlanData.maxStops(a).toString()); inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(); textSize = 14.5f; gravity = android.view.Gravity.END
        }
        stopsRow.addView(maxStops, LinearLayout.LayoutParams((96 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(stopsRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.plan_sizes_title)
            .setMessage(R.string.plan_sizes_message)
            .setView(android.widget.ScrollView(a).apply { addView(box) })
            .setPositiveButton(R.string.save) { _, _ ->
                PlanData.saveTypes(a, rows.map { (t, check, cap) ->
                    t.copy(capacity = SheetReader.number(cap.text?.toString().orEmpty()) ?: t.capacity, on = check.isChecked)
                })
                SheetReader.number(clubBelow.text?.toString().orEmpty())?.let { PlanData.setClubBelow(a, it) }
                maxStops.text?.toString()?.trim()?.toIntOrNull()?.let { PlanData.setMaxStops(a, it) }
                refresh()
            }
            .setNeutralButton(R.string.plan_sizes_reset) { _, _ ->
                PlanData.saveTypes(a, VehiclePlanner.DEFAULT_TYPES); PlanData.setClubBelow(a, 300.0); PlanData.setMaxStops(a, 2); refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadZone(l: PlanLoad): Zone = DispatchZone.ofLoad(l.codes.map { zones[it] ?: Zone.UNKNOWN })

    private fun zoneName(z: Zone): String = a.getString(when (z) {
        Zone.LOCAL -> R.string.zone_local_tag
        Zone.NON_LOCAL -> R.string.zone_non_local_tag
        Zone.UPCOUNTRY -> R.string.zone_upcountry_tag
        Zone.UNKNOWN -> R.string.zone_unknown_tag
    })

    /** The vehicles in the chosen zone as a plan of their own (totals by size for those only). */
    private fun shownResult(): PlanResult? {
        val r = result ?: return null
        if (zoneFilter == null) return r
        val loads = shown.map { it.value }
        val byType = r.byType.map { (t, _) -> t to loads.count { it.type == t } }.filter { it.second > 0 }
        return PlanResult(loads, byType, loads.sumOf { it.volume })
    }

    /** Counts on the Local / Non-local / Upcountry chips, the vehicles of the chosen one, and the DC's city and state. */
    private fun showZones(r: PlanResult) {
        val byZone = r.loads.withIndex().groupBy { loadZone(it.value) }
        val f = zoneFilter
        shown = if (f == null) r.loads.withIndex().toList() else byZone[f].orEmpty()
        val texts = mapOf(null to R.string.zone_all, Zone.LOCAL to R.string.zone_local, Zone.NON_LOCAL to R.string.zone_non_local,
            Zone.UPCOUNTRY to R.string.zone_upcountry, Zone.UNKNOWN to R.string.zone_unknown)
        for ((z, chip) in zoneChip) chip.text = a.getString(texts.getValue(z), if (z == null) r.loads.size else byZone.getOrElse(z) { emptyList() }.size)
        zoneChip.getValue(Zone.UNKNOWN).visibility = if (byZone[Zone.UNKNOWN].isNullOrEmpty() && zoneFilter != Zone.UNKNOWN) View.GONE else View.VISIBLE
        val any = r.loads.isNotEmpty()
        zoneBar.visibility = if (any) View.VISIBLE else View.GONE
        zoneText.visibility = if (any) View.VISIBLE else View.GONE
        val state = PlanData.dcState(a)
        zoneText.text = if (state == null) a.getString(R.string.zone_line_no_state, PlanData.dcCity(a))
            else a.getString(R.string.zone_line, PlanData.dcCity(a), state)
    }

    /** Stores left out for being below their zone's minimum volume; tap one to plan it anyway. */
    private fun showHeld(held: List<PlanStop>) {
        if (held.isEmpty()) return
        val d = a.resources.displayMetrics.density
        list.addView(TextView(a).apply {
            text = a.getString(R.string.held_title, held.size, vol(held.sumOf { it.volume }))
            setTextColor(Ui.color(a, R.color.ink)); textSize = 14.5f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(a, R.font.jakarta_bold)
            setPadding((20 * d).toInt(), (22 * d).toInt(), (20 * d).toInt(), (2 * d).toInt())
        })
        list.addView(TextView(a).apply {
            text = a.getString(R.string.held_hint, vol(PlanData.zoneMin(a, Zone.UPCOUNTRY)), vol(PlanData.zoneMin(a, Zone.LOCAL)))
            setTextColor(Ui.color(a, R.color.muted)); textSize = 12.5f
            setPadding((20 * d).toInt(), 0, (20 * d).toInt(), (6 * d).toInt())
        })
        for (s in held.sortedBy { it.volume }) {
            list.addView(TextView(a).apply {
                text = "${s.code} · ${s.shownName}
" + Ui.joinNonBlank(zones[s.code]?.let { zoneName(it) }.orEmpty(), s.city,
                    a.getString(R.string.held_volume, vol(s.volume)))
                setTextColor(Ui.color(a, R.color.ink)); textSize = 13.5f
                setBackgroundResource(android.R.drawable.list_selector_background)
                setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), (8 * d).toInt())
                setOnClickListener {
                    MaterialAlertDialogBuilder(a)
                        .setTitle(a.getString(R.string.held_plan_title, s.code))
                        .setMessage(a.getString(R.string.held_plan_message, s.shownName, vol(s.volume)))
                        .setPositiveButton(R.string.held_plan_yes) { _, _ -> PlanData.setForced(a, PlanData.forced(a) + s.code); refresh() }
                        .setNeutralButton(R.string.plan_remove_store) { _, _ ->
                            val p = PlanData.load(a); PlanData.save(a, p.copy(stops = p.stops.filter { it.code != s.code })); refresh()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            })
        }
    }

    /** The DC's local city and state, and how near a store with no city must be to count as local. */
    private fun editZones() {
        val d = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0) }
        fun field(hint: Int, value: String, type: Int): TextInputEditText {
            val l = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { this.hint = a.getString(hint) }
            val e = TextInputEditText(l.context).apply { setText(value); inputType = type; setSingleLine() }
            l.addView(e)
            box.addView(l, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
            return e
        }
        val words = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val city = field(R.string.zone_field_city, PlanData.dcCity(a), words)
        val state = field(R.string.zone_field_state, PlanData.dcState(a).orEmpty(), words)
        val near = field(R.string.zone_field_km, km(PlanData.localKm(a)), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val minUp = field(R.string.zone_field_min_up, vol(PlanData.zoneMin(a, Zone.UPCOUNTRY)), num)
        val minNon = field(R.string.zone_field_min_non, vol(PlanData.zoneMin(a, Zone.NON_LOCAL)), num)
        val minLocal = field(R.string.zone_field_min_local, vol(PlanData.zoneMin(a, Zone.LOCAL)), num)
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.zone_title)
            .setMessage(R.string.zone_message)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val c = city.text?.toString()?.trim().orEmpty()
                val st = state.text?.toString()?.trim().orEmpty()
                // Saved only when changed, so the city and state worked out from the DC keep following it.
                PlanData.setDcCityState(a,
                    if (c == DispatchZone.dcCityLabel(StoreGeo.dcQuery(a)) && PlanData.dcCityTyped(a).isBlank()) "" else c,
                    if (PlanData.dcStateTyped(a).isBlank() && DispatchZone.canonical(st) == PlanData.dcState(a)) "" else st)
                SheetReader.number(near.text?.toString().orEmpty())?.takeIf { it > 0 }?.let { PlanData.setLocalKm(a, it) }
                for ((z, f) in listOf(Zone.UPCOUNTRY to minUp, Zone.NON_LOCAL to minNon, Zone.LOCAL to minLocal)) {
                    SheetReader.number(f.text?.toString().orEmpty())?.let { PlanData.setZoneMin(a, z, it) }
                }
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun mapLabels() = PlanMap.Labels(
        dcName = a.getString(R.string.plan_map_dc, StoreGeo.dcQuery(a)),
        vehicle = { n, type, part -> a.getString(R.string.plan_map_pin_vehicle, n, type ?: a.getString(R.string.plan_no_type), vol(part)) },
        km = { a.getString(R.string.plan_km_from_dc, km(it)) }
    )

    /** The small map: DC and the day's stores as pins; hidden until there is a plan. */
    private fun showMap(r: PlanResult, stops: List<PlanStop>) {
        if (r.loads.isEmpty()) { mapCard.visibility = View.GONE; return }
        mapCard.visibility = View.VISIBLE
        val m = map ?: try {
            PlanMap.newMap(a, inScroll = true).also {
                mapBox.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1))
                map = it
            }
        } catch (e: Throwable) { mapCard.visibility = View.GONE; return }
        val pinned = PlanMap.show(m, shownResult(), places, dcPlace, mapLabels(), shown.map { it.index + 1 })
        val total = shown.flatMap { it.value.codes }.distinct().size
        mapText.text = if (pinned == 0) a.getString(R.string.plan_map_none) else a.getString(R.string.plan_map_text, pinned, total)
    }

    /** How many stores are on the map, and from where; looks up the ones missing (once a day for a store not found). */
    private fun showGeo(stops: List<PlanStop>) {
        if (stops.isEmpty()) { geoText.visibility = View.GONE; return }
        geoText.visibility = View.VISIBLE
        val codes = stops.map { it.code }.distinct()
        val onMap = codes.count { it in places }
        val dc = dcPlace
        if (onMap < codes.size || dc == null) StoreGeo.fill(a, stops) { if (stops.isNotEmpty()) refresh() }
        geoText.text = when {
            StoreGeo.busy -> a.getString(R.string.plan_geo_busy, onMap, codes.size)
            onMap == 0 -> a.getString(R.string.plan_geo_none)
            dc == null -> a.getString(R.string.plan_geo_no_dc, onMap, codes.size)
            else -> a.getString(R.string.plan_geo_line, onMap, codes.size, StoreGeo.dcQuery(a), km(PlanData.nearKm(a)))
        }
    }

    /** The DC's place on the map, and how close stores must be to count as nearby. */
    private fun editMap() {
        val d = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0) }
        fun field(hint: Int, value: String, type: Int): TextInputEditText {
            val l = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { this.hint = a.getString(hint) }
            val e = TextInputEditText(l.context).apply { setText(value); inputType = type; setSingleLine() }
            l.addView(e)
            box.addView(l, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
            return e
        }
        val dcWas = StoreGeo.dcQuery(a)
        val dcField = field(R.string.plan_field_dc, dcWas, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        val near = field(R.string.plan_field_near, km(PlanData.nearKm(a)), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.plan_map_title)
            .setMessage(R.string.plan_map_message)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                SheetReader.number(near.text?.toString().orEmpty())?.takeIf { it > 0 }?.let { PlanData.setNearKm(a, it) }
                val q = dcField.text?.toString()?.trim().orEmpty()
                if (q.isNotEmpty() && (q != dcWas || dcPlace == null)) {
                    StoreGeo.setDcPlace(a, q) { found ->
                        Toast.makeText(a, if (found) a.getString(R.string.plan_place_set, q) else a.getString(R.string.plan_place_not_found, q), Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun export() {
        val r = shownResult() ?: return
        if (r.loads.isEmpty()) { Toast.makeText(a, R.string.nothing_to_export, Toast.LENGTH_SHORT).show(); return }
        val day = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date(PlanData.load(a).created.takeIf { it > 0 } ?: System.currentTimeMillis()))
        val tag = zoneFilter?.let { "_" + zoneName(it).replace(Regex("[^A-Za-z]"), "") }.orEmpty()
        Exporter.deliver(a, "Vehicle_Plan_$day$tag.xlsx", VehiclePlanner.exportRows(r) { zoneName(loadZone(it)) },
            VehiclePlanner.EXPORT_WIDTHS, numbers = true)
    }

    // ---------- Reading the sheet ----------

    /** Reads sheet photos one after another and adds their stores to the plan. */
    fun readPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val wait = MaterialAlertDialogBuilder(a).setMessage(a.getString(R.string.plan_reading, 1, uris.size)).setCancelable(false).show()
        val before = PlanData.load(a).sources.count { it.label.startsWith(a.getString(R.string.plan_photo_label_prefix)) }
        val lines = mutableListOf<String>()
        var failed: Exception? = null
        fun next(i: Int) {
            if (i >= uris.size) {
                wait.dismiss()
                refresh()
                showReadSummary(lines, failed)
                return
            }
            wait.setMessage(a.getString(R.string.plan_reading, i + 1, uris.size))
            PlanData.readPhoto(a, uris[i]) { words, e ->
                val label = a.getString(R.string.plan_photo_label, before + i + 1)
                if (words == null) {
                    failed = e
                    lines += a.getString(R.string.plan_read_failed_line, label)
                } else {
                    val res = SheetReader.fromWords(words, MasterData.get(a).stores.associate { it.code.trim() to it.name })
                    PlanData.add(a, label, res)
                    lines += summaryLine(label, res)
                }
                next(i + 1)
            }
        }
        next(0)
    }

    fun readExcel(uri: Uri) {
        val res = try {
            val bytes = a.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            SheetReader.fromRows(VehicleListImporter.xlsxSheets(bytes))
        } catch (e: Exception) {
            Toast.makeText(a, a.getString(R.string.plan_excel_failed, e.message ?: ""), Toast.LENGTH_LONG).show(); return
        }
        if (res.stops.isEmpty()) { Toast.makeText(a, R.string.plan_excel_empty, Toast.LENGTH_LONG).show(); return }
        val label = a.getString(R.string.plan_excel_label)
        PlanData.add(a, label, res)
        refresh()
        showReadSummary(listOf(summaryLine(label, res)), null)
    }

    private fun summaryLine(label: String, res: SheetReader.Result): String {
        val t = res.sheetTotal
        return label + ": " + a.getString(R.string.plan_source_rows, res.stops.size) + when {
            t == null -> ""
            res.totalOk -> " · " + a.getString(R.string.plan_total_ok, vol(t))
            else -> " · " + a.getString(R.string.plan_total_bad, vol(t), vol(res.readTotal))
        }
    }

    private fun showReadSummary(lines: List<String>, failed: Exception?) {
        val msg = buildString {
            append(lines.joinToString("\n"))
            if (failed != null) append("\n\n").append(a.getString(R.string.plan_read_failed_hint))
            append("\n\n").append(a.getString(R.string.plan_read_check))
        }
        MaterialAlertDialogBuilder(a).setTitle(R.string.plan_read_title).setMessage(msg).setPositiveButton(R.string.done, null).show()
    }
}
