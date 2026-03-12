"""
Elite Long-term Memory System
精英长期记忆系统

终极AI助手记忆系统，提供WAL协议、向量搜索、Git-Notes集成和云备份功能。
"""

__version__ = "1.0.0"
__author__ = "AI Assistant"

from .storage.memory_store import (
    MemoryStore,
    Memory,
    MemoryType,
    get_memory_store,
    reset_memory_store
)

from .search.vector_search import (
    VectorSearchEngine,
    get_search_engine,
    reset_search_engine
)

from .core.wal import (
    WALManager,
    LogType,
    LogEntry,
    get_wal_manager,
    reset_wal_manager
)

from .git.git_notes import (
    GitNotesManager,
    init_git_notes,
    sync_memories_to_git,
    load_memories_from_git
)

__all__ = [
    # 存储
    "MemoryStore",
    "Memory",
    "MemoryType",
    "get_memory_store",
    "reset_memory_store",
    
    # 搜索
    "VectorSearchEngine",
    "get_search_engine",
    "reset_search_engine",
    
    # WAL
    "WALManager",
    "LogType",
    "LogEntry",
    "get_wal_manager",
    "reset_wal_manager",
    
    # Git
    "GitNotesManager",
    "init_git_notes",
    "sync_memories_to_git",
    "load_memories_from_git",
]


def init_memory_system(base_path: str = ".trae/memory") -> MemoryStore:
    """
    初始化记忆系统
    
    Args:
        base_path: 存储路径
        
    Returns:
        MemoryStore实例
    """
    return get_memory_store(base_path)


def get_memory_system() -> MemoryStore:
    """
    获取记忆系统实例
    
    Returns:
        MemoryStore实例
    """
    return get_memory_store()


def close_memory_system():
    """关闭记忆系统"""
    store = get_memory_store()
    store.close()
    reset_memory_store()
    reset_search_engine()
    reset_wal_manager()
