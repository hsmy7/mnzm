#include "TextureAtlas.h"

void TextureAtlas::defineAtlas(int totalWidth, int totalHeight,
                                const SpriteDef* sprites, int count) {
    m_width = totalWidth;
    m_height = totalHeight;
    m_regions.reserve(count);

    for (int i = 0; i < count; i++) {
        const auto& s = sprites[i];
        AtlasRegion reg;
        reg.u0 = (float)s.x / (float)m_width;
        reg.v0 = (float)s.y / (float)m_height;
        reg.u1 = (float)(s.x + s.w) / (float)m_width;
        reg.v1 = (float)(s.y + s.h) / (float)m_height;
        reg.pixelW = s.w;
        reg.pixelH = s.h;
        m_regions[s.name] = reg;
    }
}

const AtlasRegion* TextureAtlas::getRegion(const char* name) const {
    auto it = m_regions.find(name);
    if (it != m_regions.end()) {
        return &it->second;
    }
    return nullptr;
}
