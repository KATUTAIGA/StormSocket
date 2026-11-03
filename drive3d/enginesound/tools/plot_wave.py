import sys, numpy as np, soundfile as sf, matplotlib; matplotlib.use('Agg'); import matplotlib.pyplot as plt
paths = sys.argv[2:]; out = sys.argv[1]
fig, axs = plt.subplots(2, len(paths), figsize=(5.2 * len(paths), 5.2), squeeze=False)
for i, p in enumerate(paths):
    x, sr = sf.read(p); x = x if x.ndim == 1 else x.mean(1); s = int(sr * 1.0); seg = x[s: s + int(sr * 0.08)]
    axs[0][i].plot(np.arange(len(seg)) / sr * 1000, seg, lw=0.6); axs[0][i].set_title(p.split('/')[-1], fontsize=8); axs[0][i].set_xlabel('ms')
    w = x[s: s + int(sr * 2)] * np.hanning(int(sr * 2)); X = 20 * np.log10(np.abs(np.fft.rfft(w)) / len(w) * 4 + 1e-9); f = np.fft.rfftfreq(len(w), 1 / sr)
    axs[1][i].semilogx(f[1:], X[1:], lw=0.5); axs[1][i].set_xlim(20, 8000); axs[1][i].set_ylim(X.max() - 80, X.max() + 3); axs[1][i].set_xlabel('Hz'); axs[1][i].grid(alpha=0.3, which='both')
fig.tight_layout(); fig.savefig(out, dpi=100)
