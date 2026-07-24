package com.example.gravimusicplayer

data class GraviPickerSettings(
    val depth: Int = 2,
    val parentOdds: Boolean = true,
    val childOdds: Boolean = true,
    val evenOddsMinFileCount: Int = 5,
    val lessLikelyDivisor: Float = 2f,
    val queueEntries: Int = 100,
    val edgeCaseFolderDepths: Map<String, Int> = emptyMap(),
    val blacklistFolders: Set<String> = emptySet(),
) {
    fun sanitized(): GraviPickerSettings {
        return copy(
            depth = depth.coerceAtLeast(0),
            evenOddsMinFileCount = evenOddsMinFileCount.coerceAtLeast(-1),
            lessLikelyDivisor = lessLikelyDivisor.coerceAtLeast(0.1f),
            queueEntries = queueEntries.coerceAtLeast(1),
            edgeCaseFolderDepths = edgeCaseFolderDepths
                .filterValues { it > 0 }
                .mapKeys { it.key.normalizedFolderPath() }
                .filterKeys { it.isNotBlank() },
            blacklistFolders = blacklistFolders
                .map { it.normalizedFolderPath() }
                .filter { it.isNotBlank() }
                .toSet(),
        )
    }
}

internal data class GraviCategoryEntry(
    val folderPath: String,
    val items: List<GraviPickerItem>,
    val itemWeightsByUri: Map<String, Double>,
    val weight: Double,
)

internal data class GraviPickerItem(
    val audioItem: AudioItem,
    val folderPath: String,
)

internal fun String.normalizedFolderPath(): String {
    return trim().replace('\\', '/').trim('/')
}

internal fun String.folderParts(): List<String> {
    val normalizedPath = normalizedFolderPath()
    return if (normalizedPath.isBlank()) emptyList() else normalizedPath.split('/')
        .filter { it.isNotBlank() }
}