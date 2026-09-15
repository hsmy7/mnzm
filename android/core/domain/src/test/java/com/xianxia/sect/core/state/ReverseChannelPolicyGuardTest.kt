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
    fun `audit red line - disciple channel closed, aiSectDisciples section stays transported`() {
        // 弟子通道：**已关闭**（w3-13 删除批硬前置达成——W4-D 续批任务域收口后，
        // AUTHORITATIVE 稳态协议列写者全部拥有 C++ 真相先行臂：交谈 1860 / 派遣 1861 /
        // 弟子操作面 1740–1759；lifeEvents 协议外投影转非捕获路径；检测 AUTHORITATIVE
        // 门控）。若回退（通道恢复传输），本测试失败并指向 handover §2.76。
        assertFalse(
            "弟子通道应已关闭（写者收口完成，见 handover §2.76），恢复传输须回滚本判定",
            ReverseChannelPolicy.isDiscipleChannelTransported()
        )
        // aiSectDisciples 顶层段：**仍必须保留传输**（存档前自愈 regenerateSectsBeforeSave /
        // 攻宗守军清理等稳态写者在位——§2.75④ 第 3 项未完成）
        assertTrue(
            "aiSectDisciples 顶层段仍有稳态写者（AI_SECT 域结论），不得关闭",
            ReverseChannelPolicy.isSectionTransported(ReverseChannelPolicy.SECTION_AI_SECT_DISCIPLES)
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
