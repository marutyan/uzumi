# MozcをAndroidへ組み込むための確認

確認日：2026-09-21。ビルド依存の追加確認：2026-09-22。対象は公式`google/mozc`のcommit `13c98988247aa711d99db9e348ec2a597d14b5cd`。一次ソースの読み取り結果であり、native build・変換・辞書の実行は未検証。

## 確認できた接続経路

現行[Androidビルド手順](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/docs/build_mozc_for_android.md)は`libmozc.so`を生成する。APKクライアントは廃止されているため、IMEの画面・ライフサイクル・Editor同期は本プロジェクトが担当する。

[JNI実装](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/android/jni/mozcjni.cc)は`initialize()`から、`evalCommand(byte[])`、`onPostLoad(profilePath, dataPath)`、`getDataVersion()`を登録する。コマンドはprotobufの`Command`としてSessionHandlerへ渡される。JNI登録名が既定のJava package/classへ固定されているため、同名の最小ブリッジを作るか、変更を明示してnative側をビルドする必要がある。

[protocol定義](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/protocol/commands.proto)にはsession作成/破棄、キー、候補、preedit、incognito指定がある。[BUILD](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/protocol/BUILD.bazel)には`commands_java_proto_lite`等のJava lite生成targetがある。protobuf runtimeと生成元のversion整合を保つ必要がある。

native側のSessionHandlerはglobal instanceなので、一つの直列workerから呼ぶ案とする。Editor sessionの切替とMozcのsession IDを別々に管理し、結果を受け取る段階でEditorの世代を照合する。JNI呼出し終了後に古い結果を破棄するだけでは、エンジン側の学習が既に行われる可能性があるため、機密欄では要求自体を送らず、通常欄でも学習方針を事前指定する。

## ライブラリだけでは足りない

[native_libs target](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/android/jni/BUILD.bazel)が梱包するのは各ABIのライブラリ。別途、[OSS dataset target](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/data_manager/oss/BUILD.bazel)が生成する`mozc.data`等の辞書資産を確認する必要がある。

JNI実装はdatasetを開けない場合にminimal engineへfallbackする。初期化の返値trueだけでは漢字辞書が使えている証明にならない。data versionと既知のかな漢字変換ケースを受入試験に入れ、辞書不在を正常な変換エンジンとして隠さない。

OSS辞書はGoogle日本語入力/Gboardの語彙と同一ではない。[dictionary_oss README](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/data/dictionary_oss/README.txt)にはIPAdic、沖縄辞書等の条件が記載される。コード・辞書・protobuf等のruntimeを別々に棚卸しし、NOTICEを用意してからアプリへ同梱する。最終的な配布資産の監査は未完了。

## 既存環境と追加導入

MacにはPython 3.14.7、JDK 25、Android SDK 36/37、Build Tools 36/37、Gradle 9.7.1が存在する。Pythonの新規導入は不要。BazeliskとNDKは今回確認した標準配置にはない。

公式[依存取得スクリプト](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/build_tools/update_deps.py)はMac用NDK r29 zipのサイズを`1,049,519,838` bytes、SHA-256を`ce5e4b100ec5fe5be4eb3edcb2c02528824ff9cda3860f5304619be6c3da34d3`と固定している。既定実行ではQt等も取得し得るため、MacのAndroid専用試作では`--noqt --noninja`を指定すると、取得対象の一覧をNDKだけにできる。Bazelが後から取得する依存は別であり、この指定だけで全ダウンロードがNDKだけになるわけではない。Bazel自身と推移依存、展開後の総容量・所要時間は不明であり、NDKのzip容量と混同しない。

追加導入は専用のローカル作業領域へ限定し、既存SDKやglobal PATHを変更しない案とする。BazeliskとBazelの候補は下表に固定した。推移依存の解決結果はビルド試作で記録する。依存スクリプトは既存の展開先を削除する処理を含むため、共有SDKを展開先にしない。

## 固定できたビルド依存

[`src/.bazeliskrc`](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/.bazeliskrc)の指定はBazel 9.0.2である。`.bazelversion`の不在を、版指定がないことと混同しない。[`MODULE.bazel`](https://github.com/google/mozc/blob/13c98988247aa711d99db9e348ec2a597d14b5cd/src/MODULE.bazel)はprotobuf 34.1とNDKの配置先`src/third_party/ndk/android-ndk-r29`を指定する。Java lite runtimeのMaven座標は、生成物の依存定義を確認してから決める。

| 導入候補 | Mac arm64用の容量 | SHA-256 | 根拠 |
|---|---:|---|---|
| Bazelisk v1.29.0 | 6,659,202 bytes | `cee851f726789227d5561004e9904a52be45c3efb56f8b38b6993d6adbaa0409` | [公式release](https://github.com/bazelbuild/bazelisk/releases/tag/v1.29.0)の`bazelisk-darwin-arm64` |
| Bazel 9.0.2 | 60,272,019 bytes | `525cfcbf9790af7319ea78c9ff2053b8a5634013c41783440b5d82433f14d280` | [公式release](https://github.com/bazelbuild/bazel/releases/tag/9.0.2)の`bazel-9.0.2-darwin-arm64` |

容量とhashは2026-09-22に公式GitHub release APIのasset metadataから確認した。ダウンロードしたバイナリの検証結果ではない。導入時にも取得物のhashを照合する。BazeliskのcacheとBazelのoutput rootを専用作業領域へ指定し、既存SDK・global PATHを変更しない。NDKの展開先も専用Mozc checkout内に限定する。ここで挙げた容量には展開後ファイル、Bazel cache、推移依存を含めない。

## 次の作業単位

1. 下記の追加導入範囲を合意し、固定commitからarm64向けnative libraryとOSS datasetをビルドする。全ABI archiveは必要な場合だけ生成する。
2. 同じprotocol versionのJava lite生成物・runtimeと最小JNIブリッジを接続し、端末上でsession作成→読み入力→候補→確定→session破棄を検証する。
3. dataset不在・破損、低メモリ、接続切替、機密欄、連続入力を試験する。API 37ではOS memory limiterによる終了も確認する。
4. 変換を組み込み、ユーザー辞書CRUDと保存・削除・再起動後復元を独立に実装・検証する。`commands.proto`の旧`user_dictionary_command`はreservedであるため、旧Androidコードの辞書操作をそのまま呼べると仮定しない。

この確認は採用案を具体化するもので、Mozc組込み済み、漢字変換済み、Phase 1完了という意味ではない。

## ローカル試作の実行範囲

2026-09-23時点の空き容量は約195 GiB。試作専用領域はリポジトリ内の`.local-build/mozc-probe/`とし、`.gitignore`で成果物を追跡対象から除く。既存Android SDK、global PATH、他プロジェクトのcacheは変更しない。

今回は[Bazel 9.0.2のMac arm64実行ファイル](https://github.com/bazelbuild/bazel/releases/tag/9.0.2)を専用領域へ直接配置する案とし、Bazeliskの導入は省く。約60 MBの実行ファイルは取得後、上表のSHA-256を照合する。Mozcは指定commit `13c98988247aa711d99db9e348ec2a597d14b5cd`を専用領域へcheckoutする。そこで固定された`update_deps.py --noqt --noninja`を実行すると、Mac用NDK r29の約1.05 GBのzipを取得・検証し、**そのcheckout内**の`src/third_party/ndk`を作り直す。NDK展開後の容量とBazelが追加取得する依存の総量は不明。

実行するコマンドの骨子は次のとおり。`probe`には上記専用領域の絶対パスを入れる。依存スクリプトとBazelのcacheはこの領域だけで扱い、取得物のハッシュ、実際の通信量、所要時間、使用容量を記録する。

```sh
probe=/Users/marutyan/PrivateDev/uzumi/.local-build/mozc-probe
mkdir -p "$probe/bin"
curl -fL https://github.com/bazelbuild/bazel/releases/download/9.0.2/bazel-9.0.2-darwin-arm64 -o "$probe/bin/bazel"
shasum -a 256 "$probe/bin/bazel"
# 上表のSHA-256との一致を確認してから実行権を付ける。
chmod u+x "$probe/bin/bazel"
git clone --filter=blob:none https://github.com/google/mozc.git "$probe/mozc"
git -C "$probe/mozc" checkout --detach 13c98988247aa711d99db9e348ec2a597d14b5cd
cd "$probe/mozc/src"
/opt/homebrew/bin/python3.14 build_tools/update_deps.py --noqt --noninja
"$probe/bin/bazel" --output_user_root="$probe/bazel-cache" build --config oss_android --config release_build //android/jni:native_libs //data_manager/oss:mozc.data //protocol:commands_java_proto_lite
```

NDKの取得だけで約1.05 GB、Bazelでさらに推移依存を取得する。まず20 GiBまたは1時間を試作の上限とし、超えそうなら記録して止め、追加実行の是非を判断する。ここでの許可はローカルのビルド試作に限り、生成物をAPKへ同梱・公開する判断ではない。コード・辞書・Java runtimeの配布ライセンス確認は組込み前に行う。
