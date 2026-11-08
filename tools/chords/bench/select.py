"""Picks the benchmark songs from the McGill Billboard dataset (chords marked by musicians, second
by second): a fixed sample across the decades, written as a manifest the JVM test downloads.

    python select.py <billboard-index.csv> <lab-dir/McGill-Billboard> <out-dir> [count]
    -> <out-dir>/manifest.tsv   (id, title, artist, seconds)  and  <out-dir>/<id>.ref.lab (majmin)
"""
import csv, os, random, shutil, sys

def main(index, labs, out, count=60):
    rows = [r for r in csv.DictReader(open(index, encoding="utf-8")) if r["title"]]
    have = [r for r in rows if os.path.isfile(os.path.join(labs, r["id"].zfill(4), "majmin.lab"))]
    # One per (title, artist): the dataset repeats songs charted twice.
    seen, songs = set(), []
    for r in have:
        k = (r["title"].lower(), r["artist"].lower())
        if k not in seen:
            seen.add(k)
            songs.append(r)
    random.Random(7).shuffle(songs)
    songs.sort(key=lambda r: r["chart_date"][:3])  # by decade, shuffled inside
    by_decade = {}
    for r in songs:
        by_decade.setdefault(r["chart_date"][:3], []).append(r)
    picked = []
    while len(picked) < count and any(by_decade.values()):
        for d in sorted(by_decade):
            if by_decade[d] and len(picked) < count:
                picked.append(by_decade[d].pop(0))
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "manifest.tsv"), "w", encoding="utf-8") as f:
        for r in picked:
            lab = os.path.join(labs, r["id"].zfill(4), "majmin.lab")
            end = float(open(lab).read().strip().splitlines()[-1].split()[1])
            shutil.copy(lab, os.path.join(out, r["id"].zfill(4) + ".ref.lab"))
            shutil.copy(os.path.join(labs, r["id"].zfill(4), "majmin7.lab"), os.path.join(out, r["id"].zfill(4) + ".ref7.lab"))
            f.write("%s\t%s\t%s\t%.1f\n" % (r["id"].zfill(4), r["title"], r["artist"], end))
    print(len(picked), "songs ->", out)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else 60)
