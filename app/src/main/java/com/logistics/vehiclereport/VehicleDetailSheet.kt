package com.logistics.vehiclereport

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opens when a vehicle number is tapped (Today, WhatsApp, Vehicles): every detail known for the vehicle,
 * which details are still missing (with "Add pending details"), today's status and its recent reports.
 */
object VehicleDetailSheet {

    private const val MAX_POSTS = 8

    /** Opens the right sheet for a WhatsApp entry: one vehicle, a choice when 4 digits fit several, or the code alone. */
    fun showForRecord(a: MainActivity, r: VehicleRecord) {
        when (r.match) {
            MatchType.MULTI -> {
                val options = r.candidates.toTypedArray()
                MaterialAlertDialogBuilder(a)
                    .setTitle(a.getString(R.string.det_which, r.typed))
                    .setItems(options) { _, i -> show(a, options[i]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            MatchType.CODE -> show(a, r.typed.ifBlank { r.vehicle }, codeOnlyIn = true)
            else -> show(a, r.candidates.firstOrNull() ?: r.vehicle)
        }
    }

    fun show(a: MainActivity, number: String, codeOnlyIn: Boolean = false) {
        // A bare 4-digit code (posted without the full number) has no vehicle of its own yet.
        val codeOnly = codeOnlyIn || (number.isNotEmpty() && number.length <= 4 && number.all { it.isDigit() })
        val dialog = BottomSheetDialog(a)
        val view = LayoutInflater.from(a).inflate(R.layout.sheet_vehicle_detail, null)
        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // Same soft page colour as the app behind the white cards, keeping the sheet's rounded top.
        (view.parent as? View)?.backgroundTintList = ColorStateList.valueOf(Ui.color(a, R.color.bg_light))
        render(a, dialog, view, number, codeOnly)
        dialog.show()
    }

    private fun render(a: MainActivity, dialog: BottomSheetDialog, view: View, number: String, codeOnly: Boolean) {
        try { VehicleStore.fillBlanksFromPosts(a) } catch (e: Exception) { }
        val s = a.loadSnapshot()
        val v = if (codeOnly) null else s.vehicles.firstOrNull { it.number == number }
        val posts = s.records.filter { r ->
            if (codeOnly) r.match == MatchType.CODE && (r.typed == number || r.vehicle == number)
            else number in r.candidates || r.vehicle == number
        }.sortedByDescending { it.time }
        val m = MasterData.get(a)
        val seen = fromWhatsApp(a, number, posts, codeOnly)
        // What the list has, completed with what WhatsApp posts say (a detail found in a post is not "pending").
        val shown = (v ?: DedicatedVehicle(number)).let { x ->
            x.copy(
                type = x.type.ifBlank { seen.type }, transporter = x.transporter.ifBlank { seen.transporter },
                driver = x.driver.ifBlank { seen.driver }, mobile = x.mobile.ifBlank { seen.mobile },
                route = x.route.ifBlank { seen.route }
            )
        }
        val today = ReportBuilder.startOfDay()

        // ---- Header ----
        Ui.avatar(view.findViewById(R.id.detAvatar), shown.transporter, number)
        view.findViewById<TextView>(R.id.detPlate).text = number
        view.findViewById<TextView>(R.id.detSub).apply {
            text = Ui.joinNonBlank(shown.type, shown.transporter).ifEmpty { a.getString(R.string.no_details) }
        }
        val pill = view.findViewById<TextView>(R.id.detStatus)
        val gates = GateMarks.gates(a)
        val marks = gates.forDay(today)
        val todayDay = when {
            codeOnly -> null
            v != null -> ReportBuilder.dayStatus(listOf(v), s.records, today, marks, gates.legacy(today)).firstOrNull()
            posts.any { it.time >= today } || marks[number] != null -> VehicleDay(DedicatedVehicle(number), DayStatus.REPORTED,
                posts.filter { it.time >= today }, inList = false, mark = marks[number], legacy = gates.legacy(today))
            else -> null
        }
        // A listed vehicle with no post today still gets its "Not posted" pill and can be marked by hand.
        val state = todayDay?.state ?: if (v != null && !codeOnly) TodayState.NOT_POSTED else null
        if (state != null) {
            Ui.styleState(pill, state)
            pill.visibility = View.VISIBLE
        } else {
            pill.setText(R.string.det_not_today)
            pill.setTextColor(Ui.color(a, R.color.muted))
            pill.backgroundTintList = ColorStateList.valueOf(Ui.color(a, R.color.gray_bg))
            pill.visibility = View.VISIBLE
        }

        // ---- At the DC today: Yet to report / Reported / Departed ----
        val gateShown = state != null
        view.findViewById<View>(R.id.detGateTitle).visibility = if (gateShown) View.VISIBLE else View.GONE
        val gateGroup = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.gateGroup)
        gateGroup.visibility = if (gateShown) View.VISIBLE else View.GONE
        val gateTimes = view.findViewById<TextView>(R.id.detGateTimes)
        gateTimes.text = Ui.gateTimes(a, todayDay?.mark)
        gateTimes.visibility = if (gateShown && gateTimes.text.isNotBlank()) View.VISIBLE else View.GONE
        if (state != null) Ui.bindGate(gateGroup, state) { gate ->
            a.holdTodayOrder()
            GateMarks.set(a, today, number, gate)
            if (dialog.isShowing) render(a, dialog, view, number, codeOnly)
        }

        // ---- Number plate: scan when only the last 4 were posted today; take a scan back ----
        val scanBtn = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.detScan)
        val postedToday = posts.any { it.time >= today }
        val scannedToday = todayDay?.scannedAt ?: 0L
        when {
            scannedToday > 0 -> {
                scanBtn.visibility = View.VISIBLE
                scanBtn.setText(R.string.scan_remove)
                scanBtn.setOnClickListener {
                    PlateScans.remove(a, today, VehicleParser.last4(number), number)
                    if (dialog.isShowing) render(a, dialog, view, number, codeOnly)
                }
            }
            (codeOnly && postedToday) || todayDay?.last4Only == true -> {
                scanBtn.visibility = View.VISIBLE
                scanBtn.setText(R.string.scan_plate)
                scanBtn.setOnClickListener { dialog.dismiss(); a.scanPlate(number) }
            }
            else -> scanBtn.visibility = View.GONE
        }

        // ---- Tags ----
        val tags = view.findViewById<ViewGroup>(R.id.detTags)
        tags.removeAllViews()
        if (!codeOnly) tags.addView(Ui.infoChip(a, a.getString(R.string.last4_label, VehicleParser.last4(number))))
        tags.addView(when {
            codeOnly -> Ui.infoChip(a, a.getString(R.string.det_code_only), Ui.Tone.WARN)
            v == null -> Ui.infoChip(a, a.getString(R.string.det_not_in_list), Ui.Tone.WARN)
            v.dedicated -> Ui.infoChip(a, a.getString(R.string.det_reporting), Ui.Tone.OK)
            else -> Ui.infoChip(a, a.getString(R.string.record_only))
        })

        // ---- Each detail: from the vehicle list, else from WhatsApp, else pending ----
        class Field(val label: Int, val inList: String, val posted: String, val postedAt: Long?, val put: (DedicatedVehicle, String) -> DedicatedVehicle)
        fun at(f: (VehicleRecord) -> String) = posts.firstOrNull { it.match != MatchType.MULTI && f(it).isNotBlank() }?.time
        val fields = listOf(
            Field(R.string.field_type, v?.type.orEmpty(), seen.type, at { it.size }) { x, t -> x.copy(type = t) },
            Field(R.string.field_transporter, v?.transporter.orEmpty(), seen.transporter, at { it.vendor }) { x, t -> x.copy(transporter = t) },
            Field(R.string.field_driver, v?.driver.orEmpty(), seen.driver, at { it.driver }) { x, t -> x.copy(driver = t) },
            Field(R.string.field_mobile, v?.mobile.orEmpty(), seen.mobile, at { it.mobile }) { x, t -> x.copy(mobile = t) },
            Field(R.string.field_route, v?.route.orEmpty(), seen.route, at { it.city }) { x, t -> x.copy(route = t) }
        )
        fun name(f: Field) = a.getString(f.label).removeSuffix(" *")
        val missing = fields.filter { it.inList.isBlank() && it.posted.isBlank() }
        val postedOnly = fields.filter { it.inList.isBlank() && it.posted.isNotBlank() }

        fun again() { if (dialog.isShowing) render(a, dialog, view, number, codeOnly) }
        fun reopenAfter(d: BottomSheetDialog) = d.setOnDismissListener { again() }
        fun saveToList(list: List<Field>) {
            val cur = VehicleStore.findVehicle(a, number) ?: return
            VehicleStore.saveVehicle(a, list.fold(cur) { x, f -> f.put(x, f.posted) })
            android.widget.Toast.makeText(a, R.string.saved, android.widget.Toast.LENGTH_SHORT).show()
            again()
        }

        val card = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.detPendingCard)
        val pTitle = view.findViewById<TextView>(R.id.detPendingTitle)
        val pText = view.findViewById<TextView>(R.id.detPendingText)
        val pButton = view.findViewById<MaterialButton>(R.id.detPendingButton)
        fun cardTone(bg: Int, fg: Int) {
            card.setCardBackgroundColor(Ui.color(a, bg)); pTitle.setTextColor(Ui.color(a, fg))
        }
        when {
            v == null -> {
                card.visibility = View.VISIBLE
                cardTone(R.color.warn_bg, R.color.chk)
                pTitle.setText(if (codeOnly) R.string.det_code_title else R.string.det_not_in_list_title)
                pText.text = if (codeOnly) a.getString(R.string.det_code_text, number) else a.getString(R.string.det_not_in_list_text)
                pButton.setText(R.string.det_add_to_list)
                pButton.setIconResource(R.drawable.ic_add)
                pButton.setOnClickListener {
                    val d = VehicleFormSheet.show(a, null, endsWith = if (codeOnly) number else null,
                        suggest = if (codeOnly) seen.copy(number = "") else seen)
                    d.setOnDismissListener {
                        val added = if (codeOnly) null else VehicleStore.findVehicle(a, number)
                        if (added != null && dialog.isShowing) render(a, dialog, view, number, false)
                    }
                }
            }
            missing.isNotEmpty() -> {
                card.visibility = View.VISIBLE
                cardTone(R.color.warn_bg, R.color.chk)
                pTitle.text = a.resources.getQuantityString(R.plurals.det_pending_title, missing.size, missing.size)
                pText.text = missing.joinToString(", ") { name(it) } +
                    if (postedOnly.isNotEmpty()) "\n\n" + a.getString(R.string.det_posted_note, postedOnly.joinToString(", ") { name(it) }) else ""
                pButton.setText(R.string.det_add_pending)
                pButton.setIconResource(R.drawable.ic_add)
                pButton.setOnClickListener { reopenAfter(VehicleFormSheet.show(a, v, suggest = seen)) }
            }
            postedOnly.isNotEmpty() -> {
                card.visibility = View.VISIBLE
                cardTone(R.color.ok_bg, R.color.ok)
                pTitle.setText(R.string.det_found_title)
                pText.text = a.getString(R.string.det_found_text, postedOnly.joinToString(", ") { name(it) })
                pButton.setText(R.string.det_save_found)
                pButton.setIconResource(R.drawable.ic_download)
                pButton.setOnClickListener { saveToList(postedOnly) }
            }
            else -> card.visibility = View.GONE
        }

        // ---- Details ----
        val rows = view.findViewById<LinearLayout>(R.id.detRows)
        rows.removeAllViews()
        val inflater = LayoutInflater.from(a)
        fun row(label: String, value: String, sub: String = "", action: String? = null, subTone: Int = R.color.muted, onAction: (() -> Unit)? = null) {
            val r = inflater.inflate(R.layout.row_detail, rows, false)
            r.findViewById<TextView>(R.id.rowLabel).text = label
            val valueView = r.findViewById<TextView>(R.id.rowValue)
            if (value.isBlank()) {
                valueView.setText(R.string.det_pending_value)
                valueView.setTextColor(Ui.color(a, R.color.no))
            } else valueView.text = value
            r.findViewById<TextView>(R.id.rowSub).apply {
                text = sub; setTextColor(Ui.color(a, subTone))
                visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
            }
            r.findViewById<TextView>(R.id.rowAction).apply {
                if (action != null && onAction != null) {
                    text = action; visibility = View.VISIBLE; setOnClickListener { onAction() }
                } else visibility = View.GONE
            }
            if (rows.childCount > 0) rows.addView(View(a).apply {
                setBackgroundColor(Ui.color(a, R.color.line))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
            rows.addView(r)
        }
        val edit: () -> Unit = {
            if (v != null) reopenAfter(VehicleFormSheet.show(a, v, suggest = seen)) else pButton.performClick()
        }
        val add = a.getString(R.string.det_add)
        val day = SimpleDateFormat("d MMM", Locale.getDefault())
        val store = m.matchStore(shown.route)
        val rate = store?.let { st -> RateCard.get(a).lookup(st.store.code, shown.type) }
        for (f in fields) {
            val value = f.inList.ifBlank { f.posted }
            // Extra line: vendor code, or the store's code and region.
            val extra = when (f.label) {
                R.string.field_transporter -> if (value.isBlank()) "" else
                    m.resolveVendor(value, "", "", value)?.code?.takeIf { it.isNotBlank() }?.let { a.getString(R.string.opt_code, it) }.orEmpty()
                R.string.field_route -> m.matchStore(value)?.let { st ->
                    val full = "${st.store.code} ${st.store.name}"
                    if (value.equals(full, true)) st.store.region else Ui.joinNonBlank(full, st.store.region)
                }.orEmpty()
                else -> ""
            }
            when {
                f.inList.isNotBlank() -> row(name(f), value, sub = extra,
                    action = if (f.label == R.string.field_mobile) a.getString(R.string.call) else null,
                    onAction = { Ui.dial(a, value) })
                f.posted.isNotBlank() -> row(name(f), value,
                    sub = Ui.joinNonBlank(extra, a.getString(R.string.det_from_whatsapp, f.postedAt?.let { day.format(Date(it)) }.orEmpty())),
                    subTone = R.color.ok,
                    action = if (v != null) a.getString(R.string.det_save) else null,
                    onAction = { saveToList(listOf(f)) })
                else -> row(name(f), "", action = add, onAction = edit)
            }
        }
        if (rate != null) row(a.getString(R.string.det_rate), "₹ " + RatesTab.formatAmount(rate.amount), sub = rate.vehicleType)
        row(a.getString(R.string.det_from), Masters.DISPATCH_FROM)

        // ---- Buttons ----
        view.findViewById<MaterialButton>(R.id.detCall).apply {
            isEnabled = shown.mobile.isNotBlank()
            setOnClickListener { Ui.dial(a, shown.mobile) }
        }
        view.findViewById<MaterialButton>(R.id.detEdit).apply {
            setText(if (v == null) R.string.det_add_to_list else R.string.det_edit)
            setOnClickListener { edit() }
        }

        // ---- Recent reports ----
        var days = 0
        for (d in 0 until 7) {
            val start = ReportBuilder.startOfDay(today - d * ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2)
            if (posts.any { it.time >= start && it.time < start + ReportBuilder.DAY_MS }) days++
        }
        view.findViewById<TextView>(R.id.detWeek).text = a.getString(R.string.det_week, days)
        val list = view.findViewById<LinearLayout>(R.id.detPosts)
        list.removeAllViews()
        val fmt = SimpleDateFormat("EEE d MMM · hh:mm a", Locale.getDefault())
        if (posts.isEmpty()) {
            list.addView(TextView(a).apply {
                setText(R.string.det_no_posts)
                setTextColor(Ui.color(a, R.color.muted)); textSize = 13f
                setPadding(4, 8, 4, 8)
            })
        }
        for (p in posts.take(MAX_POSTS)) {
            val r = inflater.inflate(R.layout.row_post, list, false)
            r.findViewById<TextView>(R.id.postWhen).text = fmt.format(Date(p.time)) + if (p.count > 1) " · ${p.count}×" else ""
            val tag = r.findViewById<TextView>(R.id.postTag)
            // A post shows it was sent; reported / departed is marked by hand above.
            Ui.styleState(tag, if (p.match == MatchType.MULTI) TodayState.CHECK else TodayState.REPORTED)
            if (p.match != MatchType.MULTI) tag.setText(R.string.st_posted)
            val st = m.matchStore(p.city)
            r.findViewById<TextView>(R.id.postWhere).text = "→ " + (st?.let { Ui.joinNonBlank("${it.store.code} ${it.store.name}", it.store.region) }
                ?: p.city.ifBlank { a.getString(R.string.location_unknown) })
            r.findViewById<TextView>(R.id.postWho).text = Ui.joinNonBlank(
                a.getString(R.string.det_by, p.senders.joinToString(", ").ifBlank { p.sender }),
                p.size, p.mobile, if (p.replacement.isNotBlank()) a.getString(R.string.replacement_line, p.replacement) else "")
            list.addView(r)
        }
    }

    /** For the Vehicles list: what WhatsApp posts say about each vehicle. */
    fun postedDetails(a: android.content.Context, vehicles: List<DedicatedVehicle>, records: List<VehicleRecord>) =
        PostedDetails.forVehicles(a, vehicles, records)

    private fun fromWhatsApp(a: android.content.Context, number: String, posts: List<VehicleRecord>, codeOnly: Boolean): DedicatedVehicle =
        PostedDetails.of(a, number, posts).let { if (codeOnly) it.copy(number = "") else it }
}
