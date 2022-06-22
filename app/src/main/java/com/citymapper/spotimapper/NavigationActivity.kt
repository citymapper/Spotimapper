package com.citymapper.spotimapper

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.constraintlayout.widget.ConstraintLayout
import com.citymapper.sdk.cache.StoredRouteHandle
import com.citymapper.sdk.directions.CitymapperDirections
import com.citymapper.sdk.navigation.CitymapperNavigationTracking
import com.citymapper.sdk.navigation.TrackingConfiguration
import com.citymapper.sdk.ui.navigation.CitymapperDirectionsView
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NavigationActivity : AppCompatActivity() {

    private val TAG = "NavigationActivity"

    private var mPlayerThumbnailUri: ImageUri? = null

    private var mPlaybackPaused: Boolean? = null

    private var mPlayableUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        GlobalScope.launch {
            loadRoute()
        }

        requireViewById<ImageButton>(R.id.mediaPlayButton).setOnClickListener {
            MainActivity.spotifyAppRemote?.playerApi?.resume()
        }
        requireViewById<ImageButton>(R.id.mediaPauseButton).setOnClickListener {
            MainActivity.spotifyAppRemote?.playerApi?.pause()
        }

        mPlayableUri = intent.getStringExtra("PLAYABLE_URI")

        requireViewById<ConstraintLayout>(R.id.playerControls).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.component = ComponentName("com.spotify.music", "com.spotify.music.MainActivity")
            intent.data = Uri.parse(mPlayableUri)
            startActivity(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        MainActivity.spotifyAppRemote?.playerApi?.subscribeToPlayerState()?.setEventCallback {
            updatePlayerState(it)
        }
    }

    private suspend fun loadRoute() {
        val routeHandle = intent.getParcelableExtra<StoredRouteHandle>("ROUTE")!!
        val route = CitymapperDirections.getInstance(this).loadRoute(routeHandle)!!

        runOnUiThread {
            val directionsView = CitymapperDirectionsView(this)

            val frame = requireViewById<FrameLayout>(R.id.navigationFrame)
            frame.addView(
                directionsView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )

            directionsView.configure(
                uiControls = CitymapperDirectionsView.UiControls.Default,
                onStopNavigationTracking = { CitymapperDirectionsView.StopNavigationTrackingBehavior.DisplayOverview },
                onClose = { onBackPressed() }
            )

            val navigationTracking = CitymapperNavigationTracking.getInstance(this)
            val navigableRoute =
                navigationTracking.createNavigableRoute(
                    route,
                    TrackingConfiguration()
                )
            directionsView.setNavigableRoute(navigableRoute)
        }
    }

    private fun updatePlayerState(playerState: PlayerState) {
        runOnUiThread {
            val title = requireViewById<TextView>(R.id.titleText)
            if (title.text != playerState.track.name) {
                title.text = playerState.track.name
                title.isSelected = true
            }

            val newSubtitleText = if (playerState.track.isEpisode && playerState.track.album != null) {
                playerState.track.album.name
            } else if (playerState.track.artists != null) {
                playerState.track.artists.joinToString(separator = ", ") { it.name }
            } else {
                ""
            }
            val subtitle = requireViewById<TextView>(R.id.subtitleText)
            if (subtitle.text != newSubtitleText) {
                subtitle.text = newSubtitleText
                subtitle.isSelected = true
            }

            if (playerState.isPaused != mPlaybackPaused) {
                if (mPlaybackPaused == null) {
                    val loadingSpinner = requireViewById<ProgressBar>(R.id.loadingSpinner)
                    loadingSpinner.visibility = View.GONE
                }

                val pauseButton = requireViewById<ImageButton>(R.id.mediaPauseButton)
                val playButton = requireViewById<ImageButton>(R.id.mediaPlayButton)
                mPlaybackPaused = playerState.isPaused

                if (mPlaybackPaused == true) {
                    pauseButton.visibility = View.GONE
                    playButton.visibility = View.VISIBLE
                } else {
                    pauseButton.visibility = View.VISIBLE
                    playButton.visibility = View.GONE
                }
            }
        }

        updateThumbnailImage(playerState.track.imageUri)
    }

    private fun updateThumbnailImage(imageUri: ImageUri) {
        if (imageUri != mPlayerThumbnailUri) {
            mPlayerThumbnailUri = imageUri
            GlobalScope.launch {
                MainActivity.spotifyAppRemote?.let { spotifyAppRemote ->
                    val thumbnailBitmap = awaitSpotifyResult(
                        spotifyAppRemote.imagesApi.getImage(
                            imageUri,
                            Image.Dimension.THUMBNAIL
                        )
                    )
                    runOnUiThread {
                        val thumbnail = requireViewById<ImageView>(R.id.thumbnail)
                        thumbnail.setImageBitmap(thumbnailBitmap)
                    }
                }
            }
        }
    }

    private suspend fun <T> awaitSpotifyResult(callResult: CallResult<T>): T = suspendCancellableCoroutine<T> { cont ->
        callResult.setResultCallback {
            cont.resume(it)
        }
        callResult.setErrorCallback {
            cont.resumeWithException(it)
        }
        cont.invokeOnCancellation {
            callResult.cancel()
        }
    }
}