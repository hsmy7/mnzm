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
    // 小栈预分配（512 顶点 = 16KB 栈），超限时自动切换到堆分配
    // 避免 Android 背景线程默认栈（~1MB）上开辟 768KB 数组导致溢出
    static constexpr int STACK_CAPACITY = 512;
    SpriteVertex stackBuffer[STACK_CAPACITY];
    SpriteVertex* vertices;
    int vertexCount = 0;
    int capacity = STACK_CAPACITY;
    bool heapAllocated = false;
    uint32_t currentTexture = 0;
    float projMat[16];

    void begin(const float projection[16]);
    void add(uint32_t textureId,
             float x, float y, float w, float h,
             float u0, float v0, float u1, float v1,
             float r = 1.0f, float g = 1.0f,
             float b = 1.0f, float a = 1.0f);
    void addCentered(uint32_t textureId,
                     float cx, float cy, float w, float h,
                     float u0, float v0, float u1, float v1,
                     float r = 1.0f, float g = 1.0f,
                     float b = 1.0f, float a = 1.0f);
    int end();
    void clear();

    ~SpriteBatcher() {
        if (heapAllocated) delete[] vertices;
    }

private:
    void grow();  // 超出栈容量时切换到堆
};
