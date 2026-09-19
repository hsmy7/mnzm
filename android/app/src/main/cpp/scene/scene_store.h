#pragma once

#include <cstdint>
#include <vector>

// ============================================================
// SceneStore — C++ 场景真相持有者（重构方案 2026-09-17 R3.1）
//
// 渲染场景所有权下沉的结构性开端：此前场景数据（地形/道路/建筑/作物/崖壁/云）
// 由 Kotlin 每帧以 drawAllTiles 17 参数全量数组跨 JNI 推送（渲染器 = 顶点工厂）；
// 本模块把场景状态收进 native-renderer 侧持有，Kotlin 仅在**数据变化时**推送
// 更新（sceneUpdate* 端口），每帧绘制只剩 drawFrame(camera, overlayFlags)
// 相机标量 + 覆盖标志（G3：< 200B/帧）。
//
// ## 数据来源（单一来源关系）
//   - terrain：gamecore 地形生成单一权威（gamecore/map/terrain.h，生成即数据
//     入 GameData.terrainTiles）的渲染消费副本，初始化时导入一次（sceneSetTerrain）；
//     建筑占位标记的语义与旧路径一致（占位格 = TILE_BUILDING 值，绘制端取
//     地面变体 0），由导入方保证同值。
//   - road mask / 建筑集 / 作物集 / 云实例：Kotlin 侧变更驱动推送
//     （RenderFrame/RenderCommandBus 既有生产者的引用变化即推送）。
//   - 崖壁布局：IslandCliffBridge（C++ gamecore::map::island_cliff.h 单一权威
//     合成器）一次性预计算的稳定布局导入；纹理 ID 表仍走
//     setIslandCliffTextures 既有端口。
//   - 叠加层状态（R3.3/B11）：选中索引 / 逐建筑拆除标记 / 预览几何（占地框 +
//     预览精灵），同样变化驱动导入——叠加层的**几何生成**不在本模块，
//     由 scene_draw.h 按这些状态 + 相机算出（旧路径每帧逐 rect 跨线的替代）。
//
// ## 等价性红线（R3 行为等价性风险最高）
//   本模块只做**存储**（逐值搬运，零几何/层序/UV 计算）——所有绘制判定仍在
//   scene_draw.h 单份绘制核心（旧 drawAllTiles 路径与新路径消费同一实现），
//   新旧路径像素等价由构造保证 + scene_equivalence_test 顶点流对照锁定。
//
// ## 平台纯度
//   零 Android/JNI 依赖（桌面 GTest 直接覆盖）；线程契约与既有渲染全局量同：
//   渲染线程单消费者（sceneUpdate*/drawFrame 均由渲染线程经 JNI 到达），
//   导入端口与消费端口同线程顺序执行，无锁。
// ============================================================
namespace scene {

/// 建筑数据单条步长（[gx, gy, spriteW, spriteH, nameIdx]，与旧 drawAllTiles
/// buildingData 协议同形——Kotlin buildBuildingDataArray 逐位同值）
inline constexpr int kBuildingStride = 5;

/// 作物数据单条步长（[gx, gy, progress01]，与旧 cropData 协议同形）
inline constexpr int kCropStride = 3;

/// 云实例数据单条步长（[x, y, w, h, spriteIndex, alpha]，
/// 与 CloudLayerAnimator.CLOUD_DATA_STRIDE / 旧 cloudData 协议同形）
inline constexpr int kCloudStride = 6;

/// 崖壁布局单条步长（[texIdx, x, y, w, h, u0, v0, u1, v1, flags]，
/// 与 IslandCliffBridge.PIECE_STRIDE / 旧 cliffData 协议同形）
inline constexpr int kCliffStride = 10;

/// 叠加层预览数据单条步长（R3.3）：
/// [boxX, boxY, boxW, boxH, spriteX, spriteY, spriteW, spriteH,
///  u0, v0, u1, v1, r, g, b, a]
/// 占地框与预览精灵同源同帧（与旧 RenderFrame.previewXxx/previewBoxXxx 字段族
/// 逐项同值）——几何位置来自触控（非网格对齐），故按值变化推送；颜色/线宽/
/// 可见性判定在 C++ 侧（scene_draw.h）。
inline constexpr int kPreviewStride = 16;

/// 拆除高亮标记取值（与 Kotlin DemolishHighlightMark 同值：逐建筑 1 字节）
inline constexpr uint8_t kDemolishMarkNone = 0;
inline constexpr uint8_t kDemolishMarkGreen = 1;
inline constexpr uint8_t kDemolishMarkSelected = 2;

/// 叠加层预览状态（占地框 + 预览精灵；世界像素，值变化驱动导入）
struct PreviewState {
    // 占地框（预览框）矩形
    float boxX = 0.0f, boxY = 0.0f, boxW = 0.0f, boxH = 0.0f;
    // 预览精灵矩形（居中 + 底部对齐绘于占地框内）
    float spriteX = 0.0f, spriteY = 0.0f, spriteW = 0.0f, spriteH = 0.0f;
    // 精灵图集 UV 与调色
    float u0 = 0.0f, v0 = 0.0f, u1 = 0.0f, v1 = 0.0f;
    float r = 1.0f, g = 1.0f, b = 1.0f, a = 1.0f;
};

class SceneStore {
public:
    // ── 地形（一次性导入） ──────────────────────────────────────

    /// 导入地形（展平行主序瓦片类型数组 + 网格尺寸 + 格像素）。
    /// 语义 = 整表替换（地图切换/建筑占位变化时重新导入，O(N) 拷贝，
    /// 变化频率 = 地图加载/建筑放置级，非每帧）。
    void setTerrain(const int32_t* tiles, int64_t tileCount,
                    int32_t cols, int32_t rows, int32_t tileSize) {
        if (tiles == nullptr || tileCount <= 0 || cols <= 0 || rows <= 0 || tileSize <= 0) {
            clearTerrain();
            return;
        }
        terrain_.assign(tiles, tiles + tileCount);
        cols_ = cols;
        rows_ = rows;
        tileSize_ = tileSize;
    }

    /// 是否已导入有效地形（drawFrame 据此跳过地图层——旧路径 tileData=null
    /// 整体跳过的等价判定）
    bool hasTerrain() const { return !terrain_.empty(); }

    const int32_t* terrainData() const { return terrain_.data(); }
    int64_t terrainCount() const { return static_cast<int64_t>(terrain_.size()); }
    int32_t cols() const { return cols_; }
    int32_t rows() const { return rows_; }
    int32_t tileSize() const { return tileSize_; }

    // ── 石板道路掩码 ───────────────────────────────────────────

    /// 更新道路掩码（展平行主序；1-based 编码 0=非道路，与旧 roadData 协议同形）。
    /// nullptr/count=0 = 清空道路层（等价旧路径 roadData=null 跳过整层）。
    void updateRoads(const int32_t* masks, int64_t count) {
        if (masks == nullptr || count <= 0) {
            roads_.clear();
            return;
        }
        roads_.assign(masks, masks + count);
    }

    const int32_t* roadsData() const { return roads_.data(); }
    int64_t roadsCount() const { return static_cast<int64_t>(roads_.size()); }
    bool hasRoads() const { return !roads_.empty(); }

    // ── 建筑集 ─────────────────────────────────────────────────

    /// 更新建筑集（[gx,gy,sw,sh,nameIdx]×count）。
    /// @param buildingCount 建筑数（≤ dataLen/5；超出的数组尾部被忽略——
    ///        与旧路径 effectiveCount = min(claim, arrLen) 同一钳制语义）
    void updateBuildings(const float* data, int buildingCount) {
        if (data == nullptr || buildingCount <= 0) {
            buildings_.clear();
            buildingCount_ = 0;
            return;
        }
        buildings_.assign(data, data + static_cast<int64_t>(buildingCount) * kBuildingStride);
        buildingCount_ = buildingCount;
    }

    const float* buildingsData() const { return buildings_.data(); }
    int buildingCount() const { return buildingCount_; }
    bool hasBuildings() const { return buildingCount_ > 0; }

    // ── 灵田作物集 ─────────────────────────────────────────────

    /// 更新作物集（[gx,gy,progress01]×count；progress 的帧间平滑仍在绘制核心）。
    void updateCrops(const float* data, int cropCount) {
        if (data == nullptr || cropCount <= 0) {
            crops_.clear();
            cropCount_ = 0;
            return;
        }
        crops_.assign(data, data + static_cast<int64_t>(cropCount) * kCropStride);
        cropCount_ = cropCount;
    }

    const float* cropsData() const { return crops_.data(); }
    int cropCount() const { return cropCount_; }
    bool hasCrops() const { return cropCount_ > 0; }

    // ── 云实例 ─────────────────────────────────────────────────

    /// 更新云实例（[x,y,w,h,spriteIndex,alpha]×count；渲染线程逐帧生成、
    /// 变化时推送——运动由 Kotlin CloudLayerAnimator 驱动，本模块只存快照）。
    void updateClouds(const float* data, int cloudCount) {
        if (data == nullptr || cloudCount <= 0) {
            clouds_.clear();
            cloudCount_ = 0;
            return;
        }
        clouds_.assign(data, data + static_cast<int64_t>(cloudCount) * kCloudStride);
        cloudCount_ = cloudCount;
    }

    const float* cloudsData() const { return clouds_.data(); }
    int cloudCount() const { return cloudCount_; }
    bool hasClouds() const { return cloudCount_ > 0; }

    // ── 崖壁布局 ───────────────────────────────────────────────

    /// 导入崖壁布局（[texIdx,x,y,w,h,u0,v0,u1,v1,flags]×pieceCount，世界像素）。
    /// 布局为一次性预计算的稳定数据（地图尺寸/种子/纹理掩码变化时重建）。
    void setCliffLayout(const float* pieces, int pieceCount) {
        if (pieces == nullptr || pieceCount <= 0) {
            cliffs_.clear();
            cliffPieceCount_ = 0;
            return;
        }
        cliffs_.assign(pieces, pieces + static_cast<int64_t>(pieceCount) * kCliffStride);
        cliffPieceCount_ = pieceCount;
    }

    const float* cliffsData() const { return cliffs_.data(); }
    int cliffPieceCount() const { return cliffPieceCount_; }
    bool hasCliffs() const { return cliffPieceCount_ > 0; }

    // ── 叠加层状态（R3.3：选中索引 / 拆除标记 / 预览几何） ─────────
    //
    // 三类数据均**变化驱动**导入（Kotlin 侧值比较后才触 JNI），几何生成
    // （格→世界矩形、线宽、透明度、相机投影、Y 轴压缩）全在 scene_draw.h——
    // 每帧绘制不再逐 rect 跨线（旧路径放置模式最坏 ~258 次 drawRect 的主体来源）。

    /// 设置选中建筑索引（-1 = 无选中；越界由绘制核心按建筑数判跳过）
    void setSelection(int32_t index) { selectionIndex_ = index; }
    int32_t selectionIndex() const { return selectionIndex_; }

    /// 更新拆除标记（逐建筑 1 字节，与 buildings_ 同序；count 由调用方按
    /// min(建筑数, 标记数) 钳制）。nullptr/0 = 非拆除模式（整层跳过）。
    void setDemolishMarkers(const uint8_t* markers, int count) {
        if (markers == nullptr || count <= 0) {
            markers_.clear();
            return;
        }
        markers_.assign(markers, markers + count);
    }
    const uint8_t* markersData() const { return markers_.data(); }
    int markerCount() const { return static_cast<int>(markers_.size()); }
    bool hasDemolishMarkers() const { return !markers_.empty(); }

    /// 导入预览状态（kPreviewStride 个浮点；nullptr = 清空预览几何）。
    /// 可见性/合法性不在此承载——每帧经 drawFrame 的 overlayFlags 位表达。
    void setPreview(const float* values) {
        if (values == nullptr) {
            preview_ = PreviewState{};
            return;
        }
        preview_.boxX = values[0];
        preview_.boxY = values[1];
        preview_.boxW = values[2];
        preview_.boxH = values[3];
        preview_.spriteX = values[4];
        preview_.spriteY = values[5];
        preview_.spriteW = values[6];
        preview_.spriteH = values[7];
        preview_.u0 = values[8];
        preview_.v0 = values[9];
        preview_.u1 = values[10];
        preview_.v1 = values[11];
        preview_.r = values[12];
        preview_.g = values[13];
        preview_.b = values[14];
        preview_.a = values[15];
    }
    const PreviewState& preview() const { return preview_; }

    // ── 生命周期 ───────────────────────────────────────────────

    /// 清空全部场景状态（shutdownRenderer 纪元复位调用——防跨 surface 代际
    /// 残留，语义同 g_lastCropProgress/g_groundTexId 等既有清理纪律）。
    void reset() {
        clearTerrain();
        roads_.clear();
        buildings_.clear();
        buildingCount_ = 0;
        crops_.clear();
        cropCount_ = 0;
        clouds_.clear();
        cloudCount_ = 0;
        cliffs_.clear();
        cliffPieceCount_ = 0;
        selectionIndex_ = -1;
        markers_.clear();
        preview_ = PreviewState{};
    }

private:
    void clearTerrain() {
        terrain_.clear();
        cols_ = 0;
        rows_ = 0;
        tileSize_ = 0;
    }

    std::vector<int32_t> terrain_;
    int32_t cols_ = 0;
    int32_t rows_ = 0;
    int32_t tileSize_ = 0;

    std::vector<int32_t> roads_;

    std::vector<float> buildings_;
    int buildingCount_ = 0;

    std::vector<float> crops_;
    int cropCount_ = 0;

    std::vector<float> clouds_;
    int cloudCount_ = 0;

    std::vector<float> cliffs_;
    int cliffPieceCount_ = 0;

    int32_t selectionIndex_ = -1;
    std::vector<uint8_t> markers_;
    PreviewState preview_;
};

}  // namespace scene
