# Ouvir e medir o áudio em músicas de verdade

Os testes do tom (e do karaokê) usam sons de resposta conhecida: notas, acordes, cliques. Para
ouvir e medir em música real, ponha arquivos **WAV de 16 bits** numa pasta e aponte `SOUND_AUDIO_DIR`
para ela (nada dessa pasta entra no repositório):

```sh
D=/tmp/musicas; mkdir -p $D
ffmpeg -i musica.m4a -ar 44100 -ac 2 -c:a pcm_s16le $D/musica.wav
```

## Tom (transpor o áudio)

```sh
SOUND_AUDIO_DIR=$D ./gradlew :app:testDebugUnitTest --tests '*PitchOnSongsLiveTest*' -i
```

Escreve `out/<nome>.up2.wav`, `.down3.wav` e `.up5.wav` (dois tons acima… como o celular toca) e
imprime, de cada um: o nível contra o original, o pico, quantas amostras passaram do fundo de escala
e o custo (ms de processamento por segundo de música).

Em 14/11/2026, em 68 s de trechos de 10 músicas: nível −0,35 a −0,70 dB, nenhuma amostra acima do
fundo de escala, ~20 ms por segundo de música estéreo (2% de um núcleo do servidor). A curva de
ataques (onset) do resultado bate com a do original em 0,94–0,98 (um vocoder de fase comum, sem
tratar ataques: 0,52–0,61) e os ataques agudos saem com 0,90–0,94 da nitidez do original (comum: 0,54).
