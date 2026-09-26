package com.yourapp.USBooter.util

/** Shared helpers for the Apple filesystems (HFS+). */
object AppleFs {
    /** GPT GUID in on-disk mixed-endian order. */
    fun guidBytes(text: String): ByteArray {
        val u = java.util.UUID.fromString(text)
        val out = ByteArray(16)
        val msb = u.mostSignificantBits
        val lsb = u.leastSignificantBits
        val d1 = (msb ushr 32).toInt(); val d2 = ((msb ushr 16) and 0xFFFF).toInt(); val d3 = (msb and 0xFFFF).toInt()
        for (i in 0 until 4) out[i] = (d1 ushr (8 * i)).toByte()
        for (i in 0 until 2) out[4 + i] = (d2 ushr (8 * i)).toByte()
        for (i in 0 until 2) out[6 + i] = (d3 ushr (8 * i)).toByte()
        for (i in 0 until 8) out[8 + i] = (lsb ushr (8 * (7 - i))).toByte()
        return out
    }
}
