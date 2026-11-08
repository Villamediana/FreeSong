"""Scores chord labs against the musicians' ones (MIREX style, weighted by time).

    python evaluate.py <dir> <system> [<system> ...]      e.g.  evaluate.py songs ours btc btcv
    reads <dir>/<id>.ref.lab (majmin) and <id>.ref7.lab (with sevenths), <id>.<system>.lab

majmin: right root and major/minor, where a chord is marked (no-chord stretches left out, as the
app always shows a chord). sevenths: also the seventh (7, maj7, min7). The recordings found online
can start a little earlier or later than the annotated ones: each song is scored at the offset
(within ±2 s) that fits it best, the same search for every system.
"""
import glob, os, sys
import numpy as np
import mir_eval

def load(path):
    """start end label per line (blank lines skipped: the dataset's files end with some)."""
    rows = [l.split() for l in open(path) if l.strip()]
    return np.array([[float(r[0]), float(r[1])] for r in rows]), [r[2] for r in rows]

def score(ref_i, ref_l, est_i, est_l, compare):
    est_i, est_l = mir_eval.util.adjust_intervals(est_i, est_l, ref_i.min(), ref_i.max(), mir_eval.chord.NO_CHORD, mir_eval.chord.NO_CHORD)
    ints, r, e = mir_eval.util.merge_labeled_intervals(ref_i, ref_l, est_i, est_l)
    dur = mir_eval.util.intervals_to_durations(ints)
    comp = np.array(compare(r, e), dtype=float)
    mask = (comp >= 0) & (np.array(r) != "N")
    return float((comp * dur * mask).sum()), float((dur * mask).sum())

def main(d, systems):
    ids = sorted(os.path.basename(p)[:-8] for p in glob.glob(os.path.join(d, "*.ref.lab")))
    totals = {s: [0.0, 0.0, 0.0, 0.0] for s in systems}
    print("%-6s %-38s" % ("id", "song") + "".join("%14s" % s for s in systems))
    titles = {l.split("\t")[0]: l.split("\t")[1] + " — " + l.split("\t")[2] for l in open(os.path.join(d, "manifest.tsv"), encoding="utf-8")}
    for i in ids:
        if not all(os.path.isfile(os.path.join(d, "%s.%s.lab" % (i, s))) for s in systems):
            continue
        ref_i, ref_l = load(os.path.join(d, i + ".ref.lab"))
        ref7_i, ref7_l = load(os.path.join(d, i + ".ref7.lab"))
        cells = []
        for s in systems:
            est_i, est_l = load(os.path.join(d, "%s.%s.lab" % (i, s)))
            best = None
            for off in np.arange(-2.0, 2.01, 0.1):
                hit, tot = score(ref_i, ref_l, est_i + off, est_l, mir_eval.chord.majmin)
                if best is None or hit > best[0]:
                    best = (hit, tot, off)
            hit7, tot7 = score(ref7_i, ref7_l, est_i + best[2], est_l, mir_eval.chord.sevenths)
            t = totals[s]
            t[0] += best[0]; t[1] += best[1]; t[2] += hit7; t[3] += tot7
            cells.append("%5.1f%% %5.1f%%" % (100 * best[0] / max(best[1], 1e-9), 100 * hit7 / max(tot7, 1e-9)))
        print("%-6s %-38s" % (i, titles.get(i, "")[:38]) + "".join("%14s" % c for c in cells))
    print("\n%-45s" % "TOTAL (majmin, sevenths)" + "".join("%14s" % ("%5.1f%% %5.1f%%" % (100 * t[0] / max(t[1], 1e-9), 100 * t[2] / max(t[3], 1e-9))) for t in totals.values()))

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
