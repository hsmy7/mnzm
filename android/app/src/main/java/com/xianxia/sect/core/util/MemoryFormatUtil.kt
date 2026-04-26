package com.xianxia.sect.core.util

import java.util.Locale

object MemoryFormatUtil {
    fun formatMemory(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
