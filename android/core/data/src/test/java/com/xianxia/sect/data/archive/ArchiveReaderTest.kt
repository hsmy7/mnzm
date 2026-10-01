package com.xianxia.sect.data.archive

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.xianxia.sect.core.model.BattleLog
import com.xianxia.sect.core.model.BattleResult
import com.xianxia.sect.core.model.BattleType
import com.xianxia.sect.core.model.Disciple
import com.xianxia.sect.core.model.DiscipleStatus
import com.xianxia.sect.data.local.GameDatabase
import com.xianxia.sect.data.engine.encodeArchivedBattleLogBlob
import com.xianxia.sect.data.engine.encodeArchivedDiscipleBlob
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 归档读面单测：列表（按归档时间倒序 + limit）+ 按 id 还原载荷（往返等价）
 * + 损坏/空载荷如实降级 null + 概览计数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveReaderTest {

    private lateinit var db: GameDatabase
    private lateinit var reader: ArchiveReader

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, GameDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reader = ArchiveReader(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun battleLog(id: String, timestamp: Long) = BattleLog(
        id = id,
        timestamp = timestamp,
        year = 12,
        month = 7,
        type = BattleType.SECT_WAR,
        attackerName = "青云宗",
        defenderName = "血煞门",
        result = BattleResult.WIN,
        details = "宗门战：$id"
    )

    private fun archivedBattleLog(id: String, archivedAt: Long) = ArchivedBattleLog(
        originalId = id,
        battleType = BattleType.SECT_WAR.name,
        result = BattleResult.WIN.name,
        timestamp = 1_700_000_000_000L,
        attackerName = "青云宗",
        defenderName = "血煞门",
        dataBlob = encodeArchivedBattleLogBlob(battleLog(id, 1_700_000_000_000L)),
        archivedAt = archivedAt
    )

    @Test
    fun `列表按归档时间倒序返回且受 limit 约束`() = runTest {
        db.archivedBattleLogDao().insertAll(
            listOf(
                archivedBattleLog("b-old", archivedAt = 1_000L),
                archivedBattleLog("b-new", archivedAt = 2_000L),
                archivedBattleLog("b-mid", archivedAt = 1_500L)
            )
        )

        val all = reader.listRecentBattleLogs()
        assertEquals(listOf("b-new", "b-mid", "b-old"), all.map { it.originalId })

        val limited = reader.listRecentBattleLogs(limit = 2)
        assertEquals(listOf("b-new", "b-mid"), limited.map { it.originalId })
    }

    @Test
    fun `按id还原战报载荷与原日志往返等价`() = runTest {
        val original = battleLog("b-roundtrip", 1_700_000_000_000L)
        db.archivedBattleLogDao().insertAll(listOf(archivedBattleLog(original.id, archivedAt = 1L)))

        val rowId = db.archivedBattleLogDao().listRecent(10).single().id
        val restored = reader.restoreBattleLog(rowId)

        assertEquals(original, restored)
    }

    @Test
    fun `按id还原弟子载荷与原弟子往返等价`() = runTest {
        val original = Disciple(id = "d-dead", name = "林寒", isAlive = false, status = DiscipleStatus.DEAD)
        db.archivedDiscipleDao().insertAll(
            listOf(
                ArchivedDisciple(
                    originalId = original.id,
                    name = original.name,
                    realm = original.realm,
                    dataBlob = encodeArchivedDiscipleBlob(original),
                    archivedAt = 1L
                )
            )
        )

        val rowId = db.archivedDiscipleDao().listRecent(10).single().id
        val restored = reader.restoreDisciple(rowId)

        assertEquals(original, restored)
    }

    @Test
    fun `载荷损坏或为空时还原如实降级为 null`() = runTest {
        db.archivedBattleLogDao().insertAll(
            listOf(
                archivedBattleLog("b-corrupt", archivedAt = 1L).copy(dataBlob = "bm90LWEtYmF0dGxlLWxvZw=="),
                archivedBattleLog("b-empty", archivedAt = 2L).copy(dataBlob = "")
            )
        )

        val rows = db.archivedBattleLogDao().listRecent(10).associateBy { it.originalId }
        assertNull(reader.restoreBattleLog(rows.getValue("b-corrupt").id))
        assertNull(reader.restoreBattleLog(rows.getValue("b-empty").id))
    }

    @Test
    fun `概览返回两表行数`() = runTest {
        assertEquals(ArchiveOverview(0, 0), reader.overview())

        db.archivedBattleLogDao().insertAll(listOf(archivedBattleLog("b-1", archivedAt = 1L)))
        db.archivedDiscipleDao().insertAll(
            listOf(
                ArchivedDisciple(
                    originalId = "d-1",
                    name = "林寒",
                    realm = 5,
                    dataBlob = encodeArchivedDiscipleBlob(Disciple(id = "d-1", name = "林寒")),
                    archivedAt = 1L
                )
            )
        )

        val overview = reader.overview()
        assertEquals(1, overview.battleLogCount)
        assertEquals(1, overview.discipleCount)
        assertTrue(overview.battleLogCount > 0)
    }
}
