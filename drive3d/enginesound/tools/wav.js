const fs = require('fs');
exports.writeWav = function (path, f32, sr) {
  fs.mkdirSync(require('path').dirname(path), { recursive: true });
  const n = f32.length, b = Buffer.alloc(44 + n * 2);
  b.write('RIFF', 0); b.writeUInt32LE(36 + n * 2, 4); b.write('WAVEfmt ', 8); b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(sr, 24); b.writeUInt32LE(sr * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write('data', 36); b.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) { let v = Math.max(-1, Math.min(1, f32[i])); b.writeInt16LE(Math.round(v * 32767), 44 + i * 2); }
  fs.writeFileSync(path, b);
};
