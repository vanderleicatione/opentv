/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.tif

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The activity Live Channels opens when the user adds (or re-scans) the OpenTV input.
 *
 * The framework contract is only "return RESULT_OK once the input is ready". There is nothing for
 * the user to configure — the channels come from whatever sources they already set up in OpenTV.
 *
 * It does NOT run the sync in its own scope: on aggressive TVs the activity is backgrounded and
 * killed the instant it returns, which would cancel a sync mid-flight. Instead it kicks off
 * [TifSyncService] — a foreground service that outlives this activity — and returns at once. The
 * channels appear a few seconds later when the service finishes; Live Channels picks them up.
 */
class OpenTvSetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TifSyncService.start(applicationContext)
        setResult(RESULT_OK)
        finish()
    }
}
