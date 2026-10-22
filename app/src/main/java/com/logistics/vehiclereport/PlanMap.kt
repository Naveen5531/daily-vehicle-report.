package com.logistics.vehiclereport

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.util.Locale

/**
 * The Plan tab's map: a pin for the DC and a numbered pin for every planned store, coloured by vehicle, with a line
 * DC → stops for vehicles carrying more than one store. Uses OpenStreetMap tiles (no key, no sign-in); the store places
 * are the ones already found for nearby / on-the-way clubbing. Tap a pin for the store and its vehicle.
 */
object PlanMap {
    /** One colour per vehicle, cycled. */
    private val COLORS = intArrayOf(
        0xFF0B6B44.toInt(), 0xFF2950A8.toInt(), 0xFFB4471A.toInt(), 0xFF7A3FB0.toInt(), 0xFF8A5A00.toInt(),
        0xFF0E7C86.toInt(), 0xFFB3245C.toInt(), 0xFF4E6B1F.toInt(), 0xFF5A4636.toInt(), 0xFF1F5F9E.toInt()
    )
    private const val DC_COLOR = 0xFF0B3D2E.toInt()

    fun colorFor(vehicleIndex: Int) = COLORS[vehicleIndex % COLORS.size]

    private var configured = false

    /** Map settings once: tiles cached in the app's own cache folder (no storage permission needed). */
    fun setup(c: Context) {
        if (configured) return
        val conf = Configuration.getInstance()
        conf.userAgentValue = c.packageName
        val base = File(c.cacheDir, "osmdroid")
        conf.osmdroidBasePath = base
        conf.osmdroidTileCache = File(base, "tiles")
        configured = true
    }

    /** A map view ready for pins; [inScroll] keeps a finger on the map from scrolling the page. */
    @SuppressLint("ClickableViewAccessibility")
    fun newMap(c: Context, inScroll: Boolean): MapView {
        setup(c)
        return MapView(c).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 18.0
            controller.setZoom(6.0)
            controller.setCenter(GeoPoint(17.4, 78.4))
            if (inScroll) setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
    }

    /** Puts the DC and the plan's stores on [map] and fits the view to them. Returns how many stores got a pin. */
    /** [numbers]: each load's vehicle number on the plan (when only some vehicles are shown); else 1, 2, 3 … */
    fun show(map: MapView, r: PlanResult?, places: Map<String, LatLon>, dc: LatLon?, labels: Labels, numbers: List<Int>? = null): Int {
        map.overlays.clear()
        val c = map.context
        val points = ArrayList<GeoPoint>()
        var pinned = 0
        val dcPoint = dc?.let { GeoPoint(it.lat, it.lon) }
        r?.loads?.forEachIndexed { at0, l ->
            val i = (numbers?.getOrNull(at0) ?: (at0 + 1)) - 1
            val color = colorFor(i)
            val stops = l.codes.mapNotNull { code -> places[code]?.let { code to GeoPoint(it.lat, it.lon) } }
            if (stops.size > 1 && dcPoint != null) {
                map.overlays.add(Polyline(map).apply {
                    setPoints(listOf(dcPoint) + stops.map { it.second })
                    outlinePaint.color = color
                    outlinePaint.strokeWidth = 3.5f * c.resources.displayMetrics.density
                    outlinePaint.alpha = 190
                    infoWindow = null
                })
            }
            for ((code, at) in stops) {
                val s = l.parts.first { it.first.code == code }.first
                val part = l.parts.filter { it.first.code == code }.sumOf { it.second }
                map.overlays.add(Marker(map).apply {
                    position = at
                    icon = pin(c, (i + 1).toString(), color)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "${s.code} · ${s.shownName}"
                    snippet = labels.vehicle(i + 1, l.type?.name, part)
                    subDescription = dc?.let { labels.km(VehiclePlanner.km(it, LatLon(at.latitude, at.longitude))) }
                })
                points += at
                pinned++
            }
        }
        if (dcPoint != null) {
            map.overlays.add(Marker(map).apply {
                position = dcPoint
                icon = pin(c, "DC", DC_COLOR)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = labels.dcName
            })
            points += dcPoint
        }
        fit(map, points)
        map.invalidate()
        return pinned
    }

    private fun fit(map: MapView, points: List<GeoPoint>) {
        when {
            points.isEmpty() -> return
            points.size == 1 -> { map.controller.setZoom(13.0); map.controller.setCenter(points[0]) }
            else -> {
                val box = BoundingBox.fromGeoPoints(points)
                val padded = box.increaseByScale(1.25f)
                // Before the map is laid out it has no size to fit into; fit once it has.
                if (map.width > 0 && map.height > 0) map.zoomToBoundingBox(padded, false, (24 * map.context.resources.displayMetrics.density).toInt())
                else map.addOnFirstLayoutListener { _, _, _, _, _ ->
                    map.zoomToBoundingBox(padded, false, (24 * map.context.resources.displayMetrics.density).toInt())
                }
            }
        }
    }

    /** A teardrop pin with a number (or "DC") on it. */
    private fun pin(c: Context, text: String, color: Int): BitmapDrawable {
        val d = c.resources.displayMetrics.density
        val w = (30 * d).toInt()
        val h = (40 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val r = w / 2f - 1.5f * d
        val cx = w / 2f
        val cy = r + 1.5f * d
        val body = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            moveTo(cx - r * 0.62f, cy + r * 0.78f)
            lineTo(cx, h - 1f * d)
            lineTo(cx + r * 0.62f, cy + r * 0.78f)
            close()
        }
        cv.drawPath(body, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * d; this.color = 0xFFFFFFFF.toInt()
        })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = (if (text.length >= 3) 9.5f else if (text.length == 2) 11.5f else 13f) * d
        }
        cv.drawText(text, cx, cy - (tp.descent() + tp.ascent()) / 2, tp)
        return BitmapDrawable(c.resources, bmp)
    }

    /** The texts shown on a pin, from the app's strings. */
    class Labels(val dcName: String, val vehicle: (Int, String?, Double) -> String, val km: (Double) -> String)

    /** A bigger map over the whole screen (back or the close button returns to the Plan tab). */
    fun openFull(c: Context, r: PlanResult?, places: Map<String, LatLon>, dc: LatLon?, labels: Labels, numbers: List<Int>? = null) {
        val dialog = Dialog(c, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
        val frame = FrameLayout(c)
        val map = newMap(c, inScroll = false)
        frame.addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val d = c.resources.displayMetrics.density
        frame.addView(FloatingActionButton(c).apply {
            setImageResource(R.drawable.ic_close)
            size = FloatingActionButton.SIZE_MINI
            contentDescription = c.getString(R.string.plan_map_close)
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            setMargins(0, (36 * d).toInt(), (16 * d).toInt(), 0)
        })
        dialog.setContentView(frame)
        dialog.setOnDismissListener { map.onDetach() }
        show(map, r, places, dc, labels, numbers)
        dialog.show()
        map.onResume()
    }

    /** Opens the phone's map app (Google Maps) at a store's pin; falls back to the browser. */
    fun openInMaps(c: Context, at: LatLon, label: String) {
        val ll = String.format(Locale.US, "%.6f,%.6f", at.lat, at.lon)
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll(${Uri.encode(label)})"))
        try {
            c.startActivity(geo)
        } catch (e: ActivityNotFoundException) {
            try {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$ll")))
            } catch (e2: Exception) { }
        }
    }

    fun visible(v: View, on: Boolean) { v.visibility = if (on) View.VISIBLE else View.GONE }
}
