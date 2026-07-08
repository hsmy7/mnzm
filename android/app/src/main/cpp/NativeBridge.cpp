#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>
#include <android/log.h>
#include "Renderer2D.h"
#include "VulkanBackend.h"
#include "TextureAtlas.h"
#include "SpriteBatcher.h"

// ============================================================
// 日志宏
// ============================================================

#define LOG_TAG "NativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
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

// 世界像素尺寸（由 initRenderer 设置）
static int g_worldPixelsW = 0;
static int g_worldPixelsH = 0;

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

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_getAtlasUV(
    JNIEnv* env, jobject /*thiz*/, jstring name) {

    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    auto* reg = g_atlas ? g_atlas->getRegion(nameStr) : nullptr;
    env->ReleaseStringUTFChars(name, nameStr);

    if (!reg) return nullptr;

    jfloat uv[4] = { reg->u0, reg->v0, reg->u1, reg->v1 };
    jfloatArray result = env->NewFloatArray(4);
    env->SetFloatArrayRegion(result, 0, 4, uv);
    return result;
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

    cameraProjMatrix(g_projMatrix, camX, camY, scale, (float)vpW, (float)vpH);
    if (g_renderer) g_renderer->setProjection(g_projMatrix);

    // 记录视口世界坐标范围（供 drawAllTiles 可见性检测使用）
    g_viewLeft   = camX;
    g_viewTop    = camY;
    g_viewRight  = camX + (float)vpW / (scale > 0.001f ? scale : 1.0f);
    g_viewBottom = camY + (float)vpH / (scale > 0.001f ? scale : 1.0f);
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
    jfloatArray floorTileUVMap) { // 建筑 UV 映射 + 地砖 UV 映射

    if (!g_renderer || !tileData || !uvMap) return;

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
                batcher.add(atlasTexId, wx, wy,
                    (float)tileSize, (float)tileSize,
                    uvs[gIdx * 4], uvs[gIdx * 4 + 1],
                    uvs[gIdx * 4 + 2], uvs[gIdx * 4 + 3]);
            }

            // (B) 装饰叠加层（草/树）
            if (tile >= 1 && tile <= 5) {
                int uvIdx = tile;
                if (uvIdx < (int)uvCount) {
                    float u0 = uvs[uvIdx * 4], v0 = uvs[uvIdx * 4 + 1];
                    float u1 = uvs[uvIdx * 4 + 2], v1 = uvs[uvIdx * 4 + 3];

                    if (tile >= 4) {
                        // 树（2×2 格，偏移 (-1,-1)）
                        batcher.add(atlasTexId,
                            wx - (float)tileSize, wy - (float)tileSize,
                            (float)(tileSize * 2), (float)(tileSize * 2),
                            u0, v0, u1, v1);
                    } else {
                        // 草（1×1 格）
                        batcher.add(atlasTexId, wx, wy,
                            (float)tileSize, (float)tileSize,
                            u0, v0, u1, v1);
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

    if (buildingVisible && buildingData && buildingUVMap && buildingCount > 0) {
        buildings = env->GetFloatArrayElements(buildingData, nullptr);
        buvs = env->GetFloatArrayElements(buildingUVMap, nullptr);
        buvCount = env->GetArrayLength(buildingUVMap) / 4;

        // 占地尺寸查找表（与 SpriteAtlasDef.FOOTPRINT_BY_NAME_INDEX 对应）
        static const int FP_W[] = {4,4,1,4,6,6,6,4,4,6,6,4,4,4,4,6,6,2};
        static const int FP_H[] = {4,4,1,3,4,5,4,3,3,4,4,3,4,4,4,6,4,2};
        static const int FP_COUNT = sizeof(FP_W) / sizeof(FP_W[0]);

        for (int i = 0; i < buildingCount; i++) {
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
            if (buvIdx >= (int)buvCount) buvIdx = 0;

            // (A) 地砖底座（灵田除外），按占地尺寸绘制
            if (nameIdx != SPIRIT_FIELD_NAME_INDEX && floorTileUVMap != nullptr) {
                // 地砖索引由占地尺寸决定
                int ftIdx = -1;
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

                if (ftIdx >= 0) {
                    jfloat* ftuvs = env->GetFloatArrayElements(floorTileUVMap, nullptr);
                    jsize ftuvCount = env->GetArrayLength(floorTileUVMap) / 4;
                    if (ftIdx < (int)ftuvCount) {
                        batcher.add(atlasTexId, ftPx, ftPy, ftPw, ftPh,
                            ftuvs[ftIdx * 4], ftuvs[ftIdx * 4 + 1],
                            ftuvs[ftIdx * 4 + 2], ftuvs[ftIdx * 4 + 3]);
                    }
                    env->ReleaseFloatArrayElements(floorTileUVMap, ftuvs, JNI_ABORT);
                }
            }

            // (B) 建筑精灵
            batcher.add(atlasTexId, px, py, pw, ph,
                buvs[buvIdx * 4], buvs[buvIdx * 4 + 1],
                buvs[buvIdx * 4 + 2], buvs[buvIdx * 4 + 3]);
        }
    }

    // ---- 3. 提交合并后的图集绘制 ----
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
    verts[0] = { x,   y,   u0, v0, r, g, b, a };
    verts[1] = { x+w, y,   u1, v0, r, g, b, a };
    verts[2] = { x,   y+h, u0, v1, r, g, b, a };
    verts[3] = { x+w, y,   u1, v0, r, g, b, a };
    verts[4] = { x+w, y+h, u1, v1, r, g, b, a };
    verts[5] = { x,   y+h, u0, v1, r, g, b, a };

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
