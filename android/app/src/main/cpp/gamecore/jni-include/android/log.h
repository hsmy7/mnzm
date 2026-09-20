#pragma once

// ============================================================
// android/log.h — 桌面（Windows/llvm-mingw）替身
//
// 动机：`GameCoreBridge.cpp` 是 **Android JNI 桥**（用 JNI 接口）；
// `scripts/build-desktop-jni.ps1` 用 llvm-mingw 把它编成 Windows `.so` 供
// JUnit 对拍测试加载。但桥里 `#include <android/log.h>` 依赖 NDK sysroot 的
// Bionic 头——**不能**把 NDK sysroot 加进 include 路径（会与 llvm-mingw 的
// libc++/UCRT 头冲突：`asm/types.h`、`corecrt.h` typedef 重定义等）。
//
// 解法：提供最小替身，把 `__android_log_print` 映射到 stdout。
// 桌面测试日志据此可见（无需 adb logcat）；Android 构建**不受影响**——
// NDK 的 CMake 工具链 include 路径优先于本目录（本目录仅在
// build-desktop-jni.ps1 里以 `-I` 形式加入，位于最前但 Android 构建不用该脚本）。
//
// 说明：本文件只声明桥实际使用的 5 个符号（4 个级别常量 + 1 个函数），
// 不追求与 NDK 头 ABI 完备。
// ============================================================

#include <cstdarg>
#include <cstdio>

// ── 桥实际使用的类型 / 级别常量（NDK 头同名）──────────────────
// 级别值必须与 NDK 对齐（ANDROID_LOG_INFO = 4 等）——桥用 switch 映射
// gamecore::LogLevel，值错只影响日志前缀、不影响逻辑。
enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT = 1,
    ANDROID_LOG_VERBOSE = 2,
    ANDROID_LOG_DEBUG = 3,
    ANDROID_LOG_INFO = 4,
    ANDROID_LOG_WARN = 5,
    ANDROID_LOG_ERROR = 6,
    ANDROID_LOG_FATAL = 7,
    ANDROID_LOG_SILENT = 8,
};

inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    static const char* const kLevels[] = {"?", "?", "V", "D", "I", "W", "E", "F", "?"};
    const char* level = (prio >= 0 && prio <= 8) ? kLevels[prio] : "?";
    std::fprintf(stdout, "[%s/%s] ", level, tag ? tag : "");
    va_list args;
    va_start(args, fmt);
    std::vfprintf(stdout, fmt, args);
    va_end(args);
    std::fputc('\n', stdout);
    return 0;
}
