package com.example.sg100usb

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.example.sg100usb.ui.DashboardViewModel
import com.example.sg100usb.ui.Sg100App

class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Sg100App(viewModel)
        }
    }

    // With launchMode="singleTop" Android calls this instead of recreating the activity
    // when a USB_DEVICE_ATTACHED intent arrives while the app is in the foreground.
    // UsbHidManager's BroadcastReceiver already handles device attachment independently,
    // so no additional work is needed here — we just prevent the default re-launch.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }
}
