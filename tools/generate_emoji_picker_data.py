#!/usr/bin/env python3
"""Mozcの固定データから絵文字・顔文字選択画面のassetを再生成する。

入力はMozc commit 13c98988247aa711d99db9e348ec2a597d14b5cdの
emoji_data.tsvとcategorized.tsvで、両方のSHA-256を検査する。
Python 3の標準ライブラリだけを使い、リポジトリ直下で
`python3 tools/generate_emoji_picker_data.py`を実行する。
生成結果の一致を調べるときは`--check`を付ける。
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path


# スクリプトの場所からリポジトリ直下を求め、実行時の作業場所に依存させない。
ROOT = Path(__file__).resolve().parents[1]

# Mozcの配布監査で確認した固定commitと入力ファイルのSHA-256。
SOURCE_COMMIT = "13c98988247aa711d99db9e348ec2a597d14b5cd"

# 絵文字データの入力パスをMozcのディレクトリ構造に合わせて固定する。
EMOJI_SOURCE = Path("third_party/mozc/data/emoji/emoji_data.tsv")

# Mozcのカテゴリ付き顔文字データの入力パスを固定する。
EMOTICON_SOURCE = Path("third_party/mozc/data/emoticon/categorized.tsv")

# 生成JSONの出力先をAndroid assets内に固定する。
OUTPUT = Path("app/src/main/assets/emoji/emoji.json")

# ソースの由来確認に使う既知のSHA-256。
EXPECTED_HASHES = {
    EMOJI_SOURCE: "91e8d93f5e71b7fd821b30c8d72a7f5f22214fab5d8157d009600b304fe0cf08",
    EMOTICON_SOURCE: "4497c16a706de418b05e73eaddbce13e5d3390e7c2de71200b28c0c97ae5c4fc",
}

# 画面に出す絵文字カテゴリの順と読み上げ名を固定する。
EMOJI_CATEGORIES = [
    ("face", "顔", "顔の絵文字"),
    ("people", "人・手", "人と身体の絵文字"),
    ("animal", "動物", "動物の絵文字"),
    ("food", "食べ物", "食べ物と飲み物の絵文字"),
    ("nature", "自然", "自然と天気の絵文字"),
    ("activity", "活動", "スポーツと活動の絵文字"),
    ("travel", "場所", "乗り物と場所の絵文字"),
    ("objects", "物", "道具と物の絵文字"),
    ("symbols", "記号", "記号の絵文字"),
    ("flags", "旗", "旗の絵文字"),
]

# 顔文字データのカテゴリ名を画面用のID、ラベル、読み上げ名へ写す。
KAOMOJI_CATEGORIES = {
    "SMILE": ("kaomoji_smile", "顔文字・笑", "笑顔の顔文字"),
    "SADNESS": ("kaomoji_sadness", "顔文字・悲", "悲しみの顔文字"),
    "SWEAT": ("kaomoji_sweat", "顔文字・汗", "汗の顔文字"),
    "DISPLEASURE": ("kaomoji_displeasure", "顔文字・不満", "不満の顔文字"),
    "SURPRISE": ("kaomoji_surprise", "顔文字・驚", "驚きの顔文字"),
}

# Mozcの日本語説明から絵文字カテゴリを判定する語をカテゴリごとに固定する。
CATEGORY_KEYWORDS = {
    "face": ("顔", "笑顔", "表情", "にこ", "泣", "涙", "ウインク", "キス", "口", "舌", "ほほえみ"),
    "people": (
        "人", "手", "足", "身体", "体", "赤ちゃん", "家族", "男", "女", "職業", "医者", "警察", "消防", "学生", "妊娠",
    ),
    "animal": (
        "動物", "犬", "猫", "鳥", "魚", "虫", "馬", "牛", "熊", "猿", "象", "イルカ", "くじら", "蛇", "爬虫類", "亀",
        "ねずみ", "昆虫", "狐", "リス", "ペンギン", "パンダ",
    ),
    "food": (
        "食べ物", "料理", "果物", "野菜", "パン", "寿司", "すし", "ごはん", "ラーメン", "飲み物", "飲料", "コーヒー",
        "ビール", "ワイン", "ケーキ", "菓子", "肉", "ピザ",
    ),
    "nature": (
        "花", "植物", "木", "草", "葉", "天気", "太陽", "月", "星", "地球", "宇宙", "雨", "雪", "雲", "火", "水", "風",
        "雷", "山", "海", "波", "火山",
    ),
    "activity": (
        "スポーツ", "競技", "ボール", "ゲーム", "遊び", "音楽", "楽器", "舞台", "ダンス", "メダル", "演奏", "芸術", "釣り",
        "スキー", "サーフィン",
    ),
    "travel": (
        "車", "電車", "列車", "飛行機", "船", "交通", "旅行", "旅", "場所", "建物", "建築", "駅", "道路", "地図", "信号",
        "バス", "自転車", "タクシー",
    ),
    "objects": (
        "道具", "時計", "電話", "コンピュータ", "鍵", "本", "バッグ", "照明", "衣服", "服", "機器", "家電", "家具", "郵便",
        "カメラ", "電池", "電球", "封筒",
    ),
}

# Mozcの説明で分類できない絵文字を符号位置範囲で補って分類する。
CODE_POINT_RANGES = {
    "face": ((0x1F600, 0x1F64F), (0x1F910, 0x1F92F), (0x1F970, 0x1F97A), (0x1F9D0, 0x1F9D0)),
    "animal": ((0x1F400, 0x1F43F), (0x1F980, 0x1F9AE)),
    "food": ((0x1F330, 0x1F37F), (0x1F950, 0x1F96F), (0x1F9C0, 0x1F9CB), (0x1FAD0, 0x1FADF)),
    "nature": ((0x1F300, 0x1F32F),),
    "activity": ((0x1F3A0, 0x1F3FF), (0x1F93C, 0x1F945)),
    "travel": ((0x1F680, 0x1F6FF),),
    "objects": ((0x1F4A0, 0x1F5FF),),
}

# 異体字セレクタ、ZWJ、keycap結合記号をカテゴリ判定から除く。
IGNORED_CODE_POINTS = {0xFE0E, 0xFE0F, 0x200D, 0x20E3}

# CLDR由来の絵文字とMozc管理の顔文字を区別できるよう、形式をassetへ記録する。
ASSET_FORMAT_VERSION = 1


# ファイルを読み、生成スクリプトが期待するSHA-256と一致することを確かめる。
def read_pinned_source(path: Path) -> str:
    content = (ROOT / path).read_bytes()
    actual = hashlib.sha256(content).hexdigest()
    expected = EXPECTED_HASHES[path]
    if actual != expected:
        raise ValueError(f"{path} のSHA-256が固定値と異なります: {actual}")
    return content.decode("utf-8")


# Unicodeの日本語範囲を含む語を選び、TalkBackで使う短い説明名を作る。
def has_japanese(text: str) -> bool:
    return any(
        "\u3040" <= character <= "\u30ff"
        or "\u3400" <= character <= "\u9fff"
        or "\uf900" <= character <= "\ufaff"
        for character in text
    )


# Mozcの日本語名、説明語、読みの順で使える短いラベルを探す。
def make_label(japanese_name: str, description: str, reading: str) -> str:
    if japanese_name and japanese_name != "絵文字":
        return japanese_name
    for source in (description, reading):
        for word in source.split():
            if has_japanese(word):
                return word
    return "絵文字"


# 符号位置またはMozcの説明語から絵文字のカテゴリを一つ決める。
def emoji_category(code_points: list[int], searchable_text: str) -> str:
    # カテゴリ判定でだけ修飾子を外し、parse_emojisは元の絵文字列をそのままassetへ残す。
    points = [point for point in code_points if point not in IGNORED_CODE_POINTS and not 0x1F3FB <= point <= 0x1F3FF]
    base = points[0] if points else 0
    if any(0x1F1E6 <= point <= 0x1F1FF for point in points) or base in (0x1F3F3, 0x1F3F4):
        return "flags"
    if any(start <= base <= end for start, end in CODE_POINT_RANGES["face"]):
        return "face"
    for category, keywords in CATEGORY_KEYWORDS.items():
        if any(keyword in searchable_text for keyword in keywords):
            return category
    for category, ranges in CODE_POINT_RANGES.items():
        if category == "face":
            continue
        if any(start <= base <= end for start, end in ranges):
            return category
    return "symbols"


# 絵文字TSVの7列を読み、カテゴリと読み上げラベルを付けた項目へ変換する。
def parse_emojis(source: str) -> list[dict[str, object]]:
    items = []
    seen = set()
    for line_number, line in enumerate(source.splitlines(), start=1):
        if not line or line.startswith("#"):
            continue
        columns = line.split("\t")
        if len(columns) != 7:
            raise ValueError(f"emoji_data.tsv:{line_number} は7列ではありません")
        code_points = [int(part, 16) for part in columns[0].split()]
        value = columns[1]
        if not value or value in seen:
            continue
        seen.add(value)
        reading, unicode_name, japanese_name, description, _version = columns[2:]
        label = make_label(japanese_name, description, reading)
        searchable_text = " ".join((unicode_name, japanese_name, description, reading))
        items.append({
            "value": value,
            "label": label,
            "categories": [emoji_category(code_points, searchable_text)],
            "kind": "emoji",
        })
    return items


# 顔文字TSVのカテゴリと説明を保持し、複数カテゴリを持つ行は各タブに表示する。
def parse_kaomoji(source: str) -> list[dict[str, object]]:
    rows = list(csv.reader(source.splitlines(), delimiter="\t"))
    if not rows or rows[0] != ["# value", "category", "description"]:
        raise ValueError("categorized.tsvの列名が想定と異なります")
    items = []
    seen = set()
    for row in rows[1:]:
        if not row or all(not column for column in row):
            continue
        if len(row) != 3:
            raise ValueError("categorized.tsvに3列でない行があります")
        value, category, description = row
        if not value or value in seen:
            continue
        seen.add(value)
        source_categories = category.split()
        categories = [KAOMOJI_CATEGORIES[name][0] for name in source_categories if name in KAOMOJI_CATEGORIES]
        items.append({
            "value": value,
            "label": make_label("", description, ""),
            "categories": categories,
            "kind": "kaomoji",
        })
    return items


# 固定順のカテゴリ、由来情報、重複を除いた項目を安定したJSONへまとめる。
def make_asset(emoji_source: str, emoticon_source: str) -> bytes:
    emoji_items = parse_emojis(emoji_source)
    kaomoji_items = parse_kaomoji(emoticon_source)
    categories = [
        {"id": category_id, "label": label, "spoken": spoken}
        for category_id, label, spoken in EMOJI_CATEGORIES
    ]
    categories.extend(
        {"id": category_id, "label": label, "spoken": spoken}
        for category_id, label, spoken in KAOMOJI_CATEGORIES.values()
    )
    payload = {
        "formatVersion": ASSET_FORMAT_VERSION,
        "sourceCommit": SOURCE_COMMIT,
        "sourceSha256": {path.name: digest for path, digest in EXPECTED_HASHES.items()},
        "categories": categories,
        "items": emoji_items + kaomoji_items,
    }
    return (json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


# 固定入力からassetを作り、書込みまたは再生成一致の確認を実行する。
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="生成済みassetとの一致だけを確認する")
    arguments = parser.parse_args()
    try:
        generated = make_asset(read_pinned_source(EMOJI_SOURCE), read_pinned_source(EMOTICON_SOURCE))
    except (OSError, UnicodeError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    output = ROOT / OUTPUT
    if arguments.check:
        if not output.exists() or output.read_bytes() != generated:
            print(f"{OUTPUT} が再生成結果と一致しません", file=sys.stderr)
            return 1
        print(f"一致: {OUTPUT} ({len(generated)} bytes)")
        return 0
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(generated)
    print(f"生成: {OUTPUT} ({len(generated)} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
