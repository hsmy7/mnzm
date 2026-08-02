package com.xianxia.sect.core.engine.domain.exploration

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.model.SecretRealmAIMember
import com.xianxia.sect.core.model.SecretRealmAITeam
import com.xianxia.sect.core.state.MutableGameState
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远古秘境 AI 宗门队伍——仅派遣占位：秘境存在期间，所有有存活弟子的 AI 宗门
 * 派遣真实弟子中境界最高的 4 名进入秘境（纯背景存在，无任何行为）。
 */
@Singleton
@GameService("SecretRealmAIProcessor")
class SecretRealmAIProcessor @Inject constructor() {

    /**
     * 月变入口：秘境存在时，为尚未派遣的 AI 宗门逐月派遣队伍（幂等去重）。
     */
    fun processMonthlyAiTeams(state: MutableGameState) {
        val data = state.gameData
        if (!data.secretRealmState.exists) return

        val existingSectIds = data.secretRealmAITeams.map { it.sectId }.toSet()
        val pendingSects = data.aiSectDisciples.filter { (sectId, disciples) ->
            sectId !in existingSectIds && disciples.any { it.isAlive }
        }
        if (pendingSects.isEmpty()) return

        val newTeams = pendingSects.map { (sectId, disciples) ->
            val sectName = data.worldMapSects.find { it.id == sectId }?.name ?: sectId
            SecretRealmAITeam(
                id = UUID.randomUUID().toString(),
                sectId = sectId,
                sectName = sectName,
                members = disciples
                    .filter { it.isAlive }
                    .sortedBy { it.realm }
                    .take(GameConfig.SecretRealm.AI_TEAM_SIZE)
                    .map { d ->
                        SecretRealmAIMember(
                            discipleId = d.id,
                            name = d.name,
                            portraitRes = d.portraitRes,
                            realm = d.realm
                        )
                    }
            )
        }
        if (newTeams.isNotEmpty()) {
            state.gameData = data.copy(
                secretRealmAITeams = data.secretRealmAITeams + newTeams
            )
        }
    }
}
