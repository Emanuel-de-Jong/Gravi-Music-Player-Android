package com.example.gravimusicplayer.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import com.example.gravimusicplayer.AudioItem
import com.example.gravimusicplayer.BrowserEntry
import com.example.gravimusicplayer.BrowserSortMode
import com.example.gravimusicplayer.DEFAULT_PLAYER_SETTINGS
import com.example.gravimusicplayer.DefaultStartPlayOrder
import com.example.gravimusicplayer.FavoritesRepository
import com.example.gravimusicplayer.GraviQueuePicker
import com.example.gravimusicplayer.LibraryRepository
import com.example.gravimusicplayer.PendingPlaybackRequest
import com.example.gravimusicplayer.PerformanceProfiler
import com.example.gravimusicplayer.PlaybackService
import com.example.gravimusicplayer.PlaybackSnapshot
import com.example.gravimusicplayer.PlayerPreferences
import com.example.gravimusicplayer.QueueOrder
import com.example.gravimusicplayer.QueueType
import com.example.gravimusicplayer.TagGroup
import com.example.gravimusicplayer.favoriteKey
import com.example.gravimusicplayer.withFavoriteKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@PreviewScreenSizes
@Composable
fun GraviMusicPlayerApp() {
    val context = LocalContext.current
    val libraryRepository = remember(context) { LibraryRepository(context) }
    val favoritesRepository = remember(context) { FavoritesRepository(context) }
    val graviQueuePicker = remember(context) { GraviQueuePicker(context) }
    val preferences = remember(context) { PlayerPreferences(context) }
    val favoritesDeviceId = remember(preferences) { preferences.favoritesDeviceId }
    val initialSettings = remember(preferences) { preferences.settings }
    val performanceProfiler = remember(context) { PerformanceProfiler.get(context) }
    val coroutineScope = rememberCoroutineScope()
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.FOLDERS) }
    var isPlayerExpanded by rememberSaveable { mutableStateOf(false) }
    var rootUriString by rememberSaveable { mutableStateOf(preferences.rootUriString) }
    var folderStack by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var folderSearchQuery by rememberSaveable { mutableStateOf("") }
    var appliedFolderSearchQuery by rememberSaveable { mutableStateOf("") }
    var genreSearchQuery by rememberSaveable { mutableStateOf("") }
    var appliedGenreSearchQuery by rememberSaveable { mutableStateOf("") }
    var browserSortMode by rememberSaveable { mutableStateOf(BrowserSortMode.FILENAME) }
    var browserSortAscending by rememberSaveable { mutableStateOf(true) }
    var genreSortAscending by rememberSaveable { mutableStateOf(true) }
    var browserEntries by remember { mutableStateOf(emptyList<BrowserEntry>()) }
    var tagGroups by remember { mutableStateOf(emptyList<TagGroup>()) }
    var isGeneratingCache by remember { mutableStateOf(preferences.rootUriString != null) }
    var libraryCacheVersion by remember { mutableIntStateOf(0) }
    var cacheGenerationRequest by remember { mutableIntStateOf(0) }
    var pendingPlaybackRequest by remember { mutableStateOf<PendingPlaybackRequest?>(null) }
    var playbackService by remember { mutableStateOf<PlaybackService?>(null) }
    var playbackSnapshot by remember { mutableStateOf(PlaybackSnapshot()) }
    var defaultStartPlayOrder by rememberSaveable {
        mutableStateOf(initialSettings.defaultStartPlayOrder)
    }
    var savedLoopMode by rememberSaveable { mutableStateOf(initialSettings.loopMode) }
    var genreSeparator by rememberSaveable { mutableStateOf(initialSettings.genreSeparator) }
    var appliedGenreSeparator by rememberSaveable { mutableStateOf(initialSettings.genreSeparator) }
    var showBrowserThumbnails by rememberSaveable {
        mutableStateOf(initialSettings.showBrowserThumbnails)
    }
    var queueSearchResults by rememberSaveable { mutableStateOf(initialSettings.queueSearchResults) }
    var skipSilenceEnabled by rememberSaveable { mutableStateOf(initialSettings.skipSilenceEnabled) }
    var loudnessNormalizationEnabled by rememberSaveable {
        mutableStateOf(initialSettings.loudnessNormalizationEnabled)
    }
    var fineGrainedVolumeEnabled by rememberSaveable {
        mutableStateOf(initialSettings.fineGrainedVolumeEnabled)
    }
    var graviPickerSettings by remember { mutableStateOf(initialSettings.graviPickerSettings) }
    var pendingPlaylistExport by remember { mutableStateOf<List<AudioItem>?>(null) }
    var isFolderActionRunning by remember { mutableStateOf(false) }
    var mediaLibraryPermissionVersion by remember { mutableIntStateOf(0) }
    var pendingPerformanceExport by remember { mutableStateOf<String?>(null) }
    var isPerformanceExporting by remember { mutableStateOf(false) }
    var favoriteKeys by remember { mutableStateOf(emptySet<String>()) }
    var favoritesRefreshRequest by remember { mutableIntStateOf(0) }

    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                rootUriString = uri.toString()
                preferences.rootUriString = rootUriString
                folderStack = emptyList()
                folderSearchQuery = ""
                appliedFolderSearchQuery = ""
                genreSearchQuery = ""
                appliedGenreSearchQuery = ""
                browserEntries = emptyList()
                tagGroups = emptyList()
                favoriteKeys = emptySet()
                libraryRepository.clearSessionCache()
                cacheGenerationRequest++
                favoritesRefreshRequest++
            }
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val mediaLibraryPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            libraryRepository.clearSessionCache()
            mediaLibraryPermissionVersion++
        }
    val playlistExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
            val exportItems = pendingPlaylistExport
            pendingPlaylistExport = null
            if (uri != null && exportItems != null) {
                writeM3u8Playlist(context, uri, exportItems)
            }
        }
    val performanceExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val report = pendingPerformanceExport
            pendingPerformanceExport = null
            if (uri != null && report != null) {
                coroutineScope.launch(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(report.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) {
                        isPerformanceExporting = false
                    }
                }
            } else {
                isPerformanceExporting = false
            }
        }

    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val boundService = (service as PlaybackService.PlaybackBinder).getService()
                playbackService = boundService
                boundService.setLoopMode(savedLoopMode)
                boundService.setSkipSilenceEnabled(skipSilenceEnabled)
                boundService.setLoudnessNormalizationEnabled(loudnessNormalizationEnabled)
                boundService.setFineGrainedVolumeEnabled(fineGrainedVolumeEnabled)
                playbackSnapshot = boundService.getSnapshot().withFavoriteKeys(favoriteKeys)
                boundService.setListener { playbackSnapshot = it.withFavoriteKeys(favoriteKeys) }
                pendingPlaybackRequest?.let {
                    boundService.playQueue(
                        it.queue.withFavoriteKeys(favoriteKeys),
                        it.startIndex,
                        it.queueType,
                        it.queueName,
                        it.queueOrder,
                    )
                    pendingPlaybackRequest = null
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                playbackService?.setListener(null)
                playbackService = null
            }
        }
        context.bindService(
            Intent(context, PlaybackService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        onDispose {
            playbackService?.setListener(null)
            context.unbindService(connection)
        }
    }

    LaunchedEffect(
        rootUriString,
        mediaLibraryPermissionVersion,
        appliedGenreSeparator,
        cacheGenerationRequest
    ) {
        val rootUri = rootUriString
        if (rootUri == null) {
            isGeneratingCache = false
            browserEntries = emptyList()
            tagGroups = emptyList()
            favoriteKeys = emptySet()
        } else {
            isGeneratingCache = true
            tagGroups = withContext(Dispatchers.IO) {
                if (!libraryRepository.hasCompleteCache(rootUri, appliedGenreSeparator)) {
                    libraryRepository.generateAllCaches(rootUri, appliedGenreSeparator)
                }
                libraryRepository.buildTagGroups(
                    libraryRepository.loadCachedGenreAudioItems(rootUri, appliedGenreSeparator)
                )
            }
            libraryCacheVersion++
            isGeneratingCache = false
        }
    }

    LaunchedEffect(rootUriString, libraryCacheVersion, favoritesRefreshRequest) {
        val rootUri = rootUriString
        favoriteKeys = if (rootUri == null) {
            emptySet()
        } else {
            withContext(Dispatchers.IO) {
                favoritesRepository.initialize(rootUri, favoritesDeviceId)
                val allItems = libraryRepository.loadRecursiveAudioItems(rootUri, emptyList())
                favoritesRepository.refreshAndroidEventPaths(
                    rootUri,
                    allItems,
                    favoritesDeviceId
                ).favoriteKeys
            }
        }
    }

    LaunchedEffect(favoriteKeys) {
        playbackService?.setFavoriteKeys(favoriteKeys)
        playbackSnapshot = playbackSnapshot.withFavoriteKeys(favoriteKeys)
    }

    LaunchedEffect(
        rootUriString,
        folderStack,
        appliedFolderSearchQuery,
        libraryCacheVersion,
        isGeneratingCache
    ) {
        val rootUri = rootUriString
        browserEntries =
            if (rootUri == null || isGeneratingCache) emptyList() else withContext(Dispatchers.IO) {
                if (appliedFolderSearchQuery.isBlank()) {
                    libraryRepository.loadBrowserEntries(rootUri, folderStack)
                } else {
                    libraryRepository.searchBrowserEntries(rootUri, appliedFolderSearchQuery)
                }
            }
    }

    LaunchedEffect(rootUriString) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        rootUriString ?: return@LaunchedEffect
        val mediaLibraryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (context.checkSelfPermission(mediaLibraryPermission) != PackageManager.PERMISSION_GRANTED) {
            mediaLibraryPermissionLauncher.launch(mediaLibraryPermission)
        }
    }

    AppScaffold(
        currentDestination = currentDestination,
        isGeneratingCache = isGeneratingCache,
        onDestinationSelected = { destination ->
            isPlayerExpanded = false
            currentDestination = destination
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (isGeneratingCache) {
                LibraryLoadingScreen()
            } else if (isPlayerExpanded) {
                PlayScreen(
                    snapshot = playbackSnapshot,
                    expanded = true,
                    onCollapse = { isPlayerExpanded = false },
                    onPlayPause = { playbackService?.togglePlayPause() },
                    onNext = { playbackService?.playNext() },
                    onPrevious = { playbackService?.playPrevious() },
                    onSeek = { playbackService?.seekTo(it) },
                    onPlayQueueIndex = { playbackService?.playQueueIndex(it) },
                    onRemoveQueueItem = { playbackService?.removeQueueItem(it) },
                    onShuffleQueue = { playbackService?.shuffleQueue() },
                    onToggleCurrentFavorite = {
                        val rootUri = rootUriString
                        val currentItem = playbackSnapshot.currentItem
                        if (rootUri != null && currentItem != null) {
                            coroutineScope.launch {
                                val updatedFavoriteKeys = withContext(Dispatchers.IO) {
                                    favoritesRepository.toggleFavorite(
                                        rootUri,
                                        currentItem,
                                        favoritesDeviceId,
                                    ).favoriteKeys
                                }
                                favoriteKeys = updatedFavoriteKeys
                            }
                        }
                    },
                    onToggleFavoriteQueueFilter = {
                        playbackService?.toggleFavoriteQueueFilter(favoriteKeys)
                    },
                    showThumbnails = showBrowserThumbnails,
                    onLoopModeChanged = {
                        savedLoopMode = it
                        preferences.loopMode = it
                        playbackService?.setLoopMode(it)
                    },
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f)) {
                        val sortedBrowserEntries = sortBrowserEntries(
                            browserEntries,
                            browserSortMode,
                            browserSortAscending,
                        )
                        val filteredTagGroups = tagGroups.filter {
                            appliedGenreSearchQuery.isBlank() || it.name.contains(
                                appliedGenreSearchQuery,
                                ignoreCase = true
                            )
                        }.let { groups ->
                            if (genreSortAscending) {
                                groups.sortedBy { it.name.lowercase() }
                            } else {
                                groups.sortedByDescending { it.name.lowercase() }
                            }
                        }
                        when (currentDestination) {
                            AppDestinations.FOLDERS -> FoldersScreen(
                                rootUriString = rootUriString,
                                folderStack = folderStack,
                                entries = sortedBrowserEntries,
                                currentTrackCount = sortedBrowserEntries.sumOf {
                                    it.trackCount ?: if (it.audioItem != null) 1 else 0
                                },
                                searchQuery = folderSearchQuery,
                                sortMode = browserSortMode,
                                sortAscending = browserSortAscending,
                                showThumbnails = showBrowserThumbnails,
                                isFolderActionRunning = isFolderActionRunning,
                                onChooseFolder = { folderPicker.launch(null) },
                                onSearchQueryChanged = { query ->
                                    folderSearchQuery = query
                                    if (query.isBlank()) appliedFolderSearchQuery = ""
                                },
                                onSearchSubmitted = {
                                    appliedFolderSearchQuery = folderSearchQuery
                                },
                                onSortModeChanged = { browserSortMode = it },
                                onToggleSortDirection = {
                                    browserSortAscending = !browserSortAscending
                                },
                                onOpenFolder = {
                                    folderStack =
                                        it.uriString.split('/').filter { part -> part.isNotBlank() }
                                    folderSearchQuery = ""
                                    appliedFolderSearchQuery = ""
                                },
                                onBack = { folderStack = folderStack.dropLast(1) },
                                onPlayFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue = withContext(Dispatchers.IO) {
                                                libraryRepository.loadRecursiveAudioItems(
                                                    rootUri,
                                                    selectedFolderStack
                                                )
                                            }
                                            pendingPlaybackRequest = requestPlayback(
                                                context,
                                                playbackService,
                                                queue,
                                                0,
                                                QueueType.FOLDER,
                                                folderQueueName(selectedFolderStack),
                                                QueueOrder.ORDERED,
                                                favoriteKeys,
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onShuffleFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue = withContext(Dispatchers.IO) {
                                                graviQueuePicker.shuffleQueue(
                                                    libraryRepository.loadRecursiveAudioItems(
                                                        rootUri,
                                                        selectedFolderStack
                                                    )
                                                )
                                            }
                                            pendingPlaybackRequest = requestPlayback(
                                                context,
                                                playbackService,
                                                queue,
                                                0,
                                                QueueType.FOLDER,
                                                folderQueueName(selectedFolderStack),
                                                QueueOrder.SHUFFLED,
                                                favoriteKeys,
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onGraviShuffleFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    val selectedGraviPickerSettings = graviPickerSettings
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue = withContext(Dispatchers.IO) {
                                                graviQueuePicker.buildGraviQueue(
                                                    libraryRepository.loadRecursiveAudioItems(
                                                        rootUri,
                                                        selectedFolderStack
                                                    ),
                                                    selectedGraviPickerSettings,
                                                    selectedFolderStack.joinToString("/"),
                                                )
                                            }
                                            pendingPlaybackRequest = requestPlayback(
                                                context,
                                                playbackService,
                                                queue,
                                                0,
                                                QueueType.FOLDER,
                                                folderQueueName(selectedFolderStack),
                                                QueueOrder.GRAVI_SHUFFLED,
                                                favoriteKeys,
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onExportFolder = {
                                    val rootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = folderStack
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val exportItems = withContext(Dispatchers.IO) {
                                                libraryRepository.loadRecursiveAudioItems(
                                                    rootUri,
                                                    selectedFolderStack
                                                )
                                            }
                                            pendingPlaylistExport = exportItems
                                            playlistExporter.launch(
                                                playlistFileName(
                                                    selectedFolderStack
                                                )
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onPlayFile = { entry ->
                                    val selectedItem = entry.audioItem ?: return@FoldersScreen
                                    val selectedRootUri = rootUriString ?: return@FoldersScreen
                                    val selectedFolderStack = selectedItem.folderPath.split('/')
                                        .filter { it.isNotBlank() }
                                    coroutineScope.launch {
                                        isFolderActionRunning = true
                                        try {
                                            val queue =
                                                if (appliedFolderSearchQuery.isBlank() || queueSearchResults) {
                                                    sortedBrowserEntries.mapNotNull { it.audioItem }
                                                } else {
                                                    withContext(Dispatchers.IO) {
                                                        libraryRepository.loadRecursiveAudioItems(
                                                            selectedRootUri,
                                                            selectedFolderStack,
                                                        )
                                                    }
                                                }
                                            val startIndex =
                                                queue.indexOfFirst { it.uriString == selectedItem.uriString }
                                                    .coerceAtLeast(0)
                                            val queueName =
                                                if (appliedFolderSearchQuery.isBlank() || queueSearchResults) {
                                                    folderQueueName(folderStack)
                                                } else {
                                                    folderQueueName(selectedFolderStack)
                                                }
                                            val playbackQueue = if (
                                                defaultStartPlayOrder == DefaultStartPlayOrder.SHUFFLED
                                            ) {
                                                queue.shuffledWithCurrentItemFirst(selectedItem)
                                            } else {
                                                queue
                                            }
                                            pendingPlaybackRequest = requestPlayback(
                                                context,
                                                playbackService,
                                                playbackQueue,
                                                if (defaultStartPlayOrder == DefaultStartPlayOrder.SHUFFLED) 0 else startIndex,
                                                QueueType.FOLDER,
                                                queueName,
                                                if (defaultStartPlayOrder == DefaultStartPlayOrder.SHUFFLED) {
                                                    QueueOrder.SHUFFLED
                                                } else {
                                                    QueueOrder.ORDERED
                                                },
                                                favoriteKeys,
                                            )
                                        } finally {
                                            isFolderActionRunning = false
                                        }
                                    }
                                },
                                onAddFileToQueue = { entry ->
                                    entry.audioItem?.let {
                                        playbackService?.addToQueue(
                                            it.copy(isFavorite = it.favoriteKey() in favoriteKeys),
                                            false
                                        )
                                    }
                                },
                                onAddFileToQueueAndPlay = { entry ->
                                    entry.audioItem?.let {
                                        playbackService?.addToQueue(
                                            it.copy(isFavorite = it.favoriteKey() in favoriteKeys),
                                            true
                                        )
                                    }
                                },
                            )

                            AppDestinations.GENRES -> GenresScreen(
                                rootUriString = rootUriString,
                                tagGroups = filteredTagGroups,
                                searchQuery = genreSearchQuery,
                                sortAscending = genreSortAscending,
                                onChooseFolder = { folderPicker.launch(null) },
                                onSearchQueryChanged = { query ->
                                    genreSearchQuery = query
                                    if (query.isBlank()) appliedGenreSearchQuery = ""
                                },
                                onSearchSubmitted = {
                                    appliedGenreSearchQuery = genreSearchQuery
                                },
                                onToggleSortDirection = {
                                    genreSortAscending = !genreSortAscending
                                },
                                onPlayTag = { tagGroup ->
                                    val playbackQueue = if (
                                        defaultStartPlayOrder == DefaultStartPlayOrder.SHUFFLED
                                    ) {
                                        graviQueuePicker.shuffleQueue(tagGroup.items)
                                    } else {
                                        tagGroup.items
                                    }
                                    pendingPlaybackRequest = requestPlayback(
                                        context,
                                        playbackService,
                                        playbackQueue,
                                        0,
                                        QueueType.GENRE,
                                        tagGroup.name,
                                        if (defaultStartPlayOrder == DefaultStartPlayOrder.SHUFFLED) {
                                            QueueOrder.SHUFFLED
                                        } else {
                                            QueueOrder.ORDERED
                                        },
                                        favoriteKeys,
                                    )
                                },
                            )

                            AppDestinations.SETTINGS -> SettingsScreen(
                                rootUriString = rootUriString,
                                defaultStartPlayOrder = defaultStartPlayOrder,
                                genreSeparator = genreSeparator,
                                showBrowserThumbnails = showBrowserThumbnails,
                                queueSearchResults = queueSearchResults,
                                skipSilenceEnabled = skipSilenceEnabled,
                                loudnessNormalizationEnabled = loudnessNormalizationEnabled,
                                fineGrainedVolumeEnabled = fineGrainedVolumeEnabled,
                                graviPickerSettings = graviPickerSettings,
                                onChooseFolder = { folderPicker.launch(null) },
                                onDefaultStartPlayOrderChanged = {
                                    defaultStartPlayOrder = it
                                    preferences.defaultStartPlayOrder = it
                                },
                                onGenreSeparatorChanged = {
                                    genreSeparator = it
                                },
                                onApplyGenreSeparator = {
                                    genreSeparator = it
                                    appliedGenreSeparator = it.ifBlank {
                                        DEFAULT_PLAYER_SETTINGS.genreSeparator
                                    }
                                    preferences.genreSeparator = it
                                    tagGroups = emptyList()
                                    cacheGenerationRequest++
                                },
                                onShowBrowserThumbnailsChanged = {
                                    showBrowserThumbnails = it
                                    preferences.showBrowserThumbnails = it
                                },
                                onQueueSearchResultsChanged = {
                                    queueSearchResults = it
                                    preferences.queueSearchResults = it
                                },
                                onSkipSilenceEnabledChanged = {
                                    skipSilenceEnabled = it
                                    preferences.skipSilenceEnabled = it
                                    playbackService?.setSkipSilenceEnabled(it)
                                },
                                onLoudnessNormalizationChanged = {
                                    loudnessNormalizationEnabled = it
                                    preferences.loudnessNormalizationEnabled = it
                                    playbackService?.setLoudnessNormalizationEnabled(it)
                                },
                                onFineGrainedVolumeChanged = {
                                    fineGrainedVolumeEnabled = it
                                    preferences.fineGrainedVolumeEnabled = it
                                    playbackService?.setFineGrainedVolumeEnabled(it)
                                },
                                onGraviPickerSettingsChanged = {
                                    graviPickerSettings = it
                                    preferences.graviPickerSettings = it
                                },
                                onResetSettings = {
                                    preferences.resetSettingsExceptRootUri()
                                    val settings = preferences.settings
                                    defaultStartPlayOrder = settings.defaultStartPlayOrder
                                    savedLoopMode = settings.loopMode
                                    genreSeparator = settings.genreSeparator
                                    appliedGenreSeparator = settings.genreSeparator
                                    showBrowserThumbnails = settings.showBrowserThumbnails
                                    queueSearchResults = settings.queueSearchResults
                                    skipSilenceEnabled = settings.skipSilenceEnabled
                                    loudnessNormalizationEnabled =
                                        settings.loudnessNormalizationEnabled
                                    fineGrainedVolumeEnabled = settings.fineGrainedVolumeEnabled
                                    graviPickerSettings = settings.graviPickerSettings
                                    playbackService?.setLoopMode(savedLoopMode)
                                    playbackService?.setSkipSilenceEnabled(skipSilenceEnabled)
                                    playbackService?.setLoudnessNormalizationEnabled(
                                        loudnessNormalizationEnabled
                                    )
                                    playbackService?.setFineGrainedVolumeEnabled(
                                        fineGrainedVolumeEnabled
                                    )
                                    tagGroups = emptyList()
                                    cacheGenerationRequest++
                                },
                                onClearCaches = {
                                    libraryRepository.clearAllCaches(rootUriString)
                                    browserEntries = emptyList()
                                    tagGroups = emptyList()
                                    cacheGenerationRequest++
                                },
                                onClearPerformanceData = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        performanceProfiler.clearData()
                                    }
                                },
                                onExportPerformanceData = {
                                    isPerformanceExporting = true
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val report = runCatching {
                                            performanceProfiler.exportReport()
                                        }.getOrNull()
                                        withContext(Dispatchers.Main) {
                                            if (report != null) {
                                                pendingPerformanceExport = report
                                                performanceExporter.launch("gravi-performance-data.json")
                                            } else {
                                                isPerformanceExporting = false
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                    MiniPlayer(
                        snapshot = playbackSnapshot,
                        showArtwork = showBrowserThumbnails,
                        onExpand = { isPlayerExpanded = true },
                        onPlayPause = { playbackService?.togglePlayPause() },
                        onNext = { playbackService?.playNext() },
                    )
                }
            }
            if (isPerformanceExporting) {
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = {},
                    title = { Text("Saving debug data") },
                    text = { CircularProgressIndicator() },
                )
            }
        }
    }
}

private fun sortBrowserEntries(
    entries: List<BrowserEntry>,
    sortMode: BrowserSortMode,
    sortAscending: Boolean,
): List<BrowserEntry> {
    val folders = entries.filter { it.isDirectory }
    val files = entries.filter { !it.isDirectory }
    return sortBrowserEntryGroup(folders, sortMode, sortAscending)
        .plus(sortBrowserEntryGroup(files, sortMode, sortAscending))
}

private fun sortBrowserEntryGroup(
    entries: List<BrowserEntry>,
    sortMode: BrowserSortMode,
    sortAscending: Boolean,
): List<BrowserEntry> {
    val comparator = compareBy<BrowserEntry> { browserSortKey(it, sortMode) }
        .thenBy { it.name.lowercase() }
    return if (sortAscending) entries.sortedWith(comparator) else entries.sortedWith(comparator.reversed())
}

private fun browserSortKey(entry: BrowserEntry, sortMode: BrowserSortMode): Comparable<*> {
    val item = entry.audioItem
    return when (sortMode) {
        BrowserSortMode.FILENAME -> item?.displayTitle?.lowercase() ?: entry.name.lowercase()
        BrowserSortMode.ARTIST -> item?.artist?.lowercase() ?: item?.displayTitle?.lowercase()
        ?: entry.name.lowercase()

        BrowserSortMode.TITLE -> item?.metadataTitle?.lowercase() ?: item?.displayTitle?.lowercase()
        ?: entry.name.lowercase()

        BrowserSortMode.DURATION -> item?.durationMs ?: 0L
        BrowserSortMode.RELEASE_DATE -> item?.releaseDate.releaseDateSortKey()
        BrowserSortMode.ADDITION_DATE -> item?.lastModifiedMs ?: 0L
    }
}

private fun String?.releaseDateSortKey(): Long {
    val digits = orEmpty().filter { it.isDigit() }
    if (digits.length < 4) return 0L

    val year = digits.take(4)
    val month = digits.drop(4).take(2).ifBlank { "00" }
    val day = digits.drop(6).take(2).ifBlank { "00" }
    return "$year$month$day".toLongOrNull() ?: 0L
}

private fun folderQueueName(folderStack: List<String>): String {
    return if (folderStack.isEmpty()) "Root" else folderStack.joinToString(" / ")
}

private fun playlistFileName(folderStack: List<String>): String {
    val folderName = if (folderStack.isEmpty()) "Root music folder" else folderStack.last()
    val safeFolderName = folderName
        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .ifBlank { "playlist" }
    return "$safeFolderName.m3u8"
}

private fun writeM3u8Playlist(context: Context, uri: Uri, items: List<AudioItem>) {
    val playlistContent = buildM3u8Playlist(items)
    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
        outputStream.write(playlistContent.toByteArray(Charsets.UTF_8))
    }
}

private fun buildM3u8Playlist(items: List<AudioItem>): String {
    return buildString {
        appendLine("#EXTM3U")
        items.forEach { item ->
            appendLine(item.uriString)
        }
    }
}

private fun requestPlayback(
    context: Context,
    playbackService: PlaybackService?,
    queue: List<AudioItem>,
    startIndex: Int,
    queueType: QueueType,
    queueName: String,
    queueOrder: QueueOrder,
    favoriteKeys: Set<String>,
): PendingPlaybackRequest? {
    val favoriteQueue = queue.withFavoriteKeys(favoriteKeys)
    PlaybackService.start(context)
    playbackService?.playQueue(favoriteQueue, startIndex, queueType, queueName, queueOrder)
    return if (playbackService == null) PendingPlaybackRequest(
        favoriteQueue,
        startIndex,
        queueType,
        queueName,
        queueOrder,
    ) else null
}

private fun List<AudioItem>.shuffledWithCurrentItemFirst(currentItem: AudioItem): List<AudioItem> {
    val remainingItems = filter { it.uriString != currentItem.uriString }.shuffled()
    return listOf(currentItem) + remainingItems
}