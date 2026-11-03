import sys, json, numpy as np, soundfile as sf, matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
from scipy import signal
wav, out = sys.argv[1], sys.argv[2]; fmax = float(sys.argv[3]) if len(sys.argv) > 3 else 3000
x, sr = sf.read(wav); x = x if x.ndim == 1 else x.mean(1); meta = json.load(open(wav.replace('.wav', '.json')))
f, t, S = signal.spectrogram(x, sr, nperseg=4096, noverlap=3584, window='hann'); S = 10 * np.log10(S + 1e-14); m = f <= fmax
fig, ax = plt.subplots(figsize=(10, 4)); ax.pcolormesh(t, f[m], S[m], shading='auto', vmin=S.max() - 85, vmax=S.max()); ax.set_title(wav.split('/')[-1]); ax.set_xlabel('s'); ax.set_ylabel('Hz'); fig.tight_layout(); fig.savefig(out, dpi=100)
