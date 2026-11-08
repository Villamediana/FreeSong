"""Experiments on BTC's output before porting it: smoothing the frame-by-frame chords and correcting
the recording's tuning. Writes labs for evaluate.py:

    python btc_post.py <btc-repo> <dir>
    -> <id>.btcv_raw.lab   frame argmax (as btc_run.py)
       <id>.btcv_hmm.lab   Viterbi over the frame probabilities, a price on every change
       <id>.btcv_tune.lab  the same, the CQT shifted to the recording's own tuning
"""
import glob, os, sys
import numpy as np

def main(repo, d):
    sys.path.append(repo)
    np.float = float
    import torch, librosa
    torch.set_num_threads(int(os.environ.get("BTC_THREADS", "2")))
    from numpy._core.multiarray import scalar
    torch.serialization.add_safe_globals([(scalar, "numpy.core.multiarray.scalar"), np.dtype, type(np.dtype("float64"))])
    from btc_model import BTC_model
    from utils.mir_eval_modules import idx2voca_chord
    cfg = dict(feature_size=144, timestep=108, num_chords=170, input_dropout=0.2, layer_dropout=0.2, attention_dropout=0.2,
               relu_dropout=0.2, num_layers=8, num_heads=4, hidden_size=128, total_key_depth=128, total_value_depth=128,
               filter_size=128, loss="ce", probs_out=True)
    ckpt = torch.load(os.path.join(repo, "test", "btc_model_large_voca.pt"), map_location="cpu", weights_only=True)
    model = BTC_model(config=cfg)
    model.load_state_dict(ckpt["model"])
    model.eval()
    mean, std, names = ckpt["mean"], ckpt["std"], idx2voca_chord()
    penalties = [float(p) for p in os.environ.get("BTC_PENALTIES", "4").split(",")]
    for pcm in sorted(glob.glob(os.path.join(d, "*.phone.f32"))):
        wav = np.fromfile(pcm, dtype=np.float32)
        base = pcm.replace(".phone.f32", "")
        variants = (("_proto", "proto"),) if os.environ.get("BTC_CQT") == "proto" else (("", 0.0), ("_tune", None))
        for tag, tuning in variants:
            if tuning == "proto":
                sys.path.append(os.path.dirname(os.path.abspath(__file__)))
                import cqt_proto
                feat = cqt_proto.features(wav)
            else:
                if tuning is None:
                    tuning = float(librosa.estimate_tuning(y=wav, sr=22050, bins_per_octave=24))
                feat = cqt_log(wav, librosa, tuning)
            logp = log_probs(model, (feat - mean) / std, torch)
            if tag == "":
                write(base + ".btcv_raw.lab", logp.argmax(1), names)
            tuning = 0.0 if tuning == "proto" else tuning
            for pen in penalties:
                write(base + ".btcv_hmm%s%s.lab" % (tag, "" if len(penalties) == 1 else "%g" % pen), viterbi(logp, pen), names)
        print(os.path.basename(base), "tuning %+.2f" % tuning, flush=True)

def cqt_log(wav, librosa, tuning):
    sr, inst = 22050, int(22050 * 10.0)
    parts, at = [], 0
    while len(wav) > at + inst:
        parts.append(librosa.cqt(wav[at:at + inst], sr=sr, n_bins=144, bins_per_octave=24, hop_length=2048, tuning=tuning))
        at += inst
    parts.append(librosa.cqt(wav[at:], sr=sr, n_bins=144, bins_per_octave=24, hop_length=2048, tuning=tuning))
    return np.log(np.abs(np.concatenate(parts, axis=1)) + 1e-6).T

def log_probs(model, feature, torch):
    n = feature.shape[0]
    pad = 108 - n % 108
    feature = np.pad(feature, ((0, pad), (0, 0)))
    out = []
    with torch.no_grad():
        x = torch.tensor(feature, dtype=torch.float32).unsqueeze(0)
        for t in range(feature.shape[0] // 108):
            h, _ = model.self_attn_layers(x[:, 108 * t:108 * (t + 1), :])
            out.append(torch.log_softmax(model.output_layer(h), -1).squeeze(0).numpy())
    return np.concatenate(out)[:n]

def viterbi(logp, penalty):
    n, k = logp.shape
    score = logp[0].copy()
    back = np.zeros((n, k), dtype=np.int32)
    for t in range(1, n):
        best = score.argmax()
        stay = score
        move = score[best] - penalty
        back[t] = np.where(stay >= move, np.arange(k), best)
        score = np.maximum(stay, move) + logp[t]
    path = np.zeros(n, dtype=np.int32)
    path[-1] = score.argmax()
    for t in range(n - 1, 0, -1):
        path[t - 1] = back[t][path[t]]
    return path

def write(out, preds, names):
    unit = 10.0 / 108
    lines, start = [], 0
    for k in range(1, len(preds) + 1):
        if k == len(preds) or preds[k] != preds[start]:
            lines.append("%.3f\t%.3f\t%s\n" % (start * unit, k * unit, names[preds[start]]))
            start = k
    open(out, "w").write("".join(lines))

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
