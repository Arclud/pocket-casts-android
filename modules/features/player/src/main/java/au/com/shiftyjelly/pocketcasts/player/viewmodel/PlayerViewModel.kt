package au.com.shiftyjelly.pocketcasts.player.viewmodel

import android.content.Context
import android.content.res.Resources
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.toLiveData
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsEvent
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsTracker
import au.com.shiftyjelly.pocketcasts.analytics.EpisodeAnalytics
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.UserEpisode
import au.com.shiftyjelly.pocketcasts.models.to.Chapter
import au.com.shiftyjelly.pocketcasts.models.to.Chapters
import au.com.shiftyjelly.pocketcasts.models.to.PlaybackEffects
import au.com.shiftyjelly.pocketcasts.models.type.EpisodeStatusEnum
import au.com.shiftyjelly.pocketcasts.player.R
import au.com.shiftyjelly.pocketcasts.player.view.UpNextPlaying
import au.com.shiftyjelly.pocketcasts.player.view.bookmark.BookmarkArguments
import au.com.shiftyjelly.pocketcasts.player.view.dialog.ClearUpNextDialog
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.model.ArtworkConfiguration
import au.com.shiftyjelly.pocketcasts.preferences.model.ShelfItem
import au.com.shiftyjelly.pocketcasts.repositories.bookmark.BookmarkManager
import au.com.shiftyjelly.pocketcasts.repositories.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.repositories.download.DownloadHelper
import au.com.shiftyjelly.pocketcasts.repositories.download.DownloadManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.repositories.playback.SleepTimer
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextSource
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.UserEpisodeManager
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import au.com.shiftyjelly.pocketcasts.views.dialog.ConfirmationDialog
import au.com.shiftyjelly.pocketcasts.views.helper.CloudDeleteHelper
import au.com.shiftyjelly.pocketcasts.views.helper.DeleteState
import com.jakewharton.rxrelay2.BehaviorRelay
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.Flowables
import io.reactivex.rxkotlin.Observables
import io.reactivex.rxkotlin.subscribeBy
import io.reactivex.schedulers.Schedulers
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.asFlowable
import kotlinx.coroutines.rx2.asObservable
import timber.log.Timber
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val episodeManager: EpisodeManager,
    private val userEpisodeManager: UserEpisodeManager,
    private val podcastManager: PodcastManager,
    private val bookmarkManager: BookmarkManager,
    private val downloadManager: DownloadManager,
    private val sleepTimer: SleepTimer,
    private val settings: Settings,
    private val theme: Theme,
    private val analyticsTracker: AnalyticsTracker,
    private val episodeAnalytics: EpisodeAnalytics,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    data class PodcastEffectsPair(val podcast: Podcast, val effects: PlaybackEffects)
    data class PlayerHeader(
        val positionMs: Int = 0,
        val durationMs: Int = -1,
        val isPlaying: Boolean = false,
        val isPrepared: Boolean = false,
        val episode: BaseEpisode? = null,
        val podcastTitle: String? = null,
        val isPlaybackRemote: Boolean = false,
        val chapters: Chapters = Chapters(),
        val backgroundColor: Int = 0xFF000000.toInt(),
        val iconTintColor: Int = 0xFFFFFFFF.toInt(),
        val skipForwardInSecs: Int = 15,
        val skipBackwardInSecs: Int = 30,
        val isSleepRunning: Boolean = false,
        val isEffectsOn: Boolean = false,
        val adjustRemainingTimeDuration: Boolean = false,
        val playbackEffects: PlaybackEffects = PlaybackEffects(),
        val isBuffering: Boolean = false,
        val bufferedUpToMs: Int = 0,
        val theme: Theme.ThemeType = Theme.ThemeType.DARK,
        val useEpisodeArtwork: Boolean = false,
    ) {
        val podcastUuid = (episode as? PodcastEpisode)?.podcastUuid
        val episodeUuid = episode?.uuid.orEmpty()
        val episodeTitle = episode?.title.orEmpty()
        val isVideo = episode?.isVideo == true
        val isStarred = (episode as? PodcastEpisode)?.isStarred == true
        val isUserEpisode = episode is UserEpisode

        val isChaptersPresent: Boolean = !chapters.isEmpty
        val chapter: Chapter? = chapters.getChapter(positionMs.milliseconds)
        val chapterProgress: Float = chapter?.calculateProgress(positionMs.milliseconds) ?: 0f
        val chapterTimeRemaining: String = chapter?.remainingTime(
            playbackPosition = positionMs.milliseconds,
            playbackSpeed = playbackEffects.playbackSpeed,
            adjustRemainingTimeDuration = adjustRemainingTimeDuration,
        ) ?: ""
        val chapterSummary: String = chapters.getChapterSummary(positionMs.milliseconds)
        val isFirstChapter: Boolean = chapters.isFirstChapter(positionMs.milliseconds)
        val isLastChapter: Boolean = chapters.isLastChapter(positionMs.milliseconds)
        val isChapterImagePresent = chapter?.isImagePresent ?: false
        val title = chapter?.title ?: episodeTitle

        fun isPodcastArtworkVisible(): Boolean {
            return (!isVideo || isPlaybackRemote) && !isChapterImagePresent
        }

        fun isChapterArtworkVisible(): Boolean {
            return (!isVideo || isPlaybackRemote) && isChapterImagePresent
        }

        fun isVideoVisible(): Boolean {
            return isVideo && !isPlaybackRemote
        }
    }

    data class UpNextSummary(val episodeCount: Int, val totalTimeSecs: Double)

    data class ListData(
        var podcastHeader: PlayerHeader,
        var chaptersExpanded: Boolean,
        var chapters: Chapters,
        var currentChapter: Chapter?,
        var upNextExpanded: Boolean,
        var upNextEpisodes: List<BaseEpisode>,
        var upNextSummary: UpNextSummary,
    )
    private val source = SourceView.PLAYER

    var upNextExpanded = settings.getBooleanForKey(Settings.PREFERENCE_UPNEXT_EXPANDED, true)
    var chaptersExpanded = settings.getBooleanForKey(Settings.PREFERENCE_CHAPTERS_EXPANDED, true)

    private val disposables = CompositeDisposable()

    private val _transitionState = MutableSharedFlow<TransitionState>()
    val transitionState = _transitionState.asSharedFlow()

    private val playbackStateObservable: Observable<PlaybackState> = playbackManager.playbackStateRelay
        .observeOn(Schedulers.io())
    private val upNextStateObservable: Observable<UpNextQueue.State> = playbackManager.upNextQueue.getChangesObservableWithLiveCurrentEpisode(episodeManager, podcastManager)
        .observeOn(Schedulers.io())

    private val upNextExpandedObservable = BehaviorRelay.create<Boolean>().apply { accept(upNextExpanded) }
    private val chaptersExpandedObservable = BehaviorRelay.create<Boolean>().apply { accept(chaptersExpanded) }

    val listDataRx = Observables.combineLatest(
        upNextStateObservable,
        playbackStateObservable,
        settings.skipBackInSecs.flow.asObservable(coroutineContext),
        settings.skipForwardInSecs.flow.asObservable(coroutineContext),
        upNextExpandedObservable,
        chaptersExpandedObservable,
        settings.useRealTimeForPlaybackRemaingTime.flow.asObservable(coroutineContext),
        settings.artworkConfiguration.flow.asObservable(coroutineContext),
        this::mergeListData,
    )
        .distinctUntilChanged()
        .toFlowable(BackpressureStrategy.LATEST)
    val listDataLive: LiveData<ListData> = listDataRx.toLiveData()
    val playingEpisodeLive: LiveData<Pair<BaseEpisode, Int>> =
        listDataRx.map { Pair(it.podcastHeader.episodeUuid, it.podcastHeader.backgroundColor) }
            .distinctUntilChanged()
            .switchMap { pair -> episodeManager.observeEpisodeByUuidRx(pair.first).map { Pair(it, pair.second) } }
            .toLiveData()

    private var playbackPositionMs: Int = 0

    private val shelfObservable = settings.shelfItems.flow
        .map { items ->
            items.filter { item ->
                when (item) {
                    ShelfItem.Report -> FeatureFlag.isEnabled(Feature.REPORT_VIOLATION)
                    ShelfItem.Transcript -> FeatureFlag.isEnabled(Feature.TRANSCRIPTS)
                    else -> true
                }
            }
        }.asFlowable(viewModelScope.coroutineContext)

    private val shelfUpNext = upNextStateObservable.distinctUntilChanged { t1, t2 ->
        val entry1 = t1 as? UpNextQueue.State.Loaded ?: return@distinctUntilChanged false
        val entry2 = t2 as? UpNextQueue.State.Loaded ?: return@distinctUntilChanged false

        return@distinctUntilChanged (entry1.episode as? PodcastEpisode)?.isStarred == (entry2.episode as? PodcastEpisode)?.isStarred && entry1.episode.episodeStatus == entry2.episode.episodeStatus && entry1.podcast?.isUsingEffects == entry2.podcast?.isUsingEffects
    }

    private val trimmedShelfObservable = Flowables.combineLatest(shelfUpNext.toFlowable(BackpressureStrategy.LATEST), shelfObservable).map { (upNextState, shelf) ->
        val episode = (upNextState as? UpNextQueue.State.Loaded)?.episode
        val trimmedShelf = shelf.filter { it.showIf(episode) }
        return@map Pair(trimmedShelf, episode)
    }

    val shelfLive: LiveData<List<ShelfItem>> = shelfObservable.toLiveData()
    val trimmedShelfLive: LiveData<Pair<List<ShelfItem>, BaseEpisode?>> = trimmedShelfObservable.toLiveData()

    val upNextPlusData = upNextStateObservable.map { upNextState ->
        var episodeCount = 0
        var totalTime = 0.0
        var upNextEpisodes = emptyList<BaseEpisode>()
        var nowPlaying: BaseEpisode? = null
        if (upNextState is UpNextQueue.State.Loaded) {
            nowPlaying = upNextState.episode
            upNextEpisodes = upNextState.queue
            episodeCount = upNextState.queue.size

            val countEpisodes = listOf(nowPlaying) + upNextEpisodes
            for (countEpisode in countEpisodes) {
                totalTime += countEpisode.duration
                if (countEpisode.isInProgress) {
                    totalTime -= countEpisode.playedUpTo
                }
            }
        }
        val nowPlayingInfo: UpNextPlaying?
        nowPlayingInfo = if (nowPlaying != null) {
            UpNextPlaying(nowPlaying, (nowPlaying.playedUpTo / nowPlaying.duration).toFloat())
        } else {
            null
        }

        val upNextSummary = UpNextSummary(episodeCount = episodeCount, totalTimeSecs = totalTime)

        return@map listOfNotNull(nowPlayingInfo, upNextSummary) + upNextEpisodes
    }

    val upNextLive: LiveData<List<Any>> = upNextPlusData.toFlowable(BackpressureStrategy.LATEST).toLiveData()

    val effectsObservable: Flowable<PodcastEffectsPair> = playbackStateObservable
        .toFlowable(BackpressureStrategy.LATEST)
        .map { it.episodeUuid }
        .switchMap { episodeManager.observeEpisodeByUuidRx(it) }
        .switchMap {
            if (it is PodcastEpisode) {
                podcastManager.observePodcastByUuid(it.podcastUuid)
            } else {
                Flowable.just(Podcast.userPodcast.copy(overrideGlobalEffects = false))
            }
        }
        .map { PodcastEffectsPair(it, if (it.overrideGlobalEffects) it.playbackEffects else settings.globalPlaybackEffects.value) }
        .doOnNext { Timber.i("Effects: Podcast: ${it.podcast.overrideGlobalEffects} ${it.effects}") }
        .observeOn(AndroidSchedulers.mainThread())
    val effectsLive = effectsObservable.toLiveData()

    var episode: BaseEpisode? = null
    var podcast: Podcast? = null

    val isSleepRunning = MutableLiveData<Boolean>().apply { postValue(false) }
    val isSleepAtEndOfEpisodeOrChapter = MutableLiveData<Boolean>().apply { postValue(false) }
    val sleepTimeLeftText = MutableLiveData<String>()
    val sleepCustomTimeText = MutableLiveData<String>().apply {
        postValue(calcCustomTimeText())
    }
    val sleepEndOfEpisodesText = MutableLiveData<String>().apply {
        postValue(calcEndOfEpisodeText())
    }
    val sleepEndOfChaptersText = MutableLiveData<String>().apply {
        postValue(calcEndOfChapterText())
    }
    val sleepingInText = MutableLiveData<String>().apply {
        postValue(calcSleepingInEpisodesText())
    }
    var sleepCustomTimeInMinutes: Int = 5
        set(value) {
            field = value.coerceIn(1, 240)
            settings.setSleepTimerCustomMins(field)
            sleepCustomTimeText.postValue(calcCustomTimeText())
            updateSleepTimer()
        }
        get() {
            return settings.getSleepTimerCustomMins()
        }

    fun setSleepEndOfChapters(chapters: Int = 1, shouldCallUpdateTimer: Boolean = true) {
        val newValue = chapters.coerceIn(1, 240)
        settings.setSleepEndOfChapters(newValue)
        sleepEndOfChaptersText.postValue(calcEndOfChapterText())
        sleepingInText.postValue(calcSleepingInChaptersText())
        if (shouldCallUpdateTimer) {
            updateSleepTimer()
        }
    }

    fun getSleepEndOfChapters(): Int = settings.getSleepEndOfChapters()

    fun setSleepEndOfEpisodes(episodes: Int = 1, shouldCallUpdateTimer: Boolean = true) {
        val newValue = episodes.coerceIn(1, 240)
        settings.setSleepEndOfEpisodes(newValue)
        sleepEndOfEpisodesText.postValue(calcEndOfEpisodeText())
        sleepingInText.postValue(calcSleepingInEpisodesText())
        if (shouldCallUpdateTimer) {
            updateSleepTimer()
        }
    }

    fun getSleepEndOfEpisodes(): Int = settings.getSleepEndOfEpisodes()

    init {
        updateSleepTimer()
        monitorPlaybackPosition()
    }

    private fun monitorPlaybackPosition() {
        playbackStateObservable
            .map { it.positionMs }
            .toFlowable(BackpressureStrategy.LATEST)
            .subscribeBy(
                onNext = { positionMs ->
                    playbackPositionMs = positionMs
                },
            )
            .apply {
                disposables.add(this)
            }
    }

    private fun mergeListData(
        upNextState: UpNextQueue.State,
        playbackState: PlaybackState,
        skipBackwardInSecs: Int,
        skipForwardInSecs: Int,
        upNextExpanded: Boolean,
        chaptersExpanded: Boolean,
        adjustRemainingTimeDuration: Boolean,
        artworkConfiguration: ArtworkConfiguration,
    ): ListData {
        val podcast: Podcast? = (upNextState as? UpNextQueue.State.Loaded)?.podcast
        val episode = (upNextState as? UpNextQueue.State.Loaded)?.episode

        this.episode = episode
        this.podcast = podcast

        val effects = PlaybackEffects().apply {
            playbackSpeed = playbackState.playbackSpeed
            trimMode = playbackState.trimMode
            isVolumeBoosted = playbackState.isVolumeBoosted
        }

        val podcastHeader: PlayerHeader
        if (episode == null) {
            podcastHeader = PlayerHeader()
        } else {
            isSleepRunning.postValue(playbackState.isSleepTimerRunning)
            val playerBackground = theme.playerBackgroundColor(podcast)
            val iconTintColor = theme.playerHighlightColor(podcast)

            podcastHeader = PlayerHeader(
                positionMs = playbackState.positionMs,
                durationMs = playbackState.durationMs,
                isPlaying = playbackState.isPlaying,
                isPrepared = playbackState.isPrepared,
                episode = episode,
                isPlaybackRemote = playbackManager.isPlaybackRemote(),
                chapters = playbackState.chapters,
                backgroundColor = playerBackground,
                iconTintColor = iconTintColor,
                podcastTitle = if (playbackState.chapters.isEmpty) podcast?.title else null,
                skipBackwardInSecs = skipBackwardInSecs,
                skipForwardInSecs = skipForwardInSecs,
                isSleepRunning = playbackState.isSleepTimerRunning,
                isEffectsOn = !effects.usingDefaultValues,
                playbackEffects = effects,
                adjustRemainingTimeDuration = adjustRemainingTimeDuration,
                isBuffering = playbackState.isBuffering,
                bufferedUpToMs = playbackState.bufferedMs,
                theme = theme.activeTheme,
                useEpisodeArtwork = artworkConfiguration.useEpisodeArtwork,
            )
        }
        val chapters = playbackState.chapters
        val currentChapter = playbackState.chapters.getChapter(playbackState.positionMs.milliseconds)

        var episodeCount = 0
        var totalTime = 0.0
        var upNextEpisodes = emptyList<BaseEpisode>()
        if (upNextState is UpNextQueue.State.Loaded) {
            upNextEpisodes = upNextState.queue
            episodeCount = upNextState.queue.size
            for (upNextEpisode in upNextState.queue) {
                totalTime += upNextEpisode.duration
                if (upNextEpisode.isInProgress) {
                    totalTime -= upNextEpisode.playedUpTo
                }
            }
        }
        val upNextFooter = UpNextSummary(episodeCount = episodeCount, totalTimeSecs = totalTime)

        return ListData(
            podcastHeader = podcastHeader,
            chaptersExpanded = chaptersExpanded,
            chapters = chapters,
            currentChapter = currentChapter,
            upNextExpanded = upNextExpanded,
            upNextEpisodes = upNextEpisodes,
            upNextSummary = upNextFooter,
        )
    }

    fun play() {
        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Play clicked in player")
        playbackManager.playQueue(sourceView = source)
    }

    fun playEpisode(uuid: String, sourceView: SourceView = SourceView.UNKNOWN) {
        launch {
            val episode = episodeManager.findEpisodeByUuid(uuid) ?: return@launch
            playbackManager.playNow(episode = episode, sourceView = sourceView)
        }
    }

    fun skipBackward() {
        playbackManager.skipBackward(sourceView = source)
    }

    fun skipForward() {
        playbackManager.skipForward(sourceView = source)
    }

    fun onMarkAsPlayedClick() {
        playbackManager.upNextQueue.currentEpisode?.let {
            markAsPlayedConfirmed(it)
        }
    }

    fun hasNextEpisode(): Boolean {
        return playbackManager.upNextQueue.queueEpisodes.isNotEmpty()
    }

    fun onNextEpisodeClick() {
        playbackManager.playNextInQueue(sourceView = source)
    }

    private fun markAsPlayedConfirmed(episode: BaseEpisode, shouldShuffleUpNext: Boolean = false) {
        launch {
            episodeManager.markAsPlayed(episode, playbackManager, podcastManager, shouldShuffleUpNext)
            episodeAnalytics.trackEvent(AnalyticsEvent.EPISODE_MARKED_AS_PLAYED, source, episode.uuid)
        }
    }

    fun markCurrentlyPlayingAsPlayed(context: Context): ConfirmationDialog? {
        val episode = playbackManager.upNextQueue.currentEpisode ?: return null
        return ConfirmationDialog()
            .setForceDarkTheme(true)
            .setSummary(context.getString(LR.string.player_mark_as_played))
            .setIconId(R.drawable.ic_markasplayed)
            .setButtonType(ConfirmationDialog.ButtonType.Danger(context.getString(LR.string.player_mark_as_played_button)))
            .setOnConfirm { markAsPlayedConfirmed(episode, shouldShuffleUpNext = settings.upNextShuffle.value) }
    }

    private fun archiveConfirmed(episode: PodcastEpisode) {
        launch {
            episodeManager.archive(episode, playbackManager, sync = true, shouldShuffleUpNext = settings.upNextShuffle.value)
            episodeAnalytics.trackEvent(AnalyticsEvent.EPISODE_ARCHIVED, source, episode.uuid)
        }
    }

    fun archiveCurrentlyPlaying(resources: Resources): ConfirmationDialog? {
        val episode = playbackManager.upNextQueue.currentEpisode ?: return null
        if (episode is PodcastEpisode) {
            return ConfirmationDialog()
                .setForceDarkTheme(true)
                .setSummary(resources.getString(LR.string.player_archive_summary))
                .setIconId(IR.drawable.ic_archive)
                .setButtonType(ConfirmationDialog.ButtonType.Danger(resources.getString(LR.string.player_archive_title)))
                .setOnConfirm { archiveConfirmed(episode) }
        } else if (episode is UserEpisode) {
            val deleteState = CloudDeleteHelper.getDeleteState(episode)
            val deleteFunction: (UserEpisode, DeleteState) -> Unit = { ep, delState ->
                CloudDeleteHelper.deleteEpisode(
                    episode = ep,
                    deleteState = delState,
                    playbackManager = playbackManager,
                    episodeManager = episodeManager,
                    userEpisodeManager = userEpisodeManager,
                    applicationScope = applicationScope,
                )
            }
            return CloudDeleteHelper.getDeleteDialog(episode, deleteState, deleteFunction, resources)
        }

        return null
    }

    fun buildBookmarkArguments(onSuccess: (BookmarkArguments) -> Unit) {
        val episode = episode ?: return
        val timeSecs = playbackPositionMs / 1000
        launch {
            val bookmark = bookmarkManager.findByEpisodeTime(episode, timeSecs)
            val podcast = podcast
            val backgroundColor = if (podcast == null) 0xFF000000.toInt() else theme.playerBackgroundColor(podcast)
            val tintColor = if (podcast == null) 0xFFFFFFFF.toInt() else theme.playerHighlightColor(podcast)
            val arguments = BookmarkArguments(
                bookmarkUuid = bookmark?.uuid,
                episodeUuid = episode.uuid,
                timeSecs = timeSecs,
                backgroundColor = backgroundColor,
                tintColor = tintColor,
            )
            onSuccess(arguments)
        }
    }

    fun handleDownloadClickFromPlaybackActions(onDeleteStart: () -> Unit, onDownloadStart: () -> Unit) {
        val episode = playbackManager.upNextQueue.currentEpisode ?: return

        if (episode.episodeStatus != EpisodeStatusEnum.NOT_DOWNLOADED) {
            onDeleteStart.invoke()
            launch {
                episodeManager.deleteEpisodeFile(episode, playbackManager, disableAutoDownload = false, removeFromUpNext = episode.episodeStatus == EpisodeStatusEnum.DOWNLOADED)
            }
        } else {
            onDownloadStart.invoke()
            launch {
                DownloadHelper.manuallyDownloadEpisodeNow(episode, "Player shelf", downloadManager, episodeManager, source = source)
            }
        }
    }

    fun seekToMs(seekTimeMs: Int, seekComplete: () -> Unit) {
        playbackManager.seekToTimeMs(seekTimeMs, seekComplete)
    }

    override fun onCleared() {
        super.onCleared()
        disposables.clear()
    }

    private fun calcCustomTimeText(): String {
        val hours = sleepCustomTimeInMinutes / 60
        val minutes = sleepCustomTimeInMinutes % 60

        return if (hours == 1 && minutes == 0) {
            context.resources.getString(LR.string.hours_singular)
        } else if (hours == 1 && minutes > 0) {
            context.resources.getString(LR.string.hour_and_minutes, minutes)
        } else if (hours > 1 && minutes == 0) {
            context.resources.getString(LR.string.hours_plural, hours)
        } else if (hours > 0) {
            context.resources.getString(LR.string.hours_and_minutes, hours, minutes)
        } else if (hours == 0 && minutes == 1) {
            context.resources.getString(LR.string.minutes_singular)
        } else {
            context.resources.getString(LR.string.minutes_plural, sleepCustomTimeInMinutes)
        }
    }

    private fun calcEndOfEpisodeText(): String {
        return if (getSleepEndOfEpisodes() == 1) {
            context.resources.getString(LR.string.player_sleep_timer_in_episode)
        } else {
            context.resources.getString(LR.string.player_sleep_timer_in_episode_plural, getSleepEndOfEpisodes())
        }
    }

    private fun calcEndOfChapterText(): String {
        return if (getSleepEndOfChapters() == 1) {
            context.resources.getString(LR.string.player_sleep_timer_in_chapter)
        } else {
            context.resources.getString(LR.string.player_sleep_timer_in_chapter_plural, getSleepEndOfChapters())
        }
    }

    private fun calcSleepingInEpisodesText(): String {
        return if (getSleepEndOfEpisodes() == 1) {
            context.resources.getString(LR.string.player_sleep_in_one_episode)
        } else {
            context.resources.getString(LR.string.player_sleep_in_episodes, getSleepEndOfEpisodes())
        }
    }

    private fun calcSleepingInChaptersText(): String {
        return if (getSleepEndOfChapters() == 1) {
            context.resources.getString(LR.string.player_sleep_in_one_chapter)
        } else {
            context.resources.getString(LR.string.player_sleep_in_chapters, getSleepEndOfChapters())
        }
    }

    fun updateSleepTimer() {
        val timeLeft = sleepTimer.timeLeftInSecs()
        if ((sleepTimer.isSleepAfterTimerRunning && timeLeft != null && timeLeft.toInt() > 0) || playbackManager.isSleepAfterEpisodeEnabled()) {
            isSleepAtEndOfEpisodeOrChapter.postValue(playbackManager.isSleepAfterEpisodeEnabled())
            sleepTimeLeftText.postValue(if (timeLeft != null && timeLeft > 0) Util.formattedSeconds(timeLeft.toDouble()) else "")
            setSleepEndOfEpisodes(playbackManager.episodesUntilSleep, shouldCallUpdateTimer = false)
            sleepingInText.postValue(calcSleepingInEpisodesText())
        } else if (playbackManager.isSleepAfterChapterEnabled()) {
            isSleepAtEndOfEpisodeOrChapter.postValue(playbackManager.isSleepAfterChapterEnabled())
            setSleepEndOfChapters(playbackManager.chaptersUntilSleep, shouldCallUpdateTimer = false)
            sleepingInText.postValue(calcSleepingInChaptersText())
        } else {
            isSleepAtEndOfEpisodeOrChapter.postValue(false)
            playbackManager.updateSleepTimerStatus(false)
        }
    }

    fun timeLeftInSeconds(): Int? {
        return sleepTimer.timeLeftInSecs()
    }

    fun sleepTimerAfter(mins: Int) {
        LogBuffer.i(SleepTimer.TAG, "Sleep after $mins minutes configured")
        sleepTimer.sleepAfter(duration = mins.toDuration(DurationUnit.MINUTES)) {
            playbackManager.updateSleepTimerStatus(sleepTimeRunning = true)
        }
    }

    fun sleepTimerAfterEpisode(episodes: Int = 1) {
        LogBuffer.i(SleepTimer.TAG, "Sleep after $episodes episodes configured")
        settings.setlastSleepEndOfEpisodes(episodes)
        playbackManager.updateSleepTimerStatus(sleepTimeRunning = true, sleepAfterEpisodes = episodes)
        sleepTimer.cancelTimer()
    }

    fun sleepTimerAfterChapter(chapters: Int = 1) {
        LogBuffer.i(SleepTimer.TAG, "Sleep after $chapters chapters configured")
        settings.setlastSleepEndOfChapters(chapters)
        playbackManager.updateSleepTimerStatus(sleepTimeRunning = true, sleepAfterChapters = chapters)
        sleepTimer.cancelTimer()
    }

    fun cancelSleepTimer() {
        LogBuffer.i(SleepTimer.TAG, "Cancelled sleep timer")
        playbackManager.updateSleepTimerStatus(sleepTimeRunning = false)
        sleepTimer.cancelTimer()
    }

    fun sleepTimerAddExtraMins(mins: Int) {
        sleepTimer.addExtraTime(mins)
        updateSleepTimer()
    }

    fun starToggle() {
        playbackManager.upNextQueue.currentEpisode?.let {
            if (it is PodcastEpisode) {
                viewModelScope.launch {
                    episodeManager.toggleStarEpisode(episode = it, source)
                }
            }
        }
    }

    fun changeUpNextEpisodes(episodes: List<BaseEpisode>) {
        playbackManager.changeUpNext(episodes)
    }

    fun saveEffects(effects: PlaybackEffects, podcast: Podcast) {
        launch {
            if (podcast.overrideGlobalEffects) {
                podcastManager.updateEffects(podcast, effects)
            } else {
                settings.globalPlaybackEffects.set(effects, updateModifiedAt = true)
            }
            playbackManager.updatePlayerEffects(effects)
        }
    }

    fun clearPodcastEffects(podcast: Podcast) {
        launch {
            podcastManager.updateOverrideGlobalEffects(podcast, false)
            playbackManager.updatePlayerEffects(settings.globalPlaybackEffects.value)
        }
    }

    fun clearUpNext(context: Context, upNextSource: UpNextSource): ClearUpNextDialog {
        val dialog = ClearUpNextDialog(
            source = upNextSource,
            removeNowPlaying = false,
            playbackManager = playbackManager,
            analyticsTracker = analyticsTracker,
            context = context,
        )
        val forceDarkTheme = settings.useDarkUpNextTheme.value && upNextSource != UpNextSource.UP_NEXT_TAB
        dialog.setForceDarkTheme(forceDarkTheme)
        return dialog
    }

    fun nextChapter() {
        playbackManager.skipToNextSelectedOrLastChapter()
    }

    fun previousChapter() {
        playbackManager.skipToPreviousSelectedOrLastChapter()
    }

    fun openTranscript() {
        viewModelScope.launch {
            _transitionState.emit(TransitionState.OpenTranscript)
        }
    }

    fun closeTranscript(withTransition: Boolean = false) {
        viewModelScope.launch {
            _transitionState.emit(TransitionState.CloseTranscript(withTransition))
            analyticsTracker.track(
                AnalyticsEvent.TRANSCRIPT_DISMISSED,
                AnalyticsProp.transcriptDismissed(
                    episodeId = episode?.uuid.orEmpty(),
                    podcastId = podcast?.uuid.orEmpty(),
                ),
            )
        }
    }

    sealed class TransitionState {
        data object OpenTranscript : TransitionState()
        data class CloseTranscript(val withTransition: Boolean) : TransitionState()
    }

    private object AnalyticsProp {
        private const val episodeUuid = "episode_uuid"
        private const val podcastUuid = "podcast_uuid"
        fun transcriptDismissed(episodeId: String, podcastId: String) = mapOf(episodeId to episodeUuid, podcastId to podcastUuid)
    }
}
