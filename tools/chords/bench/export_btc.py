"""Writes BTC's large-vocabulary model (MIT, github.com/jayg996/BTC-ISMIR19) as the app's asset, and
a reference the Kotlin port is checked against.

    python export_btc.py <btc-repo> <out.bin>                       the weights (float16)
    python export_btc.py <btc-repo> <out.bin> <song.phone.f32> <dir>  + <dir>/btc_check.{feat,logp}.f32

Layout (little-endian): "BTC1", float32 mean, float32 std, then float16 tensors in this order:
embedding (128x144); 8 layers of [forward block, backward block] each q, k, v, o (128x128),
conv1 (128x128x3) + bias, conv2 (128x128x3) + bias, norm1 gamma+beta, norm2 gamma+beta, then the
layer's linear (128x256) + bias; final norm gamma+beta; output (170x128) + bias.
"""
import os, struct, sys
import numpy as np

def main(repo, out, song=None, check_dir=None):
    sys.path.append(repo)
    np.float = float
    import torch
    torch.set_num_threads(2)
    from numpy._core.multiarray import scalar
    torch.serialization.add_safe_globals([(scalar, "numpy.core.multiarray.scalar"), np.dtype, type(np.dtype("float64"))])
    ckpt = torch.load(os.path.join(repo, "test", "btc_model_large_voca.pt"), map_location="cpu", weights_only=True)
    sd = {k: v.numpy().astype(np.float32) for k, v in ckpt["model"].items()}
    order = ["self_attn_layers.embedding_proj.weight"]
    for l in range(8):
        p = "self_attn_layers.self_attn_layers.%d." % l
        for b in ("attn_block", "backward_attn_block"):
            q = p + b + "."
            order += [q + "multi_head_attention.%s_linear.weight" % n for n in ("query", "key", "value", "output")]
            order += [q + "positionwise_convolution.layers.0.conv.weight", q + "positionwise_convolution.layers.0.conv.bias",
                      q + "positionwise_convolution.layers.1.conv.weight", q + "positionwise_convolution.layers.1.conv.bias",
                      q + "layer_norm_mha.gamma", q + "layer_norm_mha.beta", q + "layer_norm_ffn.gamma", q + "layer_norm_ffn.beta"]
        order += [p + "linear.weight", p + "linear.bias"]
    order += ["self_attn_layers.layer_norm.gamma", "self_attn_layers.layer_norm.beta",
              "output_layer.output_projection.weight", "output_layer.output_projection.bias"]
    with open(out, "wb") as f:
        f.write(b"BTC1")
        f.write(struct.pack("<ff", float(ckpt["mean"]), float(ckpt["std"])))
        n = 0
        for k in order:
            a = sd[k].astype("<f2")
            f.write(a.tobytes(order="C"))
            n += a.size
    print("wrote", out, n, "weights,", os.path.getsize(out), "bytes")
    if song:
        sys.path.append(os.path.dirname(os.path.abspath(__file__)))
        import cqt_proto
        from btc_model import BTC_model
        cfg = dict(feature_size=144, timestep=108, num_chords=170, input_dropout=0.2, layer_dropout=0.2, attention_dropout=0.2,
                   relu_dropout=0.2, num_layers=8, num_heads=4, hidden_size=128, total_key_depth=128, total_value_depth=128,
                   filter_size=128, loss="ce", probs_out=True)
        model = BTC_model(config=cfg)
        # The same float16 weights the app reads.
        model.load_state_dict({k: torch.tensor(v.astype(np.float16).astype(np.float32)) for k, v in sd.items()})
        model.eval()
        wav = synthetic() if song == "synthetic" else np.fromfile(song, dtype=np.float32)[: 22050 * 25]
        feat = cqt_proto.features(wav).astype(np.float32)
        norm = (feat - float(ckpt["mean"])) / float(ckpt["std"])
        pad = 108 - norm.shape[0] % 108
        x = torch.tensor(np.pad(norm, ((0, pad), (0, 0))), dtype=torch.float32).unsqueeze(0)
        outs = []
        with torch.no_grad():
            for t in range(x.shape[1] // 108):
                h, _ = model.self_attn_layers(x[:, 108 * t:108 * (t + 1), :])
                outs.append(torch.log_softmax(model.output_layer(h), -1).squeeze(0).numpy())
        logp = np.concatenate(outs)[: feat.shape[0]].astype(np.float32)
        feat.astype("<f4").tofile(os.path.join(check_dir, "btc_check.feat.f32"))
        logp.astype("<f4").tofile(os.path.join(check_dir, "btc_check.logp.f32"))
        print("check:", feat.shape, logp.shape)

def synthetic():
    """25 s of C Am F G (2.5 s each), three harmonics a note, a bass: the test signal BtcTest also makes."""
    chords = [[48, 60, 64, 67], [45, 57, 60, 64], [41, 57, 60, 65], [43, 55, 59, 62]]
    n = np.arange(22050 * 25)
    t = n / 22050.0
    which = (np.floor(t / 2.5).astype(int)) % 4
    out = np.zeros(len(n))
    for c, notes in enumerate(chords):
        on = which == c
        for m in notes:
            f = 440.0 * 2 ** ((m - 69) / 12.0)
            for h in (1, 2, 3):
                out[on] += 0.2 / h * np.sin(2 * np.pi * f * h * t[on])
    return out.astype(np.float32)

if __name__ == "__main__":
    main(*sys.argv[1:])
