#pragma once

#include "Renderer2D.h"

// ============================================================
// SpriteBatcher — 精灵批处理构建器
// 将可见精灵收集到一个连续顶点缓冲区中，按纹理分组
//
// 使用方式：
//   batcher.begin(projectionMatrix);
//   batcher.add(textureId, x, y, w, h, u0, v0, u1, v1, color);
//   batcher.add(...);
//   batcher.end();  // 返回 DrawBatch 列表
// ============================================================

struct SpriteBatcher {
    SpriteVertex vertices[MAX_VERTICES];
    int vertexCount = 0;
    uint32_t currentTexture = 0;
    float projMat[16];

    void begin(const float projection[16]);
    void add(uint32_t textureId,
             float x, float y, float w, float h,       // 目标矩形（世界坐标）
             float u0, float v0, float u1, float v1,   // UV 矩形
             float r = 1.0f, float g = 1.0f,
             float b = 1.0f, float a = 1.0f);           // 颜色（默认白色）
    void addCentered(uint32_t textureId,
                     float cx, float cy, float w, float h,
                     float u0, float v0, float u1, float v1,
                     float r = 1.0f, float g = 1.0f,
                     float b = 1.0f, float a = 1.0f);
    int end();  // 返回顶点数
    void clear();
};
