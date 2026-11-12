package com.logistics.vehiclereport

import android.app.Dialog
import android.content.ClipboardManager
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * Store location master: each store's exact DMart pin, set once and used everywhere after (Plan map, Route in Maps,
 * km from DC). Set one store from a Google Maps link / lat, long, or many at once from an Excel
 * (Store Code + Latitude + Longitude, or Store Code + Google Maps link). Kept per account and in the cloud copy.
 */
object StoreLocations {
    private val main = Handler(Looper.getMainLooper())

    /** One row of an imported location sheet. */
    class Row(val code: String, val at: LatLon?, val link: String)

    /** Rows with a location in a workbook: a sheet with a Store Code column and Latitude + Longitude, or a map link column. */
    fun rowsFromWorkbook(bytes: ByteArray): List<Row> {
        val out = ArrayList<Row>()
        for (rows in VehicleListImporter.xlsxSheets(bytes)) {
            val hi = rows.take(10).indexOfFirst { r -> r.any { it.lowercase().replace(" ", "").let { h -> h == "storecode" || h == "sitecode" || h == "code" } } }
            if (hi < 0) continue
            out += rowsFrom(rows.drop(hi), rows[hi])
            if (out.isNotEmpty()) break
        }
        return out
    }

    /** Rows from a sheet whose first row is [header] (also used for the store list import). */
    fun rowsFrom(withHeader: List<List<String>>, header: List<String>): List<Row> {
        val h = header.map { it.lowercase().trim() }
        val cCode = h.indexOfFirst { it.replace(" ", "") in setOf("storecode", "sitecode", "code") }
        val cLat = h.indexOfFirst { it.startsWith("lat") }
        val cLon = h.indexOfFirst { it.startsWith("lon") || it.startsWith("lng") }
        val cLink = h.indexOfFirst { it.contains("link") || it.contains("map") || it.contains("url") || it.contains("lat, long") || it.contains("latlong") }
        if (cCode < 0 || (cLink < 0 && (cLat < 0 || cLon < 0))) return emptyList()
        return withHeader.drop(1).mapNotNull { r ->
            val code = r.getOrNull(cCode).orEmpty().trim().removeSuffix(".0")
            if (code.isBlank()) return@mapNotNull null
            val lat = if (cLat >= 0) r.getOrNull(cLat)?.trim()?.toDoubleOrNull() else null
            val lon = if (cLon >= 0) r.getOrNull(cLon)?.trim()?.toDoubleOrNull() else null
            val at = if (lat != null && lon != null && lat in 5.0..38.0 && lon in 66.0..99.0) LatLon(lat, lon) else null
            val link = if (cLink >= 0) r.getOrNull(cLink).orEmpty().trim() else ""
            if (at == null && link.isBlank()) null else Row(code, at, link)
        }
    }

    /** Pins the rows (map links read in the background); [done] gets how many were pinned and how many not. */
    fun applyRows(c: android.content.Context, rows: List<Row>, done: (Int, Int) -> Unit) {
        val app = c.applicationContext
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
            val kind = StoreGeo.kind(a, s.code)
            rows += listOf(s.code, s.name, s.city, s.region,
                at?.let { String.format(Locale.US, "%.6f", it.lat) }.orEmpty(),
                at?.let { String.format(Locale.US, "%.6f", it.lon) }.orEmpty(), "",
                when (kind) { StoreGeo.Kind.EXACT -> "Exact"; StoreGeo.Kind.AUTO -> "Approx (check)"; StoreGeo.Kind.NONE -> "Not set" })
        }
        Exporter.deliver(a, "Store_Locations.xlsx", rows, listOf(12, 30, 18, 14, 12, 12, 34, 14))
    }

    /** The master screen: every store, its pin, search, set one, import / download Excel. */
    fun open(a: MainActivity) {
        val d = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val dialog = Dialog(a, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.color(a, R.color.bg_light))
            setPadding(px(16), px(36), px(16), px(8))
        }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(a).apply {
            setText(R.string.loc_title); textSize = 20f; setTextColor(Ui.color(a, R.color.ink))
            typeface = ResourcesCompat.getFont(a, R.font.jakarta_bold)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(MaterialButton(a, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            setText(R.string.close); setOnClickListener { dialog.dismiss() }
        })
        root.addView(head)
        val summary = TextView(a).apply { textSize = 13f; setTextColor(Ui.color(a, R.color.muted)); setPadding(0, px(2), 0, px(8)) }
        root.addView(summary)
        val btns = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val importBtn = MaterialButton(a).apply { setText(R.string.loc_import); setIconResource(R.drawable.ic_upload) }
        val exportBtn = MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.loc_export); setIconResource(R.drawable.ic_download)
        }
        btns.addView(importBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(8) })
        btns.addView(exportBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btns)
        val searchBox = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = a.getString(R.string.loc_search) }
        val search = TextInputEditText(searchBox.context).apply { setSingleLine() }
        searchBox.addView(search)
        root.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6) })
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(a).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(6) })

        lateinit var fill: () -> Unit
        fill = {
            val stores = MasterData.get(a).stores
            val places = StoreGeo.places(a)
            val q = search.text?.toString()?.trim()?.lowercase().orEmpty()
            val kinds = stores.associate { it.code to StoreGeo.kind(a, it.code) }
            summary.text = if (stores.isEmpty()) a.getString(R.string.loc_no_stores)
                else a.getString(R.string.loc_summary, kinds.values.count { it == StoreGeo.Kind.EXACT }, stores.size,
                    kinds.values.count { it == StoreGeo.Kind.AUTO }, kinds.values.count { it == StoreGeo.Kind.NONE })
            list.removeAllViews()
            // Not exact first, so the ones still to fix are on top.
            val shown = stores.filter { q.isEmpty() || listOf(it.code, it.name, it.city, it.region).any { f -> f.lowercase().contains(q) } }
                .sortedBy { kinds[it.code]?.ordinal?.let { o -> if (o == 0) 1 else 0 } ?: 0 }
            for (s in shown.take(400)) {
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
                    text = Ui.joinNonBlank(s.city, s.region,
                        when (kind) {
                            StoreGeo.Kind.EXACT -> a.getString(R.string.loc_exact)
                            StoreGeo.Kind.AUTO -> a.getString(R.string.loc_auto)
                            StoreGeo.Kind.NONE -> a.getString(R.string.loc_none)
                        },
                        at?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) }.orEmpty())
                    textSize = 12.5f
                    setTextColor(Ui.color(a, when (kind) { StoreGeo.Kind.EXACT -> R.color.ok; StoreGeo.Kind.AUTO -> R.color.chk; else -> R.color.no }))
                })
                row.setOnClickListener { setOne(a, s) { fill() } }
                list.addView(row)
                list.addView(View(a).apply { setBackgroundColor(0x1F000000) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { fill() }
        })
        importBtn.setOnClickListener {
            MaterialAlertDialogBuilder(a).setTitle(R.string.loc_import).setMessage(R.string.loc_import_help)
                .setPositiveButton(R.string.loc_pick_file) { _, _ -> a.pickStoreLocationsFile { fill() } }
                .setNeutralButton(R.string.loc_export) { _, _ -> export(a) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        exportBtn.setOnClickListener { export(a) }
        dialog.setContentView(root)
        dialog.show()
        fill()
    }

    /** Set one store's exact pin: Find in Google Maps → Share → Copy link, then Save (the copied link fills in). */
    fun setOne(a: MainActivity, s: Store, done: () -> Unit) {
        val d = a.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }
        val name = Ui.joinNonBlank("DMart ${s.name.replace(Regex("(?i)\\bd[- _]?mart\\b"), " ").replace(Regex("\\s+"), " ").trim()}", s.city, sep = ", ")
        box.addView(MaterialButton(a, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.plan_find_in_maps); setIconResource(R.drawable.ic_pin)
            setOnClickListener {
                val near = StoreGeo.places(a)[s.code.trim()] ?: StoreGeo.dc(a)
                try {
                    val uri = if (near != null) Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%s", near.lat, near.lon, Uri.encode(name)))
                        else Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(name))
                    a.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) { }
            }
        })
        box.addView(TextView(a).apply {
            setText(R.string.plan_find_in_maps_help); textSize = 12f; setTextColor(Ui.color(a, R.color.muted)); setPadding(px(4), px(2), px(4), px(6))
        })
        val l = TextInputLayout(a, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = a.getString(R.string.loc_link_hint) }
        val e = TextInputEditText(l.context).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        l.addView(e)
        box.addView(l)
        // A Google Maps link copied just before: filled in, ready to save.
        fun paste() {
            try {
                val cm = a.getSystemService(ClipboardManager::class.java)
                val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString().orEmpty().trim()
                if (clip.isNotBlank() && clip.length < 600 && StoreGeo.isLink(clip)) e.setText(clip)
            } catch (x: Exception) { }
        }
        paste()
        val dlg = MaterialAlertDialogBuilder(a)
            .setTitle("${s.code} · ${s.name}")
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val t = e.text?.toString()?.trim().orEmpty()
                if (t.isBlank()) return@setPositiveButton
                applyRows(a, listOf(StoreLocations.Row(s.code, StoreGeo.coords(t), t))) { ok, _ ->
                    Toast.makeText(a, if (ok > 0) R.string.plan_place_pinned else R.string.plan_place_link_bad, Toast.LENGTH_LONG).show()
                    done()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .apply {
                if (StoreGeo.kind(a, s.code) != StoreGeo.Kind.NONE) setNeutralButton(R.string.loc_clear) { _, _ -> StoreGeo.clear(a, s.code); done() }
            }
            .create()
        // Coming back from Google Maps with a copied link: fill it in.
        dlg.setOnShowListener { e.post { paste() } }
        dlg.window?.decorView?.viewTreeObserver?.addOnWindowFocusChangeListener { has -> if (has && e.text.isNullOrBlank()) paste() }
        dlg.show()
    }
}
