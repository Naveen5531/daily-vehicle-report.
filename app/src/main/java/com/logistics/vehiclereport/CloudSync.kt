package com.logistics.vehiclereport

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Keeps a copy of the signed-in account's data in the cloud (Firestore: users/{uid}/data/{setting}),
 * readable and writable only by that account (see firestore.rules). The phone stays the working copy:
 * changes are sent a little later in one go; on a new phone the copy is brought back after sign-in.
 * Large values (the message log) are stored in several parts.
 */
object CloudSync {
    /** Characters per part: keeps every document well under Firestore's 1 MB limit even for non-English text. */
    private const val PART = 250_000
    private const val DELAY_MS = 90_000L

    private val main = Handler(Looper.getMainLooper())
    private val dirty = HashSet<String>()
    private var watched: SharedPreferences? = null
    private var appContext: Context? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        synchronized(dirty) { dirty += key }
        main.removeCallbacks(flush)
        main.postDelayed(flush, DELAY_MS)
    }

    private val flush = Runnable { appContext?.let { flushNow(it) } }

    private fun enabled(c: Context) = Account.isSignedIn(c) && !Account.isLocal(c) && Account.cloudReady(c)

    private fun data(uid: String) = FirebaseFirestore.getInstance().collection("users").document(uid).collection("data")

    /** Starts watching the signed-in account's data for changes. */
    fun start(c: Context) {
        stop()
        if (!enabled(c)) return
        appContext = c.applicationContext
        watched = VehicleStore.prefs(c).also { it.registerOnSharedPreferenceChangeListener(listener) }
    }

    fun stop() {
        watched?.unregisterOnSharedPreferenceChangeListener(listener)
        watched = null
        main.removeCallbacks(flush)
    }

    /** Sends the changed settings now (also called when the app goes to the background). */
    fun flushNow(c: Context) {
        main.removeCallbacks(flush)
        if (!enabled(c)) return
        val keys = synchronized(dirty) { dirty.toList().also { dirty.clear() } }
        if (keys.isEmpty()) return
        val uid = Account.uid(c) ?: return
        val all = VehicleStore.prefs(c).all
        for (k in keys) upload(uid, k, all[k])
    }

    /** Sends everything (first sign-in with data already on the phone). */
    fun uploadAll(c: Context) {
        if (!enabled(c)) return
        val uid = Account.uid(c) ?: return
        for ((k, v) in VehicleStore.prefs(c).all) upload(uid, k, v)
    }

    private fun upload(uid: String, key: String, value: Any?) {
        val col = data(uid)
        if (value == null) { col.document(key).delete(); return }
        val (type, text) = when (value) {
            is String -> "s" to value
            is Int -> "i" to value.toString()
            is Long -> "l" to value.toString()
            is Float -> "f" to value.toString()
            is Boolean -> "b" to value.toString()
            is Set<*> -> "set" to org.json.JSONArray(value.map { it.toString() }).toString()
            else -> return
        }
        val parts = text.chunked(PART).ifEmpty { listOf("") }
        val batch = FirebaseFirestore.getInstance().batch()
        batch.set(col.document(key), mapOf("t" to type, "v" to parts[0], "parts" to parts.size, "at" to FieldValue.serverTimestamp()))
        for (i in 1 until parts.size) batch.set(col.document("${key}__p$i"), mapOf("v" to parts[i]))
        batch.commit()
    }

    /**
     * Brings the cloud copy to this phone when the account has no data here yet; when the phone already has data
     * (e.g. moved from the version without accounts) that data is sent up instead. [done] runs on the main thread.
     */
    fun restoreOrUpload(c: Context, done: (restored: Boolean) -> Unit) {
        if (!enabled(c)) { done(false); return }
        val uid = Account.uid(c) ?: run { done(false); return }
        val prefs = VehicleStore.prefs(c)
        if (prefs.all.isNotEmpty()) { uploadAll(c); done(false); return }
        data(uid).get().addOnSuccessListener { snap ->
            val docs = snap.documents.associateBy { it.id }
            val e = prefs.edit()
            var n = 0
            for ((id, d) in docs) {
                if (id.contains("__p")) continue
                val parts = (d.getLong("parts") ?: 1L).toInt()
                val text = buildString {
                    append(d.getString("v").orEmpty())
                    for (i in 1 until parts) append(docs["${id}__p$i"]?.getString("v").orEmpty())
                }
                try {
                    when (d.getString("t")) {
                        "s" -> e.putString(id, text)
                        "i" -> e.putInt(id, text.toInt())
                        "l" -> e.putLong(id, text.toLong())
                        "f" -> e.putFloat(id, text.toFloat())
                        "b" -> e.putBoolean(id, text.toBoolean())
                        "set" -> e.putStringSet(id, org.json.JSONArray(text).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                        else -> continue
                    }
                    n++
                } catch (x: Exception) { }
            }
            e.commit()
            Account.resetCaches()
            Account.loadSettings(c)
            done(n > 0)
        }.addOnFailureListener { done(false) }
    }

    /** Deletes the account's cloud copy (account deletion). [done] gets true when everything was removed. */
    fun deleteAll(c: Context, done: (Boolean) -> Unit) {
        if (!enabled(c)) { done(true); return }
        val uid = Account.uid(c) ?: run { done(true); return }
        stop()
        data(uid).get().addOnSuccessListener { snap ->
            val batches = snap.documents.chunked(400)
            if (batches.isEmpty()) { done(true); return@addOnSuccessListener }
            var left = batches.size
            var ok = true
            for (chunk in batches) {
                val b = FirebaseFirestore.getInstance().batch()
                chunk.forEach { b.delete(it.reference) }
                b.commit().addOnCompleteListener { t ->
                    if (!t.isSuccessful) ok = false
                    if (--left == 0) done(ok)
                }
            }
        }.addOnFailureListener { done(false) }
    }
}
