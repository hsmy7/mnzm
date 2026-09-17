package com.xianxia.sect

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xianxia.sect.core.nativebridge.GameCoreBridge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NativeFpDeterminismTest — FP 确定性对拍探针（R0.2 真机腿）。
 *
 * 经真机 arm64 的 libgamecorejni（native-game-core）运行
 * [GameCoreBridge.nativeFpDeterminismProbe]，断言摘要与桌面腿
 * （x86-64 GTest `determinism_probe_test.cpp`，经 CI `cpp-engine-test`
 * job 周期执行）录制的 golden **逐位一致**——浮点位一致的跨架构工程锁定。
 *
 * 🔴 golden 同步约定：本常量与 C++ 侧 `gamecore/determinism_probe.h`
 * 的 `kGoldenDigest` 必须一致；转录重定基线时两处一起改。
 * 周期执行：`.github/workflows/arm64-fp-determinism.yml`（Firebase Test Lab）。
 */
@RunWith(AndroidJUnit4::class)
class NativeFpDeterminismTest {

    /** 桌面腿 golden（C++ kGoldenDigest = 0x490e8dc522e12921 的十六进制） */
    private val desktopGoldenDigest = "490e8dc522e12921"

    @Test
    fun fpProbeDigestMatchesDesktopGolden() {
        GameCoreBridge.ensureLoaded()
        val digest = GameCoreBridge.nativeFpDeterminismProbe()
        assertEquals(
            "arm64 FP transcript diverged from desktop golden — " +
                "浮点位跨架构漂移（检查 -ffp-contract=off 是否覆盖全部编译路径）",
            desktopGoldenDigest,
            digest
        )
    }
}
