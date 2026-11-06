# Surco — arquitetura e regras de trabalho

Surco é um player de música **offline** para Android (Kotlin + Jetpack Compose + Media3). A internet
só é usada para **buscar e baixar** (YouTube / YouTube Music via NewPipeExtractor, metadados no
iTunes Search API, letras no LRCLIB). Tudo o resto — biblioteca, playlists, álbuns, aleatório,
reprodução inteligente por gênero, letras sincronizadas — funciona sem rede.

Pacote/applicationId: `com.villamediana.surco` (debug: `.debug`). minSdk 26, target 36, compile 37.
Toolchain: AGP 9.5.1 (Kotlin embutido — **não** aplicar `org.jetbrains.kotlin.android`), Kotlin 2.4.21,
KSP 2.3.13, Gradle 9.8.2, Compose BOM 2026.09.00, Media3 1.11.1, Room 2.8.5, WorkManager 2.12,
Coil 3.6.3, OkHttp 5.5.1, NewPipeExtractor v0.26.5 (JitPack).

```bash
export JAVA_HOME=$HOME/.toolchain/jdk-21.0.13+9 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:compileDebugKotlin --console=plain -q          # compila
./gradlew :app:testDebugUnitTest --console=plain              # testes JVM/Robolectric
SURCO_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*Live*' # testes que batem na internet de verdade
```

Nunca subir emulador. Nada de `assembleRelease` fora do release.

## Camadas e contratos (arquivos compartilhados — NÃO editar dentro de um módulo)

| Arquivo | O que é |
|---|---|
| `data/db/Entities.kt`, `Daos.kt`, `SurcoDatabase.kt` | Room. `Track` é ao mesmo tempo entidade e modelo de domínio. |
| `data/LibraryRepository.kt` | Fonte única da biblioteca: flows de músicas, álbuns, artistas, gêneros, playlists, curtidas, histórico; mutações (curtir, editar, playlists, apagar). Agrupa em memória. |
| `data/model/LibraryModels.kt` | AlbumSummary/Detail, ArtistSummary/Detail, GenreSummary, PlaylistSummary/Detail, LocalSearchResult, PinnedItem… |
| `data/Storage.kt` | Pastas no armazenamento do app: audio/, covers/, lyrics/, tmp/. |
| `data/SettingsStore.kt` | DataStore com as preferências (`Settings`). |
| `data/Lrc.kt` | Parser de LRC (`Lrc.parse`, `ParsedLyrics.indexAt`). |
| `online/OnlineContracts.kt` | `YouTubeSource`, `MetadataEnricher`, `LyricsSource` + modelos remotos. |
| `download/DownloadContracts.kt` | `DownloadController`, `DownloadSummary`. |
| `playback/PlaybackContracts.kt` | `PlayerConnection`, `PlaybackState`, `PlaySource`, `SmartSeed`, `SleepTimer`, `RepeatMode`. |
| `smart/SmartContracts.kt` | `Mix`, `Taste`. Assinaturas de `SmartMixes`/`SmartQueue` também são contrato. |
| `AppGraph.kt` | DI manual. Acesso: `SurcoApplication.graph` (library, settings, storage, youTube, enricher, lyrics, downloads, player, http, db, scope). |
| `ui/theme/*` | Cores, tipografia, `Dimens`, `Motion`, `LocalBottomInset`. |
| `ui/components/*` | `Cover`, `MosaicCover`, `TrackRow`, `PlayingBars`, `SectionHeader`, `EmptyState`, `PlayPauseButton`, `PillButton`, `FilterPill`, `pressScale`, `SurcoMark`, `SurcoWordmark`, `CoverColors`/`rememberCoverColor`, `formatDuration`/`formatLongDuration`/`formatBytes`. |
| `ui/nav/*` | Rotas type-safe, `Navigator` (`LocalNavigator` / `navigator()`), `SurcoRoot` (NavHost + barra inferior + `PlayerLayer`), `LinkInbox`. |
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

- Fundo preto quente (`SurcoColors.Ink`), texto papel (`Text`/`TextMuted`/`TextFaint`), **um** acento
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
