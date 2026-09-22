package com.xianxia.sect.ui.game.saveload

import android.util.Log
import com.xianxia.sect.core.model.GameData
import com.xianxia.sect.data.cloud.ArbitrationVerdict
import com.xianxia.sect.data.cloud.CloudSavePayload
import com.xianxia.sect.data.cloud.SaveBackend
import com.xianxia.sect.data.cloud.SaveBackendError
import com.xianxia.sect.data.cloud.SaveBackendResult
import com.xianxia.sect.data.cloud.UploadLedger
import com.xianxia.sect.data.crypto.SavePayloadIntegrity
import com.xianxia.sect.data.facade.StorageFacade
import com.xianxia.sect.data.model.SaveData
import com.xianxia.sect.data.unified.SaveError
import com.xianxia.sect.data.unified.SaveResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 云档→本地缓存落盘段单测（SR-6 C4）。
 *
 * 两条职责：① SR-3 抽段的**行为等价**（verdict 分流 / 管线拒绝 / 落盘失败 / 账本收敛，
 * 文案单点定义在本组件，SR-3 那 13 例持真实本组件跑同一链路互为背书）；
 * ② SR-6 新增的**跨槽语义**——存量单档 `mnzm_cloud_save`（slot 0）落到玩家选定的空槽 N，
 * 且**不把源槽的保存序号抄进目标槽**（[UploadLedger] 序号按槽独立）。
 */
class CloudSaveCacheWriterTest {

    private val saveBackend: SaveBackend = mockk(relaxed = true)
    private val storageFacade: StorageFacade = mockk(relaxed = true)
    private val uploadLedger: UploadLedger = mockk(relaxed = true)

    private lateinit var writer: CloudSaveCacheWriter

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        writer = CloudSaveCacheWriter(saveBackend, storageFacade, uploadLedger)
        coEvery { storageFacade.save(any(), any()) } returns SaveResult.success(Unit)
    }

    @After
    fun tearDown() = unmockkAll()

    private fun saveData(version: Int = SAVE_VERSION_OK): SaveData = SaveData(
        gameData = GameData(sectName = "青云宗", saveVersion = version),
        disciples = emptyList(),
        pills = emptyList(),
        materials = emptyList(),
        herbs = emptyList(),
        seeds = emptyList()
    )

    private fun stubDownload(
        sourceSlot: Int,
        saveId: Long?,
        verdict: ArbitrationVerdict = ArbitrationVerdict.LOCAL_BEHIND,
        version: Int = SAVE_VERSION_OK,
        integrity: SavePayloadIntegrity = SavePayloadIntegrity.VERIFIED
    ) {
        coEvery { saveBackend.download(sourceSlot) } returns SaveBackendResult.Success(
            CloudSavePayload(saveData(version), saveId = saveId, verdict = verdict, integrity = integrity)
        )
    }

    // ── ① 同槽（SR-3 既有语义等价）──

    @Test
    fun `同槽下载 - 落缓存并按云端 W 收敛账本`() = runTest {
        stubDownload(2, saveId = 7L)

        val outcome = writer.downloadIntoCache(sourceSlot = 2, targetSlot = 2)

        assertTrue(outcome is CloudSaveCacheWriter.Outcome.Written)
        assertEquals(2, (outcome as CloudSaveCacheWriter.Outcome.Written).targetSlot)
        coVerify { storageFacade.save(2, any()) }
        verify { uploadLedger.adoptCloudState(2, 7L) }
        verify(exactly = 0) { uploadLedger.recordLocalSave(any()) }
    }

    @Test
    fun `同槽下载 W 未知 - 落缓存但账本保持原状（存量档 U11）`() = runTest {
        stubDownload(3, saveId = null)

        writer.downloadIntoCache(sourceSlot = 3, targetSlot = 3)

        coVerify { storageFacade.save(3, any()) }
        verify(exactly = 0) { uploadLedger.adoptCloudState(any(), any()) }
    }

    @Test
    fun `载荷完整性判据原样透传给调用侧文案`() = runTest {
        stubDownload(1, saveId = 4L, integrity = SavePayloadIntegrity.MISMATCH)

        val outcome = writer.downloadIntoCache(sourceSlot = 1, targetSlot = 1)

        assertEquals(
            SavePayloadIntegrity.MISMATCH,
            (outcome as CloudSaveCacheWriter.Outcome.Written).integrity
        )
    }

    // ── ② 跨槽（SR-6 存量单档迁移）──

    @Test
    fun `跨槽迁移 - 落到目标空槽且不抄源槽序号`() = runTest {
        stubDownload(0, saveId = 11L)

        val outcome = writer.downloadIntoCache(sourceSlot = 0, targetSlot = 4)

        assertEquals(4, (outcome as CloudSaveCacheWriter.Outcome.Written).targetSlot)
        coVerify { storageFacade.save(4, any()) }
        coVerify(exactly = 0) { storageFacade.save(0, any()) }
        verify(exactly = 0) { uploadLedger.adoptCloudState(any(), any()) }
    }

    // ── ③ 拒绝与失败面：文案单点定义，禁止静默覆盖 ──

    @Test
    fun `后端 CONFLICT - 不落盘不收敛，交冲突面二选一`() = runTest {
        coEvery { saveBackend.download(4) } returns SaveBackendResult.Failure(
            SaveBackendError.CONFLICT,
            "本地与云端均有新进度，需要选择保留哪一份"
        )

        val outcome = writer.downloadIntoCache(sourceSlot = 4, targetSlot = 4)

        assertEquals(CloudSaveCacheWriter.Outcome.ConflictPending, outcome)
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
        verify(exactly = 0) { uploadLedger.adoptCloudState(any(), any()) }
    }

    @Test
    fun `UPLOAD_PENDING verdict - 拒绝覆盖本机未上传进度，文案逐字锚定`() = runTest {
        stubDownload(1, saveId = 5L, verdict = ArbitrationVerdict.UPLOAD_PENDING)

        val outcome = writer.downloadIntoCache(sourceSlot = 1, targetSlot = 1)

        assertEquals(
            "本机此槽位有未上传的新进度，已停止从云端覆盖：请联网等待自动上传完成后重试",
            (outcome as CloudSaveCacheWriter.Outcome.Rejected).message
        )
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
    }

    @Test
    fun `下载失败 - 原因如实带回`() = runTest {
        coEvery { saveBackend.download(2) } returns SaveBackendResult.Failure(
            SaveBackendError.NETWORK,
            "连接超时"
        )

        val outcome = writer.downloadIntoCache(sourceSlot = 2, targetSlot = 2)

        assertEquals("云存档下载失败：连接超时", (outcome as CloudSaveCacheWriter.Outcome.Rejected).message)
    }

    @Test
    fun `saveVersion 越界 - 管线拒绝且不落盘`() = runTest {
        stubDownload(6, saveId = 3L, version = SAVE_VERSION_OUT_OF_RANGE)

        val outcome = writer.downloadIntoCache(sourceSlot = 6, targetSlot = 6)

        val message = (outcome as CloudSaveCacheWriter.Outcome.Rejected).message
        assertTrue("实际文案：$message", message.startsWith("云存档版本异常："))
        coVerify(exactly = 0) { storageFacade.save(any(), any()) }
    }

    @Test
    fun `落缓存失败 - 不收敛账本（IN1：账本只在缓存真的落盘后推进）`() = runTest {
        stubDownload(5, saveId = 9L)
        coEvery { storageFacade.save(5, any()) } returns SaveResult.failure(
            SaveError.SAVE_FAILED,
            "disk io error"
        )

        val outcome = writer.downloadIntoCache(sourceSlot = 5, targetSlot = 5)

        assertEquals("云存档落盘失败：disk io error", (outcome as CloudSaveCacheWriter.Outcome.Rejected).message)
        verify(exactly = 0) { uploadLedger.adoptCloudState(any(), any()) }
    }

    private companion object {
        /** 现役可加载的存档版本（与 SR-3 用例同值） */
        const val SAVE_VERSION_OK = 2

        /** 越界版本：迁移器必须显式拒绝而非"尽力加载" */
        const val SAVE_VERSION_OUT_OF_RANGE = 99
    }
}
