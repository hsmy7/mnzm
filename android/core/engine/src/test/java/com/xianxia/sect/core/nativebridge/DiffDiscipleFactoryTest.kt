package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
import com.xianxia.sect.core.model.CharacterTemplateDb
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.util.DeterministicRng
import com.xianxia.sect.core.util.NameService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DiffDiscipleFactoryTest — 弟子创建跨语言差分对拍。
 *
 * 守护目标：Kotlin `DiscipleFactory.create`（core/engine/domain/disciple/
 * DiscipleFactory.kt）与 C++ `gamecore::system::createDisciple`
 * （disciple_factory.h）在相同种子下产出**逐字段位级一致**的弟子：
 * 立绘 / 模板 id / 六维方差 / 悟性 / 技能 / 基础属性。
 *
 * 确定性基础：Kotlin 侧 `seed.nextInt` 是 `DeterministicRng` 的适配器；
 * C++ 侧 `nativeFromSeed(seed)`
 * 独立同种子实例按相同消费序驱动：六维方差 14 次 → 悟性 1 次 →
 * 肖像 0 或 1 次（[DiscipleFactory.DiscipleSeed.portraitResOverride] 非空即 0 次）
 * → 技能 16 次。
 *
 * 模板分支（G08）：seed 携带 `templateId` + `portraitResOverride`（JNI JSON 键名为
 * `portraitRes`）时，两侧都必须直接采用该立绘键并跳过那次肖像随机数——
 * 由本类的「模板分支」用例逐模板覆盖。
 *
 * 前置：桌面 JNI 已构建并注入 `-Dgamecore.jni.path`；未注入时跳过。
 */
class DiffDiscipleFactoryTest {

    private val json = Json

    private fun assertInt(tag: String, key: String, expected: Int, c: JsonObject) {
        assertEquals("$tag $key", expected, c[key]!!.jsonPrimitive.content.toInt())
    }

    private fun assertStr(tag: String, key: String, expected: String, c: JsonObject) {
        assertEquals("$tag $key", expected, c[key]!!.jsonPrimitive.content)
    }

    private fun assertCombat(tag: String, d: Disciple, c: JsonObject) {
        assertStr(tag, "portraitRes", d.portraitRes, c)
        assertStr(tag, "templateId", d.templateId, c)
        assertInt(tag, "hpVariance", d.combat.hpVariance, c)
        assertInt(tag, "mpVariance", d.combat.mpVariance, c)
        assertInt(tag, "attackVariance", d.combat.attackVariance, c)
        assertInt(tag, "attackVariance", d.combat.attackVariance, c)
        assertInt(tag, "defenseVariance", d.combat.defenseVariance, c)
        assertInt(tag, "defenseVariance", d.combat.defenseVariance, c)
        assertInt(tag, "speedVariance", d.combat.speedVariance, c)
        assertInt(tag, "baseHp", d.combat.baseHp, c)
        assertInt(tag, "baseMp", d.combat.baseMp, c)
        assertInt(tag, "baseAttack", d.combat.baseAttack, c)
        assertInt(tag, "baseAttack", d.combat.baseAttack, c)
        assertInt(tag, "baseDefense", d.combat.baseDefense, c)
        assertInt(tag, "baseDefense", d.combat.baseDefense, c)
        assertInt(tag, "baseSpeed", d.combat.baseSpeed, c)
    }

    private fun assertSkills(tag: String, d: Disciple, c: JsonObject) {
        assertInt(tag, "comprehension", d.skills.comprehension, c)
        assertInt(tag, "intelligence", d.skills.intelligence, c)
        assertInt(tag, "charm", d.skills.charm, c)
        assertInt(tag, "morality", d.skills.morality, c)
        assertInt(tag, "artifactRefining", d.skills.artifactRefining, c)
        assertInt(tag, "pillRefining", d.skills.pillRefining, c)
        assertInt(tag, "spiritPlanting", d.skills.spiritPlanting, c)
        assertInt(tag, "mining", d.skills.mining, c)
        assertInt(tag, "teaching", d.skills.teaching, c)
    }

    /**
     * 双臂同种子各造一名弟子并逐字段对拍。
     *
     * @param templateId 角色模板 id；空串 = 非模板弟子（两侧同写空串）
     * @param portraitResOverride 立绘键；非空即模板分支（两侧都不消费肖像随机数）。
     *   注意 JNI seed JSON 侧的键名是 `portraitRes`（与 C++
     *   `DiscipleCreationSeed.portraitResOverride` 的映射见 GameCoreJni.cpp）
     * @return Kotlin 侧弟子，供调用方追加身份字段断言
     */
    private fun runDiff(
        seed: Long,
        id: String,
        gender: String,
        fullName: String,
        surname: String,
        spiritRootType: String,
        realm: Int = 9,
        realmLayer: Int = 1,
        templateId: String = "",
        portraitResOverride: String = ""
    ): Disciple {
        val kRng = DeterministicRng.fromSeed(seed)
        DiffRngBridge.nativeFromSeed(seed)

        val kDisciple = DiscipleFactory().create(
            DiscipleFactory.DiscipleSeed(
                id = id,
                gender = gender,
                nameResult = NameService.NameResult(surname = surname, fullName = fullName),
                spiritRootType = spiritRootType,
                realm = realm,
                realmLayer = realmLayer,
                nextInt = { from, until -> from + kRng.nextInt(until - from) },
                templateId = templateId,
                portraitResOverride = portraitResOverride
            )
        )

        val seedJson = """
            {"id":"$id","gender":"$gender","fullName":"$fullName",
             "surname":"$surname","spiritRootType":"$spiritRootType",
             "realm":$realm,"realmLayer":$realmLayer,
             "templateId":"$templateId","portraitRes":"$portraitResOverride"}
        """.trimIndent()
        val c = json.parseToJsonElement(
            DiffRngBridge.nativeCreateDisciple(seedJson)
        ).jsonObject

        val tag = "seed=$seed id=$id spiritRoot=$spiritRootType template=$templateId"
        assertCombat(tag, kDisciple, c)
        assertSkills(tag, kDisciple, c)
        return kDisciple
    }

    @Test
    fun `createDisciple matches Kotlin across seeds genders and root counts`() {
        assumeTrue(DiffRngBridge.isAvailable())
        val seeds = longArrayOf(42, 20260901, 987654321)
        val genders = listOf("male", "female")
        // 灵根数 1/2/5 覆盖悟性资质全部阶梯分支
        val roots = listOf("火", "火,水", "火,水,木,金,土")
        for (seed in seeds) {
            for (gender in genders) {
                for ((i, root) in roots.withIndex()) {
                    runDiff(
                        seed = seed,
                        id = "diff-$seed-$gender-$i",
                        gender = gender,
                        fullName = if (gender == "male") "李逍遥" else "慕容雪",
                        surname = if (gender == "male") "李" else "慕容",
                        spiritRootType = root
                    )
                }
            }
        }
    }

    @Test
    fun `createDisciple matches Kotlin with non-default realm`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // realm 5（化神）+ 小层 3：覆盖非默认境界/层数分支
        runDiff(
            seed = 777, id = "realm5", gender = "male",
            fullName = "玄真子", surname = "玄",
            spiritRootType = "火,水,木", realm = 5, realmLayer = 3
        )
        // realm 7（金丹）：与 GTest 同种子同参数（male/单灵根火）
        runDiff(
            seed = 20260901, id = "realm7", gender = "male",
            fullName = "李逍遥", surname = "李",
            spiritRootType = "火", realm = 7, realmLayer = 1
        )
    }

    @Test
    fun `createDisciple matches Kotlin when negative pool drains`() {
        assumeTrue(DiffRngBridge.isAvailable())
        // 固定种子下多次创建，前序抽选会耗尽部分 template 池，后续轮次
        // 触发"负面池耗尽 → 品阶1兜底"与"template 去重过滤"路径
        repeat(12) { i ->
            runDiff(
                seed = 2024L + i,
                id = "drain-$i",
                gender = if (i % 2 == 0) "male" else "female",
                fullName = "弟子$i",
                surname = "王",
                spiritRootType = "金"
            )
        }
    }

    /**
     * 模板分支对拍（G08）：逐条走查 `CharacterTemplateDb` 全表。
     *
     * 与生产构造口径同构（`DiscipleService.instantiateTemplate`：姓名/性别/灵根取自
     * 模板、境界取 STARTUP_*、立绘走 portraitResOverride），断言两件事：
     * 1. 双臂 `portraitRes` / `templateId` 逐字相等（由 assertCombat 覆盖）且等于模板值；
     * 2. 双臂其余字段（方差/悟性/技能/基础属性）仍逐位相等——**不比对具体数值**，
     *    因为模板臂比通用臂少消耗 1 次肖像随机数、后续序列整体平移（D-6 预期），
     *    本批不重录任何黄金基线。
     */
    @Test
    fun `createDisciple matches Kotlin on template branch across all templates`() {
        assumeTrue(DiffRngBridge.isAvailable())
        CharacterTemplateDb.ALL.forEachIndexed { index, template ->
            val disciple = runDiff(
                seed = TEMPLATE_BRANCH_SEED_BASE + index,
                id = "tpl-${template.id}",
                gender = template.gender,
                fullName = template.name,
                surname = template.name.take(TEMPLATE_SURNAME_PREFIX_LENGTH),
                spiritRootType = template.spiritRootType,
                realm = CharacterTemplateDb.STARTUP_REALM,
                realmLayer = CharacterTemplateDb.STARTUP_REALM_LAYER,
                templateId = template.id,
                portraitResOverride = template.portraitKey
            )
            // Kotlin 侧身份字段必须完全来自模板（C++ 侧一致性已由 assertCombat 保证）
            assertEquals("模板立绘键必须原样落 portraitRes", template.portraitKey, disciple.portraitRes)
            assertEquals("templateId 必须落模板 id", template.id, disciple.templateId)
            assertEquals("姓名必须取自模板", template.name, disciple.name)
            assertEquals("境界必须取开局口径", CharacterTemplateDb.STARTUP_REALM, disciple.realm)
        }
    }

    private companion object {
        /** 模板分支对拍种子基（逐模板 +1；数值本身无业务含义，不产生黄金基线） */
        const val TEMPLATE_BRANCH_SEED_BASE = 20260925L

        /** 模板姓氏前缀长度，与 `DiscipleService` 的 SURNAME_PREFIX_LENGTH 口径一致 */
        const val TEMPLATE_SURNAME_PREFIX_LENGTH = 1
    }
}
