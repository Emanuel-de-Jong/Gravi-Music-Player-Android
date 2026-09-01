package com.example.gravimusicplayer

class PlaybackHistoryTracker(private val repository: PlayHistoryRepository) {
    private var queueId: Long? = null
    private var item: AudioItem? = null
    private var startedAtMs: Long? = null
    private var lastPositionMs: Long? = null
    private var listenedDurationMs = 0L
    private var qualified = false
    private var recorded = false

    fun setQueueId(value: Long?) {
        queueId = value
    }

    fun start(item: AudioItem, positionMs: Long) {
        this.item = item
        startedAtMs = System.currentTimeMillis()
        lastPositionMs = positionMs.coerceAtLeast(0)
        listenedDurationMs = 0
        qualified = false
        recorded = false
    }

    fun observe(positionMs: Long, isActuallyPlaying: Boolean) {
        val safePositionMs = positionMs.coerceAtLeast(0)
        val previousPositionMs = lastPositionMs
        if (item == null || previousPositionMs == null || !isActuallyPlaying) {
            lastPositionMs = safePositionMs
            return
        }

        val deltaMs = safePositionMs - previousPositionMs
        if (deltaMs <= 0 || deltaMs > SEEK_GAP_TOLERANCE_MS) {
            lastPositionMs = safePositionMs
            return
        }

        listenedDurationMs += deltaMs
        lastPositionMs = safePositionMs
        updateQualification()
    }

    fun resetPosition(positionMs: Long) {
        lastPositionMs = positionMs.coerceAtLeast(0)
    }

    fun finish(positionMs: Long, isActuallyPlaying: Boolean) {
        observe(positionMs, isActuallyPlaying)
        updateQualification()
        val currentItem = item
        val currentStartedAtMs = startedAtMs
        if (!recorded && qualified && currentItem != null && currentStartedAtMs != null) {
            repository.recordQualifiedPlay(
                currentItem,
                queueId,
                currentStartedAtMs,
                listenedDurationMs,
            )
            recorded = true
        }
        clearSession()
    }

    private fun updateQualification() {
        if (qualified) return

        val thresholdMs = item?.durationMs?.takeIf { it > 0 }?.coerceAtMost(QUALIFICATION_MS)
            ?: QUALIFICATION_MS
        if (listenedDurationMs >= thresholdMs) {
            qualified = true
        }
    }

    private fun clearSession() {
        item = null
        startedAtMs = null
        lastPositionMs = null
        listenedDurationMs = 0
        qualified = false
        recorded = false
    }

    private companion object {
        const val QUALIFICATION_MS = 30_000L
        const val SEEK_GAP_TOLERANCE_MS = 2_500L
    }
}