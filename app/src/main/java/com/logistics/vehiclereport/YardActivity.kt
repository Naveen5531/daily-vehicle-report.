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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import java.util.Date

/**
 * Yard: Standing, In-transit and At-DC-now vehicles as the office Excel (App Data workbook) pulled them from Drishti.
 * Excel sends them with the gate log through the office laptop link; this screen only shows them.
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

    private lateinit var subtitle: TextView
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var toggle: MaterialButtonToggleGroup
    private val buttons = mutableListOf<MaterialButton>()

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
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences("yard", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onStop() {
        getSharedPreferences("yard", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(listener)
        super.onStop()
    }

    // ---------- layout ----------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun font(id: Int): Typeface? = try { ResourcesCompat.getFont(this, id) } catch (e: Exception) { null }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.bg_light))
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        // The outer frame takes the status / navigation bar insets, so the side padding above stays.
        val frame = android.widget.FrameLayout(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(color(R.color.bg_light))
        }

        // Top bar: back, title, pull now
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "←"
            textSize = 24f
            setTextColor(color(R.color.ink))
            setPadding(0, 0, dp(12), 0)
            setOnClickListener { finish() }
        })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = getString(R.string.yard_title)
            textSize = 24f
            typeface = font(R.font.bricolage_extrabold)
            setTextColor(color(R.color.ink))
        })
        subtitle = TextView(this).apply { textSize = 12.5f; setTextColor(color(R.color.muted)) }
        titles.addView(subtitle)
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(MaterialButton(this).apply {
            text = getString(R.string.yard_pull)
            setOnClickListener { pullNow(quiet = false) }
        })
        root.addView(top)

        // Tabs
        toggle = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        tabs.forEachIndexed { i, t ->
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id = View.generateViewId()
                text = t.label
                isAllCaps = false
                textSize = 13f
            }
            buttons.add(b)
            toggle.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        toggle.check(buttons[current].id)
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { current = buttons.indexOfFirst { it.id == id }.coerceAtLeast(0); refresh() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })

        // Search + dedicated only
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(EditText(this).apply {
            hint = getString(R.string.yard_search)
            isSingleLine = true
            textSize = 14f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty().trim(); refresh() }
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(CheckBox(this).apply {
            text = getString(R.string.yard_dedicated_only)
            textSize = 13f
            setOnCheckedChangeListener { _, on -> dedicatedOnly = on; refresh() }
        })
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) })

        // List
        val scroll = ScrollView(this)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(24)) }
        empty = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.muted))
            setPadding(dp(4), dp(24), dp(4), dp(24))
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(empty)
        inner.addView(list)
        scroll.addView(inner)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return frame
    }

    // ---------- data ----------

    private fun refresh() {
        val at = YardStore.lastAt(this)
        subtitle.text = if (at > 0) getString(R.string.yard_updated, ReportBuilder.timeFmt().format(Date(at)))
                        else getString(R.string.yard_never)

        val dedicated = try {
            VehicleStore.getVehicles(this).filter { it.dedicated }.map { it.number }.toSet()
        } catch (e: Exception) { emptySet() }

        tabs.forEachIndexed { i, t ->
            buttons[i].text = getString(R.string.yard_tab_count, t.label, YardStore.table(this, t.sheet).rows.size)
        }

        val table = YardStore.table(this, tabs[current].sheet)
        val vCol = table.col("vehicle number", "vehicle no", "vehicle")
        val q = query.uppercase().filter { it.isLetterOrDigit() }
        val shown = table.rows.filter { r ->
            val plate = plateOf(r.getOrNull(vCol).orEmpty())
            (!dedicatedOnly || plate in dedicated) &&
                (q.isEmpty() || r.any { cell -> cell.uppercase().filter { it.isLetterOrDigit() }.contains(q) })
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
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(color(R.color.surface))
            useCompatPadding = false
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = if (raw.isNotBlank()) raw else "–"
            textSize = 17f
            typeface = font(R.font.jakarta_bold)
            setTextColor(color(R.color.ink))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (isDed) head.addView(TextView(this).apply {
            text = getString(R.string.yard_dedicated)
            textSize = 11.5f
            typeface = font(R.font.jakarta_bold)
            setTextColor(color(R.color.emerald))
            setBackgroundColor(color(R.color.brand_light))
            setPadding(dp(8), dp(3), dp(8), dp(3))
        })
        box.addView(head)

        val lines = StringBuilder()
        t.headers.forEachIndexed { i, h ->
            if (i == vCol) return@forEachIndexed
            val v = r.getOrNull(i).orEmpty().trim()
            if (v.isEmpty() || v == "-") return@forEachIndexed
            if (lines.isNotEmpty()) lines.append('\n')
            lines.append(h).append(": ").append(v)
        }
        box.addView(TextView(this).apply {
            text = lines.toString()
            textSize = 13f
            setTextColor(color(R.color.muted))
            setPadding(0, dp(4), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        card.addView(box)

        val mobile = mobileOf(t, r)
        card.setOnClickListener {
            if (mobile != null) {
                try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$mobile"))) } catch (e: Exception) { }
            }
        }
        card.setOnLongClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("vehicle", plate))
            Toast.makeText(this, getString(R.string.yard_copied, plate), Toast.LENGTH_SHORT).show()
            true
        }
        return card.also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
    }

    /** Driver mobile from a "Driver Mobile" column or "NAME (9876543210)". */
    private fun mobileOf(t: YardStore.Table, r: List<String>): String? {
        val m = t.col("mobile")
        val candidates = listOfNotNull(r.getOrNull(m), r.getOrNull(t.col("driver")))
        for (c in candidates) {
            val d = Regex("\\d{10}").find(c.replace(" ", ""))
            if (d != null) return d.value.takeLast(10)
        }
        return null
    }

    private fun pullNow(quiet: Boolean) {
        if (!LaptopLink.linked(this)) {
            if (!quiet) Toast.makeText(this, R.string.yard_not_linked, Toast.LENGTH_LONG).show()
            return
        }
        val app = applicationContext
        if (!quiet) subtitle.text = getString(R.string.laptop_checking)
        Thread {
            val err = try { LaptopLink.pullAndApply(app); null } catch (e: Throwable) { e.message ?: e.toString() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                refresh()
                if (!quiet && err != null) Toast.makeText(this, getString(R.string.laptop_net_error, err), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    companion object {
        fun open(c: Context) = c.startActivity(Intent(c, YardActivity::class.java))
    }
}
