package com.example.gravimusicplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : Service() {
    private val binder = PlaybackBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var audioManager: AudioManager
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var listener: ((PlaybackSnapshot) -> Unit)? = null
    private var snapshot = PlaybackSnapshot()
    private var connectedBluetoothOutputDeviceIds = emptySet<Int>()
    private var silenceAnalyzer: SilenceAnalyzer? = null
    private var skipSilenceEnabled = true
    private var silenceAnalysisRequest = 0
    private var trimStartPositionMs = 0
    private var trimEndPositionMs: Int? = null
    private var handlingTrimmedEnd = false
    private var waitingForSilenceAnalysis = false

    private val progressUpdater = object : Runnable {
        override fun run() {
            player?.let {
                val currentPosition = safePosition(it)
                snapshot = snapshot.copy(
                    positionMs = currentPosition,
                    durationMs = safeDuration(it),
                )
                notifyListener()
                handleTrimmedEndIfNeeded(currentPosition)
            }
            handler.postDelayed(this, 500)
        }
    }

    inner class PlaybackBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            connectedBluetoothOutputDeviceIds = currentBluetoothOutputDeviceIds()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            val removedBluetoothDeviceIds = removedDevices
                .filter { it.isBluetoothOutputDevice() }
                .map { it.id }
                .toSet()
            val hadBluetoothOutput = connectedBluetoothOutputDeviceIds.isNotEmpty()
            connectedBluetoothOutputDeviceIds = currentBluetoothOutputDeviceIds()
            if (hadBluetoothOutput && removedBluetoothDeviceIds.isNotEmpty()) {
                pauseForBluetoothOutputLoss()
            }
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseForBluetoothOutputLoss()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        createNotificationChannel()
        player = buildPlayer()
        silenceAnalyzer = SilenceAnalyzer(this)
        player?.addListener(playbackListener)
        mediaSession = MediaSessionCompat(this, "Gravi Music Player").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(mediaSessionCallback)
            isActive = true
        }
        connectedBluetoothOutputDeviceIds = currentBluetoothOutputDeviceIds()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
        registerReceiver(
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        )
        handler.post(progressUpdater)
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
            .build()
        val offloadPreferences = TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
            .setIsGaplessSupportRequired(false)
            .setIsSpeedChangeSupportRequired(false)
            .build()
        return ExoPlayer.Builder(this).build().apply {
            trackSelectionParameters = trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(offloadPreferences)
                .build()
            setAudioAttributes(audioAttributes, true)
            skipSilenceEnabled = false
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_NEXT -> playNext()
            ACTION_PREVIOUS -> playPrevious()
            ACTION_STOP -> stopPlayback()
            else -> updateForegroundNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        unregisterReceiver(becomingNoisyReceiver)
        serviceScope.cancel()
        player?.release()
        mediaSession?.release()
        super.onDestroy()
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            if (snapshot.isPlaying) return

            togglePlayPause()
        }

        override fun onPause() {
            if (!snapshot.isPlaying) return

            togglePlayPause()
        }

        override fun onSkipToNext() {
            playNext()
        }

        override fun onSkipToPrevious() {
            playPrevious()
        }

        override fun onStop() {
            stopPlayback()
        }

        override fun onSeekTo(position: Long) {
            seekTo(position.toInt())
        }
    }

    fun setListener(newListener: ((PlaybackSnapshot) -> Unit)?) {
        listener = newListener
        notifyListener()
    }

    fun getSnapshot(): PlaybackSnapshot = snapshot

    fun playQueue(
        queue: List<AudioItem>,
        startIndex: Int,
        queueType: QueueType,
        queueName: String,
        queueOrder: QueueOrder,
    ) {
        if (queue.isEmpty()) {
            snapshot = snapshot.copy(errorMessage = "No audio files found.")
            notifyListener()
            return
        }

        val safeIndex = startIndex.coerceIn(queue.indices)
        snapshot = snapshot.copy(
            queue = queue,
            queueType = queueType,
            queueName = queueName,
            currentIndex = safeIndex,
            queueOrder = queueOrder,
            positionMs = 0,
            durationMs = 0,
            errorMessage = null,
        )
        playIndex(safeIndex)
    }

    fun togglePlayPause() {
        val currentPlayer = player ?: return
        if (currentPlayer.isPlaying) {
            pausePlayback(currentPlayer)
        } else {
            currentPlayer.play()
            snapshot = snapshot.copy(isPlaying = true, positionMs = safePosition(currentPlayer))
        }
        updateMediaSession()
        updateForegroundNotification()
        notifyListener()
    }

    fun playNext() {
        val nextIndex = getNextIndex() ?: return
        playIndex(nextIndex)
    }

    fun playPrevious() {
        val currentPlayer = player
        if (currentPlayer != null && safePosition(currentPlayer) > 3000) {
            seekTo(0)
            return
        }

        val previousIndex = getPreviousIndex() ?: return
        playIndex(previousIndex)
    }

    fun playQueueIndex(index: Int) {
        if (index !in snapshot.queue.indices) return

        playIndex(index)
    }

    fun seekTo(positionMs: Int) {
        val currentPlayer = player ?: return
        currentPlayer.seekTo(positionMs.coerceIn(0, safeDuration(currentPlayer)).toLong())
        snapshot = snapshot.copy(positionMs = safePosition(currentPlayer))
        updateMediaSession()
        notifyListener()
    }

    fun shuffleQueue() {
        if (snapshot.queue.isEmpty()) return

        val currentQueue = snapshot.queue
        val currentItem = currentQueue.getOrNull(snapshot.currentIndex) ?: return
        val shuffledQueue = listOf(currentItem) + currentQueue
            .filterIndexed { index, _ -> index != snapshot.currentIndex }
            .shuffled()
        snapshot = snapshot.copy(
            queue = shuffledQueue,
            currentIndex = 0,
            queueOrder = QueueOrder.SHUFFLED,
        )
        notifyListener()
    }

    fun setLoopMode(mode: LoopMode) {
        snapshot = snapshot.copy(loopMode = mode)
        notifyListener()
    }

    fun setSkipSilenceEnabled(enabled: Boolean) {
        skipSilenceEnabled = enabled
        if (!enabled) {
            silenceAnalysisRequest++
            trimStartPositionMs = 0
            trimEndPositionMs = null
            handlingTrimmedEnd = false
            if (waitingForSilenceAnalysis) {
                waitingForSilenceAnalysis = false
                player?.play()
                snapshot =
                    snapshot.copy(isPlaying = true, positionMs = safePosition(player ?: return))
                updateMediaSession()
                updateForegroundNotification()
                notifyListener()
            }
        }
    }

    private fun playIndex(index: Int) {
        val item = snapshot.queue.getOrNull(index) ?: return
        val currentPlayer = player ?: return
        val playbackItem = readPlaybackMetadata(item)
        val updatedQueue = snapshot.queue.toMutableList().apply {
            this[index] = playbackItem
        }
        currentPlayer.stop()
        currentPlayer.clearMediaItems()
        currentPlayer.setMediaItem(MediaItem.fromUri(playbackItem.uri))
        currentPlayer.prepare()
        silenceAnalysisRequest++
        trimStartPositionMs = 0
        trimEndPositionMs = null
        handlingTrimmedEnd = false
        waitingForSilenceAnalysis = false
        snapshot = snapshot.copy(
            queue = updatedQueue,
            currentIndex = index,
            isPlaying = false,
            positionMs = 0,
            durationMs = playbackItem.durationMs?.toInt() ?: 0,
            audioInfoText = playbackItem.compactAudioInfo(),
            errorMessage = null
        )
        mediaSession?.isActive = true
        updateMediaSession()
        updateForegroundNotification()
        notifyListener()
        if (skipSilenceEnabled) {
            requestSilenceBoundaries(playbackItem, silenceAnalysisRequest)
        } else {
            startPlayback(0, null)
        }
    }

    private fun requestSilenceBoundaries(playbackItem: AudioItem, request: Int) {
        waitingForSilenceAnalysis = true
        serviceScope.launch {
            val boundaries = withContext(Dispatchers.IO) {
                silenceAnalyzer?.analyze(playbackItem.uri) {
                    request != silenceAnalysisRequest || !skipSilenceEnabled
                } ?: SilenceBoundaries()
            }
            if (request != silenceAnalysisRequest || !skipSilenceEnabled) return@launch

            waitingForSilenceAnalysis = false
            startPlayback(boundaries.startPositionMs, boundaries.endPositionMs)
        }
    }

    private fun startPlayback(startPositionMs: Int, endPositionMs: Int?) {
        val currentPlayer = player ?: return
        trimStartPositionMs = startPositionMs
        trimEndPositionMs = endPositionMs
        handlingTrimmedEnd = false
        if (startPositionMs > 0) {
            currentPlayer.seekTo(startPositionMs.toLong())
        }
        currentPlayer.play()
        snapshot = snapshot.copy(
            isPlaying = true,
            positionMs = safePosition(currentPlayer),
            durationMs = safeDuration(currentPlayer),
            errorMessage = null,
        )
        updateMediaSession()
        updateForegroundNotification()
        notifyListener()
    }

    private fun stopPlayback() {
        silenceAnalysisRequest++
        trimStartPositionMs = 0
        trimEndPositionMs = null
        handlingTrimmedEnd = false
        waitingForSilenceAnalysis = false
        player?.stop()
        player?.clearMediaItems()
        mediaSession?.isActive = false
        snapshot = PlaybackSnapshot(
            queueOrder = snapshot.queueOrder,
            loopMode = snapshot.loopMode,
        )
        updateMediaSession()
        notifyListener()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun pausePlayback(currentPlayer: Player) {
        currentPlayer.pause()
        snapshot = snapshot.copy(isPlaying = false, positionMs = safePosition(currentPlayer))
        updateMediaSession()
        updateForegroundNotification()
        notifyListener()
    }

    private fun pauseForBluetoothOutputLoss() {
        val currentPlayer = player ?: return
        if (!currentPlayer.isPlaying) return

        pausePlayback(currentPlayer)
    }

    private val playbackListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> updatePlaybackState()
                Player.STATE_ENDED -> handlePlaybackEnded()
                Player.STATE_BUFFERING -> Unit
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            snapshot = snapshot.copy(isPlaying = false, errorMessage = "Unable to play this file.")
            updateForegroundNotification()
            notifyListener()
        }
    }

    private fun updatePlaybackState() {
        val currentPlayer = player ?: return
        snapshot = snapshot.copy(
            isPlaying = currentPlayer.isPlaying,
            positionMs = safePosition(currentPlayer),
            durationMs = safeDuration(currentPlayer),
            audioInfoText = snapshot.currentItem?.compactAudioInfo(),
            errorMessage = null,
        )
        updateMediaSession()
        updateForegroundNotification()
        notifyListener()
    }

    private fun handlePlaybackEnded() {
        silenceAnalysisRequest++
        trimStartPositionMs = 0
        trimEndPositionMs = null
        handlingTrimmedEnd = false
        waitingForSilenceAnalysis = false
        if (snapshot.loopMode == LoopMode.SONG) {
            playIndex(snapshot.currentIndex)
            return
        }

        val nextIndex = getNextIndex()
        if (nextIndex == null) {
            snapshot = snapshot.copy(isPlaying = false, positionMs = snapshot.durationMs)
            updateMediaSession()
            updateForegroundNotification()
            notifyListener()
        } else {
            playIndex(nextIndex)
        }
    }

    private fun handleTrimmedEndIfNeeded(positionMs: Int) {
        val endPositionMs = trimEndPositionMs ?: return
        if (!snapshot.isPlaying || handlingTrimmedEnd || positionMs < endPositionMs) return

        handlingTrimmedEnd = true
        player?.pause()
        handlePlaybackEnded()
    }

    private fun getNextIndex(): Int? {
        if (snapshot.queue.isEmpty()) return null

        val nextIndex = snapshot.currentIndex + 1
        return when {
            nextIndex in snapshot.queue.indices -> nextIndex
            snapshot.loopMode == LoopMode.QUEUE -> 0
            else -> null
        }
    }

    private fun getPreviousIndex(): Int? {
        if (snapshot.queue.isEmpty()) return null

        val previousIndex = snapshot.currentIndex - 1
        return when {
            previousIndex in snapshot.queue.indices -> previousIndex
            snapshot.loopMode == LoopMode.QUEUE -> snapshot.queue.lastIndex
            else -> null
        }
    }

    private fun updateForegroundNotification() {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        val toggleTitle = if (snapshot.isPlaying) "Pause" else "Play"
        val toggleIcon =
            if (snapshot.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val artworkBitmap = loadArtworkBitmap(snapshot.currentItem?.artworkUriString)
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingIntentFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(snapshot.currentItem?.displayTitle ?: "Gravi Music Player")
            .setContentText(snapshot.currentItem?.folderPath ?: "Ready")
            .setContentIntent(openIntent)
            .setLargeIcon(artworkBitmap)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(snapshot.isPlaying)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                servicePendingIntent(ACTION_PREVIOUS, 1)
            )
            .addAction(toggleIcon, toggleTitle, servicePendingIntent(ACTION_TOGGLE, 2))
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                servicePendingIntent(ACTION_NEXT, 3)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                servicePendingIntent(ACTION_STOP, 4)
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateMediaSession() {
        val currentItem = snapshot.currentItem
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(
                MediaMetadataCompat.METADATA_KEY_TITLE,
                currentItem?.displayTitle ?: "Gravi Music Player"
            )
            .putString(
                MediaMetadataCompat.METADATA_KEY_ALBUM,
                snapshot.queueDisplayTitle()
            )
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, snapshot.durationMs.toLong())
        loadArtworkBitmap(currentItem?.artworkUriString)?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession?.setMetadata(metadataBuilder.build())

        val state =
            if (snapshot.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, snapshot.positionMs.toLong(), 1f)
                .build()
        )
    }

    private fun loadArtworkBitmap(artworkUriString: String?): Bitmap? {
        val uriString = artworkUriString ?: return null
        return runCatching {
            contentResolver.openInputStream(uriString.toUri())?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        }.getOrNull()
    }

    private fun readPlaybackMetadata(item: AudioItem): AudioItem {
        if (item.mimeType != null && item.bitrate != null && item.durationMs != null && item.lyrics != null) return item

        val retriever = MediaMetadataRetriever()
        return try {
            runCatching {
                retriever.setDataSource(this, item.uri)
                item.copy(
                    mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                    bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull(),
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull(),
                    lyrics = Mp3LyricsReader.readLyrics(this, item.uri),
                )
            }.getOrDefault(item)
        } finally {
            retriever.release()
        }
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int {
        return PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }

    private fun notifyListener() {
        listener?.invoke(snapshot)
    }

    private fun currentBluetoothOutputDeviceIds(): Set<Int> {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isBluetoothOutputDevice() }
            .map { it.id }
            .toSet()
    }

    private fun AudioDeviceInfo.isBluetoothOutputDevice(): Boolean {
        if (!isSink) return false

        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                type == AudioDeviceInfo.TYPE_BLE_BROADCAST
    }

    private fun safePosition(player: Player): Int {
        return runCatching { player.currentPosition.toInt() }.getOrDefault(0).coerceAtLeast(0)
    }

    private fun safeDuration(player: Player): Int {
        return runCatching { player.duration.toInt() }.getOrDefault(0).coerceAtLeast(0)
    }

    companion object {
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_TOGGLE = "com.example.gravimusicplayer.TOGGLE"
        private const val ACTION_NEXT = "com.example.gravimusicplayer.NEXT"
        private const val ACTION_PREVIOUS = "com.example.gravimusicplayer.PREVIOUS"
        private const val ACTION_STOP = "com.example.gravimusicplayer.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java)
            )
        }
    }
}