package com.xianxia.sect.ui.game.delegate

import android.util.Log
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.engine.equipItem
import com.xianxia.sect.core.engine.forgetManual
import com.xianxia.sect.core.engine.learnManual
import com.xianxia.sect.core.engine.replaceManual
import com.xianxia.sect.core.engine.unequipItem
import com.xianxia.sect.core.engine.unequipItemById
import com.xianxia.sect.core.engine.usePill
import kotlinx.coroutines.CancellationException

// ── 装备/功法/丹药操作族扩展（自 DiscipleDelegate 拆出，行为零变更）───────────
// batch-02 TooManyFunctions 收敛（类内 ≤19）外移为同包扩展，调用点语法不变。

@Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
fun DiscipleDelegate.equipItem(discipleId: String, equipmentId: String) {
    gameEngine.launchOnEngine {
        try {
            val result = gameEngine.equipItem(discipleId, equipmentId)
            if (result is DomainResult.Failure) {
                android.util.Log.w(
                    "DiscipleDelegate",
                    "equipItem failed: disciple=$discipleId" +
                        " equipment=$equipmentId" +
                        " error=${result.error.message}"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DiscipleDelegate", "operation failed", e)
        }
    }
}

@Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
fun DiscipleDelegate.unequipItem(discipleId: String, slot: EquipmentSlot) {
    gameEngine.launchOnEngine {
        try {
            val result = gameEngine.unequipItem(discipleId, slot)
            if (result == null) return@launchOnEngine // disciple not found or slot empty
            if (result is DomainResult.Failure) {
                android.util.Log.w(
                    "DiscipleDelegate",
                    "unequipItem(slot) failed: disciple=$discipleId" +
                        " slot=$slot error=${result.error.message}"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DiscipleDelegate", "operation failed", e)
        }
    }
}

@Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
fun DiscipleDelegate.unequipItem(discipleId: String, equipmentId: String) {
    gameEngine.launchOnEngine {
        try {
            val result = gameEngine.unequipItemById(discipleId, equipmentId)
            if (result is DomainResult.Failure) {
                android.util.Log.w(
                    "DiscipleDelegate",
                    "unequipItem(id) failed: disciple=$discipleId" +
                        " equipment=$equipmentId" +
                        " error=${result.error.message}"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DiscipleDelegate", "operation failed", e)
        }
    }
}

@Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
fun DiscipleDelegate.forgetManual(discipleId: String, instanceId: String) {
    gameEngine.launchOnEngine {
        try {
            gameEngine.forgetManual(discipleId, instanceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DiscipleDelegate", "operation failed", e)
        }
    }
}

@Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
fun DiscipleDelegate.replaceManual(discipleId: String, oldInstanceId: String, newStackId: String) {
    gameEngine.launchOnEngine {
        try {
            gameEngine.replaceManual(discipleId, oldInstanceId, newStackId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DiscipleDelegate", "operation failed", e)
        }
    }
}

@Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
fun DiscipleDelegate.learnManual(discipleId: String, stackId: String) {
    gameEngine.launchOnEngine {
        try {
            gameEngine.learnManual(discipleId, stackId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DiscipleDelegate", "operation failed", e)
        }
    }
}

@Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
fun DiscipleDelegate.usePill(discipleId: String, pillId: String) {
    gameEngine.launchOnEngine {
        try {
            gameEngine.usePill(discipleId, pillId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DiscipleDelegate", "operation failed", e)
        }
    }
}

@Suppress("TooGenericExceptionCaught") // 异常翻译边界: 刻意宽捕获, 归因日志后按领域语义重抛
fun DiscipleDelegate.usePill(discipleId: String, pill: Pill) {
    gameEngine.launchOnEngine {
        try {
            gameEngine.usePill(discipleId, pill.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("DiscipleDelegate", "operation failed", e)
        }
    }
}
