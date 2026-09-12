#pragma once

// ============================================================
// GameCoreBridge — JNI 桥（Android 专用薄层）
//
// 职责：Kotlin GameCoreBridge.kt ↔ C++ game-core 引擎的参数转换。
// 约束：本文件**只做 JNI 转换**，不含任何游戏逻辑（逻辑全在 game-core）。
// 通用入口设计（不是每方法一个 JNI）：execute/advance/export/import/poll，
// 业务操作经 ActionId 协议分发。
//
// JNI 库名：native-game-core（独立于渲染库 native-renderer）
// Kotlin 端：com.xianxia.sect.core.nativebridge.GameCoreBridge
// ============================================================

#include <jni.h>

// 注册 JNI 函数表（可选；当前用标准名称绑定）
// Java_com_xianxia_sect_core_nativebridge_GameCoreBridge_<method>
