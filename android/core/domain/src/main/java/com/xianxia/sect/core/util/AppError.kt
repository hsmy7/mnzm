package com.xianxia.sect.core.util

sealed class AppError {
    abstract val code: String
    abstract val message: String
    abstract val cause: Throwable?

    sealed class Domain : AppError() {

        sealed class Production : Domain() {
            data class SlotBusy(
                override val message: String = "槽位正在工作中",
                val slotIndex: Int = -1,
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_001"
            }

            data class InsufficientMaterials(
                override val message: String = "材料不足",
                val missingMaterials: Map<String, Int> = emptyMap(),
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_002"
            }

            data class InvalidSlot(
                override val message: String = "无效的槽位",
                val slotIndex: Int = -1,
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_003"
            }

            data class RecipeNotFound(
                override val message: String = "配方不存在",
                val recipeId: String = "",
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_004"
            }

            data class DiscipleNotAvailable(
                override val message: String = "弟子不可用",
                val discipleId: String = "",
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_005"
            }

            data class InvalidStateTransition(
                override val message: String = "无效的状态转换",
                val fromStatus: String = "",
                val toStatus: String = "",
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_006"
            }

            data class ProductionFailed(
                override val message: String = "生产失败",
                val recipeName: String = "",
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_007"
            }

            data class DatabaseError(
                override val message: String = "数据库错误",
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_008"
            }

            data class Unknown(
                override val message: String = "未知生产错误",
                override val cause: Throwable? = null
            ) : Production() {
                override val code = "PROD_099"
            }
        }

        sealed class Storage : Domain() {
            data class SlotNotFound(
                override val message: String = "存档槽位不存在",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_001"
            }

            data class SlotCorrupted(
                override val message: String = "存档数据已损坏",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_002"
            }

            data class SaveFailed(
                override val message: String = "保存失败",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_003"
            }

            data class LoadFailed(
                override val message: String = "加载失败",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_004"
            }

            data class DeleteFailed(
                override val message: String = "删除失败",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_005"
            }

            data class BackupFailed(
                override val message: String = "备份失败",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_006"
            }

            data class RestoreFailed(
                override val message: String = "恢复失败",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_007"
            }

            data class EncryptionError(
                override val message: String = "加密错误",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_008"
            }

            data class DecryptionError(
                override val message: String = "解密错误",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_009"
            }

            data class IoError(
                override val message: String = "IO错误",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_010"
            }

            data class DatabaseError(
                override val message: String = "数据库错误",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_011"
            }

            data class TransactionFailed(
                override val message: String = "事务失败",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_012"
            }

            data class Timeout(
                override val message: String = "操作超时",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_013"
            }

            data class ChecksumMismatch(
                override val message: String = "校验和不匹配",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_014"
            }

            data class KeyDerivationError(
                override val message: String = "密钥错误",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_015"
            }

            data class IntegrityError(
                override val message: String = "数据完整性错误",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_016"
            }

            data class VerificationFailed(
                override val message: String = "验证失败",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_017"
            }

            data class Expired(
                override val message: String = "数据已过期",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_018"
            }

            data class Tampered(
                override val message: String = "数据已被篡改",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_019"
            }

            data class Unknown(
                override val message: String = "未知存储错误",
                override val cause: Throwable? = null
            ) : Storage() {
                override val code = "STORAGE_099"
            }
        }

        sealed class Validation : Domain() {
            data class InvalidInput(
                override val message: String = "输入无效",
                override val cause: Throwable? = null
            ) : Validation() {
                override val code = "VALID_001"
            }

            data class ConfigError(
                override val message: String = "配置错误",
                override val cause: Throwable? = null
            ) : Validation() {
                override val code = "VALID_002"
            }

            data class OutOfRange(
                override val message: String = "超出范围",
                override val cause: Throwable? = null
            ) : Validation() {
                override val code = "VALID_003"
            }

            data class EmptyValue(
                override val message: String = "值为空",
                override val cause: Throwable? = null
            ) : Validation() {
                override val code = "VALID_004"
            }
        }

        sealed class GameState : Domain() {
            data class InvalidState(
                override val message: String = "无效的游戏状态",
                override val cause: Throwable? = null
            ) : GameState() {
                override val code = "GAME_001"
            }

            data class NotFound(
                override val message: String = "未找到",
                override val cause: Throwable? = null
            ) : GameState() {
                override val code = "GAME_002"
            }

            data class PermissionDenied(
                override val message: String = "权限不足",
                override val cause: Throwable? = null
            ) : GameState() {
                override val code = "GAME_003"
            }
        }

        sealed class GameLoop : Domain() {
            data class TickTimeout(
                val elapsedMs: Long,
                override val cause: Throwable? = null
            ) : GameLoop() {
                override val code = "LOOP_001"
                override val message: String = "游戏循环超时 (${elapsedMs}ms)"
            }

            data class StateInconsistency(
                val detail: String,
                override val cause: Throwable? = null
            ) : GameLoop() {
                override val code = "LOOP_002"
                override val message: String = "状态不一致: $detail"
            }

            data class EngineNotRunning(
                val operation: String,
                override val cause: Throwable? = null
            ) : GameLoop() {
                override val code = "LOOP_003"
                override val message: String = "引擎未运行，无法执行: $operation"
            }

            data class Unknown(
                override val message: String = "未知游戏循环错误",
                override val cause: Throwable? = null
            ) : GameLoop() {
                override val code = "LOOP_099"
            }
        }

        sealed class Network : Domain() {
            data class NoConnection(
                override val message: String = "网络连接失败",
                override val cause: Throwable? = null
            ) : Network() {
                override val code = "NET_001"
            }

            data class Timeout(
                override val message: String = "网络请求超时",
                override val cause: Throwable? = null
            ) : Network() {
                override val code = "NET_002"
            }

            data class Unknown(
                override val message: String = "未知网络错误",
                override val cause: Throwable? = null
            ) : Network() {
                override val code = "NET_099"
            }
        }
    }

    data class Unknown(
        override val message: String = "未知错误",
        override val cause: Throwable? = null
    ) : AppError() {
        override val code = "UNKNOWN_ERROR"
    }

    companion object {
        fun fromException(e: Throwable): AppError {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return when (e) {
                is java.net.UnknownHostException -> Domain.Network.NoConnection(cause = e)
                is java.net.SocketTimeoutException -> Domain.Network.Timeout(cause = e)
                is java.io.IOException -> Domain.Network.NoConnection(e.message ?: "网络错误", e)
                is IllegalArgumentException -> Domain.Validation.InvalidInput(e.message ?: "参数错误", e)
                is IllegalStateException -> Domain.GameLoop.StateInconsistency(e.message ?: "状态错误", e)
                is NoSuchElementException -> Domain.GameState.NotFound(e.message ?: "未找到数据", e)
                is SecurityException -> Domain.GameState.PermissionDenied(e.message ?: "权限不足", e)
                else -> Unknown(e.message ?: "未知错误", e)
            }
        }
    }
}
