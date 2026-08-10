#include "KtxLoader.h"

#include <android/log.h>
#include <cstring>

#define LOG_TAG "KtxLoader"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace ktx1;

/** 小端读取 uint32（KTX1 规范强制小端，头已验证 endianness） */
static uint32_t readU32(const uint8_t* p) {
    uint32_t v;
    memcpy(&v, p, sizeof(v));
    return v;
}

bool loadKtx1(const uint8_t* fileData, size_t fileSize, KtxInfo& info) {
    if (!fileData || fileSize < HEADER_SIZE) {
        LOGE("loadKtx1: 文件过短或为空 (%zu 字节)", fileSize);
        return false;
    }

    // magic：8 字节 "«KTX 11»"（AB 4B 54 58 20 31 31 BB）
    if (readU32(fileData + MAGIC_OFFSET) != MAGIC0 ||
        readU32(fileData + MAGIC_OFFSET + 4) != MAGIC1) {
        LOGE("loadKtx1: magic 校验失败");
        return false;
    }
    if (readU32(fileData + ENDIANNESS_OFFSET) != ENDIANNESS) {
        LOGE("loadKtx1: endianness 非法");
        return false;
    }
    // 压缩纹理容器：glType/glFormat 必须为 0
    if (readU32(fileData + GL_TYPE_OFFSET) != 0 ||
        readU32(fileData + GL_FORMAT_OFFSET) != 0) {
        LOGE("loadKtx1: 非压缩纹理容器 (glType/glFormat != 0)");
        return false;
    }
    // 只接受本管线产物：ASTC 4x4 LDR
    if (readU32(fileData + GL_INTERNAL_FORMAT_OFFSET) != GL_COMPRESSED_RGBA_ASTC_4x4_KHR) {
        LOGE("loadKtx1: 内部格式非 ASTC_4x4_LDR (0x%x)",
             readU32(fileData + GL_INTERNAL_FORMAT_OFFSET));
        return false;
    }
    // 单层单面单 mip 容器约束
    if (readU32(fileData + PIXEL_DEPTH_OFFSET) != 0 ||
        readU32(fileData + ARRAY_ELEMENTS_OFFSET) != 0 ||
        readU32(fileData + FACES_OFFSET) != 1 ||
        readU32(fileData + MIP_LEVELS_OFFSET) != 1) {
        LOGE("loadKtx1: 容器维度非法 (depth/array/faces/mips)");
        return false;
    }
    // 无 key-value 扩展（本管线不写）
    if (readU32(fileData + KEY_VALUE_BYTES_OFFSET) != 0) {
        LOGE("loadKtx1: 不支持的 key-value 扩展");
        return false;
    }

    const uint32_t width = readU32(fileData + PIXEL_WIDTH_OFFSET);
    const uint32_t height = readU32(fileData + PIXEL_HEIGHT_OFFSET);

    // ASTC 块对齐：宽高必须为 4 的倍数（防越界读取块数据）
    if (width == 0 || height == 0 ||
        width % ASTC_BLOCK != 0 || height % ASTC_BLOCK != 0) {
        LOGE("loadKtx1: 尺寸非法 %ux%u（需 4 的倍数）", width, height);
        return false;
    }
    // 尺寸上限（对抗性审查 M2：防 32 位 size_t 几何推导回绕绕过校验 + 越限 extent）
    if (width > MAX_TEXTURE_DIMENSION || height > MAX_TEXTURE_DIMENSION) {
        LOGE("loadKtx1: 尺寸超上限 %ux%u（上限 %u）", width, height, MAX_TEXTURE_DIMENSION);
        return false;
    }

    // dataSize 几何推导（块数 × 16 字节），与 build-atlas.mjs 同式。
    // 64 位算术：32 位 size_t 下 (1G/4)*(4/4)*16 回绕为 0 会绕过 dataSize 校验（M2）
    const uint64_t expectedDataSize =
        (uint64_t)(width / ASTC_BLOCK) * (uint64_t)(height / ASTC_BLOCK) * ASTC_BLOCK_BYTES;

    // mip0 数据段：[dataSize 4 字节][数据]——dataSize 字段必须完整在文件内且等于推导值
    const size_t dataSizeFieldOffset = HEADER_SIZE;
    const size_t dataOffset = dataSizeFieldOffset + DATA_SIZE_FIELD;
    if (fileSize < dataOffset) {
        // 对抗性审查 M1：fileSize ∈ {65,66,67} 时原条件不拦截，
        // readU32(fileData+64) 越界读 1-3 字节
        LOGE("loadKtx1: 文件缺少 dataSize 字段 (file=%zu)", fileSize);
        return false;
    }
    const uint32_t storedDataSize = readU32(fileData + dataSizeFieldOffset);
    if (storedDataSize != expectedDataSize) {
        LOGE("loadKtx1: dataSize 不一致 stored=%u expected=%llu",
             storedDataSize, (unsigned long long)expectedDataSize);
        return false;
    }
    // 精确尺寸校验：数据段必须恰好结束于文件尾（防尾随字节注入——
    // 与 AtlasManifestSyncTest 的精确总尺寸断言同式）
    if ((uint64_t)dataOffset + storedDataSize != (uint64_t)fileSize) {
        LOGE("loadKtx1: 数据段尺寸不精确 (offset=%zu size=%u file=%zu)",
             dataOffset, storedDataSize, fileSize);
        return false;
    }

    info.data = fileData + dataOffset;
    info.dataSize = storedDataSize;
    info.width = width;
    info.height = height;
    info.internalFormat = GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
    return true;
}
