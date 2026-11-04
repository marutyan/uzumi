"""研究の図の既定スタイルと描画の補助。寸法、文字、色、box、colormapを一か所に持ち、全ての図で同じ役割に同じ見た目を使う。

使い方: このファイルをプロジェクトへ複製し（置き場は各SKILL.mdに従う）、`import paperstyle as ps` の後に `ps.use("cvpr")` を呼ぶ。
matplotlib 3.6以降を前提とする（`layout="none"`を使うため）。
"""
import matplotlib as mpl
import matplotlib.patheffects as pe
import numpy as np
from matplotlib.lines import Line2D
from matplotlib.patches import Rectangle

# 会議ごとの本文フォント、図の幅（in）、基準の文字サイズ（pt）。公式テンプレートとtueplotsで確認した値
VENUES = {
    "cvpr": dict(serif="times", col=3.25, full=6.875, base=8),
    "iccv": dict(serif="times", col=3.25, full=6.875, base=8),
    "icml": dict(serif="times", col=3.25, full=6.75, base=8),
    "neurips": dict(serif="times", col=5.5, full=5.5, base=9),
    "iclr": dict(serif="times", col=5.5, full=5.5, base=9),
    # ECCVはComputer Modernで、テンプレートがTimesの使用を禁じている
    "eccv": dict(serif="cm", col=4.8, full=4.8, base=8),
    # 画面で読む分析図と相談資料の図。明朝体を使わず端末にある日本語フォントで描き、線と目盛りをscale倍に太くする
    "screen": dict(serif=None, col=6.0, full=12.0, base=12, scale=1.7),
}
# 図の既定の高さ（in）。幅は段幅で決まるため、描画領域は余白ではなく高さで広げる。画像を並べる図は縦横比から決める
COL_H = 2.6
FULL_H = 2.4
# 画面用（screen）の全幅の図の高さ（in）。全幅12inに2×2のパネルを並べる目安
SCREEN_H = 9.0

# 掲載用の図の文字はTimes New Romanに固定する。互換フォントへ黙って落とすと端末ごとに字形と幅が変わるため
SERIF_FONT = "Times New Roman"
# 画面用の図で使う日本語フォントの候補（macOS、Linuxの順）。端末にあるものを前から使う
JAPANESE_FONTS = ["Hiragino Sans", "Hiragino Kaku Gothic ProN", "Noto Sans CJK JP", "Noto Sans JP",
                  "IPAexGothic", "IPAGothic", "TakaoGothic", "Yu Gothic", "YuGothic", "Meiryo"]
# 日本語フォントに欠ける記号だけを補うフォント。matplotlibに同梱されているため、どの端末にもある
FALLBACK_FONT = "DejaVu Sans"

# ---- 線グラフ・棒グラフの手法の色と線種。白黒印刷でも線種とマーカーで区別できる ----
OKABE_ITO = ["#0072B2", "#D55E00", "#009E73", "#CC79A7", "#E69F00", "#56B4E9", "#000000", "#F0E442"]
OURS = dict(color="#D55E00", ls="-", marker="o", lw=1.6)
# 提案手法から要素を外した版。提案手法と同じ暖色系にして、表の構造を色で表す
OURS_VARIANTS = [dict(color="#E69F00", ls="--", marker="o", lw=1.2),
                 dict(color="#B8860B", ls=(0, (1, 1)), marker="o", lw=1.2)]
# 比較手法は青、緑、ピンク、水色、黒の順に割り当てる。登場順ではなく、論文全体で手法名ごとに一度決めた番号を使う
BASELINES = [dict(color="#0072B2", ls=(0, (5, 1.5)), marker="s", lw=1.1),
             dict(color="#009E73", ls=(0, (3, 1, 1, 1)), marker="^", lw=1.1),
             dict(color="#CC79A7", ls=(0, (1, 1)), marker="D", lw=1.1),
             dict(color="#56B4E9", ls=(0, (6, 2, 1, 2, 1, 2)), marker="v", lw=1.1),
             dict(color="#000000", ls=(0, (2, 2)), marker="x", lw=1.0)]
NEUTRAL = "#7a7a7a"  # 参照線、補助の注記、どの手法にも属さない要素

# ---- 画像上のbox。写真の上で一目で分かる原色にする。線グラフの手法色とは色相だけをそろえる ----
BOX = dict(gt="#00FF00", ours="#FF0000", baseline="#00FFFF", fn="#FFFF00")
# 破線と点線の間隔はmatplotlibが線の太さに比例させるため、太さを変えても見た目の比が保たれる
BOX_LS = dict(solid="-", fp=(0, (4, 2)), fn=(0, (1.5, 1.5)))
# 画像上に打つ点（参照点、sampling点）の縁。背景の色に埋もれないよう白で縁取る
POINT_EDGE = "white"

# ---- colormapは量の種類で決める。種類が同じなら全ての図で同じcolormapにする ----
CMAPS = dict(
    value="viridis",      # 0以上の量（重み、確率、スコア）
    magnitude="magma",    # 勾配や誤差の大きさ
    signed="RdBu_r",      # 符号のある量や差（0を中心にTwoSlopeNormで使う）
    cam="jet",            # 画像に重ねるCAMやattention（CV論文の慣習に合わせる）
    ordinal="plasma",     # 層番号やepochなど順序だけに意味がある量。正解boxの緑と紛れないよう緑を含まないもの
    depth="Spectral_r",   # depth（Depth Anythingの慣習）
)


def use(venue="cvpr"):
    """会議（画面用は"screen"）に合わせてrcParamsを設定し、その寸法を返す。既定はCV系のCVPR。"""
    v = VENUES[venue]
    base = v["base"]
    # 線、マーカー、目盛りの太さと長さの倍率。掲載用の図は1
    k = v.get("scale", 1.0)
    if v["serif"] == "times":
        require_font(SERIF_FONT)
    mpl.rcdefaults()
    mpl.rcParams.update(_font_params(v["serif"]))
    mpl.rcParams.update({
        "font.size": base, "axes.labelsize": base, "axes.titlesize": base,
        # 目盛りと凡例は基準より1pt小さく、7ptを下限にする（tueplotsの基準-2ptは6ptになり小さすぎる）
        "xtick.labelsize": max(base - 1, 7), "ytick.labelsize": max(base - 1, 7), "legend.fontsize": max(base - 1, 7),
        "axes.linewidth": 0.6 * k, "lines.linewidth": 1.2 * k, "lines.markersize": 4 * k,
        "xtick.major.width": 0.6 * k, "ytick.major.width": 0.6 * k, "xtick.major.size": 3 * k, "ytick.major.size": 3 * k,
        "xtick.minor.width": 0.4 * k, "ytick.minor.width": 0.4 * k,
        "xtick.minor.size": 1.8 * k, "ytick.minor.size": 1.8 * k,
        # 四辺の枠と縦横の薄いgrid。ML論文で最も見慣れた見た目に合わせる
        "axes.grid": True, "grid.linewidth": 0.4 * k, "grid.color": "#e0e0e0",
        "axes.axisbelow": True,
        "legend.frameon": True, "legend.framealpha": 0.9, "legend.edgecolor": "#bbbbbb",
        "legend.fancybox": False, "legend.handlelength": 2.2,
        "axes.prop_cycle": mpl.cycler(color=OKABE_ITO),
        "image.cmap": CMAPS["value"],
        # 余白を詰めすぎると見出しが上端で切れるため、constrained layoutの余白は既定値のままにする
        "figure.constrained_layout.use": True,
        "savefig.dpi": 300, "pdf.fonttype": 42, "ps.fonttype": 42,
    })
    return v


def _font_params(serif):
    """VENUESのserifの値から文字のrcParamsを作る。Noneは画面用で、端末にある日本語フォントと記号の補いを使う。"""
    if serif == "times":
        return {"font.family": "serif", "font.serif": [SERIF_FONT], "mathtext.fontset": "stix"}
    if serif == "cm":
        return {"font.family": "serif", "font.serif": ["CMU Serif", "Latin Modern Roman", "DejaVu Serif"],
                "mathtext.fontset": "cm"}
    from matplotlib import font_manager
    installed = {f.name for f in font_manager.fontManager.ttflist}
    japanese = [name for name in JAPANESE_FONTS if name in installed][:1]
    if not japanese:
        # 日本語を描けないまま記号用のフォントだけで続けると字形が欠けるため、足りないフォントを示して警告する。
        # Skillの実行（-W error::UserWarning）ではここで止まり、英語だけの図は警告を出して描ける
        import warnings
        warnings.warn("画面用の図に使える日本語フォントがありません。次のどれかを入れてください: "
                      + ", ".join(JAPANESE_FONTS), UserWarning, stacklevel=3)
    # font.familyに並べたフォントは、字形が欠けた文字だけ次のフォントで描かれる（matplotlib 3.6以降）
    return {"font.family": japanese + [FALLBACK_FONT],
            "mathtext.fontset": "dejavusans"}


def require_font(name):
    """フォントが入っていなければ、インストール方法を示して止める。"""
    from matplotlib import font_manager
    try:
        font_manager.findfont(name, fallback_to_default=False)
    except ValueError:
        raise RuntimeError(
            f"{name} が見つかりません。Ubuntuなら `sudo apt install ttf-mscorefonts-installer` の後、"
            "`rm -rf ~/.cache/matplotlib` でフォントキャッシュを消してから再実行してください。") from None


def method_styles(ours=None, variants=(), baselines=()):
    """手法名から線の見た目への対応表を作る。論文ごとに一度だけ呼び、全ての図でこの表を使う。

    oursは提案手法、variantsは提案手法から要素を外した版、baselinesは比較手法。
    どれが提案手法か決まっていない比較ではoursを省き、全てをbaselinesへ渡して強調しない。
    """
    if len(variants) > len(OURS_VARIANTS) or len(baselines) > len(BASELINES):
        raise ValueError("手法の数が色の数を超えています。図を分けるか、比較する手法を絞ってください。")
    if variants and ours is None:
        raise ValueError("variantsは提案手法から要素を外した版なので、oursと一緒に渡してください。")
    names = ([] if ours is None else [ours]) + list(variants) + list(baselines)
    if len(set(names)) != len(names):
        raise ValueError(f"同じ手法名が複数の役割に入っています: {names}")
    table = {} if ours is None else {ours: OURS}
    table.update(zip(variants, OURS_VARIANTS))
    table.update(zip(baselines, BASELINES))
    return table


def ordinal_colors(n, on_image=False):
    """順序のある値（層番号、epoch、λなどの設定値）をn色に離散化する。白地では薄くて見えない明るい端を使わない。"""
    top = 1.0 if on_image else 0.8
    return [mpl.colormaps[CMAPS["ordinal"]](v) for v in np.linspace(0.0, top, n)]


def line_kw(style, markevery=None, **over):
    """OURS / BASELINES の辞書をplot()の引数へ変換する。markeveryを渡すとマーカーを間引いて付ける。"""
    kw = dict(color=style["color"], ls=style["ls"], lw=style["lw"])
    if markevery is not None:
        kw.update(marker=style["marker"], markevery=markevery, ms=3.2,
                  mfc="white" if style is not OURS else style["color"], mew=0.8)
    kw.update(over)
    return kw


def box_lw_for_width(width_in):
    """画像の表示幅（in）からboxの線の太さ（pt）を決める。小さい画像で線が画像を潰さず、大きい画像で細すぎないようにする。
    matplotlibの図とアーキテクチャ図（research-diagram）の両方でこの式を使う。"""
    # 幅1.5inで約1.0pt、3.25inで1.5pt（上限）、0.6in以下で0.6pt（下限）
    return float(np.clip(0.35 + 0.45 * width_in, 0.6, 1.5))


def box_lw(ax):
    """matplotlibの画像パネルの表示幅からboxの線の太さ（pt）を決める。"""
    return box_lw_for_width(ax.get_position().width * ax.figure.get_figwidth())


def box_thickness_px(h, w, frac=0.004):
    """画素へ直接書き込むとき（OpenCVやPILで保存する画像）の線の太さ（px）。解像度に比例させ、最低1pxにする。"""
    return max(1, round(frac * max(h, w)))


def _is_light(color, threshold):
    """色の明るさ（輝度）がthresholdを超えるか。明るい色の上には黒い文字を載せる判断などに使う。"""
    r, g, b = mpl.colors.to_rgb(color)
    return 0.299 * r + 0.587 * g + 0.114 * b > threshold


def draw_box(ax, box, color, label=None, ls="-", lw=None, fs=6.5):
    """画像上にbox (x0, y0, x1, y1) と名前札を描く。lwを省くとパネルの表示幅から決める。縁取りは付けない。"""
    lw = box_lw(ax) if lw is None else lw
    x0, y0, x1, y1 = box
    ax.add_patch(Rectangle((x0, y0), x1 - x0, y1 - y0, fill=False, ec=color, lw=lw, ls=ls))
    if label:
        ax.text(x0 + 1, y0 + 1, label, color="black" if _is_light(color, 0.6) else "white", fontsize=fs,
                va="top", ha="left", bbox=dict(boxstyle="square,pad=0.12", fc=color, lw=0, alpha=0.9))


def legend_line(color, label, ls="-", lw=1.2):
    """凡例の見本線。白地で消える明るい色（黄色や水色）だけ、細い灰色の縁を付ける。画像上のboxには縁を付けない。"""
    kw = {}
    if _is_light(color, 0.75):
        kw["path_effects"] = [pe.Stroke(linewidth=lw + 0.8, foreground="#777777"), pe.Normal()]
    return Line2D([], [], color=color, ls=ls, lw=lw, label=label, **kw)


def _make_colorwheel():
    """optical flowのcolor wheel（Baker et al., Middlebury）。赤→黄→緑→シアン→青→マゼンタの55色。"""
    ry, yg, gc, cb, bm, mr = 15, 6, 4, 11, 13, 6
    wheel = [[255, 255 * i / ry, 0] for i in range(ry)]
    wheel += [[255 - 255 * i / yg, 255, 0] for i in range(yg)]
    wheel += [[0, 255, 255 * i / gc] for i in range(gc)]
    wheel += [[0, 255 - 255 * i / cb, 255] for i in range(cb)]
    wheel += [[255 * i / bm, 0, 255] for i in range(bm)]
    wheel += [[255, 0, 255 - 255 * i / mr] for i in range(mr)]
    return np.array(wheel) / 255


FLOW_WHEEL = _make_colorwheel()


def flow_to_color(flow, max_rad):
    """flow (H, W, 2) をRGB (H, W, 3) へ変換する。向き=色相、大きさ=彩度。比べる全パネルで同じmax_radを使う。

    NaNや無限大、1e9を超える値の画素は未知の動きとして黒にする（Middleburyの表示規則）。
    """
    valid = np.isfinite(flow).all(-1) & (np.abs(flow) < 1e9).all(-1)
    flow = np.where(valid[..., None], flow, 0.0)
    u, v = flow[..., 0] / max_rad, flow[..., 1] / max_rad
    rad = np.sqrt(u ** 2 + v ** 2)
    fk = (np.arctan2(-v, -u) / np.pi + 1) / 2 * (len(FLOW_WHEEL) - 1)
    k0 = np.floor(fk).astype(int)
    k1 = (k0 + 1) % len(FLOW_WHEEL)
    f = (fk - k0)[..., None]
    col = (1 - f) * FLOW_WHEEL[k0] + f * FLOW_WHEEL[k1]
    col = 1 - np.clip(rad, 0, 1)[..., None] * (1 - col)
    col[rad > 1] *= 0.75  # max_radを超える動きは暗くして範囲外と分かるようにする
    col[~valid] = 0.0
    return col

