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
 * 云档→本地缓存落盘段单测。
 *
 * 两条职责：① verdict 分流 / 管线拒绝 / 落盘失败 / 账本收敛的行为锚定（文案单点
 * 定义在本组件）；② 单档下载落盘链——落缓存 + 账本基线收敛到云端 W（换设备续玩的
 * 持久化面：落盘后本机保存才能与云端序号连续对账）。
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
        coEvery { storageFacade.save(any()) } returns SaveResult.success(Unit)
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
        saveId: Long?,
        verdict: ArbitrationVerdict = ArbitrationVerdict.LOCAL_BEHIND,
        version: Int = SAVE_VERSION_OK,
        integrity: SavePayloadIntegrity = SavePayloadIntegrity.VERIFIED
    ) {
        coEvery { saveBackend.download() } returns SaveBackendResult.Success(
            CloudSavePayload(saveData(version), saveId = saveId, verdict = verdict, integrity = integrity)
        )
    }

    // ── ① 正常落盘链（换设备续玩的持久化面）──

    @Test
    fun `下载落盘 - 落缓存并按云端 W 收敛账本`() = runTest {
        stubDownload(saveId = 7L)

        val outcome = writer.downloadIntoCache()

        assertTrue(outcome is CloudSaveCacheWriter.Outcome.Written)
        coVerify { storageFacade.save(any()) }
        verify { uploadLedger.adoptCloudState(7L) }
        verify(exactly = 0) { uploadLedger.recordLocalSave() }
    }

    @Test
    fun `下载落盘 W 未知 - 落缓存但账本保持原状（存量档 U11）`() = runTest {
        stubDownload(saveId = null)

        writer.downloadIntoCache()

        coVerify { storageFacade.save(any()) }
        verify(exactly = 0) { uploadLedger.adoptCloudState(any()) }
    }

    @Test
    fun `载荷完整性判据原样透传给调用侧文案`() = runTest {
        stubDownload(saveId = 4L, integrity = SavePayloadIntegrity.MISMATCH)

        val outcome = writer.downloadIntoCache()

        assertEquals(
            SavePayloadIntegrity.MISMATCH,
            (outcome as CloudSaveCacheWriter.Outcome.Written).integrity
        )
    }

    // ── ② 拒绝与失败面：文案单点定义，禁止静默覆盖 ──

    @Test
    fun `后端 CONFLICT - 不落盘不收敛，交冲突面二选一`() = runTest {
        coEvery { saveBackend.download() } returns SaveBackendResult.Failure(
            SaveBackendError.CONFLICT,
            "本地与云端均有新进度，需要选择保留哪一份"
        )

        val outcome = writer.downloadIntoCache()

        assertEquals(CloudSaveCacheWriter.Outcome.ConflictPending, outcome)
        coVerify(exactly = 0) { storageFacade.save(any()) }
        verify(exactly = 0) { uploadLedger.adoptCloudState(any()) }
    }

    @Test
    fun `UPLOAD_PENDING verdict - 拒绝覆盖本机未上传进度，文案逐字锚定`() = runTest {
        stubDownload(saveId = 5L, verdict = ArbitrationVerdict.UPLOAD_PENDING)

        val outcome = writer.downloadIntoCache()

        assertEquals(
            "本机有未上传的新进度，已停止从云端覆盖：请联网等待自动上传完成后重试",
            (outcome as CloudSaveCacheWriter.Outcome.Rejected).message
        )
        coVerify(exactly = 0) { storageFacade.save(any()) }
    }

    @Test
    fun `下载失败 - 原因如实带回`() = runTest {
        coEvery { saveBackend.download() } returns SaveBackendResult.Failure(
            SaveBackendError.NETWORK,
            "连接超时"
        )

        val outcome = writer.downloadIntoCache()

        assertEquals("云存档下载失败：连接超时", (outcome as CloudSaveCacheWriter.Outcome.Rejected).message)
    }

    @Test
    fun `saveVersion 高于当前版本 - 版本戳仅作识别，仍走校验落盘`() = runTest {
        stubDownload(saveId = 3L, version = SAVE_VERSION_AHEAD)

        val outcome = writer.downloadIntoCache()

        assertTrue("高版本云档应正常落盘，实际 $outcome", outcome is CloudSaveCacheWriter.Outcome.Written)
        coVerify(exactly = 1) { storageFacade.save(any()) }
    }

    @Test
    fun `落缓存失败 - 不收敛账本（IN1：账本只在缓存真的落盘后推进）`() = runTest {
        stubDownload(saveId = 9L)
        coEvery { storageFacade.save(any()) } returns SaveResult.failure(
            SaveError.SAVE_FAILED,
            "disk io error"
        )

        val outcome = writer.downloadIntoCache()

        assertEquals("云存档落盘失败：disk io error", (outcome as CloudSaveCacheWriter.Outcome.Rejected).message)
        verify(exactly = 0) { uploadLedger.adoptCloudState(any()) }
    }

    private companion object {
        /** 现役可加载的存档版本（与 SR-3 用例同值） */
        const val SAVE_VERSION_OK = 2

        /** 高于当前版本的版本戳（saveVersion 仅作识别，加载管线不按版本拒绝） */
        const val SAVE_VERSION_AHEAD = 99
    }
}
