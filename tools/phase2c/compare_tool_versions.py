#!/usr/bin/env python3
"""自動測定の道具の版を変えたときに、旧い版と新しい版で同じ結果になるかを確かめるスクリプト。

評価条件`docs/phase2c-evaluation-protocol.md`の「道具の版」の規則に従い、新しい版を本測定に使う前に行う。
課題は開発用の30文（`docs/phase3a-dev-tasks.tsv`）か、Phase 2cの練習6文（R01〜R06）だけを使い、
試験用の54文は使わない。

- `run`：同じAPKのまま、条件ごとに旧い版（git commitから取り出す）と新しい版を、学習を消してから同じ順番で流す。
  実機を使うため、端末を他の測定に使っていない時にだけ実行する。
- `compare`：二つの結果（jsonl）を課題IDごとに比べ、正誤・各操作数・flicker・状態変化・手順の記録の食い違いを示す。
  完了時間（`elapsed_ms`など）は待ち方で変わるため比べず、1課題あたりの時間として並べて示す。

外部の依存は使わない（macOSの既存のpython3の標準ライブラリだけ）。
"""

import argparse
import hashlib
import json
import shutil
import statistics
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional

REPO = Path(__file__).resolve().parents[2]
NEW_TOOL = REPO / "tools" / "phase2c" / "run_automated.py"
DEV_TASKS = REPO / "docs" / "phase3a-dev-tasks.tsv"
PHASE2C_TASKS = REPO / "docs" / "phase2c-tasks.tsv"
# 旧い版の道具が入ったcommit。版1の待ち方に伸縮の手順を加えた版で、Phase 2cの版2の結果（2026-09-24）を作った。
OLD_TOOL_COMMIT = "2f27b80"
# 旧い版の`run_automated.py`のSHA-256。取り出した道具が結果を作った道具と同じことを確かめるために使う。
OLD_TOOL_SHA256 = "13c903f19cfd15796a887cd5ef7c6b873d8942d9923282958979740816daceb6"
# 練習の課題ID。Phase 2cの課題文のうち、同等性の確認に使ってよいのはこれだけ。
PRACTICE_IDS = ["R01", "R02", "R03", "R04", "R05", "R06"]

# 一致を求める項目。評価用計数の列のうち時刻以外と、正誤、送った押下の数、訂正の手順の記録。
COMPARED_COUNTS = [
    "keys", "commits", "corrections", "terminators", "display_changes", "flicker", "stable_overwrites",
    "chosen_overwrites", "stale_results_discarded", "to_stable", "to_chosen", "to_provisional",
]
COMPARED_FIELDS = ["status", "correct", "sent_presses", "fix_steps"]


def task_ids(tasks_file: Path) -> List[str]:
    """課題文のTSVの課題ID（ファイルの順）。"""
    return [line.split("\t")[0] for line in tasks_file.read_text(encoding="utf-8").splitlines()[1:]]


def load(path: Path) -> Dict[str, dict]:
    """結果のjsonlを課題IDごとに読む。同じ課題が複数あれば最後の行を使う。"""
    rows = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            record = json.loads(line)
            rows[record["task_id"]] = record
    return rows


def values(record: Optional[dict]) -> Dict[str, object]:
    """一致を求める項目の値。結果の行が無ければ空。"""
    if record is None:
        return {}
    out: Dict[str, object] = {name: record.get(name) for name in COMPARED_FIELDS}
    counts = record.get("counts", {})
    out.update({name: counts.get(name) for name in COMPARED_COUNTS})
    return out


def cycle_ms(rows: Dict[str, dict], order: List[str]) -> List[int]:
    """1課題あたりの時間（ms）。次の課題の計数の開始時刻との差で、準備・入力・待ち・照合をすべて含む（端末の時計）。"""
    starts = [rows[t]["counts"]["started_ms"] for t in order if t in rows and rows[t].get("status") == "ok"]
    return [b - a for a, b in zip(starts, starts[1:])]


def compare(old_path: Path, new_path: Path) -> int:
    """二つの結果を課題IDごとに比べて表を出す。食い違いが一つでもあれば1を返す。"""
    old, new = load(old_path), load(new_path)
    order = [t for t in old if t in new] + [t for t in new if t not in old]
    order += [t for t in old if t not in order]
    mismatches = 0
    print(f"| 課題 | 一致 | 食い違い（旧→新） | 完了時間 ms（旧→新） |")
    print("|---|---|---|---:|")
    for task_id in order:
        a, b = values(old.get(task_id)), values(new.get(task_id))
        if not a or not b:
            diff = ["結果の行が片方に無い"]
        else:
            diff = [f"{name} {a[name]}→{b[name]}" for name in a if a[name] != b[name]]
        mismatches += bool(diff)
        elapsed = [(old.get(task_id) or {}).get("counts", {}).get("elapsed_ms"),
                   (new.get(task_id) or {}).get("counts", {}).get("elapsed_ms")]
        print(f"| {task_id} | {'はい' if not diff else 'いいえ'} | {'; '.join(diff) or '-'} | {elapsed[0]}→{elapsed[1]} |")
    print()
    for label, rows in (("旧", old), ("新", new)):
        cycles = cycle_ms(rows, order)
        if cycles:
            print(f"{label}：1課題あたり 中央値 {statistics.median(cycles) / 1000:.1f} 秒、平均 {statistics.mean(cycles) / 1000:.1f} 秒"
                  f"（{len(cycles)}区間、次の課題の開始までの差）")
    print(f"\n課題 {len(order)} 件のうち、食い違い {mismatches} 件")
    return 1 if mismatches else 0


def extract_old_tool(work: Path, tasks_file: Path) -> Path:
    """旧い版の道具をgitから取り出し、課題文を読む場所（道具から見た`docs/phase2c-tasks.tsv`）へ課題文を置く。

    旧い版は課題文の場所が固定されているため、道具を変えずに開発用の課題を読ませるための置き方である。
    """
    root = work / "old-tool"
    (root / "tools" / "phase2c").mkdir(parents=True, exist_ok=True)
    (root / "docs").mkdir(parents=True, exist_ok=True)
    source = subprocess.run(["git", "-C", str(REPO), "show", f"{OLD_TOOL_COMMIT}:tools/phase2c/run_automated.py"],
                            capture_output=True, text=True, check=True).stdout
    if hashlib.sha256(source.encode("utf-8")).hexdigest() != OLD_TOOL_SHA256:
        raise SystemExit(f"old tool at {OLD_TOOL_COMMIT} does not match the expected SHA-256")
    script = root / "tools" / "phase2c" / "run_automated.py"
    script.write_text(source, encoding="utf-8")
    shutil.copyfile(tasks_file, root / "docs" / "phase2c-tasks.tsv")
    return script


def run(work: Path, practice: bool, conditions: List[str], repeat_old: bool) -> int:
    """条件ごとに、旧い版→新しい版（→任意で旧い版をもう一度）を同じ課題・同じ順番で流し、比べる。"""
    tasks_file = PHASE2C_TASKS if practice else DEV_TASKS
    ids = PRACTICE_IDS if practice else task_ids(DEV_TASKS)
    work.mkdir(parents=True, exist_ok=True)
    old_tool = extract_old_tool(work, tasks_file)
    old_work, new_work = work / "old", work / "new"
    old_work.mkdir(exist_ok=True)
    new_work.mkdir(exist_ok=True)
    # 面の配置は新しい版で一度だけ読み、両方の版で同じものを使う。
    subprocess.run([sys.executable, str(NEW_TOOL), "probe", "--work", str(new_work)], check=True)
    shutil.copyfile(new_work / "layouts.json", old_work / "layouts.json")
    status = 0
    for condition in conditions:
        runs = [("old", old_tool, old_work, f"results-{condition}.jsonl"),
                ("new", NEW_TOOL, new_work, f"results-{condition}.jsonl")]
        if repeat_old:
            runs.append(("old-repeat", old_tool, old_work, f"results-{condition}-repeat.jsonl"))
        for label, tool, run_work, out in runs:
            print(f"== {condition} {label}", file=sys.stderr, flush=True)
            command = [sys.executable, str(tool), "run", "--condition", condition, "--tasks", ",".join(ids),
                       "--work", str(run_work), "--out", out]
            if tool == NEW_TOOL:
                command += ["--tasks-file", str(tasks_file)]
            (run_work / out).unlink(missing_ok=True)
            if subprocess.run(command).returncode != 0:
                print(f"{condition} {label} stopped; see the jsonl for the aborted task", file=sys.stderr)
                status = 2
        print(f"\n## 条件{condition}：旧い版と新しい版\n")
        status = max(status, compare(old_work / f"results-{condition}.jsonl", new_work / f"results-{condition}.jsonl"))
        if repeat_old:
            print(f"\n## 条件{condition}：旧い版の繰り返し（同じ版での揺れ）\n")
            compare(old_work / f"results-{condition}.jsonl", old_work / f"results-{condition}-repeat.jsonl")
    return status


def main() -> int:
    """入口。runで両方の版を流して比べ、compareで既存の結果を比べる。"""
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    run_parser = sub.add_parser("run")
    run_parser.add_argument("--work", required=True, help="結果を置く場所（.local-build/phase2c/<日付>-tool-v2など）")
    run_parser.add_argument("--practice", action="store_true", help="開発用の30文ではなく、Phase 2cの練習6文を使う")
    run_parser.add_argument("--conditions", default="O,N")
    run_parser.add_argument("--repeat-old", action="store_true", help="旧い版をもう一度流し、同じ版での揺れも示す")
    compare_parser = sub.add_parser("compare")
    compare_parser.add_argument("old")
    compare_parser.add_argument("new")
    args = parser.parse_args()
    if args.command == "compare":
        return compare(Path(args.old), Path(args.new))
    return run(Path(args.work), args.practice, args.conditions.split(","), args.repeat_old)


if __name__ == "__main__":
    sys.exit(main())
