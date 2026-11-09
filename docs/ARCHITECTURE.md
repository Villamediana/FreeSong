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
- **Toque na letra com crossfade** (1.12.4, `playback/SeekBlend.kt`): tocar numa linha da letra vai
  até ela com 300 ms de crossfade em vez de corte seco (a barra de progresso continua corte seco).
  É um `AudioProcessor` no próprio sink do ExoPlayer (`SeekBlend.renderers`): guarda os últimos
  1,5 s que entregou; o comando `SEEK_BLEND` arma com a posição ouvida e faz o seek; no flush desse
  seek, o trecho entregue e ainda não ouvido (o buffer do AudioTrack, que o seek joga fora) é
  misturado, saindo, no começo do trecho novo, entrando (potência igual). Sem segundo player, sem
  atraso no toque. Pausado = seek normal. A posição vem do `StreamMetadata.positionOffsetUs` que o
  `DefaultAudioSink` passa no 2º flush de cada seek (`SeekBlendSinkTest` confere no sink real; o
  Media3 1.11 já faz uma rampa de ~20 ms depois de cada flush). Pra tirar: o renderers factory e o
  comando no `PlaybackService`, `PlayerActions.seekLine` (a letra volta ao `seek`).

## Acordes (1.10.0, 2026-11-08; ouvido BTC desde 1.11.0; Cifra Club primeiro desde 1.12.0)

Acordes em cima da letra. **Primeiro a cifra do Cifra Club** (escrita por quem toca); só música sem
cifra lá é **ouvida no celular** a partir do áudio (1.12.0, pedido do Miguel: "o oído como fallback").

- **Cifra Club** (`chords/CifraClub.kt`): a busca (`solr.sscdn.co/cc/h2`) responde a qualquer um; a
  página dá 403 (Akamai) a biblioteca HTTP pela forma da conexão, então é lida pelo motor de
  navegador do próprio celular — `online/BrowserFetch` (WebView fora da tela, só arquivos do próprio
  site, sem imagens, lida assim que o `<pre>` é montado), como a Luna faz. Foi o Miguel que decidiu
  (antes, na 1.10.0, eu tinha decidido não fazer). Só vale cifra **desta** música: mesmo título e
  artista (`CifraMatch`); se o site só tem a versão de outro artista (ex.: "Si Usted la Viera" do
  Chacín → só a do Arjona), ela vale quando a letra dela bate com a nossa (`SheetLayout.fits`, 60%
  das linhas) e a tela diz "versão de …". `CifraClubPage` lê o `<pre>` (acordes em `<b>`, espaço de
  largura zero, partes de tab/dedilhado e acordes sobre tab fora), o tom ("com forma de" = tom
  escrito) e o capotraste. Nada da página vai pro repo (testes com música inventada; páginas reais
  em `SOUND_CIFRA_DIR` para `CifraClubLiveTest`).
- `SheetLayout` encaixa a cifra **na nossa letra** (LRCLIB): cada linha da letra casa com a linha
  (ou duas) da cifra num caminho de melhor custo (seguir é grátis, ficar/pular custa pouco, voltar —
  refrão repetido que a cifra escreve uma vez — custa mais); dentro da linha as palavras são alinhadas
  (sem acento, uma letra de diferença) e o acorde vai sobre a mesma letra em que foi escrito. Linhas
  só de acordes entram onde a cifra tem: intro do início, solo na linha vazia da letra (com o tempo
  dela), final. Letra que não bate (ou nenhuma letra): a cifra como está escrita, parada.
- `ChordChart.sheet` guarda a cifra (nomes como escritos; a forma tocada é `Chord.closest`, que tira
  extensões até achar um acorde conhecido); `looked` diz que o Cifra Club respondeu. Ouvida sem
  resposta (sem internet) → pergunta de novo uma vez por sessão com os acordes abertos, e a cifra
  achada substitui. `ChordState.Working(looking = true)` mostra "Procurando a cifra…".
- Faixa de acordes (1.12.1): **sem** "Cifra: Cifra Club" (o Miguel não quer o rótulo); fica o
  transpositor e, numa cifra, o capotraste e "Versão de …". **Transpor** (`ChordChart.transposed`,
  `Chord.transposeName`): −/+ meio tom, "Tom A +2", tocar no tom volta ao original; vale para ouvido e
  cifra (nomes escritos mantêm as extensões, grafia pelo tom novo), as linhas não mudam. Guardado por
  música em `filesDir/chord-shifts.json` (`ChordShifts`, ±11). **Tradução junto com acordes**: o botão
  não some; a tradução vai embaixo da linha (`rememberTranslationsByText`, achada pelo texto da linha).
- Visual dos acordes (1.12.2), pra caber no design da letra como a tradução cabe:
  - Acorde na **tinta da própria linha** (amarelo na cantada, esmaecido nas já cantadas; nas que vêm,
    um pouco mais firme que as palavras, porque quem toca lê adiante). Nada de menta fixo por cima.
    Continua sem marcar o acorde que soa: acende a linha, nunca um acorde sozinho.
  - Nome tipografado como songbook (`ChordName`): acorde cheio, extensão menor e elevada
    (Bm⁷⁽⁹⁾), baixo depois da barra na linha (D/F#; "7/9" é extensão). Mesmo tamanho nas linhas só
    de acordes. Acorde mais colado à sua linha (`CHORD_TUCK`), mais ar entre linhas (18 dp).
  - Tom e capotraste saíram de cima da letra e foram pra fileira do play (`ChordControls`): "TOM"
    sobre − A⁺² + à esquerda, "CAPOTRASTE" sobre "2ª casa" (ou "VERSÃO DE") à direita — rótulo em
    caixa alta pequena como o "TOCANDO DE" do player. Entre o título e a letra só fica o estado
    enquanto procura/ouve/falhou. Botão de acordes ligado = tinta cheia, como o de tradução.
- Folha do acorde (1.12.3), na mesma língua: nome tipografado grande (`ChordName.typeset(display =
  true)`, extensão menor que na letra), dito e notas com a tônica no acento da letra; instrumentos
  como palavras (`QuietChoice`: escolhido em tinta cheia sobre pílula translúcida, igual ao "Letra"
  do player; sem caixa em volta); diagrama em tinta com a tônica em `SoundColors.Lyric` (amarelo no
  escuro, destaque no claro) — sem menta; piano com as teclas do acorde no acento e o baixo de acorde
  com barra num tom mais fundo; "POSIÇÃO 1 DE 4" e "NESTA MÚSICA" em caixa alta pequena; setas
  coladas ao rótulo e ponto da posição atual alongado; acordes da música tipografados, sem caixas.
- 1.12.4: linha curta sob muitos acordes ("I say, oh" do Sparks com seis) empilhava os primeiros um
  em cima do outro. `ChordPlacement`: sempre **uma fileira, da esquerda pra direita**; se não cabe, os
  acordes daquela linha ficam menores só o necessário (escala pela linha de base). Duas fileiras foram
  tentadas e o Miguel recusou: "empilhado não sei qual vem primeiro". Todos os acordes numa mesma
  linha de base (`FirstBaseline`): o nome com extensão elevada fica mais alto, não mais baixo.
  Cavaquinho fora da folha por enquanto (`Instrument.offered`: Violão · Ukulele · Piano).

- `chords/` (puro Kotlin, testado, menos o que toca no Android):
  - `AudioChords` — o áudio da música: o arquivo baixado, ou o stream lido pelo mesmo `AudioCache`
    do player (música recém-ouvida não gasta dados); `AudioDecoder` decodifica em mono 22 kHz
    (`MonoResampler`, com anti-aliasing).
  - **Ouvido (1.11.0): BTC** — `Cqt` calcula o espectro constant-Q igual ao `librosa.cqt` que o
    modelo viu no treino (144 bins de C1, 24/oitava, hop 2048, pedaços de 10 s); `Btc` é o
    Bi-directional Transformer for Chord recognition (Park et al., ISMIR 2019, MIT), reescrito em
    Kotlin puro com os pesos em float16 (`assets/chords/btc_voca.bin`, 5,9 MB), 170 acordes
    (12 tônicas × min, maj, dim, aug, min6, maj6, min7, minmaj7, maj7, 7, dim7, hdim7, sus2, sus4);
    janelas de 108 quadros em até 4 threads de prioridade baixa. `ChordReading` lê os acordes com
    Viterbi (preço 8 por troca) e o tom pelas notas mais longas. `BeatTracker` só dá o andamento.
    Substituiu Basic Pitch + regras (`ChordHearing`) e o LiteRT.
  - Régua: `tools/chords/bench` — 53 músicas do McGill Billboard com acordes marcados por músicos:
    ouvido antigo 64,0% (47,1% com a sétima), BTC 77,4% (61,3%). Corrigir afinação não ajudou.
  - `ChordEngine` — um por vez numa thread de fundo, cancela ao trocar de música; `ChordStore`
    guarda em `filesDir/chords/<chave>.json` (chave = vídeo, então baixada e streamada dividem).
    Ajustes › Armazenamento tem "Acordes".
  - `ChordLayout` — põe o acorde sobre a palavra pelo tempo da linha (LRC só diz quando a linha
    começa); intro/solo/fim viram linha só de acordes. Letra sem tempo (só texto, comum no LRCLIB):
    o Miguel escolheu acordes sobre as palavras com tempo estimado (`ChordLayout.estimated`: linhas
    espalhadas entre uma intro e um final típicos pelo tamanho de cada uma, folga primeiro nas pausas
    entre estrofes, cada linha puxada para uma troca de acorde próxima) — pode errar por uma linha.
    Sem letra nenhuma, linhas de 4 compassos.
  - `Fingerings` — formas: canônicas (tabela do LauncherAI) primeiro, depois chords-db (MIT,
    `assets/chords/guitar.json`/`ukulele.json`, cada forma conferida nota a nota) e as achadas por
    busca (`FretSearch`), ordenadas por facilidade e espalhadas pelo braço. Cavaquinho (DGBD) é todo
    por busca. Piano: posição fundamental + inversões (`PianoVoicing`).
- `ui/chords/` — `ChordLyricsLines` (acorde escrito sobre a palavra, SEM marcar o que soa agora —
  pedido do Miguel, 1.11.1; letra sincronizada acompanha como a letra normal; letra sem tempo fica
  parada, sem linha amarela nem rolagem nem toque para pular),
  `ChordSheet` (Violão · Ukulele · Cavaquinho · Piano, guardado em `Settings.chordInstrument`;
  posições deslizáveis; acordes da música embaixo), diagramas em Canvas. Botão no topo da letra
  cheia liga/desliga (`Settings.chords`, desligado por padrão).
- **Nada roda sem a pessoa pedir** (pedido do Miguel, 1.10.1: "nem todo mundo toca"): só a letra
  cheia aberta com o botão ligado ouve uma música (`rememberChordState(listen = true)`); fechar a
  letra, desligar o botão ou trocar de música para a análise na hora (`ChordEngine.stop`). O cartão
  de letra do player nunca ouve — só mostra acordes já ouvidos (`ChordEngine.recall`, lê o disco).
- O áudio chega na taxa do decodificador (44,1/48 kHz, 16 bits ou float) e o `MonoResampler` desce
  pra 22 kHz com filtro anti-aliasing (Butterworth de 8ª ordem).
- Validação: `tools/chords/README.md` (régua Billboard e músicas avulsas, inclusive comparação com cifra).

## Atualização sem loja (1.13.0, 2026-11-09)

O app se atualiza pelas releases do GitHub (`Villamediana/FreeSong`), sem Play Store.

- **Publicar**: subir `versionCode`/`versionName`, commit, PR, merge na main e
  `tools/release.sh -n $'- novidade\n- outra'` (ou `-F notas.md`). O script confere git (HEAD na
  `origin/main`), versão maior que a última, compila a release, confere pacote/versão/**certificado**
  (SHA-256 fixado no script: APK de outra chave o Android recusa por cima) e cria a release com a APK.
  Termina lendo `/releases/latest` como os celulares leem e só diz `published` se tag, tamanho e
  SHA-256 batem. As notas são o texto da tela "Nova versão" (Markdown leve).
- **Pré-release e rascunho não chegam aos celulares** (`/releases/latest` ignora): dá pra subir um
  teste como pre-release e promover depois.
- `update/Releases.kt` (puro): `ReleaseFeed.parse` (tag `vX.Y.Z`, primeira `.apk`, `digest`
  sha256), `Versions` (número a número), `ReleaseNotes` (linhas pra folha).
- `update/Updates.kt` (`graph.updates`): estados `Idle/Checking/UpToDate/CheckFailed/Available/
  NeedsPermission/Downloading/Installing/Failed`. Checagem quieta no máximo a cada 6 h (abrir o app
  + `UpdateCheckWorker` diário com rede) com ETag (`304` não gasta o limite de 60/h da API).
  A folha abre sozinha **uma vez por versão**; "Agora não" grava `dismissed`. Ajustes › Sobre tem a
  linha "Procurar atualização" / "Atualizar para X". Memória em `filesDir/update.json`, APK em
  `cacheDir/update/` (apagada quando a versão nova sobe ou some do GitHub).
- Atualizar = permissão "instalar apps desconhecidos" (uma vez, `ACTION_MANAGE_UNKNOWN_APP_SOURCES`;
  volta pro app e segue sozinho no `onResume`) → download com progresso no próprio botão
  (`ApkDownloader`: tamanho + SHA-256 do GitHub, `.part` e rename) → `getPackageArchiveInfo`
  confere pacote e versionCode maior → `SessionInstaller` (PackageInstaller, uma sessão; Android 12+
  com `USER_ACTION_NOT_REQUIRED`: depois da 1ª atualização feita pelo app, as próximas não pedem
  confirmação). A tela "Atualizar este app?" do Android (`STATUS_PENDING_USER_ACTION`) só abre com o
  app em primeiro plano (`UpdateHost`, `repeatOnLifecycle(RESUMED)`).
- `UpdatedReceiver` (`MY_PACKAGE_REPLACED`): a versão nova avisa "FreeSong atualizado" só quando a
  atualização veio do app (`installing` na memória = versão atual).
- Debug (`.debug`) não procura atualização (`Updates.enabled = !BuildConfig.DEBUG`): a release não
  atualiza outro pacote.
- Testes: `update/ReleaseFeedTest`, `UpdatesTest` (GitHub falso: ETag, falhas, integridade, pacote
  errado, permissão, cancelar, memória), `SessionInstallerTest` (Robolectric), `UpdateLiveTest`
  (GitHub real: lê a última release, 304 com ETag, baixa a APK e confere o SHA-256).

## Amigos e jam (1.14.0, 2026-11-09)

Servidor em `relay/` (Node 22, `node:sqlite`, `ws`; instalação em `relay/DEPLOY.md`), em
`https://freesong.simplyonegoal.com` (`BuildConfig.RELAY_URL`). Regras do Miguel: sem admin; lista
global com foto/@/nome de todos, horas e top 5 só pra amigos; o servidor guarda só perfil e amizades
— música trocada vive no celular (o relay entrega e apaga no ack ou em 14 dias); jam só na memória.

- `social/SocialAccount` — perfil criado sozinho no primeiro uso (@ e nome sorteados), token e
  código de recuperação no DataStore "social" (vai no backup do Android). Recuperar pelo código
  apaga do relay o perfil automático vazio deste celular. Apagar perfil = `OPTED_OUT` (não recria).
- `social/RelayApi` (HTTP) e `social/RelayLink` (WebSocket, ligado com o app na tela ou numa jam;
  reconecta; mede o relógio do relay por ping). `social/SocialHub` (`graph.social`) junta tudo,
  notifica quando o app não está na tela e tem o `SocialWorker` (a cada 1 h com o app fechado).
- `SharedSongs` — tabela `shared_songs` (banco v6, AutoMigration 5→6), única por (direção, entrega).
- Jam: `social/JamSync` (puro: seguir e comandos → estado) e `social/JamSession`. Cada celular toca
  sozinho; o relay guarda fila, índice, posição no relógio dele e play. Os comandos do app passam
  por `playback/PlaybackGate` (`MediaPlayerConnection.gate`) e viram estado da jam pra todos; a jam
  move o player pelo `FollowingPlayer` (PLAY com `ARG_START_POSITION`/`ARG_AUTOPLAY`, fonte `JAM`,
  sem aleatório e sem a continuação local da fila). Pausa que não vem do app (ligação, fone, timer)
  fica só neste celular (`held`) até tocar play. Fim de música e "próxima" do fone propagam (erro de
  stream não). Quem criou a jam estende a fila com o rádio da última música. Dois toques ao mesmo
  tempo: o relay aceita o primeiro (`baseRev`) e manda a versão atual pro outro.
- UI em `ui/social/` (Amigos, Perfil, página do amigo com as músicas trocadas, folhas de jam e de
  mandar música); entrada pela foto no topo da tela principal; "Mandar pra um amigo" no ⋮ das
  músicas; rostos da jam no topo do player.
- Testes: `social/JamSyncTest`, `JamSessionTest` (relay e player falsos), `SharedSongsTest`
  (inclui migração 5→6), `RelayClientTest` (o relay Node de verdade numa porta livre; precisa de
  `relay/node_modules`), `ui/social/SocialUiTest` (prints com `SOUND_SHOTS=1`); servidor:
  `cd relay && npm test`.

## Sugestões que aprendem (1.15.0, 2026-11-09)

Pedido do Miguel: o Início "muda bastante conforme a pessoa escuta" e devia aprender o gênero e o
que a pessoa gosta e está gostando.

- **`smart/TasteProfile`** (puro): o gosto montado de tudo — escutas da biblioteca (`play_events`)
  e de streaming (`online_plays`), pulos, corações (`tracks.liked` + `liked_online`), músicas
  baixadas (sinal fraco) e os gêneros escolhidos. Duas memórias com decaimento: longa (meia-vida
  45 dias, o gosto) e curta (5 dias, o momento), misturadas 70/30 — uma noite de outra coisa
  empurra, não vira. Uma música repetida conta pro artista só até um teto. Pulada 2× sem nunca ir
  até o fim = não volta (`avoidSongs`); artista pulado quase sempre = não volta (`avoidArtists`);
  coração sempre ganha.
- **Gênero**: a tag da biblioteca; pro que é só streaming, o gênero do artista no catálogo do
  iTunes (`discover/ArtistGenres`, poucos por vez, guardado 60 dias em `artist-genres.json`).
  `smart/GenreMatch` traduz qualquer nome ("Sertanejo Universitário", "Hip-Hop/Rap") pros gêneros do
  Início.
- **`smart/Recommend`** (puro): o que as rádios do YouTube concordam + o gosto (artista curtido sobe,
  artista novo ganha um empurrão pequeno pra ter descoberta), nada pulado, nada já conhecido, no
  máx. 2–3 por artista, nunca o mesmo artista seguido.
- **`discover/ForYou`** (no grafo, `graph.forYou`) monta o Início:
  - "Descobertas pra você": das rádios das músicas mais amadas, revezando entre os gêneros; uma vez
    por dia (`discover.daily`), as puladas saem da linha.
  - "Porque você curte <artista>": o favorito do momento + outro em rodízio; os artistas e as
    músicas ficam fixos na janela de 6 h (`anchors`) — não muda a cada música tocada (era o
    "Porque você ouviu", que seguia a música atual).
  - Estantes de gênero: os gêneros escutados (e os escolhidos), na ordem do gosto; metade vem da
    rádio das favoritas da pessoa naquele gênero, metade do pool do gênero na ordem dela.
- Os "Feitos pra você" e a continuação automática da fila também deixam de fora o que é pulado.
- Testes: `smart/TasteProfileTest`, `RecommendTest` (inclui `GenreMatch`), `discover/ForYouTest`
  (banco de verdade + YouTube falso; inclui a estabilidade na janela), `ArtistGenresTest`.

## Rádios variadas e atualização sem fechar na cara (1.16.0, 2026-11-09)

Pedidos do Miguel: a rádio de uma música vinha sempre igual; a fila devia seguir o que está se
ouvindo; atualizar fechava o app duas vezes.

- **`smart/RadioMix` + `discover/SongRadio`** (`graph.songRadio`): toda rádio e a continuação da
  fila. A rádio do YouTube Music de cada semente (fixa por natureza — era a causa da repetição) +
  a de 1–2 músicas sorteadas dela (`widenFrom`), ranqueadas por concordância, proximidade da
  semente, gosto (`TasteProfile`) e um pouco de acaso (`CHANCE`): a mesma música nunca abre a mesma
  rádio duas vezes, mas o começo segue perto dela. `Session` (últimas 3 h): música ouvida agora não
  repete, pulada agora não volta, artista pulado 2× fica de fora por enquanto.
- Continuação (`PlaybackService.maybeExtend`): sementes = últimas da fila **não puladas agora**
  (`QueueContinuation.seeds(skipped)`); a busca, músicas de amigos e o reconhecimento começam a rádio
  por ela. "Iniciar rádio" (folha da música) e "Recomendadas" do gênero (`ForYou.like`) também.
- **Atualização automática**: o Android fecha o app pra trocar a versão — inevitável com ele em uso.
  Então, onde o Android deixa atualizar sem perguntar (12+, FreeSong instalado pelo próprio
  FreeSong — `SessionInstaller.canInstallQuietly`): achou versão → `UpdateAutoWorker` baixa no
  Wi-Fi em silêncio → Android 14+: `commitSessionAfterInstallConstraintsAreMet` (app fora da tela e
  sem música — o próprio sistema espera); 12–13: `UpdateIdleWorker` instala 2 min depois de o app
  sair da tela se não houver música. Depois: só o aviso "atualizado" (não abre o app por cima de
  nada). Falha/tempo esgotado do automático: quieto, tenta de novo depois.
- O botão "Atualizar" (1.16.1, pedido do Miguel: "aquele do Android que clica em atualizar e no fim
  mostra abrir"): baixa e abre o **instalador do próprio Android** (`SessionInstaller.installScreen`,
  `ACTION_INSTALL_PACKAGE` com o APK pelo FileProvider `${applicationId}.files`, `cache/update/`):
  "Atualizar" → progresso → "Abrir". O app fecha por baixo dessa tela. `EXTRA_INSTALLER_PACKAGE_NAME`
  = FreeSong, pra ele seguir como quem atualiza (as automáticas dependem disso). Sem resposta de
  volta: voltar ao app ainda na versão velha = desistiu (`onResume` → Available, nada recomeça
  sozinho). Sem instalador que responda (`screenUnavailable`), cai na sessão do PackageInstaller
  **com** `USER_ACTION_REQUIRED` — o botão nunca instala em silêncio.
- 1.16.3: o app fechava logo depois de baixar. Corrida: a automática (disparada pela busca) deixava
  uma instalação esperando com o Android 14+, que entrava assim que a tela do instalador cobria o app
  ("fora de uso"). Agora o botão chama `standDownAuto()` (abandona sessões, cancela o worker) e a
  automática só roda com o app fora da tela (`UpdateAutoWorker` → `Result.retry()` se estiver).
- Testes: `smart/RadioMixTest` (variedade: tirar o acaso derruba), `QueueContinuationTest` (seeds),
  `UpdatesTest` (automático 14+, 12–13, onde o Android perguntaria, falha quieta, botão com
  automático esperando).

## Perfil e amigos com cara própria; @ fixo (1.17.0, 2026-11-09)

Pedido do Miguel: o @nome é um id único criado sozinho, não se edita (só o nome de exibição); e as
telas de perfil/amigos estavam feias. Primeiro ficaram iguais à página de artista — ele não quis
("era para ser diferente"). Ficou assim (`ui/social/PersonPage.kt`):
- **Cartão da pessoa** (`PersonCard`): a cor dela (`seedColor(username)`, `personFill`) com ondas
  sonoras saindo do canto (`waves`), foto com anel branco, nome, @, horas em destaque e "desde".
  Em Amigos, o seu é o `MiniPersonCard`.
- Botões grandes embaixo (`PersonButton`): amigo "Mandar música" / "Jam"; você "Editar nome" /
  "Trocar foto"; desconhecido "Adicionar". ⋮ no topo.
- "Mais ouvidas": capas grandes com a posição por cima (`RankedCover`), "Tocar" no título.
  Amigo: "Músicas trocadas" em balões. Você: código de recuperação como ticket (`RecoveryCard`).
- `ProfilePage`/`FriendPage` recebem só dados (as telas guardam o estado): `SocialUiTest` desenha e
  gera prints com `SOUND_SHOTS=1`.
- **@ fixo**: o app não oferece mais trocar; o relay 1.2.0 responde `400 username_fixed` a outro
  `username` no `PATCH /v1/me` (o mesmo de volta passa).

## Trocar de celular com o código: o cofre (1.18.0 + relay 1.3.0, 2026-11-09)

Pedido do Miguel: com o código, além do perfil e das amizades, voltar as curtidas, o aprendizado do
gosto etc. — "não sentir diferença"; e o mesmo perfil em vários celulares. Ele tinha pedido antes que
o servidor guardasse só o básico: por isso o cofre é **cifrado no celular**.
- `social/Vault.kt` (puro): `VaultCrypto` — chave do cofre aleatória (AES-256-GCM, gzip), embrulhada
  pela chave do código (PBKDF2-SHA256 60k, sal "freesong-vault:<id>"); `VaultData` (curtidas,
  escutas de 400 dias, playlists com chave = createdAt, importadas do Spotify, músicas trocadas,
  gêneros); `VaultMerge` — 3 vias com a base da última sync: adicionado de um lado fica, removido de
  um lado some, playlist mudada dos dois lados fica a mais nova, escutas e músicas trocadas só somam;
  sem base (celular recém-recuperado) junta tudo. Saída em ordem fixa (nada novo = nada gravado).
- `social/VaultSync.kt`: lê o cofre → abre (chave guardada, senão o código) → junta → aplica no
  celular (`PhoneVault`) → grava com `baseRev` (409 = outro celular gravou: lê de novo). A chave fica
  no DataStore "social" (`userId:base64`), a base em `files/vault-base-<id>.json`. Código novo:
  `SocialHub.newRecoveryCode()` → `vault.rekey()` (os outros celulares seguem com a chave que têm).
- Quando: ao abrir o app (`bootstrap`, depois da caixa de entrada), 15 s depois de mexer em curtidas
  ou playlists, no `SocialWorker` de hora em hora; recuperar = `bootstrap`.
- Relay: tabela `vaults` (user_id, rev, key, data BLOB, updated_at), `GET/PUT /v1/me/vault`, some com
  o perfil. O teste "o banco guarda só o básico" confere que o cofre é só bytes opacos.
- Testes: `VaultTest` (cifra, junção), `VaultSyncTest` (relay de verdade + dois/três celulares:
  recuperar traz tudo, descurtir num some no outro, escrita simultânea, código novo).

## Ajustes sem engasgo e histórico com teto (1.18.0, 2026-11-09)

- Ajustes abria "travado": as configurações chegavam depois do primeiro quadro e a tela se montava no
  meio da animação. Agora `graph.settingsNow` e `graph.libraryStats` (StateFlow quentes, lidos ao
  abrir o app) entregam tudo no primeiro quadro; medir pastas/caches espera ~450 ms (a abertura).
- `LibraryRepository.pruneHistory` ao abrir o app: escutas de 400 dias, no máximo 50 mil de cada
  tipo; as horas podadas somam em `Settings.listenedBeforeMs` (horas do perfil não caem). Nada lê
  mais longe que isso (a Retrospectiva de janeiro precisa do ano anterior inteiro).

## Retrospectiva como história (1.19.0, 2026-11-09)

Pedido do Miguel: "não pode ser só uma página… cheia de animações, efeitos e narrativa".
- `stats/Recap.kt` ganhou o que a narrativa conta: escuta por mês, dias com música, maior
  sequência, a primeira música do ano (a primeira não pulada) e quando tocou.
- `stats/RecapStoryPlan.kt` (puro): capítulos na ordem — abertura, a primeira música, o tempo, mês a
  mês, o horário (você é da noite…), o gênero, o artista (suspense → revelação), os artistas, a
  música do ano (rufar → revelação), as 5 em contagem regressiva, o pôster final. Capítulo sem o
  que contar fica de fora. `soundtrack()`: a #2 toca até o capítulo da #1, que entra nele.
- `ui/recap/RecapStory.kt`: tela cheia (rota "full page"), barra de progresso por capítulo, toque à
  direita/esquerda, segurar pausa, som liga/desliga (sem som numa jam). Cada cena é função de `t`
  (segundos no capítulo): pausar para o tempo, e um teste mostra qualquer instante. O relógio só
  anda com a tela RESUMED, sem dedo segurando e até o pôster assentar (bateria).
- `ui/recap/RecapScenes.kt` + `StoryKit.kt`: as cenas e as peças (fundo com luz que deriva + grão +
  vinheta, anéis, flash da revelação, palavras chegando uma a uma, vinil girando, medidor, relógio
  de 24 h com o céu do horário). Cores: a do app girada no círculo cromático, a da capa onde ela
  estrela. Tipo e objetos medidos em % da largura (`LocalStoryUnit`).
- Testes: `RecapStoryPlanTest`; `RecapStoryTest` (cada capítulo com seu texto, toques, "Ver de
  novo"); `SOUND_FILM=1` renderiza a história quadro a quadro em `app/build/recap-film` (ffmpeg junta
  em vídeo). No Robolectric as capas ficam no desenho padrão: decodificar arquivo fora da thread
  principal aborta os gráficos nativos.

## Início enxuto: uma faixa por ideia (1.20.0, 2026-11-09)

Pedido do Miguel: o Início "não é pra bombardear", tem que ser útil pra desfrutar.
- Saiu "Tocadas recentemente" (só da tela; o histórico e o Android Auto seguem iguais).
- "Porque você curte <artista>" deixou de ser uma faixa por artista: é **uma** faixa de cards, um por
  artista (os 2 favoritos do momento + 2 em rodízio a cada 6 h, `ForYou.because()`), cada card um mix
  de 30 músicas no estilo dele.
- "X pra você" (uma faixa de músicas por gênero) virou **uma** faixa "Seus gêneros" com um card por
  gênero ("Latino hoje"), o mix de 30 do `ForYou.shelf()`.
- Os mixes ficam em `discover/HomeMixes.kt` (`graph.homeMixes`, ids `home:genre:` / `home:because:`):
  o card abre a mesma lista na página de mix (`MixScreen`, ramo `HomeMixes.PREFIX`) e o botão toca
  (`DiscoverViewModel.playMix`). "Descobertas pra você" segue em músicas soltas.
- Ordem: Suas músicas → Feitos pra você → Descobertas → Porque você curte → Seus gêneros →
  Escolher gêneros.
- 1.20.2: "Playlists pra você" saiu (era busca de playlists do YouTube por gênero, sem aprender
  nada; Miguel escolheu tirar) — e a busca delas em segundo plano também. Os cards de "Porque você
  curte" e "Seus gêneros" pegam a cor da própria capa (`DiscoverViewModel.tinted`, a mesma da página
  do mix), como os de "Feitos pra você".

## Páginas cheias sem engasgo (1.20.0)

Ajustes (e equalizador, vídeo…) travavam ao entrar e sair: o espaço do mini player no rodapé
encolhia animado (tudo embaixo e a própria página se remontavam a cada quadro do deslize) e o player
era recriado no primeiro quadro da volta. Agora, em `SoundRoot`, o espaço fica congelado enquanto
uma página cheia está por cima e o player volta só depois do deslize (`PAGE_MS`).

## Play/pause redondinho, busca mais rápida, "Seus números" no perfil (1.21.0 + relay 1.4.0, 2026-11-09)

- `ui/components/PlayGlyph.kt`: o play/pause do app inteiro (mini player, player, cards, páginas) é um
  desenho só, com cantos arredondados (CornerPathEffect, 13% da largura: triângulo macio e palitos
  em pílula, na mesma maciez das setas `Icons.Rounded.Skip*`) e o morph triângulo ↔ barras. Em
  repouso o play é um triângulo único (as duas metades mostrariam um entalhe).
- Busca: círculo 650 → 560 ms (Miguel: "só mais um pouquinho") e a entrada do conteúdo ~14% antes.
- Perfil: `stats/ListeningStats.kt` + `ui/social/ProfileStats.kt` — "Seus números", Mês/Ano:
  minutos, músicas, artistas, ritmo da semana + "Você é da noite · pico às 22h" e os seus artistas
  (abrem o perfil do artista). O carrossel "Mais ouvidas" continua como era. Miguel tirou sequência e
  gêneros ("não precisa"). No seu perfil é calculado na hora; os **amigos** veem os mesmos números
  ("Números de Ana") porque o `SocialHub.syncStats` manda os totais do mês e do ano (`NumbersOf`)
  junto com horas e top 5 — relay 1.4.0 (`numbers_json`, `relay/src/numbers.js` confere tudo). Só
  totais, nenhum histórico.

## DNA que aprende o momento inteiro (1.26.0, 2026-11-09)

O DNA (`smart/MusicDna.kt`) continua **silencioso**: nenhuma tela nova, nenhum botão, nunca a
palavra na tela — só muda o que toca e o que é sugerido. O que ele aprende agora:

- **Hora e dia de verdade.** A hora é contínua (uma música das 21h conta um pouco às 20h e às 22h,
  quase nada às 15h) e cada hora da semana tem a sua memória (168 vagas): segunda de manhã não é
  sábado de manhã. A madrugada pertence ao dia anterior (`DnaMoment.DAY_STARTS`).
- **Lugar e companhia em tudo.** Artistas, gêneros, músicas e pulos guardam o lugar (os que o app
  aprende sozinho, com "Lembrar lugares" ligado) e quem estava junto na jam.
- **O som da música: clima e ritmo.** `playback/SoundTap.kt` (um `TeeAudioProcessor` na saída do
  player) entrega o áudio que já está tocando ao `smart/SoundPrint.kt` (`SoundAnalyzer`): poucas
  somas por amostra, sem transformada — volume, brilho, quanto acontece, firmeza da batida e
  andamento. Fica em `song_sounds` (banco v10), **só no aparelho** (não vai pro cofre nem pro
  servidor) e a música medida o bastante (`SETTLED_SECONDS`) não é ouvida de novo. `SoundMap` põe
  cada música no lugar dela entre tudo o que a pessoa ouve (calma↔intensa, parada↔movida) e o DNA
  aprende que som é de cada momento — vale também pra música nunca ouvida naquele momento.
- **Amigos.** `smart/FriendsTaste.kt`: o que cada amigo já mostra aos amigos (artistas do mês e do
  ano, top de músicas) empurra as sugestões e os rádios; mais quando vários concordam, inteiro pra
  quem está junto na jam. Nada novo é pedido ao servidor nem sai do aparelho. O gosto de quem ouve
  manda: o dos amigos não entra no perfil e o que é pulado sempre continua fora.
- **Biblioteca no momento.** `ForYou.libraryFit()` → `Taste.fit` → `Scoring.tasteWeight`: o
  aleatório inteligente e o "a seguir" das músicas do aparelho pesam o quanto cada uma combina com
  agora (`MusicDna.fit`: artista × som × pulos neste momento).

Regras que não mudam: o pulo vale só no momento em que aconteceu, é perdoado por ouvir até o fim e
some com o tempo; coração segura a música; ajuste fino só nas constantes do `MusicDna`.

Ficou de fora, de propósito: sugerir pelo que toca **perto de outros usuários**. Pra isso o
servidor teria que saber onde cada celular está — e hoje ele não sabe nem guarda nada disso.
