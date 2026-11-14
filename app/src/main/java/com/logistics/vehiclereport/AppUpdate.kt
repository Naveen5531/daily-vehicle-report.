package com.logistics.vehiclereport

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update: every GitHub build publishes version.json next to the APK on the apk-builds branch. When it names a
 * higher version than the one installed, the app offers to download it and opens the installer (same signing key,
 * so it installs over the current app and keeps all data). Fails quietly when the network does not reach GitHub.
 */
object AppUpdate {
    private const val BASE = "https://raw.githubusercontent.com/Naveen5531/daily-vehicle-report./apk-builds/"
    private const val CHECK_EVERY_MS = 30L * 60 * 1000
    private var lastCheck = 0L
    @Volatile private var busy = false

    /** On opening the app: at most one check every 30 minutes. */
    fun checkIfDue(a: Activity) {
        val now = System.currentTimeMillis()
        if (now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        check(a, manual = false)
    }

    /** [manual]: also says "up to date" / "could not check". */
    fun check(a: Activity, manual: Boolean) {
        if (busy) return
        busy = true
        Thread {
            var newCode = 0; var newName = ""; var ok = false
            try {
                val c = (URL(BASE + "version.json?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; useCaches = false
                }
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                newCode = o.optInt("code"); newName = o.optString("name"); ok = true
            } catch (e: Exception) { }
            busy = false
            val have = try {
                @Suppress("DEPRECATION") a.packageManager.getPackageInfo(a.packageName, 0).versionCode
            } catch (e: Exception) { Int.MAX_VALUE }
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                when {
                    ok && newCode > have -> offer(a, newName)
                    manual -> android.widget.Toast.makeText(a,
                        if (ok) "App is up to date" else "Could not check for updates (no internet?)", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun offer(a: Activity, name: String) {
        MaterialAlertDialogBuilder(a)
            .setTitle("Update available")
            .setMessage("New version $name is ready. Your data stays as it is.")
            .setPositiveButton("Update now") { _, _ -> download(a) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun download(a: Activity) {
        // Android asks once to allow installing from this app.
        if (!a.packageManager.canRequestPackageInstalls()) {
            android.widget.Toast.makeText(a, "Allow \"Install unknown apps\" for this app, then tap Update again", android.widget.Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            return
        }
        val box = MaterialAlertDialogBuilder(a).setTitle("Downloading update").setMessage("Please wait…").setCancelable(false).show()
        Thread {
            var file: File? = null
            try {
                val dir = File(a.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, "Daily_Vehicle_Report.apk")
                val c = (URL(BASE + "Daily_Vehicle_Report.apk?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000; useCaches = false
                }
                c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                if (f.length() > 1_000_000) file = f
            } catch (e: Exception) { }
            a.runOnUiThread {
                try { box.dismiss() } catch (e: Exception) { }
                val f = file
                if (f == null) {
                    android.widget.Toast.makeText(a, "Download failed. Try again on a better connection.", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
                    a.startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            }
        }.start()
    }
}
