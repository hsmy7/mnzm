#pragma once

#include <string>
#include <utility>
#include <variant>

// ============================================================
// 领域结果类型 — 对应 Kotlin `DomainResult<T>` / sealed 结果模式
// （CLAUDE.md 1.3：禁止裸 Boolean 代表成败，C++ 侧同守）
//
// 语义：
//   Ok(value)      — 成功
//   Partial(value) — 部分成功（Kotlin DomainResult.Partial：附 error 说明）
//   Failure(error) — 失败（AppError 等价：code + message）
//
// 用法：
//   Result<int> r = Result<int>::ok(42);
//   if (r.isOk()) { ... } else { auto err = r.error(); }
// ============================================================
namespace gamecore {

/// 错误码（对应 Kotlin AppError.code；新业务码按需扩展）
enum class ErrorCode : int32_t {
    kNone = 0,
    kGeneric = 1,          // 未分类业务失败
    kInvalidArgument = 2,  // 参数非法（对应 Kotlin INVALID_* 校验失败）
    kNotFound = 3,         // 目标不存在（实体/宗门/配方等）
    kNotEnoughResource = 4, // 资源不足（灵石/材料/玉符等）
    kSlotOccupied = 5,     // 槽位被占用
    kStateConflict = 6,    // 状态冲突（重复操作/非法状态转换）
    kInternal = 100,       // 程序错误（对应 Kotlin 异常路径）
};

/// 错误信息（对应 Kotlin AppError）
struct AppError {
    ErrorCode code = ErrorCode::kNone;
    std::string message;

    static AppError of(ErrorCode code, std::string msg) {
        return AppError{code, std::move(msg)};
    }
};

/// 三态领域结果
template <typename T>
class Result {
public:
    /// 成功
    static Result ok(T value) { return Result(OkTag{}, std::move(value)); }

    /// 部分成功（值 + 错误说明）
    static Result partial(T value, AppError error) {
        return Result(PartialTag{}, std::move(value), std::move(error));
    }

    /// 失败
    static Result failure(AppError error) { return Result(FailTag{}, std::move(error)); }
    static Result failure(ErrorCode code, std::string msg) {
        return Result(FailTag{}, AppError::of(code, std::move(msg)));
    }

    bool isOk() const { return std::holds_alternative<OkPayload>(payload_); }
    bool isPartial() const { return std::holds_alternative<PartialPayload>(payload_); }
    bool isFailure() const { return std::holds_alternative<FailPayload>(payload_); }

    /// 取值（Ok/Partial 语义均返回；Failure 返回默认构造 T——调用方必须先判 isFailure）
    const T& value() const {
        if (const auto* ok = std::get_if<OkPayload>(&payload_)) return ok->value;
        if (const auto* p = std::get_if<PartialPayload>(&payload_)) return p->value;
        static const T kEmpty{};
        return kEmpty;
    }
    T& value() {
        if (auto* ok = std::get_if<OkPayload>(&payload_)) return ok->value;
        if (auto* p = std::get_if<PartialPayload>(&payload_)) return p->value;
        static T kEmpty{};
        return kEmpty;
    }

    /// 失败/部分失败的错误信息
    const AppError& error() const {
        if (const auto* p = std::get_if<PartialPayload>(&payload_)) return p->error;
        if (const auto* f = std::get_if<FailPayload>(&payload_)) return f->error;
        static const AppError kNone{};
        return kNone;
    }

private:
    struct OkTag {};
    struct PartialTag {};
    struct FailTag {};

    struct OkPayload { T value; };
    struct PartialPayload { T value; AppError error; };
    struct FailPayload { AppError error; };

    using Payload = std::variant<OkPayload, PartialPayload, FailPayload>;

    explicit Result(OkTag, T value) : payload_(OkPayload{std::move(value)}) {}
    Result(PartialTag, T value, AppError error)
        : payload_(PartialPayload{std::move(value), std::move(error)}) {}
    explicit Result(FailTag, AppError error) : payload_(FailPayload{std::move(error)}) {}

    Payload payload_;
};

/// 无值结果（对应 Kotlin 返回 Unit 的操作；仍保留三态语义）
using ActionResult = Result<bool>;

}  // namespace gamecore
