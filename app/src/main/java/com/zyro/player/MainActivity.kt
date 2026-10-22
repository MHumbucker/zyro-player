package com.zyro.player

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.zyro.player.data.downloader.OfflineCollectionKeys
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import com.player.player.PlayerPlaybackState
import com.zyro.player.data.auth.AuthFlowManager
import com.zyro.player.data.auth.SessionManager
import com.zyro.player.data.remote.PlaylistInfo
import com.zyro.player.data.remote.TrackInfo
import com.zyro.player.ui.MainViewModel
import com.zyro.player.ui.LibraryMutation
import com.zyro.player.ui.components.*
import com.zyro.player.ui.screens.FeedScreen
import com.zyro.player.ui.screens.FeedSection
import com.zyro.player.ui.screens.HomeScreen
import com.zyro.player.ui.screens.LibraryScreen
import com.zyro.player.ui.screens.OnboardingScreen
import com.zyro.player.ui.screens.SearchScreen
import com.zyro.player.ui.screens.SoundCloudAuthScreen
import com.zyro.player.ui.theme.ThemeMode
import com.zyro.player.ui.theme.ZyroPlayerTheme
import com.zyro.player.ui.theme.rememberZyroFontFamily
import com.zyro.player.ui.viewmodel.SoundCloudAuthViewModel
import androidx.compose.ui.tooling.preview.Preview
import com.player.player.RepeatMode

class MainActivity : ComponentActivity() {

    /** Ссылка SoundCloud, пришедшая извне. Обрабатывается уже внутри Compose. */
    private val incomingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incomingLink.value = extractSoundCloudUrl(intent)
        handleAuthRedirect(intent)

        setContent {
            val viewModel: MainViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val fontMode by viewModel.fontMode.collectAsStateWithLifecycle()
            val customFont by viewModel.customFont.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }
            val fontFamily = rememberZyroFontFamily(fontMode, customFont.uri)
            val link by incomingLink.collectAsStateWithLifecycle()

            LaunchedEffect(link) {
                link?.let {
                    viewModel.handleIncomingLink(it)
                    incomingLink.value = null
                }
            }

            ZyroPlayerTheme(darkTheme = darkTheme, fontFamily = fontFamily) {
                MainAppContent(viewModel = viewModel)
            }
        }
    }

    // launchMode=singleTask: повторный переход по ссылке приходит сюда, а не создаёт новую активити
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingLink.value = extractSoundCloudUrl(intent)
        handleAuthRedirect(intent)
    }

    /**
     * OAuth-редирект SoundCloud приходит как sc://auth?code=...
     * Кладём код в AuthFlowManager, откуда его забирает экран авторизации.
     */
    private fun handleAuthRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "sc" && data.host == "auth") {
            data.getQueryParameter("code")?.takeIf { it.isNotBlank() }?.let {
                AuthFlowManager.setAuthCode(it)
            }
        }
    }

    private fun extractSoundCloudUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        val host = data.host ?: return null
        return if (host.endsWith("soundcloud.com")) data.toString() else null
    }
}

@Composable
fun MainAppContent(viewModel: MainViewModel) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val isPlayerExpanded by viewModel.isPlayerExpanded.collectAsStateWithLifecycle()

    val currentTrack by viewModel.playerManager.currentTrack.collectAsStateWithLifecycle()
    val playbackState by viewModel.playerManager.playbackState.collectAsStateWithLifecycle()
    val currentPosition by viewModel.playerManager.currentPosition.collectAsStateWithLifecycle()
    val duration by viewModel.playerManager.duration.collectAsStateWithLifecycle()
    val isShuffle by viewModel.playerManager.isShuffle.collectAsStateWithLifecycle()
    val repeatMode by viewModel.playerManager.repeatMode.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playerManager.playbackSpeed.collectAsStateWithLifecycle()
    val equalizerState by viewModel.playerManager.equalizerState.collectAsStateWithLifecycle()

    val homeSections by viewModel.homeSections.collectAsStateWithLifecycle()
    val homeCoverOverrides by viewModel.homeCoverOverrides.collectAsStateWithLifecycle()
    val isHomeLoading by viewModel.isHomeLoading.collectAsStateWithLifecycle()

    val feedActivities by viewModel.feedActivities.collectAsStateWithLifecycle()
    val isFeedLoading by viewModel.isFeedLoading.collectAsStateWithLifecycle()
    val isFeedLoadingMore by viewModel.isFeedLoadingMore.collectAsStateWithLifecycle()
    val feedHasMore by viewModel.feedHasMore.collectAsStateWithLifecycle()
    val feedError by viewModel.feedError.collectAsStateWithLifecycle()

    val discoverTracks by viewModel.discoverTracks.collectAsStateWithLifecycle()
    val isDiscoverLoading by viewModel.isDiscoverLoading.collectAsStateWithLifecycle()
    val isDiscoverLoadingMore by viewModel.isDiscoverLoadingMore.collectAsStateWithLifecycle()
    val discoverHasMore by viewModel.discoverHasMore.collectAsStateWithLifecycle()
    val discoverError by viewModel.discoverError.collectAsStateWithLifecycle()

    val likedTracks by viewModel.likedTracks.collectAsStateWithLifecycle()
    val likedArtists by viewModel.likedArtists.collectAsStateWithLifecycle()
    val likedAlbums by viewModel.likedAlbums.collectAsStateWithLifecycle()
    val likedPlaylists by viewModel.likedPlaylists.collectAsStateWithLifecycle()
    val localPlaylistCovers by viewModel.localPlaylistCovers.collectAsStateWithLifecycle()
    val offlineTracks by viewModel.offlineTracks.collectAsStateWithLifecycle()
    val offlineCollections by viewModel.offlineCollections.collectAsStateWithLifecycle()
    val offlineCollectionTracks by viewModel.offlineCollectionTracks.collectAsStateWithLifecycle()
    val offlineDownloadState by viewModel.offlineDownloadState.collectAsStateWithLifecycle()
    val playlists by viewModel.customPlaylists.collectAsStateWithLifecycle()
    // В старых версиях серверные плейлисты импортировались в Room (coverUrl
    // заполнялся только импортом). Не показываем эти устаревшие копии.
    val localPlaylists = remember(playlists) { playlists.filter { it.coverUrl.isBlank() } }
    val historyTracks by viewModel.historyTracks.collectAsStateWithLifecycle()

    val lyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()
    val isLyricsLoading by viewModel.isLyricsLoading.collectAsStateWithLifecycle()

    val activeAlbumDetail by viewModel.activeAlbumDetail.collectAsStateWithLifecycle()
    val isAlbumRefreshing by viewModel.isAlbumRefreshing.collectAsStateWithLifecycle()
    val activePlaylistDetail by viewModel.activePlaylistDetail.collectAsStateWithLifecycle()
    val isPlaylistRefreshing by viewModel.isPlaylistRefreshing.collectAsStateWithLifecycle()
    val activeLocalPlaylistDetail by viewModel.activeLocalPlaylistDetail.collectAsStateWithLifecycle()
    val activeArtistDetail by viewModel.activeArtistDetail.collectAsStateWithLifecycle()
    val isArtistDetailLoading by viewModel.isArtistDetailLoading.collectAsStateWithLifecycle()
    val isArtistRefreshing by viewModel.isArtistRefreshing.collectAsStateWithLifecycle()
    val followOverrides by viewModel.followOverrides.collectAsStateWithLifecycle()
    val activeTrackInfoDetail by viewModel.activeTrackInfoDetail.collectAsStateWithLifecycle()

    var trackToAddToPlaylist by remember { mutableStateOf<TrackInfo?>(null) }
    var isQueueExpanded by remember { mutableStateOf(false) }

    val queue by viewModel.playerManager.queue.collectAsStateWithLifecycle()
    val currentIndex by viewModel.playerManager.currentIndex.collectAsStateWithLifecycle()
    val playbackContext by viewModel.playbackContext.collectAsStateWithLifecycle()
    val onScreenCommentsEnabled by viewModel.onScreenCommentsEnabled.collectAsStateWithLifecycle()

    // === SoundCloud ===
    var showSettings by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var showEditProfile by remember { mutableStateOf(false) }
    var showOfflineSettings by remember { mutableStateOf(false) }
    var showScAuth by remember { mutableStateOf(false) }
    // Полноэкранный WebView для прохождения капчи DataDome.
    var showCaptchaChallenge by remember { mutableStateOf(false) }
    val captchaRequired by SessionManager.captchaRequired.collectAsStateWithLifecycle()
    LaunchedEffect(captchaRequired) {
        if (!captchaRequired) showCaptchaChallenge = false
    }
    // Реальная высота нижнего бара: плашка капчи должна висеть над ним
    // и на обычных экранах, и поверх полноэкранных шторок.
    var bottomBarHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    // Разовый переход на вкладку «Любимые треки» из плашки лайков на главной.
    var openLibraryWithLikes by remember { mutableStateOf(false) }
    // Переход в поиск по большой плашке на главной: сразу фокус и клавиатура.
    var searchAutoFocus by remember { mutableStateOf(0) }
    // Активная секция «Ленты»: 0 — «Найти», 1 — «Подписки».
    var feedSection by rememberSaveable { mutableStateOf(FeedSection.DISCOVER) }
    // Время первого нажатия «назад» на главной для подтверждения выхода.
    var lastExitBackAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(selectedTab) {
        lastExitBackAt = 0L
        if (selectedTab != 3) openLibraryWithLikes = false
    }

    val context = LocalContext.current
    val hostActivity = remember(context) { context.findHostActivity() }

    // Системный «назад» на уровне вкладок. Детальные экраны, плеер и шторки
    // объявлены ниже и перехватывают жест раньше; сюда он доходит, когда
    // открыта одна из вкладок.
    BackHandler {
        if (selectedTab != 0) {
            // Лента/поиск/медиатека → главная.
            viewModel.setTab(0)
            lastExitBackAt = 0L
        } else {
            val now = SystemClock.elapsedRealtime()
            val canExit = lastExitBackAt != 0L && now - lastExitBackAt <= 2000L
            if (canExit) {
                hostActivity?.finish()
            } else {
                lastExitBackAt = now
                Toast.makeText(
                    context,
                    "Нажмите назад ещё раз, чтобы выйти",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // === Разрешение на уведомления ===
    // MediaStyle-уведомление — это то, по чему Volumee и системные контроллеры
    // понимают, что Zyro сейчас активный плеер. Если пользователь отклонил
    // разрешение в онбординге, просим его ещё раз при первом воспроизведении.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            android.widget.Toast.makeText(
                context,
                "Без уведомлений Zyro не сможет показывать прогресс кеширования и управление плеером",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    var notificationPermissionAsked by remember { mutableStateOf(false) }
    LaunchedEffect(currentTrack?.originalUrl, playbackState, offlineDownloadState.isActive) {
        val wantsPlayback = playbackState == PlayerPlaybackState.PLAYING ||
            playbackState == PlayerPlaybackState.PREPARING
        val wantsDownloadNotification = offlineDownloadState.isActive

        // Не спрашиваем разрешение при простом восстановлении очереди:
        // трек показан, но ещё не играет.
        if ((wantsDownloadNotification || (currentTrack != null && wantsPlayback)) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED &&
            !notificationPermissionAsked
        ) {
            notificationPermissionAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // === Онбординг ===
    // Показывается ровно тогда, когда онбординг ещё НЕ пройден (флаг "zyro_onboarding").
    // Единственный источник правды — сам флаг, поэтому после прохождения он больше не появится.
    val onboardingPrefs = remember { OnboardingPreferences(context) }
    var showOnboarding by remember { mutableStateOf(!onboardingPrefs.isCompleted) }
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val fontMode by viewModel.fontMode.collectAsStateWithLifecycle()
    val customFont by viewModel.customFont.collectAsStateWithLifecycle()

    // Показываем ошибки плеера пользователю (раньше они терялись в логах).
    val playerError by viewModel.playerManager.errorMessage.collectAsStateWithLifecycle()
    LaunchedEffect(playerError) {
        playerError?.takeIf { it.isNotBlank() }?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Одноразовые сообщения пользователю: скачивание, поиск альбома и т.п.
    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Общие зависимости процесса: тот же репозиторий библиотеки, что у
    // MainViewModel. Внутри него — кеш JSON плейлистов (повторное добавление
    // трека не делает лишний GET) и общий мьютекс мутаций. Раньше здесь
    // создавался второй экземпляр с отдельными кешами и отдельным HTTP-клиентом.
    val app = remember { context.applicationContext as ZyroApplication }
    val authManager = remember { app.authManager }
    val scRepo = remember { app.soundCloudRepository }
    val libRepo = viewModel.libraryRepo
    val scPrefs = remember { context.getSharedPreferences("zyro_sc_sync", android.content.Context.MODE_PRIVATE) }
    val scViewModel = remember { SoundCloudAuthViewModel(authManager, libRepo, scPrefs) }

    val scState by scViewModel.uiState.collectAsStateWithLifecycle()

    // Лента загружается при входе на вкладку; повторное нажатие вкладки
    // обновляет подписки через MainViewModel.setTab(). Секция «Найти»
    // грузится один раз и обновляется кнопкой в шапке.
    LaunchedEffect(selectedTab, scState.isConnected, feedSection) {
        if (selectedTab == 1) {
            if (feedSection == FeedSection.DISCOVER) {
                viewModel.loadDiscover()
            } else if (scState.isConnected) {
                viewModel.loadFeed()
            }
        }
    }

    // После повторного входа обновляем ленту, даже если isConnected уже был true.
    LaunchedEffect(scState.currentUser?.id) {
        if (selectedTab == 1 && scState.isConnected) {
            if (feedSection == FeedSection.FOLLOWING) viewModel.refreshFeed()
            else viewModel.refreshDiscover()
        }
    }

    LaunchedEffect(scState.profileSavedAt) {
        if (scState.profileSavedAt > 0L) {
            showEditProfile = false
            Toast.makeText(context, "Профиль обновлён", Toast.LENGTH_SHORT).show()
            scViewModel.clearProfileFlags()
        }
    }
    LaunchedEffect(scState.profileImageUpdatedAt) {
        if (scState.profileImageUpdatedAt > 0L) {
            Toast.makeText(context, "Изображение профиля обновлено", Toast.LENGTH_SHORT).show()
            scViewModel.clearProfileFlags()
        }
    }

    // Источники для «Добавить из» в плейлисте: лайки, серверные и локальные плейлисты.
    val playlistAddSources = remember(likedTracks, localPlaylists, scState.libraryData) {
        buildList {
            add(
                PlaylistAddSource(
                    key = "likes",
                    title = "Лайки",
                    subtitle = "${likedTracks.size} треков",
                    isLikes = true
                )
            )
            (scState.libraryData?.playlists.orEmpty() +
                scState.libraryData?.likedPlaylists.orEmpty())
                .distinctBy { it.id }
                .forEach { playlist ->
                    add(
                        PlaylistAddSource(
                            key = "sc_${playlist.id}",
                            title = playlist.title,
                            subtitle = "Плейлист • ${playlist.trackCount} треков",
                            playlist = playlist
                        )
                    )
                }
            localPlaylists.forEach { playlist ->
                add(
                    PlaylistAddSource(
                        key = "local_${playlist.playlistId}",
                        title = playlist.title,
                        subtitle = "Локальный плейлист",
                        localPlaylistId = playlist.playlistId
                    )
                )
            }
        }
    }

    // Полный снапшот SoundCloud сразу переносим в Room. Раньше локальная
    // библиотека тянула только первые 100 лайков, из-за чего счётчик рос
    // от запуска к запуску и порядок не совпадал с сайтом.
    LaunchedEffect(scState.libraryData) {
        val data = scState.libraryData ?: return@LaunchedEffect
        viewModel.importSoundCloudLikes(data.likedTracks)
        viewModel.importSoundCloudFollowings(data.followings)
    }
    // Держим сессию SoundCloud живой: ghost WebView + keep-alive,
    // пока пользователь авторизован.
    LaunchedEffect(scState.isConnected) {
        if (scState.isConnected) {
            SessionManager.ensureGhost(context)
            SessionManager.requestSessionRefresh(context, force = false)
        } else {
            viewModel.clearFeed()
            viewModel.clearDiscover()
        }
    }

    val likedUrls = remember(likedTracks) { likedTracks.map { it.originalUrl }.toSet() }
    val offlineUrls = remember(offlineTracks) { offlineTracks.map { it.originalUrl }.toSet() }
    val offlineCollectionKeys = remember(offlineCollections) {
        offlineCollections.map { it.collectionKey }.toSet()
    }

    // После подтверждённой сервером мутации обновляем снапшот SoundCloud-вкладки
    // (он хранится в отдельном ViewModel), чтобы списки согласованно отражали изменение.
    LaunchedEffect(Unit) {
        viewModel.mutations.collect { mutation ->
            when (mutation) {
                is LibraryMutation.TrackLike ->
                    scViewModel.applyTrackLikeState(mutation.track, mutation.liked)
                is LibraryMutation.PlaylistLike ->
                    scViewModel.applyPlaylistLikeState(mutation.playlist, mutation.liked)
                is LibraryMutation.PlaylistCreated ->
                    scViewModel.applyPlaylistCreated(mutation.playlist)
                is LibraryMutation.PlaylistUpdated ->
                    scViewModel.applyPlaylistUpdated(mutation.playlist)
                is LibraryMutation.PlaylistDeleted ->
                    scViewModel.applyPlaylistDeleted(mutation.playlistId)
                is LibraryMutation.Follow ->
                    scViewModel.applyFollowState(mutation.user, mutation.following)
            }
        }
    }

    // Универсальные кинематографичные анимации для экранов (BottomSheet / Modals)
    val sheetEnterAnim = ZyroModalEnter
    val sheetExitAnim = ZyroModalExit

    // Анимации для переключения вкладок Tab (fade + мягкий подъём)
    val tabEnterAnim = fadeIn(tween(280, easing = FastOutSlowInEasing)) +
            scaleIn(
                initialScale = 0.985f,
                animationSpec = tween(340, easing = FastOutSlowInEasing)
            ) +
            slideInVertically(
                animationSpec = tween(340, easing = FastOutSlowInEasing)
            ) { it / 40 }
    val tabExitAnim = fadeOut(tween(160, easing = FastOutLinearInEasing)) +
            scaleOut(
                targetScale = 0.99f,
                animationSpec = tween(160, easing = FastOutLinearInEasing)
            )

    ZyroAuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // «Морозная» подложка: контент плавно уходит в фон ещё до бара,
                        // поэтому под навигацией и мини-плеером ничего не просвечивает.
                        // drawBehind рисует выше границ, не увеличивая высоту бара.
                        .drawBehind {
                            val extend = 64.dp.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        ZyroBackground.copy(alpha = 0.55f),
                                        ZyroBackground.copy(alpha = 0.92f),
                                        ZyroBackground
                                    )
                                ),
                                topLeft = Offset(0f, -extend),
                                size = Size(size.width, size.height + extend)
                            )
                        }
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp)
                        .onSizeChanged { bottomBarHeightPx = it.height }
                ) {
                    AnimatedVisibility(
                        visible = currentTrack != null && !isPlayerExpanded,
                        enter = slideInVertically(initialOffsetY = { 80 }, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { 80 }, animationSpec = tween(200)) + fadeOut()
                    ) {
                        Column {
                            MiniPlayerBar(
                                track = currentTrack!!,
                                playbackState = playbackState,
                                currentPosition = currentPosition,
                                duration = duration,
                                isLiked = likedUrls.contains(currentTrack!!.originalUrl),
                                onExpandClick = { viewModel.setPlayerExpanded(true) },
                                onPlayPauseClick = { viewModel.togglePlayPause() },
                                onToggleLike = { viewModel.toggleLikeTrackWithSync(currentTrack!!) },
                                onNextClick = { viewModel.playNext() },
                                onPrevClick = { viewModel.playPrevious() }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    ZyroBottomNavBar(
                        selectedTab = selectedTab,
                        onTabSelected = { viewModel.setTab(it) }
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = 0.dp,
                        bottom = 0.dp
                    )
            ) {
                AnimatedVisibility(
                    visible = selectedTab == 0,
                    enter = tabEnterAnim,
                    exit = tabExitAnim,
                    modifier = Modifier.fillMaxSize()
                ) {
                    HomeScreen(
                        sections = homeSections,
                        coverOverrides = homeCoverOverrides,
                        isLoading = isHomeLoading,
                        isConnected = scState.isConnected,
                        currentUser = scState.currentUser,
                        likesCount = likedTracks.size,
                        currentTrackUrl = currentTrack?.originalUrl,
                        onRefreshClick = { viewModel.loadHomeFeed() },
                        onSettingsClick = { showSettings = true },
                        onSearchClick = {
                            searchAutoFocus++
                            viewModel.setTab(2)
                        },
                        onLikesPlateClick = { openLibraryWithLikes = true; viewModel.setTab(3) },
                        onShuffleLikesClick = { viewModel.playShuffledLikedTracks() },
                        onTrackClick = { track, queue ->
                            viewModel.playTrack(track, queue, "Играет на главной")
                        },
                        onMixClick = { viewModel.openPlaylistDetail(it) },
                        onArtistClick = { viewModel.openArtistDetail(it) },
                        onLoginClick = { showScAuth = true },
                        onProfileClick = { viewModel.openArtistDetail(it) }
                    )
                }
                AnimatedVisibility(
                    visible = selectedTab == 1,
                    enter = tabEnterAnim,
                    exit = tabExitAnim,
                    modifier = Modifier.fillMaxSize()
                ) {
                    FeedScreen(
                        section = feedSection,
                        onSectionChange = { feedSection = it },
                        activities = feedActivities,
                        isLoading = isFeedLoading,
                        isLoadingMore = isFeedLoadingMore,
                        hasMore = feedHasMore,
                        error = feedError,
                        isConnected = scState.isConnected,
                        currentTrackUrl = currentTrack?.originalUrl,
                        likedUrls = likedUrls,
                        onRefresh = { viewModel.refreshFeed() },
                        onLoadMore = { viewModel.loadMoreFeed() },
                        onTrackClick = { track, queue ->
                            viewModel.playTrack(track, queue, "Играет в ленте")
                        },
                        onPlaylistClick = { viewModel.openPlaylistDetail(it) },
                        onArtistClick = { viewModel.openArtistDetail(it) },
                        onLikeTrack = { viewModel.toggleLikeTrackWithSync(it) },
                        onLoginClick = { showScAuth = true },
                        discoverTracks = discoverTracks,
                        isDiscoverLoading = isDiscoverLoading,
                        isDiscoverLoadingMore = isDiscoverLoadingMore,
                        discoverHasMore = discoverHasMore,
                        discoverError = discoverError,
                        isPlaying = playbackState == PlayerPlaybackState.PLAYING,
                        onDiscoverRefresh = { viewModel.refreshDiscover() },
                        onDiscoverLoadMore = { viewModel.loadMoreDiscover() },
                        onDiscoverPlay = { track, queue ->
                            viewModel.playTrack(track, queue, "Играет в ленте: Найти")
                        },
                        onDiscoverArtistClick = { viewModel.openArtistForTrack(it) },
                        onTogglePlayPause = { viewModel.togglePlayPause() }
                    )
                }
                AnimatedVisibility(
                    visible = selectedTab == 2,
                    enter = tabEnterAnim,
                    exit = tabExitAnim,
                    modifier = Modifier.fillMaxSize()
                ) {
                    SearchScreen(
                        viewModel = viewModel,
                        onTrackClick = { track, queue ->
                            viewModel.playTrack(track, queue, "Играет в поиске")
                        },
                        onArtistClick = { viewModel.openArtistDetail(it) },
                        onAlbumClick = { viewModel.openAlbumDetail(it) },
                        onPlaylistClick = { viewModel.openPlaylistDetail(it) },
                        onAddToPlaylist = { track ->
                            trackToAddToPlaylist = track
                        },
                        autoFocusTrigger = searchAutoFocus
                    )
                }
                AnimatedVisibility(
                    visible = selectedTab == 3,
                    enter = tabEnterAnim,
                    exit = tabExitAnim,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LibraryScreen(
                        initialSection = if (openLibraryWithLikes) "LIKED_TRACKS" else "ALL",
                        likedTracks = likedTracks,
                        likedArtists = likedArtists,
                        likedAlbums = likedAlbums,
                        likedPlaylists = likedPlaylists,
                        offlineTracks = offlineTracks,
                        offlineCollections = offlineCollections,
                        offlineCollectionTracks = offlineCollectionTracks,
                        offlineDownloadState = offlineDownloadState,
                        playlists = localPlaylists,
                        localPlaylistCovers = localPlaylistCovers,
                        history = historyTracks,
                        currentTrackUrl = currentTrack?.originalUrl,
                        scIsConnected = scState.isConnected,
                        scIsSyncing = scState.isSyncing,
                        scUsername = scState.username,
                        scCurrentUser = scState.currentUser,
                        scLibraryData = scState.libraryData,
                        scSyncMessage = scState.syncMessage,
                        onScSyncClick = { scViewModel.syncLibrary() },
                        isRefreshing = scState.isMicroSyncing,
                        onRefresh = { scViewModel.microSyncLibrary() },
                        onScConnectClick = {
                            if (!scState.isConnected) showScAuth = true else showSync = true
                        },
                        onCreatePlaylist = { draft -> viewModel.createPlaylistSynced(draft) },
                        onDeletePlaylist = { viewModel.deletePlaylist(it) },
                        onSelectPlaylist = { viewModel.openLocalPlaylistDetail(it) },
                        onTrackClick = { track, queue, context ->
                            viewModel.playTrack(track, queue, context)
                        },
                        onLikeTrack = { viewModel.toggleLikeTrackWithSync(it) },
                        onDownloadTrack = { viewModel.downloadTrackForOffline(it) },
                        onDeleteOffline = { viewModel.deleteOfflineTrack(it) },
                        onArtistClick = { viewModel.openArtistDetail(it) },
                        onAlbumClick = { viewModel.openAlbumDetail(it) },
                        onPlaylistClick = { viewModel.openPlaylistDetail(it) },
                        onDownloadAlbum = { viewModel.downloadAlbumForOffline(it) },
                        onDownloadPlaylist = { viewModel.downloadPlaylistForOffline(it) },
                        onDownloadLocalPlaylist = { viewModel.downloadLocalPlaylistForOffline(it) },
                        onDownloadAllLiked = { viewModel.downloadLikedTracksForOffline() },
                        onPlayNext = { viewModel.playNextInQueue(it) },
                        onAddToQueue = { viewModel.toggleQueue(it) },
                        onTrackArtistClick = { viewModel.openArtistForTrack(it) },
                        onScLikeTrack = { viewModel.toggleLikeTrackWithSync(it) },
                        onScLikePlaylist = { viewModel.toggleLikePlaylistWithSync(it) },
                        onScFollowArtist = { viewModel.toggleFollowUserWithSync(it) }
                    )
                }

                // Инфо о треке рисуется НИЖЕ профиля артиста: из него можно
                // открыть артиста, и тогда «назад» должен вернуть к треку.
                ZyroRetainedVisibility(
                    value = activeTrackInfoDetail,
                    enter = sheetEnterAnim,
                    exit = sheetExitAnim
                ) { track ->
                    TrackInfoDetailModal(
                        track = track,
                        // Для трека из deep-link локальный список может ещё
                        // не содержать его, но сервер уже сообщил isLiked.
                        isLiked = likedUrls.contains(track.originalUrl) || track.isLiked,
                        isOffline = offlineUrls.contains(track.originalUrl),
                        onClose = { viewModel.closeTrackInfo() },
                        onLikeTrack = { viewModel.toggleLikeTrackWithSync(it) },
                        onDownloadTrack = { viewModel.downloadTrackForOffline(it) },
                        onArtistClick = { trk ->
                            viewModel.openArtistForTrack(trk, preserveDetails = true)
                        }
                    )
                }

                ZyroRetainedVisibility(
                    value = activeArtistDetail,
                    enter = sheetEnterAnim,
                    exit = sheetExitAnim
                ) { fullData ->
                    ArtistDetailModal(
                        artistData = fullData,
                        currentTrackUrl = currentTrack?.originalUrl,
                        likedUrls = likedUrls,
                        offlineUrls = offlineUrls,
                        isLoading = isArtistDetailLoading,
                        isRefreshing = isArtistRefreshing,
                        isLiked = likedArtists.any { it.permalinkUrl == fullData.user.permalinkUrl },
                        isFollowing = followOverrides[fullData.user.id]
                            ?: (scState.libraryData?.followings?.any { it.id == fullData.user.id } ?: false),
                        isOwnProfile = scState.currentUser?.id == fullData.user.id,
                        onRefresh = { viewModel.refreshArtistDetail() },
                        onLikeArtist = { viewModel.toggleLikeArtist(fullData.user) },
                        onFollowArtist = { viewModel.toggleFollowUserWithSync(fullData.user) },
                        onEditProfile = { showEditProfile = true },
                        onClose = { viewModel.closeArtistDetail() },
                        onTrackClick = { track, queue ->
                            viewModel.playTrack(
                                track,
                                queue,
                                "Играет у артиста ${fullData.user.username}"
                            )
                        },
                        onLikeTrack = { viewModel.toggleLikeTrackWithSync(it) },
                        onDownloadTrack = { viewModel.downloadTrackForOffline(it) },
                        onDeleteOfflineTrack = { viewModel.deleteOfflineTrack(it.originalUrl) },
                        onAlbumClick = { album ->
                            viewModel.openAlbumDetail(album, preserveDetails = true)
                        },
                        onPlaylistClick = { playlist ->
                            viewModel.openPlaylistDetail(playlist, preserveDetails = true)
                        }
                    )
                }

                ZyroRetainedVisibility(
                    value = activeAlbumDetail,
                    enter = sheetEnterAnim,
                    exit = sheetExitAnim
                ) { (album, tracks) ->
                    val albumDownloadKey = OfflineCollectionKeys.album(album.permalinkUrl)
                    AlbumDetailModal(
                        album = album,
                        tracks = tracks,
                        currentTrackUrl = currentTrack?.originalUrl,
                        likedUrls = likedUrls,
                        offlineUrls = offlineUrls,
                        isLiked = likedAlbums.any { it.permalinkUrl == album.permalinkUrl },
                        isRefreshing = isAlbumRefreshing,
                        onRefresh = { viewModel.refreshAlbumDetail() },
                        onLikeAlbum = { viewModel.toggleLikeAlbum(album) },
                        onClose = { viewModel.closeAlbumDetail() },
                        onTrackClick = { track, queue ->
                            viewModel.playTrack(
                                track,
                                queue,
                                "Играет в альбоме «${album.title}»"
                            )
                        },
                        onLikeTrack = { viewModel.toggleLikeTrackWithSync(it) },
                        onDownloadTrack = { viewModel.downloadTrackForOffline(it) },
                        onDeleteOfflineTrack = { viewModel.deleteOfflineTrack(it.originalUrl) },
                        onDownloadAll = { viewModel.downloadAlbumForOffline(album) },
                        downloadState = offlineDownloadState,
                        downloadCollectionKey = albumDownloadKey,
                        isDownloaded = albumDownloadKey in offlineCollectionKeys
                    )
                }

                ZyroRetainedVisibility(
                    value = activePlaylistDetail,
                    enter = sheetEnterAnim,
                    exit = sheetExitAnim
                ) { (playlist, tracks) ->
                    val playlistDownloadKey =
                        OfflineCollectionKeys.playlist(playlist.permalinkUrl)
                    PlaylistDetailModal(
                        playlist = playlist,
                        tracks = tracks,
                        currentTrackUrl = currentTrack?.originalUrl,
                        likedUrls = likedUrls,
                        offlineUrls = offlineUrls,
                        isLiked = playlist.isLiked || likedPlaylists.any { it.permalinkUrl == playlist.permalinkUrl },
                        isRefreshing = isPlaylistRefreshing,
                        canManage = playlist.permalinkUrl.isNotBlank() && (
                            scState.libraryData?.playlists?.any {
                                it.permalinkUrl == playlist.permalinkUrl
                            } == true ||
                                playlist.user.equals(scState.currentUser?.username, ignoreCase = true)
                            ),
                        addSources = playlistAddSources,
                        onLoadSourceTracks = { source ->
                            val sourcePlaylist = source.playlist
                            val localId = source.localPlaylistId
                            when {
                                source.isLikes -> viewModel.likedTracksForPicker()
                                sourcePlaylist != null ->
                                    viewModel.loadPlaylistTracksForPicker(sourcePlaylist)
                                localId != null ->
                                    viewModel.loadLocalPlaylistTracksForPicker(localId)
                                else -> emptyList<TrackInfo>()
                            }
                        },
                        onAddTracks = { selected ->
                            viewModel.addTracksToPlaylistOnServer(playlist.id, selected)
                        },
                        onLikePlaylist = { viewModel.toggleLikePlaylistWithSync(playlist) },
                        onRemoveTrackFromPlaylist = { trackId ->
                            viewModel.removeTrackFromPlaylistOnServer(playlist.id, trackId)
                        },
                        onDeletePlaylist = {
                            viewModel.deletePlaylistOnServer(playlist.id)
                            viewModel.closePlaylistDetail()
                        },
                        onEditPlaylist = { draft ->
                            viewModel.updatePlaylistOnServer(playlist.id, draft)
                        },
                        onRefresh = { viewModel.refreshPlaylistDetail() },
                        onClose = { viewModel.closePlaylistDetail() },
                        onTrackClick = { track, queue ->
                            viewModel.playTrack(
                                track,
                                queue,
                                "Играет в плейлисте «${playlist.title}»"
                            )
                        },
                        onLikeTrack = { viewModel.toggleLikeTrackWithSync(it) },
                        onDownloadTrack = { viewModel.downloadTrackForOffline(it) },
                        onDeleteOfflineTrack = { viewModel.deleteOfflineTrack(it.originalUrl) },
                        onDownloadAll = { viewModel.downloadPlaylistForOffline(playlist) },
                        onRemoveOfflineCollection = {
                            viewModel.deleteOfflineCollection(playlistDownloadKey)
                        },
                        downloadState = offlineDownloadState,
                        downloadCollectionKey = playlistDownloadKey,
                        isDownloaded = playlistDownloadKey in offlineCollectionKeys
                    )
                }

                ZyroRetainedVisibility(
                    value = activeLocalPlaylistDetail,
                    enter = sheetEnterAnim,
                    exit = sheetExitAnim
                ) { (localPlaylist, tracks) ->
                    val playlistInfo = remember(localPlaylist, tracks, localPlaylistCovers) {
                        PlaylistInfo(
                            id = localPlaylist.playlistId,
                            permalinkUrl = "",
                            title = localPlaylist.title,
                            user = "Вы",
                            artworkUrl = localPlaylist.localCoverPath
                                .ifBlank { localPlaylist.coverUrl }
                                .ifBlank {
                                    localPlaylistCovers[localPlaylist.playlistId].orEmpty()
                                }
                                .ifBlank {
                                    tracks.firstOrNull { it.artworkUrl.isNotBlank() }
                                        ?.artworkUrl.orEmpty()
                                },
                            trackCount = tracks.size,
                            description = localPlaylist.description,
                            createdAtMs = localPlaylist.createdTimestamp
                        )
                    }
                    val localDownloadKey =
                        OfflineCollectionKeys.local(localPlaylist.playlistId)
                    PlaylistDetailModal(
                        playlist = playlistInfo,
                        tracks = tracks,
                        currentTrackUrl = currentTrack?.originalUrl,
                        likedUrls = likedUrls,
                        offlineUrls = offlineUrls,
                        isLiked = false,
                        showLikeAction = false,
                        isRefreshing = isPlaylistRefreshing,
                        canManage = true,
                        addSources = playlistAddSources,
                        onLoadSourceTracks = { source ->
                            val sourcePlaylist = source.playlist
                            val localId = source.localPlaylistId
                            when {
                                source.isLikes -> viewModel.likedTracksForPicker()
                                sourcePlaylist != null ->
                                    viewModel.loadPlaylistTracksForPicker(sourcePlaylist)
                                localId != null ->
                                    viewModel.loadLocalPlaylistTracksForPicker(localId)
                                else -> emptyList<TrackInfo>()
                            }
                        },
                        onAddTracks = { selected ->
                            viewModel.addTracksToPlaylist(localPlaylist.playlistId, selected)
                        },
                        onRemoveTrackFromPlaylistUrl = { trackUrl ->
                            viewModel.removeTrackFromPlaylist(
                                localPlaylist.playlistId,
                                trackUrl
                            )
                        },
                        onDeletePlaylist = {
                            viewModel.deletePlaylist(localPlaylist)
                            viewModel.closeLocalPlaylistDetail()
                        },
                        onEditPlaylist = { draft ->
                            viewModel.updateLocalPlaylist(localPlaylist, draft)
                        },
                        onRefresh = { viewModel.refreshLocalPlaylistDetail() },
                        onClose = { viewModel.closeLocalPlaylistDetail() },
                        onTrackClick = { track, queue ->
                            viewModel.playTrack(
                                track,
                                queue,
                                "Играет в плейлисте «${localPlaylist.title}»"
                            )
                        },
                        onLikeTrack = { viewModel.toggleLikeTrackWithSync(it) },
                        onDownloadTrack = { viewModel.downloadTrackForOffline(it) },
                        onDeleteOfflineTrack = { viewModel.deleteOfflineTrack(it.originalUrl) },
                        onDownloadAll = { viewModel.downloadLocalPlaylistForOffline(localPlaylist) },
                        onRemoveOfflineCollection = {
                            viewModel.deleteOfflineCollection(localDownloadKey)
                        },
                        downloadState = offlineDownloadState,
                        downloadCollectionKey = localDownloadKey,
                        isDownloaded = localDownloadKey in offlineCollectionKeys
                    )
                }

            }
        }

        // Без AnimatedVisibility вокруг full player:
        // enter/exit делаем сами через dismissOffset (SoundCloud-style).
        // Nested AV ломал повторное открытие (sheet «пустой», mini уже скрыт).
        if (isPlayerExpanded && currentTrack != null) {
            currentTrack?.let { track ->
                FullPlayerSheet(
                    track = track,
                    queue = queue,
                    currentIndex = currentIndex,
                    playbackState = playbackState,
                    currentPosition = currentPosition,
                    duration = duration,
                    isLiked = likedUrls.contains(track.originalUrl),
                    isOffline = offlineUrls.contains(track.originalUrl),
                    isShuffle = isShuffle,
                    repeatMode = repeatMode,
                    repository = scRepo,
                    lyrics = lyrics,
                    isLyricsLoading = isLyricsLoading,
                    playbackContext = playbackContext,
                    onScreenCommentsEnabled = onScreenCommentsEnabled,
                    onToggleOnScreenComments = { viewModel.toggleOnScreenComments() },
                    onAddToPlaylistFromMore = {
                        trackToAddToPlaylist = it
                    },
                    onTrackInfoFromMore = { trk ->
                        // Инфо-экран рисуется в основном Box раньше полноэкранного
                        // плеера, поэтому сначала сворачиваем плеер.
                        viewModel.setPlayerExpanded(false)
                        viewModel.openTrackInfo(trk)
                    },
                    onCommentUserClick = { permalinkUrl, username, avatarUrl ->
                        viewModel.setPlayerExpanded(false)
                        viewModel.openArtistByPermalink(permalinkUrl, username, avatarUrl)
                    },
                    onCloseClick = { viewModel.setPlayerExpanded(false) },
                    onPlayPauseClick = { viewModel.togglePlayPause() },
                    onNextClick = { viewModel.playNext() },
                    onPrevClick = { viewModel.playPrevious() },
                    onSeekTo = { viewModel.seekTo(it) },
                    onToggleLike = { viewModel.toggleLikeTrackWithSync(track) },
                    onDownloadClick = { viewModel.downloadTrackForOffline(track) },
                    onDeleteOfflineClick = { viewModel.deleteOfflineTrack(track.originalUrl) },
                    onDownloadToDownloads = { viewModel.downloadTrackToDownloads(track) },
                    onToggleShuffle = { viewModel.toggleShuffle() },
                    onToggleRepeat = { viewModel.toggleRepeat() },
                    onSetLyricsSource = { viewModel.setLyricsActiveSource(it) },
                    onSaveCustomLyrics = { viewModel.saveCustomUserLyrics(it) },
                    onSearchLyricsManual = { title, artist -> viewModel.searchLyricsManual(title, artist) },
                    onOpenQueueClick = { isQueueExpanded = true },
                    playbackSpeed = playbackSpeed,
                    equalizerState = equalizerState,
                    onSetPlaybackSpeed = { viewModel.playerManager.setPlaybackSpeed(it) },
                    onToggleEqualizer = { viewModel.playerManager.setEqualizerEnabled(it) },
                    onSelectEqualizerPreset = { viewModel.playerManager.selectEqualizerPreset(it) },
                    onArtistClick = { trk ->
                        viewModel.setPlayerExpanded(false)
                        viewModel.openArtistForTrack(trk)
                    },
                    onAlbumClick = { trk ->
                        viewModel.setPlayerExpanded(false)
                        viewModel.openAlbumForTrack(trk)
                    },
                    prevTrack = queue.getOrNull(currentIndex - 1),
                    nextTrack = queue.getOrNull(currentIndex + 1),
                    modifier = Modifier
                )
            }
        }

        AnimatedVisibility(
            visible = isQueueExpanded,
            enter = sheetEnterAnim, exit = sheetExitAnim
        ) {
            QueueSheet(
                queue = queue,
                currentIndex = currentIndex,
                currentTrack = currentTrack,
                isShuffle = isShuffle,
                onCloseClick = { isQueueExpanded = false },
                onTrackSelect = { idx -> viewModel.playQueueIndex(idx) },
                onRemoveFromQueue = { idx -> viewModel.removeFromQueue(idx) },
                onMoveItem = { from, to -> viewModel.moveQueueItem(from, to) },
                onMoveFinished = { viewModel.persistQueueOrder() },
                onClearQueue = { viewModel.clearQueue() },
                onToggleShuffle = { viewModel.toggleShuffle() }
            )
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = sheetEnterAnim, exit = sheetExitAnim
        ) {
            SettingsSheet(
                onCloseClick = { showSettings = false },
                onNavigateToSync = {
                    showSettings = false
                    showSync = true
                },
                onNavigateToOfflineSettings = {
                    showSettings = false
                    showOfflineSettings = true
                },
                onRestartOnboarding = {
                    onboardingPrefs.reset()
                    showSettings = false
                    showOnboarding = true
                },
                fontMode = fontMode,
                customFontName = customFont.name,
                onFontModeChange = viewModel::setFontMode,
                onCustomFontPicked = viewModel::setCustomFont,
                offlineTrackCount = offlineTracks.size,
                onClearOffline = { viewModel.deleteAllOffline() }
            )
        }

        AnimatedVisibility(
            visible = showOfflineSettings,
            enter = sheetEnterAnim, exit = sheetExitAnim
        ) {
            OfflineDownloadSettingsSheet(
                onBackClick = {
                    showOfflineSettings = false
                    showSettings = true
                }
            )
        }

        AnimatedVisibility(
            visible = showSync,
            enter = sheetEnterAnim, exit = sheetExitAnim
        ) {
            SyncScreen(
                onBackClick = { showSync = false },
                scIsConnected = scState.isConnected,
                scUsername = scState.username,
                scAvatarUrl = scState.currentUser?.avatarUrl,
                scFollowersCount = scState.currentUser?.followersCount ?: 0,
                scTrackCount = scState.currentUser?.trackCount ?: 0,
                scIsSyncing = scState.isSyncing,
                scIsLoading = scState.isLoading,
                scSyncMessage = scState.syncMessage,
                scError = scState.error,
                onScLoginClick = { showScAuth = true },
                onScLogoutClick = { scViewModel.disconnect() },
                onScSyncClick = { scViewModel.syncLibrary() },
                onScEditProfileClick = { showEditProfile = true }
            )
        }

        val profileUser = scState.currentUser
        if (showEditProfile && profileUser != null) {
            EditProfileDialog(
                user = profileUser,
                isSaving = scState.isProfileSaving,
                error = scState.profileError,
                onDismiss = {
                    if (!scState.isProfileSaving) showEditProfile = false
                },
                onSave = { username, firstName, lastName, description, city, countryCode ->
                    scViewModel.saveProfile(
                        username = username,
                        firstName = firstName,
                        lastName = lastName,
                        description = description,
                        city = city,
                        countryCode = countryCode
                    )
                },
                onAvatarPicked = { scViewModel.uploadAvatar(it) },
                onAvatarDeleted = { scViewModel.deleteAvatar() },
                onBannerPicked = { scViewModel.uploadBanner(it) },
                onBannerDeleted = { scViewModel.deleteBanner() }
            )
        }

        // Онбординг первого запуска: полноэкранный слой ПОВЕРХ основного контента.
        // SoundCloudAuthScreen объявлен ниже и потому рисуется ещё выше — OAuth возвращается на шаг 2.
        AnimatedVisibility(
            visible = showOnboarding,
            enter = fadeIn(tween(360)),
            exit = fadeOut(tween(240))
        ) {
            OnboardingScreen(
                scState = scState,
                themeMode = themeMode,
                onConnectSoundCloud = { showScAuth = true },
                onSyncLibrary = { scViewModel.syncLibrary(forceRefresh = true) },
                onThemeModeChange = { viewModel.setThemeMode(it) },
                onFinish = {
                    onboardingPrefs.markCompleted()
                    showOnboarding = false
                    viewModel.setTab(0)
                }
            )
        }

        AnimatedVisibility(
            visible = showScAuth,
            enter = sheetEnterAnim, exit = sheetExitAnim
        ) {
            SoundCloudAuthScreen(
                onAuthSuccess = {
                    scViewModel.onAuthCompleted()
                    showScAuth = false
                    if (selectedTab == 1) viewModel.refreshFeed()
                },
                onBackClick = { showScAuth = false }
            )
        }

        AnimatedVisibility(
            visible = trackToAddToPlaylist != null,
            enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.95f, animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.95f)
        ) {
            trackToAddToPlaylist?.let { track ->
                SelectPlaylistDialog(
                    track = track,
                    playlists = localPlaylists,
                    onDismiss = { trackToAddToPlaylist = null },
                    onSelectPlaylist = { playlist ->
                        viewModel.addTrackToPlaylist(playlist.playlistId, track)
                        trackToAddToPlaylist = null
                    },
                    serverPlaylists = scState.libraryData?.playlists
                        ?.filter { it.id > 0L }
                        ?: emptyList(),
                    onSelectServerPlaylist = { playlistId ->
                        // Закрываем выбор сразу: добавление на SoundCloud —
                        // это GET + PUT, держать диалог со спиннером всё это
                        // время незачем. Результат придёт тостом.
                        trackToAddToPlaylist = null
                        viewModel.addTrackToPlaylistOnServer(playlistId, track)
                    }
                )
            }
        }

        // Плашка капчи живёт на верхнем слое, поэтому видна и на обычных
        // вкладках, и поверх профиля артиста, альбома или плеера. Отступ
        // снизу равен реальной высоте нижнего бара — плашка всегда над ним.
        AnimatedVisibility(
            visible = captchaRequired && !showCaptchaChallenge,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(
                    bottom = with(density) { bottomBarHeightPx.toDp() } + 10.dp
                ),
            enter = fadeIn(tween(180)) + slideInVertically(
                initialOffsetY = { it / 2 },
                animationSpec = tween(220)
            ),
            exit = fadeOut(tween(140)) + slideOutVertically(
                targetOffsetY = { it / 2 },
                animationSpec = tween(160)
            )
        ) {
            CaptchaPromptCard(
                onPassCaptcha = { showCaptchaChallenge = true },
                onDismiss = { viewModel.dismissCaptchaPrompt() }
            )
        }

        // Капча DataDome: полноэкранный WebView поверх всего интерфейса.
        AnimatedVisibility(
            visible = showCaptchaChallenge,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160))
        ) {
            CaptchaChallengeScreen(
                onSolved = {
                    SessionManager.completeCaptchaChallenge(context)
                    showCaptchaChallenge = false
                    Toast.makeText(context, "Проверка пройдена", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showCaptchaChallenge = false }
            )
        }
    }
}

/** Нижний бар навигации: четыре вкладки + скользящий акцентный индикатор. */
@Composable
private fun ZyroBottomNavBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val navItems = listOf(
        NavItemData(Icons.Filled.Home, Icons.Outlined.Home, "tab_home", 0),
        NavItemData(Icons.Filled.DynamicFeed, Icons.Outlined.DynamicFeed, "tab_feed", 1),
        NavItemData(Icons.Filled.Search, Icons.Outlined.Search, "tab_search", 2),
        NavItemData(Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic, "tab_library", 3)
    )
    val tabCount = navItems.size
    var layoutWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val pillWidth = 64.dp
    val pillHeight = 48.dp
    val pillWidthPx = with(density) { pillWidth.toPx() }

    val pillOffsetX by animateFloatAsState(
        targetValue = if (layoutWidth > 0) {
            val tabW = layoutWidth / tabCount.toFloat()
            (selectedTab * tabW) + (tabW - pillWidthPx) / 2f
        } else 0f,
        animationSpec = spring(
            stiffness = Spring.StiffnessMediumLow,
            dampingRatio = 0.75f
        ),
        label = "pill_offset"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .zyroPlasticGlass(
                shape = RoundedCornerShape(37.dp),
                strong = true
            )
            .onSizeChanged { layoutWidth = it.width }
            .testTag("bottom_navigation_bar"),
        contentAlignment = Alignment.Center
    ) {
        // Мягкое тёплое свечение под активной вкладкой.
        Box(
            modifier = Modifier
                .offset { IntOffset(pillOffsetX.toInt(), 0) }
                .align(Alignment.CenterStart)
                .size(width = pillWidth + 24.dp, height = pillHeight + 18.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            ZyroAccent.copy(alpha = 0.26f),
                            Color.Transparent
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(pillOffsetX.toInt(), 0) }
                .align(Alignment.CenterStart)
                .size(width = pillWidth, height = pillHeight)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            ZyroAccent.copy(alpha = 0.30f),
                            ZyroAccent.copy(alpha = 0.10f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            ZyroAccent.copy(alpha = 0.75f),
                            ZyroAccent.copy(alpha = 0.22f)
                        )
                    ),
                    shape = RoundedCornerShape(26.dp)
                )
        )

        Row(modifier = Modifier.fillMaxSize()) {
            navItems.forEach { item ->
                val isSelected = selectedTab == item.index
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()

                val iconScale by animateFloatAsState(
                    targetValue = when {
                        isPressed -> 0.85f
                        isSelected -> 1.15f
                        else -> 1f
                    },
                    animationSpec = spring(
                        stiffness = Spring.StiffnessMedium,
                        dampingRatio = 0.6f
                    ),
                    label = "nav_scale_${item.index}"
                )
                val iconColor by animateColorAsState(
                    targetValue = if (isSelected) ZyroAccent else ZyroTextSecondary,
                    animationSpec = tween(300),
                    label = "nav_color_${item.index}"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onTabSelected(item.index) }
                        )
                        .testTag(item.tag),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier
                            .size(28.dp)
                            .scale(iconScale)
                    )
                }
            }
        }
    }
}

private data class NavItemData(
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String,
    val index: Int
)

/** Контекст Compose может быть обёрнут — ищем именно Activity для выхода. */
private tailrec fun Context.findHostActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}