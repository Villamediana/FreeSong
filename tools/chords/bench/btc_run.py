"""Runs BTC (Park et al., ISMIR 2019, MIT; github.com/jayg996/BTC-ISMIR19) the way its test.py does,
on the phone-resampled audio, and writes its chords as labs.

    python btc_run.py <btc-repo> <dir>      -> <dir>/<id>.btc.lab (major/minor) and <id>.btcv.lab (large vocabulary)
"""
import glob, os, sys
import numpy as np

def main(repo, d):
    sys.path.append(repo)
    np.float = float  # the repo still uses the alias numpy removed
    import torch, librosa
    torch.set_num_threads(int(os.environ.get("BTC_THREADS", "2")))  # tiny matrices: more threads only spin
    # Only tensors and the two numpy numbers (mean, std) are let out of the checkpoints: no code.
    from numpy._core.multiarray import scalar
    torch.serialization.add_safe_globals([(scalar, "numpy.core.multiarray.scalar"), np.dtype, type(np.dtype("float64"))])
    from btc_model import BTC_model
    from utils.mir_eval_modules import idx2chord, idx2voca_chord
    models = []
    for voca, suffix in ((False, "btc"), (True, "btcv")):
        # run_config.yaml's model section (its loader needs an old PyYAML).
        model_config = dict(feature_size=144, timestep=108, num_chords=170 if voca else 25, input_dropout=0.2, layer_dropout=0.2,
                            attention_dropout=0.2, relu_dropout=0.2, num_layers=8, num_heads=4, hidden_size=128,
                            total_key_depth=128, total_value_depth=128, filter_size=128, loss="ce", probs_out=False)
        ckpt = torch.load(os.path.join(repo, "test", "btc_model_large_voca.pt" if voca else "btc_model.pt"), map_location="cpu", weights_only=True)
        model = BTC_model(config=model_config)
        model.load_state_dict(ckpt["model"])
        model.eval()
        models.append((suffix, model, ckpt["mean"], ckpt["std"], idx2voca_chord() if voca else idx2chord))
    for pcm in sorted(glob.glob(os.path.join(d, "*.phone.f32"))):
        todo = [m for m in models if not os.path.isfile(pcm.replace(".phone.f32", ".%s.lab" % m[0]))]
        if not todo:
            continue
        wav = np.fromfile(pcm, dtype=np.float32)
        raw = cqt_log(wav, librosa)
        for suffix, model, mean, std, names in todo:
            write(model, (raw - mean) / std, names, pcm.replace(".phone.f32", ".%s.lab" % suffix), torch)
        print(os.path.basename(pcm), flush=True)

def cqt_log(wav, librosa):
    """BTC's features: log |CQT| (144 bins from C1, 24 a octave, hop 2048) in 10-second pieces."""
    sr, inst = 22050, int(22050 * 10.0)
    parts, at = [], 0
    while len(wav) > at + inst:
        parts.append(librosa.cqt(wav[at:at + inst], sr=sr, n_bins=144, bins_per_octave=24, hop_length=2048))
        at += inst
    parts.append(librosa.cqt(wav[at:], sr=sr, n_bins=144, bins_per_octave=24, hop_length=2048))
    return np.log(np.abs(np.concatenate(parts, axis=1)) + 1e-6).T

def write(model, feature, names, out, torch):
    unit = 10.0 / 108
    pad = 108 - feature.shape[0] % 108
    feature = np.pad(feature, ((0, pad), (0, 0)))
    with torch.no_grad():
        x = torch.tensor(feature, dtype=torch.float32).unsqueeze(0)
        preds = []
        for t in range(feature.shape[0] // 108):
            h, _ = model.self_attn_layers(x[:, 108 * t:108 * (t + 1), :])
            p, _ = model.output_layer(h)
            preds.extend(p.squeeze().tolist())
    preds = preds[:len(preds) - pad]
    lines, start = [], 0
    for k in range(1, len(preds) + 1):
        if k == len(preds) or preds[k] != preds[start]:
            lines.append("%.3f\t%.3f\t%s\n" % (start * unit, k * unit, names[preds[start]]))
            start = k
    open(out, "w").write("".join(lines))

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
