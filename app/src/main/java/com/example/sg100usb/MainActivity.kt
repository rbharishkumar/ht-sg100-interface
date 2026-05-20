package com.example.sg100usb

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
}
