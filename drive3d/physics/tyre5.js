/* ================= v32 タイヤの滑りの詳細な計算（HW.TY5）=================
   4 輪それぞれについて、毎コマ数回に分けて
     ・接地荷重 Fz: 静的な配分 + 加減速の前後の荷重移動 + 旋回の左右の荷重移動（ロール剛性配分・ロールセンターの高さ・トレッド）+ 空力のダウンフォース
     ・すべり角 α: 車体の横すべり速度 vy とヨーレート r と舵角から、各輪の位置での速度の向き。タイヤの「緩和長」ぶん遅れて力が立ち上がる
     ・スリップ率 κ: 駆動輪・制動輪の回転（縦のタイヤの計算 LongitudinalTyres が持つ車輪の回転）から
     ・横力 Fy: マジックフォーミュラ（ピークのあとに力が落ちる形）。コーナリングパワーはタイヤの種類・幅・扁平率・空気圧と荷重で決まる
     ・縦と横の力の取り合い（摩擦円）: 駆動・制動に使ったぶんだけ横に使えない。空転・ロックした輪はほとんど横力を出さない
     ・荷重感度（荷重が増えるほど摩擦係数が下がる）・キャンバー（ロールで変わる）・各輪の温度（滑りの仕事で上がる）
   を計算し、車体の横の運動（横すべり vy）とヨーの運動（r）を解く。アンダーステア・オーバーステア・パワースライド・ドリフト・タックイン・
   スピンが、これらの力の釣り合いから出る（以前は「曲がれる限界」を当てはめるだけだった）。
   限界の横加速度（前後それぞれ）は従来の計算（足まわり・空力・制動配分・天候・タイヤの温度による値）に合わせて、
   チューニングで決めた釣り合いと速さは変えない。変わるのは、限界までの過程と限界を超えたときの振る舞い・各輪の状態 */
(function (g) {
'use strict';
const HW = g.HW; if (!HW || !HW.Game || HW.TY5) return;
const G0 = 9.80665, clamp = (v, a, b) => (v < a ? a : v > b ? b : v), D2R = Math.PI / 180;
const TY5 = HW.TY5 = { on: true, VERSION: 1 };
try { const q = /[?&]ty5=(\d)/.exec(g.location && g.location.search || ''), v = q ? q[1] : g.localStorage && g.localStorage.getItem('hw.ty5'); if (v === '0') TY5.on = false; } catch (e) { /* なし */ }
/* タイヤの種類ごとの性質: Cs = コーナリングパワー係数（1 rad あたり、荷重に対する比）、C = 形状（大きいほどピークのあとに急に落ちる）、
   kLS = 荷重感度、sig = 緩和長の基準 [m] */
const CLS = { slick: { Cs: 19, C: 1.55, kLS: 0.22, sig: 0.32 }, semi: { Cs: 17, C: 1.45, kLS: 0.2, sig: 0.38 }, uhp: { Cs: 15, C: 1.38, kLS: 0.18, sig: 0.45 }, hyper: { Cs: 15.5, C: 1.4, kLS: 0.19, sig: 0.42 },
  sport: { Cs: 14, C: 1.35, kLS: 0.17, sig: 0.48 }, sum: { Cs: 12.5, C: 1.3, kLS: 0.16, sig: 0.55 }, eco: { Cs: 11, C: 1.25, kLS: 0.15, sig: 0.6 }, all: { Cs: 11, C: 1.22, kLS: 0.15, sig: 0.6 },
  winter: { Cs: 9.5, C: 1.15, kLS: 0.14, sig: 0.65 }, stud: { Cs: 8.5, C: 1.12, kLS: 0.14, sig: 0.7 }, snow: { Cs: 8.5, C: 1.12, kLS: 0.14, sig: 0.7 }, tbr: { Cs: 7.5, C: 1.15, kLS: 0.12, sig: 0.9 },
  at: { Cs: 9.5, C: 1.2, kLS: 0.15, sig: 0.7 }, mt: { Cs: 8, C: 1.15, kLS: 0.15, sig: 0.8 }, van: { Cs: 9, C: 1.2, kLS: 0.13, sig: 0.75 }, ev: { Cs: 12, C: 1.28, kLS: 0.16, sig: 0.55 }, rain: { Cs: 12, C: 1.28, kLS: 0.16, sig: 0.55 } };
const NAMES = ['左前', '右前', '左後', '右後'];
/* 車ごとの定数（スペックから。スペックを作り直すまで使い回す）*/
const CFG = new WeakMap();
TY5.cfgOf = function (c) {
  let k = CFG.get(c); if (k) return k;
  const V = (c.adv && c.adv.V) || {}, ph = c.phys || {}, y = c.tyre || {}, truck = /^truck/.test(c.body || '') || y.cls === 'tbr';
  const cls = CLS[y.cls] || (truck ? CLS.tbr : CLS.sum), wb = Math.max(1.6, c.wb || 2.6), wf = clamp(ph.wf || (V.wf ? V.wf / 100 : 0.55), 0.3, 0.75);
  const a = wb * (1 - wf), b = wb * wf, tF = (V.trF || 1520) / 1000, tR = (V.trR || V.trF || 1520) / 1000, m = Math.max(300, c.mass || 1400), h = c.cgH || 0.55;
  const tW = V.tW || 215, tA = V.tA || 55, tP = V.tP || 230, cam = V.camber === undefined ? -1 : V.camber;
  const Cs = cls.Cs * Math.pow(55 / tA, 0.35) * Math.pow(tW / 215, 0.12) * Math.pow(tP / 230, 0.18);      // 扁平率が低い・幅が広い・空気圧が高いほど、すべり角あたりの横力が立ち上がるのが速い
  const sig = clamp(cls.sig * Math.pow(tA / 50, 0.55) * Math.pow(230 / tP, 0.3), 0.2, 1.2);      // 緩和長: 扁平率が低いほど・空気圧が高いほど短い（応答が速い）
  const rsd = clamp((V.rsd || 54) / 100, 0.25, 0.85), rcF = (V.rcF === undefined ? 45 : V.rcF) / 1000, rcR = (V.rcR === undefined ? 95 : V.rcR) / 1000;
  const L = c.adv && c.adv.lsd, la = L && L.t ? clamp(L.la || 0, 0, 1) : 0, bias = clamp((V.bias || 68) / 100, 0.35, 0.9);
  const Iz = 0.95 * m * a * b, Fz0 = m * G0 / 4;
  k = { m, Iz, a, b, wb, tF, tR, h, wf, rsd, rcF, rcR, Cs, C: cls.C, kLS: Math.max(cls.kLS, (y.ls || 0) * 2), sig, cam, tW, tA, tP, la, bias, Fz0, cls: y.cls || (truck ? 'tbr' : 'sum'), truck,
    fws: (c.chs && c.chs.fws) || 0, esc: !!(c.feat && c.feat.esc), escTh: ph.escTh || 8, drive: c.drive || 'FR' };
  /* 定常のアンダーステア勾配 K [rad/(m/s²)] = m (b/CαF − a/CαR) / wb（荷重に比例するコーナリングパワーなら重量配分だけで決まり、ほぼ 0 になる。
     荷重感度・ロール・駆動の影響は走りながら出る）。舵角の換算に使う（ハンドルの切れ方を、従来の「欲しい横加速度」から決める仕組みのままにする）*/
  const CaF = 2 * Cs * m * G0 * wf / 2 * (1 - 0.3 * k.kLS), CaR = 2 * Cs * m * G0 * (1 - wf) / 2 * (1 - 0.3 * k.kLS) * 1.35;      // 後輪の単位荷重あたりの横剛性を前輪より 35% 高く（自動車の一般的なアンダーステア。駆動で後輪の横の力が落ちても、まだアンダーステアに残るように）
  k.CaF = CaF; k.CaR = CaR; k.Kus = m * (b / CaF - a / CaR) / wb;
  k.aPk = Math.atan(1.25 / Cs) / D2R;      // 横力が最大になるすべり角の目安 [°]
  CFG.set(c, k); return k;
};
/* マジックフォーミュラ（E = 0）: 横力 / 最大横力 */
const mf = (alpha, B, C) => Math.sin(C * Math.atan(B * alpha));
/* 温度とグリップ（本体の tyreStep と同じ形）*/
const tf = (T, y) => { if (!y || !y.Tw) return 1; const x = (T - y.Topt) / y.Tw, xo = Math.max(0, Math.abs(x) - 0.4); return x < 0 ? 1 - 0.3 * Math.min(1, xo * xo) : 1 - 0.2 * Math.min(1.5, xo * xo); };
TY5.active = function (game) { return !!(TY5.on && game && game.mode === 'drive' && game.P && !game.P.crash && !game.P.rev && !game.walking && game.spec && !game.spec.plant && !(game.spec.jet && !game.spec.wheelR)); };
TY5.reset = function (game) { if (game && game.P) game.P.ty5 = null; };
/* 1 コマ: 横・ヨーの運動を解く。ctx = { delta（従来の舵角）, gU, aF, aR（前後の限界の横加速度）, brake, thr, bankA } */
TY5.step = function (game, dt, ctx) {
  if (!TY5.active(game) || !(dt > 0)) { if (game && game.P) game.P.ty5 = null; return false; }
  const P = game.P, c = game.spec, K = TY5.cfgOf(c), lt = game.tyres, v = Math.max(0, P.v || 0);
  let S = P.ty5;
  if (!S) S = P.ty5 = { vy: 0, r: P.yaw || 0, al: [0, 0, 0, 0], phi: 0, dT: [0, 0, 0, 0], beta: 0, ay: 0, w: [{}, {}, {}, {}], t: 0, satF: 0, satR: 0, spinT: 0 };
  if (v < 3.5) {      // 低速: 車体の横すべりは無いものとして、従来の計算（タイヤの向き＝進む向き）に合わせる
    S.vy = 0; S.r = P.yaw || 0; S.beta = 0; for (let i = 0; i < 4; i++) S.al[i] = 0; S.ay = v * S.r;
    TY5.fillTele(game, S, K, v, ctx, null); return false;
  }
  const blend = clamp((v - 3.5) / 3, 0, 1);
  /* 舵角: 従来の「欲しい横加速度 → 舵角」の換算に、アンダーステア勾配を足す（同じ操作で同じだけ曲がる。限界からは物理で決まる）*/
  const ayCmd = (ctx.delta * v * v) / K.wb;
  // アンダーステア勾配は、縦の力（ブレーキ・駆動）で各軸のコーナリングの硬さが落ちるぶん変わる（前のコマの各軸の「横に使える割合」から）
  const kFe = clamp(S.kF || 1, 0.3, 1.1), kRe = clamp(S.kR || 1, 0.3, 1.1), KusE = K.m * (K.b / (K.CaF * kFe) - K.a / (K.CaR * kRe)) / K.wb;
  let dF = ctx.delta + clamp(KusE * ayCmd, -0.15, 0.15);
  /* カウンターステア: 後ろが流れて車体の横すべり角 β が付くと、前輪を進む向きへ当てる（運転者が自然に行う修正）。自動運転は強く、手動は補助として弱く。
     1 コマごとにしか操作が入らない（画面の更新が遅いほど修正が遅れる）ので、遅れのぶんを補う */
  { const ap = !!(game.AP && game.AP.on) || !!(HW.RAP && game.race && game.race.ap), kcs = ap ? 1.0 : 0.55, kr = (ap ? 0.06 : 0.035) * clamp(dt / 0.016, 1, 3.5);
    const rRef = v * Math.tan(ctx.delta) / (K.wb + K.Kus * v * v), r0 = S.r || 0;
    const bExp = K.b * r0 / Math.max(v, 1) - K.m * K.a * (S.ay || 0) / (K.wb * K.CaR), ex = (S.beta || 0) - bExp;      // 定常旋回で自然に付く横すべり角（後輪のすべり角のぶん）を除いた「流れ」
    const over = ex * r0 < 0 ? Math.sign(ex) * Math.max(0, Math.abs(ex) - 0.009) : 0;      // 後ろが外へ流れている向きのときだけ（0.5° の遊び）
    dF += kcs * over - kr * Math.max(0, Math.abs(r0) - Math.abs(rRef)) * Math.sign(r0);
    // 大きく流れたら（2° 超）、曲がりたい舵より「前輪を前輪の進む向きへ合わせる」ほうを優先する（前輪のすべり角を 0 に近づけ、前で車体を引き戻す）
    const w = clamp((Math.abs(over) - 0.035) / 0.1, 0, 1) * (ap ? 1 : 0.6);
    if (w > 0) { const fa = Math.atan2((S.vy || 0) + K.a * r0, Math.max(v, 1)); dF = dF * (1 - w) + (fa + 0.5 * kcs * over) * w; } }      // ヨーが狙いより大きいとき（巻き込み）だけ戻す
  dF = clamp(dF, -0.6, 0.6);
  const dR = K.fws ? -0.03 * K.fws * dF * clamp((v - 18) / 22, 0, 1) * -1 : 0;      // 4WS: 高速では後輪も同じ向きに少し切れる
  /* 各軸の摩擦の大きさ: 従来の限界（ctx.aF0 / aR0, m/s²）を、この車の荷重移動のもとでの荷重感度の損失ぶん補って、軸ごとの「静的な μ」にする */
  const ayLimF = Math.max(1.2, ctx.aF0 != null ? ctx.aF0 : ctx.aF), ayLimR = Math.max(1.2, ctx.aR0 != null ? ctx.aR0 : ctx.aR);      // 荷重移動・駆動/制動の取り分を引く前の値（この計算が自分で引くので二重にしない）
  const m = K.m, h = K.h, hra = K.rcF + (K.rcR - K.rcF) * K.a / K.wb, dfm = HW.dfMul ? HW.dfMul(c, v) : 1, dfz = Math.max(0, dfm - 1) * m * G0;
  const ax = P.aLong || 0, transX = m * ax * h / K.wb;
  const FzF0 = m * G0 * K.wf + dfz * K.wf - transX, FzR0 = m * G0 * (1 - K.wf) + dfz * (1 - K.wf) + transX;
  const lossOf = (Fz, dFz) => { const f = (z) => z * (1 - K.kLS * (z / K.Fz0 - 1)), o = Math.max(1, Fz / 2 + dFz), i = Math.max(0, Fz / 2 - dFz); return (f(o) + f(i)) / Math.max(1, Fz); };
  const dFzF_lim = m * ayLimF / K.tF * (K.rsd * (h - hra) + (K.b / K.wb) * K.rcF), dFzR_lim = m * ayLimR / K.tR * ((1 - K.rsd) * (h - hra) + (K.a / K.wb) * K.rcR);
  const FzFs = FzF0 + transX, FzRs = FzR0 - transX;      // 前後の荷重移動を除いた荷重（移動は各輪の荷重で効かせる）
  const muF = ayLimF / G0 * (m * G0 * K.wf / Math.max(1, FzFs)) / Math.max(0.4, lossOf(Math.max(100, FzFs), dFzF_lim)), muR = ayLimR / G0 * (m * G0 * (1 - K.wf) / Math.max(1, FzRs)) / Math.max(0.4, lossOf(Math.max(100, FzRs), dFzR_lim));
  /* 縦の力（駆動・制動）: 縦のタイヤの計算の結果を、各輪の荷重で配る（開放デフは左右等しく、LSD はロックの強さぶん荷重の大きい側へ）*/
  const brk = Math.max(0, game._tyreBrakeForce || 0) * (P.v > 0.05 ? 1 : 0), FxDF = lt ? lt.fxF || 0 : 0, FxDR = lt ? lt.fxR || 0 : 0;
  // 制動の配分: 後輪は EBD（電子制動力配分）で、そのときの後輪の荷重で使える量の 7 割までに抑える（前に荷重が移ると後ろが先にロックしてスピンしないように）。抑えたぶんは前に回さない（実車の EBD と同じく、その間の制動力は少し減る）
  const rB = Math.min(brk * (1 - K.bias), 0.7 * (c.mu || 1) * Math.max(0, FzR0)), FxF = FxDF - brk * K.bias, FxR = FxDR - rB;
  const kapF = lt ? lt.kapF || 0 : 0, kapR = lt ? lt.kapR || 0 : 0, kp = (lt && lt.tc && lt.tc.kp) || 0.12;
  const fullF = Math.max(1, lt && lt.fullPeakF > 0 ? lt.fullPeakF : (c.mu || 1) * m * G0 * K.wf), fullR = Math.max(1, lt && lt.fullPeakR > 0 ? lt.fullPeakR : (c.mu || 1) * m * G0 * (1 - K.wf));
  /* 温度: 各輪の温度の、平均（本体の P.tyT）からのずれ */
  const y = c.tyre, Tm = P.tyT === undefined ? 30 : P.tyT, tfm = tf(Tm, y);
  const n = Math.max(1, Math.ceil(dt * 360)), ds = dt / n, X = [K.a, K.a, -K.b, -K.b], Yw = [-K.tF / 2, K.tF / 2, -K.tR / 2, K.tR / 2];      // 車体の座標: x 前・y 右（ゲームの舵角・ヨーは右が +）。0 左前 1 右前 2 左後 3 右後
  let vy = S.vy, r = S.r, phi = S.phi, ay = S.ay; const al = S.al, W = S.w;
  const apB = !!(game.AP && game.AP.on) || !!(HW.RAP && game.race && game.race.ap), kLat = apB ? 0.92 : 0.72;      // 自動運転は、曲がりながらのブレーキを曲がる側優先に踏み加減する（手動は ABS の範囲だけ）
  const uLat = clamp(Math.abs(ayCmd) / Math.max(1.5, Math.min(ctx.aF0 || ctx.aF, ctx.aR0 || ctx.aR)), 0, 1);      // 舵で求めている横加速度の、限界に対する割合
  let absLost = 0, sumDrag = 0, satF = 0, satR = 0, workAll = 0; const work = [0, 0, 0, 0];
  const C = K.C, kRoll = (m * (h - hra)) / Math.max(1, 3.5e4 * (1 / ((c.chs && c.chs.rollV) || 1))), tauRoll = 0.12;
  for (let it = 0; it < n; it++) {
    const vx = Math.max(v, 0.5);
    // ロール（弾性ぶんは遅れて、幾何ぶん = ロールセンターの高さは即座に）。左右の荷重移動
    const phiSS = clamp(kRoll * ay, -0.12, 0.12); phi += (phiSS - phi) * Math.min(1, ds / tauRoll);
    const el = Math.abs(phiSS) > 1e-6 ? phi / phiSS : 1;
    const dFzF = m * ay / K.tF * (K.rsd * (h - hra) * el + (K.b / K.wb) * K.rcF), dFzR = m * ay / K.tR * ((1 - K.rsd) * (h - hra) * el + (K.a / K.wb) * K.rcR);
    // ay > 0 = 右へ曲がる（右向きの加速度）→ 左側（外側）の荷重が増える
    const Fz = [Math.max(0, FzF0 / 2 + dFzF), Math.max(0, FzF0 / 2 - dFzF), Math.max(0, FzR0 / 2 + dFzR), Math.max(0, FzR0 / 2 - dFzR)];
    let Fy = 0, Mz = 0, Fxb = 0;
    for (let i = 0; i < 4; i++) {
      const front = i < 2, z = Fz[i]; if (z < 1) { al[i] = 0; W[i].fy = 0; W[i].fz = 0; continue; }
      const vxi = vx - r * Yw[i], vyi = vy + r * X[i], d = front ? dF : dR;
      const aSS = Math.atan2(vyi, Math.max(0.5, Math.abs(vxi))) - d;      // 進む向きとタイヤの向きの差（すべり角）
      al[i] += (aSS - al[i]) * Math.min(1, Math.abs(vxi) * ds / K.sig);      // 緩和長: 転がりながら力が立ち上がる
      // 摩擦: 軸の μ × 荷重感度 × キャンバー × 各輪の温度
      const ls = 1 - K.kLS * (z / K.Fz0 - 1), outer = (i % 2 === 0) === (ay > 0);
      // キャンバー: 車体がロールすると外側の輪はポジティブ側へ起きる（キャンバー変化はロールの約 45% を打ち消すとする）。最適は旋回に対して −1.5° 前後
      const gam = K.cam + Math.abs(phi) / D2R * 0.55, kg = outer ? clamp((1 - 0.01 * Math.pow(gam + 1.5, 2)) / (1 - 0.01 * Math.pow(K.cam + 1.5, 2)), 0.88, 1.06) : 1;
      const kt = clamp(tf(Tm + S.dT[i], y) / Math.max(0.2, tfm), 0.75, 1.15);
      const D = (front ? muF : muR) * clamp(ls, 0.5, 1.4) * kg * kt * z;
      // 縦の力を左右に配る
      const FxA = front ? FxF : FxR, zA = front ? Fz[0] + Fz[1] : Fz[2] + Fz[3], share = (1 - K.la) * 0.5 + K.la * z / Math.max(1, zA);
      const drvAxle = K.drive === '4WD' || (front ? K.drive === 'FF' : K.drive !== 'FF'), fx0 = (FxA >= 0 && drvAxle ? share : z / Math.max(1, zA)) * FxA;
      // 摩擦円: 縦に使ったぶん、横に使える最大が減る（空転・ロックではほとんど残らない）
      // 縦の摩擦の大きさは縦のタイヤの計算（前後それぞれの最大の縦力）を基準に、使った割合 ρ で横の最大を減らす（楕円の摩擦円）
      const Dx = Math.max(1, (front ? fullF : fullR) * z / Math.max(1, zA));
      // ABS・コーナリングブレーキ制御: 曲がりながらのブレーキでは、舵で求めている横の量とすべり角に応じて制動力をロックの手前で抑え、舵が効く横の力を残す（抑えたぶん減速は弱くなる）
      const Ca0 = K.Cs * z * (1 - 0.5 * K.kLS * (z / K.Fz0 - 1)), aPk0 = Math.tan(Math.PI / (2 * C)) / Math.max(0.5, Ca0 / Math.max(1, C * D));
      let fx = fx0; if (fx0 < 0 && brk > 0) { const cap = Dx * Math.min(1 - 0.3 * clamp(Math.abs(al[i]) / aPk0, 0, 1), Math.sqrt(1 - Math.pow(kLat * uLat, 2))); if (-fx0 > cap) { absLost += (-fx0 - cap) * ds; fx = -cap; } }
      const rho = clamp(Math.abs(fx) / Dx, 0, 1);
      const kap = Math.abs(front ? kapF : kapR), spin = kap > 1.6 * kp ? clamp((kap - 1.6 * kp) / (5 * kp), 0, 0.85) : 0;      // ピークを大きく超えて空転・ロックすると横力はさらに落ちる
      const Fymax = D * (Math.sqrt(Math.max(0, 1 - rho * rho)) * (1 - spin) + 0.06);
      const Ca = K.Cs * z * (1 - 0.5 * K.kLS * (z / K.Fz0 - 1)), B = Ca / Math.max(1, C * Math.max(Fymax, 1));      // 初期の傾き（コーナリングの硬さ）は、摩擦円で縦に使った分では減らさない。減るのはピークの横力（Fymax）
      const fy = -Fymax * mf(al[i], B, C);      // 横力（すべり角と逆向き）
      // 車体の座標へ（前輪は舵角ぶん回す）
      const cs = Math.cos(d), sn = Math.sin(d), fxb = fx * cs - fy * sn, fyb = fx * sn + fy * cs;
      Fy += fyb; Fxb += fxb - fx; Mz += X[i] * fyb - Yw[i] * fxb;
      const use = Math.min(1.5, Math.sqrt(fx * fx + fy * fy) / Math.max(1, D)), slipV = Math.abs(vxi * Math.tan(al[i])) + Math.abs(kap * vx);
      work[i] += (Math.abs(fy) * Math.abs(vxi * Math.tan(al[i])) + Math.abs(fx) * Math.abs(kap) * vx) * ds; workAll += work[i];
      const aPk = Math.tan(Math.PI / (2 * C)) / Math.max(0.5, B), sat = Math.abs(al[i]) / Math.max(1e-3, aPk) * (z / Math.max(1, zA) * 2);      // ピークのすべり角に対する比（荷重の大きい輪ほど重く見る）
      if (front) satF = Math.max(satF, sat); else satR = Math.max(satR, sat);
      const wi = W[i]; wi.fz = z; wi.fy = fy; wi.fx = fx; wi.al = al[i]; wi.D = D; wi.use = use; wi.rho = rho; wi.Dx = Dx; wi.fx0 = fx0; wi.Fymax = Fymax; wi.slipV = slipV; wi.kap = front ? kapF : kapR;
    }
    // ESC: 車体の横すべりが大きくなると、片側の車輪にブレーキをかけてヨーを戻す
    if (K.esc && Math.abs(vy / vx) > K.escTh * D2R * 0.5) { const rr = vx * Math.tan(dF) / (K.wb + K.Kus * vx * vx); Mz += clamp(-(r - rr) * K.Iz * 6, -m * G0 * 0.35 * K.tF, m * G0 * 0.35 * K.tF); Fxb -= m * G0 * 0.06; }
    // 運動: 横すべり速度とヨーレート。従来の計算のヨー（トルクベクタリング・カント・横風）は小さな追加として残す
    const ayN = Fy / m + (ctx.bankA || 0) * 0.5, bc = Math.atan2(vy, vx);      // カント（路面の傾き）は旋回を助ける
    vy += (ayN - vx * r) * ds; r += (Mz / K.Iz + (ctx.tvR || 0) * 6) * ds;
    ay = ayN; sumDrag += (Fxb / m + (Fy / m) * Math.sin(bc)) * ds;      // 舵を切った前輪の横力の後ろ向き成分と、横すべりで横力が進む向きと逆に働くぶん（ドリフトで速度が落ちる）
    vy *= 1 - Math.min(0.5, ds * 0.15); // 低速での数値の安定（ごく弱い減衰）
  }
  sumDrag += (brk * (1 - K.bias) - rB) / m * dt;
  sumDrag += Math.min(absLost, brk * dt) / m;      // ABS が抑えたぶん      // EBD で抑えた後輪のブレーキのぶん、減速は弱くなる
  { const fF = (W[0].Fymax || 0) + (W[1].Fymax || 0), dFs = (W[0].D || 0) + (W[1].D || 0), fR = (W[2].Fymax || 0) + (W[3].Fymax || 0), dRs = (W[2].D || 0) + (W[3].D || 0); S.kF = dFs > 1 ? fF / dFs / 1.06 : 1; S.kR = dRs > 1 ? fR / dRs / 1.06 : 1; }
  const beta = Math.atan2(vy, Math.max(v, 0.5)), dBeta = (beta - S.beta) / dt;
  // 低速から上がるあいだは、従来の値と混ぜる
  const rOld = P.yaw || 0; S.vy = vy * blend; S.r = r * blend + rOld * (1 - blend); S.phi = phi; S.ay = ay; S.beta = beta * blend; S.satF = satF; S.satR = satR; S.gU = ctx.gU; S.aF0 = ctx.aF0; S.aR0 = ctx.aR0;
  // 各輪の温度のずれ（滑りの仕事 → 温まる。平均は本体の計算が持つので、ずれだけ）
  { const hc = Math.max(25, m * 0.043) / 4, mean = (work[0] + work[1] + work[2] + work[3]) / 4; for (let i = 0; i < 4; i++) S.dT[i] += ((work[i] - mean) / dt / 1000 * 0.58 / hc - S.dT[i] * (0.02 + 0.002 * v)) * dt; }
  // 出力: 進む向きの角速度（ヨーレート + 横すべり角の変化）、車体の向き（見た目）、アンダー・オーバー、横すべりの減速
  P.yaw = S.r + dBeta * blend; P.yawR = S.r; P.beta = -S.beta; P.delta = dF;
  P.v = Math.max(0, P.v + sumDrag * blend);
  P.us = clamp((satF - 1) * 1.2, 0, 1) * (satF > satR ? 1 : 0.4); P.os = clamp((satR - 1) * 1.2, 0, 1) * (satR >= satF ? 1 : 0.4);
  if (Math.abs(S.beta) > 0.35) P.os = Math.max(P.os, clamp((Math.abs(S.beta) - 0.35) * 2, 0, 1));
  // スピン: 横すべり角が 50° を超えると、事故の挙動（回転して止まる）へ渡す
  if (Math.abs(S.beta) > 0.87 && v > 8 && HW.Crash && HW.Crash.start) { S.spinT += dt; if (S.spinT > 0.15) { const sg = Math.sign(S.r) || 1; HW.Crash.start(game, 6 + Math.abs(S.r) * 3, { spin: sg, spinK: 0.09 }); P.ty5 = null; if (game.toast) game.toast('スピン ― 後輪のグリップが限界を超えました（すべり角 ' + Math.round(Math.abs(S.beta) / D2R) + '°）', true); return true; } } else S.spinT = 0;
  TY5.fillTele(game, S, K, v, ctx, W);
  return true;
};
/* 表示用の各輪の状態（デバッグ F10・ガレージのテレメトリー）*/
TY5.fillTele = function (game, S, K, v, ctx, W) {
  const P = game.P, out = P.tyW4 || (P.tyW4 = [{}, {}, {}, {}]);
  for (let i = 0; i < 4; i++) { const w = W ? W[i] : {}, o = out[i]; o.fz = w.fz || 0; o.al = (w.al || 0) / D2R; o.kap = (w.kap || 0) * 100; o.use = w.use || 0; o.T = (P.tyT || 0) + (S.dT ? S.dT[i] : 0); o.fy = w.fy || 0; o.fx = w.fx || 0; }
  P.tyBeta = (S.beta || 0) / D2R; P.tyPhi = (S.phi || 0) / D2R;
};
/* 文字の表（デバッグ表示の F10 に足す）*/
TY5.lines = function (game) {
  const P = game.P, w = P.tyW4; if (!w) return [];
  const f = (x, d) => (x >= 0 ? ' ' : '') + x.toFixed(d);
  const L = ['タイヤ（4 輪）  荷重kN  すべり角°  スリップ率%  摩擦円の使用%  温度℃'];
  for (let i = 0; i < 4; i++) L.push(NAMES[i] + '      ' + (w[i].fz / 1000).toFixed(2).padStart(5) + '   ' + f(w[i].al, 1).padStart(6) + '    ' + f(w[i].kap, 1).padStart(6) + '      ' + Math.round(w[i].use * 100).toString().padStart(4) + '       ' + Math.round(w[i].T));
  L.push('車体の横すべり角 ' + (P.tyBeta || 0).toFixed(1) + '°  ロール ' + (P.tyPhi || 0).toFixed(1) + '°  アンダー ' + Math.round((P.us || 0) * 100) + '%  オーバー ' + Math.round((P.os || 0) * 100) + '%');
  return L;
};
/* ガレージ: タイヤの性質の説明（部品で変わる）*/
TY5.describe = function (c) {
  const K = TY5.cfgOf(c);
  return { Cs: +K.Cs.toFixed(1), sig: +K.sig.toFixed(2), aPk: +K.aPk.toFixed(1), Kus: +(K.Kus * 1000 / D2R * 0.001 * 1000).toFixed(2), kLS: +K.kLS.toFixed(2), C: K.C, rsd: K.rsd, la: K.la };
};
})(typeof window !== 'undefined' ? window : globalThis);
