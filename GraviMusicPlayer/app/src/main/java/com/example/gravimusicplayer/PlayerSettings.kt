package com.example.gravimusicplayer

data class PlayerSettings(
    val defaultStartPlayOrder: DefaultStartPlayOrder = DefaultStartPlayOrder.ORDERED,
    val loopMode: LoopMode = LoopMode.QUEUE,
    val genreSeparator: String = ";",
    val showBrowserThumbnails: Boolean = true,
    val queueSearchResults: Boolean = true,
    val skipSilenceEnabled: Boolean = true,
    val loudnessNormalizationEnabled: Boolean = true,
    val fineGrainedVolumeEnabled: Boolean = true,
    val graviPickerSettings: GraviPickerSettings = GraviPickerSettings(
        depth = 2,
        parentOdds = true,
        childOdds = true,
        evenOddsMinFileCount = 5,
        lessLikelyDivisor = 2f,
        queueEntries = 100,
        edgeCaseFolderDepths = emptyMap(),
        blacklistFolders = emptySet(),
    ),
)

val DEFAULT_PLAYER_SETTINGS = PlayerSettings()