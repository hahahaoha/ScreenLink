package com.screenlink.app.ui.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.screenlink.app.core.ServiceLocator
import com.screenlink.app.net.ControllerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ControllerViewModel : ViewModel() {

    val logs = MutableStateFlow<List<String>>(emptyList())

    val client = ControllerClient { message ->
        logs.value = (listOf(message) + logs.value).take(60)
    }

    fun connect(host: String, port: Int, key: String, width: Int, quality: Int, fps: Int) {
        client.connect(host, port, key, width, quality, fps)
        viewModelScope.launch {
            ServiceLocator.settings.setLastHost(host)
            ServiceLocator.settings.setLastPort(port)
        }
    }

    fun sendTouch(action: Int, x: Float, y: Float) = client.sendTouch(action, x, y)

    fun sendKey(keyCode: Int) = client.sendKey(keyCode)

    fun disconnect() = client.disconnect()

    override fun onCleared() {
        client.release()
        super.onCleared()
    }
}
