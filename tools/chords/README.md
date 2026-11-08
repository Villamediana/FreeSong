# Acordes em músicas reais

Confere o caminho inteiro dos acordes (batidas → notas do Basic Pitch → acordes) em gravações de
verdade, aqui no servidor, com o mesmo modelo do app.

```sh
D=/algum/diretorio
# 1. baixa o áudio das músicas de ChordsLiveTest.songs
SOUND_LIVE=1 SOUND_CHORDS_DIR=$D ./gradlew :app:testDebugUnitTest --tests '*ChordsLiveTest.fetchesSongs'
# 2. decodifica em mono 22 kHz e roda o modelo (venv com numpy + ai-edge-litert)
for f in $D/*.m4a; do ffmpeg -loglevel error -y -i "$f" -ac 1 -ar 22050 -f f32le "${f%.m4a}.f32"; done
for f in $D/*.f32; do case $f in *.frames.f32|*.onsets.f32) continue;; esac
  python -I tools/chords/basic_pitch_frames.py "$f" app/src/main/assets/chords/nmp.tflite; done
# 3. ouve os acordes com o código do app e diz quanto do tempo caiu nos acordes conhecidos
SOUND_CHORDS_DIR=$D ./gradlew :app:testDebugUnitTest --tests '*ChordsLiveTest.hearsRealSongs' -i
```

Para conferir o áudio como o celular o entrega (o decodificador dá a taxa original e o
`MonoResampler` do app desce para 22 kHz, com filtro anti-aliasing):

```sh
for f in $D/*.m4a; do ffmpeg -loglevel error -y -i "$f" -ac 1 -f f32le "${f%.m4a}.44100.f32"; done
SOUND_CHORDS_DIR=$D ./gradlew :app:testDebugUnitTest --tests '*ChordsLiveTest.resamplesLikeThePhone'
for f in $D/*.phone.f32; do python -I tools/chords/basic_pitch_frames.py "$f" app/src/main/assets/chords/nmp.tflite; done
SOUND_CHORDS_DIR=$D SOUND_CHORDS_VARIANT=.phone ./gradlew :app:testDebugUnitTest --tests '*ChordsLiveTest.hearsRealSongs' -i
```

Pelo caminho do celular (1.10.1): Let It Be 97%, Stand By Me 100%, Perfect 99%, Riptide 90%.

Resultado em 2026-11-08: Let It Be 96%, Stand By Me 100%, Perfect 99%, Riptide 88% (Riptide é
tocada com capotraste na 1ª: soa Bbm–Ab–Db).
