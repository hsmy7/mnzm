package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.Disciple
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.full.memberProperties

/**
 * 编译期安全网：Disciple 的每个主构造函数字段都必须归类到结算合并策略中。
 *
 * 任何人新增 Disciple 字段 → 此测试失败 → CI 变红 → 强制声明合并策略。
 * 结合 [GameStateStore.mergeDiscipleAfterSettlement] 中的字段分类文档，
 * 确保新增字段不会因遗漏而静默丢失或被错误覆盖。
 *
 * ## 字段分类
 *
 * | 类别 | 说明 | mergeDiscipleAfterSettlement 行为 |
 * |------|------|----------------------------------|
 * | SETTLEMENT_MODIFIED | 结算系统会修改 | 从 shadow 取值覆盖 |
 * | PLAYER_OPERATED | 玩家可操作 | 显式保留主状态值 |
 * | UNCHANGING | 结算中不变 | copy() 默认保留，无需显式声明 |
 * | COMPUTED | 非主构造函数属性 | 不参与合并 |
 */
class DiscipleMergeCoverageTest {

    // ============ 字段分类清单 ============

    /** 结算修改字段：在 mergeDiscipleAfterSettlement 中从 shadow 取值 */
    private val settlementModified = setOf(
        "cultivation",
        "realm", "realmLayer",
        "lifespan",
        "equipment",
        "combat",
        "manualIds",
        "skills",
        "cultivationSpeedBonus", "cultivationSpeedDuration",
        "pillEffects",
        "isAlive"
    )

    /** 玩家操作字段：在 mergeDiscipleAfterSettlement 中显式保留 */
    private val playerOperated = setOf(
        "discipleType",
        "status",
        "statusData"
    )

    /**
     * 不变字段：主构造函数中的字段，结算中不被修改，由 copy() 默认行为保留。
     *
     * 当新增主构造函数字段且结算不会修改它时，加到这里。
     */
    private val unchanging = setOf(
        "id", "slotId",
        "name", "surname",
        "spiritRootType",
        "age",
        "gender",
        "portraitRes",
        "talentIds",
        "manualMasteries",
        "autoLearnFromWarehouse",
        "soulPower",
        "cultivationCompletionMonth", "cultivationCompletionPhase",
        "manualCompletionMonth", "manualCompletionPhase",
        "equipmentNurturingCompletionMonth", "equipmentNurturingCompletionPhase",
        "social",
        "usage"
    )

    /**
     * 计算属性 / 委托属性：不在主构造函数中，不参与 copy() 合并。
     *
     * 新增 getter/委托属性时加到这里（通常这些属性名与主构造函数字段不同）。
     */
    private val computedProps = setOf(
        // --- 委托到 @Embedded 组件的属性 ---
        "baseHp", "baseMp", "basePhysicalAttack", "baseMagicAttack",
        "basePhysicalDefense", "baseMagicDefense", "baseSpeed",
        "hpVariance", "mpVariance", "physicalAttackVariance", "magicAttackVariance",
        "physicalDefenseVariance", "magicDefenseVariance", "speedVariance",
        "totalCultivation", "breakthroughCount", "breakthroughFailCount",
        "currentHp", "currentMp",
        "pillPhysicalAttackBonus", "pillMagicAttackBonus", "pillPhysicalDefenseBonus",
        "pillMagicDefenseBonus", "pillHpBonus", "pillMpBonus", "pillSpeedBonus",
        "pillEffectDuration", "pillCritRateBonus", "pillCritEffectBonus",
        "pillCultivationSpeedBonus", "pillSkillExpSpeedBonus", "pillNurtureSpeedBonus",
        "activePillCategory",
        "weaponId", "armorId", "bootsId", "accessoryId",
        "weaponNurture", "armorNurture", "bootsNurture", "accessoryNurture",
        "storageBagItems", "storageBagSpiritStones", "spiritStones",
        "partnerId", "partnerSectId", "parentId1", "parentId2",
        "lastChildYear", "childBirthMonth", "griefEndYear",
        "intelligence", "charm", "loyalty", "comprehension",
        "artifactRefining", "pillRefining", "spiritPlanting",
        "mining", "teaching", "morality",
        "salaryPaidCount", "salaryMissedCount",
        "monthlyUsedPillIds", "usedExtendLifePillIds",
        "recruitedMonth", "hasReviveEffect", "hasClearAllEffect",
        // --- 计算属性 ---
        "canCultivate", "realmName", "realmNameOnly", "maxCultivation",
        "cultivationProgress", "spiritRoot", "spiritRootName",
        "physicalAttack", "physicalDefense", "magicAttack", "magicDefense",
        "speed", "maxHp", "maxMp", "hpPercent", "mpPercent",
        "equippedItems", "learnedManuals",
        "genderName", "genderSymbol", "hasPartner",
        "comprehensionSpeedBonus"
    )

    // ============ 测试 ============

    /**
     * 编译器生成的合成参数，不是真实字段。
     * @Serializable 数据类会生成 seen0（默认参数位掩码）和 serializationConstructorMarker。
     */
    private val syntheticParams = setOf(
        "seen0", "seen1",
        "serializationConstructorMarker"
    )

    @Test
    fun `every Disciple primary constructor field MUST be categorized`() {
        // 主构造函数字段：data class 的参数（排除合成参数）
        val constructorParamNames = Disciple::class.constructors
            .first()
            .parameters
            .map { it.name!! }
            .filter { it !in syntheticParams }
            .toSet()

        val allCategorized = settlementModified + playerOperated + unchanging
        val uncategorized = constructorParamNames - allCategorized

        if (uncategorized.isNotEmpty()) {
            fail(
                "Disciple 主构造函数新增了未归类的字段:\n" +
                "  ${uncategorized.sorted().joinToString("\n  ")}\n\n" +
                "请在 DiscipleMergeCoverageTest 中将它们加入对应分类:\n" +
                "  - SETTLEMENT_MODIFIED: 结算系统会修改此字段\n" +
                "  - PLAYER_OPERATED: 玩家可操作此字段\n" +
                "  - UNCHANGING: 结算中不会修改，由 copy() 默认保留\n\n" +
                "同时请在 GameStateStore.mergeDiscipleAfterSettlement 中:\n" +
                "  - 若为 SETTLEMENT_MODIFIED → 加入 copy() 参数列表\n" +
                "  - 若为 PLAYER_OPERATED → 加入显式保留列表\n" +
                "  - 更新方法的 KDoc 字段分类表"
            )
        }
    }

    @Test
    fun `no field in settlementModified is also in playerOperated or unchanging`() {
        val overlap = settlementModified.intersect(playerOperated + unchanging)
        if (overlap.isNotEmpty()) {
            fail("字段同时出现在多个分类中: ${overlap.sorted()}")
        }
    }

    @Test
    fun `no field in playerOperated is also in unchanging`() {
        val overlap = playerOperated.intersect(unchanging)
        if (overlap.isNotEmpty()) {
            fail("字段同时出现在多个分类中: ${overlap.sorted()}")
        }
    }

    @Test
    fun `all categorized fields exist in Disciple primary constructor`() {
        val allProps = Disciple::class.memberProperties.map { it.name }.toSet()
        val allCategorized = settlementModified + playerOperated + unchanging
        val nonexistent = allCategorized - allProps

        if (nonexistent.isNotEmpty()) {
            fail(
                "以下字段在分类清单中但不存在于 Disciple 类的任何属性中:\n" +
                "  ${nonexistent.sorted().joinToString("\n  ")}\n\n" +
                "这些字段可能已被删除或重命名，请更新分类清单。"
            )
        }
    }
}
