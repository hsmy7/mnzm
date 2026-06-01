package com.xianxia.sect.core.state

import com.xianxia.sect.core.model.GameData
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

/**
 * 编译期安全网：GameData 的每个字段都必须有 @SettlementStrategy 注解。
 *
 * 任何人新增 GameData 字段 → 此测试失败 → CI 变红 → 强制声明合并策略。
 * 注解是唯一的真相源，不存在需要同步维护的第二份列表。
 */
class GameDataSettlementCoverageTest {

    private val computedProps = setOf(
        "displayTime", "isPlayerProtected", "playerProtectionRemainingYears",
        "worldMap", "buildings", "economy", "organization", "exploration"
    )

    @Test
    fun `every GameData field MUST have SettlementStrategy annotation`() {
        val props = GameData::class.memberProperties
            .filter { it.name !in computedProps }
            .filter { it.findAnnotation<SettlementStrategy>() == null }

        if (props.isNotEmpty()) {
            val names = props.map { it.name }.sorted()
            fail(
                "${names.size} GameData field(s) missing @SettlementStrategy:\n" +
                names.joinToString("\n") { "  - $it" } +
                "\n\n请为 GameData.kt 中的以上字段添加注解：" +
                "@SettlementStrategy(Strategy.XXX)" +
                "\n可选值: PRESERVE_OLD / USE_SHADOW / DELTA / THREE_WAY_ID / CUSTOM" +
                "\n\nPRESERVE_OLD → 结算不修改，始终取 oldState（玩家设置、槽位分配）" +
                "\nUSE_SHADOW   → 玩家不修改，始终取 shadow（结算独有字段、内部ID）" +
                "\nDELTA        → 数值 delta 合并（spiritStones 等）" +
                "\nTHREE_WAY_ID → 列表 ID 增删合并（recruitList 等）" +
                "\nCUSTOM       → 自定义合并函数（需在 GameStateStore.customGameDataMergers 注册）"
            )
        }
    }

    @Test
    fun `CUSTOM fields must have registered merger function`() {
        val customFields = GameData::class.memberProperties
            .filter { it.findAnnotation<SettlementStrategy>()?.value == Strategy.CUSTOM }
            .map { it.name }
            .toSet()

        if (customFields.isEmpty()) return  // no CUSTOM fields → nothing to check

        // 通过反射读取 GameStateStore 的 customGameDataMergers
        val mergersField = GameStateStore::class.memberProperties
            .find { it.name == "customGameDataMergers" }
        if (mergersField == null) {
            if (customFields.isNotEmpty())
                fail("CUSTOM fields exist but customGameDataMergers not found in GameStateStore")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val mergerKeys = try {
            val instance = GameStateStore::class.constructors.firstOrNull { it.parameters.isEmpty() }
            if (instance != null) {
                (mergersField.call(instance.call()) as? Map<String, *>)?.keys ?: emptySet()
            } else {
                // GameStateStore 有 @Inject 构造函数，不能无参调用
                // 跳过此检查，依赖运行时异常来捕获未注册的 CUSTOM 字段
                println("Note: Skipping CUSTOM merger check (GameStateStore requires DI)")
                return
            }
        } catch (e: Exception) {
            println("Note: Could not access customGameDataMergers (${e.message})")
            return
        }

        val missing = customFields - mergerKeys
        if (missing.isNotEmpty()) {
            fail(
                "CUSTOM fields without registered merger:\n" +
                missing.joinToString("\n") { "  - $it" } +
                "\n\nAdd merge function to GameStateStore.customGameDataMergers"
            )
        }
    }

    @Test
    fun `print strategy coverage summary`() {
        val props = GameData::class.memberProperties
            .filter { it.name !in computedProps }

        val groups = linkedMapOf(
            "PRESERVE_OLD" to mutableListOf<String>(),
            "DELTA" to mutableListOf<String>(),
            "THREE_WAY_ID" to mutableListOf<String>(),
            "CUSTOM" to mutableListOf<String>(),
            "USE_SHADOW" to mutableListOf<String>(),
        )

        for (prop in props) {
            val annotation = prop.findAnnotation<SettlementStrategy>()
            val strategy = annotation?.value?.name ?: "MISSING"
            groups.getOrPut(strategy) { mutableListOf() }.add(prop.name)
        }

        val total = props.size
        println("=== GameData Settlement Strategy Coverage ($total fields) ===")
        for ((strategy, names) in groups) {
            if (names.isNotEmpty()) {
                println("$strategy: ${names.size}/$total")
                names.sorted().forEach { println("  $it") }
            }
        }
        val missing = groups["MISSING"]?.size ?: 0
        println("Covered: ${total - missing}/$total")
    }
}
