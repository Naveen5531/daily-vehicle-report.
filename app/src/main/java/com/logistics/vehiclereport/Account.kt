package com.logistics.vehiclereport

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import java.security.MessageDigest

/**
 * Who is signed in on this phone. Every account has its own data: its own storage file on the phone and its own
 * copy in the cloud (Firebase, readable only by that account). Nothing of one account is visible to another.
 */
object Account {
    private const val DEVICE = "device"
    private const val K_UID = "uid"
    private const val K_EMAIL = "email"
    private const val K_NAME = "name"
    /** Data saved by versions without accounts; moved into the first account that signs in on this phone. */
    private const val LEGACY = "vehicle_cache"
    const val LOCAL_UID = "local"
    const val K_DC_NAME = "dc_name"
    const val K_DC_ASKED = "dc_asked"

    private fun device(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(DEVICE, Context.MODE_PRIVATE)

    /** True when this build has the Firebase settings (google-services.json), i.e. online accounts work. */
    fun cloudReady(c: Context): Boolean = try {
        FirebaseApp.getApps(c).isNotEmpty() || FirebaseApp.initializeApp(c.applicationContext) != null
    } catch (e: Exception) { false }

    fun uid(c: Context): String? = device(c).getString(K_UID, null)
    fun isSignedIn(c: Context) = uid(c) != null
    fun isLocal(c: Context) = uid(c) == LOCAL_UID
    fun email(c: Context): String = device(c).getString(K_EMAIL, "").orEmpty()
    fun name(c: Context): String = device(c).getString(K_NAME, "").orEmpty()

    /** Storage file of the signed-in account ("acct_none" when nobody is signed in: always empty). */
    fun storeName(c: Context): String = uid(c)?.let { "acct_" + hash(it) } ?: "acct_none"

    /** After a successful sign-in: remember the account, move old data in, load its settings. */
    fun signedIn(c: Context, uid: String, email: String, name: String) {
        device(c).edit().putString(K_UID, uid).putString(K_EMAIL, email).putString(K_NAME, name).commit()
        resetCaches()
        moveLegacyData(c)
        if (uid != LOCAL_UID) moveLocalData(c)
        loadSettings(c)
        Thread { try { VehicleStore.upgradeOldRecords(c.applicationContext) } catch (e: Throwable) { } }.start()
        CloudSync.start(c)
    }

    /** Signs out on this phone. The account's data stays in its cloud copy (and on this phone for next time). */
    fun signOut(c: Context) {
        CloudSync.flushNow(c)
        CloudSync.stop()
        try { if (cloudReady(c)) FirebaseAuth.getInstance().signOut() } catch (e: Exception) { }
        device(c).edit().remove(K_UID).remove(K_EMAIL).remove(K_NAME).commit()
        resetCaches()
        Masters.dispatchName = ""
    }

    /** Removes this account's data from the phone (used when the account is deleted). */
    fun wipeLocal(c: Context) {
        VehicleStore.prefs(c).edit().clear().commit()
        resetCaches()
    }

    /** Settings kept per account that other parts of the app read without a Context. */
    fun loadSettings(c: Context) {
        Masters.dispatchName = VehicleStore.prefs(c).getString(K_DC_NAME, "").orEmpty()
    }

    fun setDcName(c: Context, name: String) {
        VehicleStore.prefs(c).edit().putString(K_DC_NAME, name.trim()).putBoolean(K_DC_ASKED, true).apply()
        Masters.dispatchName = name.trim()
    }

    fun resetCaches() {
        MasterData.reset()
        RateCard.reset()
    }

    private fun moveLegacyData(c: Context) =
        moveFrom(c, c.applicationContext.getSharedPreferences(LEGACY, Context.MODE_PRIVATE))

    /**
     * Data kept with "use this phone only" (builds without online accounts) moves into the first online account
     * that signs in on this phone, and from there to its cloud copy.
     */
    private fun moveLocalData(c: Context) =
        moveFrom(c, c.applicationContext.getSharedPreferences("acct_" + hash(LOCAL_UID), Context.MODE_PRIVATE))

    private fun moveFrom(c: Context, legacy: SharedPreferences) {
        val old = legacy.all
        if (old.isEmpty()) return
        val target = VehicleStore.prefs(c)
        if (target.all.isEmpty()) {
            val e = target.edit()
            for ((k, v) in old) {
                @Suppress("UNCHECKED_CAST")
                when (v) {
                    is String -> e.putString(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Set<*> -> e.putStringSet(k, v as Set<String>)
                }
            }
            if (e.commit()) legacy.edit().clear().commit()
        }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
