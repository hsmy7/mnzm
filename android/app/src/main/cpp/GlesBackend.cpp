#include "GlesBackend.h"
#include <android/log.h>
#include <cstring>
#include <string>

#define GLES_TAG "GlesBackend"
#define GLES_LOGI(...) __android_log_print(ANDROID_LOG_INFO, GLES_TAG, __VA_ARGS__)
#define GLES_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, GLES_TAG, __VA_ARGS__)
#define GLES_LOGW(...) __android_log_print(ANDROID_LOG_WARN, GLES_TAG, __VA_ARGS__)

// GLSL ES 1.00 着色器（GLES2.0 最大兼容）。单纹理 sampler + 顶点色调制。
// 说明：不做 UV 翻转——图集以 RGBA 经 glTexImage2D 上传，GLES 将 data[0] 置于
// v=0（图像顶行），与上层"v=0=精灵顶部"语义一致；仅投影在 setProjection 做 Y 翻转
// 以匹配世界 Y-down。若真机验证发现纹理上下颠倒，将本行 vUV.y 改为 1.0-aUV.y 即反转。
static const char* kVertSrc =
    "attribute vec2 aPos;\n"
    "attribute vec2 aUV;\n"
    "attribute vec4 aColor;\n"
    "uniform mat4 uProj;\n"
    "varying vec2 vUV;\n"
    "varying vec4 vColor;\n"
    "void main() {\n"
    "  gl_Position = uProj * vec4(aPos, 0.0, 1.0);\n"
    "  vUV = aUV;\n"
    "  vColor = aColor;\n"
    "}\n";

static const char* kFragSrc =
    "precision mediump float;\n"
    "uniform sampler2D uTex;\n"
    "varying vec2 vUV;\n"
    "varying vec4 vColor;\n"
    "void main() {\n"
    "  gl_FragColor = texture2D(uTex, vUV) * vColor;\n"
    "}\n";

namespace {

GLuint compileShader(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    if (!sh) return 0;
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetShaderiv(sh, GL_INFO_LOG_LENGTH, &len);
        std::string log(len > 1 ? len - 1 : 0, '\0');
        if (len > 1) glGetShaderInfoLog(sh, len, nullptr, &log[0]);
        GLES_LOGE("GLES shader compile failed (%s): %s",
                  type == GL_VERTEX_SHADER ? "vert" : "frag", log.c_str());
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

}  // namespace

// ============================================================
// 生命周期
// ============================================================

bool GlesBackend::init(const RenderConfig& config, void* nativeWindow) {
    m_config = config;
    m_viewportW = config.viewportW;
    m_viewportH = config.viewportH;

    if (!initEgl(nativeWindow)) return false;
    if (!initPipeline()) {
        shutdown();
        return false;
    }

    glViewport(0, 0, m_viewportW, m_viewportH);
    glEnable(GL_BLEND);
    // 与 VulkanBackend 一致的普通 alpha 混合（纹理×顶点色；阴影为白色纹理+顶点色）。
    // 若真机验证发现高亮/阴影色差，调整为预乘混合：glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)。
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glDisable(GL_DEPTH_TEST);

    m_ready.store(true);
    GLES_LOGI("GLES backend initialized (%dx%d)", m_viewportW, m_viewportH);
    return true;
}

bool GlesBackend::initEgl(void* nativeWindow) {
    m_window = static_cast<ANativeWindow*>(nativeWindow);
    if (!m_window) {
        GLES_LOGE("initEgl: null native window");
        return false;
    }

    m_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (m_display == EGL_NO_DISPLAY) { GLES_LOGE("eglGetDisplay failed"); return false; }
    EGLint major, minor;
    if (eglInitialize(m_display, &major, &minor) != EGL_TRUE) {
        GLES_LOGE("eglInitialize failed (%d)", eglGetError());
        return false;
    }

    const EGLint cfgAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig cfg;
    EGLint numCfgs = 0;
    if (!eglChooseConfig(m_display, cfgAttribs, &cfg, 1, &numCfgs) || numCfgs < 1) {
        GLES_LOGE("eglChooseConfig failed (%d)", eglGetError());
        return false;
    }

    m_surface = eglCreateWindowSurface(m_display, cfg,
        reinterpret_cast<EGLNativeWindowType>(m_window), nullptr);
    if (m_surface == EGL_NO_SURFACE) {
        GLES_LOGE("eglCreateWindowSurface failed (%d)", eglGetError());
        return false;
    }

    const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    m_context = eglCreateContext(m_display, cfg, EGL_NO_CONTEXT, ctxAttribs);
    if (m_context == EGL_NO_CONTEXT) {
        GLES_LOGE("eglCreateContext failed (%d)", eglGetError());
        return false;
    }

    if (!eglMakeCurrent(m_display, m_surface, m_surface, m_context)) {
        GLES_LOGE("eglMakeCurrent failed (%d)", eglGetError());
        return false;
    }
    return true;
}

bool GlesBackend::initPipeline() {
    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertSrc);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragSrc);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return false;
    }

    m_program = glCreateProgram();
    glAttachShader(m_program, vs);
    glAttachShader(m_program, fs);
    // 固定 attrib 位置（0=pos, 1=uv, 2=color），与 draw() 的 glVertexAttribPointer 一致
    glBindAttribLocation(m_program, 0, "aPos");
    glBindAttribLocation(m_program, 1, "aUV");
    glBindAttribLocation(m_program, 2, "aColor");
    glLinkProgram(m_program);
    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint ok = 0;
    glGetProgramiv(m_program, GL_LINK_STATUS, &ok);
    if (!ok) {
        GLint len = 0;
        glGetProgramiv(m_program, GL_INFO_LOG_LENGTH, &len);
        std::string log(len > 1 ? len - 1 : 0, '\0');
        if (len > 1) glGetProgramInfoLog(m_program, len, nullptr, &log[0]);
        GLES_LOGE("GLES program link failed: %s", log.c_str());
        return false;
    }
    m_projLoc = glGetUniformLocation(m_program, "uProj");

    glGenBuffers(1, &m_vbo);

    // 1×1 白色纹理（id=0，纯色矩形）——先注册，供 glFor(0) 命中
    const uint8_t white[4] = { 255, 255, 255, 255 };
    glGenTextures(1, &m_whiteTex);
    glBindTexture(GL_TEXTURE_2D, m_whiteTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, white);
    m_textures.push_back({ m_whiteTex, 0 });

    return true;
}

// ============================================================
// Renderer2D 接口
// ============================================================

void GlesBackend::shutdown() {
    m_ready.store(false);
    destroyPipeline();
    if (m_display != EGL_NO_DISPLAY) {
        if (m_context != EGL_NO_CONTEXT) eglMakeCurrent(m_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (m_surface != EGL_NO_SURFACE) eglDestroySurface(m_display, m_surface);
        if (m_context != EGL_NO_CONTEXT) eglDestroyContext(m_display, m_context);
        eglTerminate(m_display);
    }
    m_display = EGL_NO_DISPLAY;
    m_surface = EGL_NO_SURFACE;
    m_context = EGL_NO_CONTEXT;
    m_window = nullptr;
    m_pendingDraws.clear();
    m_vertexBuffer.clear();
}

void GlesBackend::destroyPipeline() {
    if (!m_textures.empty()) {
        for (auto& t : m_textures) {
            if (t.gl) glDeleteTextures(1, &t.gl);
        }
        m_textures.clear();
    }
    if (m_vbo) glDeleteBuffers(1, &m_vbo);
    if (m_program) glDeleteProgram(m_program);
    m_vbo = 0;
    m_program = 0;
    m_whiteTex = 0;
    m_projLoc = -1;
}

bool GlesBackend::resize(int width, int height) {
    if (width <= 0 || height <= 0) return false;
    m_viewportW = width;
    m_viewportH = height;
    if (m_display != EGL_NO_DISPLAY && m_surface != EGL_NO_SURFACE && m_context != EGL_NO_CONTEXT) {
        eglMakeCurrent(m_display, m_surface, m_surface, m_context);
        glViewport(0, 0, width, height);
    }
    return true;
}

void GlesBackend::beginFrame() {
    // 清屏与提交统一在 submitFrame 处理；beginFrame 仅重置状态
    m_pendingDraws.clear();
    m_vertexBuffer.clear();
}

void GlesBackend::endFrame() {}

uint32_t GlesBackend::uploadTexture(const void* pixels, int width, int height) {
    if (!pixels || width <= 0 || height <= 0) return 0;
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                 GL_RGBA, GL_UNSIGNED_BYTE, pixels);

    const uint32_t id = m_nextTexId++;
    m_textures.push_back({ tex, id });
    return id;
}

void GlesBackend::destroyTexture(uint32_t id) {
    for (auto it = m_textures.begin(); it != m_textures.end(); ++it) {
        if (it->id == id && it->gl) {
            glDeleteTextures(1, &it->gl);
            m_textures.erase(it);
            return;
        }
    }
}

void GlesBackend::setProjection(const float mat[16]) {
    // 复制矩阵，做 Y 翻转（GLES NDC Y 向上 vs 上层 Vulkan NDC Y 向下）。
    // 翻转 output.y：negate 列主序矩阵中贡献 y 的 4 个分量。
    for (int i = 0; i < 16; ++i) m_projMatrix[i] = mat[i];
    m_projMatrix[1] = -mat[1];
    m_projMatrix[5] = -mat[5];
    m_projMatrix[9] = -mat[9];
    m_projMatrix[13] = -mat[13];
}

void GlesBackend::draw(const SpriteVertex* vertices, int count, uint32_t textureId) {
    if (!vertices || count <= 0) return;
    const int offset = static_cast<int>(m_vertexBuffer.size());
    m_vertexBuffer.insert(m_vertexBuffer.end(), vertices, vertices + count);
    m_pendingDraws.push_back({ offset, count, textureId });
}

GLuint GlesBackend::glFor(uint32_t id) const {
    for (const auto& t : m_textures) {
        if (t.id == id) return t.gl;
    }
    // 未找到 → 回退白色纹理（与 VulkanBackend 的未知纹理回退至白色纹理同语义，
    // 避免描述符/采样指向错误数据）
    return m_whiteTex;
}

void GlesBackend::submitFrame() {
    if (!m_ready.load()) return;

    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(m_program);
    glUniformMatrix4fv(m_projLoc, 1, GL_FALSE, m_projMatrix);

    if (!m_vertexBuffer.empty()) {
        glBindBuffer(GL_ARRAY_BUFFER, m_vbo);
        glBufferData(GL_ARRAY_BUFFER,
                     static_cast<GLsizeiptr>(m_vertexBuffer.size() * sizeof(SpriteVertex)),
                     m_vertexBuffer.data(), GL_DYNAMIC_DRAW);

        // SpriteVertex = { float px,py,u,v,r,g,b,a } → 8 floats = 32 字节
        const GLsizei stride = 8 * sizeof(float);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, stride, (const GLvoid*)0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, stride, (const GLvoid*)(2 * sizeof(float)));
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, stride, (const GLvoid*)(4 * sizeof(float)));

        GLuint curTex = 0;
        for (const auto& cmd : m_pendingDraws) {
            const GLuint g = glFor(cmd.textureId);
            if (g != curTex) {
                glBindTexture(GL_TEXTURE_2D, g);
                curTex = g;
            }
            glDrawArrays(GL_TRIANGLES, cmd.vertexOffset, cmd.count);
        }
    }

    eglSwapBuffers(m_display, m_surface);

    m_pendingDraws.clear();
    m_vertexBuffer.clear();
}
