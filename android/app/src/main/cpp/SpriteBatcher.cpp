#include "SpriteBatcher.h"
#include <cstring>

void SpriteBatcher::begin(const float projection[16]) {
    clear();
    memcpy(projMat, projection, sizeof(projMat));
}

void SpriteBatcher::add(uint32_t textureId,
                        float x, float y, float w, float h,
                        float u0, float v0, float u1, float v1,
                        float r, float g, float b, float a) {
    if (vertexCount + 6 > MAX_VERTICES) return;

    float x1 = x;
    float y1 = y;
    float x2 = x + w;
    float y2 = y + h;

    // 两个三角形组成一个矩形
    // 三角形1: (x1,y1)-(x2,y1)-(x1,y2)
    // 三角形2: (x2,y1)-(x2,y2)-(x1,y2)
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
}
