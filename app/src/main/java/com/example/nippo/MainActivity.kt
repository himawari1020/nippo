package com.example.nippo

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val viewModel: MainViewModel = viewModel()
                val uiState by viewModel.uiState.collectAsState()

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when {
                        uiState.currentUser == null -> LoginScreen()
                        !uiState.isEmailVerified -> VerificationWaitingScreen(
                            email = uiState.currentUser?.email,
                            onCheckVerified = { viewModel.reloadUser() },
                            onLogout = { viewModel.logout() }
                        )
                        else -> DashboardScreen(viewModel = viewModel, uiState = uiState)
                    }
                }
            }
        }
    }
}

@Composable
fun VerificationWaitingScreen(email: String?, onCheckVerified: () -> Unit, onLogout: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(imageVector = Icons.Default.Email, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text(stringResource(R.string.verification_needed), style = MaterialTheme.typography.headlineSmall, color = Color(0xFFE67E22))
        Spacer(modifier = Modifier.height(8.dp))
        Text(stringResource(R.string.verification_sent_msg, email ?: ""), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.verification_hint), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(32.dp))
        Button(modifier = Modifier.fillMaxWidth(), onClick = onCheckVerified) { Text(stringResource(R.string.verification_check_button)) }
        TextButton(onClick = onLogout) { Text(stringResource(R.string.logout_return_button)) }
    }
}

@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = CredentialManager.create(context)
    val auth = FirebaseAuth.getInstance()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSignUpMode by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val msgLoginFailed = stringResource(R.string.error_login_failed)
    val msgVerificationSent = stringResource(R.string.msg_verification_sent)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))

        TextField(value = email, onValueChange = { email = it }, label = { Text(stringResource(R.string.email_label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(modifier = Modifier.height(16.dp))
        TextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.password_label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))

        errorMessage?.let { Text(text = it, color = Color.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                errorMessage = null
                if (isSignUpMode) {
                    auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            task.result?.user?.sendEmailVerification()?.addOnCompleteListener { emailTask ->
                                if (emailTask.isSuccessful) Toast.makeText(context, msgVerificationSent, Toast.LENGTH_LONG).show()
                            }
                        } else { errorMessage = task.exception?.localizedMessage }
                    }
                } else {
                    auth.signInWithEmailAndPassword(email, password).addOnCompleteListener { task ->
                        if (!task.isSuccessful) errorMessage = msgLoginFailed
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = email.isNotBlank() && password.length >= 6
        ) { Text(if (isSignUpMode) stringResource(R.string.signup_button) else stringResource(R.string.login_button)) }

        TextButton(onClick = { isSignUpMode = !isSignUpMode; errorMessage = null }) {
            Text(if (isSignUpMode) stringResource(R.string.switch_to_login) else stringResource(R.string.switch_to_signup))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
            HorizontalDivider(modifier = Modifier.weight(1f)); Text(stringResource(R.string.or_divider), modifier = Modifier.padding(horizontal = 8.dp), color = Color.Gray); HorizontalDivider(modifier = Modifier.weight(1f))
        }

        OutlinedButton(
            onClick = {
                val webClientId = BuildConfig.WEB_CLIENT_ID
                val googleIdOption = GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false).setServerClientId(webClientId).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
                coroutineScope.launch {
                    try {
                        val result = credentialManager.getCredential(context, request)
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
                        val credential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                        auth.signInWithCredential(credential)
                    } catch (e: Exception) { errorMessage = "Google sign-in failed: ${e.message}" }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.google_sign_in)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel, uiState: MainUiState) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }

    var realNameInput by remember { mutableStateOf("") }
    var inviteCodeInput by remember { mutableStateOf("") }
    var newCompanyNameInput by remember { mutableStateOf("") }
    var isCreateMode by remember { mutableStateOf(false) }

    // 【修正点】UiText.asString(context) を使用して文字列に変換
    LaunchedEffect(uiState.errorMessage, uiState.successMessage) {
        uiState.errorMessage?.let { uiText ->
            Toast.makeText(context, uiText.asString(context), Toast.LENGTH_LONG).show()
            viewModel.clearMessages()
        }
        uiState.successMessage?.let { uiText ->
            Toast.makeText(context, uiText.asString(context), Toast.LENGTH_SHORT).show()
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (showSettings) stringResource(R.string.settings_title) else stringResource(R.string.dashboard_title)) },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (showSettings) {
                // --- 設定画面 ---
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.account_info), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.user_name_label, uiState.userName), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.affiliation_info, uiState.companyName), color = Color.Gray)

                    val roleStr = if (uiState.role == "admin") stringResource(R.string.role_admin) else stringResource(R.string.role_user)
                    Text(stringResource(R.string.role_label, roleStr), color = Color.Gray)

                    Spacer(modifier = Modifier.height(32.dp))

                    if (uiState.role == "admin") {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF1F0)),
                            border = BorderStroke(1.dp, Color.Red),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.admin_menu), color = Color.Red, style = MaterialTheme.typography.labelLarge)
                                Text(stringResource(R.string.delete_account_warning), style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                    enabled = !uiState.isLoading,
                                    onClick = { viewModel.deleteAccount() }
                                ) { Text(stringResource(R.string.delete_account_button)) }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { viewModel.logout() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.logout)) }
                    TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.back_to_dashboard)) }
                }
            } else {
                // --- 打刻画面 ---
                if (uiState.companyId != null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = stringResource(R.string.hello_user, uiState.userName), style = MaterialTheme.typography.headlineMedium)

                        Text(
                            text = if (uiState.companyName.isNotEmpty()) stringResource(R.string.affiliation_info, uiState.companyName) else stringResource(R.string.affiliation_id_info, uiState.companyId),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )

                        Spacer(modifier = Modifier.height(48.dp))

                        Button(
                            onClick = { viewModel.recordAttendance(uiState.isWorking) },
                            modifier = Modifier.size(200.dp),
                            shape = CircleShape,
                            enabled = !uiState.isLoading,
                            colors = ButtonDefaults.buttonColors(containerColor = if (uiState.isWorking) Color(0xFFEF4444) else Color(0xFF10B981))
                        ) {
                            Text(
                                text = if (uiState.isWorking) stringResource(R.string.clock_out) else stringResource(R.string.clock_in),
                                style = MaterialTheme.typography.headlineMedium
                            )
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (uiState.companyId == null && !showSettings && uiState.currentUser != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (isCreateMode) stringResource(R.string.dialog_create_company) else stringResource(R.string.dialog_join_company)) },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        TextButton(onClick = { isCreateMode = false }) {
                            Text(stringResource(R.string.tab_join), color = if (!isCreateMode) MaterialTheme.colorScheme.primary else Color.Gray)
                        }
                        TextButton(onClick = { isCreateMode = true }) {
                            Text(stringResource(R.string.tab_create), color = if (isCreateMode) MaterialTheme.colorScheme.primary else Color.Gray)
                        }
                    }
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(stringResource(R.string.label_real_name))
                    TextField(value = realNameInput, onValueChange = { realNameInput = it }, singleLine = true, modifier = Modifier.fillMaxWidth())

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isCreateMode) {
                        Text(stringResource(R.string.label_company_name))
                        TextField(value = newCompanyNameInput, onValueChange = { newCompanyNameInput = it }, placeholder = { Text(stringResource(R.string.hint_company_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.hint_admin_note), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    } else {
                        Text(stringResource(R.string.label_invite_code))
                        TextField(value = inviteCodeInput, onValueChange = { inviteCodeInput = it.uppercase() }, placeholder = { Text(stringResource(R.string.hint_invite_code)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = realNameInput.isNotBlank() && !uiState.isLoading &&
                            ((isCreateMode && newCompanyNameInput.isNotBlank()) || (!isCreateMode && inviteCodeInput.length >= 6)),
                    onClick = {
                        if (isCreateMode) {
                            viewModel.createCompany(newCompanyNameInput, realNameInput)
                        } else {
                            viewModel.joinCompany(inviteCodeInput, realNameInput)
                        }
                    }
                ) { Text(if (isCreateMode) stringResource(R.string.btn_create_start) else stringResource(R.string.btn_join_start)) }
            },
            dismissButton = { TextButton(onClick = { viewModel.logout() }) { Text(stringResource(R.string.logout)) } }
        )
    }
}