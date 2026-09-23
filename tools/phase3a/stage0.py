#!/usr/bin/env python3
"""Phase 3aの段階0（入力形式の確認）を、開発用の30文だけで流すスクリプト。

評価条件`docs/phase3a-model-selection-protocol.md`の「段階0」を機械的に行う。
- 開発用の課題（`docs/phase3a-dev-tasks.tsv`）の読みを1打鍵ずつ入れたときの各時点の読みから、
  端末の`NeuralRangeConverter`と同じ規則（かなの連なりだけをモデルへ渡す、左文脈は同じ要求で先に出した表記）で
  モデルへの要求を作り、各モデルでgreedyに変換する。
- 保護範囲の表記が左文脈になる場合も含めるため、各時点の読みを任意の位置で「前が保護範囲、後ろが部分範囲」に分けた
  要求も作る（tokenizerの照合とtoken数の上限の確認だけに使う）。
- jinenは、作者の`tokenizer.json`をこのファイルの`HfBpeTokenizer`で読み、llama.cpp内蔵のtokenizerとtoken列を比べる。

試験用の集合（Phase 2cの54文、AJIMEE-Bench）は読まない。外部の依存は使わない（標準ライブラリだけ）。
生の記録は`.local-build/phase3a/<日付>/stage0/`へ、集計はJSONで標準出力へ出す。
"""

import argparse
import json
import subprocess
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

REPO = Path(__file__).resolve().parents[2]
DEV_TASKS = REPO / "docs" / "phase3a-dev-tasks.tsv"
NP = Path("/Users/marutyan/PrivateDev/uzumi/.local-build/neural-probe")
PROBE = Path("/Users/marutyan/PrivateDev/uzumi/.local-build/phase3a/bin/stage0_probe")
JINEN_TOKENIZER = NP / "tokenizers" / "jinen-v2-small-tokenizer.json"

# 評価条件で固定した推論の設定。
THREADS = 4
N_CTX = 512
# 全モデル共通の文字数の上限（評価条件の段階0）。かなの連なりは30文字、左文脈は64文字。
MAX_KANA_CHARS = 30
MAX_CONTEXT_CHARS = 64

# プロンプトの区切り記号。U+EE02が左文脈、U+EE00が読みの始まり、U+EE01が出力の始まり。
CONTEXT_TAG = ""
INPUT_TAG = ""
OUTPUT_TAG = ""


@dataclass(frozen=True)
class ModelSpec:
    """比較条件の1モデル。styleはプロンプト形式（zenzかjinen）。"""
    key: str
    gguf: str
    style: str


MODELS = [
    ModelSpec("ZS", "zenz-v3.2-small-Q5_K_M.gguf", "zenz"),
    ModelSpec("ZX", "zenz-v3.2-xsmall-Q5_K_M.gguf", "zenz"),
    ModelSpec("JS", "jinen-v2-small-Q5_K_M.gguf", "jinen"),
    ModelSpec("JX", "jinen-v2-xsmall-Q5_K_M.gguf", "jinen"),
]

# 「小゛゜」キーの巡回（端末のKanaModifierと同じ）。濁点・半濁点・小書きの文字は、基の文字を入れてから変わる。
KANA_CYCLES = [
    "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ", "かが", "きぎ", "くぐ", "けげ", "こご",
    "さざ", "しじ", "すず", "せぜ", "そぞ", "ただ", "ちぢ", "つっづ", "てで", "とど",
    "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ", "やゃ", "ゆゅ", "よょ", "わゎ",
]


def typing_states(reading: str) -> List[str]:
    """読みを1打鍵ずつ入れたときの各時点の読みを返す。濁点などは基の文字を経由する。"""
    states = []
    current = ""
    for ch in reading:
        cycle = next((c for c in KANA_CYCLES if ch in c[1:]), None)
        if cycle is None:
            current += ch
            states.append(current)
            continue
        for step in cycle[: cycle.index(ch) + 1]:
            states.append(current + step)
        current += ch
    return states


def is_kana(ch: str) -> bool:
    """端末の`isKanaCluster`と同じ範囲（ひらがな、カタカナ、長音、繰り返し記号、結合用の濁点）。中黒は含めない。"""
    cp = ord(ch)
    return 0x3041 <= cp <= 0x3096 or 0x3099 <= cp <= 0x309F or 0x30A1 <= cp <= 0x30FA or 0x30FC <= cp <= 0x30FF


def script_runs(text: str) -> List[Tuple[str, bool]]:
    """読みを、かなの連なりとそれ以外の連なりへ分ける（端末の`scriptRuns`と同じ）。"""
    runs: List[Tuple[str, bool]] = []
    for ch in text:
        kana = is_kana(ch)
        if runs and runs[-1][1] == kana:
            runs[-1] = (runs[-1][0] + ch, kana)
        else:
            runs.append((ch, kana))
    return runs


def to_katakana(text: str) -> str:
    """ひらがな（ぁ..ゖ、ゝゞ）を同じ音のカタカナへ写す。端末の実装と同じ規則。"""
    out = []
    for ch in text:
        cp = ord(ch)
        out.append(chr(cp + 0x60) if 0x3041 <= cp <= 0x3096 or 0x309D <= cp <= 0x309E else ch)
    return "".join(out)


def sanitize_field(text: str) -> str:
    """プロンプトの欄から区切り記号（U+EE00..U+EE0F）と制御文字を除く。"""
    return "".join(c for c in text if not (0xEE00 <= ord(c) <= 0xEE0F or ord(c) < 0x20 or ord(c) == 0x7F))


def build_prompt(style: str, context: str, reading: str) -> str:
    """評価条件の前処理の候補どおりにプロンプトを作る。"""
    context = sanitize_field(context)[-MAX_CONTEXT_CHARS:]
    kana = to_katakana(sanitize_field(reading))
    if style == "zenz":
        # zenz：左文脈があるときだけU+EE02を付ける。半角空白を全角へ、改行を除く（改行は上の除去で消える）。
        prompt = (CONTEXT_TAG + context if context else "") + INPUT_TAG + kana + OUTPUT_TAG
        return prompt.replace(" ", "　")
    # jinen：左文脈が空でもU+EE02を付け、プロンプト全体をNFKC正規化する。
    return unicodedata.normalize("NFKC", CONTEXT_TAG + context + INPUT_TAG + kana + OUTPUT_TAG)


class HfBpeTokenizer:
    """jinenの作者が公開した`tokenizer.json`を、HuggingFace tokenizersの手順どおりにtoken化する最小の実装。

    このファイルの設定（added tokenの分離、NFKC、Digits(individual_digits)、byte fallback付きBPE）だけに対応する。
    公式のライブラリを導入せずに照合するためのもので、他の設定のtokenizer.jsonは読めない（読んだら止める）。
    """

    def __init__(self, path: Path):
        spec = json.loads(path.read_text(encoding="utf-8"))
        assert spec["normalizer"] == {"type": "NFKC"}, spec["normalizer"]
        assert spec["pre_tokenizer"] == {"type": "Digits", "individual_digits": True}, spec["pre_tokenizer"]
        model = spec["model"]
        assert model["type"] == "BPE" and model["byte_fallback"] and model["dropout"] is None
        assert model["continuing_subword_prefix"] is None and model["end_of_word_suffix"] is None
        assert not model["ignore_merges"]
        self.vocab: Dict[str, int] = model["vocab"]
        self.unk_id = self.vocab[model["unk_token"]]
        # 結合の優先順位（小さいほど先に結合する）。
        self.ranks: Dict[Tuple[str, str], int] = {}
        for rank, merge in enumerate(model["merges"]):
            a, b = merge if isinstance(merge, list) else merge.split(" ", 1)
            self.ranks.setdefault((a, b), rank)
        assert all(not t["normalized"] for t in spec["added_tokens"])
        self.added: Dict[str, int] = {t["content"]: t["id"] for t in spec["added_tokens"]}
        # 最長一致で探すため、長い順に並べる。
        self.added_by_length = sorted(self.added, key=len, reverse=True)

    def encode(self, text: str) -> List[int]:
        """add_special_tokens=Falseのencodeと同じtoken列を返す（karukanの呼び方と同じ）。"""
        ids: List[int] = []
        for piece, is_added in self._split_added(text):
            if is_added:
                ids.append(self.added[piece])
                continue
            normalized = unicodedata.normalize("NFKC", piece)
            for word in self._split_digits(normalized):
                ids.extend(self._bpe(word))
        return ids

    def _split_added(self, text: str) -> List[Tuple[str, bool]]:
        """added tokenを左から最長一致で切り出す（normalized=Falseのtokenは正規化前の文字列で探す）。"""
        out: List[Tuple[str, bool]] = []
        i = 0
        start = 0
        while i < len(text):
            match = next((t for t in self.added_by_length if text.startswith(t, i)), None)
            if match is None:
                i += 1
                continue
            if start < i:
                out.append((text[start:i], False))
            out.append((match, True))
            i += len(match)
            start = i
        if start < len(text):
            out.append((text[start:], False))
        return out

    @staticmethod
    def _split_digits(text: str) -> List[str]:
        """Digits(individual_digits=True)：数の文字（Rustの`char::is_numeric`、Nd・Nl・No）を1文字ずつ切り離す。"""
        words: List[str] = []
        current = ""
        for ch in text:
            if unicodedata.category(ch) in ("Nd", "Nl", "No"):
                if current:
                    words.append(current)
                    current = ""
                words.append(ch)
            else:
                current += ch
        if current:
            words.append(current)
        return words

    def _bpe(self, word: str) -> List[int]:
        """1語をbyte fallback付きのBPEでtoken化する。語彙に無い文字はUTF-8のbyte token（<0xXX>）へ分ける。"""
        symbols: List[str] = []
        for ch in word:
            if ch in self.vocab:
                symbols.append(ch)
                continue
            byte_tokens = [f"<0x{b:02X}>" for b in ch.encode("utf-8")]
            if all(t in self.vocab for t in byte_tokens):
                symbols.extend(byte_tokens)
            else:
                symbols.append("<unk>")
        while len(symbols) > 1:
            best: Optional[Tuple[int, int]] = None
            for i in range(len(symbols) - 1):
                rank = self.ranks.get((symbols[i], symbols[i + 1]))
                if rank is not None and (best is None or rank < best[0]):
                    best = (rank, i)
            if best is None:
                break
            i = best[1]
            symbols[i : i + 2] = [symbols[i] + symbols[i + 1]]
        return [self.vocab.get(s, self.unk_id) for s in symbols]


@dataclass
class Generation:
    """probeの1要求の結果。"""
    prompt_tokens: int
    ids: List[int]
    generated: int
    termination: str
    ms: float
    output: str
    invalid_utf8: bool


class Probe:
    """stage0_probeを1モデルぶん起動し、1行ずつ要求を送る。同じプロンプトはcacheから返す（端末のLRU cacheに当たる）。"""

    def __init__(self, spec: ModelSpec, max_tokens: int):
        parse_special = "1" if spec.style == "jinen" else "0"
        self.proc = subprocess.Popen(
            [str(PROBE), str(NP / "models" / spec.gguf), parse_special, str(THREADS), str(max_tokens), str(N_CTX)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding="utf-8",
        )
        ready = self.proc.stdout.readline().strip()
        if ready != "#ready":
            raise RuntimeError(f"probe failed to start: {ready}")
        self.cache: Dict[Tuple[str, str], Generation] = {}
        self.requests = 0

    def run(self, cmd: str, prompt: str) -> Generation:
        """要求を1件送る。cmdは`gen`か`tok`。"""
        key = (cmd, prompt)
        if key in self.cache:
            return self.cache[key]
        self.requests += 1
        self.proc.stdin.write(f"{self.requests}\t{cmd}\t{prompt}\n")
        self.proc.stdin.flush()
        cols = self.proc.stdout.readline().rstrip("\n").split("\t")
        raw = bytes.fromhex(cols[7]) if len(cols) > 7 else b""
        try:
            output = raw.decode("utf-8")
            invalid = False
        except UnicodeDecodeError:
            output = raw.decode("utf-8", "replace")
            invalid = True
        result = Generation(
            prompt_tokens=int(cols[1]),
            ids=[int(x) for x in cols[2].split(",")] if cols[2] else [],
            generated=int(cols[3]),
            termination=cols[4],
            ms=float(cols[5]),
            output=output,
            invalid_utf8=invalid,
        )
        self.cache[key] = result
        return result

    def close(self):
        """probeを終了する。"""
        self.proc.stdin.close()
        self.proc.wait(timeout=30)


def strip_marker(text: str) -> str:
    """出力から区切り記号以降を除いた表示用の文字列。"""
    for i, ch in enumerate(text):
        if 0xEE00 <= ord(ch) <= 0xEE0F:
            return text[:i]
    return text


class Converter:
    """端末の`NeuralRangeConverter`のうちモデルへ要求を出す部分だけを、辞書なしで再現する。"""

    def __init__(self, spec: ModelSpec, probe: Probe, log):
        self.spec = spec
        self.probe = probe
        self.log = log

    def surface(self, context: str, chunk: str, kind: str, task_id: str) -> str:
        """保護範囲の表記contextに続く部分範囲chunkを変換した表記を返し、各要求を記録する。"""
        surfaces: List[str] = []
        for text, kana in script_runs(chunk):
            if not kana:
                surfaces.append(text)
                continue
            # 30文字を超えるかなの連なりは30文字ごとに区切る（端末ではMozcの文節境界で区切るため、これは最長の場合）。
            for start in range(0, len(text), MAX_KANA_CHARS):
                piece = text[start : start + MAX_KANA_CHARS]
                left = (context + "".join(surfaces))[-MAX_CONTEXT_CHARS:]
                prompt = build_prompt(self.spec.style, left, piece)
                result = self.probe.run("gen", prompt)
                shown = strip_marker(result.output)
                accepted = result.termination in ("eog", "marker") and shown != "" and not result.invalid_utf8
                surfaces.append(shown if accepted else piece)
                self.log.append({
                    "model": self.spec.key, "kind": kind, "task": task_id, "context": left, "reading": piece,
                    "prompt": prompt, "prompt_tokens": result.prompt_tokens, "ids": result.ids,
                    "generated": result.generated, "termination": result.termination, "ms": result.ms,
                    "output": result.output, "invalid_utf8": result.invalid_utf8,
                })
        return "".join(surfaces)


def load_dev_tasks() -> List[Tuple[str, str]]:
    """開発用の課題（id、読み）を読む。"""
    rows = DEV_TASKS.read_text(encoding="utf-8").splitlines()[1:]
    tasks = []
    for row in rows:
        cols = row.split("\t")
        assert cols[2] == "開発", cols
        tasks.append((cols[0], cols[3]))
    return tasks


def summarize(records: List[dict]) -> dict:
    """要求の記録を、評価条件の段階0で記録する項目へまとめる。同じプロンプトは1要求として数える。"""
    unique: Dict[str, dict] = {}
    for r in records:
        unique.setdefault(r["prompt"], r)
    rows = list(unique.values())
    n = len(rows)
    count = lambda pred: sum(1 for r in rows if pred(r))
    return {
        "requests_all": len(records),
        "requests_unique": n,
        "token_limit": count(lambda r: r["termination"] == "token_limit"),
        "token_limit_rate": count(lambda r: r["termination"] == "token_limit") / n if n else 0,
        "marker_stop": count(lambda r: r["termination"] == "marker"),
        "empty_output": count(lambda r: strip_marker(r["output"]) == "" and r["termination"] != "token_limit"),
        "invalid_utf8": count(lambda r: r["invalid_utf8"]),
        "context_limit_or_error": count(lambda r: r["termination"] in ("context_limit", "decode_failed", "tokenize_failed")),
        "max_prompt_tokens": max((r["prompt_tokens"] for r in rows), default=0),
        "max_generated_tokens": max((r["generated"] for r in rows), default=0),
        "max_reading_chars": max((len(r["reading"]) for r in rows), default=0),
        "max_context_chars": max((len(r["context"]) for r in rows), default=0),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, help="生の記録を置くディレクトリ（Git追跡外）")
    parser.add_argument("--max-tokens", type=int, default=64)
    parser.add_argument("--models", default="ZS,ZX,JS,JX")
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    tasks = load_dev_tasks()
    hf = HfBpeTokenizer(JINEN_TOKENIZER)
    summary = {"max_tokens": args.max_tokens, "models": {}}
    for spec in MODELS:
        if spec.key not in args.models.split(","):
            continue
        probe = Probe(spec, args.max_tokens)
        base: List[dict] = []
        protected: List[dict] = []
        converter_base = Converter(spec, probe, base)
        converter_protected = Converter(spec, probe, protected)
        # 各時点の読みの表記（保護範囲の表記として使う）。
        surface_of: Dict[str, str] = {}
        for task_id, reading in tasks:
            for state in typing_states(reading):
                surface_of[state] = converter_base.surface("", state, "base", task_id)
        for task_id, reading in tasks:
            for state in typing_states(reading):
                for split in range(1, len(state)):
                    prefix = state[:split]
                    if prefix not in surface_of:
                        surface_of[prefix] = converter_base.surface("", prefix, "prefix", task_id)
                    converter_protected.surface(surface_of[prefix], state[split:], "protected", task_id)
        probe.close()
        records = base + protected
        with (out / f"requests-{spec.key}-max{args.max_tokens}.jsonl").open("w", encoding="utf-8") as f:
            for r in records:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        model_summary = {
            "base": summarize([r for r in base if r["kind"] == "base"]),
            "all": summarize(records),
        }
        if spec.style == "jinen":
            prompts = sorted({r["prompt"] for r in records})
            ids_by_prompt = {r["prompt"]: r["ids"] for r in records}
            mismatches = []
            for prompt in prompts:
                expected = hf.encode(prompt)
                if expected != ids_by_prompt[prompt]:
                    mismatches.append({"prompt": prompt, "tokenizer_json": expected, "llama_cpp": ids_by_prompt[prompt]})
            model_summary["tokenizer"] = {"prompts": len(prompts), "mismatches": len(mismatches)}
            with (out / f"tokenizer-mismatch-{spec.key}.jsonl").open("w", encoding="utf-8") as f:
                for m in mismatches:
                    f.write(json.dumps(m, ensure_ascii=False) + "\n")
        summary["models"][spec.key] = model_summary
        print(spec.key, json.dumps(model_summary, ensure_ascii=False), file=sys.stderr, flush=True)
    (out / f"summary-max{args.max_tokens}.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1))
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
