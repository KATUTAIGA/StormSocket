/* ================= v31 エンジン音の物理モデルをゲームの合成器につなぐ層（HW.PX）=================
   engsim.js（気筒の熱力学 + 排気・吸気の波動管）を、EngineSynth.render の排気・吸気・燃焼振動の発生源として使う。
   対象: ガソリンのレシプロ（4 サイクル）。測った表（ordT）を持つディーゼルや、ロータリー以外の特殊な音源は従来の合成のまま。
   ・車のスペック（c.adv: ボア・ストローク・圧縮比・カムの作用角・点火時期）→ 気筒の幾何・バルブタイミング・燃焼
   ・部品（c.rl.snd.px: 触媒・ヘッダー・マフラー・センターパイプ・吸気）→ 排気管・吸気管の構造
   ・走行状態（回転・スロットル・過給圧・燃料カット・アンチラグ・リミッター）→ 毎ブロックの制御量 */
(function (g) {
'use strict';
const HW = g.HW, ES = g.EngSimCore; if (!HW || !HW.EngineSynth || !ES || HW.PX) return;
const clamp = (v, a, b) => Math.max(a, Math.min(b, v)), ss = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };
const PX = HW.PX = { VERSION: 1, on: true, ES };
/* 切り替え: コンソールで localStorage.setItem('hw.px', '0') → 従来の合成に戻す（'1' で物理モデル）。URL に ?px=0 でも切り替えられる */
try { const q = /[?&]px=(\d)/.exec(g.location && g.location.search || ''), v = q ? q[1] : g.localStorage && g.localStorage.getItem('hw.px'); if (v === '0') PX.on = false; } catch (e) { /* なし */ }
/* 手で作った音のプロファイルの排気量[L]。カタログ由来の c2_* などは HW.SNDREQ の L、無ければ 1 気筒 0.5L */
const LBY = { yaris: 1.5, hiace: 2.7, nbox: 0.66, prius: 1.8, alphard: 2.5, camry: 2.5, gr86: 2.4, gtr: 2.6, supra: 3.0, r34: 2.6, rx7: 1.3, nsx: 3.0, k20a: 2.0, ls3: 6.2, crown: 2.4, m8: 4.4, ea825: 4.0, b16b: 1.6, b18c: 1.8, f20c: 2.0, k20c: 2.0, nsxnc1: 3.5, smile: 0.66 };
const ARCHBY = { gr86: 'b4', yaris: 'i3', nsx: 'v6o', nsxnc1: 'v6', gtr: 'i6', r34: 'i6', supra: 'i6', rx7: 'rot', ls3: 'v8x', m8: 'v8x', ea825: 'v8f', crown: 'i4', k20a: 'i4', b16b: 'i4', b18c: 'i4', f20c: 'i4', k20c: 'i4', hiace: 'i4', prius: 'i4', alphard: 'i4', camry: 'i4', nbox: 'i3', smile: 'i3' };
PX.archOf = function (prof, id) {
  if (prof.arch) return prof.arch;
  if (ARCHBY[id]) return ARCHBY[id];
  const q = HW.SNDREQ && HW.SNDREQ[id];
  if (q && q.kind === 'gas') {
    const n = q.cyl;
    if (n === 4) return q.sty === 'flat' ? 'b4' : 'i4';
    if (n === 6) return q.sty === 'flat' ? 'b6' : (q.vee ? 'v6' : 'i6');
    if (n === 8) return q.sty === 'flat' || q.sty === 'race' || q.sty === 'f1' ? 'v8f' : 'v8x';
  }
  return ES.archFor(prof);
};
/* マフラーの「抜けのよさ」o（0 = 純正の静かな多室、1 = ストレート）→ 拡張室の構造 */
PX.mufFrom = function (m, muffL) {
  const o = clamp(m.open, 0, 1), L = Math.max(0.2, muffL * (m.lm || 1)), lerp = (a, b, t) => a + (b - a) * t;
  if (m.n >= 2) return [{ L: L * 0.58, r: lerp(5, 4, o), o: 0.12, lp: lerp(0.55, 0.65, o), g: lerp(0.975, 0.985, o) }, { L: L * 0.34, r: lerp(4, 3, o), o: 0.1, lp: lerp(0.6, 0.7, o), g: lerp(0.98, 0.988, o) }];
  return [{ L: L * lerp(1, 0.55, o), r: lerp(3.2, 1.7, o), o: 0.1, lp: lerp(0.68, 0.86, o), g: lerp(0.985, 0.996, o) }];
};
/* プロファイル + スペック → コア設定 */
PX.cfgFor = function (prof, c, id) {
  const n = prof.cyl, arch = PX.archOf(prof, id), q = HW.SNDREQ && HW.SNDREQ[id] || {}, sty = q.sty || (prof.vtec || prof.nai ? 'race' : 'road');
  const V = c && c.adv && c.adv.V, geo = c && c.adv && c.adv.geo, rot = arch === 'rot';
  let vd = (LBY[id] || q.L || 0.5 * n) / n;
  if (V && V.bore && V.stroke) vd = Math.PI / 4 * V.bore * V.bore * V.stroke / 1e6;
  const sb = V && V.bore ? V.stroke / V.bore : (sty === 'race' || sty === 'f1' ? 0.78 : sty === 'flat' ? 0.85 : 0.95), cr = V && V.cr ? V.cr : (sty === 'race' ? 12 : prof.tb ? 9.0 : 10.5);
  const red = (c && c.redline) || (prof.redline) || 7000, idle = (c && c.idle) || prof.idleRpm || 800, camI = V && V.camIn ? V.camIn : (sty === 'race' ? 270 : 232), camE = V && V.camEx ? V.camEx : camI;
  const di = camI + 30, de = camE + 30, lsa = sty === 'race' ? 108 : 112;
  const vt = rot ? { evo: 100, evc: 372, ivo: 338, ivc: 592, dvE: 0.5, dvI: 0.55 } : { evo: 250 - lsa * 0 - de / 2 + (112 - lsa) * 0, evc: 250 + de / 2, ivo: 360 + lsa - di / 2, ivc: 360 + lsa + di / 2, dvE: sty === 'race' ? 0.36 : 0.33, dvI: sty === 'race' ? 0.42 : 0.39 };
  if (!rot) { vt.evo = 360 - lsa - de / 2; vt.evc = 360 - lsa + de / 2; }
  /* 排気の構造（部品由来 c.rl.snd.px があればそれを使う）*/
  const px = (c && c.rl && c.rl.snd && c.rl.snd.px) || {}, big = (n * vd) / 3;
  const sD = clamp(0.055 * Math.pow(Math.max(big, 0.3), 0.33), 0.04, 0.095), pipe = prof.pipe || 1.6, muffL = prof.muff || 0.6;
  const nb = Math.max(...(ES.ARCH[arch] ? ES.ARCH[arch].bank : [0])) + 1;
  const ex = {
    pD: clamp(0.038 * Math.pow(vd / 0.5, 0.33) * (sty === 'race' ? 1.1 : 1), 0.028, 0.06),
    pL: px.pL || (sty === 'race' ? 0.5 : 0.34), ng: px.ng === undefined ? 0 : px.ng, sL: 0.35 + 0.1 * (nb > 1 ? 1 : 0), gL: 0.3,
    sD: sD * (px.sPipe || 1), tD: px.tD || sD * (px.sPipe || 1), mL: Math.max(0.5, pipe * 0.55), tL: Math.max(0.4, pipe * 0.45), yL: 0.3,
    cat: px.cat === undefined ? 0.55 : px.cat,
    muf: (px.muf && px.muf.open !== undefined ? PX.mufFrom(px.muf, muffL) : px.muf) || (sty === 'race' && muffL < 0.5 ? [{ L: Math.max(0.25, muffL), r: 2.4, o: 0.1, lp: 0.7, g: 0.99 }] : [{ L: Math.max(0.2, muffL * 0.58), r: 5, o: 0.12, lp: 0.55, g: 0.975 }, { L: Math.max(0.15, muffL * 0.34), r: 4, o: 0.1, lp: 0.6, g: 0.98 }]),
    dual: px.dual === undefined ? (nb > 1 && (prof.vol || 1) >= 1.0) : px.dual, xp: px.xp === undefined ? (sty === 'cross' || prof.fire && n === 8 && sty === 'road' ? 1 : (sty === 'race' || sty === 'flat' ? 2 : 0)) : px.xp,
    turb: c && c.boostCfg && c.boostCfg.kind !== 'sc' ? (((c.boostCfg.twin || (prof.tb && prof.tb.twin)) && nb > 1) ? 2 : 1) : (!c && prof.tb && !prof.tb.sc ? (prof.tb.twin && nb > 1 ? 2 : 1) : 0), bpWot: px.bpWot || 11000
  };
  const inLen = (c && c.rl && c.rl.snd && c.rl.snd.prof && c.rl.snd.prof.inLen) || prof.inLen || 0.32, inn = { rL: inLen * 0.9, rD: clamp(0.042 * Math.pow(vd / 0.5, 0.33), 0.03, 0.06), pL: 0.1, pD: 0.075 * Math.pow(big, 0.33), pV: (0.0015 + 0.0016 * n * vd / 0.5) * (px.plenum || 1), aL: 0.45, aD: 0.065 * Math.pow(big, 0.25) };
  if (px.itb) { inn.pV = 0.0002; inn.aL = 0.08; inn.rL *= 0.7; inn.itb = 1; }
  return { n, arch, vd, sb, cr, vt, ex, in: inn, hd: prof.hd, redline: red, idleRpm: idle, bpWot: ex.bpWot, rodK: sty === 'race' ? 1.65 : 1.75, id, sty, ignOff: V && V.ign ? V.ign : 0, peakBar: c && c.boostCfg ? c.boostCfg.peakBar : 0 };
};
/* audioUpdate から: スペックが変わったときだけ設定を作り直す（毎フレームは同じ参照を返す）*/
PX.cfgCached = function (game, prof, c, id) {
  if (!c || !prof) return null;
  const key = [id, prof, c.adv, c.rl && c.rl.snd && c.rl.snd.px, c.boostCfg && c.boostCfg.kind, c.redline, c.idle];
  const m = game._pxM || (game._pxM = []);
  let same = m.length === key.length; for (let i = 0; same && i < key.length; i++) if (m[i] !== key[i]) same = false;
  if (!same) { game._pxM = key; game._pxC = null; try { game._pxC = PX.eligible(prof) ? PX.cfgFor(prof, c, id) : null; } catch (e) { game._pxC = null; if (g.console && !PX._w) { PX._w = 1; console.warn('PX cfg: ' + (e && e.stack || e)); } } }
  return game._pxC;
};
/* 物理モデルで鳴らす対象: ガソリンのレシプロ（測った表・ディーゼルの打音・ハイブリッドの電気走行専用は従来どおり）*/
PX.eligible = function (prof) { return !!(PX.on && prof && prof.cyl >= 1 && prof.cyl <= 20 && !prof.ordT && !prof.knock && !prof.clat && prof.px !== false); };
const S = HW.EngineSynth.prototype;
/* 合成器ごとの物理モデルを用意（設定が変わったら作り直す）*/
S.pxFor = function (p) {
  this._pxT0 = typeof performance !== 'undefined' ? performance.now() : 0;
  const cfg = p.pxc; if (!cfg || this.lite || !PX.on) { this.pxM = null; this.pxCfg = null; return null; }
  if (this.pxCfg !== cfg || !this.pxM || this.pxSr !== this.sr) {
    this.pxCfg = cfg; this.pxSr = this.sr;
    try { this.pxM = new ES(this.sr, cfg); this.pxM.setTemps(0.3, true); this.pxM.warm(Math.max(500, cfg.idleRpm || 800), 0.35);      /* アイドル回転で空回し（アイドルの空気量はアイドル回転で合わせる。いまの回転ではない）*/ this.pxEnv = 1; this.pxG = 1; this.pxT = 0; this.pxCool = 0; }
    catch (e) { this.pxM = null; if (g.console && !PX._w2) { PX._w2 = 1; console.warn('PX build: ' + (e && e.stack || e)); } }
  }
  return this.pxM;
};
/* 計算が間に合わないとき（遅い端末）は、物理モデルを止めて従来の合成に戻す: 1 ブロックの計算が再生時間の 75% を超える状態が 2 秒近く続いたら */
S.pxGuard = function (n) {
  if (typeof performance === 'undefined') return;
  const used = performance.now() - this._pxT0, avail = n / this.sr * 1000; this._pxSlow = (this._pxSlow || 0) * 0.97 + (used / avail > 0.75 ? 0.03 : 0);
  if (this._pxSlow > 0.8) { PX.on = false; PX.slowOff = true; this.pxM = null; this.pxCfg = null; if (g.console) console.warn('PX: 計算が間に合わないため、従来のエンジン音に切り替えました'); }
};
/* ブロックごとの制御量 */
S.pxCtl = function (X, p, r1, load, bt) {
  const cfg = this.pxCfg, tb = p.tb, sx = p.sx || {}, thr = clamp(p.thrE === undefined ? load : p.thrE, 0, 1);
  const boost = clamp(p.boost, 0, 1.6), bar = (cfg.peakBar || 0.8) * 1e5;
  const alsOn = !!(tb && tb.als && load < 0.25 && r1 > 3000 && boost > 0.1);
  const fcut = this.ovr > 0.5 || p.blown || p.flat ? 1 : 0;
  const lim = p.lim ? 0.5 : 0;
  const wg = this.TB && this.TB.wg || 0;
  X.ctl({ rpm: r1, thr: alsOn ? Math.max(thr, 0.22) : thr, boostPa: boost * bar, fcut: fcut && !alsOn, adv: alsOn ? -44 : (p.adv !== undefined ? p.adv : undefined), rough: p.rough || 0, mis: lim + (alsOn ? 0.3 : 0), cold: p.cold || 0, als: alsOn, turbRR: tb ? (0.18 + 0.42 * clamp(this.shaft || 0, 0, 1)) * (1 - 0.65 * clamp(wg, 0, 1)) : 0 });
  /* アンチラグ: 点火をリタードして排気で燃やし続ける。排気管の中で不規則に爆ぜる */
  this.pxAls = (this.pxAls || 0) - bt; if (alsOn) { while (this.pxAls <= 0) { X.bang(0.35 + 0.8 * Math.random(), 0); this.pxAls += 0.04 + 0.09 * Math.random(); } } else if (this.pxAls < 0) this.pxAls = 0;
  if (cfg.ignOff && !alsOn && X.advOv === null) X.advOff = cfg.ignOff;
  this.pxT += bt; if (this.pxT > 0.25) { this.pxT = 0; X.setTemps(X.load); }
  /* レベラー: 排気の音の大きさは回転・負荷で 35dB も変わるので、ゲーム向けに圧縮する（実車の録音をゲームに入れるときと同じ扱い）*/
  const en = Math.max(this.pxEnv, 1e-3), gT = en < 1 ? Math.pow(en, -0.3) : Math.pow(en, -0.6);      /* 基準より小さいときも大きいときも縮める（アイドルと全開の差を 15dB ほどに）*/
  this.pxG += (clamp(gT, 0.3, 3.2) - this.pxG) * Math.min(1, bt / 0.08);
};
})(typeof window !== 'undefined' ? window : globalThis);
