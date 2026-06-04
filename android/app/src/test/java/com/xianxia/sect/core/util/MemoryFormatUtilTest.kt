package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Test

class MemoryFormatUtilTest {

    @Test
    fun formatMemory_bytes() {
        assertEquals("0 B", MemoryFormatUtil.formatMemory(0))
        assertEquals("100 B", MemoryFormatUtil.formatMemory(100))
        assertEquals("1023 B", MemoryFormatUtil.formatMemory(1023))
    }

    @Test
    fun formatMemory_kilobytes() {
        assertEquals("1.0 KB", MemoryFormatUtil.formatMemory(1024))
        assertEquals("512.0 KB", MemoryFormatUtil.formatMemory(512 * 1024))
        assertEquals("1023.0 KB", MemoryFormatUtil.formatMemory(1023 * 1024))
    }

    @Test
    fun formatMemory_megabytes() {
        assertEquals("1.0 MB", MemoryFormatUtil.formatMemory(1024 * 1024))
        assertEquals("512.0 MB", MemoryFormatUtil.formatMemory(512L * 1024 * 1024))
        assertEquals("1023.0 MB", MemoryFormatUtil.formatMemory(1023L * 1024 * 1024))
    }

    @Test
    fun formatMemory_gigabytes() {
        assertEquals("1.00 GB", MemoryFormatUtil.formatMemory(1024L * 1024 * 1024))
        assertEquals("2.50 GB", MemoryFormatUtil.formatMemory((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatMemory_boundaryBetweenBytesAndKB() {
        assertEquals("1023 B", MemoryFormatUtil.formatMemory(1023))
        assertEquals("1.0 KB", MemoryFormatUtil.formatMemory(1024))
    }

    @Test
    fun formatMemory_boundaryBetweenKBAndMB() {
        assertEquals("1023.0 KB", MemoryFormatUtil.formatMemory(1023 * 1024))
        assertEquals("1.0 MB", MemoryFormatUtil.formatMemory(1024 * 1024))
    }

    @Test
    fun formatMemory_boundaryBetweenMBAndGB() {
        assertEquals("1023.0 MB", MemoryFormatUtil.formatMemory(1023L * 1024 * 1024))
        assertEquals("1.00 GB", MemoryFormatUtil.formatMemory(1024L * 1024 * 1024))
    }

    @Test
    fun formatMemory_largeValue() {
        val bytes = 10L * 1024 * 1024 * 1024 // 10 GB
        assertEquals("10.00 GB", MemoryFormatUtil.formatMemory(bytes))
    }
}
