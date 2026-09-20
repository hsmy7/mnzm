#pragma once

// ============================================================
// unistd.h — 桌面（Windows/llvm-mingw）替身：补 `gettid()`
//
// 动机：`GameCoreBridge.cpp` 用 `gettid()`（**Bionic 扩展**，POSIX 标准
// `unistd.h` 与 MinGW 的 `unistd.h` 均不提供）记录 JNI owner 线程
// （NDEBUG 之外的 owner 线程契约断言）。该符号在 Linux/Android 由 Bionic
// 提供，在 llvm-mingw 下缺失 ⇒ 桌面桥编译失败。
//
// 解法：在 `gamecore/jni-include/`（桌面对拍专用 include 根，**不进 Android
// 构建**——见 `scripts/build-desktop-jni.ps1`）提供最小替身，映射到 Win32
// `GetCurrentThreadId()`。语义等价：都是"当前线程的唯一整数标识"，且在同一
// 会话内稳定（owner 线程 first-wins 记录只需相等性）。
//
// 注意：本头**必须包含 MinGW 原 `unistd.h`** 再做补充，否则会遮蔽
// 标准声明（`getpid`/`isatty` 等）。
// ============================================================

#include_next <unistd.h>

#if defined(_WIN32)
#include <windows.h>

/// Bionic `gettid()` 替身：Win32 当前线程 id
inline pid_t gettid() { return static_cast<pid_t>(::GetCurrentThreadId()); }

#endif
