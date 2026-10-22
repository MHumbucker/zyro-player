package com.zyro.player.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyro.player.data.downloader.OfflineCollectionKeys
import com.zyro.player.data.downloader.OfflineDownloadState
import com.zyro.player.data.local.LikedAlbumEntity
import com.zyro.player.data.local.LikedArtistEntity
import com.zyro.player.data.local.LikedPlaylistEntity
import com.zyro.player.data.local.ListeningHistoryEntity
import com.zyro.player.data.local.OfflineCollectionEntity
import com.zyro.player.data.local.OfflineCollectionTrackCrossRef
import com.zyro.player.data.local.PlaylistEntity
import com.zyro.player.data.local.TrackEntity
import com.zyro.player.data.remote.AlbumInfo
import com.zyro.player.data.remote.PlaylistInfo
import com.zyro.player.data.remote.ScLibraryData
import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.data.remote.UserInfo
import com.zyro.player.domain.PlaylistDraft
import com.zyro.player.ui.components.ZyroTextPrimary
import com.zyro.player.ui.components.ZyroTopScrim
import com.zyro.player.ui.components.zyroDirectionalTransition
import com.zyro.player.ui.components.zyroSectionTransition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    /** Секция, с которой открываем экран (например, «LIKED_TRACKS» с главной). */
    initialSection: String = "ALL",
    likedTracks: List<TrackEntity>,
    likedArtists: List<LikedArtistEntity>,
    likedAlbums: List<LikedAlbumEntity>,
    likedPlaylists: List<LikedPlaylistEntity>,
    offlineTracks: List<TrackEntity>,
    offlineCollections: List<OfflineCollectionEntity> = emptyList(),
    offlineCollectionTracks: List<OfflineCollectionTrackCrossRef> = emptyList(),
    offlineDownloadState: OfflineDownloadState = OfflineDownloadState(),
    playlists: List<PlaylistEntity>,
    /** Обложки локальных плейлистов (первый трек), ключ — playlistId. */
    localPlaylistCovers: Map<Long, String> = emptyMap(),
    history: List<ListeningHistoryEntity>,
    currentTrackUrl: String?,
    // SoundCloud — теперь без дефолтов, обязательно прокидывать!
    scIsConnected: Boolean,
    scIsSyncing: Boolean,
    scUsername: String?,
    scCurrentUser: UserInfo?,
    scLibraryData: ScLibraryData?,
    scSyncMessage: String?,
    onScSyncClick: () -> Unit,
    onScConnectClick: () -> Unit,
    // ---
    onCreatePlaylist: (PlaylistDraft) -> Unit,
    onDeletePlaylist: (PlaylistEntity) -> Unit,
    onSelectPlaylist: (PlaylistEntity) -> Unit,
    onTrackClick: (TrackInfo, List<TrackInfo>, String) -> Unit,
    onLikeTrack: (TrackInfo) -> Unit,
    onDownloadTrack: (TrackInfo) -> Unit,
    onPlayNext: ((TrackInfo) -> Unit)? = null,
    onAddToQueue: ((TrackInfo) -> Unit)? = null,
    onTrackArtistClick: ((TrackInfo) -> Unit)? = null,
    onDeleteOffline: (String) -> Unit,
    onArtistClick: (UserInfo) -> Unit = {},
    onAlbumClick: (AlbumInfo) -> Unit = {},
    onPlaylistClick: (PlaylistInfo) -> Unit = {},
    onDownloadAlbum: ((AlbumInfo) -> Unit)? = null,
    onDownloadPlaylist: ((PlaylistInfo) -> Unit)? = null,
    onDownloadLocalPlaylist: ((PlaylistEntity) -> Unit)? = null,
    onDownloadAllLiked: (() -> Unit)? = null,
    // Действия внутри вкладки SoundCloud (реальные мутации на сервере)
    onScLikeTrack: (TrackInfo) -> Unit = {},
    onScLikePlaylist: (PlaylistInfo) -> Unit = {},
    onScFollowArtist: (UserInfo) -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var activeSection by remember { mutableStateOf(initialSection) }
    var previousSection by remember { mutableStateOf("ALL") }
    var showCreateDialog by remember { mutableStateOf(false) }

    // Системный жест «назад» внутри медиатеки возвращает к обзорному экрану,
    // а не закрывает приложение. Тот же переход, что и у стрелки в тулбаре.
    BackHandler(enabled = activeSection != "ALL") {
        previousSection = activeSection
        activeSection = "ALL"
    }

    // Собственные плейлисты SoundCloud показываем из снапшота, а не из Room:
    // при синхронизации они больше не создают локальные копии.
    val serverPlaylists = scLibraryData?.playlists ?: emptyList()
    // Сохранённые (лайкнутые) плейлисты приходят из API, а в Room лежат только
    // лайки, поставленные внутри приложения. Вкладка должна показывать оба набора.
    val scLikedPlaylists = scLibraryData?.likedPlaylists ?: emptyList()

    val offlineCollectionKeys = remember(offlineCollections) {
        offlineCollections.map { it.collectionKey }.toSet()
    }
    val allLikedOffline = likedTracks.isNotEmpty() && likedTracks.all { it.isOffline }

    val sectionTitle = when (activeSection) {
        "LIKED_TRACKS" -> "Любимые треки"
        "LIKED_ARTISTS" -> "Артисты"
        "LIKED_ALBUMS" -> "Альбомы"
        "PLAYLISTS" -> "Плейлисты"
        "OFFLINE" -> "Офлайн"
        "HISTORY" -> "История"
        else -> "Моя Библиотека"
    }

    // Стартовая оценка высоты шапки (статус-бар + TopAppBar), чтобы список
    // не прыгал на первом кадре до onSizeChanged.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var headerHeight by remember { mutableStateOf(statusBarTop + 64.dp) }
    var isScrolled by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    // При смене секции прячем затемнение до первого события скролла.
    LaunchedEffect(activeSection) {
        isScrolled = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("library_screen")
    ) {
        AnimatedContent(
            targetState = activeSection,
            transitionSpec = {
                // ALL ↔ секция — направленный горизонтальный переход
                // между секциями — мягкий vertical
                if (initialState == "ALL" || targetState == "ALL") {
                    zyroDirectionalTransition(forward = targetState != "ALL")
                } else {
                    zyroSectionTransition()
                }
            },
            label = "library_section_transition",
            modifier = Modifier.fillMaxSize()
        ) { section ->
            val onScrolledChange: (Boolean) -> Unit = { scrolled ->
                if (activeSection == section) isScrolled = scrolled
            }
            when (section) {
                "ALL" -> LibraryAllSection(
                    likedTracks = likedTracks,
                    likedArtists = likedArtists,
                    likedAlbums = likedAlbums,
                    offlineTracks = offlineTracks,
                    playlists = playlists,
                    likedPlaylists = likedPlaylists,
                    scLikedPlaylists = scLikedPlaylists,
                    serverPlaylists = serverPlaylists,
                    history = history,
                    currentTrackUrl = currentTrackUrl,
                    scIsConnected = scIsConnected,
                    scUsername = scUsername,
                    onSectionClick = {
                        previousSection = activeSection
                        activeSection = it
                    },
                    onTrackClick = { track, queue ->
                        onTrackClick(track, queue, "Играет в недавно прослушанных")
                    },
                    onLikeTrack = onLikeTrack,
                    onDownloadTrack = onDownloadTrack,
                    onDeleteOfflineTrack = { onDeleteOffline(it.originalUrl) },
                    onPlayNext = onPlayNext,
                    offlineTrackUrls = offlineTracks.map { it.originalUrl }.toSet(),
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    topPadding = headerHeight,
                    onScrolledChange = onScrolledChange
                )
                "LIKED_TRACKS" -> LikedTracksSection(
                    likedTracks = likedTracks,
                    currentTrackUrl = currentTrackUrl,
                    offlineTracks = offlineTracks,
                    onTrackClick = { track, queue ->
                        onTrackClick(track, queue, "Играет в любимых треках")
                    },
                    onLikeTrack = onLikeTrack,
                    onDownloadTrack = onDownloadTrack,
                    onDeleteOfflineTrack = { onDeleteOffline(it.originalUrl) },
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onArtistClick = onTrackArtistClick,
                    onDownloadAll = onDownloadAllLiked,
                    downloadState = offlineDownloadState,
                    isDownloaded = allLikedOffline,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    topPadding = headerHeight,
                    onScrolledChange = onScrolledChange
                )
                "LIKED_ARTISTS" -> ArtistsSection(
                    artists = likedArtists,
                    onArtistClick = onArtistClick,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    topPadding = headerHeight,
                    onScrolledChange = onScrolledChange
                )
                "LIKED_ALBUMS" -> AlbumsSection(
                    albums = likedAlbums,
                    onAlbumClick = onAlbumClick,
                    onDownloadAlbum = onDownloadAlbum,
                    downloadState = offlineDownloadState,
                    offlineCollectionKeys = offlineCollectionKeys,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    topPadding = headerHeight,
                    onScrolledChange = onScrolledChange
                )
                "PLAYLISTS" -> PlaylistsSection(
                    likedPlaylists = likedPlaylists,
                    scLikedPlaylists = scLikedPlaylists,
                    serverPlaylists = serverPlaylists,
                    playlists = playlists,
                    onPlaylistClick = onPlaylistClick,
                    onSelectPlaylist = onSelectPlaylist,
                    onDeletePlaylist = onDeletePlaylist,
                    onCreatePlaylist = { showCreateDialog = true },
                    onDownloadPlaylist = onDownloadPlaylist,
                    onDownloadLocalPlaylist = onDownloadLocalPlaylist,
                    localPlaylistCovers = localPlaylistCovers,
                    downloadState = offlineDownloadState,
                    offlineCollectionKeys = offlineCollectionKeys,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    topPadding = headerHeight,
                    onScrolledChange = onScrolledChange
                )
                "OFFLINE" -> OfflineSection(
                    offlineTracks = offlineTracks,
                    likedTracks = likedTracks,
                    currentTrackUrl = currentTrackUrl,
                    onTrackClick = { track, queue ->
                        onTrackClick(track, queue, "Играет в офлайне")
                    },
                    onLikeTrack = onLikeTrack,
                    onDownloadTrack = onDownloadTrack,
                    onDeleteOfflineTrack = { onDeleteOffline(it.originalUrl) },
                    offlineCollections = offlineCollections,
                    offlineCollectionTracks = offlineCollectionTracks,
                    onPlayNext = onPlayNext,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    topPadding = headerHeight,
                    onScrolledChange = onScrolledChange
                )
                "HISTORY" -> HistorySection(
                    history = history,
                    likedTracks = likedTracks,
                    offlineTracks = offlineTracks,
                    currentTrackUrl = currentTrackUrl,
                    onTrackClick = { track, queue ->
                        onTrackClick(track, queue, "Играет в истории")
                    },
                    onLikeTrack = onLikeTrack,
                    onDownloadTrack = onDownloadTrack,
                    onDeleteOfflineTrack = { onDeleteOffline(it.originalUrl) },
                    onPlayNext = onPlayNext,
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    topPadding = headerHeight,
                    onScrolledChange = onScrolledChange
                )
            }
        }

        // Затемнение под прозрачной шапкой; шапка уходит в статус-бар.
        ZyroTopScrim(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
            height = headerHeight + 24.dp,
            visible = isScrolled
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                .statusBarsPadding()
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = sectionTitle,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = ZyroTextPrimary
                        )
                    )
                },
                navigationIcon = {
                    if (activeSection != "ALL") {
                        IconButton(onClick = {
                            previousSection = activeSection
                            activeSection = "ALL"
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Назад",
                                tint = ZyroTextPrimary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    // Прозрачная шапка: фон авроры просвечивает и сверху,
                    // поэтому нет тёмной полосы другого оттенка.
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { draft ->
                onCreatePlaylist(draft)
                showCreateDialog = false
            },
            showServerFields = scIsConnected
        )
    }
}