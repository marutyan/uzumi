#!/bin/bash
# 音声入力の比較に使う録音を、台本（docs/voice-input-script.tsv）の1行ずつMacのマイクで取る。
# 録音は本人の声なのでGit追跡外の.local-build/voice-eval/recordings/へ置く。<id>.wavは48kHzの原本（スピーカー再生用）、
# <id>.16k.wavは試作へ直接入れる16kHz・モノラル・16bitの版である。
# 使い方（手元のMacのターミナルで実行）: tools/voice/record.sh [id ...]   id省略時は全行
set -euo pipefail
REPO=$(cd "$(dirname "$0")/../.." && pwd)
SCRIPT_TSV=$REPO/docs/voice-input-script.tsv
OUT=$REPO/.local-build/voice-eval/recordings
# 録音に使うAVFoundationの音声デバイス番号。`ffmpeg -f avfoundation -list_devices true -i ""`で確かめる。
DEVICE=${UZUMI_RECORD_DEVICE:-0}
mkdir -p "$OUT"

# 指定したidだけを録るか判定する。引数が無ければ全行を録る。
wanted() {
  [ "$#" -le 1 ] && return 0
  local id=$1; shift
  for w in "$@"; do [ "$w" = "$id" ] && return 0; done
  return 1
}

tail -n +2 "$SCRIPT_TSV" | while IFS=$'\t' read -r id kind text _; do
  wanted "$id" "$@" || continue
  echo
  echo "[$id] $kind"
  echo "  $text"
  read -r -p "Enterで録音開始（sで飛ばす）: " ans < /dev/tty
  [ "$ans" = "s" ] && continue
  ffmpeg -hide_banner -loglevel error -y -f avfoundation -i ":$DEVICE" -ac 1 -ar 48000 "$OUT/$id.wav" < /dev/null &
  pid=$!
  read -r -p "  録音中…Enterで停止: " _ < /dev/tty
  kill -INT "$pid"
  wait "$pid" || true
  ffmpeg -hide_banner -loglevel error -y -i "$OUT/$id.wav" -ac 1 -ar 16000 -sample_fmt s16 "$OUT/$id.16k.wav"
  echo "  保存: $OUT/$id.wav（$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT/$id.wav")秒）"
done
