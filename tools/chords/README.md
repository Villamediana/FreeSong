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

Tirar a voz antes (Demucs htdemucs, o melhor separador; `bench/sep_run.py`), nas 20 primeiras
músicas da régua: mix inteiro 77,5% (59,6%); sem a voz 73,8% (54,3%); só baixo e harmonia 76,9%
(54,3%). Piora — o BTC aprendeu com a música inteira e os resíduos da separação o confundem — e
no celular custaria minutos por música. Não entrou.

## Músicas avulsas: `ChordsLiveTest`

```sh
D=/algum/diretorio
SOUND_LIVE=1 SOUND_CHORDS_DIR=$D ./gradlew :app:testDebugUnitTest --tests '*ChordsLiveTest.fetchesSongs'
for f in $D/*.m4a; do ffmpeg -loglevel error -y -i "$f" -ac 1 -f f32le "${f%.m4a}.44100.f32"; done
SOUND_CHORDS_DIR=$D ./gradlew :app:testDebugUnitTest --tests '*ChordsLiveTest.resamplesLikeThePhone'
SOUND_CHORDS_DIR=$D SOUND_CHORDS_VARIANT=.phone ./gradlew :app:testDebugUnitTest --tests '*ChordsLiveTest.hearsRealSongs' -i
```

Música com cifra (`Song.sheet`): compara acorde por acorde, na ordem, com a cifra.

## Cifras do Cifra Club: `CifraClubLiveTest`

A cifra de verdade sobre a letra de verdade (LRCLIB). O site recusa biblioteca HTTP, então as
páginas são salvas antes por um navegador (uma por música, `<slug>.html`):

```sh
D=/tmp/cifras; mkdir -p $D
google-chrome --headless=new --no-sandbox --timeout=20000 --dump-dom \
  https://www.cifraclub.com.br/legiao-urbana/tempo-perdido/ > $D/tempo-perdido.html
SOUND_LIVE=1 SOUND_CIFRA_DIR=$D ./gradlew :app:testDebugUnitTest --tests '*CifraClubLiveTest*' -i
```

Imprime cada música como o celular mostra e confere que a busca escolhe a cifra certa. Em 08/11:
Si Usted la Viera (versão do Arjona pela letra), Tempo Perdido, Let It Be, Evidências e Perfect com
36/36, 64/70, 177/167 (refrão repetido), 131/137 e 117/119 acordes no lugar.

