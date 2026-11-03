import sys, os, json, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from engan import order_spectrum, load
REF, MINE = sys.argv[1], sys.argv[2]; scn = sys.argv[3] if len(sys.argv) > 3 else 'wot3000'; rpm = float(sys.argv[4]) if len(sys.argv) > 4 else 3000
ids = ['i4_vtec', 'i6_2jz', 'v6_60', 'v6_odd', 'v8_ls', 'v8_f136', 'v10_lfa', 'v12_412']
def top(x, sr, rpm, k=8, nord=24):
    o, a = order_spectrum(x[int(sr * 0.4):], sr, rpm, nord=nord, res=0.5); a = a - a.max(); idx = np.argsort(a)[::-1][:k]; return ' '.join('%g:%.0f' % (o[i], a[i]) for i in sorted(idx))
for id in ids:
    ej = json.load(open(os.path.join(REF, id, 'engine.json'))); n = ej['cylinder_count']
    xr, s1 = load(os.path.join(REF, id, scn + '.wav')); xm, s2 = load(os.path.join(MINE, id, scn + '.wav'))
    print('%-8s n=%2d fire-order=%g' % (id, n, n / 2)); print('   ref :', top(xr, s1, rpm)); print('   mine:', top(xm, s2, rpm))
