# Mozc UT辞書（sudachidict、personal-names、place-names）の公開データを、Mozc固定commitの辞書形式へ変換する。
# 上流の merge_dictionaries.py の手順（表記の正規化、Mozc既存語との重複除去、ID付け直し、コスト調整）を、
# jawiki（CC BY-SA）の索引を使わずに行う。上流のjawikiヒット数0の場合の規則をすべての語に当てる。
# 使い方: python3 -I make_ut_dictionary.py <dictionary_ossのdir> <出力txt> <統計json> [--sudachi-cost-base=N]
#        [--sudachi-drop-name-like] [--sudachi-drop-known-reading=N] name=path ...
# 選択肢は調整の案（tuning/CRITERIA.md）に対応し、sudachidictの語だけに効く。
import collections
import json
import re
import sys
import unicodedata
from pathlib import Path

# 辞書ごとに付ける品詞（id.defの品詞名）と、同じ読み・表記が複数の辞書にあるときの優先順（上流のcat順と同じ）。
# 公開データはlid/ridが0000で品詞が失われているため、辞書の種類から品詞を決める。
POS_BY_DICT = {
    "personal-names": "名詞,固有名詞,人名,一般,*,*,*",
    "place-names": "名詞,固有名詞,地域,一般,*,*,*",
    "sudachidict": "名詞,一般,*,*,*,*,*",
}
PRIORITY = ["personal-names", "place-names", "sudachidict"]

# 上流のadjust_entries.pyと同じく、読みはひらがなと長音だけを許す。
RE_YOMI = re.compile(r"[ぁ-ゔー]+")


def load_pos_ids(id_def: Path) -> dict:
    """id.defの「ID 品詞名」の行から、品詞名→IDの対応を作る。"""
    ids = {}
    for line in id_def.read_text(encoding="utf-8").splitlines():
        num, name = line.split(" ", 1)
        ids.setdefault(name, int(num))
    return ids


def normalize_value(value: str) -> str:
    """上流のnormalize_entryと同じ表記の正規化（NFKCと「~」→「〜」）。"""
    return unicodedata.normalize("NFKC", value).replace("~", "〜")


def load_mozc_entries(dict_dir: Path):
    """Mozcのdictionary0*.txtから、（読み、正規化した表記）の組、読みごとの表記の集合、姓（人名,姓）の（読み、表記）を返す。"""
    pairs = set()
    values_by_yomi = collections.defaultdict(set)
    surnames = set()
    for path in sorted(dict_dir.glob("dictionary0*.txt")):
        for line in path.read_text(encoding="utf-8").splitlines():
            cols = line.split("\t")
            value = normalize_value(cols[4])
            pairs.add((cols[0], value))
            values_by_yomi[cols[0]].add(value)
            if cols[1] == str(SURNAME_ID):
                surnames.add((cols[0], value))
    return pairs, values_by_yomi, surnames


def starts_with_surname(yomi: str, value: str, surnames_by_value: dict) -> bool:
    """表記と読みの先頭が、ある姓の表記と読みに一致し、両方に残りがあるか（姓＋名などの人名らしい語の判定）。"""
    for n in range(1, len(value)):
        for s_yomi in surnames_by_value.get(value[:n], ()):
            if yomi.startswith(s_yomi) and len(yomi) > len(s_yomi):
                return True
    return False


# id.defの「名詞,固有名詞,人名,姓」のID（Mozc commit 13c98988）。案Bの姓の判定に使う。
SURNAME_ID = 1923


def ut_cost(original: int, base: int = 9000) -> int:
    """上流のapply_jawiki_hitで、jawikiのヒット数が0の語に当てるコスト（既定は9000台）。案Aではbaseを上げる。"""
    return base + original // 20


def convert_entry(name: str, cols: list, stats: collections.Counter, cost_base: int = 9000):
    """一行を検査して（読み、表記、コスト）を返す。除外した行は理由を数えてNoneを返す。"""
    if len(cols) != 5 or not cols[3].isdigit():
        stats[f"{name}:除外:列の形式"] += 1
        return None
    yomi, _, _, cost, value = cols
    value = normalize_value(value)
    if not RE_YOMI.fullmatch(yomi):
        stats[f"{name}:除外:読みがひらがな以外"] += 1
        return None
    if len(value) < 2 or len(value) > 25:
        stats[f"{name}:除外:表記の長さ"] += 1
        return None
    if any(unicodedata.category(ch).startswith("C") for ch in value):
        stats[f"{name}:除外:制御文字"] += 1
        return None
    if value.isascii():
        # 上流はjawikiに無い英数字だけの表記を捨てる。jawikiを使わないため、英数字だけの表記はすべて捨てる。
        stats[f"{name}:除外:英数字だけの表記"] += 1
        return None
    return yomi, value, ut_cost(int(cost), cost_base)


def main():
    dict_dir, out_path, stats_path = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    opts = [a for a in sys.argv[4:] if a.startswith("--")]
    sources = dict(a.split("=", 1) for a in sys.argv[4:] if not a.startswith("--"))
    cost_base = next((int(o.split("=")[1]) for o in opts if o.startswith("--sudachi-cost-base=")), 9000)
    drop_name_like = "--sudachi-drop-name-like" in opts
    known_reading_min = next((int(o.split("=")[1]) for o in opts if o.startswith("--sudachi-drop-known-reading=")), None)
    pos_ids = load_pos_ids(dict_dir / "id.def")
    assert pos_ids["名詞,固有名詞,人名,姓,*,*,*"] == SURNAME_ID
    mozc_pairs, mozc_values_by_yomi, surnames = load_mozc_entries(dict_dir)
    surnames_by_value = collections.defaultdict(list)
    for s_yomi, s_value in surnames:
        surnames_by_value[s_value].append(s_yomi)
    # 案B: personal-names、place-namesの表記（読みを問わない）。sudachidictより先に読むため、ここで集める。
    name_values = set()
    if drop_name_like:
        for name in ("personal-names", "place-names"):
            for line in Path(sources[name]).read_text(encoding="utf-8").splitlines():
                name_values.add(normalize_value(line.split("\t")[4]))
    stats = collections.Counter()

    # （読み、表記）→（優先順、コスト、辞書名）。同じ組は優先順の高い辞書、次にコストの低いものを残す。
    best = {}
    for name in PRIORITY:
        for line in Path(sources[name]).read_text(encoding="utf-8").splitlines():
            stats[f"{name}:入力行"] += 1
            entry = convert_entry(name, line.split("\t"), stats, cost_base if name == "sudachidict" else 9000)
            if entry is None:
                continue
            yomi, value, cost = entry
            if name == "sudachidict" and drop_name_like and (
                    value in name_values or starts_with_surname(yomi, value, surnames_by_value)):
                stats[f"{name}:除外:人名・地名らしい語（案B）"] += 1
                continue
            if name == "sudachidict" and known_reading_min is not None and \
                    len(mozc_values_by_yomi.get(yomi, ())) >= known_reading_min:
                stats[f"{name}:除外:既存の読みに表記が{known_reading_min}種類以上（案C）"] += 1
                continue
            if (yomi, value) in mozc_pairs:
                stats[f"{name}:除外:Mozc既存語と重複"] += 1
                continue
            key = (yomi, value)
            cand = (PRIORITY.index(name), cost, name)
            if key in best:
                stats[f"{name}:除外:UT辞書内で重複"] += 1
                best[key] = min(best[key], cand)
            else:
                best[key] = cand

    lines = []
    for (yomi, value), (_, cost, name) in sorted(best.items()):
        pos_id = pos_ids[POS_BY_DICT[name]]
        lines.append(f"{yomi}\t{pos_id}\t{pos_id}\t{cost}\t{value}\n")
        stats[f"{name}:出力"] += 1
    out_path.write_text("".join(lines), encoding="utf-8")
    stats["出力合計"] = len(lines)
    result = {"options": opts, "pos_ids": {n: pos_ids[p] for n, p in POS_BY_DICT.items()}, "counts": dict(sorted(stats.items()))}
    stats_path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
