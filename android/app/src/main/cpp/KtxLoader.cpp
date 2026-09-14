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
    // 单层单 面 Container 约束（mip 层级 >= 1，B.1 支持多 mip）
    const uint32_t mipLevels = readU32(fileData + MIP_LEVELS_OFFSET);
    if (readU32(fileData + PIXEL_DEPTH_OFFSET) != 0 ||
        readU32(fileData + ARRAY_ELEMENTS_OFFSET) != 0 ||
        readU32(fileData + FACES_OFFSET) != 1 ||
        mipLevels < 1) {
        LOGE("loadKtx1: 容器维度非法 (depth/array/faces/mips=%u)", mipLevels);
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
    // 尺寸上限（防 32 位 size_t 几何推导回绕绕过校验 + 越限 extent）
    if (width > MAX_TEXTURE_DIMENSION || height > MAX_TEXTURE_DIMENSION) {
        LOGE("loadKtx1: 尺寸超上限 %ux%u（上限 %u）", width, height, MAX_TEXTURE_DIMENSION);
        return false;
    }

    // 逐级 dataSize 几何推导（每级 mip 的块数 × 16 字节），与 build-atlas.mjs / lib/ktx1.mjs 同式。
    // 64 位算术：32 位 size_t 下 (1G/4)*(4/4)*16 回绕为 0 会绕过 dataSize 校验（M2）。
    // 每级尺寸 = max(ASTC_BLOCK, base >> level)；到 4×4 块下限为止（与 build-atlas.mjs
    // generateMips 停止条件一致）。
    //
    // ★ 块数用**向上取整**：非 2 的幂尺寸（如 1176×3552 的 mip2 = 294×888、
    //   mip3 = 147×444）末行/末列不足一块时编码器补齐整块，块数 = ceil(dim/4)。
    //   用整除会在 147 这类非 4 倍数级上少算一块 → 拒绝合法纹理
    //   （与 lib/ktx1.mjs wrapKtx1 的期望值必须同式；图集为 2 的幂，两者等价）。

    // mip0 数据区：header 后为逐级 [dataSize 4 字节][数据]。
    // 先累计所有 mip 数据区总长（含各层 size4 前缀），再精确校验 = 文件末尾。
    const size_t dataRegionStart = HEADER_SIZE;
    size_t cursor = dataRegionStart;
    for (uint32_t i = 0; i < mipLevels; i++) {
        uint32_t lw = width >> i;
        uint32_t lh = height >> i;
        if (lw < ASTC_BLOCK) lw = ASTC_BLOCK;
        if (lh < ASTC_BLOCK) lh = ASTC_BLOCK;
        // 块数向上取整（末行/末列不足一块由编码器补齐整块）
        const uint32_t blocksX = (lw + ASTC_BLOCK - 1) / ASTC_BLOCK;
        const uint32_t blocksY = (lh + ASTC_BLOCK - 1) / ASTC_BLOCK;
        const uint64_t levelData =
            (uint64_t)blocksX * (uint64_t)blocksY * ASTC_BLOCK_BYTES;

        // 每个 mip 的 [dataSize 4 字节] 字段必须完整在文件内
        if (fileSize < cursor + DATA_SIZE_FIELD) {
            LOGE("loadKtx1: 文件缺少 mip %u 的 dataSize 字段 (file=%zu)", i, fileSize);
            return false;
        }
        const uint32_t storedSize = readU32(fileData + cursor);
        if (storedSize != levelData) {
            LOGE("loadKtx1: mip %u dataSize 不一致 stored=%u expected=%llu",
                 i, storedSize, (unsigned long long)levelData);
            return false;
        }
        cursor += DATA_SIZE_FIELD + storedSize;
    }
    // 精确尺寸校验：数据区必须恰好结束于文件尾（防尾随字节注入——
    // 与 AtlasManifestSyncTest 的精确总尺寸断言同式）
    if ((uint64_t)cursor != (uint64_t)fileSize) {
        LOGE("loadKtx1: 数据区尺寸不精确 (cursor=%zu file=%zu)", cursor, fileSize);
        return false;
    }

    info.data = fileData + dataRegionStart;
    info.dataSize = cursor - dataRegionStart;
    info.width = width;
    info.height = height;
    info.mipCount = mipLevels;
    info.internalFormat = GL_COMPRESSED_RGBA_ASTC_4x4_KHR;
    return true;
}
