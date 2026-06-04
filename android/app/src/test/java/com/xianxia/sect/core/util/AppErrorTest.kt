package com.xianxia.sect.core.util

import org.junit.Assert.*
import org.junit.Test

class AppErrorTest {

    // ---- AppError.Domain.Production 子类 ----

    @Test
    fun production_slotBusy_code() {
        val error = AppError.Domain.Production.SlotBusy(slotIndex = 2)
        assertEquals("PROD_001", error.code)
        assertEquals("槽位正在工作中", error.message)
        assertEquals(2, error.slotIndex)
        assertNull(error.cause)
    }

    @Test
    fun production_insufficientMaterials_code() {
        val error = AppError.Domain.Production.InsufficientMaterials(
            missingMaterials = mapOf("灵石" to 100)
        )
        assertEquals("PROD_002", error.code)
        assertEquals(mapOf("灵石" to 100), error.missingMaterials)
    }

    @Test
    fun production_invalidSlot_code() {
        val error = AppError.Domain.Production.InvalidSlot(slotIndex = -1)
        assertEquals("PROD_003", error.code)
    }

    @Test
    fun production_recipeNotFound_code() {
        val error = AppError.Domain.Production.RecipeNotFound(recipeId = "r1")
        assertEquals("PROD_004", error.code)
        assertEquals("r1", error.recipeId)
    }

    @Test
    fun production_discipleNotAvailable_code() {
        val error = AppError.Domain.Production.DiscipleNotAvailable(discipleId = "d1")
        assertEquals("PROD_005", error.code)
    }

    @Test
    fun production_invalidStateTransition_code() {
        val error = AppError.Domain.Production.InvalidStateTransition(fromStatus = "idle", toStatus = "running")
        assertEquals("PROD_006", error.code)
    }

    @Test
    fun production_productionFailed_code() {
        val error = AppError.Domain.Production.ProductionFailed(recipeName = "炼丹")
        assertEquals("PROD_007", error.code)
    }

    @Test
    fun production_databaseError_code() {
        val error = AppError.Domain.Production.DatabaseError()
        assertEquals("PROD_008", error.code)
    }

    @Test
    fun production_unknown_code() {
        val error = AppError.Domain.Production.Unknown()
        assertEquals("PROD_099", error.code)
    }

    // ---- AppError.Domain.Storage 子类 ----

    @Test
    fun storage_slotNotFound_code() {
        val error = AppError.Domain.Storage.SlotNotFound()
        assertEquals("STORAGE_001", error.code)
    }

    @Test
    fun storage_slotCorrupted_code() {
        val error = AppError.Domain.Storage.SlotCorrupted()
        assertEquals("STORAGE_002", error.code)
    }

    @Test
    fun storage_saveFailed_code() {
        val error = AppError.Domain.Storage.SaveFailed()
        assertEquals("STORAGE_003", error.code)
    }

    @Test
    fun storage_loadFailed_code() {
        val error = AppError.Domain.Storage.LoadFailed()
        assertEquals("STORAGE_004", error.code)
    }

    @Test
    fun storage_deleteFailed_code() {
        val error = AppError.Domain.Storage.DeleteFailed()
        assertEquals("STORAGE_005", error.code)
    }

    @Test
    fun storage_backupFailed_code() {
        val error = AppError.Domain.Storage.BackupFailed()
        assertEquals("STORAGE_006", error.code)
    }

    @Test
    fun storage_restoreFailed_code() {
        val error = AppError.Domain.Storage.RestoreFailed()
        assertEquals("STORAGE_007", error.code)
    }

    @Test
    fun storage_encryptionError_code() {
        val error = AppError.Domain.Storage.EncryptionError()
        assertEquals("STORAGE_008", error.code)
    }

    @Test
    fun storage_decryptionError_code() {
        val error = AppError.Domain.Storage.DecryptionError()
        assertEquals("STORAGE_009", error.code)
    }

    @Test
    fun storage_ioError_code() {
        val error = AppError.Domain.Storage.IoError()
        assertEquals("STORAGE_010", error.code)
    }

    @Test
    fun storage_databaseError_code() {
        val error = AppError.Domain.Storage.DatabaseError()
        assertEquals("STORAGE_011", error.code)
    }

    @Test
    fun storage_transactionFailed_code() {
        val error = AppError.Domain.Storage.TransactionFailed()
        assertEquals("STORAGE_012", error.code)
    }

    @Test
    fun storage_timeout_code() {
        val error = AppError.Domain.Storage.Timeout()
        assertEquals("STORAGE_013", error.code)
    }

    @Test
    fun storage_checksumMismatch_code() {
        val error = AppError.Domain.Storage.ChecksumMismatch()
        assertEquals("STORAGE_014", error.code)
    }

    @Test
    fun storage_keyDerivationError_code() {
        val error = AppError.Domain.Storage.KeyDerivationError()
        assertEquals("STORAGE_015", error.code)
    }

    @Test
    fun storage_integrityError_code() {
        val error = AppError.Domain.Storage.IntegrityError()
        assertEquals("STORAGE_016", error.code)
    }

    @Test
    fun storage_verificationFailed_code() {
        val error = AppError.Domain.Storage.VerificationFailed()
        assertEquals("STORAGE_017", error.code)
    }

    @Test
    fun storage_expired_code() {
        val error = AppError.Domain.Storage.Expired()
        assertEquals("STORAGE_018", error.code)
    }

    @Test
    fun storage_tampered_code() {
        val error = AppError.Domain.Storage.Tampered()
        assertEquals("STORAGE_019", error.code)
    }

    @Test
    fun storage_unknown_code() {
        val error = AppError.Domain.Storage.Unknown()
        assertEquals("STORAGE_099", error.code)
    }

    // ---- AppError.Domain.Validation ----

    @Test
    fun validation_invalidInput_code() {
        val error = AppError.Domain.Validation.InvalidInput()
        assertEquals("VALID_001", error.code)
    }

    @Test
    fun validation_configError_code() {
        val error = AppError.Domain.Validation.ConfigError()
        assertEquals("VALID_002", error.code)
    }

    @Test
    fun validation_outOfRange_code() {
        val error = AppError.Domain.Validation.OutOfRange()
        assertEquals("VALID_003", error.code)
    }

    @Test
    fun validation_emptyValue_code() {
        val error = AppError.Domain.Validation.EmptyValue()
        assertEquals("VALID_004", error.code)
    }

    // ---- AppError.Domain.GameState ----

    @Test
    fun gameState_invalidState_code() {
        val error = AppError.Domain.GameState.InvalidState()
        assertEquals("GAME_001", error.code)
    }

    @Test
    fun gameState_notFound_code() {
        val error = AppError.Domain.GameState.NotFound()
        assertEquals("GAME_002", error.code)
    }

    @Test
    fun gameState_permissionDenied_code() {
        val error = AppError.Domain.GameState.PermissionDenied()
        assertEquals("GAME_003", error.code)
    }

    // ---- AppError.Domain.Network ----

    @Test
    fun network_noConnection_code() {
        val error = AppError.Domain.Network.NoConnection()
        assertEquals("NET_001", error.code)
    }

    @Test
    fun network_timeout_code() {
        val error = AppError.Domain.Network.Timeout()
        assertEquals("NET_002", error.code)
    }

    @Test
    fun network_unknown_code() {
        val error = AppError.Domain.Network.Unknown()
        assertEquals("NET_099", error.code)
    }

    // ---- AppError.Domain.GameLoop ----

    @Test
    fun gameLoop_tickTimeout_code() {
        val error = AppError.Domain.GameLoop.TickTimeout(elapsedMs = 500L)
        assertEquals("LOOP_001", error.code)
        assertTrue(error.message.contains("500"))
    }

    @Test
    fun gameLoop_stateInconsistency_code() {
        val error = AppError.Domain.GameLoop.StateInconsistency(detail = "数据不匹配")
        assertEquals("LOOP_002", error.code)
        assertTrue(error.message.contains("数据不匹配"))
    }

    @Test
    fun gameLoop_engineNotRunning_code() {
        val error = AppError.Domain.GameLoop.EngineNotRunning(operation = "save")
        assertEquals("LOOP_003", error.code)
        assertTrue(error.message.contains("save"))
    }

    @Test
    fun gameLoop_unknown_code() {
        val error = AppError.Domain.GameLoop.Unknown()
        assertEquals("LOOP_099", error.code)
    }

    // ---- AppError.Unknown ----

    @Test
    fun unknown_code() {
        val error = AppError.Unknown()
        assertEquals("UNKNOWN_ERROR", error.code)
        assertEquals("未知错误", error.message)
    }

    @Test
    fun unknown_customMessage() {
        val error = AppError.Unknown(message = "自定义错误")
        assertEquals("自定义错误", error.message)
    }

    // ---- toUiError ----

    @Test
    fun toUiError_convertsToUiError() {
        val appError = AppError.Domain.Storage.SaveFailed()
        val uiError = appError.toUiError()
        assertEquals("STORAGE_003", uiError.code)
        assertEquals(appError, uiError.appError)
    }

    // ---- fromException ----

    @Test
    fun fromException_unknownHost_returnsNoConnection() {
        val error = AppError.fromException(java.net.UnknownHostException("test"))
        assertTrue(error is AppError.Domain.Network.NoConnection)
    }

    @Test
    fun fromException_illegalArgument_returnsInvalidInput() {
        val error = AppError.fromException(IllegalArgumentException("bad arg"))
        assertTrue(error is AppError.Domain.Validation.InvalidInput)
        assertEquals("bad arg", error.message)
    }

    @Test
    fun fromException_illegalState_returnsStateInconsistency() {
        val error = AppError.fromException(IllegalStateException("bad state"))
        assertTrue(error is AppError.Domain.GameLoop.StateInconsistency)
    }

    @Test
    fun fromException_noSuchElement_returnsNotFound() {
        val error = AppError.fromException(NoSuchElementException("missing"))
        assertTrue(error is AppError.Domain.GameState.NotFound)
    }

    @Test
    fun fromException_security_returnsPermissionDenied() {
        val error = AppError.fromException(SecurityException("denied"))
        assertTrue(error is AppError.Domain.GameState.PermissionDenied)
    }

    @Test
    fun fromException_unknownException_returnsUnknown() {
        val error = AppError.fromException(RuntimeException("oops"))
        assertTrue(error is AppError.Unknown)
        assertEquals("oops", error.message)
    }

    @Test
    fun fromException_cancellationException_throws() {
        try {
            AppError.fromException(kotlinx.coroutines.CancellationException("cancel"))
            fail("Should have thrown CancellationException")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }

    // ---- cause property ----

    @Test
    fun error_withCause_preservesCause() {
        val cause = RuntimeException("root cause")
        val error = AppError.Domain.Storage.SaveFailed(cause = cause)
        assertSame(cause, error.cause)
    }

    // ---- custom message ----

    @Test
    fun storage_customMessage_overridesDefault() {
        val error = AppError.Domain.Storage.SaveFailed(message = "自定义保存失败")
        assertEquals("自定义保存失败", error.message)
    }
}
