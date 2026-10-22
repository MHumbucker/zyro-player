package com.zyro.player.data.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Мост между MainActivity (принимающей redirect sc://auth) и экраном авторизации.
 * Код авторизации складывается сюда из intent'а и разбирается в Compose.
 */
object AuthFlowManager {
    private val _authCode = MutableStateFlow<String?>(null)
    val authCode: StateFlow<String?> = _authCode.asStateFlow()

    fun setAuthCode(code: String) {
        _authCode.value = code
    }

    fun clearAuthCode() {
        _authCode.value = null
    }
}
