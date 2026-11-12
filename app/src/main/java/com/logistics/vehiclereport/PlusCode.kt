package com.logistics.vehiclereport

import kotlin.math.floor

/**
 * Google Plus Codes (Open Location Code): "7J4VF2PX+FP8" or short "F2PX+FP8" (with a nearby place) → lat, long.
 * Google Maps share links for a place often carry only this code and the address, not the coordinates.
 */
object PlusCode {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val SEP = 8

    /** A plus code in [text]: full ("7J4VF2PX+FP8") or short ("F2PX+FP8"); null if none. */
    fun find(text: String): String? =
        Regex("(?i)(?<![0-9A-Z])([23456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]{2,3})(?![0-9A-Z])").find(text)?.groupValues?.get(1)?.uppercase()

    fun isFull(code: String) = code.indexOf('+') == SEP

    /** Centre of a full code. */
    fun decode(code: String): LatLon? {
        val c = code.uppercase().replace("+", "").trimEnd('0')
        if (c.length < 8 || c.any { it !in ALPHABET }) return null
        var lat = -90.0; var lng = -180.0
        var pv = 20.0; var size = 20.0
        var i = 0
        while (i < minOf(c.length, 10) - 1) {
            lat += ALPHABET.indexOf(c[i]) * pv
            lng += ALPHABET.indexOf(c[i + 1]) * pv
            size = pv; pv /= 20.0; i += 2
        }
        var latSize = size; var lngSize = size
        while (i < c.length) {
            val d = ALPHABET.indexOf(c[i])
            latSize /= 5.0; lngSize /= 4.0
            lat += (d / 4) * latSize; lng += (d % 4) * lngSize
            i++
        }
        return LatLon(lat + latSize / 2, lng + lngSize / 2)
    }

    /** First 10 digits of the code of a place (for recovering a short code). */
    private fun encode(lat0: Double, lng0: Double): String {
        var lat = (lat0.coerceIn(-90.0, 89.999999)) + 90.0
        var lng = ((lng0 + 180.0) % 360.0 + 360.0) % 360.0
        val sb = StringBuilder()
        var pv = 20.0
        repeat(5) {
            val a = floor(lat / pv).toInt().coerceIn(0, 19); lat -= a * pv
            val b = floor(lng / pv).toInt().coerceIn(0, 19); lng -= b * pv
            sb.append(ALPHABET[a]).append(ALPHABET[b]); pv /= 20.0
        }
        return sb.insert(SEP, '+').toString()
    }

    /** A short code made full with a place near it (within ~40 km, e.g. its town). */
    fun recover(short: String, near: LatLon): LatLon? {
        if (isFull(short)) return decode(short)
        val pad = SEP - short.indexOf('+')
        if (pad <= 0 || pad % 2 != 0) return null
        val res = Math.pow(20.0, 2.0 - pad / 2)
        val half = res / 2
        val area = decode(encode(near.lat, near.lon).substring(0, pad) + short) ?: return null
        var lat = area.lat; var lng = area.lon
        if (near.lat + half < lat && lat - res >= -90) lat -= res else if (near.lat - half > lat && lat + res <= 90) lat += res
        if (near.lon + half < lng) lng -= res else if (near.lon - half > lng) lng += res
        return LatLon(lat, lng)
    }
}
