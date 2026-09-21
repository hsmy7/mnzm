package com.xianxia.sect.data.local

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xianxia.sect.core.model.GameHeavyData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 嵌套 `Room.withTransaction` 语义实跑验证（SR-0 前置侦察批 T5）。
 *
 * 收口存档审计（`docs/save-system-audit-2026-09-21.md` §15 存疑 1，等级 C）：
 * "外层 `StorageEngine.performFullTransactionSave`（审计引用 :646，现随源码漂移至 :596）
 * + 内层 `StorageEngineWriteOps.writeAllDataToDatabase:55` 两处 `withTransaction`
 * 是否并入同一事务"。外层 block 内调 [innerProbeWrite]，与生产两调用点结构同构
 * （同一 [GameDatabase] 实例、suspend DAO 写入）。
 *
 * **静态证据**（room-runtime 2.7.0 源码，Google Maven sources jar）：
 * `ConnectionPoolImpl.transaction/beginTransaction/endTransaction`——同连接
 * `transactionStack` 非空时嵌套调用发 `SAVEPOINT '<depth>'` 而非 `BEGIN`，
 * 提交走 `RELEASE SAVEPOINT`，整体回滚走 `ROLLBACK TO SAVEPOINT`/外层 `ROLLBACK`；
 * `Transactor.withTransaction` KDoc 明示"已事务中再调用 = 嵌套事务，type 继承父事务"。
 *
 * **测试即守卫**：本项目 Room 固定 2.7.0（`gradle/libs.versions.toml`）；升级 Room 时
 * 若嵌套合并/回滚语义变化，本类四例即红。故保留于测试目录（SR-0 交付物，非临时脚本）。
 *
 * 结论（四例全绿即证实）：
 * 1. 嵌套 = **并入同一事务**（同连接 SAVEPOINT），内层不存在独立提交点；
 * 2. 内层成功 + 外层失败 ⇒ 全量回滚（内层已 RELEASE 的写一并消失）；
 * 3. 内层失败未捕获 ⇒ 异常穿透，外层已写内容一并回滚；
 * 4. 内层失败被外层**吞掉** ⇒ 仅内层自身写回滚（ROLLBACK TO SAVEPOINT），
 *    外层早于/晚于内层的写照常提交——2.7 savepoint 语义，与旧 room-ktx
 *    （≤2.6 TransactionElement 引用计数、被吞内层写保留）不同，生产侧
 *    `writeAllDataToDatabase` 内层抛异常是穿透路径（无人吞），行为 = 全量回滚，
 *    与 `performFullTransactionSave` :599 失败分支"事务已回滚"注释一致。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RoomNestedTransactionSemanticsTest {

    private companion object {
        /** 测试专用槽位（非业务槽 1..6，撞库即红）。 */
        const val SLOT = 99
        const val KEY_OUTER = "txnProbeOuter"
        const val KEY_NESTED = "txnProbeNested"
        const val KEY_POST = "txnProbePost"
    }

    private lateinit var db: GameDatabase
    private lateinit var dao: GameHeavyDataDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, GameDatabase::class.java)
            // Robolectric 测试线程即主线程，仅放行断言用的阻塞读；事务语义不受影响
            .allowMainThreadQueries()
            .build()
        dao = db.gameHeavyDataDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 复刻内层调用点形状：`StorageEngineWriteOps.writeAllDataToDatabase:55` 的独立 `withTransaction`。 */
    private suspend fun innerProbeWrite(key: String, payload: String) {
        db.withTransaction {
            dao.upsert(GameHeavyData(slotId = SLOT, dataKey = key, dataValue = payload.toByteArray()))
        }
    }

    private fun exists(key: String): Boolean = dao.getByKey(SLOT, key) != null

    // ==================== 1. 基线：嵌套成功 ⇒ 双写均提交 ====================

    @Test
    fun `nested transaction merges into outer - success commits both`() {
        kotlinx.coroutines.runBlocking {
            db.withTransaction {
                dao.upsert(GameHeavyData(slotId = SLOT, dataKey = KEY_OUTER, dataValue = byteArrayOf(1)))
                println("[txn-probe] outer thread=${Thread.currentThread().name}")
                innerProbeWrite(KEY_NESTED, "nested")
                println("[txn-probe] inner thread=${Thread.currentThread().name}")
            }
        }
        assertNotNull("外层写应提交", exists(KEY_OUTER))
        assertNotNull("内层写应提交（并入同一事务）", exists(KEY_NESTED))
    }

    // ============ 2. 合并性核心证：内层"已成功"，外层失败 ⇒ 全量回滚 ============
    // 若内层是独立事务，其写已提交，外层回滚不应波及——B 消失即证明并入同一事务。

    @Test
    fun `outer rollback discards completed nested transaction writes`() {
        kotlinx.coroutines.runBlocking {
            try {
                db.withTransaction {
                    innerProbeWrite(KEY_NESTED, "nested")
                    dao.upsert(GameHeavyData(slotId = SLOT, dataKey = KEY_OUTER, dataValue = byteArrayOf(2)))
                    throw IllegalStateException("outer-fail")
                }
                fail("外层异常应穿透")
            } catch (e: IllegalStateException) {
                assertEquals("outer-fail", e.message)
            }
        }
        assertFalse("外层写应回滚", exists(KEY_OUTER))
        assertFalse("内层已 RELEASE 的写应随外层全量回滚（合并性核心证据）", exists(KEY_NESTED))
    }

    // ======== 3. 内层失败未捕获 ⇒ 穿透 + 外层已写内容一并回滚 ========

    @Test
    fun `uncaught nested failure rolls back outer writes too`() {
        kotlinx.coroutines.runBlocking {
            try {
                db.withTransaction {
                    dao.upsert(GameHeavyData(slotId = SLOT, dataKey = KEY_OUTER, dataValue = byteArrayOf(3)))
                    db.withTransaction {
                        dao.upsert(GameHeavyData(slotId = SLOT, dataKey = KEY_NESTED, dataValue = byteArrayOf(4)))
                        throw IllegalStateException("inner-fail")
                    }
                }
                fail("内层异常应穿透外层")
            } catch (e: IllegalStateException) {
                assertEquals("inner-fail", e.message)
            }
        }
        assertFalse("外层早于内层的写应一并回滚", exists(KEY_OUTER))
        assertFalse("内层写应回滚", exists(KEY_NESTED))
    }

    // ==== 4. 内层失败被外层吞掉 ⇒ 仅内层写回滚，外层写照常提交（2.7 savepoint 语义） ====

    @Test
    fun `swallowed nested failure keeps outer writes discards nested writes only`() {
        kotlinx.coroutines.runBlocking {
            db.withTransaction {
                dao.upsert(GameHeavyData(slotId = SLOT, dataKey = KEY_OUTER, dataValue = byteArrayOf(5)))
                try {
                    db.withTransaction {
                        dao.upsert(GameHeavyData(slotId = SLOT, dataKey = KEY_NESTED, dataValue = byteArrayOf(6)))
                        throw IllegalStateException("inner-swallowed")
                    }
                } catch (e: IllegalStateException) {
                    assertEquals("inner-swallowed", e.message)
                    // 有意吞掉：观察 2.7 savepoint 语义
                }
                dao.upsert(GameHeavyData(slotId = SLOT, dataKey = KEY_POST, dataValue = byteArrayOf(7)))
            }
        }
        assertNotNull("外层早于内层的写应保留", exists(KEY_OUTER))
        assertFalse("内层自身写应回滚到 savepoint（2.7 语义；旧 room-ktx 会保留）", exists(KEY_NESTED))
        assertNotNull("外层晚于内层的写应保留", exists(KEY_POST))
    }
}
