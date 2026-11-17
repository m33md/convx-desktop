/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.integrations

import com.convx.desktop.core.AppInfo
import com.convx.desktop.core.Http
import com.convx.desktop.core.Log
import com.convx.desktop.core.Secrets
import com.convx.desktop.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ListenBrainz, the open alternative to Last.fm, as the phone submits to it: your user token
 * from listenbrainz.org/settings, a "playing now" when a song starts and a listen once it counts.
 */
object ListenBrainz {
    private const val Tag = "ListenBrainz"
    private const val Api = "https://api.listenbrainz.org/1/"
    const val TokenUrl = "https://listenbrainz.org/settings/"
    private const val KeyToken = "listenbrainz.token"
    private const val KeyUser = "listenbrainz.user"

    val token: String? get() = Secrets[KeyToken]
    val user: String? get() = Secrets[KeyUser]

    /** Checks [newToken] with ListenBrainz and keeps it if it's good; returns the user name or an error. */
    suspend fun setToken(newToken: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(Api + "validate-token")
                .header("Authorization", "Token ${newToken.trim()}")
                .header("User-Agent", userAgent())
                .build()
            Http.client.newCall(request).execute().use { response ->
                val body = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val valid = body["valid"]?.jsonPrimitive?.boolean == true
                check(valid) { "ListenBrainz doesn't recognise that token." }
                val name = body["user_name"]?.jsonPrimitive?.contentOrNull ?: "ListenBrainz"
                Secrets[KeyToken] = newToken.trim()
                Secrets[KeyUser] = name
                name
            }
        }
    }

    fun forget() {
        Secrets[KeyToken] = null
        Secrets[KeyUser] = null
    }

    suspend fun playingNow(song: Song) = submit("playing_now", song, null)

    suspend fun listen(song: Song, startedAtSeconds: Long): Boolean = submit("single", song, startedAtSeconds)

    private suspend fun submit(type: String, song: Song, listenedAt: Long?): Boolean {
        val token = token ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildJsonObject {
                    put("listen_type", type)
                    put("payload", buildJsonArray {
                        add(buildJsonObject {
                            listenedAt?.let { put("listened_at", it) }
                            put("track_metadata", buildJsonObject {
                                put("artist_name", song.artists.joinToString(", ") { it.name }.ifBlank { "Unknown artist" })
                                put("track_name", song.title)
                                song.album?.name?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
                                put("additional_info", buildJsonObject {
                                    song.duration?.let { put("duration_ms", it * 1000L) }
                                    if (!com.convx.desktop.player.LocalMusic.isLocal(song.id)) {
                                        put("origin_url", "https://music.youtube.com/watch?v=${song.id}")
                                    }
                                    put("submission_client", "Convx for ${AppInfo.PLATFORM}")
                                    put("submission_client_version", AppInfo.VERSION)
                                })
                            })
                        })
                    })
                }
                val request = Request.Builder()
                    .url(Api + "submit-listens")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Token $token")
                    .header("User-Agent", userAgent())
                    .build()
                Http.client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "ListenBrainz answered ${response.code}: ${response.body?.string()?.take(200)}" }
                }
            }.onFailure { Log.w(Tag, "Couldn't submit $type for ${song.id}: ${it.message}") }.isSuccess
        }
    }

    private fun userAgent() = "ConvxDesktop/${AppInfo.VERSION}"
}
