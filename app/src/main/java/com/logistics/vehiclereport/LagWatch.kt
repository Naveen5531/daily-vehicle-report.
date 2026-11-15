package com.logistics.vehiclereport

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Finds what makes the app hang: a watcher thread pings the main thread every 100 ms; when the main thread does not answer
 * for 350 ms+, the code it is busy in is written to a small log. "Speed report" on the Export tab shows / shares it.
 */
object LagWatch {
    private const val LIMIT_MS = 350L
    private const val MAX_BYTES = 60_000
    @Volatile private var started = false
    /** The tab on screen, written to the log with each hang. */
    @Volatile var where = ""

    private fun file(c: Context) = File(c.filesDir, "lag_log.txt")

    fun start(c: Context) {
        if (started) return
        started = true
        val app = c.applicationContext
        val main = Looper.getMainLooper()
        val handler = Handler(main)
        val fmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)
        Thread({
            while (true) {
                val sent = System.currentTimeMillis()
                val box = LongArray(1)
                handler.post { box[0] = System.currentTimeMillis() }
                var worst: String? = null
                // Wait for the answer; while waiting past the limit, remember the main thread's current code.
                while (box[0] == 0L) {
                    Thread.sleep(50)
                    val waited = System.currentTimeMillis() - sent
                    if (waited >= LIMIT_MS) worst = describe(main.thread.stackTrace)
                    if (waited > 20_000) break
                }
                val blocked = (if (box[0] == 0L) System.currentTimeMillis() else box[0]) - sent
                if (blocked >= LIMIT_MS && worst != null) {
                    try {
                        val f = file(app)
                        val old = if (f.exists() && f.length() < MAX_BYTES) f.readText() else ""
                        f.writeText("${fmt.format(Date())}  stuck ${blocked} ms on [$where] in:\n$worst\n\n$old")
                    } catch (e: Exception) { }
                }
                Thread.sleep(150)
            }
        }, "lag-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** The app's own frames (top of the stack first), else the top few frames. */
    private fun describe(st: Array<StackTraceElement>): String {
        val mine = st.filter { it.className.startsWith("com.logistics") }.take(6)
        val use = if (mine.isNotEmpty()) mine else st.take(5)
        val top = st.take(2).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        return "  top: $top\n" + use.joinToString("\n") { "  at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
    }

    fun read(c: Context): String = try { file(c).takeIf { it.exists() }?.readText().orEmpty() } catch (e: Exception) { "" }
    fun clear(c: Context) { try { file(c).delete() } catch (e: Exception) { } }
}
