package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.StorageBagItem
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import com.xianxia.sect.core.util.DomainLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val YEAR_TAG = "GameEngineCore"

/**
 * 年变真相源切换（死亡链）的信封数据——
 * nativeSettleYear 回传平台效应草稿（C++ 死亡链状态面完成后的 Kotlin 残留输入）。
 */
internal data class YearSettlementEnvelope(
    /** 死亡弟子草稿（袋物品物化/DAO 清理/DeathEvent/死亡记录档案） */
    val agedDeaths: List<AgedDeathDraft>,
    /** 丧亲事件草稿（lifeEvents 瞬态列写入） */
    val bereavements: List<BereavementDraft>
)

internal data class AgedDeathDraft(
    val discipleId: String,
    val name: String,
    val surname: String,
    val age: Int,
    val realm: Int,
    val realmLayer: Int,
    val deathYear: Int,
    val cause: String,
    val storageBagItems: List<StorageBagItem>
)

internal data class BereavementDraft(
    val grievingId: Int,
    val relationship: String,
    val deceasedName: String,
    val grievingAge: Int
)

/** 解析 nativeSettleYear 信封（宽松：缺键 → 空，兼容旧 .so 无草稿段）。 */
@Suppress("TooGenericExceptionCaught", "CyclomaticComplexMethod")  // 降级契约 + 信封多字段解析分支
internal fun parseYearSettlementEnvelope(envJson: String): YearSettlementEnvelope {
    val root = try {
        Json.parseToJsonElement(envJson).jsonObject
    } catch (e: Exception) {
        DomainLog.w(YEAR_TAG, "年变信封解析失败，按空信封处理: ${e.message}")
        return YearSettlementEnvelope(emptyList(), emptyList())
    }

    val agedDeaths = root["agedDeaths"]?.jsonArray?.mapNotNull { el ->
        val obj = el.jsonObject
        val discipleId = obj["discipleId"]?.jsonPrimitive?.contentOrNull
            ?: return@mapNotNull null
        AgedDeathDraft(
            discipleId = discipleId,
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
            surname = obj["surname"]?.jsonPrimitive?.contentOrNull ?: "",
            age = obj["age"]?.jsonPrimitive?.intOrNull ?: 0,
            realm = obj["realm"]?.jsonPrimitive?.intOrNull ?: 9,
            realmLayer = obj["realmLayer"]?.jsonPrimitive?.intOrNull ?: 1,
            deathYear = obj["deathYear"]?.jsonPrimitive?.intOrNull ?: 0,
            cause = obj["cause"]?.jsonPrimitive?.contentOrNull ?: "age",
            storageBagItems = runCatching {
                (obj["storageBagItems"]?.jsonArray ?: emptyList()).mapNotNull { item ->
                    runCatching {
                        Json.decodeFromJsonElement<StorageBagItem>(item)
                    }.getOrNull()
                }
            }.getOrElse { emptyList() }
        )
    } ?: emptyList()

    val bereavements = root["bereavements"]?.jsonArray?.mapNotNull { el ->
        val obj = el.jsonObject
        val grievingId = obj["grievingId"]?.jsonPrimitive?.intOrNull
            ?: return@mapNotNull null
        BereavementDraft(
            grievingId = grievingId,
            relationship = obj["relationship"]?.jsonPrimitive?.contentOrNull ?: "亲属",
            deceasedName = obj["deceasedName"]?.jsonPrimitive?.contentOrNull ?: "",
            grievingAge = obj["grievingAge"]?.jsonPrimitive?.intOrNull ?: 0
        )
    } ?: emptyList()

    return YearSettlementEnvelope(agedDeaths, bereavements)
}

/**
 * 年变真相源切换管线：生产年变路径从 Kotlin YearSettlementExecutor
 * 编排切换为 C++ `runYearSettlement` + Kotlin 残留执行器互插——
 * ① nativeSettleYear——C++ 完整年变（T1 全部 11 项 + T2 主要子项 + 年报 + 年俸），
 *    信封含死亡链平台效应草稿（agedDeaths/bereavements）；
 * ② applyDirtyFromNative——增量镜像（失败先全量兜底，仍失败异常传播）；
 * ③ Kotlin 残留执行器（单事务：物化/丧亲/死亡档案——招募生成/AI 招募/
 *    商人收购/交易刷新均已下沉 C++，本执行器不再调用）；
 * ④ 事务外平台效应（DAO 清理 + DeathEvent）。
 *
 * 返回 false 表示 native 未就绪（调用方回退 Kotlin 完整编排——此时 C++
 * 状态未变更，回退安全）；返回 true 表示 C++ 状态已变更——**此点之后任何
 * 失败必须抛异常传播（processAuthoritativeTick 的 refund + 看门狗自愈），
 * 不得回退 Kotlin 编排**（否则 C++ 已结算 + Kotlin 再结算 = 双份执行）。
 *
 * 失败语义与旬/月变管线同构；RNG 行为基线（思过释放先于招募生成）登记于
 * YearSettlementResidualExecutor KDoc。
 */
@Suppress("TooGenericExceptionCaught")  // 降级契约：native 链路失败统一 refund+重抛
internal suspend fun GameEngineCore.settleYearNative(): Boolean {
    if (!GameCoreBridge.isLoaded || !GameCoreBridge.nativeIsInitialized()) return false
    return try {
        // ① C++ 完整年变结算（信封含死亡链平台效应草稿）
        val envJson = GameCoreBridge.nativeSettleYear().decodeToString()
        // ② 增量镜像；失败先全量兜底，仍失败走异常回退路径
        val applied = stateSyncServiceRef.applyDirtyFromNative()
        if (applied == null && !stateSyncServiceRef.syncFromNative()) {
            error("年变镜像失败（增量+全量均不可用）")
        }
        val env = parseYearSettlementEnvelope(envJson)
        // ③ Kotlin 残留执行器（单事务——C++ 状态已变更，此处失败必须传播）
        // updateMirror = 非捕获事务（w3-13 弟子通道关闭配套，MonthOps 同款）：
        // 物化/丧亲均为 C++ 年结事实的 Kotlin 投影，无需回导
        stateStore.updateMirror { yearSettlementResidualExecutor.execute(this, env) }
        // ④ 事务外平台效应（Room 生产槽 DAO 清理 + DeathEvent 分发）
        yearSettlementResidualExecutor.applyPlatformEffects(env)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.w(YEAR_TAG, "年变 native 管线异常（C++ 状态已变更，传播至看门狗自愈）: ${e.message}")
        throw e
    }
}
