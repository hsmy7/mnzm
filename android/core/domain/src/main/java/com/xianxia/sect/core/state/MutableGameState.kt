package com.xianxia.sect.core.state

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.model.*

data class MutableGameState(
    var gameData: GameData,
    var discipleTables: DiscipleTables,        // 替代 List<Disciple>，组件表存储
    var equipmentStacks: EntityStore<EquipmentStack>,
    var equipmentInstances: EntityStore<EquipmentInstance>,
    var manualStacks: EntityStore<ManualStack>,
    var manualInstances: EntityStore<ManualInstance>,
    var pills: EntityStore<Pill>,
    var materials: EntityStore<Material>,
    var herbs: EntityStore<Herb>,
    var seeds: EntityStore<Seed>,
    var storageBags: EntityStore<StorageBag>,
    var teams: List<ExplorationTeam>,
    var battleLogs: List<BattleLog>,
    var isPaused: Boolean,
    var isLoading: Boolean,
    var isSaving: Boolean,
    var pendingNotification: GameNotification? = null,
    var isSettlementShadow: Boolean = false
)

/**
 * 统一的玩家战斗日志写入辅助。
 * 所有玩家参与的战斗都应通过此函数写入 battleLogs，避免散落的拼接逻辑导致遗漏。
 *
 * @param year        游戏年
 * @param month       游戏月
 * @param type        战斗类型（PVE/PVP/SECT_WAR/CAVE_EXPLORATION/SCOUT）
 * @param attackerName 攻击方描述（如"玩家队伍"、"妖兽"、AI宗门名）
 * @param defenderName 防守方描述（如"玩家宗门"、妖兽名、AI宗门名、任务名）
 * @param result      战斗结果
 * @param teamMembers 我方参战弟子快照
 * @param enemies     敌方参战单位快照
 * @param rounds      回合明细
 * @param turns       总回合数
 * @param details     文字摘要
 * @param drops       战利品/被掠夺物品描述列表
 * @param beastsDefeated 击杀敌人数（宗门战为击杀进攻者数）
 * @param teamCasualties 我方阵亡数
 */
fun MutableGameState.recordPlayerBattle(
    year: Int,
    month: Int,
    type: BattleType,
    attackerName: String,
    defenderName: String,
    result: BattleResult,
    teamMembers: List<BattleLogMember> = emptyList(),
    enemies: List<BattleLogEnemy> = emptyList(),
    rounds: List<BattleLogRound> = emptyList(),
    turns: Int = 0,
    details: String = "",
    drops: List<String> = emptyList(),
    beastsDefeated: Int = 0,
    teamCasualties: Int = 0
) {
    battleLogs = (battleLogs + BattleLog(
        year = year,
        month = month,
        type = type,
        attackerName = attackerName,
        defenderName = defenderName,
        result = result,
        teamMembers = teamMembers,
        enemies = enemies,
        rounds = rounds,
        turns = turns,
        details = details,
        drops = drops,
        beastsDefeated = beastsDefeated,
        teamCasualties = teamCasualties
    )).takeLast(GameConfig.Logs.MAX_BATTLE_LOGS)
}
