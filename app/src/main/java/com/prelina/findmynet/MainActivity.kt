package com.prelina.findmynet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.prelina.findmynet.ui.theme.FindMyNetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivityNew : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化 SessionManager（参考 iCloud3 v3 在 __init__ 时自动设置）
        ICloudService.initSessionManager(applicationContext)
        
        setContent {
            FindMyNetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FindMyApp()
                }
            }
        }
    }
}

@Composable
fun FindMyApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showTwoFactorDialog by remember { mutableStateOf(false) }
    var isCheckingSession by remember { mutableStateOf(true) }
    
    // 保存登录凭据（用于 2FA 验证成功后保存会话）
    var savedEmail by remember { mutableStateOf("") }
    var savedPassword by remember { mutableStateOf("") }
    
    val scope = rememberCoroutineScope()
    val iCloudService = remember { ICloudService.getInstance() }
    
    // 🔑 获取 SessionManager 实例（与 ICloudService 中的是同一个）
    val sessionManager = remember { 
        SessionManager(context)
    }
    
    // 🔑 应用启动时自动检查并恢复会话（类似 iCloud3 V3 的自动登录）
    LaunchedEffect(Unit) {
        isCheckingSession = true
        
        try {
            // 获取保存的用户名
            val savedUsername = sessionManager.getSavedUsername()
            
            if (savedUsername != null) {
                
                // 尝试恢复会话
                if (sessionManager.restoreSession(iCloudService)) {
                    
                    // 自动跳转到地图页面
                    val intent = Intent(context, MapActivity::class.java)
                    context.startActivity(intent)
                    (context as? ComponentActivity)?.finish()
                } else {
                    // 预填用户名
                    email = savedUsername
                }
            } else {
            }
        } catch (e: Exception) {
        } finally {
            isCheckingSession = false
        }
    }

    // 2FA 对话框
    if (showTwoFactorDialog) {
        TwoFactorDialog(
            onDismiss = { showTwoFactorDialog = false },
            onCodeSubmit = { code ->
                scope.launch {
                    isLoading = true
                    errorMessage = null
                    try {
                        val result = iCloudService.verify2FACode(code)
                        if (result.success) {
                            showTwoFactorDialog = false
                            
                            // 🔑 2FA 验证成功后，保存会话
                            iCloudService.saveSessionToStorage(savedEmail, savedPassword)
                            
                            // 跳转到地图页面
                            val intent = Intent(context, MapActivity::class.java)
                            context.startActivity(intent)
                            (context as? ComponentActivity)?.finish() // 关闭登录页面
                        } else {
                            errorMessage = result.errorMessage ?: "Incorrect verification code"
                        }
                    } catch (e: Exception) {
                        errorMessage = "Verification failed: ${e.message}"
                    } finally {
                        isLoading = false
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 检查会话中的加载状态
        if (isCheckingSession) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Checking sign-in status...",
                style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }
        
            // 登录界面
            Text(
                text = "Find My Mac",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // 重要提示卡片
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "Important",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "If you run into any problems, please file an issue on GitHub.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Apple ID") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorMessage = null
                        
                        // 🔑 保存登录凭据（用于 2FA 验证后保存会话）
                        savedEmail = email
                        savedPassword = password
                        
                        try {
                            val result = iCloudService.login(email, password)
                            if (result.success) {
                                // 登录成功，跳转到地图页面
                                val intent = Intent(context, MapActivity::class.java)
                                context.startActivity(intent)
                                (context as? ComponentActivity)?.finish() // 关闭登录页面
                            } else if (result.requiresTwoFactor) {
                                // 显示 2FA 对话框
                                showTwoFactorDialog = true
                                errorMessage = "Enter the verification code"
                            } else {
                                errorMessage = result.errorMessage ?: "Sign-in failed"
                            }
                        } catch (e: java.net.UnknownHostException) {
                            errorMessage = "Network error: could not reach Apple's servers.\n\nPlease check your network connection."
                        } catch (e: java.io.IOException) {
                            if (e.message?.contains("503") == true) {
                                errorMessage = "Apple's servers returned HTTP 503 (temporarily unavailable).\n\nPlease wait a while and try again."
                            } else {
                                errorMessage = "Network error: ${e.message}"
                            }
                        } catch (e: Exception) {
                            errorMessage = "Error: ${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isLoading) "Signing in..." else "Sign in")
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // Batch 2: the upstream "go to MapActivity" debug button that
            // bypassed the login screen was removed here (flagged in
            // BATCH1.md / AUDIT section 7 item 2). MapActivity is now
            // reachable only via a successful login or a restored session.


            // 网络诊断按钮
            OutlinedButton(
                onClick = {
                    scope.launch {
                        errorMessage = "Testing network connection...\n"
                        try {
                            // 测试 DNS 解析
                            withContext(Dispatchers.IO) {
                                val address = java.net.InetAddress.getByName("www.apple.com")
                                errorMessage += "DNS resolution OK: ${address.hostAddress}\n"
                                
                                // 测试 HTTPS 连接
                                val url = java.net.URL("https://www.apple.com")
                                val connection = url.openConnection()
                                connection.connectTimeout = 5000
                                connection.connect()
                                errorMessage += "HTTPS connection OK\n\nThe network looks fine; you can sign in."
                            }
                        } catch (e: Exception) {
                            errorMessage += "Connection failed: ${e.message}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test network connection")
            }

            errorMessage?.let { msg ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

    }
}

@Composable
fun TwoFactorDialog(
    onDismiss: () -> Unit,
    onCodeSubmit: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Two-Factor Authentication")
        },
        text = {
            Column {
                Text(
                    text = "Enter the 6-digit code sent to your devices",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                OutlinedTextField(
                    value = code,
                    onValueChange = { 
                        // 只允许输入数字，最多6位
                        if (it.length <= 6 && it.all { char -> char.isDigit() }) {
                            code = it
                            error = null
                        }
                    },
                    label = { Text("Verification code") },
                    placeholder = { Text("000000") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Text(
                    text = "Tip: check the code shown on your Apple devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (code.length == 6) {
                        onCodeSubmit(code)
                    } else {
                        error = "Enter the full 6-digit code"
                    }
                },
                enabled = code.length == 6
            ) {
                Text("Verify")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
