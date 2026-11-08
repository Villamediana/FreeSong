"""Runs Basic Pitch (the app's own nmp.tflite) over a song, the way BasicPitch.kt does, and writes
the note and onset frames so the JVM test can hear its chords (ChordsLiveTest.hearsRealSongs).

    python basic_pitch_frames.py song.f32      # mono float32 PCM at 22050 Hz (ffmpeg -f f32le)
    -> song.frames.f32, song.onsets.f32        # [frames x 88] float32
"""
import sys
import numpy as np
from ai_edge_litert.interpreter import Interpreter

RATE, HOP = 22050, 256
WINDOW = RATE * 2 - HOP
ANNOT_FPS = RATE // HOP
OVERLAP_FRAMES = 30
OVERLAP = OVERLAP_FRAMES * HOP
STEP = WINDOW - OVERLAP

def main(path, model):
    audio = np.fromfile(path, dtype=np.float32)
    padded = np.concatenate([np.zeros(OVERLAP // 2, np.float32), audio])
    run = Interpreter(model_path=model).get_signature_runner("serving_default")
    notes, onsets = [], []
    i = 0
    while i < len(padded):
        w = np.zeros(WINDOW, np.float32)
        chunk = padded[i:i + WINDOW]
        w[:len(chunk)] = chunk
        out = run(input_2=w.reshape(1, WINDOW, 1))
        notes.append(out["note"][0])
        onsets.append(out["onset"][0])
        i += STEP
    olap = OVERLAP_FRAMES // 2
    n = int(np.floor(len(audio) * (ANNOT_FPS / RATE)))
    unwrap = lambda parts: np.concatenate([p[olap:len(p) - olap] for p in parts])[:n]
    base = path[:-4]
    unwrap(notes).astype(np.float32).tofile(base + ".frames.f32")
    unwrap(onsets).astype(np.float32).tofile(base + ".onsets.f32")
    print(base, n, "frames")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "app/src/main/assets/chords/nmp.tflite")
