"""
Memory Storage Manager
记忆存储管理器 - 统一管理记忆的增删改查
"""

import json
import uuid
import time
from typing import List, Dict, Optional, Any
from pathlib import Path
from dataclasses import dataclass, asdict
from datetime import datetime
from enum import Enum

import sys
sys.path.insert(0, str(Path(__file__).parent.parent))

from core.wal import WALManager, LogType, get_wal_manager
from search.vector_search import VectorSearchEngine, get_search_engine


class MemoryType(Enum):
    """记忆类型"""
    PROJECT = "project"        # 项目信息
    CODE_STYLE = "code_style"  # 代码规范
    FEATURE = "feature"        # 功能需求
    DECISION = "decision"      # 设计决策
    ISSUE = "issue"            # 问题记录
    CONVERSATION = "conversation"  # 对话记录


@dataclass
class Memory:
    """记忆数据类"""
    id: str
    type: str
    title: str
    content: str
    tags: List[str]
    created_at: float
    updated_at: float
    access_count: int
    last_accessed: float
    metadata: Dict[str, Any]
    
    def to_dict(self) -> Dict:
        return {
            "id": self.id,
            "type": self.type,
            "title": self.title,
            "content": self.content,
            "tags": self.tags,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "access_count": self.access_count,
            "last_accessed": self.last_accessed,
            "metadata": self.metadata
        }
    
    @classmethod
    def from_dict(cls, data: Dict) -> 'Memory':
        return cls(
            id=data["id"],
            type=data["type"],
            title=data["title"],
            content=data["content"],
            tags=data.get("tags", []),
            created_at=data["created_at"],
            updated_at=data["updated_at"],
            access_count=data.get("access_count", 0),
            last_accessed=data.get("last_accessed", data["created_at"]),
            metadata=data.get("metadata", {})
        )
    
    def get_search_text(self) -> str:
        """获取用于搜索的文本"""
        return f"{self.title} {self.content} {' '.join(self.tags)}"


class MemoryStore:
    """记忆存储管理器"""
    
    def __init__(self, base_path: str = ".trae/memory"):
        self.base_path = Path(base_path)
        self.store_path = self.base_path / "store.json"
        
        self.wal = get_wal_manager(base_path)
        self.search_engine = get_search_engine(base_path)
        
        self.memories: Dict[str, Memory] = {}
        self.tag_index: Dict[str, List[str]] = {}  # tag -> memory_ids
        self.type_index: Dict[str, List[str]] = {}  # type -> memory_ids
        
        self._ensure_directories()
        self._recover_from_wal()
        
    def _ensure_directories(self):
        """确保目录存在"""
        self.base_path.mkdir(parents=True, exist_ok=True)
        
    def _recover_from_wal(self):
        """从WAL日志恢复数据"""
        def apply_entry(entry):
            if entry.log_type == LogType.INSERT.value:
                if entry.data:
                    memory = Memory.from_dict(entry.data)
                    self._add_to_indices(memory)
                    self.memories[memory.id] = memory
                    
            elif entry.log_type == LogType.UPDATE.value:
                if entry.data:
                    memory = Memory.from_dict(entry.data)
                    if memory.id in self.memories:
                        self._remove_from_indices(self.memories[memory.id])
                    self._add_to_indices(memory)
                    self.memories[memory.id] = memory
                    
            elif entry.log_type == LogType.DELETE.value:
                if entry.memory_id in self.memories:
                    self._remove_from_indices(self.memories[entry.memory_id])
                    del self.memories[entry.memory_id]
                    
        self.wal.recover(apply_entry)
        
        # 同步到向量索引
        for memory in self.memories.values():
            self.search_engine.add(
                memory.id,
                memory.get_search_text(),
                {"type": memory.type, "title": memory.title}
            )
            
    def _add_to_indices(self, memory: Memory):
        """添加记忆到索引"""
        # 类型索引
        if memory.type not in self.type_index:
            self.type_index[memory.type] = []
        if memory.id not in self.type_index[memory.type]:
            self.type_index[memory.type].append(memory.id)
            
        # 标签索引
        for tag in memory.tags:
            if tag not in self.tag_index:
                self.tag_index[tag] = []
            if memory.id not in self.tag_index[tag]:
                self.tag_index[tag].append(memory.id)
                
    def _remove_from_indices(self, memory: Memory):
        """从索引中移除记忆"""
        # 类型索引
        if memory.type in self.type_index and memory.id in self.type_index[memory.type]:
            self.type_index[memory.type].remove(memory.id)
            
        # 标签索引
        for tag in memory.tags:
            if tag in self.tag_index and memory.id in self.tag_index[tag]:
                self.tag_index[tag].remove(memory.id)
                
    def create(
        self,
        memory_type: MemoryType,
        title: str,
        content: str,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict] = None
    ) -> Memory:
        """
        创建新记忆
        
        Args:
            memory_type: 记忆类型
            title: 标题
            content: 内容
            tags: 标签列表
            metadata: 附加元数据
            
        Returns:
            创建的记忆对象
        """
        now = time.time()
        
        memory = Memory(
            id=str(uuid.uuid4()),
            type=memory_type.value,
            title=title,
            content=content,
            tags=tags or [],
            created_at=now,
            updated_at=now,
            access_count=0,
            last_accessed=now,
            metadata=metadata or {}
        )
        
        # 写入WAL
        self.wal.append(LogType.INSERT, memory.id, memory.to_dict())
        
        # 添加到内存索引
        self._add_to_indices(memory)
        self.memories[memory.id] = memory
        
        # 添加到向量索引
        self.search_engine.add(
            memory.id,
            memory.get_search_text(),
            {"type": memory.type, "title": memory.title}
        )
        
        return memory
        
    def get(self, memory_id: str) -> Optional[Memory]:
        """
        获取记忆
        
        Args:
            memory_id: 记忆ID
            
        Returns:
            记忆对象或None
        """
        memory = self.memories.get(memory_id)
        if memory:
            memory.access_count += 1
            memory.last_accessed = time.time()
        return memory
        
    def update(
        self,
        memory_id: str,
        title: Optional[str] = None,
        content: Optional[str] = None,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict] = None
    ) -> Optional[Memory]:
        """
        更新记忆
        
        Args:
            memory_id: 记忆ID
            title: 新标题
            content: 新内容
            tags: 新标签列表
            metadata: 新元数据（会合并）
            
        Returns:
            更新后的记忆对象或None
        """
        memory = self.memories.get(memory_id)
        if not memory:
            return None
            
        # 从索引中移除旧数据
        self._remove_from_indices(memory)
        self.search_engine.remove(memory_id)
        
        # 更新字段
        if title is not None:
            memory.title = title
        if content is not None:
            memory.content = content
        if tags is not None:
            memory.tags = tags
        if metadata is not None:
            memory.metadata.update(metadata)
            
        memory.updated_at = time.time()
        
        # 写入WAL
        self.wal.append(LogType.UPDATE, memory.id, memory.to_dict())
        
        # 添加到新索引
        self._add_to_indices(memory)
        self.search_engine.add(
            memory.id,
            memory.get_search_text(),
            {"type": memory.type, "title": memory.title}
        )
        
        return memory
        
    def delete(self, memory_id: str) -> bool:
        """
        删除记忆
        
        Args:
            memory_id: 记忆ID
            
        Returns:
            是否删除成功
        """
        memory = self.memories.get(memory_id)
        if not memory:
            return False
            
        # 写入WAL
        self.wal.append(LogType.DELETE, memory_id)
        
        # 从索引中移除
        self._remove_from_indices(memory)
        del self.memories[memory_id]
        
        # 从向量索引中移除
        self.search_engine.remove(memory_id)
        
        return True
        
    def search(
        self,
        query: str,
        memory_type: Optional[MemoryType] = None,
        tags: Optional[List[str]] = None,
        k: int = 10,
        threshold: float = 0.3
    ) -> List[Dict]:
        """
        搜索记忆
        
        Args:
            query: 查询文本
            memory_type: 过滤类型
            tags: 过滤标签
            k: 返回结果数量
            threshold: 相似度阈值
            
        Returns:
            搜索结果列表
        """
        # 向量搜索
        results = self.search_engine.search(query, k=k * 2, threshold=threshold)
        
        # 应用过滤器
        filtered = []
        for result in results:
            memory = self.memories.get(result["id"])
            if not memory:
                continue
                
            # 类型过滤
            if memory_type and memory.type != memory_type.value:
                continue
                
            # 标签过滤
            if tags and not any(tag in memory.tags for tag in tags):
                continue
                
            filtered.append({
                "memory": memory.to_dict(),
                "similarity": result["similarity"]
            })
            
            if len(filtered) >= k:
                break
                
        return filtered
        
    def get_by_type(self, memory_type: MemoryType) -> List[Memory]:
        """
        按类型获取记忆
        
        Args:
            memory_type: 记忆类型
            
        Returns:
            记忆列表
        """
        ids = self.type_index.get(memory_type.value, [])
        return [self.memories[mid] for mid in ids if mid in self.memories]
        
    def get_by_tag(self, tag: str) -> List[Memory]:
        """
        按标签获取记忆
        
        Args:
            tag: 标签
            
        Returns:
            记忆列表
        """
        ids = self.tag_index.get(tag, [])
        return [self.memories[mid] for mid in ids if mid in self.memories]
        
    def get_all_tags(self) -> List[str]:
        """获取所有标签"""
        return list(self.tag_index.keys())
        
    def get_all_types(self) -> List[str]:
        """获取所有类型"""
        return list(self.type_index.keys())
        
    def get_stats(self) -> Dict:
        """获取统计信息"""
        return {
            "total_memories": len(self.memories),
            "total_tags": len(self.tag_index),
            "types": {t: len(ids) for t, ids in self.type_index.items()},
            "wal_stats": self.wal.get_stats(),
            "search_stats": self.search_engine.get_stats()
        }
        
    def export(self, filepath: str):
        """
        导出记忆到文件
        
        Args:
            filepath: 导出文件路径
        """
        data = {
            "export_time": datetime.now().isoformat(),
            "memories": [m.to_dict() for m in self.memories.values()]
        }
        
        Path(filepath).write_text(
            json.dumps(data, ensure_ascii=False, indent=2),
            encoding="utf-8"
        )
        
    def import_(self, filepath: str):
        """
        从文件导入记忆
        
        Args:
            filepath: 导入文件路径
        """
        data = json.loads(Path(filepath).read_text(encoding="utf-8"))
        
        for memory_data in data.get("memories", []):
            memory = Memory.from_dict(memory_data)
            
            # 生成新ID避免冲突
            memory.id = str(uuid.uuid4())
            memory.created_at = time.time()
            memory.updated_at = time.time()
            
            # 写入WAL
            self.wal.append(LogType.INSERT, memory.id, memory.to_dict())
            
            # 添加到索引
            self._add_to_indices(memory)
            self.memories[memory.id] = memory
            
            self.search_engine.add(
                memory.id,
                memory.get_search_text(),
                {"type": memory.type, "title": memory.title}
            )
            
    def cleanup_old_memories(self, days: int = 365):
        """
        清理旧记忆
        
        Args:
            days: 保留天数
        """
        cutoff = time.time() - (days * 24 * 60 * 60)
        
        to_delete = [
            mid for mid, m in self.memories.items()
            if m.last_accessed < cutoff and m.access_count < 5
        ]
        
        for mid in to_delete:
            self.delete(mid)
            
        return len(to_delete)
        
    def close(self):
        """关闭存储管理器"""
        self.wal.close()


# 单例实例
_store_instance: Optional[MemoryStore] = None


def get_memory_store(base_path: str = ".trae/memory") -> MemoryStore:
    """获取存储管理器单例"""
    global _store_instance
    if _store_instance is None:
        _store_instance = MemoryStore(base_path)
    return _store_instance


def reset_memory_store():
    """重置存储管理器（用于测试）"""
    global _store_instance
    if _store_instance:
        _store_instance.close()
    _store_instance = None
