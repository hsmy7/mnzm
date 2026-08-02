package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.config.VassalConfig
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SectBattleType
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.GameEventCategory
import com.xianxia.sect.core.model.GameEventType
import com.xianxia.sect.core.model.VassalContract
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.DiscipleTables
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.state.recordGameEvent
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlin.math.max
import com.xianxia.sect.core.util.GameRngManager
import com.xianxia.sect.core.util.RngPartition
import com.xianxia.sect.core.model.AISectPersonality
import com.xianxia.sect.core.model.SectRelationLevel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 附庸/附属体系服务。
 * 涵盖两个方向：
 * 1. 附庸（玩家宗门）向主宗（AI宗门）缴纳年贡 — 已有
 * 2. 附属（AI宗门）向宗主（玩家宗门）缴纳年贡 — 新增
 */
@Singleton
@GameService("VassalService")
class VassalService @Inject constructor(
    private val stateStore: GameStateStore,
    private val spiritStoneWallet: SpiritStoneWallet,
    private val rngManager: GameRngManager
) {
    private val rng get() = rngManager.getRng(RngPartition.SYSTEM)

    companion object {
        private const val TAG = "VassalService"
    }

    // ═══════════════════════════
    // 已有：玩家是AI的附庸
    // ═══════════════════════════

    /** 建立附庸关系 */
    suspend fun establishVassalage(suzerainSectId: String) {
        stateStore.update {
            gameData = gameData.copy(suzerainSectId = suzerainSectId)
        }
    }

    /** 是否附庸 */
    fun isVassal(): Boolean =
        stateStore.gameData.value.suzerainSectId.isNotEmpty()

    /** 获取主宗ID，"" 表示独立宗门 */
    fun getSuzerainSectId(): String =
        stateStore.gameData.value.suzerainSectId

    /** 处理年贡（每年一月调用） */
    fun processYearlyTribute() {
        val data = stateStore.gameData.value
        val suzerainId = data.suzerainSectId
        if (suzerainId.isEmpty()) return
        val income = data.lastYearSpiritStoneIncome
        val tribute = max(
            (income * GameConfig.AIAttack.VASSAL_TRIBUTE_RATIO).toLong(),
            if (income > 0) GameConfig.AIAttack.VASSAL_TRIBUTE_MIN else 0L
        )
        if (tribute <= 0) return
        stateStore.update {
            val result = spiritStoneWallet.deduct(this, tribute, SpiritStoneGrade.LOW, SpiritStoneReason.VassalTribute, SpiritStoneSource.Internal)
            if (result !is DeductResult.Success) {
                DomainLog.w(TAG, "processYearlyTribute: 年贡扣除失败(tribute=$tribute, balance=${(result as? DeductResult.Insufficient)?.balance})")
            }
        }
    }

    /** 记录年收入供年贡计算 */
    suspend fun recordYearlyIncome() {
        stateStore.update {
            gameData = gameData.copy(
                lastYearSpiritStoneIncome = gameData.spiritStones
            )
        }
    }

    // ═══════════════════════════
    // 新增：AI是玩家的附属
    // ═══════════════════════════

    /** 判断某AI宗门是否为玩家的附属 */
    fun isPlayerVassal(sectId: String): Boolean =
        stateStore.gameData.value.vassalContracts.any {
            it.vassalSectId == sectId
        }

    /** 获取玩家所有附属宗门的ID列表 */
    fun getPlayerVassals(): List<String> =
        stateStore.gameData.value.vassalContracts.map {
            it.vassalSectId
        }

    /**
     * 计算AI接受附属的概率（纯函数，无副作用）
     *
     * 四因素权重：战力差40%、占领丢失30%、胜负15%、好感度15%
     *
     * 内部委托 [IntelligentSectDecisionEngine] 实现。
     * 好感度自动转为 [SectRelationLevel] 等级后传入引擎。
     */
    fun calculateVassalChance(
        playerPower: Double,
        aiPower: Double,
        conquestCount: Int,
        lostSectCount: Int,
        battleWinCount: Int,
        battleLossCount: Int,
        favor: Int
    ): Double {
        if (aiPower <= 0) return 0.0
        val powerRatio = playerPower / aiPower
        // NaN/Infinity 防御
        if (powerRatio.isNaN() || powerRatio.isInfinite()) return 0.0

        return IntelligentSectDecisionEngine.calculateChance(
            profile = IntelligentSectDecisionEngine.VASSAL_PROFILE,
            powerRatio = powerRatio,
            conquestCount = conquestCount,
            lostSectCount = lostSectCount,
            battleWinCount = battleWinCount,
            battleLossCount = battleLossCount,
            favorLevel = SectRelationLevel.fromFavor(favor)
        )
    }

    /**
     * 请求AI成为附属宗门（简化版，供聊天流使用）
     *
     * @param sectId 目标AI宗门ID
     * @return true=成功
     */
    suspend fun requestVassalContract(sectId: String): Boolean {
        val data = stateStore.gameData.value
        val aiSect = data.worldMapSects.find { it.id == sectId }
            ?: return false
        if (aiSect.isPlayerSect) return false

        // 已有附属关系则跳过
        if (data.vassalContracts.any {
            it.vassalSectId == sectId
        }) return false

        // 已结盟不可同时附属
        val hasAlliance = data.alliances.any {
            it.sectIds.contains("player")
                && it.sectIds.contains(sectId)
        }
        if (hasAlliance) return false

        // 计算双方战力
        val playerPower = computePlayerTotalPower()
        val aiPower = computeAITotalPower(sectId)

        // 计算好感度
        val playerSect = data.worldMapSects.find {
            it.isPlayerSect
        } ?: return false
        val favor = FavorDomain.findFavor(data.sectRelations, playerSect.id, sectId)

        // 计算战绩（仅宗门战，近3年）
        val recentRecords = data.sectBattleRecords.filter {
            it.year >= data.gameYear - 3
        }
        val conquestCount = recentRecords.count {
            it.type == SectBattleType.CONQUEST
        }
        val lostSectCount = recentRecords.count {
            it.type == SectBattleType.LOST_SECT
        }
        val battleWinCount = recentRecords.count {
            it.type == SectBattleType.BATTLE_WIN
        }
        val battleLossCount = recentRecords.count {
            it.type == SectBattleType.BATTLE_LOSS
        }

        val chance = calculateVassalChance(
            playerPower.toDouble(), aiPower.toDouble(),
            conquestCount, lostSectCount,
            battleWinCount, battleLossCount, favor
        )
        val success = rng.nextDouble() < chance

        if (success) {
            stateStore.update {
                gameData = gameData.copy(
                    sectRelations = FavorDomain.setAcquainted(
                        gameData.sectRelations,
                        playerSect.id,
                        sectId,
                        gameData.gameYear
                    ),
                    vassalContracts = gameData.vassalContracts
                        + VassalContract(
                        vassalSectId = sectId,
                        establishedYear = gameData.gameYear,
                        lastTributeYear = 0
                    )
                )
            }
        }

        return success
    }

    /**
     * 解除附属关系（玩家主动解散）
     *
     * @param sectId 目标AI宗门ID
     */
    suspend fun dissolveVassalContract(sectId: String): Boolean {
        stateStore.update {
            gameData = gameData.copy(
                vassalContracts = gameData.vassalContracts.filter {
                    it.vassalSectId != sectId
                }
            )
        }
        return true
    }

    /**
     * 处理玩家附属宗门年贡（每年一月调用）
     * 年贡直接从虚空生成加到玩家灵石。
     * 新建立的契约当年不计贡，从下一年开始。
     */
    fun processYearlyVassalTribute(year: Int) {
        val data = stateStore.gameData.value
        val updatedContracts = data.vassalContracts.toMutableList()
        var totalTribute = 0L
        var changed = false

        val ite = updatedContracts.listIterator()
        while (ite.hasNext()) {
            val contract = ite.next()
            // 新建立的契约当年不计贡
            if (contract.establishedYear >= year) continue
            if (contract.lastTributeYear >= year) continue

            val aiSect = data.worldMapSects.find {
                it.id == contract.vassalSectId
            }
            if (aiSect == null) {
                ite.remove()
                changed = true
                continue
            }

            val amount = VassalConfig.TRIBUTE_BY_SECT_LEVEL[
                aiSect.level
            ] ?: 50_000L
            totalTribute += amount
            ite.set(contract.copy(lastTributeYear = year))
            changed = true
        }

        if (changed) {
            stateStore.update {
                spiritStoneWallet.add(this, totalTribute, SpiritStoneGrade.LOW, SpiritStoneSource.Internal)
                gameData = gameData.copy(
                    vassalContracts = updatedContracts
                )
            }
        }
    }

    /**
     * 每月判定AI附属是否脱离。
     * 四因素权重同接受逻辑。
     */
    fun processMonthlyBreakawayCheck() {
        stateStore.update { processMonthlyBreakawayCheck(this) }
    }

    fun processMonthlyBreakawayCheck(state: MutableGameState) {
        val data = state.gameData
        val contracts = data.vassalContracts
        if (contracts.isEmpty()) return

        val playerPower = computePlayerTotalPower(state.discipleTables)
        val playerSect = data.worldMapSects.find {
            it.isPlayerSect
        } ?: return
        val recentRecords = data.sectBattleRecords.filter {
            it.year >= data.gameYear - 3
        }
        val conquests = recentRecords.count { it.type == SectBattleType.CONQUEST }
        val losses = recentRecords.count { it.type == SectBattleType.LOST_SECT }
        val battleWins = recentRecords.count { it.type == SectBattleType.BATTLE_WIN }
        val battleLosses = recentRecords.count { it.type == SectBattleType.BATTLE_LOSS }

        var changed = false
        val removedIds = mutableListOf<String>()

        for (contract in contracts) {
            if (checkSingleVassalBreakaway(
                contract, data, playerPower.toDouble(), playerSect,
                conquests, losses, battleWins, battleLosses
            )) {
                removedIds.add(contract.vassalSectId)
                changed = true
            }
        }

        if (changed) {
            state.gameData = state.gameData.copy(
                vassalContracts = state.gameData.vassalContracts.filter {
                    it.vassalSectId !in removedIds
                }
            )
            removedIds.forEach { sectId ->
                val sect = data.worldMapSects.find { it.id == sectId }
                if (sect != null) {
                    state.recordGameEvent(
                        GameEventCategory.WORLD, GameEventType.VASSAL_BREAKAWAY,
                        "${sect.name}脱离了附属关系"
                    )
                }
            }
        }
    }

    /** 检查单个附属是否脱离，true=脱离 */
    private fun checkSingleVassalBreakaway(
        contract: VassalContract,
        data: com.xianxia.sect.core.model.GameData,
        playerPower: Double,
        playerSect: com.xianxia.sect.core.model.WorldSect,
        conquests: Int, losses: Int,
        battleWins: Int, battleLosses: Int
    ): Boolean {
        val aiSect = data.worldMapSects.find {
            it.id == contract.vassalSectId
        } ?: return true // 宗门已不存在 → 移除

        // 使用传入的 data 快照计算 AI 战力，保持与快照一致
        val aiDisciples = data.aiSectDisciples[contract.vassalSectId] ?: emptyList()
        val aiPower = SectCombatPowerCalculator.calculateSectPower(aiDisciples)
        if (aiPower <= 0) return false

        val powerRatio = playerPower / aiPower.toDouble()

        // 委托共享引擎计算脱离概率
        val breakawayFavor = computeBreakawayFavor(data.sectRelations, playerSect.id, contract.vassalSectId)
        val favorLevel = SectRelationLevel.fromFavor(breakawayFavor)
        val breakChance = IntelligentSectDecisionEngine.calculateBreakawayChance(
            powerRatio = powerRatio,
            conquestCount = conquests,
            lostSectCount = losses,
            battleWinCount = battleWins,
            battleLossCount = battleLosses,
            favorLevel = favorLevel
        )

        if (rng.nextDouble() < breakChance) {
            DomainLog.i(TAG, "AI附属脱离: sectId=${contract.vassalSectId}, " +
                "powerRatio=${"%.2f".format(powerRatio)}, " +
                "breakChance=${"%.2f".format(breakChance)}")
            return true
        }
        return false
    }

    /** 获取脱离相关的好感度值 */
    private fun computeBreakawayFavor(
        sectRelations: List<com.xianxia.sect.core.model.SectRelation>,
        playerSectId: String,
        vassalSectId: String
    ): Int {
        return FavorDomain.findRelation(sectRelations, playerSectId, vassalSectId)?.favor
            ?: 50 // 默认 50
    }

    // ═══════════════════════════
    // 私有辅助方法
    // ═══════════════════════════

    /** 计算玩家宗门总战力（统一永久基础属性公式，无装备/功法估算项） */
    private fun computePlayerTotalPower(): Long {
        val disciples = stateStore.discipleTables.assembleAll()
        return SectCombatPowerCalculator.calculateSectPower(disciples)
    }

    /** 计算玩家宗门总战力（MutableGameState 重载，使用事务内数据） */
    private fun computePlayerTotalPower(tables: DiscipleTables): Long {
        val disciples = tables.assembleAll()
        return SectCombatPowerCalculator.calculateSectPower(disciples)
    }

    /** 计算AI宗门总战力 */
    private fun computeAITotalPower(sectId: String): Long {
        val data = stateStore.gameData.value
        val aiDisciples = data.aiSectDisciples[sectId]
            ?: emptyList()
        return SectCombatPowerCalculator.calculateSectPower(aiDisciples)
    }
}
