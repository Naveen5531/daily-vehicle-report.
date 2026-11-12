package com.logistics.vehiclereport

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * Store master locations: each store's exact DMart pin, set once and used everywhere after (Plan map, Route in Maps,
 * km from DC). One by one from a Google Maps link / lat, long, or in bulk from an Excel
 * (Store Code + Latitude + Longitude, or Store Code + Google Maps link). Kept per account and in the cloud copy.
 */
object StoreLocations {
    private val main = Handler(Looper.getMainLooper())

    /** One row of an imported sheet: the store and its location. */
    class Row(val code: String, val at: LatLon?, val link: String, val name: String = "", val city: String = "", val region: String = "")

    /** Rows in a workbook: a sheet with a Store Code column and Latitude + Longitude, or a map link column. */
    fun rowsFromWorkbook(bytes: ByteArray): List<Row> {
        for (rows in VehicleListImporter.xlsxSheets(bytes)) {
            val hi = rows.take(10).indexOfFirst { r -> r.any { it.lowercase().replace(" ", "").let { h -> h == "storecode" || h == "sitecode" || h == "code" } } }
            if (hi < 0) continue
            val out = rowsFrom(rows.drop(hi), rows[hi])
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    private fun rowsFrom(withHeader: List<List<String>>, header: List<String>): List<Row> {
        val h = header.map { it.lowercase().trim() }
        val cCode = h.indexOfFirst { it.replace(" ", "") in setOf("storecode", "sitecode", "code") }
        val cLat = h.indexOfFirst { it.startsWith("lat") && !it.contains("long") }
        val cLon = h.indexOfFirst { (it.startsWith("lon") || it.startsWith("lng")) }
        val cLink = h.indexOfFirst { it.contains("link") || it.contains("map") || it.contains("url") || it.contains("lat, long") || it.contains("latlong") || it == "location" }
        val cName = h.indexOfFirst { it.contains("name") }
        val cCity = h.indexOfFirst { it.contains("city") }
        val cRegion = h.indexOfFirst { it.contains("region") || it.contains("zone") }
        if (cCode < 0 || (cLink < 0 && (cLat < 0 || cLon < 0))) return emptyList()
        fun cell(r: List<String>, c: Int) = if (c >= 0) r.getOrNull(c).orEmpty().trim() else ""
        return withHeader.drop(1).mapNotNull { r ->
            val code = cell(r, cCode).removeSuffix(".0")
            if (code.isBlank()) return@mapNotNull null
            val lat = cell(r, cLat).toDoubleOrNull()
            val lon = cell(r, cLon).toDoubleOrNull()
            val at = if (lat != null && lon != null && lat in 5.0..38.0 && lon in 66.0..99.0) LatLon(lat, lon) else null
            val link = cell(r, cLink)
            if (at == null && link.isBlank()) null else Row(code, at, link, cell(r, cName), cell(r, cCity), cell(r, cRegion))
        }
    }

    /** Adds stores missing from the store master (code + name); a store already there keeps its details. */
    fun addToMaster(c: Context, stores: List<Store>) {
        val m = MasterData.get(c)
        val have = m.stores.map { it.code.trim() }.toSet()
        val add = stores.filter { it.code.isNotBlank() && it.code.trim() !in have }.distinctBy { it.code.trim() }
        if (add.isNotEmpty()) MasterData.save(c, Masters(m.stores + add, m.vendors))
    }

    /** Adds or changes one store in the store master. */
    fun putStore(c: Context, s: Store) {
        val m = MasterData.get(c)
        val list = m.stores.toMutableList()
        val i = list.indexOfFirst { it.code.trim() == s.code.trim() }
        if (i >= 0) list[i] = s else list += s
        MasterData.save(c, Masters(list, m.vendors))
    }

    fun removeStore(c: Context, code: String) {
        val m = MasterData.get(c)
        MasterData.save(c, Masters(m.stores.filter { it.code.trim() != code.trim() }, m.vendors))
        StoreGeo.clear(c, code)
    }

    /** Pins the rows (map links read in the background); [done] gets how many were pinned and how many not. */
    fun applyRows(c: Context, rows: List<Row>, done: (Int, Int) -> Unit) {
        val app = c.applicationContext
        addToMaster(app, rows.filter { it.name.isNotBlank() }.map { Store(it.code, it.name, it.city, it.region) })
        Thread {
            var ok = 0; var bad = 0
            for (r in rows) {
                val at = r.at ?: (if (r.link.isNotBlank()) StoreGeo.resolveLink(r.link) else null)
                if (at != null) { StoreGeo.setExact(app, r.code, at); ok++ } else bad++
            }
            main.post { done(ok, bad) }
        }.start()
    }

    /** Excel of every store with its location now, to fill in once and import back. */
    fun export(a: MainActivity) {
        val places = StoreGeo.places(a)
        val rows = ArrayList<List<String>>()
        rows += listOf("Store Code", "Store Name", "Actual City", "Region", "Latitude", "Longitude", "Google Maps Link", "Pin")
        for (s in MasterData.get(a).stores) {
            val at = places[s.code.trim()]
            rows += listOf(s.code, s.name, s.city, s.region,
                at?.let { String.format(Locale.US, "%.6f", it.lat) }.orEmpty(),
                at?.let { String.format(Locale.US, "%.6f", it.lon) }.orEmpty(), "",
                when (StoreGeo.kind(a, s.code)) { StoreGeo.Kind.EXACT -> "Exact"; StoreGeo.Kind.AUTO -> "Approx (check)"; StoreGeo.Kind.NONE -> "Not set" })
        }
        if (rows.size < 2) rows += listOf("5043", "Gajuwaka", "Visakhapatnam", "Vizag", "17.690000", "83.210000", "", "Example – delete this row")
        Exporter.deliver(a, "Store_Master_Locations.xlsx", rows, listOf(12, 30, 18, 14, 12, 12, 34, 14))
    }

    private fun mapsName(s: Store) =
        Ui.joinNonBlank("DMart " + s.name.replace(Regex("(?i)\\bd[- _]?mart\\b"), " ").replace(Regex("\\s+"), " ").trim(), s.city, sep = ", ")

    /**
     * Add or edit a store: code, name, city, region and its location (Google Maps link or lat, long; "Find in Google
     * Maps" → Share → Copy link fills it in). [askOnly] shows only the location (code and name already captured).
     * [done] gets true when saved; [skip] (one-by-one walk) adds a Skip button.
     */
    fun editStore(a: MainActivity, s: Store?, askOnly: Boolean = false, title: String? = null, skip: (() -> Unit)? = null, done: (Boolean) -> Unit) {
        val d = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }
        fun field(hint: Int, value: String, type: Int): TextInputEditText {
            val l = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { this.hint = a.getString(hint) }
            val e = TextInputEditText(l.context).apply { setText(value); inputType = type; setSingleLine() }
            l.addView(e)
            box.addView(l, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
            return e
        }
        val words = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        val code = if (askOnly) null else field(R.string.sm_code, s?.code.orEmpty(), InputType.TYPE_CLASS_TEXT).apply { isEnabled = s == null }
        val name = if (askOnly) null else field(R.string.sm_name, s?.name.orEmpty(), words)
        val city = if (askOnly) null else field(R.string.sm_city, s?.city.orEmpty(), words)
        val region = if (askOnly) null else field(R.string.sm_region, s?.region.orEmpty(), words)
        val now = s?.let { StoreGeo.places(a)[it.code.trim()] }
        val kind = s?.let { StoreGeo.kind(a, it.code) } ?: StoreGeo.Kind.NONE
        box.addView(TextView(a).apply {
            text = a.getString(when (kind) { StoreGeo.Kind.EXACT -> R.string.sm_loc_exact; StoreGeo.Kind.AUTO -> R.string.sm_loc_auto; else -> R.string.sm_loc_none }) +
                (now?.let { String.format(Locale.US, "  (%.5f, %.5f)", it.lat, it.lon) } ?: "")
            textSize = 12.5f; setPadding(px(4), px(12), px(4), px(2))
            setTextColor(Ui.color(a, when (kind) { StoreGeo.Kind.EXACT -> R.color.ok; StoreGeo.Kind.AUTO -> R.color.chk; else -> R.color.no }))
        })
        box.addView(MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.plan_find_in_maps); setIconResource(R.drawable.ic_pin)
            setOnClickListener {
                val st = Store(code?.text?.toString()?.trim() ?: s?.code.orEmpty(), name?.text?.toString()?.trim() ?: s?.name.orEmpty(),
                    city?.text?.toString()?.trim() ?: s?.city.orEmpty(), "")
                val q = mapsName(st)
                val near = now ?: StoreGeo.dc(a)
                try {
                    val uri = if (near != null) Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%s", near.lat, near.lon, Uri.encode(q)))
                        else Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(q))
                    a.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) { }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
        box.addView(TextView(a).apply {
            setText(R.string.plan_find_in_maps_help); textSize = 12f; setTextColor(Ui.color(a, R.color.muted)); setPadding(px(4), px(2), px(4), 0)
        })
        val loc = field(R.string.sm_location, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        fun paste() {
            try {
                val cm = a.getSystemService(ClipboardManager::class.java)
                val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString().orEmpty().trim()
                if (clip.isNotBlank() && clip.length < 600 && StoreGeo.isLink(clip) && loc.text.isNullOrBlank()) loc.setText(clip)
            } catch (x: Exception) { }
        }
        val b = MaterialAlertDialogBuilder(a)
            .setTitle(title ?: s?.let { "${it.code} · ${it.name}" } ?: a.getString(R.string.sm_add))
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> done(false) }
        if (skip != null) b.setNeutralButton(R.string.sm_skip) { _, _ -> skip() }
        else if (s != null && !askOnly) b.setNeutralButton(R.string.sm_delete) { _, _ ->
            MaterialAlertDialogBuilder(a).setMessage(a.getString(R.string.sm_delete_ask, s.code, s.name))
                .setPositiveButton(R.string.sm_delete) { _, _ -> removeStore(a, s.code); done(true) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        val dlg = b.create()
        dlg.setOnShowListener {
            paste()
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val c = (code?.text?.toString()?.trim() ?: s?.code.orEmpty()).removeSuffix(".0")
                val n = name?.text?.toString()?.trim() ?: s?.name.orEmpty()
                if (c.isBlank() || n.isBlank()) { Toast.makeText(a, R.string.sm_need_code_name, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                if (s == null && MasterData.get(a).stores.any { it.code.trim() == c }) {
                    Toast.makeText(a, a.getString(R.string.sm_code_exists, c), Toast.LENGTH_LONG).show(); return@setOnClickListener
                }
                val t = loc.text?.toString()?.trim().orEmpty()
                if (t.isNotBlank() && !StoreGeo.isLink(t)) { Toast.makeText(a, R.string.sm_bad_location, Toast.LENGTH_LONG).show(); return@setOnClickListener }
                if (s == null && t.isBlank() && kind == StoreGeo.Kind.NONE) { Toast.makeText(a, R.string.sm_need_location, Toast.LENGTH_LONG).show(); return@setOnClickListener }
                if (!askOnly) putStore(a, Store(c, n, city?.text?.toString()?.trim().orEmpty(), region?.text?.toString()?.trim().orEmpty()))
                else if (s != null) addToMaster(a, listOf(s))
                dlg.dismiss()
                if (t.isBlank()) { done(true); return@setOnClickListener }
                applyRows(a, listOf(Row(c, StoreGeo.coords(t), t))) { ok, _ ->
                    Toast.makeText(a, if (ok > 0) R.string.plan_place_pinned else R.string.plan_place_link_bad, Toast.LENGTH_LONG).show()
                    done(ok > 0)
                }
            }
        }
        // Coming back from Google Maps with a copied link: fill it in.
        dlg.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { has -> if (has) paste() }
        dlg.show()
    }

    /** Asks the location of each store one by one (Save → next, Skip → next); [finished] when the list is done. */
    fun askOneByOne(a: MainActivity, stores: List<Store>, finished: () -> Unit) {
        fun step(i: Int) {
            if (i >= stores.size) { finished(); return }
            val s = stores[i]
            editStore(a, s, askOnly = true, title = a.getString(R.string.sm_walk_title, i + 1, stores.size, s.code, s.name),
                skip = { step(i + 1) }) { saved -> if (saved) step(i + 1) else finished() }
        }
        step(0)
    }
}

/** Store Master tab: every store with its exact location; add one by one, or bulk by Excel. */
class StoresTab(private val a: MainActivity, root: FrameLayout) {
    private val d = a.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()
    private val summary = TextView(a)
    private val search = TextInputEditText(a)
    private val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var filter = 0   // 0 all, 1 to fix (not exact)

    init {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(10), px(16), 0) }
        summary.apply { textSize = 13f; setTextColor(Ui.color(a, R.color.muted)); setPadding(px(2), 0, px(2), px(8)) }
        col.addView(summary)
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(MaterialButton(a).apply {
            setText(R.string.sm_bulk); setIconResource(R.drawable.ic_upload)
            setOnClickListener {
                MaterialAlertDialogBuilder(a).setTitle(R.string.sm_bulk).setMessage(R.string.loc_import_help)
                    .setPositiveButton(R.string.loc_pick_file) { _, _ -> a.pickStoreLocationsFile { refresh() } }
                    .setNeutralButton(R.string.loc_export) { _, _ -> StoreLocations.export(a) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(8) })
        btns.addView(MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.loc_export); setIconResource(R.drawable.ic_download)
            setOnClickListener { StoreLocations.export(a) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(btns)
        col.addView(MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.sm_fix_all); setIconResource(R.drawable.ic_pin)
            setOnClickListener {
                val todo = MasterData.get(a).stores.filter { StoreGeo.kind(a, it.code) != StoreGeo.Kind.EXACT }
                if (todo.isEmpty()) Toast.makeText(a, R.string.sm_all_done, Toast.LENGTH_SHORT).show()
                else StoreLocations.askOneByOne(a, todo) { refresh() }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        val sl = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = a.getString(R.string.loc_search) }
        sl.addView(search.apply { setSingleLine() })
        col.addView(sl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
        val fChips = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val chipAll = com.google.android.material.chip.Chip(a).apply { setText(R.string.sm_filter_all); isCheckable = true; isChecked = true }
        val chipFix = com.google.android.material.chip.Chip(a).apply { setText(R.string.sm_filter_fix); isCheckable = true }
        chipAll.setOnClickListener { filter = 0; chipAll.isChecked = true; chipFix.isChecked = false; refresh() }
        chipFix.setOnClickListener { filter = 1; chipFix.isChecked = true; chipAll.isChecked = false; refresh() }
        fChips.addView(chipAll); fChips.addView(chipFix, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) })
        col.addView(fChips)
        col.addView(ScrollView(a).apply {
            addView(list)
            clipToPadding = false
            setPadding(0, 0, 0, px(90))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        root.addView(ExtendedFloatingActionButton(a).apply {
            setText(R.string.sm_add); setIconResource(R.drawable.ic_add)
            setOnClickListener { StoreLocations.editStore(a, null) { refresh() } }
        }, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, px(16), px(16)) })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        })
    }

    fun refresh() {
        val stores = MasterData.get(a).stores
        val places = StoreGeo.places(a)
        val kinds = stores.associate { it.code to StoreGeo.kind(a, it.code) }
        summary.text = if (stores.isEmpty()) a.getString(R.string.sm_empty)
            else a.getString(R.string.loc_summary, kinds.values.count { it == StoreGeo.Kind.EXACT }, stores.size,
                kinds.values.count { it == StoreGeo.Kind.AUTO }, kinds.values.count { it == StoreGeo.Kind.NONE })
        val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
        list.removeAllViews()
        val shown = stores.filter { s ->
            (filter == 0 || kinds[s.code] != StoreGeo.Kind.EXACT) &&
                (q.isEmpty() || listOf(s.code, s.name, s.city, s.region).any { it.lowercase().contains(q) })
        }
        for (s in shown.take(500)) {
            val kind = kinds[s.code] ?: StoreGeo.Kind.NONE
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(4), px(10), px(4), px(10))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            row.addView(TextView(a).apply {
                text = "${s.code} · ${s.name}"; textSize = 15f; setTextColor(Ui.color(a, R.color.ink))
                typeface = ResourcesCompat.getFont(a, R.font.jakarta_semibold)
            })
            val at = places[s.code.trim()]
            row.addView(TextView(a).apply {
                text = Ui.joinNonBlank(s.city, s.region, a.getString(when (kind) {
                    StoreGeo.Kind.EXACT -> R.string.loc_exact; StoreGeo.Kind.AUTO -> R.string.loc_auto; else -> R.string.loc_none
                }), at?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) }.orEmpty())
                textSize = 12.5f
                setTextColor(Ui.color(a, when (kind) { StoreGeo.Kind.EXACT -> R.color.ok; StoreGeo.Kind.AUTO -> R.color.chk; else -> R.color.no }))
            })
            row.setOnClickListener { StoreLocations.editStore(a, s) { refresh() } }
            list.addView(row)
            list.addView(View(a).apply { setBackgroundColor(Ui.color(a, R.color.line)) }, LinearLayout.LayoutParams(-1, 1))
        }
        if (shown.isEmpty()) list.addView(TextView(a).apply {
            setText(if (stores.isEmpty()) R.string.sm_empty else R.string.sm_none_here); textSize = 13.5f
            setTextColor(Ui.color(a, R.color.muted)); setPadding(px(4), px(20), px(4), px(20)); gravity = Gravity.CENTER
        })
    }
}
