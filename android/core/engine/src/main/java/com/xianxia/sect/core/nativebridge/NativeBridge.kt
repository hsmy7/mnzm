package com.xianxia.sect.core.nativebridge

/**
 * NativeBridge — JNI 桥接到 C++ 2D 渲染引擎。
 *
 * 所有 JNI 函数对应 NativeBridge.cpp 中的 extern "C" 实现。
 * 渲染器架构：单 Pipeline + 单纹理图集 + 持久映射 VBO。
 */
object NativeBridge {

    /** 是否已加载原生库 */
    private var loaded = false

    /** 加载原生库 */
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("native-renderer")
            loaded = true
        }
    }

    // ============================================================
    // 两阶段初始化预加载（Phase 1）
    // ============================================================

    /** 预加载 Vulkan 设备和着色器（在加载界面阶段调用，无 Surface 依赖） */
    external fun prewarmDevice(
        cacheDir: String,
        worldW: Int, worldH: Int, tileSize: Int
    ): Boolean

    // ============================================================
    // 纹理图集
    // ============================================================

    /** 初始化精灵图集 UV 映射 */
    external fun initAtlas(): Boolean

    /** 获取精灵的 UV 坐标 [u0, v0, u1, v1] */
    external fun getAtlasUV(name: String): FloatArray?

    // ============================================================
    // 渲染器生命周期
    // ============================================================

    /** 初始化渲染器（surface = android.view.Surface 对象） */
    external fun initRenderer(
        viewportW: Int, viewportH: Int,
        worldW: Int, worldH: Int, tileSize: Int,
        surface: android.view.Surface
    ): Boolean

    /** 关闭渲染器 */
    external fun shutdownRenderer()

    /** 调整窗口大小 */
    external fun resizeRenderer(width: Int, height: Int): Boolean

    // ============================================================
    // 纹理上传
    // ============================================================

    /** 上传纹理到 GPU，返回纹理 ID */
    external fun uploadTexture(pixelData: ByteArray, width: Int, height: Int): Int

    // ============================================================
    // 帧渲染
    // ============================================================

    /** 开始帧（清空 pending draw calls） */
    external fun beginFrame()

    /** 设置相机投影矩阵 */
    external fun setCamera(
        camX: Float, camY: Float, scale: Float,
        vpW: Int, vpH: Int
    )

    /** 统一瓦片绘制（地面+装饰+建筑合并到图集单次 draw call） */
    external fun drawAllTiles(
        tileData: IntArray,          // 展平瓦片类型数组 [0..N]
        cols: Int, rows: Int,        // 地图网格尺寸
        buildingData: FloatArray?,   // 建筑数据 [x,y,w,h,nameIdx] × count
        buildingCount: Int,          // 建筑数量
        buildingVisible: Boolean,    // 是否显示建筑
        tileSize: Int,
        atlasTexId: Int,
        uvMap: FloatArray,           // UV 映射 [u0,v0,u1,v1] 按 tile 类型索引
        buildingUVMap: FloatArray?   // 建筑 UV 映射
    )

    /** 绘制纯色矩形（网格线/放置预览） */
    external fun drawRect(
        x: Float, y: Float, w: Float, h: Float,
        r: Float, g: Float, b: Float, a: Float
    )

    /** 从图集绘制精灵纹理（用于建造/移动预览的半透明建筑） */
    external fun drawSprite(
        x: Float, y: Float, w: Float, h: Float,
        atlasTexId: Int,
        u0: Float, v0: Float, u1: Float, v1: Float,
        r: Float, g: Float, b: Float, a: Float
    )

    /** 提交帧到 GPU */
    external fun submitFrame()

    /** 渲染器是否就绪 */
    external fun isRendererReady(): Boolean
}
