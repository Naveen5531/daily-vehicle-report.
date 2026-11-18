package com.logistics.vehiclereport

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
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
    @Volatile private var installing = false

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
                    ok && newCode > have && !manual && a.packageManager.canRequestPackageInstalls() -> autoUpdate(a, newName)
                    ok && newCode > have -> offer(a, newName)
                    manual -> android.widget.Toast.makeText(a,
                        if (ok) "App is up to date" else "Could not check for updates (no internet?)", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Automatic update: downloads the new build in the background and installs it without any "Update now" question.
     * Android installs an update of an app silently when the update is allowed to skip the confirmation; where it still
     * wants one, its own Install screen opens straight away. Falls back to the manual route on any failure.
     */
    private fun autoUpdate(a: Activity, name: String) {
        if (installing) return
        installing = true
        android.widget.Toast.makeText(a, "Updating the app to $name…", android.widget.Toast.LENGTH_LONG).show()
        Thread {
            val f = fetchApk(a)
            a.runOnUiThread {
                installing = false
                if (f == null) return@runOnUiThread       // no connection: tried again at the next check
                if (!installSession(a, f)) openInstaller(a, f)
            }
        }.start()
    }

    private fun fetchApk(a: Activity): File? {
        return try {
            val dir = File(a.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val f = File(dir, "Daily_Vehicle_Report.apk")
            val c = (URL(BASE + "Daily_Vehicle_Report.apk?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; useCaches = false
            }
            c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            if (f.length() > 1_000_000) f else null
        } catch (e: Exception) { null }
    }

    /** Installs through Android's package installer, asking for no confirmation where the system allows it. */
    private fun installSession(a: Activity, f: File): Boolean = try {
        val pi = a.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            f.inputStream().use { i -> session.openWrite("update.apk", 0, f.length()).use { o -> i.copyTo(o); session.fsync(o) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(a, id, Intent(a, InstallResultReceiver::class.java).setPackage(a.packageName), flags)
            session.commit(pending.intentSender)
        }
        true
    } catch (e: Exception) { false }

    private fun openInstaller(a: Activity, f: File) {
        val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
        a.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** Receives the installer's answer; when Android needs the person's confirmation, its Install screen is opened. */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
            @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }
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
