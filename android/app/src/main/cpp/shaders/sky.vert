#version 460

// === SkyBackground 屏幕空间渐变顶点着色器 ===
// 输入：inPos（归一化屏幕坐标 0..1）、inUV（v = 归一化 Y，0=顶 1=底）。
// 输出：outUV.v 作为片元解析渐变的竖直坐标。
// 投影由后端以常量"屏幕正交矩阵"经 push-constant.mat4 传入（SkyBackground.screenOrtho），
// 因此天空为 Screen Space：Camera 平移/缩放不影响。
// 与 sky.frag 共用同一 push-constant 块（mat4 proj + 四段颜色/位置/强度），保证布局一致。

layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUV;

layout(push_constant) uniform SkyPC {
    layout(offset = 0) mat4 proj;
    layout(offset = 64) vec4 topColor;       // rgb=顶部色, a=upperMidT
    layout(offset = 80) vec4 upperMidColor;  // rgb=上中部色, a=lowerMidT
    layout(offset = 96) vec4 lowerMidColor;  // rgb=下中部色, a=strength
    layout(offset = 112) vec4 bottomColor;   // rgb=底部色, a=1
} pc;

layout(location = 0) out vec2 outUV;

void main() {
    gl_Position = pc.proj * vec4(inPos, 0.0, 1.0);
    outUV = inUV;
}
