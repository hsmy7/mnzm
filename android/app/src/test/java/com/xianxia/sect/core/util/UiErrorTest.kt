package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Test

class UiErrorTest {

    // ---- 构造 ----

    @Test
    fun construction_defaultValues() {
        val error = UiError(code = "TEST", userMessage = "test msg")
        assertEquals("TEST", error.code)
        assertEquals("test msg", error.userMessage)
        assertNull(error.appError)
        assertFalse(error.isRecoverable)
        assertEquals(UiErrorSeverity.ERROR, error.severity)
    }

    @Test
    fun construction_withAllParams() {
        val appError = AppError.Domain.Storage.SaveFailed()
        val error = UiError(
            code = "STORAGE_003",
            userMessage = "保存失败",
            appError = appError,
            isRecoverable = true,
            severity = UiErrorSeverity.WARNING
        )
        assertEquals(appError, error.appError)
        assertTrue(error.isRecoverable)
        assertEquals(UiErrorSeverity.WARNING, error.severity)
    }

    // ---- displayMessage ----

    @Test
    fun displayMessage_equalsUserMessage() {
        val error = UiError(code = "TEST", userMessage = "显示消息")
        assertEquals("显示消息", error.displayMessage)
    }

    // ---- fromAppError ----

    @Test
    fun fromAppError_storageSlotNotFound() {
        val appError = AppError.Domain.Storage.SlotNotFound()
        val uiError = UiError.fromAppError(appError)
        assertEquals("STORAGE_001", uiError.code)
        assertEquals("存档不存在", uiError.userMessage)
        assertEquals(UiErrorSeverity.INFO, uiError.severity)
        assertTrue(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_storageSlotCorrupted() {
        val uiError = UiError.fromAppError(AppError.Domain.Storage.SlotCorrupted())
        assertEquals("存档数据损坏，请尝试恢复备份", uiError.userMessage)
        assertEquals(UiErrorSeverity.ERROR, uiError.severity)
        assertTrue(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_storageSaveFailed() {
        val uiError = UiError.fromAppError(AppError.Domain.Storage.SaveFailed())
        assertEquals("保存失败，请重试", uiError.userMessage)
        assertEquals(UiErrorSeverity.WARNING, uiError.severity)
        assertTrue(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_storageLoadFailed() {
        val uiError = UiError.fromAppError(AppError.Domain.Storage.LoadFailed())
        assertEquals("加载失败，请重试", uiError.userMessage)
        assertTrue(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_storageEncryptionError() {
        val uiError = UiError.fromAppError(AppError.Domain.Storage.EncryptionError())
        assertEquals(UiErrorSeverity.ERROR, uiError.severity)
        assertFalse(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_storageDecryptionError() {
        val uiError = UiError.fromAppError(AppError.Domain.Storage.DecryptionError())
        assertFalse(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_storageRestoreFailed() {
        val uiError = UiError.fromAppError(AppError.Domain.Storage.RestoreFailed())
        assertEquals(UiErrorSeverity.ERROR, uiError.severity)
        assertFalse(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_storageTampered() {
        val uiError = UiError.fromAppError(AppError.Domain.Storage.Tampered())
        assertEquals("数据已被篡改", uiError.userMessage)
        assertFalse(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_networkNoConnection() {
        val uiError = UiError.fromAppError(AppError.Domain.Network.NoConnection())
        assertEquals("网络连接失败，请检查网络设置", uiError.userMessage)
        assertEquals(UiErrorSeverity.WARNING, uiError.severity)
        assertTrue(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_networkTimeout() {
        val uiError = UiError.fromAppError(AppError.Domain.Network.Timeout())
        assertEquals("网络请求超时，请稍后重试", uiError.userMessage)
        assertTrue(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_productionSlotBusy() {
        val uiError = UiError.fromAppError(AppError.Domain.Production.SlotBusy())
        assertEquals("槽位正在工作中", uiError.userMessage)
        assertEquals(UiErrorSeverity.INFO, uiError.severity)
        assertTrue(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_productionInsufficientMaterials() {
        val uiError = UiError.fromAppError(AppError.Domain.Production.InsufficientMaterials())
        assertEquals("材料不足", uiError.userMessage)
        assertFalse(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_validationInvalidInput() {
        val uiError = UiError.fromAppError(AppError.Domain.Validation.InvalidInput(message = "参数错误"))
        assertEquals("参数错误", uiError.userMessage)
        assertEquals(UiErrorSeverity.INFO, uiError.severity)
        assertTrue(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_gameStatePermissionDenied() {
        val uiError = UiError.fromAppError(AppError.Domain.GameState.PermissionDenied())
        assertEquals("权限不足", uiError.userMessage)
        assertFalse(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_gameLoopTickTimeout() {
        val uiError = UiError.fromAppError(AppError.Domain.GameLoop.TickTimeout(elapsedMs = 100L))
        assertEquals("游戏运行缓慢", uiError.userMessage)
        assertEquals(UiErrorSeverity.WARNING, uiError.severity)
        assertTrue(uiError.isRecoverable)
    }

    @Test
    fun fromAppError_unknown() {
        val uiError = UiError.fromAppError(AppError.Unknown(message = "某错误"))
        assertEquals("操作失败：某错误", uiError.userMessage)
        assertEquals(UiErrorSeverity.ERROR, uiError.severity)
        assertFalse(uiError.isRecoverable)
    }

    // ---- fromException ----

    @Test
    fun fromException_illegalArgument() {
        val uiError = UiError.fromException(IllegalArgumentException("bad"))
        assertEquals("bad", uiError.userMessage)
        assertEquals(UiErrorSeverity.INFO, uiError.severity)
    }

    // ---- UiErrorSeverity ----

    @Test
    fun uiErrorSeverity_hasThreeValues() {
        assertEquals(3, UiErrorSeverity.values().size)
        assertEquals(UiErrorSeverity.INFO, UiErrorSeverity.valueOf("INFO"))
        assertEquals(UiErrorSeverity.WARNING, UiErrorSeverity.valueOf("WARNING"))
        assertEquals(UiErrorSeverity.ERROR, UiErrorSeverity.valueOf("ERROR"))
    }

    @Test
    fun uiErrorSeverity_ordinalOrder() {
        assertTrue(UiErrorSeverity.INFO.ordinal < UiErrorSeverity.WARNING.ordinal)
        assertTrue(UiErrorSeverity.WARNING.ordinal < UiErrorSeverity.ERROR.ordinal)
    }

    // ---- appError reference ----

    @Test
    fun fromAppError_preservesAppErrorReference() {
        val appError = AppError.Domain.Storage.SaveFailed()
        val uiError = UiError.fromAppError(appError)
        assertSame(appError, uiError.appError)
    }
}
