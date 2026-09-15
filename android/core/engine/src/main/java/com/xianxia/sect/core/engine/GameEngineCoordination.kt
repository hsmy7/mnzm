package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.RecruitIntegrity
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.nativebridge.ActionIds
import kotlinx.serialization.json.put











/** 关注键最大长度（type 前缀 + 冒号 + 最长物品名，防御恶意超长键撑爆存档） */
private const val MAX_WATCHED_KEY_LENGTH = 64

// ── Focus / UI state ────────────────────────────────────────────────

@Suppress("UnusedParameter") // id: 焦点域收敛后的兼容 no-op 门面（调用面含测试桩，随焦点域移除时一并删除）
fun GameEngine.setFocusedDiscipleId(id: String?) {
    // 焦点域已覆盖焦点弟子功能，不再需要独立焦点弟子跟踪
}

fun GameEngine.setActiveTab(tab: String) {
    stateStore.activeTab = tab
}

fun GameEngine.setActiveDialog(dialogName: String?) {
    stateStore.activeDialog = dialogName
}

fun GameEngine.pushSubDialogDomain(domainName: String) {
    stateStore.activeSubDialogs = stateStore.activeSubDialogs + domainName
}

fun GameEngine.popSubDialogDomain(domainName: String) {
    stateStore.activeSubDialogs = stateStore.activeSubDialogs - domainName
}

fun GameEngine.notifyUserInteraction() = gameEngineCore.onUserInteraction()

// ── Data update helpers ─────────────────────────────────────────────

suspend fun GameEngine.updateGameData(update: (GameData) -> GameData) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update { gameData = update(gameData) }
    }
}

/**
 * 切换物品关注状态（键格式 "type:name"，如 "pill:聚气丹"）。
 * 已关注则取消，未关注则添加；去重并截断到 [GameData.MAX_WATCHED_ITEMS]。
 *
 * @param key 关注键，空白/超长/格式错误视为无效输入返回失败（正常 UI 路径不会产生）
 * @return 成功或校验失败
 */
suspend fun GameEngine.toggleWatchItem(key: String): DomainResult<Unit> {
    if (key.isBlank()) {
        return DomainResult.Failure(
            AppError.Domain.Validation.InvalidInput("物品关注键不能为空")
        )
    }
    if (key.length > MAX_WATCHED_KEY_LENGTH) {
        return DomainResult.Failure(
            AppError.Domain.Validation.InvalidInput("物品关注键过长")
        )
    }
    if (':' !in key) {
        return DomainResult.Failure(
            AppError.Domain.Validation.InvalidInput("物品关注键格式错误")
        )
    }
    return engineContextDispatcher.withEngineContext {
        stateStore.update { gameData = gameData.toggleWatchedItem(key) }
        DomainResult.Success(Unit)
    }
}

/**
 * 同步写入口（`updateGameData` 的 launchInScope 变体）：在引擎协程 scope 内
 * 执行事务，不挂起调用方——设置项域（batch-23）native 臂失败/降级时的
 * 回退臂写者即此函数（`updateSettingsOrFallback` → `updateGameDataSync`）。
 *
 * public 可见性与同文件 [updateGameData] / [updateGameDataAndSync] 对齐
 * （feature:game 测试经 MockK 捕获回退臂闭包断言字段映射——原 internal
 * 使跨模块测试不可 stub，为 GameViewModelTest 预存失败根因之一）。
 */
fun GameEngine.updateGameDataSync(update: (GameData) -> GameData) {
    gameEngineCore.launchInScope { stateStore.update { gameData = update(gameData) } }
}

suspend fun GameEngine.updateDisciple(discipleId: String, update: (Disciple) -> Disciple) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val id = discipleId.toInt()
            if (id !in discipleTables.ids) return@update
            val current = discipleTables.assemble(id)
            val updated = update(current)
            discipleTables.remove(id)
            discipleTables.insert(updated)
        }
    }
}

/**
 * 弟子交谈效果原子应用（W4-D 续批：交谈写面下沉——弟子通道最后一个
 * "协议列数据丢失风险"写者，W4-A·A5 登记的 W4-D 决策项）。
 *
 * C++ 真相先行（`DISCIPLE_CHAT_EFFECT_TX=1860`：修为 max(0,+x)、道德/忠诚/悟性
 * 和 clamp [1,100]、`statusData["lastChatYear"]` 冷却标记——chat_effect_tx.h，
 * **零 RNG**：增量数值已由引擎侧 `RngPartition.CHAT` 签发（[chatDraw] 族）后
 * 参数传入，双臂抽取增量恒 0）。弟子不存在 = 成功无操作（Kotlin `return@update`
 * 同语义）。失败信封/降级 null → Kotlin 原路径（[updateDisciple]，回退臂语义
 * 不变——红线 3）。
 *
 * 非数字 id：C++ 臂不尝试（协议为整数 id），直接走 Kotlin 原路径——其
 * `toInt()` 抛出 + 调用方捕获的原行为保持不变。
 */
suspend fun GameEngine.applyConversationEffectAtomic(
    discipleId: String,
    currentYear: Int,
    moralityDelta: Int,
    loyaltyDelta: Int,
    cultivationDelta: Double,
    intelligenceDelta: Int
) {
    val intId = discipleId.toIntOrNull()
    if (intId != null && tryDiscipleOpNative(ActionIds.DISCIPLE_CHAT_EFFECT_TX) {
            put("discipleId", intId)
            put("currentYear", currentYear)
            put("cultivationDelta", cultivationDelta)
            put("moralityDelta", moralityDelta)
            put("loyaltyDelta", loyaltyDelta)
            put("intelligenceDelta", intelligenceDelta)
        } != null) {
        return
    }
    updateDisciple(discipleId) { disciple ->
        val newStatus = disciple.statusData.toMutableMap().apply {
            this["lastChatYear"] = currentYear.toString()
        }
        disciple.copy(
            cultivation = maxOf(0.0, disciple.cultivation + cultivationDelta),
            skills = disciple.skills.copy(
                morality = (disciple.skills.morality + moralityDelta).coerceIn(1, 100),
                loyalty = (disciple.skills.loyalty + loyaltyDelta).coerceIn(1, 100),
                intelligence = (disciple.skills.intelligence + intelligenceDelta).coerceIn(1, 100)
            ),
            statusData = newStatus
        )
    }
}

/**
 * 原子重命名宗门弟子，并在同一事务内清除招募列表中与"旧身份"同人的残留条目。
 * 改名会破坏 [RecruitIntegrity.isSamePerson] 的 5 字段签名匹配，
 * 若不在此净化，残留双胞胎将永久逃脱净化、可被重复招募。
 *
 * @param discipleId 宗门弟子 ID
 * @param newName 新姓名
 */
suspend fun GameEngine.renameDisciple(discipleId: String, newName: String) {
    // C++ 真相先行（W4-A·w3-01：names 行写 + 招募列表 isSamePerson 同人净化
    // 在 C++；失败信封/降级 null → Kotlin 原路径回退臂——双实现并行契约）
    if (tryDiscipleOpNative(ActionIds.DISCIPLE_OP_RENAME) {
            put("discipleId", discipleId)
            put("newName", newName)
        } != null) {
        return
    }
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val id = discipleId.toInt()
            if (id !in discipleTables.ids) return@update
            val current = discipleTables.assemble(id)
            val updated = current.copy(name = newName)
            discipleTables.remove(id)
            discipleTables.insert(updated)
            // 按改名前的旧身份过滤：签名+年龄容差命中的同人残留一并清除
            val kept = gameData.recruitList.filter { !RecruitIntegrity.isSamePerson(it, current) }
            if (kept.size != gameData.recruitList.size) {
                gameData = gameData.copy(recruitList = kept)
            }
        }
    }
}

suspend fun GameEngine.changeDiscipleTypeAtomic(discipleId: String, newType: String) {
    // C++ 真相先行（discipleTypes 行写；状态推导 syncSingleDiscipleStatus
    // 由下方调用方照原序执行——与 Kotlin 事务序一致）
    if (tryDiscipleOpNative(ActionIds.DISCIPLE_OP_CHANGE_TYPE) {
            put("discipleId", discipleId)
            put("newType", newType)
        } != null) {
        discipleFacade.syncSingleDiscipleStatus(discipleId)
        return
    }
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val id = discipleId.toInt()
            if (id in discipleTables.ids) discipleTables.discipleTypes[id] = newType
        }
        discipleFacade.syncSingleDiscipleStatus(discipleId)
    }
}

/**
 * 弟子关注切换（W4-A·w3-01 新增入口：DiscipleDelegate.toggleFollowDisciple
 * 原 [updateDisciple] lambda 直改面的事务化形态）。
 *
 * C++ 真相先行（statusData["followed"] 翻转）；flag 关/降级/失败信封回退
 * Kotlin 原路径（同一 [updateDisciple] 事务语义）。
 */
suspend fun GameEngine.toggleFollowDisciple(discipleId: String) {
    if (tryDiscipleOpNative(ActionIds.DISCIPLE_OP_TOGGLE_FOLLOW) {
            put("discipleId", discipleId)
        } != null) {
        return
    }
    updateDisciple(discipleId) { disciple ->
        val currentFollowed = disciple.statusData["followed"] == "true"
        val newStatusData = disciple.statusData.toMutableMap().apply {
            if (currentFollowed) remove("followed") else this["followed"] = "true"
        }
        disciple.copy(statusData = newStatusData)
    }
}



suspend fun GameEngine.updateGameDataAndSync(update: (GameData) -> GameData) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update { gameData = update(gameData) }
        discipleFacade.syncAllDiscipleStatuses()
    }
}


