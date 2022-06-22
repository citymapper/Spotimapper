package com.citymapper.spotimapper

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.bumptech.glide.Glide
import com.citymapper.sdk.cache.StoredRouteHandle
import com.citymapper.sdk.core.transit.Leg
import com.citymapper.sdk.core.transit.OwnVehicleLeg
import com.citymapper.sdk.core.transit.Route
import com.citymapper.sdk.core.transit.WalkLeg
import com.citymapper.sdk.directions.CitymapperDirections
import com.citymapper.sdk.ui.routedetail.RouteDetail
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

class MusicSelectorActivity : AppCompatActivity() {
    class PlayableAdapter(context: Context, playables: Array<PlayableGenerator.Playable>):
            ArrayAdapter<PlayableGenerator.Playable>(context, 0, playables)
    {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val playable = getItem(position)!!
            var view = convertView
            if (view == null) {
                view = LayoutInflater.from(context).inflate(R.layout.item_playable, parent, false)
            }
            view!!.requireViewById<TextView>(R.id.title).text = playable.title
            view!!.requireViewById<TextView>(R.id.subtitle).text = playable.subtitle
            view!!.requireViewById<TextView>(R.id.timeLabel).text = "${playable.durationMins} mins"
            val imageView = view!!.requireViewById<ImageView>(R.id.coverArt)
            if (playable.thumbnailLargeUrl != null) {
                var largeThumbReq = Glide.with(context).load(playable.thumbnailLargeUrl)
                if (playable.thumbnailSmallUrl != null) {
                    val smallThumbReq = Glide.with(context).load(playable.thumbnailSmallUrl)
                    largeThumbReq = largeThumbReq.thumbnail(smallThumbReq)
                }
                largeThumbReq.into(imageView)
            }
            return view
        }
    }

    class HttpError(val code: Int): Exception("Http response $code")

    private val TAG = "MusicSelectorActivity"

    private lateinit var mPlayableGenerator: PlayableGenerator
    private lateinit var mRoute: Route

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_selector)

        val accessToken = intent.getStringExtra("ACCESS_TOKEN")
        if (accessToken == null) {
            Toast.makeText(this, "Spotify is not authorised!", Toast.LENGTH_LONG).show()
            finish()
        } else {
            mPlayableGenerator = PlayableGenerator(cacheDir, accessToken)

            GlobalScope.launch {
                dothething()
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun dothething() {
        val routeHandle = intent.getParcelableExtra<StoredRouteHandle>("ROUTE")!!
        mRoute = CitymapperDirections.getInstance(this).loadRoute(routeHandle)!!

        Log.i(TAG, "Loaded route")

        try {
            val playables = mPlayableGenerator.findPlayOptions(mRoute)
            Log.i(TAG, "Got ${playables.size} playables")

            runOnUiThread {
                val pageTitleView = requireViewById<TextView>(R.id.pageTitle)
                val durationMins = mRoute.duration!!.inWholeMinutes
                pageTitleView.text = "Music for your $durationMins minute journey"

                val playablesListView = requireViewById<ListView>(R.id.playables)
                playablesListView.adapter = PlayableAdapter(this, playables)

                playablesListView.setOnItemClickListener { parent, view, position, id ->
                    clickPlay(parent.getItemAtPosition(position) as PlayableGenerator.Playable)
                }

                hideProgressSpinner()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load playables!", e)
            runOnUiThread {
                requireViewById<TextView>(R.id.pageTitle).text = "An error occured!"
                hideProgressSpinner()
            }
        }
    }

    private fun hideProgressSpinner() {
        val progressSpinner = requireViewById<ProgressBar>(R.id.progressSpinner)
        progressSpinner.visibility = View.GONE
    }

    private fun clickPlay(playable: PlayableGenerator.Playable) {
        Log.i(TAG, "Clicked ${playable.title} -> ${playable.uri}")
        if (MainActivity.spotifyAppRemote == null) {
            Log.e(TAG, "Spotify app remote not initialised!")
            Toast.makeText(this, "Spotify app remote not initialised!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Playing ${playable.title}!", Toast.LENGTH_SHORT).show()
            MainActivity.spotifyAppRemote!!.playerApi.play(playable.uri);
        }

        val route = mRoute!!
        if (doesRouteGo(route)) {
            // Start navigation activity
            val routeHandle = CitymapperDirections.getInstance(this).storeRoute(route)
            val intent = Intent(this, NavigationActivity::class.java)
            intent.putExtra("ROUTE", routeHandle)
            intent.putExtra("PLAYABLE_URI", playable.uri)
            startActivity(intent)
        } else {
            // Navigation not supported, start JD screen
            RouteDetail.showStandaloneRouteDetailScreen(this, route)
        }
    }

    /** Does route support go mode */
    private fun doesRouteGo(route: Route): Boolean {
        return route.legs.size == 1 && doesLegGo(route.legs[0])
    }

    /** Does a leg support go mode */
    private fun doesLegGo(leg: Leg): Boolean = when (leg) {
            is OwnVehicleLeg -> true
            is WalkLeg -> true
            else -> false
        }
}