package com.xianxia.sect.core.nativebridge

/**
 * NativeBridge — JNI 桥接到 C++ 2D 渲染引擎。
 *
 * 所有 JNI 函数对应 NativeBridge.cpp 中的 extern "C" 实现。
 * 渲染器架构：单 Pipeline + 单纹理图集 + 持久映射 VBO。
 */
@Suppress("TooManyFunctions") // JNI 外部函数契约面：每个 external fun 绑定 C++ 导出符号（ABI 锁定），
// 函数数=桥接协议面，拆分即破坏符号表组织
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
    // 渲染后端选择（GPU GLES 中间层：Vulkan→GPU GLES→CPU Canvas）
    // ============================================================

    /** 渲染后端类型常量（对应 NativeBridge.cpp g_backendType） */
    const val BACKEND_VULKAN = 0
    const val BACKEND_GLES = 1

    /**
     * 设置渲染后端类型（在 [initRenderer]/[prewarmDevice] 前调用）。
     * 0=Vulkan（默认）、1=GPU GLES；Kotlin 侧 NativeSurfaceView 依渲染策略选择。
     */
    external fun setRenderBackend(backend: Int)

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

    /**
     * 初始化渲染器（surface = android.view.Surface 对象）。
     *
     * @param renderScale 渲染缩放（0.5–1.0；1.0 = 直渲全分辨率）。渲染进
     * 离屏降采样目标后 vkCmdBlitImage 上采样到交换链（平板省电），
     * NaN/越界由 C++ 侧消毒
     */
    external fun initRenderer(
        viewportW: Int, viewportH: Int,
        worldW: Int, worldH: Int, tileSize: Int,
        renderScale: Float,
        surface: android.view.Surface
    ): Boolean

    /** 关闭渲染器 */
    external fun shutdownRenderer()

    /**
     * 最近一次 [initRenderer]/[prewarmDevice] 失败阶段错误码（
     * initRenderer 只回 boolean，~25 个 C++ 失败点坍缩成一个 false——本错误码
     * 贯通 C++→JNI，供 RenderFallbackReporter 产出结构化回退事件的 stage 字段）。
     *
     * 编码与 C++ `RenderInitError` 枚举一致：0=NONE/成功、1=NO_WINDOW、
     * 10..24=VK_*（instance/physical device/logical device/queue/surface/
     * swapchain/render pass/offscreen/descriptor pool/pipeline layout/pipeline/
     * shaders/command pool/device memory/fence）、30..35=GLES_*（display/config/
     * window surface/context/shader compile/program link）、99=UNKNOWN。
     */
    external fun getLastInitError(): Int

    /** 调整窗口大小。
     *
     * 本方法仅「记录 pending 尺寸」——真实 resize 由渲染线程
     * 在帧边界经 [consumePendingResize] 消费执行，acquire 与 destroy 从此不可能并发
     * （同一线程顺序执行），swapchain UB 窗口按构造消除。任意线程可调、无锁。
     */
    external fun resizeRenderer(width: Int, height: Int): Boolean

    /**
     * 渲染线程帧边界消费 pending resize（P2-3/D4；RenderLoop 中与
     * consumePendingRenderScale 并列调用）。软件渲染路径不应调用（C++ 侧
     * resize 仅记录尺寸，软件后端无 swapchain 语义）。
     *
     * @return true = 有 pending 被消费且 resize 成功
     */
    external fun consumePendingResize(): Boolean

    /**
     * 动态更新渲染缩放（渲染线程调用；内部重建离屏目标，语义同 resize）。
     * 通道模式仿 [setRenderQuality]：Compose 线程仅写 @Volatile，渲染线程消费后调用本方法。
     *
     * @param renderScale 渲染缩放（0.5–1.0；NaN/越界由 C++ 侧消毒为安全值）
     * @return 实际生效的渲染缩放值
     */
    external fun setRenderScale(renderScale: Float): Float

    // ============================================================
    // 纹理上传
    // ============================================================

    /**
     * 上传图集纹理到 GPU，返回纹理 ID；0 = 后端不可用或缓冲区非法。
     *
     * 像素必须经 **direct** [ByteBuffer] 传入（JNI 走
     * `GetDirectBufferAddress` 零拷贝读取），不得再构造 `ByteArray` 中转——
     * 2048² RGBA 图集经 ByteArray 会瞬时并存 Bitmap(16MB) + ByteArray(16MB)
     * + JNI 侧副本，低端机 OOM 高危。缓冲区按 `ByteOrder.nativeOrder()`
     * 写入，内存字节序为 R,G,B,A（见 [uploadGroundTextureDirect] 同布局）。
     *
     * 调用线程：主线程（C++ `g_renderer` 无锁，与渲染线程互斥由调用侧保证）。
     *
     * @param pixelData RGBA 像素 direct 缓冲区（容量 ≥ width*height*4）
     * @return 纹理 ID；0 = 后端不可用/非 direct 缓冲区/容量不足
     */
    external fun uploadTextureDirect(pixelData: java.nio.ByteBuffer, width: Int, height: Int): Int

    /**
     * 删除纹理（Rhi 契约——GLES 入待删队列由渲染线程持上下文删除；
     * Vulkan 延迟释放在途帧采样结束后销毁）。id=0（白纹理）/无渲染器时无操作。
     * Kotlin 暂无调用方——图集重建路径未来接入时免坑。
     */
    external fun destroyTexture(id: Int)

    /**
     * 上传 RGBA **mip 链**纹理（2.3：RGBA 回退路径真 mip），返回纹理 ID；0 = 失败。
     *
     * @param pixelData level-major 紧凑 direct 缓冲区：首级 = 完整 width×height 图集，
     *   后续各级 50% 等比（尺寸 max(1, base>>level)），RGBA8 每级 4 字节/像素——
     *   bufferOffset 逐级累积恒 4 字节对齐（满足 VUID-VkBufferImageCopy 对齐约束）
     * @param mipCount mip 层级数（含首级；2048² 图集 = 11 级，2048→2）
     * @return 纹理 ID；0 = 后端不可用（非 Vulkan）/非 direct 缓冲区/容量不足 →
     *   调用方（AtlasAsyncPipeline）单级回退 [uploadTextureDirect]（旧行为）
     */
    external fun uploadTextureMipChainDirect(
        pixelData: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        mipCount: Int
    ): Int

    /**
     * 上传宗门地图单一无缝地面纹理（REPEAT 采样，整图铺）。
     *
     * 与 [uploadTextureDirect] 同 staging 上传 + 同 RGBA direct 缓冲区布局，
     * 仅采样器地址模式为 REPEAT（UV 可超 [0,1] 循环平铺）。
     * C++ 侧记录该纹理 ID，地皮轮廓 mesh（地图边缘 v2）以 REPEAT 铺满轮廓内。
     *
     * @param pixelData RGBA 像素 direct 缓冲区（容量 ≥ width*height*4）
     * @return 纹理 ID；0 = 后端不支持/非 direct 缓冲区/容量不足
     */
    external fun uploadGroundTextureDirect(
        pixelData: java.nio.ByteBuffer,
        width: Int,
        height: Int
    ): Int

    /** 底部岩石无缝纹理上传（REPEAT；map_rock_base 128²，地图边缘 v2 材质通道） */
    external fun uploadRockTextureDirect(
        pixelData: java.nio.ByteBuffer,
        width: Int,
        height: Int
    ): Int

    /**
     * 上传 KTX1 封装的 ASTC 4×4 LDR 压缩图集。
     *
     * C++ 侧 KtxLoader 全字段校验（magic/endianness/glType/glFormat/glInternalFormat/
     * faces/mips/尺寸块对齐/dataSize 几何推导），任一字段非法返回 0；
     * 设备无 `textureCompressionASTC_LDR` 特性亦返回 0——调用方回退 RGBA 图集路径
     * （[uploadTextureDirect]），视觉零差异仅 GPU 显存差异（16MB → 4MB）。
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
     * [RenderFlags.buildingShadows] 由 C++ 场景绘制核心消费（阴影 quad）；
     * [RenderFlags.selectionHighlight] 由 C++ 叠加层生成核心消费（新路径），
     * Kotlin 侧保留同源对照实现（drawRect×5）；
     * [RenderFlags.decorLod] 由 C++ skipDecor 消费（缩放 LOD 门控）。
     *
     * @param buildingShadows 建筑投影阴影开关
     * @param selectionHighlight 普通选中高亮描边开关
     * @param decorLod 装饰层缩放 LOD 开关（关闭时 skipDecor 不含 scale 条件）
     */
    external fun setRenderFlags(buildingShadows: Boolean, selectionHighlight: Boolean, decorLod: Boolean)

    /**
     * 推送纹理采样质量开关（B.1 + 自选清晰度联动；渲染线程调用）。
     * 通道模式仿 [setRenderScale]：Compose 线程仅写 @Volatile，渲染线程消费后调用本方法。
     * 影响地图图集/地面采样器：mipmap 三线性过滤 + 各向异性（设备支持时）。
     * 仅 VulkanBackend 支持；GLES/Canvas 后端无操作。
     *
     * @param anisotropyMax 最大各向异性倍率（0 = 关闭；ClarityMode X2/X4/X8 → 2.0/4.0/8.0）
     * @param mipmap 是否启用 mipmap 三线性过滤（ClarityMode mipmap 字段）
     */
    external fun setTextureQuality(anisotropyMax: Float, mipmap: Boolean)

    // ============================================================
    // 程序绘制天空渐变背景（SkyBackground，2026 天幕组件）
    // ============================================================

    /**
     * SkyBackground 配置推送（Compose 线程调用，渲染线程下一帧生效）。
     * 四段渐变（top→second→third→bottom）；只需改颜色/位置/强度即可实现未来
     * 晴天/傍晚/夜晚/阴天切换，不改地图渲染。
     *
     * @param topR/G/B 顶部颜色（蔚蓝 #4B9FD1）
     * @param secondR/G/B 第二停靠色（浅蓝青 #62B0D8，位于 secondT）
     * @param thirdR/G/B 第三停靠色（更浅蓝青 #78C0DF，位于 thirdT）
     * @param botR/G/B 底部颜色（淡蓝白 #A9DCE8）
     * @param secondT 第二停靠位置（0=屏幕顶，1=屏幕底；默认 0.25）
     * @param thirdT 第三停靠位置（0=屏幕顶，1=屏幕底；默认 0.6）
     * @param strength 渐变强度（0=整面平铺为顶色，1=全渐变；C++ 侧 clamp [0,1]）
     */
    // JNI external 声明必须与 C++ 函数签名 1:1 平铺（参数分组会破坏 JNI 映射）——
    // LongParameterList 抑制为声明性豁免（JNI 面 1:1 平铺约定），参数语义见逐行注释
    @Suppress("LongParameterList")
    external fun setSkyConfig(
        topR: Float, topG: Float, topB: Float,
        secondR: Float, secondG: Float, secondB: Float,
        thirdR: Float, thirdG: Float, thirdB: Float,
        botR: Float, botG: Float, botB: Float,
        secondT: Float, thirdT: Float, strength: Float
    )

    /**
     * 绘制屏幕空间天空背景（渲染线程帧首调用：beginFrame 之后、drawFrame 之前）。
     * 背景以屏幕正交投影绘制（相机平移/缩放不影响），始终为最底图层。
     */
    external fun drawSky()

    // ============================================================
    // SceneStore 场景绘制路径（R3.2/B10；B18 起为唯一渲染路径）——
    // 场景数据变化驱动导入 + drawFrame(相机, 覆盖标志) 每帧调用
    //
    // 【JNI 面豁免登记】（沿 R0.2/B06 先例）：8 端口属"场景数据导入 +
    // 每帧绘制"通道，无法沿用既有通道（nativeExecute ActionId 业务事务面 /
    // 镜像导出面均非渲染场景数据形状）；即 B18 已退役的旧唯一渲染入口
    // drawAllTiles（17 参数全量数组每帧跨线）的替身。C++ 侧见 NativeBridge.cpp
    // 同名实现。
    // ============================================================

    /**
     * 地形一次性导入（C++ SceneStore；地图切换/建筑占位变化时重导，
     * 非每帧）。与 gamecore 地形单一权威同值（Kotlin 侧消费的
     * flatTileData 含建筑占位标记）。
     *
     * @param tileData 展平瓦片类型数组（index = row * cols + col）
     */
    external fun sceneSetTerrain(tileData: IntArray, cols: Int, rows: Int, tileSize: Int)

    /**
     * 建筑集更新（变化驱动推送——RenderCommandBus 快照或帧数据引用变化时）。
     *
     * @param buildingData [gx, gy, spriteW, spriteH, nameIdx] × N（与旧路径同形）
     * @param buildingCount 建筑数（C++ 侧与数组容量钳制，同旧路径 effectiveCount）
     */
    external fun sceneUpdateBuildings(buildingData: FloatArray?, buildingCount: Int)

    /**
     * 灵田作物集更新（变化驱动推送；progress 的帧间平滑在 C++ 绘制核心）。
     *
     * @param cropData [gx, gy, progress01] × N（与旧路径同形）
     */
    external fun sceneUpdateCrops(cropData: FloatArray?, cropCount: Int)

    /**
     * 石板道路掩码更新（变化驱动推送；RoadMaskTracker 内容等价早退保证
     * 稳定引用——引用变化即内容变化）。
     *
     * @param roadData 展平 1-based 编码（0=非道路，与旧路径同形）；null = 清空道路层
     */
    external fun sceneUpdateRoads(roadData: IntArray?, cellCount: Int)

    /**
     * 云实例快照更新（渲染线程 CloudLayerAnimator 生成、变化时推送）。
     *
     * @param cloudData [x, y, w, h, spriteIndex, alpha] × N（与旧路径同形）
     */
    external fun sceneUpdateClouds(cloudData: FloatArray?, cloudCount: Int)

    /**
     * 弯曲地皮轮廓复合数据导入（地图边缘系统 v2；GroundBoundaryBridge
     * 一次性预计算的稳定数据，地图尺寸变化时重导）。
     * 布局 = 头部 11 float + 折线 + 掩码 + 地皮 mesh + 底部 mesh
     * （见 GroundBoundaryBridge.Header / gamecore/map/ground_boundary.h）。
     */
    external fun sceneSetGroundBoundary(data: FloatArray?)

    /**
     * 图集纹理 ID 注入（上传完成时；0 = 未就绪——C++ 侧跳过地图层，
     * 崖壁层不受影响，与旧路径 atlasTextureId==0 守卫语义一致）。
     */
    external fun sceneSetAtlasTexture(atlasTexId: Int)

    // ============================================================
    // R3.3/B11 叠加层状态导入端口（选中索引 / 拆除标记 / 预览几何）
    //
    // 【JNI 面豁免登记】（沿 R0.2 探针 / B06 nativeSetDirtyExportProtobuf /
    // 上方场景 8 端口先例）：本组 3 端口属"叠加层状态变化驱动导入"通道，
    // 与场景 8 端口同族（既有通道均非渲染叠加状态形状）。它们替代的是旧路径
    // **每帧逐 rect 跨线**（放置模式最坏 258 次 drawRect 网格线 + 5 占地框 +
    // 5 选中 + 逐建筑拆除矩形）：仅在值变化的那一帧触线，几何生成全在 C++
    // （scene_draw.h::buildOverlayLayers）。既有 [drawRect] 端口保留不删。
    // ============================================================

    /**
     * 选中建筑索引导入（R3.3）。-1 = 无选中；越界时 C++ 侧按建筑数跳过高亮
     * （与旧路径 `index in 0 until buildingCount` 判定同语义）。
     */
    external fun sceneSetSelection(selectedIndex: Int)

    /**
     * 拆除高亮标记导入（R3.3；逐建筑 1 字节，与建筑集同序，取值见
     * [com.xianxia.sect.core.render.DemolishHighlightMark]）。
     *
     * @param markers null = 非拆除模式（整层跳过）
     * @param markerCount 标记数（C++ 侧与数组长度、建筑数取小）
     */
    external fun sceneSetDemolishMarkers(markers: ByteArray?, markerCount: Int)

    /**
     * 预览几何导入（R3.3）：占地框 + 预览精灵同源一帧。
     *
     * 布局 = `[boxX, boxY, boxW, boxH, spriteX, spriteY, spriteW, spriteH,
     * u0, v0, u1, v1, r, g, b, a]`（世界像素 + 图集归一化 UV + 调色，
     * 与 [com.xianxia.sect.core.render.RenderFrame] 的 preview/previewBox
     * 字段族逐项同值）。
     * 各层可见性与可放置/阻挡（绿/红）**不在此承载**——每帧经
     * [drawFrame] 的 overlayFlags 位表达；线宽/不透明度/相机投影/Y 轴压缩
     * 全部由 C++ 侧生成常量计算。
     *
     * @param previewData null 或长度不足 = 清空预览几何
     */
    external fun sceneSetPreview(previewData: FloatArray?)

    // ============================================================
    // R3.8/B13 浮字通道端口（浮动文字池·事件驱动 spawn）
    //
    // 【JNI 面豁免登记】（沿 R0.2 探针 / B06 nativeSetDirtyExportProtobuf /
    // 上方场景 8 端口 / R3.3 叠加层 3 端口先例）：本端口属"**低频游戏事件**
    // 驱动"通道（伤害结算/治疗/词条提示发生时才调用），与渲染循环**正交**——
    // 不构成任何每帧形状的 JNI 传输。它替代的是"浮动文字动态字形渲染"这一
    // 原本需要每帧提交文本/字形数据的通道：Tier1 字形预烘焙进图集、
    // C++ 侧对象池持实例 + 时间驱动动画 ⇒ **浮字活跃期每帧 JNI 次数 = 0**
    // （批次红线），既有的逐 rect/文本提交端口一个都不新增。
    // 端口总数前后对照见完成报告。
    // ============================================================

    /**
     * 浮字生成（R3.8/B13）——**事件驱动**低频通道，绝不由渲染循环调用。
     *
     * 入参扁平序（长度 = [FLOAT_SPAWN_STRIDE]，与 C++
     * `scene::kFloatSpawnStride` 镜像）：
     * ```
     * [0] worldX      世界锚点 X（格）
     * [1] worldY      世界锚点 Y（格）
     * [2] assetIndex  Tier1 资产索引（词条 0..FLOAT_WORD_COUNT-1 /
     *                 单字形 FLOAT_GLYPH_BASE_INDEX..）
     * [3] charCount   连续字形数（词条 = FLOAT_WORD_LENGTH[i]；数字串 = 位数）
     * [4] styleIndex  样式档（FLOAT_STYLE_*）
     * [5] scale       附加缩放（<=0 或非有限 → 拒绝）
     * [6] riseScale   上浮速度倍率（<=0 或非有限 → 拒绝）
     * [7] bounce      暴击弹跳（非 0 = 播放）
     * [8] reserved    保留（须为 0，非 0 直接拒绝）
     * [9] reserved2   保留（须为 0）
     * ```
     *
     * ## 零每帧 JNI 纪律
     * 动画（上浮/淡出/弹跳）全在 C++ `scene::FloatTextPool` 内按帧时间标量
     * 推进——**本端口每帧调用次数 = 0**（只在事件发生时调用）。池满覆盖
     * 最旧（不阻塞调用方）；非法参数静默拒绝（不入池、不崩溃）。
     *
     * ## 与 Canvas 兜底的关系
     * 浮字状态**不经** `RenderFrame` 契约、不进 `SoftwareCanvasBackend`——
     * Canvas 兜底零改动（GPU 路径增强特性）。
     *
     * @param spawnData null 或长度不足 = 忽略（不崩溃）
     */
    external fun sceneSpawnFloatingText(spawnData: FloatArray?)


    /**
     * 每帧绘制（新路径唯一帧入口）：相机标量 + 覆盖标志 + 帧级 alpha
     * （G3 <200B/帧）；场景数据由 C++ SceneStore 持有，Kotlin 不再传
     * SpriteAtlasDef UV 数组（UV 表由 build-atlas.mjs 同源生成进 C++）。
     *
     * @param overlayFlags 覆盖标志位掩码（与 C++ `scene::kOverlayBit*` 逐位同值）：
     *   bit0 = buildingVisible（建筑层可见）/ bit1 = 网格线 / bit2 = 预览精灵 /
     *   bit3 = 占地框 / bit4 = 预览合法性（绿/红）/ bit5 = 选中高亮 /
     *   bit6 = 拆除高亮——bit1–6 为 R3.3 启用，对应叠加层几何由 C++ 侧
     *   按本掩码 + [sceneSetSelection]/[sceneSetDemolishMarkers]/[sceneSetPreview]
     *   导入的状态生成（Kotlin 侧每帧逐 rect 跨线的替代）
     * @param fadeAlpha 地图淡入 alpha（C++ 侧 clamp [0,1]）
     * @param frameAlpha 逻辑帧插值因子（作物进度帧间平滑权重）
     */
    // JNI external 声明必须与 C++ 函数签名 1:1 平铺（参数分组会破坏 JNI 映射）——
    // LongParameterList 抑制为声明性豁免（JNI 面 1:1 平铺约定），参数语义见逐行注释
    @Suppress("LongParameterList")
    external fun drawFrame(
        camX: Float, camY: Float, scale: Float,
        viewportW: Int, viewportH: Int,
        overlayFlags: Int,
        fadeAlpha: Float,
        frameAlpha: Float
    )

    /** 绘制纯色矩形（网格线/放置预览） */
    // LongParameterList 豁免：签名由 NativeBridge.cpp JNI 绑定逐位固定（顶点格式 ABI），
    // 装箱参数载体在渲染热路径每帧分配不可接受
    @Suppress("LongParameterList")
    external fun drawRect(
        x: Float, y: Float, w: Float, h: Float,
        r: Float, g: Float, b: Float, a: Float
    )

    /** 从图集绘制精灵纹理（用于建造/移动预览的半透明建筑） */
    // LongParameterList 豁免：签名由 NativeBridge.cpp JNI 绑定逐位固定（顶点+UV 格式 ABI），
    // 装箱参数载体在渲染热路径每帧分配不可接受
    @Suppress("LongParameterList")
    external fun drawSprite(
        x: Float, y: Float, w: Float, h: Float,
        atlasTexId: Int,
        u0: Float, v0: Float, u1: Float, v1: Float,
        r: Float, g: Float, b: Float, a: Float
    )

    /** 提交帧到 GPU */
    external fun submitFrame()

    /** 获取最后一次成功读取的 Vulkan 驱动版本号（0 = 未知/未初始化） */
    external fun getVulkanDriverVersion(): Int

    /** 获取最后一次成功读取的 Vulkan API 版本（VK_MAKE_VERSION 编码；0 = 未知） */
    external fun getVulkanApiVersion(): Int

    /** 获取最后一次成功读取的 GPU vendorID（0 = 未知/未初始化） */
    external fun getVulkanVendorId(): Int

    /** 获取最后一次成功读取的 GPU 设备名（"" = 未知） */
    external fun getVulkanDeviceName(): String

    /**
     * 精灵容量溢出累计遥测（R0.3；kAnyThread 读——C++ atomic 累计计数）。
     *
     * 返回 LongArray[3]：[0]=累计丢弃精灵数、[1]=累计溢出帧数、[2]=累计降级生效帧数。
     * 渲染线程低频轮询（每 60 渲染帧一次）折叠进 RenderMetrics；
     * 渲染器未初始化时返回全 0。
     */
    external fun nativeGetSpriteOverflowStats(): LongArray

    // ============================================================
    // 双端协议常量（与 C++ scene_draw.h / scene_store.h 镜像；
    // SceneOverlayProtocolGuardTest / SceneUvTablesMirrorGuardTest 逐位锁定）
    // ============================================================

    /**
     * 浮字 spawn 入参步长（R3.8/B13）——与 C++
     * `scene::kFloatSpawnStride` 同值（镜像守卫锁定）。
     * 字段序见 [sceneSpawnFloatingText] KDoc。
     */
    const val FLOAT_SPAWN_STRIDE = 10
}
