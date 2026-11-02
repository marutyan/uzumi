# 絵文字・顔文字一覧のデータ

## 元データとライセンス

生成元はMozc commit `13c98988247aa711d99db9e348ec2a597d14b5cd` の `emoji_data.tsv` と `categorized.tsv` です。再生成に必要な元ファイルをリポジトリ内へコピーし、固定SHA-256で変更を検出します。

| 元ファイル | bytes | SHA-256 |
| --- | ---: | --- |
| `third_party/mozc/data/emoji/emoji_data.tsv` | 305,347 | `91e8d93f5e71b7fd821b30c8d72a7f5f22214fab5d8157d009600b304fe0cf08` |
| `third_party/mozc/data/emoticon/categorized.tsv` | 10,117 | `4497c16a706de418b05e73eaddbce13e5d3390e7c2de71200b28c0c97ae5c4fc` |

[配布監査](mozc-distribution-audit.md)では、絵文字データがUnicode CLDR 48の日本語注釈に基づくという強い推測と、[NOTICE](../third_party/mozc/NOTICE.txt)へUnicode License v3を収録した経緯を記録しています。顔文字データはMozc管理データとして、同NOTICEのBSD 3-Clause表示に含まれます。

## 再生成

リポジトリ直下で次を実行します。Python 3の標準ライブラリだけを使います。

```sh
python3 tools/generate_emoji_picker_data.py
python3 tools/generate_emoji_picker_data.py --check
```

生成先は `app/src/main/assets/emoji/emoji.json` です。生成器は入力ごとにSHA-256を照合し、`--check`では生成結果とassetの全バイトを比較します。タブのカテゴリはMozcの日本語説明・読みと符号位置から分類し、顔文字のカテゴリは元TSVの値を使います。

## 件数と圧縮量

assetは2,107項目（絵文字1,918件、重複を除いた顔文字189件）、173,047 bytesです。Mozcの元TSVに肌色修飾子を含む絵文字はなく、生成器も変種を追加しません。実行時は `Paint.hasGlyph` が端末のフォントにない絵文字を除くため、表示件数は端末によって変わります。

ZIPのdeflate圧縮レベル6で圧縮すると、asset本体は20,918 bytes、同assetだけを含むZIPアーカイブは21,062 bytesです。未圧縮時より152,129 bytes小さくなります。これはZIP圧縮の見積もりで、APKの実増分は未確認です。
