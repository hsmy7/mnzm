#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>
#include <atomic>
#include <android/log.h>
#include "Renderer2D.h"
#include "VulkanBackend.h"
#include "TextureAtlas.h"
#include "SpriteBatcher.h"
#include "KtxLoader.h"
// 建筑占地尺寸查找表（2026-08-01：由 SpriteAtlasDef.kt 生成，禁止手改——
// 运行 ./gradlew generateFootprintHeader 重新生成）
#include "footprint_table.h"

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
static TextureAtlas* g_atlas = nullptr;
static float g_projMatrix[16]{};

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
//   decorationsDisabled || qualityFactor < 0.6f（同 Canvas 帧缓冲 RGB_565 阈值 0.6）
// ============================================================

/** 热控质量因子（0-1，1 = 全质量） */
static std::atomic<float> g_qualityFactor{1.0f};

/** 装饰层关闭标志（热控/省电） */
static std::atomic<bool> g_decorationsDisabled{false};

/** 装饰层跳过阈值（与 Canvas SoftwareCanvasBackend.qualityFactor < 0.6f 对齐） */
static constexpr float DECOR_QUALITY_THRESHOLD = 0.6f;

// ============================================================
// 渲染特性开关（由 setRenderFlags 更新，渲染线程单消费者读）
// 与 Kotlin 侧 RenderFlags 数据类（core:engine）保持一致：
//   buildingShadows  ↔ RenderFlags.buildingShadows
//   selectionHighlight ↔ RenderFlags.selectionHighlight
// 阴影常量与 BuildingRenderGeometry.kt 同值——修改任一侧必须同步另一侧
// ============================================================

/** 建筑投影阴影开关（WP3） */
static std::atomic<bool> g_buildingShadows{true};

/** 选中高亮开关（WP3；当前由 Kotlin 侧 VulkanRenderBackend 消费 host.renderConfig，
 *  此处仅存储以保持双端通道对称，未来 C++ 侧绘制高亮时直接消费） */
static std::atomic<bool> g_selectionHighlight{true};

/** 装饰层缩放 LOD 开关（WP5；与 RenderFlags.decorLod 对应——关闭时 skipDecor
 *  不含 g_scale 条件，行为 = 特性未实现前现状，用于低端设备兜底） */
static std::atomic<bool> g_decorLod{true};

/** 阴影偏移量（格数）：建筑右下偏移 0.25 格（与 BuildingRenderGeometry.SHADOW_OFFSET_TILES 同值） */
static constexpr float SHADOW_OFFSET_TILES = 0.25f;

/** 阴影不透明度（0-1）：半透明黑（与 BuildingRenderGeometry.SHADOW_ALPHA 同值） */
static constexpr float SHADOW_ALPHA = 0.2f;

// ============================================================
// 地图淡入过渡状态（由 setFadeAlpha 更新，渲染线程单消费者读）
// 与 Kotlin 侧 FadeTransition（core:engine）同一数学来源：
//   触发：RenderThread 启动时 NativeSurfaceView.fadeIn()（首次/重入/降级统一）
//   计算：渲染线程每帧 alphaAt(elapsedNs, durationNs)（EaseOutCubic，纯时钟驱动）
//   应用：drawAllTiles 所有 add 的 alpha 乘算；drawRect/drawSprite（预览/高亮）不受影响
// ============================================================

/** 地图淡入 alpha（0-1，1 = 完全不透明） */
static std::atomic<float> g_fadeAlpha{1.0f};

/** 检查世界坐标矩形是否与视口相交（可见性检测） */
static inline bool isRectVisible(float x, float y, float w, float h) {
    // 矩形完全在视口之外才返回 false
    return !(x + w <= g_viewLeft || x >= g_viewRight ||
             y + h <= g_viewTop || y >= g_viewBottom);
}

/** 瓷砖类型常量（与 SectMapTileGenerator.kt 保持一致） */
static constexpr int TILE_GROUND       = 0;
static constexpr int TILE_BUILDING     = 6;

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

    if (g_renderer) {
        delete g_renderer;  // 析构函数自动调 shutdown()，句柄置空后幂等
        g_renderer = nullptr;
    }

    const char* dir = cacheDir ? env->GetStringUTFChars(cacheDir, nullptr) : nullptr;

    auto* vb = new VulkanBackend();
    g_renderer = vb;
    bool ok = vb->initDevice(dir, worldW, worldH, tileSize);

    if (dir) env->ReleaseStringUTFChars(cacheDir, dir);

    if (!ok) {
        LOGE("prewarmDevice failed — will fall back to full init at surface time");
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

/** Phase 2: 初始化 Surface（在 SurfaceView 就绪后调用） */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_initRenderer(
    JNIEnv* env, jobject /*thiz*/,
    jint viewportW, jint viewportH,
    jint worldW, jint worldH, jint tileSize,
    jobject surface) {

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;

    g_worldPixelsW = worldW;
    g_worldPixelsH = worldH;

    if (g_renderer) {
        auto* vb = static_cast<VulkanBackend*>(g_renderer);
        if (vb->isDeviceReady()) {
            // Phase 1 已完成，只需初始化 Surface
            bool ok = vb->initSurface(window, viewportW, viewportH);
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
    config.renderScale = 1.0f;

    bool ok = g_renderer->init(config, window);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_shutdownRenderer(
    JNIEnv* /*env*/, jobject /*thiz*/) {

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
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_resizeRenderer(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint width, jint height) {

    if (!g_renderer) return JNI_FALSE;
    return g_renderer->resize(width, height) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// 纹理上传（接收 Kotlin 端的 ARGB 像素数据）
// ============================================================

extern "C" JNIEXPORT jint JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_uploadTexture(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray pixelData, jint width, jint height) {

    if (!g_renderer) return 0;

    jbyte* pixels = env->GetByteArrayElements(pixelData, nullptr);
    uint32_t id = g_renderer->uploadTexture(pixels, width, height);
    env->ReleaseByteArrayElements(pixelData, pixels, JNI_ABORT);
    return static_cast<jint>(id);
}

// ============================================================
// WP7：压缩图集上传（KTX1 容器 → KtxLoader 全字段校验 → Vulkan ASTC 上传）
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
                static_cast<int>(info.width), static_cast<int>(info.height));
        } else {
            LOGE("uploadCompressedAtlas: 后端不支持压缩上传（非 VulkanBackend），回退 RGBA");
        }
    } else {
        LOGW("uploadCompressedAtlas: KTX 校验失败，回退 RGBA 图集");
    }

    env->ReleaseByteArrayElements(ktxData, bytes, JNI_ABORT);
    return static_cast<jint>(id);
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

    // 对抗性审查 M2：scale=0/NaN → 除零产生 NaN 投影矩阵 → 全屏黑无法恢复。
    // 统一 sanitize 后所有下游（投影矩阵/视口范围/LOD 门控）使用同一安全值
    // （与 Canvas 侧 SoftwareCanvasBackend.sanitizeScale 语义对齐：非法 → 1.0）
    float safeScale = scale;
    if (!(safeScale > 0.001f)) safeScale = 1.0f;  // NaN 比较恒 false 一并拦截

    g_scale = safeScale;
    cameraProjMatrix(g_projMatrix, camX, camY, safeScale, (float)vpW, (float)vpH);
    if (g_renderer) g_renderer->setProjection(g_projMatrix);

    // 记录视口世界坐标范围（供 drawAllTiles 可见性检测使用）
    g_viewLeft   = camX;
    g_viewTop    = camY;
    g_viewRight  = camX + (float)vpW / safeScale;
    g_viewBottom = camY + (float)vpH / safeScale;
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
    jfloatArray floorTileUVMap,  // 建筑 UV 映射 + 地砖 UV 映射
    jfloatArray cropData,        // 灵田作物数据 [gx, gy, progress01] × N（WP6，可 null）
    jfloatArray cropUVMap) {     // 作物 UV 映射 [u0,v0,u1,v1] × 3 阶段（WP6，可 null）

    if (!g_renderer || !tileData || !uvMap) return;

    // ★ 地图淡入 alpha（本帧单次读取——所有 add 共用同一值，避免逐次 atomic load）
    const float fadeAlpha = g_fadeAlpha.load();

    // ★ 瓦片几何扩展因子：每个瓦片扩展 0.5 屏幕像素，消除相邻瓦片间 1px 裂缝。
    // 当 scale 极小（<0.001）时用 scale=1 防止除零。
    const float EPS_SCALE = (g_scale > 0.001f) ? g_scale : 1.0f;
    const float GAP_EPSILON = 0.5f / EPS_SCALE;

    jint* tiles = env->GetIntArrayElements(tileData, nullptr);
    jfloat* uvs = env->GetFloatArrayElements(uvMap, nullptr);
    jsize uvCount = env->GetArrayLength(uvMap) / 4;

    SpriteBatcher batcher;
    batcher.begin(g_projMatrix);

    // ---- 1. 瓦片层 ----
    // 每格先画地面，再装饰叠加上方（建筑格的地面由建筑精灵覆盖）
    // 同一 batcher 中先 add 的先画 → 地面最先画，装饰浮在地面上
    // 地面纹理：默认格用 uvMap[0]，TILE_GROUND_V2(7) 用 uvMap[7]
    static const int TILE_GROUND_V2 = 7;
    // 灵田在图集 BUILDING_NAMES 中的索引 = 2（唯一不画地砖的建筑）
    static const int SPIRIT_FIELD_NAME_INDEX = 2;
    // 灵矿场在图集 BUILDING_NAMES 中的索引 = 0（使用专属地皮覆盖）
    static const int SPIRIT_MINE_NAME_INDEX = 0;
    // 灵矿场地皮覆盖在 floorTileUVMap 中的索引（第5个，index=4）
    static const int SPIRIT_MINE_GROUND_UV_INDEX = 4;

    for (int row = 0; row < rows; row++) {
        jint rowBase = row * cols;
        float wy = (float)(row * tileSize);
        for (int col = 0; col < cols; col++) {
            int tile = static_cast<int>(tiles[rowBase + col]);

            float wx = (float)(col * tileSize);

            // 可见性检测
            if (!isRectVisible(wx, wy, (float)tileSize, (float)tileSize)) continue;

            // (A) 地面底图（所有格子都有）
            // 地面变体格(7)用自身纹理，其他格(0/装饰/建筑)用默认地面纹理uvMap[0]
            int gIdx = (tile == TILE_GROUND_V2) ? 7 : 0;
            // uvCount 是条目数（每组 4 个 float = 1 组 UV），gIdx 直接比较
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

            // (B) 装饰叠加层（草/树）
            // 热控降质/装饰关闭/缩放 LOD 时跳过（WP5 加 g_scale 条件，g_decorLod 门控；
            // 与 Canvas RenderLodPolicy 同阈值 0.6 双端对齐）
            const bool skipDecor = g_decorationsDisabled.load() ||
                                   g_qualityFactor.load() < DECOR_QUALITY_THRESHOLD ||
                                   (g_decorLod.load() && g_scale < DECOR_QUALITY_THRESHOLD);
            if (!skipDecor && tile >= 1 && tile <= 5) {
                int uvIdx = tile;
                if (uvIdx < (int)uvCount) {
                    float u0 = uvs[uvIdx * 4] + UV_EPSILON;
                    float v0 = uvs[uvIdx * 4 + 1] + UV_EPSILON;
                    float u1 = uvs[uvIdx * 4 + 2] - UV_EPSILON;
                    float v1 = uvs[uvIdx * 4 + 3] - UV_EPSILON;

                    if (tile >= 4) {
                        // 树（2×2 格，偏移 (-1,-1)）
                        float treeW = (float)(tileSize * 2);
                        float treeH = (float)(tileSize * 2);
                        batcher.add(atlasTexId,
                            wx - (float)tileSize - GAP_EPSILON,
                            wy - (float)tileSize - GAP_EPSILON,
                            treeW + 2.0f * GAP_EPSILON,
                            treeH + 2.0f * GAP_EPSILON,
                            u0, v0, u1, v1,
                            1.0f, 1.0f, 1.0f, fadeAlpha);
                    } else {
                        // 草（1×1 格）
                        batcher.add(atlasTexId,
                            wx - GAP_EPSILON, wy - GAP_EPSILON,
                            (float)tileSize + 2.0f * GAP_EPSILON,
                            (float)tileSize + 2.0f * GAP_EPSILON,
                            u0, v0, u1, v1,
                            1.0f, 1.0f, 1.0f, fadeAlpha);
                    }
                }
            }
            // (C) 建筑占位格（tile=6）：地面已画，建筑精灵由下面的建筑层叠加上去
        }
    }

    // ---- 2. 建筑层 ----
    jfloat* buildings = nullptr;
    jfloat* buvs = nullptr;
    jsize buvCount = 0;
    jfloat* ftuvs = nullptr;  // 地砖 UV（循环外一次性 pin，防每建筑 2 次 JNI——对抗性审查 L3）
    jsize ftuvCount = 0;

    if (buildingVisible && buildingData && buildingUVMap && buildingCount > 0) {
        buildings = env->GetFloatArrayElements(buildingData, nullptr);
        buvs = env->GetFloatArrayElements(buildingUVMap, nullptr);
        buvCount = env->GetArrayLength(buildingUVMap) / 4;
        if (floorTileUVMap != nullptr) {
            ftuvs = env->GetFloatArrayElements(floorTileUVMap, nullptr);
            ftuvCount = env->GetArrayLength(floorTileUVMap) / 4;
        }

        // 对抗性审查 M1：buildingCount 与数组长度取小（防御上游不一致的越界读）
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

            int fpW = (nameIdx >= 0 && nameIdx < FP_COUNT) ? FP_W[nameIdx] : 2;
            int fpH = (nameIdx >= 0 && nameIdx < FP_COUNT) ? FP_H[nameIdx] : 2;

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
            // 对抗性审查 M3/M4：负 nameIdx 直接负索引越界读（原条件只防上界）
            if (buvIdx < 0 || buvIdx >= (int)buvCount) buvIdx = 0;

            // (A) 地砖底座（灵田除外），按占地尺寸绘制。
            //    灵矿场使用专属地皮覆盖纹理，其他建筑使用通用地砖。
            if (ftuvs != nullptr) {
                int ftIdx = -1;
                if (nameIdx == SPIRIT_MINE_NAME_INDEX) {
                    ftIdx = SPIRIT_MINE_GROUND_UV_INDEX;
                } else if (nameIdx != SPIRIT_FIELD_NAME_INDEX) {
                    // 地砖索引由占地尺寸决定
                    int ftW = fpW, ftH = fpH;
                    if      (ftW == 2 && ftH == 2) ftIdx = 0;
                    else if (ftW == 2 && ftH == 3) ftIdx = 1;
                    else if (ftW == 3 && ftH == 2) ftIdx = 2;
                    else if (ftW == 3 && ftH == 3) ftIdx = 3;
                    // 新占地尺寸映射到最接近的现有地砖
                    else if (ftW == 4 && ftH == 4) ftIdx = 3;  // 方形 → 3x3
                    else if (ftW == 6 && ftH == 4) ftIdx = 2;  // 宽扁 → 3x2
                    else if (ftW == 4 && ftH == 6) ftIdx = 1;  // 窄高 → 2x3
                    else if (ftW == 6 && ftH == 6) ftIdx = 3;  // 大方 → 3x3
                    else if (ftW == 4 && ftH == 8) ftIdx = 1;  // 瘦高 → 2x3
                    else if (ftW == 2 && ftH == 4) ftIdx = 1;  // 窄高 → 2x3
                    else if (ftW == 4 && ftH == 3) ftIdx = 2;  // 宽扁 → 3x2
                    else if (ftW == 6 && ftH == 5) ftIdx = 2;  // 宽扁 → 3x2
                    else if (ftW == 6 && ftH == 3) ftIdx = 2;  // 宽扁 → 3x2
                    else if (ftW == 5 && ftH == 3) ftIdx = 2;  // 宽扁 → 3x2
                }

                if (ftIdx >= 0 && ftIdx < (int)ftuvCount) {
                    batcher.add(atlasTexId, ftPx, ftPy, ftPw, ftPh,
                        ftuvs[ftIdx * 4] + UV_EPSILON,
                        ftuvs[ftIdx * 4 + 1] + UV_EPSILON,
                        ftuvs[ftIdx * 4 + 2] - UV_EPSILON,
                        ftuvs[ftIdx * 4 + 3] - UV_EPSILON,
                        1.0f, 1.0f, 1.0f, fadeAlpha);
                }
            }

            // (A2) 建筑投影阴影（地砖之上、精灵之下，绘制顺序保证阴影被精灵覆盖）
            // 半透明黑 quad + 右下偏移 0.25 格（textureId=0 = 白色纹理 × 顶点色）
            // 坐标/常量与 BuildingRenderGeometry.shadowRect 同数学（双端一致）
            if (g_buildingShadows.load()) {
                float shx = ftPx + tileSize * SHADOW_OFFSET_TILES;
                float shy = ftPy + tileSize * SHADOW_OFFSET_TILES;
                batcher.add(0, shx, shy, ftPw, ftPh,
                    0.0f, 0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, SHADOW_ALPHA * fadeAlpha);
            }

            // (B) 建筑精灵
            batcher.add(atlasTexId, px, py, pw, ph,
                buvs[buvIdx * 4] + UV_EPSILON,
                buvs[buvIdx * 4 + 1] + UV_EPSILON,
                buvs[buvIdx * 4 + 2] - UV_EPSILON,
                buvs[buvIdx * 4 + 3] - UV_EPSILON,
                1.0f, 1.0f, 1.0f, fadeAlpha);
        }
    }

    // ---- 3. 灵田作物层（WP6，建筑精灵之上——作物浮在灵田建筑上） ----
    // 数据 [gx, gy, progress01] × N；阶段索引 + 阶段内淡化 alpha 与
    // Kotlin SpiritCropRender 同数学（阶段边界 1/3、2/3，crossfade=(p-stage/3)×3）
    if (cropData && cropUVMap) {
        jfloat* crops = env->GetFloatArrayElements(cropData, nullptr);
        jfloat* cuvs = env->GetFloatArrayElements(cropUVMap, nullptr);
        jsize cuvCount = env->GetArrayLength(cropUVMap) / 4;
        jsize cropCount = env->GetArrayLength(cropData) / 3;

        for (int i = 0; i < cropCount; i++) {
            int idx = i * 3;
            float gx = crops[idx];
            float gy = crops[idx + 1];
            float progress = crops[idx + 2];

            // NaN/越界防御：非法进度 → 跳过（Kotlin 侧 clamp 后必为合法值，
            // 此处为数据篡改防御层——不画任何像素）
            if (progress != progress || progress < 0.0f || progress > 1.0f) continue;
            // 对抗性审查 L1：gx/gy NaN → NaN 顶点进入绘制（isRectVisible 对 NaN
            // 恒返回可见，GPU 对 NaN 顶点行为未定义）——与 Canvas 侧同式防御
            if (gx != gx || gy != gy) continue;

            int stage;
            float alpha;
            if (progress < 1.0f / 3.0f) {
                stage = 0;
                alpha = progress * 3.0f;
            } else if (progress < 2.0f / 3.0f) {
                stage = 1;
                alpha = (progress - 1.0f / 3.0f) * 3.0f;
            } else {
                stage = 2;
                alpha = (progress - 2.0f / 3.0f) * 3.0f;
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
        env->ReleaseFloatArrayElements(cropData, crops, JNI_ABORT);
        env->ReleaseFloatArrayElements(cropUVMap, cuvs, JNI_ABORT);
    }

    // ---- 4. 提交合并后的图集绘制 ----
    int vertCount = batcher.end();
    if (vertCount > 0) {
        g_renderer->draw(batcher.vertices, vertCount,
                         static_cast<uint32_t>(atlasTexId));
    }

    // 释放 JNI 数组
    env->ReleaseIntArrayElements(tileData, tiles, JNI_ABORT);
    env->ReleaseFloatArrayElements(uvMap, uvs, JNI_ABORT);
    if (buildings) env->ReleaseFloatArrayElements(buildingData, buildings, JNI_ABORT);
    if (buvs) env->ReleaseFloatArrayElements(buildingUVMap, buvs, JNI_ABORT);
    if (ftuvs) env->ReleaseFloatArrayElements(floorTileUVMap, ftuvs, JNI_ABORT);
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
// 删除旧的 Renderer2D.cpp 内容（如果有冲突）
// 注：Renderer2D.cpp 只包含接口定义，实现全在上方
// ============================================================
