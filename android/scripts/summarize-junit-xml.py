#!/usr/bin/env python3
"""汇总 Android 各模块 JUnit XML 结果（B15 门 2 实证用）。

用法: python summarize-junit-xml.py [--json]

对每个模块的 test-results/testReleaseUnitTest*/ 目录下全部 *.xml 求和，
打印 tests/failures/errors/skipped 与 XML 文件时间戳范围；并单独统计
Diff* 测试类 与 SoftwareCanvasBackend* 测试类 的用例数/skip 数。

注意：必须在完整 run 之后执行，过滤 run（--tests）会留下旧 XML 造成误判。
本脚本会打印每个模块的 XML mtime 范围，便于人工确认「同一轮」。
"""
import argparse
import json
import os
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ANDROID = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MODULES = [
    ("core:engine", "core/engine"),
    ("core:domain", "core/domain"),
    ("core:data", "core/data"),
    ("core:ui", "core/ui"),
    ("feature:game", "feature/game"),
    ("app", "app"),
]


def iter_xml(module_dir):
    """只收 testReleaseUnitTest* 目录下的 XML。

    B15 门 2 的判据是 testReleaseUnitTest 的完整 run；testDebugUnitTest
    等其它 task 的历史 XML 不属于本次门（且会让 totals 虚高），必须排除。
    """
    root = os.path.join(ANDROID, module_dir, "build", "test-results")
    if not os.path.isdir(root):
        return []
    out = []
    for entry in sorted(os.listdir(root)):
        if not entry.startswith("testReleaseUnitTest"):
            continue
        sub = os.path.join(root, entry)
        if not os.path.isdir(sub):
            continue
        for name in sorted(os.listdir(sub)):
            if name.startswith("TEST-") and name.endswith(".xml"):
                out.append(os.path.join(sub, name))
    return out


def ts(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")


def scan(files, prefix_filter=None):
    tests = failures = errors = skipped = 0
    matched_classes = set()
    mtimes = []
    for path in files:
        try:
            mtimes.append(os.path.getmtime(path))
        except OSError:
            pass
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        # Gradle 的 testsuite/@name 与 testcase/@classname 均为 FQCN。
        # 前缀过滤要求「简单类名前缀」匹配：取 FQCN 的最后一段再比对，
        # 避免 com.xianxia.sect.core.nativebridge.Diff* 之外的类误入，
        # 也避免 "Diff" 作为包名段时误命中。
        simple = (root.get("name") or "").rsplit(".", 1)[-1]
        if prefix_filter is not None and not simple.startswith(prefix_filter):
            continue
        if prefix_filter is not None:
            matched_classes.add(root.get("name") or simple)
        for suite in ([root] if root.tag == "testsuite" else root.iter("testsuite")):
            tests += int(suite.get("tests") or 0)
            failures += int(suite.get("failures") or 0)
            errors += int(suite.get("errors") or 0)
            skipped += int(suite.get("skipped") or 0)
    return {
        "tests": tests,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "classes": sorted(matched_classes),
        "n_class": len(matched_classes),
        "mtime_min": ts(min(mtimes)) if mtimes else None,
        "mtime_max": ts(max(mtimes)) if mtimes else None,
        "n_xml": len(files),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json", action="store_true")
    ap.add_argument(
        "--max-age-min",
        type=float,
        default=90.0,
        help="最老 XML 允许的年龄（分钟）；超龄则在末尾打印 STALE 警告。",
    )
    args = ap.parse_args()

    report = {"modules": {}, "totals": {}, "diff": {}, "softcanvas": {}}
    agg = dict(tests=0, failures=0, errors=0, skipped=0)

    per_module_files = {}
    for label, rel in MODULES:
        files = iter_xml(rel)
        per_module_files[label] = files
        res = scan(files)
        report["modules"][label] = res
        for k in agg:
            agg[k] += res[k]

    report["totals"] = dict(agg)

    all_files = [p for files in per_module_files.values() for p in files]
    mtimes = []
    for path in all_files:
        try:
            mtimes.append(os.path.getmtime(path))
        except OSError:
            pass
    newest = max(mtimes) if mtimes else 0.0
    report["totals"]["mtime_min"] = ts(min(mtimes)) if mtimes else None
    report["totals"]["mtime_max"] = ts(newest) if mtimes else None

    # 陈旧判定：以「最新 XML」为参照点，凡早于它超过 --max-age-min 的都算陈旧。
    # 完整 run 中同模块 XML 应在数分钟内同批产出，一个月的差值必是残留。
    stale_cutoff = newest - args.max_age_min * 60
    stale = []
    for label, files in per_module_files.items():
        for path in files:
            try:
                mt = os.path.getmtime(path)
            except OSError:
                continue
            if mt < stale_cutoff:
                stale.append((label, os.path.basename(path), ts(mt)))
    report["stale"] = {
        "count": len(stale),
        "max_age_min": args.max_age_min,
        "newest_reference": ts(newest) if newest else None,
        "examples": stale[:10],
    }

    report["diff"] = scan(all_files, prefix_filter="Diff")
    report["softcanvas"] = scan(all_files, prefix_filter="SoftwareCanvasBackend")

    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return

    print("== 六模块 JUnit（testReleaseUnitTest）汇总 ==")
    for label, _rel in MODULES:
        r = report["modules"][label]
        print(
            f"  {label:<15} tests={r['tests']:<5} fail={r['failures']:<3} "
            f"err={r['errors']:<3} skip={r['skipped']:<3} xml={r['n_xml']:<3} "
            f"[{r['mtime_min']} .. {r['mtime_max']}]"
        )
    t = report["totals"]
    print(
        f"  {'TOTAL':<15} tests={t['tests']:<5} fail={t['failures']:<3} "
        f"err={t['errors']:<3} skip={t['skipped']:<3} "
        f"[{t['mtime_min']} .. {t['mtime_max']}]"
    )

    if stale:
        print(
            f"\n!! STALE: {len(stale)} 个 XML 早于最新 XML 超过 "
            f"{args.max_age_min:g} 分钟（参照 {ts(newest)}）——"
            f"说明存在历史残留，totals 不含本次完整 run，勿用于验收。"
        )
        for label, name, m in stale[:10]:
            print(f"     {label}: {name} @ {m}")
    else:
        print(
            f"\n== 新鲜度 ==\n  OK: 全部 XML 在 {ts(newest)} 前 {args.max_age_min:g} 分钟内产出，"
            f"判定为同一轮完整 run。"
        )

    d = report["diff"]
    print(f"\n== Diff* 测试类 ==\n  classes={d['n_class']} tests={d['tests']} "
          f"fail={d['failures']} err={d['errors']} skip={d['skipped']}")
    s = report["softcanvas"]
    print(f"\n== SoftwareCanvasBackend* 测试类 ==\n  classes={s['n_class']} tests={s['tests']} "
          f"fail={s['failures']} err={s['errors']} skip={s['skipped']}")
    for c in s["classes"]:
        print(f"    - {c}")


if __name__ == "__main__":
    main()
