// ============================================================
// determinism_probe_test.cpp — FP 确定性对拍探针（桌面腿，R0.2）
//
// 守护目标：kGoldenDigest 位锁定（x86-64 桌面基线）。golden 变更 =
// 探针场景/引擎 FP 行为变化——任何翻转必须在提交说明中注明并重录
// （arm64 真机腿经 GameCoreBridge.nativeFpDeterminismProbe 断言同一 golden）。
// ============================================================
#include <gtest/gtest.h>

#include "gamecore/determinism_probe.h"

namespace {

using gamecore::probe::digestHex;
using gamecore::probe::kGoldenDigest;
using gamecore::probe::runDeterminismProbe;

TEST(DeterminismProbeTest, DigestMatchesGoldenBaseline) {
    const auto result = runDeterminismProbe();
    EXPECT_EQ(kGoldenDigest, result.digest)
        << "FP determinism transcript diverged from golden baseline."
        << " actual=0x" << digestHex(result.digest)
        << " golden=0x" << digestHex(kGoldenDigest)
        << " (intentional transcript change? re-record kGoldenDigest on desktop CI"
        << " and note in commit message)";
}

TEST(DeterminismProbeTest, DigestIsStableAcrossRepeatedRuns) {
    const auto first = runDeterminismProbe();
    const auto second = runDeterminismProbe();
    EXPECT_EQ(first.digest, second.digest)
        << "probe must be self-contained (no global state leakage between runs)";
}

}  // namespace
