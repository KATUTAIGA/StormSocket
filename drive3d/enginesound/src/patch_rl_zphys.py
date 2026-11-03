# -*- coding: utf-8 -*-
"""v31 エンジン音の物理モデル（engsim.js + engsim_game.js）を EngineSynth.render に差し込む。build.py の 25 章から exec される。
   物理モデルが使えない音源（測った表を持つディーゼル・lite 版・設定なし）では PX = null になり、従来の合成がそのまま動く。"""

# ---- 1) 本体: コア + ゲームとの接続（EngineSynth と rl_sound の後、起動の前）
ins_before("/* ----- 起動 ----- */", js('engsim.js') + '\n' + js('engsim_game.js') + '\n')

# ---- 2) render: 物理モデルの用意とブロックごとの制御
rep("    const boost = clamp(p.boost, 0, 1), tbOn = !!(tb && !tb.sc);",
    "    // v31: 物理モデル（気筒の熱力学 + 排気・吸気の波動管）。使えない音源は PX = null（従来の合成）\n"
    "    const PX = this.pxFor(p);\n"
    "    let pxEx = 0, pxIn = 0, pxBk = 0, pxO = 1, pxS2 = 0;\n"
    "    if (PX) {\n"
    "      this.pxCtl(PX, p, r1, load, bt);\n"
    "      const kk = prof.ex * (1 - 0.25 * this.ovr) * (1 - p.ev) * (1 - 0.62 * inside) * this.pwk, R0 = PX.ref;\n"
    "      pxEx = 0.03 * kk * (1 + 0.28 * exh + 0.1 * ecu) * this.pxG / R0.ex;\n"
    "      pxIn = 0.004 * prof.intk * Math.pow(this.pxG, 0.5) * (1 + 0.45 * ink) * (1 - p.ev * 0.85) * (0.6 + 0.8 * inside) * (1 + 0.5 * (p.mid || 0) * inside) / R0.in;\n"
    "      pxBk = 0.0022 * Math.pow(this.pxG, 0.5) * (0.3 + 0.9 * inside) * (1 - p.ev) * (1 - 0.5 * this.ovr) * Math.min(1.5, 3500 / Math.max(r1, 1500)) / R0.blk;\n"
    "      pxO = 0.35 + 0.25 * inside;\n"
    "    }\n"
    "    const boost = clamp(p.boost, 0, 1), tbOn = !!(tb && !tb.sc);")

# ---- 3) 発火: 従来のパルス注入は物理モデルのときは行わない（音の側の副作用 = ノック・機械音の引き金は残す）
rep("          injE[(wp + de) & IM] += a * 0.15;", "          if (!PX) { injE[(wp + de) & IM] += a * 0.15;")
rep("injI[(wp + 93 + de) & IM] += a * 0.12 * itbK;", "injI[(wp + 93 + de) & IM] += a * 0.12 * itbK; }")

# ---- 4) 1 サンプル: 物理モデルを進めて、排気・吸気・燃焼振動を混ぜる
rep("      let sum = ex * (eg0 + (exGain - eg0) * u) * 7.5 + st.m2 * (ig0 + (inGain - ig0) * u) * 7.5 + ord * orderMix;",
    "      let sum;\n"
    "      if (PX) { PX.rpm = rpm; PX.step(th1 * 720); const pe = PX.o_ex; pxS2 += pe * pe / (PX.ref.ex * PX.ref.ex);\n"
    "        sum = pe * pxEx + PX.o_in * pxIn + PX.o_blk * pxBk + (ex * 0.25) * eg0 * 7.5 + ord * orderMix * pxO; }\n"
    "      else sum = ex * (eg0 + (exGain - eg0) * u) * 7.5 + st.m2 * (ig0 + (inGain - ig0) * u) * 7.5 + ord * orderMix;")

# ---- 5) アフターファイヤー・バックファイア: 排気管の中の爆発として物理モデルに入れる
rep("        if (this.popClock >= pp.t) { this.popEnv = pp.amp; this.popPh = 0; this.popIdx++; }",
    "        if (this.popClock >= pp.t) { this.popEnv = PX ? pp.amp * 0.3 : pp.amp; this.popPh = 0; this.popIdx++; if (PX) PX.bang(pp.amp * (0.7 + 0.6 * Math.random()), 0); }")
rep("if (burb && Math.random() < burb * 6) { this.popEnv = Math.max(this.popEnv, 0.12 + Math.random() * 0.28); this.popPh = 0; }",
    "if (burb && Math.random() < burb * 6) { this.popEnv = Math.max(this.popEnv, (0.12 + Math.random() * 0.28) * (PX ? 0.3 : 1)); this.popPh = 0; if (PX) PX.bang(0.12 + Math.random() * 0.3, 0); }")
rep("if (Math.random() < limPop) { this.popEnv = Math.max(this.popEnv, 0.35 + 0.45 * Math.random()); this.popPh = 0; } } } }",
    "if (Math.random() < limPop) { this.popEnv = Math.max(this.popEnv, (0.35 + 0.45 * Math.random()) * (PX ? 0.3 : 1)); this.popPh = 0; if (PX) PX.bang(0.35 + 0.45 * Math.random(), 0); } } } }")

# ---- 6) ブロックの終わり: レベラー用の音量の追跡
rep("    this.rpm = r1; this.prevLoad = load; this._eg0 = exGain; this._ig0 = inGain; if (this.TB) this.TB.f0 = tbF0;",
    "    this.rpm = r1; this.prevLoad = load; this._eg0 = exGain; this._ig0 = inGain; if (this.TB) this.TB.f0 = tbF0;\n"
    "    if (PX) { const e = Math.sqrt(pxS2 / Math.max(1, n)); this.pxEnv += (e - this.pxEnv) * Math.min(1, bt / 0.15); this.pxGuard(n); }")

# ---- 7) NaN の掃除: 物理モデルは作り直す
rep("fix(this); if (this.s) fix(this.s); if (this.rls) fix(this.rls); this.chirps = [];", "fix(this); if (this.s) fix(this.s); if (this.rls) fix(this.rls); this.pxM = null; this.pxCfg = null; this.chirps = [];")

# ---- 8) audioUpdate: 車のスペックから物理モデルの設定を渡す
rep("p.prof = prof; p.snd = c.snd || {}; p.tb = turboSound(c);",
    "p.prof = prof; p.snd = c.snd || {}; p.tb = turboSound(c);\n"
    "  p.pxc = HW.PX ? HW.PX.cfgCached(this, prof, c, c.sndId || c.id) : null;      // v31: 物理モデルの設定（スペックが変わったときだけ作り直す）")
print('patch_rl_zphys ok')
