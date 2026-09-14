#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

// ============================================================
// 宗门地图地形生成器（瓦片生成的 C++ 单一权威）
//
// Kotlin `core/util/SectMapTileGenerator.kt` 的位级等价移植（对拍红线）。
// C++ 为瓦片生成的单一权威；Kotlin 生成器仅作为 JVM 测试基线 / native
// 通道不可用时的降级路径
// （对拍守护：DiffSectTerrainTest 双端全数组逐位一致）。
//
// ## 与 Kotlin 的位级一致约定
//   - cellHash：Int64 加法回绕 → .toInt() 位截断（uint32 截断等价）→
//     Int 乘法回绕（uint32 乘等价——有符号溢出在 C++ 为 UB，必须走 uint32）。
//   - smoothNoise：Java Float = IEEE binary32、**无 FMA 收缩**；C++ 编译器
//     （arm64 默认 ffp-contract=on）会把 `3f - 2f*fx` 收缩为 fnmadd 产生
//     不同舍入——收缩敏感的中间值经 volatile 局部强制内存往返隔断
//     （每种子一次性 O(W×H) 调用，成本可忽略）。乘加不收缩 + 逐运算
//     左结合书写 = 与 ART 逐位一致（任意平台，含真机 arm64）。
//   - 迭代序：草滩 pass（gx 外层/gy 内层）→ 石堆 pass → 树丛 pass → 边界树环 →
//     门楼清场，与 Kotlin 逐位同序（格间无依赖，序不变为审读口径）。
//
// ## 瓦片索引（单一数据源 = scripts/build-atlas.mjs MAP_SPRITES 序）
//   与 Kotlin SpriteAtlasDef.TileType.index、渲染库 TextureAtlas.h 精灵
//   索引同源；本处独立定义（gamecore 禁依赖代码生成头），漂移由
//   DiffSectTerrainTest（C++/Kotlin 全数组对拍）即红。
//
// ## 与快照协议的关系（WS-5b 地图冻结后的现行口径）
//   **生成即数据**：地形段入 GameData.terrainTiles（行主序 flat，非空才导出
//   键）+ mapGenVersion 版本戳，随存档持久化——"存的地形恒优先"：有段
//   即直接采用（跨版本冻结，不重算）；仅无段（新档/老档回填前）才按
//   mapSeed 调 [generateTileData] 生成（C++ 在 importStateInternal 归一化族
//   ensureTerrainGenerated，Kotlin 在 boot 回填——两端同源确定性）。生成器
//   版本演进时递增 MAP_GEN_VERSION ⇒ 老档老地图永久冻结、新档新地图
//   （无需发版协调）。地形只在该一次性生成/回填点进入状态，此后零写者
//   ⇒ 前向/反向镜像稳态零增量。内存与协议结构面保持 flat 单一表示；
//   RLE 类存储压缩（若引入）仅可存在于存档编码层，不入本模型。
//
// ## 寻路地基
//   NPC 可行走网格的静态地形侧（本头文件产出）+ 建筑占位
//   （state_.gameData.placedBuildings 镜像）+ 道路
//   （gameData.roads）在 C++ 侧齐备；可行走语义（树/边界是否阻塞）属
//   玩法设计文档拍板项，本文件不做前瞻性 API。
// ============================================================

namespace gamecore::map::terrain {

// 瓦片类型（值 = build-atlas.mjs MAP_SPRITES 精灵索引 = Kotlin TileType.index）
enum TileType : int32_t {
    TILE_GROUND = 0,
    TILE_GRASS1 = 1,
    TILE_GRASS2 = 2,
    TILE_GRASS3 = 3,
    TILE_GRASS4 = 4,
    TILE_STONE1 = 5,
    TILE_STONE2 = 6,
    TILE_STONE3 = 7,
    TILE_TREE1 = 8,
    TILE_TREE2 = 9,
    TILE_BUILDING = 10,  // 占位标记值（生成器不产出，渲染层建筑占用标记用）
};

// 宗门入口门楼包围盒（Kotlin GameConfig.SectMap 门楼常量的 C++ 传参形态——
// 单一数据源仍在 Kotlin GameConfig，JNI 入口按值传入，避免双端常量漂移）
struct GateBox {
    int32_t x = 0;        // GATE_X
    int32_t y = 0;        // GATE_Y
    int32_t width = 0;    // GATE_WIDTH
    int32_t height = 0;   // GATE_HEIGHT
    int32_t spriteY = 0;  // GATE_SPRITE_Y（清场起始行）
};

// ── 位置哈希（Kotlin cellHash 位级等价）────────────────────────
//
// Kotlin: val h = (x * 374761393L + y * 668265263L + seed.toLong()).toInt()
//         val hash = h * (h xor (h shl 13))
//         return ((hash and 0x7FFFFFFF) % 10000) / 10000f
inline float cellHash(int32_t x, int32_t y, int32_t seed) {
    const int64_t h64 = static_cast<int64_t>(x) * 374761393LL +
                        static_cast<int64_t>(y) * 668265263LL +
                        static_cast<int64_t>(seed);
    const uint32_t h = static_cast<uint32_t>(h64);   // Kotlin .toInt() 位截断
    const uint32_t mixed = h * (h ^ (h << 13));      // Int 乘法回绕 = uint32 乘
    return static_cast<float>(static_cast<int32_t>(mixed & 0x7FFFFFFFu) % 10000) /
           10000.0f;
}

// ── 平滑噪声（Kotlin smoothNoise 位级等价）──────────────────────
//
// 双线性插值 + smoothstep；scale = 一个"地块"的格数。
inline float smoothNoise(int32_t x, int32_t y, int32_t scale, int32_t seed) {
    const float sx = static_cast<float>(x) / static_cast<float>(scale);
    const float sy = static_cast<float>(y) / static_cast<float>(scale);
    const int32_t ix = static_cast<int32_t>(sx);  // 截断朝零 = Kotlin toInt()
    const int32_t iy = static_cast<int32_t>(sy);
    const float fx = sx - static_cast<float>(ix);
    const float fy = sy - static_cast<float>(iy);

    // smoothstep：volatile 隔断 `3f - 2f*fx` 的 fnmadd 收缩（见文件头位级约定）
    const volatile float sx2Raw = 3.0f - 2.0f * fx;
    const volatile float sy2Raw = 3.0f - 2.0f * fy;
    const float sx2 = fx * fx * sx2Raw;  // (fx*fx) * sx2Raw 左结合
    const float sy2 = fy * fy * sy2Raw;

    const float v00 = cellHash(ix, iy, seed);
    const float v10 = cellHash(ix + 1, iy, seed);
    const float v01 = cellHash(ix, iy + 1, seed);
    const float v11 = cellHash(ix + 1, iy + 1, seed);

    // 逐乘积命名（乘积间相加非 fma 形态，乘积内部无加法——无收缩点），
    // 求和保持 Kotlin 左结合序 ((p00+p10)+p01)+p11
    const float p00 = v00 * (1.0f - sx2) * (1.0f - sy2);
    const float p10 = v10 * sx2 * (1.0f - sy2);
    const float p01 = v01 * (1.0f - sx2) * sy2;
    const float p11 = v11 * sx2 * sy2;
    return ((p00 + p10) + p01) + p11;
}

// ── 草滩 pass（Kotlin placeGrassPatches 等价）──────────────────
inline void placeGrassPatches(std::vector<int32_t>& data, int32_t w, int32_t h,
                              float density, int32_t seed) {
    const float fillRaw = density * 0.80f;
    const float fill = (fillRaw < 0.0f) ? 0.0f : (fillRaw > 1.0f) ? 1.0f : fillRaw;
    const float threshold = 1.0f - fill;
    for (int32_t gx = 0; gx < w; ++gx) {
        for (int32_t gy = 0; gy < h; ++gy) {
            const size_t idx = static_cast<size_t>(gy) * static_cast<size_t>(w) + gx;
            if (data[idx] != TILE_GROUND) continue;
            if (smoothNoise(gx, gy, 8, 42 ^ seed) < threshold) continue;
            if (cellHash(gx, gy, 43 ^ seed) >= 0.80f) continue;
            const float pick = cellHash(gx, gy, 44 ^ seed);
            // Kotlin when 首匹配链：< 0.25 变体1、< 0.50 变体2、< 0.75 变体3、其余变体4
            data[idx] = (pick < 0.25f) ? TILE_GRASS1
                      : (pick < 0.50f) ? TILE_GRASS2
                      : (pick < 0.75f) ? TILE_GRASS3
                                       : TILE_GRASS4;
        }
    }
}

// ── 石堆 pass（Kotlin placeStoneScatter 等价）──────────────────
// 稀疏岩石散布（噪声尺度 14、散布上限 0.35）；只落在裸地——草滩之后运行，
// 岩石成簇出现在空旷地面而不覆盖草/树。
inline void placeStoneScatter(std::vector<int32_t>& data, int32_t w, int32_t h,
                              float density, int32_t seed) {
    const float fillRaw = density * 0.12f;
    const float fill = (fillRaw < 0.0f) ? 0.0f : (fillRaw > 1.0f) ? 1.0f : fillRaw;
    const float threshold = 1.0f - fill;
    for (int32_t gx = 0; gx < w; ++gx) {
        for (int32_t gy = 0; gy < h; ++gy) {
            const size_t idx = static_cast<size_t>(gy) * static_cast<size_t>(w) + gx;
            if (data[idx] != TILE_GROUND) continue;
            if (smoothNoise(gx, gy, 14, 202 ^ seed) < threshold) continue;
            if (cellHash(gx, gy, 203 ^ seed) >= 0.35f) continue;
            const float pick = cellHash(gx, gy, 204 ^ seed);
            // Kotlin when 首匹配链：< 0.34 变体1、< 0.67 变体2、其余变体3
            data[idx] = (pick < 0.34f) ? TILE_STONE1
                      : (pick < 0.67f) ? TILE_STONE2
                                       : TILE_STONE3;
        }
    }
}

// ── 树丛 pass（Kotlin placeTreeClusters 等价）──────────────────
inline void placeTreeClusters(std::vector<int32_t>& data, int32_t w, int32_t h,
                              float density, int32_t seed) {
    const float fillRaw = density * 0.35f;
    const float fill = (fillRaw < 0.0f) ? 0.0f : (fillRaw > 1.0f) ? 1.0f : fillRaw;
    const float threshold = 1.0f - fill;
    for (int32_t gx = 0; gx < w; ++gx) {
        for (int32_t gy = 0; gy < h; ++gy) {
            const size_t idx = static_cast<size_t>(gy) * static_cast<size_t>(w) + gx;
            if (data[idx] != TILE_GROUND) continue;
            if (smoothNoise(gx, gy, 12, 101 ^ seed) < threshold) continue;
            if (cellHash(gx, gy, 45 ^ seed) >= 0.35f) continue;
            data[idx] = (cellHash(gx, gy, 46 ^ seed) < 0.5f) ? TILE_TREE1 : TILE_TREE2;
        }
    }
}

// ── 边界树环（Kotlin placeBorderTrees 等价）────────────────────
// 地图四周 ring 格覆盖为树（棋盘交替），全装饰之后运行、覆盖一切变体。
inline void placeBorderTrees(std::vector<int32_t>& data, int32_t w, int32_t h,
                             int32_t ring) {
    if (ring <= 0) return;
    for (int32_t y = 0; y < h; ++y) {
        for (int32_t x = 0; x < w; ++x) {
            if (x < ring || x >= w - ring || y < ring || y >= h - ring) {
                data[static_cast<size_t>(y) * static_cast<size_t>(w) + x] =
                    ((x + y) % 2 == 0) ? TILE_TREE1 : TILE_TREE2;
            }
        }
    }
}

// ── 门楼清场（Kotlin placeSectGateway 等价）────────────────────
// 底部中央门楼包围盒 ± 左右各 1 列清为地面（门楼精灵由建筑层固定条目渲染）。
// 确定性、与 seed 无关；小地图（尺寸不足）跳过。
inline void placeSectGateway(std::vector<int32_t>& data, int32_t w, int32_t h,
                             const GateBox& gate) {
    const int32_t maxX = gate.x + gate.width;
    const int32_t maxY = gate.y + gate.height;
    const int32_t clearFrom = (gate.x - 1 < 0) ? 0 : gate.x - 1;
    const int32_t clearTo = (maxX + 2 > w) ? w : maxX + 2;
    if (clearFrom >= clearTo || h < maxY) return;
    for (int32_t y = gate.spriteY; y < maxY; ++y) {
        for (int32_t x = clearFrom; x < clearTo; ++x) {
            data[static_cast<size_t>(y) * static_cast<size_t>(w) + x] = TILE_GROUND;
        }
    }
}

// ── 生成入口（Kotlin generateTileData 等价，展平行主序）────────
//
// @param w/h 地图列/行数（格）
// @param density 总装饰密度 [0,1]（Kotlin 默认 0.18f）
// @param seed 世界种子（不同种子不同分布；0 = Kotlin 向后兼容默认）
// @param borderRing 边界树环厚度（0 = 无）
// @param gate 门楼包围盒（清场区；小地图自动跳过）
// @return 行主序展平数组（row*col+col = 格值），size = w*h
inline std::vector<int32_t> generateTileData(int32_t w, int32_t h, float density,
                                             int32_t seed, int32_t borderRing,
                                             const GateBox& gate) {
    std::vector<int32_t> data(static_cast<size_t>(w) * static_cast<size_t>(h),
                              TILE_GROUND);
    placeGrassPatches(data, w, h, density, seed);
    placeStoneScatter(data, w, h, density, seed);
    placeTreeClusters(data, w, h, density, seed);
    if (borderRing > 0) {
        placeBorderTrees(data, w, h, borderRing);
    }
    placeSectGateway(data, w, h, gate);
    return data;
}

}  // namespace gamecore::map::terrain
