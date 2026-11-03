/* ================= v31 エンジン音の物理モデル（HW.EngSim）=================
   実車に近い音にするために、「音そのもの」ではなく「音が生まれる仕組み」を計算する。
     ① 気筒ごとの熱力学: 圧縮 → 点火（ヴィーベ燃焼）→ 膨張 → 排気バルブが開く瞬間のブローダウン → 排気行程 → 吸気 → バルブオーバーラップ（掃気）。
        気筒内の圧力・温度・質量を毎サンプル積分し、バルブを通る流れは管の側の圧力（反射波）と連立して解く
     ② 排気系: 気筒ごとのプライマリー（長さの差）→ 集合部 → バンク管 → 合流／X・H パイプ → 触媒・タービン → マフラー（拡張室）→ テールパイプ → 開口端。
        すべて 1 次元の波動管（デジタル導波管）。圧力波は管の中を往復し、断面積の変わるところ・集合部・開口端で反射する。
        → 点火順序・バンク角・集合のしかた・管径・マフラーの構造・触媒の有無で、音色と「脈動」が自然に変わる
     ③ 吸気系: 吸気口 → スロットル（開度で反射が変わる）→ プレナム → ランナー → 吸気バルブ。吸気マニホールドの圧力は流量の収支から決まる
        → スロットル開度・過給圧・回転でシリンダーに入る空気が変わり、燃焼圧力（＝排気の勢い）が変わる
     ④ 燃焼のばらつき・失火・点火リタード（アンチラグ）・排気に流れ込んだ未燃ガスの爆発（バックファイア）
   音の出力: 排気の開口端から放射される音（体積流量の微分）、吸気口の音、ブロックを伝わる燃焼の振動
   設計の参考: Engine Simulator（ange-yaghi/engine-sim, MIT License, Copyright (c) 2022 AngeTheGreat）の構成
     （気筒内の圧力 → 排気の流れ → 遅延 → インパルス応答）。コードは流用せず、同様の発想を波動管で書き直した */
(function (g) {
'use strict';
const TAU = Math.PI * 2, DEG = Math.PI / 180;
const clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
const ss = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };
const RG = 287, GAM = 1.33, CVv = RG / (GAM - 1), CPv = CVv + RG, PATM = 101325, CHK = 0.667 /* 窒息流の係数 */;
/* 比熱は温度で増える（高温では解離・振動励起で 2500K に 1250J/kgK 近く）。内部エネルギー u(T) = CV0·T + ½·KCV·(T-300)² と、その逆関数 */
const CV0 = 760, KCV = 0.22;
const WB = new Float32Array(258); for (let i = 0; i < 258; i++) WB[i] = 1 - Math.exp(-5 * Math.pow(Math.min(1, i / 256), 3));      /* ヴィーベ燃焼の質量燃焼割合 */
const uOf = (T) => (T > 300 ? CV0 * T + 0.5 * KCV * (T - 300) * (T - 300) : CV0 * T), hOf = (T) => uOf(T) + RG * T;
const TofU = (u) => { const u3 = CV0 * 300; if (u <= u3) return u / CV0; const x = (-CV0 + Math.sqrt(CV0 * CV0 - 2 * KCV * (u3 - u))) / KCV; return 300 + x; };

/* ---------- 気筒配列: 点火順に並べた着火角（クランク 2 回転 = 720°）とバンク ---------- */
const ev = (n) => Array.from({ length: n }, (_, k) => (k * 720) / n);
const alt = (n) => Array.from({ length: n }, (_, k) => k & 1);
const zero = (n) => new Array(n).fill(0);
const ARCH = {
  i1: { n: 1, ang: [0], bank: [0] },
  i2: { n: 2, ang: [0, 360], bank: [0, 0] },
  i3: { n: 3, ang: ev(3), bank: zero(3) },
  i4: { n: 4, ang: ev(4), bank: zero(4) },                        /* 1-3-4-2 */
  b4: { n: 4, ang: ev(4), bank: [0, 0, 1, 1] },                   /* 水平対向 1-3-2-4（右 1,3 / 左 2,4）*/
  i5: { n: 5, ang: ev(5), bank: zero(5) },                        /* 1-2-4-5-3 */
  i6: { n: 6, ang: ev(6), bank: zero(6) },                        /* 1-5-3-6-2-4 */
  b6: { n: 6, ang: ev(6), bank: alt(6) },                         /* 水平対向 1-6-2-4-3-5 */
  v6: { n: 6, ang: ev(6), bank: alt(6) },                         /* 60° V6（等間隔）*/
  v6o: { n: 6, ang: [0, 150, 240, 390, 480, 630], bank: alt(6) }, /* 90° V6 の不等間隔（150°/90° 交互）*/
  v8x: { n: 8, ang: ev(8), bank: [0, 1, 0, 1, 1, 0, 1, 0] },      /* クロスプレーン 1-8-7-2-6-5-4-3: バンクごとに不等間隔 */
  v8f: { n: 8, ang: ev(8), bank: alt(8) },                        /* フラットプレーン: バンクごとに等間隔 */
  v10: { n: 10, ang: ev(10), bank: alt(10) },                     /* 1-6-5-10-2-7-3-8-4-9 */
  v12: { n: 12, ang: ev(12), bank: alt(12) },                     /* 1-7-5-11-3-9-6-12-2-8-4-10 */
  rot: { n: 4, ang: ev(4), bank: zero(4) }                        /* ロータリー（2 ローター ≒ 4 気筒 4 サイクル相当）*/
};
for (const n of [14, 16, 18, 20]) ARCH['v' + n] = { n, ang: ev(n), bank: alt(n) };
function archFor(prof) {
  if (prof.arch && ARCH[prof.arch]) return prof.arch;
  const n = prof.cyl;
  if (n <= 1) return 'i1'; if (n === 2) return 'i2'; if (n === 3) return 'i3'; if (n === 4) return prof.flat4 ? 'b4' : 'i4'; if (n === 5) return 'i5';
  if (n === 6) return prof.fire && prof.fire.length === 6 && Math.abs(prof.fire[1] * 720 - 150) < 2 ? 'v6o' : (prof.vee ? 'v6' : 'i6');
  if (n === 8) return 'v8x'; if (n === 10) return 'v10'; if (n === 12) return 'v12'; return ARCH['v' + n] ? 'v' + n : 'v12';
}

/* ---------- ピストン・クランクの幾何（シリンダー容積 V(α)）---------- */
function makeKin(bore, stroke, rod, cr) {
  const Vd = (Math.PI / 4) * bore * bore * stroke, Vc = Vd / (cr - 1), N = 720, tab = new Float64Array(N + 2), r = stroke / 2, lam = r / rod;
  for (let i = 0; i <= N + 1; i++) {
    const a = (i * 0.5) * DEG, s = Math.sin(a), x = r * (1 - Math.cos(a)) + rod * (1 - Math.sqrt(1 - lam * lam * s * s));
    tab[i] = Vc + (Math.PI / 4) * bore * bore * x;
  }
  return { Vd, Vc, tab, bore, stroke };
}
/* バルブの有効開口面積の割合 a(φ)（φ: 着火 TDC からのクランク角 0..720）。リフトは sin²、スロート面積で頭打ち */
function makeValve(open, dur) {
  const N = 1440, t = new Float32Array(N + 2);
  for (let i = 0; i <= N + 1; i++) {
    const phi = (i * 0.5) % 720; let u = (phi - open) / dur; if (u < 0) u += 720 / dur;
    if (u < 0 || u > 1) { t[i] = 0; continue; }
    const s = Math.sin(Math.PI * u); t[i] = Math.min(1, (0.5 * s + 0.5 * s * s) / 0.75);
  }
  return t;
}

/* ---------- 波動管（デジタル導波管）: end0 = 上流（エンジン側）/ end1 = 下流 ---------- */
class Tube {
  constructor(sr, len, cr, diam, lp, g, cls) {
    this.sr = sr; this.len = len; this.D = diam; this.A = Math.PI / 4 * diam * diam; this.lp = lp; this.g = g; this.cls = cls || 'm'; this.cr = cr;
    this.sz = 1024; this.f = new Float32Array(this.sz); this.b = new Float32Array(this.sz); this.w = 0; this.fs = 0; this.bs = 0;
    this.a0 = 0; this.a1 = 0; this.o0 = 0; this.o1 = 0; this.N = 2; this.Nt = 2; this.Y = this.A / 400; this.setC(400 * cr, true);
  }
  /* 管内の音速 c[m/s] と長さから遅延（サンプル数）を決める。Y: 特性アドミタンス（断面積 / c）— 質量流量 ṁ = Y (p⁺ − p⁻）*/
  setC(c, now) { this.c = c; this.Nt = clamp(this.len / c * this.sr, 2, this.sz - 4); this.Y = this.A / c; if (now || this.N === undefined || this.N === 2) this.N = this.Nt; }
  read() {
    /* 音速（温度）が変わっても遅延はゆっくり動かす（急に動かすとクリックになる）*/
    if (this.N !== this.Nt) { const d = this.Nt - this.N; this.N += d > 0.0015 ? 0.0015 : d < -0.0015 ? -0.0015 : d; }
    const w = this.w, ni = this.N | 0, fr = this.N - ni, m = this.sz - 1, i0 = (w - ni) & m, i1 = (w - ni - 1) & m, f = this.f, b = this.b;
    const rf = f[i0] + (f[i1] - f[i0]) * fr, rb = b[i0] + (b[i1] - b[i0]) * fr;
    this.fs += (rf - this.fs) * this.lp; this.bs += (rb - this.bs) * this.lp;
    this.a1 = this.g * this.fs; this.a0 = this.g * this.bs;      /* a1: 下流端に着く波 / a0: 上流端に着く波 */
  }
  write() { const w = this.w; this.f[w] = this.o0; this.b[w] = this.o1; this.w = (w + 1) & (this.sz - 1); }
}
/* 並列接合（集合部・合流・X パイプ・プレナム）: 全ポートで圧力が等しく、流量の総和が 0。Cm > 0 で容積（圧縮性）を持つ */
class ParJ {
  constructor() { this.ts = []; this.es = []; this.Cm = 0; this.p = 0; this.src = 0; this.sY = 1; }
  add(t, e) { this.ts.push(t); this.es.push(e); return this; }
  upd() { let s = 0; for (const t of this.ts) s += t.Y; this.sY = s; }
  run(dt) {
    const ts = this.ts, es = this.es, n = ts.length; let num = this.src;
    for (let i = 0; i < n; i++) num += 2 * ts[i].Y * (es[i] ? ts[i].a1 : ts[i].a0);
    let pJ; if (this.Cm > 0) { const k = this.Cm / dt; pJ = (num + k * this.p) / (this.sY + k); } else pJ = num / this.sY;
    if (this.leak) pJ *= this.leak;      /* 直流成分（吸気管の平均の負圧）は別の状態（pman）で持つので、波の側の直流はゆっくり捨てる */
    this.p = pJ;
    for (let i = 0; i < n; i++) { if (es[i]) ts[i].o1 = pJ - ts[i].a1; else ts[i].o0 = pJ - ts[i].a0; }
  }
}
/* 直列接合: 管 A の下流端 → 管 B の上流端。断面積の変化と抵抗（触媒・タービン・スロットル）の両方を表す。rr: 抵抗比、R: 絶対値 [Pa·s/kg]（あれば優先）*/
class SerJ {
  constructor(a, b, rr) { this.a = a; this.b = b; this.rr = rr || 0; this.R = -1; this.dcb = 0; this.md = 0; }
  run() {
    const A = this.a, B = this.b, Ya = A.Y, Yb = B.Y, ia = A.a1, ib = B.a0, za = 1 / Ya, zb = 1 / Yb;
    const Rm = this.R >= 0 ? this.R : this.rr * (za + zb) * 0.5;
    let m = 2 * (ia - ib) / (Rm + za + zb);
    if (this.dcb) { this.md += (m - this.md) * this.dcb; m -= this.md; }      /* 平均の流れ（直流）による圧力降下は別の状態（吸気管の平均圧力）が持つ。波の側は交流だけ */
    A.o1 = ia - m * za; B.o0 = ib + m * zb;
  }
}

/* ---------- 気筒 ---------- */
class Cyl {
  constructor() { this.p = PATM; this.T = 340; this.m = 0; this.V = 0; this.U = 0; this.Q = 0; this.ign = 700; this.dur = 60; this.xPrev = 0; this.phiPrev = 0; this.pEvo = 0; this.tEvo = 0; this.pPk = 0; this.mis = false; this.pPrev = PATM; this.fresh = 0; this.kc = 1; this.me = 0; this.mi = 0; this.late = 0; this.eFlag = 0; this.cycles = 0; }
}

/* ============ EngSim 本体 ============ */
class EngSim {
  constructor(sr, cfg) {
    this.sr = sr; this.dt = 1 / sr; this.cfg = cfg;
    const n = cfg.n, A = ARCH[cfg.arch] || ARCH.i4;
    this.n = n; this.arch = cfg.arch; this.ang = cfg.ang || A.ang; this.bank = cfg.bank || A.bank;
    const Vd = cfg.vd || 0.5, sb = cfg.sb || 0.95, bore = Math.cbrt(4 * Vd * 1e-3 / (Math.PI * sb)), stroke = bore * sb;
    this.kin = makeKin(bore, stroke, stroke * (cfg.rodK || 1.75), cfg.cr || 10.5); this.bore = bore;
    const vt = cfg.vt || {}; this.evc = vt.evc || 372; this.evo = vt.evo || 133; this.ivo = vt.ivo || 350; this.ivc = vt.ivc || 595;
    this.tEx = makeValve(this.evo, this.evc - this.evo); this.tIn = makeValve(this.ivo, this.ivc - this.ivo);
    const dvE = (vt.dvE || 0.34) * bore, dvI = (vt.dvI || 0.40) * bore;
    this.AexMax = 0.66 * (vt.nvE || 2) * Math.PI / 4 * dvE * dvE; this.AinMax = 0.66 * (vt.nvI || 2) * Math.PI / 4 * dvI * dvI;
    this.cyl = Array.from({ length: n }, () => new Cyl());
    this.rs = 12345; const cv = cfg.cylVar === undefined ? 0.035 : cfg.cylVar;      /* 気筒ごとの固有のばらつき（充填・燃料・点火時期・バルブの流量）: 実機の「半次数」の成分のもと */
    for (const c of this.cyl) { c.kc = 1 + cv * this.gauss(); c.dIgn = 1.6 * cv / 0.035 * this.gauss(); c.ka = 1 + 0.5 * cv * this.gauss(); }
    for (const c of this.cyl) { c.V = this.kin.Vc; c.T = 340; c.m = c.p * c.V / (RG * c.T); c.U = c.m * uOf(c.T); }
    /* 制御量 */
    this.rpm = 800; this.hlK = 2.2e-3; this.thr = 0; this.thrK = 0; this.boostPa = 0; this.fcut = 0; this.advOv = null; this.rough = 0; this.cold = 0; this.als = 0; this.cov = 0.03; this.load = 0; this.misP = 0;
    this.Vtot = Vd * n * 1e-3; this.pman = 0.4 * PATM; this.mThrS = 0; this.mEngS = 0; this.pexM = PATM * 1.04; this.mTot = 0; this.Tman = 325; this.Tex = 850;
    this.thrA = cfg.thrA || 1.5 * (0.9 * 1.18 * this.Vtot * (cfg.redline || 7000) / 120) / (0.7 * Math.sqrt(2 * 1.18 * 6000));
    this.idleRpm = cfg.idleRpm || 800;
    { const mI = 0.85 * (0.38 * PATM / (RG * 320)) * this.Vtot * this.idleRpm / 120, rh = PATM / (RG * 300); this.aIdle = mI / (0.7 * Math.sqrt(2 * rh * (PATM - 0.38 * PATM))); }
    { const mMax = 1.1 * 0.9 * 1.18 * this.Vtot * (cfg.redline || 7000) / 120; this.bp = (cfg.bpWot || 11000) / (mMax * mMax); }
    this.bangs = []; this.th = 0; this.cycN = 0; this.rs = 12345; this.kJet = cfg.kJet === undefined ? 1.2 : cfg.kJet; this.jn = 0; this.jl1 = 0; this.jl2 = 0; this.jh = 0;
    this.build(cfg);
    this.o_ex = 0; this.o_in = 0; this.o_blk = 0; this.uPrev = 0; this.iPrev = 0; this.rE = 0; this.rI = 0; this.blkS = 0;
    this.stat = { pEvo: new Float64Array(n), tEvo: new Float64Array(n), pPk: new Float64Array(n), cyc: 0, mis: 0, mdotEx: 0, pman: 0, bangs: 0 };
    this.setTemps(0.5, true);
    /* 基準の実効値とアイドル空気量は、同じ設定なら使い回す（部品を変えるたびに作り直しても、数十ミリ秒の引っかかりを避ける）*/
    this.ckey = cfg.noCal ? '' : JSON.stringify([sr, cfg.arch, cfg.n, +cfg.vd.toFixed(4), cfg.sb, cfg.cr, cfg.vt, cfg.ex, cfg.in, cfg.redline, cfg.idleRpm, cfg.hd, cfg.kJet]);
    const hit = EngSim.cache.get(this.ckey);
    if (cfg.noCal) this.ref = { ex: 1, in: 1, blk: 1 };
    else if (hit) { this.ref = hit.ref; if (hit.aIdle) { this.aIdle = hit.aIdle; this.aIdleOk = true; } }
    else { this.ref = EngSim.calibrate(sr, cfg); if (EngSim.cache.size > 40) EngSim.cache.delete(EngSim.cache.keys().next().value); EngSim.cache.set(this.ckey, { ref: this.ref, aIdle: 0 }); }
  }
  rnd() { this.rs = (this.rs * 1664525 + 1013904223) >>> 0; return this.rs / 4294967296; }
  gauss() { return (this.rnd() + this.rnd() + this.rnd() - 1.5) * 1.6; }
  /* ---------- 排気系・吸気系のネットワークを組む ---------- */
  build(cfg) {
    const sr = this.sr, n = this.n, ex = cfg.ex || {}, bank = this.bank, hd = cfg.hd || [];
    const nb = Math.max(...bank) + 1; this.nb = nb;
    const dP = ex.pD || 0.040, dS = ex.sD || 0.055, dT = ex.tD || dS;
    this.tubes = []; const T = (len, cr, D, lp, g, cls) => { const t = new Tube(sr, len, cr, D, lp, g, cls); this.tubes.push(t); return t; };
    this.pri = []; this.banks = []; this.pj = []; this.sj = [];
    for (let b = 0; b < nb; b++) {
      const idx = []; for (let k = 0; k < n; k++) if (bank[k] === b) idx.push(k);
      idx.sort((a, c) => this.ang[a] - this.ang[c]);
      const ng = ex.ng && idx.length >= 4 ? ex.ng : 1, B = { idx, ng, secs: [], S: null };
      idx.forEach((k) => { this.pri[k] = T(Math.max(0.06, (ex.pL || 0.42) + (hd[k] || 0) * 1e-3 * 343 * 0.5), 1.0, dP, 0.85, 0.997, 'p'); });
      B.S = T(ex.sL || 0.5, 0.9, dS, 0.88, 0.997, 's');
      if (ng > 1) {
        const gj = []; for (let gi = 0; gi < ng; gi++) { const sec = T(ex.gL || 0.35, 0.95, dS * 0.8, 0.85, 0.997, 's'); B.secs.push(sec); const j = new ParJ(); j.add(sec, 0); gj.push(j); }
        idx.forEach((k, i) => gj[i % ng].add(this.pri[k], 1)); this.pj.push(...gj);
        const cj = new ParJ(); for (const s of B.secs) cj.add(s, 1); cj.add(B.S, 0); this.pj.push(cj); B.cj = cj;
      } else { const cj = new ParJ(); idx.forEach((k) => cj.add(this.pri[k], 1)); cj.add(B.S, 0); this.pj.push(cj); B.cj = cj; }
      this.banks.push(B);
    }
    this.dual = nb > 1 && ex.dual !== false; this.turbo = ex.turb || 0;
    const mkPath = (up) => {
      const P = { up, chain: [], tail: null }; let cur = up;
      const Lm = ex.mL || 0.7;
      const m0 = T(Lm * 0.45, 0.82, dS, 0.88, 0.997, 'm'); P.m0 = m0; P.first = [cur, m0]; cur = m0;
      const add = (nt, rr, tag) => { const s = new SerJ(cur, nt, rr); s.tag = tag; this.sj.push(s); P.chain.push(s); cur = nt; return s; };
      if (ex.cat) { const t1 = T(0.14, 0.8, dS, 0.7, 0.99, 'm'); add(t1, ex.cat, 'cat'); P.cat = P.chain[P.chain.length - 1]; }
      if (this.turbo) { const t2 = T(0.18, 0.9, dS * 0.9, 0.55, 0.99, 'm'); const s = add(t2, 0.3, 'turb'); P.turb = s; }
      const m1 = T(Lm * 0.55, 0.78, dS, 0.88, 0.997, 'm'); add(m1, 0, 'pipe');
      for (const m of (ex.muf || [])) { const cT = T(m.L, 0.74, dS * Math.sqrt(m.r), m.lp || 0.7, m.g || 0.99, 'c'); add(cT, 0, 'exp'); const o = T(m.o || 0.14, 0.74, dS, 0.8, 0.995, 'c'); add(o, 0, 'con'); }
      const tail = T(ex.tL || 0.8, 0.7, dT, 0.88, 0.997, 't'); add(tail, 0, 'tail'); P.tail = tail;
      return P;
    };
    this.paths = [];
    if (this.dual) {
      this.banks.forEach((B) => { const P = mkPath(B.S); this.paths.push(P); });
      /* クロスオーバー: H（途中を 1 本でつなぐ）/ X（4 ポートの合流）。最初の接合を置き換える */
      const xp = ex.xp || 0; this.xmix = xp;
      if (xp) {
        const P0 = this.paths[0], P1 = this.paths[1], J = [];
        if (xp === 2) { J.push(new ParJ().add(P0.up, 1).add(P1.up, 1).add(P0.m0, 0).add(P1.m0, 0)); }
        else { const hT = T(0.3, 0.9, dS * 0.75, 0.7, 0.995, 'm'); J.push(new ParJ().add(P0.up, 1).add(P0.m0, 0).add(hT, 0), new ParJ().add(P1.up, 1).add(P1.m0, 0).add(hT, 1)); }
        this.pj.push(...J);
      } else for (const P of this.paths) { const s = new SerJ(P.up, P.m0, 0); this.sj.push(s); }
    } else {
      /* シングル: バンク管を Y パイプで合流 */
      const main = T(ex.yL || 0.3, 0.88, dS * (nb > 1 ? 1.2 : 1), 0.7, 0.995, 's');
      if (nb > 1) { const yj = new ParJ(); for (const B of this.banks) yj.add(B.S, 1); yj.add(main, 0); this.pj.push(yj); } else { this.sj.push(new SerJ(this.banks[0].S, main, 0)); }
      const P = mkPath(main); this.paths.push(P); this.sj.push(new SerJ(main, P.m0, 0));
    }
    /* ---- 吸気系: 吸気口 ← 吸気管 ← スロットル ← プレナム ← ランナー ← バルブ ---- */
    const inn = cfg.in || {}; this.inn = inn;
    this.run = []; for (let k = 0; k < n; k++) this.run[k] = T((inn.rL || 0.30) * (1 + 0.02 * ((k * 7) % 5)), 0.69, inn.rD || 0.042, 0.7, 0.997, 'i');
    this.pl = T(inn.pL || 0.10, 0.69, inn.pD || 0.075, 0.7, 0.998, 'i');
    this.air = T(inn.aL || 0.45, 0.69, inn.aD || 0.065, 0.62, 0.995, 'i');
    const plj = new ParJ(); for (const r of this.run) plj.add(r, 1); plj.add(this.pl, 0); plj.Cm = (inn.pV || 0.002) / (345 * 345); this.plj = plj; plj.leak = 1 - 1 / (0.4 * sr); this.pj.push(plj); this.Vman = Math.max(0.0015, (inn.pV || 0.002) + this.run.reduce((a, r) => a + r.A * r.len, 0));
    this.thrJ = new SerJ(this.pl, this.air, 0); this.thrJ.R = 1e9; this.thrJ.dcb = 0.002; this.sj.push(this.thrJ);
    this.endR = { t: 0 };
  }
  /* 管内の音速: 排気ガスの温度 T [K] から（c = sqrt(γ R T)）。負荷が高いほど熱く、下流ほど冷える */
  setTemps(load, now) {
    const Tg = 620 + 520 * clamp(load, 0, 1), c0 = Math.sqrt(GAM * RG * Tg);
    this.Tex = Tg;
    for (const t of this.tubes) { if (t.cls === 'i') t.setC(Math.sqrt(1.4 * RG * (300 + 25 * clamp(this.boostPa / 1e5, 0, 2))) * t.cr / 0.69, now); else t.setC(c0 * t.cr, now); }
    for (const j of this.pj) j.upd();
  }
  /* ブロック（128〜1024 サンプル）ごとの制御量 */
  ctl(o) {
    this.rpm = Math.max(200, o.rpm); this.hlK = 2.2e-3 * Math.pow(2500 / Math.max(this.rpm, 500), 0.85);      /* 壁への熱損失（回転が低いほど大きい）*/ this.thr = clamp(o.thr, 0, 1); this.thrK = (1 - Math.cos(this.thr * 80 * DEG)) / (1 - Math.cos(80 * DEG));      /* バタフライ弁: 踏み込みに対する開口面積の割合（小開度では非常に小さい）*/ this.boostPa = Math.max(0, o.boostPa || 0); this.fcut = o.fcut ? 1 : 0; this.advOv = o.adv === undefined ? null : o.adv;
    this.rough = clamp(o.rough || 0, 0, 1); this.als = o.als ? 1 : 0; this.cold = o.cold || 0;
    this.cov = clamp(0.025 + 0.06 * Math.max(0, 1 - this.rpm / (this.idleRpm * 2.2)) + 0.12 * this.rough, 0.01, 0.4);
    this.misP = this.fcut ? 0 : 0.0 + 0.5 * this.rough * Math.max(0, 1 - this.rpm / (this.idleRpm * 1.6)) + (o.mis || 0);
    this.tbRR = o.turbRR === undefined ? 0.3 : o.turbRR; for (const P of this.paths) if (P.turb) P.turb.rr = this.tbRR;
    const lo = clamp(this.mEngS / Math.max(1e-6, this.maxFlow()), 0, 1); this.load = lo;
  }
  maxFlow() { return 0.9 * 1.18 * this.Vtot * this.rpm / 120 * (this.pman / PATM + 0.3); }
  /* 排気の途中に爆発（未燃ガスの燃焼）を起こす。amp: 0..1+ */
  bang(amp, where) { this.bangs.push({ t: 0, amp, w: where || 0 }); this.stat.bangs++; }
  /* ---------- 1 サンプル進める（thDeg: クランク角 0..720）---------- */
  step(thDeg) {
    const dt = this.dt, n = this.n, cyl = this.cyl, kin = this.kin, rpm = this.rpm, tubes = this.tubes;
    for (let i = 0, L = tubes.length; i < L; i++) tubes[i].read();
    /* 吸気マニホールドの圧力（質量収支）*/
    const pup = PATM * 0.99 + this.boostPa;
    { const thA = this.thrA * this.thrK + this.aIdle * (1 + 0.6 * this.cold), rh = pup / (RG * 310), pm = this.pman, d = pup - pm;
      let mt = d > 0 ? 0.7 * thA * Math.sqrt(2 * rh * d) : 0; const mc = CHK * 0.7 * thA * pup / Math.sqrt(RG * 310); if (mt > mc) mt = mc;
      this.mThr = mt; this.mThrS += (mt - this.mThrS) * 0.002;
      const Rth = this.mThrS / (rh * 0.49 * thA * thA + 1e-12); this.thrJ.R = this.fixR !== undefined ? this.fixR : Math.min(1e9, Math.max(1, Rth));
      this.mEngS += ((this.mEngNow || 0) - this.mEngS) * 0.004; }
    /* 気筒 */
    let mEng = 0, mEx = 0, blk = 0, pexM = this.pexM; const Tman = this.Tman, pm = this.pman;
    const hlK = this.hlK, dPhi = rpm * 6 * dt;
    for (let k = 0; k < n; k++) {
      const c = cyl[k]; let phi = thDeg - this.ang[k]; phi -= 720 * Math.floor(phi / 720);
      const a = phi >= 360 ? phi - 360 : phi, x = a * 2, ix = x | 0, V1 = kin.tab[ix] + (kin.tab[ix + 1] - kin.tab[ix]) * (x - ix), dV = V1 - c.V;
      /* サイクルの境界（φ が 720 → 0）: 統計・次のサイクルの乱数 */
      if (phi < c.phiPrev - 360) {
        c.cycles++; this.stat.pEvo[k] = c.pEvo; this.stat.tEvo[k] = c.tEvo; this.stat.pPk[k] = c.pPk; c.pPk = 0; this.stat.cyc++;
      }
      const ph0 = c.phiPrev; c.phiPrev = phi;
      /* IVC: 閉じ込めた混合気から、このサイクルの発熱量を決める */
      if (ph0 < this.ivc && phi >= this.ivc) {
        const fr = clamp(c.fresh / Math.max(c.m, 1e-9), 0, 1), eg = this.cold > 0 ? 0 : 0;
        c.Q = c.fresh * 2.45e6 * (1 + 0.04 * this.thr) * c.kc * (1 + this.cov * this.gauss()); c.xPrev = 0; c.fresh = 0;
        c.mis = this.fcut ? true : (this.rnd() < this.misP);
        if (c.mis) { c.Q = this.fcut ? 0 : c.Q * 0.0; this.stat.mis++; }
        /* 点火時期（BTDC がプラス）*/
        let adv = this.advOv !== null ? this.advOv : (9 + 22 * ss(800, 5500, rpm) - 7 * Math.pow(this.load, 1.3) + 4 * (1 - this.thr) + (this.advOff || 0)); adv += c.dIgn + 1.2 * this.gauss() * (1 + 3 * this.rough);
        c.ign = adv >= 0 ? 720 - adv : -adv; c.dur = clamp(52 + 16 * Math.max(0, 1 - rpm / 2500) + 12 * this.rough - 8 * this.load, 38, 90);
        c.eFlag = 0;
      }
      /* EVO: ブローダウン開始時の気筒の状態を記録 */
      if (ph0 < this.evo && phi >= this.evo) { c.pEvo = c.p; c.tEvo = c.T; }
      /* 燃焼（ヴィーベ）*/
      let dQ = 0;
      if (c.Q > 0) {
        let pb = phi - c.ign; if (pb < -360) pb += 720; else if (pb > 360) pb -= 720;
        let xb; if (pb <= 0) xb = 0; else if (pb >= c.dur) xb = 1; else { const wu = pb / c.dur * 256, wi = wu | 0; xb = WB[wi] + (WB[wi + 1] - WB[wi]) * (wu - wi); }
        dQ = c.Q * (xb - c.xPrev); if (dQ < 0) dQ = 0; c.xPrev = xb; if (xb >= 1) c.Q = 0;
      }
      /* バルブを通る流れ */
      const vx = phi * 2, vi = vx | 0, vf = vx - vi, tE = this.tEx, tI = this.tIn, aE = tE[vi] + (tE[vi + 1] - tE[vi]) * vf, aI = tI[vi] + (tI[vi + 1] - tI[vi]) * vf;
      let me = 0, mi = 0; const p = c.p, T0 = c.T;
      if (aE > 0.002) {
        const t = this.pri[k], Zm = 1 / t.Y, A = this.AexMax * c.ka * aE, d = p - pexM - 2 * t.a0, ad = d < 0 ? -d : d;
        const rho = d >= 0 ? p / (RG * T0) : pexM / (RG * this.Tex), S = 2 * A * A * rho;
        let m = 0.5 * (-S * Zm + Math.sqrt(S * S * Zm * Zm + 4 * S * ad));
        if (d >= 0) { const mc = CHK * A * p / Math.sqrt(RG * T0); if (m > mc) m = mc; me = m; } else me = -m;
        t.o0 = t.a0 + me / t.Y; mEx += me;
      } else this.pri[k].o0 = this.pri[k].a0;
      if (aI > 0.002) {
        const t = this.run[k], Zm = 1 / t.Y, A = this.AinMax * c.ka * aI, d = pm + 2 * t.a0 - p, ad = d < 0 ? -d : d;
        const rho = d >= 0 ? pm / (RG * Tman) : p / (RG * T0), S = 2 * A * A * rho;
        let m = 0.5 * (-S * Zm + Math.sqrt(S * S * Zm * Zm + 4 * S * ad));
        if (d >= 0) { const pr = (pm + 2 * t.a0) ; const mc = CHK * A * Math.max(pr, 1) / Math.sqrt(RG * Tman); if (m > mc) m = mc; mi = m; } else mi = -m;
        t.o0 = t.a0 - mi / t.Y; mEng += mi; if (mi > 0) c.fresh += mi * dt;
      } else this.run[k].o0 = this.run[k].a0;
      /* 第一法則: U' = U + Σ ṁ h dt − p dV + dQ（outflow は気筒内の温度、inflow は源の温度）*/
      let dE = -p * dV + dQ * 0.8 - hlK * dPhi * c.m * CVv * (T0 - 480) * 0.5;
      if (me > 0) dE -= me * dt * hOf(T0); else if (me < 0) dE += -me * dt * hOf(this.Tex);
      if (mi > 0) dE += mi * dt * hOf(Tman); else if (mi < 0) dE += mi * dt * hOf(T0);
      let m2 = c.m + (mi - me) * dt; if (m2 < 2e-7) m2 = 2e-7;
      let U2 = c.U + dE; const T2 = clamp(TofU(U2 / m2), 220, 3600); U2 = m2 * uOf(T2);
      c.m = m2; c.U = U2; c.T = T2; c.V = V1; let p2 = m2 * RG * T2 / V1; if (p2 > 2.5e7) p2 = 2.5e7; c.p = p2;
      if (p2 > c.pPk) c.pPk = p2;
      blk += p2 - c.pPrev; c.pPrev = p2;
    }
    this.mEngNow = mEng; this.mTot += (Math.abs(mEx) - this.mTot) * 0.001; this.stat.mdotEx = this.mTot;
    /* マニホールド圧: 質量収支（容積 = プレナム + ランナー）。Pa = ρRT のスケールで積分 */
    { this.pman = clamp(pm + (RG * Tman / this.Vman) * (this.mThr - this.mEngS) * dt, 0.12 * PATM, pup * 1.001); this.stat.pman = this.pman; }
    this.pexM += ((PATM + this.bp * this.mTot * this.mTot) - this.pexM) * 0.002;
    /* バックファイア */
    let bs = 0;
    for (let i = this.bangs.length - 1; i >= 0; i--) { const b = this.bangs[i]; b.t += dt; const u = b.t, v = b.amp * 0.8e5 * (Math.exp(-u / 0.0022) - Math.exp(-u / 0.00035));      /* 圧力の山（Pa）: 排気管の中の爆発 */ bs += v; if (u > 0.02) this.bangs.splice(i, 1); }
    /* 接合の計算（爆発は集合部への体積流量の注入として入れる）*/
    if (bs !== 0) { const bb = this.banks; for (let i = 0; i < bb.length; i++) bb[i].cj.src = bs * (i === 0 ? 1 : 0.7) * bb[i].cj.sY; }
    this.plj.src = this.mEngS;      /* 気筒が吸う平均の流量は、吸気管の平均圧力（pman）の側で扱う。波の側には交流（脈動）だけを残す */
    for (let i = 0, L = this.pj.length; i < L; i++) this.pj[i].run(dt);
    for (let i = 0, L = this.sj.length; i < L; i++) this.sj[i].run();
    /* 開口端: 反射（高域ほど逃げる）と放射（体積流量の微分）*/
    let u = 0;
    let jet = 0;
    for (const P of this.paths) { const t = P.tail, a = t.a1, ff = 0.34; this.rE += (a - this.rE) * ff; t.o1 = -0.985 * this.rE; const ue = a - t.o1; u += ue; if (ue > 0) { const q = ue * 0.00066; jet += q * q * this.kJet; } }
    { const w = this.rnd() - 0.5; this.jl1 += (w - this.jl1) * 0.32; this.jl2 += (this.jl1 - this.jl2) * 0.32; this.jh += (this.jl2 - this.jh) * 0.012; this.jn = (this.jl2 - this.jh) * 2.2; }
    if (jet > 0) jet *= this.jn;
    { const t = this.air, a = t.a1; this.rI += (a - this.rI) * 0.3; t.o1 = -0.92 * this.rI; const ui = a - t.o1; this.o_in = (ui - this.iPrev); this.iPrev = ui; }
    this.o_ex = u - this.uPrev + jet; this.uPrev = u;
    this.blkS += (blk - this.blkS) * 0.5; this.o_blk = blk - this.blkS;
    for (let i = 0, L = tubes.length; i < L; i++) tubes[i].write();
    if (bs !== 0) for (const B of this.banks) B.cj.src = 0;
    return this.o_ex;
  }
}
/* 起動直後の過渡（気筒が大気の状態から始まる）を捨てるため、音を出す前に空回しする */
EngSim.prototype.warm = function (rpm, sec) {
  const N = Math.round(this.sr * sec); let th = this.th || 0; this.ctl({ rpm, thr: 0 }); this.setTemps(0.2, true);
  const go = (n) => { for (let i = 0; i < n; i++) { if ((i & 255) === 0) this.ctl({ rpm, thr: 0 }); th += rpm * 6 / this.sr; if (th >= 720) th -= 720; this.step(th); } };
  /* アイドル空気量（アイドル制御弁）: アイドルで吸気管の圧力が狙い（約 40kPa）になるように、空回しで合わせる */
  if (!this.aIdleOk) { /* 吸気管の圧力を狙い（約 40kPa）に固定して 0.35 秒回し、そのとき気筒が吸う平均流量 = アイドルで必要な空気量 */
    const tgt = (this.cfg.idleMap || 0.52) * PATM, rh = PATM / (RG * 300), Nf = Math.round(this.sr * 0.35); let acc = 0, cnt = 0;
    for (let i = 0; i < Nf; i++) { if ((i & 255) === 0) this.ctl({ rpm, thr: 0 }); th += rpm * 6 / this.sr; if (th >= 720) th -= 720; this.pman = tgt; this.step(th); this.pman = tgt; if (i > Nf * 0.4) { acc += this.mEngNow || 0; cnt++; } }
    const m = Math.max(2e-5, acc / Math.max(1, cnt)); this.aIdle = clamp(m / (0.7 * Math.sqrt(2 * rh * (PATM * 0.99 - tgt))), 2e-6, 4e-3);
    const ce = EngSim.cache.get(this.ckey); if (ce) ce.aIdle = this.aIdle; this.aIdleOk = true;
  }
  go(N);
  this.th = th; this.bangs.length = 0;
};
/* 基準（回転 = レッドラインの 50%、全開）での各出力の実効値。ゲーム側の音量の基準にする。作り直した別のインスタンスで短く回して測る */
EngSim.calibrate = function (sr, cfg) {
  const c2 = Object.assign({}, cfg, { noCal: true }), E = new EngSim(sr, c2), rpm = Math.max(1500, (cfg.redline || 7000) * 0.5), N = Math.round(sr * 0.55);
  let th = 0, se = 0, si = 0, sb = 0, n = 0; E.setTemps(1, true);
  for (let i = 0; i < N; i++) { if (i % 1024 === 0) E.ctl({ rpm, thr: 1, boostPa: 0 }); th += rpm * 6 / sr; if (th >= 720) th -= 720; E.step(th); if (i > N * 0.4) { se += E.o_ex * E.o_ex; si += E.o_in * E.o_in; sb += E.o_blk * E.o_blk; n++; } }
  const r = { ex: Math.sqrt(se / n) || 1, in: Math.sqrt(si / n) || 1, blk: Math.sqrt(sb / n) || 1, rpm }; return r;
};
EngSim.cache = new Map();
EngSim.ARCH = ARCH; EngSim.archFor = archFor;
g.EngSimCore = EngSim;
if (typeof module !== 'undefined') module.exports = EngSim;
})(typeof window !== 'undefined' ? window : globalThis);
