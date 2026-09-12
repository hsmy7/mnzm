package com.xianxia.sect.core.domain.favor

import org.junit.Assert.*
import org.junit.Test

class GiftServiceTest {

    @Test
    fun `GiftResult - 成功结果数据类正确初始化`() {
        val result = GiftResult(
            success = true,
            rejected = false,
            favorChange = 10,
            newFavor = 60,
            message = "接受成功",
            responseType = "accept"
        )
        assertTrue(result.success)
        assertFalse(result.rejected)
        assertEquals(10, result.favorChange)
        assertEquals(60, result.newFavor)
        assertEquals("接受成功", result.message)
        assertEquals("accept", result.responseType)
    }

    @Test
    fun `GiftResult - 拒绝结果数据类正确初始化`() {
        val result = GiftResult(
            success = false,
            rejected = true,
            favorChange = 0,
            newFavor = 0,
            message = "被拒绝了",
            responseType = "rejected"
        )
        assertFalse(result.success)
        assertTrue(result.rejected)
        assertEquals("被拒绝了", result.message)
    }

    @Test
    fun `GiftResult - 默认参数`() {
        val result = GiftResult(success = true)
        assertTrue(result.success)
        assertFalse(result.rejected)
        assertEquals(0, result.favorChange)
        assertEquals(0, result.newFavor)
        assertEquals("", result.message)
        assertEquals("", result.responseType)
    }
}
