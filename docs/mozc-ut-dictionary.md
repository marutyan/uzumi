# Mozc UT追加辞書入りの mozc.data

2026-11-04、候補が他のIMEより少ないという問題に対して、Mozc固定commit `13c98988247aa711d99db9e348ec2a597d14b5cd` の `//data_manager/oss:mozc.data` に Mozc UT の追加辞書3つを加えた。生成物はGitへ入れず、`.local-build/mozc-ut/artifacts/` に置き、ビルド時に `-Puzumi.mozcArtifactsDir` でこのディレクトリを指定する。`native_libs.zip` は `.local-build/mozc-probe/artifacts/` のものと同じ（SHA-256 `b04cbb1d…3934c`）。

## 入力

| 辞書 | commit | データファイル SHA-256 | ライセンス |
| --- | --- | --- | --- |
| mozcdic-ut-sudachidict | `13129ddebe1cb8eabb63feccbe157adec4e1de01` | `1b9555338cc36606a5f497a2f57a0f9b948fa02d8c13d18ee9cfb5c99fb9d86e` | Apache-2.0（SudachiDict。UniDicのBSD-3とNEologdの表示を含む） |
| mozcdic-ut-personal-names | `3da38e0187c5db754c420a5733ae95640652323c` | `fa542ba4a2105af1f86608fa08721c4d1af298a1330e90c7e3046a4a1239f6c1` | Apache-2.0（元データの出典は不明） |
| mozcdic-ut-place-names | `7e2b4422837846ea2e78d1a34856d95001d5941a` | `addad60898216af8a1736fdef7508ec7e60e709979044ad2f40609959401c688` | 日本郵便の郵便番号データ（著作権を主張しない） |

取得したのはデータファイルだけで、上流のスクリプトは実行していない。Wikipedia（jawiki、CC BY-SA）由来のデータとコストは使っていない。第三者表示は `third_party/mozc/NOTICE.txt` の第11節〜第13節に収録した。

## 変換

`tools/mozc_ut/make_ut_dictionary.py` で、3辞書を Mozc の辞書形式の `dictionary_ut.txt` に変換する。上流の `merge_dictionaries.py` の手順から、jawikiの索引を使う部分を除いたものである。

- 表記はNFKCで正規化し、「~」を「〜」にする。表記が2〜25文字でないもの、読みにひらがなと長音以外を含むもの、英数字だけの表記は除く。
- Mozcの `dictionary0*.txt` と同じ（読み、正規化した表記）の組は除く。3辞書の間で重複する組は、personal-names、place-names、sudachidictの順に一つだけ残す。
- 公開データの品詞IDは0000なので、辞書の種類から品詞を付ける。sudachidictは `名詞,一般`、personal-namesは `名詞,固有名詞,人名,一般`、place-namesは `名詞,固有名詞,地域,一般`。
- コストは、personal-namesとplace-namesが `9000 + 元のコスト // 20`、sudachidictが `10000 + 元のコスト // 20`（`--sudachi-cost-base=10000`）。

Mozcのソースへの変更は、`dictionary_ut.txt` を `src/data/dictionary_oss/` に置き、`BUILD.bazel` へ1行足すことだけである（`tools/mozc_ut/mozc-source.diff`）。

## ビルド

```
bazel --output_user_root=<作業dir>/bazel-cache build //data_manager/oss:mozc.data \
  --config oss_macos --config release_build --host_copt=-faligned-allocation --copt=-faligned-allocation \
  --repository_cache=<作業dir>/bazel-cache/cache/repos
```

辞書を足す前に同じ手順でビルドした `mozc.data` は、既存の `.local-build/mozc-probe/artifacts/mozc.data`（SHA-256 `720d0cb4…5d70`）と一致した。採用した版は2回ビルドし、SHA-256が一致した。

| 生成物 | bytes | SHA-256 |
| --- | ---: | --- |
| `mozc.data`（追加前） | 18,994,682 | `720d0cb42651fe35692578ea5f3c752940b8357fc24efc46230c904d2f5d5d70` |
| `mozc.data`（採用、案A1） | 35,345,446 | `8234a7de58abf8a72f2e89eb6c5ad3f3591045d35bdfef0e69947bf2d2ad0eb8` |

`dictionary_ut.txt` は1,124,047行（sudachidict 875,406、place-names 170,077、personal-names 78,564）。辞書の語数は、追加前の1,436,006語から2,560,053語になった。

## 案の選び方

sudachidictの扱いだけを変えた7案を、開発用の集合（Phase 3aの開発用30文、Mozc同梱のquality regression、前回の15語）で比べた。試験用A（Phase 2cの54文）と試験用B（AJIMEE-Bench）は使っていない。案と、結果を見る前に固定した選び方は `tools/mozc_ut/selection-criteria.md` にある。

固定した選び方では案C1になったが、結果を見た後にユーザーが選び方を変え、案A1を採った。C1に劣った点はVN02（雪が降る）1文の改善を失ったことだけで、oss、myoji、前回の悪化の回復ではA1が上回り、C1はmyojiのdesktopで追加前を下回ったためである。選び方を後から変えた事実は、同じファイルに追記してある。

A1の結果（合格数、追加前→A1）は、oss 368→381（desktop、mobileとも381件中）、regression 113/113→114/115、myoji 41,201/44,802→43,695/48,302、開発用22文 13→13。15語の上位10件への割り込みは2件（ほけん:母健、すいません:す今仙）。残る悪化は、たなからぼたもち（desktop、mobile）、あめふる（desktop）、myojiのdesktop 517件とmobile 8件である。

辞書を変えたので、研究の評価は基準線を含む全条件を新しい辞書で測り直す。追加前の辞書による結果とは混ぜない。
