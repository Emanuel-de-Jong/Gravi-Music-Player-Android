package com.example.gravimusicplayer

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

class PlayerPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var settings: PlayerSettings
        get() = PlayerSettings(
            defaultStartPlayOrder = defaultStartPlayOrder,
            loopMode = loopMode,
            genreSeparator = genreSeparator,
            showBrowserThumbnails = showBrowserThumbnails,
            queueSearchResults = queueSearchResults,
            skipSilenceEnabled = skipSilenceEnabled,
            loudnessNormalizationEnabled = loudnessNormalizationEnabled,
            fineGrainedVolumeEnabled = fineGrainedVolumeEnabled,
            graviPickerSettings = graviPickerSettings,
        )
        set(value) {
            val safeValue = value.copy(
                genreSeparator = value.genreSeparator.ifBlank {
                    DEFAULT_PLAYER_SETTINGS.genreSeparator
                },
                graviPickerSettings = value.graviPickerSettings.sanitized(),
            )
            preferences.edit {
                putString(KEY_DEFAULT_START_PLAY_ORDER, safeValue.defaultStartPlayOrder.name)
                putString(KEY_LOOP_MODE, safeValue.loopMode.name)
                putString(KEY_GENRE_SEPARATOR, safeValue.genreSeparator)
                putBoolean(KEY_SHOW_BROWSER_THUMBNAILS, safeValue.showBrowserThumbnails)
                putBoolean(KEY_QUEUE_SEARCH_RESULTS, safeValue.queueSearchResults)
                putBoolean(KEY_SKIP_SILENCE_ENABLED, safeValue.skipSilenceEnabled)
                putBoolean(
                    KEY_LOUDNESS_NORMALIZATION_ENABLED,
                    safeValue.loudnessNormalizationEnabled
                )
                putBoolean(KEY_FINE_GRAINED_VOLUME_ENABLED, safeValue.fineGrainedVolumeEnabled)
                putGraviPickerSettings(safeValue.graviPickerSettings)
            }
        }

    var rootUriString: String?
        get() = preferences.getString(KEY_ROOT_URI, null)
        set(value) {
            preferences.edit { putString(KEY_ROOT_URI, value) }
        }

    val favoritesDeviceId: String
        get() {
            val currentValue = preferences.getString(KEY_FAVORITES_DEVICE_ID, null)
            if (!currentValue.isNullOrBlank()) return currentValue

            val newValue = "android-${UUID.randomUUID()}"
            preferences.edit { putString(KEY_FAVORITES_DEVICE_ID, newValue) }
            return newValue
        }

    var defaultStartPlayOrder: DefaultStartPlayOrder
        get() = loadMode(
            KEY_DEFAULT_START_PLAY_ORDER,
            DEFAULT_PLAYER_SETTINGS.defaultStartPlayOrder
        )
        set(value) {
            preferences.edit { putString(KEY_DEFAULT_START_PLAY_ORDER, value.name) }
        }

    var loopMode: LoopMode
        get() = loadMode(KEY_LOOP_MODE, DEFAULT_PLAYER_SETTINGS.loopMode)
        set(value) {
            preferences.edit { putString(KEY_LOOP_MODE, value.name) }
        }

    var genreSeparator: String
        get() = preferences.getString(
            KEY_GENRE_SEPARATOR,
            DEFAULT_PLAYER_SETTINGS.genreSeparator
        ).orEmpty().ifBlank { DEFAULT_PLAYER_SETTINGS.genreSeparator }
        set(value) {
            preferences.edit {
                putString(
                    KEY_GENRE_SEPARATOR,
                    value.ifBlank { DEFAULT_PLAYER_SETTINGS.genreSeparator }
                )
            }
        }

    var showBrowserThumbnails: Boolean
        get() = preferences.getBoolean(
            KEY_SHOW_BROWSER_THUMBNAILS,
            DEFAULT_PLAYER_SETTINGS.showBrowserThumbnails
        )
        set(value) {
            preferences.edit { putBoolean(KEY_SHOW_BROWSER_THUMBNAILS, value) }
        }

    var queueSearchResults: Boolean
        get() = preferences.getBoolean(
            KEY_QUEUE_SEARCH_RESULTS,
            DEFAULT_PLAYER_SETTINGS.queueSearchResults
        )
        set(value) {
            preferences.edit { putBoolean(KEY_QUEUE_SEARCH_RESULTS, value) }
        }

    var skipSilenceEnabled: Boolean
        get() = preferences.getBoolean(
            KEY_SKIP_SILENCE_ENABLED,
            DEFAULT_PLAYER_SETTINGS.skipSilenceEnabled
        )
        set(value) {
            preferences.edit { putBoolean(KEY_SKIP_SILENCE_ENABLED, value) }
        }

    var loudnessNormalizationEnabled: Boolean
        get() = preferences.getBoolean(
            KEY_LOUDNESS_NORMALIZATION_ENABLED,
            DEFAULT_PLAYER_SETTINGS.loudnessNormalizationEnabled
        )
        set(value) {
            preferences.edit { putBoolean(KEY_LOUDNESS_NORMALIZATION_ENABLED, value) }
        }

    var fineGrainedVolumeEnabled: Boolean
        get() = preferences.getBoolean(
            KEY_FINE_GRAINED_VOLUME_ENABLED,
            DEFAULT_PLAYER_SETTINGS.fineGrainedVolumeEnabled
        )
        set(value) {
            preferences.edit { putBoolean(KEY_FINE_GRAINED_VOLUME_ENABLED, value) }
        }

    var graviPickerSettings: GraviPickerSettings
        get() = GraviPickerSettings(
            depth = preferences.getInt(
                KEY_GRAVI_DEPTH,
                DEFAULT_PLAYER_SETTINGS.graviPickerSettings.depth
            ),
            parentOdds = preferences.getBoolean(
                KEY_GRAVI_PARENT_ODDS,
                DEFAULT_PLAYER_SETTINGS.graviPickerSettings.parentOdds
            ),
            childOdds = preferences.getBoolean(
                KEY_GRAVI_CHILD_ODDS,
                DEFAULT_PLAYER_SETTINGS.graviPickerSettings.childOdds
            ),
            evenOddsMinFileCount = preferences.getInt(
                KEY_GRAVI_EVEN_ODDS_MIN_FILE_COUNT,
                DEFAULT_PLAYER_SETTINGS.graviPickerSettings.evenOddsMinFileCount
            ),
            lessLikelyDivisor = preferences.getFloat(
                KEY_GRAVI_LESS_LIKELY_DIVISOR,
                DEFAULT_PLAYER_SETTINGS.graviPickerSettings.lessLikelyDivisor
            ),
            queueEntries = preferences.getInt(
                KEY_GRAVI_QUEUE_ENTRIES,
                DEFAULT_PLAYER_SETTINGS.graviPickerSettings.queueEntries
            ),
            edgeCaseFolderDepths = parseEdgeCaseFolderDepths(
                preferences.getString(
                    KEY_GRAVI_EDGE_CASE_FOLDER_DEPTHS,
                    formatEdgeCaseFolderDepths(
                        DEFAULT_PLAYER_SETTINGS.graviPickerSettings.edgeCaseFolderDepths
                    )
                ).orEmpty()
            ),
            blacklistFolders = parseBlacklistFolders(
                preferences.getString(
                    KEY_GRAVI_BLACKLIST_FOLDERS,
                    DEFAULT_PLAYER_SETTINGS.graviPickerSettings.blacklistFolders
                        .sorted()
                        .joinToString("\n")
                ).orEmpty()
            ),
        ).sanitized()
        set(value) {
            val safeValue = value.sanitized()
            preferences.edit { putGraviPickerSettings(safeValue) }
        }

    fun resetSettingsExceptRootUri() {
        val rootUri = rootUriString
        val favoritesDeviceId = favoritesDeviceId
        preferences.edit { clear() }
        rootUriString = rootUri
        preferences.edit { putString(KEY_FAVORITES_DEVICE_ID, favoritesDeviceId) }
    }

    private inline fun <reified Mode> loadMode(
        key: String,
        fallback: Mode
    ): Mode where Mode : Enum<Mode> {
        val modeName = preferences.getString(key, fallback.name)
        return enumValues<Mode>().firstOrNull { it.name == modeName } ?: fallback
    }

    private fun parseEdgeCaseFolderDepths(value: String): Map<String, Int> {
        return value.lines()
            .mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null

                val folderPath = parts[0].normalizedFolderPath()
                val depthIncrement = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                if (folderPath.isBlank() || depthIncrement <= 0) return@mapNotNull null
                folderPath to depthIncrement
            }
            .toMap()
    }

    private fun formatEdgeCaseFolderDepths(value: Map<String, Int>): String {
        return value.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }
    }

    private fun android.content.SharedPreferences.Editor.putGraviPickerSettings(
        value: GraviPickerSettings
    ) {
        putInt(KEY_GRAVI_DEPTH, value.depth)
        putBoolean(KEY_GRAVI_PARENT_ODDS, value.parentOdds)
        putBoolean(KEY_GRAVI_CHILD_ODDS, value.childOdds)
        putInt(KEY_GRAVI_EVEN_ODDS_MIN_FILE_COUNT, value.evenOddsMinFileCount)
        putFloat(KEY_GRAVI_LESS_LIKELY_DIVISOR, value.lessLikelyDivisor)
        putInt(KEY_GRAVI_QUEUE_ENTRIES, value.queueEntries)
        putString(
            KEY_GRAVI_EDGE_CASE_FOLDER_DEPTHS,
            formatEdgeCaseFolderDepths(value.edgeCaseFolderDepths)
        )
        putString(
            KEY_GRAVI_BLACKLIST_FOLDERS,
            value.blacklistFolders.sorted().joinToString("\n")
        )
    }

    private fun parseBlacklistFolders(value: String): Set<String> {
        return value.lines()
            .map { it.normalizedFolderPath() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    companion object {
        private const val PREFERENCES_NAME = "gravi_music_player"
        private const val KEY_ROOT_URI = "root_uri"
        private const val KEY_FAVORITES_DEVICE_ID = "favorites_device_id"
        private const val KEY_DEFAULT_START_PLAY_ORDER = "default_start_play_order"
        private const val KEY_LOOP_MODE = "loop_mode"
        private const val KEY_GENRE_SEPARATOR = "genre_separator"
        private const val KEY_SHOW_BROWSER_THUMBNAILS = "show_browser_thumbnails"
        private const val KEY_QUEUE_SEARCH_RESULTS = "queue_search_results"
        private const val KEY_SKIP_SILENCE_ENABLED = "skip_silence_enabled"
        private const val KEY_LOUDNESS_NORMALIZATION_ENABLED = "loudness_normalization_enabled"
        private const val KEY_FINE_GRAINED_VOLUME_ENABLED = "fine_grained_volume_enabled"
        private const val KEY_GRAVI_DEPTH = "gravi_depth"
        private const val KEY_GRAVI_PARENT_ODDS = "gravi_parent_odds"
        private const val KEY_GRAVI_CHILD_ODDS = "gravi_child_odds"
        private const val KEY_GRAVI_EVEN_ODDS_MIN_FILE_COUNT = "gravi_even_odds_min_file_count"
        private const val KEY_GRAVI_LESS_LIKELY_DIVISOR = "gravi_less_likely_divisor"
        private const val KEY_GRAVI_QUEUE_ENTRIES = "gravi_queue_entries"
        private const val KEY_GRAVI_EDGE_CASE_FOLDER_DEPTHS = "gravi_edge_case_folder_depths"
        private const val KEY_GRAVI_BLACKLIST_FOLDERS = "gravi_blacklist_folders"
    }
}