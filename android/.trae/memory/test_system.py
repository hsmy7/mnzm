"""
Memory System Test
记忆系统测试脚本
"""

import sys
from pathlib import Path

# 添加路径
sys.path.insert(0, str(Path(__file__).parent))

from storage.memory_store import MemoryStore, MemoryType, reset_memory_store
from search.vector_search import VectorSearchEngine, reset_search_engine
from core.wal import WALManager, LogType, reset_wal_manager


def test_wal():
    """测试WAL系统"""
    print("=" * 60)
    print("测试 WAL 预写日志系统")
    print("=" * 60)
    
    reset_wal_manager()
    wal = WALManager(".trae/memory/test")
    
    # 添加日志条目
    wal.append(LogType.INSERT, "test-1", {"title": "测试记忆", "content": "内容"})
    wal.append(LogType.INSERT, "test-2", {"title": "另一个测试", "content": "更多内容"})
    wal.append(LogType.UPDATE, "test-1", {"title": "更新的测试", "content": "更新内容"})
    
    # 获取统计
    stats = wal.get_stats()
    print(f"✓ WAL条目数: {stats['total_entries']}")
    print(f"✓ 当前序列号: {stats['current_sequence']}")
    
    # 读取所有条目
    entries = wal.read_all()
    print(f"✓ 成功读取 {len(entries)} 条日志")
    
    wal.close()
    print("✓ WAL测试通过\n")


def test_vector_search():
    """测试向量搜索"""
    print("=" * 60)
    print("测试 向量搜索引擎")
    print("=" * 60)
    
    reset_search_engine()
    engine = VectorSearchEngine(".trae/memory/test", dim=64)
    
    # 添加测试数据
    test_memories = [
        ("mem-1", "Kotlin Android开发使用Jetpack Compose", {"type": "project"}),
        ("mem-2", "洞府系统包含随机生成和探索功能", {"type": "feature"}),
        ("mem-3", "代码风格要求简洁明了", {"type": "code_style"}),
        ("mem-4", "修仙游戏使用MVVM架构", {"type": "project"}),
        ("mem-5", "装备系统包含品阶和强化", {"type": "feature"}),
    ]
    
    for mem_id, text, meta in test_memories:
        engine.add(mem_id, text, meta)
        
    print(f"✓ 添加了 {len(test_memories)} 条记忆")
    
    # 搜索测试
    results = engine.search("Kotlin开发", k=3)
    print(f"✓ 搜索'Kotlin开发'找到 {len(results)} 条结果")
    
    for r in results:
        print(f"  - {r['id']}: 相似度 {r['similarity']:.2%}")
        
    results = engine.search("洞府探索", k=3)
    print(f"✓ 搜索'洞府探索'找到 {len(results)} 条结果")
    
    for r in results:
        print(f"  - {r['id']}: 相似度 {r['similarity']:.2%}")
        
    stats = engine.get_stats()
    print(f"✓ 向量总数: {stats['total_vectors']}")
    print(f"✓ 向量维度: {stats['dimensions']}")
    
    print("✓ 向量搜索测试通过\n")


def test_memory_store():
    """测试记忆存储"""
    print("=" * 60)
    print("测试 记忆存储管理器")
    print("=" * 60)
    
    reset_wal_manager()
    reset_search_engine()
    reset_memory_store()
    
    store = MemoryStore(".trae/memory/test")
    
    # 创建记忆
    mem1 = store.create(
        MemoryType.PROJECT,
        "项目技术栈",
        "本项目使用Kotlin语言和Jetpack Compose框架开发",
        tags=["kotlin", "android", "compose"]
    )
    print(f"✓ 创建记忆: {mem1.title} (ID: {mem1.id[:8]}...)")
    
    mem2 = store.create(
        MemoryType.FEATURE,
        "洞府系统",
        "洞府系统包含随机生成、探索战斗、奖励发放等功能",
        tags=["game", "cave", "exploration"]
    )
    print(f"✓ 创建记忆: {mem2.title} (ID: {mem2.id[:8]}...)")
    
    mem3 = store.create(
        MemoryType.CODE_STYLE,
        "代码规范",
        "代码要简洁，不要过多注释，遵循Kotlin编码规范",
        tags=["coding", "style"]
    )
    print(f"✓ 创建记忆: {mem3.title} (ID: {mem3.id[:8]}...)")
    
    # 搜索测试
    results = store.search("Kotlin开发", k=5)
    print(f"✓ 搜索找到 {len(results)} 条结果")
    
    # 按类型获取
    project_memories = store.get_by_type(MemoryType.PROJECT)
    print(f"✓ 项目类型记忆: {len(project_memories)} 条")
    
    # 按标签获取
    kotlin_memories = store.get_by_tag("kotlin")
    print(f"✓ Kotlin标签记忆: {len(kotlin_memories)} 条")
    
    # 获取统计
    stats = store.get_stats()
    print(f"✓ 总记忆数: {stats['total_memories']}")
    print(f"✓ 总标签数: {stats['total_tags']}")
    
    store.close()
    print("✓ 记忆存储测试通过\n")


def test_full_workflow():
    """测试完整工作流程"""
    print("=" * 60)
    print("测试 完整工作流程")
    print("=" * 60)
    
    reset_wal_manager()
    reset_search_engine()
    reset_memory_store()
    
    store = MemoryStore(".trae/memory/test")
    
    # 模拟对话记录
    conversation_memories = [
        ("需求讨论", "用户要求实现洞府系统，包含随机生成和探索功能"),
        ("技术决策", "决定使用HNSW算法实现向量搜索"),
        ("Bug修复", "修复了数据库版本不匹配的问题"),
        ("性能优化", "优化了世界地图的渲染性能"),
    ]
    
    for title, content in conversation_memories:
        store.create(
            MemoryType.CONVERSATION,
            title,
            content,
            tags=["conversation", "record"]
        )
        
    print(f"✓ 记录了 {len(conversation_memories)} 条对话")
    
    # 模拟后续查询
    results = store.search("洞府系统功能", k=3)
    print(f"✓ 查询'洞府系统功能'找到 {len(results)} 条相关记忆")
    
    results = store.search("性能问题", k=3)
    print(f"✓ 查询'性能问题'找到 {len(results)} 条相关记忆")
    
    # 导出测试
    store.export(".trae/memory/test_export.json")
    print("✓ 成功导出记忆到文件")
    
    store.close()
    print("✓ 完整工作流程测试通过\n")


def cleanup_test_data():
    """清理测试数据"""
    import shutil
    test_dir = Path(".trae/memory/test")
    if test_dir.exists():
        shutil.rmtree(test_dir)
        print("✓ 清理测试数据完成")


def main():
    """主测试函数"""
    print("\n" + "=" * 60)
    print("精英长期记忆系统 - 功能测试")
    print("=" * 60 + "\n")
    
    try:
        test_wal()
        test_vector_search()
        test_memory_store()
        test_full_workflow()
        
        print("=" * 60)
        print("所有测试通过！")
        print("=" * 60)
        
    except Exception as e:
        print(f"\n✗ 测试失败: {e}")
        import traceback
        traceback.print_exc()
        
    finally:
        cleanup_test_data()


if __name__ == "__main__":
    main()
