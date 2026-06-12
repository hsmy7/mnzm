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
    }

    data class Unknown(
        override val message: String = "未知错误",
        override val cause: Throwable? = null
    ) : AppError() {
        override val code = "UNKNOWN_ERROR"
    }
}
