package com.xianxia.sect.data.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SR-1 邮件快照写路径守卫（源码扫描，口径同 `ClearAllSlotTablesCoverageTest`）。
 *
 * 锁三件事：
 * 1. `writeAllDataToDatabase` 链路（clearOldSlotEntities + writeCoreEntities）
 *    必须包含邮件整对象替换的删/写两段——后人重构写路径时漏掉任何一段 ⇒ 变红；
 * 2. `replaceMailsForSlot`（云恢复单表替换）必须单事务先删后写，且**不吞内层异常**
 *    （SR-0 §5：Room 2.7.0 吞内层异常 = 仅回滚内层写，不得依赖其做部分提交）；
 * 3. `StorageFacade` 暴露 getMailsForSlot / replaceMailsForSlot 窄口
 *    （feature 注入面唯一通道）。
 */
class MailSnapshotWritePathGuardTest {

    private companion object {
        /** 单元测试工作目录 = 模块根（`android/core/data`） */
        const val WRITE_OPS_SRC = "src/main/java/com/xianxia/sect/data/engine/StorageEngineWriteOps.kt"
        const val MAIL_OPS_SRC = "src/main/java/com/xianxia/sect/data/engine/StorageEngineMailOps.kt"
        const val FACADE_SRC = "src/main/java/com/xianxia/sect/data/facade/StorageFacade.kt"
    }

    @Test
    fun `writeAllDataToDatabase chain performs mail whole-object replacement`() {
        val src = File(WRITE_OPS_SRC).readText()
        val clearFun = src.substringAfter("fun StorageEngine.clearOldSlotEntities").substringBefore("\n}")
        val coreWriteFun = src.substringAfter("fun StorageEngine.writeCoreEntities").substringBefore("\n}")
        val writeMailsFun = File(MAIL_OPS_SRC).readText()
            .substringAfter("fun StorageEngine.writeMails").substringBefore("\n}")

        assertTrue(
            "clearOldSlotEntities 必须删 mails（整对象替换·删侧）",
            clearFun.contains("mailDao().deleteAllForSlot(slot)")
        )
        assertTrue(
            "writeCoreEntities 必须调用 writeMails（整对象替换·写侧，同外层事务）",
            coreWriteFun.contains("writeMails(slot, data)")
        )
        assertTrue(
            "writeMails 必须经 mailDao().insertAll 回填快照",
            writeMailsFun.contains("mailDao().insertAll")
        )
        assertTrue(
            "writeMails 必须按 slot 盖章（copy(slotId = slot)）",
            writeMailsFun.contains("copy(slotId = slot)")
        )
    }

    @Test
    fun `replaceMailsForSlot is single-transaction delete-then-insert without swallowing inner failures`() {
        val mailOps = File(MAIL_OPS_SRC).readText()
        val replaceBody = mailOps.substringAfter("fun StorageEngine.replaceMailsForSlot").substringBefore("\n}")

        assertTrue(
            "replaceMailsForSlot 必须在 withTransaction 内先删后写（IN1 原子性）",
            replaceBody.contains("withTransaction") &&
                replaceBody.indexOf("deleteAllForSlot") in 0 until replaceBody.indexOf("insertAll")
        )
        assertTrue(
            "replaceMailsForSlot 不得吞内层异常（Room 2.7.0 吞内层异常 = 仅回滚内层写，SR-0 §5 警示）",
            !replaceBody.contains("catch")
        )
    }

    @Test
    fun `StorageFacade exposes mail snapshot narrow surface`() {
        val src = File(FACADE_SRC).readText()

        assertTrue(
            "StorageFacade 必须暴露 getMailsForSlot（保存编排注入通道）",
            src.contains("suspend fun getMailsForSlot(slot: Int): List<MailEntity>")
        )
        assertTrue(
            "StorageFacade 必须暴露 replaceMailsForSlot（云恢复通道）",
            src.contains("suspend fun replaceMailsForSlot(slot: Int, mails: List<MailEntity>)")
        )
    }
}
