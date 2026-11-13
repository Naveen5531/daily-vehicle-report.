package com.logistics.vehiclereport

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Date

/**
 * Yard: Standing / In-transit / At-DC-now vehicles, pulled from Drishti by the office Excel and sent through the
 * account's own Firebase. Styled to match the app (emerald hero, white stat tiles, status pills).
 */
class YardActivity : AppCompatActivity() {

    private data class Tab(val sheet: String, val label: String)

    private val tabs = listOf(
        Tab("Standing", "Standing"),
        Tab("Transit", "In-transit"),
        Tab("overdue", "Overdue")
    )
    private var current = 0
    private var query = ""
    private var dedicatedOnly = false

    private lateinit var updatedPill: TextView
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var dedChip: TextView
    private lateinit var shiftBtn: TextView
    private val tileCount = arrayOfNulls<TextView>(3)
    private val tileWrap = arrayOfNulls<View>(3)

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == YardStore.KEY_AT) runOnUiThread { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = savedInstanceState?.getInt("tab") ?: 0
        setContentView(build())
        refresh()
        pullNow(quiet = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.putInt("tab", current)
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences("yard", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onStop() {
        getSharedPreferences("yard", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(listener)
        super.onStop()
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun font(id: Int): Typeface? = try { ResourcesCompat.getFont(this, id) } catch (e: Exception) { null }
    private fun tv(text: String, sizeSp: Float, colorId: Int, fontId: Int? = null) = TextView(this).apply {
        this.text = text; textSize = sizeSp; setTextColor(color(colorId)); fontId?.let { typeface = font(it) }
    }
    private fun pill(bgRes: Int, tintId: Int): View = View(this).apply { setBackgroundResource(bgRes); backgroundTintList = ContextCompat.getColorStateList(this@YardActivity, tintId) }

    // ---------- layout ----------
    private fun build(): View {
        val frame = FrameLayout(this).apply { fitsSystemWindows = true; setBackgroundColor(color(R.color.bg_light)) }
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        top.addView(tv("‹", 30f, R.color.ink).apply {
            setPadding(0, 0, dp(14), dp(4)); setOnClickListener { finish() }
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(tv(getString(R.string.yard_title), 26f, R.color.ink, R.font.bricolage_extrabold))
        titles.addView(tv(getString(R.string.yard_subtitle), 12.5f, R.color.muted, R.font.jakarta_medium))
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        outer.addView(top)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(28)) }

        body.addView(hero())

        // Search + dedicated chip
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val search = EditText(this).apply {
            hint = getString(R.string.yard_search); setHintTextColor(color(R.color.faint))
            setTextColor(color(R.color.ink)); textSize = 14f; typeface = font(R.font.jakarta_medium)
            isSingleLine = true
            setBackgroundResource(R.drawable.gate_row_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty().trim(); refresh() }
            })
        }
        row.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dedChip = tv(getString(R.string.yard_dedicated_only), 13f, R.color.muted, R.font.jakarta_bold).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.pill_bg)
            backgroundTintList = ContextCompat.getColorStateList(this@YardActivity, R.color.soft)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setOnClickListener { dedicatedOnly = !dedicatedOnly; styleDedChip(); refresh() }
        }
        row.addView(dedChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        shiftBtn = tv(getString(R.string.yard_shift_btn), 12.5f, R.color.emerald, R.font.jakarta_bold).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.pill_bg)
            backgroundTintList = ContextCompat.getColorStateList(this@YardActivity, R.color.brand_light)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            setOnClickListener { shiftDialog() }
        }
        body.addView(shiftBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        empty = tv("", 14f, R.color.muted, R.font.jakarta_medium).apply { setPadding(dp(4), dp(28), dp(4), dp(28)); gravity = Gravity.CENTER }
        body.addView(empty)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)

        scroll.addView(body)
        outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(outer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return frame
    }

    private fun hero(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.hero_bg)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(getString(R.string.yard_hero), 13f, R.color.on_emerald_muted, R.font.jakarta_bold),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        updatedPill = tv("", 11.5f, R.color.emerald, R.font.jakarta_bold).apply {
            setBackgroundResource(R.drawable.pill_bg); backgroundTintList = ContextCompat.getColorStateList(this@YardActivity, R.color.mint)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        head.addView(updatedPill)
        // tap pill area for pull now
        head.setOnClickListener { pullNow(false) }
        card.addView(head)

        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.setPadding(0, dp(14), 0, 0)
        for (i in tabs.indices) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundResource(R.drawable.hero_stat_bg)
                setPadding(dp(6), dp(12), dp(6), dp(12))
                setOnClickListener { current = i; styleTiles(); refresh() }
            }
            val cnt = tv("0", 24f, R.color.emerald, R.font.bricolage_extrabold)
            val lbl = tv(tabs[i].label, 11.5f, R.color.muted, R.font.jakarta_bold).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) }
            tile.addView(cnt); tile.addView(lbl)
            tileCount[i] = cnt; tileWrap[i] = tile
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(10)
            tiles.addView(tile, lp)
        }
        card.addView(tiles)

        val linkBtn = tv(getString(R.string.yard_link), 12.5f, R.color.emerald, R.font.jakarta_bold).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.pill_bg)
            backgroundTintList = ContextCompat.getColorStateList(this@YardActivity, R.color.mint)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { DrishtiActivity.showLaptopLink(this@YardActivity) { refresh() } }
        }
        card.addView(linkBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        styleTiles(); styleDedChip()
        return card
    }

    private fun styleTiles() {
        for (i in tabs.indices) {
            val sel = i == current
            tileWrap[i]?.backgroundTintList = ContextCompat.getColorStateList(this, if (sel) R.color.mint else R.color.surface)
            tileWrap[i]?.alpha = if (sel) 1f else 0.9f
        }
    }

    private fun styleDedChip() {
        dedChip.backgroundTintList = ContextCompat.getColorStateList(this, if (dedicatedOnly) R.color.brand_light else R.color.soft)
        dedChip.setTextColor(color(if (dedicatedOnly) R.color.emerald else R.color.muted))
    }

    // ---------- data ----------
    private fun refresh() {
        val at = YardStore.lastAt(this)
        updatedPill.text = if (at > 0) ReportBuilder.timeFmt().format(Date(at)) else getString(R.string.yard_waiting_pill)

        val dedicated = try { VehicleStore.getVehicles(this).filter { it.dedicated }.map { it.number }.toSet() } catch (e: Exception) { emptySet() }
        tileCount[0]?.text = YardStore.table(this, tabs[0].sheet).rows.size.toString()
        tileCount[1]?.text = YardStore.table(this, tabs[1].sheet).rows.size.toString()
        val overdue = computeOverdue(dedicated)
        tileCount[2]?.text = overdue.size.toString()

        val isOverdue = current == 2
        shiftBtn.visibility = if (isOverdue) View.VISIBLE else View.GONE
        dedChip.visibility = if (isOverdue) View.GONE else View.VISIBLE
        list.removeAllViews()

        if (isOverdue) {
            val q = query.uppercase().filter { it.isLetterOrDigit() }
            val shown = overdue.filter { q.isEmpty() || (it.vehicle + it.store + it.detail).uppercase().filter { ch -> ch.isLetterOrDigit() }.contains(q) }
            empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            empty.text = if (at == 0L) getString(R.string.yard_empty_never) else getString(R.string.yard_overdue_none)
            for (o in shown) list.addView(overdueCard(o))
            return
        }

        val table = YardStore.table(this, tabs[current].sheet)
        val vCol = table.col("vehicle number", "vehicle no", "vehicle")
        val q = query.uppercase().filter { it.isLetterOrDigit() }
        val shown = table.rows.filter { r ->
            val plate = plateOf(r.getOrNull(vCol).orEmpty())
            (!dedicatedOnly || plate in dedicated) &&
                (q.isEmpty() || r.any { it.uppercase().filter { ch -> ch.isLetterOrDigit() }.contains(q) })
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            at == 0L -> getString(R.string.yard_empty_never)
            table.rows.isEmpty() -> getString(R.string.yard_empty_none)
            else -> getString(R.string.yard_empty_filter)
        }
        for (r in shown) list.addView(card(table, r, vCol, dedicated))
    }

    // ---------- overdue logic ----------
    private data class OverdueRow(val vehicle: String, val store: String, val reason: String, val detail: String, val severity: Int)

    private fun computeOverdue(dedicated: Set<String>): List<OverdueRow> {
        val from = YardStore.table(this, "From 1041")
        if (from.rows.isEmpty()) return emptyList()
        val standing = YardStore.table(this, "Standing")
        val coming = YardStore.table(this, "Coming to 1041")
        val now = System.currentTimeMillis()

        val sVeh = standing.col("vehicle number", "vehicle no", "vehicle")
        val sIn = standing.col("checked in", "checkedin")
        val sWait = standing.col("waiting")
        val standingMap = HashMap<String, Pair<Long, String>>()
        for (r in standing.rows) {
            val pl = plateOf(r.getOrNull(sVeh).orEmpty()); if (pl.isEmpty()) continue
            standingMap[pl] = DrishtiData.parseTime(r.getOrNull(sIn).orEmpty(), 0L) to r.getOrNull(sWait).orEmpty()
        }
        val cVeh = coming.col("vehicle number", "vehicle no", "vehicle")
        val returned = coming.rows.mapNotNull { plateOf(it.getOrNull(cVeh).orEmpty()).takeIf { it.isNotEmpty() } }.toHashSet()

        val fVeh = from.col("vehicle number", "vehicle no", "vehicle")
        val fDest = from.col("destination", "dest")
        val fOut = from.col("checked out", "checkedout")
        val fDrv = from.col("driver name", "driver")
        val out = ArrayList<OverdueRow>()
        for (r in from.rows) {
            val pl = plateOf(r.getOrNull(fVeh).orEmpty()); if (pl.isEmpty()) continue
            val raw = r.getOrNull(fVeh).orEmpty()
            val store = r.getOrNull(fDest).orEmpty().trim().ifEmpty { "?" }
            val dispatch = DrishtiData.parseTime(r.getOrNull(fOut).orEmpty(), 0L)
            if (dispatch <= 0) continue
            if (dispatch < StoreShift.windowStart(this, store, now)) continue
            if (dedicatedOnly && pl !in dedicated) continue
            val driver = r.getOrNull(fDrv).orEmpty().trim()
            val mob = mobileOf(from, r)
            val who = (if (driver.isNotEmpty()) driver else "") + (if (mob != null) " ($mob)" else "")
            val st = standingMap[pl]
            if (st != null) {
                val h = waitHours(st.second, st.first, now)
                if (h >= 3.0) out.add(OverdueRow(raw, store, getString(R.string.yard_no_checkout, fmtH(h)),
                    "Store " + store + (if (who.isNotBlank()) "\nDriver: " + who.trim() else "") +
                        (if (st.first > 0) "\nChecked in " + ReportBuilder.timeFmt().format(Date(st.first)) else ""), 1))
            } else if (pl !in returned) {
                val h = (now - dispatch) / 3600000.0
                if (h >= 5.0) out.add(OverdueRow(raw, store, getString(R.string.yard_no_checkin, fmtH(h)),
                    "Store " + store + (if (who.isNotBlank()) "\nDriver: " + who.trim() else "") +
                        "\nDispatched " + ReportBuilder.timeFmt().format(Date(dispatch)), 2))
            }
        }
        out.sortByDescending { it.severity }
        return out
    }

    /** Waiting time at store: parse "HH : MM" if present, else now - check-in. */
    private fun waitHours(waitText: String, checkIn: Long, now: Long): Double {
        val m = Regex("(\\d+)\\s*:\\s*(\\d+)").find(waitText)
        if (m != null) return m.groupValues[1].toInt() + m.groupValues[2].toInt() / 60.0
        if (checkIn > 0) return (now - checkIn) / 3600000.0
        return 0.0
    }

    private fun fmtH(h: Double): String {
        val total = (h * 60).toInt()
        return (total / 60).toString() + "h " + String.format("%02dm", total % 60)
    }

    private fun overdueCard(o: OverdueRow): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.gate_row_bg)
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(o.vehicle.ifBlank { "\u2013" }, 17f, R.color.ink, R.font.jakarta_bold),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val fg = if (o.severity >= 2) R.color.no else R.color.chk
        val bg = if (o.severity >= 2) R.color.no_bg else R.color.chk_bg
        head.addView(pillText(o.reason, fg, bg))
        card.addView(head)
        card.addView(tv(o.detail, 13f, R.color.muted, R.font.jakarta_medium).apply { setPadding(0, dp(5), 0, 0); setLineSpacing(dp(3).toFloat(), 1f) })
        val mob = Regex("\\((\\d{10})\\)").find(o.detail)?.groupValues?.get(1)
        card.setOnClickListener { if (mob != null) try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$mob"))) } catch (e: Exception) {} }
        return card.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) } }
    }

    /** Dialog: mark each destination store Day or Night (Night start 10 PM, Day start 6 AM). */
    private fun shiftDialog() {
        val from = YardStore.table(this, "From 1041")
        val fDest = from.col("destination", "dest")
        val stores = from.rows.mapNotNull { it.getOrNull(fDest)?.trim()?.takeIf { s -> s.isNotEmpty() && s != "-" } }.distinct().sorted()
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        if (stores.isEmpty()) box.addView(tv(getString(R.string.yard_shift_empty), 14f, R.color.muted, R.font.jakarta_medium))
        for (code in stores) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            r.addView(tv(code, 15f, R.color.ink, R.font.jakarta_bold), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val lbl = tv(if (StoreShift.isNight(this, code)) getString(R.string.yard_night) else getString(R.string.yard_day), 13f, R.color.muted, R.font.jakarta_bold)
            val sw = Switch(this).apply {
                isChecked = StoreShift.isNight(this@YardActivity, code)
                setOnCheckedChangeListener { _, night ->
                    StoreShift.setNight(this@YardActivity, code, night)
                    lbl.text = if (night) getString(R.string.yard_night) else getString(R.string.yard_day)
                }
            }
            r.addView(lbl); r.addView(sw)
            box.addView(r)
        }
        scroll.addView(box)
        AlertDialog.Builder(this)
            .setTitle(R.string.yard_shift_title)
            .setView(scroll)
            .setPositiveButton(R.string.yard_done) { d, _ -> d.dismiss(); refresh() }
            .show()
    }

    private fun plateOf(    private fun plateOf(cell: String): String {
        val first = cell.substringBefore("(").trim()
        return DrishtiData.plate(first) ?: first.uppercase().filter { it.isLetterOrDigit() }
    }

    private fun card(t: YardStore.Table, r: List<String>, vCol: Int, dedicated: Set<String>): View {
        val raw = r.getOrNull(vCol).orEmpty()
        val plate = plateOf(raw)
        val isDed = plate in dedicated

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.gate_row_bg)
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv(if (raw.isNotBlank()) raw else "–", 17f, R.color.ink, R.font.jakarta_bold),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (isDed) head.addView(tv(getString(R.string.yard_dedicated), 10.5f, R.color.emerald, R.font.jakarta_bold).apply {
            setBackgroundResource(R.drawable.pill_bg); backgroundTintList = ContextCompat.getColorStateList(this@YardActivity, R.color.brand_light)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        statusPill(t, r)?.let { head.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) }) }
        card.addView(head)

        val lines = StringBuilder()
        t.headers.forEachIndexed { i, h ->
            if (i == vCol) return@forEachIndexed
            val hl = h.lowercase()
            if (hl.contains("waiting") || hl.contains("elapsed") || hl.contains("hh")) return@forEachIndexed  // shown as pill
            val v = r.getOrNull(i).orEmpty().trim()
            if (v.isEmpty() || v == "-") return@forEachIndexed
            if (lines.isNotEmpty()) lines.append('\n')
            lines.append(h).append(": ").append(v)
        }
        card.addView(tv(lines.toString(), 13f, R.color.muted, R.font.jakarta_medium).apply {
            setPadding(0, dp(5), 0, 0); setLineSpacing(dp(3).toFloat(), 1f)
        })

        val mobile = mobileOf(t, r)
        card.setOnClickListener { if (mobile != null) try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$mobile"))) } catch (e: Exception) {} }
        card.setOnLongClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("vehicle", plate))
            Toast.makeText(this, getString(R.string.yard_copied, plate), Toast.LENGTH_SHORT).show(); true
        }
        return card.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
    }

    /** A coloured pill for waiting / elapsed time, or Departed when checked out. */
    private fun statusPill(t: YardStore.Table, r: List<String>): View? {
        val outCol = t.col("checked out", "checkedout")
        if (outCol >= 0) {
            val out = r.getOrNull(outCol).orEmpty().trim()
            if (out.isNotEmpty() && out != "-") return pillText(getString(R.string.yard_departed), R.color.gone, R.color.gone_bg)
        }
        val wCol = t.col("waiting", "elapsed", "hh:mm")
        if (wCol >= 0) {
            val w = r.getOrNull(wCol).orEmpty().trim().replace(" ", "")
            if (w.isNotEmpty() && w != "-") return pillText(w, R.color.wait, R.color.wait_bg)
        }
        return null
    }

    private fun pillText(text: String, fg: Int, bg: Int) = tv(text, 11.5f, fg, R.font.jakarta_bold).apply {
        setBackgroundResource(R.drawable.pill_bg); backgroundTintList = ContextCompat.getColorStateList(this@YardActivity, bg)
        setPadding(dp(9), dp(4), dp(9), dp(4))
    }

    private fun mobileOf(t: YardStore.Table, r: List<String>): String? {
        for (c in listOfNotNull(r.getOrNull(t.col("mobile")), r.getOrNull(t.col("driver")))) {
            Regex("\\d{10}").find(c.replace(" ", ""))?.let { return it.value.takeLast(10) }
        }
        return null
    }

    private fun pullNow(quiet: Boolean) {
        if (FirebaseLink.available(this)) {
            if (!quiet) updatedPill.text = getString(R.string.yard_checking_pill)
            FirebaseLink.pullOnce(this) { _, err ->
                if (isFinishing) return@pullOnce
                refresh()
                if (!quiet && err != null) Toast.makeText(this, getString(R.string.laptop_net_error, err), Toast.LENGTH_LONG).show()
            }
            return
        }
        if (!LaptopLink.linked(this)) { if (!quiet) Toast.makeText(this, R.string.yard_not_linked, Toast.LENGTH_LONG).show(); return }
        val app = applicationContext
        Thread {
            val err = try { LaptopLink.pullAndApply(app); null } catch (e: Throwable) { e.message ?: e.toString() }
            runOnUiThread { if (!isFinishing) { refresh(); if (!quiet && err != null) Toast.makeText(this, getString(R.string.laptop_net_error, err), Toast.LENGTH_LONG).show() } }
        }.start()
    }

    companion object { fun open(c: Context) = c.startActivity(Intent(c, YardActivity::class.java)) }
}
