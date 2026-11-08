# Régua dos acordes

Músicas do [McGill Billboard](https://ddmal.music.mcgill.ca/research/The_McGill_Billboard_Project_(Chord_Analysis_Dataset)/)
(acordes marcados por músicos, segundo a segundo; nenhuma delas no treino do BTC), ouvidas pelo
mesmo código do app e pontuadas como no MIREX.

```sh
B=/algum/diretorio
# anotações (índice + labs)
curl -L -o $B/index.csv "https://www.dropbox.com/s/o0olz0uwl9z9stb/billboard-2.0-index.csv?dl=1"
curl -L -o $B/lab.tar.xz "https://www.dropbox.com/s/fg8lvy79o7etiyc/billboard-2.0.1-lab.tar.xz?dl=1"
mkdir -p $B/lab && tar -xJf $B/lab.tar.xz -C $B/lab
python -I select.py $B/index.csv $B/lab/McGill-Billboard $B/songs 60       # amostra por década
# áudio: a gravação com a mesma duração da anotada (±4 s)
SOUND_LIVE=1 SOUND_BENCH_DIR=$B/songs ./gradlew :app:testDebugUnitTest --tests '*ChordsBenchTest.fetches'
for f in $B/songs/*.m4a; do ffmpeg -loglevel error -y -i "$f" -ac 1 -ar 44100 -f f32le "${f%.m4a}.44100.f32"; done
SOUND_BENCH_DIR=$B/songs ./gradlew :app:testDebugUnitTest --tests '*ChordsBenchTest.resamples'
SOUND_BENCH_DIR=$B/songs ./gradlew :app:testDebugUnitTest --tests '*ChordsBenchTest.hears'     # -> <id>.ours.lab
python -I evaluate.py $B/songs ours          # venv com numpy + mir_eval
```

`btc_run.py`, `btc_post.py` e `cqt_proto.py` rodam o BTC original (PyTorch + librosa) para
comparar; `export_btc.py` gera `assets/chords/btc_voca.bin` e a referência do `BtcTest`.
