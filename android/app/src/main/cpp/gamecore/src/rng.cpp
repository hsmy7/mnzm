#include "gamecore/rng/pcg_xsh_rr.h"

#include <cmath>

namespace gamecore::rng {

// Kotlin: nextGaussian(mean, stddev):
//   while(true) { u1 = nextDouble(); if (u1 == 0.0) continue;
//                 u2 = nextDouble();
//                 z = sqrt(-2*ln(u1)) * cos(2*PI*u2); return z*stddev + mean; }
//
// 精度注意：JVM Math.cos/log/sqrt（Android ART → Bionic libm，fdlibm 系）
// 与 C++ std::cos/log/sqrt（桌面 glibc 亦 fdlibm 系）通常位级一致；
// 差分对拍测试守护；若发现最后一位差异，改为内嵌 fdlibm 实现。
double DeterministicRng::nextGaussian(double mean, double stddev) {
    constexpr double kTwoPi = 6.2831853071795864769252867665590057683943387987502;
    while (true) {
        const double u1 = nextDouble();
        if (u1 == 0.0) continue;  // 避免 ln(0) = -inf
        const double u2 = nextDouble();
        const double z = std::sqrt(-2.0 * std::log(u1)) * std::cos(kTwoPi * u2);
        return z * stddev + mean;
    }
}

}  // namespace gamecore::rng
