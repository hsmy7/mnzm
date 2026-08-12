package com.xianxia.sect.core.config

import com.xianxia.sect.core.GameConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 真实 assets 配置 JSON 与代码常量一致性守卫（对抗性审查补强）。
 *
 * [GameConfigConsistencyTest] 只守卫"代码常量 vs data class 默认值"两源，
 * 从不读取 assets 实际 JSON——若运营修改 game_config.json 数值（或字段名拼错
 * 静默回退默认值），运行时使用与代码默认值不同的数值而测试全部通过。
 * 本测试读取真实 JSON 文件，断言 realmGap 数值与 [GameConfig] 常量一致。
 */
class ConfigJsonConsistencyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun realmGapValues_matchGameConfigConstants() {
        val file = File("src/main/assets/config/game_config.json")
        val text = file.readText()
        val config = json.decodeFromString<GameConfigData>(text)
        val realmGap = config.battle.realmGap

        assertEquals(
            "JSON damageBonusPerLayer 与 GameConfig 常量不一致",
            GameConfig.Battle.RealmGap.DAMAGE_BONUS_PER_LAYER, realmGap.damageBonusPerLayer, 0.001
        )
        assertEquals(
            "JSON damageReductionPerLayer 与 GameConfig 常量不一致",
            GameConfig.Battle.RealmGap.DAMAGE_REDUCTION_PER_LAYER, realmGap.damageReductionPerLayer, 0.001
        )
        assertEquals(
            "JSON instantKillGap 与 GameConfig 常量不一致",
            GameConfig.Battle.RealmGap.INSTANT_KILL_GAP.toDouble(), realmGap.instantKillGap.toDouble(), 0.001
        )
        assertEquals(
            "JSON damageBonusPerMajorRealm 与 GameConfig 常量不一致",
            GameConfig.Battle.RealmGap.DAMAGE_BONUS_PER_MAJOR_REALM, realmGap.damageBonusPerMajorRealm, 0.001
        )
    }
}
