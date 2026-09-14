package com.xianxia.sect.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移链守卫（`ALL_MIGRATIONS` 单点登记表的连续性）。
 *
 * ## 为什么需要
 * `@Database(version)` 递增时必须同步登记 `MIGRATION_(N-1)_N`，否则：
 * - 生产侧：老档打开即失败（Room 找不到迁移路径）；
 * - 测试侧：各迁移测试的建库链滞后（**2026-09-15 v50→v51 实测**：5 个测试文件
 *   12 处链尾全部失效，Room 报 `A migration from 38 to 51 was required but not found`）。
 *
 * 测试侧已收敛为共用 `ALL_MIGRATIONS`，本守卫负责生产侧：断言该表
 * **自 3 连续覆盖到 `DATABASE_VERSION` 且逐条为 N→N+1**，任一处遗漏即失败。
 */
class MigrationChainGuardTest {

    @Test
    fun `migration chain is contiguous up to DATABASE_VERSION`() {
        val actual = ALL_MIGRATIONS.map { it.endVersion }
        val expected = (FIRST_MIGRATION_END_VERSION..GameDatabaseConfig.DATABASE_VERSION).toList()
        assertEquals(
            "ALL_MIGRATIONS 必须自 $FIRST_MIGRATION_END_VERSION 连续覆盖到 DATABASE_VERSION=" +
                "${GameDatabaseConfig.DATABASE_VERSION}（缺号 = 递增版本时漏登记 MIGRATION_(N-1)_N，" +
                "在 GameDatabase.kt 的 ALL_MIGRATIONS 末尾追加）",
            expected,
            actual
        )
    }

    @Test
    fun `every migration advances exactly one version`() {
        ALL_MIGRATIONS.forEach { m ->
            assertEquals(
                "迁移 ${m.startVersion}→${m.endVersion} 必须恰好前进一个版本" +
                    "（跳版会让 Room 找不到中间路径）",
                m.startVersion + 1,
                m.endVersion
            )
        }
    }

    @Test
    fun `chain tail matches database version`() {
        val last = ALL_MIGRATIONS.last()
        assertEquals(
            "链尾 endVersion 必须等于 DATABASE_VERSION=${GameDatabaseConfig.DATABASE_VERSION}" +
                "（两者不等 ⇒ 版本已递增但迁移未登记）",
            GameDatabaseConfig.DATABASE_VERSION,
            last.endVersion
        )
    }

    @Test
    fun `chain is strictly ascending`() {
        val versions = ALL_MIGRATIONS.map { it.endVersion }
        assertTrue("ALL_MIGRATIONS 必须升序且无重复：$versions", versions == versions.sorted().distinct())
    }

    private companion object {
        /** 首条登记的迁移为 `MIGRATION_2_3`（v2 档可升）。 */
        const val FIRST_MIGRATION_END_VERSION = 3
    }
}
