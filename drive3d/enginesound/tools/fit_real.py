#!/usr/bin/env python3
"""実車の録音（一定回転の区間）に、物理モデルの排気系パラメータを合わせる。
   usage: fit_real.py --wav real.wav --rpm 3000 --arch i6 --n 6 --disp 3.0 [--redline 7000] [--band 60 4000] [--start 1.0 --dur 4]
   手順: 録音と同じ回転・全開でコアを回し、1/3 オクターブ帯の形（音量は合わせて比べる）の RMS 差が最小になる
         ジェット乱流ノイズ(kJet)・触媒の抵抗(cat)・マフラーの構造(多室/1室/ストレート)・テールの長さ を総当たりで探す。
   結果は cfg の ex / kJet に入れる値（部品の rl_sound の px に反映する）として表示する。録音そのものはリポジトリに入れない"""
import sys, os, json, subprocess, tempfile, argparse, itertools, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from engan import load, third_octave
ap = argparse.ArgumentParser(); ap.add_argument('--wav', required=True); ap.add_argument('--rpm', type=float, required=True); ap.add_argument('--arch', default='i4'); ap.add_argument('--n', type=int, default=4)
ap.add_argument('--disp', type=float, default=2.0); ap.add_argument('--redline', type=float, default=7000); ap.add_argument('--band', nargs=2, type=float, default=[60, 4000]); ap.add_argument('--start', type=float, default=0.5); ap.add_argument('--dur', type=float, default=4.0)
ap.add_argument('--thr', type=float, default=1.0); a = ap.parse_args(); here = os.path.dirname(os.path.abspath(__file__))
x, sr = load(a.wav); x = x[int(a.start * sr): int((a.start + a.dur) * sr)]; c0, l0 = third_octave(x, sr, 25, 10000); m = (c0 >= a.band[0]) & (c0 <= a.band[1]); t0 = l0 - 10 * np.log10(np.sum(10 ** (l0[m] / 10)))
MUF = {'stock': [{'L': 0.35, 'r': 5, 'o': 0.12, 'lp': 0.58, 'g': 0.978}, {'L': 0.2, 'r': 4, 'o': 0.1, 'lp': 0.62, 'g': 0.98}], 'sport': [{'L': 0.4, 'r': 3, 'o': 0.1, 'lp': 0.7, 'g': 0.986}], 'straight': [{'L': 0.25, 'r': 1.7, 'o': 0.1, 'lp': 0.86, 'g': 0.996}]}
best = []
for kj, cat, mf, tl in itertools.product([0.0, 0.6, 1.2, 2.5, 5.0], [0.55, 0.25, 0.0], list(MUF), [0.6, 1.0, 1.6]):
    cfg = {'arch': a.arch, 'n': a.n, 'vd': a.disp / a.n, 'redline': a.redline, 'rpm': a.rpm, 'thr': a.thr, 'secs': 3.0, 'kJet': kj, 'ex': {'pL': 0.42, 'sL': 0.5, 'mL': 0.9, 'tL': tl, 'cat': cat, 'muf': MUF[mf], 'dual': a.n >= 8, 'xp': 1 if a.n >= 8 else 0, 'ng': 0}}
    with tempfile.NamedTemporaryFile(suffix='.wav', delete=False) as f: p = f.name
    subprocess.run(['node', os.path.join(here, 'render_one.js'), json.dumps(cfg), p], check=True); y, s2 = load(p); os.unlink(p); y = y[int(0.8 * s2):]
    c1, l1 = third_octave(y, s2, 25, 10000); L = min(len(c0), len(c1)); mm = m[:L]; t1 = l1[:L] - 10 * np.log10(np.sum(10 ** (l1[:L][mm] / 10)))
    best.append((float(np.sqrt(np.mean((t1[mm] - t0[:L][mm]) ** 2))), kj, cat, mf, tl))
best.sort(); print('RMS band diff (dB) | kJet cat muffler tailL(m)')
for r in best[:6]: print('%6.2f | %.1f %.2f %-8s %.1f' % r)
print('→ 最良:', json.dumps({'kJet': best[0][1], 'ex': {'cat': best[0][2], 'muf': MUF[best[0][3]], 'tL': best[0][4]}}))
