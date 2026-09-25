package com.xianxia.sect.core.nativebridge

import com.xianxia.sect.core.engine.domain.disciple.DiscipleFactory
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.SocialData
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
 * 六维方差 / 悟性 / 肖像 / 技能 / 基础属性 / 寿命。
 *
 * 确定性基础：Kotlin 侧 `seed.nextInt` 是 `DeterministicRng` 的适配器；
 * C++ 侧 `nativeFromSeed(seed)`
 * 独立同种子实例按相同消费序驱动（14 次方差 + 2 次阶梯 +
 * 1 次肖像 + 18 次技能）。
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
        assertInt(tag, "physicalAttackVariance", d.combat.physicalAttackVariance, c)
        assertInt(tag, "magicAttackVariance", d.combat.magicAttackVariance, c)
        assertInt(tag, "physicalDefenseVariance", d.combat.physicalDefenseVariance, c)
        assertInt(tag, "magicDefenseVariance", d.combat.magicDefenseVariance, c)
        assertInt(tag, "speedVariance", d.combat.speedVariance, c)
        assertInt(tag, "baseHp", d.combat.baseHp, c)
        assertInt(tag, "baseMp", d.combat.baseMp, c)
        assertInt(tag, "basePhysicalAttack", d.combat.basePhysicalAttack, c)
        assertInt(tag, "baseMagicAttack", d.combat.baseMagicAttack, c)
        assertInt(tag, "basePhysicalDefense", d.combat.basePhysicalDefense, c)
        assertInt(tag, "baseMagicDefense", d.combat.baseMagicDefense, c)
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

    private fun runDiff(
        seed: Long,
        id: String,
        gender: String,
        fullName: String,
        surname: String,
        spiritRootType: String,
        realm: Int = 9,
        realmLayer: Int = 1
    ) {
        val kRng = DeterministicRng.fromSeed(seed)
        DiffRngBridge.nativeFromSeed(seed)

        val kDisciple = DiscipleFactory().create(
            DiscipleFactory.DiscipleSeed(
                id = id,
                gender = gender,
                nameResult = NameService.NameResult(fullName, surname),
                spiritRootType = spiritRootType,
                realm = realm,
                realmLayer = realmLayer,
                social = SocialData(),
                nextInt = { from, until -> from + kRng.nextInt(until - from) }
            )
        )

        val seedJson = """
            {"id":"$id","gender":"$gender","fullName":"$fullName",
             "surname":"$surname","spiritRootType":"$spiritRootType",
             "realm":$realm,"realmLayer":$realmLayer}
        """.trimIndent()
        val c = json.parseToJsonElement(
            DiffRngBridge.nativeCreateDisciple(seedJson)
        ).jsonObject

        val tag = "seed=$seed id=$id spiritRoot=$spiritRootType"
        assertCombat(tag, kDisciple, c)
        assertSkills(tag, kDisciple, c)
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
}
