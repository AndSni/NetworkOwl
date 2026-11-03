package com.netwatch.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    /** Bytes per second as bits per second, the unit speed tests and ISPs use. */
    fun rate(bytesPerSec: Double): String {
        val bits = bytesPerSec * 8
        return when {
            bits >= 1e6 -> String.format(Locale.US, "%.1f Mb/s", bits / 1e6)
            bits >= 1e3 -> String.format(Locale.US, "%.0f kb/s", bits / 1e3)
            bits >= 1 -> String.format(Locale.US, "%.0f b/s", bits)
            else -> "0"
        }
    }

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / 1e9)
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1e6)
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f kB", b / 1e3)
        else -> "$b B"
    }

    fun mbps(bytesPerSec: Double): Float = (bytesPerSec * 8 / 1e6).toFloat()

    fun time(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
    fun clock(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    fun dayClock(epochMs: Long): String = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(epochMs))

    fun duration(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> "<1 min"
            m < 60 -> "$m min"
            else -> "${m / 60} h ${m % 60} min"
        }
    }
}
