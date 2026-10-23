/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.tif

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.opentv.R
import kotlinx.coroutines.launch

/**
 * The activity Live Channels opens when the user adds (or re-scans) the OpenTV input.
 *
 * The framework contract is only "return RESULT_OK once the input is ready". There is nothing for
 * the user to configure — the channels come from whatever sources they already set up in OpenTV —
 * so this just runs one sync, so the first batch of channels exists before Live Channels reads
 * them, and finishes. Everything after this stays fresh via the periodic worker and app start-up.
 */
class OpenTvSetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.tif_setup_adding)
                textSize = 20f
                setPadding(64, 64, 64, 64)
            },
        )
        lifecycleScope.launch {
            TifSync.sync(applicationContext)
            setResult(RESULT_OK)
            finish()
        }
    }
}
