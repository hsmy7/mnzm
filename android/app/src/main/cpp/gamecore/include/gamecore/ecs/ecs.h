#pragma once

// ============================================================
// ECS 统一入口（phase ①-④：通用 ECS 骨架 / System 调度 / JobSystem / 弟子接入）
//
// 单头引入全部 ECS 组件。纯头文件、C++20、零平台依赖（core/platform.h 端口注入
// 之外无任何 Android/iOS 泄漏）。JSON 存档协议零变更：
//   - EntityId / ComponentTypeId / DiscipleRef 均为纯运行态，不入 json_codec。
//   - DiscipleStore 仍是弟子数据唯一权威；ecs/disciple_component.h 为派生层。
// ============================================================

#include "gamecore/ecs/entity.h"
#include "gamecore/ecs/component.h"
#include "gamecore/ecs/storage.h"
#include "gamecore/ecs/registry.h"
#include "gamecore/ecs/world.h"
#include "gamecore/ecs/view.h"
#include "gamecore/ecs/system.h"
#include "gamecore/ecs/job_system.h"
#include "gamecore/ecs/disciple_component.h"
