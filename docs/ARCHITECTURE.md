# Sound — arquitetura e regras de trabalho

Sound é um player de música para Android (Kotlin + Jetpack Compose + Media3) pensado para ser
**offline**: busca e baixa do YouTube / YouTube Music (NewPipeExtractor), com metadados do iTunes
Search API e letras do LRCLIB. Também **toca online** sem baixar (padrão ao tocar um resultado da
busca), mostra o **vídeo** da música e tem **equalizador**. Biblioteca, playlists, álbuns, aleatório,
reprodução inteligente por gênero e letras sincronizadas das músicas baixadas funcionam sem rede.

Pacote/applicationId: `com.villamediana.sound` (debug: `.debug`). minSdk 26, target 36, compile 37.
Toolchain: AGP 9.5.1 (Kotlin embutido — **não** aplicar `org.jetbrains.kotlin.android`), Kotlin 2.4.21,
KSP 2.3.13, Gradle 9.8.2, Compose BOM 2026.09.00, Media3 1.11.1, Room 2.8.5, WorkManager 2.12,
Coil 3.6.3, OkHttp 5.5.1, NewPipeExtractor v0.26.5 (JitPack).

```bash
export JAVA_HOME=$HOME/.toolchain/jdk-21.0.13+9 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:compileDebugKotlin --console=plain -q          # compila
./gradlew :app:testDebugUnitTest --console=plain              # testes JVM/Robolectric
SOUND_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*Live*' # testes que batem na internet de verdade
```

Nunca subir emulador. Nada de `assembleRelease` fora do release.

## Camadas e contratos (arquivos compartilhados — NÃO editar dentro de um módulo)

| Arquivo | O que é |
|---|---|
| `data/db/Entities.kt`, `Daos.kt`, `SoundDatabase.kt` | Room. `Track` é ao mesmo tempo entidade e modelo de domínio. |
| `data/LibraryRepository.kt` | Fonte única da biblioteca: flows de músicas, álbuns, artistas, gêneros, playlists, curtidas, histórico; mutações (curtir, editar, playlists, apagar). Agrupa em memória. |
| `data/model/LibraryModels.kt` | AlbumSummary/Detail, ArtistSummary/Detail, GenreSummary, PlaylistSummary/Detail, LocalSearchResult, PinnedItem… |
| `data/Storage.kt` | Pastas no armazenamento do app: audio/, covers/, lyrics/, tmp/. |
| `data/SettingsStore.kt` | DataStore com as preferências (`Settings`). |
| `data/Lrc.kt` | Parser de LRC (`Lrc.parse`, `ParsedLyrics.indexAt`). |
| `online/OnlineContracts.kt` | `YouTubeSource`, `MetadataEnricher`, `LyricsSource` + modelos remotos. |
| `download/DownloadContracts.kt` | `DownloadController`, `DownloadSummary`. |
| `playback/PlaybackContracts.kt` | `PlayerConnection`, `PlaybackState`, `PlaySource`, `SmartSeed`, `SleepTimer`, `RepeatMode`. |
| `smart/SmartContracts.kt` | `Mix`, `Taste`. Assinaturas de `SmartMixes`/`SmartQueue` também são contrato. |
| `AppGraph.kt` | DI manual. Acesso: `SoundApplication.graph` (library, settings, storage, youTube, enricher, lyrics, downloads, player, http, db, scope). |
| `ui/theme/*` | Cores, tipografia, `Dimens`, `Motion`, `LocalBottomInset`. |
| `ui/components/*` | `Cover`, `MosaicCover`, `TrackRow`, `PlayingBars`, `SectionHeader`, `EmptyState`, `PlayPauseButton`, `PillButton`, `FilterPill`, `pressScale`, `SoundMark`, `SoundWordmark`, `CoverColors`/`rememberCoverColor`, `formatDuration`/`formatLongDuration`/`formatBytes`. |
| `ui/nav/*` | Rotas type-safe, `Navigator` (`LocalNavigator` / `navigator()`), `SoundRoot` (NavHost + barra inferior + `PlayerLayer`), `LinkInbox`. |
| `AndroidManifest.xml`, `build.gradle.kts`, `libs.versions.toml`, `res/values/strings.xml` | Infra. |

Se um módulo precisar de algo num arquivo compartilhado: **não edite** — crie um helper/extensão no
próprio pacote ou descreva a necessidade no relatório final. Nada de dependência nova.

## Módulos (cada um é dono só das suas pastas)

| Módulo | Pastas | Entrega |
|---|---|---|
| online | `online/` (menos `OnlineContracts.kt`) | `NewPipeYouTubeSource`, `CatalogMetadataEnricher` (limpeza de título + iTunes), `LrclibLyricsSource` |
| download | `download/` (menos `DownloadContracts.kt`) | `WorkDownloadController` + worker em WorkManager |
| playback | `playback/` (menos `PlaybackContracts.kt`), `smart/` (menos `SmartContracts.kt`) | `PlaybackService`, `MediaPlayerConnection`, `SmartQueue`, `SmartMixes` |
| ui-player | `ui/player/` | `PlayerLayer`: mini player + tela cheia, letras, fila, timer |
| ui-home | `ui/home/`, `ui/library/`, `ui/settings/` | Início, Biblioteca, Ajustes |
| ui-detail | `ui/detail/`, `ui/sheets/` | Álbum, Artista, Playlist, Gênero, Mix, Curtidas, Músicas; folhas de ações |
| ui-search | `ui/search/`, `ui/downloads/` | Busca online/local, página de álbum/playlist/artista remoto, Downloads |

Strings: cada módulo cria `res/values/strings_<módulo>.xml` (pt-BR, padrão) **e**
`res/values-es/strings_<módulo>.xml` (espanhol), com nomes prefixados (`home_`, `lib_`, `settings_`,
`player_`, `detail_`, `sheet_`, `search_`, `dl_`, `online_`, `download_`, `playback_`) para não colidir.
Nada de texto de UI hardcoded em Kotlin. Recursos novos (drawables) também prefixados.

JSON: usar `kotlinx.serialization` (o `org.json` do Android vira stub nos testes JVM).

## Linguagem visual

- Fundo preto quente (`SoundColors.Ink`), texto papel (`Text`/`TextMuted`/`TextFaint`), **um** acento
  tangerina (`Accent`). A cor extra vem das capas: cabeçalhos de detalhe e o player usam a cor
  dominante da capa (`rememberCoverColor`) como um véu suave no topo que some no `Ink`. Esse é o
  único degradê permitido. Nada de neon, glow, glassmorphism ou bento.
- Títulos grandes e pesados com tracking apertado (`displaySmall`/`headlineLarge`); sobrelinhas em
  `labelSmall` maiúsculo. Fonte do sistema.
- Capas com canto 6 dp (como uma capa de disco); artistas em círculo.
- Movimento: `pressScale` em botões próprios, springs (`Motion`), crossfade de imagem,
  `Modifier.animateItem()` em listas, `AnimatedContent` em trocas de estado. Haptics em curtir e
  long-press (`LocalHapticFeedback`).
- Margem lateral `Dimens.Gutter` (20 dp), 28 dp entre seções.
- **Toda tela rolável**: `contentPadding` inferior = `LocalBottomInset.current` (passa do mini player
  e da barra) e respeita a status bar no topo (`WindowInsets.statusBars`).
- Pouco texto, nada verboso. Estados vazios curtos e úteis (`EmptyState`).
- Long-press ou ⋮ em qualquer música abre `TrackActionsSheet` (ui/sheets).

## Fluxo de dados

- Busca → `YouTubeSource` → resultados `RemoteTrack`/`RemoteCollection`.
- Baixar → `DownloadController.download(...)`/`downloadCollection(...)` → tarefas na tabela
  `downloads` → worker baixa o áudio em faixas de 8 MB (sem isso o YouTube estrangula a velocidade),
  enriquece metadados, salva capa e letra, cria a `Track`, e põe na playlist alvo na posição certa.
- Tocar → `PlayerConnection.play(tracks, startIndex, source)` → `PlaybackService` (ExoPlayer +
  MediaLibrarySession). mediaId = `Track.id`; o serviço resolve o arquivo local. Fone com fio ou
  Bluetooth: a saída de áudio é do sistema; o serviço trata foco de áudio, "becoming noisy" (tirou
  o fone → pausa) e botões de mídia (AVRCP), e a sessão aparece na notificação, tela de bloqueio,
  relógio e carro.
- Inteligente → `SmartQueue` (aleatório inteligente, rádio, gênero, continuação) e `SmartMixes`
  (mixes da tela inicial). Puro Kotlin, determinístico por semente, testado.

## Segunda leva (pedido do Miguel em 2026-11-06)

- **Tocar online** (padrão ao tocar um resultado da busca; `Settings.playOnlineByDefault`):
  `PlayerConnection.playOnline(remoteTracks, …)`. Músicas online viajam como `Track` transitória
  (`data/OnlineTrack.kt`: id negativo, `isOnline`, mediaId `yt:<videoId>`). O serviço resolve o
  stream na hora (ResolvingDataSource + faixas de 2 MB — requisição aberta ao YouTube cai pra poucos
  KB/s), usa o arquivo local se a música já estiver baixada. Músicas online entram na fila salva
  (`QueueSnapshot.online`, voltam a tocar por streaming) mas não geram histórico nem estatísticas.
  Camada em `playback/stream/`: `ChunkedHttpDataSource` (Range de 2 MB, renova a URL em 403/410),
  `StreamUrlCache` (até 4 h ou o `expire` da URL), `OnlineStreams` (placeholder
  `sound://stream/<id>?q=<qualidade>`, pré-resolve a próxima). Fila online que acaba continua com
  `YouTubeSource.related` (o "a seguir" do YouTube Music).
  No player, música online mostra "Baixar" no lugar do coração; letra buscada na hora (LRCLIB).
- **Equalizador** (`AudioEffectsController` em `playback/FeatureContracts.kt`, `graph.effects`):
  Equalizer/BassBoost/Virtualizer na sessão de áudio do ExoPlayer, presets próprios (`EqPreset`),
  persistido. Tela em `ui/equalizer/` (rota `EqualizerRoute`, `navigator.openEqualizer()`).
  Presets são curvas próprias em dB numa grade de oitavas, amostradas nas bandas do aparelho por
  interpolação em log-frequência; a curva personalizada é salva por frequência (DataStore
  `sound_effects`); as bandas são sondadas numa sessão descartável (`generateAudioSessionId`).
  O serviço chama `graph.effects.attach(sessionId)` quando a sessão muda. O vídeo não é equalizado.
- **Ver o vídeo** (`VideoPlayback`, `graph.video`; `YouTubeSource.videoStream`): ExoPlayer próprio
  com vídeo + áudio, pausa a música e retoma na mesma posição. Tela imersiva `ui/video/`
  (`VideoRoute`, `navigator.openVideo(videoId, title)`); render com media3-ui-compose `PlayerSurface`.
- **Compartilhar** (`ui/components/Share.kt`): link (YouTube Music) ou o próprio arquivo de áudio
  (FileProvider `${applicationId}.files`), álbum/playlist remota.

## Acordes (1.10.0, 2026-11-08)

Acordes em cima da letra, ouvidos **no celular** a partir do áudio da própria música (o Miguel
escolheu isso; o Cifra Club recusa pedido de app e não vamos burlar a proteção do site).

- `chords/` (puro Kotlin, testado, menos o que toca no Android):
  - `AudioChords` — o áudio da música: o arquivo baixado, ou o stream lido pelo mesmo `AudioCache`
    do player (música recém-ouvida não gasta dados); `AudioDecoder` decodifica em mono 22 kHz.
  - `BeatTracker` — batidas da gravação (Ellis/librosa: força de ataque em bandas mel, tempo por
    autocorrelação com viés em 120 BPM, programação dinâmica). Os acordes mudam na batida da banda.
  - `BasicPitch` — modelo do Spotify (Apache 2.0, 200 KB, `assets/chords/nmp.tflite`, LiteRT,
    2 threads) ouve as notas. Trazido do LauncherAI como estava.
  - `ChordHearing` — notas → acordes maior/menor/7/m7 por batida (Viterbi, mínimo ~1,3 s por
    acorde, tom da música pesa). Do LauncherAI, agora sobre as batidas reais.
  - `ChordEngine` — um por vez numa thread de fundo, cancela ao trocar de música; `ChordStore`
    guarda em `filesDir/chords/<chave>.json` (chave = vídeo, então baixada e streamada dividem).
    Ajustes › Armazenamento tem "Acordes".
  - `ChordLayout` — põe o acorde sobre a palavra pelo tempo da linha (LRC só diz quando a linha
    começa); intro/solo/fim viram linha só de acordes; sem letra sincronizada, linhas de 4 compassos.
  - `Fingerings` — formas: canônicas (tabela do LauncherAI) primeiro, depois chords-db (MIT,
    `assets/chords/guitar.json`/`ukulele.json`, cada forma conferida nota a nota) e as achadas por
    busca (`FretSearch`), ordenadas por facilidade e espalhadas pelo braço. Cavaquinho (DGBD) é todo
    por busca. Piano: posição fundamental + inversões (`PianoVoicing`).
- `ui/chords/` — `ChordLyricsLines` (acorde sobre a palavra, o que soa agora preenchido em menta),
  `ChordSheet` (Violão · Ukulele · Cavaquinho · Piano, guardado em `Settings.chordInstrument`;
  posições deslizáveis; acordes da música embaixo), diagramas em Canvas. Botão no topo da letra
  cheia liga/desliga (`Settings.chords`, desligado por padrão).
- **Nada roda sem a pessoa pedir** (pedido do Miguel, 1.10.1: "nem todo mundo toca"): só a letra
  cheia aberta com o botão ligado ouve uma música (`rememberChordState(listen = true)`); fechar a
  letra, desligar o botão ou trocar de música para a análise na hora (`ChordEngine.stop`). O cartão
  de letra do player nunca ouve — só mostra acordes já ouvidos (`ChordEngine.recall`, lê o disco).
- O áudio chega na taxa do decodificador (44,1/48 kHz, 16 bits ou float) e o `MonoResampler` desce
  pra 22 kHz com filtro anti-aliasing (Butterworth de 8ª ordem); o modelo roda janela a janela
  (sem copiar a música inteira em janelas) — música longa cabe na memória.
- Validação com músicas reais: `tools/chords/README.md`. Pelo caminho do celular (decodificador +
  `MonoResampler`): Let It Be 97%, Stand By Me 100%, Perfect 99%, Riptide 90% do tempo nos acordes certos.
