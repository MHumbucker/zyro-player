package com.zyro.player.ui.viewmodel

import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zyro.player.data.auth.SoundCloudAuthManager
import com.zyro.player.data.remote.ScLibraryData
import com.zyro.player.data.remote.SoundCloudLibraryRepository
import com.zyro.player.data.remote.UserInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class SoundCloudAuthViewModel(
    private val authManager: SoundCloudAuthManager,
    private val libraryRepository: SoundCloudLibraryRepository,
    private val prefs: SharedPreferences
) : ViewModel() {

    companion object {
        private const val META_KEY = "sc_sync_meta"
        private const val TTL_MS = 24L * 60 * 60 * 1000
        // v2: полный список лайков с created_at и без лимита «первые 100».
        // v3: обложки плейлистов больше не подменяются аватаром автора.
        // Старый кеш принудительно перезагружается один раз после обновления.
        private const val META_VERSION = 3
    }

    private fun readMeta(): JSONObject? {
        val raw = prefs.getString(META_KEY, null) ?: return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }

    private fun writeMeta(data: ScLibraryData) {
        val json = JSONObject().apply {
            put("version", META_VERSION)
            put("likedTracks", data.likedTracks.size)
            put("likedPlaylists", data.likedPlaylists.size)
            put("followings", data.followings.size)
            put("playlists", data.playlists.size)
            put("timestamp", System.currentTimeMillis())
        }
        prefs.edit().putString(META_KEY, json.toString()).apply()
    }

    private fun isFresh(meta: JSONObject?): Boolean {
        if (meta == null) return false
        if (meta.optInt("version", 0) != META_VERSION) return false
        val ts = meta.optLong("timestamp", 0L)
        return System.currentTimeMillis() - ts < TTL_MS
    }

    /**
     * Применяет локальную мутацию и сразу пишет снапшот в дисковый кеш.
     * Иначе после перезапуска процесса новый плейлист «пропадал» до
     * ручного pull-to-refresh: старый кеш считался свежим.
     */
    private fun updateLibraryData(transform: (ScLibraryData) -> ScLibraryData) {
        val data = _uiState.value.libraryData ?: return
        val updated = transform(data)
        _uiState.value = _uiState.value.copy(libraryData = updated)
        libraryRepository.saveLibraryToCache(updated)
        writeMeta(updated)
    }

    private val _uiState = MutableStateFlow(ScSyncUiState())
    val uiState: StateFlow<ScSyncUiState> = _uiState.asStateFlow()

    init {
        refreshState()
        // Загружаем кеш сразу
        loadCachedData()
        // Если пользователь залогинен — запускаем фоновую синхронизацию (если кеш устарел)
        if (authManager.isLoggedIn) {
            syncLibrary(forceRefresh = false)
        }
    }

    private fun loadCachedData() {
        val cached = libraryRepository.getCachedLibrary()
        if (cached != null) {
            _uiState.value = _uiState.value.copy(
                libraryData = cached,
                currentUser = cached.currentUser,
                username = cached.currentUser?.username,
                isConnected = authManager.isLoggedIn
            )
        }
    }

    fun refreshState() {
        authManager.refreshAuthState()
        val isLogged = authManager.isLoggedIn
        _uiState.value = _uiState.value.copy(
            isConnected = isLogged,
            username = if (isLogged) authManager.currentUsername else null
        )
        if (isLogged && _uiState.value.currentUser == null) {
            fetchCurrentUser()
        }
    }

    private fun fetchCurrentUser() {
        viewModelScope.launch {
            val user = libraryRepository.fetchCurrentUser()
            if (user != null) {
                _uiState.value = _uiState.value.copy(
                    username = user.username,
                    currentUser = user
                )
            }
        }
    }

    /**
     * Вызывается после успешного OAuth: токены уже сохранены TokenManager'ом,
     * остаётся подтянуть профиль и синхронизировать библиотеку.
     */
    fun onAuthCompleted() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            authManager.refreshAuthState()
            _uiState.value = _uiState.value.copy(isConnected = authManager.isLoggedIn)

            val user = libraryRepository.fetchCurrentUser()
            if (user != null) {
                authManager.updateUserProfile(user.id, user.username)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isConnected = true,
                    username = user.username,
                    currentUser = user
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isConnected = authManager.isLoggedIn,
                    username = authManager.currentUsername.ifBlank { "SoundCloud" },
                    error = null
                )
            }

            syncLibrary(forceRefresh = true)
        }
    }

    fun disconnect() {
        authManager.clearAuth()
        libraryRepository.clearCache()
        _uiState.value = ScSyncUiState()
    }

    fun syncLibrary(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            val meta = readMeta()
            if (!forceRefresh && isFresh(meta)) {
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    error = null,
                    syncMessage = "Кеш актуален: ${meta?.optInt("likedTracks", 0)} лайков · ${meta?.optInt("playlists", 0)} плейлистов · ${meta?.optInt("likedPlaylists", 0)} лайкнутых · ${meta?.optInt("followings", 0)} подписок"
                )
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                isSyncing = true,
                error = null,
                syncMessage = "Синхронизация библиотеки..."
            )
            try {
                // Сюда попадаем только если кеш устарел/несовместим или запрошен
                // принудительный синк. Поэтому 5-минутный кеш репозитория обходим:
                // иначе старый неполный снапшот мог бы остаться ещё на несколько минут.
                val data = libraryRepository.fetchFullLibrary(forceRefresh = true)
                writeMeta(data)
                Log.i(
                    "ZYRO_PLAYER",
                    "SoundCloud sync OK: likes=${data.likedTracks.size}, " +
                        "playlists=${data.playlists.size}, " +
                        "likedPlaylists=${data.likedPlaylists.size}, " +
                        "followings=${data.followings.size}, " +
                        "history=${data.playHistory.size}"
                )
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    error = null,
                    libraryData = data,
                    syncMessage = "Готово: ${data.likedTracks.size} лайков · ${data.playlists.size} плейлистов · ${data.likedPlaylists.size} лайкнутых плейлистов · ${data.followings.size} подписок",
                    currentUser = data.currentUser,
                    username = data.currentUser?.username
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    error = e.message ?: "Ошибка синхронизации"
                )
            }
        }
    }

    /**
     * Быстрое обновление библиотеки по pull-to-refresh.
     *
     * В отличие от [syncLibrary] не перекачивает историю прослушиваний и не
     * показывает полноэкранный «Синхронизация…». Обновляет только то, что
     * видно в медиатеке: лайки, свои и сохранённые плейлисты, подписки.
     * Все запросы идут параллельно, чтобы жест не «висел» на экране.
     */
    fun microSyncLibrary() {
        if (!authManager.isLoggedIn) {
            _uiState.value = _uiState.value.copy(syncMessage = "Локальная библиотека актуальна")
            return
        }
        if (_uiState.value.isSyncing || _uiState.value.isMicroSyncing) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isMicroSyncing = true, error = null)
            try {
                val current = _uiState.value.libraryData
                val fresh = coroutineScope {
                    val likedTracks = async { libraryRepository.fetchLikedTracks(limit = 500) }
                    val playlists = async { libraryRepository.fetchPlaylists(limit = 200) }
                    val likedPlaylists = async {
                        libraryRepository.fetchLikedPlaylists(limit = 200)
                    }
                    val followings = async {
                        libraryRepository.fetchFollowings(limit = 500)
                    }
                    val user = async { libraryRepository.fetchCurrentUser() }
                    ScLibraryData(
                        likedTracks = likedTracks.await(),
                        playlists = playlists.await(),
                        likedPlaylists = likedPlaylists.await(),
                        followings = followings.await(),
                        // Историю не трогаем: она не отображается в медиатеке,
                        // а её загрузка заметно удлиняет микро-синхронизацию.
                        playHistory = current?.playHistory ?: emptyList(),
                        currentUser = user.await() ?: current?.currentUser
                    )
                }
                libraryRepository.saveLibraryToCache(fresh)
                writeMeta(fresh)
                _uiState.value = _uiState.value.copy(
                    isMicroSyncing = false,
                    error = null,
                    libraryData = fresh,
                    currentUser = fresh.currentUser,
                    username = fresh.currentUser?.username ?: _uiState.value.username,
                    syncMessage = "Обновлено: ${fresh.playlists.size} плейлистов · " +
                        "${fresh.likedTracks.size} лайков"
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isMicroSyncing = false,
                    error = e.message ?: "Не удалось обновить библиотеку"
                )
            }
        }
    }

    // ==================== МУТАЦИИ НА СЕРВЕРЕ ====================

    /**
     * Лайк трека на SoundCloud.
     */
    fun likeTrack(trackId: Long) {
        viewModelScope.launch {
            val result = libraryRepository.likeTrack(trackId)
            if (result is com.zyro.player.data.remote.Result.Error) {
                _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    /**
     * Удаление лайка трека с SoundCloud.
     */
    fun unlikeTrack(trackId: Long) {
        viewModelScope.launch {
            val result = libraryRepository.unlikeTrack(trackId)
            if (result is com.zyro.player.data.remote.Result.Error) {
                _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    /**
     * Лайк плейлиста на SoundCloud.
     */
    fun likePlaylist(playlistId: Long) {
        viewModelScope.launch {
            val result = libraryRepository.likePlaylist(playlistId)
            if (result is com.zyro.player.data.remote.Result.Error) {
                _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    /**
     * Удаление лайка плейлиста с SoundCloud.
     */
    fun unlikePlaylist(playlistId: Long) {
        viewModelScope.launch {
            val result = libraryRepository.unlikePlaylist(playlistId)
            if (result is com.zyro.player.data.remote.Result.Error) {
                _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    /**
     * Подписка на пользователя на SoundCloud.
     */
    fun followUser(userId: Long) {
        viewModelScope.launch {
            val result = libraryRepository.followUser(userId)
            if (result is com.zyro.player.data.remote.Result.Error) {
                _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    /**
     * Отписка от пользователя на SoundCloud.
     */
    fun unfollowUser(userId: Long) {
        viewModelScope.launch {
            val result = libraryRepository.unfollowUser(userId)
            if (result is com.zyro.player.data.remote.Result.Error) {
                _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    /**
     * Сохранение текстовых полей профиля SoundCloud.
     */
    fun saveProfile(
        username: String,
        firstName: String,
        lastName: String,
        description: String,
        city: String,
        countryCode: String
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProfileSaving = true,
                profileError = null,
                profileSavedAt = 0L,
                profileImageUpdatedAt = 0L
            )
            val result = libraryRepository.updateProfile(
                username = username,
                description = description,
                city = city,
                countryCode = countryCode,
                firstName = firstName,
                lastName = lastName
            )
            when (result) {
                is com.zyro.player.data.remote.Result.Success -> {
                    applyCurrentUser(result.data)
                    _uiState.value = _uiState.value.copy(
                        isProfileSaving = false,
                        profileError = null,
                        profileSavedAt = System.currentTimeMillis()
                    )
                }
                is com.zyro.player.data.remote.Result.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isProfileSaving = false,
                        profileError = result.message
                    )
                }
                else -> {
                    _uiState.value = _uiState.value.copy(isProfileSaving = false)
                }
            }
        }
    }

    fun uploadAvatar(imageBase64: String) = updateProfileImage {
        libraryRepository.uploadAvatar(imageBase64)
    }

    fun deleteAvatar() = updateProfileImage {
        libraryRepository.deleteAvatar()
    }

    fun uploadBanner(imageBase64: String) = updateProfileImage {
        libraryRepository.uploadBanner(imageBase64)
    }

    fun deleteBanner() = updateProfileImage {
        libraryRepository.deleteBanner()
    }

    /**
     * Общая обёртка для аватара/баннера: после успешной загрузки перечитываем
     * профиль, чтобы получить свежие URL картинок, и обновляем снапшот.
     */
    private fun updateProfileImage(
        action: suspend () -> com.zyro.player.data.remote.Result<Unit>
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProfileSaving = true,
                profileError = null,
                profileSavedAt = 0L,
                profileImageUpdatedAt = 0L
            )
            val result = action()
            if (result is com.zyro.player.data.remote.Result.Error) {
                _uiState.value = _uiState.value.copy(
                    isProfileSaving = false,
                    profileError = result.message
                )
                return@launch
            }
            val fresh = libraryRepository.fetchCurrentUser()
            if (fresh != null) applyCurrentUser(fresh)
            _uiState.value = _uiState.value.copy(
                isProfileSaving = false,
                profileError = null,
                profileImageUpdatedAt = System.currentTimeMillis()
            )
        }
    }

    /** Применяет обновлённый профиль к состоянию и дисковому кешу. */
    private fun applyCurrentUser(user: UserInfo) {
        val current = _uiState.value.libraryData
        val updatedLibrary = current?.copy(currentUser = user)
        _uiState.value = _uiState.value.copy(
            username = user.username,
            currentUser = user,
            libraryData = updatedLibrary ?: _uiState.value.libraryData
        )
        if (updatedLibrary != null) {
            libraryRepository.saveLibraryToCache(updatedLibrary)
        }
    }

    fun clearProfileFlags() {
        _uiState.value = _uiState.value.copy(
            profileError = null,
            profileSavedAt = 0L,
            profileImageUpdatedAt = 0L
        )
    }

    /**
     * Создание плейлиста на SoundCloud.
     */
    fun createPlaylist(title: String, description: String = "") {
        viewModelScope.launch {
            val result = libraryRepository.createPlaylist(title, description)
            if (result is com.zyro.player.data.remote.Result.Error) {
                _uiState.value = _uiState.value.copy(error = result.message)
            } else if (result is com.zyro.player.data.remote.Result.Success) {
                // Обновляем список плейлистов и сразу сохраняем снапшот на диск.
                updateLibraryData { data ->
                    data.copy(
                        playlists = data.playlists + result.data
                    )
                }
            }
        }
    }

    /**
     * Удаление плейлиста с SoundCloud.
     */
    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            val result = libraryRepository.deletePlaylist(playlistId)
            if (result is com.zyro.player.data.remote.Result.Error) {
                _uiState.value = _uiState.value.copy(error = result.message)
            } else if (result is com.zyro.player.data.remote.Result.Success) {
                updateLibraryData { data ->
                    data.copy(
                        playlists = data.playlists.filter { it.id != playlistId }
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // ==================== ЛОКАЛЬНОЕ ОБНОВЛЕНИЕ СНАПШОТА ====================
    // Вызывается после подтверждённой сервером мутации (из MainViewModel),
    // чтобы вкладка SoundCloud отражала изменение без полной перезагрузки.

    fun applyTrackLikeState(track: com.zyro.player.data.remote.TrackInfo, liked: Boolean) {
        updateLibraryData { data ->
            val updated = if (liked) {
                // Свежий лайк — сверху, как на сайте SoundCloud.
                val likedTrack = track.copy(
                    isLiked = true,
                    likedAt = System.currentTimeMillis()
                )
                listOf(likedTrack) + data.likedTracks.filterNot {
                    it.originalUrl == track.originalUrl
                }
            } else {
                data.likedTracks.filterNot { it.originalUrl == track.originalUrl }
            }
            data.copy(likedTracks = updated)
        }
    }

    fun applyPlaylistLikeState(playlist: com.zyro.player.data.remote.PlaylistInfo, liked: Boolean) {
        updateLibraryData { data ->
            val updated = if (liked) {
                listOf(playlist) + data.likedPlaylists.filterNot { it.id == playlist.id }
            } else {
                data.likedPlaylists.filterNot { it.id == playlist.id }
            }
            data.copy(likedPlaylists = updated)
        }
    }

    fun applyPlaylistCreated(playlist: com.zyro.player.data.remote.PlaylistInfo) {
        updateLibraryData { data ->
            val updated = listOf(playlist) + data.playlists.filterNot { it.id == playlist.id }
            data.copy(playlists = updated)
        }
    }

    fun applyPlaylistUpdated(playlist: com.zyro.player.data.remote.PlaylistInfo) {
        updateLibraryData { data ->
            data.copy(
                playlists = data.playlists.map {
                    if (it.id == playlist.id) playlist else it
                },
                likedPlaylists = data.likedPlaylists.map {
                    if (it.id == playlist.id) playlist else it
                }
            )
        }
    }

    fun applyPlaylistDeleted(playlistId: Long) {
        updateLibraryData { data ->
            data.copy(
                playlists = data.playlists.filterNot { it.id == playlistId },
                likedPlaylists = data.likedPlaylists.filterNot { it.id == playlistId }
            )
        }
    }

    fun applyFollowState(user: UserInfo, following: Boolean) {
        updateLibraryData { data ->
            val updated = if (following) {
                val withoutOld = data.followings.filterNot {
                    (user.id != 0L && it.id == user.id) ||
                        it.permalinkUrl == user.permalinkUrl
                }
                listOf(user) + withoutOld
            } else {
                data.followings.filterNot {
                    (user.id != 0L && it.id == user.id) ||
                        it.permalinkUrl == user.permalinkUrl
                }
            }
            data.copy(followings = updated)
        }
    }

    data class ScSyncUiState(
        val isConnected: Boolean = false,
        val isLoading: Boolean = false,
        val isSyncing: Boolean = false,
        /** Pull-to-refresh в медиатеке: обновляем только списки, без истории. */
        val isMicroSyncing: Boolean = false,
        /** Идёт сохранение профиля или загрузка аватара/баннера. */
        val isProfileSaving: Boolean = false,
        /** Ошибка последнего действия с профилем (показывается в форме). */
        val profileError: String? = null,
        /** Время успешного сохранения текстовых полей профиля. */
        val profileSavedAt: Long = 0L,
        /** Время успешной загрузки/удаления аватара или баннера. */
        val profileImageUpdatedAt: Long = 0L,
        val username: String? = null,
        val currentUser: UserInfo? = null,
        val libraryData: ScLibraryData? = null,
        val syncMessage: String? = null,
        val error: String? = null
    )
}
