#!/usr/bin/env python3
"""Phase 3aの段階2（端末での逐次入力の再生）を、評価条件どおりに流すスクリプト。

評価条件`docs/phase3a-model-selection-protocol.md`の「段階2」を機械的に行う。キー入力、訂正、状態の確認口での待ち合わせは
Phase 2cの自動測定の道具（`tools/phase2c/run_automated.py`、版2）をそのまま使い、ここでは次を足す。

- 条件（M、ZS、ZX、JS、JX）の切り替え。すべてライブ変換で、debugの受信口の`NEURAL_SELECT`でモデルだけを変える。
- 5回×5条件の順番を5×5のラテン方格で決める（方格の行の割り当てはseed 20261015）。各条件の間に5分以上空け、
  `dumpsys thermalservice`の温度状態がNONEに戻るまで待つ。
- 全条件で同じAPKを使うことを、各条件の開始時にAPKのSHA-256で確かめる。
- 課題ごとに許容表記（`accepted_b64`）と最終文（`final_b64`）を受信口へ渡し、正→誤の遷移と数字の並びを端末内で判定させる。
- 条件ごとに評価用計数（`EVAL_DUMP`）、時間の記録（`EVAL_DUMP_TIMINGS`）、PSS（`EVAL_MEMORY`と1秒ごとの`dumpsys meminfo`）を残す。
- cold start（10回）と電池（10分の再生、`dumpsys batterystats`）の手順。

**試験用の集合は一度だけ使う。** 既定の課題は開発用の30文（`docs/phase3a-dev-tasks.tsv`）で、試験用の54文
（`docs/phase2c-tasks.tsv`）は`--test-set`と最終設定の識別名（`--fixed-config`）を付けたときだけ読む。同じ識別名で
同じ回・条件を二度流そうとしたら止める（不具合を直した後は、新しい識別名で全条件を流し直す）。
記録には本文・候補を残さない（課題ごとの正誤と件数だけ）。外部の依存は使わない（python3の標準ライブラリだけ）。
"""

import argparse
import base64
import json
import random
import re
import statistics
import sys
import threading
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "phase2c"))
import run_automated as p2c  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
DEV_TASKS = REPO / "docs" / "phase3a-dev-tasks.tsv"
TEST_TASKS = REPO / "docs" / "phase2c-tasks.tsv"
LOCAL = REPO / ".local-build" / "phase3a"

# この道具の版。手順を変えたら上げ、結果に記録する。
TOOL_VERSION = 1

# 評価条件の比較条件。Mはモデルを選ばない（ニューラルを無効にした同じAPK）。
CONDITIONS = ["M", "ZS", "ZX", "JS", "JX"]
# 評価条件で固定した回数、ラテン方格のseed、条件の間の最短の間隔（秒）。
ROUNDS = 5
LATIN_SEED = 20261015
REST_BETWEEN_CONDITIONS_S = 300
# モデルの準備（読み込みとSHA-256の確認）を待つ上限（秒）。
MODEL_READY_TIMEOUT_S = 120.0
# 温度状態がNONE（0）へ戻るのを待つ上限（秒）。超えたら止める。
THERMAL_TIMEOUT_S = 3600.0
# PSSを読む間隔（秒）。評価条件の「dumpsys meminfoで1秒ごと」。
MEMINFO_INTERVAL_S = 1.0
# cold startの回数と、電池の測定で再生を繰り返す時間（秒）。
COLD_START_TRIALS = 10
BATTERY_DURATION_S = 600

# 評価用計数の版3の列（Phase 2cの版2の列の後ろへ、ニューラル側とH3の列を足したもの）。
NEURAL_COLUMNS = [
    "neural_ranges", "neural_kana_runs", "neural_long_splits", "neural_requests", "neural_cache_hits",
    "neural_cancelled", "neural_timeouts", "neural_unavailable", "check1_rejected", "check2_rejected",
    "check3_rejected", "check4_rejected", "mozc_fallbacks", "reading_fallbacks", "h3_generated",
    "h3_unrejected", "h3_applied_violations", "input_modifications", "correct_to_wrong", "digit_violation",
]
COUNT_COLUMNS_V3 = p2c.COUNT_COLUMNS + NEURAL_COLUMNS


def log(message: str) -> None:
    """進行を標準エラーへ出す。本文や候補の文字列は出さない。"""
    p2c.log(message)


# ---- 順番 ----

def latin_schedule(seed: int = LATIN_SEED) -> List[List[str]]:
    """5回ぶんの条件の順番を返す。巡回の5×5のラテン方格を作り、行（回への割り当て）と記号（条件）をseedで並べ替える。

    巡回の方格は各記号が各行・各列に一度ずつ現れる。行と記号の入れ替えはその性質を保つため、
    どの条件も各回に一度、各位置（何番目に流すか）に一度ずつ当たる。
    """
    rng = random.Random(seed)
    size = len(CONDITIONS)
    symbols = CONDITIONS[:]
    rng.shuffle(symbols)
    rows = [[symbols[(r + c) % size] for c in range(size)] for r in range(size)]
    rng.shuffle(rows)
    return rows


def is_latin(rows: List[List[str]]) -> bool:
    """各行・各列に各条件が一度ずつ現れるか。"""
    columns = [[row[c] for row in rows] for c in range(len(CONDITIONS))]
    return all(sorted(line) == sorted(CONDITIONS) for line in rows + columns)


# ---- 課題ごとのextra ----

def b64(text: str) -> str:
    """UTF-8の文字列をBase64にする（shellで引用しなくてよい文字だけになる）。"""
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def start_extras(task: "p2c.Task") -> Dict[str, str]:
    """計数の開始で、課題の許容表記（`|`区切り）を渡す。端末は課題の間だけメモリに持ち、判定の件数だけを残す。"""
    return {"accepted_b64": b64("|".join(task.accepted))}


def finish_extras(snap: "p2c.Snapshot") -> Dict[str, str]:
    """計数の終了で、最終文（末尾の改行を除く）を渡す。数字の並びの照合にだけ使われる。"""
    text = snap.field_text[:-1] if snap.field_text.endswith("\n") else snap.field_text
    return {"final_b64": b64(text)}


# ---- 端末の状態 ----

def parse_status(data: str) -> Dict[str, int]:
    """受信口の`名前=値`のタブ区切りの行を、数値の辞書にする。"""
    values = {}
    for item in data.strip().split("\t"):
        name, _, value = item.partition("=")
        if re.fullmatch(r"-?\d+", value):
            values[name] = int(value)
    return values


def parse_thermal_status(text: str) -> Optional[int]:
    """`dumpsys thermalservice`の出力から温度状態（0がNONE）を読む。読めなければNone。"""
    match = re.search(r"[Tt]hermal [Ss]tatus:\s*(-?\d+)", text)
    return int(match.group(1)) if match else None


def parse_total_pss(text: str) -> Optional[int]:
    """`dumpsys meminfo <process>`の出力からPSSの合計（KB）を読む。プロセスが無ければNone。"""
    match = re.search(r"TOTAL PSS:\s*(\d+)", text) or re.search(r"^\s*TOTAL\s+(\d+)", text, re.M)
    return int(match.group(1)) if match else None


def parse_computed_drain(text: str) -> Optional[float]:
    """`dumpsys batterystats`の出力から端末全体の推定消費（mAh）を読む。形式は端末で確かめていない（読めなければNone）。"""
    match = re.search(r"Computed drain:\s*([\d.]+)", text)
    return float(match.group(1)) if match else None


def apk_sha256() -> str:
    """端末に入っているUzumiのAPK（base.apk）のSHA-256。"""
    path = p2c.shell(f"pm path {p2c.PACKAGE}").strip().splitlines()[0].replace("package:", "")
    return p2c.shell(f"sha256sum {path}").split()[0]


def device_conditions() -> Dict[str, str]:
    """評価条件の「実機の条件」に挙げた端末の状態を記録する。値は端末の設定と状態だけで、課題の本文は含まない。"""
    commands = {
        "fingerprint": "getprop ro.build.fingerprint",
        "brightness": "settings get system screen_brightness",
        "brightness_mode": "settings get system screen_brightness_mode",
        "peak_refresh_rate": "settings get system peak_refresh_rate",
        "min_refresh_rate": "settings get system min_refresh_rate",
        "low_power": "settings get global low_power",
        "battery": "dumpsys battery",
        "thermal": "dumpsys thermalservice",
        "memory_limiter": "am memory-limiter status",
    }
    return {name: p2c.shell(command, check=False).strip()[:4000] for name, command in commands.items()}


def wait_thermal_none(timeout: float = THERMAL_TIMEOUT_S) -> None:
    """温度状態がNONE（0）になるまで待つ。読めない場合と上限を超えた場合は止める。"""
    deadline = time.monotonic() + timeout
    while True:
        status = parse_thermal_status(p2c.shell("dumpsys thermalservice", check=False))
        if status == 0:
            return
        if status is None:
            raise p2c.Abort("cannot read thermal status")
        if time.monotonic() > deadline:
            raise p2c.Abort(f"thermal status did not return to NONE ({status})")
        log(f"thermal status {status}; waiting")
        time.sleep(30)


class MemInfoSampler:
    """条件の再生の間、IMEと`:neural`のPSSを1秒ごとに読んでCSVへ残す別thread。値はPSS（KB）と時刻だけ。"""

    def __init__(self, path: Path):
        self.path = path
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self) -> None:
        """止めるまで読み続ける。プロセスが無い時点は空欄にする。"""
        with self.path.open("w", encoding="utf-8") as out:
            out.write("elapsed_s,ime_pss_kb,neural_pss_kb\n")
            begin = time.monotonic()
            while not self.stop.is_set():
                ime = parse_total_pss(p2c.shell(f"dumpsys meminfo {p2c.PACKAGE}", check=False))
                neural = parse_total_pss(p2c.shell(f"dumpsys meminfo {p2c.PACKAGE}:neural", check=False))
                out.write(f"{time.monotonic() - begin:.1f},{'' if ime is None else ime},{'' if neural is None else neural}\n")
                out.flush()
                self.stop.wait(MEMINFO_INTERVAL_S)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.stop.set()
        self.thread.join(timeout=10)


# ---- 条件の準備 ----

def prepare_neural_condition(condition: str, work: Path) -> Dict[str, int]:
    """学習を消してライブ変換をONにし（Phase 2cの条件Nと同じ準備）、モデルを選んで準備ができるまで待つ。"""
    p2c.prepare_condition("N", work)
    p2c.broadcast("NEURAL_SELECT", extras={"model": condition})
    # 選択は入力欄の開始ごとに読まれるため、試験画面を開き直す。
    p2c.shell(f"am start -n {p2c.TASK_ACTIVITY} --es task SETUP3")
    deadline = time.monotonic() + MODEL_READY_TIMEOUT_S
    while True:
        status = parse_status(p2c.broadcast("EVAL_STATUS"))
        if condition == "M" and status.get("neural_selected") == 0:
            return status
        if condition != "M" and status.get("neural_ready") == 1:
            return status
        if condition != "M" and status.get("neural_load_reason", -1) > 0:
            raise p2c.Abort(f"model load failed (reason {status['neural_load_reason']})")
        if time.monotonic() > deadline:
            raise p2c.Abort(f"model {condition} was not ready in {MODEL_READY_TIMEOUT_S:.0f}s")
        time.sleep(0.5)


def memory_line() -> Dict[str, int]:
    """受信口の`EVAL_MEMORY`を読む（`:neural`の値は1回遅れて入るため、2回読んで後の値を使う）。"""
    p2c.broadcast("EVAL_MEMORY")
    time.sleep(1.0)
    return parse_status(p2c.broadcast("EVAL_MEMORY"))


# ---- 試験用の集合の一度だけの使用 ----

def claim_test_run(fixed_config: str, round_index: int, condition: str) -> Path:
    """試験用の集合で、同じ最終設定・回・条件を二度流さないための記録を作る。既にあれば止める。"""
    ledger = LOCAL / "test-set-ledger"
    ledger.mkdir(parents=True, exist_ok=True)
    marker = ledger / f"{fixed_config}-round{round_index}-{condition}.json"
    if marker.exists():
        raise p2c.Abort(f"test set already used for {marker.name}; use a new --fixed-config after a fix")
    marker.write_text(json.dumps({"started": time.strftime("%Y-%m-%dT%H:%M:%S"), "tool_version": TOOL_VERSION}))
    return marker


# ---- 1条件の再生 ----

def select_tasks(test_set: bool, task_ids: Optional[str]) -> List["p2c.Task"]:
    """流す課題。試験用の集合はPhase 2cと同じ順番（seed 20261001）、開発用はファイルの順。"""
    if test_set:
        selected = p2c.automated_order(p2c.load_tasks(TEST_TASKS))
    else:
        selected = p2c.load_tasks(DEV_TASKS)
    if task_ids:
        by_id = {t.task_id: t for t in selected}
        selected = [by_id[i] for i in task_ids.split(",")]
    return selected


def run_condition(condition: str, round_index: int, tasks: List["p2c.Task"], work: Path,
                  layouts, expected_apk: Optional[str]) -> str:
    """1条件を流し、結果・計数・時間・PSSをファイルへ残す。使ったAPKのSHA-256を返す。"""
    apk = apk_sha256()
    if expected_apk is not None and apk != expected_apk:
        raise p2c.Abort("APK changed between conditions")
    prefix = work / f"round{round_index}-{condition}"
    ready = prepare_neural_condition(condition, work)
    previous = p2c.broadcast("EVAL_DUMP")
    Path(f"{prefix}-counts-before.tsv").write_text(previous, encoding="utf-8")
    p2c.broadcast("EVAL_CLEAR")
    meta = {"condition": condition, "round": round_index, "tool_version": TOOL_VERSION,
            "phase2c_tool_version": p2c.TOOL_VERSION, "apk_sha256": apk, "key_interval_s": p2c.KEY_INTERVAL_S,
            "ready_status": ready, "memory_before": memory_line(), "device": device_conditions(),
            "tasks": [t.task_id for t in tasks]}
    kb = p2c.Keyboard(layouts)
    status = "ok"
    with MemInfoSampler(Path(f"{prefix}-meminfo.csv")), Path(f"{prefix}-results.jsonl").open("w", encoding="utf-8") as out:
        for task in tasks:
            begin = time.monotonic()
            try:
                result = p2c.run_task(kb, task, condition)
            except p2c.Abort as error:
                log(f"ABORT {condition} round{round_index} at {task.task_id}: {error}")
                out.write(json.dumps({"condition": condition, "round": round_index, "task_id": task.task_id,
                                      "status": "abort", "note": str(error)}, ensure_ascii=False) + "\n")
                status = "abort"
                break
            result.wall_ms = int((time.monotonic() - begin) * 1000)
            row = dict(result.__dict__, round=round_index)
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            out.flush()
    meta["memory_after"] = memory_line()
    meta["status"] = status
    Path(f"{prefix}-counts.tsv").write_text(p2c.broadcast("EVAL_DUMP"), encoding="utf-8")
    Path(f"{prefix}-timings.tsv").write_text(p2c.broadcast("EVAL_DUMP_TIMINGS"), encoding="utf-8")
    Path(f"{prefix}-meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    if status != "ok":
        raise p2c.Abort(f"condition {condition} round {round_index} aborted")
    return apk


# ---- cold startと電池 ----

def cold_start(condition: str, work: Path) -> List[int]:
    """`:neural`が無い状態からbindし、最初の推論結果が返るまでの時間をCOLD_START_TRIALS回測る（ミリ秒）。

    毎回アプリを止めて学習を消し、試験画面を開いてモデルを選ぶ。準備ができたら開発用の文ではない固定の1文字（「あ」）を
    入れ、最初の結果が届くまで待つ。値はIMEが測ったbindから最初の結果までで、準備の確認の間隔（0.5秒）の分だけ長く出得る。
    """
    values = []
    layouts = load_layouts(work)
    for trial in range(COLD_START_TRIALS):
        prepare_neural_condition(condition, work)
        kb = p2c.Keyboard(layouts)
        p2c.prepare_task(kb, "COLD")
        kb.send(p2c.plan_text("あ", kb.state)[0])
        deadline = time.monotonic() + 30
        while True:
            memory = parse_status(p2c.broadcast("EVAL_MEMORY"))
            if memory.get("neural_cold_start_ms", -1) >= 0:
                values.append(memory["neural_cold_start_ms"])
                break
            if time.monotonic() > deadline:
                raise p2c.Abort("no inference result for cold start")
            time.sleep(0.2)
        log(f"cold start {condition} trial {trial + 1}: {values[-1]} ms")
    (work / f"coldstart-{condition}.json").write_text(json.dumps(
        {"condition": condition, "values_ms": values, "median_ms": statistics.median(values), "max_ms": max(values)}))
    return values


def battery(condition: str, round_index: int, tasks: List["p2c.Task"], work: Path) -> Optional[float]:
    """充電を切った状態で課題の再生を10分間くり返し、端末全体の推定消費（mAh）を返す。終わったら充電の状態を戻す。"""
    layouts = load_layouts(work)
    prepare_neural_condition(condition, work)
    p2c.shell("dumpsys batterystats --reset")
    p2c.shell("dumpsys battery unplug")
    kb = p2c.Keyboard(layouts)
    try:
        deadline = time.monotonic() + BATTERY_DURATION_S
        repeats = 0
        while time.monotonic() < deadline:
            for task in tasks:
                if time.monotonic() >= deadline:
                    break
                p2c.run_task(kb, task, condition)
            repeats += 1
        stats = p2c.shell("dumpsys batterystats", timeout=120)
    finally:
        p2c.shell("dumpsys battery reset")
    drain = parse_computed_drain(stats)
    (work / f"battery-round{round_index}-{condition}.json").write_text(json.dumps(
        {"condition": condition, "round": round_index, "repeats": repeats, "computed_drain_mah": drain}))
    return drain


def load_layouts(work: Path):
    """probeで読んだ面の配置を読む。"""
    layouts = json.loads((work / "layouts.json").read_text(encoding="utf-8"))
    return {k: {d: tuple(b) for d, b in v.items()} for k, v in layouts.items()}


def main() -> int:
    """測定の入口。planは順番を表示するだけで端末を使わない。"""
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["plan", "probe", "run", "coldstart", "battery"])
    parser.add_argument("--work", default=str(LOCAL / time.strftime("%Y-%m-%d")))
    parser.add_argument("--rounds", default=",".join(str(i + 1) for i in range(ROUNDS)),
                        help="流す回（1〜5）をカンマ区切りで。既定はすべて")
    parser.add_argument("--conditions", default=",".join(CONDITIONS), help="coldstart・batteryで測る条件")
    parser.add_argument("--tasks", help="課題IDをカンマ区切りで指定（開発の確認用）")
    parser.add_argument("--test-set", action="store_true", help="試験用の54文を使う（最終設定の固定後に一度だけ）")
    parser.add_argument("--fixed-config", help="--test-setで必須。固定した最終設定の識別名（例：Uzumiのcommit）")
    parser.add_argument("--no-rest", action="store_true", help="条件の間の5分の休みを省く（開発用の集合の確認だけ）")
    args = parser.parse_args()
    schedule = latin_schedule()
    assert is_latin(schedule)
    if args.command == "plan":
        for index, row in enumerate(schedule, start=1):
            print(f"round{index}\t" + "\t".join(row))
        return 0
    if args.test_set and not args.fixed_config:
        parser.error("--test-set requires --fixed-config")
    if args.test_set and (args.no_rest or args.tasks):
        parser.error("--test-set cannot be combined with --no-rest or --tasks")
    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    # 課題の開始と終了で、許容表記と最終文を受信口へ渡す。計数の行は版3の列で読む。
    p2c.START_EXTRAS = start_extras
    p2c.FINISH_EXTRAS = finish_extras
    p2c.COUNT_COLUMNS = COUNT_COLUMNS_V3
    if args.command == "probe":
        layouts = p2c.probe_layouts()
        (work / "layouts.json").write_text(json.dumps(layouts, ensure_ascii=False, indent=1), encoding="utf-8")
        return 0
    tasks = select_tasks(args.test_set, args.tasks)
    if args.command == "coldstart":
        for condition in args.conditions.split(","):
            if condition != "M":
                cold_start(condition, work)
        return 0
    if args.command == "battery":
        for round_index in [int(r) for r in args.rounds.split(",")]:
            for condition in schedule[round_index - 1]:
                wait_thermal_none()
                battery(condition, round_index, tasks, work)
        return 0
    layouts = load_layouts(work)
    (work / "schedule.json").write_text(json.dumps(
        {"seed": LATIN_SEED, "tool_version": TOOL_VERSION, "rows": schedule, "test_set": args.test_set,
         "fixed_config": args.fixed_config}, ensure_ascii=False, indent=1))
    expected_apk: Optional[str] = None
    first = True
    for round_index in [int(r) for r in args.rounds.split(",")]:
        for condition in schedule[round_index - 1]:
            if not first and not args.no_rest:
                log(f"rest {REST_BETWEEN_CONDITIONS_S}s before {condition}")
                time.sleep(REST_BETWEEN_CONDITIONS_S)
            first = False
            wait_thermal_none()
            if args.test_set:
                claim_test_run(args.fixed_config, round_index, condition)
            try:
                expected_apk = run_condition(condition, round_index, tasks, work, layouts, expected_apk)
            except p2c.Abort as error:
                log(f"STOP: {error}")
                return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
