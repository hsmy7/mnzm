package com.xianxia.sect.data.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * IN1 原子性守卫（SR-7 C7 补口）——缓存层 DB 写必须整体待在单个事务里。
 *
 * 方案 §3 IN1：「所有缓存层 DB 写在单事务内；后置步骤（上传/归档）失败只降级不回滚本地」。
 * 既有覆盖的缺口：`RoomNestedTransactionSemanticsTest` 测的是 **Room 库语义**（嵌套事务
 * 并入外层），把 `performFullTransactionSave` 的外层 `withTransaction` 拆掉它照样绿；
 * `MailSnapshotWritePathGuardTest` 只锁邮件表那一段。本守卫补的是**生产代码形状**这一层。
 *
 * 两条断言都有真实失败模式：把 `withTransaction` 换成裸调用、或在事务外新插一行写，
 * 都会在此判红。
 */
class CacheWriteAtomicityGuardTest {

    private companion object {
        val ENGINE = "core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngine.kt"
        val SAVE_SUPPORT = "core/data/src/main/java/com/xianxia/sect/data/engine/StorageEngineSaveSupport.kt"
        val MODULES = listOf("app", "core/data", "core/domain", "core/engine", "core/ui", "feature/game")
    }

    @Test
    fun `全量保存的 DB 写包在单个 withTransaction 内`() {
        val body = functionBody(ENGINE, "private suspend fun performFullTransactionSave")
        assertTrue("写库入口 writeAllDataToDatabase 必须仍在 performFullTransactionSave 内",
            "writeAllDataToDatabase" in body)
        val txnAt = body.indexOf("withTransaction")
        val writeAt = body.indexOf("writeAllDataToDatabase")
        assertTrue("performFullTransactionSave 必须经 withTransaction 包裹 DB 写（IN1）", txnAt >= 0)
        assertTrue("withTransaction 必须出现在 DB 写之前（IN1：不得退化成事务外裸写）", txnAt < writeAt)
    }

    @Test
    fun `删档全表清单跑在单个 withTransaction 内`() {
        val body = functionBody(SAVE_SUPPORT, "internal suspend fun StorageEngine.clearAllSlotTables")
        val txnAt = body.indexOf("withTransaction")
        val firstDeleteAt = body.indexOf("Dao().delete")
        assertTrue("clearAllSlotTables 必须整体在 withTransaction 内（IN1 + 审计 §12-K）", txnAt >= 0)
        assertTrue("第一条删表语句必须在事务之内", firstDeleteAt > txnAt)
    }

    /**
     * 取函数体。先归一 CRLF（不归一时边界切分会**静默返回全文**，守卫随即空转），
     * 再断言切分确实生效——同 `WalRetirementGuardTest` 的反空转纪律。
     */
    private fun functionBody(relative: String, signature: String): String {
        val text = androidFile(relative).readText().replace("\r\n", "\n").replace("\r", "\n")
        val tail = text.substringAfter(signature)
        val body = tail.substringBefore("\n    }\n")
        assertTrue(
            "$signature 的函数体切分失败（拿到全文=边界失配，本守卫会空转）",
            body.length < tail.length
        )
        return body
    }

    private fun androidRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = if (dir.name == "android") dir else File(dir, "android")
            if (MODULES.all { File(candidate, "$it/src/main").isDirectory }) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("定位不到 android 源根（user.dir=${System.getProperty("user.dir")}）——守卫不得静默跳过")
    }

    private fun androidFile(relative: String): File = File(androidRoot(), relative)
}
