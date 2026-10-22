package com.logistics.vehiclereport

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Live reading: while the user has a WhatsApp chat open, reads the message bubbles on screen
 * (sender, text, time) as they scroll, and saves the vehicles found. Only runs inside WhatsApp.
 */
class WhatsAppScreenReader : AccessibilityService() {

    companion object {
        private const val TAG = "WhatsAppScreenReader"
        const val KEY_LAST_READ = "screen_last_read"
        const val KEY_LAST_COUNT = "screen_last_count"
        /** What the last look at WhatsApp found (shown on the Live tab so problems are visible). */
        const val KEY_LAST_SCAN = "screen_last_scan"

        /** Set while Android has the service running (it can be "on" in Settings but stopped). */
        @Volatile var running: WhatsAppScreenReader? = null

        /** Minutes after which live reading switches itself off (banking apps refuse to open while it is on). 0 = never. */
        const val KEY_AUTO_OFF = "screen_auto_off_min"
        const val DEFAULT_AUTO_OFF = 30

        /** Turns live reading off now (same as switching it off in Accessibility settings). */
        fun stop(): Boolean {
            val r = running ?: return false
            return try { r.disableSelf(); true } catch (e: Throwable) { false }
        }

        fun isEnabled(c: Context): Boolean {
            val me = ComponentName(c, WhatsAppScreenReader::class.java).flattenToString()
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }

        private val WA = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val NOT_A_TITLE = Regex(
            "^(online|typing…?|typing\\.\\.\\.|tap here.*|click here.*|last seen.*|.*\\bmembers?\\b.*|today|yesterday)$",
            RegexOption.IGNORE_CASE
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private var pending = false
    private var lastToast = 0L
    private var lastHint = 0L
    private var sessionNew = 0
    /** Sender of the last bubble read in each chat, for bubbles whose name is above the screen. */
    private val lastSender = HashMap<String, String>()

    private val readNow = Runnable {
        pending = false
        try { readScreen() } catch (e: Throwable) {
            Log.e(TAG, "read failed", e)
            note("Error: ${e.javaClass.simpleName}")
            try { CrashLog.save(this, "Live screen reading", e) } catch (t: Throwable) { }
        }
    }

    private val autoOff = Runnable { try { disableSelf() } catch (e: Throwable) { } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        val min = VehicleStore.prefs(this).getInt(KEY_AUTO_OFF, DEFAULT_AUTO_OFF)
        if (min > 0) {
            main.postDelayed(autoOff, min * 60_000L)
            Toast.makeText(this, getString(R.string.live_auto_off_toast, min), Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(autoOff)
        if (running === this) running = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        running = this
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in WA) return
        if (!pending) {            // read at most every ~0.8 s while scrolling
            pending = true
            main.postDelayed(readNow, 800)
        }
    }

    override fun onInterrupt() {}

    private fun note(text: String) {
        VehicleStore.prefs(this).edit()
            .putString(KEY_LAST_SCAN, "${java.text.SimpleDateFormat("hh:mm:ss a", java.util.Locale.getDefault()).format(java.util.Date())} · $text")
            .apply()
    }

    private fun hint(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastHint < 15_000) return
        lastHint = now
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /** WhatsApp's main window, even when the keyboard or a popup is the active one. */
    private fun whatsAppRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.takeIf { it.packageName?.toString() in WA }?.let { return it }
        return try {
            windows.orEmpty().mapNotNull { it.root }.firstOrNull { it.packageName?.toString() in WA }
        } catch (e: Exception) { null }
    }

    private fun readScreen() {
        val root = whatsAppRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
        fun byId(node: AccessibilityNodeInfo, vararg ids: String) =
            ids.asSequence().flatMap { node.findAccessibilityNodeInfosByViewId("$pkg:id/$it").orEmpty().asSequence() }.toList()

        val screen = Rect().also { root.getBoundsInScreen(it) }
        val chat = (byId(root, "conversation_contact_name", "conversation_contact_name_text").firstOrNull()?.text?.toString()
            ?: titleFallback(root, screen))?.trim()
        if (chat.isNullOrBlank()) {
            note("WhatsApp open – no chat open")
            return
        }

        val list = messageList(root, byId(root, "message_text").firstOrNull())
        if (list == null) {
            note("\"$chat\" – messages not found on screen")
            hint(getString(R.string.live_hint_no_messages))
            return
        }

        val messages = mutableListOf<IncomingMessage>()
        var day: Long? = null
        var sender: String? = lastSender[chat]
        var named = false
        val now = System.currentTimeMillis()

        for (i in 0 until list.childCount) {
            val row = list.getChild(i) ?: continue
            val texts = allTexts(row)
            if (texts.isEmpty()) continue

            val idBody = byId(row, "message_text").firstOrNull()?.text?.toString()
            // Date divider ("TODAY", "14 October 2026"): may be its own row or on top of a bubble.
            for (t in texts) {
                if (t == idBody) continue
                ScreenTime.dayStart(t, now)?.let { day = it }
            }

            val timeText = byId(row, "date").firstOrNull()?.text?.toString()
                ?: texts.lastOrNull { ScreenTime.isTime(it) }
                ?: continue
            val content = texts.filter { !ScreenTime.isTime(it) && ScreenTime.dayStart(it, now) == null }
            val body = idBody ?: content.maxByOrNull { it.length } ?: continue

            val idName = byId(row, "name_in_group_tv", "name_in_group").firstOrNull()?.text?.toString()?.trim()
            // Without view ids: in a group the first short line above the message is the sender's name.
            val name = idName ?: content.firstOrNull()?.takeIf {
                content.size >= 2 && it != body && it.length <= 40 && '\n' !in it
            }?.trim()
            val outgoing = byId(row, "status").isNotEmpty()
            sender = when {
                outgoing -> "You"
                !name.isNullOrBlank() -> { named = true; name }
                else -> sender // same sender as the bubble above
            }
            val time = ScreenTime.at(day, timeText, now) ?: continue
            messages += IncomingMessage(
                chat = chat, sender = sender ?: chat, text = body.trim(),
                time = time, exactTime = true, isGroup = named || sender != null
            )
        }
        sender?.let { lastSender[chat] = it }

        if (messages.isEmpty()) {
            note("\"$chat\" – no messages read")
            return
        }
        val added = VehicleStore.ingest(applicationContext, messages)
        note("\"$chat\" – ${messages.size} message(s) on screen, $added new vehicle(s)")
        if (added > 0) {
            sessionNew += added
            VehicleStore.prefs(this).edit()
                .putLong(KEY_LAST_READ, now)
                .putInt(KEY_LAST_COUNT, added)
                .apply()
            if (now - lastToast > 2500) {
                lastToast = now
                Toast.makeText(this, getString(R.string.live_toast_new, sessionNew), Toast.LENGTH_SHORT).show()
                sessionNew = 0
            }
        } else {
            val group = VehicleStore.getSelectedGroup(this)
            if (group.isNotBlank() && !VehicleStore.sameChat(chat, group)) {
                hint(getString(R.string.live_hint_other_chat, group))
            }
        }
    }

    /** Chat name without view ids: the first plain text in the top bar. */
    private fun titleFallback(root: AccessibilityNodeInfo, screen: Rect): String? {
        val limit = screen.top + screen.height() / 7
        val found = mutableListOf<Pair<Rect, String>>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.top > limit) return
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && r.bottom <= limit && !NOT_A_TITLE.matches(t) && !ScreenTime.isTime(t) &&
                !t.contains(",")) found += r to t
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return found.minWithOrNull(compareBy({ it.first.top }, { it.first.left }))?.second
    }

    /** The scrolling list of bubbles: the parent list of a message, or else the biggest scrolling view. */
    private fun messageList(root: AccessibilityNodeInfo, aMessage: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = aMessage?.parent
        while (n != null) {
            val cls = n.className?.toString().orEmpty()
            if (cls.contains("RecyclerView") || cls.contains("ListView") || n.isScrollable) return n
            n = n.parent
        }
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        fun walk(x: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25) return
            if (x.isScrollable && x.childCount > 0) {
                val r = Rect().also { x.getBoundsInScreen(it) }
                val area = r.width() * r.height()
                if (area > bestArea) { bestArea = area; best = x }
            }
            for (i in 0 until x.childCount) x.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return best
    }

    private fun allTexts(node: AccessibilityNodeInfo, out: MutableList<String> = mutableListOf(), depth: Int = 0): List<String> {
        if (depth > 12) return out
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        for (i in 0 until node.childCount) node.getChild(i)?.let { allTexts(it, out, depth + 1) }
        return out
    }
}
