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
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()

    private var userListener: ListenerRegistration? = null
    private var companyListener: ListenerRegistration? = null
    private var attendanceListener: ListenerRegistration? = null

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _uiState.update { it.copy(currentUser = user) }

            if (user != null) {
                checkEmailVerification(user)
                startUserListener(user.uid)
            } else {
                stopListeners()
                _uiState.update { MainUiState() }
            }
        }
    }

    private fun checkEmailVerification(user: FirebaseUser) {
        val isVerified = !user.providerData.any { it.providerId == "password" } || user.isEmailVerified
        _uiState.update { it.copy(isEmailVerified = isVerified) }
    }

    private fun startUserListener(uid: String) {
        userListener?.remove()
        userListener = db.collection("users").document(uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    _uiState.update {
                        it.copy(errorMessage = UiText.StringResource(R.string.msg_user_fetch_failed, e.message ?: ""))
                    }
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
                    if (cId != null) {
                        startCompanyListener(cId)
                        startAttendanceListener(uid, cId)
                    }
                } else {
                    if (_uiState.value.companyId != null) {
                        logout()
                        _uiState.update {
                            it.copy(errorMessage = UiText.StringResource(R.string.msg_account_deleted))
                        }
                    }
                }
            }
    }

    private fun startCompanyListener(companyId: String) {
        companyListener?.remove()
        companyListener = db.collection("companies").document(companyId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    _uiState.update {
                        it.copy(errorMessage = UiText.StringResource(R.string.msg_company_fetch_failed, e.message ?: ""))
                    }
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    _uiState.update { it.copy(companyName = snapshot.getString("name") ?: "") }
                }
            }
    }

    private fun startAttendanceListener(uid: String, companyId: String) {
        attendanceListener?.remove()
        attendanceListener = db.collection("attendance")
            .whereEqualTo("uid", uid)
            .whereEqualTo("companyId", companyId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    val msgUiText = if (e.message?.contains("index") == true) {
                        UiText.StringResource(R.string.msg_index_needed)
                    } else {
                        UiText.StringResource(R.string.msg_attendance_fetch_failed, e.message ?: "")
                    }
                    _uiState.update { it.copy(errorMessage = msgUiText) }
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.isEmpty) {
                    val type = snapshot.documents[0].getString("type")
                    _uiState.update { it.copy(isWorking = type == "clock_in") }
                }
            }
    }

    private fun stopListeners() {
        userListener?.remove()
        companyListener?.remove()
        attendanceListener?.remove()
    }

    // --- Actions ---

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

    fun recordAttendance(currentIsWorking: Boolean) {
        if (_uiState.value.isLoading) return

        val companyId = _uiState.value.companyId ?: return
        val nextType = if (currentIsWorking) "clock_out" else "clock_in"

        _uiState.update {
            it.copy(isLoading = true, isWorking = !currentIsWorking)
        }

        val data = hashMapOf("type" to nextType, "companyId" to companyId)

        functions.getHttpsCallable("recordAttendance").call(data)
            .addOnSuccessListener {
                val msgId = if (nextType == "clock_in") R.string.msg_clock_in_success else R.string.msg_clock_out_success
                _uiState.update {
                    it.copy(isLoading = false, successMessage = UiText.StringResource(msgId))
                }
            }
            .addOnFailureListener { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isWorking = currentIsWorking,
                        errorMessage = e.message?.let { msg -> UiText.DynamicString(msg) }
                            ?: UiText.StringResource(R.string.msg_clock_failed)
                    )
                }
            }
    }

    fun createCompany(companyName: String, userName: String) {
        _uiState.update { it.copy(isLoading = true) }
        val data = hashMapOf("companyName" to companyName, "userName" to userName)

        functions.getHttpsCallable("createCompany").call(data)
            .addOnSuccessListener {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = UiText.StringResource(R.string.msg_company_created))
                }
            }
            .addOnFailureListener { e ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message?.let { UiText.DynamicString(it) })
                }
            }
    }

    fun joinCompany(inviteCode: String, userName: String) {
        _uiState.update { it.copy(isLoading = true) }
        val data = hashMapOf("inviteCode" to inviteCode, "userName" to userName)

        functions.getHttpsCallable("joinCompany").call(data)
            .addOnSuccessListener {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = UiText.StringResource(R.string.msg_company_joined))
                }
            }
            .addOnFailureListener { e ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message?.let { UiText.DynamicString(it) })
                }
            }
    }

    fun deleteAccount() {
        _uiState.update { it.copy(isLoading = true) }
        functions.getHttpsCallable("deleteAccountAndCompany").call()
            .addOnSuccessListener {
                _uiState.update {
                    it.copy(isLoading = false, successMessage = UiText.StringResource(R.string.msg_account_deleted_success))
                }
                logout()
            }
            .addOnFailureListener { e ->
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = e.message?.let { UiText.DynamicString(it) })
                }
            }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopListeners()
    }
}