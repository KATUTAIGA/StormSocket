# -*- coding: utf-8 -*-
"""v32 タイヤの滑りの詳細な計算（tyre5.js）を走行の計算に差し込む。build.py の 25 章から exec される。"""
ins_before("/* ----- 起動 ----- */", js('tyre5.js') + '\n')
# 走行: 従来の「限界の横加速度 → アンダー/オーバー」の後ろで、4 輪のタイヤの力から横の運動を解き直す（使えないとき = 低速・事故・後退などは従来のまま）
rep("    P.beta = (P.beta || 0) + (sgY * os * 0.3 - (P.beta || 0)) * Math.min(1, dt * (os > 0 ? 3 : 2.2));\n    if (os > 0) { P.psi += sgY * os * 0.22 * dt; P.v = Math.max(0, P.v - os * 2.5 * dt); }\n    P.us = us; P.os = os;",
    "    if (HW.TY5 && HW.TY5.step(this, dt, { delta, gU, aF, aR, aF0: Math.max(1.5, Math.min(gU * ax.f, ax.roll) + bankA), aR0: Math.max(1.5, Math.min(gU * 1.06 * ax.r, ax.roll * 1.06) + bankA), brake, thr, bankA, tvR: this.tvRate ? this.tvRate() : 0 })) { us = P.us; os = P.os; }      // v32: 4 輪のすべり角・スリップ率・荷重から\n"
    "    else {\n    P.beta = (P.beta || 0) + (sgY * os * 0.3 - (P.beta || 0)) * Math.min(1, dt * (os > 0 ? 3 : 2.2));\n    if (os > 0) { P.psi += sgY * os * 0.22 * dt; P.v = Math.max(0, P.v - os * 2.5 * dt); }\n    P.us = us; P.os = os; }")
# デバッグ表示（F10 車両の物理）に、4 輪の状態を足す
rep("    if (n === 10) { const P = game.P, p = game.dmgPerf || {}, el = Dbg.panel(); el.hidden = false;",
    "    if (n === 10) { const P = game.P, p = game.dmgPerf || {}, el = Dbg.panel(); el.hidden = false; el._ty5 = HW.TY5 ? HW.TY5.lines(game) : [];")
print('patch_rl_zw_tyre ok')
rep("(p.dead ? ' 走行不能' : '')].join('\\n'); continue; }", "(p.dead ? ' 走行不能' : '')].concat(el._ty5 || []).join('\\n'); continue; }")
print('patch_rl_zw_tyre f10 ok')
# サーキットの自動運転: グリップの学習は、ハンドルが落ち着いている（定常に近い）ときだけ。切り始めの過渡（車体が向きを変えるまでの遅れ）を
# 「グリップが低い」と取り違えると、コーナーの速度を必要以上に落とす（4 輪の計算ではヨーの立ち上がりに遅れがあるため）
rep("  if (this.path.circuit && Math.abs(scE) > 0.25 && Math.abs(scE) < 0.92 && Q.v > 15 && !Q.crash && !(Q.os > 0.05)) A.gAd =",
    "  const scRate = Math.abs(scE - (A.scE0 === undefined ? scE : A.scE0)) / Math.max(1e-3, dt); A.scE0 = scE; A.scSt = scRate < 0.5 ? (A.scSt || 0) + dt : 0;\n"
    "  if (this.path.circuit && Math.abs(scE) > 0.25 && Math.abs(scE) < 0.92 && Q.v > 15 && !Q.crash && !(Q.os > 0.05) && (!Q.ty5 || A.scSt > 0.35)) A.gAd =")
print('patch_rl_zw_tyre rap ok')
