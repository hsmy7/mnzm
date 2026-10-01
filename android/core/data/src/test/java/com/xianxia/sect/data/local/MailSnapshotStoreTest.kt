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
 * SR-1 邮件快照存取语义实跑（真 `GameDatabase`，Robolectric 内存库）。
 *
 * 覆盖生产写路径的两段 DAO 语义（`StorageEngineWriteOps` 整对象替换 = 先删后写，
 * 两段共用本文件的 DAO 调用形状）：
 * - 读：[MailDao.getAllSync]（保存编排注入 SaveData.mails 用）——全量、sendTime DESC；
 * - 替换：deleteAll + insertAll——旧行清零、新快照全量回填；空快照 ⇒ 表空
 *   （旧档缺 tag 56 的兼容语义）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MailSnapshotStoreTest {

    private companion object {
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

    private fun mail(id: String, sendTime: Long) = MailEntity(
        id = id, title = "件-$id", sendTime = sendTime,
        hasAttachment = true, attachmentClaimed = false
    )

    @Test
    fun `getAllSync returns full snapshot ordered by sendTime desc`() = runBlocking {
        dao.insertAll(
            listOf(
                mail("a", T0),
                mail("b", T0 + 500),
                mail("c", T0 + 100)
            )
        )

        val snapshot = dao.getAllSync()

        assertEquals("全量（3 件）", listOf("b", "c", "a"), snapshot.map { it.id })
    }

    @Test
    fun `whole-object replace clears old rows and restores snapshot`() = runBlocking {
        dao.insertAll(
            listOf(
                mail("old-1", T0),
                mail("old-2", T0 + 1),
                mail("keep-me", T0 + 2)
            )
        )

        // 生产写路径同形：先删后写
        val replacement = listOf(
            mail("new-1", T0 + 100),
            mail("new-2", T0 + 200)
        )
        dao.deleteAll()
        dao.insertAll(replacement)

        val replaced = dao.getAllSync()
        assertEquals("旧行清零、新快照全量回填", listOf("new-2", "new-1"), replaced.map { it.id })
    }

    @Test
    fun `empty snapshot replace leaves table empty (legacy-save semantics)`() = runBlocking {
        dao.insertAll(listOf(mail("legacy", T0)))

        // 旧档无 mails 字段 ⇒ 快照空表 ⇒ 整对象替换后表空（方案明示单向兼容）
        dao.deleteAll()

        assertTrue(dao.getAllSync().isEmpty())
        assertEquals(0, dao.countMails())
    }
}
