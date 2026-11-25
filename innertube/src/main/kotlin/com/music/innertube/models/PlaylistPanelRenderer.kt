package com.music.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class PlaylistPanelRenderer(
    val title: String?,
    val titleText: Runs?,
    val shortBylineText: Runs?,
    val contents: List<Content>,
    val isInfinite: Boolean?,
    val numItemsToShow: Int?,
    val playlistId: String?,
    val continuations: List<Continuation>?,
) {
    @Serializable
    data class Content(
        val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer?,
        val automixPreviewVideoRenderer: AutomixPreviewVideoRenderer?,
        val playlistPanelVideoWrapperRenderer: PlaylistPanelVideoWrapperRenderer? = null,
    ) {
        /**
         * The queue item. A song that also has a music video comes wrapped, with the song as the
         * primary item and the video as its counterpart (YouTube Music's song/video switch).
         */
        val item: PlaylistPanelVideoRenderer?
            get() = playlistPanelVideoRenderer ?: playlistPanelVideoWrapperRenderer?.primaryRenderer?.playlistPanelVideoRenderer
    }

    @Serializable
    data class PlaylistPanelVideoWrapperRenderer(
        val primaryRenderer: Primary?,
    ) {
        @Serializable
        data class Primary(
            val playlistPanelVideoRenderer: PlaylistPanelVideoRenderer?,
        )
    }
}
