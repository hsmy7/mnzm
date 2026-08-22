#include <gtest/gtest.h>

#include "gamecore/core/result.h"

namespace gamecore {
namespace {

TEST(ResultTest, OkHoldsValue) {
    auto r = Result<int>::ok(42);
    EXPECT_TRUE(r.isOk());
    EXPECT_FALSE(r.isFailure());
    EXPECT_EQ(42, r.value());
}

TEST(ResultTest, FailureHoldsError) {
    auto r = Result<int>::failure(ErrorCode::kNotFound, "disciple not found");
    EXPECT_TRUE(r.isFailure());
    EXPECT_FALSE(r.isOk());
    EXPECT_EQ(ErrorCode::kNotFound, r.error().code);
    EXPECT_EQ("disciple not found", r.error().message);
}

TEST(ResultTest, PartialHoldsValueAndError) {
    auto r = Result<int>::partial(7, AppError::of(ErrorCode::kGeneric, "partial"));
    EXPECT_TRUE(r.isPartial());
    EXPECT_EQ(7, r.value());
    EXPECT_EQ(ErrorCode::kGeneric, r.error().code);
}

TEST(ResultTest, ValueOnFailureReturnsDefault) {
    auto r = Result<std::string>::failure(ErrorCode::kGeneric, "boom");
    EXPECT_TRUE(r.isFailure());
    EXPECT_TRUE(r.value().empty());  // 默认构造
}

TEST(ResultTest, ActionResultSemantics) {
    auto ok = ActionResult::ok(true);
    EXPECT_TRUE(ok.isOk());
    auto fail = ActionResult::failure(ErrorCode::kInvalidArgument, "bad");
    EXPECT_TRUE(fail.isFailure());
}

}  // namespace
}  // namespace gamecore
