"""
Git Notes Integration
Git-Notes集成 - 将记忆同步到git notes
"""

import json
import subprocess
import zlib
from typing import List, Dict, Optional
from pathlib import Path
from datetime import datetime


class GitNotesManager:
    """Git Notes管理器"""
    
    NOTES_REF = "refs/notes/memory"
    
    def __init__(self, repo_path: str = "."):
        self.repo_path = Path(repo_path).resolve()
        self._check_git_repo()
        
    def _check_git_repo(self):
        """检查是否为git仓库"""
        git_dir = self.repo_path / ".git"
        if not git_dir.exists():
            raise ValueError(f"Not a git repository: {self.repo_path}")
            
    def _run_git(self, args: List[str], check: bool = True) -> subprocess.CompletedProcess:
        """
        运行git命令
        
        Args:
            args: git命令参数
            check: 是否检查返回值
            
        Returns:
            命令执行结果
        """
        cmd = ["git", "-C", str(self.repo_path)] + args
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            encoding="utf-8"
        )
        
        if check and result.returncode != 0:
            raise RuntimeError(f"Git command failed: {' '.join(cmd)}\n{result.stderr}")
            
        return result
        
    def _get_head_commit(self) -> Optional[str]:
        """获取当前HEAD提交哈希"""
        try:
            result = self._run_git(["rev-parse", "HEAD"], check=False)
            if result.returncode == 0:
                return result.stdout.strip()
        except:
            pass
        return None
        
    def _notes_exists(self) -> bool:
        """检查notes是否存在"""
        try:
            result = self._run_git(
                ["notes", "--ref", self.NOTES_REF, "list"],
                check=False
            )
            return result.returncode == 0 and result.stdout.strip()
        except:
            return False
            
    def sync_to_git(self, memories: List[Dict]) -> str:
        """
        同步记忆到git notes
        
        Args:
            memories: 记忆列表
            
        Returns:
            同步的commit hash
        """
        head = self._get_head_commit()
        if not head:
            raise RuntimeError("No commits in repository")
            
        # 准备数据
        data = {
            "version": "1.0",
            "sync_time": datetime.now().isoformat(),
            "commit": head,
            "memory_count": len(memories),
            "memories": memories
        }
        
        # 压缩数据
        json_str = json.dumps(data, ensure_ascii=False)
        compressed = zlib.compress(json_str.encode("utf-8"))
        
        # 写入notes
        self._run_git([
            "notes", "--ref", self.NOTES_REF,
            "add", "-f", "-m", json_str,
            head
        ])
        
        return head
        
    def sync_from_git(self) -> Optional[List[Dict]]:
        """
        从git notes恢复记忆
        
        Returns:
            记忆列表或None
        """
        head = self._get_head_commit()
        if not head:
            return None
            
        try:
            result = self._run_git([
                "notes", "--ref", self.NOTES_REF,
                "show", head
            ], check=False)
            
            if result.returncode != 0:
                return None
                
            content = result.stdout.strip()
            
            # 尝试解析JSON
            try:
                data = json.loads(content)
                if isinstance(data, dict) and "memories" in data:
                    return data["memories"]
            except json.JSONDecodeError:
                # 可能是压缩数据，尝试解压
                try:
                    compressed = bytes.fromhex(content)
                    decompressed = zlib.decompress(compressed)
                    data = json.loads(decompressed.decode("utf-8"))
                    return data.get("memories", [])
                except:
                    pass
                    
        except Exception as e:
            print(f"Error reading git notes: {e}")
            
        return None
        
    def get_history(self) -> List[Dict]:
        """
        获取记忆同步历史
        
        Returns:
            历史记录列表
        """
        history = []
        
        try:
            # 获取所有notes
            result = self._run_git(
                ["notes", "--ref", self.NOTES_REF, "list"],
                check=False
            )
            
            if result.returncode != 0:
                return history
                
            for line in result.stdout.strip().split("\n"):
                if not line:
                    continue
                    
                parts = line.split()
                if len(parts) >= 2:
                    note_hash = parts[0]
                    commit_hash = parts[1]
                    
                    try:
                        # 获取note内容
                        content_result = self._run_git(
                            ["notes", "--ref", self.NOTES_REF, "show", commit_hash],
                            check=False
                        )
                        
                        if content_result.returncode == 0:
                            content = content_result.stdout.strip()
                            try:
                                data = json.loads(content)
                                history.append({
                                    "commit": commit_hash,
                                    "note_hash": note_hash,
                                    "sync_time": data.get("sync_time"),
                                    "memory_count": data.get("memory_count", 0)
                                })
                            except:
                                history.append({
                                    "commit": commit_hash,
                                    "note_hash": note_hash
                                })
                    except:
                        pass
                        
        except Exception as e:
            print(f"Error getting history: {e}")
            
        return history
        
    def remove_notes(self):
        """删除所有记忆notes"""
        try:
            self._run_git(
                ["notes", "--ref", self.NOTES_REF, "remove", "--ignore-missing", "HEAD"],
                check=False
            )
        except:
            pass
            
    def push_notes(self, remote: str = "origin"):
        """
        推送notes到远程
        
        Args:
            remote: 远程名称
        """
        self._run_git([
            "push", remote,
            f"{self.NOTES_REF}:{self.NOTES_REF}"
        ])
        
    def fetch_notes(self, remote: str = "origin"):
        """
        从远程拉取notes
        
        Args:
            remote: 远程名称
        """
        self._run_git([
            "fetch", remote,
            f"refs/notes/*:refs/notes/*"
        ])
        
    def merge_notes(self, remote: str = "origin"):
        """
        合并远程notes
        
        Args:
            remote: 远程名称
        """
        try:
            self._run_git([
                "notes", "--ref", self.NOTES_REF,
                "merge", "-v", f"{remote}/{self.NOTES_REF}"
            ])
        except RuntimeError as e:
            # 合并冲突，使用本地版本
            print(f"Merge conflict, using local version: {e}")
            self._run_git([
                "notes", "--ref", self.NOTES_REF,
                "merge", "--strategy=ours", f"{remote}/{self.NOTES_REF}"
            ])


def init_git_notes(repo_path: str = ".") -> GitNotesManager:
    """
    初始化Git Notes管理器
    
    Args:
        repo_path: 仓库路径
        
    Returns:
        GitNotesManager实例
    """
    return GitNotesManager(repo_path)


# 便捷函数
def sync_memories_to_git(memories: List[Dict], repo_path: str = ".") -> str:
    """
    便捷函数：同步记忆到git
    
    Args:
        memories: 记忆列表
        repo_path: 仓库路径
        
    Returns:
        commit hash
    """
    manager = GitNotesManager(repo_path)
    return manager.sync_to_git(memories)


def load_memories_from_git(repo_path: str = ".") -> Optional[List[Dict]]:
    """
    便捷函数：从git加载记忆
    
    Args:
        repo_path: 仓库路径
        
    Returns:
        记忆列表或None
    """
    manager = GitNotesManager(repo_path)
    return manager.sync_from_git()
