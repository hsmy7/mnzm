package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.engine.SectCombatPowerCalculator
import com.xianxia.sect.core.engine.annotation.GameService
import com.xianxia.sect.core.engine.domain.battle.AISectAttackManager
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SectBattleType
import com.xianxia.sect.core.model.SpiritStoneGrade
import com.xianxia.sect.core.model.VassalContract
import com.xianxia.sect.core.state.GameStateStore
import com.xianxia.sect.core.state.MutableGameState
import com.xianxia.sect.core.util.DomainLog
import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.wallet.DeductResult
import com.xianxia.sect.core.wallet.SpiritStoneReason
import com.xianxia.sect.core.wallet.SpiritStoneSource
import com.xianxia.sect.core.wallet.SpiritStoneWallet
import kotlin.math.max
import kotlin.random.Random
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
    private val spiritStoneWallet: SpiritStoneWallet
) {

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

        // 战力差 40%
        val powerScore = when {
            powerRatio >= 5.0 -> 0.40
            powerRatio >= 3.0 -> 0.30
            powerRatio >= 2.0 -> 0.20
            powerRatio >= GameConfig.Vassal.POWER_RATIO_MIN -> 0.10
            else -> 0.0
        }

        // 占领丢失 30%
        val totalOccupy = conquestCount + lostSectCount
        val occupyRatio = if (totalOccupy > 0) {
            conquestCount.toDouble() / totalOccupy
        } else 0.5
        val occupyScore = occupyRatio * 0.30

        // 胜负 15%
        val totalSkirmish = battleWinCount + battleLossCount
        val skirmishRatio = if (totalSkirmish > 0) {
            battleWinCount.toDouble() / totalSkirmish
        } else 0.5
        val skirmishScore = skirmishRatio * 0.15

        // 好感度 15%
        val favorScore = (favor.toDouble() / 100.0) * 0.15

        val total = powerScore + occupyScore + skirmishScore + favorScore
        return total.coerceIn(0.0, 0.95)
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
            playerPower, aiPower,
            conquestCount, lostSectCount,
            battleWinCount, battleLossCount, favor
        )
        val success = Random.nextDouble() < chance

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

            val amount = GameConfig.Vassal.TRIBUTE_BY_SECT_LEVEL[
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
    fun processMonthlyBreakawayCheck(year: Int, month: Int) {
        val data = stateStore.gameData.value
        val contracts = data.vassalContracts
        if (contracts.isEmpty()) return

        val playerPower = computePlayerTotalPower()
        val playerSect = data.worldMapSects.find {
            it.isPlayerSect
        } ?: return
        val recentRecords = data.sectBattleRecords.filter {
            it.year >= data.gameYear - 3
        }
        val conquests = recentRecords.count {
            it.type == SectBattleType.CONQUEST
        }
        val losses = recentRecords.count {
            it.type == SectBattleType.LOST_SECT
        }
        val battleWins = recentRecords.count {
            it.type == SectBattleType.BATTLE_WIN
        }
        val battleLosses = recentRecords.count {
            it.type == SectBattleType.BATTLE_LOSS
        }

        var changed = false
        val removedIds = mutableListOf<String>()

        for (contract in contracts) {
            if (checkSingleVassalBreakaway(
                contract, data, playerPower, playerSect,
                conquests, losses, battleWins, battleLosses
            )) {
                removedIds.add(contract.vassalSectId)
                changed = true
            }
        }

        if (changed) {
            stateStore.update {
                gameData = gameData.copy(
                    vassalContracts = gameData.vassalContracts.filter {
                        it.vassalSectId !in removedIds
                    }
                )
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

        val aiPower = computeAITotalPower(contract.vassalSectId)
        if (aiPower <= 0) return false
        val powerRatio = playerPower / aiPower

        // 战力差 40%
        val baseBreak = when {
            powerRatio >= 5.0 -> 0.0
            powerRatio >= 3.0 -> 0.05
            powerRatio >= 2.0 -> 0.12
            powerRatio >= 1.5 -> 0.20
            else -> 0.35
        }
        val powerScore = baseBreak * 0.40 / 0.35

        // 占领丢失 30%
        val totalOcc = conquests + losses
        val occLoss = if (totalOcc > 0) {
            losses.toDouble() / totalOcc
        } else 0.0
        val occupyScore = occLoss * 0.30

        // 胜负 15%
        val totalSk = battleWins + battleLosses
        val skLoss = if (totalSk > 0) {
            battleLosses.toDouble() / totalSk
        } else 0.0
        val skirmishScore = skLoss * 0.15

        // 好感度 15%
        val favor = FavorDomain.findRelation(
            data.sectRelations, playerSect.id, contract.vassalSectId
        )?.favor ?: 50
        val favorScore =
            max(0.0, (50 - favor).toDouble() / 100.0) * 0.15

        val breakChance = (
            powerScore + occupyScore
                + skirmishScore + favorScore
            ).coerceIn(0.0, 0.40)

        if (Random.nextDouble() < breakChance) {
            DomainLog.i(TAG,
                "AI附属脱离: sectId=${contract.vassalSectId}, " +
                "powerRatio=${"%.2f".format(powerRatio)}, " +
                "breakChance=${"%.2f".format(breakChance)}")
            return true
        }
        return false
    }

    // ═══════════════════════════
    // 私有辅助方法
    // ═══════════════════════════

    /** 计算玩家宗门总战力 */
    private fun computePlayerTotalPower(): Double {
        val disciples = stateStore.discipleTables.assembleAll()
        return AISectAttackManager.calculatePowerScore(disciples)
    }

    /** 计算AI宗门总战力 */
    private fun computeAITotalPower(sectId: String): Double {
        val data = stateStore.gameData.value
        val aiDisciples = data.aiSectDisciples[sectId]
            ?: emptyList()
        return AISectAttackManager.calculatePowerScore(aiDisciples)
    }
}
