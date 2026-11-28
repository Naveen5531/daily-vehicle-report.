package com.logistics.vehiclereport

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallScreeningService
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Incoming call from a driver the app already knows (captured from WhatsApp posts or the vehicle list): a notification
 * shows the driver, vehicle, vendor and destination. The call is never blocked, held or silenced, and no contact is
 * needed. Works only when the user has made this app the call screening app (Android 10 and later).
 */
class CallLookupService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        // Let the call through at once; the lookup runs after, so the phone never waits for it.
        respondToCall(details, CallScreeningService.CallResponse.Builder()
            .setDisallowCall(false).setRejectCall(false)
            .setSilenceCall(false).setSkipCallLog(false).setSkipNotification(false)
            .build())
        val number = details.handle?.schemeSpecificPart ?: return
        Thread {
            try {
                CallLookup.find(this, number)?.let { CallLookup.notify(this, it) }
            } catch (e: Throwable) { }
        }.start()
    }
}

object CallLookup {
    private const val CH = "driver_calls"
    private const val NOTE_ID = 7301

    /** What the app knows about the driver of a vehicle. */
    class Found(
        val driver: String,
        val mobile: String,
        val vehicle: String,
        val size: String,
        val vendor: String,
        val place: String
    )

    /** The last 10 digits of a number, so +91, 0 and spaces do not matter. */
    fun digits(s: String): String = s.filter { it.isDigit() }.takeLast(10)

    /**
     * The driver for a calling number: the newest WhatsApp post with that mobile gives the vehicle and destination;
     * the vehicle list fills what the post does not say. Null when the number is not known.
     */
    fun find(c: Context, number: String): Found? {
        val key = digits(number)
        if (key.length < 10) return null
        val post = VehicleStore.getAll(c).filter { digits(it.mobile) == key }.maxByOrNull { it.time }
        val vehicle = VehicleStore.getVehicles(c).firstOrNull { digits(it.mobile) == key }
        if (post == null && vehicle == null) return null
        return Found(
            driver = post?.driver?.ifBlank { null } ?: vehicle?.driver.orEmpty(),
            mobile = number,
            vehicle = post?.vehicle?.ifBlank { null } ?: vehicle?.number.orEmpty(),
            size = post?.size?.ifBlank { null } ?: vehicle?.type.orEmpty(),
            vendor = post?.vendor?.ifBlank { null } ?: vehicle?.transporter.orEmpty(),
            place = post?.city?.ifBlank { null } ?: vehicle?.route.orEmpty()
        )
    }

    fun notify(c: Context, f: Found) {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH, "Driver calls", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val who = f.driver.ifBlank { f.mobile }
        val details = listOf(f.vehicle, f.size, f.vendor, f.place).filter { it.isNotBlank() }.joinToString("\n")
        val note = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_truck)
            .setContentTitle("Driver calling: $who")
            .setContentText(listOf(f.vehicle, f.place).filter { it.isNotBlank() }.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(c).notify(NOTE_ID, note) } catch (e: SecurityException) { }
    }
}

/**
 * Launcher entry "Allow driver names on calls": asks Android to make this app the call screening app, which is what
 * lets [CallLookupService] see incoming calls. Only one call screening app can be active at a time.
 */
class CallRoleActivity : Activity() {
    private val requestCode = 7302

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "This needs Android 10 or later.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            Toast.makeText(this, "This phone does not allow a call screening app.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            Toast.makeText(this, "Driver names on calls are already on.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), requestCode)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode) {
            val on = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
            Toast.makeText(this, if (on) "Driver names on calls: on" else "Driver names on calls: not on", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
