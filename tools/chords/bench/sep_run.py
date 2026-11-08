"""Experiment: does taking the voice out (Demucs htdemucs, the best separator around) help BTC?
Writes, per song, the accompaniment at 22 kHz for btc_post.py to hear.

    python sep_run.py <dir> [count]   -> <dir>/sep/<id>.phone.f32 (drums+bass+other) and
                                         <dir>/harm/<id>.phone.f32 (bass+other)
"""
import glob, os, sys
import numpy as np

def main(d, count=20):
    import torch, soxr
    torch.set_num_threads(8)
    from demucs.pretrained import get_model
    from demucs.apply import apply_model
    model = get_model("htdemucs")
    model.eval()
    srcs = model.sources  # drums, bass, other, vocals
    for sub in ("sep", "harm"):
        os.makedirs(os.path.join(d, sub), exist_ok=True)
    natives = sorted(glob.glob(os.path.join(d, "*.44100.f32")))[: int(count)]
    for f in natives:
        i = os.path.basename(f)[: -len(".44100.f32")]
        if os.path.isfile(os.path.join(d, "harm", i + ".phone.f32")):
            continue
        y = np.fromfile(f, dtype=np.float32)
        x = torch.tensor(np.stack([y, y]))[None]
        with torch.no_grad():
            out = apply_model(model, x, shifts=0, split=True, overlap=0.25, progress=False)[0]  # (sources, 2, n)
        parts = {s: out[k].mean(0).numpy() for k, s in enumerate(srcs)}
        acc = parts["drums"] + parts["bass"] + parts["other"]
        harm = parts["bass"] + parts["other"]
        for sub, sig in (("sep", acc), ("harm", harm)):
            soxr.resample(sig.astype(np.float32), 44100, 22050).astype("<f4").tofile(os.path.join(d, sub, i + ".phone.f32"))
        print(i, flush=True)

if __name__ == "__main__":
    main(*sys.argv[1:])
