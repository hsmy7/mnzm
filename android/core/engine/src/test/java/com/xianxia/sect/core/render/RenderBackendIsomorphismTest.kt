package com.xianxia.sect.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 渲染后端同构契约测试（R3.6 骨架延续；地图边缘 v2 更新）。
 *
 * GLES 与 Vulkan 后端必须**消费同一份场景数据**（RenderFrame + SceneStore），
 * 而不是各自维护渲染形态分支。旧「远景地面四重门策略」随整图 REPEAT quad
 * 一起退役（地图边缘 v2：地皮/底部 mesh 恒走独立 REPEAT 纹理，GLES 侧由
 * `uploadRepeatTexture` 的 POT 守卫提供同等能力），能力差异重新收敛为
 * 数据驱动（纹理 id=0 ⇒ 对应层跳过）。
 *
 * 本文件锁定同构性在可测层面的证据：
 * 1. [RenderFrame] 是后端无关载体——边界复合数据以同一字段
 *    [RenderFrame.groundBoundaryData] 送达双端；
 * 2. [GroundBoundaryBridge] 的输出布局常量跨后端唯一（头部/掩码位/复合版本）。
 */
class RenderBackendIsomorphismTest {

    @Test
    fun `RenderFrame - 后端无关载体，Canvas 与 GPU 消费同一字段集`() {
        // R3.6 红线：Canvas 兜底与 GPU 路径消费同一个 RenderFrame，字段语义不变。
        // 本测试锁定关键字段的存在与默认值，任何字段增删都会在此暴露。
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
        // 地图边缘 v2：边界复合数据同字段送达双端（null = 未接线）
        assertEquals(null, frame.groundBoundaryData)
    }

    @Test
    fun `GroundBoundary 布局常量 - 跨后端唯一（头部掩码位与复合版本）`() {
        // 双端（Vulkan/GLES 走 SceneStore，Canvas 直读 RenderFrame）消费的
        // 复合布局与掩码语义必须同源——常量与 C++ ground_boundary.h 一致，
        // 漂移由 DiffGroundBoundaryTest / ground_boundary_test 即红。
        assertEquals(1, GroundBoundaryBridge.Header.VERSION)
        assertEquals(11, GroundBoundaryBridge.Header.FLOATS)
        assertEquals(GroundBoundaryGenerator.VERSION, GroundBoundaryBridge.Header.VERSION)
        assertEquals(GroundBoundaryGenerator.HEADER_FLOATS, GroundBoundaryBridge.Header.FLOATS)
        assertEquals(1, GroundBoundaryBridge.MASK_BIT_QUAD)
        assertEquals(2, GroundBoundaryBridge.MASK_BIT_TREE)
    }
}
