package com.citymapper.spotimapper

import com.google.gson.Gson
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.ExperimentalTime


data class Track(val id: String, val uri: String, val duration_ms: Long, val popularity: Int)
data class TrackItem(val track: Track)
data class TrackPage(val items: List<TrackItem>)
data class Image(val url: String, val height: Int, val width: Int)

data class AlbumTrackPage(val items: List<Track>)
data class Artist(val name: String)
data class Album(
    val name: String,
    val artists: List<Artist>,
    val tracks: AlbumTrackPage,
    val images: List<Image>,
    val uri: String)
{

    val title: String get() = "$name - $artistList"
    val artistList: String get() = artists.joinToString(separator = ", ") { it.name }
    @OptIn(ExperimentalTime::class)
    val runtime: Duration get() = Duration.milliseconds(tracks.items.sumOf { it.duration_ms })
}

data class SavedAlbum(val album: Album)
data class AlbumPage(val items: List<SavedAlbum>)

data class PlaylistCreateRequest(
    val name: String,
    val public: Boolean,
    val collaborative: Boolean,
    val description: String)
data class EmptyPlaylist(val id: String)
data class Playlist(val images: List<Image>, val tracks: TrackPage)

data class AudioFeatures(val danceability: Float, val energy: Float)
data class AudioFeaturesResult(val audio_features: List<AudioFeatures>)
data class TrackWithAudioFeatures(val track: Track, val features: AudioFeatures)

data class UserProfile(val id: String)

data class PutTracksResponse(val snapshot_id: String)

data class PodcastShow(val id: String)
data class PodcastShowItem(val show: PodcastShow)
data class PodcastShowPage(val items: List<PodcastShowItem>)

data class PodcastEpisode(
    val name: String,
    val duration_ms: Long,
    val uri: String,
    val release_date: String,
    val images: List<Image>)
{
    @OptIn(ExperimentalTime::class)
    val runtime: Duration
        get() = Duration.milliseconds(duration_ms)
}
data class PodcastEpisodePage(val items: List<PodcastEpisode>)


class SpotifyApi(private val accessToken: String) {
    private val httpClient = OkHttpClient()

    suspend fun getAlbums(): List<Album> {
        val page = spotifyApiGet<AlbumPage>("/me/albums")
        return page.items.map { it.album }
    }

    suspend fun getPodcasts(): List<PodcastEpisode> {
        val page = spotifyApiGet<PodcastShowPage>("/me/shows?limit=5")
        return page.items.flatMap {
            spotifyApiGet<PodcastEpisodePage>("/shows/${it.show.id}/episodes?limit=5").items
        }
    }

    suspend fun getTracksFromPlaylistWithFeatures(playlistId: String): List<TrackWithAudioFeatures> {
        val tracks = getTracksFromPlaylist(playlistId)
        return mapTrackFeatures(tracks)
    }

    suspend fun getUserTracksWithFeatures(): List<TrackWithAudioFeatures> {
        // Get top 100 saved tracks from user
        val ut1 = spotifyApiGet<TrackPage>("/me/tracks?offset=0&limit=50")
        val ut2 = spotifyApiGet<TrackPage>("/me/tracks?offset=50&limit=50")
        val tracks = (ut1.items + ut2.items).map { it.track }
        return mapTrackFeatures(tracks)
    }

    suspend fun mapTrackFeatures(tracks: List<Track>): List<TrackWithAudioFeatures> {
        // TODO: Handle more than 100 tracks
        val trackList = tracks.joinToString(separator = ",") { it.id }
        val trackFeatures = spotifyApiGet<AudioFeaturesResult>("/audio-features?ids=$trackList")
        return tracks.zip(trackFeatures.audio_features) { track, features ->
            TrackWithAudioFeatures(track, features)
        }
    }

    suspend fun getTracksFromPlaylist(playlistId: String): List<Track> =
        getPlaylist(playlistId).tracks.items.map { it.track }

    suspend fun getPlaylist(playlistId: String): Playlist = spotifyApiGet("/playlists/$playlistId")

    suspend fun putTracksOnPlaylist(playlistId: String, trackUris: List<String>) {
        val uris = trackUris.joinToString(separator = ",")

        val request = requestBuilder("/playlists/$playlistId/tracks?uris=$uris")
            .put("{}".toRequestBody("application/json".toMediaType()))
            .build()
        spotifyApiCall<PutTracksResponse>(request, 201)
    }

    suspend fun createEmptyPlaylist(request: PlaylistCreateRequest): EmptyPlaylist {
        val userProfile = spotifyApiGet<UserProfile>("/me")
        val jsonStr = Gson().toJson(request)
        val httpReq = requestBuilder("/users/${userProfile.id}/playlists")
            .post(jsonStr.toRequestBody("application/json".toMediaType()))
            .build()
        return spotifyApiCall(httpReq, 201)
    }

    private suspend inline fun <reified T> spotifyApiGet(endpoint: String): T {
        val request = requestBuilder(endpoint).build()
        return spotifyApiCall(request)
    }

    private fun requestBuilder(endpoint: String): Request.Builder =
        Request.Builder()
            .url("https://api.spotify.com/v1$endpoint")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Content-Type", "application/json")

    private suspend inline fun <reified T> spotifyApiCall(request: Request, expectedCode: Int = 200): T = spotifyApiCall(request, expectedCode, T::class.java)

    private suspend fun <T> spotifyApiCall(request: Request, expectedCode: Int, responseClass: Class<T>): T = suspendCancellableCoroutine { cont ->
        val call = httpClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.code != expectedCode) {
                    cont.resumeWithException(MusicSelectorActivity.HttpError(response.code))
                } else {
                    try {
                        val x = Gson().fromJson(response.body!!.charStream(), responseClass)
                        cont.resume(x)
                    } catch (e: Throwable) {
                        cont.resumeWithException(e)
                    }
                }
            }
        })
        cont.invokeOnCancellation { call.cancel() }
    }
}