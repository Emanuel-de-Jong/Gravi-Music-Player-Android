package com.example.gravimusicplayer

import android.net.Uri
import androidx.core.net.toUri

data class AudioItem(
    val uriString: String,
    val title: String,
    val folderPath: String,
    val tags: List<String> = emptyList(),
    val artworkUriString: String? = null,
    val mimeType: String? = null,
    val bitrate: Int? = null,
    val durationMs: Long? = null,
    val artist: String? = null,
    val releaseDate: String? = null,
    val lyrics: String? = null,
    val replayGainTrackGainDb: Float? = null,
    val replayGainTrackPeak: Float? = null,
    val lastModifiedMs: Long = 0,
    val sizeBytes: Long = 0,
    val metadataTitle: String? = null,
) {
    val uri: Uri
        get() = uriString.toUri()

    val displayTitle: String
        get() = title.substringBeforeLast('.', title)
}

data class BrowserEntry(
    val name: String,
    val uriString: String,
    val isDirectory: Boolean,
    val audioItem: AudioItem? = null,
    val trackCount: Int? = null,
)

data class TagGroup(
    val name: String,
    val items: List<AudioItem>,
)

data class PendingPlaybackRequest(
    val queue: List<AudioItem>,
    val startIndex: Int,
    val queueType: QueueType,
    val queueName: String,
    val queueOrder: QueueOrder,
)

data class PlaybackSnapshot(
    val queue: List<AudioItem> = emptyList(),
    val queueType: QueueType? = null,
    val queueName: String? = null,
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val queueOrder: QueueOrder = QueueOrder.ORDERED,
    val loopMode: LoopMode = LoopMode.QUEUE,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val audioInfoText: String? = null,
    val errorMessage: String? = null,
) {
    val currentItem: AudioItem?
        get() = queue.getOrNull(currentIndex)
}

enum class QueueType(
    val label: String,
) {
    FOLDER("Folder"),
    GENRE("Genre"),
}

enum class QueueOrder(
    val label: String,
) {
    ORDERED("Ordered"),
    SHUFFLED("Shuffled"),
    GRAVI_SHUFFLED("Gravi"),
}

enum class DefaultStartPlayOrder(
    val label: String,
) {
    ORDERED("Ordered"),
    SHUFFLED("Shuffled"),
}

enum class LoopMode(
    val label: String,
) {
    OFF("No repeat"),
    SONG("Repeat song"),
    QUEUE("Repeat queue"),
}

enum class BrowserSortMode(
    val label: String,
) {
    FILENAME("Name"),
    ARTIST("Artist"),
    TITLE("Title"),
    DURATION("Duration"),
    RELEASE_DATE("Release date"),
    ADDITION_DATE("Addition date"),
}

fun LoopMode.next(): LoopMode {
    return when (this) {
        LoopMode.OFF -> LoopMode.QUEUE
        LoopMode.QUEUE -> LoopMode.SONG
        LoopMode.SONG -> LoopMode.OFF
    }
}

fun PlaybackSnapshot.queueDisplayTitle(): String {
    val currentQueueType = queueType
    val currentQueueName = queueName
    if (currentQueueType == null || currentQueueName == null) {
        return currentItem?.folderPath ?: "Select a file or folder to begin."
    }

    return "${queueOrder.label} ${currentQueueType.label}: $currentQueueName"
}