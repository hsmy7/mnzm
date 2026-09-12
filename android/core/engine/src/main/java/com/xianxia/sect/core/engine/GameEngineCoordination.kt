package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.RecruitIntegrity
import com.xianxia.sect.core.util.AppError
import com.xianxia.sect.core.util.DomainResult











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

internal fun GameEngine.updateGameDataSync(update: (GameData) -> GameData) {
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
 * 原子重命名宗门弟子，并在同一事务内清除招募列表中与"旧身份"同人的残留条目。
 * 改名会破坏 [RecruitIntegrity.isSamePerson] 的 5 字段签名匹配，
 * 若不在此净化，残留双胞胎将永久逃脱净化、可被重复招募。
 *
 * @param discipleId 宗门弟子 ID
 * @param newName 新姓名
 */
suspend fun GameEngine.renameDisciple(discipleId: String, newName: String) {
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
    return engineContextDispatcher.withEngineContext {
        stateStore.update {
            val id = discipleId.toInt()
            if (id in discipleTables.ids) discipleTables.discipleTypes[id] = newType
        }
        discipleFacade.syncSingleDiscipleStatus(discipleId)
    }
}

suspend fun GameEngine.updateGameDataAndSync(update: (GameData) -> GameData) {
    return engineContextDispatcher.withEngineContext {
        stateStore.update { gameData = update(gameData) }
        discipleFacade.syncAllDiscipleStatuses()
    }
}


