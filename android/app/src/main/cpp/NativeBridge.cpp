#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cstring>
#include "Renderer2D.h"
#include "VulkanBackend.h"
#include "TextureAtlas.h"
#include "SpriteBatcher.h"

// ============================================================
// NativeBridge — JNI 入口点
// Kotlin 端包名: com.xianxia.sect.core.nativebridge.NativeBridge
// ============================================================

static Renderer2D* g_renderer = nullptr;
static TextureAtlas* g_atlas = nullptr;
static float g_projMatrix[16]{};

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
// 渲染器生命周期
// ============================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_initRenderer(
    JNIEnv* env, jobject /*thiz*/,
    jint viewportW, jint viewportH,
    jint worldW, jint worldH, jint tileSize,
    jobject surface) {

    if (g_renderer) {
        g_renderer->shutdown();
        delete g_renderer;
    }

    // 从 Java Surface 对象获取 ANativeWindow
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) return JNI_FALSE;

    RenderConfig config{};
    config.viewportW = viewportW;
    config.viewportH = viewportH;
    config.worldWidth = worldW;
    config.worldHeight = worldH;
    config.tileSize = tileSize;
    config.renderScale = 1.0f;

    g_renderer = new VulkanBackend();
    bool ok = g_renderer->init(config, window);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_shutdownRenderer(
    JNIEnv* /*env*/, jobject /*thiz*/) {

    if (g_renderer) {
        g_renderer->shutdown();
        delete g_renderer;
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
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_drawGround(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jfloat worldW, jfloat worldH, jint textureId,
    jint tilesX, jint tilesY) {

    if (!g_renderer) return;

    float uMax = (float)tilesX;
    float vMax = (float)tilesY;

    SpriteVertex verts[6]{};
    verts[0] = { 0, 0, 0, 0, 1,1,1,1 };
    verts[1] = { worldW, 0, uMax, 0, 1,1,1,1 };
    verts[2] = { 0, worldH, 0, vMax, 1,1,1,1 };
    verts[3] = { worldW, 0, uMax, 0, 1,1,1,1 };
    verts[4] = { worldW, worldH, uMax, vMax, 1,1,1,1 };
    verts[5] = { 0, worldH, 0, vMax, 1,1,1,1 };

    g_renderer->draw(verts, 6, static_cast<uint32_t>(textureId));
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_drawDecor(
    JNIEnv* env, jobject /*thiz*/,
    jintArray tileData, jint cols, jint rows,
    jint firstCol, jint lastCol,
    jint firstRow, jint lastRow,
    jint tileSize,
    jint atlasTexId,
    jfloatArray uvMap) {

    if (!g_renderer || !tileData || !uvMap) return;

    jint* tiles = env->GetIntArrayElements(tileData, nullptr);
    jfloat* uvs = env->GetFloatArrayElements(uvMap, nullptr);
    jsize uvCount = env->GetArrayLength(uvMap) / 4;

    SpriteBatcher batcher;
    batcher.begin(g_projMatrix);

    for (int row = firstRow; row <= lastRow && row < rows; row++) {
        jint rowOffset = row * cols;
        for (int col = firstCol; col <= lastCol && col < cols; col++) {
            int tile = static_cast<int>(tiles[rowOffset + col]);
            if (tile < 1 || tile > 5) continue;  // 跳过 GROUND(0) 和 BUILDING(6)

            int uvIdx = (tile - 1);
            if (uvIdx >= uvCount) continue;

            float u0 = uvs[uvIdx * 4], v0 = uvs[uvIdx * 4 + 1];
            float u1 = uvs[uvIdx * 4 + 2], v1 = uvs[uvIdx * 4 + 3];

            if (tile >= 4) {
                // 树（2×2 格，偏移 (-1,-1)）
                batcher.add(atlasTexId,
                    (col - 1) * tileSize, (row - 1) * tileSize,
                    tileSize * 2.0f, tileSize * 2.0f,
                    u0, v0, u1, v1);
            } else {
                // 草（1×1 格）
                batcher.add(atlasTexId,
                    col * tileSize, row * tileSize,
                    tileSize, tileSize,
                    u0, v0, u1, v1);
            }
        }
    }

    int count = batcher.end();
    if (count > 0) {
        g_renderer->draw(batcher.vertices, count,
                         static_cast<uint32_t>(atlasTexId));
    }

    env->ReleaseIntArrayElements(tileData, tiles, JNI_ABORT);
    env->ReleaseFloatArrayElements(uvMap, uvs, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_xianxia_sect_core_nativebridge_NativeBridge_drawBuildings(
    JNIEnv* env, jobject /*thiz*/,
    jfloatArray buildingData, jint count,
    jint tileSize, jint atlasTexId,
    jfloatArray buildingUVMap) {

    if (!g_renderer || count <= 0) return;

    jfloat* data = env->GetFloatArrayElements(buildingData, nullptr);
    jfloat* uvs = env->GetFloatArrayElements(buildingUVMap, nullptr);

    SpriteBatcher batcher;
    batcher.begin(g_projMatrix);

    for (int i = 0; i < count; i++) {
        // buildingData layout: [gx, gy, gw, gh, nameIdx] per building
        int idx = i * 5;
        float gx = data[idx];
        float gy = data[idx + 1];
        float gw = data[idx + 2];
        float gh = data[idx + 3];
        int nameIdx = static_cast<int>(data[idx + 4]);

        float px = gx * tileSize;
        float py = gy * tileSize;
        float uvBase = nameIdx * 4;

        batcher.add(atlasTexId, px, py,
                    gw * tileSize, gh * tileSize,
                    uvs[static_cast<int>(uvBase)],
                    uvs[static_cast<int>(uvBase) + 1],
                    uvs[static_cast<int>(uvBase) + 2],
                    uvs[static_cast<int>(uvBase) + 3]);
    }

    int vertCount = batcher.end();
    if (vertCount > 0) {
        g_renderer->draw(batcher.vertices, vertCount,
                         static_cast<uint32_t>(atlasTexId));
    }

    env->ReleaseFloatArrayElements(buildingData, data, JNI_ABORT);
    env->ReleaseFloatArrayElements(buildingUVMap, uvs, JNI_ABORT);
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
Java_com_xianxia_sect_core_nativebridge_NativeBridge_submitFrame(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    if (g_renderer) g_renderer->submitFrame();
}

// ============================================================
// 删除旧的 Renderer2D.cpp 内容（如果有冲突）
// 注：Renderer2D.cpp 只包含接口定义，实现全在上方
// ============================================================
