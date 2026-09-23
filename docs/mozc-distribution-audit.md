# Mozc由来資産の配布物監査

確認日：2026-09-23。対象は公式`google/mozc`のcommit [`13c98988247aa711d99db9e348ec2a597d14b5cd`](https://github.com/google/mozc/tree/13c98988247aa711d99db9e348ec2a597d14b5cd)から[ローカルビルド試作](mozc-build-probe.md)で作った生成物と、Java lite生成コードが必要とするprotobuf runtimeである。ここでの判定は本人用debug APKへの同梱を対象とし、一般公開の可否は判断しない。法的助言ではなく、一次資料とビルドグラフの照合結果である。

## 結論

**本人用debug APKへの同梱は条件付きで可。** 条件は、[`third_party/mozc/NOTICE.txt`](../third_party/mozc/NOTICE.txt)をAPKへ添付することの一つだけである。同梱を止める条件の資産はない。

- 郵便番号データは実際に`mozc.data`へ入る。READMEの「OSS版に含まない」は古い記述で、固定commitのBUILDとビルド結果が正しい。事業所データを含め、日本郵便は著作権を主張せず、再配布に許諾は要らないと公式に明記している。除外版の再ビルドは不要と判断し、実施していない。
- `emoji_data.tsv`はUnicode CLDR 48の日本語注釈に基づくと判断し、Unicode License v3の表示をNOTICEに入れた。生成スクリプトが非公開なので、この由来は強い根拠のある推測である。
- `libmozc.so`に静的リンクされる第三者コードは、Abseil、Protocol Buffers（C++）、utf8_range、LLVM libc++/libc++abi/compiler-rt、bionicの起動用objectである。zlibはリンク入力にあるが、最終生成物には残らない。
- Java lite生成コードには`com.google.protobuf:protobuf-javalite:4.34.1`（BSD 3-Clause）が対応する。また、`artifacts/`には`commands`のjarしかないが、生成コードは他の4つのproto jarも参照する。

## 対象と固定条件

| 資産 | 生成target | 確認した実物 | SHA-256 |
| --- | --- | --- | --- |
| `libmozc.so`（4 ABI、`native_libs.zip`内） | `//android/jni:native_libs`（`//android/jni:mozc`を各ABIでcross build） | `artifacts/native_libs.zip`、15,753,780 bytes | `b04cbb1dbb0ec198cef85be8f74f967ba1b6bb2abbb7c1f28edfee234a63934c` |
| 　arm64-v8a | 同上 | 16,182,000 bytes | `05e757129db76bfc66db0224b3cfa9b7d8bc1e0ccd986919a5b66a251128aeee` |
| 　armeabi-v7a | 同上 | 12,940,000 bytes | `c01159acb0ca2734dacbb33df23d9952cf1729275885ee3d2ccffe450c42e329` |
| 　x86 | 同上 | 14,120,544 bytes | `9a1f79aca3ec6bd4c22e4f276c1fe6e8e70de1626fa773f4d238cf3a7243dfed` |
| 　x86_64 | 同上 | 15,176,512 bytes | `9c3b8be6f2150d7c2aa0441668861ab54abbb914a89188e81b46465633bf2402` |
| `mozc.data` | `//data_manager/oss:mozc_dataset_for_oss` | `artifacts/mozc.data`、18,994,682 bytes。`bazel-bin`内の出力と同一 | `720d0cb42651fe35692578ea5f3c752940b8357fc24efc46230c904d2f5d5d70` |
| Java lite（commands） | `//protocol:commands_java_proto_lite` | `libcommands_proto-lite.jar`、295,977 bytes | `b0b9497de0c9fc6f1f52f2069d856facc9d2401fdb1b942f82360b005634065a` |
| Java lite（candidate_window） | `//protocol:candidate_window_java_proto_lite` | `bazel-bin/protocol/libcandidate_window_proto-lite.jar`、115,345 bytes | `98bfd32be008c6e7d8ae5f52ff32199abf7ea381f4da9caf4f774a8413811a10` |
| Java lite（config） | `//protocol:config_java_proto_lite` | `bazel-bin/protocol/libconfig_proto-lite.jar`、87,396 bytes | `f2f01f80b610069c6406a3b46e3d0370d1985ad8505342bfd2621412bea94032` |
| Java lite（engine_builder） | `//protocol:engine_builder_java_proto_lite` | `bazel-bin/protocol/libengine_builder_proto-lite.jar`、19,526 bytes | `e3666da2c6389fcc41b049571dd92fbd38ccb8a951592c0f4b29877439ed6431` |
| Java lite（user_dictionary_storage） | `//protocol:user_dictionary_storage_java_proto_lite` | `bazel-bin/protocol/libuser_dictionary_storage_proto-lite.jar`、37,356 bytes | `d547b8477575080c33ca957ddab96aa046fcbb98d93246784396e8e39a3d8d34` |
| protobuf javalite runtime | Maven Central `com.google.protobuf:protobuf-javalite:4.34.1` | 1,067,654 bytes。公式`.sha1`（`43597f6df55a8a5703654a1a5a92ed9a260034bc`）と一致 | `cfbc20253fd8c365b74bd63f8f90b90ef92005b6619ce629c3eef39b0afef197` |

パスは`.local-build/mozc-probe/`からの相対で、`bazel-bin`は`mozc/src/bazel-bin`を指す。監査ではBazelの`cquery`/`aquery`を既存のoutput rootで実行したが、いずれも「0 total actions」で、既存生成物のSHA-256が変わっていないことを監査後に確認した。

## 資産ごとの判定

| 資産 | 入る第三者コード・データ | ライセンス | 表示義務 | 判定 |
| --- | --- | --- | --- | --- |
| `libmozc.so` | Mozc本体、Abseil 20260107.1、Protocol Buffers 34.1（C++ full runtime）、utf8_range、LLVM libc++/libc++abi/compiler-rt builtins、bionic `crtbegin_so`/`crtend_so` | BSD 3-Clause、Apache 2.0、BSD 3-Clause、MIT、Apache 2.0 with LLVM Exceptions（旧NCSA/MIT併記）、BSD 2-Clause | 著作権表示・条件・免責の添付。Apache 2.0はライセンス全文の添付 | 条件付き可 |
| `mozc.data` | Mozc作成データ、IPAdic/ICOT由来辞書、沖縄辞書、Japanese Usage Dictionary、日本郵便の郵便番号データ、Unicode CLDR 48由来の絵文字注釈、Tamachi由来のa11y最小データ | BSD 3-Clause、IPAdic/ICOT条件、Public Domain、BSD 2-Clause、著作権不主張、Unicode License v3、MIT | IPAdicは著作権表示と後続段落を全コピーに含める。ICOTは「NO WARRANTY」節を常に添付。BSD 2-Clause・Unicode v3・MITは表示と許諾文 | 条件付き可 |
| Java lite生成コード（5 jar） | Mozcの`.proto`から生成したコード | 生成コードは入力ファイルの所有者（Google、Mozcの条件）に属する | Mozcの表示 | 条件付き可 |
| `protobuf-javalite` 4.34.1 | Protocol Buffers Java lite runtime。POM上の依存なし | BSD 3-Clause（POMの`<licenses>`とprotobuf v34.1の`LICENSE`） | 著作権表示・条件・免責の添付 | 条件付き可 |

どの資産もNOTICEの添付だけで条件を満たす。Apache 2.0の§4(b)（変更したファイルへの告知）は、Mozcの`MODULE.bazel`がAbseilとprotobufへpatchを当てていないため該当しない。AbseilとProtocol Buffersの配布物には`NOTICE`ファイルがなく、§4(d)の転記対象もない。

## `mozc.data`の実入力

### 入力の特定方法

`//data_manager/oss:mozc_dataset_for_oss`の依存をMac host設定で`aquery`し、`mozc.data`を生成するactionから入力を遡った。コンパイル・リンク等のツール生成actionは除き、元データだけを列挙した。スクリプトは本文末の「確認方法」に記す。結果は次の通りで、[`data_manager/oss/BUILD.bazel`](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/data_manager/oss/BUILD.bazel)の記述と一致した。`data/rules/segmenter.def`は生成ツールへコンパイルされる入力のため、この遡りから外れるが、BUILDの`segmenter_def`で使われる。

| 入力（`src/`からの相対） | 用途 | 由来と条件 |
| --- | --- | --- |
| `data/dictionary_oss/dictionary00.txt`〜`dictionary09.txt`、`id.def`、`connection_single_column.txt`、`suffix.txt`、`collocation*.txt`、`suggestion_filter.txt`、`reading_correction.tsv`、`aux_dictionary.tsv`、`dictionary_filter.tsv` | 基礎辞書、品詞ID、連接コスト等 | [README.txt](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/data/dictionary_oss/README.txt)と[LICENSE](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/LICENSE)の「Files: src/data/dictionary*」。IPAdic（mecab-ipadic-2.7.0-20070801）とICOTの条件、沖縄辞書のPublic Domain、Google追加分のBSD 3-Clause。IPAdicの条件文は[mecab-ipadicの`COPYING`](https://github.com/taku910/mecab/blob/master/mecab-ipadic/COPYING)と一致した |
| `data/dictionary_manual/domain.txt`、`places.tsv`、`words.tsv` | 手動追加語 | Mozcの管理データ（BSD 3-Clause）。第三者表示の記載なし |
| `external/+http_archive+zip_code_ken_all/KEN_ALL.CSV`、`external/+http_archive+zip_code_jigyosyo/JIGYOSYO.CSV` | 郵便番号辞書（`//dictionary:zip_code_data`） | 日本郵便。下記「郵便番号」 |
| `data/emoji/emoji_data.tsv` | 絵文字変換 | Unicode CLDR 48。下記「絵文字」 |
| `data/emoticon/emoticon.tsv`、`categorized.tsv` | 顔文字 | Mozcの管理データ（BSD 3-Clause）。第三者表示の記載なし |
| `data/symbol/symbol.tsv`、`ordering_rule.txt` | 記号 | Mozcの管理データ（BSD 3-Clause）。第三者表示の記載なし |
| `data/single_kanji/single_kanji.tsv`、`variant_rule.txt` | 単漢字・異体字 | Mozcの管理データ（BSD 3-Clause）。第三者表示の記載なし |
| `data/a11y_description/a11y_description_data.tsv` | 読み上げ用の漢字説明 | 2行だけの最小データ。下記「a11y説明」 |
| `data/zero_query/zero_query.def`、`zero_query_number.def` | 入力前の候補 | ファイル先頭にGoogle Inc.の著作権表示（BSD 3-Clause） |
| `data/rules/*.def`、`sorting_map.tsv` | 品詞・分割規則 | Mozcの管理データ（BSD 3-Clause） |
| `external/+http_archive+ja_usage_dict/usage_dict.txt` | 用例辞書 | [Japanese Usage Dictionary `2025-01-25`](https://github.com/hiroyuki-komatsu/japanese-usage-dictionary/blob/2025-01-25/LICENSE)、BSD 2-Clause。取得済みLICENSEのSHA-256は`91e74c9b189a60a3f5ba13b4aa28f87f25ee9252a64d547784e72752d089631a` |

「Mozcの管理データ」と書いた行は、Mozcの[README](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/README.md)が第三者コードを含む場所として挙げる`src/third_party`、`src/data/dictionary_oss`、`src/data/test/dictionary`、`src/data/test/stress_test`に入らず、ファイル内にも別の著作権表示がないことを根拠とする。個々のデータ行の作成経緯までは確認できない。

### 郵便番号：実際に入る

- [`dictionary_oss/BUILD.bazel`](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/data/dictionary_oss/BUILD.bazel)の`base_dictionary_data`が`//dictionary:zip_code_data`を含み、[`dictionary/BUILD.bazel`](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/dictionary/BUILD.bazel)の`zip_code_data`はOSS設定で`KEN_ALL.CSV`と`JIGYOSYO.CSV`を`gen_zip_code_seed`へ渡す。
- [`MODULE.bazel`](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/MODULE.bazel)は両CSVを[`hiroyuki-komatsu/japanpost_zipcode`の`621d059`](https://github.com/hiroyuki-komatsu/japanpost_zipcode/tree/621d059fbcbfae17bfca15b439692bae934268c3)（2026-04-03のsnapshot）からSHA-256固定で取得する。このリポジトリは日本郵便の配布物のmirrorである。
- ビルド結果の`bazel-bin/dictionary/zip_code.tsv`（9,599,281 bytes）は146,945行。そのうち住所と事業所名を空白でつないだ事業所の行が22,424行あり、`JIGYOSYO.CSV`の22,424行と一致した。フィルタ後の基礎辞書`bazel-bin/data/dictionary_oss/dictionary.txt`にも`ZIP_CODE`の行が146,945行残る。固定checkoutの`dictionary0*.txt`には`ZIP_CODE`行がないため、これらはすべてCSV由来である。
- 取得済みCSVのSHA-256：`KEN_ALL.CSV` `941a1737b13b0c1441525f5baa2915e908f1a00dc1e87f85eeebdf7740bb9922`（124,822行）、`JIGYOSYO.CSV` `3f0be2eee9aef147644ed25c73bf48d5a08d5312e5006a9ebdb006e3602b813a`（22,424行）。

README.txtの「Open source version doesn't include Japanese postal code dictionary.」は、現在のBUILDと実出力に合わない古い記述と判断した。

条件は日本郵便の公式説明で確認した。[住所の郵便番号データ](https://www.post.japanpost.jp/service/search/zipcode/download/readme.html)は「使用・再配布・移植・改良について　郵便番号データに限っては日本郵便株式会社は著作権を主張しません。自由に配布していただいて結構です。」とする。[大口事業所個別番号データ](https://www.post.japanpost.jp/service/search/zipcode/download/office/readme.html)は同じ見出しの下で「大口事業所個別番号データに限っては日本郵便株式会社は著作権を主張しません。自由に配布していただいて結構です。日本郵便株式会社への許諾も必要ありません。」とする。Mozcの[credits](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/data/installer/credits_en.html)は両データを同じ趣旨で記載し、そこから張られた旧URL（`/zipcode/dl/readme.html`、`/zipcode/dl/jigyosyo/readme.html`）は、いずれもこの2ページへ転送される。表示義務はないが、出典としてNOTICEに記載した。

事業所名22,424件は企業・団体名を含む。著作権の条件とは別の論点だが、変換候補に実在の事業所名と住所が出る点は把握しておく。

### 絵文字：CLDR 48由来と判断

`emoji_data.tsv`の先頭は「generated by tools/emoji/generate_emoji_data.py」と書くが、このスクリプトは公開リポジトリにない。次の根拠から、Unicode CLDR 48の日本語注釈（`common/annotations/ja.xml`と`common/annotationsDerived/ja.xml`）に基づくと判断した。

1. GitHub上のこのファイルの履歴で最新の更新は、commit [`4517e51`](https://github.com/google/mozc/commit/4517e51d53063397222adb5512c7ad972b17c181)「Update data to CLDR 48 (i.e. Emoji 17.0).」（2025-10-23）である。
2. 最大の絵文字版はE17.0（8行）で、CLDR 48が対応するEmoji 17.0と合う。
3. [CLDR `release-48`](https://github.com/unicode-org/cldr/tree/release-48/common/annotations)の2ファイルと照合すると、1,918行中1,886行（98.3%）でCLDRの日本語keywordがすべて説明欄に含まれた。CLDR 47の同じ2ファイルでは1,637行だった。取得した`annotations/ja.xml`のSHA-256は`27b1009d4ca4cb61b68a9e8c52f974629d76d00b95711bd984783bc0f58d3da4`、`annotationsDerived/ja.xml`は`4f2e1b1e64594cabbe12c6f60cee497863923f49833e1631942f16d015838e67`。
4. `manual_emoji_data.tsv`の先頭に「Readings covered by the Unicode CLDR should not be added to this file.」とある。

CLDRのデータファイルには「SPDX-License-Identifier: Unicode-3.0」が付き、[`release-48/LICENSE`](https://github.com/unicode-org/cldr/blob/release-48/LICENSE)はUnicode License v3である。同ライセンスはコピーまたは付随文書に著作権表示と許諾文を置くことを条件とするため、全文をNOTICEに入れた。Mozcのcreditsにはこの表示がないが、条件を満たす側に倒した。

### a11y説明：Tamachi由来の最小データ

`a11y_description_data.tsv`は「亜→アネッタイ ノ ア」「胃→イブクロ ノ イ」の2行だけである。これを追加したcommit [`e31edb6`](https://github.com/google/mozc/commit/e31edb67838378f55957755186acedbf728c7550)は、社内版はtamachiyomiのBUILD ruleから生成し、OSS版には単体テスト用の最小データだけを置くと説明し、同時にcreditsへ「Tamachi Phonetic Kanji Alphabet」のMIT Licenseを追加した。2行がTamachiのデータそのものかは確認できないが、MITの表示をNOTICEに入れた。

## `libmozc.so`の実リンク閉包

### 確認方法と結果

1. `//android/jni:mozc`をarm64-v8a platformで`aquery`し、`CppLink` actionの入力を列挙した。Mozc自身のobjectが148個、Abseilが142個、protobufが80個、zlibが15個だった。
2. 動的依存（`llvm-readelf -d`）は4 ABIとも`liblog.so`、`libc.so`、`libm.so`、`libdl.so`だけだった。`libc++_shared.so`を要求しないため、libc++は静的リンクされる。リンク入力にはNDKの`libc++_static.a`と`libc++abi.a`がある。
3. 4 ABIの定義済みsymbol（`llvm-nm -C --defined-only`）を調べた。

| 由来 | 静的に入るか | 根拠 | ライセンス |
| --- | --- | --- | --- |
| Mozc（`base`、`composer`、`converter`、`dictionary`、`engine`、`prediction`、`rewriter`、`session`、`storage`等） | 入る | `mozc::`のsymbol。リンク対象packageのソースに、Google以外の著作権表示はない | BSD 3-Clause |
| Abseil 20260107.1 | 入る | `absl::lts_20260107`のsymbolが6,040〜6,424個 | Apache 2.0。取得済み`LICENSE`は[公式tag](https://github.com/abseil/abseil-cpp/blob/20260107.1/LICENSE)とSHA-256 `c79a7fea…62747`で一致 |
| Protocol Buffers 34.1（C++。lite以外にdescriptor、reflection、text_formatも含む） | 入る | `google::protobuf`のsymbolが7,368〜7,489個。`protobuf_version.bzl`は`PROTOC_VERSION = "34.1"` | BSD 3-Clause。[`v34.1/LICENSE`](https://github.com/protocolbuffers/protobuf/blob/v34.1/LICENSE)とSHA-256 `6e5e1173…fea7d`で一致 |
| utf8_range（protobuf同梱） | 入る | `utf8_range_IsValid`等 | MIT。[`v34.1/third_party/utf8_range/LICENSE`](https://github.com/protocolbuffers/protobuf/blob/v34.1/third_party/utf8_range/LICENSE)とSHA-256 `02de69b6…f5074`で一致 |
| zlib | **入らない** | リンク入力にはあるが、`inflate`、`deflate`、`crc32`、`adler32`等のsymbolもzlibの文字列もない。protobufの`gzip_stream`が`--gc-sections`で除かれたと判断 | 表示不要。将来のビルドで残る場合は再確認する |
| re2 | 入らない | `re2::`のsymbolなし | — |
| LLVM libc++、libc++abi | 入る | `std::__ndk1`のsymbolが約11,200〜11,600個、`__cxxabiv1`。DWARFのcompile unitはNDK prebuiltの`libcxx`・`libcxxabi`だけで、Mozc側はdebug情報なしでビルドされている | Apache 2.0 with LLVM Exceptions（Legacy NCSA/MIT併記）。NDK r29の`NOTICE.toolchain`（SHA-256 `789714a9…8710a`）から転記 |
| compiler-rt builtins | 入る | `__udivti3`、`__aarch64_*`（outline atomics） | 同上 |
| libunwind | 入らない | `_Unwind_*`は`LIBC_R`版の未定義symbolとして`libc.so`から解決される | — |
| bionic `crtbegin_so.o`/`crtend_so.o` | 入る（NDK共有libraryの標準） | `-shared`リンクではNDK clangがsysrootの起動用objectを付ける | BSD 2-Clause（AOSP）。本文はAOSP bionicの`main` branchで確認 |

LLVM Exceptionsは、コンパイルの結果としてObject形式に埋め込まれた部分について§4(a)(b)(d)の条件を免除する。静的リンクしたlibc++がこれに当たると解釈できるが、断定せず表示を入れた。`libmozc.so`はdebug情報付き・未stripで、ビルドパスの文字列を含む。ライセンス上の問題ではない。

## Java liteとruntime

- 生成コード`ProtoCommands.java`の先頭は「Protobuf Java Version: 4.34.1」で、`com.google.protobuf.GeneratedMessageLite`等のlite APIだけを使う。Bazelはprotobuf 34.1の`@protobuf//java/core:lite`をソースからビルドしてコンパイルした。
- 対応するMaven座標は`com.google.protobuf:protobuf-javalite:4.34.1`である。[Maven Centralのmetadata](https://repo1.maven.org/maven2/com/google/protobuf/protobuf-javalite/maven-metadata.xml)に4.34.1が存在する（最新は4.36.2）。[POM](https://repo1.maven.org/maven2/com/google/protobuf/protobuf-javalite/4.34.1/protobuf-javalite-4.34.1.pom)に依存はなく、親POMのlicenseはBSD-3-Clause。jar自体にLICENSEファイルは入らないため、NOTICEで表示する。生成元と同じ版を使い、版を上げるときは生成コードも同じ版で作り直す。
- `commands.proto`は`candidate_window.proto`、`config.proto`、`engine_builder.proto`、`user_dictionary_storage.proto`をimportする。生成コードは`ProtoCandidateWindow`、`ProtoConfig`、`ProtoEngineBuilder`、`ProtoUserDictionaryStorage`を参照するため、`artifacts/`の`libcommands_proto-lite.jar`だけではコンパイル・実行できない。対象と固定条件の表に挙げた4つのjarも必要で、いずれも`bazel-bin/protocol/`にある。条件はすべてMozcのBSD 3-Clauseである。

## NOTICEの作り方と組込み条件

[`third_party/mozc/NOTICE.txt`](../third_party/mozc/NOTICE.txt)（55,678 bytes）は、次の一次資料を原文のまま連結して作った。手写しはしていない。

| 節 | 転記元 |
| --- | --- |
| 1 Mozc（IPAdic・ICOT・沖縄辞書を含む） | 固定checkoutの`LICENSE`（公式rawとSHA-256 `44cdd923…1a648c`で一致） |
| 2 Japanese Usage Dictionary | Bazel取得済みの`LICENSE` |
| 3 日本郵便 | 公式説明ページの該当文 |
| 4 Unicode CLDR | `release-48/LICENSE`（SHA-256 `b4c0ae8e…4d0b6`） |
| 5 Tamachi | Mozc `credits_en.html`の該当節 |
| 6 Abseil、7 Protocol Buffers、8 utf8_range | Bazel取得済みの各`LICENSE`（公式tagと一致） |
| 9 LLVM | NDK r29 `NOTICE.toolchain`の2113–2347行（Apache 2.0 with LLVM Exceptions。三者共通）、2348–2423行（compiler-rt）、2660–2735行（libc++）、2972–3047行（libc++abi） |
| 10 bionic | AOSP `crtbegin_so.c`の冒頭comment。`crtend_so.S`の著作権年も併記 |

APKへの組込みでは、このファイルを変更せずassetsへ入れ、アプリ内から表示できるようにする。IPAdic/ICOTの条件は辞書のコピーに表示を伴わせることを求めるため、`mozc.data`を同梱するAPKには必ず含める。Uzumi自身や、Mozc以外のAndroid依存のNOTICEはこの監査の対象外である。

## 確認方法

作業ディレクトリは`$probe/mozc/src`、`probe`は`.local-build/mozc-probe`の絶対パス。

```sh
# libmozc.so のリンク入力（CppLink actionの入力を列挙）
"$probe/bin/bazel" --output_user_root="$probe/bazel-cache" aquery 'mnemonic("CppLink", //android/jni:mozc)' \
  --config oss_android --config release_build --platforms=//android/jni:arm64-v8a --output=jsonproto > aq.json
# mozc.data の入力（生成actionから元データまで遡る）
"$probe/bin/bazel" --output_user_root="$probe/bazel-cache" aquery 'deps(//data_manager/oss:mozc_dataset_for_oss)' \
  --config oss_macos --config release_build --host_copt=-faligned-allocation --copt=-faligned-allocation --output=jsonproto > aq2.json
# 実生成物の確認（NDK r29 の llvm-readelf / llvm-nm）
llvm-readelf -d libs/<abi>/libmozc.so | grep NEEDED
llvm-nm -C --defined-only libs/<abi>/libmozc.so | grep -cE 'absl::|google::protobuf|utf8_range|std::__ndk1'
llvm-nm -C --defined-only libs/<abi>/libmozc.so | grep -ciE ' (inflate|deflate|crc32|adler32)$'   # 0 であること
# 郵便番号の件数
awk -F'\t' '$6=="ZIP_CODE"' bazel-bin/dictionary/zip_code.tsv | wc -l
awk -F'\t' '$6=="ZIP_CODE" && $5 ~ / /' bazel-bin/dictionary/zip_code.tsv | wc -l
```

JSONの展開、CLDRとの照合、NOTICEの連結は短いPythonスクリプトで行った。スクリプトはリポジトリに置いていない。同じ照合は、`aquery`の`actions`・`depSetOfFiles`・`artifacts`・`pathFragments`を辿る方法と、CLDR XMLの`<annotation>`をcode pointごとに読んで`emoji_data.tsv`の6列目と比べる方法で再現できる。

## 未確認事項

- 🟡 `emoji_data.tsv`の生成手順は非公開で、CLDR 48の2ファイル以外（Unicodeの`emoji-test.txt`等）も入力かは不明。Unicodeのデータファイルも同じUnicode License v3なので、NOTICEの内容は変わらない。
- 🟡 `a11y_description_data.tsv`の2行がTamachiのデータそのものかは不明。MITの表示を入れて対応した。
- 🟢 bionicの起動用objectの本文は、NDK r29の正確なsource revisionではなくAOSPの`main` branchで確認した。`crt_pad_segment.o`等、他の起動用objectのリンク有無は確認していない。いずれもAOSPのBSD系条件と推測する。
- 🟢 顔文字・記号・単漢字・手動追加語など「Mozcの管理データ」とした行は、ファイルの表示とREADMEの記載から判断した。個々の行の作成経緯は確認できない。
- ⚪ 監査はarm64-v8aで`aquery`を行い、他の3 ABIはsymbolと動的依存で同じ構成であることを確かめた。他ABIの`aquery`は実行していない。
- ⚪ 除外版`mozc.data`の再ビルドは不要と判断し、実施していない。新しい生成物はない。

## 要確認

- 🟡 protobuf javalite runtimeの取得方法。Maven Centralの`com.google.protobuf:protobuf-javalite:4.34.1`をGradle依存に加えるか、Bazelでソースからビルドした`liblite.jar`を同梱するか。前者はアプリへの新しい依存の追加になる。
- 🟡 Java lite生成コードの受け渡し。`artifacts/`にない4つのproto jar（または生成source jar）も接続担当へ渡す。
- 🟢 一般公開する場合は、NOTICEのアプリ内表示の形と、事業所名を含む郵便番号辞書を残すかを改めて判断する。今回の判定は本人用debug APKに限る。
