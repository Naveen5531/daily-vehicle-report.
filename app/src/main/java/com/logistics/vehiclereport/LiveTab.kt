package com.logistics.vehiclereport

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live tab: read WhatsApp on demand (screen reading while the group is open, or pull notifications now). */
class LiveTab(private val a: MainActivity, root: View) {
    private val status: TextView = root.findViewById(R.id.liveStatus)
    private val screenBtn: MaterialButton
    private lateinit var stopBtn: MaterialButton
    private lateinit var autoBtn: MaterialButton
    private val time = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    init {
        val screen = card(root.findViewById(R.id.liveScreen), R.drawable.ic_live, R.color.wa_green, R.color.wa_bg,
            R.string.live_screen_title, R.string.live_screen_desc)
        screenBtn = button(screen, R.string.live_screen_on, R.drawable.ic_chat) {
            when {
                !WhatsAppScreenReader.isEnabled(a) -> askToEnable()
                WhatsAppScreenReader.running == null -> askToRestart()
                else -> openWhatsApp()
            }
        }
        stopBtn = button(screen, R.string.live_stop, R.drawable.ic_warning) { stopReading() }
        autoBtn = button(screen, R.string.live_auto_off, R.drawable.ic_calendar) { pickAutoOff() }

        val notif = card(root.findViewById(R.id.liveNotif), R.drawable.ic_download, R.color.brand_primary, R.color.ok_bg,
            R.string.live_notif_title, R.string.live_notif_desc)
        button(notif, R.string.live_pull, R.drawable.ic_live) { pullNow() }

        val export = card(root.findViewById(R.id.liveExport), R.drawable.ic_upload, R.color.blue, R.color.blue_bg,
            R.string.chat_import_title, R.string.chat_import_desc)
        button(export, R.string.chat_import_choose, R.drawable.ic_upload) { a.pickChatExport() }
    }

    fun refresh(s: Snapshot) {
        val enabled = WhatsAppScreenReader.isEnabled(a)
        val screenOn = enabled && WhatsAppScreenReader.running != null
        screenBtn.setText(if (enabled) R.string.live_open_whatsapp else R.string.live_screen_on)
        stopBtn.visibility = if (enabled) View.VISIBLE else View.GONE
        val min = VehicleStore.prefs(a).getInt(WhatsAppScreenReader.KEY_AUTO_OFF, WhatsAppScreenReader.DEFAULT_AUTO_OFF)
        autoBtn.text = if (min == 0) a.getString(R.string.live_auto_off_never) else a.getString(R.string.live_auto_off_min, min)
        val p = VehicleStore.prefs(a)
        val last = p.getLong(WhatsAppScreenReader.KEY_LAST_READ, 0L)
        status.text = a.getString(
            R.string.live_status_text,
            if (screenOn) "🟢" else if (enabled) "🟠" else "⚪",
            when {
                screenOn -> a.getString(R.string.on)
                enabled -> a.getString(R.string.live_stopped)
                else -> a.getString(R.string.off)
            },
            if (s.hasAccess) "🟢" else "⚪", a.getString(if (s.hasAccess) R.string.on else R.string.off),
            if (last == 0L) a.getString(R.string.never) else time.format(Date(last)),
            p.getString(WhatsAppScreenReader.KEY_LAST_SCAN, null) ?: a.getString(R.string.never)
        )
    }

    /** Banking apps refuse to open while an accessibility service is on: switch live reading off. */
    private fun stopReading() {
        if (WhatsAppScreenReader.stop()) {
            Toast.makeText(a, R.string.live_stopped_ok, Toast.LENGTH_LONG).show()
            a.refreshAll()
        } else {
            MaterialAlertDialogBuilder(a)
                .setTitle(R.string.live_stop)
                .setMessage(R.string.live_stop_manual)
                .setPositiveButton(R.string.continue_) { _, _ -> a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun pickAutoOff() {
        val values = intArrayOf(10, 30, 60, 120, 0)
        val labels = values.map { if (it == 0) a.getString(R.string.live_auto_off_never) else a.getString(R.string.live_auto_off_min, it) }.toTypedArray()
        val cur = values.indexOf(VehicleStore.prefs(a).getInt(WhatsAppScreenReader.KEY_AUTO_OFF, WhatsAppScreenReader.DEFAULT_AUTO_OFF))
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.live_auto_off_title)
            .setSingleChoiceItems(labels, cur) { d, i ->
                VehicleStore.prefs(a).edit().putInt(WhatsAppScreenReader.KEY_AUTO_OFF, values[i]).apply()
                d.dismiss(); a.refreshAll()
            }
            .show()
    }

    private fun askToRestart() = Disclosure.accessibility(a) {
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.live_stopped_title)
            .setMessage(R.string.live_stopped_steps)
            .setPositiveButton(R.string.continue_) { _, _ ->
                a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askToEnable() = Disclosure.accessibility(a) {
        MaterialAlertDialogBuilder(a)
            .setTitle(R.string.live_enable_title)
            .setMessage(R.string.live_enable_steps)
            .setPositiveButton(R.string.continue_) { _, _ ->
                a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openWhatsApp() {
        val pm = a.packageManager
        val launch = pm.getLaunchIntentForPackage("com.whatsapp") ?: pm.getLaunchIntentForPackage("com.whatsapp.w4b")
        if (launch == null) {
            Toast.makeText(a, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
            return
        }
        val group = VehicleStore.getSelectedGroup(a)
        if (group.isNotBlank()) Toast.makeText(a, a.getString(R.string.reading_only, group), Toast.LENGTH_LONG).show()
        a.startActivity(launch)
    }

    private fun pullNow() {
        val listener = WhatsAppNotificationListener.instance
        if (listener == null) {
            try {
                NotificationListenerService.requestRebind(ComponentName(a, WhatsAppNotificationListener::class.java))
            } catch (e: Exception) { }
            Toast.makeText(a, R.string.live_not_connected, Toast.LENGTH_LONG).show()
            return
        }
        val (read, added) = listener.pullActive()
        Toast.makeText(a, a.getString(R.string.live_pulled, read, added), Toast.LENGTH_LONG).show()
        a.refreshAll()
    }

    private fun card(view: View, icon: Int, fg: Int, bg: Int, title: Int, desc: Int): ChipGroup {
        view.findViewById<View>(R.id.reportIconBox).backgroundTintList = ColorStateList.valueOf(Ui.color(a, bg))
        view.findViewById<ImageView>(R.id.reportIcon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Ui.color(a, fg))
        }
        view.findViewById<TextView>(R.id.reportTitle).setText(title)
        view.findViewById<TextView>(R.id.reportDesc).setText(desc)
        return view.findViewById(R.id.reportControls)
    }

    private fun button(group: ViewGroup, text: Int, icon: Int, onClick: () -> Unit): MaterialButton {
        val b = MaterialButton(a)
        b.setText(text)
        b.setIconResource(icon)
        b.iconSize = (16 * a.resources.displayMetrics.density).toInt()
        b.cornerRadius = (24 * a.resources.displayMetrics.density).toInt()
        b.setOnClickListener { onClick() }
        group.addView(b, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return b
    }
}
