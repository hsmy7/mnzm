#include <gtest/gtest.h>

#include <vector>

#include "gamecore/map/draw_order.h"

namespace gamecore {
namespace {

using gamecore::map::decorBeforeBuilding;
using gamecore::map::decorDrawRect;
using gamecore::map::mergeObjectLayerOrder;

// ============================================================
// 绘制层序合成器测试（立体装饰 ↔ 建筑归并 + 装饰绘制矩形锚点）
// 覆盖：归并序正确、同键建筑在后、空集/单边边界、两组各自保序、
//      锚点公式（格底边居中）、小数格精度。
// ============================================================

TEST(DrawOrderTest, MergeInterleavesByBottomY) {
    const float decor[] = {48.0f, 240.0f};
    const float buildings[] = {96.0f, 288.0f, 384.0f};
    uint8_t out[5] = {};
    const int n = mergeObjectLayerOrder(decor, 2, buildings, 3, out);
    ASSERT_EQ(5, n);
    // 48(装饰) → 96(建筑) → 240(装饰) → 288(建筑) → 384(建筑)
    EXPECT_EQ(0, out[0]);
    EXPECT_EQ(1, out[1]);
    EXPECT_EQ(0, out[2]);
    EXPECT_EQ(1, out[3]);
    EXPECT_EQ(1, out[4]);
}

TEST(DrawOrderTest, SameBottomYBuildingDrawsAfterDecor) {
    // 同键（同一地面接触点）：装饰先绘、建筑覆盖——建筑立在土地上
    const float decor[] = {96.0f};
    const float buildings[] = {96.0f};
    uint8_t out[2] = {};
    ASSERT_EQ(2, mergeObjectLayerOrder(decor, 1, buildings, 1, out));
    EXPECT_EQ(0, out[0]);
    EXPECT_EQ(1, out[1]);
    EXPECT_TRUE(decorBeforeBuilding(96.0f, 96.0f));
    EXPECT_FALSE(decorBeforeBuilding(97.0f, 96.0f));
}

TEST(DrawOrderTest, EmptyGroupsPassThrough) {
    uint8_t out[3] = {};
    EXPECT_EQ(0, mergeObjectLayerOrder(nullptr, 0, nullptr, 0, out));

    const float buildings[] = {1.0f, 2.0f, 3.0f};
    ASSERT_EQ(3, mergeObjectLayerOrder(nullptr, 0, buildings, 3, out));
    for (int i = 0; i < 3; ++i) EXPECT_EQ(1, out[i]);

    const float decor[] = {1.0f, 2.0f, 3.0f};
    ASSERT_EQ(3, mergeObjectLayerOrder(decor, 3, nullptr, 0, out));
    for (int i = 0; i < 3; ++i) EXPECT_EQ(0, out[i]);
}

TEST(DrawOrderTest, BothGroupsKeepRelativeOrder) {
    // 归并不改变组内相对序：按标记消费两组，各自产出序与原序逐项一致
    const float decor[] = {10.0f, 30.0f, 50.0f};
    const float buildings[] = {20.0f, 40.0f};
    uint8_t out[5] = {};
    ASSERT_EQ(5, mergeObjectLayerOrder(decor, 3, buildings, 2, out));

    std::vector<float> decorSeq;
    std::vector<float> buildingSeq;
    int di = 0;
    int bi = 0;
    for (int i = 0; i < 5; ++i) {
        if (out[i] == 0) {
            decorSeq.push_back(decor[di++]);
        } else {
            buildingSeq.push_back(buildings[bi++]);
        }
    }
    EXPECT_EQ(3, di);
    EXPECT_EQ(2, bi);
    EXPECT_EQ((std::vector<float>{10.0f, 30.0f, 50.0f}), decorSeq);
    EXPECT_EQ((std::vector<float>{20.0f, 40.0f}), buildingSeq);
}

TEST(DrawOrderTest, DecorDrawRectAnchorsBottomCenterOnCell) {
    float rect[4] = {};
    // 草1（1 × 1.157 格）站在 48px 格上：左右居中、底边贴格底边
    decorDrawRect(480.0f, 960.0f, 48.0f, 1.0f, 1.157f, rect);
    EXPECT_FLOAT_EQ(480.0f, rect[0]);
    EXPECT_FLOAT_EQ(960.0f + 48.0f - 1.157f * 48.0f, rect[1]);
    EXPECT_FLOAT_EQ(48.0f, rect[2]);
    EXPECT_FLOAT_EQ(1.157f * 48.0f, rect[3]);

    // 树1（2 × 3.291 格）：水平居中（左右各伸出 0.5 格）、向上伸出 2.291 格
    decorDrawRect(0.0f, 0.0f, 48.0f, 2.0f, 3.291f, rect);
    EXPECT_FLOAT_EQ(-24.0f, rect[0]);
    EXPECT_FLOAT_EQ(-2.291f * 48.0f, rect[1]);
    EXPECT_FLOAT_EQ(96.0f, rect[2]);
    EXPECT_FLOAT_EQ(3.291f * 48.0f, rect[3]);

    // 底边恒等于格底边（x 居中、y 底对齐两条锚点契约一起锁定）
    const float cellBottom = 0.0f + 48.0f;
    EXPECT_FLOAT_EQ(cellBottom, rect[1] + rect[3]);
}

}  // namespace
}  // namespace gamecore
