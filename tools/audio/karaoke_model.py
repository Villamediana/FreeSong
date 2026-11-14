"""O modelo do karaokê do FreeSong, como o app baixa: Spleeter 2stems (Deezer, MIT) em ONNX,
convertido para meia precisão (metade do tamanho, mesma qualidade na régua).

    pip install onnx onnxconverter-common
    python tools/audio/karaoke_model.py pasta/

Parte do pacote ONNX do sherpa-onnx (k2-fsa, Apache-2.0):
https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/sherpa-onnx-spleeter-2stems.tar.bz2
Escreve os dois arquivos com o nome que a release `karaoke-1` usa e imprime tamanho e SHA-256 —
os mesmos de `KaraokeModel.PARTS` (o app confere os dois ao baixar).
"""
import hashlib, io, os, sys, tarfile, urllib.request

import onnx
from onnxconverter_common import float16

SOURCE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/sherpa-onnx-spleeter-2stems.tar.bz2"
NAMES = {"vocals": "spleeter-2stems-vocals-fp16.onnx", "accompaniment": "spleeter-2stems-accompaniment-fp16.onnx"}


def main(out):
    os.makedirs(out, exist_ok=True)
    with urllib.request.urlopen(SOURCE) as r:
        archive = tarfile.open(fileobj=io.BytesIO(r.read()), mode="r:bz2")
    for stem, name in NAMES.items():
        member = next(m for m in archive.getmembers() if m.name.endswith(f"/{stem}.onnx"))
        model = onnx.load_model_from_string(archive.extractfile(member).read())
        half = float16.convert_float_to_float16(model, keep_io_types=True)
        path = os.path.join(out, name)
        onnx.save(half, path)
        data = open(path, "rb").read()
        print(f"{name}  {len(data)} bytes  sha256 {hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
