#include <gtest/gtest.h>

#include <atomic>
#include <cstddef>
#include <vector>

#include "gamecore/ecs/job_system.h"

namespace gamecore::ecs {
namespace {

TEST(JobSystemTest, SubmitAndWaitRunsTasks) {
    JobSystem js(2);
    std::atomic<int> count{0};
    js.submit([&] { count.fetch_add(1); });
    js.submit([&] { count.fetch_add(1); });
    js.wait();
    EXPECT_EQ(count.load(), 2);
}

TEST(JobSystemTest, ParallelForWithSingleThreadIsSerialAndCorrect) {
    JobSystem js(1);
    const std::size_t n = 1000;
    std::vector<int> out(n, 0);
    js.parallelFor(n, [&](std::size_t begin, std::size_t end) {
        for (std::size_t i = begin; i < end; ++i) out[i] = static_cast<int>(i) * 2;
    });
    for (std::size_t i = 0; i < n; ++i) EXPECT_EQ(out[i], static_cast<int>(i) * 2);
}

TEST(JobSystemTest, ParallelForMultiThreadProcessesEveryIndexOnce) {
    JobSystem js;  // 默认线程数（>=1）
    const std::size_t n = 5000;
    std::vector<int> out(n, -1);
    js.parallelFor(n, [&](std::size_t begin, std::size_t end) {
        for (std::size_t i = begin; i < end; ++i) out[i] = static_cast<int>(i);
    });
    // 每个 index 恰被写一次且值正确（结果确定，与线程调度无关）
    for (std::size_t i = 0; i < n; ++i) EXPECT_EQ(out[i], static_cast<int>(i));
}

TEST(JobSystemTest, ParallelForEmptyCountNoOp) {
    JobSystem js(2);
    std::atomic<int> calls{0};
    js.parallelFor(0, [&](std::size_t, std::size_t) { calls.fetch_add(1); });
    EXPECT_EQ(calls.load(), 0);
}

TEST(JobSystemTest, ParallelForSumIsDeterministic) {
    JobSystem js(4);
    const std::size_t n = 10000;
    std::vector<std::uint64_t> out(n, 0);
    // 与索引无关的确定函数（0..n-1 求和），写入各自切片
    js.parallelFor(n, [&](std::size_t begin, std::size_t end) {
        for (std::size_t i = begin; i < end; ++i) out[i] = i;
    });
    std::uint64_t sum = 0;
    for (auto v : out) sum += v;
    EXPECT_EQ(sum, n * (n - 1) / 2);
}

TEST(JobSystemTest, ReusableAcrossBatches) {
    JobSystem js(3);
    const std::size_t n = 100;
    std::vector<int> out(n, 0);
    js.parallelFor(n, [&](std::size_t begin, std::size_t end) {
        for (std::size_t i = begin; i < end; ++i) out[i] = static_cast<int>(i);
    });
    std::vector<int> out2(n, 0);
    js.parallelFor(n, [&](std::size_t begin, std::size_t end) {
        for (std::size_t i = begin; i < end; ++i) out2[i] = static_cast<int>(i) + 1;
    });
    for (std::size_t i = 0; i < n; ++i) {
        EXPECT_EQ(out[i], static_cast<int>(i));
        EXPECT_EQ(out2[i], static_cast<int>(i) + 1);
    }
}

}  // namespace
}  // namespace gamecore::ecs
