package com.music.innertube.mappers

import com.music.innertube.InnerTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Album
import com.music.innertube.models.Artist
import com.music.innertube.models.MusicResponsiveListItemRenderer
import com.music.innertube.models.MusicShelfRenderer
import com.music.innertube.models.SectionListRenderer
import com.music.innertube.models.SongItem
import com.music.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.music.innertube.models.getContinuation
import com.music.innertube.models.getItems
import com.music.innertube.models.oddElements
import com.music.innertube.models.response.BrowseResponse
import com.music.innertube.models.response.GetQueueResponse
import com.music.innertube.pages.NextPage
import com.music.innertube.pages.PageHelper
import com.music.innertube.utils.parseTime
import com.music.innertube.pages.AlbumPage
import com.music.innertube.pages.NewReleaseAlbumPage
import io.ktor.client.call.body
import kotlinx.coroutines.CancellationException

internal object AlbumMapper {
    internal fun sections(response: BrowseResponse): List<SectionListRenderer.Content> = buildList {
        response.contents?.twoColumnBrowseResultsRenderer?.tabs.orEmpty().forEach {
            addAll(it?.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty())
        }
        response.contents?.singleColumnBrowseResultsRenderer?.tabs.orEmpty().forEach {
            addAll(it.tabRenderer.content?.sectionListRenderer?.contents.orEmpty())
        }
        addAll(response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents.orEmpty())
        addAll(response.contents?.sectionListRenderer?.contents.orEmpty())
        addAll(response.continuationContents?.sectionListContinuation?.contents.orEmpty())
    }

    internal fun parseAlbum(response: BrowseResponse, browseId: String): AlbumPage {
        val sections = sections(response)
        val responsive = sections.firstNotNullOfOrNull { it.musicResponsiveHeaderRenderer }
            ?: response.header?.musicResponsiveHeaderRenderer
            ?: response.header?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer
        val detail = response.header?.musicDetailHeaderRenderer
            ?: response.header?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicDetailHeaderRenderer
        val playlistId = sections.firstNotNullOfOrNull { it.musicPlaylistShelfRenderer?.playlistId }
            ?: responsive?.buttons?.firstNotNullOfOrNull {
                it.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint?.playlistId
                    ?: it.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.playlistId
            }
            ?: detail?.menu?.menuRenderer?.topLevelButtons?.firstNotNullOfOrNull {
                it.buttonRenderer?.navigationEndpoint?.watchPlaylistEndpoint?.playlistId
            }
            ?: response.microformat?.microformatDataRenderer?.urlCanonical?.let { url ->
                java.net.URI(url).rawQuery?.split('&')?.firstOrNull { it.startsWith("list=") }
                    ?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            }
            ?: ""
        val descriptionRuns = sections.firstNotNullOfOrNull { it.musicDescriptionShelfRenderer?.description?.runs }
            ?: responsive?.description?.runs ?: detail?.description?.runs
        val artistRuns = responsive?.straplineTextOne?.runs
            ?: detail?.subtitle?.runs?.filter { it.navigationEndpoint?.browseEndpoint != null }
        val artists = (if (responsive?.straplineTextOne != null) artistRuns?.oddElements() else artistRuns)
            ?.map { Artist(it.text, it.navigationEndpoint?.browseEndpoint?.browseId) }.orEmpty()
        val album = AlbumItem(
            browseId = browseId,
            playlistId = playlistId,
            title = requireNotNull((responsive?.title ?: detail?.title)?.runs?.firstOrNull()?.text) { "Album title missing: $browseId" },
            artists = artists,
            year = (responsive?.subtitle ?: detail?.subtitle)?.runs?.lastOrNull()?.text?.toIntOrNull(),
            thumbnail = responsive?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                ?: detail?.thumbnail?.croppedSquareThumbnailRenderer?.getThumbnailUrl()
                ?: response.background?.musicThumbnailRenderer?.getThumbnailUrl().orEmpty(),
            explicit = false,
            description = descriptionRuns?.joinToString("") { it.text },
        )
        fun recommendations(keyword: String) = sections
            .filter { section -> section.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs
                ?.any { it.text.contains(keyword, ignoreCase = true) } == true }
            .flatMap { it.musicCarouselShelfRenderer?.contents.orEmpty() }
            .mapNotNull { it.musicTwoRowItemRenderer }
            .mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
        return AlbumPage(
            album = album,
            songs = songContents(response).getItems().mapNotNull { albumSong(it, album) },
            otherVersions = recommendations("versions"),
            releasesForYou = (recommendations("releases") + recommendations("more from")).distinctBy { it.id },
            description = album.description,
            descriptionRuns = descriptionRuns,
        )
    }

    internal fun songContents(response: BrowseResponse): List<MusicShelfRenderer.Content> =
        response.continuationContents?.musicPlaylistShelfContinuation?.contents
            ?: response.continuationContents?.musicShelfContinuation?.contents
            ?: response.onResponseReceivedActions?.flatMap { it.appendContinuationItemsAction?.continuationItems.orEmpty() }
                ?.takeIf { it.isNotEmpty() }
            ?: sections(response).flatMap { section ->
                section.musicPlaylistShelfRenderer?.contents ?: section.musicShelfRenderer?.contents.orEmpty()
            }

    internal fun continuation(response: BrowseResponse): String? =
        songContents(response).getContinuation()
            ?: response.continuationContents?.musicPlaylistShelfContinuation?.continuations?.getContinuation()
            ?: response.continuationContents?.musicShelfContinuation?.continuations?.getContinuation()
            ?: response.continuationContents?.sectionListContinuation?.continuations?.getContinuation()
            ?: sections(response).firstNotNullOfOrNull {
                it.musicPlaylistShelfRenderer?.continuations?.getContinuation()
                    ?: it.musicShelfRenderer?.continuations?.getContinuation()
            }

    /**
     * An album and its songs. With an account, an album that fails or comes back without songs is
     * asked for again without it: some accounts get albums with nothing playable where a guest gets
     * every song.
     */
    suspend fun album(innerTube: InnerTube, browseId: String, withSongs: Boolean): Result<AlbumPage> {
        val first = catching { album(innerTube, browseId, withSongs, guest = false) }
        val page = first.getOrNull()
        if (innerTube.cookie == null || (page != null && (!withSongs || page.songs.isNotEmpty()))) return first
        val guest = catching { album(innerTube, browseId, withSongs, guest = true) }
        innerTube.log?.invoke(
            "Album $browseId: " +
                (first.exceptionOrNull()?.let { "failed with the account (${it.message?.take(160)})" }
                    ?: "no songs with the account (${page?.unavailable} rows that can't play)") +
                "; as a guest: " + (guest.exceptionOrNull()?.let { "failed (${it.message?.take(160)})" }
                    ?: "${guest.getOrNull()?.songs?.size} songs (${guest.getOrNull()?.unavailable} rows that can't play)"),
        )
        return if (page == null || guest.getOrNull()?.songs?.isNotEmpty() == true) guest.takeIf { it.isSuccess } ?: first else first
    }

    private suspend fun album(innerTube: InnerTube, browseId: String, withSongs: Boolean, guest: Boolean): AlbumPage {
        val response = innerTube.browse(WEB_REMIX, browseId, setLogin = true, guest = guest).body<BrowseResponse>()
        val page = parseAlbum(response, browseId)
        val playlistId = page.album.playlistId
        val songs = when {
            !withSongs -> emptyList()
            page.songs.isNotEmpty() -> collectSongs(innerTube, response, page.album, guest)
            playlistId.isNotBlank() -> albumSongs(innerTube, playlistId, page.album, guest).getOrNull().orEmpty()
            else -> error("Album tracks missing: $browseId")
        }.ifEmpty {
            // Neither page listed a song Convx can read: the queue YouTube Music plays the album from
            // lists them in another form.
            if (withSongs && playlistId.isNotBlank()) queueSongs(innerTube, playlistId, page.album) else emptyList()
        }
        val rows = songContents(response).getItems().size
        return page.copy(songs = songs, unavailable = if (withSongs && songs.isEmpty()) rows else 0)
    }

    /** The album's songs as YouTube Music queues them when you press Play on it. */
    private suspend fun queueSongs(innerTube: InnerTube, playlistId: String, album: AlbumItem): List<SongItem> =
        runCatching {
            innerTube.getQueue(WEB_REMIX, null, playlistId.removePrefix("VL")).body<GetQueueResponse>().queueDatas
                .mapNotNull { data -> data.content.item?.let(NextPage::fromPlaylistPanelVideoRenderer) }
                .map { song ->
                    song.copy(
                        album = song.album ?: Album(album.title, album.browseId),
                        artists = song.artists.ifEmpty { album.artists.orEmpty() },
                        thumbnail = song.thumbnail.ifBlank { album.thumbnail },
                    )
                }
                .distinctBy { it.id }
        }.getOrElse { if (it is CancellationException) throw it else emptyList() }

    /**
     * A row of an album's track list. Rows YouTube Music lists without a length (some albums, some
     * countries) or with the video id somewhere else still count as songs; only a row with no video
     * at all is left out.
     */
    internal fun albumSong(renderer: MusicResponsiveListItemRenderer, album: AlbumItem?): SongItem? {
        AlbumPage.getSong(renderer, album)?.let { return it }
        val columns = renderer.flexColumns.flatMap { it.musicResponsiveListItemFlexColumnRenderer.text?.runs.orEmpty() }
        val id = renderer.playlistItemData?.videoId
            ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
            ?: renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.videoId
            ?: columns.firstNotNullOfOrNull { it.navigationEndpoint?.watchEndpoint?.videoId }
            ?: renderer.menu?.menuRenderer?.items?.firstNotNullOfOrNull { it.menuNavigationItemRenderer?.navigationEndpoint?.watchEndpoint?.videoId }
            ?: return null
        val title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
            ?.joinToString("") { it.text }?.takeIf { it.isNotBlank() } ?: return null
        val time = Regex("\\d+:\\d{2}(?::\\d{2})?")
        val duration = (renderer.fixedColumns.orEmpty() + renderer.flexColumns.drop(1))
            .flatMap { it.musicResponsiveListItemFlexColumnRenderer.text?.runs.orEmpty() }
            .firstOrNull { it.text.trim().matches(time) }?.text?.trim()?.parseTime()
        return SongItem(
            id = id,
            title = title,
            artists = PageHelper.extractRuns(renderer.flexColumns, "MUSIC_PAGE_TYPE_ARTIST")
                .map { Artist(it.text, it.navigationEndpoint?.browseEndpoint?.browseId) }
                .ifEmpty { album?.artists.orEmpty() },
            album = album?.let { Album(it.title, it.browseId) },
            duration = duration,
            musicVideoType = renderer.musicVideoType,
            thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: album?.thumbnail.orEmpty(),
            explicit = renderer.badges?.any { it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE" } == true,
        )
    }

    suspend fun albumSongs(innerTube: InnerTube, playlistId: String, album: AlbumItem?, guest: Boolean = false): Result<List<SongItem>> = catching {
        val response = innerTube.browse(WEB_REMIX, "VL${playlistId.removePrefix("VL")}", guest = guest).body<BrowseResponse>()
        collectSongs(innerTube, response, album, guest).ifEmpty {
            if (album != null) queueSongs(innerTube, playlistId, album) else emptyList()
        }
    }

    private suspend fun collectSongs(innerTube: InnerTube, response: BrowseResponse, album: AlbumItem?, guest: Boolean = false): List<SongItem> {
        val songs = songContents(response).getItems().mapNotNull { albumSong(it, album) }.toMutableList()
        var next = continuation(response)
        val seen = mutableSetOf<String>()
        while (next != null && seen.size < 50 && seen.add(next)) {
            val continued = innerTube.browse(client = WEB_REMIX, continuation = next, guest = guest).body<BrowseResponse>()
            songs += songContents(continued).getItems().mapNotNull { albumSong(it, album) }
            next = continuation(continued)
        }
        return songs.distinctBy { it.id }
    }

    private suspend fun <T> catching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
