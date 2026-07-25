package com.xianxia.sect.data.serialization.unified

import com.xianxia.sect.core.model.*

internal class TeamAndBattleConverter {

    fun convertTeam(team: com.xianxia.sect.core.model.ExplorationTeam): SerializableExplorationTeam {
        return SerializableExplorationTeam(
            id = team.id,
            name = team.name,
            memberIds = team.memberIds,
            status = team.status.name,
            targetSectId = team.scoutTargetSectId ?: "",
            startYear = team.startYear,
            startMonth = team.startMonth,
            duration = team.duration,
            currentProgress = team.progress,
            caveId = team.caveId ?: "",
            caveName = team.caveName,
            dungeon = team.dungeon,
            dungeonName = team.dungeonName,
            memberNames = team.memberNames,
            startDay = team.startDay,
            currentX = team.currentX.toInt(),
            currentY = team.currentY.toInt(),
            targetX = team.targetX.toInt(),
            targetY = team.targetY.toInt(),
            moveProgress = team.moveProgress.toDouble(),
            arrivalYear = team.arrivalYear,
            arrivalMonth = team.arrivalMonth,
            arrivalDay = team.arrivalDay,
            route = team.route.map { convertRoutePoint(it) },
            currentRouteIndex = team.currentRouteIndex,
            currentSegmentProgress = team.currentSegmentProgress.toDouble(),
            pityCounterEquipment = team.pityCounterEquipment,
            pityCounterPill = team.pityCounterPill,
            pityCounterManual = team.pityCounterManual,
            scoutTargetSectName = team.scoutTargetSectName
        )
    }

    fun convertBackTeam(data: SerializableExplorationTeam): com.xianxia.sect.core.model.ExplorationTeam {
        return com.xianxia.sect.core.model.ExplorationTeam(
            id = data.id,
            name = data.name,
            memberIds = data.memberIds,
            startYear = data.startYear,
            startMonth = data.startMonth,
            duration = data.duration,
            status = safeEnumValueOfIgnoreCase(data.status, com.xianxia.sect.core.model.ExplorationStatus.TRAVELING, "status", "ExplorationTeam"),
            progress = data.currentProgress,
            scoutTargetSectId = data.targetSectId,
            caveId = data.caveId.ifEmpty { null },
            caveName = data.caveName,
            dungeon = data.dungeon,
            dungeonName = data.dungeonName,
            memberNames = data.memberNames,
            startDay = data.startDay,
            currentX = data.currentX.toFloat(),
            currentY = data.currentY.toFloat(),
            targetX = data.targetX.toFloat(),
            targetY = data.targetY.toFloat(),
            moveProgress = data.moveProgress.toFloat(),
            arrivalYear = data.arrivalYear,
            arrivalMonth = data.arrivalMonth,
            arrivalDay = data.arrivalDay,
            route = data.route.map { convertBackRoutePoint(it) },
            currentRouteIndex = data.currentRouteIndex,
            currentSegmentProgress = data.currentSegmentProgress.toFloat(),
            pityCounterEquipment = data.pityCounterEquipment,
            pityCounterPill = data.pityCounterPill,
            pityCounterManual = data.pityCounterManual,
            scoutTargetSectName = data.scoutTargetSectName
        )
    }

    fun convertBattleLog(log: com.xianxia.sect.core.model.BattleLog): SerializableBattleLog {
        return SerializableBattleLog(
            id = log.id,
            timestamp = log.timestamp,
            gameYear = log.year,
            gameMonth = log.month,
            attackerSectId = "",
            attackerSectName = log.attackerName,
            defenderSectId = "",
            defenderSectName = log.defenderName,
            result = log.result.name,
            rounds = log.rounds.map { convertBattleLogRound(it) },
            attackerMembers = log.teamMembers.map { convertBattleLogMember(it) },
            defenderMembers = log.enemies.map { convertBattleLogEnemy(it) },
            rewards = emptyMap(),
            type = log.type.name,
            details = log.details,
            drops = log.drops,
            dungeonName = log.dungeonName,
            teamId = log.teamId ?: "",
            turns = log.turns,
            teamCasualties = log.teamCasualties,
            beastsDefeated = log.beastsDefeated
        )
    }

    fun convertBackBattleLog(data: SerializableBattleLog): com.xianxia.sect.core.model.BattleLog {
        return com.xianxia.sect.core.model.BattleLog(
            id = data.id,
            timestamp = data.timestamp,
            year = data.gameYear,
            month = data.gameMonth,
            type = safeEnumValueOf(data.type, com.xianxia.sect.core.model.BattleType.PVE, "type", "BattleLog"),
            attackerName = data.attackerSectName,
            defenderName = data.defenderSectName,
            result = safeEnumValueOf(data.result, com.xianxia.sect.core.model.BattleResult.DRAW, "result", "BattleLog"),
            details = data.details,
            drops = data.drops,
            dungeonName = data.dungeonName,
            rounds = data.rounds.map { convertBackBattleLogRound(it) },
            teamMembers = data.attackerMembers.map { convertBackBattleLogMember(it) },
            enemies = data.defenderMembers.map { convertBackBattleLogEnemy(it) },
            teamId = data.teamId.ifEmpty { null },
            turns = data.turns,
            teamCasualties = data.teamCasualties,
            beastsDefeated = data.beastsDefeated
        )
    }

    private fun convertRoutePoint(point: String): SerializableMapPoint {
        val parts = point.split(",", limit = 2)
        return SerializableMapPoint(
            x = parts.getOrNull(0)?.toIntOrNull() ?: 0,
            y = parts.getOrNull(1)?.toIntOrNull() ?: 0
        )
    }

    private fun convertBackRoutePoint(data: SerializableMapPoint): String {
        return "${data.x},${data.y}"
    }

    fun convertBattleLogRound(round: com.xianxia.sect.core.model.BattleLogRound): SerializableBattleLogRound {
        return SerializableBattleLogRound(
            roundNumber = round.roundNumber,
            actions = round.actions.map { convertBattleLogAction(it) }
        )
    }

    fun convertBackBattleLogRound(data: SerializableBattleLogRound): com.xianxia.sect.core.model.BattleLogRound {
        return com.xianxia.sect.core.model.BattleLogRound(
            roundNumber = data.roundNumber,
            actions = data.actions.map { convertBackBattleLogAction(it) }
        )
    }

    fun convertBattleLogAction(action: com.xianxia.sect.core.model.BattleLogAction): SerializableBattleLogAction {
        return SerializableBattleLogAction(
            actorId = "",
            actorName = action.attacker,
            attackerType = action.attackerType,
            targetId = "",
            targetName = action.target,
            skillName = action.skillName ?: "",
            damage = action.damage,
            isCritical = action.isCrit,
            effect = action.message,
            type = action.type,
            damageType = action.damageType,
            isKill = action.isKill
        )
    }

    fun convertBackBattleLogAction(data: SerializableBattleLogAction): com.xianxia.sect.core.model.BattleLogAction {
        return com.xianxia.sect.core.model.BattleLogAction(
            type = data.type,
            attacker = data.actorName,
            attackerType = data.attackerType,
            target = data.targetName,
            damage = data.damage,
            damageType = data.damageType,
            isCrit = data.isCritical,
            isKill = data.isKill,
            message = data.effect,
            skillName = data.skillName
        )
    }

    fun convertBattleLogMember(member: com.xianxia.sect.core.model.BattleLogMember): SerializableBattleLogMember {
        return SerializableBattleLogMember(
            discipleId = member.id,
            name = member.name,
            realm = member.realm,
            isAlive = member.isAlive,
            remainingHp = member.hp,
            maxHp = member.maxHp,
            remainingMp = member.mp,
            maxMp = member.maxMp,
            portraitRes = member.portraitRes
        )
    }

    fun convertBackBattleLogMember(data: SerializableBattleLogMember): com.xianxia.sect.core.model.BattleLogMember {
        return com.xianxia.sect.core.model.BattleLogMember(
            id = data.discipleId,
            name = data.name,
            realm = data.realm,
            hp = data.remainingHp,
            maxHp = data.maxHp,
            mp = data.remainingMp,
            maxMp = data.maxMp,
            isAlive = data.isAlive,
            portraitRes = data.portraitRes
        )
    }

    fun convertBattleLogEnemy(enemy: com.xianxia.sect.core.model.BattleLogEnemy): SerializableBattleLogMember {
        return SerializableBattleLogMember(
            discipleId = enemy.id,
            name = enemy.name,
            realm = enemy.realm,
            isAlive = enemy.isAlive,
            remainingHp = enemy.hp,
            maxHp = enemy.maxHp,
            portraitRes = enemy.portraitRes
        )
    }

    fun convertBackBattleLogEnemy(data: SerializableBattleLogMember): com.xianxia.sect.core.model.BattleLogEnemy {
        return com.xianxia.sect.core.model.BattleLogEnemy(
            id = data.discipleId,
            name = data.name,
            realm = data.realm,
            hp = data.remainingHp,
            maxHp = data.maxHp,
            isAlive = data.isAlive,
            portraitRes = data.portraitRes
        )
    }

    fun convertAlliance(alliance: com.xianxia.sect.core.model.Alliance): SerializableAlliance {
        return SerializableAlliance(
            id = alliance.id ?: "",
            sectIds = alliance.sectIds ?: emptyList(),
            startYear = alliance.startYear ?: 0,
            initiatorId = alliance.initiatorId ?: "",
            envoyDiscipleId = alliance.envoyDiscipleId ?: ""
        )
    }

    fun convertBackAlliance(data: SerializableAlliance): com.xianxia.sect.core.model.Alliance {
        return com.xianxia.sect.core.model.Alliance(
            id = data.id,
            sectIds = data.sectIds,
            startYear = data.startYear,
            initiatorId = data.initiatorId,
            envoyDiscipleId = data.envoyDiscipleId
        )
    }
}
