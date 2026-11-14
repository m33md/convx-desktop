/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.convx.desktop.ui.components.Artwork
import com.convx.desktop.ui.components.ConvxIcon
import com.convx.desktop.ui.components.IconButton
import com.convx.desktop.ui.components.MessageState
import com.convx.desktop.ui.components.pressable
import com.convx.desktop.ui.theme.ConvxType
import com.music.innertube.YouTube
import com.music.innertube.models.comment.CommentRenderer
import com.music.innertube.models.comment.CommentThreadRenderer
import androidx.compose.foundation.lazy.rememberLazyListState
import com.convx.desktop.ui.components.ListScrollbar

private val Secondary = Color.White.copy(alpha = 0.6f)

/**
 * The phone's comment sheet, as the full player's side panel: the song's YouTube comments, the
 * next page loading as you reach the end, and a comment's replies in place of the list, with a
 * way back, as the phone's list and detail panes.
 */
@Composable
fun CommentsPanel(videoId: String, modifier: Modifier = Modifier) {
    var comments by remember(videoId) { mutableStateOf<List<CommentThreadRenderer>>(emptyList()) }
    var next by remember(videoId) { mutableStateOf<String?>(null) }
    var loading by remember(videoId) { mutableStateOf(true) }
    var open by remember(videoId) { mutableStateOf<CommentThreadRenderer?>(null) }
    LaunchedEffect(videoId) {
        YouTube.comments(videoId).onSuccess { (first, token) ->
            comments = first
            next = token
        }
        loading = false
    }
    AnimatedContent(
        targetState = open,
        transitionSpec = {
            val forward = targetState != null
            (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn())
                .togetherWith(slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
        },
        contentAlignment = Alignment.TopStart,
        modifier = modifier,
        label = "comments",
    ) { thread ->
        if (thread != null) {
            Replies(thread) { open = null }
            return@AnimatedContent
        }
        Column(Modifier.fillMaxSize()) {
            Text("Comments", style = ConvxType.Title2, color = Color.White, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp))
            when {
                loading -> Spinner()
                comments.isEmpty() -> MessageState("No comments found", "This song has no YouTube comments, or they're turned off.", icon = "chat_msg")
                else -> Box(Modifier.fillMaxSize()) {
                    val list = rememberLazyListState()
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(comments, key = { it.comment?.commentRenderer?.commentId ?: "comment-${it.hashCode()}" }) { thread ->
                            val renderer = thread.comment?.commentRenderer ?: return@items
                            CommentItem(renderer, onShowReplies = { open = thread })
                        }
                        val token = next
                        if (token != null) {
                            item(key = "more") {
                                LaunchedEffect(token) {
                                    YouTube.commentContinuation(token).onSuccess { (more, nextToken) ->
                                        comments = (comments + more).distinctBy { it.comment?.commentRenderer?.commentId ?: it.hashCode() }
                                        next = nextToken
                                    }.onFailure { next = null }
                                }
                                Spinner(Modifier.height(64.dp))
                            }
                        }
                    }
                    ListScrollbar(list, bottom = 24.dp)
                }
            }
        }
    }
}

@Composable
private fun Replies(thread: CommentThreadRenderer, onBack: () -> Unit) {
    val main = thread.comment?.commentRenderer ?: return
    var replies by remember(thread) { mutableStateOf<List<CommentRenderer>>(emptyList()) }
    var next by remember(thread) {
        mutableStateOf(
            thread.replies?.commentRepliesRenderer?.contents?.firstOrNull()?.continuationItemRenderer
                ?.continuationEndpoint?.continuationCommand?.token
                ?: thread.replies?.commentRepliesRenderer?.viewReplies?.buttonRenderer?.command?.continuationCommand?.token
                ?: thread.replies?.commentRepliesRenderer?.viewReplies?.buttonRenderer?.navigationEndpoint?.continuationCommand?.token,
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton("arrow_back", onBack, size = 36.dp, iconSize = 20.dp)
            Spacer(Modifier.width(6.dp))
            Text("${main.replyCount ?: 0} Replies", style = ConvxType.Title3, color = Color.White)
        }
        Box(Modifier.fillMaxSize()) {
            val list = rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "main") { CommentItem(main, onShowReplies = null) }
                items(replies, key = { it.commentId ?: "reply-${it.hashCode()}" }) { reply ->
                    Row {
                        Spacer(Modifier.width(36.dp))
                        CommentItem(reply, onShowReplies = null, isReply = true)
                    }
                }
                val token = next
                if (token != null) {
                    item(key = "more") {
                        LaunchedEffect(token) {
                            YouTube.commentReplies(token).onSuccess { (more, nextToken) ->
                                replies = (replies + more).distinctBy { it.commentId ?: it.hashCode() }
                                next = nextToken
                            }.onFailure { next = null }
                        }
                        Spinner(Modifier.height(56.dp))
                    }
                }
            }
            ListScrollbar(list, bottom = 24.dp)
        }
    }
}

@Composable
private fun CommentItem(renderer: CommentRenderer, onShowReplies: (() -> Unit)?, isReply: Boolean = false) {
    val replyCount = renderer.replyCount ?: 0
    Row(Modifier.fillMaxWidth()) {
        Artwork(
            renderer.authorThumbnail?.thumbnails?.lastOrNull()?.url,
            Modifier.size(if (isReply) 28.dp else 36.dp),
            size = 88,
            shape = CircleShape,
            placeholderIcon = "person",
            iconSize = 16.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    renderer.authorText?.runs?.firstOrNull()?.text.orEmpty(),
                    style = ConvxType.SubheadStrong,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(renderer.publishedTimeText?.runs?.firstOrNull()?.text.orEmpty(), style = ConvxType.Footnote, color = Secondary)
            }
            Spacer(Modifier.height(2.dp))
            // A PC can copy a line out of a comment, so the text can be selected.
            SelectionContainer {
                Text(renderer.contentText?.runs?.joinToString("") { it.text }.orEmpty(), style = ConvxType.Body, color = Color.White)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                renderer.voteCount?.let { votes ->
                    ConvxIcon("thumb_up_like", tint = Secondary, size = 14.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(votes.runs?.firstOrNull()?.text ?: "0", style = ConvxType.Footnote, color = Secondary)
                }
                if (replyCount > 0 && onShowReplies != null) {
                    Spacer(Modifier.width(16.dp))
                    Row(Modifier.pressable(onShowReplies), verticalAlignment = Alignment.CenterVertically) {
                        ConvxIcon("chat_msg", tint = Secondary, size = 14.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(if (replyCount == 1) "1 reply" else "$replyCount replies", style = ConvxType.Footnote, color = Secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Spinner(modifier: Modifier = Modifier.height(240.dp)) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
    }
}
