# Acordes em músicas reais

O app ouve os acordes no próprio celular: o áudio em mono 22 kHz (`MonoResampler`), o espectro
constant-Q (`Cqt`, o mesmo do librosa), o modelo BTC (`Btc`, transformer de reconhecimento de
acordes, MIT, reescrito em Kotlin) e a leitura em acordes de tocar (`ChordReading`, Viterbi com
preço por troca).

## Régua: `bench/`

53 músicas do McGill Billboard, com os acordes marcados por músicos segundo a segundo. Ver
`bench/README.md`. Resultado (2026-11-08), acerto ponderado pelo tempo:

| | nota base + maior/menor | com a sétima |
|---|---|---|
| ouvido antigo (Basic Pitch + regras, até 1.10.4) | 64,0% | 47,1% |
| BTC (1.11.0) | 77,4% | 61,3% |

## Músicas avulsas: `ChordsLiveTest`

```sh
D=/algum/diretorio
SOUND_LIVE=1 SOUND_CHORDS_DIR=$D ./gradlew :app:testDebugUnitTest --tests '*ChordsLiveTest.fetchesSongs'
for f in $D/*.m4a; do ffmpeg -loglevel error -y -i "$f" -ac 1 -f f32le "${f%.m4a}.44100.f32"; done
SOUND_CHORDS_DIR=$D ./gradlew :app:testDebugUnitTest --tests '*ChordsLiveTest.resamplesLikeThePhone'
SOUND_CHORDS_DIR=$D SOUND_CHORDS_VARIANT=.phone ./gradlew :app:testDebugUnitTest --tests '*ChordsLiveTest.hearsRealSongs' -i
```

Música com cifra (`Song.sheet`): compara acorde por acorde, na ordem, com a cifra.
