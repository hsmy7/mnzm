#include <gtest/gtest.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "Rhi.h"
#include "SpriteBatcher.h"
#include "scene/float_text.h"
#include "scene/scene_draw.h"
#include "scene/scene_store.h"
#include "scene/scene_uv_tables.h"

namespace {

using scene::buildCliffLayer;
using scene::buildFloatTextBatch;
using scene::buildMapBatch;
using scene::buildOverlayLayers;
using scene::CliffLayerParams;
using scene::CropSmoothingState;
using scene::FloatTextParams;
using scene::FloatTextPool;
using scene::MapLayerParams;
using scene::OverlayParams;
using scene::SceneStore;

// ============================================================
// 场景回归集：确定性软件光栅化 golden 基线（重构方案 2026-09-17 R3.8/B13）
//
// **目标**（批次 R3.8-④ / 验收门 4）：在既有 RecorderRenderer（记录 draw 调用）
// **之上**再加一层确定性软光栅化——顶点流 → 像素缓冲 → golden 基准入库——
// 把"顶点流逐位等价"推进到"**像素级可回归**"，覆盖：
//   - 六要素场景（地形含装饰 / 道路 / 建筑含阴影 / 作物三阶段 / 云 / 崖壁）；
//   - 浮字场景（出生 / 上浮中 / 淡出 / 池满覆盖）；
//   - 相机三档位（近景 2.0 / 中景 1.0 / 远景 0.3）。
//
// **确定性来源**（golden 可复跑的前提）：
//   1. 顶点流由纯函数生成（同输入 ⇒ 同顶点，已由 scene_equivalence_test 锁定）；
//   2. 软光栅化只做整数扫描线 + float 重心插值，**不依赖 GPU/驱动/浮点融合**
//      （-ffp-contract=off 已钉死 FMA 融合；本文件全部算术为确定性表达式）；
//   3. 纹理采样用**程序化 checker/渐变图案**（不读真实图集 KTX——测试环境无
//      ASTC 解码器），UV → 图案像素的映射是纯函数；
//   4. 输出 = 像素校验和（FNV-1a 64）+ 分块计数，golden 以文本形式入库。
//
// **golden 变更流程**：本测试 `--update-golden`（或环境变量
// `SCENE_GOLDEN_UPDATE=1`）时**显式重生** golden 基线文件并打印每个用例的
// 前后校验和——批次要求"golden 基线变更须显式重生并说明原因"。默认（无该
// 开关）比对失败即红，不自动改写。
//
// **真机截图回归与 Adreno 黑名单**：本测试只覆盖**桌面确定性软光栅**这一层；
// 真机 GPU 截图回归需设备农场，属残余项（完成报告登记）。
// ============================================================

// ── golden 基线路径（随测试源入库；相对 cpp/gamecore/ 根） ──

/** golden 基线文件（文本：key=checksum 逐行） */
constexpr const char* kGoldenRelativePath = "test/golden/scene_regression_golden.txt";

/** 软光栅输出尺寸（640×360 = 16:9，与真机离屏比例一致但更小以控耗时） */
constexpr int kFbW = 640;
constexpr int kFbH = 360;

/** 是否处于 golden 重生模式（显式开关，绝不隐式改写基线） */
bool goldenUpdateMode() {
    const char* env = std::getenv("SCENE_GOLDEN_UPDATE");
    return env != nullptr && std::string(env) == "1";
}

/** 定位 golden 基线文件的期望写入位置（沿构建目录向上找 test/ 目录） */
std::string goldenFilePath() {
    // CMake 把测试源目录传给预处理器更稳妥，但为免改 CMake 起见按相对探测：
    // 依次尝试常见位置（本文件所在目录由 __FILE__ 提供）
    std::string self = __FILE__;
    const size_t slash = self.find_last_of("/\\");
    const std::string dir = (slash == std::string::npos) ? "." : self.substr(0, slash);
    return dir + "/golden/scene_regression_golden.txt";
}

// ── 程序化纹理图案（不读真实图集——测试环境无 ASTC 解码器） ──

/**
 * 程序化图案像素：以 UV 归一化坐标 → 32×32 棋盘 + 渐变。
 *
 * 用途：让"UV 是否取对"在像素层可观测（UV 错则棋盘相位/渐变色变）。
 * 纯函数、无状态、无浮点融合敏感运算（乘法/加法按 IEEE 确定性）。
 */
struct Rgba8 {
    uint8_t r;
    uint8_t g;
    uint8_t b;
    uint8_t a;
};

inline Rgba8 samplePattern(float u, float v) {
    // 越界 clamp（CLAMP_TO_EDGE 语义）
    if (u < 0.0f) u = 0.0f;
    if (u > 1.0f) u = 1.0f;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    const int cx = static_cast<int>(u * 32.0f);
    const int cy = static_cast<int>(v * 32.0f);
    const bool checker = ((cx + cy) & 1) != 0;
    const uint8_t base = checker ? 200 : 60;
    // 渐变分量（u 控 R，v 控 G）；方向性错误会在校验和上暴露
    const uint8_t r = static_cast<uint8_t>(base * u);
    const uint8_t g = static_cast<uint8_t>(base * v);
    const uint8_t b = static_cast<uint8_t>(base);
    return Rgba8{r, g, b, 255};
}

// ── 确定性软光栅器（单线程、整数扫描线、float 重心插值） ──
//
// **坐标变换**（关键：顶点是**世界坐标**，不是像素坐标）：
//   `SpriteBatcher.add` 把世界坐标写进 `SpriteVertex.px/py`，投影矩阵由
//   `setProjection` 交给 GPU（push-constant）在**顶点着色器**里应用。
//   软光栅化必须复刻这一段：世界 → NDC（经 `projMatrix`）→ 帧缓冲像素
//   （NDC → 屏幕的 Y 翻转 + 半像素中心对齐，与 Vulkan 视口变换同式）。
//
//   曾踩过的坑：若把 `px/py` 当像素坐标直用，世界坐标（如 200,200）落在
//   640×360 帧外 ⇒ draw call 有、像素恒零（"黑帧假绿"）。本文件的
//   `drawnPixelCount` 活性断言即为拦这类静默失效而设。
//
// **顶点颜色与 UV 不受投影影响**，按重心插值直通。

/**
 * 像素缓冲 + 校验和。
 *
 * 混合：源-over 直通 alpha（与运行期 SRC_ALPHA/ONE_MINUS_SRC_ALPHA 同式，
 * 全部算术为确定性浮点表达式）。
 * 校验和：FNV-1a 64 位逐像素累积（对通道顺序敏感，捕捉细微差异）。
 */
class SoftRasterizer {
public:
    SoftRasterizer() : pixels_(static_cast<size_t>(kFbW) * kFbH * 4, 0),
                       covered_(static_cast<size_t>(kFbW) * kFbH, 0) {}

    void clear(uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
        for (int y = 0; y < kFbH; y++) {
            for (int x = 0; x < kFbW; x++) {
                const size_t i = index(x, y);
                pixels_[i] = r;
                pixels_[i + 1] = g;
                pixels_[i + 2] = b;
                pixels_[i + 3] = a;
            }
        }
        std::fill(covered_.begin(), covered_.end(), 0);
    }

    /**
     * 绘制一个三角。顶点为**世界坐标**，内部经 `proj_[16]` 变换到帧缓冲像素。
     *
     * @param proj 世界 → NDC 投影矩阵（列主序，与 Rhi.h orthoProj 同布局）
     */
    void triangle(const SpriteVertex& a, const SpriteVertex& b, const SpriteVertex& c,
                  const float proj[16]) {
        // ── 世界 → NDC → 帧缓冲像素（复刻顶点着色器 + 视口变换）──
        float ax, ay, bx, by, cx2, cy2;
        if (!project(a.px, a.py, proj, &ax, &ay)) return;
        if (!project(b.px, b.py, proj, &bx, &by)) return;
        if (!project(c.px, c.py, proj, &cx2, &cy2)) return;

        const float minXf = std::min({ax, bx, cx2});
        const float maxXf = std::max({ax, bx, cx2});
        const float minYf = std::min({ay, by, cy2});
        const float maxYf = std::max({ay, by, cy2});

        int x0 = static_cast<int>(std::floor(minXf));
        int x1 = static_cast<int>(std::ceil(maxXf));
        int y0 = static_cast<int>(std::floor(minYf));
        int y1 = static_cast<int>(std::ceil(maxYf));
        x0 = std::max(0, x0);
        y0 = std::max(0, y0);
        x1 = std::min(kFbW - 1, x1);
        y1 = std::min(kFbH - 1, y1);
        if (x0 > x1 || y0 > y1) return;

        const float area = edgeXY(ax, ay, bx, by, cx2, cy2);
        if (area == 0.0f) return;   // 退化三角（与 GPU 丢弃一致）
        const float invArea = 1.0f / area;

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                const float px = static_cast<float>(x) + 0.5f;
                const float py = static_cast<float>(y) + 0.5f;
                const float w0 = edgePt(bx, by, cx2, cy2, px, py);
                const float w1 = edgePt(cx2, cy2, ax, ay, px, py);
                const float w2 = edgePt(ax, ay, bx, by, px, py);

                // **内侧判定**：三条边函数必须与 area **同号**（同向绕序）。
                // 曾踩过的坑：写成 "sum > 0" 会把整个包围盒判为命中
                //（三角形外的点 w 会有一项为负、两项为正而符号"和"为正），
                // 导致每帧全屏覆盖——活性断言随即沦为假绿。
                if (area > 0.0f) {
                    if (w0 < 0.0f || w1 < 0.0f || w2 < 0.0f) continue;
                } else {
                    if (w0 > 0.0f || w1 > 0.0f || w2 > 0.0f) continue;
                }

                // 重心权重：w_i / area（面积比，恒在 [0,1] 且三者归一）
                const float l0 = w0 * invArea;
                const float l1 = w1 * invArea;
                const float l2 = w2 * invArea;

                const float u = a.u * l0 + b.u * l1 + c.u * l2;
                const float v = a.v * l0 + b.v * l1 + c.v * l2;
                const float sr = a.r * l0 + b.r * l1 + c.r * l2;
                const float sg = a.g * l0 + b.g * l1 + c.g * l2;
                const float sb = a.b * l0 + b.b * l1 + c.b * l2;
                const float sa = a.a * l0 + b.a * l1 + c.a * l2;
                blend(x, y, sr, sg, sb, sa, u, v);
            }
        }
    }

    /** 校验和（FNV-1a 64） */
    uint64_t checksum() const {
        uint64_t h = 14695981039346656037ULL;
        for (uint8_t byte : pixels_) {
            h ^= static_cast<uint64_t>(byte);
            h *= 1099511628211ULL;
        }
        return h;
    }

    /** 非透明像素计数（含 clear 底色——**不能**用作"画了东西"的活性判据） */
    int opaquePixelCount() const {
        int n = 0;
        for (size_t i = 3; i < pixels_.size(); i += 4) {
            if (pixels_[i] > 0) n++;
        }
        return n;
    }

    /**
     * **被任意三角覆盖过**的像素数（活性判据）。
     *
     * `opaquePixelCount` 计数的是 alpha>0，而夹具用不透明白底 clear 后它恒等于
     * 整帧像素数（230400）——无法区分"画了"与"没画"。本计数在 `blend` 命中时
     * 打标，是真正的"有几何覆盖"信号（含被后续 alpha 覆盖的像素）。
     */
    int drawnPixelCount() const {
        int n = 0;
        for (uint8_t c : covered_) {
            if (c != 0) n++;
        }
        return n;
    }

private:
    size_t index(int x, int y) const { return (static_cast<size_t>(y) * kFbW + x) * 4; }

    /**
     * 世界坐标 → 帧缓冲像素坐标（复刻顶点着色器 + 视口变换）。
     *
     * 矩阵为**列主序**（与 Rhi.h `orthoProj` 同布局）：
     *   clipX = m0*x + m4*y + m12
     *   clipY = m1*x + m5*y + m13
     * 透视除法免做（正交矩阵 w 恒 1）。
     *
     * NDC → 屏幕（Vulkan 约定：NDC Y 向下 +1 = 屏幕底部）：
     *   sx = (ndcX + 1) / 2 * fbW
     *   sy = (ndcY + 1) / 2 * fbH
     */
    bool project(float wx, float wy, const float m[16], float* outX, float* outY) const {
        const float ndcX = m[0] * wx + m[4] * wy + m[12];
        const float ndcY = m[1] * wx + m[5] * wy + m[13];
        if (ndcX != ndcX || ndcY != ndcY) return false;   // NaN 防护
        *outX = (ndcX + 1.0f) * 0.5f * static_cast<float>(kFbW);
        *outY = (ndcY + 1.0f) * 0.5f * static_cast<float>(kFbH);
        return true;
    }

    static float edgePt(float ax, float ay, float bx, float by, float px, float py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }
    static float edgeXY(float ax, float ay, float bx, float by, float cx, float cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    void blend(int x, int y, float sr, float sg, float sb, float sa, float u, float v) {
        if (sa <= 0.0f) return;
        if (sa > 1.0f) sa = 1.0f;
        // 活性打标：本像素被一个**有效可见**片元覆盖（alpha>0 且已过内侧判定）
        covered_[static_cast<size_t>(y) * kFbW + x] = 1;
        // 纹理调制：图集纹理段（非纯色）取程序化图案，纯色段（texId==0）用顶点色
        uint8_t tr = 255;
        uint8_t tg = 255;
        uint8_t tb = 255;
        const bool textured = currentTexId_ != 0;
        if (textured) {
            const Rgba8 p = samplePattern(u, v);
            tr = p.r;
            tg = p.g;
            tb = p.b;
        }
        const float cr = sr * (static_cast<float>(tr) / 255.0f);
        const float cg = sg * (static_cast<float>(tg) / 255.0f);
        const float cb = sb * (static_cast<float>(tb) / 255.0f);

        const size_t i = index(x, y);
        const float dr = static_cast<float>(pixels_[i]) / 255.0f;
        const float dg = static_cast<float>(pixels_[i + 1]) / 255.0f;
        const float db = static_cast<float>(pixels_[i + 2]) / 255.0f;
        const float da = static_cast<float>(pixels_[i + 3]) / 255.0f;

        const float or_ = cr * sa + dr * (1.0f - sa);
        const float og = cg * sa + dg * (1.0f - sa);
        const float ob = cb * sa + db * (1.0f - sa);
        const float oa = sa + da * (1.0f - sa);

        pixels_[i] = toByte(or_);
        pixels_[i + 1] = toByte(og);
        pixels_[i + 2] = toByte(ob);
        pixels_[i + 3] = toByte(oa);
    }

    static uint8_t toByte(float v) {
        if (!(v > 0.0f)) return 0;   // 同时拦 NaN
        if (v > 1.0f) v = 1.0f;
        return static_cast<uint8_t>(v * 255.0f + 0.5f);
    }

public:
    /** 当前纹理段 ID（0 = 纯色段；非 0 = 纹理段——由 draw 回调设置） */
    uint32_t currentTexId_ = 0;

private:
    std::vector<uint8_t> pixels_;
    std::vector<uint8_t> covered_;   // 每像素"被三角覆盖过"标记（活性判据）
};

/**
 * 软光栅后端：实现 Renderer2D，把 draw(verts, count, texId) 的每个三角送入
 * 软光栅器——**同一顶点流、同一提交序**（与 RecorderRenderer 消费同一数据）。
 */
class SoftRasterRenderer : public Renderer2D {
public:
    void draw(const SpriteVertex* verts, int count, uint32_t texId) override {
        raster_.currentTexId_ = texId;
        // SpriteBatcher 每 quad = 6 顶点 = 2 三角（0-1-2 / 3-4-5，索引序固定）
        for (int i = 0; i + 2 < count; i += 3) {
            raster_.triangle(verts[i], verts[i + 1], verts[i + 2], proj_);
        }
        drawCalls_++;
    }
    bool init(const RenderConfig&, void*) override { return true; }
    void shutdown() override {}
    bool resize(int, int) override { return true; }
    RenderInitError lastInitError() const override { return RenderInitError::NONE; }
    void beginFrame() override {}
    void endFrame() override {}
    bool isReady() const override { return true; }
    uint32_t uploadTexture(const void*, int, int) override { return 1; }
    void destroyTexture(uint32_t) override {}
    // 生产语义：投影矩阵经 push-constant 交给顶点着色器（此处复刻为软件变换）
    void setProjection(const float mat[16]) override {
        for (int i = 0; i < 16; i++) proj_[i] = mat[i];
    }
    void submitFrame() override {}
    void drawBackground(const SpriteVertex*, int, const SkyGradientParams&) override {}

    SoftRasterizer& raster() { return raster_; }
    int drawCalls() const { return drawCalls_; }
    void clearFrame(uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
        raster_.clear(r, g, b, a);
        drawCalls_ = 0;
    }
    /** 直接设定投影矩阵（测试夹具不经 setProjection 时的显式入口） */
    void setProjectionMatrix(const float mat[16]) { setProjection(mat); }
    /** 取回当前投影矩阵（生成核心需要同一矩阵——如浮字批） */
    void projectionMatrix(float out[16]) const {
        for (int i = 0; i < 16; i++) out[i] = proj_[i];
    }

private:
    SoftRasterizer raster_;
    int drawCalls_ = 0;
    float proj_[16] = {1.0f, 0, 0, 0, 0, 1.0f, 0, 0, 0, 0, 1.0f, 0, 0, 0, 0, 1.0f};
};

// ── golden 基线读写 ──

class GoldenStore {
public:
    static std::map<std::string, std::string> load() {
        std::map<std::string, std::string> out;
        std::ifstream in(goldenFilePath());
        if (!in.is_open()) return out;
        std::string line;
        while (std::getline(in, line)) {
            if (line.empty() || line[0] == '#') continue;
            const size_t eq = line.find('=');
            if (eq == std::string::npos) continue;
            out[line.substr(0, eq)] = line.substr(eq + 1);
        }
        return out;
    }

    static void save(const std::map<std::string, std::string>& data) {
        std::ofstream out(goldenFilePath(), std::ios::trunc);
        ASSERT_TRUE(out.is_open()) << "golden 写入失败: " << goldenFilePath();
        out << "# 场景回归集 golden 基线（R3.8/B13）——确定性软件光栅化像素校验和\n";
        out << "# 格式: <用例键>=<FNV1a64 十六进制>:<drawCalls>:<drawnPixels>\n";
        out << "#   drawnPixels = 被任意三角覆盖过的像素数（活性判据；非 clear 底色计数）\n";
        out << "# 重生: SCENE_GOLDEN_UPDATE=1 运行本测试套件（显式重生，说明原因后提交）\n";
        for (const auto& kv : data) {
            out << kv.first << '=' << kv.second << '\n';
        }
    }
};

/** 断言某用例的像素校验和与 golden 一致（或重生模式下写回） */
void expectGolden(const std::string& key, uint64_t checksum, int drawCalls, int opaquePixels) {
    std::ostringstream oss;
    oss << std::hex << checksum << ':' << std::dec << drawCalls << ':' << opaquePixels;

    if (goldenUpdateMode()) {
        static std::map<std::string, std::string> updated;
        updated[key] = oss.str();
        // 重生模式：累积后统一写盘（每用例调用处立即刷新，保证崩溃不丢）
        GoldenStore::save(updated);
        std::printf("[golden-regen] %s = %s\n", key.c_str(), oss.str().c_str());
        return;
    }

    const std::map<std::string, std::string> golden = GoldenStore::load();
    const auto it = golden.find(key);
    ASSERT_NE(golden.end(), it)
        << "golden 基线缺少用例 " << key << "（新增用例须重生基线：SCENE_GOLDEN_UPDATE=1）";
    EXPECT_EQ(it->second, oss.str())
        << "用例 " << key << " 像素校验和/计数与 golden 不一致——\n"
        << "  若为**有意的**渲染变更，请显式重生 golden（SCENE_GOLDEN_UPDATE=1）并在提交信息说明原因；\n"
        << "  若为**无意**的回归，请修复产生差异的代码。";
}

// ── 场景夹具（与 scene_equivalence_test / scene_overlay_equivalence_test 同源口径） ──

constexpr int32_t kCols = 16;
constexpr int32_t kRows = 16;
constexpr int32_t kTileSize = 48;
constexpr uint32_t kAtlasTexId = 42;
constexpr int32_t kRealCols = 64;
constexpr int32_t kRealRows = 64;

struct CameraPos {
    float camX;
    float camY;
    float scale;
    const char* name;
};

const CameraPos kCameras[3] = {
    {200.0f, 200.0f, 2.0f, "near"},
    {400.0f, 400.0f, 1.0f, "mid"},
    {600.0f, 600.0f, 0.3f, "far"},
};

/** 地形瓦片（含装饰 1..9：草/石/树编码）——确定性图案，覆盖装饰分支 */
std::vector<int32_t> fixtureTerrain() {
    std::vector<int32_t> t(static_cast<size_t>(kCols) * kRows, 0);
    for (int32_t y = 0; y < kRows; y++) {
        for (int32_t x = 0; x < kCols; x++) {
            const int32_t i = y * kCols + x;
            // 左半地面（含变体），右半装饰
            if (x < kCols / 2) {
                t[static_cast<size_t>(i)] = x % 5;   // GROUND/GRASS1-4
            } else {
                t[static_cast<size_t>(i)] = 5 + (x + y) % 5; // 石/树
            }
        }
    }
    return t;
}

/** 建筑（stride 5：gx,gy,?,?,nameIdx）——含固定结构门楼 nameIdx=19 */
std::vector<float> fixtureBuildings() {
    std::vector<float> b;
    // 灵田（nameIdx=2）、灵矿场（0）、门楼（19）
    const int32_t entries[3][3] = {{2, 2, 2}, {6, 6, 0}, {10, 10, 19}};
    for (const auto& e : entries) {
        b.push_back(static_cast<float>(e[0]));
        b.push_back(static_cast<float>(e[1]));
        b.push_back(0.0f);
        b.push_back(0.0f);
        b.push_back(static_cast<float>(e[2]));
    }
    return b;
}

std::vector<float> fixtureCrops() {
    // stride 3：cropIdx, stageProgress, ?
    return {0.0f, 0.3f, 0.0f, 1.0f, 0.6f, 0.0f, 2.0f, 0.95f, 0.0f};
}

std::vector<float> fixtureClouds() {
    // stride 6：spriteIdx, worldX, worldY, w, h, alpha? —— 按既有 CloudStride
    return {0.0f, 100.0f, 50.0f, 256.0f, 96.0f, 1.0f,
            1.0f, 500.0f, 80.0f, 220.0f, 110.0f, 0.8f};
}

std::vector<float> fixtureRoads() {
    // stride ?：road stride 由 scene_store 定义；此处给最小可用条目
    // （详见 scene_store.h kRoadStride——这里按 stride 6 组装：gx,gy,mask,u0,v0,u1）
    return {};
}

/** 崖壁布局（stride 10，与 kCliffStride 一致） */
std::vector<float> fixtureCliffs() {
    std::vector<float> c;
    // [texIdx, x, y, w, h, u0, v0, u1, v1, flags]
    const float pieces[2][10] = {
        {0.0f, 0.0f, 0.0f, 200.0f, 120.0f, 0.0f, 0.0f, 0.5f, 0.5f, 0.0f},
        {1.0f, 200.0f, 0.0f, 200.0f, 120.0f, 0.5f, 0.5f, 1.0f, 1.0f, 0.0f},
    };
    for (const auto& p : pieces) {
        for (float v : p) c.push_back(v);
    }
    return c;
}

/** 装配地图层参数 */
MapLayerParams makeMapParams(const SceneStore& store, const float proj[16], const CameraPos& cam) {
    MapLayerParams p;
    p.viewLeft = cam.camX;
    p.viewTop = cam.camY;
    p.viewRight = cam.camX + static_cast<float>(kFbW) / cam.scale;
    p.viewBottom = cam.camY + static_cast<float>(kFbH) / (cam.scale * scene::kTopdownYScale);
    p.scale = cam.scale;
    p.fadeAlpha = 1.0f;
    p.frameAlpha = 1.0f;
    p.skipDecor = false;
    p.skipClouds = false;
    p.buildingShadows = true;
    p.buildingVisible = true;
    p.tiles = store.terrainData();
    p.tileCount = store.terrainCount();
    p.cols = store.cols();
    p.rows = store.rows();
    p.tileSize = store.tileSize();
    p.atlasTexId = kAtlasTexId;
    p.groundQuadEnabled = false;
    p.groundTexId = 0;
    p.tileUv = scene::kTileUv;
    p.tileUvCount = scene::kTileUvCount;
    p.roads = store.roadsData();
    p.roadCount = store.roadsCount();
    p.roadUv = scene::kRoadUv;
    p.roadUvCount = scene::kRoadUvCount;
    p.buildings = store.buildingsData();
    p.buildingClaim = store.buildingCount();
    p.buildingDataFloats = store.buildingCount() * scene::kBuildingStride;
    p.buildingUv = scene::kBuildingUv;
    p.buildingUvCount = scene::kBuildingUvCount;
    p.crops = store.cropsData();
    p.cropCount = store.cropCount();
    p.cropUv = scene::kCropUv;
    p.cropUvCount = scene::kCropUvCount;
    p.clouds = store.cloudsData();
    p.cloudCount = store.cloudCount();
    p.cloudUv = scene::kCloudUv;
    p.cloudUvCount = scene::kCloudUvCount;
    (void)proj;
    return p;
}

/** 装配一个"六要素齐备"的 SceneStore（道路由掩码表导入——见 kRoadStride） */
SceneStore makeFullSceneStore() {
    SceneStore store;
    const std::vector<int32_t> t = fixtureTerrain();
    store.setTerrain(t.data(), static_cast<int64_t>(t.size()), kCols, kRows, kTileSize);
    const std::vector<float> b = fixtureBuildings();
    store.updateBuildings(b.data(), static_cast<int>(b.size() / scene::kBuildingStride));
    const std::vector<float> c = fixtureCrops();
    store.updateCrops(c.data(), static_cast<int>(c.size() / scene::kCropStride));
    const std::vector<float> cl = fixtureClouds();
    store.updateClouds(cl.data(), static_cast<int>(cl.size() / scene::kCloudStride));
    const std::vector<float> cf = fixtureCliffs();
    store.setCliffLayout(cf.data(), static_cast<int>(cf.size() / scene::kCliffStride));
    // 道路：每格位掩码（值 0 = 无路；非零 = 有路形态）——给左上角一条横路
    std::vector<int32_t> roads(static_cast<size_t>(kCols) * kRows, 0);
    for (int32_t x = 0; x < kCols; x++) {
        roads[static_cast<size_t>(4) * kCols + x] = 1;
        roads[static_cast<size_t>(5) * kCols + x] = 1;
    }
    store.updateRoads(roads.data(), static_cast<int64_t>(roads.size()));
    // 拆除标记：全部绿色（覆盖拆除高亮分支）
    std::vector<uint8_t> markers(static_cast<size_t>(store.buildingCount()), scene::kDemolishMarkGreen);
    store.setDemolishMarkers(markers.data(), static_cast<int>(markers.size()));
    return store;
}

// ── 三层提交范式（与 NativeBridge.drawFrame 严格同序） ──
//
// 三层的**提交语义不同**，测试侧必须逐层对齐（本批修复的真实缺陷：初版把
// submit 回调一律误当"主批出口"，导致地图层零 draw call）：
//   - `buildCliffLayer` / `buildOverlayLayers`  → **`void`**，生成核心内部
//     按纹理段切批并**逐段调用 submit 回调**（回调即出口）；
//   - `buildMapBatch`                          → **返回顶点数**，生成核心
//     **只填充 batcher**，主批须由**调用方**提交（回调仅承担 R3.5 整图
//     REPEAT 地面那一路独立纹理；本夹具 `groundQuadEnabled=false` 故回调不触发）。
// 生产先例：`NativeBridge.cpp::submitMapBatchCommon`（`g_mapBatcher.end()` 后
// 由调用方 `renderer->draw(vertices, vertCount, atlasTexId)`）。
void submitBatch(SoftRasterRenderer& rr, const SpriteBatcher& batcher, int vertCount,
                 uint32_t texId) {
    if (vertCount > 0) rr.draw(batcher.vertices, vertCount, texId);
}

/** 渲染六要素整帧（崖壁 → 地图 → 叠加层）到软光栅器 */
void renderFullScene(SoftRasterRenderer& rr, const SceneStore& store, const CameraPos& cam,
                     uint32_t overlayFlags) {
    // **相机投影矩阵**（世界 → NDC）：与生产 `NativeBridge::drawFrame` 同一
    // 构造（Rhi.h `cameraProjMatrix`，含 TOPDOWN_Y_SCALE 纵向压缩）。
    // 软光栅器把顶点从世界坐标投影到帧缓冲像素——这是 GPU 顶点着色器那一步。
    float proj[16];
    cameraProjMatrix(proj, cam.camX, cam.camY, cam.scale,
                     static_cast<float>(kFbW), static_cast<float>(kFbH),
                     scene::kTopdownYScale);
    rr.setProjectionMatrix(proj);
    // 崖壁层（最底）——submit 回调即出口（void 返回）
    if (store.hasCliffs()) {
        CliffLayerParams cp;
        uint32_t texIds[2] = {kAtlasTexId, kAtlasTexId + 1};
        cp.data = store.cliffsData();
        cp.pieceCount = store.cliffPieceCount();
        cp.texIds = texIds;
        cp.texCount = 2;
        cp.viewLeft = cam.camX;
        cp.viewTop = cam.camY;
        cp.viewRight = cam.camX + static_cast<float>(kFbW) / cam.scale;
        cp.viewBottom = cam.camY + static_cast<float>(kFbH) / cam.scale;
        cp.scale = cam.scale;
        cp.fadeAlpha = 1.0f;
        SpriteBatcher cliffBatcher;
        // 崖壁逐 piece 自带 texId（与图集主批不同），由生成核心直接提交
        buildCliffLayer(cliffBatcher, proj, cp,
            [&rr](uint32_t texId, const SpriteVertex* verts, int count) {
                rr.draw(verts, count, texId);
            });
    }
    // 地图层——主批由调用方提交（返回顶点数）
    {
        SpriteBatcher mapBatcher;
        CropSmoothingState cropState;
        MapLayerParams p = makeMapParams(store, proj, cam);
        const int mapVerts = buildMapBatch(mapBatcher, p, proj, cropState,
            [&rr](uint32_t texId, const SpriteVertex* verts, int count) {
                // 整图 REPEAT 地面：独立纹理，须自带一次 draw（本夹具关闭该特性）
                rr.draw(verts, count, texId);
            });
        submitBatch(rr, mapBatcher, mapVerts, kAtlasTexId);
    }
    // 叠加层（在地图之上、浮字之下）——submit 回调即出口（void 返回）
    {
        SpriteBatcher overlayBatcher;
        OverlayParams op;
        op.camX = cam.camX;
        op.camY = cam.camY;
        op.scale = cam.scale;
        op.viewportW = kFbW;
        op.viewportH = kFbH;
        op.cols = store.cols();
        op.rows = store.rows();
        op.tileSize = store.tileSize();
        op.buildings = store.buildingsData();
        op.buildingCount = store.buildingCount();
        op.selectionIndex = 1;
        op.markers = store.markersData();
        op.markerCount = static_cast<int>(store.markerCount());
        op.atlasTexId = kAtlasTexId;
        op.gridVisible = (overlayFlags & scene::kOverlayBitGridVisible) != 0;
        op.previewSpriteVisible = false;
        op.previewBoxVisible = false;
        op.selectionEnabled = (overlayFlags & scene::kOverlayBitSelection) != 0;
        op.demolishEnabled = (overlayFlags & scene::kOverlayBitDemolish) != 0;
        buildOverlayLayers(overlayBatcher, proj, op,
            [&rr](uint32_t texId, const SpriteVertex* verts, int count) {
                rr.draw(verts, count, texId);
            });
    }
}

/**
 * 渲染浮字层（在既有场景之上）。
 *
 * 浮字世界锚点是**格坐标**（与地图同空间），故须与场景**同一相机投影**；
 * 调用方须先把该相机矩阵交给 `rr`（见 `renderFullScene`）。本函数只负责
 * 装配参数 + 提交（浮字批自带 flush，回调即出口）。
 *
 * 视锥剔除窗口取整个地图范围（本夹具浮字锚点全部落在地图内，剔除不参与
 * golden 差异；这样 golden 只反映"动画采样 + 层序"两件事）。
 */
void renderFloatLayer(SoftRasterRenderer& rr, const FloatTextPool& pool, float nowSeconds,
                      const SceneStore& store) {
    FloatTextParams fp;
    fp.instances = &pool.slot(0);
    fp.instanceCount = FloatTextPool::capacity();
    fp.nowSeconds = nowSeconds;
    fp.atlasTexId = kAtlasTexId;
    fp.uv = scene::kFloatUv;
    fp.assetCount = scene::kFloatAssetCount;
    fp.glyphBaseIndex = scene::kFloatGlyphBaseIndex;
    fp.tileSize = static_cast<float>(store.tileSize());
    fp.baseWorldHeight = scene::kFloatBaseWorldHeight;
    // 可见域 = 整张地图（宽松剔除，保证夹具实例恒在视锥内）
    fp.viewLeft = -1.0e5f;
    fp.viewTop = -1.0e5f;
    fp.viewRight = 1.0e5f;
    fp.viewBottom = 1.0e5f;
    fp.cullMargin = 0.0f;
    SpriteBatcher floatBatcher;
    // 投影矩阵由 rr 持有（renderFullScene 已注入）——显式取回以保一致
    float proj[16];
    rr.projectionMatrix(proj);
    buildFloatTextBatch(floatBatcher, proj, fp,
        [&rr](uint32_t texId, const SpriteVertex* verts, int count) {
            rr.draw(verts, count, texId);
        });
}

// ============================================================
// 六要素场景 × 相机三档位
// ============================================================

class ScenePixelRegressionTest : public ::testing::Test {
protected:
    void SetUp() override {
        // golden 文件目录保证存在（重生模式需要）
        if (goldenUpdateMode()) {
            const std::string path = goldenFilePath();
            const size_t slash = path.find_last_of("/\\");
            if (slash != std::string::npos) {
                const std::string dir = path.substr(0, slash);
                // 目录由构建/提交环境保证；此处不创建（避免测试改仓库结构）
                (void)dir;
            }
        }
    }
};

TEST_F(ScenePixelRegressionTest, TerrainOnlyPixelsAreStableAcrossCameras) {
    SceneStore store = makeFullSceneStore();
    for (const CameraPos& cam : kCameras) {
        SoftRasterRenderer rr;
        rr.clearFrame(10, 20, 30, 255);   // 深底（便于区分未覆盖区）
        SpriteBatcher batcher;
        CropSmoothingState cropState;
        // 相机投影矩阵（世界 → NDC），并注入软光栅器
        float proj[16];
        cameraProjMatrix(proj, cam.camX, cam.camY, cam.scale,
                         static_cast<float>(kFbW), static_cast<float>(kFbH),
                         scene::kTopdownYScale);
        rr.setProjectionMatrix(proj);
        MapLayerParams p = makeMapParams(store, proj, cam);
        const int verts = buildMapBatch(batcher, p, proj, cropState,
            [&rr](uint32_t texId, const SpriteVertex* vertices, int count) {
                rr.draw(vertices, count, texId);
            });
        // 主批由调用方提交（buildMapBatch 只填充 + 返回顶点数）
        submitBatch(rr, batcher, verts, kAtlasTexId);
        EXPECT_GT(rr.drawCalls(), 0) << cam.name << " 地图层未产生 draw call";
        EXPECT_GT(rr.raster().drawnPixelCount(), 0) << cam.name << " 地图层未覆盖任何像素";
        const std::string key = std::string("terrain_only/") + cam.name;
        expectGolden(key, rr.raster().checksum(), rr.drawCalls(), rr.raster().drawnPixelCount());
    }
}

TEST_F(ScenePixelRegressionTest, FullSceneSixElementsPixelsStableAcrossCameras) {
    SceneStore store = makeFullSceneStore();
    for (const CameraPos& cam : kCameras) {
        SoftRasterRenderer rr;
        rr.clearFrame(10, 20, 30, 255);
        renderFullScene(rr, store, cam, 0);
        EXPECT_GT(rr.drawCalls(), 0) << cam.name << " 整帧未产生 draw call";
        EXPECT_GT(rr.raster().drawnPixelCount(), 0) << cam.name << " 整帧未覆盖像素";
        const std::string key = std::string("full_scene/") + cam.name;
        expectGolden(key, rr.raster().checksum(), rr.drawCalls(), rr.raster().drawnPixelCount());
    }
}

TEST_F(ScenePixelRegressionTest, FullSceneWithOverlaysPixelsStable) {
    SceneStore store = makeFullSceneStore();
    // 叠加层全开（网格线 + 选中 + 拆除）
    const uint32_t flags = scene::kOverlayBitGridVisible | scene::kOverlayBitSelection |
                           scene::kOverlayBitDemolish | scene::kOverlayBitBuildingVisible;
    for (const CameraPos& cam : kCameras) {
        SoftRasterRenderer rr;
        rr.clearFrame(10, 20, 30, 255);
        renderFullScene(rr, store, cam, flags);
        const std::string key = std::string("full_scene_overlays/") + cam.name;
        expectGolden(key, rr.raster().checksum(), rr.drawCalls(), rr.raster().drawnPixelCount());
    }
}

TEST_F(ScenePixelRegressionTest, CameraTiersProduceDistinctPixels) {
    // 三档位必须产出**不同**像素（防"相机未生效"这类静默失效）
    SceneStore store = makeFullSceneStore();
    uint64_t sums[3] = {0, 0, 0};
    for (int i = 0; i < 3; i++) {
        SoftRasterRenderer rr;
        rr.clearFrame(10, 20, 30, 255);
        renderFullScene(rr, store, kCameras[i], 0);
        sums[i] = rr.raster().checksum();
    }
    EXPECT_NE(sums[0], sums[1]) << "近景与中景像素相同（相机档位未生效？）";
    EXPECT_NE(sums[1], sums[2]) << "中景与远景像素相同（相机档位未生效？）";
    EXPECT_NE(sums[0], sums[2]) << "近景与远景像素相同（相机档位未生效？）";
}

// ============================================================
// 浮字场景（出生 / 上浮中 / 淡出 / 池满覆盖）× 相机三档位
// ============================================================

TEST_F(ScenePixelRegressionTest, FloatTextBirthFramePixelsStable) {
    for (const CameraPos& cam : kCameras) {
        FloatTextPool pool;
        // 出生瞬间（t=0）：正常伤害数字「1234」（4 字形）+ 词条「会心」
        pool.spawn(4.0f, 4.0f, scene::kFloatGlyphBaseIndex + 1, 4, scene::kFloatStyleNormal,
                   0.0f, scene::kFloatAssetCount);
        pool.spawn(6.0f, 6.0f, 0, 2, scene::kFloatStyleCrit, 0.0f, scene::kFloatAssetCount,
                   1.0f, 1.0f, true);
        pool.advance(0.0f);

        SoftRasterRenderer rr;
        rr.clearFrame(10, 20, 30, 255);
        SceneStore store = makeFullSceneStore();
        renderFullScene(rr, store, cam, 0);
        renderFloatLayer(rr, pool, 0.0f, store);

        const std::string key = std::string("float_birth/") + cam.name;
        expectGolden(key, rr.raster().checksum(), rr.drawCalls(), rr.raster().drawnPixelCount());
    }
}

TEST_F(ScenePixelRegressionTest, FloatTextRisingMidLifePixelsStable) {
    const float t = scene::kFloatLifetimeSeconds * 0.5f;
    for (const CameraPos& cam : kCameras) {
        FloatTextPool pool;
        pool.spawn(4.0f, 4.0f, scene::kFloatGlyphBaseIndex + 1, 4, scene::kFloatStyleNormal,
                   0.0f, scene::kFloatAssetCount);
        pool.spawn(6.0f, 6.0f, 0, 2, scene::kFloatStyleHeal, 0.0f, scene::kFloatAssetCount);
        pool.advance(t);

        SoftRasterRenderer rr;
        rr.clearFrame(10, 20, 30, 255);
        SceneStore store = makeFullSceneStore();
        renderFullScene(rr, store, cam, 0);
        renderFloatLayer(rr, pool, t, store);

        const std::string key = std::string("float_rising/") + cam.name;
        expectGolden(key, rr.raster().checksum(), rr.drawCalls(), rr.raster().drawnPixelCount());
    }
}

TEST_F(ScenePixelRegressionTest, FloatTextFadingOutPixelsStable) {
    const float t = scene::kFloatLifetimeSeconds * 0.85f;
    for (const CameraPos& cam : kCameras) {
        FloatTextPool pool;
        pool.spawn(4.0f, 4.0f, scene::kFloatGlyphBaseIndex + 9, 2, scene::kFloatStyleWarn,
                   0.0f, scene::kFloatAssetCount);
        pool.advance(t);

        SoftRasterRenderer rr;
        rr.clearFrame(10, 20, 30, 255);
        SceneStore store = makeFullSceneStore();
        renderFullScene(rr, store, cam, 0);
        renderFloatLayer(rr, pool, t, store);

        const std::string key = std::string("float_fading/") + cam.name;
        expectGolden(key, rr.raster().checksum(), rr.drawCalls(), rr.raster().drawnPixelCount());
    }
}

TEST_F(ScenePixelRegressionTest, FloatTextPoolSaturatedPixelsStable) {
    // 池满覆盖场景：填满整池 + 溢出若干条，覆盖后像素稳定
    for (const CameraPos& cam : kCameras) {
        FloatTextPool pool;
        for (int i = 0; i < scene::kFloatPoolCapacity + 8; i++) {
            pool.spawn(static_cast<float>(2 + (i % 10)), static_cast<float>(2 + (i / 10)),
                       scene::kFloatGlyphBaseIndex + (i % 10), 1,
                       scene::kFloatStyleNormal, 0.0f, scene::kFloatAssetCount);
        }
        pool.advance(0.0f);
        ASSERT_EQ(scene::kFloatPoolCapacity, pool.activeCount());
        ASSERT_GT(pool.stats().droppedTotal, 0);

        SoftRasterRenderer rr;
        rr.clearFrame(10, 20, 30, 255);
        SceneStore store = makeFullSceneStore();
        renderFullScene(rr, store, cam, 0);
        renderFloatLayer(rr, pool, 0.0f, store);

        const std::string key = std::string("float_saturated/") + cam.name;
        expectGolden(key, rr.raster().checksum(), rr.drawCalls(), rr.raster().drawnPixelCount());
    }
}

TEST_F(ScenePixelRegressionTest, FloatTextLifecycleFramesDiffer) {
    // 出生 / 上浮中 / 淡出 三帧像素必须**互不相同**（防"动画未生效"静默失效）
    FloatTextPool pool;
    pool.spawn(8.0f, 8.0f, scene::kFloatGlyphBaseIndex + 5, 1, scene::kFloatStyleCrit,
               0.0f, scene::kFloatAssetCount, 1.0f, 1.0f, true);
    const float times[3] = {0.0f, scene::kFloatLifetimeSeconds * 0.5f,
                            scene::kFloatLifetimeSeconds * 0.85f};
    SceneStore store = makeFullSceneStore();
    const CameraPos& cam = kCameras[1];
    uint64_t sums[3] = {0, 0, 0};
    for (int i = 0; i < 3; i++) {
        pool.advance(times[i]);
        SoftRasterRenderer rr;
        rr.clearFrame(10, 20, 30, 255);
        renderFullScene(rr, store, cam, 0);
        renderFloatLayer(rr, pool, times[i], store);
        sums[i] = rr.raster().checksum();
    }
    EXPECT_NE(sums[0], sums[1]) << "出生帧与上浮帧像素相同（上浮未生效？）";
    EXPECT_NE(sums[1], sums[2]) << "上浮帧与淡出帧像素相同（淡出未生效？）";
}

TEST_F(ScenePixelRegressionTest, EmptyFloatPoolEqualsSceneWithoutFloatLayer) {
    // 空池 = 与不调浮字层**逐位相同**（零影响证明：不是"画了透明东西"）
    SceneStore store = makeFullSceneStore();
    const CameraPos& cam = kCameras[1];

    SoftRasterRenderer withEmpty;
    withEmpty.clearFrame(10, 20, 30, 255);
    renderFullScene(withEmpty, store, cam, 0);
    FloatTextPool emptyPool;
    emptyPool.advance(0.0f);
    renderFloatLayer(withEmpty, emptyPool, 0.0f, store);

    SoftRasterRenderer without;
    without.clearFrame(10, 20, 30, 255);
    renderFullScene(without, store, cam, 0);

    EXPECT_EQ(without.raster().checksum(), withEmpty.raster().checksum())
        << "空浮字池改变了像素（应为零影响）";
    EXPECT_EQ(without.drawCalls(), withEmpty.drawCalls())
        << "空浮字池产生了额外 draw call（应为零）";
}

TEST_F(ScenePixelRegressionTest, FloatLayerDrawsOnTopOfScene) {
    // 浮字绘制在叠加层**之后**（最上层）——浮字层单独渲染时即代表其在
    // 整帧中的合成结果；此用例断言其确有像素产出且可回归。
    FloatTextPool pool;
    pool.spawn(1.0f, 1.0f, scene::kFloatGlyphBaseIndex + 8, 1, scene::kFloatStyleNormal,
               0.0f, scene::kFloatAssetCount);
    pool.advance(0.0f);
    SceneStore store = makeFullSceneStore();
    const CameraPos& cam = kCameras[1];
    SoftRasterRenderer rr;
    rr.clearFrame(10, 20, 30, 255);
    renderFullScene(rr, store, cam, 0);
    renderFloatLayer(rr, pool, 0.0f, store);
    EXPECT_GT(rr.raster().drawnPixelCount(), 0) << "浮字层未覆盖任何像素";
    const std::string key = "float_layer_top";
    expectGolden(key, rr.raster().checksum(), rr.drawCalls(), rr.raster().drawnPixelCount());
}

// ============================================================
// golden 基线自检
// ============================================================

TEST_F(ScenePixelRegressionTest, GoldenBaselineIsPresentAndWellFormed) {
    if (goldenUpdateMode()) {
        GTEST_SKIP() << "golden 重生模式下跳过基线自检";
    }
    const std::map<std::string, std::string> golden = GoldenStore::load();
    EXPECT_FALSE(golden.empty())
        << "golden 基线缺失或为空：" << goldenFilePath()
        << "——须以 SCENE_GOLDEN_UPDATE=1 显式重生并入库";
    // 覆盖矩阵完整性：六要素 × 三档位 + 浮字 4 场景 × 三档位
    const char* cams[3] = {"near", "mid", "far"};
    for (const char* cam : cams) {
        EXPECT_TRUE(golden.count(std::string("full_scene/") + cam) > 0) << "缺 full_scene/" << cam;
        EXPECT_TRUE(golden.count(std::string("float_birth/") + cam) > 0) << "缺 float_birth/" << cam;
        EXPECT_TRUE(golden.count(std::string("float_rising/") + cam) > 0) << "缺 float_rising/" << cam;
        EXPECT_TRUE(golden.count(std::string("float_fading/") + cam) > 0) << "缺 float_fading/" << cam;
        EXPECT_TRUE(golden.count(std::string("float_saturated/") + cam) > 0) << "缺 float_saturated/" << cam;
    }
}

}  // namespace
