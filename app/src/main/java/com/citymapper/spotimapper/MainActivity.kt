package com.citymapper.spotimapper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.citymapper.sdk.core.ApiCall
import com.citymapper.sdk.core.ApiResult
import com.citymapper.sdk.core.geo.Coords
import com.citymapper.sdk.core.geo.distanceTo
import com.citymapper.sdk.core.transit.DirectionsResults
import com.citymapper.sdk.core.transit.Route
import com.citymapper.sdk.directions.CitymapperDirections
import com.citymapper.sdk.directions.results.DirectionsError
import com.citymapper.sdk.ui.routelist.CitymapperRouteListView
import com.google.android.gms.common.api.Status
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.AutocompleteSupportFragment
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse


class MainActivity : AppCompatActivity() {
    companion object {
        var spotifyAppRemote: SpotifyAppRemote? = null
    }

    private val TAG = "MainActivity"
    private val spotifyClientId = BuildConfig.SPOTIFY_CLIENT_ID
    private val REDIRECT_URI = "http://com.citymapper.spotimapper/callback"
    private val REQUEST_CODE = 1337
    private val REQUIRED_SCOPES = arrayOf(
        "playlist-modify-private",
        "playlist-modify-public",
        "playlist-read-private",
        "user-library-read"
    )

    private var userLocation: Coords? = null
    private var destination: Coords? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var mSpotifyAccessToken: String? = null

    private var mWalkRoutes: List<Route> = listOf()
    private var mBikeRoutes: List<Route> = listOf()
    private var mTransitRoutes: List<Route> = listOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mapsApiKey = BuildConfig.GOOGLE_MAPS_SDK_KEY
        if (mapsApiKey.isEmpty()) {
            Toast.makeText(this, "No Google Maps API key", Toast.LENGTH_LONG).show()
            return
        }
        if (BuildConfig.CITYMAPPER_SDK_KEY.isEmpty()) {
            Toast.makeText(this, "No Citymapper API key", Toast.LENGTH_LONG).show()
            return
        }

        // Setup Places Client
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, mapsApiKey)
        }

        initSpotifyAppRemote()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val locationPermissionRequest = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            when {
                permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) -> {
                    // Precise location access granted.
                    resumeLocationListening()
                }
                permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false) -> {
                    // Only approximate location access granted.
                    noLocationPermissions()
                } else -> {
                // No location access granted.
                noLocationPermissions()
            }
            }
        }

        locationPermissionRequest.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    override fun onStart() {
        super.onStart()

        initSpotifyWebApi()

        initSearchFragment()

        resumeLocationListening()
    }

    private fun resumeLocationListening() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location : Location? ->
                    // Got last known location. In some rare situations this can be null.
                    if (location == null) {
                        // TODO: Start live location updates
                        Log.w(TAG, "Fused location service returned null location!")
                    } else {
                        updateUserLocation(location)
                    }
                    // TODO: Handle the case of outdated/imprecise location data (start live updating)
                }
        }
    }

    private fun noLocationPermissions() {
        runOnUiThread {
            Log.w(TAG, "Location permissions were not granted!")
            Toast.makeText(this@MainActivity, "No location permissions!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUserLocation(location: Location) {
        val newCoords = Coords(location.latitude, location.longitude)

        when {
            userLocation == null -> {
                Log.i(TAG, "First user location received $newCoords")
                userLocation = newCoords
                planRoutes()
            }
            (userLocation?.distanceTo(newCoords)?.inMeters ?: 0.0) > 100.0 -> {
                Log.i(TAG, "User location moved by >100m ($newCoords) - updating routes")
                userLocation = newCoords
                planRoutes()
            }
            else -> {
                Log.i(TAG, "User location has not significantly changed ($newCoords) - not updating")
            }
        }
    }

    private fun initSearchFragment() {
        // Initialize the AutocompleteSupportFragment.
        val autocompleteFragment =
            supportFragmentManager.findFragmentById(R.id.autocomplete_fragment)
                    as AutocompleteSupportFragment

        // Specify the types of place data to return.
        autocompleteFragment.setPlaceFields(listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG))

        // Set up a PlaceSelectionListener to handle the response.
        autocompleteFragment.setOnPlaceSelectedListener(object : PlaceSelectionListener {
            override fun onPlaceSelected(place: Place) {
                Log.i(TAG, "Place: ${place.name}, ${place.id}")
                val ll = place.latLng!!
                Log.i(TAG, "Coords = ${ll.latitude}, ${ll.longitude}")
                destination = Coords(ll.latitude, ll.longitude)
                planRoutes()
            }

            override fun onError(status: Status) {
                Log.i(TAG, "An error occurred: $status")
                Toast.makeText(
                    this@MainActivity,
                    "Something went wrong with places!",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun planRoutes() {
        userLocation?.let { userLocation ->
            destination?.let { destination ->
                planRoutes(userLocation, destination)
            } ?: run {
                Log.w(TAG, "Destination field not set, not planning routes")
            }
        } ?: run {
            Log.w(TAG, "User location field not set, not planning routes")
        }
    }

    private fun planRoutes(origin: Coords, destination: Coords) {
        mWalkRoutes = listOf()
        mBikeRoutes = listOf()
        mTransitRoutes = listOf()

        runOnUiThread {
            val loadingSpinner = requireViewById<ProgressBar>(R.id.loadingSpinner)
            loadingSpinner.visibility = View.VISIBLE

            // Clear results list
            updateRoutes()
        }

        val cmDirections = CitymapperDirections.getInstance(this)
        val transitCall = cmDirections.planTransitRoutes(start = origin, end = destination)
        asyncUpdateRouteResults(transitCall) {
            mTransitRoutes = it
            runOnUiThread {
                val loadingSpinner = requireViewById<ProgressBar>(R.id.loadingSpinner)
                loadingSpinner.visibility = View.GONE
            }
        }

        val bikeCall = cmDirections.planBikeRoutes(start = origin, end = destination)
        asyncUpdateRouteResults(bikeCall) { mBikeRoutes = it }

        val walkCall = cmDirections.planWalkRoutes(start = origin, end = destination)
        asyncUpdateRouteResults(walkCall) { mWalkRoutes = it }
    }

    private fun asyncUpdateRouteResults(apiCall: ApiCall<DirectionsResults, DirectionsError>, routesCb: (List<Route>) -> Unit) {
        apiCall.executeAsync { result ->
            when (result) {
                is ApiResult.Failure.NetworkFailure -> {
                    Log.e(TAG, "ApiResult Network failure", result.error)
                }
                is ApiResult.Failure.UnknownFailure -> {
                    Log.e(TAG, "ApiResult Unknown failure", result.error)
                }
                is ApiResult.Failure.HttpFailure -> {
                    Log.e(TAG, "HttpFailure ${result.code} - ${result.error}")
                }
                is ApiResult.Success -> {
                    routesCb(result.data.routes)
                    updateRoutes()
                }
            }
        }
    }

    private fun updateRoutes() {
        val routes = mWalkRoutes + mBikeRoutes + mTransitRoutes
        val routeListView = requireViewById<CitymapperRouteListView>(R.id.route_list)
        routeListView.setRoutes(routes) { route ->
            // Handle result click (see below)
            Log.i("DEBUG", "Clicked a route!")
            startRouteTunesActivity(route)
        }
    }

    private fun startRouteTunesActivity(route: Route) {
        val routeHandle = CitymapperDirections.getInstance(this).storeRoute(route)
        val myIntent = Intent(this, MusicSelectorActivity::class.java)
        myIntent.putExtra("ACCESS_TOKEN", mSpotifyAccessToken)
        myIntent.putExtra("ROUTE", routeHandle)
        startActivity(myIntent)
    }

    private fun initSpotifyWebApi() {
        val builder = AuthorizationRequest.Builder(spotifyClientId, AuthorizationResponse.Type.TOKEN, REDIRECT_URI)
        builder.setScopes(REQUIRED_SCOPES)
        val request = builder.build()

        AuthorizationClient.openLoginActivity(this, REQUEST_CODE, request)
    }

    private fun initSpotifyAppRemote() {
        val connectionParams = ConnectionParams.Builder(spotifyClientId)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(this, connectionParams,
            object : Connector.ConnectionListener {
                override fun onConnected(remote: SpotifyAppRemote) {
                    spotifyAppRemote = remote
                    Log.d(TAG, "Spotify app remote connected! Yay!")

                    // Now you can start interacting with App Remote
                    // TODO: Don't let the screen progress til we get here
                }

                override fun onFailure(throwable: Throwable) {
                    Log.e(TAG, throwable.message, throwable)

                    // Something went wrong when attempting to connect! Handle errors here
                }
            })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        // Check if result comes from the correct activity
        if (requestCode == REQUEST_CODE) {
            val response = AuthorizationClient.getResponse(resultCode, intent)
            when (response.type) {
                AuthorizationResponse.Type.TOKEN -> {
                    Log.i(TAG, "Authorization succeeded! Token = ${response.accessToken}")
                    mSpotifyAccessToken = response.accessToken
                    // TODO: Don't let us click off until we get here!
                }
                AuthorizationResponse.Type.ERROR -> {
                    Log.e(TAG, "Authorization failed!")
                }
                else -> {
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spotifyAppRemote?.let { SpotifyAppRemote.disconnect(it) }
        spotifyAppRemote = null
    }
}