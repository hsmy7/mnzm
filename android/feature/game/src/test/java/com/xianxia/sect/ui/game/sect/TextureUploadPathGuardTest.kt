package com.xianxia.sect.ui.game.sect

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生产纹理上传路径必须经 TextureCache 守卫（MR3-P3.2 / 方案 D2 验证门槛）。
 *
 * 锁定三面（源码文本解析，沿 [SceneOverlayProtocolGuardTest] 先例）：
 * 1. `NativeBridge.cpp` 五个生产上传 JNI（RGBA 单级 / RGBA mip / 地面 / 岩石 /
 *    ASTC）全部调用 `TextureCache::get().acquire`——同 key 零重传与 refCount
 *    契约的唯一入口；
 * 2. `AtlasAsyncPipeline` 不得直调 `NativeBridge.destroyTexture`（MR1-P1.4
 *    过渡臂已由键控 `textureRelease` 替换）；
 * 3. 键常量只经 `RendererTextureKeys`（禁裸 Long 自造键）——生产 trackAcquiredKey
 *    调用点引用该对象。
 *
 * 失败消息带操作指引：缺哪条、去哪改。
 */
class TextureUploadPathGuardTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "android/$relative"))) {
                if (candidate.exists()) return candidate
            }
            dir = dir.parentFile
        }
        error("仓库文件定位失败：$relative（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun sourceOf(relative: String): String = repoFile(relative).readText()

    private val nativeBridgeCpp: String
        get() = sourceOf("app/src/main/cpp/NativeBridge.cpp")

    private val atlasPipeline: String
        get() = sourceOf(
            "feature/game/src/main/java/com/xianxia/sect/ui/game/sect/AtlasAsyncPipeline.kt"
        )

    private val rendererKeys: String
        get() = sourceOf(
            "core/engine/src/main/java/com/xianxia/sect/core/nativebridge/RendererTextureKeys.kt"
        )

    /** 从 JNI 导出名截取到下一导出/文件尾的函数体片段 */
    private fun jniFunctionBody(source: String, exportSuffix: String): String {
        val sig = "Java_com_xianxia_sect_core_nativebridge_NativeBridge_$exportSuffix"
        val start = source.indexOf(sig)
        assertTrue(
            "NativeBridge.cpp 缺少 JNI 导出 $sig（上传面被改名/删除？）——" +
                "去 android/app/src/main/cpp/NativeBridge.cpp 核对 P3.2 接线",
            start >= 0
        )
        val next = source.indexOf("extern \"C\" JNIEXPORT", start + sig.length)
        return if (next > start) source.substring(start, next) else source.substring(start)
    }

    @Test
    fun `五个生产上传 JNI 均经 TextureCache acquire`() {
        val exports = listOf(
            "uploadTextureDirect",
            "uploadTextureMipChainDirect",
            "uploadGroundTextureDirect",
            "uploadRockTextureDirect",
            "uploadCompressedAtlas",
        )
        for (export in exports) {
            val body = jniFunctionBody(nativeBridgeCpp, export)
            assertTrue(
                "NativeBridge.$export 未走 TextureCache::get().acquire——" +
                    "生产上传必须 miss→upload→insert 经键控缓存（方案 D2/P3.2）。" +
                    "去 NativeBridge.cpp 给该导出包 acquire(key, pinned, uploadFn)",
                body.contains("TextureCache::get().acquire")
            )
        }
    }

    @Test
    fun `AtlasAsyncPipeline 不再直调 destroyTexture 过渡释放`() {
        // MR1-P1.4 过渡臂：previousRound + destroyTexture(id) —— MR3 由键控 release 替换
        assertFalse(
            "AtlasAsyncPipeline 仍直调 NativeBridge.destroyTexture——" +
                "MR1-P1.4 过渡臂应已替换为 textureRelease(packedKey)。" +
                "去 AtlasAsyncPipeline.kt 改键控释放",
            atlasPipeline.contains("NativeBridge.destroyTexture")
        )
        assertTrue(
            "AtlasAsyncPipeline 应经 textureRelease 键控释放上一轮引用（P3.2）",
            atlasPipeline.contains("NativeBridge.textureRelease")
        )
    }

    @Test
    fun `键常量只经 RendererTextureKeys 族`() {
        assertTrue(
            "RendererTextureKeys 应声明 KEY_ATLAS_ASTC（与 C++ texture_key 同值）",
            rendererKeys.contains("KEY_ATLAS_ASTC")
        )
        assertTrue(
            "AtlasAsyncPipeline 生产登记应引用 RendererTextureKeys 常量，禁裸 Long 自造键",
            atlasPipeline.contains("RendererTextureKeys.KEY_")
        )
        // 反向：不得出现手写 pack( 字面量拼键（允许的只有 RendererTextureKeys 自身）
        val pipelinePackLiterals = Regex("""pack\s*\(\s*\d+""").containsMatchIn(atlasPipeline)
        assertFalse(
            "AtlasAsyncPipeline 不得手写 pack(assetId, ...) 字面量拼键——" +
                "一律经 RendererTextureKeys 常量",
            pipelinePackLiterals
        )
    }

    @Test
    fun `JNI 面 textureAcquire Release UnpinAll 三导出在位`() {
        for (export in listOf("textureAcquire", "textureRelease", "textureUnpinAll")) {
            assertTrue(
                "NativeBridge.cpp 缺少 $export 导出（线程契约表四通道 / D2.4 pinned 迁移）",
                nativeBridgeCpp.contains("NativeBridge_$export")
            )
        }
    }

    @Test
    fun `P3-3 上传后断开宿主字节缓冲引用`() {
        // AtlasPayload.releaseHostBuffers 置空 ktx/rgba/direct 缓冲；
        // start() 在 onReady 前调用——静态断言调用点与方法体在位
        assertTrue(
            "AtlasAsyncPipeline 应在 onReady 前调用 payload.releaseHostBuffers()（P3.3）",
            atlasPipeline.contains("payload.releaseHostBuffers()")
        )
        assertTrue(
            "AtlasPayload.releaseHostBuffers 应置空 ktx ByteArray（P3.3 峰值）",
            atlasPipeline.contains("fun releaseHostBuffers()")
        )
        // 断言方法体覆盖三类宿主缓冲（ktx + mip + ground/rock direct）
        val methodStart = atlasPipeline.indexOf("fun releaseHostBuffers()")
        assertTrue("releaseHostBuffers 方法体缺失", methodStart > 0)
        val methodBody = atlasPipeline.substring(methodStart, methodStart + 600)
        for (field in listOf("ktx = null", "rgbaMipPixels = null", "groundPixels = null")) {
            assertTrue(
                "releaseHostBuffers 应断开 $field 引用（P3.3）",
                methodBody.contains(field)
            )
        }
    }
}
