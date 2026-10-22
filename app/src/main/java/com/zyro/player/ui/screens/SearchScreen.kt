package com.zyro.player.ui.screens

import com.zyro.player.ui.components.ZyroArtworkFallback
import com.zyro.player.ui.components.ZyroPullToRefreshBox

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import com.zyro.player.data.remote.*
import com.zyro.player.ui.MainViewModel
import com.zyro.player.ui.SearchCategory
import com.zyro.player.ui.components.HomeRecommendationsShimmer
import com.zyro.player.ui.components.TrackListItem
import com.zyro.player.ui.components.ZyroAccent
import com.zyro.player.ui.components.ZyroExpandVertically
import com.zyro.player.ui.components.ZyroGlassBorder
import com.zyro.player.ui.components.ZyroShrinkVertically
import com.zyro.player.ui.components.ZyroShapes
import com.zyro.player.ui.components.ZyroSurface
import com.zyro.player.ui.components.ZyroSurfaceElevated
import com.zyro.player.ui.components.ZyroTopScrim
import com.zyro.player.ui.components.zyroPlasticGlass
import com.zyro.player.ui.components.ZyroTextPrimary
import com.zyro.player.ui.components.ZyroTextSecondary

// ============================================
// GLASS MODIFIER
// ============================================
private fun Modifier.zyroGlassSearch(cornerRadius: androidx.compose.ui.unit.Dp = 24.dp): Modifier =
    this.zyroPlasticGlass(shape = RoundedCornerShape(cornerRadius))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: MainViewModel,
    onTrackClick: (TrackInfo, List<TrackInfo>) -> Unit,
    onArtistClick: (UserInfo) -> Unit,
    onAlbumClick: (AlbumInfo) -> Unit,
    onPlaylistClick: (PlaylistInfo) -> Unit,
    /** Открывает общий диалог «Добавить в плейлист» (живёт в MainActivity). */
    onAddToPlaylist: (TrackInfo) -> Unit = {},
    /** Каждый инкремент поднимает клавиатуру и ставит фокус в поле поиска. */
    autoFocusTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    val state by viewModel.searchUiState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchCategory by viewModel.searchCategory.collectAsState()
    val currentTrack by viewModel.playerManager.currentTrack.collectAsState()
    val likedTracks by viewModel.likedTracks.collectAsState()
    val queue by viewModel.playerManager.queue.collectAsState()

    val likedUrls = remember(likedTracks) { likedTracks.map { it.originalUrl }.toSet() }
    val offlineTracks by viewModel.offlineTracks.collectAsState()
    val offlineUrls = remember(offlineTracks) { offlineTracks.map { it.originalUrl }.toSet() }
    val queueUrls = remember(queue) { queue.map { it.originalUrl }.toSet() }

    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val keyboardController = LocalSoftwareKeyboardController.current

    // === Search History ===
    val searchHistory by viewModel.searchHistory.collectAsState()
    var isSearchFocused by remember { mutableStateOf(false) }

    // Переход с главного экрана по поисковой плашке: сразу фокус + клавиатура.
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocusTrigger) {
        if (autoFocusTrigger <= 0) return@LaunchedEffect
        var attempts = 0
        while (attempts < 30) {
            attempts++
            try {
                searchFocusRequester.requestFocus()
                // Даём полю стать фокусируемым и только потом поднимаем IME.
                delay(120)
                keyboardController?.show()
                break
            } catch (_: IllegalStateException) {
                // Поле ещё не попало в композицию — подождём и попробуем снова.
                delay(60)
            }
        }
    }

    val performSearchWithHistory = {
        keyboardController?.hide()
        isSearchFocused = false
        // История пополняется внутри performSearch() — единая точка для
        // кнопки поиска, клавиатуры и тапа по сохранённому запросу.
        viewModel.performSearch()
    }

    // Infinite scroll
    LaunchedEffect(listState, state.activeQuery) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = listState.layoutInfo.totalItemsCount
            last to total
        }.collect { (last, total) ->
            if (state.activeQuery.isNotBlank() && state.hasMore && !state.isLoadingMore && !state.isLoading && total > 0 && last >= total - 4) {
                viewModel.loadMore()
            }
        }
    }

    var headerHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    Scaffold(
        containerColor = Color.Transparent,
        // Верхний инсет уже применён корневым Scaffold в MainActivity,
        // нижний — нижней навигацией. Иначе получаем двойные отступы.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.testTag("search_screen")
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ===== SEARCH HISTORY =====
            // Показываем не только по фокусу, но и сразу при открытии вкладки,
            // если история уже есть: это полезнее, чем пустая подсказка.
            AnimatedVisibility(
                visible = searchQuery.isBlank() &&
                    (isSearchFocused || searchHistory.isNotEmpty()),
                enter = ZyroExpandVertically,
                exit = ZyroShrinkVertically
            ) {
                if (searchHistory.isNotEmpty()) {
                    SearchHistoryPanel(
                        history = searchHistory,
                        onItemClick = { query ->
                            viewModel.onSearchQueryChange(query)
                            performSearchWithHistory()
                        },
                        onItemDelete = { query -> viewModel.removeSearchHistory(query) },
                        onClearAll = { viewModel.clearSearchHistory() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 20.dp,
                                end = 20.dp,
                                top = headerHeight + 12.dp,
                                bottom = 12.dp
                            )
                    )
                } else {
                    EmptySearchHint(
                        icon = Icons.Default.History,
                        text = "Начните вводить запрос или выберите категорию",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 20.dp,
                                end = 20.dp,
                                top = headerHeight + 32.dp,
                                bottom = 32.dp
                            )
                    )
                }
            }

            // ===== LOADING =====
            if (state.isLoading && !isSearchFocused) {
                HomeRecommendationsShimmer(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = headerHeight + 8.dp, bottom = 190.dp)
                )
            }

            // ===== EMPTY QUERY (not focused, история пуста) =====
            if (!state.isLoading && state.activeQuery.isBlank() && !isSearchFocused &&
                searchHistory.isEmpty()
            ) {
                EmptySearchHint(
                    icon = Icons.Default.Search,
                    text = "Введите название трека, артиста или вставьте ссылку SoundCloud",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = headerHeight + 32.dp, start = 32.dp, end = 32.dp, bottom = 32.dp)
                )
            }

            // ===== RESULTS =====
            if (state.activeQuery.isNotBlank() && !isSearchFocused) {
                ZyroPullToRefreshBox(
                    isRefreshing = state.isLoading,
                    onRefresh = { viewModel.performSearch() },
                    modifier = Modifier.fillMaxSize(),
                    indicatorTopPadding = headerHeight + 32.dp
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 190.dp, top = headerHeight + 8.dp)
                ) {
                    // ARTISTS
                    if (state.users.isNotEmpty() && (searchCategory == SearchCategory.ALL || searchCategory == SearchCategory.ARTISTS)) {
                        item {
                            SectionHeader(title = "Артисты")
                            UsersCarousel(users = state.users, onArtistClick = onArtistClick)
                        }
                    }
                    // ALBUMS
                    if (state.albums.isNotEmpty() && (searchCategory == SearchCategory.ALL || searchCategory == SearchCategory.ALBUMS)) {
                        item {
                            SectionHeader(title = "Альбомы")
                            AlbumsCarousel(albums = state.albums, onAlbumClick = onAlbumClick)
                        }
                    }
                    // PLAYLISTS
                    if (state.playlists.isNotEmpty() && (searchCategory == SearchCategory.ALL || searchCategory == SearchCategory.PLAYLISTS)) {
                        item {
                            SectionHeader(title = "Плейлисты")
                            PlaylistsCarousel(playlists = state.playlists, onPlaylistClick = onPlaylistClick)
                        }
                    }
                    // TRACKS
                    if (state.tracks.isNotEmpty() && (searchCategory == SearchCategory.ALL || searchCategory == SearchCategory.TRACKS)) {
                        item { SectionHeader(title = "Треки") }
                        itemsIndexed(
                            items = state.tracks,
                            key = { idx, track -> "${track.originalUrl}_$idx" }
                        ) { _, track ->
                            TrackCard(
                                track = track,
                                isPlaying = track.originalUrl == currentTrack?.originalUrl,
                                isLiked = likedUrls.contains(track.originalUrl),
                                isInQueue = queueUrls.contains(track.originalUrl),
                                onClick = { onTrackClick(track, state.tracks) },
                                onToggleLike = { viewModel.toggleLikeTrack(track) },
                                onToggleQueue = { viewModel.toggleQueue(track) },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onPlayNext = { viewModel.playNextInQueue(track) },
                                onDownload = { viewModel.downloadTrackForOffline(track) },
                                onDeleteOffline = {
                                    viewModel.deleteOfflineTrack(track.originalUrl)
                                },
                                isOffline = offlineUrls.contains(track.originalUrl),
                                onArtist = { viewModel.openArtistForTrack(track) },
                                onAlbum = {
                                    if (track.albumPermalinkUrl.isNotBlank())
                                        viewModel.openAlbumForTrack(track)
                                },
                                onTrackInfo = { viewModel.openTrackInfo(track) }
                            )
                        }
                    }
                    // LOADING MORE
                    if (state.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = ZyroAccent
                                )
                            }
                        }
                    }
                }
                }
            }

            // Контент под прозрачной шапкой затемняется — как над нижним баром.
            ZyroTopScrim(
                modifier = Modifier.align(Alignment.TopCenter),
                height = headerHeight + 24.dp,
                visible = isScrolled && state.activeQuery.isNotBlank() && !isSearchFocused
            )

            // Прозрачная шапка: заголовок, поиск и фильтры плавают над результатами.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                    .statusBarsPadding()
                    .padding(bottom = 2.dp)
            ) {
                Text(
                    text = "Поиск",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = ZyroTextPrimary,
                        lineHeight = 28.sp
                    ),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp)
                )

                ZyroSearchBar(
                    query = searchQuery,
                    onQueryChange = { viewModel.onSearchQueryChange(it) },
                    onSearch = performSearchWithHistory,
                    onFocusChange = { isSearchFocused = it },
                    focusRequester = searchFocusRequester,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Filter chips
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val categories = listOf(
                        SearchCategory.ALL to "Все",
                        SearchCategory.TRACKS to "Треки",
                        SearchCategory.ARTISTS to "Артисты",
                        SearchCategory.ALBUMS to "Альбомы",
                        SearchCategory.PLAYLISTS to "Плейлисты"
                    )
                    items(categories, key = { it.first.name }) { (cat, label) ->
                        val selected = searchCategory == cat
                        val chipInteraction = remember { MutableInteractionSource() }
                        val chipPressed by chipInteraction.collectIsPressedAsState()
                        val chipScale by animateFloatAsState(
                            targetValue = if (chipPressed) 0.95f else 1f,
                            animationSpec = spring(stiffness = Spring.StiffnessMedium),
                            label = "chip_scale"
                        )
                        Box(
                            modifier = Modifier
                                .scale(chipScale)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (selected) ZyroAccent.copy(alpha = 0.15f)
                                    else ZyroSurface.copy(alpha = 0.06f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) ZyroAccent.copy(alpha = 0.4f) else ZyroGlassBorder,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable(
                                    interactionSource = chipInteraction,
                                    indication = null,
                                    onClick = { viewModel.setSearchCategory(cat) }
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) ZyroAccent else ZyroTextSecondary
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================
// SEARCH HISTORY PANEL
// ============================================
@Composable
private fun SearchHistoryPanel(
    history: List<String>,
    onItemClick: (String) -> Unit,
    onItemDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.zyroGlassSearch(cornerRadius = 24.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Недавние запросы",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = ZyroTextPrimary
                )
            )
            val clearInteraction = remember { MutableInteractionSource() }
            val clearPressed by clearInteraction.collectIsPressedAsState()
            val clearScale by animateFloatAsState(
                targetValue = if (clearPressed) 0.92f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "clear_scale"
            )
            Box(
                modifier = Modifier
                    .scale(clearScale)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = clearInteraction,
                        indication = null,
                        onClick = onClearAll
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Очистить",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = ZyroAccent
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        history.forEach { query ->
            val itemInteraction = remember { MutableInteractionSource() }
            val itemPressed by itemInteraction.collectIsPressedAsState()
            val itemScale by animateFloatAsState(
                targetValue = if (itemPressed) 0.98f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessMedium),
                label = "history_item_scale"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(itemScale)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = itemInteraction,
                        indication = null,
                        onClick = { onItemClick(query) }
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = ZyroTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = query,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = ZyroTextPrimary
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(
                    onClick = { onItemDelete(query) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Удалить из истории",
                        tint = ZyroTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ============================================
// ZYRO SEARCH BAR
// ============================================
@Composable
fun ZyroSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Box(
        modifier = modifier
            .zyroGlassSearch(cornerRadius = ZyroShapes.L)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
                )
                .onFocusChanged { onFocusChange(it.isFocused) }
                .testTag("search_input_field"),
            placeholder = {
                Text(
                    "Поиск треков, артистов...",
                    color = ZyroTextSecondary.copy(alpha = 0.6f)
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = ZyroTextSecondary
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = ZyroTextSecondary
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(ZyroShapes.L),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ZyroAccent.copy(alpha = 0.6f),
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = ZyroTextPrimary,
                unfocusedTextColor = ZyroTextPrimary,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = ZyroAccent,
                focusedLeadingIconColor = ZyroAccent,
                unfocusedLeadingIconColor = ZyroTextSecondary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                onSearch()
            })
        )
    }
}

// ============================================
// EMPTY SEARCH HINT
// ============================================
@Composable
private fun EmptySearchHint(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .zyroPlasticGlass(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = ZyroTextSecondary.copy(alpha = 0.4f)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = ZyroTextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

// ============================================
// SECTION HEADER
// ============================================
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold,
            color = ZyroTextPrimary
        ),
        modifier = modifier.padding(horizontal = 20.dp, vertical = 14.dp)
    )
}

// ============================================
// CAROUSELS & CARDS
// ============================================
@Composable
fun UsersCarousel(
    users: List<UserInfo>,
    onArtistClick: (UserInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
    ) {
        items(users, key = { it.permalinkUrl }) { user ->
            ArtistCard(user = user, onClick = { onArtistClick(user) })
        }
    }
}

@Composable
fun ArtistCard(user: UserInfo, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "artist_card_scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(88.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .border(1.5.dp, ZyroGlassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = user.avatarUrl.ifBlank { "https://images.unsplash.com/photo-1534528741775-53994a69daeb" },
                contentDescription = user.username,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = user.username,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = ZyroTextPrimary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AlbumsCarousel(
    albums: List<AlbumInfo>,
    onAlbumClick: (AlbumInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
    ) {
        items(albums, key = { "${it.id}_${it.permalinkUrl}" }) { album ->
            AlbumCard(album = album, onClick = { onAlbumClick(album) })
        }
    }
}

@Composable
fun AlbumCard(album: AlbumInfo, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "album_card_scale"
    )

    Column(
        modifier = Modifier
            .width(130.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(ZyroShapes.L))
                .background(Color.White.copy(alpha = 0.04f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(ZyroShapes.L))
        ) {
            AsyncImage(
                model = album.artworkUrl.ifBlank { ZyroArtworkFallback },
                contentDescription = album.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                color = ZyroTextPrimary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = album.artist,
            style = MaterialTheme.typography.bodySmall,
            color = ZyroTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PlaylistsCarousel(
    playlists: List<PlaylistInfo>,
    onPlaylistClick: (PlaylistInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
    ) {
        items(playlists, key = { "${it.id}_${it.permalinkUrl}" }) { playlist ->
            PlaylistCard(playlist = playlist, onClick = { onPlaylistClick(playlist) })
        }
    }
}

@Composable
fun PlaylistCard(playlist: PlaylistInfo, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "playlist_card_scale"
    )

    Column(
        modifier = Modifier
            .width(130.dp)
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(ZyroShapes.L))
                .background(Color.White.copy(alpha = 0.04f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(ZyroShapes.L))
        ) {
            AsyncImage(
                model = playlist.artworkUrl.ifBlank { ZyroArtworkFallback },
                contentDescription = playlist.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                color = ZyroTextPrimary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${playlist.trackCount} треков",
            style = MaterialTheme.typography.bodySmall,
            color = ZyroTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun TrackCard(
    track: TrackInfo,
    isPlaying: Boolean,
    isLiked: Boolean,
    isInQueue: Boolean,
    onClick: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onDeleteOffline: (() -> Unit)? = null,
    isOffline: Boolean = false,
    onArtist: (() -> Unit)? = null,
    onAlbum: (() -> Unit)? = null,
    onTrackInfo: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    TrackListItem(
        track = track,
        isPlaying = isPlaying,
        isLiked = isLiked,
        isOffline = isOffline,
        onTrackClick = onClick,
        onSwipeRight = onPlayNext,
        onLikeClick = onToggleLike,
        onPlayNextClick = onPlayNext,
        onAddToQueueClick = onToggleQueue,
        onAddToPlaylistClick = onAddToPlaylist,
        onDownloadClick = onDownload,
        onDeleteOfflineClick = onDeleteOffline,
        onArtistClick = onArtist,
        onAlbumClick = onAlbum,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 3.dp)
    )
}