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

// SkyBackground 屏幕空间渐变片元着色器（GLSL ES 1.00 最大兼容）。
// 四段渐变（uTopColor@0 → uUpperMidColor@upperMidT → uLowerMidColor@lowerMidT → uBottomColor@1）
// 由 uniform 逐像素解析：分段 smoothstep（C1 平滑）；无噪声/无颗粒/无 dither（清新柔和纯净渐变）。
// vUV.v 为归一化 Y（0=顶 1=底）；不做纹理采样；vColor 作天空程序失败时的回退未用。
// 位置/强度编码在对应向量 alpha 通道（uTopColor.a=upperMidT, uUpperMidColor.a=lowerMidT,
// uLowerMidColor.a=strength），颜色取 .rgb。
static const char* kSkyFragSrc =
    "precision mediump float;\n"
    "uniform vec4 uTopColor;\n"
    "uniform vec4 uUpperMidColor;\n"
    "uniform vec4 uLowerMidColor;\n"
    "uniform vec4 uBottomColor;\n"
    "varying vec2 vUV;\n"
    "varying vec4 vColor;\n"
    "float skySmooth(float e0, float e1, float t) {\n"
    "  float f = (t - e0) / max(e1 - e0, 0.0001);\n"
    "  f = clamp(f, 0.0, 1.0);\n"
    "  return f * f * (3.0 - 2.0 * f);\n"
    "}\n"
    "void main() {\n"
    "  float t = vUV.y;\n"
    "  float t1 = uTopColor.a;\n"
    "  float t2 = uUpperMidColor.a;\n"
    "  float strength = uLowerMidColor.a;\n"
    "  vec3 c;\n"
    "  if (t < t1) {\n"
    "    c = mix(uTopColor.rgb, uUpperMidColor.rgb, skySmooth(0.0, t1, t));\n"
    "  } else if (t < t2) {\n"
    "    c = mix(uUpperMidColor.rgb, uLowerMidColor.rgb, skySmooth(t1, t2, t));\n"
    "  } else {\n"
    "    c = mix(uLowerMidColor.rgb, uBottomColor.rgb, skySmooth(t2, 1.0, t));\n"
    "  }\n"
    "  c = mix(uTopColor.rgb, c, strength);\n"
    "  gl_FragColor = vec4(c, 1.0);\n"
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

    // SkyBackground 屏幕正交投影（GLES NDC Y 向上）：归一化屏幕坐标 (x∈[0,1] 左→右,
    // y∈[0,1] 顶→底) → NDC（y=0 顶 → +1，y=1 底 → -1）。与相机矩阵/分辨率无关，仅算一次。
    orthoProj(m_screenOrtho, 0.0f, 1.0f, 0.0f, 1.0f);

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

    // ★ 2026-09 线程模型修复：initEgl 已释放线程绑定（渲染线程尚未启动，
    //   上下文不得滞留于初始化线程——否则渲染线程 GL 调用全部无上下文静默失败，
    //   真机实测黑屏根因）。管线创建（本函数）运行于初始化线程，此时上下文
    //   在本线程，正常；退出前再次释放，交由渲染线程接管。
    eglMakeCurrent(m_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

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
    // ★ 2026-09 线程模型修复（释放时机）：上下文绑定保留至 init() 完成
    //   （initPipeline 等后续 GL 调用仍在本线程执行）——init() 末尾统一释放，
    //   渲染线程首次 submitFrame 经 ensureContextCurrent 接管
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

    // ── SkyBackground 屏幕空间渐变程序（同顶点 shader，片元用 kSkyFragSrc 加抖动去色带）──
    // 失败置 0 → submitFrame 回退主管线（无抖动）。
    {
        GLuint skyVs = compileShader(GL_VERTEX_SHADER, kVertSrc);
        GLuint skyFs = compileShader(GL_FRAGMENT_SHADER, kSkyFragSrc);
        if (skyVs && skyFs) {
            m_skyProgram = glCreateProgram();
            glAttachShader(m_skyProgram, skyVs);
            glAttachShader(m_skyProgram, skyFs);
            glBindAttribLocation(m_skyProgram, 0, "aPos");
            glBindAttribLocation(m_skyProgram, 1, "aUV");
            glBindAttribLocation(m_skyProgram, 2, "aColor");
            glLinkProgram(m_skyProgram);
            glDeleteShader(skyVs);
            glDeleteShader(skyFs);
            GLint skyOk = 0;
            glGetProgramiv(m_skyProgram, GL_LINK_STATUS, &skyOk);
            if (skyOk) {
                m_skyProjLoc = glGetUniformLocation(m_skyProgram, "uProj");
                m_skyTopLoc = glGetUniformLocation(m_skyProgram, "uTopColor");
                m_skyUpperMidLoc = glGetUniformLocation(m_skyProgram, "uUpperMidColor");
                m_skyLowerMidLoc = glGetUniformLocation(m_skyProgram, "uLowerMidColor");
                m_skyBottomLoc = glGetUniformLocation(m_skyProgram, "uBottomColor");
            } else {
                GLint len = 0;
                glGetProgramiv(m_skyProgram, GL_INFO_LOG_LENGTH, &len);
                std::string log(len > 1 ? len - 1 : 0, '\0');
                if (len > 1) glGetProgramInfoLog(m_skyProgram, len, nullptr, &log[0]);
                GLES_LOGE("GLES sky program link failed: %s", log.c_str());
                glDeleteProgram(m_skyProgram);
                m_skyProgram = 0;
                m_skyProjLoc = m_skyTopLoc = m_skyUpperMidLoc = m_skyLowerMidLoc = m_skyBottomLoc = -1;
            }
        } else {
            if (skyVs) glDeleteShader(skyVs);
            if (skyFs) glDeleteShader(skyFs);
            GLES_LOGE("GLES sky shader compile failed — sky falls back to main program");
            m_skyProgram = 0;
            m_skyProjLoc = m_skyTopLoc = m_skyUpperMidLoc = m_skyLowerMidLoc = m_skyBottomLoc = -1;
        }
    }

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
    // ★ 2026-09 线程模型修复：清理 GL 资源须持有上下文。shutdown 由主线程
    //   调用（surfaceDestroyed 时渲染线程已停止——上下文空闲可接管）
    const bool ctxOk = ensureContextCurrent();
    if (ctxOk) {
        destroyPipeline();
    }
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
    m_pendingUploads.clear();
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
    if (m_skyProgram) glDeleteProgram(m_skyProgram);
    m_vbo = 0;
    m_program = 0;
    m_skyProgram = 0;
    m_whiteTex = 0;
    m_projLoc = -1;
    m_skyProjLoc = -1;
}

bool GlesBackend::resize(int width, int height) {
    if (width <= 0 || height <= 0) return false;
    // ★ 2026-09 线程模型修复：resize 由主线程调用（handleSurfaceSizeChanged），
    //   不再直接 glViewport（无上下文）——仅记录尺寸，submitFrame 每帧落地
    m_viewportW = width;
    m_viewportH = height;
    return true;
}

void GlesBackend::beginFrame() {
    // 清屏与提交统一在 submitFrame 处理；beginFrame 仅重置状态
    m_pendingDraws.clear();
    m_vertexBuffer.clear();
    m_backgroundVertexCount = 0;
}

void GlesBackend::endFrame() {}

bool GlesBackend::ensureContextCurrent() {
    if (m_display == EGL_NO_DISPLAY || m_surface == EGL_NO_SURFACE ||
        m_context == EGL_NO_CONTEXT) return false;
    if (!eglMakeCurrent(m_display, m_surface, m_surface, m_context)) {
        GLES_LOGE("ensureContextCurrent: eglMakeCurrent failed (%d)", eglGetError());
        return false;
    }
    return true;
}

void GlesBackend::drainUploads() {
    if (m_pendingUploads.empty()) return;
    for (auto& up : m_pendingUploads) {
        GLuint tex = 0;
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, up.width, up.height, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, up.pixels.data());
        m_textures.push_back({ tex, up.id });
        GLES_LOGI("drainUploads: texture id=%u %dx%d uploaded (gl=%u)",
                  up.id, up.width, up.height, tex);
    }
    m_pendingUploads.clear();
}

uint32_t GlesBackend::uploadTexture(const void* pixels, int width, int height) {
    if (!pixels || width <= 0 || height <= 0) return 0;
    // ★ 2026-09 线程模型修复：GL 调用必须发生在持有 EGL 上下文的线程。
    //   本方法由主线程（buildAtlas）调用——仅入队（拷贝像素数据），
    //   真实 GL 上传由渲染线程在 submitFrame 开头经 drainUploads 执行
    //   （否则主线程无上下文 → GL 调用静默失败 → 图集无效 → 全黑）。
    const uint32_t id = m_nextTexId++;
    PendingUpload up;
    up.id = id;
    up.width = width;
    up.height = height;
    const size_t bytes = static_cast<size_t>(width) * height * 4;
    up.pixels.resize(bytes);
    memcpy(up.pixels.data(), pixels, bytes);
    m_pendingUploads.push_back(std::move(up));
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

void GlesBackend::drawBackground(const SpriteVertex* vertices, int count,
                                 const SkyGradientParams& params) {
    if (!vertices || count <= 0) return;
    // 屏幕空间背景：写入 m_vertexBuffer 头部（beginFrame 已清空，帧首为空）。
    // 必须在所有世界 draw() 之前调用（NativeBridge.drawSky 帧首触发）——本函数先
    // 插入背景顶点，因此后续世界 draw() 记录的 vertexOffset 自然衔接在背景之后；
    // submitFrame 最先绘制本背景段。
    m_vertexBuffer.insert(m_vertexBuffer.end(), vertices, vertices + count);
    m_backgroundVertexCount = count;
    m_skyParams = params;   // 供 submitFrame 经 uniform 推给 kSkyFragSrc
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

    // ★ 2026-09 线程模型修复：渲染线程接管 EGL 上下文 + 消费纹理上传队列 +
    //   每帧应用视口（跨线程 resize 只记录尺寸，此处落地）
    if (!ensureContextCurrent()) return;
    drainUploads();
    glViewport(0, 0, m_viewportW, m_viewportH);

    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(m_program);

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

        // ── SkyBackground 屏幕空间背景（最先绘制 — 最底图层）──────────────────
        // 用屏幕正交投影(m_screenOrtho)而非相机矩阵(m_projMatrix)，Camera 平移/缩放
        // 完全不作用于背景。主路径用天空程序（kSkyFragSrc：分段 smoothstep 解析渐变 +
        // 有序抖动，参数经 uniform 传入）；失败则回退主管线（顶点色三段渐变，无平滑）。
        // VBO 段从 offset 0 开始。
        if (m_backgroundVertexCount > 0) {
            glUseProgram(m_skyProgram ? m_skyProgram : m_program);
            if (m_skyProgram) {
                // 四段渐变：颜色 .rgb + 位置/强度编码在 alpha（uTopColor.a=upperMidT,
                // uUpperMidColor.a=lowerMidT, uLowerMidColor.a=strength）
                const float top[4] = { m_skyParams.topColor[0], m_skyParams.topColor[1], m_skyParams.topColor[2], m_skyParams.upperMidT };
                const float upperMid[4] = { m_skyParams.upperMidColor[0], m_skyParams.upperMidColor[1], m_skyParams.upperMidColor[2], m_skyParams.lowerMidT };
                const float lowerMid[4] = { m_skyParams.lowerMidColor[0], m_skyParams.lowerMidColor[1], m_skyParams.lowerMidColor[2], m_skyParams.strength };
                const float bottom[4] = { m_skyParams.bottomColor[0], m_skyParams.bottomColor[1], m_skyParams.bottomColor[2], 1.0f };
                glUniformMatrix4fv(m_skyProjLoc, 1, GL_FALSE, m_screenOrtho);
                glUniform4fv(m_skyTopLoc, 1, top);
                glUniform4fv(m_skyUpperMidLoc, 1, upperMid);
                glUniform4fv(m_skyLowerMidLoc, 1, lowerMid);
                glUniform4fv(m_skyBottomLoc, 1, bottom);
            } else {
                glUniformMatrix4fv(m_projLoc, 1, GL_FALSE, m_screenOrtho);
            }
            glBindTexture(GL_TEXTURE_2D, m_whiteTex);
            glDrawArrays(GL_TRIANGLES, 0, m_backgroundVertexCount);
        }

        // 世界绘制（相机投影）
        glUseProgram(m_program);
        glUniformMatrix4fv(m_projLoc, 1, GL_FALSE, m_projMatrix);
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
