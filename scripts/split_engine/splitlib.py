# -*- coding: utf-8 -*-
"""detekt 拆分任务队列工具库（batch-01 core:engine）。

沿用 handover §2.28/§2.29 机制发现：
- 原始列锚定切分（对 strip 后行匹配会把函数体内缩进 val 误判为块边界）；
- 表达式体函数按累计括号深度切块；
- 字符串/注释感知的花括号匹配（含 ${} 插值内代码、原始字符串、嵌套块注释）；
- 移动代码与源逐字节 diff 校验（统一去缩进 4 后比较）。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

# ---------------- 词法屏蔽（字符串/注释） ----------------

MODIFIERS = {
    "public", "private", "internal", "protected", "open", "final", "abstract",
    "override", "inline", "suspend", "operator", "tailrec", "external",
    "infix", "lateinit", "const", "expect", "actual", "companion",
}
DECL_KEYWORDS = {"fun", "val", "var", "init", "constructor", "class", "object", "interface"}
ANNOTATION_START = "@"
WORD_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def mask(text: str) -> bytearray:
    """返回与 text 等长的 mask：1=非代码（字符串/注释），0=代码。处理 ${} 插值。"""
    n = len(text)
    m = bytearray(n)
    i = 0
    state = "code"  # code | line_comment | block_comment | str | rawstr
    str_delim = ""
    raw_newlines = 0
    interp_stack = []  # 插值花括号深度栈：(base_depth标记)
    brace_depth_in_interp = 0

    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == "code":
            if c == "/" and nxt == "/":
                m[i] = m[i + 1] = 1
                i += 2
                state = "line_comment"
                continue
            if c == "/" and nxt == "*":
                m[i] = m[i + 1] = 1
                i += 2
                state = "block_comment"
                continue
            if c == "'":
                # 字符字面量（Kotlin 中 ' 仅出现在 char literal，字符串/注释已屏蔽）
                m[i] = 1
                if i + 1 < n:
                    m[i + 1] = 1
                if text[i + 1] == "\\":
                    if i + 2 < n:
                        m[i + 2] = 1
                    if i + 3 < n:
                        m[i + 3] = 1
                    i += 4
                else:
                    i += 3
                continue
            if c == '"':
                # 原始字符串 """
                if nxt == '"' and i + 2 < n and text[i + 2] == '"':
                    m[i] = m[i + 1] = m[i + 2] = 1
                    i += 3
                    state = "rawstr"
                    raw_newlines = 0
                    # 向前吞到闭合 """
                    j = i
                    while j < n:
                        if text.startswith('"""', j):
                            # 闭合（原始字符串内 ${} 插值罕见，暂不处理插值内三引号）
                            for k in range(i, j + 3):
                                m[k] = 1
                            i = j + 3
                            state = "code"
                            break
                        j += 1
                    else:
                        for k in range(i, n):
                            m[k] = 1
                        i = n
                        state = "code"
                    continue
                m[i] = 1
                i += 1
                str_delim = '"'
                state = "str"
                continue
            i += 1
            continue
        if state == "line_comment":
            if c == "\n":
                state = "code"
            else:
                m[i] = 1
            i += 1
            continue
        if state == "block_comment":
            m[i] = 1
            if c == "*" and nxt == "/":
                m[i + 1] = 1
                i += 2
                state = "code"
                continue
            i += 1
            continue
        if state == "str":
            m[i] = 1
            if c == "\\":
                if i + 1 < n:
                    m[i + 1] = 1
                i += 2
                continue
            if c == '"':
                state = "code"
                i += 1
                continue
            if c == "$" and nxt == "{":
                # 进入插值：内部是代码
                m[i] = 1  # $ 保持屏蔽，{ 视为代码
                m[i + 1] = 0
                i += 2
                state = "interp"
                brace_depth_in_interp = 1
                continue
            i += 1
            continue
        if state == "interp":
            if c == "{":
                brace_depth_in_interp += 1
                i += 1
                continue
            if c == "}":
                brace_depth_in_interp -= 1
                if brace_depth_in_interp == 0:
                    # 插值闭合 } 属于插值语法（代码）——不屏蔽，保证括号配平
                    state = "str"
                i += 1
                continue
            if c == '"' and nxt == '"':
                # 插值内的原始字符串（罕见）——简单吞三引号
                j = i + 2
                while j < n and not text.startswith('"""', j):
                    j += 1
                for k in range(i, min(j + 3, n)):
                    m[k] = 1
                i = j + 3
                continue
            if c == '"':
                # 插值内的普通字符串
                m[i] = 1
                i += 1
                while i < n:
                    m[i] = 1
                    if text[i] == "\\":
                        if i + 1 < n:
                            m[i + 1] = 1
                        i += 2
                        continue
                    if text[i] == '"':
                        i += 1
                        break
                    i += 1
                continue
            if c == "/" and nxt == "/":
                i += 2
                while i < n and text[i] != "\n":
                    i += 1
                continue
            if c == "/" and nxt == "*":
                depth = 1
                i += 2
                while i < n and depth:
                    if text.startswith("/*", i):
                        depth += 1
                        i += 2
                    elif text.startswith("*/", i):
                        depth -= 1
                        i += 2
                    else:
                        i += 1
                continue
            i += 1
            continue
    return m


def find_matching_brace(text: str, open_idx: int, m: bytearray) -> int:
    """open_idx 指向未屏蔽的 '{'，返回匹配的 '}' 下标。"""
    assert text[open_idx] == "{" and not m[open_idx]
    depth = 0
    i = open_idx
    n = len(text)
    while i < n:
        if not m[i]:
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    return i
        i += 1
    raise ValueError("unbalanced braces")


def find_class_span(text: str, class_name: str) -> tuple[int, int, int]:
    """返回 (类声明起始, 体开括号, 体闭括号)。构造参数默认值内 lambda 括号安全。"""
    m = mask(text)
    for mo in re.finditer(r"\b(class|interface|object)\s+%s\b" % re.escape(class_name), text):
        if m[mo.start()]:
            continue
        # 向后找第一个括号深度 0 的 '{'
        i = mo.end()
        paren = 0
        n = len(text)
        while i < n:
            if not m[i]:
                if text[i] == "(":
                    paren += 1
                elif text[i] == ")":
                    paren -= 1
                elif text[i] == "{" and paren == 0:
                    close = find_matching_brace(text, i, m)
                    return mo.start(), i, close
            i += 1
    raise ValueError(f"class {class_name} not found")


# ---------------- 类成员切分 ----------------

ANCHOR_WORDS = MODIFIERS | DECL_KEYWORDS | {"data", "sealed", "enum", "abstract", "annotation", "fun", "value"}


@dataclass
class Element:
    start: int  # 含 KDoc/前导注释的段起点
    decl_start: int  # 声明本体（注解/修饰符首行）起点
    end: int
    kind: str  # fun / val / var / init / constructor / class / object / companion / other
    name: str
    text: str = ""
    sig: "FnSig | None" = None


def member_anchors(text: str, open_idx: int, close_idx: int, m: bytearray) -> list[int]:
    """类体内、成员深度上的声明锚点（行首位置）。"""
    anchors = []
    depth = 0
    i = open_idx
    n = close_idx
    while i < n:
        c = text[i]
        if not m[i]:
            if c == "\n":
                # 找下一行第一个非空白字符
                j = i + 1
                while j < n and text[j] in " \t":
                    j += 1
                if j < n and text[j] != "\n":
                    # 该位置的缩进列 == 4（成员深度）才视为锚点候选
                    col = j - (i + 1)
                    wmo = WORD_RE.match(text, j)
                    if col == 4 and wmo and wmo.group(0) in ANCHOR_WORDS:
                        anchors.append(i + 1)
                    elif col == 4 and text[j] == ANNOTATION_START:
                        anchors.append(i + 1)
                i = j
                continue
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
        i += 1
    return anchors


def build_elements(text: str, open_idx: int, close_idx: int, m: bytearray) -> list[Element]:
    anchors = member_anchors(text, open_idx, close_idx, m)
    bounds = anchors + [close_idx]
    raw_segs = []
    for a, b in zip(bounds, bounds[1:]):
        raw_segs.append((a, b))
    # 合并：仅含注解/修饰符行（无声明关键字）的段并入后段
    merged = []
    pending = None
    for a, b in raw_segs:
        seg = text[a:b]
        ca = a
        while ca < b and text[ca] in " \t":
            ca += 1
        first_char = text[ca] if ca < b else ""
        is_pure_prefix = (first_char == ANNOTATION_START) or (
            first_char.isalpha() and not re.search(r"\b(fun|val|var|init|constructor|class|object|interface)\b", seg))
        if is_pure_prefix:
            if pending is None:
                pending = a
            continue
        start = pending if pending is not None else a
        merged.append((start, b))
        pending = None
    if pending is not None:
        merged.append((pending, close_idx))

    elems = []
    prev_end = open_idx
    for a, b in merged:
        ca = a
        while ca < b and text[ca] in " \t":
            ca += 1
        first_char = text[ca] if ca < b else ""
        wmo = WORD_RE.match(text, ca)
        first = wmo.group(0) if wmo else ""
        kind, name = "other", ""
        if first_char == ANNOTATION_START or (first and first in MODIFIERS):
            mo2 = re.search(r"\b(fun|val|var|init|constructor|class|object|interface)\b", text[a:b])
            if mo2:
                kind = mo2.group(1)
        else:
            kind = first if first in DECL_KEYWORDS or first in ("data", "sealed", "enum") else "other"
        # 名字提取
        if kind == "fun":
            mo3 = re.match(r"[^{=]*\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][A-Za-z0-9_.]*\.)?([A-Za-z_][A-Za-z0-9_]*)\s*[\(<]", text[a:b])
            if mo3:
                name = mo3.group(1)
        elif kind in ("val", "var"):
            mo3 = re.match(r"[^:=]*?(?:val|var)\s+(?:<[^>]*>\s*)?([A-Za-z_`][A-Za-z0-9_]*)", text[a:b])
            if mo3:
                name = mo3.group(1)
        elif kind in ("class", "object", "interface"):
            mo3 = re.search(r"\b(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)", text[a:b])
            if mo3:
                name = mo3.group(1)
            if first == "data" or first == "sealed" or first == "enum":
                mo4 = re.search(r"\b(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)", text[a:b])
                kind = "class"
                if mo4:
                    name = mo4.group(1)
        elif kind == "companion":
            name = "companion"
        # KDoc / 前导注释起点（不上跨前一元素终点）
        kstart = leading_comment_start(text, m, a, prev_end)
        elems.append(Element(kstart, a, b, kind, name))
        prev_end = b
    for e in elems:
        e.text = text[e.start:e.end]
    return elems


def leading_comment_start(text: str, m: bytearray, decl_start: int, min_pos: int) -> int:
    """从声明起点向上扫描紧邻的 KDoc/块注释（mask 感知，空行/代码行即停）。"""
    line_start = text.rfind("\n", 0, decl_start) + 1
    if line_start <= min_pos:
        return decl_start
    prev_end = line_start - 1
    if prev_end <= min_pos:
        return decl_start
    prev_start = text.rfind("\n", 0, prev_end) + 1
    prev_line = text[prev_start:prev_end]
    if prev_line.strip() == "":
        return decl_start
    if any(not m[k] for k in range(prev_start, prev_end)):
        # 前一行含未屏蔽代码字符 → 无 KDoc
        return decl_start
    # 前一行是注释行：向上走，直到空行或含代码的行
    top = prev_start
    j = prev_start
    while j > min_pos:
        ls = text.rfind("\n", 0, j - 1) + 1 if j > 0 else 0
        if ls < min_pos:
            break
        seg = text[ls:j]
        if seg.strip() == "":
            break
        if any(not m[k] for k in range(ls, j)):
            break
        top = ls
        j = ls
    return top


# ---------------- 函数签名解析 ----------------

MOD_ORDER = ["public", "private", "internal", "protected", "open", "final", "abstract",
             "override", "inline", "suspend", "operator", "tailrec", "external", "infix"]


@dataclass
class FnSig:
    modifiers: list[str] = field(default_factory=list)
    type_params: str = ""  # 含尖括号原文，如 "<T : Any>"
    receiver: str = ""  # 扩展接收者（源无）
    has_receiver: bool = False  # 成员扩展（fun Type.name）
    name: str = ""
    params: str = ""  # 括号内原文（不含括号）
    ret: str = ""  # 含 ': ' 或为空
    is_expr_body: bool = False
    head_end: int = 0  # 签名结束（'{' 或 '=' 之前）在段内下标
    body_text: str = ""  # 统一去缩进 4 后的完整声明文本


def parse_fn_signature(seg_text: str) -> FnSig | None:
    """解析段内 fun 声明签名。seg_text 含 KDoc；声明本体从某行开始。"""
    # 去掉前导 KDoc/注释找声明行
    lines = seg_text.split("\n")
    offset = 0
    for idx, ln in enumerate(lines):
        s = ln.strip()
        if s.startswith("/**") or s.startswith("/*") or s.startswith("*") or s.startswith("*/") or s == "":
            offset += len(ln) + 1
            continue
        break
    body = seg_text[offset:]
    if not re.match(r"\s*(@|[A-Za-z])", body):
        return None
    # fun 关键字位置
    fmo = re.search(r"\bfun\b", body)
    if not fmo:
        return None
    sig = FnSig()
    # 修饰符：fun 之前的词序列（跨行）
    pre = body[: fmo.start()]
    toks = re.findall(r"[A-Za-z_][A-Za-z0-9_]*", pre)
    sig.modifiers = [t for t in toks if t in MOD_ORDER]
    after = body[fmo.end():]
    # 类型参数
    if after.lstrip().startswith("<"):
        # 括号深度匹配 <>
        i = after.index("<")
        depth = 0
        j = i
        while j < len(after):
            if after[j] == "<":
                depth += 1
            elif after[j] == ">":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        sig.type_params = after[i: j + 1]
        after = after[j + 1:]
    # 名字（可带接收者 A.B / 泛型接收者 List<T>.B）——首个 '(' 前含 '.' 即有接收者
    pidx2 = after.find("(")
    hpart = after[:pidx2] if pidx2 >= 0 else after
    if "." in hpart:
        sig.has_receiver = True
        after = after[hpart.rindex(".", 0, len(hpart)) + 1:]
    nmo = re.match(r"\s*(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*))\s*", after)
    if not nmo:
        return None
    sig.name = nmo.group(1) or nmo.group(2)
    after = after[nmo.end():]
    # 接收者检测：名字后紧跟 '.'（如 fun Foo.bar）
    # （成员函数不会出现；此处工具用于成员段，通常无接收者）
    # 参数括号
    pmo = re.match(r"\s*\(", after)
    if not pmo:
        return None
    pstart = after.index("(")
    depth = 0
    j = pstart
    while j < len(after):
        if after[j] == "(":
            depth += 1
        elif after[j] == ")":
            depth -= 1
            if depth == 0:
                break
        j += 1
    sig.params = after[pstart + 1: j]
    after = after[j + 1:]
    # 返回类型 / 表达式体
    bmo = re.match(r"\s*(:[^=]*?)?\s*(\{|=)", after)
    if not bmo:
        # 多行返回类型（含泛型嵌套）——匹配到 { 或 = 为止
        j = 0
        while j < len(after) and after[j] not in "{=":
            j += 1
        ret_part = after[:j]
        bmo = re.match(r"(\s*(:[^=]*?)?)\s*(\{|=)", ret_part + after[j]) if False else None
        if j < len(after):
            ret_str = after[:j].strip()
            if ret_str.startswith(":"):
                sig.ret = ret_str
            sig.is_expr_body = after[j] == "="
            sig.head_end = offset + fmo.end() - len("fun") + (j)
            return sig
        return None
    ret_part = bmo.group(1)
    if ret_part:
        sig.ret = ret_part.strip()
    sig.is_expr_body = bmo.group(2) == "="
    sig.head_end = len(body[: fmo.end()]) + (bmo.start())
    return sig


def dedent4(seg_text: str) -> str:
    """统一去缩进 4：每个非空行去掉恰好前导 4 空格；空行保留。"""
    out = []
    for ln in seg_text.split("\n"):
        if ln.strip() == "":
            out.append("")
        elif ln.startswith("    "):
            out.append(ln[4:])
        else:
            raise ValueError(f"行缩进不足 4，无法去缩进: {ln!r}")
    return "\n".join(out)


def transform_to_extension(seg_text: str, class_name: str, visibility: str,
                           keep_private_ok: bool = False) -> str:
    """把成员函数段文本转成顶层扩展函数文本（去缩进 4 + 签名改写）。

    visibility: 'keep'（保留原修饰符）/ 'internal' / 'private'
    成员扩展函数（fun Receiver.name）拒绝移动——函数体隐式引用类属性时
    移出即失去外层接收者，双重接收者非法。
    """
    ded = dedent4(seg_text)
    lines = ded.split("\n")
    # 定位声明行（跳过 KDoc/注释/空行）
    di = 0
    for i, ln in enumerate(lines):
        s = ln.strip()
        if s == "" or s.startswith("/*") or s.startswith("*") or s.startswith("*/"):
            continue
        di = i
        break
    # 声明行序列：从 di 起，直到含 'fun ' 的行
    j = di
    while j < len(lines) and "fun " not in lines[j]:
        j += 1
    if j >= len(lines):
        raise ValueError("未找到 fun 声明行")
    head = "\n".join(lines[di:j])
    decl_line = lines[j]
    # 解析签名以取名字
    sig = parse_fn_signature(seg_text)
    if sig is None:
        raise ValueError("签名解析失败")
    name = sig.name
    mo = re.search(r"\bfun\b", decl_line)
    after_fun = decl_line[mo.end():].lstrip()
    # 接收者检测：'fun Type.name(' / 'fun <T> Type?.name(' —— 第一个 '(' 前含 '.' 即有接收者
    pidx = after_fun.find("(")
    head_part = after_fun[:pidx] if pidx >= 0 else after_fun
    rmo = "." in head_part
    if rmo:
        # 成员扩展：接收者已存在，只改可见性，不加类前缀
        # （调用方须保证函数体不引用类属性/类方法——split_run 已校验）
        kept_mods = [m0 for m0 in sig.modifiers if m0 not in ("public", "private", "internal", "protected", "override")]
        mod_prefix = f"{visibility} {' '.join(kept_mods)} " if kept_mods else f"{visibility} "
        if visibility == "keep":
            mod_prefix = ""
        new_sig_line = f"{mod_prefix}fun {after_fun}"
        new_head_lines = []
        for ln in head.split("\n"):
            s = ln.strip()
            if s.startswith("@"):
                new_head_lines.append(ln)
        rebuilt = "\n".join(new_head_lines + [new_sig_line] + lines[j + 1:])
        return rebuilt
    # after_fun 形如 ' name(...' 或 ' <T> name(...'
    kept_mods = [m0 for m0 in sig.modifiers if m0 not in ("public", "private", "internal", "protected", "override")]
    mod_prefix = f"{visibility} {' '.join(kept_mods)} " if kept_mods else f"{visibility} "
    if visibility == "keep":
        mod_prefix = ""
    new_sig_line = f"{mod_prefix}fun {class_name}.{after_fun}"
    # head 中注解行保留
    new_head_lines = []
    for ln in head.split("\n"):
        s = ln.strip()
        if s.startswith("@"):
            new_head_lines.append(ln)
    rebuilt = "\n".join(new_head_lines + [new_sig_line] + lines[j + 1:])
    return rebuilt


def fn_name_of_segment(seg_text: str) -> str | None:
    sig = parse_fn_signature(seg_text)
    return sig.name if sig else None


def class_state_names(text: str, decl_start: int, open_idx: int,
                      body_props: set[str]) -> set[str]:
    """类状态名全集 = 类体属性 + 主构造器注入属性（private val x: T）。"""
    names = set(body_props)
    ctor = text[decl_start:open_idx]
    for mo in re.finditer(r"\b(?:private|internal|protected|public)?\s*val\s+`?([A-Za-z_][A-Za-z0-9_]*)`?\s*:", ctor):
        names.add(mo.group(1))
    return names


def ext_references_class_state(elem_text: str, class_props: set[str],
                               member_names: set[str], class_name: str) -> bool:
    """成员扩展函数体是否引用类状态（属性/其他成员/外层 this 标签）。

    保守判定的假阳性（把干净函数误判为引用）只是让它留守，不破坏行为。
    """
    # 函数体 = 签名后部分（首个 { 之后）
    bmo = re.search(r"\{", elem_text)
    body = elem_text[bmo.start():] if bmo else elem_text
    # 外层接收者标签引用（this@Class）
    if re.search(r"this@%s\b" % re.escape(class_name), body):
        return True
    for w in class_props:
        if re.search(r"\b%s\b" % re.escape(w), body):
            return True
    for w in member_names:
        if re.search(r"\b%s\s*\(" % re.escape(w), body):
            return True
    return False
