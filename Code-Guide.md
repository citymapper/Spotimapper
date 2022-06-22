# Spotimapper

This document describes the layout of classes in the Spotimapper codebase, and references relevant external documentation. All classes/packages discussed here are in the `com.citymapper.spotimapper` package.

For information on building this project see the [README](README.md).

## Activity flow

Spotimapper follows a three activity flow:

1. [MainActivity](#mainactivity) -- Contains a places search box which allows the user to select a destination, and list journey options (transit/cycling/walking).
2. [MusicSelectorActivity](#musicselectoractivity) -- Shows a list of music options (albums/podcasts/playlists) which approximately match the journey times.
3. [NavigationActivity](#navigationactivity) -- This activity show the navigation UI and the playback UI.

### MainActivity

The MainActivity's primary function is to let the user pick a route. The Google Maps [Places SDK autocomplete fragment](https://developers.google.com/maps/documentation/places/android-sdk/autocomplete) is used to let the user search for and select a destination. Meanwhile the users last known location is retrieved using [FusedLocationProviderClient](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient). When a destination and user location have been provided the app then makes three separate journey planning calls to the Citymapper API: for walk, cycle, and transit journey planning. See ["Planning routes" (citymapper.com)](https://docs.external.citymapper.com/journey-planning/planning-routes.html). Journey results are displayed using the citymapper route list component - see ["Display a list of routes" (citymapper.com)](https://docs.external.citymapper.com/journey-planning/display-list-results.html).

As this is the first activity to be run, the main activity also handles initialising the Spotify SDK, ready for subsequent use in the later activities. The Spotify SDK actually needs initialising twice - once for the [Playback SDK](TODO) and once for the [API access token](TODO). The authorization flow provides access to the users library and to playback control. If this authorization flow fails for any reason the error is not currently handled and the app may behave badly.

### MusicSelectorActivity

The MusicSelectorActivity accepts a route selected in the previous activity, and uses the duration estimate provided by the Citymapper SDK to list playback options. The `PlayableGenerator` class contains the core logic of choosing music options, and this in turn makes use of the API wrapper in the `SpotifyApi` class. `PlayableGenerator` generates playables, which contain a Spotify URI and some display features - these playables are then arranged on a `ListView`.

### NavigationActivity

Once the user has selected a route and a playable, the NavigationActivity is started. This activity immediately kicks off the playback using Spotify's playback controller SDK.

The `CitymapperDirectionsView` class is used to display a navigation UI similar to that in the official Citymapper app - see ["Display turn-by-turn navigation" citymapper.com](https://docs.external.citymapper.com/turn-by-turn-navigation/display-turn-by-turn-navigation.html). A small media playback controller is also displayed in the top of the activity in this case.

At the time of this apps creation the Citymapper SDK is still under construction, and transit routes are not supported in the `CitymapperDirectionsView` class. As such for transit routes we fall-back on the route detail screen for transit journeys.
