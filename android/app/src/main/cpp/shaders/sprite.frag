#version 460
precision mediump float;

// === 2D 精灵片段着色器 ===
// 输出 = 纹理采样 × 顶点颜色
//
// binding 0: 纹理图集采样器（ASTC 压缩）

layout(location = 0) in vec2 inUV;
layout(location = 1) in vec4 inColor;

layout(binding = 0) uniform sampler2D uAtlas;

layout(location = 0) out vec4 outFrag;

void main() {
    outFrag = texture(uAtlas, inUV) * inColor;
}
