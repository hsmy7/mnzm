package com.xianxia.sect.core.engine

import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.RedeemCode
import com.xianxia.sect.core.model.RedeemRewardType
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.asKotlinRandom
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 兑换码管理器守卫（校验 / 限流 / 数据结构 / 改道后的码表与奖励生成）。
 *
 * G08 改道（决策 D-9/D-10）后的核心红线：兑换码是**资源发放**通道，
 * 弟子实例只能由角色模板实例化产生——码表里不得再有弟子类条目，
 * 角色类条目一律以 `FRAGMENT` + `templateId` 表达，且模板 id 必须可查。
 */
class RedeemCodeManagerTest {

    @Before
    fun setUp() {
        RedeemCodeManager.clearAllCaches()
    }

    // ---- validateInput ----

    @Test
    fun validateInput_emptyCode_returnsError() {
        val result = RedeemCodeManager.validateInput("")
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_whitespaceOnly_returnsError() {
        val result = RedeemCodeManager.validateInput("   ")
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_tooShort_returnsError() {
        val result = RedeemCodeManager.validateInput("AB")
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_tooLong_returnsError() {
        val longCode = "A".repeat(21)
        val result = RedeemCodeManager.validateInput(longCode)
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_invalidCharacters_returnsError() {
        val result = RedeemCodeManager.validateInput("ABC-123")
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_specialCharacters_returnsError() {
        val result = RedeemCodeManager.validateInput("ABC@123")
        assertNotNull(result)
        assertFalse(result!!.success)
    }

    @Test
    fun validateInput_validAlphanumeric_returnsNull() {
        val result = RedeemCodeManager.validateInput("ABC123")
        assertNull(result)
    }

    @Test
    fun validateInput_validWithSpacesTrimmed_returnsNull() {
        val result = RedeemCodeManager.validateInput("  ABC123  ")
        assertNull(result)
    }

    @Test
    fun validateInput_minLengthValid_returnsNull() {
        val result = RedeemCodeManager.validateInput("ABC")
        assertNull(result)
    }

    @Test
    fun validateInput_maxLengthValid_returnsNull() {
        val result = RedeemCodeManager.validateInput("A".repeat(20))
        assertNull(result)
    }

    @Test
    fun validateInput_numericOnly_returnsNull() {
        val result = RedeemCodeManager.validateInput("12345")
        assertNull(result)
    }

    // ---- getRedeemCode ----

    @Test
    fun getRedeemCode_notFound_returnsNull() {
        val result = RedeemCodeManager.getRedeemCode("NONEXISTENT")
        assertNull(result)
    }

    // ---- isCodeUsedGlobally ----

    @Test
    fun isCodeUsedGlobally_unusedCode_returnsFalse() {
        assertFalse(RedeemCodeManager.isCodeUsedGlobally("UNUSED_CODE"))
    }

    // ---- getCodeUsageRecord ----

    @Test
    fun getCodeUsageRecord_unusedCode_returnsNull() {
        assertNull(RedeemCodeManager.getCodeUsageRecord("UNUSED_CODE"))
    }

    // ---- clearAllCaches ----

    @Test
    fun clearAllCaches_resetsState() {
        RedeemCodeManager.clearAllCaches()
        // After clearing, no codes should be used globally
        assertFalse(RedeemCodeManager.isCodeUsedGlobally("ANY_CODE"))
    }

    // ---- resetRateLimitForPlayer ----

    @Test
    fun resetRateLimitForPlayer_doesNotThrow() {
        RedeemCodeManager.resetRateLimitForPlayer("test_player")
    }

    // ---- getRateLimitStats ----

    @Test
    fun getRateLimitStats_defaultPlayer_returnsZeroAttempts() {
        RedeemCodeManager.clearAllCaches()
        val stats = RedeemCodeManager.getRateLimitStats("new_player", System.currentTimeMillis())
        assertEquals(0, stats.attemptsInLastMinute)
        assertEquals(0, stats.attemptsInLastHour)
        assertEquals(0, stats.attemptsToday)
        assertTrue(stats.maxPerMinute > 0)
        assertTrue(stats.maxPerHour > 0)
        assertTrue(stats.maxPerDay > 0)
    }

    // ---- RateLimitStats ----

    @Test
    fun rateLimitStats_remainingCalculations() {
        val stats = RedeemCodeManager.RateLimitStats(
            attemptsInLastMinute = 3,
            attemptsInLastHour = 10,
            attemptsToday = 50,
            maxPerMinute = 5,
            maxPerHour = 20,
            maxPerDay = 100
        )
        assertEquals(2, stats.minuteRemaining)
        assertEquals(10, stats.hourRemaining)
        assertEquals(50, stats.dayRemaining)
    }

    @Test
    fun rateLimitStats_remainingClampedToZero() {
        val stats = RedeemCodeManager.RateLimitStats(
            attemptsInLastMinute = 10,
            attemptsInLastHour = 30,
            attemptsToday = 200,
            maxPerMinute = 5,
            maxPerHour = 20,
            maxPerDay = 100
        )
        assertEquals(0, stats.minuteRemaining)
        assertEquals(0, stats.hourRemaining)
        assertEquals(0, stats.dayRemaining)
    }

    // ---- UsedCodeRecord ----

    @Test
    fun usedCodeRecord_construction() {
        val record = RedeemCodeManager.UsedCodeRecord(
            code = "TEST123",
            usedAt = 1000000L,
            deviceId = "device1",
            playerId = "player1"
        )
        assertEquals("TEST123", record.code)
        assertEquals(1000000L, record.usedAt)
        assertEquals("device1", record.deviceId)
        assertEquals("player1", record.playerId)
    }

    // ---- IpRateLimitResult ----

    @Test
    fun ipRateLimitResult_construction() {
        val result = RedeemCodeManager.IpRateLimitResult(
            allowed = false,
            remainingAttempts = 0,
            resetTimeSeconds = 60L,
            errorMessage = "Too many requests"
        )
        assertFalse(result.allowed)
        assertEquals(0, result.remainingAttempts)
        assertEquals(60L, result.resetTimeSeconds)
        assertEquals("Too many requests", result.errorMessage)
    }

    // ---- RemoteValidationResult ----

    @Test
    fun remoteValidationResult_construction() {
        val result = RedeemCodeManager.RemoteValidationResult(
            valid = true,
            serverCode = null,
            errorMessage = null,
            signature = "sig123"
        )
        assertTrue(result.valid)
        assertNull(result.serverCode)
        assertNull(result.errorMessage)
        assertEquals("sig123", result.signature)
    }

    // ---- 兑换码改道守卫（G08 D-9 / D-10）----

    @Test
    fun predefinedCodes_compiledCodeTable_neverGrantsDiscipleInstances() {
        // Given 编译期码表（private 且无公开枚举口，读取方式见 compiledCodeTable 的说明）
        val codes = compiledCodeTable()

        // Then 1 码表内不得出现「直造弟子」类奖励（该两个取值已从枚举删除）
        val discipleLike = codes.values.filter { it.rewardType.name in retiredDiscipleRewardTypes }
        assertTrue(
            "兑换码表存在弟子类奖励条目 ${discipleLike.map { it.code to it.rewardType }}：" +
                "弟子实例只能由角色模板实例化产生，角色奖励须改发 FRAGMENT + templateId。" +
                "落点：RedeemCodeManager.predefinedCodes / core/domain/.../model/RedeemCode.kt",
            discipleLike.isEmpty()
        )

        // Then 2 角色类条目的 templateId 必须落在模板表取值域内
        codes.values.filter { it.rewardType == RedeemRewardType.FRAGMENT }.forEach { code ->
            val templateId = code.templateId
            assertTrue(
                "FRAGMENT 码 ${code.code} 的 templateId=$templateId 不在模板表 " +
                    "${CharacterTemplateDb.ids} 内——碎片按模板 id 定位角色，" +
                    "错配的码会被发放臂整单拒发、玩家永远兑不了。" +
                    "落点：core/domain/.../model/CharacterTemplate.kt 的 CharacterTemplateDb",
                templateId != null && templateId in CharacterTemplateDb.ids
            )
        }

        // Then 3 非角色类条目不得携带 templateId（防「templateId 挂在灵石码上」这类错配）
        codes.values.filter { it.rewardType != RedeemRewardType.FRAGMENT }.forEach { code ->
            assertNull(
                "非 FRAGMENT 码 ${code.code}（${code.rewardType}）不应携带 templateId",
                code.templateId
            )
        }
    }

    @Test
    fun getRedeemCode_legacyDiscipleCode8982_returnsNull() {
        // 旧「8982 = 10 名弟子」码是改道前唯一的编译期条目，D-10 要求整表置空
        assertNull(
            "兑换码 8982 必须已下线（它发放 10 名随机弟子，是「直造弟子」的最后一个入口）。" +
                "若确要恢复该码，须改配 FRAGMENT + templateId。落点：RedeemCodeManager.predefinedCodes",
            RedeemCodeManager.getRedeemCode("8982")
        )
        assertNull(
            "码表已置空：任何码的点查都应为 null（新增编译期码须走模板碎片口径）",
            RedeemCodeManager.getRedeemCode("NEWYEAR")
        )
    }

    @Test
    fun validateCode_legacyDiscipleCode_notConfigured_fails() = runTest {
        val result = RedeemCodeManager.validateCode(
            code = "8982",
            usedCodes = emptyList(),
            currentYear = 1,
            currentMonth = 1,
            nowMs = FIXED_NOW_MS
        )
        assertFalse(
            "未配置的码必须校验失败——本地臂在 getRedeemCode 为空时直接返回，不得发放任何奖励。" +
                "落点：RedeemCodeManager.validateCode → validateCodeState",
            result.success
        )
    }

    @Test
    fun generateReward_rewardTypeSweep_neverProducesDiscipleEntries() {
        // 守卫三要素①（枚举驱动）：以 RedeemRewardType 全集为锚点遍历
        // 守卫三要素②（故意排除项）：物品类 6 取值与 MANUAL_PACK 的生成臂依赖
        //   ManualDatabase / ItemDatabase 等 **asset 注册表**（纯 JVM 单测里未初始化 ⇒ 模板为空、
        //   生成器直接抛异常），其「不产出弟子」由 RedeemCodeRewardOps.generateSingleItemReward
        //   的 when 穷举兜底——它只接受 6 种物品取值，其余取值一律 error()。
        val assetDependentTypes = setOf(
            RedeemRewardType.EQUIPMENT,
            RedeemRewardType.MANUAL,
            RedeemRewardType.PILL,
            RedeemRewardType.MATERIAL,
            RedeemRewardType.HERB,
            RedeemRewardType.SEED,
            RedeemRewardType.MANUAL_PACK
        )
        val directlyGenerableTypes = setOf(RedeemRewardType.SPIRIT_STONES, RedeemRewardType.FRAGMENT)
        val unclassified = RedeemRewardType.entries.filter {
            it !in assetDependentTypes && it !in directlyGenerableTypes
        }
        assertTrue(
            "新增奖励取值 ${unclassified.map { it.name }} 未在本守卫分类：它究竟走 asset 注册表" +
                "还是可直接生成？必须先归类，若走弟子实例则违反改道口径应拒绝合入。" +
                "落点：本测试 + RedeemCodeManager.generateReward",
            unclassified.isEmpty()
        )

        val allowedEntryTypes = setOf("spiritStones", REWARD_TYPE_FRAGMENT)
        directlyGenerableTypes.forEach { type ->
            // When 走本地臂生成奖励条目
            val result = RedeemCodeManager.generateReward(
                redeemCode = RedeemCode(
                    code = "SWEEP-${type.name}",
                    rewardType = type,
                    quantity = 1,
                    rarity = 1,
                    templateId = if (type == RedeemRewardType.FRAGMENT) "zhouming" else null
                ),
                random = DeterministicRng.fromSeed(FIXED_NOW_MS).asKotlinRandom(),
                nowMs = FIXED_NOW_MS
            )
            // Then 条目类型只能是已登记的资源/碎片字面量，永不出弟子
            val entryTypes = result.rewards.map { it.type }.toSet()
            val unexpected = entryTypes - allowedEntryTypes
            assertTrue(
                "奖励类型 $type 产出了未登记条目 $unexpected：弟子类条目（disciple/starterPack）" +
                    "一旦出现即违反「弟子只能由模板实例化产生」。" +
                    "落点：RedeemCodeRewardOps / RedeemCodeManager.generateReward",
                unexpected.isEmpty()
            )
        }
    }

    @Test
    fun generateReward_fragmentRewardWithKnownTemplate_emitsSingleFragmentEntry() {
        // Given 一个发放角色碎片的码
        val code = RedeemCode(
            code = "FRAGOK",
            rewardType = RedeemRewardType.FRAGMENT,
            quantity = 60,
            templateId = "suqing"
        )

        // When 生成本地臂奖励条目
        val result = RedeemCodeManager.generateReward(
            redeemCode = code,
            random = DeterministicRng.fromSeed(FIXED_NOW_MS).asKotlinRandom(),
            nowMs = FIXED_NOW_MS
        )

        // Then 只产出一条碎片条目（id 即模板 id），不产出弟子
        assertTrue("碎片码应发放成功: ${result.message}", result.success)
        val fragments = result.rewards.filter { it.type == REWARD_TYPE_FRAGMENT }
        assertEquals("碎片条目应恰为 1 条", 1, fragments.size)
        assertEquals("条目 id 即角色模板 id（服务侧据此定位入账模板）", "suqing", fragments.first().id)
        assertEquals("碎片数量取码的 quantity", 60, fragments.first().quantity)
    }

    @Test
    fun generateReward_fragmentRewardWithUnknownTemplate_failsWithoutConsumingCode() {
        // Given 一个配了不存在模板的碎片码（运营错配）
        val code = RedeemCode(
            code = "FRAGBAD",
            rewardType = RedeemRewardType.FRAGMENT,
            quantity = 60,
            templateId = "not_a_template"
        )

        // When 生成奖励
        val result = RedeemCodeManager.generateReward(
            redeemCode = code,
            random = DeterministicRng.fromSeed(FIXED_NOW_MS).asKotlinRandom(),
            nowMs = FIXED_NOW_MS
        )

        // Then 整单拒发且**不消耗码**——玩家可改配后重试，不会「码已核销、碎片未到账」
        assertFalse("未知模板必须拒发", result.success)
        assertFalse(
            "拒发时不得标记兑换码已用（否则玩家白丢一次兑换）。" +
                "落点：RedeemCodeManager.generateReward 的 rewardRejected 分支",
            RedeemCodeManager.isCodeUsedGlobally("FRAGBAD")
        )
        assertTrue("拒发时不得产出任何奖励条目", result.rewards.isEmpty())
    }

    // ── 码表读取（改道守卫专用） ─────────────────────────────────────────────

    /**
     * 反射读取编译期码表 `RedeemCodeManager.predefinedCodes`。
     *
     * 判据是「表内**每一条**都不发放弟子」，而该 object 只公开 `getRedeemCode(code)` 点查口，
     * 点查无法穷举——同 `GameStateStoreTransientQueueGuardTest` 对私有字段的处理方式。
     * 字段改名/删除时显式失败并给出落点，不允许静默跳过。
     */
    @Suppress("UNCHECKED_CAST") // 反射读取私有码表：类型由 RedeemCodeManager 声明固定，形状不符按空表处理
    private fun compiledCodeTable(): Map<String, RedeemCode> {
        val field = try {
            RedeemCodeManager::class.java.getDeclaredField("predefinedCodes")
        } catch (e: NoSuchFieldException) {
            throw AssertionError(
                "RedeemCodeManager.predefinedCodes 不见了：码表字段被改名/删除，本守卫失去穷举口，" +
                    "必须同步改判据（不要删本测试）。落点：core/engine/.../RedeemCodeManager.kt",
                e
            )
        }
        field.isAccessible = true
        return (field.get(RedeemCodeManager) as? Map<String, RedeemCode>) ?: emptyMap()
    }

    private companion object {
        /** 已从枚举删除的弟子类取值名（码表里不得再出现同名奖励） */
        val retiredDiscipleRewardTypes = setOf("DISCIPLE", "STARTER_PACK")

        /** 固定起算时刻：限流与使用记录在单测内不依赖挂钟 */
        const val FIXED_NOW_MS = 1_700_000_000_000L
    }
}
