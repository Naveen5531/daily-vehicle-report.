package com.logistics.vehiclereport

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Everything the tabs need, loaded once per refresh. */
data class Snapshot(
    val vehicles: List<DedicatedVehicle>,
    val records: List<VehicleRecord>,
    val unmatched: List<UnmatchedMessage>,
    val hasAccess: Boolean
)

object Ui {

    fun color(context: Context, res: Int) = ContextCompat.getColor(context, res)

    fun dial(context: Context, mobile: String) {
        val digits = mobile.filter { it.isDigit() || it == '+' }
        if (digits.isEmpty()) return
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
        } catch (e: Exception) {
            Toast.makeText(context, digits, Toast.LENGTH_SHORT).show()
        }
    }

    /** Colours a status pill (and optional side bar) for a day status. */
    fun styleStatus(pill: TextView, bar: View?, status: DayStatus) {
        val c = pill.context
        val (textRes, fg, bg) = when (status) {
            DayStatus.REPORTED -> Triple(R.string.st_reported, R.color.ok, R.color.ok_bg)
            DayStatus.PENDING -> Triple(R.string.st_pending, R.color.no, R.color.no_bg)
            DayStatus.CHECK -> Triple(R.string.st_check, R.color.chk, R.color.chk_bg)
        }
        pill.setText(textRes)
        pill.setTextColor(color(c, fg))
        pill.backgroundTintList = ColorStateList.valueOf(color(c, bg))
        bar?.backgroundTintList = ColorStateList.valueOf(color(c, fg))
    }

    /** Pill for what Today shows: Not posted, To check, Yet to report, Reported, Departed. */
    fun styleState(pill: TextView, state: TodayState) {
        val c = pill.context
        val (textRes, fg, bg) = when (state) {
            TodayState.NOT_POSTED -> Triple(R.string.st_not_posted, R.color.no, R.color.no_bg)
            TodayState.CHECK -> Triple(R.string.st_check, R.color.chk, R.color.chk_bg)
            TodayState.YET -> Triple(R.string.gate_yet, R.color.wait, R.color.wait_bg)
            TodayState.REPORTED -> Triple(R.string.st_reported, R.color.ok, R.color.ok_bg)
            TodayState.DEPARTED -> Triple(R.string.gate_departed, R.color.gone, R.color.gone_bg)
        }
        pill.setText(textRes)
        pill.setTextColor(color(c, fg))
        pill.backgroundTintList = ColorStateList.valueOf(color(c, bg))
    }

    /** "Reported 09:12 AM · Departed 10:40 AM" for a hand mark, or "" when no time was marked. */
    fun gateTimes(c: Context, mark: GateMark?): String {
        if (mark == null) return ""
        val t = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
        return joinNonBlank(
            if (mark.reportedAt > 0) c.getString(R.string.gate_at_reported, t.format(java.util.Date(mark.reportedAt))) else "",
            if (mark.departedAt > 0) c.getString(R.string.gate_at_departed, t.format(java.util.Date(mark.departedAt))) else ""
        )
    }

    /** The three-way Yet to report / Reported / Departed buttons: shows [state], reports taps through [onPick]. */
    fun bindGate(group: com.google.android.material.button.MaterialButtonToggleGroup, state: TodayState, onPick: (Gate?) -> Unit) {
        group.clearOnButtonCheckedListeners()
        val ids = mapOf(Gate.YET to R.id.gateYet, Gate.REPORTED to R.id.gateReported, Gate.DEPARTED to R.id.gateDeparted)
        val shown = when (state) {
            TodayState.YET -> Gate.YET
            TodayState.REPORTED -> Gate.REPORTED
            TodayState.DEPARTED -> Gate.DEPARTED
            else -> null
        }
        if (shown == null) group.clearChecked() else group.check(ids.getValue(shown))
        group.addOnButtonCheckedListener { g, id, isChecked ->
            val picked = ids.entries.firstOrNull { it.value == id }?.key ?: return@addOnButtonCheckedListener
            when {
                isChecked && picked != shown -> g.post { onPick(picked) }
                !isChecked && picked == shown && g.checkedButtonId == android.view.View.NO_ID -> g.post { onPick(null) }
            }
        }
    }

    fun joinNonBlank(vararg parts: String, sep: String = " · ") = parts.filter { it.isNotBlank() }.joinToString(sep)

    /** Background / text colour pairs for vendor initials. The same vendor always gets the same pair. */
    private val AVATARS = longArrayOf(
        0xFFE6F0FF, 0xFF2950A8, 0xFFF3E8FF, 0xFF6B33A8, 0xFFDDF6EE, 0xFF0B6B5A,
        0xFFFFF1D6, 0xFF8A5A00, 0xFFFFE6EE, 0xFFA3264F, 0xFFEAEFF3, 0xFF3B4A57
    )
    private val NAME_NOISE = setOf(
        "transport", "transports", "logistics", "logistic", "cargo", "carriers", "carrier", "travels", "roadways",
        "services", "service", "pvt", "ltd", "private", "limited", "and", "the", "co", "company", "movers", "m/s", "ms"
    )

    fun initials(name: String): String {
        val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
        val useful = words.filter { it.lowercase() !in NAME_NOISE }.ifEmpty { words }
        return when {
            useful.size >= 2 -> "${useful[0][0]}${useful[1][0]}"
            useful.size == 1 -> useful[0].take(2)
            else -> ""
        }.uppercase()
    }

    /** Rounded square with the vendor's initials (or [fallback] when there is no vendor). */
    fun avatar(tv: TextView, name: String, fallback: String) {
        val key = name.trim().lowercase().ifEmpty { fallback.lowercase() }
        val i = (key.hashCode() and 0x7fffffff) % (AVATARS.size / 2)
        tv.text = initials(name).ifEmpty { fallback.filter { it.isLetter() }.take(2).uppercase() }
        tv.backgroundTintList = ColorStateList.valueOf(AVATARS[i * 2].toInt())
        tv.setTextColor(AVATARS[i * 2 + 1].toInt())
    }

    enum class Tone { PLAIN, OK, WARN, BAD }

    /** Small rounded label used inside WhatsApp entries. */
    fun infoChip(c: Context, text: String, tone: Tone = Tone.PLAIN): TextView =
        TextView(c, null, 0, R.style.InfoChip).apply {
            this.text = text
            val (bg, fg) = when (tone) {
                Tone.PLAIN -> R.color.soft to R.color.ink
                Tone.OK -> R.color.ok_bg to R.color.ok
                Tone.WARN -> R.color.chk_bg to R.color.chk
                Tone.BAD -> R.color.no_bg to R.color.no
            }
            backgroundTintList = ColorStateList.valueOf(color(c, bg))
            setTextColor(color(c, fg))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (240 * c.resources.displayMetrics.density).toInt()
        }
}
