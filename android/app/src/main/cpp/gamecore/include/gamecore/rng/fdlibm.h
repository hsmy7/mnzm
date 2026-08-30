#pragma once

// ============================================================
// fdlibm.h — 内嵌 fdlibm 余弦实现（C-12 清偿）
//
// 来源：OpenJDK jdk-21 java.lang.FdLibm（GPLv2 + Classpath Exception，
// "Freely Distributable Math Library" 5.3 的 Java 移植）——cos 依赖链
// （Cos.compute / __kernel_cos / Sin.__kernel_sin / RemPio2 /
// KernelRemPio2 + __HI/__LO 位操作辅助）逐句移植为 C++20。
//
// 背景（C-12 审查登记项）：JVM Math.cos 与 C++ std::cos 在 Box-Muller
// nextGaussian 中出现最后一位（1 ULP）差异——桌面 glibc 的 cos 与
// JVM fdlibm 系实现版本/编译器优化不同；内嵌 JDK fdlibm 保证与
// Kotlin 权威（JVM Math.cos）位级一致。
//
// 本文件仅提供 nextGaussian 需要的 cos（含 sin 内核），
// log/sqrt 经对拍验证位级一致（继续用 std::）。
// ============================================================

#include <bit>
#include <cmath>
#include <cstdint>

namespace gamecore::rng::fdlibm {

namespace detail {

constexpr double TWO24 = 16777216.0;  // 0x1.0p24

constexpr int32_t SIGN_BIT = 0x8000'0000;
constexpr int32_t EXP_BITS = 0x7ff0'0000;
constexpr int32_t EXP_SIGNIF_BITS = 0x7fff'ffff;

inline int32_t __HI(double x) {
    return static_cast<int32_t>(std::bit_cast<std::uint64_t>(x) >> 32);
}
inline int32_t __LO(double x) {
    return static_cast<int32_t>(std::bit_cast<std::uint64_t>(x));
}
inline double __LO(double x, int32_t low) {
    const auto bits = std::bit_cast<std::uint64_t>(x);
    return std::bit_cast<double>((bits & 0xFFFF'FFFF'0000'0000ULL) |
                                 (static_cast<std::uint64_t>(low) & 0x0000'0000'FFFF'FFFFULL));
}
inline double __HI(double x, int32_t high) {
    const auto bits = std::bit_cast<std::uint64_t>(x);
    return std::bit_cast<double>((bits & 0x0000'0000'FFFF'FFFFULL) |
                                 (static_cast<std::uint64_t>(high) << 32));
}
inline double __HI_LO(int32_t high, int32_t low) {
    return std::bit_cast<double>((static_cast<std::uint64_t>(high) << 32) |
                                 (static_cast<std::uint64_t>(low) & 0xFFFF'FFFFULL));
}

/// Java Math.scalb(x, n) 等价（x * 2^n，IEEE 精确）
inline double scalb(double x, int32_t n) { return std::scalbn(x, n); }

// ── Sin 内核（cos 规约后 quadrant 1/3 使用） ─────────────────────
constexpr double S1 = -0x1.5555555555549p-3;  // -1.66666666666666324348e-01
constexpr double S2 =  0x1.111111110f8a6p-7;  //  8.33333333332248946124e-03
constexpr double S3 = -0x1.a01a019c161d5p-13; // -1.98412698298579493134e-04
constexpr double S4 =  0x1.71de357b1fe7dp-19; //  2.75573137070700676789e-06
constexpr double S5 = -0x1.ae5e68a2b9cebp-26; // -2.50507602534068634195e-08
constexpr double S6 =  0x1.5d93a5acfd57cp-33; //  1.58969099521155010221e-10

inline double __kernel_sin(double x, double y, int32_t iy) {
    double z, r, v;
    int32_t ix = __HI(x) & EXP_SIGNIF_BITS;   // high word of x
    if (ix < 0x3e40'0000) {                   // |x| < 2**-27
        if (static_cast<int32_t>(x) == 0) {   // generate inexact
            return x;
        }
    }
    z = x * x;
    v = z * x;
    r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
    if (iy == 0) {
        return x + v * (S1 + z * r);
    } else {
        return x - ((z * (0.5 * y - v * r) - y) - v * S1);
    }
}

// ── Cos 内核 ─────────────────────────────────────────────────────
constexpr double C1 =  0x1.555555555554cp-5;  //  4.16666666666666019037e-02
constexpr double C2 = -0x1.6c16c16c15177p-10; // -1.38888888888741095749e-03
constexpr double C3 =  0x1.a01a019cb159p-16;  //  2.48015872894767294178e-05
constexpr double C4 = -0x1.27e4f809c52adp-22; // -2.75573143513906633035e-07
constexpr double C5 =  0x1.1ee9ebdb4b1c4p-29; //  2.08757232129817482790e-09
constexpr double C6 = -0x1.8fae9be8838d4p-37; // -1.13596475577881948265e-11

inline double __kernel_cos(double x, double y) {
    double a, hz, z, r, qx = 0.0;
    int32_t ix = __HI(x) & EXP_SIGNIF_BITS;   // ix = |x|'s high word
    if (ix < 0x3e40'0000) {                   // if x < 2**27
        if (static_cast<int32_t>(x) == 0) {   // generate inexact
            return 1.0;
        }
    }
    z = x * x;
    r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
    if (ix < 0x3FD3'3333) {                   // if |x| < 0.3
        return 1.0 - (0.5 * z - (z * r - x * y));
    } else {
        if (ix > 0x3fe9'0000) {               // x > 0.78125
            qx = 0.28125;
        } else {
            qx = __HI_LO(ix - 0x0020'0000, 0);
        }
        hz = 0.5 * z - qx;
        a = 1.0 - qx;
        return a - (hz - (z * r - x * y));
    }
}

// ── RemPio2（大参数规约） ────────────────────────────────────────

/// 2/pi，396 Hex 位（JDK FdLibm two_over_pi 表）
inline const int32_t two_over_pi[] = {
    0xA2F983, 0x6E4E44, 0x1529FC, 0x2757D1, 0xF534DD, 0xC0DB62,
    0x95993C, 0x439041, 0xFE5163, 0xABDEBB, 0xC561B7, 0x246E3A,
    0x424DD2, 0xE00649, 0x2EEA09, 0xD1921C, 0xFE1DEB, 0x1CB129,
    0xA73EE8, 0x8235F5, 0x2EBB44, 0x84E99C, 0x7026B4, 0x5F7E41,
    0x3991D6, 0x398353, 0x39F49C, 0x845F8B, 0xBDF928, 0x3B1FF8,
    0x97FFDE, 0x05980F, 0xEF2F11, 0x8B5A0A, 0x6D1F6D, 0x367ECF,
    0x27CB09, 0xB74F46, 0x3F669E, 0x5FEA2D, 0x7527BA, 0xC7EBE5,
    0xF17B3D, 0x0739F7, 0x8A5292, 0xEA6BFB, 0x5FB11F, 0x8D5D08,
    0x560330, 0x46FC7B, 0x6BABF0, 0xCFBC20, 0x9AF436, 0x1DA9E3,
    0x91615E, 0xE61B08, 0x659985, 0x5F14A0, 0x68408D, 0xFFD880,
    0x4D7327, 0x310606, 0x1556CA, 0x73A8C9, 0x60E27B, 0xC08C6B,
};

inline const int32_t npio2_hw[] = {
    0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C,
    0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C,
    0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
    0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
    0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB,
    0x404858EB, 0x404921FB,
};

constexpr double invpio2 = 0x1.45f306dc9c883p-1;   // 6.36619772367581382433e-01
constexpr double pio2_1  = 0x1.921fb544p0;         // 1.57079632673412561417e+00
constexpr double pio2_1t = 0x1.0b4611a626331p-34;  // 6.07710050650619224932e-11
constexpr double pio2_2  = 0x1.0b4611a6p-34;       // 6.07710050630396597660e-11
constexpr double pio2_2t = 0x1.3198a2e037073p-69;  // 2.02226624879595063154e-21
constexpr double pio2_3  = 0x1.3198a2ep-69;        // 2.02226624871116645580e-21
constexpr double pio2_3t = 0x1.b839a252049c1p-104; // 8.47842766036889956997e-32

// KernelRemPio2 常量
inline const int32_t init_jk[] = {2, 3, 4, 6};
inline const double PIo2[] = {
    0x1.921fb4p0,    // 1.57079625129699707031e+00
    0x1.4442dp-24,   // 7.54978941586159635335e-08
    0x1.846988p-48,  // 5.39030252995776476554e-15
    0x1.8cc516p-72,  // 3.28200341580791294123e-22
    0x1.01b838p-96,  // 1.27065575308067607349e-29
    0x1.a25204p-120, // 1.22933308981111328932e-36
    0x1.382228p-145, // 2.73370053816464559624e-44
    0x1.9f31dp-169,  // 2.16741683877804819444e-51
};
constexpr double twon24 = 0x1.0p-24;  // 5.96046447753906250000e-08

/// JDK FdLibm KernelRemPio2.__kernel_rem_pio2（prec=2 路径；其余未用但保留）
inline int32_t __kernel_rem_pio2(double* x, double* y, int32_t e0,
                                 int32_t nx, int32_t prec,
                                 const int32_t* ipio2) {
    int32_t jz, jx, jv, jp, jk, carry, n, i, j, k, m, q0, ih;
    int32_t iq[20];
    double z, fw;
    double f[20];
    double fq[20];
    double q[20];

    jk = init_jk[prec];
    jp = jk;

    jx = nx - 1;
    jv = (e0 - 3) / 24;
    if (jv < 0) jv = 0;
    q0 = e0 - 24 * (jv + 1);

    j = jv - jx;
    m = jx + jk;
    for (i = 0; i <= m; i++, j++) {
        f[i] = (j < 0) ? 0.0 : static_cast<double>(ipio2[j]);
    }

    for (i = 0; i <= jk; i++) {
        for (j = 0, fw = 0.0; j <= jx; j++) {
            fw += x[j] * f[jx + i - j];
        }
        q[i] = fw;
    }

    jz = jk;
    while (true) {
        for (i = 0, j = jz, z = q[jz]; j > 0; i++, j--) {
            fw = static_cast<double>(static_cast<int32_t>(twon24 * z));
            iq[i] = static_cast<int32_t>(z - TWO24 * fw);
            z = q[j - 1] + fw;
        }

        z = scalb(z, q0);
        z -= 8.0 * std::floor(z * 0.125);
        n = static_cast<int32_t>(z);
        z -= static_cast<double>(n);
        ih = 0;
        if (q0 > 0) {
            i = (iq[jz - 1] >> (24 - q0));
            n += i;
            iq[jz - 1] -= i << (24 - q0);
            ih = iq[jz - 1] >> (23 - q0);
        } else if (q0 == 0) {
            ih = iq[jz - 1] >> 23;
        } else if (z >= 0.5) {
            ih = 2;
        }

        if (ih > 0) {
            n += 1;
            carry = 0;
            for (i = 0; i < jz; i++) {
                j = iq[i];
                if (carry == 0) {
                    if (j != 0) {
                        carry = 1;
                        iq[i] = 0x100'0000 - j;
                    }
                } else {
                    iq[i] = 0xff'ffff - j;
                }
            }
            if (q0 > 0) {
                switch (q0) {
                case 1: iq[jz - 1] &= 0x7f'ffff; break;
                case 2: iq[jz - 1] &= 0x3f'ffff; break;
                }
            }
            if (ih == 2) {
                z = 1.0 - z;
                if (carry != 0) {
                    z -= scalb(1.0, q0);
                }
            }
        }

        if (z == 0.0) {
            j = 0;
            for (i = jz - 1; i >= jk; i--) {
                j |= iq[i];
            }
            if (j == 0) {
                for (k = 1; iq[jk - k] == 0; k++) {
                }
                for (i = jz + 1; i <= jz + k; i++) {
                    f[jx + i] = static_cast<double>(ipio2[jv + i]);
                    for (j = 0, fw = 0.0; j <= jx; j++) {
                        fw += x[j] * f[jx + i - j];
                    }
                    q[i] = fw;
                }
                jz += k;
                continue;
            } else {
                break;
            }
        } else {
            break;
        }
    }

    if (z == 0.0) {
        jz -= 1;
        q0 -= 24;
        while (iq[jz] == 0) {
            jz--;
            q0 -= 24;
        }
    } else {
        z = scalb(z, -q0);
        if (z >= TWO24) {
            fw = static_cast<double>(static_cast<int32_t>(twon24 * z));
            iq[jz] = static_cast<int32_t>(z - TWO24 * fw);
            jz += 1;
            q0 += 24;
            iq[jz] = static_cast<int32_t>(fw);
        } else {
            iq[jz] = static_cast<int32_t>(z);
        }
    }

    fw = scalb(1.0, q0);
    for (i = jz; i >= 0; i--) {
        q[i] = fw * static_cast<double>(iq[i]);
        fw *= twon24;
    }

    for (i = jz; i >= 0; i--) {
        for (fw = 0.0, k = 0; k <= jp && k <= jz - i; k++) {
            fw += PIo2[k] * q[i + k];
        }
        fq[jz - i] = fw;
    }

    switch (prec) {
    case 0:
        fw = 0.0;
        for (i = jz; i >= 0; i--) fw += fq[i];
        y[0] = (ih == 0) ? fw : -fw;
        break;
    case 1:
    case 2:
        fw = 0.0;
        for (i = jz; i >= 0; i--) fw += fq[i];
        y[0] = (ih == 0) ? fw : -fw;
        fw = fq[0] - fw;
        for (i = 1; i <= jz; i++) fw += fq[i];
        y[1] = (ih == 0) ? fw : -fw;
        break;
    case 3:
        for (i = jz; i > 0; i--) {
            fw = fq[i - 1] + fq[i];
            fq[i] += fq[i - 1] - fw;
            fq[i - 1] = fw;
        }
        for (i = jz; i > 1; i--) {
            fw = fq[i - 1] + fq[i];
            fq[i] += fq[i - 1] - fw;
            fq[i - 1] = fw;
        }
        for (fw = 0.0, i = jz; i >= 2; i--) fw += fq[i];
        if (ih == 0) {
            y[0] = fq[0];
            y[1] = fq[1];
            y[2] = fw;
        } else {
            y[0] = -fq[0];
            y[1] = -fq[1];
            y[2] = -fw;
        }
        break;
    }
    return n;
}

/// JDK FdLibm RemPio2.__ieee754_rem_pio2
inline int32_t __ieee754_rem_pio2(double x, double* y) {
    double z = 0.0, w, t, r, fn;
    double tx[3];
    int32_t e0, i, j, nx, n, ix, hx;

    hx = __HI(x);
    ix = hx & EXP_SIGNIF_BITS;
    if (ix <= 0x3fe9'21fb) {   // |x| ~<= pi/4
        y[0] = x;
        y[1] = 0;
        return 0;
    }
    if (ix < 0x4002'd97c) {    // |x| < 3pi/4, n=+-1
        if (hx > 0) {
            z = x - pio2_1;
            if (ix != 0x3ff9'21fb) {
                y[0] = z - pio2_1t;
                y[1] = (z - y[0]) - pio2_1t;
            } else {
                z -= pio2_2;
                y[0] = z - pio2_2t;
                y[1] = (z - y[0]) - pio2_2t;
            }
            return 1;
        } else {
            z = x + pio2_1;
            if (ix != 0x3ff'921fb) {
                y[0] = z + pio2_1t;
                y[1] = (z - y[0]) + pio2_1t;
            } else {
                z += pio2_2;
                y[0] = z + pio2_2t;
                y[1] = (z - y[0]) + pio2_2t;
            }
            return -1;
        }
    }
    if (ix <= 0x4139'21fb) {   // |x| ~<= 2^19*(pi/2)
        t = std::fabs(x);
        n = static_cast<int32_t>(t * invpio2 + 0.5);
        fn = static_cast<double>(n);
        r = t - fn * pio2_1;
        w = fn * pio2_1t;
        if (n < 32 && ix != npio2_hw[n - 1]) {
            y[0] = r - w;
        } else {
            j = ix >> 20;
            y[0] = r - w;
            i = j - ((__HI(y[0]) >> 20) & 0x7ff);
            if (i > 16) {
                t = r;
                w = fn * pio2_2;
                r = t - w;
                w = fn * pio2_2t - ((t - r) - w);
                y[0] = r - w;
                i = j - ((__HI(y[0]) >> 20) & 0x7ff);
                if (i > 49) {
                    t = r;
                    w = fn * pio2_3;
                    r = t - w;
                    w = fn * pio2_3t - ((t - r) - w);
                    y[0] = r - w;
                }
            }
        }
        y[1] = (r - y[0]) - w;
        if (hx < 0) {
            y[0] = -y[0];
            y[1] = -y[1];
            return -n;
        } else {
            return n;
        }
    }
    if (ix >= EXP_BITS) {      // inf or NaN
        y[0] = y[1] = x - x;
        return 0;
    }
    z = __LO(z, __LO(x));
    e0 = (ix >> 20) - 1046;
    z = __HI(z, ix - (e0 << 20));
    for (i = 0; i < 2; i++) {
        tx[i] = static_cast<double>(static_cast<int32_t>(z));
        z = (z - tx[i]) * TWO24;
    }
    tx[2] = z;
    nx = 3;
    while (tx[nx - 1] == 0.0) {
        nx--;
    }
    n = __kernel_rem_pio2(tx, y, e0, nx, 2, two_over_pi);
    if (hx < 0) {
        y[0] = -y[0];
        y[1] = -y[1];
        return -n;
    }
    return n;
}

}  // namespace detail

/// cos(x)（JDK FdLibm Cos.compute 等价——与 JVM Math.cos 位级一致）
inline double cos(double x) {
    double y[2];
    double z = 0.0;
    int32_t n, ix;

    ix = detail::__HI(x);
    ix &= detail::EXP_SIGNIF_BITS;
    if (ix <= 0x3fe9'21fb) {                    // |x| ~< pi/4
        return detail::__kernel_cos(x, z);
    } else if (ix >= detail::EXP_BITS) {        // cos(Inf or NaN) is NaN
        return x - x;
    } else {                                    // argument reduction needed
        n = detail::__ieee754_rem_pio2(x, y);
        switch (n & 3) {
        case 0: return  detail::__kernel_cos(y[0], y[1]);
        case 1: return -detail::__kernel_sin(y[0], y[1], 1);
        case 2: return -detail::__kernel_cos(y[0], y[1]);
        default: return  detail::__kernel_sin(y[0], y[1], 1);
        }
    }
}

// ── log 内核（经典 fdlibm e_log.c——StrictMath.log 同源，C-12 清偿） ──
// C-12 实测：JVM StrictMath.log 与 C++ std::log 在部分输入差最后一位
// （glibc 与 fdlibm 版本差异）；内嵌 fdlibm 保证位级一致。

namespace detail {

constexpr double ln2_hi = 6.93147180369123816490e-01;  /* 3fe62e42 fee00000 */
constexpr double ln2_lo = 1.90821492927058770002e-10;  /* 3dea39ef 35793c76 */
constexpr double kTwo54 = 1.80143985094819840000e+16;  /* 43500000 00000000 */
constexpr double Lg1 = 6.666666666666735130e-01;  /* 3FE55555 55555593 */
constexpr double Lg2 = 3.999999999940941908e-01;  /* 3FD99999 9997FA04 */
constexpr double Lg3 = 2.857142874366239149e-01;  /* 3FD24924 94229359 */
constexpr double Lg4 = 2.222219843214978396e-01;  /* 3FCC71C5 1D8E78AF */
constexpr double Lg5 = 1.818357216161805012e-01;  /* 3FC74664 96CB03DE */
constexpr double Lg6 = 1.531383769920937332e-01;  /* 3FC39A09 D078C69F */
constexpr double Lg7 = 1.479819860511658591e-01;  /* 3FC2F112 DF3E5244 */

}  // namespace detail

/// log(x)（经典 fdlibm __ieee754_log——StrictMath.log 等价）
inline double log(double x) {
    using detail::__HI;
    using detail::__LO;
    double hfsq, f, s, z, R, w, t1, t2, dk;
    int32_t k, hx, i, j;
    std::uint32_t lx;

    hx = __HI(x);
    lx = static_cast<std::uint32_t>(__LO(x));

    k = 0;
    if (hx < 0x0010'0000) {                    // x < 2**-1022
        if (((hx & 0x7fff'ffff) | lx) == 0) {
            return -detail::kTwo54 / 0.0;      // log(+-0) = -inf
        }
        if (hx < 0) return (x - x) / 0.0;      // log(-#) = NaN
        k -= 54;
        x *= detail::kTwo54;                   // subnormal: scale up
        hx = __HI(x);
    }
    if (hx >= 0x7ff0'0000) return x + x;
    k += (hx >> 20) - 1023;
    hx &= 0x000f'ffff;
    i = (hx + 0x95f64) & 0x100'000;
    x = detail::__HI(x, hx | (i ^ 0x3ff0'0000));   // normalize x or x/2
    k += (i >> 20);
    f = x - 1.0;
    if ((0x000f'ffff & (2 + hx)) < 3) {        // |f| < 2**-20
        if (f == 0.0) {
            if (k == 0) return 0.0;
            else {
                dk = static_cast<double>(k);
                return dk * detail::ln2_hi + dk * detail::ln2_lo;
            }
        }
        R = f * f * (0.5 - 0.33333333333333333 * f);
        if (k == 0) {
            return f - R;
        } else {
            dk = static_cast<double>(k);
            return dk * detail::ln2_hi - ((R - dk * detail::ln2_lo) - f);
        }
    }
    s = f / (2.0 + f);
    dk = static_cast<double>(k);
    z = s * s;
    i = hx - 0x6147a;
    w = z * z;
    j = 0x6b851 - hx;
    t1 = w * (detail::Lg2 + w * (detail::Lg4 + w * detail::Lg6));
    t2 = z * (detail::Lg1 + w * (detail::Lg3 + w * (detail::Lg5 + w * detail::Lg7)));
    i |= j;
    R = t2 + t1;
    if (i > 0) {
        hfsq = 0.5 * f * f;
        if (k == 0) {
            return f - (hfsq - s * (hfsq + R));
        } else {
            return dk * detail::ln2_hi - ((hfsq - (s * (hfsq + R) + dk * detail::ln2_lo)) - f);
        }
    } else {
        if (k == 0) {
            return f - s * (f - R);
        } else {
            return dk * detail::ln2_hi - ((s * (f - R) - dk * detail::ln2_lo) - f);
        }
    }
}

}  // namespace gamecore::rng::fdlibm
