#include "gamecore/rng/pcg_xsh_rr.h"

#include <cmath>

#include "gamecore/rng/fdlibm.h"

namespace gamecore::rng {

// Kotlin: nextGaussian(mean, stddev):
//   while(true) { u1 = nextDouble(); if (u1 == 0.0) continue;
//                 u2 = nextDouble();
//                 z = sqrt(-2*ln(u1)) * cos(2*PI*u2); return z*stddev + mean; }
//
// 精度：JVM StrictMath.log/cos/sqrt 为纯 Java fdlibm（无平台
// intrinsic）——C++ 内嵌 fdlibm（gamecore/rng/fdlibm.h：log + cos）保证
// 与 Kotlin 权威（StrictMath）跨平台位级一致；实测 std::log/std::cos 在
// 部分输入差最后一位（glibc 与 fdlibm 版本差异）。sqrt 沿用 std::（对拍
// 验证位级一致）。
double DeterministicRng::nextGaussian(double mean, double stddev) {
    constexpr double kTwoPi = 6.2831853071795864769252867665590057683943387987502;
    while (true) {
        const double u1 = nextDouble();
        if (u1 == 0.0) continue;  // 避免 ln(0) = -inf
        const double u2 = nextDouble();
        const double z = std::sqrt(-2.0 * fdlibm::log(u1)) *
                         fdlibm::cos(kTwoPi * u2);
        return z * stddev + mean;
    }
}

}  // namespace gamecore::rng
