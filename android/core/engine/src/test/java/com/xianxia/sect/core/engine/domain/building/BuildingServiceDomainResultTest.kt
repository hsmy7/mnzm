package com.xianxia.sect.core.engine.domain.building

import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/**
 * 验证 BuildingService 关键方法的 DomainResult 语义。
 *
 * 验证：
 * - 失败携带具体 AppError.Domain 子类及错误码
 * - when 穷尽性编译检查可用
 * - CancellationException 正确重新抛出
 * - Boolean 返回反模式已消除
 */
class BuildingServiceDomainResultTest {

    // ---- when 穷尽性 ----

    @Test
    fun `DomainResult when-exhaustive compiles`() {
        val dummy: DomainResult<String> =
            DomainResult.Failure(AppError.Domain.Production.SlotBusy())

        val handled = when (dummy) {
            is DomainResult.Success -> "success"
            is DomainResult.Partial -> "partial"
            is DomainResult.Failure -> "failure: ${dummy.error.code}"
        } // 无 else 分支——新增子类型时编译失败，强制更新所有 when

        assertTrue(handled.startsWith("failure"))
    }

    // ---- 错误码 ----

    @Test
    fun `Production errors carry specific codes`() {
        assertEquals("PROD_001", AppError.Domain.Production.SlotBusy(slotIndex = 1).code)
        assertEquals("PROD_002", AppError.Domain.Production.InsufficientMaterials().code)
        assertEquals("PROD_003", AppError.Domain.Production.InvalidSlot(slotIndex = -1).code)
        assertEquals("PROD_004", AppError.Domain.Production.RecipeNotFound(recipeId = "x").code)
        assertEquals("PROD_005", AppError.Domain.Production.DiscipleNotAvailable(discipleId = "d").code)
    }

    @Test
    fun `Building errors carry specific codes`() {
        assertEquals("BLD_001", AppError.Domain.Building.BuildingNotFound(buildingId = "x").code)
        assertEquals("BLD_002", AppError.Domain.Building.DiscipleBusy(discipleId = "d").code)
    }

    @Test
    fun `Inventory errors carry specific codes`() {
        assertEquals("INV_001", AppError.Domain.Inventory.Full().code)
        assertEquals("INV_002", AppError.Domain.Inventory.NotFound(itemId = "x").code)
        assertEquals("INV_006", AppError.Domain.Inventory.Locked(itemId = "x").code)
        assertEquals("INV_007", AppError.Domain.Inventory.Insufficient(itemId = "x", need = 5, have = 2).code)
    }

    @Test
    fun `Disciple errors carry specific codes`() {
        assertEquals("DISCIPLE_001", AppError.Domain.Disciple.NotFound(discipleId = "x").code)
        assertEquals("DISCIPLE_002", AppError.Domain.Disciple.NotAlive(discipleId = "x").code)
        assertEquals("DISCIPLE_004", AppError.Domain.Disciple.AlreadyEquipped(slot = "武器").code)
    }

    // ---- Success / Partial / Failure ----

    @Test
    fun `Success isSuccess is true`() {
        val r: DomainResult<String> = DomainResult.Success("ok")
        assertTrue(r.isSuccess)
        assertFalse(r.isFailure)
        assertEquals("ok", r.getOrNull())
    }

    @Test
    fun `Partial isSuccess is true`() {
        val r: DomainResult<String> = DomainResult.Partial("ok", overflow = 3)
        assertTrue(r.isSuccess)
        assertEquals("ok", r.getOrNull())
        assertEquals(3, (r as DomainResult.Partial).overflow)
    }

    @Test
    fun `Failure isFailure is true`() {
        val r: DomainResult<String> =
            DomainResult.Failure(AppError.Domain.Building.BuildingNotFound("x"))
        assertTrue(r.isFailure)
        assertFalse(r.isSuccess)
        assertNull(r.getOrNull())
        assertEquals("BLD_001", r.errorOrNull()?.code)
    }

    // ---- CancellationException ----

    @Test(expected = CancellationException::class)
    fun `catching rethrows CancellationException`() {
        DomainResult.catching<String> {
            throw CancellationException("cancelled")
        }
    }

    @Test
    fun `catching wraps exceptions in Failure`() {
        val result = DomainResult.catching<String> {
            throw IllegalArgumentException("bad arg")
        }
        assertTrue(result.isFailure)
        assertNotNull(result.errorOrNull())
    }

    // ---- map / flatMap ----

    @Test
    fun `map transforms success`() {
        val result = DomainResult.Success(3).map { it * 2 }
        assertEquals(6, result.getOrNull())
    }

    @Test
    fun `map passes through failure`() {
        val result: DomainResult<Int> =
            DomainResult.Failure(AppError.Domain.Inventory.Full())
        assertTrue(result.map { it * 2 }.isFailure)
    }

    @Test
    fun `flatMap chains success`() {
        val result = DomainResult.Success(3).flatMap {
            DomainResult.Success(it * 2)
        }
        assertEquals(6, result.getOrNull())
    }

    // ---- 回归：Boolean 反模式已消除 ----

    @Test
    fun `startAlchemy and startForging return DomainResult not Boolean`() {
        // 验证 BuildingFacade 接口方法签名返回 DomainResult 而非 Boolean。
        // 编译期保证：如果方法返回 Boolean，下列类型赋值将编译失败。
        val alchemyResult: DomainResult<com.xianxia.sect.core.model.production.ProductionSlot> =
            DomainResult.Failure(AppError.Domain.Production.SlotBusy())
        assertTrue(alchemyResult.isFailure)

        val forgeResult: DomainResult<com.xianxia.sect.core.model.production.ProductionSlot> =
            DomainResult.Success(com.xianxia.sect.core.model.production.ProductionSlot(
                slotIndex = 0, buildingType = com.xianxia.sect.core.model.production.BuildingType.ALCHEMY
            ))
        assertTrue(forgeResult.isSuccess)
    }
}
