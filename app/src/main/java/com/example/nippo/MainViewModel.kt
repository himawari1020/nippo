package com.example.nippo

import androidx.lifecycle.ViewModel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainViewModel : ViewModel() {
    // 状態を保持するFlow
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()

    // リスナー管理用（画面が閉じられたら監視を解除するため）
    private var userListener: ListenerRegistration? = null
    private var companyListener: ListenerRegistration? = null
    private var attendanceListener: ListenerRegistration? = null

    init {
        // 1. 認証状態の監視
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _uiState.update { it.copy(currentUser = user) }

            if (user != null) {
                checkEmailVerification(user)
                startUserListener(user.uid)
            } else {
                // ログアウト時はリスナーを解除して状態をリセット
                stopListeners()
                _uiState.update { MainUiState() } // 初期状態に戻す
            }
        }
    }

    private fun checkEmailVerification(user: FirebaseUser) {
        val isVerified = !user.providerData.any { it.providerId == "password" } || user.isEmailVerified
        _uiState.update { it.copy(isEmailVerified = isVerified) }
    }

    // ユーザー情報のリアルタイム監視
    private fun startUserListener(uid: String) {
        userListener?.remove()
        userListener = db.collection("users").document(uid)
            .addSnapshotListener { snapshot, e ->
                // エラーハンドリングを追加
                if (e != null) {
                    _uiState.update { it.copy(errorMessage = "ユーザー情報の取得に失敗: ${e.message}") }
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val cId = snapshot.getString("companyId").takeIf { !it.isNullOrEmpty() }
                    _uiState.update {
                        it.copy(
                            userName = snapshot.getString("userName") ?: "",
                            companyId = cId,
                            role = snapshot.getString("role")
                        )
                    }
                    // 会社IDが取得できたら、会社情報と打刻情報の監視を開始
                    if (cId != null) {
                        startCompanyListener(cId)
                        startAttendanceListener(uid, cId)
                    }
                } else {
                    // ドキュメントが削除された場合の処理
                    // すでに会社に参加していた(=companyIdを持っていた)のにドキュメントが消えた場合は
                    // アカウント削除とみなして強制ログアウトする
                    if (_uiState.value.companyId != null) {
                        logout()
                        _uiState.update { it.copy(errorMessage = "アカウントが削除されました") }
                    }
                }
            }
    }

    // 会社情報の監視
    private fun startCompanyListener(companyId: String) {
        companyListener?.remove()
        companyListener = db.collection("companies").document(companyId)
            .addSnapshotListener { snapshot, e ->
                // エラーハンドリングを追加
                if (e != null) {
                    _uiState.update { it.copy(errorMessage = "会社情報の取得に失敗: ${e.message}") }
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    _uiState.update { it.copy(companyName = snapshot.getString("name") ?: "") }
                }
            }
    }

    // 最新の打刻状態の監視
    private fun startAttendanceListener(uid: String, companyId: String) {
        attendanceListener?.remove()
        attendanceListener = db.collection("attendance")
            .whereEqualTo("uid", uid)
            .whereEqualTo("companyId", companyId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshot, e ->
                // エラーハンドリングを追加
                if (e != null) {
                    // インデックス未作成のエラーはここで捕捉されます
                    val msg = if (e.message?.contains("index") == true) {
                        "インデックスの作成が必要です。LogcatのURLを確認してください。"
                    } else {
                        "打刻履歴の取得に失敗: ${e.message}"
                    }
                    _uiState.update { it.copy(errorMessage = msg) }
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.isEmpty) {
                    val type = snapshot.documents[0].getString("type")
                    _uiState.update { it.copy(isWorking = type == "clock_in") }
                }
            }
    }

    // リスナーの解除
    private fun stopListeners() {
        userListener?.remove()
        companyListener?.remove()
        attendanceListener?.remove()
    }

    // --- アクション（UIから呼ばれる関数） ---

    fun reloadUser() {
        auth.currentUser?.reload()?.addOnCompleteListener {
            if (it.isSuccessful) {
                val user = auth.currentUser
                checkEmailVerification(user!!)
            }
        }
    }

    fun logout() {
        auth.signOut()
    }

    // 打刻処理（修正済み：連打防止機能追加）
    fun recordAttendance(currentIsWorking: Boolean) {
        // 【修正点1】処理中ならガードして連打を防ぐ
        if (_uiState.value.isLoading) return

        val companyId = _uiState.value.companyId ?: return
        val nextType = if (currentIsWorking) "clock_out" else "clock_in"

        // 【修正点2】isLoading = true をセットしつつ、楽観的UI更新も行う
        _uiState.update {
            it.copy(
                isLoading = true,
                isWorking = !currentIsWorking
            )
        }

        val data = hashMapOf("type" to nextType, "companyId" to companyId)

        functions.getHttpsCallable("recordAttendance").call(data)
            .addOnSuccessListener {
                val msg = if (nextType == "clock_in") "出勤しました" else "退勤しました"
                // 【修正点3】処理完了時に isLoading = false に戻す
                _uiState.update { it.copy(isLoading = false, successMessage = msg) }
            }
            .addOnFailureListener { e ->
                // 失敗時は状態を元に戻し、ロックも解除する
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isWorking = currentIsWorking, // 元の状態に戻す
                        errorMessage = e.message ?: "打刻に失敗しました"
                    )
                }
            }
    }

    // 会社作成
    fun createCompany(companyName: String, userName: String) {
        _uiState.update { it.copy(isLoading = true) }
        val data = hashMapOf("companyName" to companyName, "userName" to userName)

        functions.getHttpsCallable("createCompany").call(data)
            .addOnSuccessListener {
                _uiState.update { it.copy(isLoading = false, successMessage = "会社を作成しました") }
            }
            .addOnFailureListener { e ->
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
    }

    // 会社参加
    fun joinCompany(inviteCode: String, userName: String) {
        _uiState.update { it.copy(isLoading = true) }
        val data = hashMapOf("inviteCode" to inviteCode, "userName" to userName)

        functions.getHttpsCallable("joinCompany").call(data)
            .addOnSuccessListener {
                _uiState.update { it.copy(isLoading = false, successMessage = "参加登録が完了しました") }
            }
            .addOnFailureListener { e ->
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
    }

    // アカウント削除
    fun deleteAccount() {
        _uiState.update { it.copy(isLoading = true) }
        functions.getHttpsCallable("deleteAccountAndCompany").call()
            .addOnSuccessListener {
                _uiState.update { it.copy(isLoading = false, successMessage = "解約が完了しました") }
                logout() // ログアウト処理
            }
            .addOnFailureListener { e ->
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
    }

    // メッセージ表示後にクリアするための関数
    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    // ViewModel破棄時にリスナーを解除
    override fun onCleared() {
        super.onCleared()
        stopListeners()
    }
}