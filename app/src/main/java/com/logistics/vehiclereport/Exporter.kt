package com.logistics.vehiclereport

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/** Writes an .xlsx and lets the user share it or save it to Downloads. */
object Exporter {

    private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    /** [numbers]: plain numbers go in as numbers (so they add up in Excel); other reports keep every cell as text. */
    fun deliver(activity: AppCompatActivity, fileName: String, rows: List<List<String>>, widths: List<Int>, numbers: Boolean = false) {
        if (rows.size < 2) {
            Toast.makeText(activity, R.string.nothing_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(activity.getString(R.string.export_share), activity.getString(R.string.export_save))
        MaterialAlertDialogBuilder(activity)
            .setTitle(fileName)
            .setItems(options) { _, which ->
                if (which == 0) share(activity, fileName, rows, widths, numbers) else save(activity, fileName, rows, widths, numbers)
            }
            .show()
    }

    /** Writes one report for the mail and returns a link other apps (Gmail) can read. */
    fun writeFile(activity: AppCompatActivity, fileName: String, rows: List<List<String>>, widths: List<Int>): android.net.Uri {
        val dir = File(activity.cacheDir, "reports/mail").apply { mkdirs() }
        val file = File(dir, fileName)
        file.outputStream().use { XlsxWriter.write(it, "Report", rows, widths) }
        return FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
    }

    fun clearMailFiles(activity: AppCompatActivity) {
        File(activity.cacheDir, "reports/mail").listFiles()?.forEach { it.delete() }
    }

    private fun share(activity: AppCompatActivity, fileName: String, rows: List<List<String>>, widths: List<Int>, numbers: Boolean = false) {
        try {
            val dir = File(activity.cacheDir, "reports").apply { mkdirs() }
            dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            val file = File(dir, fileName)
            file.outputStream().use { XlsxWriter.write(it, "Report", rows, widths, numbers) }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = XLSX_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension.replace('_', ' '))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.export_share)))
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun save(activity: AppCompatActivity, fileName: String, rows: List<List<String>>, widths: List<Int>, numbers: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 8–9 would need a storage permission; the share menu lets the user save it anywhere.
            Toast.makeText(activity, R.string.save_old_android, Toast.LENGTH_LONG).show()
            share(activity, fileName, rows, widths)
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, XLSX_MIME)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create file")
            resolver.openOutputStream(uri)?.use { XlsxWriter.write(it, "Report", rows, widths, numbers) }
                ?: throw IllegalStateException("Could not open file")
            Toast.makeText(activity, activity.getString(R.string.saved_to_downloads, fileName), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.export_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }
}
