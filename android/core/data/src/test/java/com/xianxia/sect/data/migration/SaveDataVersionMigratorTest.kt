package com.xianxia.sect.data.migration

import com.xianxia.sect.core.model.CombatAttributes
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.SectRelation
import com.xianxia.sect.data.model.SaveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.ceil

/**
 * SaveDataVersionMigrator 单元测试。
 *
 * 2026-08-04 云读档管线统一：迁移逻辑从 StorageEngine 提取为公共 Migrator，
 * 本地读档与云存档加载共用。本测试对齐旧实现（StorageEngine.migrateSaveDataIfNeeded）
 * 行为逐项，防止提取过程改变迁移语义。
 */
class SaveDataVersionMigratorTest {

    private fun baseSaveData(gd: GameData, disciples: List<Disciple> = emptyList()): SaveData {
        return SaveData(
            gameData = gd,
            disciples = disciples,
            pills = emptyList(),
            materials = emptyList(),
            herbs = emptyList(),
            seeds = emptyList(),
            teams = emptyList()
        )
    }

    @Test
    fun `migrate - saveVersion 0 - scales cultivation to one tenth and advances to v2`() {
        val gd = GameData(
            sectName = "测试宗",
            saveVersion = 0,
            sectCultivation = 100.0,
            recruitList = listOf(
                Disciple(cultivation = 200.0, combat = CombatAttributes(totalCultivation = 201L))
            ),
            aiSectDisciples = mapOf(
                "sect1" to listOf(
                    Disciple(cultivation = 300.0, combat = CombatAttributes(totalCultivation = 301L))
                )
            ),
            sectRelations = listOf(SectRelation(sectId1 = "a", sectId2 = "b", acquainted = false))
        )
        val disciples = listOf(
            Disciple(cultivation = 400.0, combat = CombatAttributes(totalCultivation = 401L))
        )

        val migrated = SaveDataVersionMigrator.migrate(baseSaveData(gd, disciples))

        assertEquals("v0 应一次迁移到 v2", SaveDataVersionMigrator.CURRENT_SAVE_VERSION, migrated.gameData.saveVersion)
        assertEquals("宗门修炼值缩放 1/10", 10.0, migrated.gameData.sectCultivation, 0.001)
        assertEquals("弟子修炼值缩放 1/10", 40.0, migrated.disciples[0].cultivation, 0.001)
        assertEquals("弟子战力向上取整", ceil(401.0 / 10).toLong(), migrated.disciples[0].combat.totalCultivation)
        assertEquals("招募列表修炼值缩放", 20.0, migrated.gameData.recruitList[0].cultivation, 0.001)
        assertEquals("招募列表战力向上取整", ceil(201.0 / 10).toLong(), migrated.gameData.recruitList[0].combat.totalCultivation)
        assertEquals("AI 弟子修炼值缩放", 30.0, migrated.gameData.aiSectDisciples["sect1"]!![0].cultivation, 0.001)
        assertEquals("sectRelations 升级为 acquainted", true, migrated.gameData.sectRelations[0].acquainted)
    }

    @Test
    fun `migrate - saveVersion 1 - only upgrades sectRelations to acquainted`() {
        val gd = GameData(
            sectName = "测试宗",
            saveVersion = 1,
            sectCultivation = 50.0,
            sectRelations = listOf(SectRelation(sectId1 = "a", sectId2 = "b", acquainted = false))
        )
        val disciples = listOf(
            Disciple(cultivation = 80.0, combat = CombatAttributes(totalCultivation = 81L))
        )

        val migrated = SaveDataVersionMigrator.migrate(baseSaveData(gd, disciples))

        assertEquals("v1 迁移到 v2", SaveDataVersionMigrator.CURRENT_SAVE_VERSION, migrated.gameData.saveVersion)
        assertEquals("v1 不缩放宗门修炼值", 50.0, migrated.gameData.sectCultivation, 0.001)
        assertEquals("v1 不缩放弟子修炼值", 80.0, migrated.disciples[0].cultivation, 0.001)
        assertEquals("v1 不改战力值", 81L, migrated.disciples[0].combat.totalCultivation)
        assertEquals("sectRelations 升级为 acquainted", true, migrated.gameData.sectRelations[0].acquainted)
    }

    @Test
    fun `migrate - saveVersion 2 - returns data unchanged`() {
        val gd = GameData(
            sectName = "测试宗",
            saveVersion = 2,
            sectCultivation = 50.0,
            sectRelations = listOf(SectRelation(sectId1 = "a", sectId2 = "b", acquainted = false))
        )
        val saveData = baseSaveData(gd)

        val migrated = SaveDataVersionMigrator.migrate(saveData)

        assertSame("v2 原样返回同一实例", saveData, migrated)
    }
}
