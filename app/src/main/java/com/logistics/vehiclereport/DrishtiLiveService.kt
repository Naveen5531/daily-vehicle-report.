package com.logistics.vehiclereport

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Date

/**
 * Drishti live: reads the DC gate log every [DrishtiStore.LIVE_EVERY_MS], even when the app is closed, so a vehicle
 * shows Reported the moment it checks in at the gate and Departed when it checks out – no WhatsApp post needed.
 * Runs as a foreground service (a quiet notification stays while it is on) with an off-screen browser that uses the
 * Drishti sign-in made in the app. Stops by itself when the sign-in expires and asks to sign in again.
 */
class DrishtiLiveService : Service(), DrishtiPage.Listener {

    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var page: DrishtiPage? = null
    private var started = false
    private var wake: PowerManager.WakeLock? = null
    private val tick = Runnable { runNow() }
    private val giveUp = Runnable { stopPage(); schedule() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channels(this)
        goForeground(getString(R.string.live_starting))
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !DrishtiStore.liveOn(this)) {
            DrishtiStore.setLive(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        main.removeCallbacks(tick)
        main.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(tick)
        stopPage()
        releaseWake()
        super.onDestroy()
    }

    private fun goForeground(text: String) {
        val n = ongoing(text)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID_ONGOING, n)
        } catch (e: Throwable) {
            // Android refused a background start: nothing to run.
            CrashLog.save(this, "Drishti live start", e)
            stopSelf()
        }
    }

    private fun ongoing(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, DrishtiLiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH_LIVE)
            .setSmallIcon(R.drawable.ic_truck)
            .setContentTitle(getString(R.string.live_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.drishti_live_stop), stop)
            .build()
    }

    private fun updateOngoing(text: String) {
        if (!canNotify(this)) return
        try { getSystemService(NotificationManager::class.java).notify(ID_ONGOING, ongoing(text)) } catch (e: Exception) { }
    }

    private fun schedule() {
        main.removeCallbacks(tick)
        if (!DrishtiStore.liveOn(this)) { stopSelf(); return }
        main.postDelayed(tick, DrishtiStore.LIVE_EVERY_MS)
        // Keep the phone able to wake for the next read.
        holdWake(DrishtiStore.LIVE_EVERY_MS + 4 * 60_000L)
    }

    private fun runNow() {
        if (!DrishtiStore.liveOn(this)) { stopSelf(); return }
        if (page != null) return
        holdWake(4 * 60_000L)
        val w = try { WebView(this) } catch (e: Throwable) { schedule(); return }
        // Off screen, but laid out like a computer screen so the page draws its table rows.
        w.visibility = View.VISIBLE
        val wSpec = View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        w.measure(wSpec, hSpec)
        w.layout(0, 0, 1280, 2400)
        web = w
        started = false
        page = DrishtiPage(w, this).also { it.setup(); it.openReport() }
        main.postDelayed(giveUp, 170_000)
    }

    override fun onPageLoaded(url: String) {
        val p = page ?: return
        if (started) return
        started = true
        main.postDelayed({ if (page === p) p.read() }, 2_500)
    }

    override fun onRead(state: String, cells: List<List<String>>, pageDay: Long) {
        stopPage()
        if (state != DrishtiStore.OK) {
            DrishtiStore.setStatus(this, state)
            if (state == DrishtiStore.LOGIN) {
                alert(ID_LOGIN, getString(R.string.live_login_title), getString(R.string.live_login_text))
                DrishtiStore.setLive(this, false)
                stopSelf()
            } else {
                updateOngoing(getString(R.string.live_no_table))
                schedule()
            }
            return
        }
        val app = applicationContext
        Thread {
            val today = ReportBuilder.startOfDay()
            val before = GateMarks.forDay(app, today)
            val result = try { DrishtiStore.apply(app, cells, pageDay) } catch (e: Throwable) { null }
            val after = GateMarks.forDay(app, today)
            main.post {
                if (result != null) {
                    announce(before, after)
                    updateOngoing(summary(after))
                }
                schedule()
            }
        }.start()
    }

    /** "12 of 40 at the gate · 5 departed · last read 10:14 AM". */
    private fun summary(marks: Map<String, GateMark>): String {
        val dedicated = VehicleStore.getVehicles(this).filter { it.dedicated }.map { it.number }.toSet()
        val inNow = marks.count { it.key in dedicated && it.value.gate == Gate.REPORTED }
        val gone = marks.count { it.key in dedicated && it.value.gate == Gate.DEPARTED }
        return getString(R.string.live_summary, inNow + gone, dedicated.size, gone,
            ReportBuilder.timeFmt().format(Date(DrishtiStore.lastPull(this))))
    }

    /** A notification when dedicated vehicles check in or out at the gate. */
    private fun announce(before: Map<String, GateMark>, after: Map<String, GateMark>) {
        val dedicated = VehicleStore.getVehicles(this).filter { it.dedicated }.map { it.number }.toSet()
        val t = ReportBuilder.timeFmt()
        val lines = mutableListOf<String>()
        for ((v, m) in after) {
            if (v !in dedicated) continue
            val old = before[v]
            if (m.gate == Gate.REPORTED && (old == null || old.gate == Gate.YET))
                lines += getString(R.string.live_in, v, if (m.reportedAt > 0) t.format(Date(m.reportedAt)) else "")
            if (m.gate == Gate.DEPARTED && old?.gate != Gate.DEPARTED)
                lines += getString(R.string.live_out, v, if (m.departedAt > 0) t.format(Date(m.departedAt)) else "")
        }
        if (lines.isEmpty()) return
        alert(ID_CHANGES, resources.getQuantityString(R.plurals.live_changes, lines.size, lines.size), lines.joinToString("\n"))
    }

    private fun alert(id: Int, title: String, text: String) {
        if (!canNotify(this)) return
        val open = PendingIntent.getActivity(this, id, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_truck)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { getSystemService(NotificationManager::class.java).notify(id, n) } catch (e: Exception) { }
    }

    private fun stopPage() {
        main.removeCallbacks(giveUp)
        val p = page
        page = null; web = null
        p?.destroy()
    }

    private fun holdWake(ms: Long) {
        try {
            val w = wake ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DailyVehicleReport:DrishtiLive").also {
                    it.setReferenceCounted(false); wake = it
                }
            w.acquire(ms)
        } catch (e: Exception) { }
    }

    private fun releaseWake() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        wake = null
    }

    companion object {
        private const val CH_LIVE = "drishti_live"
        private const val CH_ALERTS = "drishti_gate"
        private const val ID_ONGOING = 4101
        private const val ID_CHANGES = 4102
        private const val ID_LOGIN = 4103
        private const val ACTION_STOP = "com.logistics.vehiclereport.DRISHTI_LIVE_STOP"

        /** True while the service is running in this app's process. */
        @Volatile var running = false
            private set

        fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        private fun channels(c: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_LIVE, c.getString(R.string.live_channel), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_ALERTS, c.getString(R.string.live_alert_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** Starts live when it is switched on and Drishti has worked once on this phone. */
        fun startIfOn(c: Context) {
            if (!DrishtiStore.liveOn(c) || !DrishtiStore.everWorked(c) || running) return
            try { ContextCompat.startForegroundService(c, Intent(c, DrishtiLiveService::class.java)) }
            catch (e: Throwable) { CrashLog.save(c, "Drishti live start", e) }
        }

        fun stop(c: Context) {
            DrishtiStore.setLive(c, false)
            try { c.stopService(Intent(c, DrishtiLiveService::class.java)) } catch (e: Exception) { }
        }
    }
}
