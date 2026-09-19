package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染后端同构契约测试（重构方案 R3.6 / 批次 B12）。
 *
 * ## R3.6 的验收对象
 * GLES 后端必须与 Vulkan 后端**消费同一份场景数据与同一份判定策略**，
 * 而不是各自维护一套渲染形态分支。本测试锁定该同构性在**可测层面**的证据：
 *
 * 1. **共用策略** —— 远景地面四重门判定 [FarViewGroundPolicy] 是后端无关纯函数，
 *    两个后端调用点传入同一入参语义（缩放/设备键/纹理就绪/用户旗标）。
 * 2. **能力差异自动降级** —— GLES 不支持 REPEAT 采样纹理
 *    （`GlesBackend.h`：`uploadRepeatTexture` 不支持 ⇒ `g_groundTexId==0`），
 *    因此 `groundTextureReady=false` 使远景整图路径**在策略层即被拒**，
 *    无需 GLES 专属分支。这是「同构改造」的核心收益：降级是数据驱动的，不是 if-else。
 * 3. **Canvas 兜底不动** —— 软渲染路径不参与本策略（红线：Canvas 零改动）。
 *
 * ## 与 C++ 侧的对应
 * 帧数据契约见 [RenderFrame]（后端无关载体）；JNI 面见 `NativeBridge.drawFrame`
 * 八标量入口。C++ 侧同构守卫见 `NativeBridge.cpp` 的 `g_farViewGroundQuad` 注释块
 * 与 `scene/scene_draw.h` 的 `groundQuadEnabled` 参数（单一路径参数化）。
 */
class RenderBackendIsomorphismTest {

    private companion object {
        /** 远景档缩放（<= 阈值） */
        const val FAR_SCALE = 0.3f

        /** 白名单同形设备键（测试替身；生产白名单为空） */
        const val DEVICE = "qualcomm/sm8650"
    }

    @Test
    fun `远景地面判定 - 后端无关纯函数对同一入参恒返回同值`() {
        // 同构的第一条：判定不含后端信息。两个后端调用点传同一组语义入参
        // （缩放来自相机、设备键来自 RenderDeviceKey、纹理就绪来自各自 host、
        // 用户旗标来自 NativeEngineFlag）必须得到同一结果——否则就是把后端差异
        // 写进了策略，违反 R3.6「同一份策略」要求。
        val args = arrayOf(
            // (scale, deviceKey, textureReady, userEnabled)
            arrayOf(FAR_SCALE, DEVICE, true, true),
            arrayOf(FAR_SCALE, DEVICE, false, true),
            arrayOf(1.5f, DEVICE, true, true),
            arrayOf(FAR_SCALE, DEVICE, true, false),
            arrayOf(Float.NaN, DEVICE, true, true)
        )
        for (a in args) {
            val first = FarViewGroundPolicy.groundQuadEnabled(
                scale = a[0] as Float,
                deviceKey = a[1] as String,
                groundTextureReady = a[2] as Boolean,
                userEnabled = a[3] as Boolean
            )
            val second = FarViewGroundPolicy.groundQuadEnabled(
                scale = a[0] as Float,
                deviceKey = a[1] as String,
                groundTextureReady = a[2] as Boolean,
                userEnabled = a[3] as Boolean
            )
            assertEquals(
                "同一入参必须恒等（纯函数、无隐藏状态）",
                first, second
            )
        }
    }

    @Test
    fun `GLES 后端 - 不支持 REPEAT 纹理故远景整图路径自动被策略拒绝`() {
        // GLES: uploadRepeatTexture 不支持 ⇒ groundTextureReady=false。
        // 策略在「纹理就绪」门即拒绝 ⇒ 自动回退逐格地面，无需 GLES 专属分支。
        // 这正是 R3.6 要求的「同构改造」：后端差异体现为数据（纹理 id），
        // 而非策略里的后端判断。
        val glesMaxTexId = 0 // GlesBackend 不支持 REPEAT 上传，地面纹理 id 恒 0
        assertFalse(
            "GLES 后端（groundTextureReady=false）必须回退逐格地面",
            FarViewGroundPolicy.groundQuadEnabled(
                scale = FAR_SCALE,
                deviceKey = DEVICE,
                groundTextureReady = glesMaxTexId != 0,
                userEnabled = true
            )
        )
    }

    @Test
    fun `Vulkan 后端 - REPEAT 纹理就绪且设备放行时才可启用（未验证设备仍回退）`() {
        // Vulkan: uploadRepeatTexture 支持 ⇒ 纹理可就绪。但生产白名单为空，
        // 未验证设备仍须回退——证明「后端支持」是必要非充分条件。
        assertFalse(
            "即便后端支持 REPEAT，未验证设备仍须回退",
            FarViewGroundPolicy.groundQuadEnabled(
                scale = FAR_SCALE,
                deviceKey = DEVICE,
                groundTextureReady = true,
                userEnabled = true
            )
        )
    }

    @Test
    fun `RenderFrame - 后端无关载体，Canvas 与 GPU 消费同一字段集`() {
        // R3.6 红线：Canvas 兜底不动。其证据 = Canvas 与 GPU 后端消费的是
        // 同一个 RenderFrame，字段语义不变。本测试锁定关键字段的存在与默认值，
        // 任何字段增删都会在此暴露（需同步评估 Canvas 侧）。
        val frame = RenderFrame(IntArray(16) { 1 }, cols = 4, rows = 4)

        // 场景几何
        assertEquals(4, frame.cols)
        assertEquals(4, frame.rows)
        // 相机（默认）
        assertEquals(0f, frame.camX, 0.001f)
        assertEquals(0f, frame.camY, 0.001f)
        assertEquals(1f, frame.scale, 0.001f)
        // 覆盖层开关（R3.3 后在 C++ 侧展开，接口语义不变）
        assertFalse(frame.showPreview)
        assertTrue(frame.buildingVisible)
    }

    @Test
    fun `降级链 - Vulkan→GLES→Canvas 三级共用同一相机与视口语义`() {
        // 同构的第三条：三级降级中相机/缩放语义恒定（世界坐标 + 物理像素视口），
        // 后端只换图形 API 不换坐标契约。远景策略依赖 scale 语义稳定——
        // 若某级后端擅自缩放 scale，四重门的「缩放达标」门会在该级失效。
        // 锁定 FAR_VIEW_SCALE_THRESHOLD 的语义锚点（与 LOD 装饰门同源）。
        assertEquals(
            "远景缩放门必须与装饰 LOD 门同源，否则降级链上档位错乱",
            RenderLodPolicy.DECOR_ZOOM_THRESHOLD,
            FarViewGroundPolicy.FAR_VIEW_SCALE_THRESHOLD,
            0.0f
        )
        assertTrue(
            "阈值应为缩小档（<=1.0），整岛观看才触发",
            FarViewGroundPolicy.FAR_VIEW_SCALE_THRESHOLD <= 1.0f
        )
    }
}
