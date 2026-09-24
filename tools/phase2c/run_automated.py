#!/usr/bin/env python3
"""Phase 2cの自動測定（条件O・N）を実機で流すスクリプト。

評価条件`docs/phase2c-evaluation-protocol.md`の「自動で測る部分」を機械的に行う。
課題の読みをUzumiのキー位置とフリック方向の列へ変え、`adb shell input swipe`で送り、
必要な訂正を候補一覧との照合で行い、評価用計数と最終文の正誤を課題IDごとに残す。
入力するのは`docs/phase2c-tasks.tsv`の架空の課題文だけで、試験画面（Phase2cTaskActivity）以外の
窓が前面に出たら止める。最終文・候補の文字列はファイルへ書かず、正誤だけを残す。
開発時の確認と、道具の版を変えたときの同等性の確認では、`--tasks-file`で開発用の課題（`docs/phase3a-dev-tasks.tsv`）も読める。

変換結果を待つときは、固定の待ち時間ではなく、debugビルドの受信口の`EVAL_STATUS`（IMEの状態を数値と真偽だけで返す）を
短い間隔で読み、送った押下をIMEが処理し終え、変換の仕事が残っていない状態になるまで待つ（道具の版2）。
上限の時間を超えたら、その課題を中断として記録して止める。画面の読み取り（`uiautomator dump`）は、判断に画面が要る
手順（課題の準備、候補を探す手順、最終文の照合）でだけ1回ずつ行う。

外部の依存は使わない（macOSの既存のpython3の標準ライブラリだけ）。
"""

import argparse
import os
import heapq
import json
import random
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

# 端末とadbの場所。評価条件の端末（Pixel 10 Pro）に固定する。
ADB = "/Users/marutyan/Library/Android/sdk/platform-tools/adb"
SERIAL = "57011FDCH0034X"
PACKAGE = "dev.uzumi.ime"
TASK_ACTIVITY = f"{PACKAGE}/.compat.Phase2cTaskActivity"
RECEIVER = f"{PACKAGE}/.compat.EvaluationCounterReceiver"
FIELD_ID = f"{PACKAGE}:id/phase2c_task_field"
# 学習を消す間だけ既定にするIME（利用者の現用IME）。この間は何も入力しない。
SIMEJI_IME = "com.adamrocker.android.input.simeji/.OpenWnnSimeji"
TASK_WINDOW_TITLE = "Phase 2c 課題入力"
# 試験欄の範囲（空の欄をuiautomatorで実測）。欄の選択が外れたときに、計数の前に押して選び直すためだけに使う。
FIELD_BOUNDS = (0, 211, 1080, 387)

REPO = Path(__file__).resolve().parents[2]
TASKS_TSV = REPO / "docs" / "phase2c-tasks.tsv"

# 道具の版。待ち方など、結果に影響し得る道具の手順を変えたら上げ、各結果の行に記録する（評価条件の「道具の版」）。
# 版1：固定の待ち時間（0.8秒）と画面の読み直しで変換結果を待つ（2026-09-24の本測定）。
# 版2：状態の確認口（EVAL_STATUS）で処理の終わりを待ち、画面の読み取りを判断に要る手順だけにする。
TOOL_VERSION = 2

# 評価条件で決めた課題の並べ替えseed。
ORDER_SEED = 20261001

# キーの間隔（秒）。評価条件の150 msを、input命令と次の命令の間の待ち時間として入れる。
KEY_INTERVAL_S = 0.15
# タップの押下時間（ms）。長押し（400 ms）と削除の連続削除（400 ms）に入らない短い固定値。
TAP_MS = 60
# フリックの移動量（px）と時間（ms）。判定の閾値20dp（密度356で約45 px）を十分に超える。
FLICK_PX = 100
FLICK_MS = 80
# 候補一覧をスクロールした後、表示が止まるまで待つ時間（秒）。スクロールはIMEの処理ではなく状態の確認口で待てないため、
# 版1と同じ固定の待ちと画面の読み直しを残す。
SETTLE_S = 0.8
# 状態の確認口を読む間隔（秒）。
STATUS_POLL_S = 0.05
# 押下と変換の処理の終わりを待つ上限（秒）。超えたら課題を中断として記録して止める（黙って先へ進まない）。
SETTLE_TIMEOUT_S = 10.0
# 課題の準備（欄を空にした後）で待つ上限（秒）。条件の最初の課題では辞書の読込みを待つことがあるため長めにする。
PREPARE_TIMEOUT_S = 15.0
# 1課題で行う訂正の手順の上限。これを超えたら訂正を諦めて終端操作へ進む。
MAX_FIX_STEPS = 30

# 12キーかな配列のフリック方向と文字（KeyboardLayoutData.KANA_MAPと同じ）。キーは読み上げ名で引く。
KANA_KEYS: Dict[str, Dict[str, str]] = {
    "あ": {"C": "あ", "L": "い", "U": "う", "R": "え", "D": "お"},
    "か": {"C": "か", "L": "き", "U": "く", "R": "け", "D": "こ"},
    "さ": {"C": "さ", "L": "し", "U": "す", "R": "せ", "D": "そ"},
    "た": {"C": "た", "L": "ち", "U": "つ", "R": "て", "D": "と"},
    "な": {"C": "な", "L": "に", "U": "ぬ", "R": "ね", "D": "の"},
    "は": {"C": "は", "L": "ひ", "U": "ふ", "R": "へ", "D": "ほ"},
    "ま": {"C": "ま", "L": "み", "U": "む", "R": "め", "D": "も"},
    "や": {"C": "や", "L": "（", "U": "ゆ", "R": "）", "D": "よ"},
    "ら": {"C": "ら", "L": "り", "U": "る", "R": "れ", "D": "ろ"},
    "読点": {"C": "、", "L": "。", "U": "？", "R": "！", "D": "…"},
    "わ": {"C": "わ", "L": "を", "U": "ん", "R": "ー", "D": "〜"},
}

# 「小゛゜」キーの巡回（KanaModifierと同じ）。先頭の文字へ何回押せばその文字になるかを作る。
KANA_CYCLES = [
    "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ", "かが", "きぎ", "くぐ", "けげ", "こご",
    "さざ", "しじ", "すず", "せぜ", "そぞ", "ただ", "ちぢ", "つっづ", "てで", "とど",
    "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ", "やゃ", "ゆゅ", "よょ", "わゎ",
]

# 記号面の半角のページに並ぶ文字（KeyboardLayoutData.SYMBOL_PAGESの3ページ目）。
SYMBOL_HALF = set("!?@#$%&*()-_=+/\\|~'\":;<>[]{}")
# 数字面の文字キー。
NUMERIC_CHARS = set("0123456789/*-+.")
# QWERTYの文字キー（数字行を含む）。
QWERTY_CHARS = set("1234567890qwertyuiopasdfghjkl'zxcvbnm,.")

# 面の切替キーの読み上げ名。面ごとに、押すと移る先の面を持つ。
MODE_SWITCH = {
    "KANA": {"QWERTY": "英字入力へ切り替え", "SYMBOL": "記号入力へ切り替え"},
    "QWERTY": {"KANA": "かな入力へ切り替え", "NUMERIC": "数字入力へ切り替え"},
    "NUMERIC": {"KANA": "かな入力へ切り替え", "SYMBOL": "記号入力へ切り替え"},
    "SYMBOL": {"KANA": "かな入力へ切り替え", "QWERTY": "英字入力へ切り替え", "NUMERIC": "数字入力へ切り替え"},
}


def log(message: str) -> None:
    """進行を標準エラーへ出す。本文や候補の文字列は出さない。"""
    print(time.strftime("%H:%M:%S"), message, file=sys.stderr, flush=True)


class Abort(Exception):
    """試験画面以外へ入力が届くおそれがあるなど、測定を止めるべき状態。"""


class FieldNotFocused(Abort):
    """試験画面は前面にあるが、試験欄が入力の対象になっていない状態。計数の前なら欄を押して戻せる。"""


class SettleTimeout(Abort):
    """上限の時間までに、送った押下と変換の処理が終わらなかった状態。課題を中断として記録して止める。"""


def adb(*args: str, check: bool = True, timeout: float = 60) -> str:
    """adbを一回呼び、標準出力を返す。"""
    result = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args[:2])} failed: {result.stderr.strip()[:200]}")
    return result.stdout


def shell(command: str, check: bool = True, timeout: float = 60) -> str:
    """端末のshellで一つの命令列を実行する。"""
    return adb("shell", command, check=check, timeout=timeout)


# ---- 課題と順番 ----

@dataclass
class Task:
    """課題1件。許容表記は完全一致で照合する。"""
    task_id: str
    category: str
    list_name: str
    reading: str
    accepted: List[str]


def load_tasks(path: Path = TASKS_TSV) -> List[Task]:
    """課題文のTSV（`phase2c-tasks.tsv`と同じ列）を読む。"""
    lines = path.read_text(encoding="utf-8").splitlines()
    tasks = []
    for line in lines[1:]:
        cols = line.split("\t")
        tasks.append(Task(cols[0], cols[1], cols[2], cols[3], cols[4].split("|")))
    return tasks


def automated_order(tasks: List[Task]) -> List[Task]:
    """自動測定の順番。リストA・B・Cの順に、各リストの課題を固定seedの乱数で並べ替えて連ねる。"""
    rng = random.Random(ORDER_SEED)
    ordered = []
    for list_name in "ABC":
        members = sorted((t for t in tasks if t.list_name == list_name), key=lambda t: t.task_id)
        rng.shuffle(members)
        ordered.extend(members)
    return ordered


# ---- 画面の読み取り ----

def parse_bounds(text: str) -> Tuple[int, int, int, int]:
    """`[x1,y1][x2,y2]`を数値へ直す。"""
    x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", text))
    return x1, y1, x2, y2


def center(bounds: Tuple[int, int, int, int]) -> Tuple[int, int]:
    """範囲の中心。"""
    return (bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2


@dataclass
class Snapshot:
    """uiautomatorの1回の出力から読んだ、試験に必要な情報だけ。ファイルへは残さない。"""
    field_text: str
    keys: Dict[str, Tuple[int, int, int, int]]
    candidates: List[Tuple[str, Tuple[int, int, int, int]]]
    grid: List[Tuple[str, Tuple[int, int, int, int]]]
    chips: List[str]
    buttons: Dict[str, Tuple[int, int, int, int]]
    bar_bounds: Optional[Tuple[int, int, int, int]]
    hsv_bounds: Optional[Tuple[int, int, int, int]]
    # ライブ変換で過去の文節を訂正中のとき、候補バーの左端の札に出る、その文節の読み。無ければNone。
    focused_reading: Optional[str] = None

    @property
    def mode(self) -> str:
        """表示中の面。キーの読み上げ名から決める。"""
        if "か" in self.keys and "さ" in self.keys:
            return "KANA"
        if "q" in self.keys:
            return "QWERTY"
        if "1" in self.keys:
            return "NUMERIC"
        if "英字入力へ切り替え" in self.keys and "数字入力へ切り替え" in self.keys:
            return "SYMBOL"
        return "UNKNOWN"

    @property
    def symbol_page(self) -> Optional[int]:
        """記号面の表示中のページ。記号面でなければNone。"""
        if self.mode != "SYMBOL":
            return None
        if "句点" in self.keys:
            return 0
        if "円記号" in self.keys:
            return 1
        if "感嘆符" in self.keys:
            return 2
        return None


def dump() -> Snapshot:
    """画面を読む。候補一覧の開閉などで一時的に試験欄が読めないことがあるため、読み直しを2回まで行う。
    別のアプリの窓や別のIMEを見つけた場合は、読み直さずにすぐ止める。"""
    for attempt in range(3):
        try:
            return dump_once()
        except FieldNotFocused:
            raise
        except Abort as error:
            if "task window" not in str(error) or attempt == 2:
                raise
            log(f"re-read screen: {error}")
            time.sleep(1.0)
    raise Abort("unreachable")


def dump_once() -> Snapshot:
    """全窓のUI階層を取り、試験画面が前面でIMEがUzumiであることを確かめてから読む。"""
    path = "/sdcard/p2c-dump.xml"
    for attempt in range(3):
        out = shell(f"uiautomator dump --windows {path} >/dev/null 2>&1; cat {path}; rm -f {path}", check=False)
        if "<displays" in out:
            break
        time.sleep(0.5)
    else:
        raise Abort("uiautomator dump failed")
    root = ET.fromstring(out[out.index("<"):].encode("utf-8"))
    field_text = None
    field_focused = False
    keys: Dict[str, Tuple[int, int, int, int]] = {}
    candidates = []
    grid = []
    chips = []
    buttons: Dict[str, Tuple[int, int, int, int]] = {}
    bar_bounds = None
    hsv_bounds = None
    focused_reading = None
    task_window_ok = False
    ime_seen = False
    for window in root.iter("window"):
        wtype = window.get("type")
        title = window.get("title") or ""
        active = window.get("active") == "true"
        if wtype == "TYPE_APPLICATION":
            if title == TASK_WINDOW_TITLE:
                task_window_ok = active and window.get("focused") == "true"
            elif active or window.get("focused") == "true":
                raise Abort("another application window is active")
        if wtype not in ("TYPE_APPLICATION", "TYPE_INPUT_METHOD", "TYPE_SYSTEM", "TYPE_ACCESSIBILITY_OVERLAY"):
            raise Abort(f"unexpected window type {wtype}")
        for node in window.iter("node"):
            package = node.get("package")
            if wtype == "TYPE_INPUT_METHOD" and package not in (PACKAGE, "android"):
                raise Abort("input method window is not Uzumi")
            if node.get("resource-id") == FIELD_ID:
                field_text = node.get("text") or ""
                field_focused = node.get("focused") == "true"
            if wtype != "TYPE_INPUT_METHOD" or package != PACKAGE:
                continue
            ime_seen = True
            cls = node.get("class") or ""
            desc = node.get("content-desc") or ""
            text = node.get("text") or ""
            bounds = parse_bounds(node.get("bounds"))
            if cls == "android.widget.Button" and desc:
                keys[desc] = bounds
            elif cls == "android.widget.HorizontalScrollView":
                hsv_bounds = bounds
                bar_bounds = (0, bounds[1], 1080, bounds[3])
                for item in node.iter("node"):
                    if item.get("class") == "android.widget.TextView" and item.get("text"):
                        candidates.append((item.get("text"), parse_bounds(item.get("bounds"))))
            elif cls == "android.widget.TextView" and node.get("clickable") == "true" and desc:
                buttons[desc] = bounds
    if not task_window_ok or field_text is None:
        raise Abort("task window is not focused")
    if not field_focused:
        raise FieldNotFocused("task field is not focused")
    if not ime_seen:
        raise Abort("Uzumi keyboard is not shown")
    # 候補バーの外（左右の札・ボタン）と候補一覧（キーボードの面の位置）を分ける。
    in_bar = set(t for t, _ in candidates)
    for window in root.iter("window"):
        if window.get("type") != "TYPE_INPUT_METHOD":
            continue
        for node in window.iter("node"):
            if node.get("package") != PACKAGE or node.get("class") != "android.widget.TextView":
                continue
            text = node.get("text") or ""
            bounds = parse_bounds(node.get("bounds"))
            if not text or hsv_bounds is None:
                continue
            if bounds[1] >= hsv_bounds[3] and node.get("clickable") == "true":
                grid.append((text, bounds))
            elif bounds[3] <= hsv_bounds[3] and bounds[2] <= hsv_bounds[0] and not node.get("content-desc"):
                chips.append(text)
            elif bounds[3] <= hsv_bounds[3] and bounds[2] <= hsv_bounds[0] and "訂正中の文節" in (node.get("content-desc") or ""):
                chips.append(text)
                focused_reading = text
    del in_bar
    return Snapshot(field_text, keys, candidates, grid, chips, buttons, bar_bounds, hsv_bounds, focused_reading)


# ---- 入力の計画 ----

@dataclass
class Press:
    """キーボードへの押下1回。directionはかなキーのフリック方向（C・L・U・R・D）。"""
    mode: str
    desc: str
    direction: str = "C"


def kana_press(char: str) -> Optional[List[Press]]:
    """かな・句読点一文字を、12キーの押下（フリックと「小゛゜」）の列にする。"""
    for key, directions in KANA_KEYS.items():
        for direction, value in directions.items():
            if value == char:
                return [Press("KANA", key, direction)]
    for cycle in KANA_CYCLES:
        if char in cycle and cycle.index(char) > 0:
            base = kana_press(cycle[0])
            if base is None:
                return None
            return base + [Press("KANA", "濁点、半濁点、小文字")] * cycle.index(char)
    return None


def producers(char: str) -> List[Tuple[Tuple[str, int], List[Press]]]:
    """文字を入れられる（面、記号のページ）と、その面での押下の列。"""
    options = []
    kana = kana_press(char)
    if kana is not None:
        options.append((("KANA", -1), kana))
    if char in QWERTY_CHARS:
        options.append((("QWERTY", -1), [Press("QWERTY", char)]))
    if char.isascii() and char.isalpha() and char.isupper():
        options.append((("QWERTY", -1), [Press("QWERTY", "SHIFT"), Press("QWERTY", char.lower())]))
    if char in NUMERIC_CHARS:
        options.append((("NUMERIC", -1), [Press("NUMERIC", char)]))
    if char in SYMBOL_HALF:
        options.append((("SYMBOL", 2), [Press("SYMBOL", char)]))
    return options


State = Tuple[str, int]  # （面、記号面のページ）。記号面のページは面を離れても保たれるため、常に持つ。


def mode_edges(state: State) -> List[Tuple[State, Press]]:
    """面の切替1回で移れる状態。記号面のページは最後に表示したページのまま。"""
    mode, page = state
    edges = [((target, page), Press(mode, desc)) for target, desc in MODE_SWITCH[mode].items()]
    if mode == "SYMBOL":
        edges.append((("SYMBOL", (page + 1) % 3), Press(mode, "PAGE")))
    return edges


def plan_text(text: str, start: State) -> Tuple[List[Press], State]:
    """文字列を入れる押下の列を、押下数が最小になるように決める（面の切替を含む）。"""
    start_key = (0, start[0], start[1])
    best = {start_key: 0}
    back: Dict[Tuple[int, str, int], Tuple[Tuple[int, str, int], List[Press]]] = {}
    queue = [(0, 0, start_key)]
    counter = 0
    goal = None
    while queue:
        cost, _, node = heapq.heappop(queue)
        if best.get(node, 1 << 30) < cost:
            continue
        index, mode, page = node
        if index == len(text):
            goal = node
            break
        steps: List[Tuple[Tuple[int, str, int], List[Press]]] = []
        for (target, target_page), presses in producers(text[index]):
            if target == mode and (target != "SYMBOL" or target_page == page):
                steps.append(((index + 1, mode, page), presses))
        for (target, target_page), press in mode_edges((mode, page)):
            steps.append(((index, target, target_page), [press]))
        for nxt, presses in steps:
            new_cost = cost + len(presses)
            if new_cost < best.get(nxt, 1 << 30):
                best[nxt] = new_cost
                back[nxt] = (node, presses)
                counter += 1
                heapq.heappush(queue, (new_cost, counter, nxt))
    if goal is None:
        raise ValueError("cannot type the reading")
    presses: List[Press] = []
    node = goal
    while node != start_key:
        prev, step = back[node]
        presses = step + presses
        node = prev
    return presses, (goal[1], goal[2])


def plan_switch(start: State, target_mode: str) -> List[Press]:
    """目的の面へ移るまでの最短の切替。"""
    if start[0] == target_mode:
        return []
    seen = {start}
    frontier: List[Tuple[State, List[Press]]] = [(start, [])]
    while frontier:
        nxt_frontier = []
        for state, path in frontier:
            for nxt, press in mode_edges(state):
                if nxt in seen:
                    continue
                if nxt[0] == target_mode:
                    return path + [press]
                seen.add(nxt)
                nxt_frontier.append((nxt, path + [press]))
        frontier = nxt_frontier
    raise ValueError("no route")


# ---- 押下の送信 ----

class Keyboard:
    """面ごとのキーの位置を覚え、押下をinput命令へ変えて送る。面と記号のページも追う。"""

    def __init__(self, layouts: Dict[str, Dict[str, Tuple[int, int, int, int]]]):
        self.layouts = layouts
        # 起動直後の記号面は1ページ目（句読点と括弧）から始まる。
        self.state: State = ("KANA", 0)
        self.sent = 0
        # 評価用計数を始めてから終えるまでの間か。この間は、送った押下の数とIMEが数えた押下の数が一致するまで待つ。
        self.recording = False

    def command(self, press: Press) -> str:
        """押下1回のinput命令。"""
        layout_name = press.mode
        if press.mode == "SYMBOL":
            layout_name = f"SYMBOL{self.state[1]}"
        layout = self.layouts[layout_name]
        desc = press.desc
        if desc == "SHIFT":
            desc = next(d for d in layout if "Shift" in d or "シフト" in d)
        elif desc == "PAGE":
            desc = next(d for d in layout if "のページへ切り替え" in d)
        elif press.mode in ("QWERTY", "NUMERIC", "SYMBOL") and len(desc) == 1:
            desc = spoken(desc)
        elif desc == "変換":
            # 入力中だけ「空白」の位置に出る変換キー。配置は入力していない間に読んだ「空白」と同じ。
            desc = "空白"
        if desc not in layout:
            raise Abort(f"key not found in {layout_name}")
        x, y = center(layout[desc])
        if press.direction == "C":
            return f"input swipe {x} {y} {x} {y} {TAP_MS}"
        dx, dy = {"L": (-FLICK_PX, 0), "R": (FLICK_PX, 0), "U": (0, -FLICK_PX), "D": (0, FLICK_PX)}[press.direction]
        return f"input swipe {x} {y} {x + dx} {y + dy} {FLICK_MS}"

    def advance(self, press: Press) -> None:
        """押下の後の面とページを進める。"""
        for nxt, edge in mode_edges(self.state):
            if edge.desc == press.desc:
                self.state = nxt
                return

    def send(self, presses: List[Press]) -> None:
        """押下の列を一回のshellで、一定の間隔を空けて送る。"""
        if not presses:
            return
        commands = []
        for press in presses:
            if press.mode != self.state[0]:
                raise Abort("plan and keyboard mode disagree")
            commands.append(self.command(press))
            self.advance(press)
        script = f"; sleep {KEY_INTERVAL_S}; ".join(commands)
        shell(script, timeout=300)
        self.sent += len(presses)

    def tap(self, bounds: Tuple[int, int, int, int]) -> None:
        """候補や候補バーのボタンを押す。"""
        x, y = center(bounds)
        shell(f"input swipe {x} {y} {x} {y} {TAP_MS}")
        self.sent += 1

    def sync(self, snapshot: Snapshot) -> None:
        """画面から読んだ面（記号面ならページも）で、追っている状態を合わせる。"""
        mode = snapshot.mode
        if mode == "UNKNOWN":
            return
        page = snapshot.symbol_page if mode == "SYMBOL" else None
        self.state = (mode, page if page is not None else self.state[1])


# 記号の読み上げ名（KeySpeechと同じ）。QWERTY・数字・記号面の文字キーを引くのに使う。
SPOKEN = {
    "!": "感嘆符", "?": "疑問符", "@": "アットマーク", "#": "シャープ", "$": "ドル",
    "%": "パーセント", "&": "アンド", "*": "アスタリスク", "(": "丸かっこ開き", ")": "丸かっこ閉じ",
    "-": "ハイフン", "_": "アンダーバー", "=": "イコール", "+": "プラス", "/": "スラッシュ",
    "\\": "バックスラッシュ", "|": "縦線", "~": "チルダ", "'": "アポストロフィ", "\"": "二重引用符",
    ":": "コロン", ";": "セミコロン", "<": "小なり", ">": "大なり", "[": "角かっこ開き",
    "]": "角かっこ閉じ", "{": "波かっこ開き", "}": "波かっこ閉じ", ",": "カンマ", ".": "ピリオド",
}


def spoken(char: str) -> str:
    """文字キーの読み上げ名。"""
    return SPOKEN.get(char, char)


# ---- 評価用計数 ----

COUNT_COLUMNS = [
    "counter_version", "task_id", "keys", "commits", "corrections", "terminators", "display_changes",
    "flicker", "stable_overwrites", "chosen_overwrites", "stale_results_discarded", "to_stable",
    "to_chosen", "to_provisional", "started_ms", "finished_ms", "elapsed_ms",
]


def broadcast(action: str, task_id: Optional[str] = None, extras: Optional[Dict[str, str]] = None) -> str:
    """評価用計数の受信口へbroadcastを送り、結果のdataを返す。extrasの値はshellで引用しなくてよい文字（Base64など）に限る。"""
    command = f"am broadcast -n {RECEIVER} -a dev.uzumi.ime.debug.{action}"
    if task_id:
        command += f" --es task {task_id}"
    for name, value in (extras or {}).items():
        if not re.fullmatch(r"[A-Za-z0-9_]+", name) or not re.fullmatch(r"[A-Za-z0-9+/=_.-]*", value):
            raise Abort(f"unsafe broadcast extra {name}")
        command += f" --es {name} {value}"
    out = shell(command)
    match = re.search(r'data="([^"]*)"', out, re.S)
    code = re.search(r"result=(-?\d+)", out)
    if code is None or code.group(1) != "-1":
        raise Abort(f"broadcast {action} rejected: {match.group(1) if match else out.strip()[:120]}")
    return match.group(1) if match else ""


# 課題の計数の開始と終了で受信口へ足すextraを作る関数。Phase 2cでは使わず空のまま。
# Phase 3aの道具（tools/phase3a/run_stage2.py）が、許容表記と最終文をBase64で渡すために差し替える。
START_EXTRAS: Callable[["Task"], Dict[str, str]] = lambda task: {}
FINISH_EXTRAS: Callable[["Snapshot"], Dict[str, str]] = lambda snap: {}


# ---- 1課題の実行 ----

def matches(text: str, targets: List[str]) -> bool:
    """最終文（終端の改行を除く）が許容表記のどれかと一致するか。"""
    if text.endswith("\n"):
        text = text[:-1]
    return text in targets


@dataclass
class TaskResult:
    """1課題の結果。本文は持たず、正誤と計数と手順の記録だけを持つ。"""
    condition: str
    task_id: str
    category: str
    correct: bool
    counts: Dict[str, int]
    fix_steps: List[str] = field(default_factory=list)
    status: str = "ok"
    note: str = ""
    sent_presses: int = 0
    reading_length: int = 0
    # 過去訂正の課題だけ：最初の押下の直前から最後の押下の直後までの端末の時刻の差（ms）。
    # 句点の後に訂正する手順では、評価用計数の完了時間（最後の終端操作まで）が訂正を含まないため、終点を揃えて別に測る。
    aligned_elapsed_ms: int = -1
    # 結果を作った道具の版（TOOL_VERSION）。版1の結果の行にはこの項目が無い。
    tool_version: int = TOOL_VERSION
    # 道具の側で測った、課題の準備から最終文の照合までの実時間（ms）。道具の速さの確認用で、評価の指標ではない。
    wall_ms: int = -1


# 開発時の確認用。設定すると表示と候補を標準エラーへ出す（本番の測定では設定しない）。
DEBUG = bool(os.environ.get("P2C_DEBUG"))


def read_status() -> Dict[str, int]:
    """状態の確認口（EVAL_STATUS）を1回読み、`名前=値`を数値の辞書にする。値は数値と真偽（1・0）だけ。"""
    data = broadcast("EVAL_STATUS")
    status = {}
    for item in data.strip().split("\t"):
        name, _, value = item.partition("=")
        if re.fullmatch(r"-?\d+", value):
            status[name] = int(value)
    return status


def status_settled(status: Dict[str, int], expected_presses: Optional[int]) -> bool:
    """処理が終わった状態か。試験欄へ入力していて、送った押下をすべて受け取り、変換の仕事と待ちが残っていないこと。

    ライブ変換では、最後に送った要求への結果（適用したものか、古いため捨てたもの）を受けていることも見る。
    ユーザーの操作で要求を出さずにrevisionだけが進むこと（保護された文節だけの状態など）があるため、
    「最後に適用した結果のrevisionが現在のrevisionと一致」ではなく、送った要求と受けた結果のrevisionで比べる。
    """
    if status.get("ime") != 1 or status.get("input_active") != 1 or status.get("task_field") != 1:
        return False
    if expected_presses is not None and status.get("presses") != expected_presses:
        return False
    if status.get("worker_tasks") != 0 or status.get("conversion_pending") != 0:
        return False
    if status.get("live") == 1 and status.get("live_result_revision", -1) < status.get("live_requested_revision", -1):
        return False
    return True


def wait_settled(expected_presses: Optional[int], timeout: float = SETTLE_TIMEOUT_S,
                 require_empty: bool = False) -> Dict[str, int]:
    """押下と変換の処理が終わるまで、状態の確認口を短い間隔で読んで待つ。

    変換の結果はIMEのUIスレッドへ後から届けられるため、処理が終わった状態を2回続けて同じ値で読めたときに終わりとする
    （1回目の読み取りの時点で積まれていた結果は、2回目の読み取りより先にUIスレッドで処理される）。
    [expected_presses]は計数中に送った押下の数で、IMEが数えた押下（キー操作数と終端操作数の和）と一致するまで待つ。
    多ければ二重入力の疑いとしてすぐ止める。入力先が試験欄でなければすぐ止める。上限を超えたら[SettleTimeout]。
    """
    deadline = time.monotonic() + timeout
    previous: Optional[Dict[str, int]] = None
    while True:
        status = read_status()
        if status.get("input_active") == 1 and status.get("task_field") != 1:
            raise Abort("input is not directed to the task field")
        if expected_presses is not None and status.get("presses", -1) > expected_presses:
            raise Abort("IME received more presses than sent")
        settled = status_settled(status, expected_presses) and not (require_empty and status.get("composing") != 0)
        if settled and status == previous:
            return status
        previous = status if settled else None
        if time.monotonic() > deadline:
            summary = ",".join(f"{k}={v}" for k, v in sorted(status.items()))
            raise SettleTimeout(f"not settled within {timeout:.0f}s ({summary})")
        time.sleep(STATUS_POLL_S)


def settle_snapshot(kb: "Keyboard") -> Snapshot:
    """押下と変換の処理が終わるまで状態の確認口で待ってから、画面を1回読む。"""
    wait_settled(kb.sent if kb.recording else None)
    snap = dump()
    if DEBUG:
        log(f"display={snap.field_text!r} cands={[c for c, _ in snap.candidates]} grid={[g for g, _ in snap.grid]} chips={snap.chips} buttons={list(snap.buttons)}")
    return snap


def stable_snapshot() -> Snapshot:
    """候補一覧のスクロールの後に使う。一定時間待って画面を読み、表示が止まるまで読み直す（版1の待ち方）。"""
    time.sleep(SETTLE_S)
    snap = dump()
    for _ in range(3):
        again = dump()
        if again.grid == snap.grid and again.field_text == snap.field_text:
            return again
        snap = again
    return snap


def remaining_targets(targets: List[str], committed: str) -> List[str]:
    """確定済みの前半に続く許容表記の残り。"""
    return [t[len(committed):] for t in targets if t.startswith(committed)]


def fix_explicit(kb: Keyboard, targets: List[str], steps: List[str], commit_all: bool = False,
                 reading: str = "") -> Snapshot:
    """明示変換（条件O）の訂正。変換キーで変換し、先頭文節の候補を目標と照合して選ぶ。

    先頭文節の候補のうち目標の残りの接頭辞になる最長の候補を選ぶ（第一候補なら確定操作、他は訂正操作）。
    選ぶと先頭文節が確定し、残りの読みが変換し直される。先頭文節ごとにしか選べないため、
    後ろの文節だけが誤っていても前の文節の第一候補を選んで確定していく。
    [commit_all]がtrueなら、表示が目標と一致しても未確定の文節が残る間は候補を選んで確定し続ける
    （句点を読みに含めて入力した過去訂正の課題では、句点の後に確定の操作が要るため）。
    候補の全範囲に目標が無ければ、先頭文節の区切りを伸縮する規則（plan_explicit_resize）を試す。[reading]は
    compositionの読みで、伸縮の位置を目標の表記から求めるためだけに使う。
    """
    committed = ""
    converted = False
    resizes = 0
    snap = settle_snapshot(kb)
    for _ in range(MAX_FIX_STEPS):
        display = snap.field_text[len(committed):] if snap.field_text.startswith(committed) else None
        rest = remaining_targets(targets, committed)
        if display is None:
            steps.append("lost-prefix")
            return snap
        if "" in rest and display == "":
            return snap
        if display in rest and not commit_all:
            return snap
        if not converted:
            kb.send(plan_switch(kb.state, "KANA"))
            kb.send([Press("KANA", "変換")])
            steps.append("convert")
            converted = True
            snap = settle_snapshot(kb)
            continue
        choice = pick_prefix([c for c, _ in snap.candidates], rest)
        source = snap.candidates
        seen = set(c for c, _ in snap.candidates)
        if choice is None and GRID_BUTTON in snap.buttons:
            snap, source, choice = search_grid(kb, snap, lambda values: pick_prefix(values, rest), steps, seen)
        if choice is None:
            plan = None if resizes >= MAX_RESIZES else plan_explicit_resize(targets, committed, reading, seen, steps)
            if plan is None:
                steps.append("no-candidate")
                return snap
            direction, count = plan
            long_press_arrow(kb, direction, count)
            steps.append(f"resize-head{'-' if direction < 0 else '+'}{count}")
            resizes += 1
            snap = settle_snapshot(kb)
            continue
        index, value = choice
        kb.tap(source[index][1])
        steps.append(f"pick{index}")
        committed += value
        snap = settle_snapshot(kb)
    steps.append("step-limit")
    return snap


# 候補一覧（∨）を開閉するボタンの読み上げ名。
GRID_BUTTON = "候補の一覧を開く、または閉じる"
# 候補一覧を1回スクロールする指の動き（y座標、px）と時間（ms）。一覧はキーボードの面（y 1711〜2299）を覆う。
# 慣性で飛ばないよう遅く動かし、1回で一覧の約5行分（1行52dp≒130 px）を上へ送る。
GRID_SCROLL_FROM_Y = 2250
GRID_SCROLL_TO_Y = 1800
GRID_SCROLL_MS = 700
# スクロールの上限。候補が多くてもこの回数で打ち切る。
GRID_MAX_SCROLLS = 15


def search_grid(kb: "Keyboard", snap: Snapshot, choose, steps: List[str], seen_out: Optional[set] = None):
    """候補一覧を開き、見えている範囲で目標に合う候補を探し、無ければ下へスクロールして全範囲を探す。

    見つかれば一覧を開いたまま（押す候補が見えている画面と、その候補を）返す。最後まで無ければ一覧を閉じて
    Noneを返す。スクロールはIMEへ操作を送らないため評価用計数には入らず、手順の記録（steps）にだけ残す。
    [seen_out]を渡すと、見えた候補をすべて加える（区切りの伸縮で文節の読みを候補のかな・カナから求めるため）。
    候補の文字列は記録せず、見つかったか（`grid-found-s<スクロール回数>`）と、全範囲に無かったか
    （`grid-absent-full`、打ち切った場合は`grid-absent-limit`）だけを残す。
    """
    kb.tap(snap.buttons[GRID_BUTTON])
    steps.append("open-grid")
    snap = settle_snapshot(kb)
    seen = set()
    for scrolls in range(GRID_MAX_SCROLLS + 1):
        values = [g for g, _ in snap.grid]
        choice = choose(values)
        if choice is not None:
            steps.append(f"grid-found-s{scrolls}")
            return snap, snap.grid, choice
        if seen_out is not None:
            seen_out |= set(values)
        new = set(values) - seen
        if scrolls > 0 and not new:
            steps.append("grid-absent-full")
            break
        seen |= set(values)
        if scrolls == GRID_MAX_SCROLLS:
            steps.append("grid-absent-limit")
            break
        shell(f"input swipe 540 {GRID_SCROLL_FROM_Y} 540 {GRID_SCROLL_TO_Y} {GRID_SCROLL_MS}")
        steps.append("scroll")
        snap = stable_snapshot()
    kb.tap(snap.buttons[GRID_BUTTON])
    steps.append("close-grid")
    return settle_snapshot(kb), [], None


def pick_prefix(values: List[str], rest: List[str]) -> Optional[Tuple[int, str]]:
    """目標の残りのどれかの接頭辞になる候補のうち最長のもの（同じ長さなら前の候補）。"""
    best = None
    for index, value in enumerate(values):
        if any(r.startswith(value) for r in rest) and (best is None or len(value) > len(best[1])):
            best = (index, value)
    return best


def fix_live(kb: Keyboard, targets: List[str], steps: List[str], base: int = 0, reading: str = "") -> Snapshot:
    """ライブ変換（条件N）の訂正。末尾の文節から「←」で前の文節へ移り、誤った文節の候補を選ぶ。

    右側の文節が目標と一致している前提で、対象の文節の表記を候補一覧から特定し（表示の残りの末尾に
    一致する最長の候補）、目標の対応する位置の末尾に一致しなければ、一致する候補を選ぶ。
    選んだ後に表示の右側が変わった場合は「末尾」で入力位置へ戻り、末尾から照合し直す。
    [base]は試験欄のうち確定済みの先頭の文字数で、compositionはその後ろから始まる（過去訂正の打ち直し用）。
    候補の全範囲に目標が無ければ、文節の区切りを伸縮する規則（plan_live_resize）を試し、伸縮の後は「末尾」で
    入力位置へ戻って末尾から照合し直す。[reading]は試験欄の先頭からの読みで、伸縮の位置を求めるためだけに使う。
    """
    snap = settle_snapshot(kb)
    end = len(snap.field_text)
    resizes = 0
    for _ in range(MAX_FIX_STEPS):
        display = snap.field_text
        if display in targets:
            return snap
        values = [c for c, _ in snap.candidates]
        # 読点は候補バーの対象にならず、「←」で飛ばされる。
        while end > base and display[end - 1] == "、":
            end -= 1
        suffix = display[end:]
        aligned = [t[: len(t) - len(suffix)] for t in targets if t.endswith(suffix)]
        current = None
        for value in sorted(values, key=len, reverse=True):
            if display[:end].endswith(value):
                current = value
                break
        if current is None and snap.chips and display[:end].endswith(snap.chips[-1]):
            current = snap.chips[-1]
        if current is None or not aligned:
            steps.append("unknown-segment")
            return snap
        start = end - len(current)
        if any(a.endswith(current) for a in aligned):
            if start <= base:
                steps.append("no-wrong-segment")
                return snap
            move_left(kb)
            steps.append("left")
            end = start
            snap = settle_snapshot(kb)
            continue
        choice = pick_suffix(values, current, aligned)
        source = snap.candidates
        seen = set(values)
        if choice is None and GRID_BUTTON in snap.buttons:
            # 候補バーに見えない候補は、候補一覧（∨）を開き、全範囲をスクロールして探す。
            snap, source, choice = search_grid(kb, snap, lambda vals: pick_suffix(vals, current, aligned), steps, seen)
        if choice is None:
            plan = None if resizes >= MAX_RESIZES else plan_live_resize(
                targets, suffix, base, reading, snap.focused_reading, seen, steps)
            if plan is None:
                steps.append("no-candidate")
                return snap
            resizes += 1
            if not run_live_resize(kb, plan, reading, steps):
                steps.append("no-candidate")
                return snap
            snap = settle_snapshot(kb)
            if "入力位置へ戻る" in snap.buttons:
                kb.tap(snap.buttons["入力位置へ戻る"])
                steps.append("return")
                snap = settle_snapshot(kb)
            end = len(snap.field_text)
            continue
        index, value = choice
        kb.tap(source[index][1])
        steps.append(f"pick{index}")
        before_suffix = suffix
        snap = settle_snapshot(kb)
        new_display = snap.field_text
        if new_display in targets:
            return snap
        if new_display.endswith(before_suffix) and new_display[: len(new_display) - len(before_suffix)].endswith(value):
            new_end = len(new_display) - len(before_suffix)
            start = new_end - len(value)
            if start <= base:
                steps.append("no-wrong-segment")
                return snap
            move_left(kb)
            steps.append("left")
            end = start
            snap = settle_snapshot(kb)
            continue
        if "入力位置へ戻る" in snap.buttons:
            kb.tap(snap.buttons["入力位置へ戻る"])
            steps.append("return")
            snap = settle_snapshot(kb)
        end = len(snap.field_text)
    steps.append("step-limit")
    return snap


# ---- 文節の区切りの伸縮（版2の道具で追加） ----

# 長押しの伸縮の時間（KeyRepeatPolicy）。押してから400 msで1回目の伸縮、以後300 msごとに繰り返す。
# k回の伸縮を1回の長押しで行うため、k回目と(k+1)回目の中間（400 + 300×(k−1) + 150 ms）まで押し続ける。
RESIZE_FIRST_MS = 400
RESIZE_INTERVAL_MS = 300
RESIZE_MARGIN_MS = 150
# 1課題で試す伸縮の規則の回数の上限。同じ誤りで伸縮を繰り返さないための打ち切り。
MAX_RESIZES = 3


def long_press_arrow(kb: Keyboard, direction: int, count: int) -> None:
    """←（direction<0、縮める）または→（伸ばす）を、伸縮がcount回繰り返される長さだけ押し続ける。
    画面に触れるのは1回で、評価用計数ではキー1・訂正1と数えられる（同じ長押しの繰り返しは数えない）。"""
    if kb.state[0] not in ("KANA", "QWERTY"):
        kb.send(plan_switch(kb.state, "KANA"))
    desc = "カーソルを左へ移動" if direction < 0 else "カーソルを右へ移動"
    x, y = center(kb.layouts[kb.state[0]][desc])
    hold = RESIZE_FIRST_MS + RESIZE_INTERVAL_MS * (count - 1) + RESIZE_MARGIN_MS
    shell(f"input swipe {x} {y} {x} {y} {hold}", timeout=60)
    kb.sent += 1


def char_kind(char: str) -> str:
    """語の始まりの判定に使う文字の種類。ひらがな、句読点、それ以外（漢字・カタカナ・英数字・記号）。"""
    if "\u3041" <= char <= "\u3096":
        return "hira"
    if char in "、。":
        return "punct"
    return "other"


@dataclass
class TargetAlignment:
    """目標の表記と読みの対応。to_readingは表記の位置（対応の区切り）から読みの位置への対応、
    word_startsは目標の「語の始まり」の読みの位置（先頭、句読点の後、ひらがなの後に漢字・カタカナ・英数字が
    始まる位置）。区切りの伸縮で、文節の境界を置く位置を決めるためだけに使う。"""
    target: str
    to_reading: Dict[int, int]
    word_starts: List[int]


def target_alignment(target: str, reading: str) -> Optional[TargetAlignment]:
    """目標の表記を読みへ対応付け、語の始まりを求める。対応付けられなければNone。"""
    groups = align_reading(target, reading)
    if groups is None:
        return None
    to_reading: Dict[int, int] = {}
    starts: List[int] = []
    previous = None
    for start, end, r_start, r_end in groups:
        to_reading[start] = r_start
        to_reading[end] = r_end
        kind = char_kind(target[start])
        if previous is None or previous == "punct" or (kind == "other" and previous == "hira"):
            starts.append(r_start)
        previous = kind
    return TargetAlignment(target, to_reading, starts)


def reading_from_values(values, reading: str, start: Optional[int] = None, end: Optional[int] = None) -> Optional[str]:
    """候補のうち、カタカナをひらがなへ直すと読みの指定の位置（startから、またはendまで）に一致する最長のもの。
    文節の読みを、候補に含まれるかな・カナの表記から求める。"""
    best = None
    for value in values:
        hira = "".join(to_hiragana(c) for c in value)
        if not hira:
            continue
        if end is not None:
            ok = end - len(hira) >= 0 and reading[end - len(hira):end] == hira
        else:
            ok = reading[start:start + len(hira)] == hira
        if ok and (best is None or len(hira) > len(best)):
            best = hira
    return best


def plan_live_resize(targets: List[str], suffix: str, base: int, reading: str, focused_reading: Optional[str],
                     seen, steps: List[str]):
    """ライブ変換（条件N）で、対象の文節の候補の全範囲に目標が無いときの伸縮を決める。

    右側（末尾側）は目標と一致を確かめ済みなので、文節の終わり（読みの位置b）は正しいとみなし、始まり（a）を見る。
    - aが目標の語の始まりでない（語が前の文節とまたがる）：前の文節へ「←」で移り、その語の始まり（g）まで
      前の文節を縮める。前の文節がg以降から始まる場合は、語の終わり（次の語の始まりかb）まで前の文節を伸ばす。
    - aが語の始まりで、文節の内部に語の始まりがある（文節が二語以上を含む）：末尾側の語だけが後ろに残るよう、
      内部の最後の語の始まりまで対象の文節を縮める。
    - どちらでもなければNone（区切りでは直せない）。
    """
    for target in targets:
        if not target.endswith(suffix):
            continue
        alignment = target_alignment(target, reading)
        if alignment is None:
            continue
        b = alignment.to_reading.get(len(target) - len(suffix))
        base_r = alignment.to_reading.get(base)
        if b is None or base_r is None:
            continue
        seg = focused_reading if focused_reading else reading_from_values(seen, reading, end=b)
        if not seg or reading[b - len(seg):b] != seg:
            steps.append("resize-no-reading")
            return None
        a = b - len(seg)
        starts = alignment.word_starts
        if a > base_r and a not in starts:
            g = max((w for w in starts if w < a), default=None)
            h = min([w for w in starts if w > a] + [b])
            if g is None or g < base_r:
                steps.append("resize-none")
                return None
            return ("prev", g, h, a, b)
        interior = [w for w in starts if a < w < b]
        if interior:
            return ("self", b - max(interior), a, b)
        steps.append("resize-none")
        return None
    steps.append("resize-no-alignment")
    return None


def verify_resize(kb: Keyboard, reading: str, start: int, expected_end: int, steps: List[str]) -> None:
    """伸縮の後、訂正中の文節の読み（札）が期待した範囲か確かめる。ずれていれば1回だけ長押しで合わせる。"""
    snap = settle_snapshot(kb)
    actual = snap.focused_reading
    if actual is None or reading[start:start + len(actual)] != actual:
        steps.append("resize-unverified")
        return
    diff = (expected_end - start) - len(actual)
    if diff != 0:
        long_press_arrow(kb, 1 if diff > 0 else -1, abs(diff))
        steps.append(f"resize-adjust{diff:+d}")


def run_live_resize(kb: Keyboard, plan, reading: str, steps: List[str]) -> bool:
    """plan_live_resizeの決めた伸縮を行う。行えなければfalse。"""
    if plan[0] == "self":
        _, count, a, b = plan
        long_press_arrow(kb, -1, count)
        steps.append(f"resize-self-{count}")
        verify_resize(kb, reading, a, b - count, steps)
        return True
    _, g, h, a, b = plan
    move_left(kb)
    steps.append("left")
    snap = settle_snapshot(kb)
    previous = snap.focused_reading
    if not previous or reading[a - len(previous):a] != previous:
        steps.append("resize-no-reading")
        return False
    p = a - len(previous)
    if g > p:
        long_press_arrow(kb, -1, a - g)
        steps.append(f"resize-prev-{a - g}")
        verify_resize(kb, reading, p, g, steps)
        return True
    if h > a:
        long_press_arrow(kb, 1, h - a)
        steps.append(f"resize-prev+{h - a}")
        verify_resize(kb, reading, p, h, steps)
        return True
    steps.append("resize-none")
    return False


def plan_explicit_resize(targets: List[str], committed: str, reading: str, seen, steps: List[str]):
    """明示変換（条件O）で、先頭文節の候補の全範囲に目標が無いときの伸縮を決める。返り値は（向き、回数）。

    先頭文節の始まり（a）は確定済みの前半の直後で正しく、終わり（b）を見る（条件Nと左右を入れ替えた同じ規則）。
    - bが目標の語の始まりでない（語が次の文節とまたがる）：先頭文節の内部にその語の始まり（g）があればgまで縮め、
      無ければ語の終わり（次の語の始まりか読みの終わり）まで伸ばす。
    - bが語の始まり（または読みの終わり）で、文節の内部に語の始まりがある：先頭側の語だけが残るよう、内部の最初の
      語の始まりまで縮める。
    - どちらでもなければNone。
    """
    for target in targets:
        if not target.startswith(committed):
            continue
        alignment = target_alignment(target, reading)
        if alignment is None:
            continue
        a = alignment.to_reading.get(len(committed))
        if a is None:
            continue
        seg = reading_from_values(seen, reading, start=a)
        if not seg:
            steps.append("resize-no-reading")
            return None
        b = a + len(seg)
        end_r = len(reading)
        starts = alignment.word_starts
        interior = [w for w in starts if a < w < b]
        if b < end_r and b not in starts:
            if interior:
                return (-1, b - max(interior))
            h = min([w for w in starts if w > b] + [end_r])
            return (1, h - b)
        if interior:
            return (-1, b - min(interior))
        steps.append("resize-none")
        return None
    steps.append("resize-no-alignment")
    return None


def move_left(kb: Keyboard) -> None:
    """「←」を押す。数字面と記号面には無いため、かなの面へ移ってから押す。"""
    if kb.state[0] not in ("KANA", "QWERTY"):
        kb.send(plan_switch(kb.state, "KANA"))
    kb.send([Press(kb.state[0], "カーソルを左へ移動")])


def pick_suffix(values: List[str], current: str, aligned: List[str]) -> Optional[Tuple[int, str]]:
    """対象の文節の表記を置き換えると、目標の対応する位置の末尾に一致する候補（最長、同じ長さなら前）。"""
    best = None
    for index, value in enumerate(values):
        if value != current and any(a.endswith(value) for a in aligned):
            if best is None or len(value) > len(best[1]):
                best = (index, value)
    return best


def prepare_task(kb: Keyboard, task_id: str) -> Snapshot:
    """試験欄を空にして課題IDを表示し、かなの面へ戻す（計数の開始前なので数えない）。"""
    shell(f"am start -n {TASK_ACTIVITY} --es task {task_id}")
    try:
        snap = prepared_snapshot()
    except FieldNotFocused as error:
        # 試験画面は前面にあるが欄の選択が外れている。計数を始める前なので、試験欄を一度押して選び直す（数えない）。
        log(f"refocus before {task_id}: {error}")
        x, y = center(FIELD_BOUNDS)
        shell(f"input swipe {x} {y} {x} {y} {TAP_MS}")
        time.sleep(1.0)
        shell(f"am start -n {TASK_ACTIVITY} --es task {task_id}")
        snap = prepared_snapshot()
    except Abort as error:
        # 計数を始める前で、まだ何も入力していない。画面の切替の途中だった場合に備えて一度だけ待って読み直す。
        log(f"retry before {task_id}: {error}")
        time.sleep(3.0)
        snap = prepared_snapshot()
    kb.sync(snap)
    if kb.state[0] != "KANA":
        kb.send(plan_switch(kb.state, "KANA"))
        snap = settle_snapshot(kb)
        kb.sync(snap)
    if kb.state[0] != "KANA":
        raise Abort("cannot return to the kana keyboard")
    return snap


def prepared_snapshot() -> Snapshot:
    """課題の準備で、試験欄が空になりIMEの処理が終わるまで待ってから画面を読む（計数の前なので何も数えない）。

    `am start`は画面が欄を空にする前に戻ることがあるため、空でなければ短く待って2回まで読み直してから止める。
    """
    for attempt in range(3):
        try:
            wait_settled(None, timeout=PREPARE_TIMEOUT_S, require_empty=True)
        except SettleTimeout:
            # 試験欄が入力の対象になっていない場合は、画面を読むとFieldNotFocusedになり、呼び出し側が欄を選び直す。
            dump()
            raise
        snap = dump()
        if snap.field_text == "":
            return snap
        log("field is not empty yet; re-read")
        time.sleep(0.3)
    raise Abort("field is not empty before the task")


def device_ms() -> int:
    """端末の時計の現在時刻（epochからのミリ秒）。評価用計数の時刻と同じ時計を使う。"""
    out = shell("date +%s%N").strip()
    return int(out) // 1_000_000


def to_hiragana(char: str) -> str:
    """カタカナ一文字をひらがなへ直す。それ以外はそのまま返す。"""
    code = ord(char)
    return chr(code - 0x60) if 0x30A1 <= code <= 0x30F6 else char


def align_reading(surface: str, reading: str) -> Optional[List[Tuple[int, int, int, int]]]:
    """表記と読みを対応付ける。かな・記号は読みの同じ一文字に、続いた漢字などは読みの1文字以上に対応させる。

    返り値は（表記の開始、終了、読みの開始、終了）の列。過去訂正の打ち直しで、表記の位置から読みの位置を
    求めるためだけに使う。対応が複数あり得る場合は、漢字の読みを短い方から試して最初に見つかったものを使う。
    """
    groups: List[Tuple[int, int, bool]] = []
    i = 0
    while i < len(surface):
        char = surface[i]
        if "\u3041" <= to_hiragana(char) <= "\u3096" or not char.isalpha():
            groups.append((i, i + 1, True))
            i += 1
            continue
        j = i
        while j < len(surface) and surface[j].isalpha() and not ("\u3041" <= to_hiragana(surface[j]) <= "\u3096"):
            j += 1
        groups.append((i, j, False))
        i = j

    def solve(g: int, r: int) -> Optional[List[Tuple[int, int, int, int]]]:
        if g == len(groups):
            return [] if r == len(reading) else None
        start, end, literal = groups[g]
        if literal:
            if r < len(reading) and to_hiragana(surface[start]) == reading[r]:
                rest = solve(g + 1, r + 1)
                return None if rest is None else [(start, end, r, r + 1)] + rest
            return None
        for length in range(1, len(reading) - r + 1):
            rest = solve(g + 1, r + length)
            if rest is not None:
                return [(start, end, r, r + length)] + rest
        return None

    return solve(0, 0)


def run_past_correction(kb: Keyboard, task: Task, condition: str, steps: List[str]) -> Tuple[Snapshot, int]:
    """過去訂正の課題を評価条件どおりに行う：句点まで読みを入力してから、前の語を直す。

    条件N：句点で表示が確定するため、目標と最初に食い違う語の先頭まで削除キーで消し、その語から後の読みを
    打ち直し、ライブ変換の訂正（fix_live）をしてから句点を入れる。確定済みの文字列を候補で直す操作は無いため、
    削除と打ち直しが候補の選択だけで直す手順の下限になる。
    条件O：明示変換では句点も読みに入り表示は確定しないため、句点まで入れてから変換し、先頭の文節から
    候補を選んで最後まで確定する（fix_explicitのcommit_all）。
    どちらも、最初の押下の直前と最後の押下の直後の端末の時刻を返し、完了時間の終点を揃える。
    """
    first_ms = device_ms()
    presses, _ = plan_text(task.reading, kb.state)
    kb.send(presses)
    if condition == "O":
        snap = fix_explicit(kb, task.accepted, steps, commit_all=True, reading=task.reading)
        return snap, device_ms() - first_ms
    snap = settle_snapshot(kb)
    committed = snap.field_text
    if committed in task.accepted:
        return snap, device_ms() - first_ms
    target = task.accepted[0]
    prefix = 0
    while prefix < min(len(committed), len(target)) and committed[prefix] == target[prefix]:
        prefix += 1
    alignment = align_reading(target, task.reading)
    group = None if alignment is None else next((g for g in alignment if g[0] <= prefix < g[1]), None)
    if group is None:
        steps.append("no-alignment")
        return snap, device_ms() - first_ms
    keep, reading_start = group[0], group[2]
    kb.send(plan_switch(kb.state, "KANA"))
    kb.send([Press("KANA", "削除")] * (len(committed) - keep))
    steps.append(f"delete{len(committed) - keep}")
    snap = settle_snapshot(kb)
    if snap.field_text != committed[:keep]:
        steps.append("delete-mismatch")
        return snap, device_ms() - first_ms
    retype = task.reading[reading_start:-1]
    presses, _ = plan_text(retype, kb.state)
    kb.send(presses)
    steps.append(f"retype{len(retype)}")
    snap = fix_live(kb, [t[:-1] for t in task.accepted], steps, base=keep, reading=task.reading[:-1])
    kb.send(plan_switch(kb.state, "KANA"))
    kb.send([Press("KANA", "読点", "L")])
    return snap, device_ms() - first_ms


def run_task(kb: Keyboard, task: Task, condition: str) -> TaskResult:
    """1課題を、計数の開始→読み→訂正→終端操作→計数の終了→最終文の照合の順に行う。"""
    prepare_task(kb, task.task_id)
    if task.category == "過去訂正":
        return run_past_task(kb, task, condition)
    reading = task.reading
    terminator = "。" if reading.endswith("。") else "ENTER"
    body = reading[:-1] if terminator == "。" else reading
    presses, _ = plan_text(body, kb.state)
    steps: List[str] = []
    broadcast("EVAL_START", task.task_id, START_EXTRAS(task))
    kb.sent = 0
    kb.recording = True
    try:
        kb.send(presses)
        # 終端操作の前の表示と照合する目標。句点で終える課題は、許容表記から末尾の句点を除いたもの。
        body_targets = [t[:-1] for t in task.accepted if t.endswith("。")] if terminator == "。" else task.accepted
        if condition == "O":
            snap = fix_explicit(kb, body_targets, steps, reading=body)
        else:
            snap = fix_live(kb, body_targets, steps, reading=body)
        if terminator == "。":
            kb.send(plan_switch(kb.state, "KANA"))
            kb.send([Press("KANA", "読点", "L")])
        else:
            kb.send([Press(kb.state[0], "改行")])
        final = settle_snapshot(kb)
    except Abort:
        kb.recording = False
        try:
            broadcast("EVAL_FINISH")
        except Exception:
            pass
        raise
    kb.recording = False
    row = broadcast("EVAL_FINISH", extras=FINISH_EXTRAS(final)).strip()
    values = row.split("\t")
    counts = {name: int(v) if re.fullmatch(r"-?\d+", v) else v for name, v in zip(COUNT_COLUMNS, values)}
    correct = matches(final.field_text, task.accepted)
    result = TaskResult(condition, task.task_id, task.category, correct, counts, steps,
                        sent_presses=kb.sent, reading_length=len(reading))
    log(f"{condition} {task.task_id} correct={correct} keys={counts.get('keys')} commits={counts.get('commits')} "
        f"corr={counts.get('corrections')} steps={','.join(steps) or '-'} sent={kb.sent}")
    return result


def run_past_task(kb: Keyboard, task: Task, condition: str) -> TaskResult:
    """過去訂正の課題1件を、計数の開始→run_past_correction→計数の終了→最終文の照合の順に行う。"""
    steps: List[str] = []
    broadcast("EVAL_START", task.task_id, START_EXTRAS(task))
    kb.sent = 0
    kb.recording = True
    try:
        _, aligned = run_past_correction(kb, task, condition, steps)
        final = settle_snapshot(kb)
    except Abort:
        kb.recording = False
        try:
            broadcast("EVAL_FINISH")
        except Exception:
            pass
        raise
    kb.recording = False
    values = broadcast("EVAL_FINISH", extras=FINISH_EXTRAS(final)).strip().split("\t")
    counts = {name: int(v) if re.fullmatch(r"-?\d+", v) else v for name, v in zip(COUNT_COLUMNS, values)}
    correct = matches(final.field_text, task.accepted)
    result = TaskResult(condition, task.task_id, task.category, correct, counts, steps,
                        sent_presses=kb.sent, reading_length=len(task.reading), aligned_elapsed_ms=aligned)
    log(f"{condition} {task.task_id} correct={correct} keys={counts.get('keys')} commits={counts.get('commits')} "
        f"corr={counts.get('corrections')} aligned_ms={aligned} steps={','.join(steps) or '-'} sent={kb.sent}")
    return result


# ---- 条件の準備 ----

# 条件の開始前に消す学習のファイル。IME側の学習、Mozcの文節・境界・予測の履歴に加え、Mozcが全角・半角の選択を
# 覚える`cform.db`も消す（アプリの「学習の全消去」は`cform.db`を消さないが、前の条件や試行で選んだ文字幅が
# 次の条件の変換結果を変えるため）。
LEARNING_FILES = ["no_backup/learning.tsv", "no_backup/mozc/profile/segment.db",
                  "no_backup/mozc/profile/boundary.db", "no_backup/mozc/profile/history.db",
                  "no_backup/mozc/profile/.history.db", "no_backup/mozc/profile/cform.db"]


def prepare_condition(condition: str, work: Path) -> None:
    """アプリを止め、学習（Mozcのprofileの学習とIME側の学習）を消し、ライブ変換の設定を条件に合わせる。"""
    live = "true" if condition == "N" else "false"
    prefs = work / "uzumi_settings.xml"
    prefs.write_text(
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
        f"    <boolean name=\"live_conversion_enabled\" value=\"{live}\" />\n</map>\n",
        encoding="utf-8",
    )
    # Uzumiが既定IMEのままだと、止めた直後にシステムが入れ直し、Mozcが学習を読み込んだ後でファイルを消すことになる
    # （メモリ上の学習が書き戻される）。止めている間だけ既定IMEを元のSimejiにし、プロセスが無いことを確かめて消す。
    # この間は何も入力しない。
    shell(f"ime set {SIMEJI_IME}")
    shell(f"am force-stop {PACKAGE}")
    time.sleep(1.0)
    if shell(f"pidof {PACKAGE}", check=False).strip():
        raise Abort("Uzumi is still running after force-stop")
    adb("push", str(prefs), "/data/local/tmp/uzumi_settings.xml")
    shell("run-as dev.uzumi.ime rm -f " + " ".join(LEARNING_FILES))
    shell("run-as dev.uzumi.ime cp /data/local/tmp/uzumi_settings.xml shared_prefs/uzumi_settings.xml")
    shell("rm -f /data/local/tmp/uzumi_settings.xml")
    left = shell("run-as dev.uzumi.ime ls " + " ".join(LEARNING_FILES) + " 2>&1", check=False)
    if shell(f"pidof {PACKAGE}", check=False).strip() or "No such file" not in left or any(
        f.split("/")[-1] + "\n" in left for f in LEARNING_FILES
    ):
        raise Abort("learning files were not cleared while Uzumi was stopped")
    shell(f"ime set {PACKAGE}/.UzumiInputMethodService")
    shell(f"am start -n {TASK_ACTIVITY} --es task SETUP")
    time.sleep(3.0)
    # 学習キャッシュの読込みの後に入力欄を開き直し、最初の課題から学習が有効な状態にする。
    shell(f"am start -n {TASK_ACTIVITY} --es task SETUP2")
    time.sleep(1.5)


def probe_layouts() -> Dict[str, Dict[str, Tuple[int, int, int, int]]]:
    """各面のキーの位置を画面から読む。何も入力せず、面の切替だけを押す。"""
    shell(f"am start -n {TASK_ACTIVITY} --es task PROBE")
    time.sleep(1.0)
    layouts = {}
    snap = dump()
    if snap.mode != "KANA":
        raise Abort("probe must start from kana")
    layouts["KANA"] = snap.keys

    def press(desc: str) -> Snapshot:
        x, y = center(dump().keys[desc])
        shell(f"input swipe {x} {y} {x} {y} {TAP_MS}")
        time.sleep(0.4)
        return dump()

    snap = press("英字入力へ切り替え")
    layouts["QWERTY"] = snap.keys
    snap = press("数字入力へ切り替え")
    layouts["NUMERIC"] = snap.keys
    snap = press("記号入力へ切り替え")
    for _ in range(3):
        layouts[f"SYMBOL{snap.symbol_page}"] = snap.keys
        page_key = next(d for d in snap.keys if "のページへ切り替え" in d)
        snap = press(page_key)
    press("かな入力へ切り替え")
    return layouts


def main() -> int:
    """測定の入口。probeで面の配置を読み、runで条件を一つ流す。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["probe", "run", "order"])
    parser.add_argument("--condition", choices=["O", "N"])
    parser.add_argument("--tasks", help="課題IDをカンマ区切りで指定（開発の確認用）")
    parser.add_argument("--tasks-file", default=str(TASKS_TSV),
                        help="課題文のTSV。既定はphase2c-tasks.tsv。同等性の確認では開発用のphase3a-dev-tasks.tsvを渡す")
    parser.add_argument("--work", required=False, default=str(REPO / ".local-build" / "phase2c" / time.strftime("%Y-%m-%d")))
    parser.add_argument("--out", help="結果を書くjsonlのファイル名（既定はresults-<条件>.jsonl）")
    parser.add_argument("--resume-from", help="中断した条件を、この課題IDから続ける")
    parser.add_argument("--no-prepare", action="store_true", help="学習の消去と設定の変更を行わない")
    args = parser.parse_args()
    work = Path(args.work)
    work.mkdir(parents=True, exist_ok=True)
    tasks_file = Path(args.tasks_file)
    tasks = load_tasks(tasks_file)
    if args.command == "order":
        for task in automated_order(tasks):
            print(task.task_id)
        return 0
    if args.command == "probe":
        layouts = probe_layouts()
        (work / "layouts.json").write_text(json.dumps(layouts, ensure_ascii=False, indent=1), encoding="utf-8")
        for name, keys in layouts.items():
            print(name, len(keys), sorted(keys)[:60])
        return 0
    layouts = json.loads((work / "layouts.json").read_text(encoding="utf-8"))
    layouts = {k: {d: tuple(b) for d, b in v.items()} for k, v in layouts.items()}
    by_id = {t.task_id: t for t in tasks}
    if args.tasks:
        selected = [by_id[i] for i in args.tasks.split(",")]
    elif tasks_file.resolve() == TASKS_TSV.resolve():
        selected = automated_order(tasks)
    else:
        # 開発用の課題はリストA・B・Cに分かれていないため、ファイルの順に流す。
        selected = tasks
    if args.resume_from:
        # 中断した条件を、学習と計数の記録を消さずに、中断した課題から同じ順番で続ける。
        ids = [t.task_id for t in selected]
        selected = selected[ids.index(args.resume_from):]
    if not args.no_prepare and not args.resume_from:
        prepare_condition(args.condition, work)
    if not args.tasks and not args.resume_from:
        # 本番の測定では、開発時の試行の行が混ざらないよう、端末内の計数の記録を条件の開始時に取り出して消す。
        previous = broadcast("EVAL_DUMP")
        (work / f"counts-before-{args.condition}.tsv").write_text(previous, encoding="utf-8")
        broadcast("EVAL_CLEAR")
        (work / f"order-{args.condition}.txt").write_text(
            f"seed={ORDER_SEED}\ntool_version={TOOL_VERSION}\n" + "\n".join(t.task_id for t in selected) + "\n", encoding="utf-8")
    kb = Keyboard(layouts)
    out_path = work / (args.out or f"results-{args.condition}.jsonl")
    with out_path.open("a", encoding="utf-8") as out:
        for task in selected:
            begin = time.monotonic()
            try:
                result = run_task(kb, task, args.condition)
            except Abort as error:
                log(f"ABORT at {task.task_id}: {error}")
                out.write(json.dumps({"condition": args.condition, "task_id": task.task_id, "status": "abort",
                                      "note": str(error), "tool_version": TOOL_VERSION}, ensure_ascii=False) + "\n")
                return 2
            result.wall_ms = int((time.monotonic() - begin) * 1000)
            out.write(json.dumps(result.__dict__, ensure_ascii=False) + "\n")
            out.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
