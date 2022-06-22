package com.citymapper.spotimapper

import android.util.Log
import com.citymapper.sdk.core.transit.Route
import com.citymapper.sdk.core.transit.TransitLeg
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import java.io.File
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

class PlayableGenerator(val cacheDir: File, accessToken: String) {
    data class Playable(
        val title: String,
        val subtitle: String,
        val uri: String,
        val thumbnailSmallUrl: String?,
        val thumbnailLargeUrl: String?,
        val durationMins: Long)

    private val TAG = "PlayableGenerator"

    private val MAX_PLAYTIME_DIFF_MINUTES = 10
    private val MAX_ALBUM_COUNT = 4

    private val POPULARITY_THRESHOLD = 50
    private val DANCEABILITY_THRESHOLD = 0.7
    private val ENERGY_THRESHOLD = 0.6

    private val DESIRED_THUMBNAIL_RESOLUTION_W = 300

    private val mSpotifyApi = SpotifyApi(accessToken)

    suspend fun findPlayOptions(route: Route): Array<Playable> {
        val albumCoro = GlobalScope.async { findAlbums(route) }
        val podcastCoro = GlobalScope.async { findPodcasts(route) }
        val playlistCoro = GlobalScope.async { generatePlaylists(route) }
        return playlistCoro.await() + albumCoro.await() + podcastCoro.await()
    }

    @OptIn(ExperimentalTime::class)
    suspend fun findAlbums(route: Route): Array<Playable> {
        val routeDuration = route.duration!!

        // Find albums with duration close to the route duration
        val albums = mSpotifyApi.getAlbums()
        val orderedAlbums = albums.sortedBy {
            (routeDuration - it.runtime).absoluteValue
        }.filter {
            (routeDuration - it.runtime).absoluteValue < Duration.minutes(MAX_PLAYTIME_DIFF_MINUTES)
        }

        return orderedAlbums.subList(0, minOf(MAX_ALBUM_COUNT, orderedAlbums.size)).map {
            Playable(it.name, it.artistList, it.uri, getThumbnailSmallUri(it.images), getThumbnailLargeUri(it.images), it.runtime.inWholeMinutes)
        }.toTypedArray()
    }

    @OptIn(ExperimentalTime::class)
    suspend fun findPodcasts(route: Route): Array<Playable> {
        val routeDuration = route.duration!!

        // Find podcasts with duration close to route duration
        val episodes = mSpotifyApi.getPodcasts()
        val orderedEpisodes = episodes.sortedBy {
            (routeDuration - it.runtime).absoluteValue
        }.filter {
            (routeDuration - it.runtime).absoluteValue < Duration.minutes(MAX_PLAYTIME_DIFF_MINUTES)
        }

        return orderedEpisodes.subList(0, minOf(MAX_ALBUM_COUNT, orderedEpisodes.size)).map {
            Playable(it.name, "", it.uri, getThumbnailSmallUri(it.images), getThumbnailLargeUri(it.images), it.runtime.inWholeMinutes)
        }.toTypedArray()
    }

    private suspend fun generatePlaylists(route: Route): Array<Playable> {
        val trackListCoro = GlobalScope.async { generateTrackList(route) }
        val playlistIdCoro = GlobalScope.async { getGeneratedPlaylistId() }

        val trackList = trackListCoro.await()
        val playlistId = playlistIdCoro.await()

        val trackUris = trackList.map { it.uri }
        mSpotifyApi.putTracksOnPlaylist(playlistId, trackUris)

        val durationMs = trackList.sumOf { it.duration_ms }
        val durationMins = durationMs / 1000 / 60

        // Query track to get images
        val playlist = mSpotifyApi.getPlaylist(playlistId)

        return arrayOf(Playable(
            "Auto generated playlist",
            "",
            "spotify:playlist:$playlistId",
            getThumbnailSmallUri(playlist.images),
            getThumbnailLargeUri(playlist.images),
            durationMins
        ))
    }

    private suspend fun getGeneratedPlaylistId(): String {
        val playlistIdFile = File(cacheDir, "playlist_id.txt")
        if (playlistIdFile.exists()) {
            Log.i(TAG, "playlist_id.txt cache file already exists, reading...")
            val id = playlistIdFile.readText()
            try {
                mSpotifyApi.getPlaylist(id)
                Log.i(TAG, "Got playlist id $id")
                return id
            } catch (e: MusicSelectorActivity.HttpError) {
                Log.w(TAG, "Playlist from playlist_id.txt cache file does not exist... Recreating")
            }
        }

        val playlist = mSpotifyApi.createEmptyPlaylist(PlaylistCreateRequest(
            "SpotiMapper Generated",
            false,
            false,
            "Automatically generated playlist used for Spotimapper journeys"
        ))
        Log.i(TAG, "Created new playlist ID ${playlist.id}")
        playlistIdFile.writeText(playlist.id)
        return playlist.id
    }

    private suspend fun generateTrackList(route: Route): ArrayList<Track> {
        var tracks = mSpotifyApi.getUserTracksWithFeatures()
        tracks = tracks.filter { it.track.popularity > POPULARITY_THRESHOLD }

        val trackSamples = mapOf(
            "low_energy" to tracks.filter {
                it.features.energy < ENERGY_THRESHOLD
            }.shuffled().toCollection(ArrayList<TrackWithAudioFeatures>()),
            "danceable" to tracks.filter {
                it.features.energy > ENERGY_THRESHOLD && it.features.danceability > DANCEABILITY_THRESHOLD
            }.shuffled().toCollection(ArrayList<TrackWithAudioFeatures>())
        )

        val genPlaylistTracks = ArrayList<Track>()
        route.legs.forEach {
            genPlaylistTracks += when (it) {
                is TransitLeg -> {
                    popTracksForDuration(trackSamples["low_energy"]!!, it.travelDurationSeconds!!)
                }
                else -> {
                    popTracksForDuration(trackSamples["danceable"]!!, it.travelDurationSeconds!!)
                }
            }
        }
        return genPlaylistTracks
    }

    private fun popTracksForDuration(tracks: ArrayList<TrackWithAudioFeatures>, durationSecs: Int): List<Track> {
        var remainingDuration = durationSecs.toLong()
        val out = ArrayList<Track>()
        while (remainingDuration > 0 && tracks.isNotEmpty()) {
            // Try to find a close fitting track first
            val tDiffs = tracks.map { (it.track.duration_ms / 1000) - remainingDuration }
            val bestFitDiff = tDiffs.filter { it >= 0 }.minOrNull()
            if (bestFitDiff != null && bestFitDiff < 20) {
                val track = tracks.removeAt(tDiffs.indexOf(bestFitDiff))
                out.add(track.track)
                Log.i(TAG, "Best Fit ${out.sumOf {it.duration_ms/1000}}s for $durationSecs")
                return out
            }

            val track = tracks.removeLast()
            out.add(track.track)
            remainingDuration -= track.track.duration_ms / 1000
        }
        Log.i(TAG, "Fit ${out.sumOf {it.duration_ms/1000}}s for $durationSecs")
        return out
    }

    private fun getThumbnailSmallUri(images: List<Image>): String? =
        images.minByOrNull { it.width }?.url

    private fun getThumbnailLargeUri(images: List<Image>): String? =
        images.minByOrNull { (DESIRED_THUMBNAIL_RESOLUTION_W - it.width).absoluteValue }?.url
}