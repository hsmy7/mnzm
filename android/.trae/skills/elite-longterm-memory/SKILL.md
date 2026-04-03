---
name: "elite-longterm-memory"
description: "Ultimate AI agent memory system for Cursor, Claude, ChatGPT & Copilot. WAL protocol + vector search + git-notes + cloud backup. Never lose context again. Vibe-coding ready."
---

# Elite Long-term Memory System

终极AI助手记忆系统 - 让AI永远记住你的项目、偏好和对话历史。

## 核心功能

### 1. WAL预写日志协议 (Write-Ahead Logging)
- 所有记忆变更先写入日志，确保数据安全
- 支持事务回滚和崩溃恢复
- 防止数据丢失和损坏

### 2. 向量语义搜索 (Vector Search)
- 基于语义的智能检索，不只是关键词匹配
- 自动理解查询意图，找到最相关的历史记忆
- 支持相似度排序和相关性评分

### 3. Git-Notes集成
- 记忆数据存储在git notes中
- 与代码版本同步，可追溯历史
- 支持分支独立的记忆管理

### 4. 云备份同步
- 多设备间记忆同步
- 加密存储保护隐私
- 自动备份和恢复

## 使用场景

### 场景1: 项目上下文保持
```
用户: 实现洞府探索功能
[系统记录]: 项目使用Kotlin + Jetpack Compose, 游戏架构为MVVM

3天后...
用户: 继续完善洞府系统
[系统自动加载]: 洞府系统已实现的字段、当前进度、技术栈信息
```

### 场景2: 个人偏好学习
```
用户: 代码要简洁，不要过多注释
[系统记录]: 用户偏好 = 简洁代码风格

后续所有代码生成自动遵循此偏好
```

### 场景3: 复杂需求追踪
```
用户: 之前洞府的奖励概率是多少？
[向量搜索]: 找到相关记忆 -> 灵石/丹药/装备/功法各60%，数量1-3
```

## 记忆类型

| 类型 | 说明 | 示例 |
|------|------|------|
| **项目信息** | 技术栈、架构、配置 | "Kotlin + Compose + Room" |
| **代码规范** | 风格偏好、命名约定 | "简洁代码，最小注释" |
| **功能需求** | 已实现/待实现功能 | "洞府系统: 随机生成、探索战斗、奖励发放" |
| **设计决策** | 为什么选择某方案 | "装备概率70%平衡游戏性" |
| **问题记录** | Bug修复、解决方案 | "数据库版本12修复schema问题" |

## 工作流程

### 自动记录触发点
1. **对话开始** - 加载相关上下文记忆
2. **代码修改** - 记录技术决策和变更原因
3. **需求讨论** - 提取并存储功能规格
4. **问题解决** - 记录问题和解决方案
5. **对话结束** - 总结并持久化新记忆

### 记忆检索流程
```
用户输入
    ↓
语义向量化
    ↓
向量相似度搜索
    ↓
相关性评分排序
    ↓
返回最相关记忆
    ↓
注入对话上下文
```

## 文件结构

```
.trae/
└── memory/
    ├── wal/                    # 预写日志
    │   ├── current.log         # 当前事务日志
    │   └── archive/            # 归档日志
    ├── vectors/                # 向量存储
    │   ├── index.bin           # 向量索引
    │   └── metadata.json       # 元数据映射
    ├── snapshots/              # 记忆快照
    │   └── YYYYMMDD_HHMMSS.json
    └── config.json             # 配置信息
```

## 命令接口

### 记忆管理
```bash
# 查看当前记忆状态
memory status

# 手动添加记忆
memory add "关键信息" --tag "项目配置"

# 搜索记忆
memory search "洞府系统"

# 导出记忆备份
memory export --format json

# 导入记忆
memory import backup.json

# 清理过期记忆
memory cleanup --older-than 90d
```

### Git集成
```bash
# 将记忆同步到git notes
memory sync-to-git

# 从git notes恢复记忆
memory sync-from-git

# 查看记忆历史
memory log --graph
```

## 配置选项

```json
{
  "auto_record": true,           // 自动记录对话
  "vector_dim": 1536,            // 向量维度
  "max_memories": 10000,         // 最大记忆数
  "retention_days": 365,         // 记忆保留天数
  "sync_to_git": true,           // 同步到git
  "cloud_backup": false,         // 云备份开关
  "similarity_threshold": 0.75   // 相似度阈值
}
```

## 最佳实践

1. **定期同步** - 每天执行 `memory sync-to-git` 备份
2. **标签分类** - 使用标签组织不同类型的记忆
3. **定期清理** - 删除过期或无效的记忆保持系统高效
4. **敏感信息** - 避免记录密码、密钥等敏感数据

## 故障排除

| 问题 | 解决方案 |
|------|----------|
| 记忆丢失 | 执行 `memory sync-from-git` 恢复 |
| 搜索不准确 | 调整 `similarity_threshold` 参数 |
| 存储过大 | 运行 `memory cleanup` 清理旧数据 |
| 同步失败 | 检查git配置和网络连接 |

## 技术规格

- **向量引擎**: HNSW (Hierarchical Navigable Small World)
- **嵌入模型**: text-embedding-3-small
- **存储格式**: JSON + Binary
- **加密方式**: AES-256-GCM (云备份)
- **支持平台**: Windows, macOS, Linux
