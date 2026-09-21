package com.xianxia.sect.ui.game

import com.xianxia.sect.core.engine.GameStateSnapshot
import com.xianxia.sect.core.model.Alliance
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.core.model.MailEntity
import com.xianxia.sect.core.model.Pill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SR-1：SaveDataTrimmer 邮件快照注入测试。
 *
 * `trimSaveData(snapshot, mails)` 的 mails 为**必填参数**（无默认值 = 编译器强制
 * 每个 SaveData 构造点显式面对整对象替换语义）——本测试锁注入传递行为：
 * 传入快照原样进入 `SaveData.mails`，零邮件构造 ⇒ 空表。
 */
class SaveDataTrimmerMailTest {

    private fun snapshot(): GameStateSnapshot = GameStateSnapshot(
        gameData = GameData(sectName = "注入测试宗"),
        disciples = emptyList(),
        equipmentStacks = emptyList(),
        equipmentInstances = emptyList(),
        manualStacks = emptyList(),
        manualInstances = emptyList(),
        pills = listOf(Pill()),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList(),
        battleLogs = emptyList(),
        alliances = listOf(Alliance())
    )

    @Test
    fun `trimSaveData carries mails snapshot into SaveData`() {
        val mails = listOf(
            MailEntity(
                id = "mail-1", slotId = 2, title = "含附件未领",
                hasAttachment = true, attachmentClaimed = false,
                attachments = "[{\"type\":\"material\",\"name\":\"灵石\",\"quantity\":100}]"
            ),
            MailEntity(id = "mail-2", slotId = 2, isRead = true, attachmentClaimed = true)
        )

        val saveData = SaveDataTrimmer.trimSaveData(snapshot(), mails)

        assertEquals("邮件快照原样传递（整对象替换语义的注入侧）", mails, saveData.mails)
        assertTrue("其余域不受影响", saveData.pills.isNotEmpty() && saveData.alliances.isNotEmpty())
    }

    @Test
    fun `trimSaveData with empty mails yields empty SaveData mails`() {
        val saveData = SaveDataTrimmer.trimSaveData(snapshot(), emptyList())

        assertTrue("空快照 = 空表（旧档兼容形态的构造侧同形）", saveData.mails.isEmpty())
    }
}
