package com.logistics.vehiclereport

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Reads incoming WhatsApp / WhatsApp Business notifications and saves any
 * Indian vehicle numbers (TS09UB1234, MH 12 AB 1234, ...) found in them.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WhatsAppVehicleListener"
        /** Set while Android has the listener connected. */
        @Volatile var instance: WhatsAppNotificationListener? = null
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        /** "Transport Group (3 messages)" -> "Transport Group" */
        private val COUNT_SUFFIX = Regex("\\s*\\(\\d+\\s+(new\\s+)?messages?\\)\\s*$", RegexOption.IGNORE_CASE)
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    /** Reads every WhatsApp message still in the notification bar. Returns how many vehicles were added. */
    fun pullActive(): Pair<Int, Int> {
        val list = try { activeNotifications.orEmpty() } catch (e: Exception) { emptyArray<StatusBarNotification>() }
        val messages = list.filter {
            it.packageName in WHATSAPP_PACKAGES && (it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0)
        }.flatMap { try { readMessages(it, it.notification) } catch (e: Exception) { emptyList() } }
        return messages.size to VehicleStore.ingest(applicationContext, messages)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName !in WHATSAPP_PACKAGES) return
        val notification = sbn.notification ?: return
        // Skip the "5 messages from 2 chats" summary; the individual chat notifications carry the text.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        try {
            val messages = readMessages(sbn, notification)
            val added = VehicleStore.ingest(applicationContext, messages)
            if (added > 0) Log.i(TAG, "Captured $added vehicle number(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading WhatsApp notification", e)
        }
    }

    private fun readMessages(sbn: StatusBarNotification, n: Notification): List<IncomingMessage> {
        val extras = n.extras ?: return emptyList()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()

        // Modern WhatsApp: MessagingStyle with sender + timestamp per message.
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style != null && style.messages.isNotEmpty()) {
            val chat = cleanChat(style.conversationTitle?.toString() ?: title)
            val me = style.user.name?.toString() ?: "You"
            val group = style.isGroupConversation
            return style.messages.mapNotNull { m ->
                val text = m.text?.toString() ?: return@mapNotNull null
                IncomingMessage(
                    chat = chat,
                    sender = m.person?.name?.toString() ?: me,
                    text = text,
                    time = if (m.timestamp > 0) m.timestamp else sbn.postTime,
                    exactTime = m.timestamp > 0,
                    isGroup = group
                )
            }
        }

        // Older format: title "Group: Sender" (or just the contact name), text = message.
        // Long posts (several vehicles) come complete in the big text; the short text may be cut.
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString() ?: return emptyList()
        var chat = title.ifEmpty { "WhatsApp" }
        var sender = chat
        var isGroup = false
        val idx = title.indexOf(": ")
        if (idx > 0) {
            chat = title.substring(0, idx)
            sender = title.substring(idx + 2)
            isGroup = true
        }
        return listOf(IncomingMessage(cleanChat(chat), sender, text, sbn.postTime, exactTime = false, isGroup = isGroup))
    }

    private fun cleanChat(raw: String): String =
        raw.replace(COUNT_SUFFIX, "").trim().ifEmpty { "WhatsApp" }
}
