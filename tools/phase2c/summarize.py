#!/usr/bin/env python3
"""Phase 2cの自動測定の結果（条件O・N）を集計し、課題IDごとの表と要約を出す。

入力は`run_automated.py`が書いた`results-O.jsonl`と`results-N.jsonl`（課題ID、正誤、評価用計数だけ）。
出力は、課題IDごとの数値のTSV（図や再集計の元データ）と、Markdownの要約表を標準出力へ出す。
比較は同じ課題の条件間で行い、中央値と、課題単位のbootstrapによる95%区間（10,000回、seed固定）を示す。
外部の依存は使わない。
"""

import argparse
import json
import random
import statistics
import sys
from pathlib import Path
from typing import Callable, Dict, List, Optional, Sequence

REPO = Path(__file__).resolve().parents[2]
TASKS_TSV = REPO / "docs" / "phase2c-tasks.tsv"

# bootstrapの回数とseed。評価条件の「10,000回、seed固定」に従う。
BOOTSTRAP_ROUNDS = 10_000
BOOTSTRAP_SEED = 20261001

# 課題IDごとのTSVに出す列（評価用計数の列と、照合の結果）。
ROW_COLUMNS = [
    "condition", "task_id", "category", "reading_chars", "correct", "no_intervention", "keys", "commits",
    "corrections", "terminators", "display_changes", "flicker", "stable_overwrites", "chosen_overwrites",
    "stale_results_discarded", "to_stable", "to_chosen", "to_provisional", "elapsed_ms", "sent_presses",
    "input_mismatch", "status",
]

CATEGORY_ORDER = ["通常文", "同音異義語", "名前", "数値", "URL", "長文", "過去訂正", "句点なし", "英語混在"]


def load_readings() -> Dict[str, str]:
    """課題IDごとの読み（flickerの分母に使う）。"""
    readings = {}
    for line in TASKS_TSV.read_text(encoding="utf-8").splitlines()[1:]:
        cols = line.split("\t")
        readings[cols[0]] = cols[3]
    return readings


def load_results(path: Path, readings: Dict[str, str]) -> List[dict]:
    """1条件の結果を読み、課題IDごとの行にする。中断した行はstatusだけを持つ。"""
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        record = json.loads(line)
        if record.get("status") != "ok":
            rows.append({"condition": record["condition"], "task_id": record["task_id"], "status": record.get("status")})
            continue
        counts = record["counts"]
        row = {
            "condition": record["condition"],
            "task_id": record["task_id"],
            "category": record["category"],
            "reading_chars": len(readings[record["task_id"]]),
            "correct": int(record["correct"]),
            "no_intervention": int(record["correct"] and counts["commits"] == 0 and counts["corrections"] == 0),
            "sent_presses": record["sent_presses"],
            "status": "ok",
        }
        for name in ROW_COLUMNS:
            if name in counts:
                row[name] = counts[name]
        # 送った押下の数とIMEが数えた押下（キー操作数と終端操作）の差。0でなければ入力の欠落・二重入力の疑い。
        row["input_mismatch"] = record["sent_presses"] - counts["keys"] - counts["terminators"]
        rows.append(row)
    # 計数を始める前の中断から同じ課題を続けた場合は、完了した行だけを残す。
    done = {r["task_id"] for r in rows if r["status"] == "ok"}
    return [r for r in rows if r["status"] == "ok" or r["task_id"] not in done]


def median(values: Sequence[float]) -> float:
    """中央値。"""
    return statistics.median(values)


def bootstrap_ci(task_ids: List[str], stat: Callable[[List[str]], Optional[float]]) -> Optional[tuple]:
    """課題を復元抽出してstatを10,000回求め、2.5%点と97.5%点を返す。"""
    rng = random.Random(BOOTSTRAP_SEED)
    values = []
    for _ in range(BOOTSTRAP_ROUNDS):
        sample = [rng.choice(task_ids) for _ in task_ids]
        value = stat(sample)
        if value is not None:
            values.append(value)
    if not values:
        return None
    values.sort()
    return values[int(0.025 * len(values))], values[int(0.975 * len(values)) - 1]


def fmt(value: Optional[float], digits: int = 1) -> str:
    """表の数値。"""
    if value is None:
        return "—"
    if isinstance(value, float) and not value.is_integer():
        return f"{value:.{digits}f}"
    return str(int(value)) if isinstance(value, float) else str(value)


def summarize(o_rows: List[dict], n_rows: List[dict]) -> str:
    """条件ごと・分類ごとの表と、仮説の判定に使う比較をMarkdownで返す。"""
    out = []
    ok_o = {r["task_id"]: r for r in o_rows if r["status"] == "ok"}
    ok_n = {r["task_id"]: r for r in n_rows if r["status"] == "ok"}
    paired = sorted(set(ok_o) & set(ok_n))

    def cond_table(title: str, groups: List[tuple]) -> None:
        out.append(f"### {title}\n")
        out.append("| 条件 | 分類 | 文数 | 最終正解率 | 無介入正解文率 | キー操作数 中央値（平均） | 確定操作数 中央値（平均） | "
                   "訂正操作数 中央値（平均） | 終端操作数 合計 | 完了時間 ms 中央値［最小〜最大］ | flicker/読み100字 | 表示の自動変更 合計 |")
        out.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
        for cond, label, rows in groups:
            if not rows:
                continue
            n = len(rows)
            elapsed = [r["elapsed_ms"] for r in rows]
            flick = sum(r["flicker"] for r in rows) / sum(r["reading_chars"] for r in rows) * 100
            out.append(
                f"| {cond} | {label} | {n} | {sum(r['correct'] for r in rows) / n:.0%} | "
                f"{sum(r['no_intervention'] for r in rows) / n:.0%} | "
                f"{fmt(median([r['keys'] for r in rows]))}（{statistics.mean(r['keys'] for r in rows):.1f}） | "
                f"{fmt(median([r['commits'] for r in rows]))}（{statistics.mean(r['commits'] for r in rows):.2f}） | "
                f"{fmt(median([r['corrections'] for r in rows]))}（{statistics.mean(r['corrections'] for r in rows):.2f}） | "
                f"{sum(r['terminators'] for r in rows)} | "
                f"{fmt(median(elapsed), 0)}［{min(elapsed)}〜{max(elapsed)}］ | {flick:.2f} | "
                f"{sum(r['display_changes'] for r in rows)} |"
            )
        out.append("")

    groups = [("O", "全体", [ok_o[t] for t in paired]), ("N", "全体", [ok_n[t] for t in paired])]
    cond_table("条件ごと（両条件で完了した課題）", groups)
    by_cat = []
    for cat in CATEGORY_ORDER:
        for cond, table in (("O", ok_o), ("N", ok_n)):
            by_cat.append((cond, cat, [table[t] for t in paired if table[t]["category"] == cat]))
    cond_table("分類ごと", by_cat)

    # 仮説の判定に使う比較。
    out.append("### 条件間の比較（同じ課題の対応、課題単位のbootstrap 95%区間）\n")
    out.append("| 指標 | 条件O | 条件N | N/O（中央値の比） | 95%区間（比） | N−O の差の中央値 | 95%区間（差） |")
    out.append("|---|---:|---:|---:|---:|---:|---:|")
    for name, label in (("keys", "キー操作数"), ("commits", "確定操作数"), ("corrections", "訂正操作数"),
                        ("elapsed_ms", "完了時間 ms")):
        mo = median([ok_o[t][name] for t in paired])
        mn = median([ok_n[t][name] for t in paired])

        def ratio(sample: List[str], name=name) -> Optional[float]:
            a = median([ok_o[t][name] for t in sample])
            b = median([ok_n[t][name] for t in sample])
            return None if a == 0 else b / a

        def diff(sample: List[str], name=name) -> float:
            return median([ok_n[t][name] - ok_o[t][name] for t in sample])

        r = None if mo == 0 else mn / mo
        rci = bootstrap_ci(paired, ratio)
        d = median([ok_n[t][name] - ok_o[t][name] for t in paired])
        dci = bootstrap_ci(paired, diff)
        out.append(f"| {label} | {fmt(mo)} | {fmt(mn)} | {fmt(r, 2)} | "
                   f"{'—' if rci is None else f'{rci[0]:.2f}〜{rci[1]:.2f}'} | {fmt(d)} | {dci[0]:.1f}〜{dci[1]:.1f} |")
    for name, label in (("corrections", "訂正操作数の平均"),):
        ao = statistics.mean(ok_o[t][name] for t in paired)
        an = statistics.mean(ok_n[t][name] for t in paired)

        def mean_ratio(sample: List[str], name=name) -> Optional[float]:
            a = statistics.mean(ok_o[t][name] for t in sample)
            return None if a == 0 else statistics.mean(ok_n[t][name] for t in sample) / a

        mci = bootstrap_ci(paired, mean_ratio)
        out.append(f"| {label} | {ao:.2f} | {an:.2f} | {'—' if ao == 0 else f'{an / ao:.2f}'} | "
                   f"{'—' if mci is None else f'{mci[0]:.2f}〜{mci[1]:.2f}'} | — | — |")
    out.append("")
    return "\n".join(out)


def main() -> int:
    """結果を読み、課題IDごとのTSVを書き、要約を標準出力へ出す。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("work", help="results-O.jsonlとresults-N.jsonlのあるディレクトリ")
    parser.add_argument("--rows", help="課題IDごとの数値を書くTSVの場所")
    parser.add_argument("--also", action="append", default=[],
                        help="集計には入れず、課題IDごとのTSVにだけ加える結果（ラベル=jsonlの場所）。途中で止めた回の記録に使う")
    args = parser.parse_args()
    work = Path(args.work)
    readings = load_readings()
    o_rows = load_results(work / "results-O.jsonl", readings)
    n_rows = load_results(work / "results-N.jsonl", readings)
    if args.rows:
        lines = ["\t".join(ROW_COLUMNS)]
        extra = []
        for spec in args.also:
            label, path = spec.split("=", 1)
            for row in load_results(Path(path), readings):
                row["condition"] = label
                extra.append(row)
        for row in o_rows + n_rows + extra:
            lines.append("\t".join(str(row.get(c, "")) for c in ROW_COLUMNS))
        Path(args.rows).write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(summarize(o_rows, n_rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
