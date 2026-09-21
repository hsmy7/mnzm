package com.xianxia.sect.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xianxia.sect.core.model.MailEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SR-1 邮件槽位快照存取语义实跑（真 `GameDatabase`，Robolectric 内存库）。
 *
 * 覆盖生产写路径的两段 DAO 语义（`StorageEngineWriteOps` 整对象替换 = 先删后写，
 * 两段共用本文件的 DAO 调用形状）：
 * - 读：[MailDao.getAllForSlotSync]（保存编排注入 SaveData.mails 用）——槽位全量、
 *   sendTime DESC、槽位隔离；
 * - 替换：deleteAllForSlot + insertAll（slotId 盖章）——旧行清零、新快照全量回填、
 *   邻槽零影响；空快照 ⇒ 表空（旧档缺 tag 56 的兼容语义）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MailSnapshotStoreTest {

    private companion object {
        const val SLOT = 5
        const val OTHER_SLOT = 6
        const val T0 = 1_760_000_000_000L
    }

    private lateinit var db: GameDatabase
    private lateinit var dao: MailDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, GameDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mailDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun mail(id: String, slot: Int, sendTime: Long) = MailEntity(
        id = id, slotId = slot, title = "件-$id", sendTime = sendTime,
        hasAttachment = true, attachmentClaimed = false
    )

    @Test
    fun `getAllForSlotSync returns full slot snapshot ordered by sendTime desc`() = runBlocking {
        dao.insertAll(
            listOf(
                mail("a", SLOT, T0),
                mail("b", SLOT, T0 + 500),
                mail("c", SLOT, T0 + 100),
                mail("other", OTHER_SLOT, T0 + 900)
            )
        )

        val snapshot = dao.getAllForSlotSync(SLOT)

        assertEquals("槽位全量（3 件，邻槽不计入）", listOf("b", "c", "a"), snapshot.map { it.id })
    }

    @Test
    fun `whole-object replace clears old rows and restores snapshot without touching other slots`() =
        runBlocking {
            dao.insertAll(
                listOf(
                    mail("old-1", SLOT, T0),
                    mail("old-2", SLOT, T0 + 1),
                    mail("keep-me", OTHER_SLOT, T0 + 2)
                )
            )

            // 生产写路径同形：先删后写（写侧 slotId 盖章）
            val replacement = listOf(
                mail("new-1", 0, T0 + 100),
                mail("new-2", 0, T0 + 200)
            )
            dao.deleteAllForSlot(SLOT)
            dao.insertAll(replacement.map { it.copy(slotId = SLOT) })

            val replaced = dao.getAllForSlotSync(SLOT)
            assertEquals("旧行清零、新快照全量回填", listOf("new-2", "new-1"), replaced.map { it.id })
            assertEquals("新行槽位已盖章", listOf(SLOT, SLOT), replaced.map { it.slotId })
            assertEquals("邻槽零影响", 1, dao.countMails(OTHER_SLOT))
            assertEquals("邻槽内容不变", "keep-me", dao.getById(OTHER_SLOT, "keep-me")?.id)
        }

    @Test
    fun `empty snapshot replace leaves slot table empty (legacy-save semantics)`() = runBlocking {
        dao.insertAll(listOf(mail("legacy", SLOT, T0)))

        // 旧档无 mails 字段 ⇒ 快照空表 ⇒ 整对象替换后表空（方案明示单向兼容）
        dao.deleteAllForSlot(SLOT)

        assertTrue(dao.getAllForSlotSync(SLOT).isEmpty())
        assertEquals(0, dao.countMails(SLOT))
    }
}
