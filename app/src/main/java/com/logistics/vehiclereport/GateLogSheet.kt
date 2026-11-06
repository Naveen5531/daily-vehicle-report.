package com.logistics.vehiclereport

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.util.Date

/**
 * Today's Drishti gate log mapped to the day's vehicle data: every vehicle that came through the DC gate, with
 * check-in / check-out, and what it is to the report – a dedicated vehicle, a replacement (and for which vehicle),
 * posted in WhatsApp, or another vehicle (kept as a record only). The destination is matched to the store list.
 */
object GateLogSheet {

    private enum class Show { ALL, DEDICATED, REPLACEMENT, OTHER, MISSING }

    fun show(a: MainActivity) {
        val dialog = BottomSheetDialog(a)
        val dp = a.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val today = ReportBuilder.startOfDay()
        val s = a.loadSnapshot()
        val mapped = DrishtiData.map(DrishtiStore.log(a, today), s.vehicles, s.records, today)
        val dedicated = s.vehicles.filter { it.dedicated }
        val atGate = mapped.filter { it.link == DrishtiData.Link.DEDICATED || it.link == DrishtiData.Link.REPLACEMENT }
            .map { it.forVehicle }.toSet()
        val missing = dedicated.filter { it.number !in atGate }
        val masters = MasterData.get(a)
        val t = ReportBuilder.timeFmt()

        val outer = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(16), px(18), px(8))
        }
        outer.addView(TextView(a).apply {
            setText(R.string.gate_log_title)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.color(a, R.color.ink))
        })
        val inside = mapped.count { it.row.checkOut == 0L }
        outer.addView(TextView(a).apply {
            text = a.getString(R.string.gate_log_summary, mapped.size, atGate.size, dedicated.size, inside) + "\n" +
                DrishtiActivity.statusText(a)
            textSize = 12.5f
            setTextColor(Ui.color(a, R.color.muted))
            setPadding(0, px(4), 0, px(8))
        })

        val chips = ChipGroup(a).apply { isSingleSelection = true; isSelectionRequired = true }
        val labels = linkedMapOf(
            Show.ALL to a.getString(R.string.gate_f_all, mapped.size),
            Show.DEDICATED to a.getString(R.string.gate_f_dedicated, mapped.count { it.link == DrishtiData.Link.DEDICATED }),
            Show.REPLACEMENT to a.getString(R.string.gate_f_repl, mapped.count { it.link == DrishtiData.Link.REPLACEMENT }),
            Show.OTHER to a.getString(R.string.gate_f_other, mapped.count { it.link == DrishtiData.Link.POSTED || it.link == DrishtiData.Link.OTHER }),
            Show.MISSING to a.getString(R.string.gate_f_missing, missing.size)
        )
        val chipOf = HashMap<Int, Show>()
        labels.forEach { (k, label) ->
            val c = Chip(a).apply { text = label; isCheckable = true; id = View.generateViewId(); isChecked = k == Show.ALL }
            chipOf[c.id] = k
            chips.addView(c)
        }
        outer.addView(chips)

        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(a).apply { addView(list) }
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(460)))

        fun pill(text: String, fg: Int, bg: Int) = TextView(a).apply {
            this.text = text
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Ui.color(a, fg))
            backgroundTintList = ColorStateList.valueOf(Ui.color(a, bg))
            setBackgroundResource(R.drawable.pill_bg)
            setPadding(px(8), px(3), px(8), px(3))
        }

        fun row(title: String, pillView: TextView, lines: List<String>, onTap: (() -> Unit)?) {
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12), px(10), px(12), px(10))
                setBackgroundResource(R.drawable.gate_row_bg)
                backgroundTintList = ColorStateList.valueOf(Ui.color(a, R.color.surface))
                if (onTap != null) setOnClickListener { onTap() }
            }
            val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(TextView(a).apply {
                text = title
                textSize = 15f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Ui.color(a, R.color.ink))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(pillView)
            box.addView(top)
            lines.filter { it.isNotBlank() }.forEach { l ->
                box.addView(TextView(a).apply {
                    text = l
                    textSize = 12.5f
                    setTextColor(Ui.color(a, R.color.muted))
                    setPadding(0, px(2), 0, 0)
                })
            }
            list.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = px(8) })
        }

        fun render(show: Show) {
            list.removeAllViews()
            if (show == Show.MISSING) {
                missing.forEach { v ->
                    row(v.number, pill(a.getString(R.string.gate_yet), R.color.wait, R.color.wait_bg),
                        listOf(Ui.joinNonBlank(v.type, v.transporter, v.driver), a.getString(R.string.gate_not_in_log))) {
                        dialog.dismiss(); VehicleDetailSheet.show(a, v.number)
                    }
                }
            } else {
                mapped.filter {
                    when (show) {
                        Show.DEDICATED -> it.link == DrishtiData.Link.DEDICATED
                        Show.REPLACEMENT -> it.link == DrishtiData.Link.REPLACEMENT
                        Show.OTHER -> it.link == DrishtiData.Link.POSTED || it.link == DrishtiData.Link.OTHER
                        else -> true
                    }
                }.forEach { m ->
                    val r = m.row
                    val p = when (m.link) {
                        DrishtiData.Link.DEDICATED -> pill(a.getString(R.string.gate_l_dedicated), R.color.ok, R.color.ok_bg)
                        DrishtiData.Link.REPLACEMENT -> pill(a.getString(R.string.gate_l_repl), R.color.chk, R.color.chk_bg)
                        DrishtiData.Link.POSTED -> pill(a.getString(R.string.gate_l_posted), R.color.wa_green, R.color.wa_bg)
                        DrishtiData.Link.OTHER -> pill(a.getString(R.string.gate_l_other), R.color.gone, R.color.gone_bg)
                    }
                    val times = Ui.joinNonBlank(
                        r.checkIn.takeIf { it > 0 }?.let { a.getString(R.string.gate_at_reported, t.format(Date(it))) }.orEmpty(),
                        if (r.checkOut > 0) a.getString(R.string.gate_at_departed, t.format(Date(r.checkOut))) else a.getString(R.string.gate_inside)
                    )
                    val store = r.destination.takeIf { it.isNotBlank() }?.let { dest ->
                        masters.matchStore(dest)?.let { st -> a.getString(R.string.gate_store_line, dest, st.store.code, st.store.name) }
                            ?: a.getString(R.string.gate_dest_line, dest)
                    }.orEmpty()
                    row(r.vehicle, p, listOf(
                        times,
                        if (m.link == DrishtiData.Link.REPLACEMENT) a.getString(R.string.gate_for_vehicle, m.forVehicle) else "",
                        Ui.joinNonBlank(r.type, r.driver, r.mobile),
                        store
                    )) { dialog.dismiss(); VehicleDetailSheet.show(a, m.forVehicle) }
                }
            }
            if (list.childCount == 0) list.addView(TextView(a).apply {
                setText(if (mapped.isEmpty()) R.string.gate_log_empty else R.string.today_empty_filter)
                setTextColor(Ui.color(a, R.color.muted))
                setPadding(0, px(16), 0, px(16))
            })
        }
        chips.setOnCheckedStateChangeListener { _, ids -> render(chipOf[ids.firstOrNull()] ?: Show.ALL) }
        render(Show.ALL)

        dialog.setContentView(outer)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        (outer.parent as? View)?.backgroundTintList = ColorStateList.valueOf(Ui.color(a, R.color.bg_light))
        dialog.show()
    }
}
