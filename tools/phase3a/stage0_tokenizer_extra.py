#!/usr/bin/env python3
"""段階0の補足：開発用の集合に現れない文字（空白、連続した数字、全角英数、記号）で、jinenの2つのtokenizerを比べる。

開発用30文の照合（stage0.py）の判定には使わない。左文脈に入り得る文字のうち、開発用の集合で確かめられなかった
ものの傾向を記録するためだけに使う。文字列は架空の短い断片で、試験用の集合から取っていない。
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import stage0  # noqa: E402

# 左文脈に入り得る断片。読みは固定の「テスト」。
CONTEXTS = [
    "午後 3時",
    "午後　3時",
    "2026年10月15日",
    "１２３円",
    "ＡＢＣのＰＤＦ",
    "Hello World",
    "〇時",
    "①番",
    "a-b_c/d?e=1&f",
    "「引用」と（括弧）",
    "…〜・",
    "😀の絵文字",
    "ｶﾀｶﾅ",
    "゙単独の濁点",
]


def main() -> int:
    hf = stage0.HfBpeTokenizer(stage0.JINEN_TOKENIZER)
    spec = next(m for m in stage0.MODELS if m.key == "JX")
    probe = stage0.Probe(spec, 1)
    rows = []
    for context in CONTEXTS:
        prompt = stage0.build_prompt("jinen", context, "てすと")
        llama_ids = probe.run("tok", prompt).ids
        hf_ids = hf.encode(prompt)
        rows.append({"context": context, "match": llama_ids == hf_ids, "tokenizer_json": hf_ids, "llama_cpp": llama_ids})
    probe.close()
    for row in rows:
        print(json.dumps(row, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
