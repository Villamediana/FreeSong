"""The CQT BTC was trained on, written out step by step as librosa computes it (librosa.cqt for
sr 22050, hop 2048, 144 bins from C1, 24 a octave), with a plain windowed-sinc halving filter in
place of soxr: the reference for the app's Kotlin port (chords/Cqt.kt).

    python cqt_proto.py compare <file.phone.f32>     librosa vs this, on the song's 10-second pieces
"""
import sys
import numpy as np

SR, HOP, BINS, BPO = 22050, 2048, 144, 24
FMIN = 32.70319566257483  # C1
HANN_BW = 1.50018310546875

def halving_filter(taps=65, beta=7.86):
    n = np.arange(taps) - (taps - 1) / 2
    h = 0.5 * np.sinc(0.5 * n) * np.kaiser(taps, beta)
    return h / h.sum()

H = halving_filter()

def halve(y):
    """y at half its rate: low-passed below the new Nyquist, every other sample (librosa's scale=True: ×√2)."""
    pad = len(H) // 2
    x = np.concatenate([np.zeros(pad), y, np.zeros(pad + 1)])
    out_len = int(np.ceil(len(y) / 2))
    full = np.convolve(x, H, mode="valid")  # full[i] = filtered sample at input index i
    return (full[0:2 * out_len:2] * np.sqrt(2.0)).astype(np.float64)

def bins():
    freqs = FMIN * 2.0 ** (np.arange(BINS) / BPO)
    r = 2.0 ** (2.0 / BPO)
    alpha = (r - 1) / (r + 1)
    Q = 1.0 / alpha
    return freqs, Q

def basis(freqs, sr, Q):
    """An octave's filters in the frequency domain, as librosa.__vqt_filter_fft builds them."""
    lengths = Q * sr / freqs
    n_fft = int(2.0 ** np.ceil(np.log2(lengths.max())))
    rows = []
    for ilen, f in zip(lengths, freqs):
        k = np.arange(np.floor(-ilen / 2), np.floor(ilen / 2))
        sig = np.exp(1j * k * 2 * np.pi * f / sr)
        n = len(sig)
        win = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n) / n)  # periodic Hann
        sig = sig * win
        sig = sig / np.abs(sig).sum()
        padded = np.zeros(n_fft, dtype=complex)
        start = (n_fft - n) // 2
        padded[start:start + n] = sig
        rows.append(padded * ilen / n_fft)
    fb = np.fft.fft(np.array(rows), axis=1)[:, : n_fft // 2 + 1]
    # Sparsify: drop the smallest coefficients making up 1% of each row's magnitude.
    mags = np.abs(fb)
    sorted_m = np.sort(mags, axis=1)
    cum = np.cumsum(sorted_m / mags.sum(axis=1, keepdims=True), axis=1)
    idx = np.argmin(cum < 0.01, axis=1)
    thresh = sorted_m[np.arange(len(fb)), idx]
    fb = fb * (mags >= thresh[:, None])
    return fb.astype(np.complex64), n_fft

def response(y, n_fft, hop, fb):
    """stft(window ones, centered, zero padding) then the filter bank."""
    pad = n_fft // 2
    x = np.concatenate([np.zeros(pad), y, np.zeros(pad)])
    frames = 1 + (len(x) - n_fft) // hop
    D = np.stack([np.fft.rfft(x[t * hop: t * hop + n_fft]) for t in range(frames)], axis=1)
    return fb @ D

def cqt(y):
    freqs, Q = bins()
    # Early downsampling: once (the top filter's cutoff is far below 11 kHz).
    y = halve(np.asarray(y, dtype=np.float64))
    sr, hop = SR / 2, HOP // 2
    lengths = Q * sr / freqs
    out = []
    my_y, my_sr, my_hop = y, sr, hop
    for i in range(BINS // BPO):
        sl = slice(BINS - BPO * (i + 1), BINS - BPO * i)
        fb, n_fft = basis(freqs[sl], my_sr, Q)
        fb = fb * np.sqrt(sr / my_sr)
        out.append(response(my_y, n_fft, my_hop, fb))
        if i < BINS // BPO - 1:
            my_hop //= 2
            my_sr /= 2.0
            my_y = halve(my_y)
    cols = min(o.shape[1] for o in out)
    V = np.concatenate([o[:, :cols] for o in reversed(out)], axis=0)
    return V / np.sqrt(lengths)[:, None]

def features(wav):
    inst = int(SR * 10.0)
    parts, at = [], 0
    while len(wav) > at + inst:
        parts.append(cqt(wav[at:at + inst]))
        at += inst
    parts.append(cqt(wav[at:]))
    return np.log(np.abs(np.concatenate(parts, axis=1)) + 1e-6).T

if __name__ == "__main__" and sys.argv[1] == "compare":
    import librosa
    wav = np.fromfile(sys.argv[2], dtype=np.float32)
    ref = librosa.cqt(wav[:220500], sr=SR, n_bins=BINS, bins_per_octave=BPO, hop_length=HOP)
    mine = cqt(wav[:220500])
    a, b = np.log(np.abs(ref) + 1e-6), np.log(np.abs(mine) + 1e-6)
    print("shape", ref.shape, mine.shape)
    d = np.abs(a - b)
    print("log |CQT| difference: mean %.4f, median %.4f, 99%% %.4f, max %.4f; typical value spread %.2f" % (d.mean(), np.median(d), np.percentile(d, 99), d.max(), a.std()))
    print("by octave (mean diff):", [round(float(d[o * 24:(o + 1) * 24].mean()), 4) for o in range(6)])
