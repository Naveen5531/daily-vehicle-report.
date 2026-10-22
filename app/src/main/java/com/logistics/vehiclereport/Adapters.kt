package com.logistics.vehiclereport

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Today tab: one card per dedicated vehicle with its status. */
class StatusAdapter(
    private val onOpen: (VehicleDay) -> Unit = {},
    /** Yet to report / Reported / Departed tapped on a row (null = mark cleared). */
    private val onGate: (VehicleDay, Gate?) -> Unit = { _, _ -> },
    /** Camera button on a vehicle posted with only its last 4 digits. */
    private val onScan: (VehicleDay) -> Unit = {}
) : RecyclerView.Adapter<StatusAdapter.Holder>() {
    private var items: List<VehicleDay> = emptyList()
    private val time = SimpleDateFormat("hh:mm a", Locale.getDefault())

    fun submit(list: List<VehicleDay>) { items = list; notifyDataSetChanged() }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val plate: TextView = v.findViewById(R.id.plateText)
        val meta1: TextView = v.findViewById(R.id.meta1)
        val meta2: TextView = v.findViewById(R.id.meta2)
        val pill: TextView = v.findViewById(R.id.statusPill)
        val timeText: TextView = v.findViewById(R.id.timeText)
        val call: MaterialButton = v.findViewById(R.id.callButton)
        val gateTimes: TextView = v.findViewById(R.id.gateTimes)
        val plateNote: TextView = v.findViewById(R.id.plateNote)
        val scan: MaterialButton = v.findViewById(R.id.scanButton)
        val gate: com.google.android.material.button.MaterialButtonToggleGroup = v.findViewById(R.id.gateGroup)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_status, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val d = items[position]
        val v = d.vehicle
        val ctx = h.itemView.context
        h.plate.text = v.number
        h.meta1.text = if (!d.inList) Ui.joinNonBlank(ctx.getString(R.string.not_in_list), v.type, v.transporter)
            else if (!v.dedicated) Ui.joinNonBlank(ctx.getString(R.string.record_only), v.type, v.transporter)
            else Ui.joinNonBlank(v.type, v.driver, v.transporter).ifEmpty { ctx.getString(R.string.no_details) }
        val first = d.hits.firstOrNull()
        val withRepl = d.hits.firstOrNull { it.replacement.isNotBlank() }
        h.meta2.text = when {
            withRepl != null -> Ui.joinNonBlank(ctx.getString(R.string.replacement_line, withRepl.replacement), withRepl.city)
            first != null -> Ui.joinNonBlank(ctx.getString(R.string.sent_by, first.typed, first.sender), first.city)
            else -> v.route
        }
        h.meta2.visibility = if (h.meta2.text.isNullOrBlank()) View.GONE else View.VISIBLE
        Ui.avatar(h.avatar, v.transporter.ifBlank { first?.vendor.orEmpty() }, v.number)
        Ui.styleState(h.pill, d.state)
        h.gateTimes.text = Ui.gateTimes(ctx, d.mark)
        h.gateTimes.visibility = if (h.gateTimes.text.isNullOrBlank()) View.GONE else View.VISIBLE
        Ui.bindGate(h.gate, d.state) { onGate(d, it) }
        when {
            d.last4Only -> {
                h.plateNote.setText(R.string.last4_note)
                h.plateNote.setTextColor(Ui.color(ctx, R.color.chk))
            }
            d.scannedAt > 0 -> {
                h.plateNote.text = ctx.getString(R.string.scanned_note, time.format(Date(d.scannedAt)))
                h.plateNote.setTextColor(Ui.color(ctx, R.color.ok))
            }
            else -> h.plateNote.text = ""
        }
        h.plateNote.visibility = if (h.plateNote.text.isNullOrBlank()) View.GONE else View.VISIBLE
        h.scan.visibility = if (d.last4Only) View.VISIBLE else View.GONE
        h.scan.setOnClickListener { onScan(d) }
        h.timeText.text = when {
            first == null -> "—"
            d.hits.sumOf { it.count } > 1 -> time.format(Date(first.time)) + " · ${d.hits.sumOf { it.count }}×"
            else -> time.format(Date(first.time))
        }
        h.call.visibility = if (v.mobile.isBlank()) View.GONE else View.VISIBLE
        h.call.setOnClickListener { Ui.dial(ctx, v.mobile) }
        h.itemView.setOnClickListener { onOpen(d) }
    }
}

/** Draws part of a text in another font (used for the region after the store name). */
private class FontSpan(private val tf: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: android.text.TextPaint) { tp.typeface = tf }
    override fun updateMeasureState(tp: android.text.TextPaint) { tp.typeface = tf }
}

private var regularFont: android.graphics.Typeface? = null
private fun regular(ctx: android.content.Context): android.graphics.Typeface? {
    if (regularFont == null) regularFont = try {
        androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.jakarta_medium)
    } catch (e: Exception) { null }
    return regularFont
}

/** WhatsApp tab: captured messages, each shown as DC → store with detail chips. */
class FeedAdapter(private val onOpen: (VehicleRecord) -> Unit = {}) : RecyclerView.Adapter<FeedAdapter.Holder>() {
    private var items: List<VehicleRecord> = emptyList()
    private var vehicles: Map<String, DedicatedVehicle> = emptyMap()
    private var showDate = false
    private val time = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val dateTime = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    fun submit(list: List<VehicleRecord>, vehicleMap: Map<String, DedicatedVehicle>, withDate: Boolean) {
        items = list; vehicles = vehicleMap; showDate = withDate; notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val plate: TextView = v.findViewById(R.id.plateText)
        val timeText: TextView = v.findViewById(R.id.timeText)
        val from: TextView = v.findViewById(R.id.routeFrom)
        val to: TextView = v.findViewById(R.id.routeTo)
        val chips: ViewGroup = v.findViewById(R.id.infoChips)
        val tag: TextView = v.findViewById(R.id.tagText)
        val who: TextView = v.findViewById(R.id.whoText)
        val msg: TextView = v.findViewById(R.id.msgText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_feed, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val r = items[position]
        val ctx = h.itemView.context
        h.plate.text = r.vehicle
        h.timeText.text = (if (showDate) dateTime else time).format(Date(r.time)) + if (r.count > 1) " · ${r.count}×" else ""
        val known = r.candidates.firstOrNull()?.let { vehicles[it] }
        val transporter = known?.transporter.orEmpty()

        // Route: your DC → matched store (or the place as written)
        val m = MasterData.get(ctx)
        val st = m.matchStore(r.city)
        val vd = m.resolveVendor(r.vendor, r.message, r.chat, transporter)
        h.from.text = Masters.DISPATCH_FROM
        h.to.text = android.text.SpannableStringBuilder().apply {
            when {
                st != null -> {
                    append("${st.store.code} ${st.store.name}")
                    val tail = Ui.joinNonBlank(st.store.region, if (st.others > 0) "+${st.others}" else "")
                    if (tail.isNotBlank()) {
                        val i = length
                        append(" · $tail")
                        setSpan(android.text.style.ForegroundColorSpan(Ui.color(ctx, R.color.muted)), i, length, 0)
                        regular(ctx)?.let { setSpan(FontSpan(it), i, length, 0) }
                    }
                }
                r.city.isNotBlank() -> append(r.city)
                else -> {
                    append(ctx.getString(R.string.location_unknown))
                    setSpan(android.text.style.ForegroundColorSpan(Ui.color(ctx, R.color.faint)), 0, length, 0)
                }
            }
        }

        // Detail chips: vendor, vehicle size, rate, driver, mobile, what matched
        val type = r.size.ifBlank { known?.type.orEmpty() }
        val rate = st?.let { RateCard.get(ctx).lookup(it.store.code, type) }
        val vendor = vd?.let { Ui.joinNonBlank(it.name, it.code) } ?: r.vendor
        h.chips.removeAllViews()
        listOf(vendor, type, rate?.let { "₹ " + RatesTab.formatAmount(it.amount) }.orEmpty(), r.driver, r.mobile)
            .filter { it.isNotBlank() }
            .forEach { h.chips.addView(Ui.infoChip(ctx, it)) }
        h.chips.addView(when {
            st != null && vd != null -> Ui.infoChip(ctx, ctx.getString(R.string.chip_matched), Ui.Tone.OK)
            st != null -> Ui.infoChip(ctx, ctx.getString(R.string.chip_location_only), Ui.Tone.WARN)
            vd != null -> Ui.infoChip(ctx, ctx.getString(R.string.chip_vendor_only), Ui.Tone.WARN)
            else -> Ui.infoChip(ctx, ctx.getString(R.string.chip_not_matched), Ui.Tone.BAD)
        })

        // How the vehicle number was recognised
        val (text, colorRes) = when {
            r.match == MatchType.MULTI -> ctx.getString(R.string.tag_multi, r.typed, r.candidates.size) to R.color.chk
            r.match == MatchType.CODE -> ctx.getString(R.string.tag_code_unknown, r.typed) to R.color.chk
            r.match == MatchType.LAST4 -> (if (known != null && known.driver.isNotBlank())
                ctx.getString(R.string.tag_last4_driver, r.typed, known.driver)
            else ctx.getString(R.string.tag_last4, r.typed)) to R.color.ok
            known != null -> ctx.getString(R.string.tag_full) to R.color.ok
            else -> ctx.getString(R.string.tag_full_unknown) to R.color.muted
        }
        h.tag.text = listOfNotNull(
            text,
            if (r.replacement.isNotBlank()) ctx.getString(R.string.replacement_line, r.replacement) else null
        ).joinToString("\n")
        h.tag.setTextColor(Ui.color(ctx, colorRes))
        h.who.text = ctx.getString(R.string.who_in_group, r.sender, r.chat)
        h.msg.text = r.message
        h.itemView.setOnClickListener { onOpen(r) }
    }
}

/** Vehicles tab: dedicated vehicles with details. */
class VehicleDetailAdapter(
    private val onEdit: (DedicatedVehicle) -> Unit,
    private val onOpen: (DedicatedVehicle) -> Unit = onEdit
) : RecyclerView.Adapter<VehicleDetailAdapter.Holder>() {
    private var items: List<DedicatedVehicle> = emptyList()

    private var posted: Map<String, DedicatedVehicle> = emptyMap()

    /** [postedDetails]: details seen in WhatsApp, shown where the list has none. */
    fun submit(list: List<DedicatedVehicle>, postedDetails: Map<String, DedicatedVehicle> = emptyMap()) {
        items = list; posted = postedDetails; notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val plate: TextView = v.findViewById(R.id.plateText)
        val last4: TextView = v.findViewById(R.id.last4Pill)
        val meta1: TextView = v.findViewById(R.id.meta1)
        val meta2: TextView = v.findViewById(R.id.meta2)
        val meta3: TextView = v.findViewById(R.id.meta3)
        val edit: MaterialButton = v.findViewById(R.id.editButton)
        val call: MaterialButton = v.findViewById(R.id.callButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_vehicle_detail, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val v = items[position]
        val ctx = h.itemView.context
        h.plate.text = v.number
        Ui.avatar(h.avatar, v.transporter, v.number)
        h.last4.text = ctx.getString(R.string.last4_short, VehicleParser.last4(v.number))
        h.meta1.text = Ui.joinNonBlank(if (v.dedicated) "" else ctx.getString(R.string.record_only), v.type, v.transporter)
            .ifEmpty { ctx.getString(R.string.no_details_tap) }
        val p = posted[v.number]
        val wa = ctx.getString(R.string.wa_short)
        fun pick(own: String, seen: String?) = own.ifBlank { seen.orEmpty().let { if (it.isBlank()) "" else "$it ($wa)" } }
        h.meta2.text = "👤 " + Ui.joinNonBlank(pick(v.driver, p?.driver), pick(v.mobile, p?.mobile)).ifEmpty { "—" }
        h.meta3.text = "📍 " + pick(v.route, p?.route).ifBlank { "—" }
        h.edit.setOnClickListener { onEdit(v) }
        h.itemView.setOnClickListener { onOpen(v) }
        h.call.visibility = if (v.mobile.isBlank()) View.GONE else View.VISIBLE
        h.call.setOnClickListener { Ui.dial(ctx, v.mobile) }
    }
}
