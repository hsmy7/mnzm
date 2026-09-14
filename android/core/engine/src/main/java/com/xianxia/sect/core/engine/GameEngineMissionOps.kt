package com.xianxia.sect.core.engine

import kotlinx.coroutines.flow.map
import com.xianxia.sect.core.model.ActiveMission
import com.xianxia.sect.core.model.BattleLogAction
import com.xianxia.sect.core.model.BattleLogEnemy
import com.xianxia.sect.core.model.BattleLogMember
import com.xianxia.sect.core.model.BattleLogRound
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Mission
import com.xianxia.sect.core.model.spiritStones
import com.xianxia.sect.core.model.guide.GuideCounterKeys
import com.xianxia.sect.core.state.recordPlayerBattle
import com.xianxia.sect.core.engine.domain.exploration.MissionSystem
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.util.DomainResult
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.engine.domain.building.updateDiscipleStatus


// ── Cross-domain: Missions ──────────────────────────────────────────

fun GameEngine.startMission(mission: Mission, selectedDisciples: List<Disciple>) {
    gameEngineCore.launchInScope {
        val discipleIds = selectedDisciples.map { it.id }
        stateStore.update {
            // 前置清理：派遣即换岗——释放每个队员在全部槽位的旧引用（保留住所），
            // 防止同一弟子同时出现在岗位与任务中（不清理会
            // 任务完成后 status 强制 IDLE 而岗位槽位残留，状态/槽位永久不一致）
            discipleIds.forEach { releaseDiscipleToIdleInside(this, it) }
            val activeMission = MissionSystem.createActiveMission(
                mission, selectedDisciples, gameData.gameYear, gameData.gameMonth
            )
            gameData = gameData.copy(activeMissions = gameData.activeMissions + activeMission)
        }
        discipleIds.forEach { assignmentGate.release(it) }
        // 双存储同步：清 Room 生产槽 Repository
        discipleIds.forEach { clearDiscipleFromProductionRepository(it) }
        discipleFacade.syncAllDiscipleStatuses()
    }
}

suspend fun GameEngine.checkAndProcessCompletedMissions(): List<String> {
    val data = stateStore.gameDataSnapshot
    val completedIds = mutableListOf<String>()
    val remainingActive = mutableListOf<ActiveMission>()
    for (activeMission in data.activeMissions) {
        if (activeMission.isComplete(data.gameYear, data.gameMonth)) {
            completedIds.add(activeMission.id)
            settleCompletedMission(activeMission, data)
        } else remainingActive.add(activeMission)
    }
    if (completedIds.isNotEmpty()) updateGameDataSync { it.copy(activeMissions = remainingActive) }
    return completedIds
}

/**
 * 结算单个完成任务：存活队员参与完成结算，
 * 结算后将存活队员状态归位 IDLE。
 */
private suspend fun GameEngine.settleCompletedMission(activeMission: ActiveMission, data: GameData) {
    val aliveDisciples = activeMission.discipleIds.mapNotNull { did ->
        stateStore.disciplesSnapshot.find { it.id == did && it.isAlive }
    }
    if (aliveDisciples.isNotEmpty()) {
        applyCompletedMissionResult(activeMission, data, aliveDisciples)
    }
    for (did in activeMission.discipleIds) {
        val disciple = stateStore.disciplesSnapshot.find { it.id == did }
        if (disciple != null && disciple.isAlive) {
            gameEngineCore.launchInScope { updateDiscipleStatus(did, DiscipleStatus.IDLE) }
        }
    }
}

/** 完成结算：装备/功法/熟练度快照装配后走 MissionSystem */
private suspend fun GameEngine.applyCompletedMissionResult(
    activeMission: ActiveMission,
    data: GameData,
    aliveDisciples: List<Disciple>
) {
    val equipMap = stateStore.equipmentInstancesSnapshot.associateBy { it.id }
    val manualMap = stateStore.manualInstancesSnapshot.associateBy { it.id }
    val proficiencies = data.manualProficiencies.mapValues { (_, list) -> list.associateBy { it.manualId } }
    val result = MissionSystem.processMissionCompletion(
        activeMission, aliveDisciples, equipMap, manualMap,
        proficiencies, battleSystem, data.bloodRefinementPctTotals,
        gameRngManager.getRng(RngPartition.MISSION)
    )
    applyMissionResult(result, activeMission, data.gameYear, data.gameMonth, aliveDisciples)
}

@Suppress("UnusedParameter") // aliveDisciples: 语义形参：签名表达 API 决策域（调用点可读性与协议完整性优先），当前策略不消费
private suspend fun GameEngine.applyMissionResult(
    result: MissionSystem.MissionResult,
    activeMission: ActiveMission,
    year: Int,
    month: Int,
    aliveDisciples: List<Disciple>
) {
    // 引导系统：累计完成任务
    incrementGuideCounter(GuideCounterKeys.MISSIONS_COMPLETED)
    if (result.spiritStones > 0) addSpiritStones(result.spiritStones.toLong())
    // 统一 quest 来源（材料/功法溢出邮件来源不显示"未知"）
    grantMissionInventoryRewards(result)

    // 有战斗则写入战斗日志
    if (result.combatTriggered && result.battleResult != null) {
        writeMissionBattleLog(
            result = result,
            activeMission = activeMission,
            year = year,
            month = month
        )
    }
}

/** 任务奖励入库存放：材料/丹药/装备/功法统一 quest 来源 */
@Suppress("CyclomaticComplexMethod")
private fun GameEngine.grantMissionInventoryRewards(result: MissionSystem.MissionResult) {
    // 统一 quest 来源（材料/功法溢出邮件来源不显示"未知"）
    inventorySystem.withTrackingSource("quest") {
        result.materials.forEach { material ->
            when (val r = inventorySystem.addMaterial(material)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial -> DomainLog.w("GameEngine", "材料 ${material.name} 溢出 ${r.overflow} 个")
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加材料失败: ${r.error}")
            }
        }
        result.pills.forEach { pill ->
            when (val r = inventorySystem.addPill(pill)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial -> DomainLog.w("GameEngine", "丹药 ${pill.name} 溢出 ${r.overflow} 个")
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加丹药失败: ${r.error}")
            }
        }
        result.equipmentStacks.forEach { equipment ->
            when (val r = inventorySystem.addEquipmentStack(equipment)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial -> DomainLog.w("GameEngine", "装备 ${equipment.name} 溢出 ${r.overflow} 个")
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加装备失败: ${r.error}")
            }
        }
        result.manualStacks.forEach { manual ->
            when (val r = inventorySystem.addManualStack(manual)) {
                is DomainResult.Success -> {}
                is DomainResult.Partial -> DomainLog.w("GameEngine", "功法 ${manual.name} 溢出 ${r.overflow} 个")
                is DomainResult.Failure -> DomainLog.w("GameEngine", "添加功法失败: ${r.error}")
            }
        }
    }
}

/** 任务战斗日志写入：战报成员/敌人/回合/drops + recordPlayerBattle */
private fun GameEngine.writeMissionBattleLog(
    result: MissionSystem.MissionResult,
    activeMission: ActiveMission,
    year: Int,
    month: Int
) {
    val bsr = result.battleResult ?: return
    val logData = bsr.log
    val teamMembers = logData.teamMembers.map { m ->
        BattleLogMember(
            id = m.id, name = m.name, realm = m.realm, realmName = m.realmName,
            hp = m.hp, maxHp = m.maxHp, mp = m.mp, maxMp = m.maxMp,
            isAlive = m.isAlive, portraitRes = m.portraitRes
        )
    }
    val enemies = logData.enemies.map { e ->
        BattleLogEnemy(
            id = e.id, name = "敌人", realm = e.realm, realmName = e.realmName,
            hp = e.hp, maxHp = e.maxHp, isAlive = e.isAlive, portraitRes = e.portraitRes
        )
    }
    val rounds = logData.rounds.map { r ->
        BattleLogRound(
            roundNumber = r.roundNumber,
            actions = r.actions.map { a ->
                BattleLogAction(
                    type = a.type, attacker = a.attacker, attackerType = a.attackerType,
                    target = a.target, damage = a.damage, damageType = a.damageType,
                    isCrit = a.isCrit, isKill = a.isKill, message = a.message,
                    skillName = a.skillName
                )
            }
        )
    }
    val drops = mutableListOf<String>()
    if (result.spiritStones > 0) drops.add("灵石 ×${result.spiritStones}")
    result.materials.forEach { drops.add("${it.name} ×${it.quantity}") }
    result.pills.forEach { drops.add("${it.name} ×${it.quantity}") }
    result.equipmentStacks.forEach { drops.add("${it.name} ×${it.quantity}") }
    result.manualStacks.forEach { drops.add("${it.name} ×${it.quantity}") }

    gameEngineCore.launchInScope {
        stateStore.update {
            recordPlayerBattle(
                year = year,
                month = month,
                type = BattleType.PVE,
                attackerName = "玩家队伍",
                defenderName = activeMission.missionName,
                result = if (result.victory) BattleResult.WIN else BattleResult.LOSE,
                teamMembers = teamMembers,
                enemies = enemies,
                rounds = rounds,
                turns = bsr.turnCount,
                details = "执行任务「${activeMission.missionName}」，" +
                    if (result.victory) "战斗胜利" else "战斗失利",
                drops = drops
            )
        }
    }
}
