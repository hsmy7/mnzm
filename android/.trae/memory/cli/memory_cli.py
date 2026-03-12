#!/usr/bin/env python3
"""
Memory CLI Tool
记忆系统命令行工具
"""

import argparse
import json
import sys
from pathlib import Path
from datetime import datetime
from typing import Optional

# 添加父目录到路径
sys.path.insert(0, str(Path(__file__).parent.parent))

from storage.memory_store import MemoryStore, MemoryType, get_memory_store
from git.git_notes import GitNotesManager


class MemoryCLI:
    """记忆系统CLI"""
    
    def __init__(self, base_path: str = ".trae/memory"):
        self.store = get_memory_store(base_path)
        
    def status(self):
        """显示系统状态"""
        stats = self.store.get_stats()
        
        print("=" * 60)
        print("记忆系统状态")
        print("=" * 60)
        print(f"总记忆数: {stats['total_memories']}")
        print(f"总标签数: {stats['total_tags']}")
        print()
        print("记忆类型分布:")
        for mem_type, count in stats['types'].items():
            print(f"  {mem_type}: {count}")
        print()
        print("WAL统计:")
        wal_stats = stats['wal_stats']
        print(f"  总条目数: {wal_stats['total_entries']}")
        print(f"  当前序列号: {wal_stats['current_sequence']}")
        print(f"  缓冲区大小: {wal_stats['buffer_size']}")
        print(f"  归档文件数: {wal_stats['archive_count']}")
        print()
        print("向量搜索统计:")
        search_stats = stats['search_stats']
        print(f"  总向量数: {search_stats['total_vectors']}")
        print(f"  向量维度: {search_stats['dimensions']}")
        print(f"  索引层数: {search_stats['index_levels']}")
        print("=" * 60)
        
    def add(self, title: str, content: str, mem_type: str, tags: Optional[list] = None):
        """添加记忆"""
        try:
            memory_type = MemoryType(mem_type)
        except ValueError:
            print(f"错误: 无效的记忆类型 '{mem_type}'")
            print(f"有效类型: {[t.value for t in MemoryType]}")
            return
            
        memory = self.store.create(
            memory_type=memory_type,
            title=title,
            content=content,
            tags=tags or []
        )
        
        print(f"✓ 记忆已创建")
        print(f"  ID: {memory.id}")
        print(f"  标题: {memory.title}")
        print(f"  类型: {memory.type}")
        print(f"  标签: {', '.join(memory.tags) if memory.tags else '无'}")
        
    def search(self, query: str, mem_type: Optional[str] = None, k: int = 10):
        """搜索记忆"""
        memory_type = None
        if mem_type:
            try:
                memory_type = MemoryType(mem_type)
            except ValueError:
                print(f"错误: 无效的记忆类型 '{mem_type}'")
                return
                
        results = self.store.search(query, memory_type=memory_type, k=k)
        
        if not results:
            print(f"未找到与 '{query}' 相关的记忆")
            return
            
        print(f"找到 {len(results)} 条相关记忆:")
        print("-" * 60)
        
        for i, result in enumerate(results, 1):
            memory = result['memory']
            similarity = result['similarity']
            
            print(f"\n[{i}] {memory['title']}")
            print(f"    相似度: {similarity:.2%}")
            print(f"    类型: {memory['type']}")
            print(f"    标签: {', '.join(memory['tags']) if memory['tags'] else '无'}")
            print(f"    ID: {memory['id']}")
            
            # 显示内容摘要
            content = memory['content']
            if len(content) > 100:
                content = content[:100] + "..."
            print(f"    内容: {content}")
            
    def get(self, memory_id: str):
        """获取记忆详情"""
        memory = self.store.get(memory_id)
        
        if not memory:
            print(f"未找到记忆: {memory_id}")
            return
            
        print("=" * 60)
        print(f"标题: {memory.title}")
        print("=" * 60)
        print(f"ID: {memory.id}")
        print(f"类型: {memory.type}")
        print(f"标签: {', '.join(memory.tags) if memory.tags else '无'}")
        print(f"访问次数: {memory.access_count}")
        print(f"创建时间: {datetime.fromtimestamp(memory.created_at).strftime('%Y-%m-%d %H:%M:%S')}")
        print(f"更新时间: {datetime.fromtimestamp(memory.updated_at).strftime('%Y-%m-%d %H:%M:%S')}")
        print("-" * 60)
        print("内容:")
        print(memory.content)
        print("=" * 60)
        
    def delete(self, memory_id: str):
        """删除记忆"""
        if self.store.delete(memory_id):
            print(f"✓ 记忆已删除: {memory_id}")
        else:
            print(f"✗ 未找到记忆: {memory_id}")
            
    def list_by_type(self, mem_type: str):
        """按类型列出记忆"""
        try:
            memory_type = MemoryType(mem_type)
        except ValueError:
            print(f"错误: 无效的记忆类型 '{mem_type}'")
            return
            
        memories = self.store.get_by_type(memory_type)
        
        if not memories:
            print(f"类型 '{mem_type}' 下没有记忆")
            return
            
        print(f"类型 '{mem_type}' 的记忆 ({len(memories)} 条):")
        print("-" * 60)
        
        for memory in memories:
            print(f"\n  {memory.title}")
            print(f"    ID: {memory.id}")
            print(f"    标签: {', '.join(memory.tags) if memory.tags else '无'}")
            
    def list_by_tag(self, tag: str):
        """按标签列出记忆"""
        memories = self.store.get_by_tag(tag)
        
        if not memories:
            print(f"标签 '{tag}' 下没有记忆")
            return
            
        print(f"标签 '{tag}' 的记忆 ({len(memories)} 条):")
        print("-" * 60)
        
        for memory in memories:
            print(f"\n  {memory.title}")
            print(f"    ID: {memory.id}")
            print(f"    类型: {memory.type}")
            
    def list_tags(self):
        """列出所有标签"""
        tags = self.store.get_all_tags()
        
        if not tags:
            print("没有标签")
            return
            
        print(f"所有标签 ({len(tags)} 个):")
        print(", ".join(sorted(tags)))
        
    def list_types(self):
        """列出所有类型"""
        types = self.store.get_all_types()
        
        if not types:
            print("没有记忆类型")
            return
            
        print(f"所有类型 ({len(types)} 个):")
        for t in sorted(types):
            count = len(self.store.type_index.get(t, []))
            print(f"  {t}: {count} 条记忆")
            
    def export(self, filepath: str):
        """导出记忆"""
        self.store.export(filepath)
        print(f"✓ 记忆已导出到: {filepath}")
        
    def import_(self, filepath: str):
        """导入记忆"""
        self.store.import_(filepath)
        print(f"✓ 记忆已从 {filepath} 导入")
        
    def sync_to_git(self):
        """同步到git notes"""
        try:
            from git.git_notes import GitNotesManager
            
            memories = [m.to_dict() for m in self.store.memories.values()]
            manager = GitNotesManager()
            commit = manager.sync_to_git(memories)
            
            print(f"✓ 记忆已同步到git notes")
            print(f"  Commit: {commit}")
            print(f"  记忆数: {len(memories)}")
        except Exception as e:
            print(f"✗ 同步失败: {e}")
            
    def sync_from_git(self):
        """从git notes恢复"""
        try:
            from git.git_notes import GitNotesManager
            
            manager = GitNotesManager()
            memories = manager.sync_from_git()
            
            if memories:
                print(f"✓ 从git notes恢复 {len(memories)} 条记忆")
                # 这里可以添加导入逻辑
            else:
                print("未在git notes中找到记忆")
        except Exception as e:
            print(f"✗ 恢复失败: {e}")
            
    def cleanup(self, days: int = 365):
        """清理旧记忆"""
        count = self.store.cleanup_old_memories(days)
        print(f"✓ 已清理 {count} 条旧记忆（超过 {days} 天未访问）")
        
    def close(self):
        """关闭存储"""
        self.store.close()


def main():
    """主函数"""
    parser = argparse.ArgumentParser(
        description="精英长期记忆系统 CLI",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  %(prog)s status                           # 查看系统状态
  %(prog)s add "项目配置" "使用Kotlin" project --tags android kotlin
  %(prog)s search "洞府系统"                # 搜索记忆
  %(prog)s get <memory_id>                  # 查看记忆详情
  %(prog)s delete <memory_id>               # 删除记忆
  %(prog)s list-type feature                # 列出所有功能需求
  %(prog)s list-tag kotlin                  # 列出所有kotlin相关记忆
  %(prog)s export backup.json               # 导出记忆
  %(prog)s import backup.json               # 导入记忆
  %(prog)s sync-to-git                      # 同步到git
  %(prog)s sync-from-git                    # 从git恢复
        """
    )
    
    subparsers = parser.add_subparsers(dest="command", help="可用命令")
    
    # status
    subparsers.add_parser("status", help="显示系统状态")
    
    # add
    add_parser = subparsers.add_parser("add", help="添加记忆")
    add_parser.add_argument("title", help="记忆标题")
    add_parser.add_argument("content", help="记忆内容")
    add_parser.add_argument("type", help="记忆类型", choices=[t.value for t in MemoryType])
    add_parser.add_argument("--tags", nargs="+", help="标签列表")
    
    # search
    search_parser = subparsers.add_parser("search", help="搜索记忆")
    search_parser.add_argument("query", help="搜索关键词")
    search_parser.add_argument("--type", help="过滤类型", choices=[t.value for t in MemoryType])
    search_parser.add_argument("-k", type=int, default=10, help="返回结果数量")
    
    # get
    get_parser = subparsers.add_parser("get", help="获取记忆详情")
    get_parser.add_argument("memory_id", help="记忆ID")
    
    # delete
    delete_parser = subparsers.add_parser("delete", help="删除记忆")
    delete_parser.add_argument("memory_id", help="记忆ID")
    
    # list-type
    list_type_parser = subparsers.add_parser("list-type", help="按类型列出记忆")
    list_type_parser.add_argument("type", help="记忆类型", choices=[t.value for t in MemoryType])
    
    # list-tag
    list_tag_parser = subparsers.add_parser("list-tag", help="按标签列出记忆")
    list_tag_parser.add_argument("tag", help="标签")
    
    # list-tags
    subparsers.add_parser("list-tags", help="列出所有标签")
    
    # list-types
    subparsers.add_parser("list-types", help="列出所有类型")
    
    # export
    export_parser = subparsers.add_parser("export", help="导出记忆")
    export_parser.add_argument("filepath", help="导出文件路径")
    
    # import
    import_parser = subparsers.add_parser("import", help="导入记忆")
    import_parser.add_argument("filepath", help="导入文件路径")
    
    # sync-to-git
    subparsers.add_parser("sync-to-git", help="同步到git notes")
    
    # sync-from-git
    subparsers.add_parser("sync-from-git", help="从git notes恢复")
    
    # cleanup
    cleanup_parser = subparsers.add_parser("cleanup", help="清理旧记忆")
    cleanup_parser.add_argument("--days", type=int, default=365, help="保留天数")
    
    args = parser.parse_args()
    
    if not args.command:
        parser.print_help()
        return
        
    cli = MemoryCLI()
    
    try:
        if args.command == "status":
            cli.status()
        elif args.command == "add":
            cli.add(args.title, args.content, args.type, args.tags)
        elif args.command == "search":
            cli.search(args.query, args.type, args.k)
        elif args.command == "get":
            cli.get(args.memory_id)
        elif args.command == "delete":
            cli.delete(args.memory_id)
        elif args.command == "list-type":
            cli.list_by_type(args.type)
        elif args.command == "list-tag":
            cli.list_by_tag(args.tag)
        elif args.command == "list-tags":
            cli.list_tags()
        elif args.command == "list-types":
            cli.list_types()
        elif args.command == "export":
            cli.export(args.filepath)
        elif args.command == "import":
            cli.import_(args.filepath)
        elif args.command == "sync-to-git":
            cli.sync_to_git()
        elif args.command == "sync-from-git":
            cli.sync_from_git()
        elif args.command == "cleanup":
            cli.cleanup(args.days)
    finally:
        cli.close()


if __name__ == "__main__":
    main()
