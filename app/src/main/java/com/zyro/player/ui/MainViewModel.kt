package com.zyro.player.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.zyro.player.ZyroApplication
import com.zyro.player.data.auth.SoundCloudAuthRecoveryInterceptor
import com.zyro.player.data.auth.SessionManager
import com.zyro.player.data.downloader.OfflineCollectionKeys
import com.zyro.player.data.downloader.OfflineDownloadNotifier
import com.zyro.player.data.downloader.OfflineDownloadResult
import com.zyro.player.data.downloader.OfflineDownloadState
import com.zyro.player.data.downloader.OfflineMetaStore
import com.zyro.player.data.downloader.PublicDownloadResult
import com.zyro.player.data.downloader.TrackDownloader
import com.zyro.player.data.local.*
import com.zyro.player.data.remote.*
import com.zyro.player.domain.InteractionLogger
import com.zyro.player.domain.PlaylistDraft
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zyro.player.ui.components.SettingsPreferences
import com.zyro.player.ui.theme.CustomFontInfo
import com.zyro.player.ui.theme.FontMode
import com.zyro.player.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

enum class SearchCategory {
    ALL, TRACKS, ARTISTS, ALBUMS, PLAYLISTS
}

data class SearchUiState(
    val activeQuery: String = "",
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val tracks: List<TrackInfo> = emptyList(),
    val users: List<UserInfo> = emptyList(),
    val albums: List<AlbumInfo> = emptyList(),
    val playlists: List<PlaylistInfo> = emptyList(),
    val offset: Int = 0
)

/** Тип горизонтальной секции главной — как рендерим карточки. */
enum class HomeSectionKind { TRACKS, MIXES, ARTISTS }

/**
 * Одна секция официального фида главной: «Миксы для …»,
 * «Больше из того, что вам нравится», «В тренде по жанрам» и т.п.
 */
data class HomeSection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val kind: HomeSectionKind,
    val isHighlight: Boolean = false,
    val tracks: List<TrackInfo> = emptyList(),
    val mixes: List<PlaylistInfo> = emptyList(),
    val artists: List<UserInfo> = emptyList()
)

/**
 * Подтверждённая сервером мутация библиотеки. Используется, чтобы обновить
 * снапшот SoundCloud-вкладки (он живёт в отдельном [SoundCloudAuthViewModel])
 * без полной перезагрузки библиотеки.
 */
sealed class LibraryMutation {
    data class TrackLike(val track: TrackInfo, val liked: Boolean) : LibraryMutation()
    data class PlaylistLike(val playlist: PlaylistInfo, val liked: Boolean) : LibraryMutation()
    data class PlaylistCreated(val playlist: PlaylistInfo) : LibraryMutation()
    data class PlaylistUpdated(val playlist: PlaylistInfo) : LibraryMutation()
    data class PlaylistDeleted(val playlistId: Long) : LibraryMutation()
    data class Follow(val user: UserInfo, val following: Boolean) : LibraryMutation()
}

@RequiresApi(Build.VERSION_CODES.O)
private const val MAX_RATE_LIMIT_RETRIES = 3
private const val RATE_LIMIT_PAUSE_MS = 90_000L
private const val TRACK_DOWNLOAD_GAP_MS = 750L
private const val FEED_PAGE_SIZE = 20

/** Размер страницы общего поиска SoundCloud (треки + артисты + альбомы + плейлисты). */
private const val SEARCH_PAGE_SIZE = 20

/** Сколько последних поисковых запросов показывать в истории. */
private const val SEARCH_HISTORY_LIMIT = 8

/**
 * Жанры официальных чартов, которыми догружается бесконечная лента «Найти».
 * Первые страницы берутся из персонального фида SoundCloud, а чарты дают
 * разнообразие и позволяют листать рекомендации гостям.
 */
private val DISCOVER_GENRES = listOf(
    "soundcloud:genres:all-music",
    "soundcloud:genres:electronic",
    "soundcloud:genres:hiphoprap",
    "soundcloud:genres:pop",
    "soundcloud:genres:rock",
    "soundcloud:genres:danceedm",
    "soundcloud:genres:alternative",
    "soundcloud:genres:indie",
    "soundcloud:genres:rnb",
    "soundcloud:genres:reggae",
    "soundcloud:genres:ambient",
    "soundcloud:genres:techno"
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ZyroApplication

    private val db = app.database

    // Единый менеджер авторизации: даёт актуальный access_token для запросов к SoundCloud.
    val authManager = app.authManager

    // Общий репозиторий процесса: кеш, client_id и токен живут дольше Activity.
    val soundCloudRepo = app.soundCloudRepository

    val lyricsRepo = LyricsRepository(db.lyricsDao(), MusixmatchClient(application))
    @RequiresApi(Build.VERSION_CODES.O)
    val playerManager = app.playerManager
    val downloader = TrackDownloader(application, soundCloudRepo, db.trackDao())
    private val offlineDownloadNotifier = OfflineDownloadNotifier(application)
    private val offlineCollectionDao = db.offlineCollectionDao()
    private val offlineMetaStore = OfflineMetaStore(application)
    val interactionLogger = InteractionLogger(db.interactionDao(), db.preferenceDao())

    // Репозиторий авторизованной библиотеки SoundCloud (лайки, подписки, плейлисты).
    // Отдельный OkHttpClient: авторизацию выставляет сам репозиторий (authedRequest),
    // поэтому не переиспользуем клиент soundCloudRepo (его интерцептор может
    // перезаписать свежий токен устаревшим).
    private val libraryHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // 401/403 от api-mobile/api-v2: снять cookies и при необходимости
        // обновить OAuth-токен, затем повторить запрос. Это единая точка
        // восстановления для ленты, лайков, подписок, профиля и плейлистов.
        .addInterceptor(
            SoundCloudAuthRecoveryInterceptor(
                refreshTokenBlocking = { staleToken ->
                    authManager.refreshAccessTokenBlocking(staleToken)
                },
                onAuthFailure = { body ->
                    // Запоминаем ссылку на challenge, но плашку показываем
                    // только по итоговой ошибке действия (фолбэк может спасти запрос).
                    SessionManager.noteCaptchaChallenge(body)
                }
            )
        )
        .build()

    val libraryRepo = SoundCloudLibraryRepository(
        client = libraryHttpClient,
        authManager = authManager,
        baseRepo = soundCloudRepo,
        prefs = application.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
    )

    /**
     * Локальные переопределения состояния подписки после успешных мутаций,
     * чтобы UI сразу отражал действие до следующей полной синхронизации.
     * Ключ — SoundCloud userId.
     */
    private val _followOverrides = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val followOverrides: StateFlow<Map<Long, Boolean>> = _followOverrides.asStateFlow()

    /** Поток подтверждённых сервером мутаций для синхронизации SoundCloud-вкладки. */
    private val _mutations = MutableSharedFlow<LibraryMutation>(extraBufferCapacity = 32)
    val mutations: SharedFlow<LibraryMutation> = _mutations.asSharedFlow()

    /** Одноразовые сообщения для пользователя (показываются тостом из Activity). */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    /**
     * Действие, которое нужно повторить после прохождения капчи SoundCloud
     * (например, лайк, отклонённый с `403`). Хранится только последнее.
     */
    @Volatile
    private var pendingCaptchaRetry: (() -> Unit)? = null

    /**
     * Откуда сейчас играет очередь: рекомендации, поиск, плейлист, артист и т.п.
     * Показывается в топбаре полноэкранного плеера, когда текст песни скрыт.
     */
    private val _playbackContext = MutableStateFlow<String?>(null)
    val playbackContext: StateFlow<String?> = _playbackContext.asStateFlow()

    fun postUserMessage(message: String) {
        _userMessages.tryEmit(message)
    }

    /** Скрывает плашку капчи и забывает отложенный повтор, чтобы он не сработал позже. */
    fun dismissCaptchaPrompt() {
        pendingCaptchaRetry = null
        SessionManager.dismissCaptchaPrompt()
    }

    // App Navigation & UI State
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    /** Локальные настройки (тема и т.п.), синхронизированы с SettingsSheet. */
    private val settingsPrefs = SettingsPreferences(application)

    /** Текущий режим темы: тёмная / светлая / системная. */
    private val _themeMode = MutableStateFlow(settingsPrefs.themeMode)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** Маленькие комментарии поверх обложки, привязанные к таймкоду. */
    private val _onScreenCommentsEnabled = MutableStateFlow(settingsPrefs.onScreenComments)
    val onScreenCommentsEnabled: StateFlow<Boolean> = _onScreenCommentsEnabled.asStateFlow()

    fun toggleOnScreenComments() {
        val next = !_onScreenCommentsEnabled.value
        settingsPrefs.onScreenComments = next
        _onScreenCommentsEnabled.value = next
    }

    /** Выбранный шрифт интерфейса (по умолчанию — системный). */
    private val _fontMode = MutableStateFlow(settingsPrefs.fontMode)
    val fontMode: StateFlow<FontMode> = _fontMode.asStateFlow()

    /** Пользовательский шрифт: URI исходного файла и имя для подписи в настройках. */
    private val _customFont = MutableStateFlow(
        CustomFontInfo(settingsPrefs.customFontUri, settingsPrefs.customFontName)
    )
    val customFont: StateFlow<CustomFontInfo> = _customFont.asStateFlow()

    /**
     * Итоговая «тёмность» интерфейса.
     * Для системной темы берётся текущая конфигурация устройства.
     */
    private val _isDarkMode = MutableStateFlow(isDarkForMode(settingsPrefs.themeMode))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _isPlayerExpanded = MutableStateFlow(false)
    val isPlayerExpanded: StateFlow<Boolean> = _isPlayerExpanded.asStateFlow()

    // Home / Официальный фид SoundCloud («mixed-selections») + стрим подписок
    private val _homeSections = MutableStateFlow<List<HomeSection>>(emptyList())
    val homeSections: StateFlow<List<HomeSection>> = _homeSections.asStateFlow()

    /**
     * Догруженные обложки для плашек фида без своей обложки.
     * Ключ — urn (для системных плейлистов) либо permalink плейлист
     * а, значение — URL обложки плейлиста или его первого трека.
     */
    private val _homeCoverOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val homeCoverOverrides: StateFlow<Map<String, String>> = _homeCoverOverrides.asStateFlow()
    private val failedCoverKeys: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val _isHomeLoading = MutableStateFlow(false)
    val isHomeLoading: StateFlow<Boolean> = _isHomeLoading.asStateFlow()

    // Feed / Лента подписок SoundCloud (`GET /stream` с курсором)
    private val _feedActivities = MutableStateFlow<List<ScFeedActivity>>(emptyList())
    val feedActivities: StateFlow<List<ScFeedActivity>> = _feedActivities.asStateFlow()

    private val _isFeedLoading = MutableStateFlow(false)
    val isFeedLoading: StateFlow<Boolean> = _isFeedLoading.asStateFlow()

    private val _isFeedLoadingMore = MutableStateFlow(false)
    val isFeedLoadingMore: StateFlow<Boolean> = _isFeedLoadingMore.asStateFlow()

    private val _feedHasMore = MutableStateFlow(false)
    val feedHasMore: StateFlow<Boolean> = _feedHasMore.asStateFlow()

    private val _feedError = MutableStateFlow<String?>(null)
    val feedError: StateFlow<String?> = _feedError.asStateFlow()

    private var feedNextHref: String? = null
    private var feedLoadedOnce = false
    /** Защита от применения ответа, пришедшего после выхода из аккаунта. */
    private var feedRequestVersion = 0

    // Feed / «Найти»: рекомендательная лента SoundCloud (вертикальный свайпер)
    private val _discoverTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val discoverTracks: StateFlow<List<TrackInfo>> = _discoverTracks.asStateFlow()

    private val _isDiscoverLoading = MutableStateFlow(false)
    val isDiscoverLoading: StateFlow<Boolean> = _isDiscoverLoading.asStateFlow()

    private val _isDiscoverLoadingMore = MutableStateFlow(false)
    val isDiscoverLoadingMore: StateFlow<Boolean> = _isDiscoverLoadingMore.asStateFlow()

    private val _discoverHasMore = MutableStateFlow(true)
    val discoverHasMore: StateFlow<Boolean> = _discoverHasMore.asStateFlow()

    private val _discoverError = MutableStateFlow<String?>(null)
    val discoverError: StateFlow<String?> = _discoverError.asStateFlow()

    private var discoverLoadedOnce = false
    private var discoverRequestVersion = 0
    private var discoverGenreIndex = 0
    private var discoverEmptyPages = 0

    // Search State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchCategory = MutableStateFlow(SearchCategory.ALL)
    val searchCategory: StateFlow<SearchCategory> = _searchCategory.asStateFlow()

    private val _searchResults = MutableStateFlow(SearchResults(emptyList(), emptyList(), emptyList(), emptyList(), false))
    val searchResults: StateFlow<SearchResults> = _searchResults.asStateFlow()

    private val _searchUiState = MutableStateFlow(SearchUiState())
    val searchUiState: StateFlow<SearchUiState> = _searchUiState.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Номер актуального поиска: ответы устаревших запросов отбрасываются.
    private var searchRequestVersion = 0

    // ---------- История поиска ----------
    // Отдельный prefs-файл: история не должна зависеть от настроек и должна
    // переживать перезапуск процесса.
    private val searchHistoryPrefs = app.getSharedPreferences(
        "search_history",
        android.content.Context.MODE_PRIVATE
    )
    private val searchHistoryGson = Gson()
    private val _searchHistory = MutableStateFlow(loadSearchHistory())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private fun loadSearchHistory(): List<String> {
        val raw = searchHistoryPrefs.getString("queries", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed: List<String>? = searchHistoryGson.fromJson(raw, type)
            val seen = mutableSetOf<String>()
            parsed.orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filter { seen.add(it.lowercase()) }
                .take(SEARCH_HISTORY_LIMIT)
        } catch (e: Exception) {
            Log.w("ZYRO_PLAYER", "Search history restore failed", e)
            emptyList()
        }
    }

    private fun persistSearchHistory(history: List<String>) {
        _searchHistory.value = history
        // commit(), а не apply(): история должна быть на диске сразу, иначе
        // пересозданный ViewModel может прочитать старый (пустой) список.
        searchHistoryPrefs.edit()
            .putString("queries", searchHistoryGson.toJson(history))
            .commit()
    }

    /**
     * Запоминает запрос после отправки поиска: последний — сверху, дубли
     * (без учёта регистра) не копятся. Ссылки SoundCloud в историю не пишем —
     * они длинные и не переиспользуются как поисковые фразы.
     */
    fun rememberSearchQuery(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        if (normalized.contains("soundcloud.com/", ignoreCase = true)) return
        if (normalized.length > 120) return
        val updated = (
            listOf(normalized) +
                _searchHistory.value.filterNot { it.equals(normalized, ignoreCase = true) }
            ).take(SEARCH_HISTORY_LIMIT)
        persistSearchHistory(updated)
    }

    fun removeSearchHistory(query: String) {
        persistSearchHistory(_searchHistory.value.filterNot { it == query })
    }

    fun clearSearchHistory() {
        persistSearchHistory(emptyList())
    }

    // Room Flows
    val likedTracks: StateFlow<List<TrackEntity>> = db.trackDao().getLikedTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val customPlaylists: StateFlow<List<PlaylistEntity>> = db.playlistDao().getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val offlineTracks: StateFlow<List<TrackEntity>> = db.trackDao().getOfflineTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Офлайн-коллекции (плейлисты/альбомы), сохранённые пользователем. */
    val offlineCollections: StateFlow<List<OfflineCollectionEntity>> =
        offlineCollectionDao.getCollections()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Состав офлайн-коллекций: какие треки входили в плейлист/альбом. */
    val offlineCollectionTracks: StateFlow<List<OfflineCollectionTrackCrossRef>> =
        offlineCollectionDao.getAllCollectionTracks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _offlineDownloadState = MutableStateFlow(OfflineDownloadState())
    val offlineDownloadState: StateFlow<OfflineDownloadState> =
        _offlineDownloadState.asStateFlow()

    private var offlineDownloadJob: Job? = null

    val historyTracks: StateFlow<List<ListeningHistoryEntity>> = db.historyDao().getListeningHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val likedArtists: StateFlow<List<LikedArtistEntity>> = db.favoritesDao().getLikedArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val likedAlbums: StateFlow<List<LikedAlbumEntity>> = db.favoritesDao().getLikedAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val likedPlaylists: StateFlow<List<LikedPlaylistEntity>> = db.favoritesDao().getLikedPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Обложки локальных плейлистов, у которых нет своей картинки: берём
     * artwork первого добавленного трека. Обновляется при изменении
     * состава плейлистов (cross-ref) и обложек треков.
     */
    val localPlaylistCovers: StateFlow<Map<Long, String>> = db.playlistDao()
        .observeFirstTrackArtworks()
        .map { rows ->
            rows.filter { it.artworkUrl.isNotBlank() && it.artworkUrl != "null" }
                .associate { it.playlistId to it.artworkUrl }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Active Lyrics State
    private val _currentLyrics = MutableStateFlow<LyricsEntity?>(null)
    val currentLyrics: StateFlow<LyricsEntity?> = _currentLyrics.asStateFlow()

    private val _isLyricsLoading = MutableStateFlow(false)
    val isLyricsLoading: StateFlow<Boolean> = _isLyricsLoading.asStateFlow()

    // Modal / Detail States
    private val _activeAlbumDetail = MutableStateFlow<Pair<AlbumInfo, List<TrackInfo>>?>(null)
    val activeAlbumDetail: StateFlow<Pair<AlbumInfo, List<TrackInfo>>?> = _activeAlbumDetail.asStateFlow()

    private var albumDetailJob: Job? = null

    private val _isAlbumRefreshing = MutableStateFlow(false)
    val isAlbumRefreshing: StateFlow<Boolean> = _isAlbumRefreshing.asStateFlow()

    private val _activePlaylistDetail = MutableStateFlow<Pair<PlaylistInfo, List<TrackInfo>>?>(null)
    val activePlaylistDetail: StateFlow<Pair<PlaylistInfo, List<TrackInfo>>?> = _activePlaylistDetail.asStateFlow()

    private var playlistDetailJob: Job? = null

    private val _isPlaylistRefreshing = MutableStateFlow(false)
    val isPlaylistRefreshing: StateFlow<Boolean> = _isPlaylistRefreshing.asStateFlow()

    /** Локальный (созданный в Zyro) плейлист: сущность Room + её треки. */
    private val _activeLocalPlaylistDetail =
        MutableStateFlow<Pair<PlaylistEntity, List<TrackInfo>>?>(null)
    val activeLocalPlaylistDetail: StateFlow<Pair<PlaylistEntity, List<TrackInfo>>?> =
        _activeLocalPlaylistDetail.asStateFlow()

    private var localPlaylistTracksJob: Job? = null

    private val _activeArtistDetail = MutableStateFlow<ArtistFullData?>(null)
    val activeArtistDetail: StateFlow<ArtistFullData?> = _activeArtistDetail.asStateFlow()

    /** true, пока данные артиста догружаются после мгновенного открытия окна */
    private val _isArtistDetailLoading = MutableStateFlow(false)
    val isArtistDetailLoading: StateFlow<Boolean> = _isArtistDetailLoading.asStateFlow()

    /** true, пока идёт pull-to-refresh уже открытого профиля артиста. */
    private val _isArtistRefreshing = MutableStateFlow(false)
    val isArtistRefreshing: StateFlow<Boolean> = _isArtistRefreshing.asStateFlow()

    /** Задача загрузки артиста — отменяем при открытии другого / закрытии */
    private var artistDetailJob: Job? = null

    /** Токен запроса, чтобы устаревший ответ не перетёр более новый */
    private var artistDetailToken = 0

    /** Импорт снапшота SoundCloud; более новый снапшот отменяет старый. */
    private var likesImportJob: Job? = null
    private var followingsImportJob: Job? = null

    private val _activeTrackInfoDetail = MutableStateFlow<TrackInfo?>(null)
    val activeTrackInfoDetail: StateFlow<TrackInfo?> = _activeTrackInfoDetail.asStateFlow()

    /** true пока грузится artist / album / playlist detail */
    private val _isDetailLoading = MutableStateFlow(false)
    val isDetailLoading: StateFlow<Boolean> = _isDetailLoading.asStateFlow()

    private val _detailLoadingLabel = MutableStateFlow("")
    val detailLoadingLabel: StateFlow<String> = _detailLoadingLabel.asStateFlow()

    init {
        // Если процесс умер во время музыки, сервис мог успеть поднять
        // воспроизведение. Если нет — просто показываем восстановленную
        // очередь и не включаем звук без действия пользователя.
        playerManager.onUiStarted()
        loadHomeFeed()
        observeCurrentTrackLyrics()

        // Один раз за запуск чистим офлайн-хранилище: обрывы загрузок (*.part),
        // записи с исчезнувшими файлами и осиротевшие файлы. Иначе такие треки
        // числятся скачанными, а плеер падает с `io unspecified`.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { downloader.cleanupOfflineStorage() }
                .onFailure { android.util.Log.e("ZYRO_PLAYER", "Offline cleanup failed", it) }
        }

        // После прохождения капчи повторяем действие, которое SoundCloud
        // отклонил с 403 (лайк трека/плейлиста).
        viewModelScope.launch {
            SessionManager.captchaSolved.collect {
                val retry = pendingCaptchaRetry
                pendingCaptchaRetry = null
                retry?.invoke()
            }
        }
    }

    fun setTab(index: Int) {
        closeArtistDetail()
        closeAlbumDetail()
        closePlaylistDetail()
        closeLocalPlaylistDetail()
        closeTrackInfo()
        if (_selectedTab.value == index) {
            if (index == 2) {
                onSearchQueryChange("")
            }
            if (index == 1 && authManager.isLoggedIn) {
                refreshFeed()
            }
        } else {
            _selectedTab.value = index
        }
    }

    fun toggleDarkMode() {
        setThemeMode(if (_isDarkMode.value) ThemeMode.LIGHT else ThemeMode.DARK)
    }

    /**
     * Применяет режим темы из онбординга / настроек.
     * Значение сразу пишется в SharedPreferences, поэтому SettingsSheet и онбординг не расходятся.
     */
    fun setThemeMode(mode: ThemeMode) {
        settingsPrefs.themeMode = mode
        _themeMode.value = mode
        _isDarkMode.value = isDarkForMode(mode)
    }

    /** Синхронно пересчитывает [isDarkMode] (нужно для SYSTEM при смене конфигурации). */
    fun refreshDarkMode() {
        _isDarkMode.value = isDarkForMode(_themeMode.value)
    }

    /** Применяет выбранный шрифт интерфейса и сохраняет его в SharedPreferences. */
    fun setFontMode(mode: FontMode) {
        settingsPrefs.fontMode = mode
        _fontMode.value = mode
    }

    /** Сохраняет выбранный пользователем файл шрифта и сразу переключает на него. */
    fun setCustomFont(uri: String, name: String) {
        settingsPrefs.customFontUri = uri
        settingsPrefs.customFontName = name
        _customFont.value = CustomFontInfo(uri, name)
        setFontMode(FontMode.CUSTOM)
    }

    private fun isDarkForMode(mode: ThemeMode): Boolean = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> {
            val uiMode = getApplication<Application>().resources.configuration.uiMode
            (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun setPlayerExpanded(expanded: Boolean) {
        _isPlayerExpanded.value = expanded
    }

    // ====================================================================
    // Главная: официальный фид SoundCloud
    // ====================================================================

    /** Кеш фида между сессиями (последние собранные секции). */
    private val homeFeedPrefs = app.getSharedPreferences("home_feed", android.content.Context.MODE_PRIVATE)
    private val homeFeedGson = Gson()

    /**
     * Версия формата кеша фида. 2 — обложки плейлистов больше не подменяются
     * аватаром автора, поэтому старый кеш с такими «обложками» нужно сбросить.
     */
    private val homeFeedCacheVersion = 2

    /**
     * Загружает данные главной:
     * 1) кеш прошлой загрузки — мгновенно рисует контент;
     * 2) стрим подписок («Новое от артистов, на которых вы подписаны»);
     * 3) официальный фид mixed-selections («Миксы», «Больше из того, что
     *    вам нравится», «В тренде по жанрам» и др.);
     * 4) гостевой fallback — чарты SoundCloud.
     */
    fun loadHomeFeed() {
        viewModelScope.launch {
            _isHomeLoading.value = true
            // Ручная загрузка главной — повод повторить неудачные запросы обложек.
            failedCoverKeys.clear()
            if (_homeSections.value.isEmpty()) {
                // Сначала отдаём кеш, чтобы главная не мигала пустотой.
                restoreHomeFeedFromCache()
            }
            // Восстанавливаем ранее догруженные обложки плашек.
            restoreHomeCoverOverrides()
            // Кеш фида мог быть показан без обложек — догружаем недостающие.
            fetchMissingHomeCovers(_homeSections.value)
            try {
                coroutineScope {
                    val streamDeferred = async {
                        when (val result = soundCloudRepo.getFollowedStreamTracks(limit = 20)) {
                            is Result.Success -> result.data
                            else -> emptyList()
                        }
                    }
                    val selectionsDeferred = async {
                        when (val result = soundCloudRepo.getHomeSelections(limit = 12)) {
                            is Result.Success -> result.data
                            else -> emptyList()
                        }
                    }

                    val streamTracks = streamDeferred.await()
                    val selections = selectionsDeferred.await()

                    val username = authManager.currentUsername.takeIf { it.isNotBlank() }
                    val sections = mutableListOf<HomeSection>()

                    if (streamTracks.isNotEmpty()) {
                        sections.add(
                            HomeSection(
                                id = "latest_from_following",
                                title = "Новое от артистов, на которых вы подписаны",
                                subtitle = "Свежее из подписок SoundCloud",
                                kind = HomeSectionKind.TRACKS,
                                isHighlight = true,
                                tracks = dedupeHomeTracks(streamTracks)
                            )
                        )
                    }

                    selections.forEachIndexed { index, sel ->
                        val (title, subtitle) = localizeHomeSelection(sel, username)
                        val kind = when {
                            sel.users.isNotEmpty() -> HomeSectionKind.ARTISTS
                            sel.playlists.isNotEmpty() -> HomeSectionKind.MIXES
                            else -> HomeSectionKind.TRACKS
                        }
                        sections.add(
                            HomeSection(
                                id = sel.id.ifBlank { "selection_$index" },
                                title = title,
                                subtitle = subtitle,
                                kind = kind,
                                tracks = dedupeHomeTracks(sel.tracks),
                                mixes = sel.playlists,
                                artists = sel.users
                            )
                        )
                    }

                    // Гостевой fallback: официальный чарт SoundCloud.
                    if (sections.isEmpty()) {
                        val chart = when (val result = soundCloudRepo.getChartTracks(kind = "top", limit = 20)) {
                            is Result.Success -> result.data
                            else -> emptyList()
                        }
                        if (chart.isNotEmpty()) {
                            sections.add(
                                HomeSection(
                                    id = "trending_chart",
                                    title = "В тренде на SoundCloud",
                                    subtitle = "Популярные треки прямо сейчас",
                                    kind = HomeSectionKind.TRACKS,
                                    tracks = chart
                                )
                            )
                        }
                    }

                    if (sections.isNotEmpty()) {
                        _homeSections.value = sections
                        saveHomeFeedToCache(sections)
                        // Догружаем обложки для миксов/плейлистов без своей обложки.
                        fetchMissingHomeCovers(sections)
                    }
                }
            } catch (e: Exception) {
                Log.e("ZYRO_PLAYER", "Home feed load failed", e)
                if (_homeSections.value.isEmpty()) {
                    // Совсем без сети/библиотеки — гостевой чарт как минимум что-то даёт.
                    restoreHomeFeedFromCache()
                }
            } finally {
                _isHomeLoading.value = false
            }
        }
    }

    // ====================================================================
    // ЛЕНТА ПОДПИСОК
    // ====================================================================

    /**
     * Загружает первую страницу ленты. Повторный заход на вкладку не
     * перезапрашивает сервер: обновление — свайпом вниз или кнопкой.
     */
    fun loadFeed(force: Boolean = false) {
        if (_isFeedLoading.value || _isFeedLoadingMore.value) return
        if (!force && feedLoadedOnce) return
        val version = ++feedRequestVersion
        _isFeedLoading.value = true
        _feedError.value = null
        viewModelScope.launch {
            when (val result = libraryRepo.fetchFeedPage(limit = FEED_PAGE_SIZE)) {
                is Result.Success -> {
                    if (version == feedRequestVersion) {
                        _feedActivities.value = result.data.activities
                        feedNextHref = result.data.nextHref
                        _feedHasMore.value = result.data.nextHref != null
                        feedLoadedOnce = true
                    }
                }
                is Result.Error -> {
                    if (version == feedRequestVersion) {
                        _feedError.value = result.message
                        if (_feedActivities.value.isEmpty()) feedLoadedOnce = false
                    }
                }
                else -> Unit
            }
            if (version == feedRequestVersion) {
                _isFeedLoading.value = false
            }
        }
    }

    /** Принудительное обновление ленты (pull-to-refresh / повторное нажатие вкладки). */
    fun refreshFeed() {
        loadFeed(force = true)
    }

    /** Догружает следующую страницу по курсору `next_href`. */
    fun loadMoreFeed() {
        val nextHref = feedNextHref ?: return
        if (_isFeedLoading.value || _isFeedLoadingMore.value) return
        val version = ++feedRequestVersion
        _isFeedLoadingMore.value = true
        viewModelScope.launch {
            _feedError.value = null
            when (val result = libraryRepo.fetchFeedPage(nextHref = nextHref, limit = FEED_PAGE_SIZE)) {
                is Result.Success -> {
                    if (version == feedRequestVersion) {
                        val merged = (_feedActivities.value + result.data.activities)
                            .distinctBy { it.id }
                        _feedActivities.value = merged
                        // Если сервер вернул тот же курсор, останавливаемся,
                        // иначе бесконечная автодогрузка будет повторять страницу.
                        val newNext = result.data.nextHref?.takeUnless { it == nextHref }
                        feedNextHref = newNext
                        _feedHasMore.value = newNext != null
                    }
                }
                is Result.Error -> {
                    if (version == feedRequestVersion) {
                        _feedError.value = result.message
                    }
                }
                else -> Unit
            }
            if (version == feedRequestVersion) {
                _isFeedLoadingMore.value = false
            }
        }
    }

    /** Сбрасывает ленту после выхода из аккаунта. */
    fun clearFeed() {
        feedRequestVersion++
        _feedActivities.value = emptyList()
        feedNextHref = null
        feedLoadedOnce = false
        _feedHasMore.value = false
        _feedError.value = null
        _isFeedLoading.value = false
        _isFeedLoadingMore.value = false
    }

    // ====================================================================
    // ЛЕНТА «НАЙТИ»: рекомендации SoundCloud (TikTok-режим)
    // ====================================================================

    /**
     * Загружает первую порцию рекомендаций: персональный фид SoundCloud
     * (если пользователь авторизован) + официальные чарты. Гостям чарты
     * доступны без токена, поэтому секция «Найти» работает всегда.
     *
     * Повторный вызов без [force] ничего не делает: обновление — кнопкой
     * в шапке или свайпом вниз/по кнопке.
     */
    fun loadDiscover(force: Boolean = false) {
        if (_isDiscoverLoading.value || _isDiscoverLoadingMore.value) return
        if (!force && discoverLoadedOnce) return
        val version = ++discoverRequestVersion
        _isDiscoverLoading.value = true
        _discoverError.value = null
        viewModelScope.launch {
            try {
                val personalized = coroutineScope {
                    val personalizedDeferred = async {
                        if (!authManager.isLoggedIn) return@async emptyList<TrackInfo>()
                        when (val result = soundCloudRepo.getHomeSelections(limit = 12)) {
                            is Result.Success -> result.data.flatMap { it.tracks }
                            else -> emptyList()
                        }
                    }
                    val chartTopDeferred = async {
                        when (
                            val result = soundCloudRepo.getChartTracks(
                                kind = "top",
                                genre = "soundcloud:genres:all-music",
                                limit = 40
                            )
                        ) {
                            is Result.Success -> result.data
                            else -> emptyList()
                        }
                    }
                    val chartTrendingDeferred = async {
                        when (
                            val result = soundCloudRepo.getChartTracks(
                                kind = "trending",
                                genre = "soundcloud:genres:all-music",
                                limit = 30
                            )
                        ) {
                            is Result.Success -> result.data
                            else -> emptyList()
                        }
                    }
                    personalizedDeferred.await() + chartTopDeferred.await() +
                        chartTrendingDeferred.await()
                }

                if (version != discoverRequestVersion) return@launch

                val tracks = dedupeHomeTracks(personalized).shuffled()
                if (tracks.isEmpty()) {
                    _discoverError.value = "Не удалось загрузить рекомендации"
                    discoverLoadedOnce = false
                } else {
                    _discoverTracks.value = tracks
                    discoverLoadedOnce = true
                    discoverGenreIndex = 0
                    discoverEmptyPages = 0
                    _discoverHasMore.value = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == discoverRequestVersion) {
                    Log.e("ZYRO_PLAYER", "Discover feed load failed", e)
                    _discoverError.value = "Не удалось загрузить рекомендации"
                    discoverLoadedOnce = false
                }
            } finally {
                if (version == discoverRequestVersion) {
                    _isDiscoverLoading.value = false
                }
            }
        }
    }

    /** Принудительное обновление рекомендаций. */
    fun refreshDiscover() {
        loadDiscover(force = true)
    }

    /**
     * Догружает следующую порцию рекомендаций из чартов по кругу жанров.
     * Новые треки добавляются в конец очереди свайпера.
     */
    fun loadMoreDiscover() {
        if (_isDiscoverLoading.value || _isDiscoverLoadingMore.value) return
        if (!discoverLoadedOnce) {
            loadDiscover()
            return
        }
        if (!_discoverHasMore.value) return
        val version = discoverRequestVersion
        _isDiscoverLoadingMore.value = true
        _discoverError.value = null
        viewModelScope.launch {
            try {
                val genre = DISCOVER_GENRES[discoverGenreIndex % DISCOVER_GENRES.size]
                val kind = if (discoverGenreIndex % 2 == 0) "top" else "trending"
                discoverGenreIndex++

                when (val result = soundCloudRepo.getChartTracks(kind = kind, genre = genre, limit = 30)) {
                    is Result.Success -> {
                        if (version == discoverRequestVersion) {
                            val existing = _discoverTracks.value
                            val knownUrls = existing.mapTo(HashSet()) { it.originalUrl }
                            val additions = dedupeHomeTracks(result.data)
                                .filter { it.originalUrl !in knownUrls }
                            if (additions.isEmpty()) {
                                discoverEmptyPages++
                                if (discoverEmptyPages >= 3) _discoverHasMore.value = false
                            } else {
                                discoverEmptyPages = 0
                                _discoverTracks.value = existing + additions
                            }
                        }
                    }
                    is Result.Error -> {
                        if (version == discoverRequestVersion) {
                            _discoverError.value = result.message
                            discoverEmptyPages++
                            if (discoverEmptyPages >= 3) _discoverHasMore.value = false
                        }
                    }
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == discoverRequestVersion) {
                    Log.e("ZYRO_PLAYER", "Discover feed load-more failed", e)
                    _discoverError.value = "Не удалось загрузить ещё треки"
                }
            } finally {
                if (version == discoverRequestVersion) {
                    _isDiscoverLoadingMore.value = false
                }
            }
        }
    }

    /** Полный сброс рекомендаций (выход из аккаунта / смена пользователя). */
    fun clearDiscover() {
        discoverRequestVersion++
        _discoverTracks.value = emptyList()
        discoverLoadedOnce = false
        discoverGenreIndex = 0
        discoverEmptyPages = 0
        _discoverHasMore.value = true
        _discoverError.value = null
        _isDiscoverLoading.value = false
        _isDiscoverLoadingMore.value = false
    }

    /** Убирает дубли по id и треки без permalink (такие не играются). */
    private fun dedupeHomeTracks(tracks: List<TrackInfo>): List<TrackInfo> =
        tracks
            .filter { it.originalUrl.isNotBlank() }
            .distinctBy { if (it.id != 0L) "id:${it.id}" else it.originalUrl }

    /**
     * Перевод названия секции на русский. У SoundCloud названия секций
     * приходят на английском независимо от локали — подставляем свои шаблоны.
     * Описания, которые уже пришли на русском (аккаунты с русской локалью),
     * используем как есть.
     */
    private fun localizeHomeSelection(sel: ScHomeSelection, username: String?): Pair<String, String> {
        val title = sel.title.trim()
        val lower = title.lowercase()
        val urnLower = sel.urn.lowercase()
        val idLower = sel.id.lowercase()

        val ruDescription = sel.description.takeIf { hasCyrillic(it) } ?: ""

        return when {
            // "Mixed for <name>" → «Миксы для <имя>»
            lower.startsWith("mixed for") || urnLower.contains("mixed-for") -> {
                val extracted = title.removePrefix("Mixed for").trim().trimEnd('…', ' ')
                val name = extracted.takeIf { it.isNotBlank() } ?: username.orEmpty()
                "Миксы для ${name.ifBlank { "вас" }}" to ruDescription.ifBlank { "Персональные ленты под ваше настроение" }
            }
            "more of what you like" in lower || "more_of_what_you_like" in idLower ->
                "Больше из того, что вам нравится" to ruDescription
            "made for you" in lower -> "Создано для вас" to ruDescription
            "trending" in lower -> "В тренде по жанрам" to ruDescription
            "artists to watch" in lower -> "Артисты, за которыми стоит следить" to ruDescription
            "curated by soundcloud" in lower || "created on soundcloud" in lower ->
                "Курируется SoundCloud" to ruDescription
            "latest" in lower && "follow" in lower ->
                "Новое от артистов, на которых вы подписаны" to ruDescription
            "liked by" in lower && "follow" in lower ->
                "Понравилось тем, на кого вы подписаны" to ruDescription
            "liked by" in lower -> "Понравилось слушателям" to ruDescription
            "stations" in lower -> "Открывайте через станции" to ruDescription
            else -> {
                // Название при этом может быть на русском (аккаунт с RU-локалью).
                if (hasCyrillic(title)) title to ruDescription else title to ""
            }
        }
    }

    private fun hasCyrillic(text: String): Boolean =
        text.any { it.code in 0x400..0x4FF }

    /**
     * На главной фид mixed-selections приходит без треков, поэтому у плашек
     * без собственной обложки обложку первого трека взять неоткуда. Догружаем
     * её в фоне: для системных плейлистов — запросом по urn, для обычных —
     * первым треком плейлиста. Результат копится в homeCoverOverrides,
     * чтобы каждый следующий рендер уже показывал настоящую обложку.
     */
    private fun fetchMissingHomeCovers(sections: List<HomeSection>) {
        val candidates = sections.asSequence()
            .flatMap { it.mixes.asSequence() }
            .filter { it.artworkUrl.isBlank() || it.artworkUrl == "null" }
            .map { it to (it.urn.ifBlank { it.permalinkUrl }) }
            .filter { (_, key) -> key.isNotBlank() }
            .filter { (mix, _) -> mix.urn.startsWith("soundcloud:system-playlists:") || mix.permalinkUrl.isNotBlank() }
            .filter { (_, key) -> key !in failedCoverKeys }
            .filter { (_, key) ->
                // В старых версиях сюда могла попасть строка "null" — считаем,
                // что обложки нет, и пробуем получить её заново.
                val existing = _homeCoverOverrides.value[key]
                existing.isNullOrBlank() || existing == "null"
            }
            .distinctBy { (_, key) -> key }
            .take(64)
            .toList()
        if (candidates.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            // По 4 запроса параллельно: обложки появляются быстро, но без
            // пачки из десятков одновременных обращений к API.
            candidates.chunked(4).forEach { chunk ->
                val results = coroutineScope {
                    chunk.map { (mix, key) ->
                        async { key to fetchHomeMixCover(mix) }
                    }.map { it.await() }
                }
                results.forEach { (key, cover) ->
                    if (cover != null) {
                        _homeCoverOverrides.update { it + (key to cover) }
                        saveHomeCoverOverrides()
                    } else {
                        // Неудачные не добиваем повторно, пока главную не обновят вручную.
                        failedCoverKeys.add(key)
                    }
                }
            }
        }
    }

    /** Обложка плейлиста либо первого его трека — как делает сам SoundCloud. */
    private suspend fun fetchHomeMixCover(playlist: PlaylistInfo): String? = try {
        // Сначала — треки, уже сохранённые в кеше библиотеки (плейлист могли
        // открывать раньше), и только потом сеть.
        val cacheKey = playlist.permalinkUrl.ifBlank { playlist.urn }
        val cover = libraryRepo.getCachedPlaylistTracks(cacheKey)
            ?.asSequence()
            ?.map { it.artworkUrl }
            ?.firstOrNull { it.isNotBlank() && it != "null" }
            ?: if (playlist.urn.startsWith("soundcloud:system-playlists:")) {
                when (val result = soundCloudRepo.getSystemPlaylist(playlist.urn)) {
                    is Result.Success -> result.data.playlist.artworkUrl.ifBlank {
                        result.data.tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl
                    }
                    else -> null
                }
            } else {
                when (val result = soundCloudRepo.getPlaylistTracks(playlist.permalinkUrl)) {
                    is Result.Success -> result.data.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl
                    else -> null
                }
            }
        // API отдаёт JSON null как строку "null" — такая «обложка» не грузится.
        cover?.takeUnless { it.isBlank() || it == "null" }
    } catch (e: Exception) {
        Log.w("ZYRO_PLAYER", "Home cover fetch failed: ${playlist.title}", e)
        null
    }

    private fun saveHomeCoverOverrides() {
        try {
            val json = homeFeedGson.toJson(_homeCoverOverrides.value)
            homeFeedPrefs.edit().putString("cover_overrides", json).apply()
        } catch (e: Exception) {
            Log.w("ZYRO_PLAYER", "Home cover overrides save failed", e)
        }
    }

    private fun restoreHomeCoverOverrides() {
        try {
            val json = homeFeedPrefs.getString("cover_overrides", null) ?: return
            val type = object : TypeToken<Map<String, String>>() {}.type
            val restored: Map<String, String> = homeFeedGson.fromJson(json, type) ?: return
            // В старых версиях сюда попадала строка "null" (JSON null от API) —
            // выкидываем её, чтобы обложки запросились заново.
            val cleaned = restored.filterValues { it.isNotBlank() && it != "null" }
            if (cleaned.isNotEmpty()) {
                _homeCoverOverrides.update { current -> cleaned + current }
            }
            if (cleaned.size != restored.size) saveHomeCoverOverrides()
        } catch (e: Exception) {
            Log.w("ZYRO_PLAYER", "Home cover overrides restore failed", e)
        }
    }

    private fun saveHomeFeedToCache(sections: List<HomeSection>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = homeFeedGson.toJson(sections)
                homeFeedPrefs.edit()
                    .putString("last_feed", json)
                    .putInt("feed_version", homeFeedCacheVersion)
                    .apply()
            } catch (e: Exception) {
                Log.w("ZYRO_PLAYER", "Home feed cache save failed", e)
            }
        }
    }

    private fun restoreHomeFeedFromCache() {
        try {
            if (homeFeedPrefs.getInt("feed_version", 1) < homeFeedCacheVersion) {
                homeFeedPrefs.edit()
                    .remove("last_feed")
                    .putInt("feed_version", homeFeedCacheVersion)
                    .apply()
                return
            }
            val json = homeFeedPrefs.getString("last_feed", null) ?: return
            val type = object : TypeToken<List<HomeSection>>() {}.type
            val cached: List<HomeSection> = homeFeedGson.fromJson(json, type) ?: return
            if (cached.isNotEmpty() && _homeSections.value.isEmpty()) {
                _homeSections.value = cached
            }
        } catch (e: Exception) {
            Log.w("ZYRO_PLAYER", "Home feed cache restore failed", e)
        }
    }

    /** Шаффл-запуск всех лайкнутых треков с плашки «Ваши лайки». */
    fun playShuffledLikedTracks() {
        val tracks = likedTracks.value
        if (tracks.isEmpty()) return
        val queue = tracks.map { it.toTrackInfo() }.shuffled()
        playTrack(queue.first(), queue, "Играют лайкнутые треки")
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        _searchUiState.value = _searchUiState.value.copy(activeQuery = query)
        if (query.isBlank()) {
            searchRequestVersion++
            _searchUiState.value = SearchUiState()
            _searchResults.value = SearchResults(emptyList(), emptyList(), emptyList(), emptyList(), false)
        }
    }

    fun performSearch() {
        val q = _searchQuery.value.trim()
        if (q.isBlank()) return
        rememberSearchQuery(q)
        val version = ++searchRequestVersion
        viewModelScope.launch {
            // Сразу очищаем прошлую выдачу: иначе старые треки остаются
            // в списке и скролл может дёрнуть loadMore для прежнего запроса.
            _searchUiState.value = SearchUiState(activeQuery = q, isLoading = true)
            _searchResults.value = SearchResults(emptyList(), emptyList(), emptyList(), emptyList(), false)
            _isSearching.value = true

            try {
                if (q.contains("soundcloud.com/")) {
                    val directResult = soundCloudRepo.getTrackInfo(q)
                    if (version != searchRequestVersion) return@launch
                    if (directResult is Result.Success) {
                        val directTrack = directResult.data
                        val res = SearchResults(listOf(directTrack), emptyList(), emptyList(), emptyList(), false)
                        _searchResults.value = res
                        _searchUiState.value = SearchUiState(
                            activeQuery = q,
                            isLoading = false,
                            hasMore = false,
                            tracks = listOf(directTrack)
                        )
                        return@launch
                    }
                }

                val result = soundCloudRepo.search(q, offset = 0, limit = SEARCH_PAGE_SIZE)
                if (version != searchRequestVersion) return@launch
                if (result is Result.Success) {
                    val res = result.data
                    _searchResults.value = res
                    _searchUiState.value = SearchUiState(
                        activeQuery = q,
                        isLoading = false,
                        // hasMore считаем по размеру всей страницы: в общем
                        // поиске на 20 записей приходится меньше 20 треков.
                        hasMore = res.hasMore,
                        tracks = res.tracks,
                        users = res.users,
                        albums = res.albums,
                        playlists = res.playlists,
                        offset = SEARCH_PAGE_SIZE
                    )
                } else {
                    _searchUiState.value = _searchUiState.value.copy(isLoading = false)
                }
            } finally {
                if (version == searchRequestVersion) {
                    _isSearching.value = false
                    _searchUiState.value = _searchUiState.value.copy(isLoading = false)
                }
            }
        }
    }

    fun loadMore() {
        val current = _searchUiState.value
        if (current.isLoadingMore || current.isLoading || !current.hasMore ||
            current.activeQuery.isBlank()
        ) {
            return
        }
        val version = searchRequestVersion
        viewModelScope.launch {
            _searchUiState.value = current.copy(isLoadingMore = true)
            val result = soundCloudRepo.search(
                current.activeQuery,
                offset = current.offset,
                limit = SEARCH_PAGE_SIZE
            )
            if (version != searchRequestVersion) return@launch
            if (result is Result.Success) {
                val res = result.data
                val latest = _searchUiState.value
                val combinedTracks = (latest.tracks + res.tracks).distinctBy { it.originalUrl }
                val combinedUsers = (latest.users + res.users).distinctBy { it.permalinkUrl }
                val combinedAlbums = (latest.albums + res.albums).distinctBy { it.permalinkUrl }
                val combinedPlaylists =
                    (latest.playlists + res.playlists).distinctBy { it.permalinkUrl }

                // Страница может принести только артистов/плейлисты без новых
                // треков — это не конец выдачи. Останавливаемся, лишь когда
                // страница целиком повторяет уже показанное.
                val hasNewItems = combinedTracks.size > latest.tracks.size ||
                    combinedUsers.size > latest.users.size ||
                    combinedAlbums.size > latest.albums.size ||
                    combinedPlaylists.size > latest.playlists.size
                val hasMore = res.hasMore && hasNewItems

                _searchResults.value = SearchResults(
                    combinedTracks,
                    combinedUsers,
                    combinedAlbums,
                    combinedPlaylists,
                    hasMore
                )
                _searchUiState.value = latest.copy(
                    isLoadingMore = false,
                    hasMore = hasMore,
                    tracks = combinedTracks,
                    users = combinedUsers,
                    albums = combinedAlbums,
                    playlists = combinedPlaylists,
                    offset = latest.offset + SEARCH_PAGE_SIZE
                )
            } else {
                _searchUiState.value = _searchUiState.value.copy(isLoadingMore = false)
            }
        }
    }

    fun setSearchCategory(category: SearchCategory) {
        _searchCategory.value = category
    }

    // Player controls wrapper
    @RequiresApi(Build.VERSION_CODES.O)
    fun playTrack(
        track: TrackInfo,
        queue: List<TrackInfo> = emptyList(),
        playbackContext: String? = null
    ) {
        _playbackContext.value = playbackContext?.takeIf { it.isNotBlank() }
        playerManager.playTrack(track, queue, resetShuffle = true)
        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.trackDao().getTrackByUrl(track.originalUrl)
            if (existing == null) {
                db.trackDao().insertOrUpdateTrack(
                    TrackEntity(
                        originalUrl = track.originalUrl,
                        title = track.title,
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        streamUrl = track.streamUrl,
                        durationMs = track.durationMs,
                        soundcloudId = track.id
                    )
                )
            } else if (existing.soundcloudId == 0L && track.id != 0L) {
                // Запоминаем ID у трека, который раньше пришёл из локальной базы.
                db.trackDao().insertOrUpdateTrack(existing.copy(soundcloudId = track.id))
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun togglePlayPause() = playerManager.togglePlayPause()
    @RequiresApi(Build.VERSION_CODES.O)
    fun playNext() = playerManager.playNext()
    @RequiresApi(Build.VERSION_CODES.O)
    fun playPrevious() = playerManager.playPrevious()
    @RequiresApi(Build.VERSION_CODES.O)
    fun seekTo(pos: Long) = playerManager.seekTo(pos)
    @RequiresApi(Build.VERSION_CODES.O)
    fun toggleShuffle() = playerManager.toggleShuffle()
    @RequiresApi(Build.VERSION_CODES.O)
    fun toggleRepeat() = playerManager.toggleRepeat()

    // Queue methods
    @RequiresApi(Build.VERSION_CODES.O)
    fun toggleQueue(track: TrackInfo) = playerManager.toggleQueueItem(track)
    @RequiresApi(Build.VERSION_CODES.O)
    fun playNextInQueue(track: TrackInfo) {
        playerManager.playNextInQueue(track)
        viewModelScope.launch(Dispatchers.IO) {
            interactionLogger.log(track, InteractionLogger.ADD_QUEUE)
        }
    }
    @RequiresApi(Build.VERSION_CODES.O)
    fun removeFromQueue(index: Int) = playerManager.removeFromQueue(index)
    @RequiresApi(Build.VERSION_CODES.O)
    fun playQueueIndex(index: Int) = playerManager.playQueueIndex(index)
    @RequiresApi(Build.VERSION_CODES.O)
    fun clearQueue() = playerManager.clearQueue()

    /**
     * Оставлено для старых вызовов из поиска: лайк всегда идёт через
     * синхронизацию с SoundCloud, а не только в локальную БД.
     */
    fun toggleLikeTrack(track: TrackInfo) = toggleLikeTrackWithSync(track)

    fun downloadTrackForOffline(track: TrackInfo) {
        enqueueOfflineDownload(
            tracks = listOf(track),
            title = track.title.ifBlank { "Трек" },
            collectionKey = null,
            collection = null
        )
    }

    /** Скачивает все любимые треки и показывает прогресс в уведомлении. */
    fun downloadLikedTracksForOffline() {
        val tracks = likedTracks.value.map { it.toTrackInfo() }
        enqueueOfflineDownload(
            tracks = tracks,
            title = "Любимые треки",
            collectionKey = OfflineCollectionKeys.LIKES,
            collection = null
        )
    }

    /** Скачивает все треки альбома и запоминает его как офлайн-коллекцию. */
    fun downloadAlbumForOffline(album: AlbumInfo) {
        if (album.permalinkUrl.isBlank()) {
            postUserMessage("Не удалось открыть альбом для скачивания")
            return
        }
        viewModelScope.launch {
            val result = soundCloudRepo.getAlbumTracks(album.permalinkUrl)
            if (result !is Result.Success || result.data.isEmpty()) {
                postUserMessage("Не удалось получить треки альбома")
                return@launch
            }
            val tracks = result.data
            val collection = OfflineCollectionEntity(
                collectionKey = OfflineCollectionKeys.album(album.permalinkUrl),
                type = OfflineCollectionKeys.TYPE_ALBUM,
                title = album.title,
                subtitle = buildString {
                    if (album.artist.isNotBlank()) append(album.artist)
                    if (album.trackCount > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${album.trackCount} треков")
                    }
                },
                artworkUrl = album.artworkUrl,
                permalinkUrl = album.permalinkUrl,
                trackCount = album.trackCount.takeIf { it > 0 } ?: tracks.size
            )
            enqueueOfflineDownload(
                tracks = tracks,
                title = "Альбом «${album.title}»",
                collectionKey = collection.collectionKey,
                collection = collection
            )
        }
    }

    /** Скачивает все треки серверного/лайкнутого плейлиста. */
    fun downloadPlaylistForOffline(playlist: PlaylistInfo) {
        if (playlist.permalinkUrl.isBlank()) {
            postUserMessage("Не удалось открыть плейлист для скачивания")
            return
        }
        viewModelScope.launch {
            val result = soundCloudRepo.getPlaylistTracks(playlist.permalinkUrl)
            if (result !is Result.Success || result.data.isEmpty()) {
                postUserMessage("Не удалось получить треки плейлиста")
                return@launch
            }
            val tracks = result.data
            val collection = OfflineCollectionEntity(
                collectionKey = OfflineCollectionKeys.playlist(playlist.permalinkUrl),
                type = OfflineCollectionKeys.TYPE_PLAYLIST,
                title = playlist.title,
                subtitle = buildString {
                    if (playlist.user.isNotBlank()) append(playlist.user)
                    if (playlist.trackCount > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${playlist.trackCount} треков")
                    }
                },
                artworkUrl = playlist.artworkUrl,
                permalinkUrl = playlist.permalinkUrl,
                trackCount = playlist.trackCount.takeIf { it > 0 } ?: tracks.size
            )
            enqueueOfflineDownload(
                tracks = tracks,
                title = "Плейлист «${playlist.title}»",
                collectionKey = collection.collectionKey,
                collection = collection
            )
        }
    }

    /** Скачивает локальный плейлист из Room. */
    fun downloadLocalPlaylistForOffline(playlist: PlaylistEntity) {
        viewModelScope.launch {
            val entities = withContext(Dispatchers.IO) {
                db.playlistDao().getTracksForPlaylistOnce(playlist.playlistId)
            }
            if (entities.isEmpty()) {
                postUserMessage("В плейлисте пока нет треков")
                return@launch
            }
            val collection = OfflineCollectionEntity(
                collectionKey = OfflineCollectionKeys.local(playlist.playlistId),
                type = OfflineCollectionKeys.TYPE_PLAYLIST,
                title = playlist.title,
                subtitle = "Локальный плейлист",
                artworkUrl = playlist.localCoverPath.ifBlank { playlist.coverUrl },
                trackCount = entities.size
            )
            enqueueOfflineDownload(
                tracks = entities.map { it.toTrackInfo() },
                title = "Плейлист «${playlist.title}»",
                collectionKey = collection.collectionKey,
                collection = collection
            )
        }
    }

    /**
     * Общий вход для офлайн-кеширования: фильтрует уже скачанное,
     * запускает последовательную загрузку и обновляет UI/уведомление.
     *
     * Сверка идёт напрямую с Room (а не с `offlineTracks.value`), потому что
     * Flow может быть ещё не собран на момент нажатия, а URL в SoundCloud
     * иногда отличается `http/https` или query-параметрами. Иначе приложение
     * заново «проходит» по всем уже скачанным трекам.
     */
    private fun enqueueOfflineDownload(
        tracks: List<TrackInfo>,
        title: String,
        collectionKey: String?,
        collection: OfflineCollectionEntity?
    ) {
        if (offlineDownloadJob?.isActive == true) {
            postUserMessage("Дождитесь завершения текущего кеширования")
            return
        }

        val unique = tracks
            .filter { it.originalUrl.isNotBlank() }
            .distinctBy { normalizeTrackUrl(it.originalUrl) }
        if (unique.isEmpty()) {
            postUserMessage("Нет треков для офлайн-кеша")
            return
        }

        val job = viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                db.trackDao().getOfflineTracksSnapshot()
            }
            val alreadyOffline = stored
                .filter { downloader.isValidOfflineFile(it) }
                .map { normalizeTrackUrl(it.originalUrl) }
                .toSet()
            val pending = unique.filter { normalizeTrackUrl(it.originalUrl) !in alreadyOffline }

            // Коллекцию регистрируем в любом случае: даже если все треки уже
            // скачаны, плейлист должен появиться в офлайн-разделе.
            if (collection != null) {
                registerOfflineCollection(collection, unique)
            }

            if (pending.isEmpty()) {
                postUserMessage("Все треки уже сохранены офлайн")
                return@launch
            }

            _offlineDownloadState.value = OfflineDownloadState(
                isActive = true,
                collectionKey = collectionKey,
                title = title,
                completed = 0,
                total = pending.size,
                currentTrackTitle = pending.first().title,
                currentTrackUrl = pending.first().originalUrl
            )
            offlineDownloadNotifier.update(_offlineDownloadState.value)

            var completed = 0
            var failed = 0
            try {
                for ((index, track) in pending.withIndex()) {
                    var attempts = 0
                    var result = OfflineDownloadResult.FAILED

                    while (attempts < MAX_RATE_LIMIT_RETRIES) {
                        _offlineDownloadState.value = _offlineDownloadState.value.copy(
                            currentTrackTitle = track.title,
                            currentTrackUrl = track.originalUrl,
                            currentTrackProgress = 0f
                        )
                        offlineDownloadNotifier.update(_offlineDownloadState.value)

                        result = downloader.downloadTrack(track.toTrackEntity()) { progress ->
                            _offlineDownloadState.value =
                                _offlineDownloadState.value.copy(currentTrackProgress = progress)
                        }

                        if (result != OfflineDownloadResult.RATE_LIMITED) break

                        attempts++
                        if (attempts < MAX_RATE_LIMIT_RETRIES) {
                            val pauseMs = RATE_LIMIT_PAUSE_MS * attempts
                            _offlineDownloadState.value = _offlineDownloadState.value.copy(
                                currentTrackTitle = "Пауза: лимит SoundCloud (429)",
                                currentTrackProgress = 0f
                            )
                            offlineDownloadNotifier.update(_offlineDownloadState.value)
                            delay(pauseMs)
                        }
                    }

                    if (result == OfflineDownloadResult.SUCCESS) {
                        completed++
                        // Аудио уже на диске — догружаем метаданные по настройкам
                        // офлайна (обложка, текст, комментарии).
                        cacheOfflineMetadata(track)
                    } else {
                        failed++
                        Log.w(
                            "ZYRO_PLAYER",
                            "Offline cache failed: ${track.title} (${track.originalUrl})"
                        )
                    }

                    _offlineDownloadState.value = _offlineDownloadState.value.copy(
                        completed = completed,
                        failed = failed,
                        currentTrackProgress = 0f
                    )
                    offlineDownloadNotifier.update(_offlineDownloadState.value)

                    // Небольшая пауза между треками: SoundCloud отдаёт 429,
                    // если resolve-запросы идут вплотную друг к другу.
                    if (index < pending.lastIndex) {
                        delay(TRACK_DOWNLOAD_GAP_MS)
                    }
                }
            } finally {
                val finished = _offlineDownloadState.value.copy(
                    isActive = false,
                    completed = completed,
                    failed = failed,
                    currentTrackTitle = "",
                    currentTrackUrl = "",
                    currentTrackProgress = 0f
                )
                _offlineDownloadState.value = finished
                offlineDownloadNotifier.finish(finished)

                if (pending.size == 1) {
                    postUserMessage(
                        if (completed == 1) "Трек сохранён офлайн"
                        else "Не удалось сохранить трек офлайн"
                    )
                } else {
                    postUserMessage(
                        if (failed == 0) "Офлайн-кеш готов: $completed из ${pending.size}"
                        else "Офлайн-кеш: $completed из ${pending.size}, ошибок: $failed"
                    )
                }
            }
        }

        offlineDownloadJob = job
        job.invokeOnCompletion { offlineDownloadJob = null }
    }

    /**
     * Приводит URL трека к виду, пригодному для сравнения «уже скачанных».
     * Отбрасывает query-параметры и различия `http/https`/регистра/слэша.
     */
    private fun normalizeTrackUrl(url: String): String =
        url.trim().substringBefore('?').trimEnd('/').lowercase()

    /**
     * Догружает метаданные к уже скачанному треку согласно настройкам
     * «Скачивание в офлайн». Ошибки не пробрасываются: аудио уже сохранено,
     * а без сети обложка/текст/комментарии просто не добавятся.
     */
    private suspend fun cacheOfflineMetadata(track: TrackInfo) {
        if (settingsPrefs.offlineArtwork && track.artworkUrl.isNotBlank()) {
            runCatching {
                // Coil кладёт изображение в общий дисковый кеш — оттуда оно
                // доступно и без сети, во всех местах, где показывается обложка.
                app.imageLoader.execute(
                    ImageRequest.Builder(app)
                        .data(track.artworkUrl)
                        .build()
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.d("ZYRO_PLAYER", "Artwork prefetch failed: ${e.message}")
            }
        }

        if (settingsPrefs.offlineLyrics) {
            runCatching {
                lyricsRepo.getLyrics(
                    trackUrl = track.originalUrl,
                    trackTitle = track.title,
                    artistName = track.artist,
                    durationMs = track.durationMs
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.d("ZYRO_PLAYER", "Lyrics prefetch failed: ${e.message}")
            }
        }

        if (settingsPrefs.offlineComments) {
            runCatching {
                val trackId = if (track.id > 0L) {
                    track.id
                } else {
                    (soundCloudRepo.resolveTrackId(track.originalUrl) as? Result.Success)?.data
                        ?: 0L
                }
                if (trackId <= 0L) return@runCatching
                when (
                    val result = soundCloudRepo.fetchTrackComments(
                        trackId = trackId,
                        offset = 0,
                        limit = settingsPrefs.offlineMaxComments
                    )
                ) {
                    is Result.Success -> offlineMetaStore.saveComments(
                        track.originalUrl,
                        result.data.comments
                    )
                    else -> Unit
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.d("ZYRO_PLAYER", "Comments prefetch failed: ${e.message}")
            }
        }
    }

    private suspend fun registerOfflineCollection(
        collection: OfflineCollectionEntity,
        tracks: List<TrackInfo>
    ) = withContext(Dispatchers.IO) {
        try {
            val unique = tracks
                .filter { it.originalUrl.isNotBlank() }
                .distinctBy { it.originalUrl }
            offlineCollectionDao.insertCollection(collection)
            offlineCollectionDao.clearCollectionTracks(collection.collectionKey)
            offlineCollectionDao.insertCollectionTracks(
                unique.mapIndexed { index, track ->
                    OfflineCollectionTrackCrossRef(
                        collectionKey = collection.collectionKey,
                        trackOriginalUrl = track.originalUrl,
                        position = index
                    )
                }
            )
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "registerOfflineCollection error", e)
        }
    }

    private fun TrackInfo.toTrackEntity() = TrackEntity(
        originalUrl = originalUrl,
        title = title,
        artist = artist,
        artworkUrl = artworkUrl,
        streamUrl = streamUrl,
        durationMs = durationMs,
        isLiked = isLiked,
        soundcloudId = id,
        likedTimestamp = likedAt
    )

    private fun TrackEntity.toTrackInfo() = TrackInfo(
        originalUrl = originalUrl,
        streamUrl = streamUrl,
        title = title,
        artist = artist,
        artworkUrl = artworkUrl,
        durationMs = durationMs,
        likesCount = likesCount,
        playbackCount = playbackCount,
        id = soundcloudId,
        isLiked = isLiked,
        likedAt = likedTimestamp
    )

    /**
     * Скачивает трек обычным аудиофайлом в системную папку «Загрузки».
     * Пользователь видит файл в файловом менеджере, он не привязан к приложению.
     */
    fun downloadTrackToDownloads(track: TrackInfo) {
        viewModelScope.launch {
            postUserMessage("Скачивание: ${track.title}")
            val result = downloader.downloadToPublicDownloads(track)
            postUserMessage(
                when (result) {
                    PublicDownloadResult.SUCCESS -> "Сохранено в Загрузки/Zyro"
                    PublicDownloadResult.UNSUPPORTED_STREAM ->
                        "Этот трек доступен только в потоковом режиме"
                    PublicDownloadResult.FAILED -> "Не удалось скачать трек"
                }
            )
        }
    }

    fun deleteOfflineTrack(trackUrl: String) {
        viewModelScope.launch {
            downloader.deleteOfflineTrack(trackUrl)
            offlineMetaStore.deleteComments(trackUrl)
            // Трек мог быть частью офлайн-коллекции — убираем его оттуда,
            // иначе в плейлисте останется «дырка».
            offlineCollectionDao.removeTrackRefs(trackUrl)
        }
    }

    /**
     * Удаляет скачивание целой коллекции (любимые треки, плейлист, альбом).
     * Файлы, которые используются другой коллекцией, не трогаем.
     */
    fun deleteOfflineCollection(collectionKey: String) {
        viewModelScope.launch {
            val all = offlineCollectionDao.getAllCollectionTracksOnce()
            val inCollection = all.filter { it.collectionKey == collectionKey }
                .map { it.trackOriginalUrl }
                .toSet()
            val usedElsewhere = all.filter { it.collectionKey != collectionKey }
                .map { it.trackOriginalUrl }
                .toSet()

            (inCollection - usedElsewhere).forEach {
                downloader.deleteOfflineTrack(it)
                offlineMetaStore.deleteComments(it)
            }
            offlineCollectionDao.clearCollectionTracks(collectionKey)
            offlineCollectionDao.deleteCollection(collectionKey)
            postUserMessage("Плейлист удалён из офлайна")
        }
    }

    /** Полная очистка офлайн-кеша — вызывается из настроек после подтверждения. */
    fun deleteAllOffline() {
        offlineDownloadJob?.cancel()
        viewModelScope.launch {
            val deleted = downloader.deleteAllOfflineTracks()
            offlineCollectionDao.clearAllCollectionTracks()
            offlineCollectionDao.clearAllCollections()
            offlineMetaStore.clearAll()
            _offlineDownloadState.value = OfflineDownloadState()
            offlineDownloadNotifier.finish(_offlineDownloadState.value)
            postUserMessage("Офлайн-кеш очищен: удалено файлов — $deleted")
        }
    }

    fun createPlaylist(title: String, description: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            db.playlistDao().insertPlaylist(PlaylistEntity(title = title, description = description))
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: TrackInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val existingTrack = db.trackDao().getTrackByUrl(track.originalUrl)
            if (existingTrack == null) {
                db.trackDao().insertOrUpdateTrack(
                    TrackEntity(
                        originalUrl = track.originalUrl,
                        title = track.title,
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        streamUrl = track.streamUrl,
                        durationMs = track.durationMs,
                        soundcloudId = track.id
                    )
                )
            }
            db.playlistDao().insertPlaylistTrack(PlaylistTrackCrossRef(playlistId = playlistId, trackOriginalUrl = track.originalUrl))
            // Если этот локальный плейлист открыт — сразу показываем трек в
            // списке, не дожидаясь повторного открытия окна.
            _activeLocalPlaylistDetail.value?.let { (playlist, tracks) ->
                if (playlist.playlistId == playlistId &&
                    tracks.none { it.originalUrl == track.originalUrl }
                ) {
                    // Новые треки в списке идут первыми (addedTimestamp DESC).
                    _activeLocalPlaylistDetail.value = playlist to (listOf(track) + tracks)
                }
            }
            val playlistName = db.playlistDao().getPlaylistById(playlistId)?.title
            postUserMessage(
                if (playlistName.isNullOrBlank()) "Трек добавлен в плейлист"
                else "Трек добавлен в плейлист «$playlistName»"
            )
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.playlistDao().removeTrackFromPlaylist(playlistId, trackUrl)
            // Если этот плейлист открыт — сразу убираем строку из модалки.
            _activeLocalPlaylistDetail.value?.let { (playlist, tracks) ->
                if (playlist.playlistId == playlistId) {
                    _activeLocalPlaylistDetail.value =
                        playlist to tracks.filterNot { it.originalUrl == trackUrl }
                }
            }
        }
    }

    fun deletePlaylist(playlist: PlaylistEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.playlistDao().deletePlaylist(playlist)
            // У cross-ref нет FK-каскада — чистим вручную.
            db.playlistDao().deletePlaylistTracks(playlist.playlistId)
            deleteLocalCover(playlist.localCoverPath)
        }
    }

    fun getTracksForPlaylistFlow(playlistId: Long): Flow<List<TrackEntity>> {
        return db.playlistDao().getTracksForPlaylist(playlistId)
    }

    /** Лайки в формате TrackInfo для экрана «Добавить из». */
    fun likedTracksForPicker(): List<TrackInfo> = likedTracks.value.map { it.toTrackInfo() }

    /**
     * Открывает локальный плейлист. Сразу показываем шапку, треки подгружаем
     * из Room — в отличие от серверного плейлиста, здесь нет сетевого запроса.
     */
    fun openLocalPlaylistDetail(playlist: PlaylistEntity) {
        closeNonLocalPlaylistDetails()
        localPlaylistTracksJob?.cancel()
        _isPlaylistRefreshing.value = false
        _activeLocalPlaylistDetail.value = playlist to emptyList()
        localPlaylistTracksJob = viewModelScope.launch(Dispatchers.IO) {
            val tracks = db.playlistDao()
                .getTracksForPlaylistOnce(playlist.playlistId)
                .map { it.toTrackInfo() }
            // Пока грузились треки, пользователь мог закрыть окно или открыть другой.
            _activeLocalPlaylistDetail.value?.let { (current, _) ->
                if (current.playlistId == playlist.playlistId) {
                    _activeLocalPlaylistDetail.value = current to tracks
                }
            }
        }
    }

    /** Pull-to-refresh локального плейлиста: перечитываем треки из Room. */
    fun refreshLocalPlaylistDetail() {
        val current = _activeLocalPlaylistDetail.value ?: return
        val playlist = current.first
        if (_isPlaylistRefreshing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isPlaylistRefreshing.value = true
            try {
                val tracks = db.playlistDao()
                    .getTracksForPlaylistOnce(playlist.playlistId)
                    .map { it.toTrackInfo() }
                _activeLocalPlaylistDetail.value?.let { (active, _) ->
                    if (active.playlistId == playlist.playlistId) {
                        _activeLocalPlaylistDetail.value = active to tracks
                    }
                }
            } finally {
                _isPlaylistRefreshing.value = false
            }
        }
    }

    fun closeLocalPlaylistDetail() {
        localPlaylistTracksJob?.cancel()
        localPlaylistTracksJob = null
        _isPlaylistRefreshing.value = false
        _activeLocalPlaylistDetail.value = null
    }

    fun updateLocalPlaylist(playlist: PlaylistEntity, draft: PlaylistDraft) {
        val cleanTitle = draft.title.trim().ifBlank { playlist.title }
        val cleanDescription = draft.description.trim()
        viewModelScope.launch(Dispatchers.IO) {
            val newCover = draft.coverUri?.let { saveLocalCover(it) }
            if (newCover != null) {
                deleteLocalCover(playlist.localCoverPath)
            }
            val updated = playlist.copy(
                title = cleanTitle,
                description = cleanDescription,
                localCoverPath = newCover ?: playlist.localCoverPath
            )
            db.playlistDao().insertPlaylist(updated)
            _activeLocalPlaylistDetail.value?.let { (current, tracks) ->
                if (current.playlistId == playlist.playlistId) {
                    _activeLocalPlaylistDetail.value = updated to tracks
                }
            }
        }
    }

    // ---------- Обложки плейлистов ----------

    private fun centerCropSquare(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        if (side <= 0) return bitmap
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        return Bitmap.createBitmap(bitmap, x, y, side, side)
    }

    /** Читает выбранную картинку, обрезает в квадрат и сжимает в JPEG. */
    private suspend fun readCoverJpeg(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val decoded = app.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@withContext null

            val square = centerCropSquare(decoded)
            if (square !== decoded) decoded.recycle()

            val maxSide = 1024
            val scaled = if (square.width > maxSide || square.height > maxSide) {
                val ratio = maxSide.toFloat() / maxOf(square.width, square.height)
                Bitmap.createScaledBitmap(
                    square,
                    (square.width * ratio).toInt().coerceAtLeast(1),
                    (square.height * ratio).toInt().coerceAtLeast(1),
                    true
                ).also { square.recycle() }
            } else {
                square
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            if (scaled !== square) scaled.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "readCoverJpeg error", e)
            null
        }
    }

    private suspend fun coverBase64(uri: Uri): String? =
        readCoverJpeg(uri)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    private suspend fun saveLocalCover(uri: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = readCoverJpeg(uri) ?: return@withContext null
        try {
            val dir = File(app.filesDir, "playlist_covers").apply { mkdirs() }
            val file = File(dir, "cover_${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (e: Exception) {
            Log.e("ZYRO_PLAYER", "saveLocalCover error", e)
            null
        }
    }

    private fun deleteLocalCover(path: String) {
        if (path.isBlank()) return
        runCatching {
            val file = File(path)
            if (file.parentFile?.name == "playlist_covers") file.delete()
        }
    }

    // ==================== СИНХРОНИЗАЦИЯ С SOUNDCLOUD ====================

    // ---------- Лайки треков ----------

    /**
     * Переключение лайка трека с синхронизацией с SoundCloud.
     * Сначала оптимистично обновляем локальное состояние, затем отправляем на сервер.
     * При ошибке откатываем локальное изменение.
     */
    fun toggleLikeTrackWithSync(track: TrackInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.trackDao().getTrackByUrl(track.originalUrl)
            // Источник истины: локальная запись, если она есть; иначе — состояние,
            // пришедшее из SoundCloud (isLiked в списке лайков).
            val wasLiked = existing?.isLiked ?: track.isLiked
            val newLikedState = !wasLiked

            // У треков из локальной библиотеки id часто 0 — без него старый код
            // просто не отправлял лайк, и на сайте ничего не появлялось.
            var trackId = track.id.takeIf { it != 0L }
                ?: existing?.soundcloudId
                ?: 0L
            if (authManager.isLoggedIn && trackId == 0L) {
                when (val resolved = soundCloudRepo.resolveTrackId(track.originalUrl)) {
                    is Result.Success -> trackId = resolved.data
                    else -> Unit
                }
            }

            val now = System.currentTimeMillis()
            val base = existing ?: TrackEntity(
                originalUrl = track.originalUrl,
                title = track.title,
                artist = track.artist,
                artworkUrl = track.artworkUrl,
                streamUrl = track.streamUrl,
                durationMs = track.durationMs,
                isLiked = wasLiked,
                soundcloudId = trackId,
                likedTimestamp = track.likedAt
            )
            val updated = base.copy(
                title = base.title.ifBlank { track.title },
                artist = base.artist.ifBlank { track.artist },
                artworkUrl = base.artworkUrl.ifBlank { track.artworkUrl },
                streamUrl = base.streamUrl.ifBlank { track.streamUrl },
                durationMs = if (base.durationMs > 0) base.durationMs else track.durationMs,
                isLiked = newLikedState,
                soundcloudId = trackId.takeIf { it != 0L } ?: base.soundcloudId,
                likedTimestamp = if (newLikedState) now else base.likedTimestamp
            )

            // Оптимистичное локальное обновление
            db.trackDao().insertOrUpdateTrack(updated)

            // Синхронизация с сервером
            var confirmed = true
            if (authManager.isLoggedIn) {
                if (trackId == 0L) {
                    db.trackDao().insertOrUpdateTrack(base)
                    confirmed = false
                    postUserMessage("Не удалось определить трек на SoundCloud")
                } else {
                    val result = if (newLikedState) {
                        libraryRepo.likeTrack(trackId)
                    } else {
                        libraryRepo.unlikeTrack(trackId)
                    }
                    if (result is Result.Error) {
                        // Откат при ошибке
                        db.trackDao().insertOrUpdateTrack(base)
                        confirmed = false
                        if (result.message.contains("403")) {
                            // 403 = проверка DataDome. Запоминаем действие, чтобы
                            // повторить его сразу после капчи.
                            pendingCaptchaRetry = { toggleLikeTrackWithSync(track) }
                            SessionManager.requestCaptchaCheck()
                            postUserMessage("SoundCloud просит проверку — нажмите «Пройти»")
                        } else {
                            postUserMessage("Не удалось синхронизировать лайк: ${result.message}")
                        }
                    }
                }
            }

            if (confirmed) {
                // id нужен вкладке SoundCloud, чтобы повторный лайк/дизлайк
                // сработал без дополнительного resolve.
                _mutations.tryEmit(LibraryMutation.TrackLike(track.copy(id = trackId), newLikedState))
            }

            interactionLogger.log(
                track,
                if (newLikedState) InteractionLogger.LIKE else InteractionLogger.UNLIKE
            )
        }
    }

    // ---------- Лайки плейлистов ----------

    /**
     * Переключение лайка плейлиста с синхронизацией с SoundCloud.
     */
    fun toggleLikePlaylistWithSync(playlist: PlaylistInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val wasLiked = playlist.isLiked ||
                likedPlaylists.value.any { it.permalinkUrl == playlist.permalinkUrl }
            val newLikedState = !wasLiked

            // Оптимистичное локальное обновление
            if (newLikedState) {
                db.favoritesDao().insertLikedPlaylist(
                    LikedPlaylistEntity(
                        permalinkUrl = playlist.permalinkUrl,
                        title = playlist.title,
                        user = playlist.user,
                        artworkUrl = playlist.artworkUrl,
                        trackCount = playlist.trackCount
                    )
                )
            } else {
                db.favoritesDao().deleteLikedPlaylist(
                    LikedPlaylistEntity(playlist.permalinkUrl, playlist.title, playlist.user, playlist.artworkUrl)
                )
            }

            // Синхронизация с сервером
            var confirmed = true
            if (authManager.isLoggedIn && playlist.id != 0L) {
                val result = if (newLikedState) {
                    libraryRepo.likePlaylist(playlist.id, playlist.urn)
                } else {
                    libraryRepo.unlikePlaylist(playlist.id, playlist.urn)
                }
                if (result is Result.Error) {
                    // Откат при ошибке
                    confirmed = false
                    if (result.message.contains("403")) {
                        pendingCaptchaRetry = { toggleLikePlaylistWithSync(playlist) }
                        SessionManager.requestCaptchaCheck()
                        postUserMessage("SoundCloud просит проверку — нажмите «Пройти»")
                    }
                    if (newLikedState) {
                        db.favoritesDao().deleteLikedPlaylist(
                            LikedPlaylistEntity(playlist.permalinkUrl, playlist.title, playlist.user, playlist.artworkUrl)
                        )
                    } else {
                        db.favoritesDao().insertLikedPlaylist(
                            LikedPlaylistEntity(
                                permalinkUrl = playlist.permalinkUrl,
                                title = playlist.title,
                                user = playlist.user,
                                artworkUrl = playlist.artworkUrl,
                                trackCount = playlist.trackCount
                            )
                        )
                    }
                }
            }

            if (confirmed) {
                _mutations.tryEmit(LibraryMutation.PlaylistLike(playlist, newLikedState))
            }
        }
    }

    // ---------- Подписки ----------

    /**
     * Переключение подписки на пользователя с синхронизацией с SoundCloud.
     */
    fun toggleFollowUserWithSync(user: UserInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            var userId = user.id
            if (userId == 0L) {
                when (val resolved = soundCloudRepo.resolveTrackId(user.permalinkUrl)) {
                    is Result.Success -> userId = resolved.data
                    else -> Unit
                }
            }
            if (userId == 0L) {
                postUserMessage("Не удалось определить артиста на SoundCloud")
                return@launch
            }
            val resolvedUser = user.copy(id = userId)

            // Текущее состояние: сначала локальное переопределение, затем — сервер.
            val wasFollowing = _followOverrides.value[userId] ?: run {
                when (val check = libraryRepo.isFollowing(userId)) {
                    is Result.Success -> check.data
                    else -> false
                }
            }
            val newFollowingState = !wasFollowing

            // Синхронизация с сервером
            if (authManager.isLoggedIn) {
                val result = if (newFollowingState) {
                    libraryRepo.followUser(userId)
                } else {
                    libraryRepo.unfollowUser(userId)
                }
                if (result is Result.Success) {
                    // Держим локальный список артистов в том же состоянии, что и
                    // подписки на сайте (он же используется вкладкой «Артисты»).
                    if (newFollowingState) {
                        db.favoritesDao().insertLikedArtist(
                            LikedArtistEntity(
                                permalinkUrl = resolvedUser.permalinkUrl,
                                username = resolvedUser.username,
                                avatarUrl = resolvedUser.avatarUrl,
                                followersCount = resolvedUser.followersCount,
                                trackCount = resolvedUser.trackCount,
                                addedTimestamp = System.currentTimeMillis(),
                                soundcloudId = userId
                            )
                        )
                    } else {
                        db.favoritesDao().deleteLikedArtist(
                            LikedArtistEntity(
                                resolvedUser.permalinkUrl,
                                resolvedUser.username,
                                resolvedUser.avatarUrl
                            )
                        )
                    }
                    // Обновляем UI только после подтверждения сервером.
                    _followOverrides.value = _followOverrides.value + (userId to newFollowingState)
                    _mutations.tryEmit(LibraryMutation.Follow(resolvedUser, newFollowingState))
                } else if (result is Result.Error) {
                    postUserMessage("Не удалось синхронизировать подписку: ${result.message}")
                }
            }
        }
    }

    // ---------- Управление плейлистами ----------

    /**
     * Создание плейлиста с учётом авторизации:
     * — если пользователь вошёл в SoundCloud, плейлист создаётся только на
     *   сервере (иначе в медиатеке появлялся бы дубль локального плейлиста);
     * — иначе создаётся локальный плейлист (офлайн-режим).
     */
    fun createPlaylistSynced(draft: PlaylistDraft) {
        viewModelScope.launch(Dispatchers.IO) {
            val title = draft.title.trim().ifBlank { "Новый плейлист" }
            if (authManager.isLoggedIn) {
                val result = libraryRepo.createPlaylist(
                    title = title,
                    description = draft.description.trim(),
                    isPublic = draft.isPublic,
                    genre = draft.genre.trim(),
                    tagList = draft.tagList.trim()
                )
                if (result is Result.Success) {
                    var playlist = result.data
                    draft.coverUri?.let { uri ->
                        val base64 = coverBase64(uri)
                        if (base64 == null) {
                            postUserMessage("Плейлист создан, но обложку прочитать не удалось")
                        } else {
                            when (val art = libraryRepo.uploadPlaylistArtwork(playlist.id, base64)) {
                                is Result.Success -> playlist = art.data
                                is Result.Error -> postUserMessage(
                                    "Плейлист создан, но обложка не загрузилась: ${art.message}"
                                )
                                else -> Unit
                            }
                        }
                    }
                    _mutations.tryEmit(LibraryMutation.PlaylistCreated(playlist))
                    postUserMessage("Плейлист создан: ${playlist.title}")
                } else if (result is Result.Error) {
                    postUserMessage("Не удалось создать плейлист: ${result.message}")
                }
            } else {
                val coverPath = draft.coverUri?.let { saveLocalCover(it) }.orEmpty()
                db.playlistDao().insertPlaylist(
                    PlaylistEntity(
                        title = title,
                        description = draft.description.trim(),
                        localCoverPath = coverPath
                    )
                )
                postUserMessage("Локальный плейлист создан: $title")
            }
        }
    }

    /**
     * Создание плейлиста на SoundCloud.
     */
    fun createPlaylistOnServer(title: String, description: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            if (!authManager.isLoggedIn) return@launch
            val result = libraryRepo.createPlaylist(title, description)
            if (result is Result.Success) {
                _mutations.tryEmit(LibraryMutation.PlaylistCreated(result.data))
            } else if (result is Result.Error) {
                postUserMessage("Не удалось создать плейлист: ${result.message}")
            }
        }
    }

    /**
     * Обновление плейлиста на SoundCloud.
     */
    fun updatePlaylistOnServer(playlistId: Long, draft: PlaylistDraft) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!authManager.isLoggedIn) return@launch
            val result = libraryRepo.updatePlaylist(
                playlistId = playlistId,
                title = draft.title.trim().ifBlank { null },
                description = draft.description.trim(),
                isPublic = draft.isPublic,
                genre = draft.genre.trim(),
                tagList = draft.tagList.trim()
            )
            if (result is Result.Success) {
                var playlist = result.data
                draft.coverUri?.let { uri ->
                    val base64 = coverBase64(uri)
                    if (base64 == null) {
                        postUserMessage("Настройки сохранены, но обложку прочитать не удалось")
                    } else {
                        when (val art = libraryRepo.uploadPlaylistArtwork(playlistId, base64)) {
                            is Result.Success -> playlist = art.data
                            is Result.Error -> postUserMessage(
                                "Настройки сохранены, но обложка не загрузилась: ${art.message}"
                            )
                            else -> Unit
                        }
                    }
                }
                // Обновляем открытую модалку и снапшот SoundCloud-библиотеки
                // подтверждёнными данными сервера.
                val current = _activePlaylistDetail.value
                if (current != null && current.first.id == playlistId) {
                    _activePlaylistDetail.value =
                        withPlaylistCoverFallback(playlist, current.second) to current.second
                }
                _mutations.tryEmit(LibraryMutation.PlaylistUpdated(playlist))
            } else if (result is Result.Error) {
                postUserMessage("Не удалось изменить плейлист: ${result.message}")
            }
        }
    }

    /**
     * Удаление плейлиста с SoundCloud.
     */
    fun deletePlaylistOnServer(playlistId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!authManager.isLoggedIn) return@launch
            // Локальная копия плейлиста хранит собственный Room-id и не связана
            // с серверным id, поэтому при успехе серверного удаления обновляем
            // только серверную библиотеку (после следующей синхронизации список
            // перестроится). Локальные плейлисты удаляются отдельным методом
            // [deletePlaylist].
            val active = _activePlaylistDetail.value
            val result = libraryRepo.deletePlaylist(playlistId)
            if (result is Result.Success) {
                if (active?.first?.id == playlistId) {
                    libraryRepo.clearPlaylistTracksCache(active.first.permalinkUrl)
                    _activePlaylistDetail.value = null
                }
                _mutations.tryEmit(LibraryMutation.PlaylistDeleted(playlistId))
            } else if (result is Result.Error) {
                postUserMessage("Не удалось удалить плейлист: ${result.message}")
            }
        }
    }

    /**
     * Добавление одного трека в плейлист на SoundCloud.
     * [onResult] вызывается на главном потоке, чтобы UI мог закрыть диалог
     * и показать результат вместо «молчаливого» ожидания двух запросов.
     */
    fun addTrackToPlaylistOnServer(
        playlistId: Long,
        track: TrackInfo,
        onResult: (Boolean) -> Unit = {}
    ) {
        if (track.id <= 0L) {
            postUserMessage("У этого трека нет ID SoundCloud")
            onResult(false)
            return
        }
        viewModelScope.launch {
            if (!authManager.isLoggedIn) {
                postUserMessage("Войдите в аккаунт SoundCloud")
                onResult(false)
                return@launch
            }
            val startedAt = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                libraryRepo.addTrackToPlaylist(playlistId, track.id)
            }
            Log.d(
                "ZYRO_PLAYER",
                "addTrackToPlaylistOnServer playlist=$playlistId " +
                    "took=${System.currentTimeMillis() - startedAt}ms result=$result"
            )
            if (result is Result.Success) {
                // Если плейлист открыт — сразу показываем в нём добавленный трек.
                val current = _activePlaylistDetail.value
                if (current != null && current.first.id == playlistId &&
                    current.second.none { it.id == track.id }
                ) {
                    val updatedTracks = current.second + track
                    _activePlaylistDetail.value =
                        current.first.copy(trackCount = updatedTracks.size) to updatedTracks
                    withContext(Dispatchers.IO) {
                        libraryRepo.savePlaylistTracksToCache(
                            current.first.permalinkUrl,
                            updatedTracks
                        )
                    }
                }
                postUserMessage("Трек добавлен в плейлист")
            } else if (result is Result.Error) {
                postUserMessage("Не удалось добавить трек: ${result.message}")
            }
            onResult(result is Result.Success)
        }
    }

    /**
     * Массовое добавление треков в серверный плейлист. Используется экраном
     * «Добавить из», где треки выбираются чекбоксами.
     */
    fun addTracksToPlaylistOnServer(playlistId: Long, tracks: List<TrackInfo>) {
        val validTracks = tracks.filter { it.id > 0L }.distinctBy { it.id }
        if (validTracks.isEmpty()) {
            postUserMessage("Нет треков SoundCloud для добавления")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (!authManager.isLoggedIn) return@launch
            val result = libraryRepo.addTracksToPlaylist(playlistId, validTracks.map { it.id })
            if (result is Result.Success) {
                val current = _activePlaylistDetail.value
                if (current != null && current.first.id == playlistId) {
                    val existingUrls = current.second.map { it.originalUrl }.toSet()
                    val added = validTracks.filterNot { it.originalUrl in existingUrls }
                    val updatedTracks = current.second + added
                    _activePlaylistDetail.value =
                        current.first.copy(trackCount = updatedTracks.size) to updatedTracks
                    libraryRepo.savePlaylistTracksToCache(
                        current.first.permalinkUrl,
                        updatedTracks
                    )
                }
                postUserMessage("Добавлено треков: ${validTracks.size}")
            } else if (result is Result.Error) {
                postUserMessage("Не удалось добавить треки: ${result.message}")
            }
        }
    }

    /**
     * Удаление трека из плейлиста на SoundCloud.
     */
    fun removeTrackFromPlaylistOnServer(playlistId: Long, trackId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!authManager.isLoggedIn) return@launch
            val result = libraryRepo.removeTrackFromPlaylist(playlistId, trackId)
            if (result is Result.Success) {
                // Обновляем открытую модалку плейлиста без повторного запроса.
                val current = _activePlaylistDetail.value
                if (current != null && current.first.id == playlistId) {
                    val updatedTracks = current.second.filterNot { it.id == trackId }
                    _activePlaylistDetail.value = current.first to updatedTracks
                    libraryRepo.savePlaylistTracksToCache(
                        current.first.permalinkUrl,
                        updatedTracks
                    )
                }
            } else if (result is Result.Error) {
                postUserMessage("Не удалось убрать трек: ${result.message}")
            }
        }
    }

    /**
     * Массовое добавление треков в локальный плейлист.
     */
    fun addTracksToPlaylist(playlistId: Long, tracks: List<TrackInfo>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            tracks.forEach { track ->
                val existingTrack = db.trackDao().getTrackByUrl(track.originalUrl)
                if (existingTrack == null) {
                    db.trackDao().insertOrUpdateTrack(
                        TrackEntity(
                            originalUrl = track.originalUrl,
                            title = track.title,
                            artist = track.artist,
                            artworkUrl = track.artworkUrl,
                            streamUrl = track.streamUrl,
                            durationMs = track.durationMs,
                            soundcloudId = track.id
                        )
                    )
                }
                db.playlistDao().insertPlaylistTrack(
                    PlaylistTrackCrossRef(
                        playlistId = playlistId,
                        trackOriginalUrl = track.originalUrl
                    )
                )
            }
            _activeLocalPlaylistDetail.value?.let { (current, existingTracks) ->
                if (current.playlistId == playlistId) {
                    val existingUrls = existingTracks.map { it.originalUrl }.toSet()
                    _activeLocalPlaylistDetail.value =
                        current to (existingTracks + tracks.filterNot { it.originalUrl in existingUrls })
                }
            }
        }
    }

    /** Загрузка треков серверного плейлиста для экрана «Добавить из». */
    suspend fun loadPlaylistTracksForPicker(playlist: PlaylistInfo): List<TrackInfo> =
        withContext(Dispatchers.IO) {
            if (playlist.permalinkUrl.isBlank()) return@withContext emptyList()
            libraryRepo.getCachedPlaylistTracks(playlist.permalinkUrl)?.let {
                return@withContext it
            }
            when (val result = soundCloudRepo.getPlaylistTracks(playlist.permalinkUrl)) {
                is Result.Success -> {
                    libraryRepo.savePlaylistTracksToCache(playlist.permalinkUrl, result.data)
                    result.data
                }
                else -> emptyList()
            }
        }

    /** Загрузка треков локального плейлиста для экрана «Добавить из». */
    suspend fun loadLocalPlaylistTracksForPicker(playlistId: Long): List<TrackInfo> =
        withContext(Dispatchers.IO) {
            db.playlistDao().getTracksForPlaylistOnce(playlistId).map { it.toTrackInfo() }
        }

    // Lyrics handling
    @RequiresApi(Build.VERSION_CODES.O)
    private fun observeCurrentTrackLyrics() {
        viewModelScope.launch {
            playerManager.currentTrack.collect { track ->
                if (track != null) {
                    _isLyricsLoading.value = true
                    val lyrics = lyricsRepo.getLyrics(
                        track.originalUrl,
                        track.title,
                        track.artist,
                        durationMs = track.durationMs
                    )
                    _currentLyrics.value = lyrics
                    _isLyricsLoading.value = false
                } else {
                    _currentLyrics.value = null
                }
            }
        }
    }

    fun setLyricsActiveSource(source: String) {
        val cur = _currentLyrics.value ?: return
        viewModelScope.launch {
            lyricsRepo.updateActiveSource(cur.trackOriginalUrl, source)
            _currentLyrics.value = cur.copy(activeSource = source)
        }
    }

    fun saveCustomUserLyrics(customText: String) {
        val track = playerManager.currentTrack.value ?: return
        viewModelScope.launch {
            lyricsRepo.saveCustomLyrics(track.originalUrl, track.title, track.artist, customText)
            _currentLyrics.value = lyricsRepo.getLyrics(
                track.originalUrl,
                track.title,
                track.artist,
                durationMs = track.durationMs
            )
        }
    }

    fun searchLyricsManual(queryTitle: String, queryArtist: String) {
        val track = playerManager.currentTrack.value ?: return
        viewModelScope.launch {
            _isLyricsLoading.value = true
            val res = lyricsRepo.getLyrics(
                track.originalUrl,
                queryTitle,
                queryArtist,
                forceRefresh = true,
                durationMs = track.durationMs
            )
            _currentLyrics.value = res
            _isLyricsLoading.value = false
        }
    }

    // Modals
    private suspend fun withDetailLoading(label: String, block: suspend () -> Unit) {
        _detailLoadingLabel.value = label
        _isDetailLoading.value = true
        try {
            block()
        } finally {
            _isDetailLoading.value = false
            _detailLoadingLabel.value = ""
        }
    }

    private fun closeNonArtistDetails() {
        closeAlbumDetail()
        closePlaylistDetail()
        closeLocalPlaylistDetail()
        closeTrackInfo()
    }

    private fun closeNonAlbumDetails() {
        closeArtistDetail()
        closePlaylistDetail()
        closeLocalPlaylistDetail()
        closeTrackInfo()
    }

    private fun closeNonPlaylistDetails() {
        closeArtistDetail()
        closeAlbumDetail()
        closeLocalPlaylistDetail()
        closeTrackInfo()
    }

    private fun closeNonLocalPlaylistDetails() {
        closeArtistDetail()
        closeAlbumDetail()
        closePlaylistDetail()
        closeTrackInfo()
    }

    /**
     * @param preserveDetails true, если альбом открыт из другого детального
     * экрана (например, из профиля артиста). Тогда родительский экран остаётся
     * внизу, чтобы системный «назад» вернул к нему, а не на вкладку.
     */
    fun openAlbumDetail(album: AlbumInfo, preserveDetails: Boolean = false) {
        if (!preserveDetails) closeNonAlbumDetails()
        _activeAlbumDetail.value = Pair(album, emptyList())
        albumDetailJob?.cancel()
        albumDetailJob = viewModelScope.launch {
            withDetailLoading("Загружаем альбом…") {
                val result = soundCloudRepo.getAlbumTracks(album.permalinkUrl)
                if (result is Result.Success) {
                    _activeAlbumDetail.value?.let { (current, _) ->
                        if (current.permalinkUrl == album.permalinkUrl) {
                            _activeAlbumDetail.value =
                                withAlbumCoverFallback(current, result.data) to result.data
                        }
                    }
                }
            }
        }
    }

    fun refreshAlbumDetail() {
        val current = _activeAlbumDetail.value ?: return
        val album = current.first
        if (album.permalinkUrl.isBlank() || _isAlbumRefreshing.value) return
        viewModelScope.launch {
            _isAlbumRefreshing.value = true
            try {
                when (val result = soundCloudRepo.getAlbumTracks(album.permalinkUrl)) {
                    is Result.Success -> {
                        _activeAlbumDetail.value?.let { (active, _) ->
                            if (active.permalinkUrl == album.permalinkUrl) {
                                _activeAlbumDetail.value =
                                    withAlbumCoverFallback(active, result.data) to result.data
                            }
                        }
                    }
                    is Result.Error -> postUserMessage(
                        "Не удалось обновить альбом: ${result.message}"
                    )
                    else -> Unit
                }
            } finally {
                _isAlbumRefreshing.value = false
            }
        }
    }

    fun closeAlbumDetail() {
        albumDetailJob?.cancel()
        albumDetailJob = null
        _isAlbumRefreshing.value = false
        _activeAlbumDetail.value = null
    }

    fun openPlaylistDetail(playlist: PlaylistInfo, preserveDetails: Boolean = false) {
        if (!preserveDetails) closeNonPlaylistDetails()
        playlistDetailJob?.cancel()
        playlistDetailJob = null
        _isPlaylistRefreshing.value = false

        // Системный плейлист (микс/станция) грузится только по urn.
        if (playlist.urn.startsWith("soundcloud:system-playlists:")) {
            openSystemPlaylistDetail(playlist)
            return
        }

        // Повторное открытие берёт треки с диска и не ждёт сетевой resolve.
        val cacheKey = playlist.permalinkUrl.ifBlank { playlist.urn }
        val cached = libraryRepo.getCachedPlaylistTracks(cacheKey)
        if (cached != null) {
            _activePlaylistDetail.value = withPlaylistCoverFallback(playlist, cached) to cached
            return
        }
        _activePlaylistDetail.value = withPlaylistCoverFallback(playlist, emptyList()) to emptyList()
        if (playlist.permalinkUrl.isBlank()) return

        playlistDetailJob = viewModelScope.launch {
            withDetailLoading("Загружаем плейлист…") {
                when (val result = soundCloudRepo.getPlaylistTracks(playlist.permalinkUrl)) {
                    is Result.Success -> {
                        _activePlaylistDetail.value?.let { (current, _) ->
                            if (current.id == playlist.id) {
                                val fresh = withPlaylistCoverFallback(current, result.data)
                                _activePlaylistDetail.value = fresh to result.data
                                libraryRepo.savePlaylistTracksToCache(
                                    current.permalinkUrl,
                                    result.data
                                )
                            }
                        }
                    }
                    is Result.Error -> postUserMessage(
                        "Не удалось загрузить плейлист: ${result.message}"
                    )
                    else -> Unit
                }
            }
        }
    }

    /**
     * Если у плейлиста нет обложки — показываем обложку первого его трека,
     * как это делает SoundCloud.
     */
    private fun withPlaylistCoverFallback(playlist: PlaylistInfo, tracks: List<TrackInfo>): PlaylistInfo =
        if (playlist.artworkUrl.isBlank() || playlist.artworkUrl == "null") {
            tracks.firstOrNull { it.artworkUrl.isNotBlank() }
                ?.let { playlist.copy(artworkUrl = it.artworkUrl) }
                ?: playlist
        } else {
            playlist
        }

    /** То же для альбома: нет обложки — берём обложку первого трека. */
    private fun withAlbumCoverFallback(album: AlbumInfo, tracks: List<TrackInfo>): AlbumInfo =
        if (album.artworkUrl.isBlank() || album.artworkUrl == "null") {
            tracks.firstOrNull { it.artworkUrl.isNotBlank() }
                ?.let { album.copy(artworkUrl = it.artworkUrl) }
                ?: album
        } else {
            album
        }

    /**
     * Системные плейлисты SoundCloud (soundcloud:system-playlists:…) не
     * резолвятся по permalink — грузим их напрямую по urn.
     */
    private fun openSystemPlaylistDetail(playlist: PlaylistInfo) {
        val cacheKey = playlist.permalinkUrl.ifBlank { playlist.urn }
        val cached = libraryRepo.getCachedPlaylistTracks(cacheKey)
        if (cached != null) {
            _activePlaylistDetail.value = withPlaylistCoverFallback(playlist, cached) to cached
            return
        }

        _activePlaylistDetail.value = withPlaylistCoverFallback(playlist, emptyList()) to emptyList()
        playlistDetailJob = viewModelScope.launch {
            withDetailLoading("Загружаем микс…") {
                refreshSystemPlaylistTracks(playlist.urn)
            }
        }
    }

    /** Загружает треки системного плейлиста и обновляет активное окно. */
    private suspend fun refreshSystemPlaylistTracks(urn: String) {
        when (val result = soundCloudRepo.getSystemPlaylist(urn)) {
            is Result.Success -> {
                _activePlaylistDetail.value?.let { (current, _) ->
                    // Совпадение по urn: id у системных плейлистов может быть 0.
                    if (current.urn == urn) {
                        val fresh = withPlaylistCoverFallback(result.data.playlist, result.data.tracks)
                        _activePlaylistDetail.value = fresh to result.data.tracks
                        // Ключи кеша: у системных плейлистов обычно только urn,
                        // но если пришёл и permalink — сохраняем под обоими ключами.
                        libraryRepo.savePlaylistTracksToCache(urn, result.data.tracks)
                        if (fresh.permalinkUrl.isNotBlank() && fresh.permalinkUrl != urn) {
                            libraryRepo.savePlaylistTracksToCache(fresh.permalinkUrl, result.data.tracks)
                        }
                    }
                }
            }
            is Result.Error -> postUserMessage(
                "Не удалось загрузить микс: ${result.message}"
            )
            else -> Unit
        }
    }

    fun refreshPlaylistDetail() {
        val current = _activePlaylistDetail.value ?: return
        val playlist = current.first
        if (_isPlaylistRefreshing.value) return

        // Системный плейлист обновляется по urn.
        if (playlist.urn.startsWith("soundcloud:system-playlists:")) {
            if (playlist.urn.isBlank()) return
            viewModelScope.launch {
                _isPlaylistRefreshing.value = true
                try {
                    refreshSystemPlaylistTracks(playlist.urn)
                } finally {
                    _isPlaylistRefreshing.value = false
                }
            }
            return
        }

        if (playlist.permalinkUrl.isBlank()) return
        viewModelScope.launch {
            _isPlaylistRefreshing.value = true
            try {
                val freshInfo = libraryRepo.getPlaylistInfo(playlist.id) ?: playlist
                val permalink = freshInfo.permalinkUrl.ifBlank { playlist.permalinkUrl }
                when (val result = soundCloudRepo.getPlaylistTracks(permalink)) {
                    is Result.Success -> {
                        _activePlaylistDetail.value?.let { (active, _) ->
                            if (active.id == playlist.id) {
                                val fresh = withPlaylistCoverFallback(freshInfo, result.data)
                                _activePlaylistDetail.value = fresh to result.data
                                libraryRepo.savePlaylistTracksToCache(
                                    fresh.permalinkUrl.ifBlank { active.permalinkUrl },
                                    result.data
                                )
                            }
                        }
                    }
                    is Result.Error -> postUserMessage(
                        "Не удалось обновить плейлист: ${result.message}"
                    )
                    else -> Unit
                }
            } finally {
                _isPlaylistRefreshing.value = false
            }
        }
    }

    fun closePlaylistDetail() {
        playlistDetailJob?.cancel()
        playlistDetailJob = null
        _isPlaylistRefreshing.value = false
        _activePlaylistDetail.value = null
    }

    /**
     * Мгновенно открывает окно артиста с уже известными данными (ник, аватар),
     * затем догружает полный профиль в фоне. Так приложение не «зависает».
     */
    fun openArtistDetail(user: UserInfo) {
        closeNonArtistDetails()
        showArtistDetail(seed = ArtistFullData(user = user), fallbackName = user.username)
    }

    fun closeArtistDetail() {
        artistDetailToken++
        artistDetailJob?.cancel()
        artistDetailJob = null
        _isArtistDetailLoading.value = false
        _isArtistRefreshing.value = false
        _activeArtistDetail.value = null
    }

    /** Pull-to-refresh открытого профиля артиста без скелетона. */
    fun refreshArtistDetail() {
        val current = _activeArtistDetail.value ?: return
        showArtistDetail(
            seed = current,
            fallbackName = current.user.username.takeIf { it.isNotBlank() },
            refresh = true
        )
    }

    /**
     * Открывает окно сразу (без ожидания сети) и догружает данные следом.
     * @param seed то, что уже известно (ник/аватар/ссылка) — показывается немедленно
     * @param fallbackName имя для поиска, если по ссылке найти не удалось
     * @param refresh true — не показывать скелетон и не сбрасывать текущие данные
     */
    private fun showArtistDetail(
        seed: ArtistFullData,
        fallbackName: String? = null,
        refresh: Boolean = false
    ) {
        // 1) Сразу показываем окно (при refresh оставляем текущие данные)
        if (!refresh) {
            _activeArtistDetail.value = seed
            _isArtistDetailLoading.value = true
        } else {
            _isArtistRefreshing.value = true
        }

        // 2) Отменяем предыдущую загрузку и запускаем новую
        artistDetailJob?.cancel()
        val token = ++artistDetailToken
        artistDetailJob = viewModelScope.launch {
            var resolved: ArtistFullData? = null
            try {
                val permalink = seed.user.permalinkUrl
                if (permalink.isNotBlank()) {
                    val result = soundCloudRepo.getArtistFullData(permalink)
                    if (result is Result.Success) {
                        val data = result.data
                        if (data.user.username.isNotBlank() && data.user.username != "Исполнитель") {
                            resolved = data
                        }
                    }
                }

                // Фолбэк — ищем исполнителя по имени
                if (resolved == null && !fallbackName.isNullOrBlank()) {
                    val search = soundCloudRepo.search(fallbackName, limit = 5)
                    if (search is Result.Success) {
                        val matched = search.data.users.firstOrNull()
                        if (matched != null) {
                            val result = soundCloudRepo.getArtistFullData(matched.permalinkUrl)
                            if (result is Result.Success) resolved = result.data
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // сеть/парсинг упали — оставляем то, что уже показали (seed)
            } finally {
                // 3) Применяем, только если это всё ещё актуальный запрос
                if (token == artistDetailToken) {
                    resolved?.let { _activeArtistDetail.value = it }
                    if (refresh) {
                        _isArtistRefreshing.value = false
                    } else {
                        _isArtistDetailLoading.value = false
                    }
                }
            }
        }
    }

    // Favorites for Artists, Albums, Playlists
    fun toggleLikeArtist(user: UserInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.favoritesDao().getLikedArtistByUrl(user.permalinkUrl)
            val wasLiked = existing != null
            val newLikedState = !wasLiked

            // Для артиста из локального списка id может быть 0 — резолвим,
            // иначе подписка на сайте не отправится.
            var userId = user.id.takeIf { it != 0L }
                ?: existing?.soundcloudId
                ?: 0L
            if (authManager.isLoggedIn && userId == 0L) {
                when (val resolved = soundCloudRepo.resolveTrackId(user.permalinkUrl)) {
                    is Result.Success -> userId = resolved.data
                    else -> Unit
                }
            }

            val now = System.currentTimeMillis()
            if (newLikedState) {
                db.favoritesDao().insertLikedArtist(
                    LikedArtistEntity(
                        permalinkUrl = user.permalinkUrl,
                        username = user.username,
                        avatarUrl = user.avatarUrl,
                        followersCount = user.followersCount,
                        trackCount = user.trackCount,
                        addedTimestamp = now,
                        soundcloudId = userId
                    )
                )
            } else {
                existing?.let { db.favoritesDao().deleteLikedArtist(it) }
            }

            if (authManager.isLoggedIn) {
                if (userId == 0L) {
                    rollbackArtistLike(user, existing, newLikedState)
                    postUserMessage("Не удалось определить артиста на SoundCloud")
                } else {
                    val result = if (newLikedState) {
                        libraryRepo.followUser(userId)
                    } else {
                        libraryRepo.unfollowUser(userId)
                    }
                    if (result is Result.Error) {
                        rollbackArtistLike(user, existing, newLikedState)
                        postUserMessage("Не удалось синхронизировать артиста: ${result.message}")
                    } else {
                        _followOverrides.value =
                            _followOverrides.value + (userId to newLikedState)
                        _mutations.tryEmit(
                            LibraryMutation.Follow(user.copy(id = userId), newLikedState)
                        )
                    }
                }
            }
        }
    }

    private suspend fun rollbackArtistLike(
        user: UserInfo,
        previous: LikedArtistEntity?,
        newLikedState: Boolean
    ) {
        if (newLikedState) {
            db.favoritesDao().deleteLikedArtist(
                LikedArtistEntity(user.permalinkUrl, user.username, user.avatarUrl)
            )
        } else if (previous != null) {
            db.favoritesDao().insertLikedArtist(previous)
        }
    }

    fun toggleLikeAlbum(album: AlbumInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val isLiked = likedAlbums.value.any { it.permalinkUrl == album.permalinkUrl }
            if (isLiked) {
                db.favoritesDao().deleteLikedAlbum(LikedAlbumEntity(album.permalinkUrl, album.title, album.artist, album.artworkUrl))
            } else {
                db.favoritesDao().insertLikedAlbum(
                    LikedAlbumEntity(
                        permalinkUrl = album.permalinkUrl,
                        title = album.title,
                        artist = album.artist,
                        // В «Медиатеке» альбом должен иметь обложку: если своей
                        // нет — сохраняем обложку первого трека.
                        artworkUrl = resolveAlbumArtwork(album),
                        trackCount = album.trackCount
                    )
                )
            }
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        playerManager.moveQueueItem(from, to)
    }

    fun persistQueueOrder() {
        playerManager.persistQueueOrder()
    }

    fun setQueue(newQueue: List<TrackInfo>) {
        playerManager.setQueue(newQueue)
    }

    fun toggleLikePlaylist(playlist: PlaylistInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val isLiked = likedPlaylists.value.any { it.permalinkUrl == playlist.permalinkUrl }
            if (isLiked) {
                db.favoritesDao().deleteLikedPlaylist(LikedPlaylistEntity(playlist.permalinkUrl, playlist.title, playlist.user, playlist.artworkUrl))
            } else {
                db.favoritesDao().insertLikedPlaylist(
                    LikedPlaylistEntity(
                        permalinkUrl = playlist.permalinkUrl,
                        title = playlist.title,
                        user = playlist.user,
                        // Фолбэк на обложку первого трека, как в SoundCloud.
                        artworkUrl = resolvePlaylistArtwork(playlist),
                        trackCount = playlist.trackCount
                    )
                )
            }
        }
    }

    /** Обложка альбома либо его первого трека (сеть — только если своей нет). */
    private suspend fun resolveAlbumArtwork(album: AlbumInfo): String {
        if (album.artworkUrl.isNotBlank() && album.artworkUrl != "null") return album.artworkUrl
        if (album.permalinkUrl.isBlank()) return ""
        return when (val result = soundCloudRepo.getAlbumTracks(album.permalinkUrl)) {
            is Result.Success ->
                result.data.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()
            else -> ""
        }
    }

    /** Обложка плейлиста либо его первого трека (сеть — только если своей нет). */
    private suspend fun resolvePlaylistArtwork(playlist: PlaylistInfo): String {
        if (playlist.artworkUrl.isNotBlank() && playlist.artworkUrl != "null") {
            return playlist.artworkUrl
        }
        if (playlist.urn.startsWith("soundcloud:system-playlists:")) {
            return when (val result = soundCloudRepo.getSystemPlaylist(playlist.urn)) {
                is Result.Success -> result.data.playlist.artworkUrl.ifBlank {
                    result.data.tracks.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()
                }
                else -> ""
            }
        }
        if (playlist.permalinkUrl.isBlank()) return ""
        return when (val result = soundCloudRepo.getPlaylistTracks(playlist.permalinkUrl)) {
            is Result.Success ->
                result.data.firstOrNull { it.artworkUrl.isNotBlank() }?.artworkUrl.orEmpty()
            else -> ""
        }
    }

    /**
     * Открывает профиль пользователя по прямой ссылке (например, из комментария).
     * Имя и аватар из комментария показываем сразу, полный профиль догружаем следом.
     */
    fun openArtistByPermalink(
        permalinkUrl: String,
        username: String = "",
        avatarUrl: String = ""
    ) {
        if (permalinkUrl.isBlank()) return
        closeNonArtistDetails()
        val seed = ArtistFullData(
            user = UserInfo(
                permalinkUrl = permalinkUrl,
                username = username.ifBlank { "Профиль" },
                avatarUrl = avatarUrl,
                followersCount = 0,
                trackCount = 0
            )
        )
        // Без фолбэка по имени: для комментатора это может открыть чужой профиль.
        showArtistDetail(seed = seed)
    }

    fun openArtistByName(artistName: String) {
        if (artistName.isBlank()) return
        closeNonArtistDetails()
        // Окно открываем сразу по имени, профиль догружаем в фоне
        val seed = ArtistFullData(
            user = UserInfo(
                permalinkUrl = "",
                username = artistName,
                avatarUrl = "",
                followersCount = 0,
                trackCount = 0
            )
        )
        showArtistDetail(seed = seed, fallbackName = artistName)
    }

    fun openArtistForTrack(track: TrackInfo, preserveDetails: Boolean = false) {
        // Мгновенно открываем окно артиста: имя/аватар уже есть на экране трека,
        // ссылку вычисляем из originalUrl, полный профиль подгружаем следом.
        if (!preserveDetails) closeNonArtistDetails()
        val seed = ArtistFullData(
            user = UserInfo(
                permalinkUrl = soundCloudArtistPermalink(track.originalUrl),
                username = track.artist.ifBlank { "Исполнитель" },
                avatarUrl = track.artistAvatarUrl,
                followersCount = 0,
                trackCount = 0
            )
        )
        showArtistDetail(seed = seed, fallbackName = track.artist)
    }

    /** "https://soundcloud.com/<artist>/<track>" → "https://soundcloud.com/<artist>" */
    private fun soundCloudArtistPermalink(trackUrl: String): String {
        if (trackUrl.isBlank()) return ""
        val clean = trackUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .substringAfter("soundcloud.com/", "")
            .substringBefore('?')
            .substringBefore('#')
        val slug = clean.trim('/').substringBefore('/')
        return if (slug.isNotBlank()) "https://soundcloud.com/$slug" else ""
    }

    /**
     * Открывает альбом, если он реально известен: по прямой ссылке с трека
     * либо найденный поиском по названию из publisher_metadata.
     */
    fun openAlbumForTrack(track: TrackInfo) {
        viewModelScope.launch {
            // 1) Прямая ссылка с трека — самый надёжный путь.
            if (track.albumPermalinkUrl.isNotBlank()) {
                openAlbumDetail(
                    AlbumInfo(
                        id = 0L,
                        permalinkUrl = track.albumPermalinkUrl,
                        title = track.albumTitle.ifBlank { track.title },
                        artist = track.artist,
                        artworkUrl = track.artworkUrl,
                        trackCount = 0
                    )
                )
                return@launch
            }

            // 2) Есть только название альбома — ищем и сверяем по названию,
            //    чтобы не открыть «похожий, но не тот» релиз.
            if (track.albumTitle.isBlank()) {
                postUserMessage("Альбом для этого трека не найден")
                return@launch
            }

            var matched: AlbumInfo? = null
            withDetailLoading("Ищем альбом…") {
                val query = listOf(track.albumTitle, track.artist)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                val result = soundCloudRepo.search(query, limit = 12)
                if (result is Result.Success) {
                    val target = normalizeAlbumTitle(track.albumTitle)
                    matched = result.data.albums.firstOrNull {
                        normalizeAlbumTitle(it.title) == target
                    } ?: result.data.albums.firstOrNull {
                        val candidate = normalizeAlbumTitle(it.title)
                        candidate.isNotBlank() &&
                                (candidate.contains(target) || target.contains(candidate))
                    }
                }
            }

            val album = matched
            if (album != null) {
                openAlbumDetail(album)
            } else {
                postUserMessage("Альбом для этого трека не найден")
            }
        }
    }

    private fun normalizeAlbumTitle(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-zа-яё0-9]+"), " ")
            .trim()

    /**
     * Открывает ссылку SoundCloud внутри приложения.
     * Вызывается из MainActivity при ACTION_VIEW на домены soundcloud.com.
     */
    fun handleIncomingLink(url: String) {
        val clean = url.substringBefore("?").substringBefore("#").trimEnd('/')
        val segments = clean.substringAfter("soundcloud.com", "")
            .trim('/')
            .split("/")
            .filter { it.isNotBlank() }

        if (segments.isEmpty()) return

        // Короткие ссылки on.soundcloud.com — редиректы, их резолвит сам API
        val isShortLink = clean.contains("on.soundcloud.com")
        val isAlbum = segments.contains("albums")
        val isSet = segments.contains("sets")

        when {
            !isShortLink && (isAlbum || isSet) -> {
                val title = slugToTitle(segments.last())
                val owner = slugToTitle(segments.first())
                if (isAlbum) {
                    openAlbumDetail(AlbumInfo(0L, clean, title, owner, "", 0))
                } else {
                    openPlaylistDetail(PlaylistInfo(0L, clean, title, owner, "", 0))
                }
            }

            // Один сегмент — профиль исполнителя
            !isShortLink && segments.size == 1 -> {
                viewModelScope.launch {
                    val result = soundCloudRepo.getUserInfo(clean)
                    if (result is Result.Success) openArtistDetail(result.data)
                }
            }

            // Всё остальное — трек
            else -> {
                viewModelScope.launch {
                    withDetailLoading("Открываем трек…") {
                        when (val result = soundCloudRepo.getTrackInfo(clean)) {
                            is Result.Success -> {
                                val track = result.data
                                // Открываем ту же карточку, что и по кнопке
                                // «Информация»: артист, альбом, жанр, описание,
                                // лайки и переход к исполнителю уже на месте.
                                setPlayerExpanded(false)
                                openTrackInfo(track)
                                playTrack(track, listOf(track), "Играет из SoundCloud")
                            }
                            is Result.Error -> {
                                postUserMessage("Не удалось открыть трек: ${result.message}")
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }

    /** "my-cool-track" -> "My Cool Track" — заголовок, пока грузятся реальные данные. */
    private fun slugToTitle(slug: String): String =
        slug.split("-", "_")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
            .ifBlank { "SoundCloud" }

    fun openTrackInfo(track: TrackInfo) {
        _activeTrackInfoDetail.value = track
    }

    fun closeTrackInfo() {
        _activeTrackInfoDetail.value = null
    }


    /** Вызывать при смене трека / стопе: позиция до ухода. */
    fun logTrackFinished(track: TrackInfo, positionMs: Long, isOffline: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            interactionLogger.logPlayEnd(track, positionMs, isOffline)
        }
    }

    fun logSkip(track: TrackInfo, positionMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            interactionLogger.log(track, InteractionLogger.SKIP, positionMs)
        }
    }

    fun logRepeat(track: TrackInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            interactionLogger.log(track, InteractionLogger.REPEAT)
        }
    }

    /**
     * Импортирует в Room полный список лайков из уже полученного снапшота
     * SoundCloud. Так локальная библиотека не зависит от лимита «первые 100»
     * и сразу показывает столько же треков, сколько вкладка синхронизации.
     */
    fun importSoundCloudLikes(tracks: List<TrackInfo>) {
        if (tracks.isEmpty()) return
        // Если снапшот обновился, пока предыдущий импорт ещё идёт, старый
        // результат не должен перезаписать более свежие метки лайков.
        likesImportJob?.cancel()
        likesImportJob = viewModelScope.launch(Dispatchers.IO) {
            importLikedTracks(tracks)
        }
    }

    private suspend fun importLikedTracks(tracks: List<TrackInfo>) {
        if (tracks.isEmpty()) return
        val now = System.currentTimeMillis()
        // Порядок из API — главный. Если у двух лайков одинаковая секунда,
        // искусственно разводим метки, иначе Room может перемешать их.
        var previousTimestamp = Long.MAX_VALUE
        val entities = tracks.mapIndexed { index, t ->
            val existing = db.trackDao().getTrackByUrl(t.originalUrl)
            var likedTimestamp = t.likedAt.takeIf { it > 0L }
                ?: (now - index * 1000L)
            if (likedTimestamp >= previousTimestamp) {
                likedTimestamp = previousTimestamp - 1L
            }
            previousTimestamp = likedTimestamp

            (existing ?: TrackEntity(
                originalUrl = t.originalUrl,
                title = t.title,
                artist = t.artist,
                artworkUrl = t.artworkUrl,
                streamUrl = t.streamUrl,
                durationMs = t.durationMs
            )).copy(
                title = t.title.ifBlank { existing?.title ?: "" },
                artist = t.artist.ifBlank { existing?.artist ?: "" },
                artworkUrl = t.artworkUrl.ifBlank { existing?.artworkUrl ?: "" },
                // Не теряем уже известный stream URL и офлайн-состояние.
                streamUrl = existing?.streamUrl?.takeIf { it.isNotBlank() } ?: t.streamUrl,
                durationMs = if (t.durationMs > 0) t.durationMs else existing?.durationMs ?: 0L,
                isLiked = true,
                likesCount = if (t.likesCount > 0) t.likesCount else existing?.likesCount ?: 0L,
                playbackCount = if (t.playbackCount > 0) t.playbackCount else existing?.playbackCount ?: 0L,
                soundcloudId = t.id.takeIf { it != 0L } ?: existing?.soundcloudId ?: 0L,
                likedTimestamp = likedTimestamp
            )
        }
        // Одна транзакция: счётчик в UI обновится сразу на полное число.
        db.trackDao().insertOrUpdateTracks(entities)
    }

    fun importSoundCloudFollowings(users: List<UserInfo>) {
        if (users.isEmpty()) return
        followingsImportJob?.cancel()
        followingsImportJob = viewModelScope.launch(Dispatchers.IO) {
            importFollowings(users)
        }
    }

    private suspend fun importFollowings(users: List<UserInfo>) {
        if (users.isEmpty()) return
        val now = System.currentTimeMillis()
        val artists = users.mapIndexed { index, u ->
            LikedArtistEntity(
                permalinkUrl = u.permalinkUrl,
                username = u.username,
                avatarUrl = u.avatarUrl,
                followersCount = u.followersCount,
                trackCount = u.trackCount,
                // Порядок followings в API — от новых к старым.
                addedTimestamp = now - index * 1000L,
                soundcloudId = u.id
            )
        }
        db.favoritesDao().insertLikedArtists(artists)
    }

    fun loadLikedTracksLazy() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                importLikedTracks(libraryRepo.fetchLikedTracks(limit = 500))
            } catch (_: Exception) { }
        }
    }

    fun loadArtistsLazy() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                importFollowings(libraryRepo.fetchFollowings(limit = 500))
            } catch (_: Exception) { }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Плеер принадлежит процессу (ZyroApplication), а не Activity: при
        // сворачивании/закрытии задачи он должен продолжать играть и грузить
        // следующие треки. Освобождает его только смерть процесса.
    }
}

/** Локальный трек из Room в формат, который понимают плеер и модалки. */
private fun TrackEntity.toTrackInfo(): TrackInfo = TrackInfo(
    originalUrl = originalUrl,
    streamUrl = streamUrl,
    title = title,
    artist = artist,
    artworkUrl = artworkUrl,
    durationMs = durationMs,
    likesCount = likesCount,
    playbackCount = playbackCount,
    id = soundcloudId,
    isLiked = isLiked,
    likedAt = likedTimestamp
)