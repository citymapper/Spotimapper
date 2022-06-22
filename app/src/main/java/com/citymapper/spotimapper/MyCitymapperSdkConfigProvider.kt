package com.citymapper.spotimapper

import android.content.Context
import com.citymapper.sdk.configuration.CitymapperSdkConfiguration

class MyCitymapperSdkConfigProvider : CitymapperSdkConfiguration.Provider {

    override fun provideCitymapperSdkConfiguration(context: Context): CitymapperSdkConfiguration {
        return CitymapperSdkConfiguration(
            endpointUrl = "api.external.citymapper.com/api",
            apiKey = BuildConfig.CITYMAPPER_SDK_KEY
        )
    }
}