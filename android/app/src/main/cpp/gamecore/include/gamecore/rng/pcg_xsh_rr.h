#pragma once

#include <cstdint>

// ============================================================
// DeterministicRng — PCG-XSH-RR 64→32 确定性 PRNG
//
// 精确复刻 Kotlin `DeterministicRng`（core/engine/util/DeterministicRng.kt），
// 逐行对照保证**存档确定性**：相同种子 + 相同调用次数 ⇒ 完全相同输出序列。
//
// 移植要点（与 Kotlin 语义逐项对齐）：
//   - state/increment 为 64 位回绕乘法（uint64 溢出 = JVM Long 溢出）
//   - nextInt: 64→32 xorshift 后**先截断 32 位再做 32 位循环旋转**
//     （先截断后旋转的次序不可颠倒，须与 Kotlin DeterministicRng 逐位一致）
//   - nextInt(bound): Lemire 无偏回绝采样，low32 按**有符号 Int 比较**（JVM 语义）
//   - nextDouble: (nextInt() & 0x7FFFFFFF) / 2^31（double 精确表示）
//   - nextGaussian: Box-Muller，不缓存配对值（保持 snapshot/restore 确定性）
//   - fromSeed: state = (seed << 1) | 1，随后丢弃一次 nextLong 混种
//
// 参考：PCG 算法 Melissa O'Neill (pcg-random.org)；项目 ADR 探索系统重构
// ============================================================
namespace gamecore::rng {

class DeterministicRng {
public:
    static constexpr uint64_t kMultiplier = 6364136223846793005ULL;
    static constexpr uint64_t kDefaultIncrement = 1ULL;

    /// 从种子创建（对应 Kotlin companion fromSeed）
    static DeterministicRng fromSeed(int64_t seed) {
        DeterministicRng rng(0, kDefaultIncrement);
        rng.state_ = (static_cast<uint64_t>(seed) << 1) | 1ULL;
        rng.nextLong();  // One round to mix seed
        return rng;
    }

    /// 默认构造（容器友好；语义 = 未初始化状态，使用前必须 fromSeed 或 restore）
    DeterministicRng() : state_(0), increment_(kDefaultIncrement) {}

    /// 直接构造（state + increment；increment 默认 1，对应 Kotlin 构造默认值）
    explicit DeterministicRng(int64_t state, uint64_t increment = kDefaultIncrement)
        : state_(static_cast<uint64_t>(state)), increment_(increment) {}

    /// 下一个 32 位随机整数（有符号 Int 语义，对应 Kotlin nextInt()）
    int32_t nextInt() {
        const uint64_t oldState = state_;
        state_ = oldState * kMultiplier + increment_;

        // Kotlin: val xorshifted = (((oldState ushr 18) xor oldState) ushr 27).toInt()
        // 先 64 位域 xorshift，再截断为 32 位
        const uint32_t xorshifted =
            static_cast<uint32_t>(((oldState >> 18) ^ oldState) >> 27);

        // Kotlin: val rot = (oldState ushr 59).toInt()  — 取低 5 位
        const uint32_t rot = static_cast<uint32_t>(oldState >> 59) & 0x1Fu;

        // Kotlin: return (xorshifted ushr rot) or (xorshifted shl ((-rot) and 31))
        // — 32 位域逻辑右移 + 回绕左移，按位或
        const uint32_t r = rot;
        const uint32_t shifted = (xorshifted >> r) | (xorshifted << ((0u - r) & 31u));
        return static_cast<int32_t>(shifted);
    }

    /// [0, bound) 范围随机整数（Lemire 无偏回绝采样，对应 Kotlin nextInt(bound)）
    /// 前置条件：bound > 0（Kotlin require；调用方保证）
    int32_t nextInt(int32_t bound) {
        // Kotlin: val t = (nextInt().toLong() and 0xFFFFFFFFL) * bound.toLong()
        const uint64_t n1 = static_cast<uint32_t>(nextInt());
        const uint64_t t = n1 * static_cast<uint64_t>(bound);  // 64 位回绕

        // Kotlin: val low32 = (t and 0xFFFFFFFFL).toInt() — 有符号 Int
        const int32_t low32 = static_cast<int32_t>(t & 0xFFFFFFFFULL);

        // Kotlin: if (low32 < bound) — **有符号比较**（low32 可能为负）
        if (low32 < bound) {
            // Kotlin: val threshold = Int.MAX_VALUE.toLong() % bound.toLong()
            const int64_t threshold = INT32_MAX % static_cast<int64_t>(bound);
            // Kotlin: while (low32 < threshold.toInt()) — threshold < bound ≤ INT32_MAX，转 int32 无损
            while (low32 < static_cast<int32_t>(threshold)) {
                const uint64_t newT =
                    static_cast<uint32_t>(nextInt()) * static_cast<uint64_t>(bound);
                return static_cast<int32_t>(newT >> 32);
            }
        }
        return static_cast<int32_t>(t >> 32);
    }

    /// [0, bound) 范围随机 Long（对应 Kotlin nextLong(bound)）
    int64_t nextLong(int64_t bound = INT64_MAX) {
        if (bound <= 0) return 0L;
        if (bound == INT64_MAX) {
            // Kotlin: return nextInt().toLong() and Long.MAX_VALUE
            return static_cast<int64_t>(nextInt()) & INT64_MAX;
        }
        // Kotlin: return (nextInt().toLong() and 0xFFFFFFFFL) % bound — 非负被除数
        return static_cast<int64_t>(static_cast<uint32_t>(nextInt())) % bound;
    }

    /// [0.0, 1.0) 范围随机 Double（对应 Kotlin nextDouble()）
    double nextDouble() {
        // Kotlin: return (nextInt().toLong() and 0x7FFFFFFFL) / 2147483648.0
        const int64_t bits = static_cast<int64_t>(nextInt()) & 0x7FFFFFFFLL;
        return static_cast<double>(bits) / 2147483648.0;
    }

    /// 正态分布（Box-Muller；对应 Kotlin nextGaussian(mean, stddev)）
    /// 不缓存配对值——每次调用消耗恰好 2 次 nextDouble
    double nextGaussian(double mean = 0.0, double stddev = 1.0);

    /// 当前状态快照（对应 Kotlin snapshot()，存档导出）
    int64_t snapshot() const { return static_cast<int64_t>(state_); }

    /// 从快照恢复（对应 Kotlin restore(savedState)，读档恢复）
    void restore(int64_t savedState) { state_ = static_cast<uint64_t>(savedState); }

private:
    uint64_t state_;
    uint64_t increment_;
};

}  // namespace gamecore::rng
