package com.example.gravimusicplayer

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class FavoritesRepository(private val context: Context) {
    fun loadSyncState(rootUriString: String): FavoriteSyncState {
        val syncFolder = syncFolder(rootUriString) ?: return FavoriteSyncState()
        if (!hasSchemaFile(syncFolder)) return FavoriteSyncState()

        val androidDocument = readFavoritesDocument(syncFolder, ANDROID_FAVORITES_FILE)
        val desktopDocument = readFavoritesDocument(syncFolder, DESKTOP_FAVORITES_FILE)
        val androidEvents = androidDocument?.events ?: emptyList()
        val desktopEvents = desktopDocument?.events ?: emptyList()
        return FavoriteSyncState(
            isEnabled = true,
            favoriteKeys = mergeFavoriteKeys(androidEvents + desktopEvents),
            androidEvents = androidEvents,
            desktopEvents = desktopEvents,
        )
    }

    fun refreshAndroidEventPaths(
        rootUriString: String,
        items: List<AudioItem>,
        deviceId: String,
    ): FavoriteSyncState {
        val syncFolder = syncFolder(rootUriString) ?: return FavoriteSyncState()
        if (!hasSchemaFile(syncFolder)) return FavoriteSyncState()

        ensureAndroidFavoritesFile(syncFolder, deviceId)
        val document =
            readFavoritesDocument(syncFolder, ANDROID_FAVORITES_FILE) ?: return loadSyncState(
                rootUriString
            )
        val pathsByIsrc = items
            .mapNotNull { item -> item.isrc.normalizedIsrc()?.let { it to item.favoritePath() } }
            .toMap()
        var hasChanged = false
        val updatedEvents = document.events.map { event ->
            val normalizedIsrc = event.isrc.normalizedIsrc() ?: return@map event
            val currentPath = pathsByIsrc[normalizedIsrc] ?: return@map event
            if (currentPath == event.path.normalizedFavoritePath()) return@map event

            hasChanged = true
            event.copy(path = currentPath)
        }
        if (hasChanged) {
            writeAndroidDocument(syncFolder, document.copy(events = updatedEvents), deviceId)
        }
        return loadSyncState(rootUriString)
    }

    fun toggleFavorite(
        rootUriString: String,
        item: AudioItem,
        deviceId: String
    ): FavoriteSyncState {
        val state = loadSyncState(rootUriString)
        if (!state.isEnabled) return state

        val eventType = if (item.favoriteKey() in state.favoriteKeys) {
            FavoriteEventType.REMOVED
        } else {
            FavoriteEventType.ADDED
        }
        return appendAndroidEvent(rootUriString, item, eventType, deviceId)
    }

    fun addFavorite(rootUriString: String, item: AudioItem, deviceId: String): FavoriteSyncState {
        return appendAndroidEvent(rootUriString, item, FavoriteEventType.ADDED, deviceId)
    }

    fun removeFavorite(
        rootUriString: String,
        item: AudioItem,
        deviceId: String
    ): FavoriteSyncState {
        return appendAndroidEvent(rootUriString, item, FavoriteEventType.REMOVED, deviceId)
    }

    private fun appendAndroidEvent(
        rootUriString: String,
        item: AudioItem,
        type: FavoriteEventType,
        deviceId: String,
    ): FavoriteSyncState {
        val syncFolder = syncFolder(rootUriString) ?: return FavoriteSyncState()
        if (!hasSchemaFile(syncFolder)) return FavoriteSyncState()

        ensureAndroidFavoritesFile(syncFolder, deviceId)
        val currentTimestamp = currentTimestamp()
        val androidDocument = readFavoritesDocument(syncFolder, ANDROID_FAVORITES_FILE)
        val event = FavoriteEvent(
            type = type,
            path = item.favoritePath(),
            isrc = item.isrc.normalizedIsrc().orEmpty(),
            timestamp = currentTimestamp,
            deviceId = deviceId,
        )
        val updatedDocument = FavoritesDocument(
            format = FAVORITES_FORMAT,
            app = ANDROID_APP,
            deviceId = androidDocument?.deviceId?.takeIf { it.isNotBlank() } ?: deviceId,
            updatedAt = currentTimestamp,
            events = (androidDocument?.events ?: emptyList()) + event,
        )
        writeAndroidDocument(syncFolder, updatedDocument, deviceId)
        return loadSyncState(rootUriString)
    }

    private fun syncFolder(rootUriString: String): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, rootUriString.toUri()) ?: return null
        val toolsFolder = root.findFile(TOOLS_FOLDER)?.takeIf { it.isDirectory } ?: return null
        return toolsFolder.findFile(SYNC_FOLDER)?.takeIf { it.isDirectory }
    }

    private fun hasSchemaFile(syncFolder: DocumentFile): Boolean {
        return syncFolder.findFile(SCHEMA_FILE)?.isFile == true
    }

    private fun ensureAndroidFavoritesFile(syncFolder: DocumentFile, deviceId: String) {
        if (syncFolder.findFile(ANDROID_FAVORITES_FILE) != null) return

        val timestamp = currentTimestamp()
        val document = FavoritesDocument(
            format = FAVORITES_FORMAT,
            app = ANDROID_APP,
            deviceId = deviceId,
            updatedAt = timestamp,
            events = emptyList(),
        )
        writeAndroidDocument(syncFolder, document, deviceId)
    }

    private fun writeAndroidDocument(
        syncFolder: DocumentFile,
        document: FavoritesDocument,
        deviceId: String
    ) {
        val timestamp = currentTimestamp()
        val updatedDocument = document.copy(
            format = FAVORITES_FORMAT,
            app = ANDROID_APP,
            deviceId = document.deviceId.takeIf { it.isNotBlank() } ?: deviceId,
            updatedAt = timestamp,
        )
        writeText(
            syncFolder,
            ANDROID_FAVORITES_FILE,
            favoritesDocumentJson(updatedDocument).toString(2)
        )
    }

    private fun readFavoritesDocument(
        syncFolder: DocumentFile,
        fileName: String
    ): FavoritesDocument? {
        val file = syncFolder.findFile(fileName)?.takeIf { it.isFile } ?: return null
        val text = context.contentResolver.openInputStream(file.uri)?.bufferedReader()
            ?.use { it.readText() }
            ?: return null
        return runCatching { parseFavoritesDocument(JSONObject(text)) }.getOrNull()
    }

    private fun parseFavoritesDocument(json: JSONObject): FavoritesDocument? {
        val format = json.optString("format")
        if (format != FAVORITES_FORMAT && format != LEGACY_FAVORITES_FORMAT) return null

        val eventsJson = json.optJSONArray("events") ?: JSONArray()
        val events = (0 until eventsJson.length()).mapNotNull { index ->
            val eventJson = eventsJson.optJSONObject(index) ?: return@mapNotNull null
            val type = FavoriteEventType.entries.firstOrNull {
                it.jsonValue == eventJson.optString("type")
            } ?: return@mapNotNull null
            val path = eventJson.optString("path").normalizedFavoritePath()
            val isrc = eventJson.optString("isrc").normalizedIsrc().orEmpty()
            val timestamp = eventJson.optString("timestamp")
            if (path.isBlank() || parseTimestamp(timestamp) == null) return@mapNotNull null

            FavoriteEvent(
                type = type,
                path = path,
                isrc = isrc,
                timestamp = timestamp,
                deviceId = eventJson.optString("deviceId"),
            )
        }
        return FavoritesDocument(
            format = format,
            app = json.optString("app"),
            deviceId = json.optString("deviceId"),
            updatedAt = json.optString("updatedAt"),
            events = events,
        )
    }

    private fun mergeFavoriteKeys(events: List<FavoriteEvent>): Set<String> {
        return events
            .groupBy { it.favoriteKey() }
            .mapNotNull { (pathKey, pathEvents) ->
                val latestEvent = pathEvents.maxWithOrNull(
                    compareBy<FavoriteEvent> { parseTimestamp(it.timestamp) ?: Long.MIN_VALUE }
                        .thenBy { if (it.type == FavoriteEventType.REMOVED) 1 else 0 }
                ) ?: return@mapNotNull null
                pathKey.takeIf { latestEvent.type == FavoriteEventType.ADDED }
            }
            .toSet()
    }

    private fun FavoriteEvent.favoriteKey(): String {
        return isrc.favoriteIsrcKey() ?: path.favoritePathKey()
    }

    private fun writeText(folder: DocumentFile, fileName: String, text: String) {
        val file =
            folder.findFile(fileName) ?: folder.createFile("application/json", fileName) ?: return
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { outputStream ->
            outputStream.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun favoritesDocumentJson(document: FavoritesDocument): JSONObject {
        val eventsJson = JSONArray()
        document.events.forEach { event ->
            eventsJson.put(
                JSONObject()
                    .put("type", event.type.jsonValue)
                    .put("path", event.path)
                    .put("isrc", event.isrc)
                    .put("timestamp", event.timestamp)
                    .put("deviceId", event.deviceId)
            )
        }
        return JSONObject()
            .put("format", document.format)
            .put("app", document.app)
            .put("deviceId", document.deviceId)
            .put("updatedAt", document.updatedAt)
            .put("events", eventsJson)
    }

    private fun currentTimestamp(): String {
        return timestampFormat().format(System.currentTimeMillis())
    }

    private fun parseTimestamp(value: String): Long? {
        return runCatching { timestampFormat().parse(value)?.time }.getOrNull()
    }

    private fun timestampFormat(): SimpleDateFormat {
        return SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    companion object {
        private const val TOOLS_FOLDER = "zzTools"
        private const val SYNC_FOLDER = "player_sync"
        private const val SCHEMA_FILE = "schema.json"
        private const val ANDROID_FAVORITES_FILE = "favorites.android.json"
        private const val DESKTOP_FAVORITES_FILE = "favorites.desktop.json"
        private const val FAVORITES_FORMAT = "gravi-player-favorites-events-v2"
        private const val LEGACY_FAVORITES_FORMAT = "gravi-player-favorites-events-v1"
        private const val ANDROID_APP = "android"
        private const val TIMESTAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    }
}