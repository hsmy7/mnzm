#version 460
precision mediump float;

// === SkyBackground 屏幕空间解析渐变片段着色器 ===
// 四段渐变（topColor@0 → upperMidColor@upperMidT → lowerMidColor@lowerMidT → bottomColor@1）
// 由 push-constant 逐像素解析：
//   - 分段 smoothstep（C1 平滑），消除顶点插值的中段折痕；对退化段鲁棒（max 钳制除零）。
//   - 渐变强度：向顶部色混合（0=平铺顶色，1=全渐变）。
//   - 无噪声/无颗粒/无 dither：保持"清新、柔和"纯净渐变。
// 顶点只提供归一化竖直坐标（inUV.v，0=顶 1=底）；不做纹理采样；顶点色作天空管线失败时的回退。
// 位置/强度编码在对应颜色向量的 alpha（topColor.a=upperMidT, upperMidColor.a=lowerMidT,
// lowerMidColor.a=strength），以把 push-constant 控制在 128 字节（minPushConstantsSize=128；颜色取 .rgb）。

layout(location = 0) in vec2 inUV;

layout(push_constant) uniform SkyPC {
    layout(offset = 0) mat4 proj;
    layout(offset = 64) vec4 topColor;
    layout(offset = 80) vec4 upperMidColor;
    layout(offset = 96) vec4 lowerMidColor;
    layout(offset = 112) vec4 bottomColor;
} pc;

layout(location = 0) out vec4 outFrag;

float smoothBetween(float e0, float e1, float t) {
    float f = (t - e0) / max(e1 - e0, 1e-4);
    f = clamp(f, 0.0, 1.0);
    return f * f * (3.0 - 2.0 * f);
}

void main() {
    float t = inUV.y;  // 0=顶, 1=底
    float t1 = pc.topColor.a;
    float t2 = pc.upperMidColor.a;
    float strength = pc.lowerMidColor.a;

    vec3 c;
    if (t < t1) {
        c = mix(pc.topColor.rgb, pc.upperMidColor.rgb, smoothBetween(0.0, t1, t));
    } else if (t < t2) {
        c = mix(pc.upperMidColor.rgb, pc.lowerMidColor.rgb, smoothBetween(t1, t2, t));
    } else {
        c = mix(pc.lowerMidColor.rgb, pc.bottomColor.rgb, smoothBetween(t2, 1.0, t));
    }
    // 渐变强度：向顶部色混合
    c = mix(pc.topColor.rgb, c, strength);
    outFrag = vec4(c, 1.0);
}
