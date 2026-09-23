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
    "input_mismatch", "aligned_elapsed_ms", "fix_steps", "status",
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
            "aligned_elapsed_ms": record.get("aligned_elapsed_ms", -1),
            "fix_steps": ",".join(record.get("fix_steps", [])),
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


def cond_table(out: List[str], title: str, groups: List[tuple]) -> None:
    """条件・分類ごとの指標の表を足す。groupsは（条件、分類の名前、行の列）。"""
    out.append(f"### {title}\n")
    out.append("| 条件 | 分類 | 文数 | 最終正解 | 無介入正解 | キー操作数 中央値（平均） | 確定操作数 中央値（平均） | "
               "訂正操作数 中央値（平均） | 終端操作数 合計 | 完了時間 ms 中央値［最小〜最大］ | flicker（合計/読みの字数、100字あたり） | 表示の自動変更 合計 |")
    out.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for cond, label, rows in groups:
        if not rows:
            continue
        n = len(rows)
        elapsed = [r["elapsed_ms"] for r in rows]
        flicker = sum(r["flicker"] for r in rows)
        chars = sum(r["reading_chars"] for r in rows)
        correct = sum(r["correct"] for r in rows)
        clean = sum(r["no_intervention"] for r in rows)
        out.append(
            f"| {cond} | {label} | {n} | {correct}/{n}（{correct / n:.0%}） | {clean}/{n}（{clean / n:.0%}） | "
            f"{fmt(median([r['keys'] for r in rows]))}（{statistics.mean(r['keys'] for r in rows):.1f}） | "
            f"{fmt(median([r['commits'] for r in rows]))}（{statistics.mean(r['commits'] for r in rows):.2f}） | "
            f"{fmt(median([r['corrections'] for r in rows]))}（{statistics.mean(r['corrections'] for r in rows):.2f}） | "
            f"{sum(r['terminators'] for r in rows)} | "
            f"{fmt(median(elapsed), 0)}［{min(elapsed)}〜{max(elapsed)}］ | {flicker}/{chars}（{flicker / chars * 100:.2f}） | "
            f"{sum(r['display_changes'] for r in rows)} |"
        )
    out.append("")


def paired_table(out: List[str], title: str, ok_o: Dict[str, dict], ok_n: Dict[str, dict], tasks: List[str],
                 elapsed_key: str = "elapsed_ms") -> None:
    """同じ課題の条件間の比較（中央値・平均・合計と、課題単位のbootstrap 95%区間）を足す。"""
    out.append(f"### {title}（{len(tasks)}課題）\n")
    co = sum(ok_o[t]["correct"] for t in tasks)
    cn = sum(ok_n[t]["correct"] for t in tasks)
    zo = sum(ok_o[t]["no_intervention"] for t in tasks)
    zn = sum(ok_n[t]["no_intervention"] for t in tasks)
    out.append(f"最終正解 O {co}/{len(tasks)}、N {cn}/{len(tasks)}。無介入正解 O {zo}/{len(tasks)}、N {zn}/{len(tasks)}。\n")
    out.append("| 指標 | 条件O | 条件N | N/O（中央値の比） | 95%区間（比） | N−O の差の中央値 | 95%区間（差） |")
    out.append("|---|---:|---:|---:|---:|---:|---:|")
    for name, label in (("keys", "キー操作数"), ("commits", "確定操作数"), ("corrections", "訂正操作数"),
                        (elapsed_key, "完了時間 ms")):
        mo = median([ok_o[t][name] for t in tasks])
        mn = median([ok_n[t][name] for t in tasks])

        def ratio(sample: List[str], name=name) -> Optional[float]:
            a = median([ok_o[t][name] for t in sample])
            return None if a == 0 else median([ok_n[t][name] for t in sample]) / a

        def diff(sample: List[str], name=name) -> float:
            return median([ok_n[t][name] - ok_o[t][name] for t in sample])

        rci = bootstrap_ci(tasks, ratio)
        dci = bootstrap_ci(tasks, diff)
        out.append(f"| {label} | {fmt(mo)} | {fmt(mn)} | {fmt(None if mo == 0 else mn / mo, 2)} | "
                   f"{'—' if rci is None else f'{rci[0]:.2f}〜{rci[1]:.2f}'} | "
                   f"{fmt(median([ok_n[t][name] - ok_o[t][name] for t in tasks]))} | {dci[0]:.1f}〜{dci[1]:.1f} |")
    so = sum(ok_o[t]["corrections"] for t in tasks)
    sn = sum(ok_n[t]["corrections"] for t in tasks)

    def mean_ratio(sample: List[str]) -> Optional[float]:
        a = statistics.mean(ok_o[t]["corrections"] for t in sample)
        return None if a == 0 else statistics.mean(ok_n[t]["corrections"] for t in sample) / a

    mci = bootstrap_ci(tasks, mean_ratio)
    out.append(f"| 訂正操作数の平均（合計） | {so / len(tasks):.2f}（{so}） | {sn / len(tasks):.2f}（{sn}） | "
               f"{'—' if so == 0 else f'{sn / so:.2f}'} | {'—' if mci is None else f'{mci[0]:.2f}〜{mci[1]:.2f}'} | — | — |")
    out.append("")


def summarize(o_rows: List[dict], n_rows: List[dict], past_o: List[dict], past_n: List[dict]) -> str:
    """条件別の集計（除外はその条件の課題だけ）と、同じ課題の対応のある比較をMarkdownで返す。"""
    out: List[str] = []
    ok_o = {r["task_id"]: r for r in o_rows if r["status"] == "ok"}
    ok_n = {r["task_id"]: r for r in n_rows if r["status"] == "ok"}
    paired = sorted(set(ok_o) & set(ok_n))

    cond_table(out, "条件別（各条件で完了した課題。除外はその条件の課題だけ）",
               [("O", "全体", list(ok_o.values())), ("N", "全体", list(ok_n.values()))])
    by_cat = []
    for cat in CATEGORY_ORDER:
        for cond, table in (("O", ok_o), ("N", ok_n)):
            by_cat.append((cond, cat, [r for r in table.values() if r["category"] == cat]))
    cond_table(out, "条件別・分類ごと", by_cat)
    paired_table(out, "対応のある比較：両条件で完了した課題", ok_o, ok_n, paired)
    no_past = [t for t in paired if ok_o[t]["category"] != "過去訂正"]
    paired_table(out, "対応のある比較：過去訂正を除く", ok_o, ok_n, no_past)

    if past_o and past_n:
        # 過去訂正の6課題を、評価条件どおりの手順で測り直した結果。完了時間は終点を揃えた値を使う。
        po = {r["task_id"]: r for r in past_o if r["status"] == "ok"}
        pn = {r["task_id"]: r for r in past_n if r["status"] == "ok"}
        for table in (po, pn):
            for r in table.values():
                r["elapsed_aligned"] = r["aligned_elapsed_ms"]
        cond_table(out, "過去訂正の再測定（手順を評価条件に合わせたもの。完了時間は計数器の値）",
                   [("O", "過去訂正", list(po.values())), ("N", "過去訂正", list(pn.values()))])
        common = sorted(set(po) & set(pn))
        paired_table(out, "過去訂正の再測定：対応のある比較（完了時間は終点を揃えた値）", po, pn, common, "elapsed_aligned")
        merged_o = dict(ok_o)
        merged_n = dict(ok_n)
        for table, merged in ((po, merged_o), (pn, merged_n)):
            for task_id, r in table.items():
                merged[task_id] = dict(r, elapsed_ms=r["aligned_elapsed_ms"])
        merged_paired = sorted(set(merged_o) & set(merged_n))
        cond_table(out, "過去訂正を再測定の結果で置いた条件別の集計（過去訂正の完了時間は終点を揃えた値）",
                   [("O", "全体", list(merged_o.values())), ("N", "全体", list(merged_n.values()))])
        paired_table(out, "対応のある比較：過去訂正を再測定の結果で置いたもの", merged_o, merged_n, merged_paired)
    return "\n".join(out)


# ---- 版2で加えた集計 ----

RESIZE_PREFIXES = ("resize-self", "resize-prev", "resize-head")


def classify_error(row: dict) -> str:
    """誤答の原因を手順の記録から分ける（版2の結果の「誤答の原因の分類」の規則）。"""
    steps = row["fix_steps"].split(",") if row.get("fix_steps") else []
    resized = any(s.startswith(RESIZE_PREFIXES) for s in steps)
    if resized:
        return "区切りの誤り"
    if len(steps) >= 2 and steps[-2] == "resize-none" and steps[-1] == "no-candidate":
        return "候補の全範囲に無い"
    return "その他"


def with_aligned_past(rows: Dict[str, dict]) -> Dict[str, dict]:
    """過去訂正の課題の完了時間を、終点を揃えた値に置き換えた写し（両条件の終点を揃えて比べるため）。"""
    out = {}
    for task_id, r in rows.items():
        if r["category"] == "過去訂正" and r.get("aligned_elapsed_ms", -1) >= 0:
            out[task_id] = dict(r, elapsed_ms=r["aligned_elapsed_ms"])
        else:
            out[task_id] = r
    return out


def load_v1(path: Path) -> Dict[str, Dict[str, dict]]:
    """版1のTSVから、過去訂正を再測定の結果で置いた条件O・Nの行を読む（版1の判定に使った範囲）。"""
    lines = path.read_text(encoding="utf-8").splitlines()
    header = lines[0].split("\t")
    rows = [dict(zip(header, line.split("\t"))) for line in lines[1:]]
    result: Dict[str, Dict[str, dict]] = {"O": {}, "N": {}}
    for cond in ("O", "N"):
        for r in rows:
            if r["status"] != "ok":
                continue
            if r["condition"] == cond and r["category"] != "過去訂正":
                result[cond][r["task_id"]] = r
            elif r["condition"] == f"{cond}-past":
                result[cond][r["task_id"]] = dict(r, elapsed_ms=r["aligned_elapsed_ms"])
    for cond in result:
        for r in result[cond].values():
            for key in ("correct", "no_intervention", "keys", "commits", "corrections", "elapsed_ms", "flicker"):
                r[key] = int(r[key])
    return result


def v2_sections(ok_o: Dict[str, dict], ok_n: Dict[str, dict], v1: Optional[Dict[str, Dict[str, dict]]]) -> str:
    """版2の要約：過去訂正の終点を揃えた比較、誤答の原因の分類、版1との同じ課題での比較。"""
    out: List[str] = []
    ao, an = with_aligned_past(ok_o), with_aligned_past(ok_n)
    paired = sorted(set(ao) & set(an))
    cond_table(out, "条件別（過去訂正の完了時間は終点を揃えた値）",
               [("O", "全体", list(ao.values())), ("N", "全体", list(an.values()))])
    paired_table(out, "対応のある比較：両条件で完了した課題（過去訂正の完了時間は終点を揃えた値）", ao, an, paired)
    no_past = [t for t in paired if ao[t]["category"] != "過去訂正"]
    paired_table(out, "対応のある比較：過去訂正を除く", ao, an, no_past)

    out.append("### 誤答の原因の分類\n")
    out.append("| 条件 | 分類 | 件数 | 課題 |")
    out.append("|---|---|---:|---|")
    for cond, table in (("O", ok_o), ("N", ok_n)):
        groups: Dict[str, List[str]] = {}
        for task_id, r in table.items():
            if not r["correct"]:
                groups.setdefault(classify_error(r), []).append(task_id)
        for label in ("候補の全範囲に無い", "区切りの誤り", "その他"):
            ids = groups.get(label, [])
            out.append(f"| {cond} | {label} | {len(ids)} | {'、'.join(ids) or '—'} |")
    out.append("")
    resized = {c: [t for t, r in table.items() if any(s.startswith(RESIZE_PREFIXES) for s in r["fix_steps"].split(","))]
               for c, table in (("O", ok_o), ("N", ok_n))}
    for cond, ids in resized.items():
        ok = [t for t in ids if (ok_o if cond == "O" else ok_n)[t]["correct"]]
        out.append(f"伸縮をした課題（{cond}）：{len(ids)}件（{'、'.join(ids) or '—'}）。うち最終正解{len(ok)}件（{'、'.join(ok) or '—'}）。\n")

    if v1 is not None:
        out.append("### 版1との比較（同じ課題。版1はAPK 61cef48、過去訂正は版1の再測定の値）\n")
        out.append("| 条件 | 対応課題数 | 最終正解 版1→版2 | 無介入正解 版1→版2 | キー操作数 中央値 版1→版2 | "
                   "訂正操作数 平均 版1→版2 | 完了時間 ms 中央値 版1→版2 | 誤→正 | 正→誤 |")
        out.append("|---|---:|---:|---:|---:|---:|---:|---|---|")
        for cond, table in (("O", ao), ("N", an)):
            old = v1[cond]
            common = sorted(set(old) & set(table))
            n = len(common)
            fixed = [t for t in common if not old[t]["correct"] and table[t]["correct"]]
            broken = [t for t in common if old[t]["correct"] and not table[t]["correct"]]
            out.append(
                f"| {cond} | {n} | {sum(old[t]['correct'] for t in common)}→{sum(table[t]['correct'] for t in common)} | "
                f"{sum(old[t]['no_intervention'] for t in common)}→{sum(table[t]['no_intervention'] for t in common)} | "
                f"{fmt(median([old[t]['keys'] for t in common]))}→{fmt(median([table[t]['keys'] for t in common]))} | "
                f"{statistics.mean(old[t]['corrections'] for t in common):.2f}→{statistics.mean(table[t]['corrections'] for t in common):.2f} | "
                f"{fmt(median([old[t]['elapsed_ms'] for t in common]), 0)}→{fmt(median([table[t]['elapsed_ms'] for t in common]), 0)} | "
                f"{'、'.join(fixed) or '—'} | {'、'.join(broken) or '—'} |")
        out.append("")
    return "\n".join(out)


def main() -> int:
    """結果を読み、課題IDごとのTSVを書き、要約を標準出力へ出す。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("work", help="results-O.jsonlとresults-N.jsonlのあるディレクトリ")
    parser.add_argument("--rows", help="課題IDごとの数値を書くTSVの場所")
    parser.add_argument("--past-o", help="過去訂正の再測定の結果（条件O）")
    parser.add_argument("--past-n", help="過去訂正の再測定の結果（条件N）")
    parser.add_argument("--also", action="append", default=[],
                        help="集計には入れず、課題IDごとのTSVにだけ加える結果（ラベル=jsonlの場所）。途中で止めた回の記録に使う")
    parser.add_argument("--v2", action="store_true", help="版2の要約（過去訂正を本測定に含む）を出す")
    parser.add_argument("--v1-rows", help="版1の課題IDごとのTSV（版1との比較に使う）")
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
    past_o = load_results(Path(args.past_o), readings) if args.past_o else []
    past_n = load_results(Path(args.past_n), readings) if args.past_n else []
    for label, rows in (("O-past", past_o), ("N-past", past_n)):
        for row in rows:
            row["row_label"] = label
    if args.v2:
        ok_o = {r["task_id"]: r for r in o_rows if r["status"] == "ok"}
        ok_n = {r["task_id"]: r for r in n_rows if r["status"] == "ok"}
        v1 = load_v1(Path(args.v1_rows)) if args.v1_rows else None
        print(summarize(o_rows, n_rows, [], []))
        print(v2_sections(ok_o, ok_n, v1))
        return 0
    print(summarize(o_rows, n_rows, past_o, past_n))
    return 0


if __name__ == "__main__":
    sys.exit(main())
