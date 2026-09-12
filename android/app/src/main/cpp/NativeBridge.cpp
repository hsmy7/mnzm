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
// 建筑占地尺寸查找表（由 SpriteAtlasDef.kt 生成，禁止手改——
// 运行 ./gradlew generateFootprintHeader 重新生成）
#include "footprint_table.h"
// 石板道路求解器 + 渲染合成器（位掩码→形态/描边判定与
// 逐格合成操作序列收敛为单一权威——Kotlin RoadTiling 与双端渲染路径
// 统一引用 gamecore/map/road_system.h + road_compositor.h）
#include "gamecore/map/road_system.h"
#include "gamecore/map/road_compositor.h"
// 绘制层序合成器（立体层装饰 ↔ 建筑按地面接触点归并；锚点公式双端同式）
#include "gamecore/map/draw_order.h"

// UV 向内收缩 0.5 texel（匹配 Cocos2d-x CC_FIX_ARTIFACTS_BY_STRECHING_TEXEL）
// 防止 CLAMP_TO_EDGE + NEAREST 采样下 UV 边界采样到相邻图素，消除彩色缝合线
static constexpr float UV_EPSILON = 0.5f / static_cast<float>(ATLAS_W);

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

// 视口世界坐标范围（由 setCamera 更新，用于 drawAllTiles 的可见性检测）
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
//   decorationsDisabled || qualityFactor < DECOR_QUALITY_THRESHOLD
//   （阈值由生成 TextureAtlas.h 提供——与 SpriteAtlasDef 同源）
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

// SHADOW_OFFSET_TILES / SHADOW_ALPHA 由生成 TextureAtlas.h 提供
//（与 SpriteAtlasDef 同源）

// ============================================================
// 地图淡入过渡状态（由 setFadeAlpha 更新，渲染线程单消费者读）
// 与 Kotlin 侧 FadeTransition（core:engine）同一数学来源：
//   触发：RenderThread 启动时 NativeSurfaceView.fadeIn()（首次/重入/降级统一）
//   计算：渲染线程每帧 alphaAt(elapsedNs, durationNs)（EaseOutCubic，纯时钟驱动）
//   应用：drawAllTiles 所有 add 的 alpha 乘算；drawRect/drawSprite（预览/高亮）不受影响
// ============================================================

/** 地图淡入 alpha（0-1，1 = 完全不透明） */
static std::atomic<float> g_fadeAlpha{1.0f};

// 作物插值状态（文件级 static——shutdownRenderer 需清空，
// 防 surface 代际残留：旧 surface 的进度基准会污染新 surface 播种闪帧）
static std::map<int64_t, float> g_lastCropProgress;
static std::vector<int64_t> g_activeCropKeys;

// ── 帧批量构建器（跨帧复用）──
// 渲染线程单消费者：drawAllTiles/drawIslandEdges 仅由 RenderThread 经 JNI 调用，
// 无并发；grow 一次后堆缓冲跨帧复用，根除每帧 5 次 new/memcpy/delete ×2 的分配链。
// 清理策略与 g_lastCropProgress 同纪律（文件级状态须在 shutdownRenderer 说明）：
// batcher 无 native 句柄，无需清理，仅容量驻留 ≤2×16384×32B=1MB 堆。
static SpriteBatcher g_mapBatcher;
static SpriteBatcher g_edgeBatcher;

// 批量构建器容量溢出限频日志（此前极小缩放下静默丢弃无日志）
static int64_t s_lastOverflowLogNs = 0;
static void logBatcherOverflowOncePerSecond(const char* layer, int dropped) {
    const int64_t nowNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    if (nowNs - s_lastOverflowLogNs < 1'000'000'000LL) return;
    s_lastOverflowLogNs = nowNs;
    LOGW("batcher overflow: layer=%s dropped=%d sprites this frame (capacity capped)", layer, dropped);
}

// 云层实例数据单条步长（[x, y, w, h, spriteIndex, alpha]，与 CloudLayerAnimator.CLOUD_DATA_STRIDE 同值）
static constexpr int CLOUD_DATA_STRIDE = 6;

/** 检查世界坐标矩形是否与视口相交（可见性检测） */
static inline bool isRectVisible(float x, float y, float w, float h) {
    // 矩形完全在视口之外才返回 false
    return !(x + w <= g_viewLeft || x >= g_viewRight ||
             y + h <= g_viewTop || y >= g_viewBottom);
}

// 瓷砖类型常量（TILE_GROUND / TILE_BUILDING）
// 由生成 TextureAtlas.h 提供（与 SpriteAtlasDef.TileType.index 同源）

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
    // 同 g_lastCropProgress 的清理理由）
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
    // 作物插值状态清空：
    // 旧 surface 的进度基准不得污染新 surface 的播种/收获插值
    g_lastCropProgress.clear();
    g_activeCropKeys.clear();
    // resize 请求通道清残留：shutdownRenderer 后到达旧表面的 pending
    // 请求不得作用于新 surface（同 g_lastCropProgress 的代际残留纪律）
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

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_setCamera(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat camX, jfloat camY, jfloat scale,
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
    // 统一俯视投影：纵向压缩系数与 Kotlin 相机数学同源（TextureAtlas.h 生成）
    const float topdownYScale = TOPDOWN_Y_SCALE;
    cameraProjMatrix(g_projMatrix, safeCamX, safeCamY, safeScale, (float)vpW, (float)vpH, topdownYScale);
    if (g_renderer) g_renderer->setProjection(g_projMatrix);

    // 记录视口世界坐标范围（供 drawAllTiles 可见性检测使用；Y 轴按俯视压缩
    // 系数扩大可见世界高度，与投影矩阵可见带严格一致）
    g_viewLeft   = safeCamX;
    g_viewTop    = safeCamY;
    g_viewRight  = safeCamX + (float)vpW / safeScale;
    g_viewBottom = safeCamY + (float)vpH / (safeScale * topdownYScale);
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
 * 地图淡入 alpha 推送（渲染线程每帧调用，Compose 线程不写）。
 * 只影响 drawAllTiles 的地图层 quad alpha；drawRect/drawSprite（预览/高亮）
 * 不受影响——与 Canvas 侧（预览/高亮用独立 Paint）行为双端一致。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_setFadeAlpha(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat fadeAlpha) {

    float a = fadeAlpha;
    if (a < 0.0f) a = 0.0f;
    if (a > 1.0f) a = 1.0f;
    g_fadeAlpha.store(a);
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
 * 绘制屏幕空间天空背景（渲染线程帧首调用，beginFrame 之后、drawAllTiles 之前）。
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

/**
 * 立体层装饰绘制项（树——收集后与建筑按地面接触点归并绘制）。
 *
 * 层序键 = bottomY（地面接触点 = 格底边世界像素）；同键时建筑在后
 * （契约见 gamecore/map/draw_order.h）。
 */
struct ObjectDecorDrawItem {
    float bottomY;
    float x, y, w, h;
    float u0, v0, u1, v1;
};

/** 单帧立体层装饰收集上限（视口最大格数上界，防异常数据把缓冲撑爆） */
static constexpr size_t kMaxObjectDecorItems = 20000;

/** 可见建筑绘制项（预计算几何 + 底边 Y，供层序归并） */
struct BuildingDrawItem {
    float bottomY;
    float x, y, w, h;
    float floorX, floorY, floorW, floorH;  // 占地矩形（阴影用）
    int uvIndex;
    bool shadow;
};

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_drawAllTiles(
    JNIEnv* env, jobject /*thiz*/,
    jintArray tileData,          // 展平瓦片类型数组 [0..N]
    jint cols, jint rows,        // 地图网格尺寸
    jfloatArray buildingData,    // 建筑数据 [x,y,w,h,nameIdx] × count
    jint buildingCount,          // 建筑数量
    jboolean buildingVisible,    // 是否显示建筑
    jint tileSize,
    jint atlasTexId,             // 图集纹理 ID
    jfloatArray uvMap,           // UV 映射 [u0,v0,u1,v1] × tileTypeCount
    jfloatArray buildingUVMap,
    jfloatArray cropData,        // 灵田作物数据 [gx, gy, progress01] × N（可 null）
    jfloatArray cropUVMap,       // 作物 UV 映射 [u0,v0,u1,v1] × 3 阶段（可 null）
    jfloat frameAlpha,           // 逻辑帧插值因子（作物进度帧间平滑）
    jfloatArray cloudData,       // 云层实例数据 [x, y, w, h, spriteIndex, alpha] × N（可 null）
    jfloatArray cloudUVMap,      // 云层 UV 映射 [u0,v0,u1,v1] × 云层类型数（可 null）
    jintArray roadData,          // 石板道路每格位掩码（展平 [0..N]，0=非道路；可 null）
    jfloatArray roadUVMap) {     // 道路 UV 映射 [u0,v0,u1,v1] × ROAD_RECTS 数（可 null）

    if (!g_renderer || !tileData || !uvMap) return;

    // 深度防御：tileData 是唯一无长度
    // 校验的数组（building/crop/uvMap 均有防御）——rows×cols 超数组实际长度
    // 即堆越界读 → SIGSEGV。生产路径同源一致，此为防篡改兜底。
    const jsize tileCount = env->GetArrayLength(tileData);
    if ((jsize)rows * cols > tileCount) return;

    // ★ 地图淡入 alpha（本帧单次读取——所有 add 共用同一值，避免逐次 atomic load）
    const float fadeAlpha = g_fadeAlpha.load();

    // ★ 瓦片几何扩展因子：每个瓦片扩展 0.5 屏幕像素，消除相邻瓦片间 1px 裂缝。
    // 当 scale 极小（<0.001）时用 scale=1 防止除零。
    const float EPS_SCALE = (g_scale > 0.001f) ? g_scale : 1.0f;
    const float GAP_EPSILON = 0.5f / EPS_SCALE;

    jint* tiles = env->GetIntArrayElements(tileData, nullptr);
    jfloat* uvs = env->GetFloatArrayElements(uvMap, nullptr);
    jsize uvCount = env->GetArrayLength(uvMap) / 4;

    // 跨帧复用构建器：引用绑定文件级 static，堆缓冲 grow 一次后驻留
    SpriteBatcher& batcher = g_mapBatcher;
    batcher.begin(g_projMatrix);

    // ---- 1. 瓦片层 ----
    // 地面：单张无缝纹理整图铺（REPEAT 采样，UV=世界坐标/tileSize）。
    // 1 UV 单位 = 1 格 = 32 世界像素 → 地面纹理 64×64 每格 2× 降采样，与逐格绘制同分辨率；
    // 整图单 quad 消除逐格接缝。g_groundTexId==0（未上传）时回退逐格地面保证可见。
    const float tileSizeF = (float)tileSize;

    // 地面绘制约束：整图 REPEAT 地面 quad 在部分 Adreno 驱动上采样异常（黑屏），
    //   且与图集非同一纹理需独立 draw。恒走逐格地面（图集 GROUND 精灵，
    //   与软件渲染路径同源同表现）——整图 quad 代码保留
    //   （GROUND_QUAD_ENABLED 关闭），待驱动/采样问题定位后再启用。
    constexpr bool GROUND_QUAD_ENABLED = false;
    if (GROUND_QUAD_ENABLED && g_groundTexId != 0) {
        SpriteBatcher groundBatcher;
        groundBatcher.begin(g_projMatrix);
        float gx0 = std::max(0.0f, g_viewLeft);
        float gy0 = std::max(0.0f, g_viewTop);
        float gx1 = std::min((float)(cols * tileSize), g_viewRight);
        float gy1 = std::min((float)(rows * tileSize), g_viewBottom);
        if (gx1 > gx0 && gy1 > gy0) {
            groundBatcher.add(g_groundTexId,
                gx0, gy0, gx1 - gx0, gy1 - gy0,
                gx0 / tileSizeF, gy0 / tileSizeF,
                gx1 / tileSizeF, gy1 / tileSizeF,
                1.0f, 1.0f, 1.0f, fadeAlpha);
        }
        const int groundVerts = groundBatcher.end();
        if (groundVerts > 0) {
            g_renderer->draw(groundBatcher.vertices, groundVerts, g_groundTexId);
        }
    }

    // 可见范围钳制迭代（平板省电）：若直接双重循环遍历全部
    // rows×cols（128×128=16384 格）再逐格剔除，平板默认视口仅可见 ~1700 格、
    // 绝大多数为无效遍历。
    // 按 g_view* 世界坐标钳制行列区间（setCamera 同帧先写，天然可用）；
    // 装饰精灵底边居中锚定在格上（可向上伸出 maxH−1 格、左右各 (maxW−1)/2 格），
    // 故钳制区间须按生成常量 DECOR_MARGIN_COLS/ROWS 外扩——否则视口边缘外的
    // 装饰越界段（树冠）会被整块漏绘（旧实现固定 ±1 格对应"2×2 树 + 1 格偏移"）。
    const int minCol = std::max(0, (int)std::floor(g_viewLeft / tileSizeF) - DECOR_MARGIN_COLS);
    const int maxCol = std::min(cols - 1, (int)std::ceil(g_viewRight / tileSizeF) + DECOR_MARGIN_COLS);
    const int minRow = std::max(0, (int)std::floor(g_viewTop / tileSizeF) - DECOR_MARGIN_ROWS);
    const int maxRow = std::min(rows - 1, (int)std::ceil(g_viewBottom / tileSizeF) + DECOR_MARGIN_ROWS);

    // 立体层装饰 / 建筑绘制项缓冲（跨帧复用容量，避免逐帧堆分配；
    // 渲染线程单消费者，见文件头"渲染线程"约定）
    static std::vector<ObjectDecorDrawItem> decorItems;
    static std::vector<BuildingDrawItem> buildingItems;
    static std::vector<float> decorBottomY;
    static std::vector<float> buildingBottomY;
    static std::vector<uint8_t> mergedOrder;
    decorItems.clear();
    buildingItems.clear();
    decorBottomY.clear();
    buildingBottomY.clear();
    mergedOrder.clear();

    for (int row = minRow; row <= maxRow; row++) {
        jint rowBase = row * cols;
        float wy = (float)(row * tileSize);
        for (int col = minCol; col <= maxCol; col++) {
            int tile = static_cast<int>(tiles[rowBase + col]);

            float wx = (float)(col * tileSize);

            // 可见性检测（钳制区间内仍保留——钳制边界含装饰溢出 1 格，地面格用精确检测）
            if (!isRectVisible(wx, wy, tileSizeF, tileSizeF)) continue;

            // (A) 地面底图：逐格绘制（图集 GROUND 精灵，与软件渲染路径同源）。
            //     整图 REPEAT quad 采样异常黑屏（GROUND_QUAD_ENABLED 关闭），
            //     恒走逐格地面
            if (!GROUND_QUAD_ENABLED || g_groundTexId == 0) {
                int gIdx = 0;
                for (int gv = 0; gv < GROUND_VARIANT_COUNT; gv++) {
                    if (tile == GROUND_VARIANTS[gv]) { gIdx = tile; break; }
                }
                if (gIdx < (int)uvCount) {
                    batcher.add(atlasTexId,
                        wx - GAP_EPSILON, wy - GAP_EPSILON,
                        (float)tileSize + 2.0f * GAP_EPSILON,
                        (float)tileSize + 2.0f * GAP_EPSILON,
                        uvs[gIdx * 4] + UV_EPSILON,
                        uvs[gIdx * 4 + 1] + UV_EPSILON,
                        uvs[gIdx * 4 + 2] - UV_EPSILON,
                        uvs[gIdx * 4 + 3] - UV_EPSILON,
                        1.0f, 1.0f, 1.0f, fadeAlpha);
                }
            }

            // (B) 装饰叠加层（草/石/树）
            // 热控降质/装饰关闭/缩放 LOD 时跳过（g_scale 条件经 g_decorLod 门控；
            // 与 Canvas RenderLodPolicy 同阈值 0.6 双端对齐）
            // 显示尺寸/绘制层/实体区间取自生成常量（TextureAtlas.h DECOR_TILE_MIN/MAX
            // + TILE_SPRITE_W/H + TILE_OBJECT_LAYER，源数据 = build-atlas.mjs LAYOUT.tiles）
            //——新增装饰种类只改 LAYOUT，渲染侧零硬编码瓦片序号
            const bool skipDecor = g_decorationsDisabled.load() ||
                                   g_qualityFactor.load() < DECOR_QUALITY_THRESHOLD ||
                                   (g_decorLod.load() && g_scale < DECOR_QUALITY_THRESHOLD);
            if (!skipDecor && tile >= DECOR_TILE_MIN && tile <= DECOR_TILE_MAX &&
                tile < TILE_TYPE_COUNT) {
                int uvIdx = tile;
                if (uvIdx < (int)uvCount) {
                    float u0 = uvs[uvIdx * 4] + UV_EPSILON;
                    float v0 = uvs[uvIdx * 4 + 1] + UV_EPSILON;
                    float u1 = uvs[uvIdx * 4 + 2] - UV_EPSILON;
                    float v1 = uvs[uvIdx * 4 + 3] - UV_EPSILON;

                    // 锚点 = 格底边居中（对象"站在"自己格子上）：显示尺寸按素材纵横比
                    // 取值（小数格），树冠因此向上伸出、草石略高于格——与 Kotlin
                    // SoftwareCanvasBackend.drawGroundRow 及 gamecore/map/draw_order.h
                    // decorDrawRect 同式（双端逐位一致）
                    float geo[4];
                    gamecore::map::decorDrawRect(wx, wy, tileSizeF,
                        TILE_SPRITE_W[tile], TILE_SPRITE_H[tile], geo);
                    if (TILE_OBJECT_LAYER[tile] == 0) {
                        // 地面层（草/石）：跟随地面逐格绘制
                        batcher.add(atlasTexId,
                            geo[0] - GAP_EPSILON,
                            geo[1] - GAP_EPSILON,
                            geo[2] + 2.0f * GAP_EPSILON,
                            geo[3] + 2.0f * GAP_EPSILON,
                            u0, v0, u1, v1,
                            1.0f, 1.0f, 1.0f, fadeAlpha);
                    } else if (decorItems.size() < kMaxObjectDecorItems) {
                        // 立体层（树）：收集后与建筑按地面接触点归并绘制（第 3 段）——
                        // 若仍留在地面层，北侧建筑会把树冠无脑压掉（层序错误）
                        decorItems.push_back(ObjectDecorDrawItem{
                            wy + tileSizeF, geo[0], geo[1], geo[2], geo[3],
                            u0, v0, u1, v1});
                    }
                }
            }
            // (C) 建筑占位格（tile = TILE_BUILDING）：地面已画，建筑精灵由下面的建筑层叠加上去
        }
    }

    // ---- 2. 石板道路层（装饰之上、建筑之下 —— 与 Canvas 侧烘焙顺序一致） ----
    // 逐格合成操作序列由单一权威 gamecore/map/road_compositor.h 产出
    //（主体→描边条→转角件→十字中心；物理下沉——本层只做
    // 操作 → SpriteBatcher 的数据装配，不再持有合成逻辑/UV 硬编码）。
    if (roadData && roadUVMap) {
        jint* roads = env->GetIntArrayElements(roadData, nullptr);
        jfloat* ruvs = env->GetFloatArrayElements(roadUVMap, nullptr);
        jsize roadArrCount = env->GetArrayLength(roadData);
        const jsize roadUVCount = env->GetArrayLength(roadUVMap);
        // 防御：roadUVMap 须容纳 kRoadSpriteCount 组 [u0,v0,u1,v1]（上游 SpriteAtlasDef.ROAD_UV_MAP 恒为 12）
        if ((jsize)rows * cols <= roadArrCount && roadUVCount >= gamecore::map::kRoadSpriteCount * 4) {
            for (int row = minRow; row <= maxRow; row++) {
                float wy = (float)(row * tileSize);
                for (int col = minCol; col <= maxCol; col++) {
                    // roadData 为 1-based 编码（0=非道路，1=单格道路，
                    // 2..16=四邻掩码 1..15）——单格道路存储值为 1 而非 0，
                    // 把存储值直接当掩码会把单格道路当非道路格跳过
                    //（玩家放置的第一格无邻居 → 永不显示）
                    const int raw = roads[row * cols + col];
                    if (raw == 0) continue;
                    const int mask = raw - 1;
                    float wx = (float)(col * tileSize);
                    if (!isRectVisible(wx, wy, tileSizeF, tileSizeF)) continue;

                    // 单一权威合成器：格内局部整型几何（运行时 tileSize=48，
                    // 4 的倍数下与浮点逐位一致——road_compositor.h 几何约定）
                    gamecore::map::RoadDrawOp ops[gamecore::map::kMaxRoadDrawOpsPerTile];
                    const int opCount = gamecore::map::emitRoadDrawOps(mask, tileSize, ops);
                    for (int i = 0; i < opCount; i++) {
                        const int si = static_cast<int>(ops[i].sprite);
                        // 2.4 flipU 消费：左/上缘条水平镜像（深色描边边朝外）——
                        // 交换 u0/u1（v 不变）即水平镜像采样
                        float ru0 = ruvs[si*4] + UV_EPSILON;
                        float ru1 = ruvs[si*4+2] - UV_EPSILON;
                        if (ops[i].flipU) {
                            float tmp = ru0; ru0 = ru1; ru1 = tmp;
                        }
                        batcher.add(atlasTexId,
                            wx + (float)ops[i].x, wy + (float)ops[i].y,
                            (float)ops[i].w, (float)ops[i].h,
                            ru0, ruvs[si*4+1]+UV_EPSILON,
                            ru1, ruvs[si*4+3]-UV_EPSILON,
                            1.0f, 1.0f, 1.0f, fadeAlpha);
                    }
                }
            }
        }
        env->ReleaseIntArrayElements(roadData, roads, JNI_ABORT);
        env->ReleaseFloatArrayElements(roadUVMap, ruvs, JNI_ABORT);
    }

    // ---- 3. 建筑层 + 立体层装饰（同一画家序归并） ----
    // 层序键 = 地面接触点（底边 Y）；同键时建筑在后（装饰被覆盖，见
    // gamecore/map/draw_order.h）。立体装饰（树）若留在地面层，北侧建筑会把
    // 树冠无脑压掉——归并后树冠正确地"压住"其前方（更靠南）的建筑底段。
    jfloat* buildings = nullptr;
    jfloat* buvs = nullptr;
    jsize buvCount = 0;

    if (buildingVisible && buildingData && buildingUVMap && buildingCount > 0) {
        buildings = env->GetFloatArrayElements(buildingData, nullptr);
        buvs = env->GetFloatArrayElements(buildingUVMap, nullptr);
        buvCount = env->GetArrayLength(buildingUVMap) / 4;

        // buildingCount 与数组长度取小（防御上游不一致的越界读）
        const jsize buildingArrCount = env->GetArrayLength(buildingData) / 5;
        const int effectiveCount = (int)std::min((jsize)buildingCount, buildingArrCount);

        static const int FP_COUNT = sizeof(FP_W) / sizeof(FP_W[0]);

        for (int i = 0; i < effectiveCount; i++) {
            int idx = i * 5;
            float gx = buildings[idx];
            float gy = buildings[idx + 1];
            float sw = buildings[idx + 2];   // 精灵宽度（比例尺寸，可能大于占地）
            float sh = buildings[idx + 3];   // 精灵高度
            int nameIdx = static_cast<int>(buildings[idx + 4]);

            // 固定结构（宗门入口门楼/阶梯）nameIdx ≥ STRUCTURE_NAME_BASE：
            // 占地来自 STRUCTURE_FP_W/H 表（供精灵底部对齐）；建筑走 FP_W/H
            const bool isStructure = nameIdx >= STRUCTURE_NAME_BASE;
            int fpW, fpH;
            if (isStructure) {
                const int si = nameIdx - STRUCTURE_NAME_BASE;
                const int sfpCount = (int)(sizeof(STRUCTURE_FP_W) / sizeof(STRUCTURE_FP_W[0]));
                if (si >= 0 && si < sfpCount) { fpW = STRUCTURE_FP_W[si]; fpH = STRUCTURE_FP_H[si]; }
                else { fpW = 2; fpH = 2; }
            } else if (nameIdx >= 0 && nameIdx < FP_COUNT) {
                fpW = FP_W[nameIdx];
                fpH = FP_H[nameIdx];
            } else {
                fpW = 2; fpH = 2;
            }

            // 精灵底部对齐于占地网格：offsetX 居中，offsetY 底部对齐
            float offsetX = (fpW - sw) * tileSize * 0.5f;
            float offsetY = (fpH - sh) * tileSize; // 底部对齐
            float px = gx * tileSize + offsetX;
            float py = gy * tileSize + offsetY;
            float pw = sw * tileSize;
            float ph = sh * tileSize;

            // 地砖使用占地尺寸
            float ftPx = gx * tileSize;
            float ftPy = gy * tileSize;
            float ftPw = fpW * tileSize;
            float ftPh = fpH * tileSize;

            // 可见性检测（使用精灵尺寸）
            if (!isRectVisible(px, py, pw, ph)) continue;

            int buvIdx = nameIdx;
            // 负 nameIdx 会负索引越界读，须与上界一并钳制
            if (buvIdx < 0 || buvIdx >= (int)buvCount) buvIdx = 0;

            // 地面接触点（占地底边）——归并序键；上游数组已按 gridY + height 升序
            //（Kotlin buildBuildingDataArray Y-sorting），本函数保序收集即可
            buildingItems.push_back(BuildingDrawItem{
                (gy + fpH) * tileSizeF,
                px, py, pw, ph,
                ftPx, ftPy, ftPw, ftPh,
                buvIdx,
                g_buildingShadows.load() && !isStructure});
        }

        // 归并绘制序：立体层装饰 + 可见建筑（两组各自已按底边 Y 升序）
        const size_t itemCount = decorItems.size() + buildingItems.size();
        mergedOrder.resize(itemCount);
        if (itemCount > 0) {
            decorBottomY.clear();
            decorBottomY.reserve(decorItems.size());
            for (const ObjectDecorDrawItem& d : decorItems) decorBottomY.push_back(d.bottomY);
            buildingBottomY.clear();
            buildingBottomY.reserve(buildingItems.size());
            for (const BuildingDrawItem& b : buildingItems) buildingBottomY.push_back(b.bottomY);
            gamecore::map::mergeObjectLayerOrder(
                decorBottomY.data(), (int)decorBottomY.size(),
                buildingBottomY.data(), (int)buildingBottomY.size(),
                mergedOrder.data());
        }

        size_t di = 0;
        size_t bi = 0;
        for (size_t k = 0; k < mergedOrder.size(); k++) {
            if (mergedOrder[k] == 0) {
                const ObjectDecorDrawItem& d = decorItems[di++];
                batcher.add(atlasTexId, d.x, d.y, d.w, d.h, d.u0, d.v0, d.u1, d.v1,
                    1.0f, 1.0f, 1.0f, fadeAlpha);
                continue;
            }
            const BuildingDrawItem& b = buildingItems[bi++];
            // (A2) 建筑投影阴影（精灵之下，绘制顺序保证阴影被精灵覆盖）
            // 半透明黑 quad + 右下偏移 0.25 格（textureId=0 = 白色纹理 × 顶点色）
            // 坐标/常量与 BuildingRenderGeometry.shadowRect 同数学（双端一致）
            // 固定结构（门楼/阶梯）不投影——避免阴影压到阶梯/地图底边外
            if (b.shadow) {
                float shx = b.floorX + tileSize * SHADOW_OFFSET_TILES;
                float shy = b.floorY + tileSize * SHADOW_OFFSET_TILES;
                batcher.add(0, shx, shy, b.floorW, b.floorH,
                    0.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, SHADOW_ALPHA * fadeAlpha);
            }
            // (B) 建筑精灵
            batcher.add(atlasTexId, b.x, b.y, b.w, b.h,
                buvs[b.uvIndex * 4] + UV_EPSILON,
                buvs[b.uvIndex * 4 + 1] + UV_EPSILON,
                buvs[b.uvIndex * 4 + 2] - UV_EPSILON,
                buvs[b.uvIndex * 4 + 3] - UV_EPSILON,
                1.0f, 1.0f, 1.0f, fadeAlpha);
        }
    } else if (!decorItems.empty()) {
        // 建筑层关闭/无建筑：立体装饰仍须绘制（单独成序——已按行序升序收集）
        for (const ObjectDecorDrawItem& d : decorItems) {
            batcher.add(atlasTexId, d.x, d.y, d.w, d.h, d.u0, d.v0, d.u1, d.v1,
                1.0f, 1.0f, 1.0f, fadeAlpha);
        }
    }

    // ---- 3. 灵田作物层（建筑精灵之上——作物浮在灵田建筑上） ----
    // 数据 [gx, gy, progress01] × N；阶段索引 + 阶段内淡化 alpha 与
    // Kotlin SpiritCropRender 同数学（阶段边界 1/3、2/3，crossfade=(p-stage/3)×3）
    if (cropData && cropUVMap) {
        jfloat* crops = env->GetFloatArrayElements(cropData, nullptr);
        jfloat* cuvs = env->GetFloatArrayElements(cropUVMap, nullptr);
        jsize cuvCount = env->GetArrayLength(cropUVMap) / 4;
        jsize cropCount = env->GetArrayLength(cropData) / 3;

        // 插值消费链：上一帧作物原始进度（key = gx/gy 网格编码）——
        // 插值基准必须存原始逻辑值（存平滑值会累积漂移），平滑值仅用于绘制；
        // 状态为文件级 static（g_lastCropProgress/g_activeCropKeys），
        // shutdownRenderer 清空防 surface 代际残留
        for (int i = 0; i < cropCount; i++) {
            int idx = i * 3;
            float gx = crops[idx];
            float gy = crops[idx + 1];
            float progress = crops[idx + 2];

            // NaN/越界防御：非法进度 → 跳过（Kotlin 侧 clamp 后必为合法值，
            // 此处为数据篡改防御层——不画任何像素）
            if (progress != progress || progress < 0.0f || progress > 1.0f) continue;
            // gx/gy NaN 会产生 NaN 顶点（isRectVisible 对 NaN
            // 恒返回可见，GPU 对 NaN 顶点行为未定义）——与 Canvas 侧同式防御
            if (gx != gx || gy != gy) continue;

            // 帧间平滑（与 Kotlin SpiritCropRender.smoothedProgress 同数学：
            // draw = prev + (cur - prev) × frameAlpha；插值基准存原始 cur）
            // 范围检查：超 int 范围的
            // float 转 int 是 UB（实践 INT_MIN 饱和）→ 多值收敛同 key 串扰；
            // 真实网格坐标为小整数，1e6 上限远大于任何合法地图
            if (gx < -1e6f || gx > 1e6f || gy < -1e6f || gy > 1e6f) continue;
            const int64_t key = (static_cast<int64_t>(static_cast<int>(gx)) << 32) |
                                static_cast<int64_t>(static_cast<int>(gy));
            g_activeCropKeys.push_back(key);
            float drawProgress = progress;
            const auto prevIt = g_lastCropProgress.find(key);
            if (prevIt != g_lastCropProgress.end()) {
                const float prev = prevIt->second;
                // frameAlpha 防御：NaN 比较恒 false
                // 会穿透 clamp——显式 isNaN 拦截为 0（无插值 = 直接用当前进度）
                float a = frameAlpha;
                if (a != a) {
                    a = 0.0f;
                } else if (a < 0.0f) {
                    a = 0.0f;
                } else if (a > 1.0f) {
                    a = 1.0f;
                }
                drawProgress = prev + (progress - prev) * a;
                if (drawProgress < 0.0f) drawProgress = 0.0f;
                if (drawProgress > 1.0f) drawProgress = 1.0f;
            }
            g_lastCropProgress[key] = progress;

            int stage;
            float alpha;
            if (drawProgress < 1.0f / 3.0f) {
                stage = 0;
                alpha = drawProgress * 3.0f;
            } else if (drawProgress < 2.0f / 3.0f) {
                stage = 1;
                alpha = (drawProgress - 1.0f / 3.0f) * 3.0f;
            } else {
                stage = 2;
                alpha = (drawProgress - 2.0f / 3.0f) * 3.0f;
            }
            if (stage >= (int)cuvCount) continue;

            float px = gx * tileSize;
            float py = gy * tileSize;
            if (!isRectVisible(px, py, (float)tileSize, (float)tileSize)) continue;

            batcher.add(atlasTexId, px, py, (float)tileSize, (float)tileSize,
                cuvs[stage * 4] + UV_EPSILON,
                cuvs[stage * 4 + 1] + UV_EPSILON,
                cuvs[stage * 4 + 2] - UV_EPSILON,
                cuvs[stage * 4 + 3] - UV_EPSILON,
                1.0f, 1.0f, 1.0f, alpha * fadeAlpha);
        }

        // 帧末裁剪：无作物的格清除残留进度条目（收获/拆除场景）。
        // 复杂度 O(n×m)（std::find 嵌套）；
        // 上界 = 同屏作物数（有界且小），未来作物 >500 时需改 unordered_set 查重。
        // 代际残留已由 shutdownRenderer 清空（状态破坏者#1）——本裁剪只负责
        // 同代内的收获/拆除清理。
        for (auto it = g_lastCropProgress.begin(); it != g_lastCropProgress.end();) {
            const bool active = std::find(g_activeCropKeys.begin(), g_activeCropKeys.end(),
                it->first) != g_activeCropKeys.end();
            if (!active) {
                it = g_lastCropProgress.erase(it);
            } else {
                ++it;
            }
        }
        g_activeCropKeys.clear();

        env->ReleaseFloatArrayElements(cropData, crops, JNI_ABORT);
        env->ReleaseFloatArrayElements(cropUVMap, cuvs, JNI_ABORT);
    }

    // ---- 3.5 云层（世界顶部动态云朵——建筑/作物之上、UI 之下） ----
    // 实例数据由 Kotlin CloudLayerAnimator 逐帧生成（只在世界外生成/穿越/出界消失，
    // 速度 3 格/秒，尺寸为原生 rect × 0.4~0.8 缩放）；本段只消费快照，
    // 与 Canvas 侧 drawClouds 同一份数据保证双端一致。
    if (cloudData && cloudUVMap) {
        jfloat* clouds = env->GetFloatArrayElements(cloudData, nullptr);
        jfloat* cuvs = env->GetFloatArrayElements(cloudUVMap, nullptr);
        jsize cuvCount = env->GetArrayLength(cloudUVMap) / 4;
        jsize cloudCount = env->GetArrayLength(cloudData) / CLOUD_DATA_STRIDE;

        // 热控降质/装饰关闭/缩放 LOD 时跳过（与装饰层同判定——云层属装饰性环境动画）
        const bool skipClouds = g_decorationsDisabled.load() ||
                                g_qualityFactor.load() < DECOR_QUALITY_THRESHOLD ||
                                (g_decorLod.load() && g_scale < DECOR_QUALITY_THRESHOLD);
        if (!skipClouds) {
            for (int i = 0; i < cloudCount; i++) {
                int idx = i * CLOUD_DATA_STRIDE;
                float cx = clouds[idx];
                float cy = clouds[idx + 1];
                float cw = clouds[idx + 2];
                float ch = clouds[idx + 3];
                float alpha = clouds[idx + 5];

                // NaN/非法值防御（数据篡改层——非法实例不画任何像素；
                // alpha 用显式 NaN 判定：NaN 比较恒 false 会穿透区间检查）
                if (cx != cx || cy != cy || cw != cw || ch != ch) continue;
                if (cw <= 0.0f || ch <= 0.0f) continue;
                if (alpha != alpha || alpha < 0.0f || alpha > 1.0f) continue;
                if (cx < -1e6f || cx > 1e6f || cy < -1e6f || cy > 1e6f) continue;

                if (!isRectVisible(cx, cy, cw, ch)) continue;

                // spriteIndex → UV 索引（NaN/负值/越界统一回退 0，仿 crop 段防御风格）
                float spriteF = clouds[idx + 4];
                int uvIdx;
                if (!(spriteF >= 0.0f && spriteF < (float)cuvCount)) {
                    uvIdx = 0;
                } else {
                    uvIdx = static_cast<int>(spriteF);
                }

                batcher.add(atlasTexId, cx, cy, cw, ch,
                    cuvs[uvIdx * 4] + UV_EPSILON,
                    cuvs[uvIdx * 4 + 1] + UV_EPSILON,
                    cuvs[uvIdx * 4 + 2] - UV_EPSILON,
                    cuvs[uvIdx * 4 + 3] - UV_EPSILON,
                    1.0f, 1.0f, 1.0f, alpha * fadeAlpha);
            }
        }

        env->ReleaseFloatArrayElements(cloudData, clouds, JNI_ABORT);
        env->ReleaseFloatArrayElements(cloudUVMap, cuvs, JNI_ABORT);
    }

    // ---- 4. 提交合并后的图集绘制 ----
    int vertCount = batcher.end();
    if (batcher.droppedSprites > 0) {
        logBatcherOverflowOncePerSecond("map", batcher.droppedSprites);
    }
    if (vertCount > 0) {
        g_renderer->draw(batcher.vertices, vertCount,
                         static_cast<uint32_t>(atlasTexId));
    }

    // 释放 JNI 数组
    env->ReleaseIntArrayElements(tileData, tiles, JNI_ABORT);
    env->ReleaseFloatArrayElements(uvMap, uvs, JNI_ABORT);
    if (buildings) env->ReleaseFloatArrayElements(buildingData, buildings, JNI_ABORT);
    if (buvs) env->ReleaseFloatArrayElements(buildingUVMap, buvs, JNI_ABORT);
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
//   - UV 逐条目携带，**不再**加 UV_EPSILON——该常量按 4096 图集纹素推导
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
    constexpr int32_t kStride = 10;
    const jsize pieceCount = env->GetArrayLength(cliffData) / kStride;
    if (pieceCount <= 0 || g_cliffTexCount <= 0) return;

    jfloat* data = env->GetFloatArrayElements(cliffData, nullptr);
    if (data == nullptr) return;

    // 观测锚点（进程内一次）：确认 C++ 侧消费到布局数据（真机排查按此过滤）
    static bool s_islandCliffLogged = false;
    if (!s_islandCliffLogged) {
        s_islandCliffLogged = true;
        LOGI("drawIslandCliffs: %d pieces (textures=%d)", (int)pieceCount, (int)g_cliffTexCount);
    }

    // ★ 地图淡入 alpha（本帧单次读取——所有 add 共用同一值，与瓦片层同式）
    const float fadeAlpha = g_fadeAlpha.load();

    // 瓦片几何扩展因子（复用 drawAllTiles 同式：每边扩展 0.5 屏幕像素防裂缝）
    const float EPS_SCALE = (g_scale > 0.001f) ? g_scale : 1.0f;
    const float GAP_EPSILON = 0.5f / EPS_SCALE;

    // 跨帧复用构建器：崖壁层与地图层各用独立 static，互不串批
    SpriteBatcher& batcher = g_edgeBatcher;
    batcher.begin(g_projMatrix);

    uint32_t batchTexId = 0;
    bool haveBatch = false;

    // 按条目遍历；纹理切换处切分批次（布局按池分组产出，切换极少）
    for (jsize i = 0; i < pieceCount; i++) {
        const int32_t base = static_cast<int32_t>(i) * kStride;
        const int32_t texIdx = static_cast<int32_t>(data[base]);
        const float sx = data[base + 1];
        const float sy = data[base + 2];
        const float sw = data[base + 3];
        const float sh = data[base + 4];
        float u0 = data[base + 5];
        float v0 = data[base + 6];
        float u1 = data[base + 7];
        float v1 = data[base + 8];

        // 纹理缺失降级（上传失败/越界）→ 跳过该条目，不画白、不崩溃
        if (texIdx < 0 || texIdx >= g_cliffTexCount) continue;
        const uint32_t texId = g_cliffTexIds[texIdx];
        if (texId == 0) continue;

        // NaN/非法值防御（数据篡改层——非法条目不画任何像素；NaN 比较恒 false
        // 会穿透区间检查，须显式判定；与 crop/cloud 段同风格）
        const bool badFloat = (sx != sx) || (sy != sy) || (sw != sw) || (sh != sh) ||
                              (u0 != u0) || (v0 != v0) || (u1 != u1) || (v1 != v1);
        if (badFloat) continue;
        if (sw <= 0.0f || sh <= 0.0f) continue;
        if (!isRectVisible(sx, sy, sw, sh)) continue;

        // 镜像/裁剪归一：批只接受 u0 ≤ u1、v0 ≤ v1 的矩形语义
        // （镜像由 UV 朝向表达，故 flags 不参与绘制决策）
        if (u0 > u1) { const float t = u0; u0 = u1; u1 = t; }
        if (v0 > v1) { const float t = v0; v0 = v1; v1 = t; }
        if (u0 < 0.0f || v0 < 0.0f || u1 > 1.0f || v1 > 1.0f) continue;

        if (!haveBatch || texId != batchTexId) {
            if (haveBatch && batcher.vertexCount > 0) {
                g_renderer->draw(batcher.vertices, batcher.vertexCount, batchTexId);
            }
            batcher.begin(g_projMatrix);
            batchTexId = texId;
            haveBatch = true;
        }

        batcher.add(texId,
            sx - GAP_EPSILON, sy - GAP_EPSILON,
            sw + 2.0f * GAP_EPSILON, sh + 2.0f * GAP_EPSILON,
            u0, v0, u1, v1,
            1.0f, 1.0f, 1.0f, fadeAlpha);
    }

    if (haveBatch && batcher.vertexCount > 0) {
        g_renderer->draw(batcher.vertices, batcher.vertexCount, batchTexId);
    }
    if (batcher.droppedSprites > 0) {
        logBatcherOverflowOncePerSecond("cliff", batcher.droppedSprites);
    }

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

    ktx1::KtxInfo info{};
    if (!ktx1::loadKtx1(bytes.data(), bytes.size(), info)) {
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
    float su0 = u0 + UV_EPSILON, sv0 = v0 + UV_EPSILON;
    float su1 = u1 - UV_EPSILON, sv1 = v1 - UV_EPSILON;
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
