package com.logistics.vehiclereport

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import org.json.JSONObject

/**
 * Live link through the account's own Firebase (the app's cloud backend). The office Excel (App Data workbook) signs
 * in with the SAME email + password and writes the Drishti gate log plus Standing / In-transit into the document
 * users/{uid}/data/yard_push. This listens to that document and applies it the moment it changes - no ntfy, no
 * office-network workaround, only Google, which the DC network allows. Only this account can read it (firestore.rules).
 */
object FirebaseLink {
    const val DOC = "yard_push"
    private var reg: ListenerRegistration? = null
    private var appCtx: Context? = null
    private var lastAt = 0L

    fun available(c: Context) = Account.isSignedIn(c) && !Account.isLocal(c) && Account.cloudReady(c)

    fun start(c: Context) {
        stop()
        if (!available(c)) return
        val uid = Account.uid(c) ?: return
        appCtx = c.applicationContext
        reg = doc(uid).addSnapshotListener(MetadataChanges.INCLUDE) { snap, _ ->
            val json = snap?.getString("json") ?: return@addSnapshotListener
            apply(json)
        }
    }

    fun stop() {
        reg?.remove(); reg = null
    }

    /** One-off fetch (Yard screen "Pull now"). done(ok, errorOrNull). */
    fun pullOnce(c: Context, done: (Boolean, String?) -> Unit) {
        if (!available(c)) { done(false, "Sign in with your email and password first."); return }
        val uid = Account.uid(c) ?: run { done(false, "Not signed in."); return }
        appCtx = c.applicationContext
        doc(uid).get()
            .addOnSuccessListener { snap ->
                val json = snap.getString("json")
                if (json == null) done(false, null) else { apply(json); done(true, null) }
            }
            .addOnFailureListener { e -> done(false, e.message ?: e.toString()) }
    }

    private fun doc(uid: String) =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("data").document(DOC)

    private fun apply(json: String) {
        val c = appCtx ?: return
        Thread {
            try {
                val o = JSONObject(json)
                val read = LaptopLink.parse(o)
                if (read.at in 1..lastAt) return@Thread   // already applied this one
                lastAt = maxOf(lastAt, read.at)
                read.sheets?.let { YardStore.save(c, it, read.at) }
                if (read.status == DrishtiStore.OK && read.cells.isNotEmpty())
                    DrishtiStore.apply(c, read.cells, read.pageDay, fromDrishti = true, readAt = read.at)
            } catch (e: Throwable) { }
        }.start()
    }
}
