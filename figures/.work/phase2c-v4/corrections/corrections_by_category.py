"""Phase 2c 版4の訂正操作数を、課題の種類ごとに条件O・Nで比べる図を作る。

元データ: docs/phase2c-results-automated-v4.tsv（課題IDごとの計数）
出力先: figures/phase2c-v4/corrections/corrections_by_category.png
条件Oの訂正のうち「→」（明示変換で次の文節へ移る押下）は、fix_stepsの"right"の数として分けて塗る。
"""
import csv
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import paperstyle as ps  # noqa: E402

ROOT = Path(__file__).resolve().parents[4]
TSV = ROOT / "docs/phase2c-results-automated-v4.tsv"
OUT = ROOT / "figures/phase2c-v4/corrections/corrections_by_category.png"


def load_means(path):
    """課題の種類ごとに、条件Oの「→」・Oの「→」以外・Nの訂正操作数の平均を返す。"""
    sums = defaultdict(lambda: np.zeros(3))
    counts = defaultdict(int)
    with open(path, encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            corr = int(row["corrections"])
            cat = row["category"]
            if row["condition"] == "O":
                right = row["fix_steps"].split(",").count("right")
                sums[cat] += (right, corr - right, 0)
                counts[cat] += 1
            else:
                sums[cat] += (0, 0, corr)
    cats = sorted(sums, key=lambda c: -(sums[c][2] / counts[c]))
    means = np.array([sums[c] / counts[c] for c in cats])
    total = sum(sums.values()) / sum(counts.values())
    return ["全体"] + cats, np.vstack([total, means]), counts[cats[0]]


def main():
    ps.use("screen")
    cats, m, n_per_cat = load_means(TSV)
    x = np.arange(len(cats))
    w = 0.38
    fig, ax = plt.subplots(figsize=(12, 5.2))
    blue, warm = ps.OKABE_ITO[0], ps.OURS["color"]
    ax.bar(x - w / 2, m[:, 1], w, color=blue, label="O：→以外の訂正")
    ax.bar(x - w / 2, m[:, 0], w, bottom=m[:, 1], color="#9ecae1", hatch="//", edgecolor=blue,
           label="O：→（次の文節へ移る押下）")
    ax.bar(x + w / 2, m[:, 2], w, color=warm, label="N：訂正")
    for i in range(len(cats)):
        ax.text(x[i] - w / 2, m[i, 0] + m[i, 1] + 0.05, f"{m[i, 0] + m[i, 1]:.2f}", ha="center", fontsize=10)
        ax.text(x[i] + w / 2, m[i, 2] + 0.05, f"{m[i, 2]:.2f}", ha="center", fontsize=10)
    ax.axvline(0.5, color=ps.NEUTRAL, lw=1)
    ax.set_xticks(x, cats)
    ax.set_ylabel("訂正操作数の平均（回／課題）")
    ax.set_ylim(0, m.max() * 1.18 + 0.2)
    ax.legend(loc="upper right")
    ax.set_title(f"条件O・Nの訂正操作数（全体54課題、各種類{n_per_cat}課題、1回ずつ測定）", fontsize=12)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(OUT)


if __name__ == "__main__":
    main()
