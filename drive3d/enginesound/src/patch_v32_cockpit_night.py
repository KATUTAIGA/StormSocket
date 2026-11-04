# -*- coding: utf-8 -*-
"""v32: ナビ画面の白い光の塊（照明の映り込み）を消す・夜を実際の暗さに。build.py の 25 章から exec される。"""

# ---- 1) ナビ画面: つやのある表面（反射する材質）だと、街灯・照明・ヘッドライトの点光源が「白い塊」になって映り、ブルームで大きくにじむ。
#         液晶は自発光なので、照明に反応しない材質にする（全車種: 手づくりの内装・取り込んだ 3D モデルの車・後付けナビ）
rep("    const scrM = new T.MeshStandardMaterial({ color: 0x030405, emissive: 0xffffff, emissiveMap: this.navTex, emissiveIntensity: 1, roughness: 0.2, metalness: 0.15, fog: false });",
    "    const scrM = new T.MeshBasicMaterial({ map: this.navTex, color: 0xffffff, fog: false });      // v32: 照明を映さない（白い光の塊が出ない）")
rep("    const scrM = this.scrM = new T.MeshStandardMaterial({ color: 0x030405, emissive: 0xffffff, emissiveMap: this.navTex, emissiveIntensity: 1, roughness: 0.2, metalness: 0.15, fog: false });",
    "    const scrM = this.scrM = new T.MeshBasicMaterial({ map: this.navTex, color: 0xffffff, fog: false });      // v32: 照明を映さない（白い光の塊が出ない）")
rep("    if (this.scrM) this.scrM.emissiveIntensity = 1 - 0.3 * lampK;",
    "    if (this.scrM) { if (this.scrM.isMeshBasicMaterial) this.scrM.color.setScalar(1 - 0.5 * lampK); else this.scrM.emissiveIntensity = 1 - 0.3 * lampK; }      // 夜は減光（v32: 夜はさらに暗く）")

# ---- 2) 夜の暗さ: 薄明が終わったあとの空と、環境光（空から回り込む光）を実際の比に近づける。
#         晴れた夜の空の輝度は昼の 1/10^7 程度で、目に見えるのは街あかり・月・ヘッドライトに照らされたところだけ。
#         従来は天文薄明後でも環境光が昼の約 7%（0.066 / 0.95）あり、遠くの壁やスタンドまで見えていた
rep("  { a: -9, top: [0.016, 0.03, 0.09], mid: [0.06, 0.07, 0.17], hor: [0.16, 0.14, 0.22], sun: [1, 0.4, 0.2], sunI: 0.0, hemi: 0.13, hemiSky: [0.25, 0.31, 0.55], hemiGnd: [0.08, 0.09, 0.12], mtn: [0.025, 0.03, 0.07] },",
    "  { a: -9, top: [0.012, 0.022, 0.065], mid: [0.04, 0.05, 0.12], hor: [0.11, 0.10, 0.16], sun: [1, 0.4, 0.2], sunI: 0.0, hemi: 0.075, hemiSky: [0.25, 0.31, 0.55], hemiGnd: [0.07, 0.08, 0.11], mtn: [0.018, 0.022, 0.05] },")
rep("  { a: -12, top: [0.010, 0.018, 0.055], mid: [0.03, 0.045, 0.11], hor: [0.075, 0.08, 0.14], sun: [1, 0.4, 0.2], sunI: 0.0, hemi: 0.088, hemiSky: [0.23, 0.30, 0.53], hemiGnd: [0.065, 0.075, 0.105], mtn: [0.016, 0.021, 0.05] },",
    "  { a: -12, top: [0.004, 0.007, 0.02], mid: [0.010, 0.015, 0.035], hor: [0.026, 0.03, 0.05], sun: [1, 0.4, 0.2], sunI: 0.0, hemi: 0.03, hemiSky: [0.22, 0.29, 0.52], hemiGnd: [0.06, 0.07, 0.10], mtn: [0.006, 0.008, 0.02] },")
rep("  { a: -18, top: [0.006, 0.011, 0.035], mid: [0.018, 0.03, 0.07], hor: [0.04, 0.055, 0.10], sun: [1, 0.4, 0.2], sunI: 0.0, hemi: 0.066, hemiSky: [0.22, 0.29, 0.52], hemiGnd: [0.06, 0.07, 0.10], mtn: [0.012, 0.017, 0.04] }",
    "  { a: -18, top: [0.0015, 0.0025, 0.007], mid: [0.004, 0.006, 0.013], hor: [0.010, 0.013, 0.022], sun: [1, 0.4, 0.2], sunI: 0.0, hemi: 0.014, hemiSky: [0.22, 0.29, 0.52], hemiGnd: [0.06, 0.07, 0.10], mtn: [0.003, 0.004, 0.01] }")
# 月明かり: 満月でも照度は約 0.2 lx（晴天の昼の 50 万分の 1）。目が暗さに慣れても、昼の 1/3 の明るさにはならない
rep("  k.hemi += 0.14 * moonLevel;", "  k.hemi += 0.03 * moonLevel;      // v32: 月明かりの回り込み（従来 0.14 は明るすぎ）")
rep("for (let i = 0; i < 3; i++) k[key][i] += add[i] * moonLevel * (1 - sstep(-4, 2, alt)); }",
    "for (let i = 0; i < 3; i++) k[key][i] += add[i] * 0.45 * moonLevel * (1 - sstep(-4, 2, alt)); }")
rep("ml.color.setRGB(0.58, 0.67, 0.98); ml.intensity = 0.5 * AS.moonLevel * (1 - (this.tunnelK || 0) * 0.9);",
    "ml.color.setRGB(0.58, 0.67, 0.98); ml.intensity = 0.13 * AS.moonLevel * (1 - (this.tunnelK || 0) * 0.9);      // v32: 満月で影がうっすら出る程度")
# 簡易の空（天体計算が使えないとき）の月明かり
rep("this.sun.intensity = (isMoon ? 0.22 : k.sunI) * (1 - tk * 0.9) * (1 - 0.62 * gloom);",
    "this.sun.intensity = (isMoon ? 0.06 : k.sunI) * (1 - tk * 0.9) * (1 - 0.62 * gloom);")
# 夜の遠景: フォグ（空気遠近）の色が明るいと、遠くの物が灰色に浮いて見える
rep("const fogC = new T.Color(...k.hor).multiplyScalar(0.85 - 0.25 * nightK);", "const fogC = new T.Color(...k.hor).multiplyScalar(0.85 - 0.5 * nightK);")
# 夜のミラー: 暗い映像を大きく持ち上げていた（ガンマ 0.62）ので、鏡だけ外より明るく見えていた。実際の鏡と同じく、ほぼそのまま映す
rep("diffuseColor.rgb = mix(diffuseColor.rgb, pow(max(diffuseColor.rgb, vec3(0.0)), vec3(0.62)) * 0.85 + vec3(0.012, 0.014, 0.022), uNight);",
    "diffuseColor.rgb = mix(diffuseColor.rgb, pow(max(diffuseColor.rgb, vec3(0.0)), vec3(0.88)) * 0.92 + vec3(0.002, 0.002, 0.003), uNight);")
print('patch_rl_zv32 ok')

# ---- 3) ミラー: ルームミラーはフロントガラス上端の中央に、天井からの支柱で吊り、運転者の目へ少し下向きに。
#         ドアミラーはドアの前端（Aピラーの付け根の後ろ）に置き、運転席から「横の窓ごしに」見えるようにする（従来はフロントガラスごしにボンネットの上に浮いて見えた）
rep("    const mirror = (w, h, x, y, z, yaw, u0, u1, v0, v1, housing) => {",
    "    const mirror = (w, h, x, y, z, yaw, u0, u1, v0, v1, housing, pit) => {")
rep("      m.position.set(x, y, z); m.rotation.y = yaw; m.position.z += 0.004; root.add(m);\n      const hs = box(w + 0.014, h + 0.014, 0.025, housing); hs.position.set(x, y, z - 0.012); hs.rotation.y = yaw; root.add(hs);",
    "      m.rotation.order = 'YXZ'; m.position.set(x, y, z); m.rotation.set(pit || 0, yaw, 0); m.position.z += 0.004; root.add(m);\n"
    "      const hs = box(w + 0.014, h + 0.014, 0.025, housing); hs.rotation.order = 'YXZ'; hs.position.set(x, y, z - 0.012); hs.rotation.set(pit || 0, yaw, 0); root.add(hs);")
rep("    mirror(0.27, 0.078, -ex, 0.14, -0.62, 0.42, 0.17, 0.83, 0.2, 0.8, blackM);",
    "    { const ry = Math.atan2(ex, 0.62), my = Math.min(0.14, c.hdr - 0.09), mz = Math.max(-0.62, -0.46 - 0.08);      // 運転者の目の方へ向ける（左右・上下）\n"
    "      mirror(0.27, 0.078, -ex, my, mz, ry, 0.17, 0.83, 0.2, 0.8, blackM, Math.atan2(my, Math.hypot(ex, -mz)));\n"
    "      const st = box(0.03, Math.max(0.02, c.hdr - my - 0.035), 0.03, blackM); st.position.set(-ex, (c.hdr + my + 0.035) / 2, mz - 0.03); root.add(st); }      // 天井からの支柱")
rep("        for (const q of cands) { const c0 = new T.Vector3(sd * (hw + q.off) - ex, beltY0 + (truckM ? 0.2 : 0.1), cowlZ + q.dz), nrm = c0.clone().negate().normalize(), f = vis ? vis(c0, nrm) : 1; if (!best || f > best.f + 1e-6) best = { f, c0, nrm, q }; if (f >= 0.95) break; }",
    "        const pX = hw - 0.05, pZ = -0.46, pH = c.hdr;      // Aピラー（ダッシュの上端・カウル → 天井の前端）\n"
    "        for (const q of cands) { const c0 = new T.Vector3(sd * (hw + q.off) - ex, beltY0 + (truckM ? 0.2 : 0.1), cowlZ + q.dz), nrm = c0.clone().negate().normalize();\n"
    "          const tq = (sd * pX - ex) / c0.x, yc = c0.y * tq, zc = c0.z * tq, zp = cowlZ + clamp((yc - dashTop) / Math.max(0.05, pH - dashTop), 0, 1) * (pZ - cowlZ);\n"
    "          const sideWin = tq > 0 && tq < 1 && zc > zp + 0.04 && yc > beltY0 + 0.005;      // 目からの視線が、ピラーより後ろ・ベルトラインより上で横の窓を通る\n"
    "          const f = (vis ? vis(c0, nrm) : 1) * (sideWin ? 1 : 0.35); if (!best || f > best.f + 1e-6) best = { f, c0, nrm, q }; if (f >= 0.95) break; }")
print('patch_rl_zv32 mirrors ok')

# ---- 4) 夜の車内: 内装の材質は「真っ黒につぶれないよう」色の 30% で自己発光していたため、夜でも天井・ドアの内張りが昼のように灰色に見えた。
#         自己発光は昼の値のままにし、暗くなるほど落とす（夜はメーター・ナビの照り返しほどの 4%）。外の街灯・ヘッドライトに照らされたところだけ見える
rep("function mat(color, extra) {\n  const m = new T.MeshLambertMaterial(Object.assign({ color }, extra || {}));\n  if (m.emissive && m.emissive.setHex && m.emissive.multiplyScalar) { m.emissive.setHex(color); m.emissive.multiplyScalar(0.3); }\n  return m;\n}",
    "const CK_MATS = [];      // v32: 夜に自己発光を落とす内装の材質\n"
    "function mat(color, extra) {\n  const m = new T.MeshLambertMaterial(Object.assign({ color }, extra || {}));\n  if (m.emissive && m.emissive.setHex && m.emissive.multiplyScalar) { m.emissive.setHex(color); m.emissive.multiplyScalar(0.3); m.userData.emi0 = m.emissive.clone(); CK_MATS.push(m); if (CK_MATS.length > 4000) CK_MATS.splice(0, 1000); }\n  return m;\n}\n"
    "HW.ckNight = function (lampK) { const k = 1 - 0.86 * Math.max(0, Math.min(1, lampK)); if (HW._ckNk === k && HW._ckNn === CK_MATS.length) return; HW._ckNk = k; HW._ckNn = CK_MATS.length; for (const m of CK_MATS) if (m.userData.emi0) m.emissive.copy(m.userData.emi0).multiplyScalar(k); };")
rep("    this.cluster.material.color.setScalar(1 - (this.nightDim || 0.12) * lampK); this.night = lampK;",
    "    this.cluster.material.color.setScalar(1 - (this.nightDim || 0.12) * lampK); this.night = lampK; if (HW.ckNight) HW.ckNight(lampK * (1 - 0.7 * clamp((game.world && game.world.tunnelK) || 0, 0, 1)));")
print('patch_rl_zv32 cabin ok')
