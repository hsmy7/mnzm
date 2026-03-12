"""
WAL (Write-Ahead Logging) System
预写日志系统 - 确保数据安全和事务完整性
"""

import json
import os
import time
import hashlib
from datetime import datetime
from typing import List, Dict, Optional, Callable
from pathlib import Path
from dataclasses import dataclass, asdict
from enum import Enum


class LogType(Enum):
    """日志类型"""
    INSERT = "INSERT"      # 插入记忆
    UPDATE = "UPDATE"      # 更新记忆
    DELETE = "DELETE"      # 删除记忆
    CHECKPOINT = "CHECKPOINT"  # 检查点
    COMPACT = "COMPACT"    # 压缩归档


@dataclass
class LogEntry:
    """日志条目"""
    timestamp: float
    log_type: str
    memory_id: str
    data: Optional[Dict]
    checksum: str
    sequence: int
    
    def to_dict(self) -> Dict:
        return {
            "timestamp": self.timestamp,
            "log_type": self.log_type,
            "memory_id": self.memory_id,
            "data": self.data,
            "checksum": self.checksum,
            "sequence": self.sequence
        }
    
    @classmethod
    def from_dict(cls, data: Dict) -> 'LogEntry':
        return cls(
            timestamp=data["timestamp"],
            log_type=data["log_type"],
            memory_id=data["memory_id"],
            data=data.get("data"),
            checksum=data["checksum"],
            sequence=data["sequence"]
        )


class WALManager:
    """WAL日志管理器"""
    
    def __init__(self, base_path: str = ".trae/memory"):
        self.base_path = Path(base_path)
        self.wal_dir = self.base_path / "wal"
        self.archive_dir = self.wal_dir / "archive"
        self.current_log = self.wal_dir / "current.log"
        self.sequence_file = self.wal_dir / "sequence"
        
        self._ensure_directories()
        self._sequence = self._load_sequence()
        self._buffer: List[LogEntry] = []
        self._buffer_size = 100  # 缓冲区大小
        self._checkpoint_interval = 1000  # 检查点间隔
        
    def _ensure_directories(self):
        """确保目录存在"""
        self.wal_dir.mkdir(parents=True, exist_ok=True)
        self.archive_dir.mkdir(parents=True, exist_ok=True)
        
    def _load_sequence(self) -> int:
        """加载序列号"""
        if self.sequence_file.exists():
            try:
                return int(self.sequence_file.read_text().strip())
            except:
                return 0
        return 0
    
    def _save_sequence(self):
        """保存序列号"""
        self.sequence_file.write_text(str(self._sequence))
        
    def _compute_checksum(self, data: Dict) -> str:
        """计算数据校验和"""
        content = json.dumps(data, sort_keys=True, ensure_ascii=False)
        return hashlib.sha256(content.encode()).hexdigest()[:16]
    
    def _verify_checksum(self, entry: LogEntry) -> bool:
        """验证校验和"""
        if entry.data is None:
            return True
        computed = self._compute_checksum(entry.data)
        return computed == entry.checksum
    
    def _next_sequence(self) -> int:
        """获取下一个序列号"""
        self._sequence += 1
        self._save_sequence()
        return self._sequence
    
    def append(self, log_type: LogType, memory_id: str, data: Optional[Dict] = None):
        """
        追加日志条目
        
        Args:
            log_type: 日志类型
            memory_id: 记忆ID
            data: 记忆数据
        """
        checksum = self._compute_checksum(data) if data else ""
        
        entry = LogEntry(
            timestamp=time.time(),
            log_type=log_type.value,
            memory_id=memory_id,
            data=data,
            checksum=checksum,
            sequence=self._next_sequence()
        )
        
        self._buffer.append(entry)
        
        # 缓冲区满时刷新到磁盘
        if len(self._buffer) >= self._buffer_size:
            self._flush()
            
        # 达到检查点间隔时创建检查点
        if self._sequence % self._checkpoint_interval == 0:
            self._create_checkpoint()
    
    def _flush(self):
        """将缓冲区刷新到磁盘"""
        if not self._buffer:
            return
            
        with open(self.current_log, "a", encoding="utf-8") as f:
            for entry in self._buffer:
                line = json.dumps(entry.to_dict(), ensure_ascii=False)
                f.write(line + "\n")
                
        self._buffer.clear()
        
    def _create_checkpoint(self):
        """创建检查点"""
        self._flush()
        
        checkpoint_entry = LogEntry(
            timestamp=time.time(),
            log_type=LogType.CHECKPOINT.value,
            memory_id="checkpoint",
            data={"sequence": self._sequence, "timestamp": datetime.now().isoformat()},
            checksum="",
            sequence=self._next_sequence()
        )
        
        with open(self.current_log, "a", encoding="utf-8") as f:
            line = json.dumps(checkpoint_entry.to_dict(), ensure_ascii=False)
            f.write(line + "\n")
    
    def read_all(self) -> List[LogEntry]:
        """读取所有日志条目"""
        entries = []
        
        # 读取归档日志
        for archive_file in sorted(self.archive_dir.glob("*.log")):
            entries.extend(self._read_log_file(archive_file))
            
        # 读取当前日志
        if self.current_log.exists():
            entries.extend(self._read_log_file(self.current_log))
            
        # 读取缓冲区
        entries.extend(self._buffer)
        
        # 按序列号排序
        entries.sort(key=lambda e: e.sequence)
        
        return entries
    
    def _read_log_file(self, filepath: Path) -> List[LogEntry]:
        """读取日志文件"""
        entries = []
        
        if not filepath.exists():
            return entries
            
        try:
            with open(filepath, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        data = json.loads(line)
                        entry = LogEntry.from_dict(data)
                        
                        # 验证校验和
                        if not self._verify_checksum(entry):
                            print(f"Warning: Checksum mismatch for entry {entry.sequence}")
                            continue
                            
                        entries.append(entry)
                    except json.JSONDecodeError:
                        continue
        except Exception as e:
            print(f"Error reading log file {filepath}: {e}")
            
        return entries
    
    def compact(self):
        """
        压缩日志 - 归档旧日志并创建新的当前日志
        """
        self._flush()
        
        if not self.current_log.exists():
            return
            
        # 生成归档文件名
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        archive_file = self.archive_dir / f"wal_{timestamp}.log"
        
        # 移动当前日志到归档
        self.current_log.rename(archive_file)
        
        # 创建压缩记录
        compact_entry = LogEntry(
            timestamp=time.time(),
            log_type=LogType.COMPACT.value,
            memory_id="compact",
            data={"archived_to": str(archive_file), "timestamp": timestamp},
            checksum="",
            sequence=self._next_sequence()
        )
        
        # 写入新的当前日志
        with open(self.current_log, "w", encoding="utf-8") as f:
            line = json.dumps(compact_entry.to_dict(), ensure_ascii=False)
            f.write(line + "\n")
    
    def recover(self, apply_callback: Callable[[LogEntry], None]):
        """
        从日志恢复数据
        
        Args:
            apply_callback: 应用到每个日志条目的回调函数
        """
        entries = self.read_all()
        
        for entry in entries:
            try:
                apply_callback(entry)
            except Exception as e:
                print(f"Error applying log entry {entry.sequence}: {e}")
                
    def get_stats(self) -> Dict:
        """获取WAL统计信息"""
        entries = self.read_all()
        
        log_types = {}
        for entry in entries:
            log_types[entry.log_type] = log_types.get(entry.log_type, 0) + 1
            
        return {
            "total_entries": len(entries),
            "current_sequence": self._sequence,
            "buffer_size": len(self._buffer),
            "log_types": log_types,
            "archive_count": len(list(self.archive_dir.glob("*.log")))
        }
    
    def close(self):
        """关闭WAL管理器，确保所有数据写入磁盘"""
        self._flush()
        

# 单例实例
_wal_instance: Optional[WALManager] = None


def get_wal_manager(base_path: str = ".trae/memory") -> WALManager:
    """获取WAL管理器单例"""
    global _wal_instance
    if _wal_instance is None:
        _wal_instance = WALManager(base_path)
    return _wal_instance


def reset_wal_manager():
    """重置WAL管理器（用于测试）"""
    global _wal_instance
    if _wal_instance:
        _wal_instance.close()
    _wal_instance = None
