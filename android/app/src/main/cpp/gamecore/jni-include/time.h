#pragma once

// ============================================================
// time.h — 桌面（Windows/llvm-mingw）替身：补 `clock_gettime64` 实现
//
// 动机（实测根因，两步）：
//   1. llvm-mingw 的 `pthread_time.h`（winpthreads 头）把 `clock_gettime()`
//      定义成 **inline 函数**（WINPTHREADS_ALWAYS_INLINE），体内委托
//      `clock_gettime64(id, (struct _timespec64*)tp)`；
//   2. 但该符号在 winpthreads 库中**只有声明没有实现**
//      （`WINPTHREAD_API int __cdecl clock_gettime64(...)`，API 宏默认为空、
//      非 dllimport）⇒ 桌面对拍桥链接期
//      `ld.lld: error: undefined symbol: clock_gettime64`。
//
// 解法（补符号，不挡头）：本头**不遮蔽**系统 `time.h` 的任何声明——只在
// `#include_next <time.h>` 之后**补上缺失的 `clock_gettime64` 实现**，
// 让 pthread_time.h 的 inline `clock_gettime` 链接闭合。早期方案曾试图
// `#define WIN_PTHREADS_TIME_H` 挡掉 pthread_time.h 再自行补
// `clock_gettime`——实测与该头的 inline 定义冲突（redefinition），且挡头
// 会连带丢掉 CLOCK_* 宏，已废弃。
//
// 语义：
//   - `CLOCK_REALTIME`：GetSystemTimeAsFileTime（FILETIME 1601 纪元 → Unix）；
//   - 单调钟（CLOCK_MONOTONIC / BOOTTIME 语义）：QueryPerformanceCounter
//     （`GetTickCount64` 精度仅 ~15.6ms，不适合帧/结算计时）。
//   - `CLOCK_BOOTTIME` 在 Windows 无对应概念 ⇒ 不由本头定义，让
//     `GameCoreBridge.cpp` 的 `#if defined(CLOCK_BOOTTIME)` 落到 MONOTONIC
//     分支（桌面测试无需跨休眠连续）。
//
// 影响面：本头仅位于 `gamecore/jni-include/`（桌面对拍专用 include 根，
// **不进 Android 构建**——NDK 工具链 include 路径不含本目录）⇒ 真机/模拟器
// 走 Bionic，语义零变化。
// ============================================================

#include_next <time.h>

#include <windows.h>

#ifndef GAMECORE_JNI_INCLUDE_TIME_SHIM
#define GAMECORE_JNI_INCLUDE_TIME_SHIM

/// winpthreads 缺失的 `clock_gettime64` 实现（inline + extern "C"：
/// 跨 TU 由 COMDAT 去重，不会多重定义）。
extern "C" inline int __cdecl clock_gettime64(clockid_t clock_id,
                                              struct _timespec64* tp) {
    if (tp == nullptr) return -1;
    if (clock_id == CLOCK_REALTIME) {
        FILETIME ft{};
        ::GetSystemTimeAsFileTime(&ft);
        // FILETIME 纪元 = 1601-01-01，以 100ns 为单位
        const unsigned long long ticks =
            (static_cast<unsigned long long>(ft.dwHighDateTime) << 32) | ft.dwLowDateTime;
        const unsigned long long epochDelta = 116444736000000000ULL;  // 1601→1970
        const unsigned long long unix100ns = ticks - epochDelta;
        tp->tv_sec = static_cast<long long>(unix100ns / 10000000ULL);
        tp->tv_nsec = static_cast<long>(unix100ns % 10000000ULL) * 100;
        return 0;
    }
    // CLOCK_MONOTONIC（BOOTTIME 语义近似——见文件头注释）
    LARGE_INTEGER freq{};
    LARGE_INTEGER counter{};
    if (!::QueryPerformanceFrequency(&freq) || freq.QuadPart == 0) return -1;
    if (!::QueryPerformanceCounter(&counter)) return -1;
    const long long totalNs =
        static_cast<long long>((counter.QuadPart * 1000000000LL) / freq.QuadPart);
    tp->tv_sec = totalNs / 1000000000LL;
    tp->tv_nsec = static_cast<long>(totalNs % 1000000000LL);
    return 0;
}

#endif  // GAMECORE_JNI_INCLUDE_TIME_SHIM
