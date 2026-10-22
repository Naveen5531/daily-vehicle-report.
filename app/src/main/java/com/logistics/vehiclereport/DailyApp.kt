package com.logistics.vehiclereport

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves any crash to a file so the app can show it (and the user can share it) on the next start. */
class DailyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { CrashLog.save(this, "CRASH", error) } catch (t: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
        if (Account.isSignedIn(this)) {
            Account.loadSettings(this)
            CloudSync.start(this)
            Thread {
                try { VehicleStore.upgradeOldRecords(this) } catch (t: Throwable) { CrashLog.save(this, "Upgrading old entries", t) }
            }.start()
        }
    }
}

object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_error.txt")

    fun save(context: Context, where: String, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val info = buildString {
            append("Daily Vehicle Report ").append(BuildConfigInfo.version(context)).append('\n')
            append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Time: ").append(SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(Date())).append('\n')
            append("Where: ").append(where).append("\n\n")
            append(sw.toString().take(6000))
        }
        file(context).writeText(info)
    }

    /** Returns the saved error once, then forgets it. */
    fun takeSaved(context: Context): String? {
        val f = file(context)
        if (!f.exists()) return null
        val text = f.readText()
        f.delete()
        return text.ifBlank { null }
    }
}

object BuildConfigInfo {
    fun version(c: Context): String = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        "v" + pi.versionName
    } catch (e: Exception) { "" }
}
