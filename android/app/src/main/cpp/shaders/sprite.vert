#version 460

// === 2D 精灵顶点着色器 ===
// 支持世界坐标变换 + 纹理UV + 逐顶点颜色
//
// 布局说明：
//   location 0: 顶点位置（世界坐标，vec2）
//   location 1: 纹理 UV（vec2）
//   location 2: 逐顶点颜色（vec4，用于 alpha/高亮控制）
//
// Push Constant: mat4 正交投影矩阵

layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUV;
layout(location = 2) in vec4 inColor;

layout(push_constant) uniform PushConstants {
    layout(offset = 0) mat4 proj;
} pc;

layout(location = 0) out vec2 outUV;
layout(location = 1) out vec4 outColor;

void main() {
    gl_Position = pc.proj * vec4(inPos, 0.0, 1.0);
    outUV = inUV;
    outColor = inColor;
}
