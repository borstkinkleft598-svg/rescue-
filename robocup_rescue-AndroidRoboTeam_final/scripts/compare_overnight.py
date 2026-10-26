#!/usr/bin/env python3
"""Compare accepted complete map scores, never partial/OOM scores. Stdlib only."""
import argparse
import csv
import json
import math
from pathlib import Path


def scores(path, require_health):
    result = {}
    with (path / "summary.csv").open() as source:
        for row in csv.DictReader(source):
            name = row["map"]
            if name == "ny":  # excluded by the user; earlier raw NY scores were audited invalid
                continue
            value = None
            if row["status"] == "success":
                try:
                    candidate = float(row["score"])
                    health_path = Path(row["log_dir"]) / "health.json"
                    health = json.loads(health_path.read_text()) if health_path.exists() else None
                    valid = health is not None and health.get("valid") is True
                    if not require_health and health is None:
                        valid = True  # explicitly supplied, previously audited legacy baseline
                    if valid and math.isfinite(candidate) and candidate >= 0:
                        value = candidate
                except (ValueError, TypeError, OSError, json.JSONDecodeError):
                    pass
            result[name] = value
    return result


def render(current, previous, original, reference=None):
    reference = reference or {}
    lines = ["# 本轮与已审计基线成绩对比", "", "仅比较完整有效成绩；失败不是0分，缺少结果不参与均值。",
             "NY排除。旧基线沿用此前审计结论；本轮必须有valid=true的health.json。", "",
             "|地图|original|较早修复版|上一轮v3|本轮v4|相对v3|相对此前最好|", "|---|---:|---:|---:|---:|---:|---:|"]
    changes, best_changes = [], []
    for name, value in current.items():
        old = previous.get(name)
        change = None if value is None or old is None or old <= 0 else 100 * (value / old - 1)
        if change is not None:
            changes.append(change)
        fmt = lambda n: "无有效成绩" if n is None else f"{n:.4f}"
        delta = "不可比较" if change is None else f"{change:+.2f}%"
        baselines = [x for x in (original.get(name), reference.get(name), old) if x is not None and x > 0]
        best_delta = None if value is None or not baselines else 100 * (value / max(baselines) - 1)
        if best_delta is not None:
            best_changes.append(best_delta)
        best_text = "不可比较" if best_delta is None else f"{best_delta:+.2f}%"
        lines.append(f"|{name}|{fmt(original.get(name))}|{fmt(reference.get(name))}|{fmt(old)}|{fmt(value)}|{delta}|{best_text}|")
    if changes:
        lines += ["", f"与v3可比较{len(changes)}张：{sum(c > 1e-9 for c in changes)}升、"
                  f"{sum(c < -1e-9 for c in changes)}降、{sum(abs(c) <= 1e-9 for c in changes)}持平。",
                  f"等权相对变化算术平均：{sum(changes) / len(changes):+.2f}%（不是官方综合分）。"]
    if best_changes:
        lines += [f"与此前各图最好有效成绩相比：{sum(c > 1e-9 for c in best_changes)}升、"
                  f"{sum(c < -1e-9 for c in best_changes)}降、{sum(abs(c) <= 1e-9 for c in best_changes)}持平。",
                  "此前最好仅作历史参照，不能视为某一个统一版本的成绩，也不是公平的独立测试集。"]
    lines += ["", "单轮成绩不能证明每项修改的独立效果；请结合回放并重复验证。", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("current", "previous", "original", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--reference", type=Path, help="earlier audited fixed-version baseline")
    args = parser.parse_args()
    reference = scores(args.reference, False) if args.reference else None
    report = render(scores(args.current, True), scores(args.previous, False), scores(args.original, False), reference)
    args.output.write_text(report)
    print(args.output)


if __name__ == "__main__":
    main()
