package com.xianxia.sect.data.engine

import com.xianxia.sect.data.result.StorageError
import com.xianxia.sect.data.result.StorageResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 后置步骤（`.sav` 文件镜像 / `.bak` 备份）降级原因映射的单元测试。
 *
 * 背景（审计 §12-C）：旧实现里备份 `Failure`/异常**只记指标、不改写 result**
 * ⇒ 调用方仍见 `isSuccess` ⇒ UI 报"游戏保存成功"，而文件镜像/备份实际未落盘。
 * 本映射是"主保存成功但文件面降级"的**唯一出口**：非 null 即必须如实提示。
 */
class BackupDegradationReasonTest {

    @Test
    fun `success maps to no warning`() {
        assertNull(
            "主保存 + 镜像 + 备份全部完成 ⇒ 无告警",
            backupWriteDegradationReason(StorageResult.success(Unit))
        )
    }

    @Test
    fun `skipped maps to its reason`() {
        assertEquals(
            "备份因超过 100MB 上限被跳过（主保存成功，非阻断）",
            backupWriteDegradationReason(StorageResult.skipped("备份因超过 100MB 上限被跳过（主保存成功，非阻断）"))
        )
    }

    @Test
    fun `skipped with blank message still yields readable reason`() {
        // 空原因会让 UI 弹出"游戏保存成功（）"——必须有兜底文案
        assertEquals(
            "备份被跳过（主保存成功）",
            backupWriteDegradationReason(StorageResult.skipped(""))
        )
    }

    @Test
    fun `failure maps to its message`() {
        assertEquals(
            "重命名 .tmp → .sav 失败 slot=3",
            backupWriteDegradationReason(
                StorageResult.failure(StorageError.IO_ERROR, "重命名 .tmp → .sav 失败 slot=3")
            )
        )
    }

    @Test
    fun `failure with blank message still yields readable reason`() {
        assertEquals(
            "文件镜像/备份写入失败（主保存成功）",
            backupWriteDegradationReason(StorageResult.failure(StorageError.BACKUP_FAILED))
        )
    }
}
