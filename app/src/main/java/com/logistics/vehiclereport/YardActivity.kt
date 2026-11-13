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
import android.widget.Toast
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
        Tab("Drishti Standing", "At DC now")
    )
    private var current = 0
    private var query = ""
    private var dedicatedOnly = false

    private lateinit var updatedPill: TextView
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var dedChip: TextView
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
        for (i in tabs.indices) tileCount[i]?.text = YardStore.table(this, tabs[i].sheet).rows.size.toString()

        val table = YardStore.table(this, tabs[current].sheet)
        val vCol = table.col("vehicle number", "vehicle no", "vehicle")
        val q = query.uppercase().filter { it.isLetterOrDigit() }
        val shown = table.rows.filter { r ->
            val plate = plateOf(r.getOrNull(vCol).orEmpty())
            (!dedicatedOnly || plate in dedicated) &&
                (q.isEmpty() || r.any { it.uppercase().filter { ch -> ch.isLetterOrDigit() }.contains(q) })
        }

        list.removeAllViews()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            at == 0L -> getString(R.string.yard_empty_never)
            table.rows.isEmpty() -> getString(R.string.yard_empty_none)
            else -> getString(R.string.yard_empty_filter)
        }
        for (r in shown) list.addView(card(table, r, vCol, dedicated))
    }

    private fun plateOf(cell: String): String {
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
