#pragma once

#include "Renderer2D.h"
#include <unordered_map>
#include <string>

// ============================================================
// TextureAtlas — 精灵图集
// 将所有精灵打包到一张 ASTC 纹理中，通过名称查询 UV 坐标
// ============================================================

struct AtlasRegion {
    float u0, v0, u1, v1;  // 归一化 UV 坐标 [0,1]
    int pixelW, pixelH;     // 原始像素尺寸
};

struct SpriteDef {
    const char* name;
    int x, y, w, h;         // 图集中的像素位置（原始图集坐标）
};

class TextureAtlas {
public:
    TextureAtlas() = default;
    ~TextureAtlas() = default;

    // 定义图集布局（描述每张精灵在图集中的像素位置）
    void defineAtlas(int totalWidth, int totalHeight,
                     const SpriteDef* sprites, int count);

    // 查询 UV 坐标
    const AtlasRegion* getRegion(const char* name) const;

    // 获取图集尺寸
    int width() const { return m_width; }
    int height() const { return m_height; }

private:
    int m_width = 0;
    int m_height = 0;
    std::unordered_map<std::string, AtlasRegion> m_regions;
};

// ============================================================
// 地图精灵图集布局定义
// 单张 2048×2048 ASTC 图集，包含所有地图精灵
// ============================================================

// 地面（64×64）
#define TILE_SIZE 64
#define TREE_SIZE 128

// 图集总尺寸
#define ATLAS_W 2048
#define ATLAS_H 2048

// 建筑精灵尺寸（所有建筑统一为 128×128）
#define BUILDING_W 128
#define BUILDING_H 128

// 地图精灵点（像素坐标，从 0,0 开始排列）
// 行1: 地面 + 装饰（64×64 精灵，8 个一行）
// 行2-5: 建筑（128×128 精灵，4 个一行）
// 行6+: 作物/其他

static const SpriteDef MAP_SPRITES[] = {
    // 行0: 地面和装饰（64×64，最多 32 个）
    { "ground_tile",      0,   0,   64, 64 },
    { "grass_small",      64,  0,   64, 64 },
    { "grass_medium",     128, 0,   64, 64 },
    { "grass_large",      192, 0,   64, 64 },
    { "tree1",            256, 0,   128,128 },  // 2×2 格树
    { "tree2",            384, 0,   128,128 },
    { "ground_tile_v2",   512, 0,   64, 64 },   // 地面变体2（与 ground_tile 随机混用）

    // 灵田作物三阶段（WP6，64×64，y=0 行空闲区——与 SpriteAtlasDef.CropStage 同步）
    { "crop_seedling",    832, 0,   64, 64 },
    { "crop_growing",     896, 0,   64, 64 },
    { "crop_mature",      960, 0,   64, 64 },

    // 行1-4: 建筑（128×128，每行 4 个）
    { "灵矿场",           0,   128, 128,128 },
    { "灵植阁",           128, 128, 128,128 },
    { "灵田",             256, 128, 128,128 },
    { "炼丹炉",           384, 128, 128,128 },
    { "锻造坊",           512, 128, 128,128 },
    { "仓库",             0,   256, 128,128 },
    { "藏经阁",           128, 256, 128,128 },
    { "问道塔",           256, 256, 128,128 },
    { "青云塔",           384, 256, 128,128 },
    { "天枢殿",           512, 256, 128,128 },
    { "执法堂",           0,   384, 128,128 },
    { "任务阁",           128, 384, 128,128 },
    { "巡视楼",           256, 384, 128,128 },
    { "监牢",             384, 384, 128,128 },
    { "单人住所",         512, 384, 128,128 },
    { "中级单人住所",     0,   512, 128,128 },
    { "多人住所",         128, 512, 128,128 },
    { "血炼池",           256, 512, 128,128 },
    { "中级多人住所",     384, 512, 128,128 },

    // 行5+: 地砖底座（128-192×128-192px，建筑脚下覆盖层）
    { "floor_tile_2x2",    0,   640, 128,128 },
    { "floor_tile_2x3",    0,   768, 128,192 },
    { "floor_tile_3x2",    0,   960, 192,128 },
    { "floor_tile_3x3",    192, 960, 192,192 },

    // 行6: 建筑专属地皮覆盖（256×256，灵矿场 4×4 地面）
    { "spirit_mine_ground",  0,  1152, 256,256 },
};

static constexpr int MAP_SPRITE_COUNT =
    sizeof(MAP_SPRITES) / sizeof(MAP_SPRITES[0]);
