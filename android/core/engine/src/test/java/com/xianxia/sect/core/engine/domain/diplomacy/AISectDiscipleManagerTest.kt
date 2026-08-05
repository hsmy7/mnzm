package com.xianxia.sect.core.engine.domain.diplomacy

import com.xianxia.sect.core.GameConfig
import com.xianxia.sect.core.SectLevel
import com.xianxia.sect.core.engine.ManualProficiencySystem
import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.PlantSlotData
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.registry.EquipmentDatabase
import com.xianxia.sect.core.registry.ManualDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AISectDiscipleManagerTest {

    @After
    fun tearDown() {
        // 恢复全局单例，避免注入的测试功法库污染其他测试类
        ManualDatabase.resetForTest()
    }

    // ── truncateToLimit ──
    // 回归覆盖：战胜AI宗门后玩家宗门涌入1000+弟子 (commit f8475620)
    // 合并逻辑曾使截断失效，这里直接验证 truncateToLimit 纯函数行为。

    @Test
    fun `truncateToLimit - 空列表原样返回`() {
        val result = AISectDiscipleManager.truncateToLimit(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `truncateToLimit - 未超上限原样返回`() {
        val disciples = (0 until 100).map { makeDisciple("d$it", power = it * 10) }
        val result = AISectDiscipleManager.truncateToLimit(disciples)
        assertEquals(100, result.size)
        // 顺序保持不变（未触发排序）
        assertEquals(disciples, result)
    }

    @Test
    fun `truncateToLimit - 刚好等于上限原样返回`() {
        val limit = PlantSlotData.MAX_AI_DISCIPLES_PER_SECT
        val disciples = (0 until limit).map { makeDisciple("d$it", power = it) }
        val result = AISectDiscipleManager.truncateToLimit(disciples)
        assertEquals(limit, result.size)
    }

    @Test
    fun `truncateToLimit - 超过上限截断至上限`() {
        val limit = PlantSlotData.MAX_AI_DISCIPLES_PER_SECT
        val disciples = (0 until limit + 50).map { makeDisciple("d$it", power = it) }
        val result = AISectDiscipleManager.truncateToLimit(disciples)
        assertEquals(limit, result.size)
    }

    @Test
    fun `truncateToLimit - 按战力降序保留强者`() {
        // 战力 = basePhysicalAttack + baseMagicAttack + baseHp
        // weak:   10 + 10 + 120 = 140
        // filler: 50 + 50 + 120 = 220
        // strong: 100 + 100 + 120 = 320
        val weak = (0 until 10).map { makeDisciple("weak_$it", pa = 10, ma = 10, hp = 120) }
        val strong = (0 until 10).map { makeDisciple("strong_$it", pa = 100, ma = 100, hp = 120) }
        // filler 数量 = 上限 - strong 数量，确保 weak 被完全淘汰
        val fillerCount = PlantSlotData.MAX_AI_DISCIPLES_PER_SECT - strong.size
        val filler = (0 until fillerCount).map { makeDisciple("filler_$it", pa = 50, ma = 50, hp = 120) }
        val all = strong + filler + weak
        val result = AISectDiscipleManager.truncateToLimit(all)
        assertEquals(PlantSlotData.MAX_AI_DISCIPLES_PER_SECT, result.size)
        // 强者战力 320 必须全部保留
        val strongSurvivors = result.filter { it.id.startsWith("strong_") }
        assertEquals(10, strongSurvivors.size)
        // 弱者战力 140 应被淘汰（filler 战力 220 优先于 weak）
        val weakSurvivors = result.filter { it.id.startsWith("weak_") }
        assertEquals(0, weakSurvivors.size)
    }

    @Test
    fun `truncateToLimit - 战力相同时不丢数据`() {
        val limit = PlantSlotData.MAX_AI_DISCIPLES_PER_SECT
        val disciples = (0 until limit + 10).map { makeDisciple("d$it", pa = 50, ma = 50, hp = 120) }
        val result = AISectDiscipleManager.truncateToLimit(disciples)
        assertEquals(limit, result.size)
        // 全部战力相同，截断后应保留 limit 个不同 id（无重复）
        assertEquals(result.map { it.id }.toSet().size, result.size)
    }

    @Test
    fun `truncateToLimit - 单次大批量涌入被截断`() {
        // 模拟路径A：多年累积后单次涌入远超上限
        val limit = PlantSlotData.MAX_AI_DISCIPLES_PER_SECT
        val disciples = (0 until limit * 3).map { makeDisciple("d$it", power = it) }
        val result = AISectDiscipleManager.truncateToLimit(disciples)
        assertEquals(limit, result.size)
        // 保留的应是战力最高的 limit 个（id 后段）
        val maxId = result.maxOf { it.id.removePrefix("d").toInt() }
        assertTrue("应保留高战力弟子", maxId >= limit * 3 - 1)
    }

    // ── 境界-年龄匹配（38岁炼虚修复）──

    @Test
    fun `fillDisciplesToTarget - 高境界弟子 年龄不低于境界最小年龄`() {
        AISectDiscipleManager.initForSlot(42L)
        // 大型宗门（level 2）可出炼虚/合体（realm 4/3）
        val result = AISectDiscipleManager.fillDisciplesToTarget(
            sectName = "测试大宗",
            existingDisciples = emptyList(),
            targetCount = 50,
            sectLevel = 2
        )
        assertEquals(50, result.size)
        for (d in result) {
            assertTrue(
                "境界 ${d.realm} 弟子年龄 ${d.age} 应不低于最小合理年龄 " +
                    "${GameConfig.Realm.minReasonableAge(d.realm)}",
                d.age >= GameConfig.Realm.minReasonableAge(d.realm)
            )
        }
    }

    @Test
    fun `fillDisciplesToTarget - 炼气弟子 年龄保持16到29`() {
        AISectDiscipleManager.initForSlot(7L)
        // 小型宗门（level 1，分布 7..9：金丹/筑基/炼气）
        val result = AISectDiscipleManager.fillDisciplesToTarget(
            sectName = "测试小宗",
            existingDisciples = emptyList(),
            targetCount = 50,
            sectLevel = 1
        )
        val qiRefining = result.filter { it.realm == 9 }
        assertTrue("应包含炼气弟子", qiRefining.isNotEmpty())
        for (d in qiRefining) {
            assertTrue("炼气弟子年龄应在 16..29", d.age in 16..29)
        }
    }

    @Test
    fun `initializeSectDisciples - 全部弟子年龄境界匹配`() {
        AISectDiscipleManager.initForSlot(99L)
        val (disciples, _) = AISectDiscipleManager.initializeSectDisciples(
            sectName = "测试宗门",
            sectLevel = 2
        )
        assertTrue("应生成弟子", disciples.isNotEmpty())
        for (d in disciples) {
            assertTrue(
                "境界 ${d.realm} 弟子年龄 ${d.age} 应不低于最小合理年龄",
                d.age >= GameConfig.Realm.minReasonableAge(d.realm)
            )
        }
    }

    // ── AI 弟子完整化：体质/词条/装备/功法 ──

    @Test
    fun `generateRandomDisciple - 生成体质与词条`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        val disciple = AISectDiscipleManager.generateRandomDisciple("测试宗", 9)
        assertNotNull("应生成体质列表", disciple.physiqueIds)
        assertNotNull("应生成词条列表", disciple.affixIds)
        // 0-3 个随机生成（可能为 0），但生成器必须可从数据库解析（不产生悬空 id）
        disciple.physiqueIds.forEach { id ->
            assertNotNull(
                "体质 id=$id 应存在于 PhysiqueDatabase",
                com.xianxia.sect.core.registry.PhysiqueDatabase.getById(id)
            )
        }
    }

    @Test
    fun `applyGearToDisciple - 装备功法数量按宗门等级`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        val expected = mapOf(
            SectLevel.SMALL to Pair(1, 1),
            SectLevel.MEDIUM to Pair(2, 3),
            SectLevel.LARGE to Pair(4, 6),
            SectLevel.TOP to Pair(4, 6)
        )
        for ((level, counts) in expected) {
            val disciple = AISectDiscipleManager.applyGearToDisciple(
                makeGearDisciple(realm = 5), level
            )
            assertEquals("宗门等级 $level 装备数量", counts.first, disciple.equipment.equippedItemIds.size)
            assertEquals("宗门等级 $level 功法数量", counts.second, disciple.manualIds.size)
        }
    }

    @Test
    fun `applyGearToDisciple - 品阶恒为境界上限`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        val disciple = AISectDiscipleManager.applyGearToDisciple(
            makeGearDisciple(realm = 5), SectLevel.LARGE
        )
        val maxRarity = GameConfig.Realm.getMaxRarity(5)
        for (eqId in disciple.equipment.equippedItemIds) {
            assertEquals("装备品阶应为境界上限", maxRarity, EquipmentDatabase.getById(eqId)?.rarity)
        }
        for (mId in disciple.manualIds) {
            assertEquals("功法品阶应为境界上限", maxRarity, ManualDatabase.getById(mId)?.rarity)
        }
    }

    @Test
    fun `applyGearToDisciple - 同 seed 确定性`() {
        ManualDatabase.initializeWithManuals(testManuals())
        AISectDiscipleManager.initForSlot(42L)
        val d1 = AISectDiscipleManager.applyGearToDisciple(makeGearDisciple(realm = 5), SectLevel.MEDIUM)
        AISectDiscipleManager.initForSlot(42L)
        val d2 = AISectDiscipleManager.applyGearToDisciple(makeGearDisciple(realm = 5), SectLevel.MEDIUM)
        assertEquals("同种子下装备/功法生成应完全一致", d1.equipment, d2.equipment)
        assertEquals(d1.manualIds, d2.manualIds)
        assertEquals(d1.manualMasteries, d2.manualMasteries)
    }

    @Test
    fun `processMonthlyCultivation - 修炼吃功法加成`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        val base = makeGearDisciple(realm = 7, cultivation = 0.0)
        val noManual = base.copy(manualIds = emptyList(), manualMasteries = emptyMap())
        val withManual = base.copy(
            manualIds = listOf("atk_4_0"),
            manualMasteries = mapOf("atk_4_0" to 30000)  // 圆满 4.0 倍加成
        )
        val gainNoManual = AISectDiscipleManager.processMonthlyCultivation(listOf(noManual), 1, SectLevel.SMALL)
            .first().cultivation
        val gainWithManual = AISectDiscipleManager.processMonthlyCultivation(listOf(withManual), 1, SectLevel.SMALL)
            .first().cultivation
        assertTrue("带功法（圆满熟练度）的修炼增量应更大: $gainNoManual vs $gainWithManual", gainWithManual > gainNoManual)
    }

    @Test
    fun `processMonthlyCultivation - 修炼吃体质加成`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        val base = makeGearDisciple(realm = 7, cultivation = 0.0)
        val noPhysique = base.copy(physiqueIds = emptyList())
        // r3_phys_cult_speed：3 阶修炼速度体质（+28%）
        val withPhysique = base.copy(physiqueIds = listOf("r3_phys_cult_speed"))
        val gainNo = AISectDiscipleManager.processMonthlyCultivation(listOf(noPhysique), 1, SectLevel.SMALL)
            .first().cultivation
        val gainWith = AISectDiscipleManager.processMonthlyCultivation(listOf(withPhysique), 1, SectLevel.SMALL)
            .first().cultivation
        assertTrue("带修炼速度体质的修炼增量应更大: $gainNo vs $gainWith", gainWith > gainNo)
    }

    @Test
    fun `processMonthlyCultivation - 突破大境界刷新装备`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        // realm 6 满修为弟子（大境界突破后 realm 5，装备品阶应升至 4）
        val disciple = AISectDiscipleManager.applyGearToDisciple(
            makeGearDisciple(realm = 6, realmLayer = 3, cultivation = 9999999.0, lifespan = 300),
            SectLevel.MEDIUM
        )
        val result = AISectDiscipleManager.processMonthlyCultivation(listOf(disciple), 1, SectLevel.MEDIUM)
            .first()
        if (result.realm == 5) {
            val maxRarity = GameConfig.Realm.getMaxRarity(5)
            for (eqId in result.equipment.equippedItemIds) {
                assertEquals("突破后装备品阶应为新境界上限", maxRarity, EquipmentDatabase.getById(eqId)?.rarity)
            }
            for (mId in result.manualIds) {
                assertEquals("突破后功法品阶应为新境界上限", maxRarity, ManualDatabase.getById(mId)?.rarity)
            }
        } else {
            // 突破判定失败：修为清零 + HP/MP 打一折（玩家同款失败路径）
            assertEquals(6, result.realm)
            assertEquals(0.0, result.cultivation, 0.0)
        }
    }

    @Test
    fun `processMonthlyCultivation - 突破失败HP和MP打一折`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        val disciple = makeGearDisciple(realm = 6, realmLayer = 3, cultivation = 9999999.0, lifespan = 300)
            .copy(combat = CombatAttributes(
                baseHp = 120, baseMp = 80,
                currentHp = 120, currentMp = 80
            ))
        val result = AISectDiscipleManager.processMonthlyCultivation(listOf(disciple), 1, SectLevel.SMALL)
            .first()
        if (result.realm == 6) {
            // 突破失败路径：修为清零 + HP/MP × 0.1（coerceAtLeast(1)）
            assertEquals(0.0, result.cultivation, 0.0)
            assertEquals(12, result.combat.currentHp)
            assertEquals(8, result.combat.currentMp)
        }
        // 若突破成功则本轮断言跳过（由上一测试覆盖刷新逻辑）
    }

    @Test
    fun `processMonthlyCultivation - 熟练度月度增长`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        val disciple = makeGearDisciple(realm = 7, cultivation = 0.0, comprehension = 90)
            .copy(manualIds = listOf("atk_4_0"), manualMasteries = mapOf("atk_4_0" to 100))
        val result = AISectDiscipleManager.processMonthlyCultivation(listOf(disciple), 2, SectLevel.SMALL)
            .first()
        val perMonthGain = ManualProficiencySystem.calculateProficiencyGainPerPhase(90, 0.0) * 3
        val expected = ((100 + perMonthGain).toInt() + perMonthGain).toInt()
        assertEquals("熟练度应逐月按玩家公式增长（1 月=3 旬）", expected, result.manualMasteries["atk_4_0"])
    }

    @Test
    fun `ensureDiscipleGear - 空分类补全写入roll标记防止重复消耗RNG`() {
        // 对抗审查修复：体质/词条/天赋为 0-3 随机，roll 出 0 时若每次读档重 roll
        // 会消耗 AI 分区 RNG 导致同档两次读档演化序列漂移——补全后须持久化标记收敛
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        val old = makeGearDisciple(realm = 5).copy(
            physiqueIds = emptyList(), affixIds = emptyList(), talentIds = emptyList()
        )

        val first = AISectDiscipleManager.ensureDiscipleGear(old, SectLevel.MEDIUM)
        val rolled = first.statusData?.get(AISectDiscipleManager.GEAR_ROLL_MARKER) == "1"
        assertTrue("补全后应写入 roll 标记", rolled)

        // 带标记弟子再次调用：不再 roll（physique/affix/talent 即使为空也不再生成）
        val second = AISectDiscipleManager.ensureDiscipleGear(first, SectLevel.MEDIUM)
        assertEquals("二次调用应保持与首次结果一致（无重复 roll）", first, second)
    }

    @Test
    fun `isGearCompleteForLevel - 数量达标判定`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        // 大型宗门标准：4 装 6 功法
        val full = AISectDiscipleManager.applyGearToDisciple(
            makeGearDisciple(realm = 5), SectLevel.LARGE
        )
        assertTrue("满配弟子应达标", AISectDiscipleManager.isGearCompleteForLevel(full, SectLevel.LARGE))
        // 只有 1 件装备 1 本功法的旧档弟子：达标判定须为 false（数量而非"有"）
        val partial = full.copy(
            equipment = full.equipment.copy(
                weaponId = full.equipment.weaponId, armorId = "", bootsId = "", accessoryId = ""
            ),
            manualIds = full.manualIds.take(1),
            manualMasteries = full.manualMasteries.filterKeys { it == full.manualIds.first() }
        )
        assertFalse("只有 1 装 1 功法不应达标", AISectDiscipleManager.isGearCompleteForLevel(partial, SectLevel.LARGE))
        // 小型宗门标准：1 装 1 功法——partial 达标
        assertTrue("小型宗门标准下应达标", AISectDiscipleManager.isGearCompleteForLevel(partial, SectLevel.SMALL))
    }

    @Test
    fun `ensureDiscipleGear - 只补缺不覆盖`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        val disciple = AISectDiscipleManager.applyGearToDisciple(
            makeGearDisciple(realm = 5), SectLevel.MEDIUM
        )
        val snapshot = disciple.copy(manualMasteries = disciple.manualMasteries.toMap())
        val after = AISectDiscipleManager.ensureDiscipleGear(disciple, SectLevel.MEDIUM)
        assertEquals("已齐备弟子调用 ensureDiscipleGear 后应逐字段不变", snapshot, after)
    }

    @Test
    fun `fillDisciplesToTarget - 老档弟子补全装备`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        // 老档弟子：无体质/词条/装备/功法（generateRandomDisciple 旧版产物）
        val old = makeGearDisciple(realm = 5).copy(
            physiqueIds = emptyList(), affixIds = emptyList(),
            equipment = com.xianxia.sect.core.model.EquipmentSet(),
            manualIds = emptyList(), manualMasteries = emptyMap()
        )
        val result = AISectDiscipleManager.fillDisciplesToTarget(
            sectName = "测试宗", existingDisciples = listOf(old),
            targetCount = 50, sectLevel = 2
        )
        assertEquals(50, result.size)
        val oldResult = requireNotNull(result.find { it.id == old.id })
        assertTrue("存量老档弟子应补全装备", oldResult.equipment.hasEquippedItems)
        assertTrue("存量老档弟子应补全功法", oldResult.manualIds.isNotEmpty())
        assertTrue("存量老档弟子应补全体质/词条", oldResult.physiqueIds.isNotEmpty() || oldResult.affixIds.isNotEmpty())
        val newbie = result.first { it.id != old.id }
        assertTrue("新补弟子应带装备", newbie.equipment.hasEquippedItems)
        assertTrue("新补弟子应带功法", newbie.manualIds.isNotEmpty())
    }

    @Test
    fun `prepareDisciplesForBattle - 不再随机生成且不改原弟子`() {
        AISectDiscipleManager.initForSlot(42L)
        ManualDatabase.initializeWithManuals(testManuals())
        val disciple = AISectDiscipleManager.applyGearToDisciple(
            makeGearDisciple(realm = 5), SectLevel.MEDIUM
        )
        val snapshot = disciple.copy(manualMasteries = disciple.manualMasteries.toMap())
        val r1 = AISectDiscipleManager.prepareDisciplesForBattle(listOf(disciple))
        val r2 = AISectDiscipleManager.prepareDisciplesForBattle(listOf(disciple))
        assertEquals("两次准备的装备实例应一致（无随机）", r1.equipmentMap.keys, r2.equipmentMap.keys)
        assertEquals("两次准备的功法实例应一致（无随机）", r1.manualMap.keys, r2.manualMap.keys)
        assertEquals("原弟子不应被修改", snapshot, disciple)
        assertEquals("应返回原弟子列表", listOf(disciple), r1.disciples)
    }

    // ── 周期性招募：数量范围 + 灵根分布 ──

    @Test
    fun `generateYearlyRecruits - 每周期招募1到5名`() {
        // 2026-08-06 需求：AI 宗门弟子生成从每年 0~6 改为每 3 年 1~5
        AISectDiscipleManager.initForSlot(20260806L)
        ManualDatabase.initializeWithManuals(testManuals())
        val existing = listOf(makeGearDisciple())
        repeat(200) { i ->
            val recruits = AISectDiscipleManager.generateYearlyRecruits("测试宗$i", existing, SectLevel.SMALL)
            assertTrue(
                "第 $i 次招募数 ${recruits.size} 应在 1..5",
                recruits.size in 1..5
            )
            recruits.forEach { recruit ->
                assertTrue("招募弟子 ${recruit.name} 应默认炼气境界", recruit.realm == 9)
            }
        }
    }

    @Test
    fun `generateRandomDisciple - AI弟子灵根根数分布与COUNT_WEIGHTS一致`() {
        // 守卫测试：AI 宗门弟子灵根生成与玩家宗门共用 SpiritRootGenerator + COUNT_WEIGHTS 概率表。
        // 若未来 AI 侧引入独立概率逻辑，本用例以确定性 RNG 大样本统计直接失败。
        AISectDiscipleManager.initForSlot(20260806L)
        ManualDatabase.initializeWithManuals(testManuals())
        val counts = mutableMapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 0)
        val sampleCount = 10000
        repeat(sampleCount) {
            val disciple = AISectDiscipleManager.generateRandomDisciple("测试宗", 9)
            val rootCount = disciple.spiritRootType.split(",").size
            assertTrue("灵根数 $rootCount 应在 1..5（实际值=${disciple.spiritRootType}）", rootCount in 1..5)
            counts[rootCount] = (counts[rootCount] ?: 0) + 1
        }
        val tolerance = 0.04
        for ((rootCount, weight) in GameConfig.SpiritRoot.COUNT_WEIGHTS) {
            val ratio = counts[rootCount]!!.toDouble() / sampleCount
            assertTrue(
                "AI弟子灵根 $rootCount 根比例 $ratio 偏离配置权重 $weight 超过容差 $tolerance",
                kotlin.math.abs(ratio - weight) <= tolerance
            )
        }
    }

    // ── 辅助 ──

    /** 注入测试功法库：每品阶 4 攻 4 防 1 心法（覆盖大型/顶级宗门 6 本功法需求） */
    private fun testManuals(): Map<String, ManualDatabase.ManualTemplate> {
        val manuals = mutableMapOf<String, ManualDatabase.ManualTemplate>()
        for (rarity in 1..6) {
            for (i in 0 until 4) {
                manuals["atk_${rarity}_$i"] = ManualDatabase.ManualTemplate(
                    id = "atk_${rarity}_$i", name = "攻击功法${rarity}-$i",
                    type = ManualType.ATTACK, rarity = rarity, description = "测试功法",
                    stats = mapOf("cultivationSpeedPercent" to rarity * 10)
                )
                manuals["def_${rarity}_$i"] = ManualDatabase.ManualTemplate(
                    id = "def_${rarity}_$i", name = "防御功法${rarity}-$i",
                    type = ManualType.DEFENSE, rarity = rarity, description = "测试功法",
                    stats = mapOf("cultivationSpeedPercent" to rarity * 10)
                )
            }
            manuals["mind_$rarity"] = ManualDatabase.ManualTemplate(
                id = "mind_$rarity", name = "心法${rarity}",
                type = ManualType.MIND, rarity = rarity, description = "测试功法",
                stats = mapOf("cultivationSpeedPercent" to rarity * 10)
            )
        }
        return manuals
    }

    private fun makeGearDisciple(
        realm: Int = 9,
        realmLayer: Int = 1,
        cultivation: Double = 0.0,
        lifespan: Int = 200,
        comprehension: Int = 50
    ): Disciple = Disciple(
        id = "gear_$realm",
        name = "测试弟子$realm",
        realm = realm,
        realmLayer = realmLayer,
        cultivation = cultivation,
        lifespan = lifespan,
        isAlive = true,
        // 占位标签：ensureDiscipleGear 只查空补全，非空即视为已具备
        talentIds = listOf("t1"),
        physiqueIds = listOf("p1"),
        affixIds = listOf("a1"),
        skills = com.xianxia.sect.core.model.SkillStats(comprehension = comprehension),
        combat = CombatAttributes(
            basePhysicalAttack = 50,
            baseMagicAttack = 50,
            baseHp = 120,
            baseMp = 80
        )
    )

    private fun makeDisciple(
        id: String,
        power: Int = 0,
        pa: Int = 12,
        ma: Int = 12,
        hp: Int = 120
    ): Disciple {
        // power 参数便捷设定整体战力（覆盖 pa/ma/hp 的和）
        val (actualPa, actualMa, actualHp) = if (power != 0) {
            Triple(power / 3, power / 3, power - 2 * (power / 3))
        } else {
            Triple(pa, ma, hp)
        }
        return Disciple(
            id = id,
            combat = CombatAttributes(
                basePhysicalAttack = actualPa,
                baseMagicAttack = actualMa,
                baseHp = actualHp
            )
        )
    }
}
