package com.xianxia.sect.data.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 删档完整性静态守卫（审计 §12-K）。
 *
 * 背景：删档有两条路径——`StorageEngine.delete`（用户删档）与 `clearSlotDataQuietly`
 * （tombstone 命中/恢复拒绝时清理残留）。审计实证两者**清单漂移**：删档清 29 表，
 * tombstone 只清 `game_data` + `disciples` **两表** ⇒ 残留 27 表行（合规与正确性双重问题），
 * 且全链漏删 4 张带 slot 列的表（`archived_battle_logs` / `archived_disciples` /
 * `overflow_mail_drafts` / `direct_mail_drafts`）。
 *
 * 处置 = 收敛为**唯一实现** `clearAllSlotTables`，并由本守卫锁"清单与 `@Database` 实体同步"：
 * 新增 Room 实体而忘记补删行 ⇒ 本用例变红。
 *
 * 口径：`GameDatabase` 声明的全部 DAO 访问器 − [EXEMPT]（无 slot 列的全局表）
 * 必须**恰好等于**清理清单里被调用的 DAO 集合（双向相等，多删少删都报错）。
 */
class ClearAllSlotTablesCoverageTest {

    private companion object {
        /** 单元测试工作目录 = 模块根（`android/core/data`），与 RoomMigration 系列同基准 */
        const val SUPPORT_SRC = "src/main/java/com/xianxia/sect/data/engine/StorageEngineSaveSupport.kt"
        const val DATABASE_SRC = "src/main/java/com/xianxia/sect/data/local/GameDatabase.kt"

        /**
         * 豁免：`change_log` 表**没有 slot 列**（全局变更日志，非槽位域），
         * 删档不应清它。新增豁免必须在此显式登记理由。
         */
        val EXEMPT = setOf("changeLogDao")
    }

    @Test
    fun `clearAllSlotTables covers every slot-scoped DAO declared in GameDatabase`() {
        val cleared = clearedDaoNames()
        val declared = declaredDaoNames()

        // 防正则失配导致的"空集假绿"：本批（SR-7 v53）删 6 张零读者镜像表后
        // 清单与声明面各为 26/27 ⇒ 阈值从 30 下调到 25（仍远高于任何可能的解析部分失配，
        // 且新增表时不必回调；下调本身由双向相等断言兜住，不会因为阈值宽松而漏判漂移）
        assertTrue("清理清单解析结果异常（仅 ${cleared.size} 个 DAO）", cleared.size >= 25)
        assertTrue("GameDatabase DAO 解析结果异常（仅 ${declared.size} 个）", declared.size >= 25)

        assertEquals(
            "删档清理清单必须与 @Database 槽位域 DAO 集合双向相等：" +
                "少删 = 残留玩家数据（合规问题），多删 = 清单漂移",
            declared - EXEMPT,
            cleared
        )
    }

    @Test
    fun `delete and tombstone cleanup share the same implementation`() {
        val engineSrc = readSource("src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt")
        val supportSrc = readSource(SUPPORT_SRC)

        assertTrue(
            "StorageEngine.delete 必须调用 clearAllSlotTables（不得内联自己的清单）",
            engineSrc.contains("clearAllSlotTables(slot)")
        )
        assertTrue(
            "clearSlotDataQuietly 必须调用 clearAllSlotTables（审计 §12-K：旧实现只删 2 表）",
            supportSrc.contains("clearSlotDataQuietly") &&
                supportSrc.substringAfter("fun StorageEngine.clearSlotDataQuietly")
                    .substringBefore("\n}").contains("clearAllSlotTables(slot)")
        )
    }

    private fun clearedDaoNames(): Set<String> {
        val body = readSource(SUPPORT_SRC)
            .substringAfter("fun StorageEngine.clearAllSlotTables")
            .substringBefore("\n}")
        return Regex("core\\.database\\.(\\w+Dao)\\(\\)")
            .findAll(body).map { it.groupValues[1] }.toSet()
    }

    private fun declaredDaoNames(): Set<String> =
        Regex("abstract fun (\\w+Dao)\\(\\)")
            .findAll(readSource(DATABASE_SRC)).map { it.groupValues[1] }.toSet()

    private fun readSource(relative: String): String {
        val file = File(relative)
        assertTrue(
            "源文件应存在（cwd=${File(".").absolutePath}）: $relative",
            file.exists()
        )
        return file.readText()
    }
}
