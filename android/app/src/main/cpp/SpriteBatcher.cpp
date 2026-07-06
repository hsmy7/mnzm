#include "SpriteBatcher.h"
#include <cstring>

void SpriteBatcher::begin(const float projection[16]) {
    clear();
    // 初始使用栈缓冲
    vertices = stackBuffer;
    capacity = STACK_CAPACITY;
    heapAllocated = false;
    memcpy(projMat, projection, sizeof(projMat));
}

void SpriteBatcher::add(uint32_t textureId,
                        float x, float y, float w, float h,
                        float u0, float v0, float u1, float v1,
                        float r, float g, float b, float a) {
    // 空间不足时自动扩容
    if (vertexCount + 6 > capacity) {
        grow();
    }

    float x1 = x;
    float y1 = y;
    float x2 = x + w;
    float y2 = y + h;

    SpriteVertex* v = &vertices[vertexCount];

    v[0] = { x1, y1, u0, v0, r, g, b, a };
    v[1] = { x2, y1, u1, v0, r, g, b, a };
    v[2] = { x1, y2, u0, v1, r, g, b, a };
    v[3] = { x2, y1, u1, v0, r, g, b, a };
    v[4] = { x2, y2, u1, v1, r, g, b, a };
    v[5] = { x1, y2, u0, v1, r, g, b, a };

    vertexCount += 6;
    currentTexture = textureId;
}

void SpriteBatcher::addCentered(uint32_t textureId,
                                float cx, float cy, float w, float h,
                                float u0, float v0, float u1, float v1,
                                float r, float g, float b, float a) {
    add(textureId, cx - w / 2.0f, cy - h / 2.0f, w, h,
        u0, v0, u1, v1, r, g, b, a);
}

int SpriteBatcher::end() {
    return vertexCount;
}

void SpriteBatcher::clear() {
    vertexCount = 0;
    currentTexture = 0;
    if (heapAllocated) {
        delete[] vertices;
        vertices = stackBuffer;
        heapAllocated = false;
        capacity = STACK_CAPACITY;
    }
}

void SpriteBatcher::grow() {
    int newCap = capacity * 2;
    if (newCap > MAX_VERTICES) newCap = MAX_VERTICES;
    if (newCap == capacity) return;  // 已达上限

    auto* newBuf = new SpriteVertex[newCap];
    // 拷贝已有顶点
    memcpy(newBuf, vertices, (size_t)vertexCount * sizeof(SpriteVertex));

    if (heapAllocated) {
        delete[] vertices;
    }

    vertices = newBuf;
    capacity = newCap;
    heapAllocated = true;
}
