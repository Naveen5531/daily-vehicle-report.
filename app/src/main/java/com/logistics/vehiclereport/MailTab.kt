package com.logistics.vehiclereport

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Mail tab: send the Excel reports by e-mail. The app has no internet of its own, so it fills in a mail
 * (to, cc, subject, summary text, attachments) and hands it to Gmail / Outlook, where you press Send.
 */
class MailTab(private val a: MainActivity, root: View) {
    private val to: TextInputEditText = root.findViewById(R.id.mailTo)
    private val cc: TextInputEditText = root.findViewById(R.id.mailCc)
    private val subject: TextInputEditText = root.findViewById(R.id.mailSubject)
    private val dates: ChipGroup = root.findViewById(R.id.mailDates)
    private val daily: MaterialCheckBox = root.findViewById(R.id.mailDaily)
    private val log: MaterialCheckBox = root.findViewById(R.id.mailLog)
    private val summary: MaterialCheckBox = root.findViewById(R.id.mailSummary)
    private val details: MaterialCheckBox = root.findViewById(R.id.mailDetails)
    private val preview: TextView = root.findViewById(R.id.mailPreview)
    private val p = VehicleStore.prefs(a)
    private val label = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val fileDate = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    private var from = ReportBuilder.startOfDay()
    private var until = ReportBuilder.startOfDay()
    private val pickChip = Chip(a)

    companion object {
        private const val K_TO = "mail_to"
        private const val K_CC = "mail_cc"
        private const val K_SUBJECT = "mail_subject"
        private const val K_ATTACH = "mail_attach"
        const val DEFAULT_SUBJECT = "{dc} – Daily Vehicle Report – {date}"
    }

    init {
        to.setText(p.getString(K_TO, ""))
        cc.setText(p.getString(K_CC, ""))
        subject.setText(p.getString(K_SUBJECT, DEFAULT_SUBJECT))
        fun saveOnChange(f: TextInputEditText, key: String) = f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { p.edit().putString(key, s?.toString().orEmpty()).apply(); if (key == K_SUBJECT) refresh() }
        })
        saveOnChange(to, K_TO); saveOnChange(cc, K_CC); saveOnChange(subject, K_SUBJECT)

        val saved = p.getString(K_ATTACH, "D")!!
        daily.isChecked = 'D' in saved; log.isChecked = 'L' in saved; summary.isChecked = 'S' in saved; details.isChecked = 'V' in saved
        listOf(daily, log, summary, details).forEach { cb -> cb.setOnCheckedChangeListener { _, _ -> saveAttach(); refresh() } }

        val today = ReportBuilder.startOfDay()
        val yesterday = ReportBuilder.startOfDay(today - ReportBuilder.DAY_MS / 2)
        val week = ReportBuilder.startOfDay(today - 6 * ReportBuilder.DAY_MS + ReportBuilder.DAY_MS / 2)
        val month = ReportBuilder.startOfDay(Calendar.getInstance().apply { timeInMillis = today; set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis)
        dates.isSingleSelection = true
        val quick = listOf(
            R.string.quick_today to (today to today), R.string.mail_yesterday to (yesterday to yesterday),
            R.string.quick_week to (week to today), R.string.quick_month to (month to today)
        )
        quick.forEachIndexed { i, (text, range) ->
            dates.addView(Chip(a).apply {
                // These chips sit on the page, not in a card: white, dark emerald when chosen.
                chipBackgroundColor = androidx.core.content.ContextCompat.getColorStateList(a, R.color.chip_bg)
                setText(text); isCheckable = true; isChecked = i == 0
                setOnClickListener { from = range.first; until = range.second; isChecked = true; refresh() }
            })
        }
        pickChip.isCheckable = true
        pickChip.chipBackgroundColor = androidx.core.content.ContextCompat.getColorStateList(a, R.color.chip_bg)
        pickChip.setText(R.string.mail_pick_dates)
        pickChip.setOnClickListener { pickRange() }
        dates.addView(pickChip)

        root.findViewById<MaterialButton>(R.id.mailSend).setOnClickListener { send() }
        refresh()
    }

    private fun saveAttach() {
        val s = (if (daily.isChecked) "D" else "") + (if (log.isChecked) "L" else "") +
            (if (summary.isChecked) "S" else "") + (if (details.isChecked) "V" else "")
        p.edit().putString(K_ATTACH, s).apply()
    }

    private fun pickRange() {
        fun dialog(start: Long, title: Int, done: (Long) -> Unit) {
            val c = Calendar.getInstance().apply { timeInMillis = start }
            DatePickerDialog(a, { _, y, m, d ->
                done(ReportBuilder.startOfDay(Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis))
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.maxDate = System.currentTimeMillis(); setTitle(title)
            }.show()
        }
        dialog(from, R.string.mail_from) { f ->
            dialog(maxOf(f, until), R.string.mail_until) { t ->
                from = minOf(f, t); until = maxOf(f, t)
                pickChip.text = rangeText(); pickChip.isChecked = true
                refresh()
            }
        }
    }

    private fun rangeText() = if (from == until) label.format(Date(from)) else "${label.format(Date(from))} – ${label.format(Date(until))}"

    private fun subjectText() = subject.text?.toString().orEmpty().ifBlank { DEFAULT_SUBJECT }
        .replace("{date}", rangeText()).replace("{dc}", Masters.DISPATCH_FROM)

    /** The mail text: counts per status, pending reporting vehicles, total cost. */
    private fun bodyText(): String {
        val s = a.loadSnapshot()
        val lk = ReportBuilder.Lookups(MasterData.get(a), RateCard.get(a))
        val rows = ReportBuilder.dailyRows(s.vehicles, s.records, from, until, lk, GateMarks.gates(a))
        val h = rows.first()
        val body = rows.drop(1).filter { it.firstOrNull() != "TOTAL" }
        val st = h.indexOf("Status"); val num = h.indexOf("Vehicle Number"); val rate = h.indexOf("Rate (₹)")
        fun count(f: (String) -> Boolean) = body.count { f(it[st]) }
        val reported = count { it.startsWith("Reported") }
        val departed = count { it.startsWith("Departed") }
        val yet = count { it.startsWith("Yet to report") }
        val check = count { it.startsWith("Check") }
        val notInList = count { it.contains("not in dedicated list") }
        val pending = body.filter { it[st].startsWith("Not posted") }
        val total = rows.last().getOrNull(rate).orEmpty()
        val tracked = s.vehicles.count { it.dedicated }
        return buildString {
            append("Dear Team,\n\nPlease find attached the vehicle report of ${Masters.DISPATCH_FROM} for ${rangeText()}.\n\n")
            append("• Vehicles reported at the DC: ${reported + departed}")
            if (departed > 0) append(" (departed: $departed)")
            append("\n")
            if (yet > 0) append("• Posted, yet to report: $yet\n")
            if (tracked > 0) append("• Reporting vehicles: $tracked · not posted: ${pending.size}\n")
            if (check > 0) append("• To check (same last 4 digits): $check\n")
            if (notInList > 0) append("• Not in vehicle list: $notInList\n")
            if (total.isNotBlank() && total != "0") append("• Total freight as per rate card: ₹ ${RatesTab.formatAmount(total)}\n")
            if (pending.isNotEmpty() && from == until) {
                append("\nNot posted:\n")
                pending.take(40).forEach { append("  ${it[num]}\n") }
                if (pending.size > 40) append("  … ${pending.size - 40} more (see attachment)\n")
            }
            append("\nRegards")
        }
    }

    fun refresh() {
        preview.text = try { a.getString(R.string.mail_subject_line, subjectText()) + "\n\n" + bodyText() } catch (e: Exception) { e.message.orEmpty() }
    }

    private fun send() {
        val toList = split(to.text?.toString())
        if (toList.isEmpty()) { to.error = a.getString(R.string.mail_need_to); return }
        if (!daily.isChecked && !log.isChecked && !summary.isChecked && !details.isChecked) {
            Toast.makeText(a, R.string.mail_need_attach, Toast.LENGTH_SHORT).show(); return
        }
        val s = a.loadSnapshot()
        val lk = ReportBuilder.Lookups(MasterData.get(a), RateCard.get(a))
        val tag = if (from == until) fileDate.format(Date(from)) else "${fileDate.format(Date(from))}_to_${fileDate.format(Date(until))}"
        val files = mutableListOf<Uri>()
        try {
            Exporter.clearMailFiles(a)
            if (daily.isChecked) files += Exporter.writeFile(a, "Dedicated_Vehicle_Report_$tag.xlsx",
                ReportBuilder.dailyRows(s.vehicles, s.records, from, until, lk, GateMarks.gates(a)), ReportBuilder.DAILY_WIDTHS)
            if (log.isChecked) files += Exporter.writeFile(a, "WhatsApp_Log_$tag.xlsx",
                ReportBuilder.logRows(s.vehicles, s.records.filter { it.time >= from && it.time < until + ReportBuilder.DAY_MS }, lk), ReportBuilder.LOG_WIDTHS)
            if (summary.isChecked) files += Exporter.writeFile(a, "Vehicle_Summary_$tag.xlsx",
                ReportBuilder.summaryRows(s.vehicles, s.records, from, until, GateMarks.gates(a)), ReportBuilder.SUMMARY_WIDTHS)
            if (details.isChecked) files += Exporter.writeFile(a, "Vehicle_Details_${fileDate.format(Date())}.xlsx",
                ReportBuilder.detailRows(s.vehicles, s.records, GateMarks.gates(a)), ReportBuilder.DETAILS_WIDTHS)
        } catch (e: Exception) {
            Toast.makeText(a, a.getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show(); return
        }
        val mail = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_EMAIL, toList.toTypedArray())
            split(cc.text?.toString()).takeIf { it.isNotEmpty() }?.let { putExtra(Intent.EXTRA_CC, it.toTypedArray()) }
            putExtra(Intent.EXTRA_SUBJECT, subjectText())
            putExtra(Intent.EXTRA_TEXT, bodyText())
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            a.startActivity(Intent.createChooser(mail, a.getString(R.string.mail_send)))
        } catch (e: Exception) {
            Toast.makeText(a, R.string.mail_no_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun split(s: String?) = s.orEmpty().split(',', ';', ' ', '\n').map { it.trim() }.filter { it.contains('@') }
}
