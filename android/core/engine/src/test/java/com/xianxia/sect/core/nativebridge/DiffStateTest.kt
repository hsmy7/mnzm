package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.core.model.EquipmentInstance
import com.xianxia.sect.core.model.EquipmentSlot
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.Herb
import com.xianxia.sect.core.model.ManualInstance
import com.xianxia.sect.core.model.ManualStack
import com.xianxia.sect.core.model.ManualType
import com.xianxia.sect.core.model.Material
import com.xianxia.sect.core.model.MaterialCategory
import com.xianxia.sect.core.model.Pill
import com.xianxia.sect.core.model.PillCategory
import com.xianxia.sect.core.model.PillGrade
import com.xianxia.sect.core.model.Seed
import com.xianxia.sect.core.model.StorageBag
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffStateTest — 状态快照跨语言差分对拍。
 *
 * 守护目标：C++ GameCore（game-core 状态模型 + JSON 快照编解码）与 Kotlin
 * 快照协议（kotlinx.serialization JSON）字段名/值**逐位一致**——这是 UI 镜像
 * 同步与存档链路零改动的数据契约。
 *
 * 流程：Kotlin 构造受控样本（仅覆盖 C++ 已移植字段）→ kotlinx JSON 编码 →
 * JNI 导入 C++ → C++ 导出 → Kotlin 解码 → 与样本逐字段相等。
 * 未覆盖字段（嵌套对象，后续批次补齐）在 C++ 宽松导入下忽略，样本保持默认值。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffStateTest {

    // ignoreUnknownKeys：C++ 侧 P1-7 已将 deathYear 纳入弟子协议而 Kotlin
    // Disciple 镜像字段未落——对拍解码容忍协议超集（落地后可回收）
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `gameData snapshot round trip matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertRoundTrip(gameDataRoundTripSample())
    }

    @Test
    fun `disciples and items snapshot round trip matches Kotlin`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertRoundTrip(disciplesAndItemsRoundTripSample())
    }

    @Test
    fun `empty state round trip is accepted`() {
        assumeTrue(DiffRngBridge.isAvailable())
        DiffRngBridge.nativeCoreInit()
        assertRoundTrip(NativeGameState())
    }

    // ── 受控样本（拆分保证单函数 < 60 行，覆盖 C++ 已移植字段） ──────────────

    /** 受控样本：GameData 全字段（标量 + 嵌套），与 assertRoundTrip 逐字段对拍。 */
    private fun gameDataRoundTripSample(): NativeGameState = NativeGameState(
        gameData = GameData()
            .applyGameDataScalarsPart1()
            .applyGameDataScalarsPart2()
            .applyGameDataNestedPart1()
            .applyGameDataNestedPart2()
            .normalizedForRoundTrip()
    )

    /**
     * 结算改造 B3 归一化预置：C++ `ensureBaselineTimeAxis` 会在导入时把
     * "权威轴零值 + 日历非初值"状态回填为已归一化形态（方案 §4.1 读档归一化）。
     * 受控样本按同一公式（GameConfig.Time，与 C++ 双端守卫锁定）预置换算结果，
     * 保持"已归一化状态导入逐字段忠实"的被测契约。
     */
    private fun GameData.normalizedForRoundTrip(): GameData = apply {
        elapsedGameMs = com.xianxia.sect.core.GameConfig.Time
            .calendarToGameMs(gameYear, gameMonth, gamePhase)
        lastSettleGameMs = elapsedGameMs
        if (spiritMineLastSettledMonth > 0 && spiritMineLastSettledGameMs == 0L) {
            val abs = spiritMineLastSettledMonth
            spiritMineLastSettledGameMs = com.xianxia.sect.core.GameConfig.Time
                .calendarToGameMs((abs - 1) / 12 + 1, (abs - 1) % 12 + 1, 0)
        }
        productionSlots = productionSlots.map { s ->
            if (s.startedAtGameMs == 0L && s.startYear >= 1) {
                val startedAt = com.xianxia.sect.core.GameConfig.Time
                    .calendarToGameMs(s.startYear, s.startMonth, 0)
                s.copy(
                    startedAtGameMs = startedAt,
                    completeAtGameMs = startedAt + s.duration *
                        com.xianxia.sect.core.GameConfig.Time.GAME_SECONDS_PER_MONTH
                            .toLong() * 1000L
                )
            } else {
                s
            }
        }
    }

    /** 标量字段（第 1 组）：id → playerHasAttackedAI。 */
    private fun GameData.applyGameDataScalarsPart1(): GameData = apply {
        id = "sect-1"
        sectName = "青云宗"
        gameYear = 12
        gameMonth = 7
        gamePhase = 1
        spiritStones = 99999
        midGradeSpiritStones = 123
        highGradeSpiritStones = 45
        spiritHerbs = 6
        sectCultivation = 1234.5678
        autoSaveIntervalMonths = 3
        activeSectId = ""
        merchantLastRefreshYear = 5
        merchantRefreshCount = 3
        merchantRefreshChances = 1
        merchantLastRefreshChanceGrantYear = 4
        recruitCountThisMonth = 1
        jadeSymbols = 7
        jadeSymbolsToday = 2
        jadeDayAnchorMs = 1700000000000L
        jadeAccumMs = 55000L
        worldLevelLastRefreshMonth = 144
        rngStates = mapOf(0 to 123456789L, 3 to -987654321L)
        unlockedRecipes = listOf("pill:聚气丹", "forge:木剑")
        unlockedManuals = listOf("基础吐纳功")
        spiritMineExpansions = 2
        spiritMineLastSettledMonth = 100
        usedTeamNumbers = listOf(1, 2)
        battleTeamsInitialized = true
        lastSaveTime = 1700000000000L
        saveVersion = 2
        playerProtectionEnabled = true
        playerProtectionStartYear = 1
        playerHasAttackedAI = false
    }

    /** 标量字段（第 2 组）：playerAllianceSlots → yearlySalaryEnabled。 */
    private fun GameData.applyGameDataScalarsPart2(): GameData = apply {
        playerAllianceSlots = 3
        prisonerSpiritRootFilter = emptySet()
        guideClaimedRewardIds = setOf(1, 2)
        isGameOver = false
        soundEnabled = true
        musicEnabled = true
        usedRedeemCodes = listOf("CODE-1")
        watchedItemIds = listOf("pill:聚气丹")
        shownWarningStageIds = listOf("w1:WAR_DECLARATION")
        secretRealmCooldownYear = 30
        suzerainSectId = ""
        annualTotalIncome = 5000
        mapSeed = 42
        sectAttackCooldowns = mapOf("sect-a" to 120)
        guideCounters = mapOf("build_first" to 1L)
        annualIncomeBySource = mapOf("mine" to 1000L)
        annualExpenditureByReason = mapOf("salary" to 800L)
        annualTotalIncome = 10000L
        annualTotalExpenditure = 9000L
        annualAlchemyCount = 5
        annualForgeCount = 3
        annualHerbCount = 10
        annualNewDisciples = 2
        annualDeceasedDisciples = 1
        annualEquipmentBySource = mapOf("forge:3" to 2)
        annualPillBySource = mapOf("alchemy:HIGH" to 1)
        annualHerbBySource = mapOf("spirit_field" to 8)
        yearlySalary = mapOf(9 to 240, 8 to 720)
        yearlySalaryEnabled = mapOf(9 to true, 8 to true)
    }

    /** 嵌套字段（第 1 组）：sectPolicies → placedBuildings。 */
    private fun GameData.applyGameDataNestedPart1(): GameData = apply {
        sectPolicies = com.xianxia.sect.core.model.SectPolicies(
            spiritMineBoost = true, enhancedSecurity = true,
            autoMineRootCounts = listOf(1, 2), autoMineThreshold = 3,
            frugality = true
        )
        elderSlots = com.xianxia.sect.core.model.ElderSlots(
            viceSectMaster = "d-1", herbGardenElder = "d-2",
            herbGardenDisciples = listOf(
                com.xianxia.sect.core.model.DirectDiscipleSlot(
                    index = 0, discipleId = "d-3", discipleName = "王五",
                    discipleRealm = "筑基", discipleSpiritRootColor = "#FF0000", sectId = ""
                )
            )
        )
        travelingMerchantItems = listOf(
            com.xianxia.sect.core.model.MerchantItem(
                id = "m-1", name = "灵草", itemId = "herb-1", rarity = 2,
                price = 100, quantity = 5, description = "出售", obtainedYear = 3, obtainedMonth = 5
            )
        )
        worldLevels = listOf(
            com.xianxia.sect.core.model.WorldLevel(
                id = "wl-1", type = com.xianxia.sect.core.model.LevelType.BEAST,
                beastType = 1, realm = 5, realmLayer = 1, beastName = "妖狼",
                x = 10.5f, y = 20.5f, spawnYear = 3, spawnMonth = 1,
                expiryYear = 3, expiryMonth = 6, count = 2,
                defeated = true, beastMaxHp = 500, beastMaxMp = 100,
                beastAttack = 50,
                beastDefense = 30
            )
        )
        placedBuildings = listOf(
            com.xianxia.sect.core.model.GridBuildingData(
                buildingId = "forge", displayName = "锻造坊",
                gridX = 3, gridY = 4, width = 2, height = 2, instanceId = "b-1"
            )
        )
    }

    /** 嵌套字段（第 2 组）：alliances → mailRecords。 */
    private fun GameData.applyGameDataNestedPart2(): GameData = apply {
        alliances = listOf(
            com.xianxia.sect.core.model.Alliance(
                id = "a-1", sectIds = listOf("s-1", "s-2"), startYear = 2, initiatorId = "s-1"
            )
        )
        patrolConfig = com.xianxia.sect.core.model.PatrolConfig(
            targetRealms = setOf(5, 6, 7), maxBeastCount = 3
        )
        productionSlots = listOf(
            com.xianxia.sect.core.model.production.ProductionSlot(
                id = "ps-1", slotIndex = 0,
                buildingType = com.xianxia.sect.core.model.production.BuildingType.FORGE,
                buildingId = "b-1",
                status = com.xianxia.sect.core.model.production.ProductionSlotStatus.WORKING,
                recipeName = "青锋剑", startYear = 3, startMonth = 1,
                duration = 6, baseDuration = 6,
                outputItemName = "青锋剑", outputItemRarity = 3,
                outputItemSlot = com.xianxia.sect.core.model.EquipmentSlot.WEAPON.name,
                expectedYield = 1, completionMonth = 7, completionPhase = 0
            )
        )
        mailRecords = listOf(
            com.xianxia.sect.core.model.MailClaimRecord(
                mailId = "mail-1", claimedAt = 1700000000000L, source = "builtin"
            )
        )
    }

    /** 受控样本：弟子 + 物品全列表（与 assertRoundTrip 逐字段对拍）。 */
    private fun disciplesAndItemsRoundTripSample(): NativeGameState = NativeGameState(
        gameData = GameData().apply { gameYear = 3 }.normalizedForRoundTrip(),
        disciples = sampleDisciples(),
        // equipmentStacks 弃用载体无 C++ 运输面（B3 容器已删，导出恒空）——
        // 旧档载体走补偿链，不在镜像对拍样本内；实例轨见下
        equipmentInstances = listOf(
            EquipmentInstance(
                id = "eq-i1", name = "玄铁甲",
                part = EquipmentSlot.BODY,
                growth = com.xianxia.sect.core.model.EquipGrowth(
                    affix = com.xianxia.sect.core.model.EquipAffixSet(
                        mainStat = com.xianxia.sect.core.model.EquipStatValue(
                            com.xianxia.sect.core.model.EquipStat.DEFENSE, 15.0
                        )
                    )
                ),
                meta = com.xianxia.sect.core.model.EquipInstanceMeta(rarity = 4),
                ownerId = "d-1", isEquipped = true
            )
        ),
        manualStacks = listOf(
            ManualStack(
                id = "m-s1", name = "基础吐纳功", rarity = 1, quantity = 3,
                type = ManualType.MIND, stats = mapOf("cultivation" to 10),
                skillName = "吐纳", skillShieldPercent = 0.05,
                skillDamageSharePercent = 0.1
            )
        ),
        manualInstances = listOf(
            ManualInstance(
                id = "m-i1", name = "御剑诀", rarity = 2,
                type = ManualType.ATTACK, stats = mapOf("attack" to 15),
                skillName = "御剑", skillDamageType = "PHYSICAL", skillHits = 3,
                skillDamageMultiplier = 1.5, ownerId = "d-1", isLearned = true
            )
        ),
        pills = listOf(
            Pill(
                id = "pill-1", name = "聚气丹", rarity = 2, quantity = 10,
                category = PillCategory.CULTIVATION, grade = PillGrade.MEDIUM,
                pillType = "qi"
            )
        ),
        materials = listOf(
            Material(
                id = "mat-1", name = "妖兽皮", rarity = 1, quantity = 5,
                category = MaterialCategory.BEAST_HIDE
            )
        ),
        herbs = listOf(Herb(id = "herb-1", name = "灵草", rarity = 1, quantity = 8, category = "普通")),
        seeds = listOf(Seed(id = "seed-1", name = "灵草种子", rarity = 1, quantity = 4, growTime = 3, yield = 2)),
        storageBags = listOf(
            StorageBag(id = "bag-1", name = "储物袋", rarity = 1, quantity = 1, description = "可随机获得1-20件同品阶物品")
        )
    )

    /** 弟子样本（存活 + 死亡各一，覆盖状态字段）。 */
    private fun sampleDisciples(): List<Disciple> = listOf(
        Disciple().apply {
            id = "d-1"
            name = "张三"
            surname = "张"
            realm = 7
            realmLayer = 2
            cultivation = 12345.6
            cultivationCheckpoint = 12000.0
            cultivationCheckpointGameMonth = 20
            isAlive = true
            gender = "male"
            portraitRes = "portrait_1"
            manualIds = listOf("m-1")
            manualMasteries = mapOf("m-1" to 50)
            status = DiscipleStatus.IN_TEAM
            statusData = mapOf("key" to "value")
            cultivationSpeedBonus = 0.25
            cultivationSpeedDuration = 10
            discipleType = "inner"
        },
        Disciple().apply { id = "d-2"; name = "李四"; isAlive = false }
    )

    // ── 辅助 ─────────────────────────────────────────────────────────

    private fun assertRoundTrip(sample: NativeGameState) {
        val encoded = json.encodeToString(NativeGameState.serializer(), sample)
        val ok = DiffRngBridge.nativeCoreImportState(encoded.encodeToByteArray())
        assertTrue("C++ 导入失败", ok)

        val exported = DiffRngBridge.nativeCoreExportState()
        val decoded = json.decodeFromString(NativeGameState.serializer(), exported.decodeToString())

        // 导出前会把 RNG 分区**活动状态**回写 rngStates
        // （镜像/存档必须拿到引擎当前确定性状态）——因此导出值
        // 除样本声明的分区外还会含其余分区的活动状态。契约更新为：
        //   1) 样本声明的分区：导入恢复后原样往返（逐键相等）
        //   2) 其余字段：逐字段相等
        assertEquals(
            "rngStates 样本分区必须原样往返（C-13 恢复保真）",
            sample.gameData.rngStates,
            decoded.gameData.rngStates.filterKeys { it in sample.gameData.rngStates },
        )
        assertTrue(
            "导出 rngStates 必须覆盖全部活动分区（≥样本声明数）",
            decoded.gameData.rngStates.size >= sample.gameData.rngStates.size,
        )
        assertEquals(
            "快照往返后必须逐字段相等",
            sample.copy(
                gameData = sample.gameData.copy(
                    rngStates = decoded.gameData.rngStates,
                    // legacyEquipmentCompensated(169) 是存档侧补偿幂等标记（B3），
                    // C++ 引擎无消费者 → 按不可镜像口径归一（与 Mirror 消费面守卫的
                    // intentionallyUnmirrored 登记同源），往返比对不因 C++ 丢弃而红
                    legacyEquipmentCompensated =
                        decoded.gameData.legacyEquipmentCompensated,
                ),
            ),
            decoded,
        )
    }
}
