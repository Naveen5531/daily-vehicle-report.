package com.logistics.vehiclereport

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Opening animation (about 5.5 s): the truck drives slowly to the DC, the dock door
 * rolls up and the DC team walks out to meet it. Drawn frame by frame from the clock, so it plays
 * even when the phone's "remove animations" setting is on.
 */
@SuppressLint("ViewConstructor")
class IntroView(context: Context, private val onDone: () -> Unit) : View(context) {

    companion object {
        private const val DRIVE_END = 3.0f
        private const val DOOR_START = 2.9f
        private const val DOOR_END = 3.4f
        private const val TEAM_START = 3.4f
        private const val WALK = 1.0f
        private const val FADE_START = 5.2f
        private const val END = 5.6f
        private const val BRAND = 0xFF0B3D2E.toInt()   // emerald, same as the app
    }

    private val start = SystemClock.uptimeMillis()
    private var finished = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(230, 255, 255, 255); strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = try { androidx.core.content.res.ResourcesCompat.getFont(context, R.font.bricolage_extrabold) } catch (e: Exception) { null }
            ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        setBackgroundColor(BRAND)
        isClickable = true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) finish()
        return true
    }

    private fun finish() {
        if (finished) return
        finished = true
        (parent as? ViewGroup)?.removeView(this)
        onDone()
    }

    private fun clamp(x: Float) = x.coerceIn(0f, 1f)
    private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
    private fun easeInOut(x: Float) = if (x < .5f) 2 * x * x else 1 - (-2 * x + 2) * (-2 * x + 2) / 2

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        if (t >= END) { post { finish() }; return }
        alpha = if (t > FADE_START) 1f - clamp((t - FADE_START) / (END - FADE_START)) else 1f

        // Scene is 320 x 114 units, centred, as wide as the screen allows.
        val s = min(width * 0.92f, 440f * resources.displayMetrics.density) / 320f
        val ox = (width - 320f * s) / 2f
        val oy = height / 2f - 70f * s
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)

        // Truck starts at the left corner of the screen (its back at the edge) and drives to the dock.
        val startX = -ox / s + 60f - 2f
        val drive = easeOut(clamp(t / DRIVE_END))
        val truckX = startX + (104f - startX) * drive
        drawRoad(canvas, drive, ox / s)
        drawDc(canvas, easeInOut(clamp((t - DOOR_START) / (DOOR_END - DOOR_START))))
        drawTruck(canvas, truckX, drive, t, 104f - startX)
        for (i in 0 until 3) drawPerson(canvas, i, t)
        canvas.restore()

        // Captions
        val c1 = clamp((t - 3.1f) / .4f)
        if (c1 > 0) {
            text.textSize = 18f * resources.displayMetrics.scaledDensity
            text.alpha = (255 * c1).toInt()
            canvas.drawText("✓  Arrived at " + Masters.dispatchName.ifBlank { "the DC" }, width / 2f, oy + 140f * s, text)
        }
        val c2 = clamp((t - 4.2f) / .4f)
        if (c2 > 0) {
            text.textSize = 14f * resources.displayMetrics.scaledDensity
            text.alpha = (215 * c2).toInt()
            canvas.drawText("DC team is out to receive the vehicle", width / 2f, oy + 140f * s + 26f * resources.displayMetrics.density, text)
        }
        postInvalidateOnAnimation()
    }

    private fun fill(c: Int) { p.style = Paint.Style.FILL; p.color = c }

    private fun drawRoad(c: Canvas, drive: Float, margin: Float) {
        val l = -margin - 10f; val rgt = 330f + margin
        fill(0xFF082E22.toInt()); c.drawRect(l, 94f, rgt, 114f, p)
        fill(Color.argb(180, 255, 255, 255)); c.drawRect(l, 94f, rgt, 96f, p)
        dash.pathEffect = DashPathEffect(floatArrayOf(22f, 18f), 280f * drive)
        c.drawLine(l, 106f, rgt, 106f, dash)
    }

    private fun drawDc(c: Canvas, door: Float) {
        fill(0xFFE9EFF4.toInt()); c.drawRect(200f, 18f, 320f, 94f, p)
        fill(0xFF9FF2C4.toInt()); c.drawRect(200f, 12f, 320f, 24f, p)
        fill(0xFF1B2B36.toInt()); c.drawRect(236f, 30f, 284f, 44f, p)
        text.textSize = 10f; text.alpha = 255
        val old = text.color; text.color = 0xFF9FF2C4.toInt()
        c.drawText(Masters.dispatchName.take(10).ifBlank { "DC" }, 260f, 41f, text); text.color = old
        fill(0xFF1B2B36.toInt()); c.drawRect(246f, 52f, 284f, 94f, p)            // dock opening
        fill(0xFFCFD8DF.toInt()); c.drawRect(246f, 52f, 284f, 52f + 42f * (1f - door * .95f), p) // shutter
        fill(0xFF9FC3E3.toInt()); c.drawRect(294f, 46f, 312f, 60f, p); c.drawRect(208f, 46f, 226f, 60f, p)
    }

    private fun drawTruck(c: Canvas, x: Float, drive: Float, t: Float, distance: Float) {
        c.save()
        val moving = drive < 1f
        val bob = if (moving) sin(t * 28f) * 0.8f else 0f
        c.translate(x - 60f, 30f + bob)
        c.scale(.8f, .8f)
        // cargo box
        path.reset(); path.moveTo(6f, 10f); path.lineTo(98f, 10f); path.quadTo(102f, 10f, 102f, 14f)
        path.lineTo(102f, 62f); path.lineTo(2f, 62f); path.lineTo(2f, 14f); path.quadTo(2f, 10f, 6f, 10f); path.close()
        fill(0xFFFF7A1A.toInt()); c.drawPath(path, p)
        fill(Color.WHITE); c.drawRect(2f, 30f, 102f, 37f, p)
        fill(0xFF1D2B36.toInt()); c.drawRect(2f, 56f, 156f, 64f, p)
        // cab
        path.reset(); path.moveTo(104f, 24f); path.lineTo(130f, 24f); path.lineTo(154f, 46f); path.lineTo(154f, 62f); path.lineTo(104f, 62f); path.close()
        fill(0xFF2DBE6C.toInt()); c.drawPath(path, p)
        path.reset(); path.moveTo(110f, 30f); path.lineTo(127f, 30f); path.lineTo(140f, 44f); path.lineTo(110f, 44f); path.close()
        fill(0xFFCFE8FF.toInt()); c.drawPath(path, p)
        fill(0xFFFFE27A.toInt()); c.drawCircle(150f, 50f, 3f, p)
        fill(0xFFE53935.toInt()); c.drawRect(150f, 58f, 158f, 63f, p)
        // exhaust puffs while driving
        if (moving) {
            for (k in 0 until 3) {
                val ph = ((t * 2.2f + k / 3f) % 1f)
                fill(Color.argb((120 * (1 - ph)).toInt(), 255, 255, 255))
                c.drawCircle(-4f - ph * 26f, 58f - ph * 10f, 3f + ph * 5f, p)
            }
        }
        // wheels turn with the distance driven
        val angle = (drive * distance / (2f * PI.toFloat() * 14f * .8f)) * 360f
        for (cx in floatArrayOf(30f, 124f)) {
            fill(0xFF111111.toInt()); c.drawCircle(cx, 66f, 14f, p)
            fill(0xFF9AA7B2.toInt()); c.drawCircle(cx, 66f, 6f, p)
            c.save(); c.rotate(angle, cx, 66f)
            p.color = 0xFF111111.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            c.drawLine(cx, 59f, cx, 73f, p); c.drawLine(cx - 7f, 66f, cx + 7f, 66f, p)
            c.restore()
        }
        c.restore()
    }

    /** DC team member walking out of the dock door towards the truck. */
    private fun drawPerson(c: Canvas, i: Int, t: Float) {
        val t0 = TEAM_START + i * 0.3f
        if (t < t0) return
        val k = clamp((t - t0) / WALK)
        val fromX = 265f; val toX = 224f - i * 15f
        val x = fromX + (toX - fromX) * easeInOut(k)
        val walking = k < 1f
        val swing = if (walking) sin((t - t0) * 14f) * 4f else 0f
        val wave = if (!walking && i == 0) sin(t * 9f) * 3f else 0f
        val vest = intArrayOf(0xFFF5C400.toInt(), 0xFFFF7A1A.toInt(), 0xFF2DBE6C.toInt())[i]
        val foot = 94f
        // legs
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFF1B2B36.toInt()
        c.drawLine(x, foot - 9f, x - swing * .6f, foot, p)
        c.drawLine(x, foot - 9f, x + swing * .6f, foot, p)
        // arms
        p.color = 0xFFE0AC69.toInt(); p.strokeWidth = 2f
        c.drawLine(x, foot - 17f, x + swing * .5f - 3f, foot - 11f, p)
        if (wave != 0f) c.drawLine(x, foot - 17f, x - 4f, foot - 25f + wave, p)
        else c.drawLine(x, foot - 17f, x - swing * .5f + 3f, foot - 11f, p)
        // body (safety vest)
        fill(vest); r.set(x - 3.5f, foot - 19f, x + 3.5f, foot - 8f); c.drawRoundRect(r, 2f, 2f, p)
        fill(Color.WHITE); c.drawRect(x - 3.5f, foot - 14f, x + 3.5f, foot - 13f, p)
        // head + helmet
        fill(0xFFE0AC69.toInt()); c.drawCircle(x, foot - 22f, 3f, p)
        fill(Color.WHITE); r.set(x - 3.4f, foot - 26f, x + 3.4f, foot - 20.5f); c.drawArc(r, 180f, 180f, true, p)
    }
}
