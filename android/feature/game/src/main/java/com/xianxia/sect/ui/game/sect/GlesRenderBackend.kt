package com.xianxia.sect.ui.game.sect

import com.xianxia.sect.core.render.RenderBackend

/**
 * GPU OpenGL ES 渲染后端适配器（2026-09 GPU GLES 中间层）。
 *
 * ## 与 [VulkanRenderBackend] 的关系
 * 两者都经 NativeBridge 的 **Rhi 虚函数** 渲染同一份 [com.xianxia.sect.core.render.RenderFrame]
 * （beginFrame/drawAllTiles/drawSprite/drawRect/setCamera/submitFrame 均为后端无关），
 * 仅底层 C++ 实现不同（VulkanBackend vs GlesBackend）。因此本类直接复用
 * [VulkanRenderBackend] 的渲染适配逻辑，仅作语义区分。
 *
 * ## 后端选择
 * 后端类型（Vulkan/GLES）在 NativeSurfaceView 初始化路径经
 * [com.xianxia.sect.core.nativebridge.NativeBridge.setRenderBackend] 设置，
 * 本适配器只承担渲染/释放职责（resize/release/renderFrame）。
 *
 * ## 降级链
 * Android 渲染路径：Vulkan → GPU GLES → CPU Canvas。本类对应中间层的 GPU GLES 后端。
 */
class GlesRenderBackend(host: NativeSurfaceView) : VulkanRenderBackend(host)
