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

    /**
     * 上传 KTX1 封装的 ASTC 4×4 LDR 压缩图集（WP7）。
     *
     * C++ 侧 KtxLoader 全字段校验（magic/endianness/glType/glFormat/glInternalFormat/
     * faces/mips/尺寸块对齐/dataSize 几何推导），任一字段非法返回 0；
     * 设备无 `textureCompressionASTC_LDR` 特性亦返回 0——调用方回退 RGBA 图集路径
     * （[uploadTexture]），视觉零差异仅 GPU 显存差异（16MB → 4MB）。
     *
     * @param ktxData KTX1 容器完整字节（64 字节头 + dataSize + ASTC 数据段）
     * @return 纹理 ID；0 = 不支持/校验失败/后端非 VulkanBackend
     */
    external fun uploadCompressedAtlas(ktxData: ByteArray): Int

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

    /**
     * 推送渲染质量热控状态（仿 [setCamera] 独立通道，Compose 线程写、渲染线程单消费者读）。
     * 装饰层跳过条件（C++ 侧）：`decorationsDisabled || qualityFactor < 0.6f`，
     * 与 Canvas [SoftwareCanvasBackend] 帧缓冲 RGB_565 阈值同常量 0.6，双端对齐。
     *
     * @param qualityFactor 质量因子 0-1（1 = 全质量；< 0.6 时装饰层降级跳过）
     * @param decorationsDisabled 装饰层关闭标志
     */
    external fun setRenderQuality(qualityFactor: Float, decorationsDisabled: Boolean)

    /**
     * 推送渲染特性开关（仿 [setRenderQuality] 独立通道，Compose 线程写、渲染线程单消费者读）。
     * 与 [RenderFlags] 数据类（core:engine）保持一致，双端开关同一时刻生效：
     * [RenderFlags.buildingShadows] 由 C++ drawAllTiles 消费（阴影 quad）；
     * [RenderFlags.selectionHighlight] 由 Kotlin 侧 VulkanRenderBackend 消费（drawRect×5），
     * C++ 侧仅存储保持通道对称；
     * [RenderFlags.decorLod] 由 C++ skipDecor 消费（缩放 LOD 门控）。
     *
     * @param buildingShadows 建筑投影阴影开关
     * @param selectionHighlight 普通选中高亮描边开关
     * @param decorLod 装饰层缩放 LOD 开关（关闭时 skipDecor 不含 scale 条件）
     */
    external fun setRenderFlags(buildingShadows: Boolean, selectionHighlight: Boolean, decorLod: Boolean)

    /**
     * 推送地图淡入 alpha（0-1，渲染线程每帧调用）。
     * 只影响 drawAllTiles 的地图层 quad alpha（C++ 侧乘算）；
     * drawRect/drawSprite（预览/高亮）不受影响——与 Canvas 侧独立 Paint 行为一致。
     *
     * @param fadeAlpha 淡入 alpha（0 = 全透明，1 = 完全不透明；C++ 侧 clamp 防御）
     */
    external fun setFadeAlpha(fadeAlpha: Float)

    /** 统一瓦片绘制（地面+装饰+建筑+地砖合并到图集单次 draw call） */
    // JNI external 声明必须与 C++ 函数签名 1:1 平铺（参数分组会破坏 JNI 映射）——
    // LongParameterList 抑制为声明性豁免，参数语义见逐行注释
    @Suppress("LongParameterList")
    external fun drawAllTiles(
        tileData: IntArray,          // 展平瓦片类型数组 [0..N]
        cols: Int, rows: Int,        // 地图网格尺寸
        buildingData: FloatArray?,   // 建筑数据 [x,y,w,h,nameIdx] × count
        buildingCount: Int,          // 建筑数量
        buildingVisible: Boolean,    // 是否显示建筑
        tileSize: Int,
        atlasTexId: Int,
        uvMap: FloatArray,           // UV 映射 [u0,v0,u1,v1] 按 tile 类型索引
        buildingUVMap: FloatArray?,  // 建筑 UV 映射
        floorTileUVMap: FloatArray?, // 地砖 UV 映射 [u0,v0,u1,v1] × 4
        cropData: FloatArray? = null, // 灵田作物数据 [gx, gy, progress01] × N（WP6，可为 null）
        cropUVMap: FloatArray? = null, // 作物 UV 映射 [u0,v0,u1,v1] × 3 阶段（WP6）
        frameAlpha: Float = 0f // 逻辑帧插值因子（批次 3 插值消费链——作物进度帧间平滑权重）
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

    /** 获取最后一次成功读取的 Vulkan 驱动版本号（0 = 未知/未初始化） */
    external fun getVulkanDriverVersion(): Int
}
