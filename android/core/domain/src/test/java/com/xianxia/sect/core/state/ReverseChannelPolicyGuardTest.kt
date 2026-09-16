package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.GameData
import kotlinx.serialization.descriptors.elementNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReverseChannelPolicyGuardTest — 反向通道逐域关闭策略的**清单式守卫**（batch-21）。
 *
 * 守护不变量：
 * 1. **穷尽分类**——`GameData` 序列化面的每个字段必须恰属一类：已关闭（关闭清单）
 *    或在册保留（[ReverseChannelPolicy.transportedGameDataFields]，附审计证据）。
 *    新增字段未分类 ⇒ 本测试失败并提示去哪儿登记（新增字段默认开放传输，
 *    因此漏登记不会丢数据，只是失去"可关闭"判定）。
 * 2. **逐域结论完整**——[ReverseChannelPolicy.Domain] 每个取值都有审计结论；
 *    `CLOSED` 必须无保留证据、`OPEN`/`PARTIAL` 必须给出稳态写者证据。
 * 3. **审计红线**——弟子通道与 `aiSectDisciples` 顶层段当前**必须保留传输**
 *    （稳态写者实测存在）；若有人关闭它们，本测试失败并指向证据。
 * 4. **关闭清单合法性**——关闭的集合/段名必须在协议名全集内（防拼写漂移）。
 */
class ReverseChannelPolicyGuardTest {

    private fun gameDataJsonFields(): Set<String> =
        GameData.serializer().descriptor.elementNames.toSet()

    @Test
    fun `every gameData field is classified as closed or transported`() {
        val all = gameDataJsonFields()
        val closed = ReverseChannelPolicy.closedGameDataFields()
        val transported = ReverseChannelPolicy.transportedGameDataFields
        val overlap = closed intersect transported
        assertTrue("同一字段不得同时出现在关闭清单与在册保留清单：$overlap", overlap.isEmpty())
        val missing = all - closed - transported
        assertTrue(
            "以下 gameData 字段未分类（既不在关闭清单也不在 transportedGameDataFields）：$missing。" +
                "请在 ReverseChannelPolicy 的逐域审计结论中登记——" +
                "有 AUTHORITATIVE 稳态 Kotlin 写者 ⇒ 加入 transportedGameDataFields 并附 file:line 证据；" +
                "无稳态写者 ⇒ 加入对应域的 closedUnits（kind=GAME_DATA_FIELD）",
            missing.isEmpty()
        )
        val stale = (closed + transported) - all
        assertTrue("清单含已不存在的字段（改名/删除后未同步）：$stale", stale.isEmpty())
    }

    @Test
    fun `every domain has an audited verdict`() {
        val missing = ReverseChannelPolicy.Domain.values()
            .filter { ReverseChannelPolicy.verdictOf(it) == null }
        assertTrue("以下域缺少逐域审计结论：${missing.map { it.displayName }}", missing.isEmpty())
        assertEquals(
            "逐域结论不得重复登记",
            ReverseChannelPolicy.Domain.values().size,
            ReverseChannelPolicy.verdictsSnapshot().size
        )
    }

    @Test
    fun `verdict evidence matches status`() {
        for (verdict in ReverseChannelPolicy.verdictsSnapshot()) {
            if (verdict.status == ReverseChannelPolicy.Status.CLOSED) {
                assertTrue(
                    "${verdict.domain.displayName} 标 CLOSED 却仍登记稳态写者证据：" +
                        "${verdict.residualEvidence}",
                    verdict.residualEvidence.isEmpty()
                )
            } else {
                assertTrue(
                    "${verdict.domain.displayName} 标 ${verdict.status} 必须给出稳态写者证据（file:line）",
                    verdict.residualEvidence.isNotEmpty()
                )
                for (evidence in verdict.residualEvidence) {
                    assertTrue(
                        "${verdict.domain.displayName} 的证据必须含 file:line：$evidence",
                        EVIDENCE_PATTERN.containsMatchIn(evidence)
                    )
                }
            }
            assertTrue(
                "${verdict.domain.displayName} 的关闭单元必须归属本域",
                verdict.closedUnits.all { it.domain == verdict.domain }
            )
        }
    }

    @Test
    fun `closed collections and sections use protocol names`() {
        val closedCollections = ReverseChannelPolicy.verdictsSnapshot()
            .flatMap { it.closedUnits }
            .filter { it.kind == ReverseChannelPolicy.Kind.COLLECTION }
            .map { it.name }
        val unknownCollections = closedCollections - ReverseChannelPolicy.COLLECTION_NAMES
        assertTrue("关闭清单含未知集合名：$unknownCollections", unknownCollections.isEmpty())

        val knownSections = setOf(
            ReverseChannelPolicy.SECTION_AI_SECT_DISCIPLES,
            ReverseChannelPolicy.SECTION_LOCKED_BEAST_IDS,
        )
        val closedSections = ReverseChannelPolicy.verdictsSnapshot()
            .flatMap { it.closedUnits }
            .filter { it.kind == ReverseChannelPolicy.Kind.TOP_LEVEL_SECTION }
            .map { it.name }
        val unknownSections = closedSections - knownSections
        assertTrue("关闭清单含未知顶层段名：$unknownSections", unknownSections.isEmpty())
    }

    @Test
    fun `audit red line - disciple channel and aiSectDisciples and worldMapSects stay closed`() {
        // 弟子通道：**已关闭**（w3-13 删除批硬前置达成——W4-D 续批任务域收口后，
        // AUTHORITATIVE 稳态协议列写者全部拥有 C++ 真相先行臂：交谈 1860 / 派遣 1861 /
        // 弟子操作面 1740–1759；lifeEvents 协议外投影转非捕获路径；检测 AUTHORITATIVE
        // 门控）。若回退（通道恢复传输），本测试失败并指向 handover §2.76。
        assertFalse(
            "弟子通道应已关闭（写者收口完成，见 handover §2.76），恢复传输须回滚本判定",
            ReverseChannelPolicy.isDiscipleChannelTransported()
        )
        // aiSectDisciples 段 + worldMapSects：**已关闭**（§2.75④ 第 3 项存档自愈收口——
        // 写者全部接线"写入后 native 基线重建"（rebaselineNativeMirror / importToNative）
        // 或 native 臂就位；boot 归一化由首旬全量导入吸收）。若回退，本测试失败并指向 §2.78。
        assertFalse(
            "aiSectDisciples 段应已关闭（写者已接线基线重建，见 handover §2.78）",
            ReverseChannelPolicy.isSectionTransported(ReverseChannelPolicy.SECTION_AI_SECT_DISCIPLES)
        )
        assertFalse(
            "worldMapSects 应已关闭（写者已接线基线重建，见 handover §2.78）",
            ReverseChannelPolicy.isGameDataFieldTransported("worldMapSects")
        )
    }

    @Test
    fun `audit red line - item4 first tranche fields stay closed`() {
        // §2.79 retained 字段族逐域判定第一段转关闭的 29 项（w3-13 删除步继续收口）。
        // 判定依据：AUTHORITATIVE 稳态 Kotlin 写者全部为 native 臂就位后的回退臂 /
        // LOAD_BOOT 族 / flag-OFF 结算臂 / 已接线基线重建（updateMirror + re-baseline）
        // 四类合法形态。若回退（恢复传输），本测试失败并指向 handover §2.79。
        val closed = listOf(
            "recruitList", "activeSectId", "sectName", "shownWarningStageIds",
            "vassalContracts", "suzerainSectId",
            "jadeSymbols", "jadeSymbolsToday", "jadeAccumMs", "jadeDayAnchorMs",
            "patrolConfigs", "spiritMineSlots", "patrolSlots", "residenceSlots",
            "terrainTiles", "mapGenVersion",
            "secretRealmState", "secretRealmSession", "secretRealmAITeams", "secretRealmCooldownYear",
            "caveExplorationTeams", "aiCaveTeams",
            "elderSlots", "librarySlots", "warehouseGarrisons", "battleTeams", "activeBloodRefinements",
            "placedBuildings", "spiritFieldPlants",
        )
        val stillTransported = closed.filter { ReverseChannelPolicy.isGameDataFieldTransported(it) }
        assertTrue(
            "§2.79 第一段关闭项不得恢复传输：$stillTransported（见 handover §2.79）",
            stillTransported.isEmpty()
        )
    }

    @Test
    fun `audit red line - item4 second tranche fields stay closed`() {
        // §2.80 retained 字段族逐域判定第二段转关闭的 30 项（钱包/年度账/执法堂/
        // 兑换/关注/邮件账本/自动购买/弹窗队列/天道试炼/外交关系/战斗世界域/
        // 事件日志/功法熟练度/生产槽）。写者经十二处接线（逐动作基线重建 /
        // updateMirror 非捕获）收敛。若回退，本测试失败并指向 handover §2.80。
        val closed = listOf(
            "spiritStones", "midGradeSpiritStones", "highGradeSpiritStones", "spiritHerbs",
            "theftJudgementsThisMonth", "annualTheftCount", "annualDesertedDisciples",
            "annualIncomeBySource", "annualExpenditureByReason", "annualTotalIncome",
            "annualTotalExpenditure", "annualNewDisciples", "annualDeceasedDisciples",
            "annualEquipmentBySource", "annualPillBySource", "annualHerbBySource",
            "usedRedeemCodes", "watchedItemIds",
            "mailRecords", "autoBuyList",
            "sectRelations",
            "worldLevels", "sectBattleRecords", "sectDetails", "scoutInfo", "heavenlyTrialState",
            "pendingPatrolBattleResults",
            "productionSlots",
            "gameEventRecords", "manualProficiencies",
        )
        val stillTransported = closed.filter { ReverseChannelPolicy.isGameDataFieldTransported(it) }
        assertTrue(
            "§2.80 第二段关闭项不得恢复传输：$stillTransported（见 handover §2.80）",
            stillTransported.isEmpty()
        )
    }

    @Test
    fun `w3-13 hard precondition met - every transport unit is closed`() {
        // 🔴 w3-13 删除步硬前置终局达成（§2.81 第三段：9 类实体集合转关闭）：
        // 关闭清单 = 全部传输单元（gameData 字段面 §2.80 + 顶层段 §2.76–§2.78 +
        // 弟子通道 §2.77 + 实体集合 §2.81）——无任何在册保留传输面。
        assertEquals(
            "gameData 字段面应全部关闭（§2.80 第二段达成）——若出现保留项，" +
                "说明有写者回退或新字段未走关闭判定流程",
            emptySet<String>(),
            ReverseChannelPolicy.transportedGameDataFields,
        )
        for (name in ReverseChannelPolicy.COLLECTION_NAMES) {
            assertFalse(
                "集合段 $name 应已关闭（§2.81 第三段：稳态写者已接线 updateMirror + " +
                    "基线重建，见 handover §2.81）",
                ReverseChannelPolicy.isCollectionTransported(name)
            )
        }
        assertFalse(
            "弟子通道应已关闭（§2.77）",
            ReverseChannelPolicy.isDiscipleChannelTransported()
        )
        assertFalse(
            "aiSectDisciples 段应已关闭（§2.78）",
            ReverseChannelPolicy.isSectionTransported(ReverseChannelPolicy.SECTION_AI_SECT_DISCIPLES)
        )
        assertFalse(
            "lockedBeastIds 段应已关闭（batch-23）",
            ReverseChannelPolicy.isSectionTransported(ReverseChannelPolicy.SECTION_LOCKED_BEAST_IDS)
        )
    }

    @Test
    fun `audit red line - item4 third tranche nine collections stay closed`() {
        // §2.81 retained 字段族逐域判定第三段（终段）转关闭的 9 类实体集合。
        // 判定依据：AUTHORITATIVE 稳态可达写者六簇已接线 updateMirror + 基线重建
        // （攻宗占领/碾压、世界关卡胜利奖励、逐出袋物化、仓库赏赐装备/功法、宗门
        // 贸易购买、妖兽迎战）；余者为回退臂/flag-OFF 结算臂/LOAD_BOOT/no-op/
        // 对拍基准专属/局部副本六类合法形态。若回退（恢复传输），本测试失败并指向
        // handover §2.81。
        val closed = listOf(
            "equipmentStacks", "equipmentInstances", "manualStacks", "manualInstances",
            "pills", "materials", "herbs", "seeds", "storageBags",
        )
        assertEquals("关闭集合清单必须恰为协议名全集", ReverseChannelPolicy.COLLECTION_NAMES, closed.toSet())
        val stillTransported = closed.filter { ReverseChannelPolicy.isCollectionTransported(it) }
        assertTrue(
            "§2.81 第三段关闭项（9 类集合）不得恢复传输：$stillTransported（见 handover §2.81）",
            stillTransported.isEmpty()
        )
    }

    @Test
    fun `closed sections are dropped and reopen restores transport`() {
        val section = ReverseChannelPolicy.SECTION_LOCKED_BEAST_IDS
        assertFalse(
            "lockedBeastIds 段应在关闭清单内（batch-23 已下沉 UI 操作面，仅剩回退臂写者）",
            ReverseChannelPolicy.isSectionTransported(section)
        )
        ReverseChannelPolicy.reopenDomain(ReverseChannelPolicy.Domain.BATTLE)
        try {
            assertTrue("回滚某域后该域单元必须恢复传输", ReverseChannelPolicy.isSectionTransported(section))
        } finally {
            ReverseChannelPolicy.resetSwitches()
        }
        assertFalse("resetSwitches 后回到审计结论", ReverseChannelPolicy.isSectionTransported(section))
    }

    private companion object {
        /** 证据格式守卫：`文件.kt:行`（可含多段，如 `a.kt:1 b.kt:2`）。 */
        val EVIDENCE_PATTERN = Regex("""\S+\.kt:\d+""")
    }
}
