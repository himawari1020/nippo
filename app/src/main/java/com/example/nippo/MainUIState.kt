package com.example.nippo

import com.google.firebase.auth.FirebaseUser

/**
 * 画面の状態（State）を管理するデータクラス
 */
data class MainUiState(
    val currentUser: FirebaseUser? = null,
    val isEmailVerified: Boolean = false,
    val userName: String = "",
    val companyId: String? = null,
    val companyName: String = "",
    val role: String? = null, // "admin" or "user"
    val isWorking: Boolean = false,
    val isLoading: Boolean = false,
    // 【変更】String? -> UiText?
    val errorMessage: UiText? = null,
    val successMessage: UiText? = null
)