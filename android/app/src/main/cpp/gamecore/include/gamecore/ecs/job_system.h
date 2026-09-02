#pragma once

#include <algorithm>
#include <condition_variable>
#include <cstddef>
#include <deque>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

// ============================================================
// ECS JobSystem（phase ③：独立 system 并行化基础）
//
// 目的：把当前单线程 executor（GameEngineCore 单线程）的独立无共享写 system
// （战斗/探索/内政/结算等）分摊到多核，解决 5000 弟子每旬 O(D) 热点。
//
// ## 确定性约束（RNG 红线）
//   parallelFor 只用于**写输出与索引一一对应**（无共享写）的场景：
//   每个 index 恰被处理一次、写入该 index 的独立切片，则无论线程调度顺序如何，
//   最终结果唯一确定。**禁止**在并行任务内消费会在任务间共享/依赖次序的 RNG/状态。
//
// ## 异常策略
//   任务应为 noexcept 式纯计算（本库不依赖异常）；submit/parallelFor 的
//   任务体若抛出会触发 terminate——由调用方保证不抛（与 game-core 零异常依赖对齐）。
// ============================================================
namespace gamecore::ecs {

class JobSystem {
public:
    /// @param threads 线程数；0 = hardware_concurrency（至少 1）
    explicit JobSystem(std::size_t threads = 0) {
        threads_ = threads == 0 ? std::max<std::size_t>(1, std::thread::hardware_concurrency())
                                : threads;
        workers_.reserve(threads_);
        for (std::size_t i = 0; i < threads_; ++i) {
            workers_.emplace_back([this] { workerLoop(); });
        }
    }

    ~JobSystem() {
        {
            std::unique_lock<std::mutex> lk(mutex_);
            stop_ = true;
        }
        cond_.notify_all();
        for (auto& w : workers_) {
            if (w.joinable()) w.join();
        }
    }

    /// 提交一个任务（非阻塞）
    void submit(std::function<void()> task) {
        {
            std::unique_lock<std::mutex> lk(mutex_);
            queue_.push_back(std::move(task));
        }
        cond_.notify_one();
    }

    /// 阻塞直到所有已提交任务完成
    void wait() {
        std::unique_lock<std::mutex> lk(mutex_);
        done_.wait(lk, [this] { return queue_.empty() && active_ == 0; });
    }

    /// 并行分块处理 [0,count)：为每个并发块调度一个任务调用 chunkFn(begin,end)。
    /// 静态连续分块——调度顺序确定性（每 index 恰一次）。chunkFn 须按 index 写独立切片。
    template <typename ChunkFn>
    void parallelFor(std::size_t count, ChunkFn&& chunkFn) {
        if (count == 0) return;
        const std::size_t nChunks = std::min(threads_, count);
        const std::size_t chunk = (count + nChunks - 1) / nChunks;
        for (std::size_t c = 0; c < nChunks; ++c) {
            const std::size_t begin = c * chunk;
            const std::size_t end = std::min(count, begin + chunk);
            if (begin >= end) continue;
            submit([&chunkFn, begin, end]() { chunkFn(begin, end); });
        }
        wait();
    }

    std::size_t threadCount() const { return threads_; }

private:
    void workerLoop() {
        for (;;) {
            std::function<void()> task;
            {
                std::unique_lock<std::mutex> lk(mutex_);
                cond_.wait(lk, [this] { return stop_ || !queue_.empty(); });
                if (stop_ && queue_.empty()) return;
                task = std::move(queue_.front());
                queue_.pop_front();
                ++active_;
            }
            task();
            {
                std::unique_lock<std::mutex> lk(mutex_);
                --active_;
            }
            done_.notify_all();
        }
    }

    std::size_t threads_ = 1;
    std::vector<std::thread> workers_;
    std::deque<std::function<void()>> queue_;
    std::mutex mutex_;
    std::condition_variable cond_;
    std::condition_variable done_;
    std::size_t active_ = 0;
    bool stop_ = false;
};

}  // namespace gamecore::ecs
