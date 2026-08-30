package com.example.gravimusicplayer

data class FavoriteEvent(
    val type: FavoriteEventType,
    val path: String,
    val isrc: String,
    val timestamp: String,
    val deviceId: String,
)

enum class FavoriteEventType(
    val jsonValue: String,
) {
    ADDED("favorite_added"),
    REMOVED("favorite_removed"),
}

data class FavoritesDocument(
    val format: String,
    val app: String,
    val deviceId: String,
    val updatedAt: String,
    val events: List<FavoriteEvent>,
)

data class FavoriteSyncState(
    val favoriteKeys: Set<String> = emptySet(),
    val androidEvents: List<FavoriteEvent> = emptyList(),
    val desktopEvents: List<FavoriteEvent> = emptyList(),
)