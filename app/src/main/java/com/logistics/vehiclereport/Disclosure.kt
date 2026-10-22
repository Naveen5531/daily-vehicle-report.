package com.logistics.vehiclereport

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.app.Activity

/**
 * Google Play "prominent disclosure": before the user is sent to Android settings to allow notification reading
 * or the accessibility live reading, the app says in its own screen what is read, why, and where it is kept,
 * and asks for an explicit "Agree". Each permission has its own, separate disclosure.
 */
object Disclosure {

    fun notifications(a: Activity, onAgree: () -> Unit) = show(a, R.string.disclose_notif_title, R.string.disclose_notif_text, onAgree)

    fun accessibility(a: Activity, onAgree: () -> Unit) = show(a, R.string.disclose_access_title, R.string.disclose_access_text, onAgree)

    private fun show(a: Activity, title: Int, text: Int, onAgree: () -> Unit) {
        MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton(R.string.disclose_agree) { _, _ -> onAgree() }
            .setNegativeButton(R.string.disclose_decline, null)
            .show()
    }
}
