#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>
#include <cmath>
#include <atomic>
#include <mutex>
#include <algorithm>
#include <chrono>
#include <map>
#include <vector>
#include <android/log.h>
#include "Rhi.h"
#include "VulkanBackend.h"
#include "GlesBackend.h"
#include "TextureAtlas.h"
#include "SpriteBatcher.h"
#include "KtxLoader.h"
#include "SkyBackground.h"
// SceneStore 场景真相 + 场景绘制核心（重构方案 2026-09-17 R3.1/R3.2）——
// 地图/崖壁/叠加层/浮字各层共用同一构建逻辑（scene_draw.h）；
// B18 前新旧两路的像素等价由 scene_equivalence_test 顶点流对照锁定。
// 占地/UV/渲染常量表由 build-atlas.mjs 同源生成进 C++（scene_uv_tables.h，
// 仓库内生成物——footprint_table.h 的消费位由此接替，生成任务保留）。
#include "scene/scene_store.h"
#include "scene/scene_draw.h"

// ============================================================
// 日志宏
// ============================================================

#define LOG_TAG "NativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ============================================================
// NativeBridge — JNI 入口点
// Kotlin 端包名: com.xianxia.sect.core.nativebridge.NativeBridge
// ============================================================

static Renderer2D* g_renderer = nullptr;

// ============================================================
// 初始化错误码：initRenderer/prewarmDevice 失败时，从即将被
// delete 的后端对象收割 lastInitError() 到本文件级静态（失败路径 g_renderer
// 随即被 delete，getter 不能读活对象），Kotlin 经 getLastInitError 读取，
// 供 RenderFallbackReporter 产出结构化回退事件（from/to/stage/gpu/driver）。
// ============================================================
static std::atomic<int> g_lastInitError{0};   // RenderInitError 枚举值

// ── resize 请求通道状态——主线程仅记录，渲染线程在帧边界消费：
//    vkAcquireNextImageKHR 与 vkDestroySwapchainKHR 从此不可能并发（同一线程顺序
//    执行），UB 窗口按构造消除。与 setCamera/setRenderQuality 同纪律：每帧路径
//    无锁，atomic 保证可见性。 ──
static std::atomic<bool> g_resizeRequested{false};
static std::atomic<int> g_pendingResizeW{0};
static std::atomic<int> g_pendingResizeH{0};

/** Harvest the last init error from a renderer about to be discarded (or inline value). */
static void harvestInitError(RenderInitError err) {
    g_lastInitError.store(static_cast<int>(err), std::memory_order_release);
}
/**
 * 渲染器生命周期互斥：prewarmDevice 现于 GameActivity
 * 进入即触发（早于 boot 数据阶段），与 surface 到来后的 initRenderer 存在真实并发
 * 窗口——两者都会 `delete g_renderer`（initRenderer 在 Phase1 未完成时走完整初始化
 * 回退分支，会 delete 正在 initDevice 的对象 → UB/崩溃）。本锁序列化
 * prewarmDevice / initRenderer / shutdownRenderer / resizeRenderer 的入口。
 * 每帧绘制/上传路径不持本锁（Kotlin 侧生命周期时序已保证渲染线程先停再 shutdown）。
 */
static std::mutex g_rendererLifecycleMutex;
static TextureAtlas* g_atlas = nullptr;
static float g_projMatrix[16]{};

// 程序绘制天空渐变背景模块（安全：Compose 线程写 setXxx，RenderThread 读 getScreenVertices）
static SkyBackground g_sky;

// 渲染后端类型（VULKAN=0 默认 / GLES=1）——NativeSurfaceView 在 initRenderer 前
// 经 nativeSetRenderBackend 设置，决定 initRenderer 创建哪种 Rhi 实现。
// 降级链：Vulkan → GPU GLES → CPU Canvas。
static int g_backendType = 0;

// 宗门地图单一无缝地面纹理（REPEAT 采样，整图铺）。0 = 未上传（回退逐格地面）
static uint32_t g_groundTexId = 0;

// ============================================================
// 浮空岛崖壁独立纹理表（地图边缘系统）
//
// 崖壁素材单张最大 1180×3552，**超出 4096² 图集容量**，故不走图集：
// 每个变体一张独立纹理，drawIslandCliffs 按布局条目的 textureIdx 取 ID。
// 纹理下标序由 Kotlin IslandCliffTextureSet 定义（LEFT_1..3 / BOTTOM_1..2 /
// CORNER_BL / CORNER_BR = 0..6），布局 UV 由 Kotlin 侧预计算后逐条目传入。
//
// 上传顺序 = 下标序（setIslandCliffTextures 一次性接收整表）；
// 单张上传失败用 0 占位，绘制端跳过引用它的条目（部分降级，非整层消失）。
// ============================================================
static constexpr int32_t kMaxCliffTextures = 16;
static uint32_t g_cliffTexIds[kMaxCliffTextures] = {};
static int32_t g_cliffTexCount = 0;

/**
 * 应用崖壁纹理 ID 表（主线程；渲染线程随后只读）。
 *
 * @param ids 长度 = 纹理数（下标序与 Kotlin IslandCliffTextureSet 一致）
 * @param count 纹理数（≤ kMaxCliffTextures；超出截断）
 */
static void applyCliffTextures(const uint32_t* ids, int32_t count) {
    if (count < 0) count = 0;
    if (count > kMaxCliffTextures) count = kMaxCliffTextures;
    for (int32_t i = 0; i < count; i++) g_cliffTexIds[i] = ids[i];
    for (int32_t i = count; i < kMaxCliffTextures; i++) g_cliffTexIds[i] = 0;
    g_cliffTexCount = count;
}

// 视口世界坐标范围（由 setCamera/drawFrame 更新，用于瓦片/崖壁层可见性检测）
static float g_viewLeft   = 0.0f;
static float g_viewTop    = 0.0f;
static float g_viewRight  = 0.0f;
static float g_viewBottom = 0.0f;

// 当前缩放值（由 setCamera 更新，用于 GAP_EPSILON 计算）
static float g_scale = 1.0f;

// 世界像素尺寸（由 initRenderer 设置）
static int g_worldPixelsW = 0;
static int g_worldPixelsH = 0;

// ============================================================
// 渲染质量热控状态（由 setRenderQuality 更新，渲染线程单消费者读）
// std::atomic 保证 Compose 线程写 / 渲染线程读的可见性（仿 setCamera 独立通道）。
// 装饰层跳过条件与 Canvas 侧 SoftwareCanvasBackend 对齐：
//   decorationsDisabled || qualityFactor < scene::kDecorQualityThreshold
//   （阈值由生成 scene_uv_tables.h 提供——与 SpriteAtlasDef 同源）
// ============================================================

/** 热控质量因子（0-1，1 = 全质量） */
static std::atomic<float> g_qualityFactor{1.0f};

/** 装饰层关闭标志（热控/省电） */
static std::atomic<bool> g_decorationsDisabled{false};

// ============================================================
// 渲染特性开关（由 setRenderFlags 更新，渲染线程单消费者读）
// 与 Kotlin 侧 RenderFlags 数据类（core:engine）保持一致：
//   buildingShadows  ↔ RenderFlags.buildingShadows
//   selectionHighlight ↔ RenderFlags.selectionHighlight
// 阴影常量与 BuildingRenderGeometry.kt 同值——修改任一侧必须同步另一侧
// ============================================================

/** 建筑投影阴影开关 */
static std::atomic<bool> g_buildingShadows{true};

/** 选中高亮开关（当前由 Kotlin 侧 VulkanRenderBackend 消费 host.renderConfig，
 *  此处仅存储以保持双端通道对称，未来 C++ 侧绘制高亮时直接消费） */
static std::atomic<bool> g_selectionHighlight{true};

/** 装饰层缩放 LOD 开关（与 RenderFlags.decorLod 对应——关闭时 skipDecor
 *  不含 g_scale 条件，行为 = 特性未实现前现状，用于低端设备兜底） */
static std::atomic<bool> g_decorLod{true};

// SHADOW_OFFSET_TILES / SHADOW_ALPHA 由生成 scene_uv_tables.h 提供
//（与 SpriteAtlasDef 同源；绘制核心 scene_draw.h 消费）

// ============================================================
// 地图淡入过渡状态（每帧经 drawFrame 的 fadeAlpha 参数携带，渲染线程写读）
// 与 Kotlin 侧 FadeTransition（core:engine）同一数学来源：
//   触发：RenderThread 启动时 NativeSurfaceView.fadeIn()（首次/重入/降级统一）
//   计算：渲染线程每帧 alphaAt(elapsedNs, durationNs)（EaseOutCubic，纯时钟驱动）
//   应用：地图层/崖壁层全部 add 的 alpha 乘算；叠加层（预览/高亮）不受影响
// ============================================================

/** 地图淡入 alpha（0-1，1 = 完全不透明） */
static std::atomic<float> g_fadeAlpha{1.0f};

// 作物插值状态收敛进 scene::CropSmoothingState（g_cropSmooth，SceneStore 段）——
// 地图层绘制消费该平滑状态（绘制核心的配套单例）

// ── 帧批量构建器（跨帧复用）──
// 渲染线程单消费者：drawFrame/drawIslandCliffs 仅由 RenderThread
// 经 JNI 调用，无并发；grow 一次后堆缓冲跨帧复用，根除每帧 5 次 new/memcpy/
// delete ×2 的分配链。清理策略与 g_cropSmooth 同纪律（文件级状态须在
// shutdownRenderer 说明）：batcher 无 native 句柄，无需清理，仅容量驻留
// ≤2×16384×32B=1MB 堆。
static SpriteBatcher g_mapBatcher;
static SpriteBatcher g_edgeBatcher;

// ============================================================
// SceneStore 场景真相（重构方案 2026-09-17 R3.1/R3.2）
//
// 场景数据（地形/道路/建筑/作物/云/崖壁）下沉 native 侧持有：
// Kotlin 仅在数据变化时经 sceneSet*/sceneUpdate* 端口导入（变化驱动，
// 非每帧），每帧绘制只剩 drawFrame(camera, overlayFlags)（G3 <200B/帧）。
// 线程契约：与每帧绘制同（渲染线程单消费者，导入与消费同线程顺序执行）。
// 纪元纪律：shutdownRenderer 复位（g_scene.reset/g_cropSmooth.clear/
// 图集纹理 ID 清零），Kotlin 侧新 surface 的新后端实例首帧重推全部场景。
// 场景绘制路径为唯一渲染入口（B18 后无第二数据面）。
// ============================================================
static scene::SceneStore g_scene;
static scene::CropSmoothingState g_cropSmooth;
/** 场景绘制路径的图集纹理 ID（sceneSetAtlasTexture 注入；0 = 未上传，
 *  与 Kotlin 侧 host.atlasTextureId==0 跳过地图层的守卫语义一致） */
static uint32_t g_sceneAtlasTexId = 0;
/** R3.3 叠加层专用构建器（跨帧复用容量）——与地图/崖壁批各用独立 static，
 *  互不串批；无 native 句柄，shutdownRenderer 无需清理（同 g_mapBatcher 纪律） */
static SpriteBatcher g_overlayBatcher;

// ============================================================
// 浮字通道（重构方案 2026-09-17 R3.8/B13）
//
// 浮动文字（伤害/治疗/词条提示）走**预烘焙 sprite + C++ 对象池**路线：
//   - Tier1 字形资产在构建期入图集（scene_uv_tables.h kFloatUv 单一权威）；
//   - 实例池 g_floatPool 全 C++ 持有，唯一写入入口是**低频事件驱动**的
//     sceneSpawnFloatingText（JNI 端口），动画由帧时间累加器在 C++ 内推进；
//   - 每帧绘制只在 drawFrame 尾部追加一次浮字批构建（**零每帧 JNI**）；
//   - 空池 = 零 draw call（buildFloatTextBatch 直接返回）；
//   - shutdownRenderer 复位池纪元（g_floatPool.reset）。
// 图层序：浮字在**最上层**（地图层 → 叠加层 → 浮字）。
// 线程契约：与 g_scene 同（渲染线程单消费者；spawn 与 drawFrame 同线程）。
// ============================================================
static scene::FloatTextPool g_floatPool;
/** 浮字批专用构建器（跨帧复用容量，与地图/崖壁/叠加层各用独立 static） */
static SpriteBatcher g_floatBatcher;
/**
 * 浮字动画帧时间累加器（秒）。
 *
 * **为什么不在 drawFrame 追加时间标量**：既有 `drawFrame` 8 参数签名是
 * R3.2 建立的 ABI 契约（红线"既有 JNI 签名零变更"）——为浮字动画新增
 * 标量属 ABI 变更，须单独豁免 + 双端同步 + 既有等价守卫逐条复跑。
 * 本批选择**零 ABI 变更**路线：C++ 侧按固定标称帧步长（1/60s）自累加，
 * 完全确定性（GTest 可重现同一时刻采样）、不查系统时钟、不跨线。
 * 长时间运行与真实墙钟的漂移对浮字（1.1s 寿命）无观感影响；
 * 若未来需要墙钟精确对齐，可另开端口推送（登记豁免）而不动 drawFrame。
 */
static float g_floatNowSeconds = 0.0f;
/** 固定标称帧步长（秒）= 1/60；改此值须同步浮字动画守卫的期望值 */
static constexpr float kFloatFrameStepSeconds = 1.0f / 60.0f;
/** 浮字时间标量上界（与 FloatTextPool::kFloatMaxTimeSeconds 同语义，防长期累加溢出） */
static constexpr float kFloatTimeWrapSeconds = 1.0e6f;

/** R3.5 远景观看容量路径开关（Kotlin `FarViewGroundPolicy` 判定后经
 *  nativeSetFarViewGroundQuad 推送：设备白名单 + 缩放到位 + 图集就绪 + 用户旗标
 *  四重门的**合取结果**）。默认 false = 逐格地面（与 R3.5 前现状逐位一致），
 *  未验证设备恒不启用（黑名单/白名单为空 ⇒ 恒 false）。仅在两路地面绘制分支
 *  参与判定，不改变任何其他绘制形状。 */
static std::atomic<bool> g_farViewGroundQuad{false};

// 批量构建器容量溢出限频日志（此前极小缩放下静默丢弃无日志）
static int64_t s_lastOverflowLogNs = 0;
static void logBatcherOverflowOncePerSecond(const char* layer, int dropped) {
    const int64_t nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    if (nowNs - s_lastOverflowLogNs < 1'000'000'000LL) return;
    s_lastOverflowLogNs = nowNs;
    LOGW("batcher overflow: layer=%s dropped=%d sprites this frame (capacity capped)", layer, dropped);
}

// ── 精灵容量溢出遥测 + 有序降级（R0.3）──────────────────────────
// 溢出 = 任一批量构建器帧内触发容量丢弃（SpriteBatcher::droppedSprites）。
// 降级策略（先跳装饰层 → 仍溢出再截断）：上一帧溢出 → 下一帧跳过装饰层
// （草/石/树/云，与热控/LOD 在同一 skipDecor 判定汇合）；降级后连续
// kOverflowRecoveryCleanFrames 个渲染帧无溢出才解除（防单帧抖动振荡）。
// 累计计数经 nativeGetSpriteOverflowStats 暴露 Kotlin，折叠进 RenderMetrics。
static std::atomic<bool> s_overflowDegradeActive{false};
static std::atomic<uint64_t> s_overflowDroppedTotal{0};   // 累计丢弃精灵数
static std::atomic<uint64_t> s_overflowFramesTotal{0};    // 累计溢出帧数
static std::atomic<uint64_t> s_degradeFramesTotal{0};     // 累计降级生效帧数
static int64_t s_cleanFramesSinceOverflow = 0;            // 降级后连续无溢出帧数（渲染线程单写者）

/** 降级解除所需的连续无溢出渲染帧数（30 帧 ≈ 0.5s@60fps） */
static constexpr int64_t kOverflowRecoveryCleanFrames = 30;

/** 溢出登记（批量构建器帧终值 → 累计计数 + 下帧降级标志；渲染线程调用） */
static void noteBatcherOverflow(int dropped) {
    if (dropped <= 0) return;
    s_overflowDroppedTotal.fetch_add(static_cast<uint64_t>(dropped), std::memory_order_relaxed);
    s_overflowFramesTotal.fetch_add(1, std::memory_order_relaxed);
    s_overflowDegradeActive.store(true, std::memory_order_relaxed);
    s_cleanFramesSinceOverflow = 0;
}

// 云层步长常量与可见性检测收敛进 scene_draw.h（kCloudStride/sceneRectVisible）——
// 单份绘制核心的组成部分，桥侧不再重复持有

// 瓷砖类型常量（TILE_GROUND / TILE_BUILDING）
// 由生成 TextureAtlas.h 提供（与 SpriteAtlasDef.TileType.index 同源）；
// SceneStore 路径的常量面（UV 表/占地表/瓦片分类）由 scene_uv_tables.h 提供

// ============================================================
// 纹理图集
// ============================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_initAtlas(
    JNIEnv* /*env*/, jobject /*thiz*/) {

    if (!g_atlas) {
        g_atlas = new TextureAtlas();
        g_atlas->defineAtlas(ATLAS_W, ATLAS_H,
                             MAP_SPRITES, MAP_SPRITE_COUNT);
    }
    return JNI_TRUE;
}

// ============================================================
// 渲染器生命周期 — 两阶段初始化
// ============================================================

/** Phase 1: 预加载设备 + 着色器（在加载界面调用，无 Surface 依赖） */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_prewarmDevice(
    JNIEnv* env, jobject /*thiz*/,
    jstring cacheDir, jint worldW, jint worldH, jint tileSize) {

    std::lock_guard<std::mutex> lifecycleLock(g_rendererLifecycleMutex);

    if (g_renderer) {
        delete g_renderer;  // 析构函数自动调 shutdown()，句柄置空后幂等
        g_renderer = nullptr;
    }

    if (g_backendType != 0) {
        // GPU GLES 后端：无两阶段 prewarm（在 initRenderer 内一次性创建 EGL 链 +
        // 管线），此处直接成功返回，交由 surface 就绪后的完整 init。
        return JNI_TRUE;
    }

    g_lastInitError.store(0, std::memory_order_release);  // 新尝试：清上次错误码
    const char* dir = cacheDir ? env->GetStringUTFChars(cacheDir, nullptr) : nullptr;

    auto* vb = new VulkanBackend();
    g_renderer = vb;
    bool ok = vb->initDevice(dir, worldW, worldH, tileSize);

    if (dir) env->ReleaseStringUTFChars(cacheDir, dir);

    if (!ok) {
        harvestInitError(vb->lastInitError());   // delete 前收割错误码
        LOGE("prewarmDevice failed (stage=%d) — will fall back to full init at surface time",
             g_lastInitError.load());
        delete g_renderer;
        g_renderer = nullptr;
        return JNI_FALSE;
    }

    LOGI("Vulkan device prewarmed successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_getVulkanDriverVersion(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(VulkanBackend::s_driverVersion);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_getVulkanApiVersion(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(VulkanBackend::s_apiVersion);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_getVulkanVendorId(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(VulkanBackend::s_vendorId);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_getVulkanDeviceName(
    JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(VulkanBackend::s_deviceName);
}

/** 设置渲染后端类型（在 initRenderer/prewarmDevice 前调用；0=Vulkan 默认，1=GLES）。 */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_setRenderBackend(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint backend) {
    g_backendType = backend;
}

/**
 * 精灵容量溢出累计遥测（R0.3；kAnyThread 读——atomic 累计计数）。
 *
 * 返回 LongArray[3]：[0]=累计丢弃精灵数、[1]=累计溢出帧数、[2]=累计降级生效帧数。
 * Kotlin 渲染线程低频轮询折叠进 RenderMetrics（崩溃上报随快照携带）。
 */
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_nativeGetSpriteOverflowStats(
    JNIEnv* env, jobject /*thiz*/) {
    const jlong stats[3] = {
        static_cast<jlong>(s_overflowDroppedTotal.load(std::memory_order_relaxed)),
        static_cast<jlong>(s_overflowFramesTotal.load(std::memory_order_relaxed)),
        static_cast<jlong>(s_degradeFramesTotal.load(std::memory_order_relaxed))
    };
    jlongArray arr = env->NewLongArray(3);
    if (arr == nullptr) return nullptr;
    env->SetLongArrayRegion(arr, 0, 3, stats);
    return arr;
}

/** Phase 2: 初始化 Surface（在 SurfaceView 就绪后调用）。renderScale 为渲染缩放
 *  （0.5–1.0，1.0 = 直渲全分辨率），NaN/越界由 VulkanBackend 消毒。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_initRenderer(
    JNIEnv* env, jobject /*thiz*/,
    jint viewportW, jint viewportH,
    jint worldW, jint worldH, jint tileSize,
    jfloat renderScale,
    jobject surface) {

    // 持生命周期锁：本函数可能 delete 正在 prewarm 的 g_renderer（prewarm 未完成
    //   时走完整初始化回退）——prewarmDevice 已提前到 GameActivity 进入即触发，
    //   与本函数存在真实并发窗口（见 g_rendererLifecycleMutex 注）。
    std::lock_guard<std::mutex> lifecycleLock(g_rendererLifecycleMutex);

    g_lastInitError.store(0, std::memory_order_release);  // 新尝试：清上次错误码
    // 清跨 surface 代际残留的 resize 请求（防旧尺寸误 resize 新 surface——
    // 同 g_cropSmooth 的清理理由）
    g_resizeRequested.store(false, std::memory_order_relaxed);

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        harvestInitError(RenderInitError::NO_WINDOW);
        LOGE("initRenderer: ANativeWindow_fromSurface failed");
        return JNI_FALSE;
    }

    g_worldPixelsW = worldW;
    g_worldPixelsH = worldH;

    // GPU GLES 中间层：创建 GlesBackend 并完整初始化（无两阶段 prewarm）。
    // 选择链在 Kotlin 侧 NativeSurfaceView/VulkanPolicy——本函数按 backendType 落地。
    if (g_backendType != 0) {
        if (g_renderer) { delete g_renderer; g_renderer = nullptr; }
        auto* gles = new GlesBackend();
        g_renderer = gles;
        RenderConfig cfg{};
        cfg.viewportW = viewportW;
        cfg.viewportH = viewportH;
        cfg.worldWidth = worldW;
        cfg.worldHeight = worldH;
        cfg.tileSize = tileSize;
        cfg.renderScale = renderScale;
        bool ok = gles->init(cfg, window);
        // 桥侧 fromSurface 引用移交后即释放：后端已自持有引用
        // （GlesBackend initEgl acquire / VulkanBackend initSurface acquire），
        // 引用所有权精确平衡——每纪元净增 0，不再泄漏
        ANativeWindow_release(window);
        if (!ok) harvestInitError(gles->lastInitError());  // delete 前收割
        return ok ? JNI_TRUE : JNI_FALSE;
    }

    if (g_renderer) {
        auto* vb = static_cast<VulkanBackend*>(g_renderer);
        if (vb->isDeviceReady()) {
            // Phase 1 已完成，只需初始化 Surface。先记录 renderScale（swapchain
            // 未创建时 setRenderScale 仅记录），initSurface 的 createOffscreenTargets
            // 按此创建离屏降采样目标。
            vb->setRenderScale(renderScale);
            bool ok = vb->initSurface(window, viewportW, viewportH);
            ANativeWindow_release(window);  // 桥侧引用移交后即释放（同上）
            if (!ok) harvestInitError(vb->lastInitError());  // delete/重建前收割
            return ok ? JNI_TRUE : JNI_FALSE;
        }
    }

    // 回退：完整初始化（prewarmDevice 未调用或失败）
    if (g_renderer) {
        delete g_renderer;
    }
    g_renderer = new VulkanBackend();
    RenderConfig config{};
    config.viewportW = viewportW;
    config.viewportH = viewportH;
    config.worldWidth = worldW;
    config.worldHeight = worldH;
    config.tileSize = tileSize;
    config.renderScale = renderScale;

    bool ok = g_renderer->init(config, window);
    ANativeWindow_release(window);  // 桥侧引用移交后即释放（同上）
    if (!ok) harvestInitError(g_renderer->lastInitError());  // delete 前收割
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** 最近一次 initRenderer/prewarmDevice 失败阶段错误码（RenderInitError；0 = 无失败） */
extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_getLastInitError(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jint>(g_lastInitError.load(std::memory_order_acquire));
}

/** 动态更新渲染缩放（渲染线程调用；内部重建离屏目标，语义同 resize）。
 *  mirror setRenderQuality 通道：Compose 线程仅写 @Volatile，渲染线程消费后调用本方法。 */
extern "C" JNIEXPORT jfloat JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_setRenderScale(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat renderScale) {

    if (!g_renderer) return 1.0f;
    // 渲染缩放（离屏降采样）为 Vulkan 专属；GLES 后端不支持，恒 1.0（直渲全分辨率）
    if (auto* vk = dynamic_cast<VulkanBackend*>(g_renderer)) {
        return vk->setRenderScale(renderScale);
    }
    return 1.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_shutdownRenderer(
    JNIEnv* /*env*/, jobject /*thiz*/) {

    // ★ 持生命周期锁：与 prewarmDevice/initRenderer 互斥（三者都会 delete g_renderer）
    std::lock_guard<std::mutex> lifecycleLock(g_rendererLifecycleMutex);

    if (g_renderer) {
        delete g_renderer;  // 析构函数自动调 shutdown()，句柄置空后幂等安全
        g_renderer = nullptr;
    }
    if (g_atlas) {
        delete g_atlas;
        g_atlas = nullptr;
    }

    // 清理视口与投影残留状态，防止意外残留的渲染线程通过全局变量访问已释放内存
    memset(g_projMatrix, 0, sizeof(g_projMatrix));
    g_viewLeft = g_viewTop = g_viewRight = g_viewBottom = 0.0f;
    g_worldPixelsW = g_worldPixelsH = 0;
    g_scale = 1.0f;  // 双指缩放随纪元复位，防旧会话缩放残留污染新 surface
    g_groundTexId = 0;  // 地面纹理随渲染器释放重置
    // 重置热控状态为默认全质量——新 surface 初始化后由 NativeSurfaceView
    // pushRenderQuality 重放当前值，此处仅防旧 surface 残留状态泄漏
    g_qualityFactor.store(1.0f);
    g_decorationsDisabled.store(false);
    // 渲染特性开关同理：新 surface 初始化后由 VulkanRenderBackend 构造时重放
    g_buildingShadows.store(true);
    g_selectionHighlight.store(true);
    g_decorLod.store(true);
    // 淡入同理：新 RenderThread 启动时 fadeIn() 重置 startNs 并推送新 alpha
    g_fadeAlpha.store(1.0f);
    // 溢出降级标志随 surface 代际复位（累计遥测计数保留——进程级诊断数据）
    s_overflowDegradeActive.store(false, std::memory_order_relaxed);
    s_cleanFramesSinceOverflow = 0;
    // 作物插值状态清空：
    // 旧 surface 的进度基准不得污染新 surface 的播种/收获插值
    g_cropSmooth.clear();
    // SceneStore 场景真相随纪元复位：旧 surface 的场景状态不得残留到
    // 新 surface（新后端实例首帧经 sceneSet*/sceneUpdate* 重推全部场景，
    // 与 g_groundTexId/g_fadeAlpha 等清理同纪律）
    g_scene.reset();
    // R3.5 远景容量开关随纪元复位（新 surface 首帧由 Kotlin 侧重新判定推送）
    g_farViewGroundQuad.store(false, std::memory_order_relaxed);
    g_sceneAtlasTexId = 0;
    // 浮字池随纪元复位（R3.8/B13）：旧 surface 的浮字实例与动画时刻不得残留
    // 到新 surface（池实例/纪元序号/遥测计数/时间累加器一并清零）
    g_floatPool.reset();
    g_floatNowSeconds = 0.0f;
    // resize 请求通道清残留：shutdownRenderer 后到达旧表面的 pending
    // 请求不得作用于新 surface（同 g_cropSmooth 的代际残留纪律）
    g_resizeRequested.store(false, std::memory_order_relaxed);
    // SkyBackground：surface 重建/降级链切换后恢复默认天空配置，防代际残留；
    // 新 surface 初始化后由 NativeSurfaceView 重放当前 skyConfig（仿 pushRenderQuality）
    g_sky.resetToDefault();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_resizeRenderer(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint width, jint height) {
    if (width <= 0 || height <= 0) return JNI_FALSE;
    g_pendingResizeW.store(width, std::memory_order_relaxed);
    g_pendingResizeH.store(height, std::memory_order_relaxed);
    g_resizeRequested.store(true, std::memory_order_release);
    return JNI_TRUE;
}

/** 渲染线程帧边界消费 pending resize（与每帧绘制同线程契约——渲染线程已停才会
 *  shutdownRenderer，故读 g_renderer 无需生命周期锁；同 stopRenderThread 纪律）。
 *  @return 是否有 pending 被消费且 resize 成功 */
bool consumePendingResizeInternal() {
    if (!g_resizeRequested.exchange(false, std::memory_order_acquire)) return false;
    if (!g_renderer) return false;
    return g_renderer->resize(g_pendingResizeW.load(std::memory_order_relaxed),
                              g_pendingResizeH.load(std::memory_order_relaxed));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_consumePendingResize(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return consumePendingResizeInternal() ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// 纹理上传（接收 Kotlin 端的 RGBA 像素 direct 缓冲区）
//
// 像素经 direct ByteBuffer 传入（GetDirectBufferAddress 零拷贝）。
//   jbyteArray 通道被禁用（GetByteArrayElements 在 2048² 图集上会再产生一份
//   16MB 副本，与 Bitmap/ByteArray 三份并存 → 低端机 OOM）。
//   缓冲区内存字节序为 R,G,B,A（Kotlin 按 ByteOrder.nativeOrder() 写入）。
// ============================================================

/**
 * 校验 direct 缓冲区容量（防 Kotlin 侧尺寸算错导致 C++ 越界读）。
 *
 * @return 有效像素指针；nullptr = 非 direct 缓冲区或容量不足
 */
namespace {
const void* lockDirectPixels(JNIEnv* env, jobject buffer, jint width, jint height,
                             const char* caller) {
    if (!buffer) {
        LOGE("%s: pixel buffer is null", caller);
        return nullptr;
    }
    void* pixels = env->GetDirectBufferAddress(buffer);
    if (!pixels) {
        LOGE("%s: 非 direct ByteBuffer（P0-3：像素必须经 direct 缓冲区传入）", caller);
        return nullptr;
    }
    if (width <= 0 || height <= 0) {
        LOGE("%s: invalid size %dx%d", caller, static_cast<int>(width), static_cast<int>(height));
        return nullptr;
    }
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    const jlong needed = static_cast<jlong>(width) * static_cast<jlong>(height) * 4;
    if (capacity < needed) {
        LOGE("%s: buffer 容量 %lld < 需要 %lld (%dx%d RGBA)", caller,
             static_cast<long long>(capacity), static_cast<long long>(needed),
             static_cast<int>(width), static_cast<int>(height));
        return nullptr;
    }
    return pixels;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_uploadTextureDirect(
    JNIEnv* env, jobject /*thiz*/,
    jobject pixelData, jint width, jint height) {

    if (!g_renderer) return 0;
    const void* pixels = lockDirectPixels(env, pixelData, width, height, "uploadTextureDirect");
    if (!pixels) return 0;
    return static_cast<jint>(g_renderer->uploadTexture(pixels, width, height));
}

/** 纹理删除出口（Kotlin 暂无调用方，契约完整即可——图集重建路径
 *  未来接入时免坑。GLES 入待删队列由渲染线程删除；Vulkan 延迟释放在途帧后） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_destroyTexture(
    JNIEnv* /*env*/, jobject /*thiz*/, jint id) {
    if (!g_renderer || id <= 0) return;
    g_renderer->destroyTexture(static_cast<uint32_t>(id));
}

// ============================================================
// 2.3：RGBA mip 链上传（level-major 紧凑 direct 缓冲区 → Vulkan 逐级拷贝）
// 失败返回 0（Kotlin 侧 AtlasAsyncPipeline 回退单级 uploadTextureDirect 路径）。
// dynamic_cast 防御：g_renderer 非 VulkanBackend（GLES 等）时同样返回 0 走回退。
// ============================================================

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_uploadTextureMipChainDirect(
    JNIEnv* env, jobject /*thiz*/,
    jobject pixelData, jint width, jint height, jint mipCount) {

    if (!g_renderer) return 0;
    // 首级容量校验（lockDirectPixels 按完整图集 w*h*4 校验；逐级总量由
    // VulkanBackend 按 max(1, base>>k) 几何推导校验，与 Kotlin 编码布局一致）
    const void* pixels = lockDirectPixels(env, pixelData, width, height, "uploadTextureMipChainDirect");
    if (!pixels) return 0;
    if (mipCount < 1) {
        LOGE("uploadTextureMipChainDirect: mipCount 非法 %d", static_cast<int>(mipCount));
        return 0;
    }
    uint32_t id = 0;
    if (auto* vk = dynamic_cast<VulkanBackend*>(g_renderer)) {
        id = vk->uploadMipChainTexture(pixels, width, height, static_cast<int>(mipCount));
    } else {
        LOGE("uploadTextureMipChainDirect: 后端不支持（非 VulkanBackend），走单级回退");
    }
    return static_cast<jint>(id);
}

// ============================================================
// 宗门地图单一无缝地面纹理上传（REPEAT 采样，整图铺）
// ============================================================

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_uploadGroundTextureDirect(
    JNIEnv* env, jobject /*thiz*/,
    jobject pixelData, jint width, jint height) {

    if (!g_renderer) return 0;
    const void* pixels = lockDirectPixels(env, pixelData, width, height, "uploadGroundTextureDirect");
    if (!pixels) return 0;

    uint32_t id = 0;
    if (auto* vk = dynamic_cast<VulkanBackend*>(g_renderer)) {
        id = vk->uploadRepeatTexture(pixels, width, height);
    } else {
        LOGE("uploadGroundTextureDirect: 后端不支持（非 VulkanBackend）");
    }
    if (id != 0) g_groundTexId = id;
    return static_cast<jint>(id);
}

// ============================================================
// 压缩图集上传（KTX1 容器 → KtxLoader 全字段校验 → Vulkan ASTC 上传）
// 失败返回 0（Kotlin 侧回退 RGBA 图集路径，视觉零差异仅内存差异）。
// dynamic_cast 防御：g_renderer 非 VulkanBackend（未来其他后端）时同样回退。
// ============================================================

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_uploadCompressedAtlas(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray ktxData) {

    if (!g_renderer || !ktxData) return 0;

    const jsize len = env->GetArrayLength(ktxData);
    if (len <= 0) return 0;

    jbyte* bytes = env->GetByteArrayElements(ktxData, nullptr);
    if (!bytes) return 0;

    KtxInfo info;
    uint32_t id = 0;
    if (loadKtx1(reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(len), info)) {
        if (auto* vk = dynamic_cast<VulkanBackend*>(g_renderer)) {
            id = vk->uploadCompressedTexture(
                info.data, info.dataSize,
                static_cast<int>(info.width), static_cast<int>(info.height),
                static_cast<int>(info.mipCount));
        } else {
            LOGE("uploadCompressedAtlas: 后端不支持压缩上传（非 VulkanBackend），回退 RGBA");
        }
    } else {
        LOGW("uploadCompressedAtlas: KTX 校验失败，回退 RGBA 图集");
    }

    env->ReleaseByteArrayElements(ktxData, bytes, JNI_ABORT);
    return static_cast<jint>(id);
}

/**
 * 运行时纹理采样质量开关（B.1 + 自选清晰度联动；渲染线程调用）。
 * 通道模式仿 [setRenderScale]：Compose 线程仅写 @Volatile，渲染线程消费后调用本方法。
 * 仅 VulkanBackend 支持；GLES/Canvas 后端无操作（清度纹理过滤仅对地图 Vulkan 渲染生效）。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_setTextureQuality(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat anisotropyMax, jboolean mipmap) {

    if (!g_renderer) return;
    if (auto* vk = dynamic_cast<VulkanBackend*>(g_renderer)) {
        vk->setTextureQuality(anisotropyMax, mipmap == JNI_TRUE);
    }
    // 非 VulkanBackend 后端无采样质量通道，静默忽略（与 setRenderScale 非 Vulkan 恒 1.0 同理）
}

// ============================================================
// 帧渲染
// ============================================================

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_beginFrame(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_renderer) g_renderer->beginFrame();
}

/**
 * 相机全局量更新（setCamera 与 drawFrame 共用——消毒/投影/视野边界单实现）。
 * 渲染线程调用（每帧路径）。
 */
static void updateCameraGlobals(jfloat camX, jfloat camY, jfloat scale,
                                jint vpW, jint vpH) {
    // 防御：scale=0/NaN → 除零产生 NaN 投影矩阵 → 全屏黑无法恢复。
    // 统一 sanitize 后所有下游（投影矩阵/视口范围/LOD 门控）使用同一安全值
    // （与 Canvas 侧 SoftwareCanvasBackend.sanitizeScale 语义对齐：非法 → 1.0）
    float safeScale = scale;
    if (!(safeScale > 0.001f)) safeScale = 1.0f;  // NaN 比较恒 false 一并拦截

    // camX/camY NaN 必须单独消毒（上式仅覆盖 scale）——
    // NaN 相机 → 投影矩阵全 NaN → Vulkan 全屏黑且相机静止时不可自愈
    float safeCamX = camX;
    float safeCamY = camY;
    if (!(safeCamX > -1e9f && safeCamX < 1e9f)) safeCamX = 0.0f;  // NaN 比较恒 false 一并拦截
    if (!(safeCamY > -1e9f && safeCamY < 1e9f)) safeCamY = 0.0f;

    g_scale = safeScale;
    // 统一俯视投影：纵向压缩系数与 Kotlin 相机数学同源（生成常量）
    const float topdownYScale = scene::kTopdownYScale;
    cameraProjMatrix(g_projMatrix, safeCamX, safeCamY, safeScale, (float)vpW, (float)vpH, topdownYScale);
    if (g_renderer) g_renderer->setProjection(g_projMatrix);

    // 记录视口世界坐标范围（供瓦片/崖壁层可见性检测使用；Y 轴按俯视压缩
    // 系数扩大可见世界高度，与投影矩阵可见带严格一致）
    g_viewLeft   = safeCamX;
    g_viewTop    = safeCamY;
    g_viewRight  = safeCamX + (float)vpW / safeScale;
    g_viewBottom = safeCamY + (float)vpH / (safeScale * topdownYScale);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_setCamera(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat camX, jfloat camY, jfloat scale,
    jint vpW, jint vpH) {
    updateCameraGlobals(camX, camY, scale, vpW, vpH);
}

/** 渲染质量热控状态推送（仿 setCamera 独立通道：Compose 线程写、渲染线程单消费者读） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_setRenderQuality(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat qualityFactor, jboolean decorationsDisabled) {

    float q = qualityFactor;
    if (q < 0.0f) q = 0.0f;
    if (q > 1.0f) q = 1.0f;
    g_qualityFactor.store(q);
    g_decorationsDisabled.store(decorationsDisabled == JNI_TRUE);
}

/** 渲染特性开关推送（仿 setRenderQuality：Compose 线程写、渲染线程单消费者读） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_setRenderFlags(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jboolean buildingShadows, jboolean selectionHighlight, jboolean decorLod) {

    g_buildingShadows.store(buildingShadows == JNI_TRUE);
    g_selectionHighlight.store(selectionHighlight == JNI_TRUE);
    g_decorLod.store(decorLod == JNI_TRUE);
}

/**
 * SkyBackground 配置推送（Compose 线程调用，渲染线程下一帧生效）。
 * 四段渐变（top→second→third→bottom）。只需改颜色/位置/强度即可实现未来
 * 晴天/傍晚/夜晚/阴天切换，不改地图渲染。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_setSkyConfig(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat topR, jfloat topG, jfloat topB,
    jfloat secondR, jfloat secondG, jfloat secondB,
    jfloat thirdR, jfloat thirdG, jfloat thirdB,
    jfloat botR, jfloat botG, jfloat botB,
    jfloat secondT, jfloat thirdT,
    jfloat strength) {

    g_sky.setTopColor(topR, topG, topB);
    g_sky.setUpperMidColor(secondR, secondG, secondB);
    g_sky.setLowerMidColor(thirdR, thirdG, thirdB);
    g_sky.setBotColor(botR, botG, botB);
    g_sky.setStops(secondT, thirdT);
    g_sky.setStrength(strength);
}

/**
 * 绘制屏幕空间天空背景（渲染线程帧首调用，beginFrame 之后、drawFrame 之前）。
 * 背景以屏幕正交投影绘制（相机平移/缩放不影响），始终为最底图层。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_drawSky(
    JNIEnv* /*env*/, jobject /*thiz*/) {

    if (!g_renderer) return;
    int count = 0;
    const SpriteVertex* verts = g_sky.getScreenVertices(count);
    if (count > 0 && verts) {
        g_renderer->drawBackground(verts, count, g_sky.getGradientParams());
    }
    g_sky.markDrawn();
}

// ============================================================
// 场景绘制提交辅助（地图层/崖壁层/叠加层共用——单实现防漂移）
// ============================================================

/** 装饰层跳过判定（溢出降级/热控关闭/质量因子/缩放 LOD——地图层唯一判定点） */
static bool decorSkipActive(bool overflowDegrade) {
    return overflowDegrade ||
           g_decorationsDisabled.load() ||
           g_qualityFactor.load() < scene::kDecorQualityThreshold ||
           (g_decorLod.load() && g_scale < scene::kDecorQualityThreshold);
}

/** 地图层批提交 + 溢出遥测结算（地图层绘制帧尾段单一实现） */
static void submitMapBatchCommon(bool overflowDegrade, uint32_t atlasTexId) {
    const int vertCount = g_mapBatcher.end();
    if (g_mapBatcher.droppedSprites > 0) {
        logBatcherOverflowOncePerSecond("map", g_mapBatcher.droppedSprites);
    }
    // 溢出遥测结算（地图层为主判定 pass：降级帧计数 + 恢复计数仅在此维护）
    noteBatcherOverflow(g_mapBatcher.droppedSprites);
    if (overflowDegrade) {
        s_degradeFramesTotal.fetch_add(1, std::memory_order_relaxed);
        if (g_mapBatcher.droppedSprites == 0) {
            // 降级帧未再溢出 → 连续恢复计数满阈值解除（恢复正常渲染）
            if (++s_cleanFramesSinceOverflow >= kOverflowRecoveryCleanFrames) {
                s_overflowDegradeActive.store(false, std::memory_order_relaxed);
            }
        }
    }
    if (vertCount > 0 && g_renderer) {
        g_renderer->draw(g_mapBatcher.vertices, vertCount, atlasTexId);
    }
}

/** 崖壁层构建 + 逐纹理连续段提交（drawFrame 路径与独立 drawIslandCliffs 端口共用；
 *  观测锚点日志进程内一次，两路等价消费布局数据） */
static void drawCliffLayerInternal(const jfloat* data, int pieceCount) {
    // 观测锚点（进程内一次）：确认 C++ 侧消费到布局数据（真机排查按此过滤）
    static bool s_islandCliffLogged = false;
    if (!s_islandCliffLogged) {
        s_islandCliffLogged = true;
        LOGI("drawIslandCliffs: %d pieces (textures=%d)", pieceCount, (int)g_cliffTexCount);
    }

    scene::CliffLayerParams p;
    p.data = data;
    p.pieceCount = pieceCount;
    p.texIds = g_cliffTexIds;
    p.texCount = g_cliffTexCount;
    p.viewLeft = g_viewLeft;
    p.viewTop = g_viewTop;
    p.viewRight = g_viewRight;
    p.viewBottom = g_viewBottom;
    p.scale = g_scale;
    p.fadeAlpha = g_fadeAlpha.load();

    // 跨帧复用构建器：崖壁层与地图层各用独立 static，互不串批
    Renderer2D* renderer = g_renderer;
    scene::buildCliffLayer(
        g_edgeBatcher, g_projMatrix, p,
        [renderer](uint32_t texId, const SpriteVertex* verts, int count) {
            if (renderer) renderer->draw(verts, count, texId);
        });

    if (g_edgeBatcher.droppedSprites > 0) {
        logBatcherOverflowOncePerSecond("cliff", g_edgeBatcher.droppedSprites);
    }
    // 崖壁层溢出只登记（下帧降级由地图层装饰跳过承接，崖壁为结构层不可跳）
    noteBatcherOverflow(g_edgeBatcher.droppedSprites);
}

/**
 * 叠加层绘制（R3.3/B11，仅新路径）：网格线 / 占地预览框 + 预览精灵 / 选中高亮 /
 * 拆除高亮的几何由 scene_draw.h 生成，参数全部取自 SceneStore 状态 + 本帧相机。
 *
 * 相机取 g_viewLeft/g_viewTop/g_scale（= drawFrame 消毒后的相机，与投影矩阵
 * 严格同源）——旧路径高亮线宽用 RenderFrame.scale（帧率门控旧值）、网格线用
 * 后端自留的 cachedScale，两值在相机移动帧可差一帧；新路径统一到投影同源，
 * 叠加层与地图层物理上不可能错位。
 *
 * 覆盖层不乘 g_fadeAlpha（淡入只作用于地图层 quad）。
 */
static void drawOverlayLayerInternal(int32_t overlayFlags, jint vpW, jint vpH) {
    scene::OverlayParams p;
    p.camX = g_viewLeft;
    p.camY = g_viewTop;
    p.scale = g_scale;
    p.viewportW = vpW;
    p.viewportH = vpH;
    p.cols = g_scene.cols();
    p.rows = g_scene.rows();
    p.tileSize = g_scene.tileSize();
    p.buildings = g_scene.buildingsData();
    p.buildingCount = g_scene.buildingCount();
    p.selectionIndex = g_scene.selectionIndex();
    p.markers = g_scene.markersData();
    p.markerCount = g_scene.markerCount();
    p.preview = g_scene.preview();
    p.atlasTexId = g_sceneAtlasTexId;
    p.gridVisible = (overlayFlags & scene::kOverlayBitGridVisible) != 0;
    p.previewSpriteVisible = (overlayFlags & scene::kOverlayBitPreviewSprite) != 0;
    p.previewBoxVisible = (overlayFlags & scene::kOverlayBitPreviewBox) != 0;
    p.previewValid = (overlayFlags & scene::kOverlayBitPreviewValid) != 0;
    p.selectionEnabled = (overlayFlags & scene::kOverlayBitSelection) != 0;
    p.demolishEnabled = (overlayFlags & scene::kOverlayBitDemolish) != 0;
    // RenderFlags.selectionHighlight：既有通道（setRenderFlags 推送），
    // 新路径由 C++ 直接消费（旧路径由 Kotlin 侧判定，两路同源同值）
    p.selectionHighlightFlag = g_selectionHighlight.load();

    Renderer2D* renderer = g_renderer;
    scene::buildOverlayLayers(
        g_overlayBatcher, g_projMatrix, p,
        [renderer](uint32_t texId, const SpriteVertex* verts, int count) {
            if (renderer) renderer->draw(verts, count, texId);
        });

    if (g_overlayBatcher.droppedSprites > 0) {
        logBatcherOverflowOncePerSecond("overlay", g_overlayBatcher.droppedSprites);
    }
    // 叠加层容量丢弃只观测不登记降级：装饰降级以地图层为判定 pass（叠加层
    // 为结构层不可跳），且叠加层 quad 数量级远小于地图层，不参与降级振荡
}

// ============================================================
// 场景绘制路径：SceneStore 导入端口 + drawFrame（R3.2——JNI 面 8 端口；
// R3.3/B11 在同一段追加叠加层状态导入 3 端口，见其独立豁免登记块）
//
// 【JNI 面豁免登记】（沿 R0.2 nativeFpDeterminismProbe / B06
// nativeSetDirtyExportProtobuf 先例）：8 端口属"场景数据导入 + 每帧绘制"
// 通道，无法沿用既有通道（nativeExecute ActionId 业务事务面 / 镜像导出面
// 均非渲染场景数据形状）；即 B18 已退役的旧唯一渲染入口 drawAllTiles
// （17 参数全量数组每帧跨线）的替身。
// ============================================================

/** 地形一次性导入（展平瓦片 + 网格尺寸 + 格像素；地图切换/建筑占位变化时重导） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_sceneSetTerrain(
    JNIEnv* env, jobject /*thiz*/,
    jintArray tileData, jint cols, jint rows, jint tileSize) {
    if (tileData == nullptr) {
        g_scene.setTerrain(nullptr, 0, 0, 0, 0);
        return;
    }
    const jsize n = env->GetArrayLength(tileData);
    if (n <= 0) {
        g_scene.setTerrain(nullptr, 0, 0, 0, 0);
        return;
    }
    std::vector<int32_t> tiles(static_cast<size_t>(n));
    env->GetIntArrayRegion(tileData, 0, n, tiles.data());
    g_scene.setTerrain(tiles.data(), n, cols, rows, tileSize);
}

/** 建筑集更新（变化驱动推送；count 与数组容量钳制语义同旧路径 effectiveCount） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_sceneUpdateBuildings(
    JNIEnv* env, jobject /*thiz*/,
    jfloatArray buildingData, jint buildingCount) {
    if (buildingData == nullptr || buildingCount <= 0) {
        g_scene.updateBuildings(nullptr, 0);
        return;
    }
    const jsize floats = env->GetArrayLength(buildingData);
    if (floats < scene::kBuildingStride) {
        g_scene.updateBuildings(nullptr, 0);
        return;
    }
    const jsize capped = static_cast<jsize>(
        std::min<int64_t>(buildingCount, floats / scene::kBuildingStride));
    if (capped <= 0) {
        g_scene.updateBuildings(nullptr, 0);
        return;
    }
    std::vector<float> data(static_cast<size_t>(capped) * scene::kBuildingStride);
    env->GetFloatArrayRegion(buildingData, 0, static_cast<jsize>(data.size()), data.data());
    g_scene.updateBuildings(data.data(), capped);
}

/** 灵田作物集更新（[gx,gy,progress01]×N；进度的帧间平滑在绘制核心） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_sceneUpdateCrops(
    JNIEnv* env, jobject /*thiz*/,
    jfloatArray cropData, jint cropCount) {
    if (cropData == nullptr || cropCount <= 0) {
        g_scene.updateCrops(nullptr, 0);
        return;
    }
    const jsize floats = env->GetArrayLength(cropData);
    if (floats < scene::kCropStride) {
        g_scene.updateCrops(nullptr, 0);
        return;
    }
    const jsize capped = static_cast<jsize>(
        std::min<int64_t>(cropCount, floats / scene::kCropStride));
    if (capped <= 0) {
        g_scene.updateCrops(nullptr, 0);
        return;
    }
    std::vector<float> data(static_cast<size_t>(capped) * scene::kCropStride);
    env->GetFloatArrayRegion(cropData, 0, static_cast<jsize>(data.size()), data.data());
    g_scene.updateCrops(data.data(), capped);
}

/** 石板道路掩码更新（展平 1-based 编码；空 = 清空道路层，双端跳过整层） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_sceneUpdateRoads(
    JNIEnv* env, jobject /*thiz*/,
    jintArray roadData, jint cellCount) {
    if (roadData == nullptr || cellCount <= 0) {
        g_scene.updateRoads(nullptr, 0);
        return;
    }
    const jsize n = env->GetArrayLength(roadData);
    const jsize capped = static_cast<jsize>(std::min<int64_t>(cellCount, n));
    if (capped <= 0) {
        g_scene.updateRoads(nullptr, 0);
        return;
    }
    std::vector<int32_t> data(static_cast<size_t>(capped));
    env->GetIntArrayRegion(roadData, 0, capped, data.data());
    g_scene.updateRoads(data.data(), capped);
}

/** 云实例快照更新（渲染线程 CloudLayerAnimator 生成、变化时推送） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_sceneUpdateClouds(
    JNIEnv* env, jobject /*thiz*/,
    jfloatArray cloudData, jint cloudCount) {
    if (cloudData == nullptr || cloudCount <= 0) {
        g_scene.updateClouds(nullptr, 0);
        return;
    }
    const jsize floats = env->GetArrayLength(cloudData);
    if (floats < scene::kCloudStride) {
        g_scene.updateClouds(nullptr, 0);
        return;
    }
    const jsize capped = static_cast<jsize>(
        std::min<int64_t>(cloudCount, floats / scene::kCloudStride));
    if (capped <= 0) {
        g_scene.updateClouds(nullptr, 0);
        return;
    }
    std::vector<float> data(static_cast<size_t>(capped) * scene::kCloudStride);
    env->GetFloatArrayRegion(cloudData, 0, static_cast<jsize>(data.size()), data.data());
    g_scene.updateClouds(data.data(), capped);
}

/** 崖壁布局导入（IslandCliffBridge 一次性预计算的稳定布局；null = 清空整层） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_sceneSetCliffLayout(
    JNIEnv* env, jobject /*thiz*/,
    jfloatArray cliffData, jint pieceCount) {
    if (cliffData == nullptr || pieceCount <= 0) {
        g_scene.setCliffLayout(nullptr, 0);
        return;
    }
    const jsize floats = env->GetArrayLength(cliffData);
    if (floats < scene::kCliffStride) {
        g_scene.setCliffLayout(nullptr, 0);
        return;
    }
    const jsize capped = static_cast<jsize>(
        std::min<int64_t>(pieceCount, floats / scene::kCliffStride));
    if (capped <= 0) {
        g_scene.setCliffLayout(nullptr, 0);
        return;
    }
    std::vector<float> data(static_cast<size_t>(capped) * scene::kCliffStride);
    env->GetFloatArrayRegion(cliffData, 0, static_cast<jsize>(data.size()), data.data());
    g_scene.setCliffLayout(data.data(), capped);
}

/** 图集纹理 ID 注入（上传完成时；0 = 未就绪——地图层跳过，崖壁层不受影响） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_sceneSetAtlasTexture(
    JNIEnv* /*env*/, jobject /*thiz*/, jint atlasTexId) {
    g_sceneAtlasTexId = atlasTexId > 0 ? static_cast<uint32_t>(atlasTexId) : 0;
}

// ============================================================
// R3.5/B12 远景观看容量路径开关（引擎控制端口）
//
// 【JNI 面豁免登记】（沿 R0.2 nativeFpDeterminismProbe / B06
// nativeSetDirtyExportProtobuf / B09 nativeSetDirtyExportColumn 先例）：
// 本端口是**引擎渲染控制态**，无法沿用既有通道——ActionId 业务事务面
// （nativeExecute）承载玩法操作，镜像导出面（nativeExport*）承载状态同步，
// 二者均非"渲染容量策略开关"形状；与既有 nativeSetAiThermalBatchSize /
// nativeSetDirtyExportProtobuf 同族（引擎线程控制端口）。
//
// 语义：Kotlin 侧由 com.xianxia.sect.core.render.FarViewGroundPolicy 逐帧/变化时
// 判定四重门（用户旗标 ∧ 图集就绪 ∧ 缩放到位 ∧ 设备白名单），把**合取结果**
// 推为单一布尔——C++ 侧不做设备判定（保持 native-renderer 零平台依赖，
// 桌面 GTest 可直测），只按布尔选地面绘制形态。
// ============================================================

/** 远景整图 REPEAT 地面开关（false = 逐格地面，R3.5 前现状；默认 false） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_nativeSetFarViewGroundQuad(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean on) {
    g_farViewGroundQuad.store(on == JNI_TRUE, std::memory_order_relaxed);
}

// ============================================================
// R3.3/B11 叠加层状态导入端口（选中索引 / 拆除标记 / 预览几何）
//
// 【JNI 面豁免登记】（沿 R0.2 探针 / B06 nativeSetDirtyExportProtobuf /
// B10 场景 8 端口先例）：本组 3 端口是"叠加层状态变化驱动导入"通道，与
// 场景 8 端口同族（ActionId 业务事务面 / 镜像导出面均非渲染叠加状态形状）。
// 它们替代的是旧路径**每帧逐 rect 跨线**——放置模式下每帧最坏 258 次
// drawRect（网格线）+ 5（占地框）+ 5（选中）+ 逐建筑拆除矩形；本组端口
// 只在状态**变化**的那一帧触线（选中切换/拆除勾选/拖拽预览），几何
// 生成全部在 C++ 侧（scene_draw.h::buildOverlayLayers）。
// 既有 drawRect 端口**保留不删**（回滚臂与其他消费者仍用）。
// ============================================================

/** 选中建筑索引导入（-1 = 无选中；越界由绘制核心按建筑数跳过——旧路径同语义） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_sceneSetSelection(
    JNIEnv* /*env*/, jobject /*thiz*/, jint selectedIndex) {
    g_scene.setSelection(static_cast<int32_t>(selectedIndex));
}

/** 拆除高亮标记导入（逐建筑 1 字节，与建筑集同序；null/0 = 非拆除模式整层跳过） */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_sceneSetDemolishMarkers(
    JNIEnv* env, jobject /*thiz*/, jbyteArray markers, jint markerCount) {
    if (markers == nullptr || markerCount <= 0) {
        g_scene.setDemolishMarkers(nullptr, 0);
        return;
    }
    const jsize n = env->GetArrayLength(markers);
    const jsize capped = static_cast<jsize>(std::min<int64_t>(markerCount, n));
    if (capped <= 0) {
        g_scene.setDemolishMarkers(nullptr, 0);
        return;
    }
    std::vector<uint8_t> data(static_cast<size_t>(capped));
    env->GetByteArrayRegion(markers, 0, capped, reinterpret_cast<jbyte*>(data.data()));
    g_scene.setDemolishMarkers(data.data(), capped);
}

/**
 * 预览几何导入（[boxX,boxY,boxW,boxH, spriteX,spriteY,spriteW,spriteH,
 * u0,v0,u1,v1, r,g,b,a]；null = 清空）。
 *
 * 位置由触控驱动（非网格对齐），故按**值变化**推送；占地框颜色（可放置/阻挡）
 * 与各层可见性不在此承载——每帧经 drawFrame 的 overlayFlags 位表达。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_sceneSetPreview(
    JNIEnv* env, jobject /*thiz*/, jfloatArray previewData) {
    if (previewData == nullptr) {
        g_scene.setPreview(nullptr);
        return;
    }
    const jsize n = env->GetArrayLength(previewData);
    if (n < scene::kPreviewStride) {
        g_scene.setPreview(nullptr);
        return;
    }
    float values[scene::kPreviewStride];
    env->GetFloatArrayRegion(previewData, 0, scene::kPreviewStride, values);
    g_scene.setPreview(values);
}

/**
 * 浮字生成（R3.8/B13）——**低频事件驱动**通道，非每帧。
 *
 * 入参扁平序（10 个标量，`kFloatSpawnStride` = 10）：
 *   [0] worldX          世界锚点 X（格）
 *   [1] worldY          世界锚点 Y（格）
 *   [2] assetIndex      Tier1 资产索引（词条 0..kFloatWordCount-1 /
 *                       单字形 kFloatGlyphBaseIndex..）
 *   [3] charCount       连续字形数（词条 = 词条字数；数字串 = 位数）
 *   [4] styleIndex      样式档（kFloatStyle* 0..3）
 *   [5] scale           附加缩放（<=0 或非有限 → 拒绝）
 *   [6] riseScale       上浮速度倍率（<=0 或非有限 → 拒绝）
 *   [7] bounce          是否播放暴击弹跳（非 0 = true）
 *   [8] reserved        保留（须为 0；非 0 直接拒绝，防未来协议歧义）
 *   [9] reserved2       保留（须为 0）
 *
 * **零每帧 JNI 纪律**：本端口只在游戏事件（伤害结算/治疗/词条提示）发生时
 * 调用，绝不由渲染循环驱动；动画/上浮/淡出全在 C++ 池内时间驱动。
 * 池满覆盖最旧（不报错、不阻塞调用方）——溢出经 g_floatPool.stats() 可观测。
 * 非法参数（非有限坐标 / 越界索引 / 保留位非 0）**静默拒绝**（不入池、不崩溃）。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_sceneSpawnFloatingText(
    JNIEnv* env, jobject /*thiz*/, jfloatArray spawnData) {
    if (spawnData == nullptr) return;
    const jsize n = env->GetArrayLength(spawnData);
    if (n < scene::kFloatSpawnStride) return;
    float v[scene::kFloatSpawnStride];
    env->GetFloatArrayRegion(spawnData, 0, scene::kFloatSpawnStride, v);

    // 保留位协议守卫：非 0 = 调用方与 native 版本不匹配 → 拒绝（防静默错读）
    if (v[8] != 0.0f || v[9] != 0.0f) return;

    const int32_t assetIndex = static_cast<int32_t>(v[2]);
    const int32_t charCount = static_cast<int32_t>(v[3]);
    const int32_t styleIndex = static_cast<int32_t>(v[4]);

    g_floatPool.spawn(
        v[0], v[1], assetIndex, charCount, styleIndex,
        g_floatNowSeconds,
        scene::kFloatAssetCount,
        v[5], v[6], v[7] != 0.0f);
}

/**
 * 每帧绘制（R3.2 新路径唯一帧入口）：相机标量 + 覆盖标志（G3 <200B/帧），
 * 场景数据从 SceneStore 消费（sceneSet* / sceneUpdate* 变化驱动维护）。
 *
 * overlayFlags 位定义（scene::kOverlayBit*，与 Kotlin OVERLAY_FLAG_* 逐位同值）：
 *   bit0 buildingVisible / bit1 网格线 / bit2 预览精灵 / bit3 占地框 /
 *   bit4 预览合法性（绿/红）/ bit5 选中高亮 / bit6 拆除高亮。
 * 位 1–6 为 R3.3 启用：四类叠加层几何在本函数内由 scene_draw.h 生成
 * （旧路径的每帧逐 rect 跨线由此退役；其数据经 sceneSetSelection /
 * sceneSetDemolishMarkers / sceneSetPreview 变化驱动导入）。
 * 层序（R3.8）：天空 → 崖壁 → 地图 → 叠加层 → **浮字（最上层）**。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_drawFrame(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat camX, jfloat camY, jfloat scale,
    jint vpW, jint vpH,
    jint overlayFlags,
    jfloat fadeAlpha, jfloat frameAlpha) {

    if (!g_renderer) return;

    // 相机段：消毒 + 投影 + 视野边界（与 setCamera 单实现）
    updateCameraGlobals(camX, camY, scale, vpW, vpH);

    // 浮字时间标量推进（C++ 内自累加——零 ABI 变更，见 g_floatNowSeconds 说明）
    g_floatNowSeconds += kFloatFrameStepSeconds;
    if (g_floatNowSeconds >= kFloatTimeWrapSeconds) g_floatNowSeconds = 0.0f;
    g_floatPool.advance(g_floatNowSeconds);

    const bool overflowDegrade = s_overflowDegradeActive.load(std::memory_order_relaxed);

    // 淡入 alpha 消毒（NaN 行为：clamp 不拦 NaN）；
    // 消毒后先写回全局量——崖壁层与地图层共享消费 g_fadeAlpha 单一来源
    // （必须先于崖壁层——图集未就绪窗口地图层跳过时崖壁淡入仍随帧推进）
    float fade = fadeAlpha;
    if (fade < 0.0f) fade = 0.0f;
    if (fade > 1.0f) fade = 1.0f;
    g_fadeAlpha.store(fade);

    // 崖壁层（z 序：天空 → 崖壁 → 地面；独立纹理不依赖图集——与旧路径
    // drawIslandCliffs 先于瓦片层的层序一致）
    if (g_scene.hasCliffs() && g_cliffTexCount > 0) {
        drawCliffLayerInternal(g_scene.cliffsData(), g_scene.cliffPieceCount());
    }

    // 地图层（地形已导入 + 图集就绪；buildingVisible = overlayFlags bit0）
    if (g_scene.hasTerrain() && g_sceneAtlasTexId != 0) {
        scene::MapLayerParams p;
        p.viewLeft = g_viewLeft;
        p.viewTop = g_viewTop;
        p.viewRight = g_viewRight;
        p.viewBottom = g_viewBottom;
        p.scale = g_scale;
        p.fadeAlpha = fade;
        p.frameAlpha = frameAlpha;
        p.skipDecor = decorSkipActive(overflowDegrade);
        p.skipClouds = p.skipDecor;
        p.buildingShadows = g_buildingShadows.load();
        p.buildingVisible = (overlayFlags & scene::kOverlayBitBuildingVisible) != 0;
        p.tiles = g_scene.terrainData();
        p.tileCount = g_scene.terrainCount();
        p.cols = g_scene.cols();
        p.rows = g_scene.rows();
        p.tileSize = g_scene.tileSize();
        p.atlasTexId = g_sceneAtlasTexId;
        p.groundQuadEnabled = g_farViewGroundQuad.load(std::memory_order_relaxed);
        p.groundTexId = g_groundTexId;
        p.tileUv = scene::kTileUv;
        p.tileUvCount = scene::kTileUvCount;
        p.roads = g_scene.roadsData();
        p.roadCount = g_scene.roadsCount();
        p.roadUv = scene::kRoadUv;
        p.roadUvCount = scene::kRoadUvCount;
        p.buildings = g_scene.buildingsData();
        p.buildingClaim = g_scene.buildingCount();
        p.buildingDataFloats = g_scene.buildingCount() * scene::kBuildingStride;
        p.buildingUv = scene::kBuildingUv;
        p.buildingUvCount = scene::kBuildingUvCount;
        p.crops = g_scene.cropsData();
        p.cropCount = g_scene.cropCount();
        p.cropUv = scene::kCropUv;
        p.cropUvCount = scene::kCropUvCount;
        p.clouds = g_scene.cloudsData();
        p.cloudCount = g_scene.cloudCount();
        p.cloudUv = scene::kCloudUv;
        p.cloudUvCount = scene::kCloudUvCount;

        scene::buildMapBatch(g_mapBatcher, p, g_projMatrix, g_cropSmooth,
            [](uint32_t texId, const SpriteVertex* verts, int count) {
                // R3.5 整图 REPEAT 地面：独立纹理，须自带一次 draw
                if (g_renderer != nullptr) g_renderer->draw(verts, count, texId);
            });
        submitMapBatchCommon(overflowDegrade, g_sceneAtlasTexId);
    }

    // 叠加层（R3.3）：选中高亮 → 拆除高亮 → 预览精灵 → 占地框 → 网格线，
    // 层序与旧 Kotlin 路径严格一致。地图层/图集未就绪时仍须绘制（旧路径的
    // 网格线与高亮本就不依赖瓦片层；预览精灵的图集守卫在生成核心内）
    drawOverlayLayerInternal(overlayFlags, vpW, vpH);

    // 浮字层（R3.8/B13）：**最上层**（叠加层之上）。空池 = 零 draw call；
    // 图集未就绪整层跳过（与地图层/叠加层预览精灵同守卫语义）。零每帧 JNI。
    if (g_sceneAtlasTexId != 0) {
        scene::FloatTextParams fp;
        fp.instances = &g_floatPool.slot(0);
        fp.instanceCount = scene::FloatTextPool::capacity();
        fp.nowSeconds = g_floatNowSeconds;
        fp.atlasTexId = g_sceneAtlasTexId;
        fp.uv = scene::kFloatUv;
        fp.assetCount = scene::kFloatAssetCount;
        fp.glyphBaseIndex = scene::kFloatGlyphBaseIndex;
        fp.tileSize = static_cast<float>(g_scene.tileSize());
        fp.baseWorldHeight = scene::kFloatBaseWorldHeight;
        fp.viewLeft = g_viewLeft;
        fp.viewTop = g_viewTop;
        fp.viewRight = g_viewRight;
        fp.viewBottom = g_viewBottom;
        // 提交顺序即 draw call 序：浮字整批一个纹理段（同图集）——
        // 空池时 buildFloatTextBatch 不 begin/不 submit ⇒ 零 draw call
        scene::buildFloatTextBatch(g_floatBatcher, g_projMatrix, fp,
            [](uint32_t texId, const SpriteVertex* verts, int count) {
                if (g_renderer != nullptr) g_renderer->draw(verts, count, texId);
            });
        if (g_floatBatcher.droppedSprites > 0) {
            logBatcherOverflowOncePerSecond("float", g_floatBatcher.droppedSprites);
        }
    }
}

// ============================================================
// 浮空岛崖壁层（z 序：天空 → 崖壁 → 地面）
//
// 世界空间静态合成：布局数据 [texIdx, x, y, w, h, u0, v0, u1, v1, flags] × N
// 由 Kotlin IslandCliffBridge（C++ gamecore::map::island_cliff.h 单一权威）
// 一次性预计算（地图尺寸/种子变化时重建，Camera 平移/缩放不重建）；本函数只
// 消费——可见性剔除 + 逐纹理 SpriteBatcher 批处理。淡入 alpha 与瓦片层同源
// （g_fadeAlpha）；GAP_EPSILON 同式（防接缝）。
//
// 与瓦片层的差异（独立纹理）：
//   - UV 逐条目携带，**不再**加 UV 收缩偏移——该常量按 4096 图集纹素推导
//     （0.5/4096），用于防图集邻居渗色；独立纹理各自归一化且无邻居，
//     加该偏移会在地图边界处露出 0.5 纹素的透明缝（UV 张成问题）。
//   - 每张纹理一次 g_renderer->draw（submitFrame 亦按 DrawBatch.textureId
//     分组重绑），故逐条目累积的批按纹理切换自然切分。
//   - 镜像条目（flags bit0）的 u0 > u1，取 min/max 归一后再送批
//     （SpriteBatcher 只接受 u0 ≤ u1 的矩形语义）。
// ============================================================
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_drawIslandCliffs(
    JNIEnv* env, jobject /*thiz*/,
    jfloatArray cliffData) {

    if (!g_renderer || !cliffData) return;
    const jsize pieceCount = env->GetArrayLength(cliffData) / scene::kCliffStride;
    if (pieceCount <= 0 || g_cliffTexCount <= 0) return;

    jfloat* data = env->GetFloatArrayElements(cliffData, nullptr);
    if (data == nullptr) return;

    drawCliffLayerInternal(data, pieceCount);

    env->ReleaseFloatArrayElements(cliffData, data, JNI_ABORT);
}

// ============================================================
// 崖壁纹理通道（独立纹理，非图集）
// ============================================================

/**
 * 接收崖壁纹理 ID 表（主线程；下标序与 Kotlin IslandCliffTextureSet 一致）。
 * 0 表示该张上传失败——绘制端跳过引用它的条目（部分降级）。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_setIslandCliffTextures(
    JNIEnv* env, jobject /*thiz*/, jintArray textureIds) {

    if (textureIds == nullptr) {
        applyCliffTextures(nullptr, 0);
        return;
    }
    const jsize n = env->GetArrayLength(textureIds);
    if (n <= 0) {
        applyCliffTextures(nullptr, 0);
        return;
    }
    const jsize capped = n > kMaxCliffTextures ? kMaxCliffTextures : n;
    std::vector<jint> ids(static_cast<size_t>(capped));
    env->GetIntArrayRegion(textureIds, 0, capped, ids.data());
    std::vector<uint32_t> uids(static_cast<size_t>(capped));
    for (jsize i = 0; i < capped; i++) {
        uids[static_cast<size_t>(i)] = static_cast<uint32_t>(ids[static_cast<size_t>(i)]);
    }
    applyCliffTextures(uids.data(), static_cast<int32_t>(capped));
}

/**
 * 上传单张崖壁压缩纹理（KTX1 封装 ASTC 4×4；仅 Vulkan 支持）。
 *
 * 与图集上传（uploadCompressedAtlas）的差异：本函数把纹理 ID 交给调用方自行
 * 保管（返回给 Kotlin 收集成表），不写入任何固定槽位。
 *
 * @return 纹理 ID；0 = 非 Vulkan 后端 / KTX 校验失败 / 设备不支持 ASTC →
 *         调用方回退 RGBA 路径（mip 链 → 单级）
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_uploadIslandCliffKtx(
    JNIEnv* env, jobject /*thiz*/, jbyteArray ktxData) {

    if (!g_renderer || !ktxData) return 0;
    // 动态转换（图集路径同纪律）：g_renderer 非 VulkanBackend（GLES 等）→ 返回 0 走回退
    auto* vk = dynamic_cast<VulkanBackend*>(g_renderer);
    if (vk == nullptr) return 0;

    const jsize len = env->GetArrayLength(ktxData);
    if (len <= 0) return 0;
    std::vector<uint8_t> bytes(static_cast<size_t>(len));
    env->GetByteArrayRegion(ktxData, 0, len, reinterpret_cast<jbyte*>(bytes.data()));

    KtxInfo info{};
    if (!loadKtx1(bytes.data(), bytes.size(), info)) {
        LOGW("uploadIslandCliffKtx: KTX 校验失败，回退 RGBA 崖壁纹理");
        return 0;
    }
    const uint32_t id = vk->uploadCompressedTexture(
        info.data, info.dataSize, static_cast<int>(info.width),
        static_cast<int>(info.height), static_cast<int>(info.mipCount));
    if (id == 0) {
        LOGW("uploadIslandCliffKtx: 上传失败（设备不支持 ASTC？），回退 RGBA 崖壁纹理");
    }
    return static_cast<jint>(id);
}

/**
 * 上传单张崖壁 RGBA mip 链纹理（仅 Vulkan 支持；GLES 返回 0 走单级回退）。
 *
 * @param pixelData level-major 紧凑 RGBA8 direct 缓冲区（首级 = width×height）
 * @return 纹理 ID；0 = 非 Vulkan 后端 / 校验失败 / direct 缓冲区非法
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_uploadIslandCliffMipChain(
    JNIEnv* env, jobject /*thiz*/,
    jobject pixelData, jint width, jint height, jint mipCount) {

    if (!g_renderer || !pixelData) return 0;
    auto* vk = dynamic_cast<VulkanBackend*>(g_renderer);
    if (vk == nullptr) return 0;
    if (width <= 0 || height <= 0 || mipCount <= 0) return 0;

    const void* pixels = lockDirectPixels(env, pixelData, width, height, "uploadIslandCliffMipChain");
    if (pixels == nullptr) return 0;
    const uint32_t id = vk->uploadMipChainTexture(
        pixels, static_cast<int>(width), static_cast<int>(height), static_cast<int>(mipCount));
    return static_cast<jint>(id);
}

/**
 * 查询设备是否支持 ASTC 4×4 纹理压缩（决定 Kotlin 侧是否走 KTX 上传路径）。
 *
 * @return JNI_TRUE = Vulkan 后端且启用 textureCompressionASTC_LDR
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_isAstcSupported(
    JNIEnv* /*env*/, jobject /*thiz*/) {

    if (!g_renderer) return JNI_FALSE;
    auto* vk = dynamic_cast<VulkanBackend*>(g_renderer);
    if (vk == nullptr) return JNI_FALSE;
    return vk->isAstcSupported() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_drawRect(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat x, jfloat y, jfloat w, jfloat h,
    jfloat r, jfloat g, jfloat b, jfloat a) {

    if (!g_renderer) return;

    SpriteVertex verts[6]{};
    for (int i = 0; i < 6; i++) {
        verts[i] = { 0, 0, 0, 0, r, g, b, a };
    }
    verts[0] = { x, y, 0, 0, r, g, b, a };
    verts[1] = { x + w, y, 0, 0, r, g, b, a };
    verts[2] = { x, y + h, 0, 0, r, g, b, a };
    verts[3] = { x + w, y, 0, 0, r, g, b, a };
    verts[4] = { x + w, y + h, 0, 0, r, g, b, a };
    verts[5] = { x, y + h, 0, 0, r, g, b, a };

    g_renderer->draw(verts, 6, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_drawSprite(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat x, jfloat y, jfloat w, jfloat h,
    jint atlasTexId,
    jfloat u0, jfloat v0, jfloat u1, jfloat v1,
    jfloat r, jfloat g, jfloat b, jfloat a) {

    if (!g_renderer) return;

    SpriteVertex verts[6]{};
    for (int i = 0; i < 6; i++) {
        verts[i] = { 0, 0, 0, 0, r, g, b, a };
    }
    float su0 = u0 + scene::kUvEpsilon, sv0 = v0 + scene::kUvEpsilon;
    float su1 = u1 - scene::kUvEpsilon, sv1 = v1 - scene::kUvEpsilon;
    verts[0] = { x,   y,   su0, sv0, r, g, b, a };
    verts[1] = { x+w, y,   su1, sv0, r, g, b, a };
    verts[2] = { x,   y+h, su0, sv1, r, g, b, a };
    verts[3] = { x+w, y,   su1, sv0, r, g, b, a };
    verts[4] = { x+w, y+h, su1, sv1, r, g, b, a };
    verts[5] = { x,   y+h, su0, sv1, r, g, b, a };

    g_renderer->draw(verts, 6, static_cast<uint32_t>(atlasTexId));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_submitFrame(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_renderer) g_renderer->submitFrame();
}

// ============================================================
// 注：Renderer2D.cpp 只包含接口定义，实现全在上方
// ============================================================
