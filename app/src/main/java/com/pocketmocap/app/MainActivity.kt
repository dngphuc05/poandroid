package com.pocketmocap.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.json.JSONObject

/**
 * Main entry point for Pocket Mocap Client.
 *
 * Hosts Compose UI + optional Unity panel for VRM rendering.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: PocketMocapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            PocketMocapApp(viewModel = viewModel)
        }
    }

