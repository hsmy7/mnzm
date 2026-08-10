#pragma once

#include <cstddef>
#include <cstdint>

// ============================================================
// KtxLoader — KTX1 单 mip 压缩纹理容器解析（WP7 ASTC 图集）
//
// 输入：assets/atlas/atlas_astc.ktx（由 scripts/build-atlas.mjs 生成，
//       astcenc -cl 4x4 -medium 压缩 + 64 字节 KTX1 头封装）
// 输出：数据段指针 + 尺寸 + 宽高 + 内部格式，全部字段校验通过才成功。
//
// 失败语义：返回 false（Kotlin 侧回退 RGBA 图集路径，视觉零差异）。
// 校验清单（对抗性审查：损坏 KTX 必须被检测，不允许半解析成功）：
//   - magic "«KTX 11»" / endianness 0x04030201
//   - glType == 0 && glFormat == 0（压缩纹理容器）
//   - glInternalFormat == 0x93B0（ASTC 4x4 LDR——只接受本管线产物）
//   - faces == 1 && mipLevels == 1 && depth == 0 && array == 0
//   - 宽高 > 0 且为 4 的倍数（ASTC 块对齐）
//   - dataSize == 块数 × 16（几何推导，防头数据不一致）
// ============================================================

// KTX1 头字段布局（64 字节，小端）
namespace ktx1 {
constexpr size_t HEADER_SIZE = 64;
constexpr size_t MAGIC_OFFSET = 0;
constexpr size_t ENDIANNESS_OFFSET = 8;
constexpr size_t GL_TYPE_OFFSET = 12;
constexpr size_t GL_TYPE_SIZE_OFFSET = 16;
constexpr size_t GL_FORMAT_OFFSET = 20;
constexpr size_t GL_INTERNAL_FORMAT_OFFSET = 24;
constexpr size_t GL_BASE_INTERNAL_FORMAT_OFFSET = 28;
constexpr size_t PIXEL_WIDTH_OFFSET = 32;
constexpr size_t PIXEL_HEIGHT_OFFSET = 36;
constexpr size_t PIXEL_DEPTH_OFFSET = 40;
constexpr size_t ARRAY_ELEMENTS_OFFSET = 44;
constexpr size_t FACES_OFFSET = 48;
constexpr size_t MIP_LEVELS_OFFSET = 52;
constexpr size_t KEY_VALUE_BYTES_OFFSET = 56;

// GL 常量（与 build-atlas.mjs wrapKtx1 写入值一致）
constexpr uint32_t MAGIC0 = 0x58544BAB;  // "«KTX" 小端读
constexpr uint32_t MAGIC1 = 0xBB313120;  // " 11»" 小端读
constexpr uint32_t ENDIANNESS = 0x04030201;
constexpr uint32_t GL_COMPRESSED_RGBA_ASTC_4x4_KHR = 0x93B0;
constexpr uint32_t GL_RGBA = 0x1908;
constexpr uint32_t ASTC_BLOCK = 4;
constexpr size_t ASTC_BLOCK_BYTES = 16;

// 每个 mip 层的数据前缀：[dataSize 4 字节][数据]
constexpr size_t DATA_SIZE_FIELD = 4;

// 纹理尺寸上限（对抗性审查 M2：32 位 size_t 下几何推导可回绕绕过校验；
// 上限同时防异常驱动收到越限 extent——Vulkan maxImageDimension2D 常见 8192/16384）
constexpr uint32_t MAX_TEXTURE_DIMENSION = 16384;
}  // namespace ktx1

/** 解析结果（仅在 loadKtx1 返回 true 时有意义） */
struct KtxInfo {
    const uint8_t* data = nullptr;  // 数据段起始（指向输入缓冲内部，调用方生命周期内有效）
    size_t dataSize = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t internalFormat = 0;
};

/**
 * 解析并校验 KTX1 容器。全字段校验通过返回 true 并填充 info；
 * 任一字段非法返回 false（调用方走回退路径，不抛异常）。
 */
bool loadKtx1(const uint8_t* fileData, size_t fileSize, KtxInfo& info);
