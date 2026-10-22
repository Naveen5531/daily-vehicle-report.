package com.logistics.vehiclereport

import java.util.Calendar

/**
 * Reads a WhatsApp "Export chat" text file.
 *
 * Android:  14/10/2026, 6:31 am - Ravi: 1234 Hyderabad
 *           14/10/26, 18:31 - +91 98765 43210: TS09UB1234 Vijayawada
 * iPhone:   [14/10/26, 6:31:05 AM] Ravi: 1234 Hyderabad
 * Lines without a date belong to the previous message.
 */
object WhatsAppChatParser {

    private const val DATE = "(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})"
    private const val TIME = "(\\d{1,2})[:.](\\d{2})(?:[:.](\\d{2}))?\\s*([aApP]\\.?\\s?[mM]\\.?)?"
    private val ANDROID = Regex("^$DATE,?\\s+$TIME\\s+[-–]\\s+(.*)$")
    private val IOS = Regex("^\\[$DATE,?\\s+$TIME\\]\\s+(.*)$")

    private val SKIP = listOf("<media omitted>", "this message was deleted", "you deleted this message", "null")

    /** Chat name from the export's title or file name, e.g. "WhatsApp Chat with Transport Dedicated.txt". */
    fun chatNameFrom(title: String?): String? {
        if (title.isNullOrBlank()) return null
        val t = title.substringAfterLast('/').removeSuffix(".txt").removeSuffix(".zip").trim()
        val m = Regex("(?i)whatsapp\\s+chat\\s*(?:with|-|–)\\s*(.+)").find(t) ?: return null
        return m.groupValues[1].trim().ifEmpty { null }
    }

    fun parse(raw: String, chat: String): List<IncomingMessage> {
        val text = raw.removePrefix("﻿").replace(" ", " ").replace(" ", " ")
            .replace("‎", "").replace("‏", "").replace("\r", "")
        val lines = text.split('\n')

        // Day/month (India) or month/day (US)?
        var monthFirst = false
        for (l in lines) {
            val m = ANDROID.find(l) ?: IOS.find(l) ?: continue
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            if (a > 12) { monthFirst = false; break }
            if (b > 12) { monthFirst = true; break }
        }

        class Draft(val time: Long, val sender: String, val body: StringBuilder)
        val drafts = mutableListOf<Draft>()
        var current: Draft? = null

        for (line in lines) {
            val m = ANDROID.find(line) ?: IOS.find(line)
            if (m == null) {
                current?.body?.append('\n')?.append(line)
                continue
            }
            val g = m.groupValues
            val rest = g[8]
            val colon = rest.indexOf(": ")
            val time = toMillis(g, monthFirst)
            if (colon <= 0 || time == null) { current = null; continue } // system line, e.g. "Ravi joined"
            val d = Draft(time, rest.substring(0, colon).trim(), StringBuilder(rest.substring(colon + 2)))
            drafts += d
            current = d
        }

        return drafts.mapNotNull { d ->
            val body = d.body.toString().trim()
            if (body.isEmpty() || SKIP.any { body.equals(it, ignoreCase = true) }) null
            else IncomingMessage(chat = chat, sender = d.sender, text = body, time = d.time, exactTime = true, isGroup = true)
        }
    }

    private fun toMillis(g: List<String>, monthFirst: Boolean): Long? {
        val a = g[1].toInt(); val b = g[2].toInt()
        var year = g[3].toInt(); if (year < 100) year += 2000
        val day = if (monthFirst) b else a
        val month = if (monthFirst) a else b
        if (month !in 1..12 || day !in 1..31) return null
        var hour = g[4].toInt()
        val minute = g[5].toInt()
        val second = g[6].toIntOrNull() ?: 0
        val ampm = g[7].lowercase().filter { it == 'a' || it == 'p' }
        if (ampm == "p" && hour < 12) hour += 12
        if (ampm == "a" && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
        }.timeInMillis
    }
}
