package com.xianxia.sect.core.engine.service

import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.battle.AIBattleWinner
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.engine.domain.building.BuildingFacade
import com.xianxia.sect.core.engine.domain.disciple.DiscipleStatCalculator
import com.xianxia.sect.core.exploration.DiscipleDeathHandler
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GarrisonSlot
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 攻防占领结算处理器（D15 拆分自 AISectBattleProcessor，2026-08-08）。
 *
 * 职责：单次 AI 进攻结果应用——玩家占领宗门驻军更新、阵亡过滤（占领者/防守方分流）、
 * 占领归属变更、好感惩罚、被夺回后玩家建筑没收。
 * 由 [AISectBattleProcessor.processAIVsAIBattles] 编排调用。
 */
@Singleton
@GameService("AISectOccupationResolver")
class AISectOccupationResolver @Inject constructor(
    private val stateStore: GameStateStore,
    private val deathHandler: DiscipleDeathHandler,
    private val buildingFacade: BuildingFacade
) {

    /** P-2：应用单次 AI 进攻结果（死亡过滤/占领/关系变更，单事务原子提交）。 */
    internal fun applyAIAttackResult(
        result: AISectAttackManager.AIAttackResult,
        gameYear: Int
    ) {
        // 2026-08-06：玩家占领宗门被 AI 夺回 → 事务提交后没收玩家在该宗门的建筑
        //（无灵石返还）。事务内捕获标志：提交后 isPlayerOccupied 已被置 false，无法事后重算。
        var wasPlayerOccupied = false
        stateStore.update {
                val currentGameData = gameData
                val defenderSect = currentGameData.worldMapSects
                    .find { it.id == result.defenderSectId }
                val isPlayerOccupied = defenderSect
                    ?.isPlayerOccupied == true
                wasPlayerOccupied = isPlayerOccupied

                // 玩家占领宗门防御：更新驻军弟子状态
                if (isPlayerOccupied) {
                    updatePlayerGarrisonState(
                        this, result, discipleTables, gameYear
                    )
                }

                // 过滤阵亡弟子（攻击者/防守者/占领者 + 驻军清理）
                val updatedAttacker = (currentGameData.aiSectDisciples[result.attackerSectId] ?: emptyList())
                    .filter { it.id !in result.deadAttackerIds }
                val isAiOccupied = defenderSect != null &&
                    defenderSect.occupierSectId.isNotEmpty() &&
                    !isPlayerOccupied
                val occupierId = defenderSect?.occupierSectId ?: ""
                val (updatedDefender, updatedOccupier, updatedSects) = computeCasualtyUpdates(
                    currentGameData, result, isAiOccupied, occupierId
                )

                var updatedData = gameData.copy(
                    aiSectDisciples = gameData.aiSectDisciples
                        .toMutableMap().apply {
                            this[result.attackerSectId] = updatedAttacker
                            this[result.defenderSectId] = updatedDefender
                            if (updatedOccupier != null &&
                                occupierId.isNotEmpty()) {
                                this[occupierId] = updatedOccupier
                            }
                        },
                    worldMapSects = updatedSects,
                    sectRelations = applyAIAttackFavorPenalty(
                        gameData.sectRelations, result
                    )
                )

                // 占领处理
                updatedData = applyAIOccupation(
                    updatedData, result, isPlayerOccupied, updatedAttacker, updatedDefender
                )

                gameData = updatedData
            }
        // 事务外执行（建筑拆除独立事务，避免嵌套 update）
        seizePlayerBuildingsAfterLoss(result, wasPlayerOccupied)
    }

    /**
     * 玩家占领宗门被 AI 夺回后，没收该宗门内玩家建造的建筑（无灵石返还）。
     *
     * 2026-08-06 产品决策：占领宗门被其他宗门占领后，玩家在其中建造的建筑
     * 自动拆除且不返还灵石（拆除入口随宗门失守而消失，资源不回笼）。
     * 判定与 [applyAIOccupation] 的夺回分支一致；事务外调用（拆除独立事务）。
     */
    internal fun seizePlayerBuildingsAfterLoss(
        result: AISectAttackManager.AIAttackResult,
        isPlayerOccupied: Boolean
    ) {
        if (!isPlayerOccupied ||
            result.winner != AIBattleWinner.ATTACKER ||
            !result.canOccupy
        ) return
        buildingFacade.seizeBuildingsOfSect(result.defenderSectId)
    }

    /** AI 攻防双方好感惩罚（-10，夹取在允许范围） */
    private fun applyAIAttackFavorPenalty(
        sectRelations: List<SectRelation>,
        result: AISectAttackManager.AIAttackResult
    ): List<SectRelation> {
        return sectRelations.map { r ->
            val relevant =
                (r.sectId1 == result.attackerSectId &&
                    r.sectId2 == result.defenderSectId) ||
                    (r.sectId1 == result.defenderSectId &&
                        r.sectId2 == result.attackerSectId)
            if (relevant) r.copy(
                favor = (r.favor - 10).coerceIn(
                    com.xianxia.sect.core.config.FavorConfig.MIN_FAVOR,
                    com.xianxia.sect.core.config.FavorConfig.MAX_FAVOR
                )
            ) else r
        }
    }

    /**
     * 阵亡过滤：按防守方是否被 AI/玩家占领分流——被 AI 占领时死亡从占领者池移除
     * 并清理驻军槽位；否则仅从防守方弟子池过滤。
     * 返回 (更新后防守者, 更新后占领者或 null, 更新后宗门列表)。
     */
    private fun computeCasualtyUpdates(
        currentGameData: GameData,
        result: AISectAttackManager.AIAttackResult,
        isAiOccupied: Boolean,
        occupierId: String
    ): Triple<List<Disciple>, List<Disciple>?, List<WorldSect>> {
        if (isAiOccupied && result.deadDefenderIds.isNotEmpty()) {
            val occupierDisc = currentGameData
                .aiSectDisciples[occupierId]
                ?: emptyList()
            val filteredOccupier = occupierDisc
                .filter { it.id !in result.deadDefenderIds }
            val clearedGarrisonSects = currentGameData.worldMapSects.map { s ->
                if (s.id == result.defenderSectId) s.copy(
                    garrisonSlots = s.garrisonSlots.map { slot ->
                        if (slot.discipleId in result.deadDefenderIds)
                            GarrisonSlot(index = slot.index) else slot
                    }
                ) else s
            }
            return Triple(
                currentGameData.aiSectDisciples[result.defenderSectId] ?: emptyList(),
                filteredOccupier,
                clearedGarrisonSects
            )
        }
        val defenderDisc = currentGameData
            .aiSectDisciples[result.defenderSectId]
            ?: emptyList()
        return Triple(
            defenderDisc.filter { it.id !in result.deadDefenderIds },
            null,
            currentGameData.worldMapSects
        )
    }

    /**
     * AI 攻占处理：占领成功时更新宗门归属（玩家占领被夺回 / AI 占领者接管）、
     * 驻军槽位与宗门弟子池合并。
     */
    private fun applyAIOccupation(
        updatedData: GameData,
        result: AISectAttackManager.AIAttackResult,
        isPlayerOccupied: Boolean,
        updatedAttacker: List<Disciple>,
        updatedDefender: List<Disciple>
    ): GameData {
        if (result.winner != AIBattleWinner.ATTACKER || !result.canOccupy) return updatedData
        return if (isPlayerOccupied) {
            updatedData.copy(
                worldMapSects = updatedData.worldMapSects.map { s ->
                    if (s.id == result.defenderSectId) s.copy(
                        isPlayerOccupied = false,
                        occupierSectId = result.attackerSectId,
                        garrisonSlots = buildGarrSlots(
                            result.survivingAttackers
                        )
                    ) else s
                }
            )
        } else {
            updatedData.copy(
                worldMapSects = updatedData.worldMapSects.map { s ->
                    if (s.id == result.defenderSectId) s.copy(
                        occupierSectId = result.attackerSectId,
                        garrisonSlots = buildGarrSlots(
                            result.survivingAttackers
                        )
                    ) else s
                },
                aiSectDisciples = updatedData.aiSectDisciples
                    .toMutableMap().apply {
                        this[result.attackerSectId] =
                            updatedAttacker + updatedDefender
                        this[result.defenderSectId] = emptyList()
                    }
            )
        }
    }

    private fun updatePlayerGarrisonState(
        state: MutableGameState,
        result: AISectAttackManager.AIAttackResult,
        tables: DiscipleTables,
        gameYear: Int
    ) {
        if (result.deadDefenderIds.isEmpty() &&
            result.defenderSurvivorHpMap.isEmpty()
        ) return
        val current = tables.assembleAll()
        val updated = current.map { d ->
            if (d.id in result.deadDefenderIds) {
                // 死亡标记由 DiscipleDeathHandler 统一写入列（见下方 markAllDead）
                d
            } else {
                val hp = result.defenderSurvivorHpMap[d.id]
                val mp = result.defenderSurvivorMpMap[d.id]
                if (hp != null && mp != null) {
                    // clamp 上限用含血炼口径（P2 对抗性审查修复），防削血
                    val (finalMaxHp, finalMaxMp) = DiscipleStatCalculator.battleWritebackMaxHpMp(state, d)
                    d.copy(
                        combat = d.combat.copy(
                            currentHp = hp.coerceIn(0, finalMaxHp),
                            currentMp = mp.coerceIn(0, finalMaxMp)
                        )
                    )
                } else d
            }
        }
        tables.replaceAll(updated)
        // 死亡标记 + deathYears 统一由 DiscipleDeathHandler 写入列
        deathHandler.markAllDead(state, result.deadDefenderIds.toSet(), gameYear)
    }

    private fun buildGarrSlots(
        survivors: List<Disciple>
    ): List<GarrisonSlot> {
        return (0 until 10).map { i ->
            if (i < survivors.size) {
                val d = survivors[i]
                GarrisonSlot(
                    index = i, discipleId = d.id,
                    discipleName = d.name,
                    discipleRealm = d.realmName,
                    discipleSpiritRootColor = d.spiritRoot.countColor,
                    portraitRes = d.portraitRes
                )
            } else GarrisonSlot(index = i)
        }
    }
}
