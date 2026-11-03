#!/usr/bin/env python3
"""音の品質検査（自動）: スパイク（クリック）・音切れ・ブロック境界の不連続・不自然なループ。
   usage: qa.py file.wav [--block 2048] [--steady-rpm N --cyl N]"""
import sys, json, argparse, numpy as np, soundfile as sf
from scipy import signal
def spikes(x, sr, k=14.0, win_ms=3.0):
    """2 階差分が局所 RMS（±win_ms）の k 倍を超える点 = 波形の飛び（クリック）"""
    d2 = np.abs(np.diff(x, 2)); n = max(8, int(sr * win_ms / 1000)); ker = np.ones(2 * n + 1) / (2 * n + 1)
    loc = np.sqrt(np.convolve(d2 ** 2, ker, mode='same') + 1e-30); idx = np.where((d2 > k * loc) & (d2 > 0.01 * np.max(np.abs(x)) * 0.2))[0]
    out = []; last = -10 ** 9
    for i in idx:
        if i - last > n: out.append(int(i)); last = i
    return out
def dropouts(x, sr, win_ms=10.0, frac=0.08):
    n = int(sr * win_ms / 1000); m = len(x) // n; r = np.sqrt((x[:m * n].reshape(m, n) ** 2).mean(1) + 1e-30)
    # 局所中央値（±0.5s）に対する落ち込み
    h = int(0.5 / (win_ms / 1000)); out = []
    for i in range(m):
        lo, hi = max(0, i - h), min(m, i + h); med = np.median(r[lo:hi])
        if r[i] < frac * med: out.append(i * win_ms / 1000)
    return out
def block_edges(x, sr, block):
    """ブロック境界（block サンプルごと）での不連続: 境界の 2 階差分 / 境界以外の 2 階差分の p99"""
    d2 = np.abs(np.diff(x, 2)); e = np.arange(block, len(d2), block); e = e[e < len(d2)]
    if len(e) < 3: return None
    edge = np.max(np.stack([d2[e - 1], d2[e], d2[np.minimum(e + 1, len(d2) - 1)]]), axis=0); mask = np.ones(len(d2), bool)
    for o in (-1, 0, 1): mask[np.clip(e + o, 0, len(d2) - 1)] = False
    base = np.percentile(d2[mask], 99.5) + 1e-12
    return {'edge_max_over_p99.5': float(np.max(edge) / base), 'edge_median_over_p99.5': float(np.median(edge) / base)}
def loops(x, sr, lo=0.2, hi=2.5):
    seg = x[: int(sr * min(len(x) / sr, 8))] - np.mean(x[: int(sr * min(len(x) / sr, 8))]); n = 1 << int(np.ceil(np.log2(len(seg) * 2)))
    # 包絡ではなく波形そのものの自己相関（エンジンの周期成分は rpm が動くと崩れるので、掃引では繰り返しはほぼ出ない）
    F = np.fft.rfft(seg, n); ac = np.fft.irfft(F * np.conj(F))[: len(seg)]; ac /= ac[0] + 1e-30; l = np.arange(len(ac)) / sr; m = (l >= lo) & (l <= hi)
    i = np.argmax(np.where(m, ac, -1)); return {'max_corr': float(ac[i]), 'lag_s': float(l[i])}
if __name__ == '__main__':
    ap = argparse.ArgumentParser(); ap.add_argument('wav'); ap.add_argument('--block', type=int, default=2048); ap.add_argument('--skip', type=float, default=0.6); ap.add_argument('--sweep', action='store_true'); a = ap.parse_args()
    x, sr = sf.read(a.wav); x = x if x.ndim == 1 else x.mean(1); x = x[int(a.skip * sr):]
    sp = spikes(x, sr); dr = dropouts(x, sr); be = block_edges(x, sr, a.block); lp = loops(x, sr)
    print(json.dumps({'file': a.wav.split('/')[-1], 'spikes': len(sp), 'spike_t': [round((i + int(a.skip * sr)) / sr, 3) for i in sp[:6]], 'dropouts': len(dr), 'dropout_t': [round(t + a.skip, 2) for t in dr[:6]], 'block_edge': be, 'loop': lp}))
