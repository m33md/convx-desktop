/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.player

import com.convx.desktop.audio.AudioEffects
import com.convx.desktop.automix.AutoMix
import com.convx.desktop.core.Log
import com.convx.desktop.data.AppSettings
import com.convx.desktop.integrations.spotify.SpotifyMatcher
import com.convx.desktop.data.Library
import com.convx.desktop.data.RelatedSongs
import com.convx.desktop.data.RepeatMode
import com.convx.desktop.data.Song
import com.convx.desktop.data.toSong
import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow

data class PlayerState(
    val queue: List<Song> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val volume: Float = 0.8f,
    val muted: Boolean = false,
    val error: String? = null,
    val stream: ResolvedStream? = null,
    /** "Playing from" label: an album, a playlist, "Radio". */
    val queueTitle: String? = null,
    /** The queue ran out: play starts the song over, and the phone draws its replay arrow. */
    val ended: Boolean = false,
    /** The phone's Tempo and Pitch: speed with the pitch kept, and semitones with the speed kept. */
    val tempo: Float = 1f,
    val transpose: Int = 0,
) {
    val current: Song? get() = queue.getOrNull(index)
    val upNext: List<Song> get() = if (index < 0) queue else queue.drop(index + 1)
    val hasNext: Boolean get() = index < queue.lastIndex || repeat == RepeatMode.ALL
}

/**
 * Queue, transport and stream lifecycle. All mutations run on one confined dispatcher, so
 * engine callbacks from the audio thread and clicks from the UI never race each other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object PlayerController : AudioEngine.Listener {
    private const val Tag = "Player"

    /** Fresh tries a YouTube song gets after it fails, each from a client that hasn't failed it yet. */
    private const val MaxRetries = 3
    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + confined)
    private val engine = AudioEngine(this)

    /** What AutoMix reaches of the player; it runs on the player's own thread. */
    private val autoMixHost = object : AutoMix.Host {
        override val scope: CoroutineScope get() = this@PlayerController.scope
        override val engine: AudioEngine get() = this@PlayerController.engine
        override val engineToken: Long get() = this@PlayerController.engineToken
        override suspend fun prepare(song: Song): AutoMix.Prepared? = prepareForMix(song)
        override fun mixedInto(index: Int, prepared: AutoMix.Prepared, deck: AudioEngine.Deck, cueMs: Long) =
            mixedIntoNow(index, prepared, deck, cueMs)
    }

    private val _state = MutableStateFlow(
        AppSettings.value.let {
            PlayerState(
                shuffle = it.rememberShuffleRepeat && it.shuffle,
                repeat = if (it.rememberShuffleRepeat) it.repeat else RepeatMode.OFF,
                volume = it.volume,
                muted = it.muted,
            )
        },
    )
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** A play waiting on Keep queue or Clear and play. */
    class QueueConflict(val keep: () -> Unit, val replace: () -> Unit)

    /** The queue as it was before a play replaced it, for Undo. */
    class ReplacedQueue(
        internal val queue: List<Song>,
        internal val index: Int,
        internal val positionMs: Long,
        internal val title: String?,
        internal val shuffle: Boolean,
        internal val unshuffled: List<Song>?,
        internal val radioEndpoint: WatchEndpoint?,
        internal val radioContinuation: String?,
    )

    private val _conflicts = MutableSharedFlow<QueueConflict>(extraBufferCapacity = 1)
    val conflicts: SharedFlow<QueueConflict> = _conflicts.asSharedFlow()
    private val _replaced = MutableSharedFlow<ReplacedQueue>(extraBufferCapacity = 1)
    val replaced: SharedFlow<ReplacedQueue> = _replaced.asSharedFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    /** The order the queue had before shuffle, so turning shuffle off restores it. */
    private var unshuffled: List<Song>? = null
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var engineToken = 0L
    private var radioContinuation: String? = null
    private var radioEndpoint: WatchEndpoint? = null
    private var consecutiveFailures = 0

    /** Fresh tries the current song has had after failing, and the clients whose streams failed it. */
    private var retries = 0
    private val failedClients = mutableSetOf<String>()
    // The current song's play time, toward the phone's History duration before it counts as a listen.
    private var listenId: String? = null
    private var listenMs = 0L
    private var listenCounted = false
    private var listenTick = 0L
    // The history entry it was counted as, and the time played last written to it.
    private var listenAt = 0L
    private var listenSavedMs = 0L
    /** The session a crossfade already left, so it fades out only once. */
    private var crossfadedToken = 0L

    /**
     * What the listener at this PC did to playback, for a listening party to pass on. What the
     * party itself sets comes in through the `party` functions and is never reported back.
     */
    sealed interface Change {
        data class Playing(val playing: Boolean, val positionMs: Long) : Change
        data class Seeked(val positionMs: Long) : Change
        data class Track(val song: Song, val playing: Boolean) : Change
    }

    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 64)
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Things to tell the listener that aren't errors, like why a control did nothing. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /**
     * Set by a listening party: why this PC may not play, pause, skip or change the queue right
     * now, or null while it may.
     */
    @Volatile
    var controlLock: (() -> String?)? = null

    /** The song last reported as a [Change.Track] or set by the party, so it isn't reported again. */
    private var announcedId: String? = null

    init {
        applyVolume()
        scope.launch {
            while (isActive) {
                val s = _state.value
                if (s.current != null && engineToken != 0L) _position.value = engine.positionMs
                countListen(s)
                val settings = AppSettings.value
                if (settings.crossfade && settings.autoMix) AutoMix.tick(autoMixHost, _state.value) else maybeCrossfade()
                // A mix's levels and filters are ridden more finely than the playhead needs.
                delay(if (AutoMix.busy) 30 else if (s.isPlaying) 100 else 400)
            }
        }
        scope.launch {
            var first = true
            AppSettings.state
                .map { effectsOf(it) to it.outputDevice }
                .distinctUntilChanged()
                .collect { (effects, device) ->
                    engine.effects = effects
                    if (device != engine.outputDevice) {
                        engine.outputDevice = device
                        // Move the song to the new output where it is.
                        val s = _state.value
                        if (!first && s.current != null && engineToken != 0L) loadCurrent(engine.positionMs, playWhenReady = s.isPlaying)
                    }
                    first = false
                }
        }
        scope.launch {
            while (isActive) {
                delay(10_000)
                persistQueue()
            }
        }
    }

    // Public API (safe from any thread)

    /** True, once the listener has been told why, while a party's host holds the controls. */
    fun refused(): Boolean {
        val reason = controlLock?.invoke() ?: return false
        _notices.tryEmit(reason)
        return true
    }

    /** A control the listener used: nothing happens while a party's host holds the controls. */
    private fun guarded(block: suspend CoroutineScope.() -> Unit): Job? = if (refused()) null else scope.launch(block = block)

    fun playSongs(songs: List<Song>, startIndex: Int = 0, title: String? = null, shuffle: Boolean? = null, force: Boolean = false): Job? {
        if (!force && songs.isNotEmpty() && intercepted(
                songs.getOrNull(startIndex)?.id,
                keep = { addToQueue(if (shuffle == true) songs.shuffled() else songs) },
                replace = { playSongs(songs, startIndex, title, shuffle, force = true) },
            )
        ) return null
        return playSongsNow(songs, startIndex, title, shuffle)
    }

    private fun playSongsNow(songs: List<Song>, startIndex: Int, title: String?, shuffle: Boolean?) = guarded {
        if (songs.isEmpty()) return@guarded
        radioContinuation = null
        radioEndpoint = null
        val wantShuffle = shuffle ?: _state.value.shuffle
        var queue = songs
        var index = startIndex.coerceIn(songs.indices)
        unshuffled = null
        if (wantShuffle) {
            unshuffled = songs
            val first = if (shuffle == true && startIndex == 0) songs.random() else songs[index]
            queue = listOf(first) + (songs - first).shuffled()
            index = 0
        }
        _state.update { it.copy(queue = queue, index = index, queueTitle = title, shuffle = wantShuffle, error = null) }
        persistSettings()
        loadCurrent(0, playWhenReady = true)
    }

    /** Plays [song] and fills the queue with YouTube Music's radio for it. */
    fun startRadio(seed: Song, endpoint: WatchEndpoint? = null, title: String? = "Radio", force: Boolean = false): Job? {
        if (!force && intercepted(
                seed.id,
                keep = { enqueueRadio(seed, endpoint) },
                replace = { startRadio(seed, endpoint, title, force = true) },
            )
        ) return null
        return startRadioNow(seed, endpoint, title)
    }

    private fun startRadioNow(seed: Song, endpoint: WatchEndpoint?, title: String?) = guarded {
        // YouTube Music's radio needs its own id for a Spotify song.
        val song = SpotifyMatcher.resolve(seed) ?: run {
            _state.update { it.copy(error = "Couldn't find \"${seed.title}\" on YouTube Music") }
            return@guarded
        }
        radioContinuation = null
        unshuffled = null
        val watch = endpoint ?: WatchEndpoint(videoId = song.id, playlistId = "RDAMVM${song.id}")
        radioEndpoint = watch
        _state.update { it.copy(queue = listOf(song), index = 0, queueTitle = title, error = null) }
        loadCurrent(0, playWhenReady = true)
        extendRadio(watch, replaceAfterCurrent = true)
    }

    /** Starts a radio/mix from a watch endpoint (e.g. a playlist's radio button) without a known first song. */
    fun playEndpoint(endpoint: WatchEndpoint, title: String? = null, force: Boolean = false): Job? {
        if (!force && intercepted(
                endpoint.videoId,
                keep = { enqueueEndpoint(endpoint) },
                replace = { playEndpoint(endpoint, title, force = true) },
            )
        ) return null
        return playEndpointNow(endpoint, title)
    }

    private fun playEndpointNow(endpoint: WatchEndpoint, title: String?) = guarded {
        val result = YouTube.next(endpoint).getOrNull() ?: run {
            _state.update { it.copy(error = "Couldn't start this mix") }
            return@guarded
        }
        val songs = result.items.map { it.toSong() }
        if (songs.isEmpty()) return@guarded
        unshuffled = null
        radioEndpoint = result.endpoint
        radioContinuation = result.continuation
        val index = (result.currentIndex ?: 0).coerceIn(songs.indices)
        _state.update { it.copy(queue = songs, index = index, queueTitle = title ?: result.title, error = null) }
        loadCurrent(0, playWhenReady = true)
    }

    /**
     * The phone's rule for a play that would replace the queue: something is playing and the play
     * doesn't start on a song already in it. With Ask before replacing the queue on, it waits for
     * the listener's answer (true); otherwise it goes ahead and the old queue is offered for Undo.
     */
    private fun intercepted(startId: String?, keep: () -> Unit, replace: () -> Unit): Boolean {
        val s = _state.value
        if (s.current == null || (startId != null && s.queue.any { it.id == startId })) return false
        // A party's host has the controls: the play is refused anyway, with its own notice.
        if (controlLock?.invoke() != null) return false
        if (AppSettings.value.askBeforeReplacingQueue) {
            _conflicts.tryEmit(QueueConflict(keep, replace))
            return true
        }
        _replaced.tryEmit(
            ReplacedQueue(s.queue, s.index, _position.value, s.queueTitle, s.shuffle, unshuffled, radioEndpoint, radioContinuation),
        )
        return false
    }

    /** Puts back the queue a play replaced: the phone's Undo on "Queue replaced". */
    fun restoreQueue(previous: ReplacedQueue) = guarded {
        unshuffled = previous.unshuffled
        radioEndpoint = previous.radioEndpoint
        radioContinuation = previous.radioContinuation
        _state.update { it.copy(queue = previous.queue, index = previous.index, queueTitle = previous.title, shuffle = previous.shuffle, error = null) }
        loadCurrent(previous.positionMs, playWhenReady = true)
    }

    /** Keep queue, for a radio: its first songs go on the end, as the phone's enqueue does. */
    private fun enqueueRadio(seed: Song, endpoint: WatchEndpoint?) = guarded {
        val song = SpotifyMatcher.resolve(seed) ?: seed
        val watch = endpoint ?: WatchEndpoint(videoId = song.id, playlistId = "RDAMVM${song.id}")
        addToQueue(YouTube.next(watch).getOrNull()?.items?.map { it.toSong() }.orEmpty().ifEmpty { listOf(song) })
    }

    private fun enqueueEndpoint(endpoint: WatchEndpoint) = guarded {
        val songs = YouTube.next(endpoint).getOrNull()?.items?.map { it.toSong() }.orEmpty()
        if (songs.isEmpty()) _state.update { it.copy(error = "Couldn't start this mix") } else addToQueue(songs)
    }

    private fun countListen(s: PlayerState) {
        val now = System.currentTimeMillis()
        val song = s.current
        if (song != null && s.isPlaying && !s.isLoading && engineToken != 0L) {
            if (listenId != song.id) resetListen(song.id) else listenMs += (now - listenTick).coerceIn(0L, 1_000L)
            if (!listenCounted && listenMs >= (AppSettings.value.historyDurationSeconds * 1000).toLong()) {
                listenCounted = true
                listenAt = Library.recordPlay(song, listenMs) ?: 0L
                listenSavedMs = listenMs
                if (listenAt != 0L) reportToYouTube(song, s.stream)
            }
        } else {
            saveListen()
        }
        listenTick = now
    }

    /**
     * Tells YouTube the song was played, as the phone does once a play counts, so a signed-in
     * account's history, Listen again and Home recommendations follow what's played here.
     */
    private fun reportToYouTube(song: Song, stream: ResolvedStream?) {
        if (YouTube.cookie == null) return
        val url = stream?.takeIf { it.videoId == song.id }?.playbackTracking ?: return
        scope.launch(Dispatchers.IO) {
            YouTube.registerPlayback(null, url)
                .onFailure { Log.w(Tag, "Couldn't add ${song.id} to your YouTube history: ${it.message}") }
        }
    }

    /**
     * Writes the time played so far to the play's history entry, as the phone's event keeps it:
     * on a pause, a new song and quitting, rather than every second, as the library is one file.
     */
    private fun saveListen() {
        if (!listenCounted || listenMs == listenSavedMs) return
        if (listenAt != 0L) Library.setPlayTime(listenAt, listenMs)
        listenSavedMs = listenMs
    }

    private fun resetListen(id: String) {
        saveListen()
        listenId = id
        listenMs = 0L
        listenCounted = false
        listenAt = 0L
        listenSavedMs = 0L
    }

    fun playNext(songs: List<Song>) = guarded {
        if (songs.isEmpty()) return@guarded
        val s = _state.value
        if (s.current == null) {
            playSongs(songs)
            return@guarded
        }
        val queue = s.queue.toMutableList().apply { addAll(s.index + 1, songs) }
        unshuffled = unshuffled?.plus(songs)
        _state.update { it.copy(queue = queue) }
        prefetchNext()
    }

    fun addToQueue(songs: List<Song>) = guarded {
        if (songs.isEmpty()) return@guarded
        if (_state.value.current == null) {
            playSongs(songs)
            return@guarded
        }
        unshuffled = unshuffled?.plus(songs)
        _state.update { it.copy(queue = it.queue + songs) }
        prefetchNext()
    }

    fun removeFromQueue(position: Int) = guarded {
        val s = _state.value
        if (position !in s.queue.indices || position == s.index) return@guarded
        val removed = s.queue[position]
        val queue = s.queue.toMutableList().apply { removeAt(position) }
        unshuffled = unshuffled?.let { list -> list - removed }
        _state.update { it.copy(queue = queue, index = if (position < s.index) s.index - 1 else s.index) }
        prefetchNext()
    }

    fun moveInQueue(from: Int, target: Int) = guarded {
        val s = _state.value
        if (from !in s.queue.indices) return@guarded
        // Dragged rows stay in Next up: past either end they stop at the edge.
        val to = if (from > s.index) target.coerceIn(s.index + 1, s.queue.lastIndex) else target.coerceIn(s.queue.indices)
        if (from == to) return@guarded
        val queue = s.queue.toMutableList().apply { add(to, removeAt(from)) }
        val index = when (s.index) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) s.index - 1 else s.index + 1
            else -> s.index
        }
        _state.update { it.copy(queue = queue, index = index) }
        prefetchNext()
    }

    fun clearUpNext() = guarded {
        val s = _state.value
        if (s.current == null) return@guarded
        unshuffled = null
        _state.update { it.copy(queue = s.queue.take(s.index + 1)) }
    }

    fun skipTo(position: Int) = guarded {
        val s = _state.value
        if (position !in s.queue.indices) return@guarded
        _state.update { it.copy(index = position, error = null) }
        loadCurrent(0, playWhenReady = true)
    }

    fun togglePlay() = guarded { togglePlayNow() }

    fun play() = guarded { if (!_state.value.isPlaying) togglePlayNow() }

    fun pause() = guarded {
        if (_state.value.isPlaying) {
            pauseNow()
            _changes.tryEmit(Change.Playing(false, _position.value))
        }
    }

    fun next() = guarded { advance(userInitiated = true) }

    fun previous() = guarded {
        val s = _state.value
        if (s.current == null) return@guarded
        if (_position.value > 3_000 || s.index == 0) {
            seekNow(0)
            _changes.tryEmit(Change.Seeked(0))
        } else {
            _state.update { it.copy(index = s.index - 1, error = null) }
            loadCurrent(0, playWhenReady = true)
        }
    }

    /**
     * The playhead read straight off the audio line rather than the 100 ms ticker, for
     * views that move every frame (word-synced lyrics).
     */
    fun livePositionMs(): Long {
        val s = _state.value
        return if (s.current != null && engineToken != 0L && !s.isLoading) engine.positionMs else _position.value
    }

    fun seekTo(positionMs: Long) = guarded { seekNow(positionMs)?.let { _changes.tryEmit(Change.Seeked(it)) } }

    fun seekBy(deltaMs: Long) = guarded { seekNow((_position.value + deltaMs).coerceAtLeast(0))?.let { _changes.tryEmit(Change.Seeked(it)) } }

    /**
     * A listening party's song and queue, applied as they are: never refused, and never
     * reported back as this PC's own change.
     */
    fun partyTrack(songs: List<Song>, index: Int, positionMs: Long, playing: Boolean, title: String?) = scope.launch {
        if (songs.isEmpty()) return@launch
        val start = index.coerceIn(songs.indices)
        radioContinuation = null
        radioEndpoint = null
        unshuffled = null
        announcedId = songs[start].id
        _state.update { it.copy(queue = songs, index = start, queueTitle = title, error = null) }
        loadCurrent(positionMs.coerceAtLeast(0), playWhenReady = playing)
    }

    /** The party's play state and, when [seekMs] is set, its position, as [partyTrack] applies them. */
    fun partyPlayback(seekMs: Long?, playing: Boolean?) = scope.launch {
        val s = _state.value
        if (s.current == null || s.isLoading) return@launch
        if (engineToken == 0L || s.error != null) {
            // Nothing loaded yet: load it where the party is, if the party is playing.
            if (seekMs != null) _position.value = seekMs
            if (playing == true) loadCurrent(_position.value, playWhenReady = true)
            return@launch
        }
        if (seekMs != null) seekNow(seekMs)
        when {
            playing == true && !s.isPlaying -> {
                engine.play()
                _state.update { it.copy(isPlaying = true) }
            }
            playing == false && s.isPlaying -> pauseNow()
        }
    }

    /** Replaces what plays after the current song with the party's running order. */
    fun partyUpcoming(songs: List<Song>) = scope.launch {
        val s = _state.value
        if (s.current == null) return@launch
        unshuffled = null
        _state.update { it.copy(queue = it.queue.take(it.index + 1) + songs) }
        prefetchNext()
    }

    fun setVolume(volume: Float) = scope.launch {
        _state.update { it.copy(volume = volume.coerceIn(0f, 1f), muted = false) }
        applyVolume()
        persistSettings()
    }

    /** The phone's Tempo and Pitch, for this listening session as on the phone; not saved. */
    fun setTempoPitch(tempo: Float, transpose: Int) = scope.launch {
        val t = tempo.coerceIn(0.25f, 2f)
        val semitones = transpose.coerceIn(-12, 12)
        _state.update { it.copy(tempo = t, transpose = semitones) }
        engine.tempo = t
        engine.pitch = 2f.pow(semitones / 12f)
    }

    fun toggleMute() = scope.launch {
        _state.update { it.copy(muted = !it.muted) }
        applyVolume()
        persistSettings()
    }

    fun toggleShuffle() = guarded {
        val s = _state.value
        if (!s.shuffle) {
            unshuffled = s.queue
            val current = s.current
            val queue = if (current == null) s.queue.shuffled()
            else s.queue.take(s.index + 1) + s.queue.drop(s.index + 1).shuffled()
            _state.update { it.copy(shuffle = true, queue = queue) }
        } else {
            val original = unshuffled
            val current = s.current
            if (original != null && current != null) {
                val pending = s.queue.toSet()
                val restored = original.filter { it in pending } + s.queue.filter { it !in original }
                _state.update { it.copy(shuffle = false, queue = restored, index = restored.indexOf(current).coerceAtLeast(0)) }
            } else {
                _state.update { it.copy(shuffle = false) }
            }
            unshuffled = null
        }
        persistSettings()
        prefetchNext()
    }

    fun cycleRepeat() = scope.launch {
        _state.update {
            it.copy(repeat = when (it.repeat) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            })
        }
        persistSettings()
    }

    fun dismissError() = scope.launch { _state.update { it.copy(error = null) } }

    /** Restores the last session's queue, paused, at the saved position. */
    fun restore() {
        val settings = AppSettings.value
        if (!settings.persistentQueue) return
        cue(settings.lastQueue, settings.lastIndex, settings.lastPositionMs)
    }

    /** Shows [songs] as the queue, paused at [positionMs], without loading any audio yet. */
    fun cue(songs: List<Song>, index: Int, positionMs: Long = 0) = scope.launch {
        if (songs.isEmpty()) return@launch
        _state.update { it.copy(queue = songs, index = index.coerceIn(songs.indices)) }
        _position.value = positionMs
    }

    fun shutdown() {
        saveListen()
        persistQueue()
        engine.stop()
    }

    // Engine callbacks (audio thread)

    override fun onReady(token: Long, durationMs: Long) {
        scope.launch {
            if (token != engineToken) return@launch
            consecutiveFailures = 0
            _state.update { it.copy(isLoading = false, durationMs = durationMs.takeIf { d -> d > 0 } ?: it.durationMs) }
            prefetchNext()
        }
    }

    override fun onEnded(token: Long) {
        scope.launch {
            if (token != engineToken) return@launch
            if (_state.value.repeat == RepeatMode.ONE) {
                loadCurrent(0, playWhenReady = true)
                // The same song from the top: a party hears it as a seek.
                _changes.tryEmit(Change.Seeked(0))
            } else {
                advance(userInitiated = false)
            }
        }
    }

    override fun onError(token: Long, error: Throwable, positionMs: Long) {
        scope.launch {
            if (token != engineToken) return@launch
            val song = _state.value.current ?: return@launch
            val stream = _state.value.stream
            // YouTube refusing one client's address is common, and another client's usually plays,
            // so a song from YouTube gets several fresh tries. A file on this PC, or the speakers,
            // get one; a full disk fails the same way every time.
            val fromYouTube = stream == null || "googlevideo.com" in stream.url
            val limit = when {
                AudioCache.isDiskFull(error) -> 0
                isOutputProblem(error) || !fromYouTube -> 1
                else -> MaxRetries
            }
            stream?.client?.let(failedClients::add)
            generateSequence(error) { it.cause }.filterIsInstance<AudioCache.HttpStatusException>()
                .firstNotNullOfOrNull { it.client }?.let(failedClients::add)
            StreamResolver.invalidate(song.id)
            // A module's song that won't play comes from YouTube for the rest of the session.
            if (stream?.client == ModuleStreams.Client) ModuleStreams.markFailed(song.id)
            // The saved copy may be what's broken; not when it was the speakers that failed.
            if (isOutputProblem(error)) AudioCache.cancel(song.id) else AudioCache.discard(song.id)
            if (retries < limit) {
                retries++
                Log.w(Tag, "Try ${retries + 1} of ${song.id} at $positionMs, other clients before $failedClients, after: $error")
                // A moment's pause from the second retry on, in case YouTube is refusing everything just now.
                if (retries > 1) {
                    _state.update { it.copy(isLoading = true) }
                    delay(1_000L * (retries - 1))
                }
                if (token != engineToken || _state.value.current?.id != song.id) return@launch
                loadCurrent(positionMs, playWhenReady = true, forceRefresh = true, avoidClients = failedClients.toSet())
            } else {
                Log.w(Tag, "Gave up on ${song.id} after ${retries + 1} tries: $error")
                failCurrent(describePlaybackError(error))
            }
        }
    }

    // Internals (confined)

    private fun togglePlayNow() {
        val s = _state.value
        when {
            s.current == null -> return
            s.isPlaying -> pauseNow()
            engineToken == 0L || s.error != null -> loadCurrent(_position.value, playWhenReady = true)
            else -> {
                engine.play()
                _state.update { it.copy(isPlaying = true) }
            }
        }
        _changes.tryEmit(Change.Playing(_state.value.isPlaying, _position.value))
    }

    private fun pauseNow() {
        engine.pause()
        _state.update { it.copy(isPlaying = false) }
        persistQueue()
    }

    /** Where it seeked to, or null with nothing to seek in. */
    private fun seekNow(positionMs: Long): Long? {
        val s = _state.value
        if (s.current == null) return null
        val target = if (s.durationMs > 0) positionMs.coerceIn(0, s.durationMs - 500) else positionMs
        AutoMix.interrupt(autoMixHost)
        _position.value = target
        if (engineToken == 0L) loadCurrent(target, playWhenReady = s.isPlaying) else engine.seek(target)
        return target
    }

    private suspend fun advance(userInitiated: Boolean, fadeInMs: Int = 0) {
        val s = _state.value
        if (s.current == null) return
        when {
            s.index < s.queue.lastIndex -> {
                _state.update { it.copy(index = s.index + 1, error = null) }
                loadCurrent(0, playWhenReady = true, fadeInMs = fadeInMs)
            }
            s.repeat == RepeatMode.ALL && s.queue.isNotEmpty() -> {
                _state.update { it.copy(index = 0, error = null) }
                loadCurrent(0, playWhenReady = true, fadeInMs = fadeInMs)
            }
            AppSettings.value.autoRadio -> {
                // Keep the music going like the phone does: extend with radio from the last song.
                val endpoint = radioEndpoint ?: WatchEndpoint(videoId = s.current!!.id, playlistId = "RDAMVM${s.current!!.id}")
                val before = s.queue.size
                extendRadio(endpoint, replaceAfterCurrent = false)
                if (_state.value.queue.size > before) {
                    _state.update { it.copy(index = s.index + 1, error = null) }
                    loadCurrent(0, playWhenReady = true)
                } else if (!userInitiated) stopAtEnd()
            }
            !userInitiated -> stopAtEnd()
        }
    }

    private fun stopAtEnd() {
        AutoMix.interrupt(autoMixHost)
        engine.stop()
        engineToken = 0L
        _position.value = 0
        _state.update { it.copy(isPlaying = false, isLoading = false, ended = true) }
    }

    private suspend fun extendRadio(endpoint: WatchEndpoint, replaceAfterCurrent: Boolean) {
        val result = YouTube.next(endpoint, radioContinuation).getOrNull() ?: return
        radioContinuation = result.continuation
        radioEndpoint = result.endpoint
        val s = _state.value
        val known = s.queue.mapTo(HashSet()) { it.id }
        val fresh = result.items.map { it.toSong() }.filter { it.id !in known }
        if (fresh.isEmpty()) return
        val queue = if (replaceAfterCurrent) s.queue.take(s.index + 1) + fresh else s.queue + fresh
        _state.update { it.copy(queue = queue) }
        prefetchNext()
    }

    private fun loadCurrent(
        startMs: Long,
        playWhenReady: Boolean,
        forceRefresh: Boolean = false,
        fadeInMs: Int = 0,
        avoidClients: Set<String> = emptySet(),
    ) {
        val queued = _state.value.current ?: return
        if (!forceRefresh) {
            retries = 0
            failedClients.clear()
        }
        loadJob?.cancel()
        AutoMix.interrupt(autoMixHost)
        // A crossfade's outgoing song keeps playing under this one; anything else stops now.
        if (fadeInMs == 0) engine.stop()
        engineToken = 0L
        _position.value = startMs
        _state.update {
            it.copy(
                isLoading = true,
                isPlaying = playWhenReady,
                ended = false,
                error = null,
                durationMs = (queued.duration ?: 0) * 1000L,
                stream = null,
            )
        }
        loadJob = scope.launch {
            val quality = AppSettings.value.audioQuality
            // A Spotify song is found on YouTube Music first, then takes its place in the queue.
            val song = if (SpotifyMatcher.isSpotify(queued)) {
                val matched = SpotifyMatcher.resolve(queued) ?: run {
                    failCurrent("Couldn't find \"${queued.title}\" on YouTube Music")
                    return@launch
                }
                if (_state.value.current?.id != queued.id) return@launch
                _state.update { s ->
                    s.copy(
                        queue = s.queue.toMutableList().also { it[s.index] = matched },
                        durationMs = (matched.duration ?: 0) * 1000L,
                    )
                }
                matched
            } else {
                queued
            }
            // A file on this PC plays straight from disk.
            val local = LocalMusic.track(song.id).takeIf { LocalMusic.isLocal(song.id) }
            if (LocalMusic.isLocal(song.id) && (local == null || !java.io.File(local.path).isFile)) {
                failCurrent("\"${song.title}\" isn't on this PC any more")
                return@launch
            }
            // A downloaded song plays from its file with no call to YouTube, so it works offline.
            val offline = if (local == null) Downloads.offline(song.id) else null
            val stream = if (local != null) {
                LocalMusic.stream(song.id, local)
            } else if (offline != null) {
                offline.first
            } else {
                StreamResolver.resolveFor(song, quality, forceRefresh, avoidClients = avoidClients).getOrElse { error ->
                    Log.e(Tag, "Couldn't resolve ${song.id}", error)
                    failCurrent(error.message ?: "This song can't be played right now")
                    return@launch
                }
            }
            if (_state.value.current?.id != song.id) return@launch
            if (song.id != announcedId) {
                announcedId = song.id
                _changes.tryEmit(Change.Track(song, playWhenReady))
            }
            // The phone notes a song's related songs the first time it plays, for Quick picks.
            if (local == null) RelatedSongs.ensure(song.id)
            val source = when {
                local != null -> CachedAudio.Complete(java.io.File(local.path))
                offline != null -> CachedAudio.Complete(offline.second)
                else -> AudioCache.open(stream)
            }
            engineToken = engine.load(source, startMs, playWhenReady, stream.durationMs, trackGain(stream), fadeInMs)
            _state.update { it.copy(stream = stream, durationMs = stream.durationMs ?: it.durationMs) }
            if (startMs == 0L) resetListen(song.id)
        }
    }

    private suspend fun failCurrent(message: String) {
        consecutiveFailures++
        engine.stop()
        engineToken = 0L
        _state.update { it.copy(isLoading = false, isPlaying = false, error = message) }
        // Skip past a dead track, but don't spin through a whole queue that can't play.
        if (AppSettings.value.skipOnError && consecutiveFailures < 3 && _state.value.index < _state.value.queue.lastIndex) {
            delay(1500)
            if (_state.value.error == message) advance(userInitiated = false)
        }
    }

    private fun prefetchNext() {
        prefetchJob?.cancel()
        val s = _state.value
        val next = s.queue.getOrNull(s.index + 1) ?: return
        prefetchJob = scope.launch {
            delay(3_000)
            val song = SpotifyMatcher.resolve(next) ?: return@launch
            val id = song.id
            if (LocalMusic.isLocal(id) || AudioCache.isCachedForPlayback(id) || Downloads.isDownloaded(id)) return@launch
            StreamResolver.resolveFor(song, AppSettings.value.audioQuality).onSuccess { AudioCache.prefetch(it) }
        }
    }

    /** Finds [queued]'s audio as [loadCurrent] would, for AutoMix to open beside the playing song. */
    private suspend fun prepareForMix(queued: Song): AutoMix.Prepared? {
        val song = if (SpotifyMatcher.isSpotify(queued)) SpotifyMatcher.resolve(queued) ?: return null else queued
        val local = LocalMusic.track(song.id).takeIf { LocalMusic.isLocal(song.id) }
        if (LocalMusic.isLocal(song.id) && (local == null || !java.io.File(local.path).isFile)) return null
        val offline = if (local == null) Downloads.offline(song.id) else null
        val stream = when {
            local != null -> LocalMusic.stream(song.id, local)
            offline != null -> offline.first
            else -> StreamResolver.resolveFor(song, AppSettings.value.audioQuality).getOrNull() ?: return null
        }
        val source = when {
            local != null -> CachedAudio.Complete(java.io.File(local.path))
            offline != null -> CachedAudio.Complete(offline.second)
            else -> AudioCache.open(stream)
        }
        return AutoMix.Prepared(song, stream, source, trackGain(stream))
    }

    /** AutoMix brought the next song in: it's the current song now, already playing from [cueMs]. */
    private fun mixedIntoNow(index: Int, prepared: AutoMix.Prepared, deck: AudioEngine.Deck, cueMs: Long) {
        val song = prepared.song
        loadJob?.cancel()
        retries = 0
        failedClients.clear()
        consecutiveFailures = 0
        engineToken = deck.token
        _position.value = cueMs
        _state.update {
            it.copy(
                queue = it.queue.toMutableList().also { queue -> if (index in queue.indices) queue[index] = song },
                index = index,
                isLoading = false,
                isPlaying = true,
                ended = false,
                error = null,
                durationMs = prepared.stream.durationMs ?: deck.durationMs.takeIf { d -> d > 0 } ?: (song.duration ?: 0) * 1000L,
                stream = prepared.stream,
            )
        }
        if (song.id != announcedId) {
            announcedId = song.id
            _changes.tryEmit(Change.Track(song, true))
        }
        if (!LocalMusic.isLocal(song.id)) RelatedSongs.ensure(song.id)
        prefetchNext()
    }

    /**
     * Fades into the next song when this one is within the crossfade of its end: the phone's
     * song transitions. Songs from one album play straight through, as albums are meant to.
     */
    private suspend fun maybeCrossfade() {
        val settings = AppSettings.value
        if (!settings.crossfade) return
        val s = _state.value
        if (!s.isPlaying || s.isLoading || s.error != null || engineToken == 0L || crossfadedToken == engineToken) return
        if (s.repeat == RepeatMode.ONE) return
        val next = when {
            s.index < s.queue.lastIndex -> s.queue[s.index + 1]
            s.repeat == RepeatMode.ALL && s.queue.size > 1 -> s.queue[0]
            else -> return
        }
        val fadeMs = (settings.crossfadeSeconds.coerceIn(1f, 12f) * 1000).toLong()
        val duration = engine.durationMs
        if (duration < fadeMs * 3) return
        if (duration - engine.positionMs > fadeMs) return
        val album = s.current?.album?.id
        if (album != null && album == next.album?.id) return
        crossfadedToken = engineToken
        engine.handOff(fadeMs.toInt())
        engineToken = 0L
        advance(userInitiated = false, fadeInMs = fadeMs.toInt())
    }

    private fun effectsOf(settings: com.convx.desktop.data.Settings) = AudioEffects(
        eq = settings.equalizer.bands.takeIf { settings.equalizer.enabled },
        preamp = settings.equalizer.preamp,
        spatial = settings.equalizer.spatialAudio,
        skipSilence = settings.skipSilence,
        vocalReduction = settings.vocalReduction,
    )

    /** The phone's normalisation: YouTube's loudness for the song, turned into -15..+3 dB. */
    private fun trackGain(stream: ResolvedStream): Float {
        if (!AppSettings.value.normalizeAudio) return 1f
        val loudness = stream.loudnessDb ?: return 1f
        return 10.0.pow((-loudness).coerceIn(-15.0, 3.0) / 20.0).toFloat()
    }

    private fun applyVolume() {
        val s = _state.value
        // Perceptual curve: the slider's midpoint sounds like half volume.
        engine.gain = if (s.muted) 0f else s.volume * s.volume
    }

    private fun persistSettings() {
        val s = _state.value
        AppSettings.update { it.copy(volume = s.volume, muted = s.muted, shuffle = s.shuffle, repeat = s.repeat) }
    }

    private fun persistQueue() {
        val s = _state.value
        // Keep the saved queue bounded; radio queues grow without limit.
        val start = (s.index - 50).coerceAtLeast(0)
        val queue = s.queue.drop(start).take(300)
        AppSettings.update {
            it.copy(lastQueue = queue, lastIndex = (s.index - start).coerceAtLeast(0), lastPositionMs = _position.value)
        }
    }
}
