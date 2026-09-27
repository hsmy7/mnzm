package com.xianxia.sect.core.config

import com.xianxia.sect.core.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守卫测试：验证 [GameConfig]（编译期常量）与 [GameConfigData]（JSON 配置/DTO 默认值）
 * 之间的同义字段数值一致。
 *
 * 两套配置独立维护，新增字段或修改值时容易产生偏差。
 * 本测试在偏差发生时失败，提示开发者同步两处。
 *
 * 覆盖范围：GameConfigData 中有明确对应 GameConfig 常量的所有字段。
 */
class GameConfigConsistencyTest {

    // ── Production ──
    @Test
    fun `spiritMineBaseOutputPerMiner 两源一致`() {
        val data = GameConfigData().production
        assertEquals(GameConfig.Production.SPIRIT_MINE_BASE_OUTPUT_PER_MINER, data.spiritMineBaseOutputPerMiner)
    }

    @Test
    fun `spiritMineMiningThreshold 两源一致`() {
        val data = GameConfigData().production
        assertEquals(GameConfig.Production.SPIRIT_MINE_MINING_THRESHOLD, data.spiritMineMiningThreshold)
    }

    @Test
    fun `spiritMineMiningBonusRate 两源一致`() {
        val data = GameConfigData().production
        assertEquals(
            GameConfig.Production.SPIRIT_MINE_MINING_BONUS_RATE,
            data.spiritMineMiningBonusRate,
            0.001
        )
    }

    // ── Warehouse ──
    @Test
    fun `baseCapacity 两源一致`() {
        val data = GameConfigData().warehouse
        assertEquals(GameConfig.Warehouse.BASE_CAPACITY, data.baseCapacity)
    }

    @Test
    fun `capacityPerBuilding 两源一致`() {
        val data = GameConfigData().warehouse
        assertEquals(GameConfig.Warehouse.CAPACITY_PER_BUILDING, data.capacityPerBuilding)
    }

    // ── Battle.RealmGap ──
    @Test
    fun `damageBonusPerLayer 两源一致`() {
        val data = GameConfigData().battle.realmGap
        assertEquals(
            GameConfig.Battle.RealmGap.DAMAGE_BONUS_PER_LAYER,
            data.damageBonusPerLayer,
            0.001
        )
    }

    @Test
    fun `damageReductionPerLayer 两源一致`() {
        val data = GameConfigData().battle.realmGap
        assertEquals(
            GameConfig.Battle.RealmGap.DAMAGE_REDUCTION_PER_LAYER,
            data.damageReductionPerLayer,
            0.001
        )
    }

    @Test
    fun `damageBonusPerMajorRealm 两源一致`() {
        val data = GameConfigData().battle.realmGap
        assertEquals(
            GameConfig.Battle.RealmGap.DAMAGE_BONUS_PER_MAJOR_REALM,
            data.damageBonusPerMajorRealm,
            0.001
        )
    }

    // ── HerbGarden ──
    @Test
    fun `auraRadiusTiles 两源一致`() {
        val data = GameConfigData().herbGarden
        assertEquals(GameConfig.HerbGarden.AURA_RADIUS_TILES, data.auraRadiusTiles, 0.001)
    }

    // ── PlayerProtection ──
    @Test
    fun `protectionYears 两源一致`() {
        val data = GameConfigData().playerProtection
        assertEquals(GameConfig.PlayerProtection.PROTECTION_YEARS, data.protectionYears)
    }

    // ── Disciple ──
    @Test
    fun `年龄最大值两源一致`() {
        val data = GameConfigData().disciple
        assertEquals(GameConfig.Disciple.MAX_AGE, data.maxAge)
    }

    @Test
    fun `保护月数两源一致`() {
        val data = GameConfigData().disciple
        assertEquals(GameConfig.Disciple.PROTECTION_MONTHS, data.protectionMonths)
    }

    // ── Elder ──
    @Test
    fun `副宗主境界两源一致`() {
        val data = GameConfigData().elder
        assertEquals(GameConfig.Elder.REALM_VICE_SECT_MASTER, data.realmViceSectMaster)
    }

    @Test
    fun `长老境界两源一致`() {
        val data = GameConfigData().elder
        assertEquals(GameConfig.Elder.REALM_ELDER, data.realmElder)
    }

    @Test
    fun `讲道长老境界两源一致`() {
        val data = GameConfigData().elder
        assertEquals(GameConfig.Elder.REALM_PREACHING_MASTER, data.realmPreachingMaster)
    }

    // ── Time ──
    @Test
    fun `每月旬数两源一致`() {
        val data = GameConfigData().time
        assertEquals(GameConfig.Time.PHASES_PER_MONTH, data.phasesPerMonth)
    }

    @Test
    fun `每年月数两源一致`() {
        val data = GameConfigData().time
        assertEquals(GameConfig.Time.MONTHS_PER_YEAR, data.monthsPerYear)
    }

    @Test
    fun `最大探索月数两源一致`() {
        val data = GameConfigData().time
        assertEquals(GameConfig.Time.MAX_EXPLORE_TIME, data.maxExploreTime)
    }

    // ── Battle (基础字段) ──
    @Test
    fun `最大队伍人数两源一致`() {
        val data = GameConfigData().battle
        assertEquals(GameConfig.Battle.MAX_TEAM_SIZE, data.maxTeamSize)
    }

    @Test
    fun `最小妖兽数量两源一致`() {
        val data = GameConfigData().battle
        assertEquals(GameConfig.Battle.MIN_BEAST_COUNT, data.minBeastCount)
    }

    @Test
    fun `最大妖兽数量两源一致`() {
        val data = GameConfigData().battle
        assertEquals(GameConfig.Battle.MAX_BEAST_COUNT, data.maxBeastCount)
    }

    @Test
    fun `最大回合数两源一致`() {
        val data = GameConfigData().battle
        assertEquals(GameConfig.Battle.MAX_TURNS, data.maxTurns)
    }

    @Test
    fun `最大闪避概率两源一致`() {
        val data = GameConfigData().battle
        assertEquals(GameConfig.Battle.MAX_DODGE_CHANCE, data.maxDodgeChance, 0.001)
    }

    @Test
    fun `防御常数两源一致`() {
        val data = GameConfigData().battle
        assertEquals(GameConfig.Battle.DEFENSE_CONSTANT, data.defenseConstant, 0.001)
    }

    @Test
    fun `伤害方差百分比两源一致`() {
        val data = GameConfigData().battle
        assertEquals(GameConfig.Battle.DAMAGE_VARIANCE_PERCENT, data.damageVariancePercent, 0.001)
    }

    // ── Cultivation ──
    @Test
    fun `每旬HPMP恢复率两源一致`() {
        val data = GameConfigData().cultivation
        assertEquals(GameConfig.Cultivation.PHASE_HP_MP_RECOVERY_RATE, data.dailyHpMpRecoveryRate, 0.001)
    }

    // ── Rarity ──
    @Test
    fun `出售价格倍率两源一致`() {
        val data = GameConfigData().rarity
        assertEquals(GameConfig.Rarity.SELL_PRICE_MULTIPLIER, data.sellPriceMultiplier, 0.001)
    }

    // ── SectMap ──
    @Test
    fun `地图格大小两源一致`() {
        val data = GameConfigData().sectMap
        assertEquals(GameConfig.SectMap.TILE_SIZE, data.tileSize)
    }

    @Test
    fun `世界宽度两源一致`() {
        val data = GameConfigData().sectMap
        assertEquals(GameConfig.SectMap.WORLD_WIDTH_CELLS, data.worldWidthCells)
    }

    @Test
    fun `世界高度两源一致`() {
        val data = GameConfigData().sectMap
        assertEquals(GameConfig.SectMap.WORLD_HEIGHT_CELLS, data.worldHeightCells)
    }

    // ── Performance ──
    @Test
    fun `最大tick采样数两源一致`() {
        val data = GameConfigData().performance
        assertEquals(GameConfig.Performance.MAX_TICK_SAMPLES, data.maxTickSamples)
    }

    // ── AI ──
    @Test
    fun `AI进攻最小弟子数两源一致`() {
        val data = GameConfigData().ai
        assertEquals(GameConfig.AI.MIN_DISCIPLES_FOR_ATTACK, data.minDisciplesForAttack)
    }

    @Test
    fun `AI战力比阈值两源一致`() {
        val data = GameConfigData().ai
        assertEquals(GameConfig.AI.POWER_RATIO_THRESHOLD, data.powerRatioThreshold, 0.001)
    }

    @Test
    fun `AI队伍大小两源一致`() {
        val data = GameConfigData().ai
        assertEquals(GameConfig.AI.TEAM_SIZE, data.teamSize)
    }

    // ── Logs ──
    @Test
    fun `最大战斗日志两源一致`() {
        val data = GameConfigData().logs
        assertEquals(GameConfig.Logs.MAX_BATTLE_LOGS, data.maxBattleLogs)
    }

    @Test
    fun `最大事件日志两源一致`() {
        val data = GameConfigData().logs
        assertEquals(GameConfig.Logs.MAX_EVENT_LOGS, data.maxEventLogs)
    }
}
