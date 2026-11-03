#!/bin/bash
# エンジン音の自動テスト一式。usage: ./run_all_tests.sh [game.html] [reference_out_dir]
#   コアの検査（Node）: 気筒サイクル・不規則運転の安定性・部品/条件の効き方・点火順序（次数）
#   ゲームの検査（ヘッドレス Chromium・game.html を渡したとき）: 全配列の掃引レンダリング → クリック/音切れ/ブロック境界/ループ検査
#   基準との比較（engine-sim で作った基準音のディレクトリを渡したとき）
cd "$(dirname "$0")"; set -o pipefail; HTML=${1:-}; REF=${2:-}; fail=0; mkdir -p ../out
echo "== 1. 気筒サイクル（実機の典型値）"; node cycle_check.js || fail=1
echo "== 2. 部品・条件の効き方"; node effects_test.js | tail -12 || fail=1
echo "== 3. 長時間の不規則な運転での安定性（9 配列 × 90 秒）"; node stress.js | tail -10 || fail=1
echo "== 4. 点火順序（次数スペクトル）"; node orders.js 3000 && python3 - <<'PY'
import numpy as np, soundfile as sf, sys
sys.path.insert(0, '.')
from engan import order_spectrum
N = {'i3': 3, 'i4': 4, 'b4': 4, 'i5': 5, 'i6': 6, 'v6': 6, 'v6o': 6, 'v8x': 8, 'v8f': 8, 'v10': 10, 'v12': 12}
bad = 0
for a, n in N.items():
    base = n / 4 if a == 'v6o' else n / 2      # 不等間隔の 90°V6 は 150°/90° 交互なので、周期は半次数（n/4 = 1.5）の整数倍に出る
    x, sr = sf.read('../out/ord_%s.wav' % a); o, amp = order_spectrum(x[sr:], sr, 3000, nord=24, res=0.5); amp = amp - amp.max(); top = [float(t) for t in o[np.argsort(amp)[::-1][:3]]]
    mult = all(abs(t / base - round(t / base)) < 0.01 for t in top)
    ok = mult if a not in ('b4', 'v8x') else any(abs(t / base - round(t / base)) < 0.01 for t in top)      # 水平対向（不等長ヘッダー）・クロスプレーン V8 はバンクごとの非対称で 5 次などが出る。発火次数の整数倍が上位に入っていること
    print('%-4s 基本の次数 %g  上位 3 つ %s  %s' % (a, base, top, 'OK' if ok else 'NG')); bad += 0 if ok else 1
sys.exit(1 if bad else 0)
PY
[ $? -ne 0 ] && fail=1
if [ -n "$HTML" ]; then
  echo "== 5. ゲームの合成器（物理モデル）: 12 配列の掃引・アイドルを出力し、クリック・音切れ・ブロック境界・ループを検査"
  python3 - <<'PY'
import json
cars = [('c2_ae86', 'i4'), ('k20a', 'i4'), ('gr86', 'b4'), ('supra', 'i6'), ('nsx', 'v6o'), ('c2_z34', 'v6'), ('ls3', 'v8x'), ('c2_f458', 'v8f'), ('c2_lfa', 'v10'), ('c2_countach21', 'v12'), ('rx7', 'rot'), ('c2_gt3rs991', 'b6')]
spec = []
for i, a in cars: spec += [{'id': i, 'px': True, 'scn': 'sweep', 'secs': 10, 'name': 'M_%s_%s_sweep' % (i, a)}, {'id': i, 'px': True, 'scn': 'idle', 'secs': 5, 'name': 'M_%s_%s_idle' % (i, a)}]
json.dump(spec, open('spec_all.json', 'w'))
PY
  node render_game.js "$HTML" ../out/gameall spec_all.json | grep -c "bad 0" ; for f in ../out/gameall/*.wav; do python3 -I qa.py $f; done | python3 -c "
import sys, json
bad = 0
for l in sys.stdin:
    d = json.loads(l); be = (d['block_edge'] or {}).get('edge_max_over_p99.5', 0); ok = d['spikes'] == 0 and d['dropouts'] == 0 and be < 6
    if '_sweep' in d['file']: ok = ok and d['loop']['max_corr'] < 0.5
    bad += 0 if ok else 1; print('%-32s spikes %d dropouts %d blockedge %.1f loop %.2f %s' % (d['file'], d['spikes'], d['dropouts'], be, d['loop']['max_corr'], 'OK' if ok else 'NG'))
print('QA ALL OK' if not bad else 'QA: %d NG' % bad); sys.exit(1 if bad else 0)"
  [ $? -ne 0 ] && fail=1
fi
if [ -n "$REF" ]; then
  echo "== 6. 基準（engine-sim の同条件の音）との比較"; node render_core_ref.js "$REF" ../out/mine >/dev/null && python3 cmp_ref.py "$REF" ../out/mine && python3 cmp_orders.py "$REF" ../out/mine wot3000 3000
fi
[ $fail -eq 0 ] && echo "ALL TESTS OK" || echo "SOME TESTS FAILED"; exit $fail
