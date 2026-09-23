#include <gtest/gtest.h>

#include <algorithm>
#include <cctype>
#include <fstream>
#include <string>
#include <vector>

// ============================================================
// gpu_allocator_guard_test — MR2 Phase 2 GPU 子系统守卫（P2.2/P2.3）
//
// 守护目标（memory-refactor-implementation-plan D1 验证门槛 + 派发门禁）：
//   1. **渲染 cpp 裸 vkAllocateMemory/vkFreeMemory 调用点收口**——生产调用
//      只允许存在于 OFF 轨（旧裸分配）三个辅助体内（createImageWithMemory /
//      createBufferWithMemory / ensureStagingBuffer）与 shutdown 的 OFF 轨
//      staging 终清。新增 GPU 分配必须走 GpuAllocator（VMA）；
//      Kotlin 单测扫不到 native——按 RNG 红线思路以桌面 ctest 源码守卫锁定。
//   2. **VMA_IMPLEMENTATION 唯一 TU**——`#define VMA_IMPLEMENTATION` 只允许
//      出现在 gpu/GpuAllocator.cpp（第二份实现 = 重复符号链接错误）。
//   3. **P2.3 消费点接线**——NativeBridge.beginFrame 帧边界把 trim 档位
//      交给 Rhi::onMemoryTrim；VulkanBackend 收缩 staging host pool。
//   4. **Vendoring 钉死**——third_party/vma/vk_mem_alloc.h 在库且 provenance
//      注释在位（tag v3.3.0 + commit）。
// ============================================================

namespace gamecore {
namespace {

/// 读仓库 cpp 根相对路径的源文件（MR1_CPP_ROOT 由 test/CMakeLists.txt 定义）
std::string readSource(const std::string& relativePath) {
    const std::string root = MR1_CPP_ROOT;
    std::ifstream in(root + "/" + relativePath, std::ios::binary);
    if (!in) return {};
    return std::string(std::istreambuf_iterator<char>(in),
                       std::istreambuf_iterator<char>());
}

/// 去掉行注释（本仓这些文件的 /* */ 不含被检符号，行注释剥离足够）
std::string stripLineComments(const std::string& src) {
    std::string out;
    out.reserve(src.size());
    std::string line;
    for (const char c : src) {
        line.push_back(c);
        if (c == '\n') {
            const auto pos = line.find("//");
            if (pos != std::string::npos) {
                // 保留行首到 // 之前（含换行，保证位置计数稳定无必要——本守卫
                // 只做包含/计数判定，不回溯偏移）
                line = line.substr(0, pos) + "\n";
            }
            out += line;
            line.clear();
        }
    }
    return out;
}

/** VulkanBackend.cpp 内 OFF 轨辅助体（裸 vkAllocate/vkFree 的唯一合法区域） */
const std::vector<std::string>& legacyTrackFunctions() {
    static const std::vector<std::string> kFunctions = {
        "bool VulkanBackend::createImageWithMemory(",
        "void VulkanBackend::destroyImageWithMemory(",
        "bool VulkanBackend::createBufferWithMemory(",
        "void VulkanBackend::destroyBufferWithMemory(",
        "bool VulkanBackend::ensureStagingBuffer(",
        "void VulkanBackend::shutdown(",
    };
    return kFunctions;
}

/** 找 out 中每个函数签名的 span：[签名起点, 下一函数定义起点) */
std::vector<std::pair<std::size_t, std::size_t>> functionSpans(
        const std::string& src, const std::vector<std::string>& signatures) {
    std::vector<std::pair<std::size_t, std::size_t>> starts;
    for (const auto& sig : signatures) {
        const auto pos = src.find(sig);
        if (pos != std::string::npos) starts.emplace_back(pos, pos + sig.size());
    }
    std::sort(starts.begin(), starts.end());
    // 全部函数定义起点（用于 span 上界）
    std::vector<std::size_t> allStarts;
    for (const auto& sig : signatures) {
        std::size_t pos = 0;
        while ((pos = src.find(sig, pos)) != std::string::npos) {
            allStarts.push_back(pos);
            pos += sig.size();
        }
    }
    std::sort(allStarts.begin(), allStarts.end());
    std::vector<std::pair<std::size_t, std::size_t>> spans;
    for (std::size_t i = 0; i < starts.size(); ++i) {
        const auto begin = starts[i].first;
        const auto upper = std::upper_bound(allStarts.begin(), allStarts.end(), begin);
        const auto end = (upper != allStarts.end()) ? *upper : src.size();
        spans.emplace_back(begin, end);
    }
    return spans;
}

/** offset 是否落在任一 span 内 */
bool inAnySpan(std::size_t offset,
               const std::vector<std::pair<std::size_t, std::size_t>>& spans) {
    for (const auto& s : spans) {
        if (offset >= s.first && offset < s.second) return true;
    }
    return false;
}

/// 统计并校验 token 只出现在合法 span 内；返回 (合法数, 越界位置列表)
struct SpanCheckResult {
    int inSpan = 0;
    std::vector<std::size_t> outside;
};

SpanCheckResult checkTokenConfined(const std::string& src, const std::string& token,
                                   const std::vector<std::pair<std::size_t, std::size_t>>& spans) {
    SpanCheckResult r;
    std::size_t pos = 0;
    while ((pos = src.find(token, pos)) != std::string::npos) {
        if (inAnySpan(pos, spans)) {
            ++r.inSpan;
        } else {
            r.outside.push_back(pos);
        }
        pos += token.size();
    }
    return r;
}

// ── 守卫 1：裸分配/释放收口 ──────────────────────────────────

TEST(GpuAllocatorGuardTest, BareVkAllocateMemoryConfinedToLegacyTrackHelpers) {
    const std::string src = stripLineComments(readSource("VulkanBackend.cpp"));
    ASSERT_FALSE(src.empty()) << "VulkanBackend.cpp 不可读";

    const auto spans = functionSpans(src, legacyTrackFunctions());
    const auto r = checkTokenConfined(src, "vkAllocateMemory", spans);
    EXPECT_TRUE(r.outside.empty())
        << "VulkanBackend.cpp 存在 OFF 轨辅助体之外的裸 vkAllocateMemory"
        << "（越界 " << r.outside.size() << " 处，首处偏移 "
        << (r.outside.empty() ? 0 : r.outside.front())
        << "）——GPU 分配必须走 GpuAllocator（VMA）；"
        << "如确需扩展 OFF 轨辅助面，请同步更新本守卫 legacyTrackFunctions 白名单";
    EXPECT_EQ(r.inSpan, 3)
        << "OFF 轨辅助体内 vkAllocateMemory 应恰 3 处"
        << "（createImageWithMemory/createBufferWithMemory/ensureStagingBuffer 各 1）"
        << "——实为 " << r.inSpan << "，若新增 OFF 轨站点请显式更新本断言";
}

TEST(GpuAllocatorGuardTest, BareVkFreeMemoryConfinedToLegacyTrackHelpers) {
    const std::string src = stripLineComments(readSource("VulkanBackend.cpp"));
    ASSERT_FALSE(src.empty());

    const auto spans = functionSpans(src, legacyTrackFunctions());
    const auto r = checkTokenConfined(src, "vkFreeMemory", spans);
    EXPECT_TRUE(r.outside.empty())
        << "VulkanBackend.cpp 存在 OFF 轨辅助体之外的裸 vkFreeMemory——"
        << "GPU 释放必须按创建轨经 destroyImage/BufferWithMemory 或 GpuAllocator";
}

TEST(GpuAllocatorGuardTest, DualTrackHelpersDelegateOnTrackToGpuAllocator) {
    // ON 轨必须经 GpuAllocator 门（isGateEnabled）分流——双轨回退语义的结构锁定
    const std::string src = stripLineComments(readSource("VulkanBackend.cpp"));
    ASSERT_FALSE(src.empty());
    EXPECT_NE(src.find("GpuAllocator::get().isGateEnabled()"), std::string::npos)
        << "VulkanBackend.cpp 缺双轨门判定（GpuAllocator::isGateEnabled）";
    EXPECT_NE(src.find("void VulkanBackend::onMemoryTrim("), std::string::npos)
        << "VulkanBackend 缺 onMemoryTrim 消费实现（P2.3）";
    EXPECT_NE(src.find("GpuAllocator::get().trimHostPool()"), std::string::npos)
        << "onMemoryTrim 未接 staging host pool 收缩（P2.3/D3 CRITICAL 动作）";
    EXPECT_NE(src.find("GpuAllocator::get().destroy()"), std::string::npos)
        << "shutdown 缺 GpuAllocator 终局销毁（生命周期契约：先 cache 后 allocator）";
    EXPECT_NE(src.find("GpuAllocator::get().init("), std::string::npos)
        << "initDevice 缺 GpuAllocator 创建挂点（生命周期契约：initDevice 成功后 create）";
}

// ── 守卫 2：VMA_IMPLEMENTATION 唯一 TU ────────────────────────

TEST(GpuAllocatorGuardTest, VmaImplementationInSingleTranslationUnit) {
    const std::string allocatorSrc = stripLineComments(readSource("gpu/GpuAllocator.cpp"));
    ASSERT_FALSE(allocatorSrc.empty()) << "gpu/GpuAllocator.cpp 不可读";
    EXPECT_NE(allocatorSrc.find("#define VMA_IMPLEMENTATION"), std::string::npos)
        << "GpuAllocator.cpp 缺 VMA_IMPLEMENTATION（唯一实现 TU）";

    for (const auto& file : std::vector<std::string>{
             "VulkanBackend.cpp", "VulkanBackend.h", "NativeBridge.cpp",
             "GlesBackend.cpp", "GlesBackend.h"}) {
        const std::string src = stripLineComments(readSource(file));
        ASSERT_FALSE(src.empty()) << "源文件不可读: " << file;
        EXPECT_EQ(src.find("#define VMA_IMPLEMENTATION"), std::string::npos)
            << file << ": 出现第二份 VMA_IMPLEMENTATION（重复符号链接错误；"
            << "其余包含点只允许声明）";
    }
}

TEST(GpuAllocatorGuardTest, GpuAllocatorHasNoDirectBareVulkanAllocCalls) {
    // 分配器自身也不得手写裸分配——一切经 VMA（子分配/dedicated/预算统计）
    const std::string src = stripLineComments(readSource("gpu/GpuAllocator.cpp"));
    ASSERT_FALSE(src.empty());
    EXPECT_EQ(src.find("vkAllocateMemory"), std::string::npos)
        << "GpuAllocator.cpp 出现裸 vkAllocateMemory（应经 vmaCreateImage/vmaCreateBuffer）";
    EXPECT_EQ(src.find("vkFreeMemory"), std::string::npos)
        << "GpuAllocator.cpp 出现裸 vkFreeMemory（应经 vmaDestroyImage/vmaDestroyBuffer）";
    // staging 专用 host pool + 收缩面（P2.3）在位
    EXPECT_NE(src.find("vmaCreatePool"), std::string::npos)
        << "GpuAllocator.cpp 缺 staging host pool 创建（P2.3）";
    EXPECT_NE(src.find("vmaDestroyPool"), std::string::npos)
        << "GpuAllocator.cpp 缺池销毁收缩路径（VMA 3.x 无 vmaTrimPool，池重建=唯一确定性收缩）";
    // 大图 dedicated 阈值判定走共享纯函数（GpuBudgetMath 单源）
    EXPECT_NE(src.find("shouldUseDedicatedAllocation"), std::string::npos)
        << "dedicated 判定未走 GpuBudgetMath 单源（阈值双写 = 口径漂移）";
}

// ── 守卫 3：P2.3 消费点接线 ──────────────────────────────────

TEST(GpuAllocatorGuardTest, FrameBoundaryTrimConsumptionWired) {
    const std::string src = stripLineComments(readSource("NativeBridge.cpp"));
    ASSERT_FALSE(src.empty());
    EXPECT_NE(src.find("g_renderer->onMemoryTrim(trimLevel)"), std::string::npos)
        << "NativeBridge.beginFrame 未把 trim 档位交给 Rhi::onMemoryTrim（P2.3 消费点）";
}

TEST(GpuAllocatorGuardTest, KotlinFlagPushChannelWired) {
    // 旗标投递口：NativeBridge.kt ensureLoaded → nativeSetMemorySubsystem
    const std::string src = readSource("../../../../core/engine/src/main/java/"
                                       "com/xianxia/sect/core/nativebridge/NativeBridge.kt");
    // MR1_CPP_ROOT = cpp 根；Kotlin 相对路径按仓库层级回溯（断言可读性优先）
    ASSERT_FALSE(src.empty())
        << "NativeBridge.kt 不可读（相对路径漂移？请修守卫定位）";
    EXPECT_NE(src.find("nativeSetMemorySubsystem(NativeEngineFlag.memorySubsystem)"),
              std::string::npos)
        << "ensureLoaded 缺旗标投递——native 双轨开关无生产写者";
}

// ── 守卫 4：vendoring 钉死 ───────────────────────────────────

TEST(GpuAllocatorGuardTest, VmaHeaderVendoredAndVersionPinned) {
    const std::string src = readSource("third_party/vma/vk_mem_alloc.h");
    ASSERT_FALSE(src.empty()) << "third_party/vma/vk_mem_alloc.h 缺失（vendoring 被移除？）";
    EXPECT_NE(src.find("Version 3.3.0"), std::string::npos)
        << "VMA 版本与钉死值（v3.3.0）不符——升级属架构决策须过门禁";
    EXPECT_NE(src.find("1d8f600fd424278486eade7ed3e877c99f0846b1"), std::string::npos)
        << "provenance 注释缺钉死 commit——更新 vendored 文件须同步 README 与本守卫";
    EXPECT_NE(src.find("Vendored"), std::string::npos)
        << "缺 vendored 上游 provenance 头注释";
}

}  // namespace
}  // namespace gamecore
