#!/usr/bin/env python3
"""自作の物理モデルと、基準（engine-sim で同じ条件に回した音）の比較。絶対音量は無視し、①1/3 オクターブ帯の形 ②次数の構成 ③音量の変化（アイドル→全開・回転）を見る。
   usage: cmp_ref.py <ref_out> <mine_out> [engines...]"""
import sys, os, json, numpy as np, soundfile as sf
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from engan import third_octave, order_spectrum, load
REF, MINE = sys.argv[1], sys.argv[2]; ids = sys.argv[3:] or ['i4_vtec', 'i6_2jz', 'v6_60', 'v6_odd', 'v8_ls', 'v8_f136', 'v10_lfa', 'v12_412']
GROUPS = [('low <200', 25, 200), ('mid 200-1k', 200, 1000), ('high 1-4k', 1000, 4000), ('air 4-10k', 4000, 10000)]
def bandshape(x, sr):
    c, l = third_octave(x, sr, 25, 10000); return c, l
def group_levels(c, l, tot):
    out = []
    for nm, lo, hi in GROUPS:
        m = (c >= lo) & (c < hi); p = 10 * np.log10(np.sum(10 ** (l[m] / 10)) + 1e-30); out.append(p - tot)
    return out
def rms_db(x): return 20 * np.log10(np.sqrt(np.mean(x ** 2)) + 1e-12)
rows = []
for id in ids:
    ej = json.load(open(os.path.join(REF, id, 'engine.json'))); n = ej['cylinder_count']
    for sc in ['wot3000', 'part25', 'idle', 'wot80']:
        rp, mp = os.path.join(REF, id, sc + '.wav'), os.path.join(MINE, id, sc + '.wav')
        if not (os.path.exists(rp) and os.path.exists(mp)): continue
        xr, sr1 = load(rp); xm, sr2 = load(mp); xr = xr[int(sr1 * 0.3):]; xm = xm[int(sr2 * 0.3):]
        cr, lr = bandshape(xr, sr1); cm, lm = bandshape(xm, sr2); L = min(len(cr), len(cm)); lr, lm = lr[:L], lm[:L]
        tr, tm = 10 * np.log10(np.sum(10 ** (lr / 10))), 10 * np.log10(np.sum(10 ** (lm / 10)))
        gr, gm = group_levels(cr, lr, tr), group_levels(cm, lm, tm)
        d = (lm - tm) - (lr - tr); m = (cr[:L] >= 60) & (cr[:L] <= 6000)
        rows.append((id, sc, gr, gm, float(np.sqrt(np.mean(d[m] ** 2)))))
print('%-9s %-8s | band levels re total (dB): ref | mine  [low  mid  high  air]      | RMS band diff 60-6k' % ('engine', 'scn'))
for id, sc, gr, gm, rd in rows:
    print('%-9s %-8s | %s | %s | %5.1f dB' % (id, sc, ' '.join('%6.1f' % v for v in gr), ' '.join('%6.1f' % v for v in gm), rd))
# 音量の変化: アイドル→全開 (wot3000) のレベル差
print('\nlevel idle -> WOT3000 (dB, post-leveling not applied for mine / engine-sim has its own leveler):')
for id in ids:
    try:
        a = [rms_db(load(os.path.join(R, id, s + '.wav'))[0][4000:]) for R in (REF, MINE) for s in ('idle', 'wot3000')]
        print('  %-9s ref %.1f dB   mine %.1f dB' % (id, a[1] - a[0], a[3] - a[2]))
    except Exception as e: print(' ', id, 'n/a', e)
