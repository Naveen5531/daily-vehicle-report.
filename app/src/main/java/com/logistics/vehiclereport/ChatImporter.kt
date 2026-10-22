package com.logistics.vehiclereport

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.DatePickerDialog
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

/** Imports a WhatsApp "Export chat" file (.txt or .zip): choose Today / a date / Everything, then save vehicles. */
object ChatImporter {

    private val dayFmt get() = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    fun start(a: MainActivity, uri: Uri, title: String?) {
        Toast.makeText(a, R.string.import_reading, Toast.LENGTH_SHORT).show()
        Thread {
            val result: Result<Pair<String, List<IncomingMessage>>> = try {
                val bytes = a.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                val fileName = displayName(a, uri)
                val text = if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) txtFromZip(bytes)
                else bytes.toString(Charsets.UTF_8)
                val chat = WhatsAppChatParser.chatNameFrom(title)
                    ?: WhatsAppChatParser.chatNameFrom(fileName)
                    ?: VehicleStore.getSelectedGroup(a).ifBlank { "WhatsApp chat" }
                Result.success(chat to WhatsAppChatParser.parse(text, chat))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            a.runOnUiThread {
                result.onSuccess { (chat, msgs) -> chooseRange(a, chat, msgs) }
                    .onFailure { Toast.makeText(a, a.getString(R.string.import_failed, it.message), Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun chooseRange(a: MainActivity, chat: String, msgs: List<IncomingMessage>) {
        if (msgs.isEmpty()) {
            MaterialAlertDialogBuilder(a).setTitle(R.string.chat_import_title).setMessage(R.string.chat_import_empty)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val first = msgs.minOf { it.time }
        val last = msgs.maxOf { it.time }
        val options = arrayOf(
            a.getString(R.string.chat_range_today),
            a.getString(R.string.chat_range_date),
            a.getString(R.string.chat_range_all)
        )
        MaterialAlertDialogBuilder(a)
            .setTitle(a.getString(R.string.chat_import_found, msgs.size, chat, dayFmt.format(Date(first)), dayFmt.format(Date(last))))
            .setItems(options) { _, which ->
                val today = ReportBuilder.startOfDay()
                when (which) {
                    0 -> save(a, msgs.filter { it.time >= today && it.time < today + ReportBuilder.DAY_MS })
                    1 -> pickDay(a, last) { d -> save(a, msgs.filter { it.time >= d && it.time < d + ReportBuilder.DAY_MS }) }
                    else -> save(a, msgs)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickDay(a: MainActivity, around: Long, onPicked: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = around }
        DatePickerDialog(a, { _, y, m, d ->
            onPicked(ReportBuilder.startOfDay(Calendar.getInstance().apply { set(y, m, d, 12, 0, 0) }.timeInMillis))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun save(a: MainActivity, msgs: List<IncomingMessage>) {
        if (msgs.isEmpty()) {
            Toast.makeText(a, R.string.chat_import_none_in_range, Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val added = try {
                msgs.chunked(300).sumOf { VehicleStore.ingest(a.applicationContext, it, ignoreGroupFilter = true) }
            } catch (e: Throwable) { -1 }
            a.runOnUiThread {
                if (added < 0) {
                    Toast.makeText(a, a.getString(R.string.import_failed, ""), Toast.LENGTH_LONG).show()
                } else {
                    MaterialAlertDialogBuilder(a)
                        .setTitle(R.string.chat_import_done_title)
                        .setMessage(a.getString(R.string.chat_import_done, msgs.size, added))
                        .setPositiveButton(R.string.chat_import_view) { _, _ -> a.showTodayTab() }
                        .setNegativeButton(R.string.close, null)
                        .show()
                    a.refreshAll()
                }
            }
        }.start()
    }

    private fun txtFromZip(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name.endsWith(".txt", ignoreCase = true)) return zip.readBytes().toString(Charsets.UTF_8)
                e = zip.nextEntry
            }
        }
        throw IllegalArgumentException("No chat text in the zip")
    }

    private fun displayName(a: MainActivity, uri: Uri): String? = try {
        a.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null } ?: uri.lastPathSegment
}
