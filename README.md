<div align="center">

# FreeSong

**Música é pra ouvir. Não pra pagar de novo todo mês nem pra aguentar anúncio entre uma faixa e outra.**

[**⬇ Baixar a APK**](../../releases/latest) · Android 8.0 ou mais novo

<!-- Capturas de tela: coloque aqui (por exemplo docs/images/player.png) -->

</div>

---

## Por que esse app existe

Gente, eu cansei.

Hoje pra ouvir música no celular você tem duas opções: paga uma assinatura todo mês, pra sempre,
ou ouve de graça engolindo anúncio a cada três músicas, sem poder pular faixa, sem poder escolher
a ordem, com a música parando no meio pra te vender alguma coisa. E se um dia você para de pagar,
tudo o que você "tinha" some junto.

A música já está aí, de graça, no YouTube. O que falta é um player decente pra ouvir ela.
Então eu fiz um.

O FreeSong é isso: um player bonito, rápido e completo, sem conta, sem assinatura, sem anúncio e
sem ninguém olhando o que você escuta. Você abre, busca, toca. Se quiser, baixa pra ouvir sem
internet. Fim.

Fiz pra mim e tô dividindo com quem também cansou.

## O que ele faz

- **Toca qualquer música** do YouTube Music na hora, sem baixar. Tocou uma, ele segue tocando
  coisas do mesmo estilo, que nem uma rádio, e não qualquer coisa com o título parecido.
- **Baixa pra ouvir offline**, uma por uma, no coração ou na setinha. Sem internet? Fica na fila
  e baixa sozinho quando conectar (ou só no Wi-Fi, se você preferir).
- **Letra sincronizada**, acompanhando a música, e **traduzida** verso por verso se ela for em
  outro idioma.
- **O clipe rodando atrás da capa**, em loop, bem de leve. Tocou na capa, abre o vídeo inteiro.
  Não curtiu? Desliga nos Ajustes e fica só a capa.
- **Crossfade** entre as músicas e **equalizador** de verdade.
- **Descobre a música que está tocando** no ambiente, pelo microfone, estilo Shazam.
- **Traz suas playlists e álbuns do Spotify**: cola o link (ou compartilha direto do app do
  Spotify) e pronto, toca aqui.
- **Sua retrospectiva**: todo janeiro, o seu ano em música. As que você mais ouviu, os artistas,
  as horas, o seu horário favorito.
- **Economiza sua internet**: o que você já ouviu fica guardado no celular e não é baixado de novo.
  E tudo o que fica guardado aparece em Ajustes › Armazenamento, com o tamanho, pra você limpar o
  que quiser.

## Como instalar

1. Baixa a APK mais recente em [**Releases**](../../releases/latest).
2. Abre o arquivo no celular. O Android vai perguntar se pode instalar de fora da loja: pode.

Só isso. Não precisa criar conta, não pede seu e-mail, não pede nada.

## Seus dados

Não tem conta, não tem rastreamento, não tem anúncio. Sua biblioteca, seu histórico e seus
ajustes ficam no seu celular.

O app só fala com a internet pro que precisa: buscar e tocar a música no YouTube, pegar a letra
no LRCLIB, mandar a "impressão digital" do som pra descobrir qual música é (nunca a gravação),
traduzir uma letra e ler uma playlist pública do Spotify quando você pede. Tudo por HTTPS.

## Pra quem é dev

O código tá todo aqui. Clona, compila e muda o que quiser.

Precisa do **JDK 21** e do **Android SDK** com a plataforma **37**. O Gradle baixa o resto.

```bash
git clone <este repositório>
cd <pasta>
export ANDROID_HOME=$HOME/Android/Sdk   # ou um local.properties com sdk.dir=...

./gradlew :app:assembleDebug
# a APK sai em app/build/outputs/apk/debug/app-debug.apk
```

A versão debug instala do lado da normal (é outro pacote: `com.villamediana.sound.debug`).

Quer gerar a release assinada com a sua chave? Copia o `keystore.properties.example` pra
`keystore.properties`, aponta pra sua chave e roda `./gradlew :app:assembleRelease`. Chave, senha
e APK nunca vão pro repositório (tá tudo no `.gitignore`).

Testes rodam no próprio computador, sem emulador:

```bash
./gradlew :app:testDebugUnitTest                 # tudo, offline
SOUND_LIVE=1 ./gradlew :app:testDebugUnitTest    # inclui os que batem na internet de verdade
```

Por dentro: Kotlin, Jetpack Compose, Media3 (ExoPlayer), Room, WorkManager, Coil, OkHttp e o
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), que faz a ponte com o
YouTube. Como o código é organizado está em [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Papo reto

Isso aqui é um projeto pessoal, feito por um dev que gosta de música. Não tem nada a ver com
YouTube, Google, Spotify ou Shazam. A ideia não é tirar nada de artista nenhum. É só ouvir música
sem ser tratado como produto. Se você curte um artista, vai no show, compra o disco, apoia do
jeito que der.

## Valeu

[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) ·
[LRCLIB](https://lrclib.net) ·
[SongRec](https://github.com/marin-m/SongRec) e [ShazamIO](https://github.com/dotX12/ShazamIO) ·
[Media3](https://developer.android.com/media/media3) ·
[Coil](https://coil-kt.github.io/coil/)
