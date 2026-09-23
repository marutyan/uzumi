# Mozc Android ローカルビルド試作

2026-09-23、公式`google/mozc`のcommit [`13c98988247aa711d99db9e348ec2a597d14b5cd`](https://github.com/google/mozc/tree/13c98988247aa711d99db9e348ec2a597d14b5cd)を固定し、リポジトリ内の追跡対象外領域`.local-build/mozc-probe/`だけで試作した。端末への組込み、かな漢字変換の実行、変換品質の評価はまだ行っていない。実行ログと生成物は同領域に置き、アプリのAPK・既存SDK・global PATHは変更していない。

## 結果

| 生成物 | サイズ | SHA-256 | 確認範囲 |
| --- | ---: | --- | --- |
| `artifacts/native_libs.zip` | 15,753,780 bytes | `b04cbb1dbb0ec198cef85be8f74f967ba1b6bb2abbb7c1f28edfee234a63934c` | 公式[Androidビルド手順](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/docs/build_mozc_for_android.md)の`bazel build package --config oss_android --config release_build --jobs=6`が成功。ZIP検査に合格。4 ABIの`libmozc.so`を含む。 |
| `artifacts/arm64-v8a/libmozc.so` | 16,182,000 bytes | `05e757129db76bfc66db0224b3cfa9b7d8bc1e0ccd986919a5b66a251128aeee` | ELF aarch64。3本のLOAD segmentのalignmentは各`0x4000`。 |
| `artifacts/mozc.data` | 18,994,682 bytes | `720d0cb42651fe35692578ea5f3c752940b8357fc24efc46230c904d2f5d5d70` | 先頭magicは`EF 4D 4F 5A 43 0D 0A`。辞書としての端末動作は未検証。 |
| `artifacts/libcommands_proto-lite.jar` | 295,977 bytes | `b0b9497de0c9fc6f1f52f2069d856facc9d2401fdb1b942f82360b005634065a` | Java lite生成物。runtimeとの結合は未検証。 |
| `artifacts/commands_proto-lite-src.jar` | 1,141,258 bytes | `d2104e904ee23e1f36f3477c7a71c6c66c6e22491def9750094e32949be60840` | `ProtoCommands.java`を含む。 |

`native_libs.zip`にはarm64-v8a、armeabi-v7a、x86、x86_64のライブラリが入る。Bazel 9.0.2（60,272,019 bytes、SHA-256 `525cfcbf9790af7319ea78c9ff2053b8a5634013c41783440b5d82433f14d280`）とNDK r29（zip 1,049,519,838 bytes、SHA-256 `ce5e4b100ec5fe5be4eb3edcb2c02528824ff9cda3860f5304619be6c3da34d3`）は取得後に照合した。[`update_deps.py`](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/build_tools/update_deps.py)のdry-runで、`--noqt --noninja`時の取得対象がNDKだけであり、展開先が専用checkout内であることも確認した。

Android設定でnative・辞書・Java liteを同時に要求すると、辞書のhost生成ツール`//base:codegen_bytearray_stream`が`@platforms//:incompatible`となり解析で失敗した。nativeは上記のAndroid `package` targetで成功。辞書とJava liteはMac host設定に分け、`--host_copt=-faligned-allocation --copt=-faligned-allocation`を付けて成功した。host側Clangがaligned allocationを拒否したためのオプションであり、ソース変更はない。`--macos_minimum_os=11.0`と`-mmacosx-version-min=11.0`だけの試行は失敗した。成功したAndroid buildは357.781秒、辞書・Java buildは55.393秒。詳細は専用領域の`RESULTS.md`と`logs/`にある。

ビルド時の作業ディレクトリは`$probe/mozc/src`、`probe`は上記専用領域の絶対パス。成功したビルド命令は次の2つ。生成物は`bazel-bin/android/jni/native_libs.zip`、`bazel-bin/data_manager/oss/mozc.data`、`bazel-bin/protocol/libcommands_proto-lite.jar`に出る。検証用コピーは`$probe/artifacts/`に置いた。

```sh
cd "$probe/mozc/src"
"$probe/bin/bazel" --output_user_root="$probe/bazel-cache" build package --config oss_android --config release_build --jobs=6
"$probe/bin/bazel" --output_user_root="$probe/bazel-cache" build --config oss_macos --config release_build --host_copt=-faligned-allocation --copt=-faligned-allocation --jobs=6 //data_manager/oss:mozc.data //protocol:commands_java_proto_lite
```

作業領域のディスク使用量は終了時約6.9 GiB。経過時間は約18分で、1時間の上限内だった。通信量上限は20 GiBだが、BazelとNDK zipの確認済み取得量1,109,791,857 bytes以外に、Mozc cloneとBazelの推移依存の取得がある。**総通信量は未計測で、20 GiB以内だったかは検証できない**。ディスク使用量やBazel cacheの容量を通信量とはみなさない。

## 配布前のライセンス確認

公式commitの[`LICENSE`](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/LICENSE)ではMozc本体はBSD 3-Clause系の条件で、バイナリ配布時には著作権表示・条件・免責条項を添付する。辞書は単一ライセンスではない。[`dictionary_oss/README.txt`](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/data/dictionary_oss/README.txt)と`LICENSE`にはIPAdic/NAIST、ICOT、沖縄辞書等の条件が載る。[`MODULE.bazel`](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/MODULE.bazel)で固定された`japanese-usage-dictionary`の`2025-01-25`タグも、辞書生成に使われる。取得済み固定tagの[`LICENSE`](https://github.com/hiroyuki-komatsu/japanese-usage-dictionary/blob/2025-01-25/LICENSE)本文はBSD 2-Clauseの2条件である。

**2026-09-23追記：** 以下の残件は[配布物監査](mozc-distribution-audit.md)で解消した。郵便番号（住所・事業所）は実際に`mozc.data`へ入り、日本郵便は著作権を主張しない。`libmozc.so`の静的リンク閉包とJava lite runtime（`protobuf-javalite` 4.34.1）を確定し、APKに添付する[`NOTICE.txt`](../third_party/mozc/NOTICE.txt)を作成した。本人用debug APKへの同梱はNOTICE添付を条件に可と判定した。以下の2段落は監査前の記録として残す。

郵便番号データは特に要確認。READMEは「OSS版に含まない」と書く一方、同じcommitの[`dictionary_oss/BUILD.bazel`](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/data/dictionary_oss/BUILD.bazel)は`//dictionary:zip_code_data`を基礎辞書に含める。`MODULE.bazel`は日本郵便データのsnapshotを固定している。この食い違いを解消し、実際の生成物に何が入り、どの表示・再配布条件が掛かるかを確定する必要がある。[`mozc.data`のBUILD](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/data_manager/oss/BUILD.bazel)にはemoji、顔文字、記号等の入力もあり、これらも同じ監査対象とする。

`libmozc.so`のAbseil/protobuf等の実リンク閉包、Java protobuf lite runtimeの版と配布条件も未確認。buildに成功したことを、APKへの同梱許諾が確定したこととは扱わない。NOTICEと配布物の棚卸しを完了するまで、APKへ生成物を組み込まない。

## 次の検証

配布物のライセンス・NOTICEとJava lite runtimeを確定し、その後に最小JNIブリッジで端末上のsession作成、読み入力、候補、確定、破棄を試験する。`getDataVersion()`と既知のかな漢字変換例で、辞書不在時のminimal engineへのfallbackを検出する。続いて接続切替、機密欄、低メモリ、連続入力を評価する。現時点の成果は**ローカルビルドの成立**に限る。
