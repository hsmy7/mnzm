#pragma once

#include <string>

// ============================================================
// 日志接口（跨平台注入）
//
// 对应 Kotlin DomainLog。game-core 不直接依赖 android/log.h：
// Android 桥层注入 __android_log_print 实现；桌面测试注入 stdout 实现。
// ============================================================
namespace gamecore {

enum class LogLevel : int {
    kDebug = 0,
    kInfo = 1,
    kWarn = 2,
    kError = 3,
};

class Logger {
public:
    virtual ~Logger() = default;

    virtual void log(LogLevel level, const std::string& tag, const std::string& message) = 0;
};

/// 默认实现：输出到 stdout/stderr（桌面/测试用；Android 由桥层覆盖）
class ConsoleLogger final : public Logger {
public:
    void log(LogLevel level, const std::string& tag, const std::string& message) override;
};

/// 空实现：静默（生产环境可注入到 Android logcat 之外的无日志场景）
class NullLogger final : public Logger {
public:
    void log(LogLevel, const std::string&, const std::string&) override {}
};

}  // namespace gamecore
