package com.xianxia.sect.core.engine

import com.xianxia.sect.core.GameConfig.TraitAdd
import com.xianxia.sect.core.GameConfig.TraitWashType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.PendingTraitAdd
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.util.asKotlinRandom
import kotlin.coroutines.cancellation.CancellationException

// GameEngineTraitAddOps.kt — 新增天赋/体质/词条（玉符消耗玩法）GameEngine 扩展入口
//
// 与洗炼（GameEngineTraitWashOps）同构但语义相反：洗炼是"替换"，新增是"追加"。
// 流程：消耗 1 玉符刷新出随机特质（无负面，下品40%/中品30%/上品30%，与洗炼共用
// rollSingle 分布）→ 刷新结果**立即持久化**到 GameData.pendingTraitAdds（关闭界面
// 再打开仍显示，可直接确认新增）→ 确认新增把特质追加到弟子（不消耗玉符）。
//
// 纯随机抽取函数（候选池/品阶分布）在 GameEngineTraitWashRoll.kt（rollSingle/
// hasRollCandidate/appendId），两玩法共用同一套抽取口径。

/** 日志 TAG（与其他 GameEngine 扩展文件一致） */
private const val LOG_TAG = "GameEngine"

/** 新增天赋/体质/词条结果 */
sealed interface TraitAddResult {
    /** 刷新成功：newId 为本次刷新产物（已持久化到 pendingTraitAdds，未确认不写弟子） */
    data class Success(val newId: String) : TraitAddResult
    /** 玉符不足（余额不足时不消耗随机序列，不写 pending） */
    data class InsufficientJadeSymbols(val current: Int, val required: Int) : TraitAddResult
    /** 其他错误（弟子不存在/已死亡/已满/无可抽条目/引擎异常，message 为玩家可读中文） */
    data class Error(val message: String) : TraitAddResult
}

/** 确认新增结果 */
sealed interface TraitAddConfirmResult {
    data object Success : TraitAddConfirmResult
    data class Error(val message: String) : TraitAddConfirmResult
}

/**
 * 新增天赋/体质/词条的**刷新**：校验弟子存在且存活 → 校验未满上限 → 预检候选池 →
 * 事务内扣 1 玉符 → 抽取（无负面 40/30/30）→ **事务内持久化 pending**。
 *
 * 玉符不足时提前返回且**不消耗随机序列**（随机序列确定性保持）。
 * 刷新即扣玉符（后续"继续消耗"每次再扣 1 枚）；确认新增不消耗玉符。
 * 刷新结果只写 pending 不写弟子；"确认新增"由 [confirmTraitAdd] 负责。
 *
 * @param discipleId 目标弟子 id
 * @param type 天赋/体质/词条类型
 */
@Suppress("TooGenericExceptionCaught") // 兜底转 Error（项目范式，同 GameEngineTraitWashOps）
suspend fun GameEngine.rollTraitAdd(
    discipleId: String,
    type: TraitWashType
): TraitAddResult = engineContextDispatcher.withEngineContext {
    val id = discipleId.toIntOrNull()
    if (id == null) {
        DomainLog.w(LOG_TAG, "新增${type.displayName}拒绝: 非法弟子ID=$discipleId")
        return@withEngineContext TraitAddResult.Error("非法弟子ID")
    }
    try {
        val result = rollTraitAddInner(id, type)
        if (result is TraitAddResult.Success) {
            // 事务外刷新玉符 UI 状态（清 1Hz 节流，徽章/详情即时更新）
            jadeSymbolService.publishJadeSymbolStateNow()
        }
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e(LOG_TAG, "新增${type.displayName}失败: id=$discipleId", e)
        TraitAddResult.Error("未知错误")
    }
}

/**
 * 刷新事务内逻辑（单 update 原子完成）：存在/存活/上限/候选校验 → 扣 1 玉符 →
 * 抽取 → 持久化 pending。所有校验均在扣费前，失败不消耗玉符与随机序列。
 *
 * ⚠️ deduct 后的事务代码必须无异常/无损失路径（JadeSymbolService.deduct 契约）：
 * 抽取与候选预检同池同过滤（预检通过后 rollSingle 不可能返回 null，见 rollSingle 实现），
 * pending 写入为纯 copy——满足契约。
 */
private fun GameEngine.rollTraitAddInner(
    id: Int,
    type: TraitWashType
): TraitAddResult = stateStore.updateAndReturn {
    if (id !in discipleTables.ids) {
        DomainLog.w(LOG_TAG, "新增${type.displayName}拒绝: 弟子不存在 id=$id")
        return@updateAndReturn TraitAddResult.Error("弟子不存在")
    }
    // 死亡弟子拒绝新增（对死人无意义，防止误操作扣玉符）
    if (discipleTables.isAlive[id] != 1) {
        DomainLog.w(LOG_TAG, "新增${type.displayName}拒绝: 弟子已死亡 id=$id")
        return@updateAndReturn TraitAddResult.Error("弟子已死亡")
    }
    val current: Disciple = discipleTables.assemble(id)
    val currentIds = type.idsOf(current)
    // 上限校验：单类特质最多 5 个（UI 隐藏 + 引擎拒绝双保险）
    if (currentIds.size >= TraitAdd.MAX_TRAITS_PER_CATEGORY) {
        DomainLog.w(LOG_TAG, "新增${type.displayName}拒绝: 已达上限 id=$id 数量=${currentIds.size}")
        return@updateAndReturn TraitAddResult.Error("该弟子${type.displayName}已满")
    }
    // 新增不得与已有槽位 template 冲突（否则确认校验拒绝）
    val excludedTemplates = buildSet {
        for (keptId in currentIds) {
            type.resolveOne(keptId)?.let { add(it.template) }
        }
    }
    // 扣费前预检候选池（无随机消耗）：池全被排除 → 不扣费直接拒绝
    if (!type.hasRollCandidate(excludedTemplates)) {
        DomainLog.w(LOG_TAG, "新增${type.displayName}拒绝: 候选池全被排除 id=$id")
        return@updateAndReturn TraitAddResult.Error("暂无可用新增结果")
    }
    val required = TraitAdd.JADE_COST
    if (!jadeSymbolService.deduct(this, required)) {
        DomainLog.w(LOG_TAG, "新增${type.displayName}拒绝: 玉符不足 id=$id 余额=${gameData.jadeSymbols} 需要=$required")
        return@updateAndReturn TraitAddResult.InsufficientJadeSymbols(
            current = gameData.jadeSymbols,
            required = required
        )
    }
    val entry = type.rollSingle(
        gameRngManager.getRng(RngPartition.SYSTEM).asKotlinRandom(),
        excludedTemplates
    )
    val newId = entry?.id
    if (newId == null) {
        // 理论不可达（预检与抽取同池同过滤，见 rollSingle 实现）；防御语义：玉符已扣、
        // 不写 pending，返回明确错误让玩家重试（不抛异常——deduct 契约）
        DomainLog.e(LOG_TAG, "新增${type.displayName}异常: 预检通过但抽取为空 id=$id")
        return@updateAndReturn TraitAddResult.Error("暂无可用新增结果")
    }
    // 持久化 pending：同 (disciple, type) 已有产物则覆盖（继续消耗刷新），其余保留
    gameData = gameData.copy(
        pendingTraitAdds = gameData.pendingTraitAdds
            .filterNot { it.discipleId == id.toString() && it.type == type.name } +
            PendingTraitAdd(discipleId = id.toString(), type = type.name, traitId = newId)
    )
    TraitAddResult.Success(newId)
}

/**
 * 确认新增：把刷新产物 [newId] **追加**到弟子该类型特质列表末尾（其余特质保留），
 * 并在同一事务内清除 pending。
 *
 * 体质（cultivationSpeedBonus）与词条（CULT_SPEED）影响修炼速率——新增瞬间必须
 * checkpointDisciple 重新记账，否则 realtimeCultivation 会用旧 checkpoint 混算新速率
 * 导致跳变（与洗炼确认替换同理）。天赋/词条的 lifespan 加成经
 * [syncLifespanForTraitChange] 同步（新增"延年"类特质寿命相应上调）。
 *
 * 本地信任模型：不校验产物是否由本会话刷新产生（任何合法 id 均可新增），
 * 单机游戏本地数据可被玩家自行修改；联网化需会话令牌绑定产物。
 *
 * @param newId 刷新产物 id（单个）
 */
@Suppress("TooGenericExceptionCaught") // 兜底转 Error（项目范式，同 GameEngineTraitWashOps）
suspend fun GameEngine.confirmTraitAdd(
    discipleId: String,
    type: TraitWashType,
    newId: String
): TraitAddConfirmResult = engineContextDispatcher.withEngineContext {
    val id = discipleId.toIntOrNull()
    if (id == null) {
        DomainLog.w(LOG_TAG, "确认新增${type.displayName}拒绝: 非法弟子ID=$discipleId")
        return@withEngineContext TraitAddConfirmResult.Error("非法弟子ID")
    }
    try {
        // 四态区分失败原因：NOT_FOUND/DEAD/FULL 给玩家明确文案（对齐洗炼确认的三态先例）
        val outcome = stateStore.updateAndReturn<ConfirmAddOutcome> {
            if (id !in discipleTables.ids) {
                DomainLog.w(LOG_TAG, "确认新增${type.displayName}拒绝: 弟子不存在 id=$id")
                return@updateAndReturn ConfirmAddOutcome.NOT_FOUND
            }
            if (discipleTables.isAlive[id] != 1) {
                DomainLog.w(LOG_TAG, "确认新增${type.displayName}拒绝: 弟子已死亡 id=$id")
                return@updateAndReturn ConfirmAddOutcome.DEAD
            }
            val current: Disciple = discipleTables.assemble(id)
            val currentIds = type.idsOf(current)
            if (currentIds.size >= TraitAdd.MAX_TRAITS_PER_CATEGORY) {
                DomainLog.w(LOG_TAG, "确认新增${type.displayName}拒绝: 已达上限 id=$id 数量=${currentIds.size}")
                return@updateAndReturn ConfirmAddOutcome.FULL
            }
            if (!isValidTraitAdd(type, currentIds, newId)) {
                DomainLog.w(
                    LOG_TAG,
                    "确认新增${type.displayName}拒绝: 新增校验失败 id=$id new=$newId 当前=$currentIds"
                )
                return@updateAndReturn ConfirmAddOutcome.INVALID
            }
            val updated = type.appendId(current, newId)
            discipleTables.remove(id)
            discipleTables.insert(syncLifespanForTraitChange(current, updated))
            // 体质/词条影响修炼速率——新增瞬间重新记账（速率投影基于 checkpoint + 新速率推导）
            discipleTables.checkpointDisciple(id, gameData.gameYear * 12 + gameData.gameMonth)
            // 清除 pending（产物已落盘到弟子）
            gameData = gameData.copy(
                pendingTraitAdds = gameData.pendingTraitAdds
                    .filterNot { it.discipleId == id.toString() && it.type == type.name }
            )
            ConfirmAddOutcome.ADDED
        }
        when (outcome) {
            ConfirmAddOutcome.ADDED -> TraitAddConfirmResult.Success
            ConfirmAddOutcome.NOT_FOUND -> TraitAddConfirmResult.Error("弟子不存在")
            ConfirmAddOutcome.DEAD -> TraitAddConfirmResult.Error("弟子已死亡")
            ConfirmAddOutcome.FULL -> TraitAddConfirmResult.Error("该弟子${type.displayName}已满")
            ConfirmAddOutcome.INVALID -> TraitAddConfirmResult.Error("该特质已无法新增")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DomainLog.e(LOG_TAG, "确认新增${type.displayName}失败: id=$discipleId", e)
        TraitAddConfirmResult.Error("未知错误")
    }
}

/**
 * 新增合法性校验：产物可被 Database 解析、产物不在当前列表中、
 * 追加后整体 template 无重复（与生成语义一致——同一 template 的特质互斥）。
 */
private fun isValidTraitAdd(
    type: TraitWashType,
    currentIds: List<String>,
    newId: String
): Boolean {
    // 空白 id 无法被 Database 解析，走下方早退
    val newEntry = type.resolveOne(newId) ?: return false
    return newId !in currentIds &&
        newEntry.template !in currentIds.mapNotNull { type.resolveOne(it)?.template }.toSet()
}

/** confirm 事务内结果四态（对外映射为明确中文文案，见 [GameEngine.confirmTraitAdd]） */
private enum class ConfirmAddOutcome { NOT_FOUND, DEAD, FULL, INVALID, ADDED }

/** 新增：在弟子该类型特质列表末尾追加 newId（确认新增落盘，其余类型不变） */
internal fun TraitWashType.appendId(disciple: Disciple, newId: String): Disciple = when (this) {
    TraitWashType.TALENT -> disciple.copy(talentIds = disciple.talentIds + newId)
    TraitWashType.PHYSIQUE -> disciple.copy(physiqueIds = disciple.physiqueIds + newId)
    TraitWashType.AFFIX -> disciple.copy(affixIds = disciple.affixIds + newId)
}
