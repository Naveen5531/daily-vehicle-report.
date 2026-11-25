package com.logistics.vehiclereport

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Live link through the account's own Firebase (the app's cloud backend). The office Excel (App Data workbook) signs
 * in with the SAME email + password and writes the Drishti gate log plus Standing / In-transit into the document
 * users/{uid}/data/yard_push. This listens to that document and applies it the moment it changes - no ntfy, no
 * office-network workaround, only Google, which the DC network allows. Only this account can read it (firestore.rules).
 *
 * Hardened: listener errors are shown and retried, every update is applied in order (none dropped), and
 * "Pull now" reports only after the data is really saved.
 */
object FirebaseLink {
    const val DOC = "yard_push"
    const val NOT_CONNECTED = "Cloud is not connected on this phone. Sign out, then sign in again with your email and password."

    private var reg: ListenerRegistration? = null
    private var appCtx: Context? = null
    @Volatile private var lastJson: String = ""
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val retry = Runnable { appCtx?.let { start(it) } }

    fun available(c: Context) = Account.isSignedIn(c) && !Account.isLocal(c) && Account.cloudReady(c)

    /** The Firebase sign-in is still active on this phone. Without it the cloud refuses every read and write. */
    private fun firebaseSignedIn(): Boolean = try {
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
    } catch (e: Throwable) { false }

    fun start(c: Context) {
        stop()
        if (!available(c)) return
        if (!firebaseSignedIn()) return
        val uid = Account.uid(c) ?: return
        val app = c.applicationContext
        appCtx = app
        reg = doc(uid).addSnapshotListener { snap, err ->
            if (err != null) { onListenError(app, err); return@addSnapshotListener }
            if (snap == null || snap.metadata.hasPendingWrites()) return@addSnapshotListener
            val json = snap.getString("json") ?: return@addSnapshotListener
            apply(app, json, null)
        }
    }

    fun stop() {
        main.removeCallbacks(retry)
        reg?.remove(); reg = null
        lastJson = ""
    }

    private fun onListenError(c: Context, e: Exception) {
        stop()
        appCtx = c
        val denied = (e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
        if (denied) main.post { Toast.makeText(c, NOT_CONNECTED, Toast.LENGTH_LONG).show() }
        main.postDelayed(retry, 20_000)   // try again in 20 seconds
    }

    /** One-off fetch (Yard screen "Pull now"). done(ok, errorOrNull) is called on the main thread after saving. */
    fun pullOnce(c: Context, done: (Boolean, String?) -> Unit) {
        if (!available(c)) { done(false, "Sign in with your email and password first."); return }
        if (!firebaseSignedIn()) { done(false, NOT_CONNECTED); return }
        val uid = Account.uid(c) ?: run { done(false, "Not signed in."); return }
        val app = c.applicationContext
        appCtx = app
        doc(uid).get()
            .addOnSuccessListener { snap ->
                val json = snap.getString("json")
                if (json == null) done(false, "Nothing from the office Excel in the cloud yet.")
                else apply(app, json, done)
            }
            .addOnFailureListener { e ->
                val denied = (e as? FirebaseFirestoreException)?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                done(false, if (denied) NOT_CONNECTED else (e.message ?: e.toString()))
            }
    }

    private fun doc(uid: String) =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("data").document(DOC)

    /** Applies one payload on a single worker thread, in order. Nothing is dropped; a failure is reported, not hidden. */
    private fun apply(c: Context, json: String, done: ((Boolean, String?) -> Unit)?) {
        worker.execute {
            var ok = false
            var problem: String? = null
            try {
                if (json == lastJson) {
                    ok = true
                } else {
                    val read = LaptopLink.parse(JSONObject(json))
                    if (read.sheets == null) problem = "The cloud data has no Standing / In-transit tables."
                    read.sheets?.let { YardStore.save(c, it, read.at) }
                    if (read.status == DrishtiStore.OK && read.cells.isNotEmpty())
                        DrishtiStore.apply(c, read.cells, read.pageDay, fromDrishti = true, readAt = read.at)
                    // In-transit says it left this DC today -> Departed on Today (after the gate log, which wins).
                    if (read.sheets != null) TransitMarks.apply(c)
                    if (read.sheets != null) { lastJson = json; ok = true }
                }
            } catch (e: Throwable) {
                problem = "Couldn't read the data from the office Excel: " + (e.message ?: e.toString())
                try { CrashLog.save(c, "Yard cloud data", e) } catch (t: Throwable) { }
            }
            done?.let { cb -> val p = problem; val k = ok; main.post { cb(k, p) } }
        }
    }
}
