package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.SecretRealmBackpack
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.xianxia.sect.core.engine.service.alignProductionSlotsForNativeMonth
import com.xianxia.sect.core.engine.service.restoreProductionSlotsFromMirror

/**
 * 月变真相源切换的信封数据（nativeSettleMonth 回传草稿/决策信息）。
 * 手工解析（字段少且非协议类型，避免 @Serializable 与 C++ nlohmann 键名
 * 二次维护——SecretRealmBackpack 复用协议编解码）。
 */
internal data class MonthSettlementEnvelope(
    /** 政策费用不足被自动禁用的政策名列表（事务外 checkpointAllProduction 决策） */
    val disabledPolicies: List<String>,
    /** 秘境到期关闭草稿（memberIds → gate release；backpack → 关闭邮件附件） */
    val secretRealmClose: MonthSecretRealmClose?,
    /** 弟子智能购买日志草稿（lifeEvents 瞬态列写入） */
    val purchaseLogs: List<MonthPurchaseLog>,
    /** 玩家占领宗门被 AI 夺回 → 建筑没收 sectId 集（P2-18 Stage 2 征伐环；
     *  建筑特性注册表/Room 生产槽位保留 Kotlin——事务外
     *  buildingFacade.seizeBuildingsOfSect，反向通道回同步） */
    val seizedBuildingsOfSect: List<String> = emptyList()
)

internal data class MonthSecretRealmClose(
    val memberIds: List<String>,
    val backpack: SecretRealmBackpack
)

internal data class MonthPurchaseLog(
    val discipleId: String,
    val itemName: String,
    val age: Int
)

private const val MONTH_TAG = "GameEngineCore"

/**
 * 月变真相源切换管线：生产月变路径从 Kotlin MonthSettlementExecutor
 * 八步编排切换为 C++ `runMonthSettlement` + Kotlin 残留执行器互插——
 * ① nativeSettleMonth——C++ 完整月变结算（八步 + 十六子事件已全量下沉），
 *    信封含 policyCosts.disabledPolicies / 秘境关闭草稿 / 购买日志草稿 /
 *    征伐没收 sectId 集；
 * ② applyDirtyFromNative——增量镜像（失败先全量兜底，仍失败异常传播）；
 * ③ Kotlin 残留执行器（单事务：购买日志 + 秘境关闭邮件——S4 后生产结算入 C++，
 *    W4-C 后战斗三件入 C++；判定口径 = 通知/日志留 Kotlin，见
 *    MonthSettlementResidualExecutor KDoc）。
 *
 * 返回 null 表示 native 未就绪（调用方回退 Kotlin 完整编排——此时 C++ 状态
 * 未变更，回退安全）；返回信封表示 C++ 状态已变更——**此点之后任何失败必须
 * 抛异常传播（processAuthoritativeTick 的 refund + 看门狗自愈），不得回退
 * Kotlin 编排**（否则 C++ 已结算 + Kotlin 再结算 = 双份执行）。
 *
 * 失败语义与旬 settleOnePhase 管线同构；RNG 序列变化（残留生产结算/任务完成
 * 位于 C++ 全部消耗之后）为"月变编排整体入 C++"的行为基线，登记于
 * MonthSettlementResidualExecutor KDoc。
 */
@Suppress("TooGenericExceptionCaught")  // 降级契约：native 链路失败统一 refund+重抛
internal fun GameEngineCore.settleMonthNative(): MonthSettlementEnvelope? {
    if (!GameCoreBridge.isLoaded || !GameCoreBridge.nativeIsInitialized()) return null
    return try {
        // ⓪ S4 槽位窗口对齐：repo 为真源整表写镜像（C++ 生产结算只看镜像——
        //    手动启动/惰性建槽/取消等 Room 先行写入的 B5 分叉自愈）
        cultivationService.alignProductionSlotsForNativeMonth()
        // ⓪' S8：AI 热控批量上界推送（平台效应——ThermalMonitor 决策保留
        //    Kotlin，批状态机在 C++ 内存运行；月结前引擎线程调用）
        GameCoreBridge.nativeSetAiThermalBatchSize(
            cultivationService.eventProcessorForMonthSettlement
                .caveExplorationProcessor.get().currentAiThermalBatchSize()
        )
        // ① C++ 完整月变结算（信封含草稿与决策信息）
        val envJson = GameCoreBridge.nativeSettleMonth().decodeToString()
        // ② 增量镜像；失败先全量兜底，仍失败走异常回退路径
        val applied = stateSyncServiceRef.applyDirtyFromNative()
        if (applied == null && !stateSyncServiceRef.syncFromNative()) {
            error("月变镜像失败（增量+全量均不可用）")
        }
        val env = parseMonthSettlementEnvelope(envJson)
        // ③ Kotlin 残留执行器（单事务：战斗三件 + 邮件 + 草稿应用
        //    ——S4 后炼丹/锻造完成结算与自动排班已入 C++；C++ 状态已变更，
        //    此处失败必须传播）
        // updateMirror = 非捕获事务（w3-13 弟子通道关闭配套）：本事务全部为
        // C++ 结算事实的 Kotlin 投影（协议外 lifeEvents 显示列 + 平台草稿），
        // 无需回导 C++——捕获会使已关闭通道的行级检测误报
        stateStore.updateMirror { monthSettlementResidualExecutor.execute(this, env) }
        // ③' 事务外平台效应：玩家占领宗门被夺回的建筑没收（独立事务——
        //    建筑拆除含嵌套 update 与 Room 槽位清理，禁止嵌套，
        //    AISectOccupationResolver 事务外拆除先例；变更经反向通道回同步）
        if (env.seizedBuildingsOfSect.isNotEmpty()) {
            seizedBuildingsHandler?.invoke(env.seizedBuildingsOfSect)
        }
        // ④ S4 槽位写回：镜像为权威整表重放 repo（C++ 结算的槽位变更同步
        //    Room；IO 失败仅记录——镜像已权威，Room 落后由下月对齐自愈）
        cultivationService.restoreProductionSlotsFromMirror(stateStore.gameData.value.currentSlot)
        env
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w(MONTH_TAG, "月变 native 管线异常（C++ 状态已变更，传播至看门狗自愈）: ${e.message}")
        throw e
    }
}

/** 解析 nativeSettleMonth 信封（宽松：缺键 → 空/默认，兼容旧 .so 无草稿段）。 */
@Suppress("TooGenericExceptionCaught")  // 降级契约：信封损坏按空信封处理（旧 .so/异常输出）
internal fun parseMonthSettlementEnvelope(envJson: String): MonthSettlementEnvelope {
    val root = try {
        Json.parseToJsonElement(envJson).jsonObject
    } catch (e: Exception) {
        DomainLog.w(MONTH_TAG, "月变信封解析失败，按空信封处理: ${e.message}")
        return MonthSettlementEnvelope(emptyList(), null, emptyList())
    }

    val disabledPolicies = root["policyCosts"]?.jsonObject
        ?.get("disabledPolicies")?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    val secretRealmClose = root["secretRealmClose"]?.jsonObject?.let { close ->
        val closed = close["closed"]?.jsonPrimitive?.booleanOrNull
            ?: (close["closed"]?.jsonPrimitive?.contentOrNull == "true")
        if (!closed) {
            null
        } else {
            val backpack = runCatching {
                val backpackEl = close["backpack"] ?: return@runCatching SecretRealmBackpack()
                Json.decodeFromJsonElement<SecretRealmBackpack>(backpackEl)
            }.getOrElse {
                DomainLog.w(MONTH_TAG, "秘境关闭草稿背包解析失败，按空背包处理: ${it.message}")
                SecretRealmBackpack()
            }
            MonthSecretRealmClose(
                memberIds = close["memberIds"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                backpack = backpack
            )
        }
    }

    val purchaseLogs = root["purchaseLogs"]?.jsonArray?.mapNotNull { el ->
        val obj = el.jsonObject
        val discipleId = obj["discipleId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        MonthPurchaseLog(
            discipleId = discipleId,
            itemName = obj["itemName"]?.jsonPrimitive?.contentOrNull ?: "",
            age = obj["age"]?.jsonPrimitive?.intOrNull ?: 0
        )
    } ?: emptyList()

    val seizedBuildingsOfSect = root["seizedSectBuildings"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    return MonthSettlementEnvelope(
        disabledPolicies, secretRealmClose, purchaseLogs, seizedBuildingsOfSect
    )
}
