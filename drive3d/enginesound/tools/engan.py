#!/usr/bin/env python3
"""エンジン音の解析ツール（numpy / scipy / matplotlib / soundfile）。
  steady : 一定回転の音 → 次数スペクトル（クランク次数ごとの強さ）、発火次数の顕著さ、音切れ・クリック・ループの検査
  sweep  : 回転を変えた音 → 次数追跡スペクトログラム（回転に追従した次数）と、音色の変化（ピッチ変更だけでないか）
  cmp    : 2 つの音（実車の録音／基準シミュレーター）との 1/3 オクターブ帯・次数バランスの比較
"""
import sys, json, argparse
import numpy as np
import soundfile as sf
from scipy import signal

def load(path, sr_out=None):
    x, sr = sf.read(path, always_2d=True)
    x = x.mean(axis=1).astype(np.float64)
    if sr_out and sr != sr_out:
        from math import gcd
        g = gcd(sr, sr_out); x = signal.resample_poly(x, sr_out // g, sr // g); sr = sr_out
    return x, sr

def third_octave(x, sr, f0=25, f1=12000):
    """1/3 オクターブ帯のレベル [dB re full-scale rms]"""
    centers = []; f = f0
    while f < min(f1, sr * 0.45): centers.append(f); f *= 2 ** (1 / 3)
    X = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2; fr = np.fft.rfftfreq(len(x), 1 / sr); out = []
    for c in centers:
        lo, hi = c / 2 ** (1 / 6), c * 2 ** (1 / 6); m = (fr >= lo) & (fr < hi)
        out.append(10 * np.log10(X[m].sum() / (len(x) ** 2 * 0.375) + 1e-20) if m.any() else -200)
    return np.array(centers), np.array(out)

def order_spectrum(x, sr, rpm, nord=48, res=0.25):
    """一定回転: クランク次数（1 回転あたりの回数）ごとの振幅 [dB]。res: 次数の分解能"""
    f1 = rpm / 60.0
    n = len(x); w = np.hanning(n); X = np.abs(np.fft.rfft(x * w)) / (w.sum() / 2); fr = np.fft.rfftfreq(n, 1 / sr)
    orders = np.arange(res, nord + 1e-9, res); amp = []
    for o in orders:
        f = o * f1; m = (fr > f - 0.3 * f1 * res) & (fr < f + 0.3 * f1 * res)
        amp.append(X[m].max() if m.any() else 0)
    return orders, 20 * np.log10(np.array(amp) + 1e-12)

def line_amp(x, sr, f, bw=1.5):
    n = len(x); w = np.hanning(n); X = np.abs(np.fft.rfft(x * w)) / (w.sum() / 2); fr = np.fft.rfftfreq(n, 1 / sr)
    m = (fr > f - bw) & (fr < f + bw); return X[m].max() if m.any() else 0.0

def harmonic_report(x, sr, rpm, cyl, nh=12):
    """発火周波数 f_fire = rpm/60 * cyl/2（4 サイクル）の倍音の強さと、半次数（失火・不等間隔の目安）"""
    f1 = rpm / 60.0; ff = f1 * cyl / 2
    A = [line_amp(x, sr, ff * k) for k in range(1, nh + 1)]
    ref = max(A) + 1e-12
    half = [line_amp(x, sr, f1 * 0.5 * j) for j in range(1, int(cyl) * 2 + 1)]
    return ff, [20 * np.log10(a / ref + 1e-9) for a in A], [20 * np.log10(a / ref + 1e-9) for a in half]

def clicks_dropouts(x, sr, win_ms=5.0):
    """クリック（連続していない波形の飛び）と音切れ（局所 RMS の急落）を探す"""
    n = int(sr * win_ms / 1000); m = len(x) // n; r = np.sqrt(np.mean(x[:m * n].reshape(m, n) ** 2, axis=1) + 1e-30)
    med = np.median(r); drops = np.where(r < 0.05 * med)[0]
    d = np.abs(np.diff(x)); s = np.median(d) + 1e-12; jump = np.where(d > 40 * s + 0.5 * np.max(np.abs(x)) * 0.3)[0]
    # クリック: 2 階差分の外れ値（局所 MAD の 25 倍超かつ最大振幅の 12% 超）
    d2 = np.abs(np.diff(x, 2)); thr = np.median(d2) * 60 + 1e-12; c = np.where((d2 > thr) & (d2 > 0.12 * np.max(np.abs(x))))[0]
    return {'win_ms': win_ms, 'rms_median': float(med), 'dropout_windows': int(len(drops)), 'click_candidates': int(len(c)), 'jump_candidates': int(len(jump)), 'peak': float(np.max(np.abs(x)))}

def loop_check(x, sr, cycle_s=None, lo=0.15, hi=3.0):
    """不自然なループ: エンジン 1 サイクル（cycle_s）の整数倍以外の遅れで、波形が（ほぼ）完全に繰り返していないか"""
    seg = x[: int(sr * min(len(x) / sr, 6))].copy(); seg -= seg.mean(); nfft = 1 << int(np.ceil(np.log2(len(seg) * 2)))
    F = np.fft.rfft(seg, nfft); ac = np.fft.irfft(F * np.conj(F))[: len(seg)]; ac /= ac[0] + 1e-30
    lags = np.arange(len(ac)) / sr; m = (lags >= lo) & (lags <= hi); best = (0.0, 0.0)
    for i in np.where(m)[0]:
        if cycle_s:
            k = lags[i] / cycle_s
            if abs(k - round(k)) < 0.03: continue
        if ac[i] > best[0]: best = (float(ac[i]), float(lags[i]))
    return {'max_repeat_corr': best[0], 'at_lag_s': best[1]}

def order_track(x, sr, rpm_fn, dur=None, nper=4, ppo=16):
    """次数追跡: 角度で等間隔に再サンプルし、クランク 1 回転ずつの FFT → (時刻, 次数, dB)。ppo: 1 回転あたりの点数 /次数"""
    dur = dur or len(x) / sr; tt = np.arange(0, dur, 1 / sr)[: len(x)]
    rpm = np.array([rpm_fn(t) for t in tt]); rev = np.cumsum(rpm / 60.0) / sr
    npr = 512; total = int(rev[-1]); ang = np.arange(0, total * npr) / npr   # 回転数
    xs = np.interp(ang, rev, x[: len(rev)]); frames = []; times = []
    wl = nper * npr; hop = npr // 2
    for s in range(0, len(xs) - wl, hop):
        seg = xs[s: s + wl] * np.hanning(wl); X = np.abs(np.fft.rfft(seg)) / (wl / 4)
        frames.append(20 * np.log10(X + 1e-9)); times.append(np.interp((s + wl / 2) / npr, rev, tt))
    orders = np.fft.rfftfreq(wl, 1 / npr) * 1.0 / (1.0) * (1.0) / 1.0
    orders = np.fft.rfftfreq(wl, 1.0 / npr) / nper * 1.0  # 回転あたり → 次数 = (cycles/frame)/nper... 
    orders = np.arange(wl // 2 + 1) / nper
    return np.array(times), orders, np.array(frames)

def plot_spec(x, sr, path, title='', fmax=4000):
    import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
    f, t, S = signal.spectrogram(x, sr, nperseg=2048, noverlap=1536, window='hann'); S = 10 * np.log10(S + 1e-14)
    fig, ax = plt.subplots(figsize=(9, 3.6)); m = f <= fmax
    ax.pcolormesh(t, f[m], S[m], shading='auto', vmin=S.max() - 90, vmax=S.max()); ax.set_xlabel('s'); ax.set_ylabel('Hz'); ax.set_title(title); fig.tight_layout(); fig.savefig(path, dpi=110); plt.close(fig)

def plot_orders(times, orders, frames, path, title='', omax=40, rpm_fn=None):
    import matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
    m = orders <= omax; fig, ax = plt.subplots(figsize=(9, 3.8)); Z = frames[:, m].T
    ax.pcolormesh(times, orders[m], Z, shading='auto', vmin=Z.max() - 70, vmax=Z.max()); ax.set_xlabel('s'); ax.set_ylabel('crank order'); ax.set_title(title)
    if rpm_fn is not None:
        ax2 = ax.twinx(); ax2.plot(times, [rpm_fn(t) for t in times], 'w-', lw=0.8, alpha=0.7); ax2.set_ylabel('rpm', color='w')
    fig.tight_layout(); fig.savefig(path, dpi=110); plt.close(fig)

if __name__ == '__main__':
    ap = argparse.ArgumentParser(); ap.add_argument('cmd'); ap.add_argument('wav'); ap.add_argument('--rpm', type=float); ap.add_argument('--cyl', type=int, default=4); ap.add_argument('--skip', type=float, default=0.5)
    ap.add_argument('--png'); a = ap.parse_args()
    x, sr = load(a.wav); x = x[int(a.skip * sr):]
    if a.cmd == 'steady':
        ff, h, half = harmonic_report(x, sr, a.rpm, a.cyl)
        print(json.dumps({'f_fire': ff, 'harm_dB': [round(v, 1) for v in h], 'half_order_dB': [round(v, 1) for v in half], 'qa': clicks_dropouts(x, sr), 'loop': loop_check(x, sr, 120.0 / a.rpm)}, indent=1))
        if a.png: plot_spec(x, sr, a.png, a.wav)
