import sys, numpy as np, soundfile as sf
from scipy import signal
for p in sys.argv[1:]:
    x, sr = sf.read(p); x = x if x.ndim == 1 else x.mean(1); x = x[int(sr * 0.8):]
    r = np.sqrt(np.mean(x ** 2) + 1e-20)
    # A-weighting (IEC 61672) via bilinear transform
    f1, f2, f3, f4 = 20.598997, 107.65265, 737.86223, 12194.217; A1000 = 1.9997
    nums = [(2 * np.pi * f4) ** 2 * (10 ** (A1000 / 20)), 0, 0, 0, 0]; dens = np.polymul([1, 4 * np.pi * f4, (2 * np.pi * f4) ** 2], [1, 4 * np.pi * f1, (2 * np.pi * f1) ** 2]); dens = np.polymul(np.polymul(dens, [1, 2 * np.pi * f3]), [1, 2 * np.pi * f2])
    b, a = signal.bilinear(nums, dens, sr); xa = signal.lfilter(b, a, x); ra = np.sqrt(np.mean(xa ** 2) + 1e-20)
    bands = [(20, 80), (80, 250), (250, 800), (800, 2500), (2500, 8000)]; X = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2; fr = np.fft.rfftfreq(len(x), 1 / sr); tot = X.sum()
    bs = ' '.join('%d-%d:%5.1f' % (lo, hi, 10 * np.log10(X[(fr >= lo) & (fr < hi)].sum() / tot + 1e-12)) for lo, hi in bands)
    print('%-34s rms %6.1f dBFS  A %6.1f  peak %.2f | %s' % (p.split('/')[-1], 20 * np.log10(r), 20 * np.log10(ra), np.max(np.abs(x)), bs))
