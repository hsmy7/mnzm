package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.domain.FavorDomain
import com.xianxia.sect.core.domain.calculateGiftFavorIncrease
import com.xianxia.sect.core.engine.domain.diplomacy.IntelligentSectDecisionEngine
import com.xianxia.sect.core.model.AISectPersonality
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.GiftPreferenceType
import com.xianxia.sect.core.model.SectDetail
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.core.model.SectRelationLevel
import com.xianxia.sect.core.model.WorldSect
import com.xianxia.sect.core.util.DeterministicRng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffDiplomacyTxTest — 外交/好感/附庸 UI 操作事务跨语言差分对拍（batch-09）。
 *
 * 守护目标：C++ gamecore::system::diplomacy_tx（赠礼事务含拒绝 roll /
 * 结盟事务 / 附属事务）与 Kotlin GiftService / DiplomacyService /
 * VassalService 写者语义逐位一致：
 *   - 赠礼后好感值双端逐位（favorChange/newFavor/favor 落值/送礼年/扣费）
 *   - SYSTEM 分区抽取终态逐位（拒绝 roll nextInt(100) / 结盟附属 nextDouble）
 *   - 结盟/附属成败判定与 Kotlin IntelligentSectDecisionEngine 概率一致
 *   - 校验失败 failure 信封（回退臂零抽取零写入同位）
 *
 * Kotlin 基准：真实 FavorDomain 纯函数 + GiftConfig + 本地参考流
 * （同分区状态 restore）；月结面（衰减/纳贡/脱离）不在本测试范围。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffDiplomacyTxTest {

    // ignoreUnknownKeys：C++ 导出的 Disciple 含 Kotlin 模型未声明的键
    //（deathYear 等 SoA 列——镜像通道宽松合并同口径），导出回读仅取本批断言字段
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun freshCore() {
        DiffRngBridge.nativeDestroy()
        DiffRngBridge.nativeCoreInit()
    }

    private fun cppExec(actionId: Int, params: JsonObject): JsonObject {
        val result = DiffRngBridge.nativeCoreExecute(
            actionId, json.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
        ).decodeToString()
        return json.parseToJsonElement(result) as JsonObject
    }

    private fun exportState(): NativeGameState = json.decodeFromString(
        NativeGameState.serializer(), DiffRngBridge.nativeCoreExportState().decodeToString()
    )

    // ── 测试态构造 ────────────────────────────────────────────

    private fun playerSect() = WorldSect(
        id = "player", name = "青云宗", level = 2, isPlayerSect = true
    )

    private fun aiSect(id: String, name: String, level: Int) =
        WorldSect(id = id, name = name, level = level)

    private fun baseDisciple(id: String, realm: Int) = Disciple(
        id = id, name = "弟子$id", realm = realm, realmLayer = 1,
        isAlive = true, spiritRootType = "metal", age = 20, lifespan = 80
    )

    private fun diplomacyGameData(systemRngState: Long?): GameData = GameData().apply {
        gameYear = 5
        spiritStones = 1_000_000
        worldMapSects = listOf(playerSect(), aiSect("sect-a", "落霞宗", 0))
        sectRelations = mutableListOf(
            SectRelation(
                sectId1 = "player", sectId2 = "sect-a",
                favor = 50, acquainted = true
            )
        )
        sectDetails = mapOf(
            "sect-a" to SectDetail(sectId = "sect-a", giftPreference = GiftPreferenceType.NONE)
        )
        if (systemRngState != null) {
            // SYSTEM 分区（id=3）初始状态——importStateJson 从快照恢复分区
            rngStates = mapOf(3 to systemRngState)
        }
    }

    private fun importState(gameData: GameData, aiDisciples: Map<String, List<Disciple>> = emptyMap()) {
        val state = NativeGameState(
            gameData = gameData,
            aiSectDisciples = aiDisciples,
            disciples = listOf(baseDisciple("d1", 5))
        )
        assertTrue("C++ 导入失败", DiffRngBridge.nativeCoreImportState(
            json.encodeToString(NativeGameState.serializer(), state).encodeToByteArray()))
    }

    // ═══════════ 赠礼事务 ═══════════

    @Test
    fun `gift accept matches Kotlin favor and wallet`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 找一个首抽 nextInt(100) >= 20（不被拒）的种子
        var seed = 0L
        for (s in 1L..200L) {
            if (DeterministicRng.fromSeed(s).nextInt(100) >= 20) { seed = s; break }
        }
        org.junit.Assume.assumeTrue("种子扫描未命中接受区间", seed > 0)
        val roll = DeterministicRng.fromSeed(seed).nextInt(100)

        freshCore()
        val rngState = DeterministicRng.fromSeed(seed).snapshot()
        importState(diplomacyGameData(rngState))

        // Kotlin 基准（真实 FavorDomain / GiftConfig）：同分区状态恢复 →
        // 同一条 nextInt(100) → 同一个拒绝判定
        val local = DeterministicRng(state = rngState)
        val localRoll = local.nextInt(100)
        assertEquals(roll, localRoll)
        val favorIncrease = calculateGiftFavorIncrease(50, 1, 0, GiftPreferenceType.NONE)
        val expectedNewFavor = (50 + favorIncrease).coerceIn(0, 100)
        // Kotlin 写路径：setAcquainted（已相识幂等）→ updateFavor
        val kotlinRelations = FavorDomain.updateFavor(
            FavorDomain.setAcquainted(
                diplomacyGameData(rngState).sectRelations, "player", "sect-a", 5
            ),
            "player", "sect-a", expectedNewFavor, 5
        )

        val r = cppExec(ActionIds.FAVOR_GIFT, buildJsonObject {
            put("sectId", "sect-a"); put("tier", 1); put("bypassYearLimit", false)
        })
        assertEquals("success", r["status"]!!.jsonPrimitive.content)
        val data = r.getValue("data").jsonObject
        assertEquals("accept", data.getValue("outcome").jsonPrimitive.content)
        assertEquals(favorIncrease, data.getValue("favorChange").jsonPrimitive.content.toInt())
        assertEquals(expectedNewFavor, data.getValue("newFavor").jsonPrimitive.content.toInt())

        // 状态逐位：好感落值/送礼年/扣费/SYSTEM 分区终态
        val exported = exportState()
        assertEquals(kotlinRelations[0].favor, exported.gameData.sectRelations[0].favor)
        assertEquals(
            kotlinRelations[0].lastInteractionYear,
            exported.gameData.sectRelations[0].lastInteractionYear
        )
        assertEquals(5, exported.gameData.sectDetails.getValue("sect-a").lastGiftYear)
        assertEquals(1_000_000L - 20_000L, exported.gameData.spiritStones)
        assertEquals(local.snapshot(), exported.gameData.rngStates[3])
    }

    @Test
    fun `gift reject consumes roll and writes nothing`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 找一个首抽 nextInt(100) < 20（拒绝）的种子
        var seed = 0L
        for (s in 1L..200L) {
            if (DeterministicRng.fromSeed(s).nextInt(100) < 20) { seed = s; break }
        }
        org.junit.Assume.assumeTrue("种子扫描未命中拒绝区间", seed > 0)

        freshCore()
        val rngState = DeterministicRng.fromSeed(seed).snapshot()
        importState(diplomacyGameData(rngState))
        val local = DeterministicRng(state = rngState)
        local.nextInt(100)   // 参考流消费同一次 roll

        val r = cppExec(ActionIds.FAVOR_GIFT, buildJsonObject {
            put("sectId", "sect-a"); put("tier", 1); put("bypassYearLimit", false)
        })
        assertEquals("success", r["status"]!!.jsonPrimitive.content)
        val data = r.getValue("data").jsonObject
        assertEquals("rejected", data.getValue("outcome").jsonPrimitive.content)

        val exported = exportState()
        assertEquals(50, exported.gameData.sectRelations[0].favor)
        assertEquals(1_000_000L, exported.gameData.spiritStones)
        assertEquals(0, exported.gameData.sectDetails.getValue("sect-a").lastGiftYear)
        assertEquals(local.snapshot(), exported.gameData.rngStates[3])
    }

    @Test
    fun `gift validation failure falls back with zero draw`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val gameData = diplomacyGameData(systemRngState = null).apply {
            sectDetails = mapOf(
                "sect-a" to SectDetail(
                    sectId = "sect-a",
                    giftPreference = GiftPreferenceType.NONE,
                    lastGiftYear = 5          // 今年已送——Kotlin prepareGift 同位早退
                )
            )
        }
        importState(gameData)

        val r = cppExec(ActionIds.FAVOR_GIFT, buildJsonObject {
            put("sectId", "sect-a"); put("tier", 1); put("bypassYearLimit", false)
        })
        // failure 信封 → Kotlin 回退原路径（同校验失败，零抽取零写入）
        assertEquals("failure", r["status"]!!.jsonPrimitive.content)
        assertEquals("already_gifted", r.getValue("code").jsonPrimitive.content)
        val exported = exportState()
        assertEquals(1_000_000L, exported.gameData.spiritStones)
        assertEquals(50, exported.gameData.sectRelations[0].favor)
    }

    // ═══════════ 结盟事务 ═══════════

    @Test
    fun `alliance roll outcome matches Kotlin engine`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        // 战力比 1.0 + 好感 INTIMATE(90) → Kotlin 引擎概率 0.4
        val chance = IntelligentSectDecisionEngine.calculateChance(
            IntelligentSectDecisionEngine.ALLIANCE_PROFILE,
            powerRatio = 1.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.fromFavor(90),
            personality = AISectPersonality.BALANCED
        )
        assertEquals(0.4, chance, 1e-12)

        val rngState = DeterministicRng.fromSeed(42).snapshot()
        importState(
            diplomacyGameData(rngState).apply {
                sectRelations = mutableListOf(
                    SectRelation(sectId1 = "player", sectId2 = "sect-a", favor = 90)
                )
            },
            aiDisciples = mapOf("sect-a" to listOf(baseDisciple("a1", 5)))
        )
        val local = DeterministicRng(state = rngState)
        val expectedSuccess = local.nextDouble() < chance

        val r = cppExec(ActionIds.DIPLOMACY_TX, buildJsonObject {
            put("op", "request_alliance"); put("sectId", "sect-a")
        })
        assertEquals("success", r["status"]!!.jsonPrimitive.content)
        val data = r.getValue("data").jsonObject
        assertEquals(
            expectedSuccess,
            data.getValue("success").jsonPrimitive.content.toBooleanStrict()
        )

        val exported = exportState()
        assertEquals(local.snapshot(), exported.gameData.rngStates[3])
        if (expectedSuccess) {
            assertEquals(1, exported.gameData.alliances.size)
            assertTrue(exported.gameData.worldMapSects[0].allianceId.isNotEmpty())
            assertTrue(exported.gameData.worldMapSects[1].allianceId.isNotEmpty())
            assertTrue(exported.gameData.sectRelations[0].acquainted)
            assertEquals(1, exported.gameData.gameEventRecords.size)
            assertEquals("alliance", exported.gameData.gameEventRecords[0].eventType)
        } else {
            assertEquals(0, exported.gameData.alliances.size)
        }
    }

    // ═══════════ 附庸事务 ═══════════

    @Test
    fun `vassal zero chance still consumes draw`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        // aiPower<=0：Kotlin calculateVassalChance 返回 0.0 但仍 rng.nextDouble()
        importState(diplomacyGameData(DeterministicRng.fromSeed(7).snapshot()))
        val local = DeterministicRng(state = DeterministicRng.fromSeed(7).snapshot())
        local.nextDouble()

        val r = cppExec(ActionIds.VASSAL_TX, buildJsonObject {
            put("op", "request_contract"); put("sectId", "sect-a")
        })
        assertEquals("success", r["status"]!!.jsonPrimitive.content)
        assertEquals(
            "false",
            r.getValue("data").jsonObject.getValue("success").jsonPrimitive.content
        )
        val exported = exportState()
        assertEquals(0, exported.gameData.vassalContracts.size)
        assertEquals(local.snapshot(), exported.gameData.rngStates[3])
    }

    @Test
    fun `vassal success writes contract and acquaintance`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        val rngState = DeterministicRng.fromSeed(11).snapshot()
        importState(
            diplomacyGameData(rngState).apply {
                sectRelations = mutableListOf(
                    SectRelation(sectId1 = "player", sectId2 = "sect-a", favor = 90)
                )
            },
            aiDisciples = mapOf("sect-a" to listOf(baseDisciple("a1", 5)))
        )
        // Kotlin 基准概率（VASSAL_PROFILE；战力比 1.0 无战力分、好感 INTIMATE 0.135）
        val chance = IntelligentSectDecisionEngine.calculateChance(
            IntelligentSectDecisionEngine.VASSAL_PROFILE,
            powerRatio = 1.0, conquestCount = 0, lostSectCount = 0,
            battleWinCount = 0, battleLossCount = 0,
            favorLevel = SectRelationLevel.fromFavor(90)
        )
        val local = DeterministicRng(state = rngState)
        val expectedSuccess = local.nextDouble() < chance

        val r = cppExec(ActionIds.VASSAL_TX, buildJsonObject {
            put("op", "request_contract"); put("sectId", "sect-a")
        })
        assertEquals("success", r["status"]!!.jsonPrimitive.content)
        assertEquals(
            expectedSuccess,
            r.getValue("data").jsonObject.getValue("success").jsonPrimitive.content
                .toBooleanStrict()
        )
        val exported = exportState()
        assertEquals(local.snapshot(), exported.gameData.rngStates[3])
        if (expectedSuccess) {
            assertEquals(1, exported.gameData.vassalContracts.size)
            assertEquals("sect-a", exported.gameData.vassalContracts[0].vassalSectId)
            assertEquals(5, exported.gameData.vassalContracts[0].establishedYear)
            assertEquals(0L, exported.gameData.vassalContracts[0].lastTributeYear)
            assertTrue(exported.gameData.sectRelations[0].acquainted)
        }
    }

    @Test
    fun `dissolve vassal removes contract`() {
        assumeTrue(DiffRngBridge.isAvailable())
        freshCore()
        importState(
            diplomacyGameData(systemRngState = null).apply {
                vassalContracts = mutableListOf(
                    com.xianxia.sect.core.model.VassalContract(
                        vassalSectId = "sect-a", establishedYear = 2, lastTributeYear = 0
                    )
                )
            }
        )
        val r = cppExec(ActionIds.VASSAL_TX, buildJsonObject {
            put("op", "dissolve_contract"); put("sectId", "sect-a")
        })
        assertEquals("success", r["status"]!!.jsonPrimitive.content)
        val exported = exportState()
        assertEquals(0, exported.gameData.vassalContracts.size)
    }
}
