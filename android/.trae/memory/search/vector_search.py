"""
Vector Search Engine
向量搜索引擎 - 基于HNSW的语义相似度搜索
"""

import json
import math
import random
import hashlib
from typing import List, Dict, Tuple, Optional
from pathlib import Path
from dataclasses import dataclass, asdict
import heapq


@dataclass
class VectorRecord:
    """向量记录"""
    id: str
    vector: List[float]
    metadata: Dict
    
    def to_dict(self) -> Dict:
        return {
            "id": self.id,
            "vector": self.vector,
            "metadata": self.metadata
        }
    
    @classmethod
    def from_dict(cls, data: Dict) -> 'VectorRecord':
        return cls(
            id=data["id"],
            vector=data["vector"],
            metadata=data.get("metadata", {})
        )


class SimpleEmbedding:
    """简单嵌入生成器（基于词频的简化版）"""
    
    def __init__(self, dim: int = 128):
        self.dim = dim
        self.vocab = {}
        
    def _tokenize(self, text: str) -> List[str]:
        """简单分词"""
        # 移除标点，转换为小写
        text = ''.join(c.lower() if c.isalnum() or c.isspace() else ' ' for c in text)
        return text.split()
    
    def _hash_token(self, token: str) -> int:
        """哈希token到固定维度"""
        return int(hashlib.md5(token.encode()).hexdigest(), 16)
    
    def encode(self, text: str) -> List[float]:
        """
        将文本编码为向量
        
        Args:
            text: 输入文本
            
        Returns:
            归一化后的向量
        """
        tokens = self._tokenize(text)
        if not tokens:
            return [0.0] * self.dim
            
        # 统计词频
        token_counts = {}
        for token in tokens:
            token_counts[token] = token_counts.get(token, 0) + 1
            
        # 生成向量
        vector = [0.0] * self.dim
        for token, count in token_counts.items():
            hash_val = self._hash_token(token)
            for i in range(self.dim):
                # 使用哈希值决定该token对各维度的贡献
                if (hash_val >> i) & 1:
                    vector[i] += count
                    
        # 归一化
        norm = math.sqrt(sum(x * x for x in vector))
        if norm > 0:
            vector = [x / norm for x in vector]
            
        return vector


class HNSWIndex:
    """
    HNSW (Hierarchical Navigable Small World) 索引
    用于高效的近似最近邻搜索
    """
    
    def __init__(
        self,
        dim: int = 128,
        m: int = 16,           # 每个节点的最大连接数
        ef_construction: int = 200,  # 构建时的搜索范围
        ef_search: int = 50,   # 搜索时的搜索范围
        ml: int = 16           # 最大层数
    ):
        self.dim = dim
        self.m = m
        self.m_max = m * 2
        self.ef_construction = ef_construction
        self.ef_search = ef_search
        self.ml = ml
        
        self.nodes: Dict[str, VectorRecord] = {}
        self.graphs: List[Dict[str, List[str]]] = []  # 每层图结构
        self.entry_point: Optional[str] = None
        self.level_multiplier = 1.0 / math.log(m)
        
    def _random_level(self) -> int:
        """随机生成层级"""
        level = 0
        while random.random() < math.exp(-1.0 / self.level_multiplier) and level < self.ml:
            level += 1
        return level
    
    def _cosine_similarity(self, v1: List[float], v2: List[float]) -> float:
        """计算余弦相似度"""
        dot = sum(a * b for a, b in zip(v1, v2))
        norm1 = math.sqrt(sum(a * a for a in v1))
        norm2 = math.sqrt(sum(b * b for b in v2))
        if norm1 == 0 or norm2 == 0:
            return 0.0
        return dot / (norm1 * norm2)
    
    def _search_layer(
        self,
        query: List[float],
        entry: str,
        ef: int,
        layer: int
    ) -> List[Tuple[float, str]]:
        """
        在单层中搜索最近邻
        
        Args:
            query: 查询向量
            entry: 入口节点
            ef: 搜索范围
            layer: 层索引
            
        Returns:
            最近邻列表 [(相似度, 节点ID), ...]
        """
        visited = {entry}
        candidates = [(-self._cosine_similarity(query, self.nodes[entry].vector), entry)]
        results = [(-self._cosine_similarity(query, self.nodes[entry].vector), entry)]
        
        heapq.heapify(candidates)
        heapq.heapify(results)
        
        while candidates:
            dist, current = heapq.heappop(candidates)
            
            # 获取当前最差结果
            worst_result = results[0][0] if len(results) >= ef else float('inf')
            
            if -dist > -worst_result and len(results) >= ef:
                break
                
            # 遍历邻居
            neighbors = self.graphs[layer].get(current, [])
            for neighbor in neighbors:
                if neighbor not in visited:
                    visited.add(neighbor)
                    neighbor_dist = -self._cosine_similarity(query, self.nodes[neighbor].vector)
                    
                    worst_result = results[0][0] if len(results) >= ef else float('inf')
                    
                    if neighbor_dist < worst_result or len(results) < ef:
                        heapq.heappush(candidates, (neighbor_dist, neighbor))
                        heapq.heappush(results, (neighbor_dist, neighbor))
                        
                        if len(results) > ef:
                            heapq.heappop(results)
                            
        return [(-d, n) for d, n in results]
    
    def _select_neighbors(
        self,
        query: List[float],
        candidates: List[Tuple[float, str]],
        m: int
    ) -> List[str]:
        """
        选择邻居节点（使用简单启发式）
        
        Args:
            query: 查询向量
            candidates: 候选节点列表
            m: 最大连接数
            
        Returns:
            选中的邻居ID列表
        """
        # 按相似度排序，选择前m个
        candidates.sort(reverse=True)
        return [node_id for _, node_id in candidates[:m]]
    
    def add(self, record: VectorRecord):
        """
        添加向量记录
        
        Args:
            record: 向量记录
        """
        if record.id in self.nodes:
            # 已存在则更新
            self.remove(record.id)
            
        self.nodes[record.id] = record
        
        # 如果是第一个节点
        if self.entry_point is None:
            self.entry_point = record.id
            # 初始化各层
            level = self._random_level()
            for _ in range(level + 1):
                self.graphs.append({record.id: []})
            return
            
        # 随机决定节点层级
        level = self._random_level()
        
        # 确保有足够的层
        while len(self.graphs) <= level:
            self.graphs.append({})
            
        # 从顶层开始搜索
        current_entry = self.entry_point
        for layer in range(len(self.graphs) - 1, level, -1):
            neighbors = self._search_layer(record.vector, current_entry, 1, layer)
            if neighbors:
                current_entry = neighbors[0][1]
                
        # 在目标层及以下的每层插入
        for layer in range(min(level, len(self.graphs) - 1), -1, -1):
            neighbors = self._search_layer(
                record.vector,
                current_entry,
                self.ef_construction,
                layer
            )
            
            selected = self._select_neighbors(record.vector, neighbors, self.m)
            self.graphs[layer][record.id] = selected
            
            # 双向连接
            for neighbor_id in selected:
                if neighbor_id not in self.graphs[layer]:
                    self.graphs[layer][neighbor_id] = []
                self.graphs[layer][neighbor_id].append(record.id)
                
                # 如果邻居连接数过多，进行剪枝
                if len(self.graphs[layer][neighbor_id]) > self.m_max:
                    neighbor_record = self.nodes[neighbor_id]
                    neighbor_neighbors = [
                        (self._cosine_similarity(neighbor_record.vector, self.nodes[n].vector), n)
                        for n in self.graphs[layer][neighbor_id]
                    ]
                    neighbor_neighbors.sort(reverse=True)
                    self.graphs[layer][neighbor_id] = [n for _, n in neighbor_neighbors[:self.m_max]]
                    
            if neighbors:
                current_entry = neighbors[0][1]
                
        # 更新入口点
        if level >= len(self.graphs) - 1:
            self.entry_point = record.id
    
    def remove(self, node_id: str):
        """
        删除向量记录
        
        Args:
            node_id: 节点ID
        """
        if node_id not in self.nodes:
            return
            
        # 从各层图中移除
        for layer in self.graphs:
            if node_id in layer:
                # 从其他节点的邻居列表中移除
                for neighbor_id in layer[node_id]:
                    if neighbor_id in layer and node_id in layer[neighbor_id]:
                        layer[neighbor_id].remove(node_id)
                del layer[node_id]
                
        # 从节点字典中移除
        del self.nodes[node_id]
        
        # 更新入口点
        if self.entry_point == node_id:
            if self.nodes:
                self.entry_point = next(iter(self.nodes))
            else:
                self.entry_point = None
    
    def search(
        self,
        query: List[float],
        k: int = 10,
        ef: Optional[int] = None
    ) -> List[Tuple[float, VectorRecord]]:
        """
        搜索最近邻
        
        Args:
            query: 查询向量
            k: 返回结果数量
            ef: 搜索范围（默认使用self.ef_search）
            
        Returns:
            [(相似度, 记录), ...]
        """
        if not self.nodes or self.entry_point is None:
            return []
            
        ef = ef or self.ef_search
        
        # 从顶层开始搜索
        current_entry = self.entry_point
        for layer in range(len(self.graphs) - 1, 0, -1):
            neighbors = self._search_layer(query, current_entry, 1, layer)
            if neighbors:
                current_entry = neighbors[0][1]
                
        # 在最底层搜索
        results = self._search_layer(query, current_entry, max(ef, k), 0)
        
        # 返回前k个结果
        return [(sim, self.nodes[node_id]) for sim, node_id in results[:k]]
    
    def save(self, filepath: Path):
        """保存索引到文件"""
        data = {
            "dim": self.dim,
            "m": self.m,
            "ef_construction": self.ef_construction,
            "ef_search": self.ef_search,
            "ml": self.ml,
            "entry_point": self.entry_point,
            "nodes": {id: record.to_dict() for id, record in self.nodes.items()},
            "graphs": self.graphs
        }
        filepath.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        
    def load(self, filepath: Path):
        """从文件加载索引"""
        if not filepath.exists():
            return
            
        data = json.loads(filepath.read_text(encoding="utf-8"))
        
        self.dim = data["dim"]
        self.m = data["m"]
        self.ef_construction = data["ef_construction"]
        self.ef_search = data["ef_search"]
        self.ml = data["ml"]
        self.entry_point = data["entry_point"]
        
        self.nodes = {
            id: VectorRecord.from_dict(record_data)
            for id, record_data in data["nodes"].items()
        }
        self.graphs = data["graphs"]


class VectorSearchEngine:
    """向量搜索引擎主类"""
    
    def __init__(self, base_path: str = ".trae/memory", dim: int = 128):
        self.base_path = Path(base_path)
        self.index_path = self.base_path / "vectors" / "index.json"
        self.metadata_path = self.base_path / "vectors" / "metadata.json"
        
        self.dim = dim
        self.index = HNSWIndex(dim=dim)
        self.embedder = SimpleEmbedding(dim=dim)
        self.metadata: Dict[str, Dict] = {}
        
        self._ensure_directories()
        self._load()
        
    def _ensure_directories(self):
        """确保目录存在"""
        (self.base_path / "vectors").mkdir(parents=True, exist_ok=True)
        
    def _load(self):
        """加载索引和元数据"""
        if self.index_path.exists():
            self.index.load(self.index_path)
            
        if self.metadata_path.exists():
            self.metadata = json.loads(self.metadata_path.read_text(encoding="utf-8"))
            
    def _save(self):
        """保存索引和元数据"""
        self.index.save(self.index_path)
        self.metadata_path.write_text(json.dumps(self.metadata, ensure_ascii=False), encoding="utf-8")
        
    def add(self, memory_id: str, text: str, metadata: Optional[Dict] = None):
        """
        添加记忆到索引
        
        Args:
            memory_id: 记忆ID
            text: 记忆文本内容
            metadata: 附加元数据
        """
        vector = self.embedder.encode(text)
        
        record = VectorRecord(
            id=memory_id,
            vector=vector,
            metadata=metadata or {}
        )
        
        self.index.add(record)
        self.metadata[memory_id] = {
            "text": text,
            **(metadata or {})
        }
        
        self._save()
        
    def remove(self, memory_id: str):
        """
        从索引中删除记忆
        
        Args:
            memory_id: 记忆ID
        """
        self.index.remove(memory_id)
        if memory_id in self.metadata:
            del self.metadata[memory_id]
        self._save()
        
    def search(
        self,
        query: str,
        k: int = 10,
        threshold: float = 0.0
    ) -> List[Dict]:
        """
        搜索相似记忆
        
        Args:
            query: 查询文本
            k: 返回结果数量
            threshold: 相似度阈值
            
        Returns:
            搜索结果列表
        """
        query_vector = self.embedder.encode(query)
        results = self.index.search(query_vector, k=k)
        
        return [
            {
                "id": record.id,
                "similarity": sim,
                "text": self.metadata.get(record.id, {}).get("text", ""),
                "metadata": {k: v for k, v in self.metadata.get(record.id, {}).items() if k != "text"}
            }
            for sim, record in results
            if sim >= threshold
        ]
        
    def get_stats(self) -> Dict:
        """获取统计信息"""
        return {
            "total_vectors": len(self.index.nodes),
            "dimensions": self.dim,
            "index_levels": len(self.index.graphs)
        }
        
    def rebuild(self):
        """重建索引"""
        # 保存所有数据
        all_data = [
            (mid, self.metadata[mid].get("text", ""), self.metadata[mid])
            for mid in self.metadata
        ]
        
        # 重置索引
        self.index = HNSWIndex(dim=self.dim)
        
        # 重新添加所有数据
        for memory_id, text, metadata in all_data:
            vector = self.embedder.encode(text)
            record = VectorRecord(
                id=memory_id,
                vector=vector,
                metadata={k: v for k, v in metadata.items() if k != "text"}
            )
            self.index.add(record)
            
        self._save()


# 单例实例
_search_instance: Optional[VectorSearchEngine] = None


def get_search_engine(base_path: str = ".trae/memory", dim: int = 128) -> VectorSearchEngine:
    """获取搜索引擎单例"""
    global _search_instance
    if _search_instance is None:
        _search_instance = VectorSearchEngine(base_path, dim)
    return _search_instance


def reset_search_engine():
    """重置搜索引擎（用于测试）"""
    global _search_instance
    _search_instance = None
