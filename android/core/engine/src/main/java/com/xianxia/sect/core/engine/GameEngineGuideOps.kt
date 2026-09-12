package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.GridBuildingData
import com.xianxia.sect.core.model.RewardCardItem
import com.xianxia.sect.core.model.SectPolicies
import com.xianxia.sect.core.model.StorageBag
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.nativebridge.ActionIds
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps
import com.xianxia.sect.core.nativebridge.GameEngineNativeOps.params
import com.xianxia.sect.core.nativebridge.NativeEngineFlag
import com.xianxia.sect.core.nativebridge.StateSyncService
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.RngPartition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * 政策整包序列化（encodeDefaults = true）。
 *
 * 整包替换语义下，"等于默认值"的字段**必须**上线：C++ 侧 `from_json` 为宽松
 * 读取（`readField`：键缺失保持默认值），若关闭态字段（如 autoMineFocused=false、
 * autoMineRootCounts=emptyList）被省略，C++ 会保留旧值 → 策略关闭操作静默失效。
 */
private val POLICIES_JSON = Json { encodeDefaults = true }

/**
 * 引导计数面 native 臂公共门控（batch-18a）。
 *
 * 门控序：AUTHORITATIVE → 镜像服务可空局部判空（handover findings 13：测试
 * mock 未 stub `stateSyncServiceRef` 时返回 null，调用点内非空检查会直接
 * NPE）→ `nativeExecute` 转发。返回 true = C++ 已提交且镜像成功（调用方
 * 直接返回，不再执行 Kotlin 原路径）；false = flag 关/未加载/信封失败 →
 * 回退 Kotlin 臂（双实现并行契约）。
 */
private fun GameEngine.tryNativeBoundaryTx(actionId: Int, paramsJson: ByteArray): Boolean {
    if (!NativeEngineFlag.authoritative) return false
    val sync: StateSyncService? = gameEngineCore.stateSyncServiceRef
    if (sync == null) return false
    return GameEngineNativeOps.tryExecuteNative(
        stateSyncService = sync,
        actionId = actionId,
        paramsJson = paramsJson
    ) != null
}

/**
 * 引导系统引擎操作 — 发放奖励、更新计数器。
 */
fun GameEngine.claimGuideReward(taskId: Int): Boolean {
    val task = com.xianxia.sect.core.model.guide.GuideTaskRegistry.getTask(taskId) ?: return false
    /** 出生随机流走 SYSTEM 分区（与伴侣配对/弟子招募同类系统级随机） */
    val rng = gameRngManager.getRng(RngPartition.SYSTEM)
    var claimed = false
    stateStore.update {
        val gd = gameData
        if (taskId in gd.guideClaimedRewardIds) return@update
        if (!task.conditions.all { it.isMet(gd) }) return@update

        // 凭据类路径：抑制溢出转邮件（失败时不标记已领取，重试补齐）
        inventorySystem.withOverflowMailSuppressed {
        val bagName = StorageBag.TIER_NAMES[0]
        val quantity = task.rewardItemQuantity
        // 统一委托 addStorageBag（走 StackableItemStore 合并，同稀有度自动合并）
        val result = inventorySystem.addStorageBag(
            StorageBag(
                id = java.util.UUID(rng.nextLong(), rng.nextLong()).toString(),
                name = bagName,
                rarity = 1,
                quantity = quantity
            )
        )
        // 发放失败（仓库满）时不标记已领取，玩家清理后可重试
        if (result !is DomainResult.Success) {
            DomainLog.w("GameEngineGuideOps", "引导奖励 $bagName 发放失败，不标记已领取: ${(result as? DomainResult.Failure)?.error}")
        } else {
            gameData = gd.copy(
                guideClaimedRewardIds = gd.guideClaimedRewardIds + taskId
            )
            claimed = true
        }
        }
    }
    if (claimed) {
        // 入队奖励卡片，触发 RewardCardHost 飞出动画
        stateStore.enqueueRewardCards(listOf(
            RewardCardItem(
                itemName = StorageBag.TIER_NAMES[0],
                itemType = "storageBag",
                rarity = 1,
                quantity = task.rewardItemQuantity
            )
        ))
    }
    return claimed
}

/**
 * 批量更新自动分配策略与引导计数器（合并为单次事务）。
 */
@Suppress("UnusedParameter") // oldPolicies: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
fun GameEngine.batchUpdateAutoAssignAndGuide(
    oldPolicies: SectPolicies,
    newPolicies: SectPolicies,
    mineActivated: Boolean,
    plantActivated: Boolean,
    productionActivated: Boolean
) {
    // batch-18a native 臂：策略整包替换 + 三激活计数合并写
    //（BOUNDARY_AUTO_ASSIGN_GUIDE_TX——零 RNG 纯事务）。失败信封/降级 →
    // Kotlin 原路径（双实现并行契约，回退臂语义与下沉前逐字一致）。
    if (tryNativeBoundaryTx(
            actionId = ActionIds.BOUNDARY_AUTO_ASSIGN_GUIDE_TX,
            paramsJson = params {
                put("policies", POLICIES_JSON.encodeToJsonElement(newPolicies))
                put("mineActivated", mineActivated)
                put("plantActivated", plantActivated)
                put("productionActivated", productionActivated)
            }
        )
    ) return
    stateStore.update {
        val gd = gameData
        var counters = gd.guideCounters
        if (mineActivated) {
            val cur = counters[GuideCounterKeys.AUTO_MINE_ACTIVATED] ?: 0L
            counters = counters + (GuideCounterKeys.AUTO_MINE_ACTIVATED to cur + 1)
        }
        if (plantActivated) {
            val cur = counters[GuideCounterKeys.AUTO_PLANT_ACTIVATED] ?: 0L
            counters = counters + (GuideCounterKeys.AUTO_PLANT_ACTIVATED to cur + 1)
        }
        if (productionActivated) {
            val cur = counters[GuideCounterKeys.AUTO_PRODUCTION_ACTIVATED] ?: 0L
            counters = counters + (GuideCounterKeys.AUTO_PRODUCTION_ACTIVATED to cur + 1)
        }
        gameData = gd.copy(
            sectPolicies = newPolicies,
            guideCounters = counters
        )
    }
}

/**
 * 递增引导计数器。
 */
fun GameEngine.incrementGuideCounter(key: String, amount: Long = 1) {
    // batch-18a native 臂：引导计数递增（BOUNDARY_GUIDE_COUNTER_INCREMENT_TX——
    // 键缺省 0 起算，零 RNG）。失败信封/降级 → Kotlin 原路径。
    if (tryNativeBoundaryTx(
            actionId = ActionIds.BOUNDARY_GUIDE_COUNTER_INCREMENT_TX,
            paramsJson = params {
                put("key", key)
                put("amount", amount)
            }
        )
    ) return
    stateStore.update {
        val currentCount = gameData.guideCounters[key] ?: 0L
        gameData = gameData.copy(
            guideCounters = gameData.guideCounters + (key to currentCount + amount)
        )
    }
}

/**
 * 计算建筑建造累计计数回填（纯函数，可单测）。
 *
 * 旧档无累计计数：按当前存量建筑回填（max 语义，不覆盖已有更高计数）。
 * 此后建筑升级/拆除不再回退引导建造进度。
 *
 * @param placedBuildings 当前全部已放置建筑
 * @param existingCounters 现有引导计数器
 * @return 回填后的完整计数器 map
 */
internal fun computeBuildingCounterBackfill(
    placedBuildings: List<GridBuildingData>,
    existingCounters: Map<String, Long>
): Map<String, Long> {
    val result = existingCounters.toMutableMap()
    placedBuildings
        .map { it.displayName }
        .distinct()
        .forEach { name ->
            val key = GuideCounterKeys.buildingBuiltKey(name)
            /** 当前设备的电源管理配置 */
            val current = placedBuildings.count { it.displayName == name }.toLong()
            if (current > (result[key] ?: 0L)) result[key] = current
        }
    return result
}

/**
 * 回填建筑建造累计计数（读档 Step 3.6，幂等）。
 *
 * 旧档无累计计数：按当前存量建筑回填，防升级/拆除回退引导进度。
 */
fun GameEngine.backfillBuildingGuideCounters() {
    // batch-18a native 臂：建造计数回填（BOUNDARY_BUILDING_GUIDE_BACKFILL_TX——
    // displayName max 语义幂等，零 RNG）。纯函数 [computeBuildingCounterBackfill]
    // 保留供 Kotlin 回退臂与单测使用（回退臂语义不变）。
    if (tryNativeBoundaryTx(
            actionId = ActionIds.BOUNDARY_BUILDING_GUIDE_BACKFILL_TX,
            paramsJson = params { }
        )
    ) return
    stateStore.update {
        val backfilled = computeBuildingCounterBackfill(gameData.placedBuildings, gameData.guideCounters)
        if (backfilled != gameData.guideCounters) {
            gameData = gameData.copy(guideCounters = backfilled)
        }
    }
}
