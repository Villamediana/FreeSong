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

## Karaokê (tirar a voz)

O teste roda a cadeia inteira como no celular — a música ouvida parte por parte pelo modelo de
verdade (ONNX Runtime), as máscaras guardadas, a voz abaixada no áudio que toca:

```sh
python tools/audio/karaoke_model.py /tmp/modelo          # os dois .onnx que o app baixa
cd /tmp/modelo && mv spleeter-2stems-vocals-fp16.onnx vocals.onnx && mv spleeter-2stems-accompaniment-fp16.onnx accompaniment.onnx
SOUND_KARAOKE_MODEL=/tmp/modelo SOUND_AUDIO_DIR=$D ./gradlew :app:testDebugUnitTest --tests '*KaraokeOnSongsLiveTest*' -i
```

Escreve `out/<nome>.karaoke.wav`. Se a pasta tiver também a voz e a banda da música sozinhas
(`<nome>.vocals.wav` e `<nome>.band.wav` — pistas separadas de verdade, como as do MUSDB18), mede:
o instrumental contra a banda de verdade (SDR), quantos dB a voz cai e o quanto a banda fica inteira.

### Por que este modelo (régua de 14/11/2026)

50 trechos de 7 s do MUSDB18 (teste), instrumental estimado contra a banda de verdade, mediana:

| Modelo | SDR do instrumental | Custo (4 threads do servidor) | Memória |
|---|---|---|---|
| Spleeter 2stems, máscara ao quadrado (**o do app**) | 11,3 dB (voz −10 dB) | 0,02 do tempo da música | pouca |
| Spleeter 2stems, máscara original | 11,45 dB (voz −8,4 dB) | 0,02 | pouca |
| Spleeter, só a rede da banda | 10,9 dB (voz −6,9 dB) | 0,01 | pouca |
| UVR_MDXNET_9482 (MDX-Net pequeno) | 13,9 dB | 0,20 | 2,5 GB |
| Kim_Vocal_2 (MDX-Net, o da Luna) | 14,5 dB | 0,59 | ~5 GB |
| UVR-MDX-NET-Inst_HQ_3 | 14,7 dB | 0,59 | 5,4 GB |

Os MDX-Net tiram a voz bem melhor (3 dB), mas pedem gigabytes de memória e de 10 a 30 vezes o
processamento: num celular são minutos por música, quando cabem. O Spleeter deixa a música pronta
em segundos e começa a tirar a voz em 1–2 s; sobra um resto de voz em algumas músicas.
Outras medidas: meia precisão = mesma qualidade (o download cai de 79 para 39 MB); áudio de 48 kHz
entregue ao modelo sem reamostrar perde 0,7 dB (por isso o app reamostra para 44,1 kHz); duas
passadas não ajudam. Em Kotlin (este teste, 68 s de 10 músicas emendadas): 10,3 dB, voz −8,6 dB,
igual em 44,1 e 48 kHz — a mesma conta em Python dá 10,4 dB.
