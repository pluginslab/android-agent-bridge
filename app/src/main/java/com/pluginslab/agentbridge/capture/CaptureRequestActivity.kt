package com.pluginslab.agentbridge.capture

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class CaptureRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        try {
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                // Start the service synchronously — must happen before the activity finishes
                // so the foreground-state allowance still covers us on Android 14+.
                ScreenCaptureService.start(this, result.resultCode, data)
                Toast.makeText(this, "Screen capture granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Screen capture declined", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            android.util.Log.e("CaptureReq", "Failed to start capture service", t)
            Toast.makeText(this, "Failed to start capture: ${t.message}", Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }
}
