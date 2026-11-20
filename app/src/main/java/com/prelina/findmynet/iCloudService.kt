package com.prelina.findmynet

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * iCloud 服务客户端
 * 基于 https://github.com/ElyaConrad/iCloud-API 实现
 * 单例模式 - 保证登录状态在整个应用中共享
 * 
 * 新增功能（参考 iCloud3 v3）:
 * - Session 持久化（自动保存/恢复会话）
 * - 450 错误自动重新认证并重试
 * - Cookie 持久化（已通过 PersistentCookieJar 实现）
 */
class ICloudService private constructor() {
    
    companion object {
        @Volatile
        private var INSTANCE: ICloudService? = null
        
        fun getInstance(): ICloudService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ICloudService().also { INSTANCE = it }
            }
        }
        
        /**
         * 初始化 SessionManager（需要 Context）
         */
        fun initSessionManager(context: android.content.Context) {
            val instance = getInstance()
            instance.sessionManager = SessionManager(context)
        }
    }
    
    private val gson = Gson()
    private val cookieJar = PersistentCookieJar()
    
    // SessionManager（会话持久化）
    private var sessionManager: SessionManager? = null
    
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    
    // 会话信息
    internal var sessionToken: String? = null
    internal var sessionId: String? = null
    internal var scnt: String? = null
    private var dsid: String? = null
    private var webservices: JsonObject? = null
    private var accountInfo: JsonObject? = null
    internal var accountCountry: String? = null
    private var accountName: String? = null
    private var accountLocked: Boolean = false
    internal var findme_url: String? = null
    
    // 2FA 状态
    private var requiresTwoFactor = false
    internal var trustToken: String? = null
    
    // 客户端配置（参考 main.js）
    private val clientId: String = generateClientId()
    private val widgetKey = "83545bf919730e51dbfba24e7e8a78d2"
    // OAuth client id of the modern (SRP + 2FA) flow; the same id
    // the 2FA verify/trust calls below already use.
    private val oauthClientId = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"
    private val clientBuildNumber = "2018Project35"
    private val clientMasteringNumber = "2018B29"
    private val locale = "ja_JP"
    private val language = "ja-jp"
    internal val iCloudUrl = "https://www.icloud.com"  // global iCloud
    private val userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
    
    private fun generateClientId(): String {
        return java.util.UUID.randomUUID().toString().uppercase()
    }
    
    /**
     * 获取用于查询参数的 clientId（去掉 "auth-" 前缀，参考 iCloud3 v3）
     */
    fun getClientIdForParams(): String {
        return clientId.removePrefix("auth-")
    }
    
    /**
     * 执行请求（供 FindMyService 使用，确保使用相同的 client 和 cookies）
     */
    fun executeRequest(request: Request): Response {
        return client.newCall(request).execute()
    }
    
    /**
     * 登录到 iCloud
     * 参考 setup.js 的 getAuthToken 和 accountLogin
     * 
     * 参考 iCloud3 v3 的模式：
     * 1. 在登录前自动尝试加载并验证保存的会话
     * 2. 如果会话有效，直接使用（跳过密码登录）
     * 3. 如果会话无效或不存在，执行完整的登录流程
     */
    suspend fun login(email: String, password: String, retryCount: Int = 3): LoginResult = withContext(Dispatchers.IO) {
        var lastError: String? = null
        
        // 步骤 0: 尝试恢复保存的会话（参考 iCloud3 v3 的 _setup_PyiCloudSession）
        if (sessionManager != null) {
            val sessionRestored = tryRestoreSession(email, password)
            if (sessionRestored) {
                
                // 验证 session token 是否仍然有效（参考 iCloud3 v3 的 _validate_token）
                val tokenValid = authenticateWithToken()
                if (tokenValid) {
                    return@withContext LoginResult(
                        success = true,
                        requiresTwoFactor = false
                    )
                } else {
                    // 清除无效会话
                    clearSavedSession()
                }
            } else {
            }
        }
        
        // 重试机制 - Apple 可能临时限流
        repeat(retryCount) { attempt ->
            try {
                if (attempt > 0) {
                    val waitTime = (attempt * 5000L) // 5秒、10秒、15秒
                    kotlinx.coroutines.delay(waitTime)
                }
                
                
                // 第一步：获取认证令牌
                val authResult = getAuthToken(email, password)
                if (!authResult.success) {
                    // 如果是 503 错误且还有重试次数，继续重试
                    if (authResult.errorMessage?.contains("503") == true && attempt < retryCount - 1) {
                        lastError = authResult.errorMessage
                        return@repeat
                    }
                    
                    return@withContext LoginResult(
                        success = false,
                        requiresTwoFactor = authResult.requiresTwoFactor,
                        errorMessage = authResult.errorMessage
                    )
                }
                
                
                // 第二步：账户登录
                val loginSuccess = accountLogin(authResult.token!!)
                if (!loginSuccess) {
                    return@withContext LoginResult(
                        success = false,
                        errorMessage = "Account login failed"
                    )
                }
                
                
                // 重要：参考 iCloud3 v3，在密码登录后需要再次用 token 认证
                // 这一步是必需的，否则会收到 450 错误
                val tokenAuthSuccess = authenticateWithToken()
                if (!tokenAuthSuccess) {
                }
                
                
                // 第三步：获取 Push Token（可选，但能提高兼容性）
                try {
                    getPushToken()
                } catch (e: Exception) {
                }
                
                
                // 保存会话到 SharedPreferences（参考 iCloud3 v3 的 session_data）
                saveSessionToStorage(email, password)
                
                return@withContext LoginResult(
                    success = true,
                    requiresTwoFactor = requiresTwoFactor
                )
                
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                
                // 如果还有重试次数，延迟后重试
                if (attempt < retryCount - 1) {
                    delay(1000)
                    // 继续下一次循环
                }
            }
        }
        
        // 所有重试都失败
        return@withContext LoginResult(
            success = false,
            errorMessage = lastError ?: "Sign-in failed after $retryCount attempts"
        )
    }
    
    /**
     * Sign in with SRP (the pyicloud flow), replacing the legacy
     * clear-text password POST to /appleauth/auth/signin, which Apple
     * now refuses with HTTP 503 (verified 2026-11-21).
     *
     * Steps (see SrpClient for the maths):
     * 1. POST /appleauth/auth/signin/init with the client ephemeral A.
     * 2. Process the returned challenge (salt, B, iteration, protocol).
     * 3. POST /appleauth/auth/signin/complete with m1 and the expected
     *    server proof m2. When Apple returns an "m2" it is verified
     *    against the locally computed proof, and on HTTP 200 a missing
     *    or mismatching m2 fails the login.
     * Session headers are then harvested from the complete response
     * exactly as the legacy flow harvested them from the sign-in
     * response, and the 200/409 handling below (including the
     * hsa2 -> 2FA branch) is unchanged.
     */
    private fun getAuthToken(email: String, password: String): AuthResult {
        val srpClient = SrpClient(email, password)

        // Step 1: signin/init
        val initBody = JsonObject().apply {
            addProperty("a", Base64.getEncoder().encodeToString(srpClient.publicA))
            addProperty("accountName", email)
            add("protocols", gson.toJsonTree(listOf("s2k", "s2k_fo")))
        }
        val initResponse = executeSrpRequest(
            "https://idmsa.apple.com/appleauth/auth/signin/init",
            initBody, null, null
        )
        val initBodyText = initResponse.body?.string()
        if (initResponse.code != 200 || initBodyText == null) {
            return AuthResult(
                success = false,
                errorMessage = srpFailureMessage(initResponse.code, initBodyText)
            )
        }

        val initJson = try {
            gson.fromJson(initBodyText, JsonObject::class.java)
        } catch (e: Exception) {
            null
        }
        var salt: ByteArray? = null
        var serverB: ByteArray? = null
        var iterations: Int? = null
        var protocol: String? = null
        var challenge: String? = null
        try {
            salt = initJson?.get("salt")?.asString?.let { decodeBase64(it) }
            serverB = initJson?.get("b")?.asString?.let { decodeBase64(it) }
            iterations = initJson?.get("iteration")?.asInt
            protocol = initJson?.get("protocol")?.asString
            challenge = initJson?.get("c")?.asString
        } catch (e: Exception) {
        }
        if (salt == null || serverB == null || iterations == null || protocol == null || challenge == null) {
            return AuthResult(
                success = false,
                errorMessage = "Sign-in failed: Apple returned an incomplete SRP challenge"
            )
        }
        val initScnt = initResponse.header("scnt")
        val initSessionId = initResponse.header("X-Apple-ID-Session-Id")

        // Step 2: process the challenge (PBKDF2 + SRP-6a proofs)
        if (!srpClient.processChallenge(salt, serverB, iterations, protocol)) {
            return AuthResult(
                success = false,
                errorMessage = "Sign-in failed: could not process Apple's SRP challenge"
            )
        }

        // Step 3: signin/complete
        val completeBody = JsonObject().apply {
            addProperty("accountName", email)
            addProperty("c", challenge)
            addProperty("m1", Base64.getEncoder().encodeToString(srpClient.clientProofM1))
            addProperty("m2", Base64.getEncoder().encodeToString(srpClient.expectedServerProofM2))
            addProperty("rememberMe", true)
            add(
                "trustTokens",
                gson.toJsonTree(trustToken?.let { listOf(it) } ?: emptyList<String>())
            )
        }
        val response = executeSrpRequest(
            "https://idmsa.apple.com/appleauth/auth/signin/complete?isRememberMeEnabled=true",
            completeBody, initScnt, initSessionId
        )
        val responseBody = response.body?.string()

        // Verify the server proof: a returned "m2" must equal the
        // locally computed H_AMK, and on HTTP 200 the proof is
        // required. (On 409 the 2FA + trust + accountLogin chain that
        // follows is itself server-validated.)
        val completeJson = responseBody?.let {
            try {
                gson.fromJson(it, JsonObject::class.java)
            } catch (e: Exception) {
                null
            }
        }
        val serverM2 = completeJson?.get("m2")?.asString?.let { decodeBase64(it) }
        if (serverM2 != null) {
            if (!srpClient.verifyServerProof(serverM2)) {
                return AuthResult(
                    success = false,
                    errorMessage = "Sign-in failed: Apple's server proof (m2) did not verify"
                )
            }
        } else if (response.code == 200) {
            return AuthResult(
                success = false,
                errorMessage = "Sign-in failed: Apple did not return a server proof (m2)"
            )
        }

        return when (response.code) {
            200 -> {
                // 保存会话信息（参考 HEADER_DATA）
                sessionToken = response.header("X-Apple-Session-Token")
                sessionId = response.header("X-Apple-ID-Session-Id")
                scnt = response.header("scnt")
                accountCountry = response.header("X-Apple-ID-Account-Country")  // 重要：保存账户国家代码
                trustToken = response.header("X-Apple-TwoSV-Trust-Token")  // 保存信任令牌


                // 检查是否需要 2FA（参考 main.js line 227）
                responseBody?.let {
                    try {
                        val jsonResponse = gson.fromJson(it, JsonObject::class.java)
                        val authType = jsonResponse.get("authType")?.asString

                        if (authType == "hsa2") {
                            requiresTwoFactor = true
                            return AuthResult(
                                success = false,
                                requiresTwoFactor = true,
                                errorMessage = "Two-factor authentication is required"
                            )
                        }
                    } catch (e: Exception) {
                    }
                }

                AuthResult(success = true, token = sessionToken)
            }
            409 -> {
                // 409 Conflict - 通常表示需要额外验证或账户状态异常

                // 保存关键的会话头信息（参考 HEADER_DATA）
                sessionToken = response.header("X-Apple-Session-Token")
                sessionId = response.header("X-Apple-ID-Session-Id")
                scnt = response.header("scnt")
                accountCountry = response.header("X-Apple-ID-Account-Country")  // 重要！
                trustToken = response.header("X-Apple-TwoSV-Trust-Token")


                // 尝试解析响应体
                responseBody?.let { body ->
                    try {
                        // 先尝试解析为 JsonElement 以处理不同类型
                        val jsonElement = gson.fromJson(body, com.google.gson.JsonElement::class.java)

                        // 如果是字符串类型，可能是简单的文本响应
                        if (jsonElement.isJsonPrimitive && jsonElement.asJsonPrimitive.isString) {
                            val message = jsonElement.asString

                            // 根据响应内容判断是否需要 2FA
                            if (message.contains("hsa2", ignoreCase = true) ||
                                message.contains("two factor", ignoreCase = true)) {
                                requiresTwoFactor = true
                                return AuthResult(
                                    success = false,
                                    requiresTwoFactor = true,
                                    errorMessage = "Two-factor authentication code required"
                                )
                            }

                            return AuthResult(
                                success = false,
                                errorMessage = message
                            )
                        }

                        // 如果是 JSON 对象
                        if (jsonElement.isJsonObject) {
                            val jsonResponse = jsonElement.asJsonObject

                            // 检查是否需要双因素认证
                            val authType = jsonResponse.get("authType")?.asString
                            val serviceErrors = jsonResponse.getAsJsonArray("serviceErrors")

                            if (authType == "hsa2" || serviceErrors?.any {
                                it.asJsonObject.get("code")?.asString == "-22993"
                            } == true) {
                                requiresTwoFactor = true
                                return AuthResult(
                                    success = false,
                                    requiresTwoFactor = true,
                                    errorMessage = "Two-factor authentication code required"
                                )
                            }

                            // 其他错误
                            val errorMsg = serviceErrors?.firstOrNull()
                                ?.asJsonObject?.get("message")?.asString
                                ?: "The account needs additional verification"


                            return AuthResult(
                                success = false,
                                errorMessage = errorMsg
                            )
                        }

                        // 未知格式
                        AuthResult(
                            success = false,
                            errorMessage = "Sign-in failed: Apple returned an unexpected response"
                        )
                    } catch (e: Exception) {

                        // 由于我们已经有了会话信息，可能是 2FA
                        // 检查响应头中的 X-Apple-TwoSV-Trust-Eligible
                        val twoSvEligible = response.header("X-Apple-TwoSV-Trust-Eligible")
                        if (twoSvEligible == "true" && sessionToken != null) {
                            requiresTwoFactor = true
                            return AuthResult(
                                success = false,
                                requiresTwoFactor = true,
                                errorMessage = "Two-factor authentication code required"
                            )
                        }

                        AuthResult(
                            success = false,
                            errorMessage = "Account verification failed: ${e.message}"
                        )
                    }
                } ?: run {
                    // 没有响应体，但有会话信息，可能需要 2FA
                    val twoSvEligible = response.header("X-Apple-TwoSV-Trust-Eligible")
                    if (twoSvEligible == "true" && sessionToken != null) {
                        requiresTwoFactor = true
                        AuthResult(
                            success = false,
                            requiresTwoFactor = true,
                            errorMessage = "Two-factor authentication code required"
                        )
                    } else {
                        AuthResult(
                            success = false,
                            errorMessage = "The account needs verification"
                        )
                    }
                }
            }
            503 -> {
                AuthResult(
                    success = false,
                    errorMessage = "Sign-in failed (HTTP 503): Apple's servers are busy or rate-limiting. Wait a while and try again."
                )
            }
            401, 403 -> {
                AuthResult(
                    success = false,
                    errorMessage = "Incorrect Apple ID or password (HTTP ${response.code})"
                )
            }
            else -> {
                AuthResult(
                    success = false,
                    errorMessage = "Sign-in failed (HTTP ${response.code})"
                )
            }
        }
    }

    /**
     * One SRP sign-in request (init or complete) with the OAuth header
     * set the 2FA path already uses. The scnt / session-id headers
     * from the init response are carried into the complete request.
     */
    private fun executeSrpRequest(
        url: String,
        body: JsonObject,
        scntHeader: String?,
        sessionIdHeader: String?
    ): Response {
        val builder = Request.Builder()
            .url(url)
            .post(gson.toJson(body).toRequestBody("application/json".toMediaType()))
            .addHeader("Accept", "application/json, text/javascript")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Apple-OAuth-Client-Id", oauthClientId)
            .addHeader("X-Apple-OAuth-Client-Type", "firstPartyAuth")
            .addHeader("X-Apple-OAuth-Redirect-URI", "https://www.icloud.com")
            .addHeader("X-Apple-OAuth-Require-Grant-Code", "true")
            .addHeader("X-Apple-OAuth-Response-Mode", "web_message")
            .addHeader("X-Apple-OAuth-Response-Type", "code")
            .addHeader("X-Apple-OAuth-State", clientId)
            .addHeader("X-Apple-Widget-Key", oauthClientId)
            .addHeader("Referer", "https://idmsa.apple.com/")
            .addHeader("User-Agent", userAgent)
        scntHeader?.let { builder.addHeader("scnt", it) }
        sessionIdHeader?.let { builder.addHeader("X-Apple-ID-Session-Id", it) }
        return client.newCall(builder.build()).execute()
    }

    private fun decodeBase64(value: String): ByteArray? {
        return try {
            Base64.getDecoder().decode(value)
        } catch (e: Exception) {
            null
        }
    }

    /** Error text for a failed SRP step, always carrying the HTTP status. */
    private fun srpFailureMessage(code: Int, body: String?): String {
        val serverMessage = body?.let {
            try {
                gson.fromJson(it, JsonObject::class.java)
                    ?.getAsJsonArray("serviceErrors")
                    ?.firstOrNull()?.asJsonObject?.get("message")?.asString
            } catch (e: Exception) {
                null
            }
        }
        return when {
            serverMessage != null -> "Sign-in failed (HTTP $code): $serverMessage"
            code == 503 -> "Sign-in failed (HTTP 503): Apple's servers are busy or rate-limiting. Wait a while and try again."
            code == 401 || code == 403 -> "Sign-in failed (HTTP $code): incorrect Apple ID or password."
            else -> "Sign-in failed (HTTP $code)."
        }
    }

    /**
     * 账户登录（参考 setup.js 的 accountLogin）
     */
    /**
     * 账户登录（参考 setup.js 的 accountLogin 和 pyicloud_ic3.py 的 _authenticate_with_token）
     */
    private fun accountLogin(authToken: String): Boolean {
        // 参考 _authenticate_with_token，包含账户国家代码和信任令牌
        val authData = mutableMapOf<String, Any>(
            "dsWebAuthToken" to authToken,
            "extended_login" to true,
            "appName" to "iCloud3"  // 添加应用名称
        )
        
        // 添加账户国家代码（如果存在）
        accountCountry?.let { authData["accountCountryCode"] = it }
        
        // 添加信任令牌（如果存在）
        trustToken?.let { authData["trustToken"] = it }
        
        val requestBody = gson.toJson(authData).toRequestBody("text/plain".toMediaType())
        
        val request = Request.Builder()
            .url("https://setup.icloud.com/setup/ws/1/accountLogin?clientBuildNumber=$clientBuildNumber&clientId=$clientId&clientMasteringNumber=$clientMasteringNumber")
            .post(requestBody)
            .addHeader("Content-Type", "text/plain")
            .addHeader("Accept", "*/*")
            .addHeader("Referer", "https://www.icloud.com/")
            .addHeader("Origin", "https://www.icloud.com")
            .addHeader("User-Agent", userAgent)
            .build()
        
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
        
        
        if (response.isSuccessful && responseBody != null) {
            try {
                accountInfo = gson.fromJson(responseBody, JsonObject::class.java)
                
                // 保存 dsInfo 信息（参考 _authenticate_with_token）
                accountInfo?.getAsJsonObject("dsInfo")?.let { dsInfo ->
                    dsid = dsInfo.get("dsid")?.asString
                    accountName = dsInfo.get("fullName")?.asString
                    accountLocked = dsInfo.get("locked")?.asBoolean ?: false
                    
                }
                
                // 保存 webservices 信息
                webservices = accountInfo?.getAsJsonObject("webservices")
                if (webservices != null) {
                    // 提取 findme URL
                    webservices?.getAsJsonObject("findme")?.get("url")?.asString?.let { findmeUrl ->
                        findme_url = findmeUrl
                    }
                }
                
                // 检查 items 中的 2FA 状态（参考 _authenticate_with_token）
                accountInfo?.getAsJsonObject("items")?.let { items ->
                    val hsaTrustedBrowser = items.get("hsaTrustedBrowser")?.asBoolean ?: false
                    val hsaChallengeRequired = items.get("hsaChallengeRequired")?.asBoolean ?: false
                }
                
                return true
            } catch (e: Exception) {
            }
        } else {
        }
        
        return false
    }
    
    /**
     * 使用 Token 认证（参考 pyicloud_ic3.py 的 _authenticate_with_token）
     * 这一步在密码登录后是必需的，用于刷新会话并避免 450 错误
     */
    private fun authenticateWithToken(): Boolean {
        try {
            
            if (sessionToken.isNullOrEmpty()) {
                return false
            }
            
            // 构建认证数据（与 accountLogin 相同的数据结构）
            val authData = mutableMapOf<String, Any>(
                "dsWebAuthToken" to sessionToken!!,
                "extended_login" to true,
                "appName" to "iCloud3"
            )
            
            // 添加账户国家代码（如果存在）
            accountCountry?.let { authData["accountCountryCode"] = it }
            
            // 添加信任令牌（如果存在）
            trustToken?.let { authData["trustToken"] = it }
            
            val requestBody = gson.toJson(authData).toRequestBody("text/plain".toMediaType())
            
            val request = Request.Builder()
                .url("https://setup.icloud.com/setup/ws/1/accountLogin?clientBuildNumber=$clientBuildNumber&clientId=$clientId&clientMasteringNumber=$clientMasteringNumber")
                .post(requestBody)
                .addHeader("Content-Type", "text/plain")
                .addHeader("Accept", "*/*")
                .addHeader("Referer", "https://www.icloud.com/")
                .addHeader("Origin", "https://www.icloud.com")
                .addHeader("User-Agent", userAgent)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            
            if (response.isSuccessful && responseBody != null) {
                try {
                    val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                    
                    // 更新 dsInfo（如果有）
                    jsonResponse.getAsJsonObject("dsInfo")?.let { dsInfo ->
                        dsid = dsInfo.get("dsid")?.asString ?: dsid
                        accountName = dsInfo.get("fullName")?.asString ?: accountName
                    }
                    
                    // 更新 webservices（如果有）
                    jsonResponse.getAsJsonObject("webservices")?.let { ws ->
                        ws.getAsJsonObject("findme")?.get("url")?.asString?.let { findmeUrl ->
                            findme_url = findmeUrl
                        }
                    }
                    
                    // 检查 items 中的 2FA 状态
                    jsonResponse.getAsJsonObject("items")?.let { items ->
                        val hsaTrustedBrowser = items.get("hsaTrustedBrowser")?.asBoolean ?: false
                    }
                    
                    return true
                } catch (e: Exception) {
                }
            } else {
            }
            
        } catch (e: Exception) {
        }
        
        return false
    }
    
    /**
     * 刷新会话用于重试（处理 450 错误）
     * 参考 iCloud3 v3 的 authenticate(refresh_session=True)
     * 
     * 这是一个同步方法，用于在 FindMyService 中处理 450 错误时调用
     */
    fun refreshSessionForRetry(): Boolean {
        return try {
            
            if (sessionToken.isNullOrEmpty()) {
                return false
            }
            
            // 使用 runBlocking 调用挂起函数
            val success = runBlocking {
                authenticateWithToken()
            }
            
            if (success) {
            } else {
            }
            
            success
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取 Push Token（参考 setup.js 的 getPushToken）
     * 这个步骤在某些情况下不是必须的
```
     */
    private fun getPushToken() {
        val host = getHostFromWebservice("push") ?: return
        
        val pushData = mapOf(
            "pushTopics" to emptyList<String>(),
            "pushTokenTTL" to 43200
        )
        
        val requestBody = gson.toJson(pushData).toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("https://$host/getToken?attempt=1&clientBuildNumber=$clientBuildNumber&clientId=$clientId&clientMasteringNumber=$clientMasteringNumber&dsid=$dsid")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("Cookie", cookieJar.getCookiesAsString(host))
            .build()
        
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
        }
    }
    
    /**
     * 验证2FA代码（参考 pyicloud_ic3.py 的 validate_2fa_code）
     */
    suspend fun verify2FACode(code: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            
            if (sessionToken == null || sessionId == null || scnt == null) {
                return@withContext LoginResult(
                    success = false,
                    errorMessage = "The session has expired. Please sign in again."
                )
            }
            
            // 构建请求头（参考 _get_auth_headers）
            val requestBuilder = Request.Builder()
                .url("https://idmsa.apple.com/appleauth/auth/verify/trusteddevice/securitycode")
                .post(
                    gson.toJson(mapOf(
                        "securityCode" to mapOf("code" to code)
                    )).toRequestBody("application/json".toMediaType())
                )
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Apple-OAuth-Client-Id", "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d")
                .addHeader("X-Apple-OAuth-Client-Type", "firstPartyAuth")
                .addHeader("X-Apple-OAuth-Redirect-URI", "https://www.icloud.com")
                .addHeader("X-Apple-OAuth-Require-Grant-Code", "true")
                .addHeader("X-Apple-OAuth-Response-Mode", "web_message")
                .addHeader("X-Apple-OAuth-Response-Type", "code")
                .addHeader("X-Apple-OAuth-State", clientId)
                .addHeader("X-Apple-Widget-Key", "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d")
                .addHeader("X-Apple-ID-Session-Id", sessionId!!)
                .addHeader("scnt", scnt!!)
                .addHeader("Referer", "https://idmsa.apple.com/")
                .addHeader("User-Agent", userAgent)
            
            // 关键：添加 X-Apple-Session-Token 到请求头
            sessionToken?.let { requestBuilder.addHeader("X-Apple-Session-Token", it) }
            
            val response = client.newCall(requestBuilder.build()).execute()
            
            // 使用 response.body?.string() 会自动处理 gzip 解压
            val responseBody = response.body?.string()
            
            
            // 更新会话信息（重要！）
            response.header("X-Apple-Session-Token")?.let { 
                sessionToken = it 
            }
            response.header("X-Apple-ID-Session-Id")?.let { 
                sessionId = it 
            }
            response.header("scnt")?.let { 
                scnt = it 
            }
            response.header("X-Apple-TwoSV-Trust-Token")?.let {
                trustToken = it
            }
            response.header("X-Apple-ID-Account-Country")?.let {
                accountCountry = it
            }
            
            // 关键发现：如果服务器返回401但响应体为空，且更新了scnt，
            // 说明验证码被接受了，但需要继续完成认证流程
            val hasUpdatedSession = response.header("scnt") != null
            
            // 解析响应体检查错误码（参考 _resolve_error_code_reason）
            if (responseBody != null && responseBody.isNotEmpty()) {
                try {
                    val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
                    
                    // 检查 errorCode 字段
                    val errorCode = jsonResponse.get("errorCode")?.asInt
                    val errorMessage = jsonResponse.get("errorMessage")?.asString
                        ?: jsonResponse.get("reason")?.asString
                        ?: jsonResponse.get("error")?.asString
                    
                    
                    // -21669 = 验证码错误（参考 validate_2fa_code）
                    if (errorCode == -21669) {
                        return@withContext LoginResult(
                            success = false,
                            errorMessage = "Incorrect verification code. Please try again."
                        )
                    }
                    
                    // 其他错误码
                    if (errorCode != null && errorCode < 0) {
                        return@withContext LoginResult(
                            success = false,
                            errorMessage = errorMessage ?: "Verification failed (error code: $errorCode)"
                        )
                    }
                } catch (e: Exception) {
                    // 继续处理 HTTP 状态码
                }
            }
            
            // 根据 HTTP 状态码判断结果
            when (response.code) {
                200, 204 -> {
                    
                    // 验证成功后，请求信任会话（参考 trust_session）
                    trustSession()
                    
                    // 然后进行账户登录（参考 _authenticate_with_token）
                    sessionToken?.let { token ->
                        if (accountLogin(token)) {
                            requiresTwoFactor = false
                            LoginResult(success = true)
                        } else {
                            LoginResult(
                                success = false,
                                errorMessage = "Two-factor verification succeeded, but account login failed"
                            )
                        }
                    } ?: LoginResult(
                        success = false,
                        errorMessage = "Missing session token"
                    )
                }
                412 -> {
                    // 412 表示需要重新认证
                    LoginResult(
                        success = false,
                        errorMessage = "The session has expired. Please sign in again."
                    )
                }
                401 -> {
                    // 关键修复：401但响应体为空且更新了scnt，说明验证码被接受
                    if (hasUpdatedSession && (responseBody == null || responseBody.isEmpty())) {
                        
                        // 验证成功后，请求信任会话（参考 trust_session）
                        trustSession()
                        
                        // 然后进行账户登录（参考 _authenticate_with_token）
                        sessionToken?.let { token ->
                            if (accountLogin(token)) {
                                requiresTwoFactor = false
                                return@withContext LoginResult(success = true)
                            } else {
                                return@withContext LoginResult(
                                    success = false,
                                    errorMessage = "Two-factor verification succeeded, but account login failed"
                                )
                            }
                        } ?: run {
                            return@withContext LoginResult(
                                success = false,
                                errorMessage = "Missing session token"
                            )
                        }
                    } else {
                        // 401但没有更新会话，真正的认证失败
                        return@withContext LoginResult(
                            success = false,
                            errorMessage = "Incorrect verification code, or the session has expired"
                        )
                    }
                }
                403 -> {
                    return@withContext LoginResult(
                        success = false,
                        errorMessage = "Verification failed (HTTP ${response.code})"
                    )
                }
                else -> {
                    return@withContext LoginResult(
                        success = false,
                        errorMessage = "Verification failed (HTTP ${response.code})"
                    )
                }
            }
        } catch (e: Exception) {
            LoginResult(
                success = false,
                errorMessage = "Verification failed: ${e.message}"
            )
        }
    }
    
    /**
     * 信任当前会话（参考 pyicloud_ic3.py 的 trust_session）
     */
    private fun trustSession(): Boolean {
        try {
            
            // 构建请求头
            val headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "X-Apple-OAuth-Client-Id" to "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d",
                "X-Apple-OAuth-Client-Type" to "firstPartyAuth",
                "X-Apple-OAuth-Redirect-URI" to "https://www.icloud.com",
                "X-Apple-OAuth-Require-Grant-Code" to "true",
                "X-Apple-OAuth-Response-Mode" to "web_message",
                "X-Apple-OAuth-Response-Type" to "code",
                "X-Apple-OAuth-State" to clientId,
                "X-Apple-Widget-Key" to "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d",
                "X-Apple-ID-Session-Id" to (sessionId ?: ""),
                "X-Apple-Session-Token" to (sessionToken ?: ""),
                "scnt" to (scnt ?: ""),
                "Referer" to "https://idmsa.apple.com/",
                "User-Agent" to userAgent
            )
            
            // URL: {AUTH_ENDPOINT}/2sv/trust
            val request = Request.Builder()
                .url("https://idmsa.apple.com/appleauth/auth/2sv/trust")
                .get()
                .apply {
                    headers.forEach { (key, value) -> addHeader(key, value) }
                }
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                return true
            } else {
                return false
            }
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * 信任当前设备（已弃用，使用 trustSession 代替）
     */
    @Deprecated("Use trustSession() instead")
    private fun trustDevice() {
        try {
            
            val request = Request.Builder()
                .url("https://idmsa.apple.com/appleauth/auth/2sv/trust")
                .get()
                .addHeader("X-Apple-Widget-Key", widgetKey)
                .addHeader("X-Apple-ID-Session-Id", sessionId ?: "")
                .addHeader("X-Apple-Session-Token", sessionToken ?: "")
                .addHeader("scnt", scnt ?: "")
                .addHeader("Referer", "https://idmsa.apple.com/")
                .addHeader("User-Agent", userAgent)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
            }
        } catch (e: Exception) {
        }
    }
    
    /**
     * 从 webservices 中获取服务主机地址
     */
    private fun getHostFromWebservice(serviceName: String): String? {
        return try {
            webservices?.getAsJsonObject(serviceName)?.get("url")?.asString?.let { url ->
                url.replace("https://", "").replace("http://", "")
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 登录结果
 */
data class LoginResult(
    val success: Boolean,
    val requiresTwoFactor: Boolean = false,
    val errorMessage: String? = null
)

/**
 * 认证结果
 */
private data class AuthResult(
    val success: Boolean,
    val token: String? = null,
    val requiresTwoFactor: Boolean = false,
    val errorMessage: String? = null
)

// ==================== 会话持久化扩展方法（参考 iCloud3 v3）====================

/**
 * 尝试从存储恢复会话
 */
fun ICloudService.tryRestoreSession(email: String, password: String): Boolean {
    return try {
        val sessionManagerField = ICloudService::class.java.getDeclaredField("sessionManager")
        sessionManagerField.isAccessible = true
        val sessionManager = sessionManagerField.get(this) as? SessionManager
        
        if (sessionManager == null) {
            return false
        }
        
        if (!sessionManager.isSessionValid(email)) {
            return false
        }
        
        val restored = sessionManager.restoreSession(this)
        
        if (restored) {
        }
        
        restored
    } catch (e: Exception) {
        false
    }
}

/**
 * 保存会话到存储
 */
fun ICloudService.saveSessionToStorage(email: String, password: String) {
    try {
        
        val sessionManagerField = ICloudService::class.java.getDeclaredField("sessionManager")
        sessionManagerField.isAccessible = true
        val sessionManager = sessionManagerField.get(this) as? SessionManager
        
        
        if (sessionManager == null) {
            return
        }
        
        
        // 获取 private 字段
        val dsidField = ICloudService::class.java.getDeclaredField("dsid")
        dsidField.isAccessible = true
        val dsid = dsidField.get(this) as? String
        
        val findmeUrlField = ICloudService::class.java.getDeclaredField("findme_url")
        findmeUrlField.isAccessible = true
        val findme_url = findmeUrlField.get(this) as? String
        
        val accountNameField = ICloudService::class.java.getDeclaredField("accountName")
        accountNameField.isAccessible = true
        val accountName = accountNameField.get(this) as? String
        
        val accountLockedField = ICloudService::class.java.getDeclaredField("accountLocked")
        accountLockedField.isAccessible = true
        val accountLocked = accountLockedField.get(this) as? Boolean ?: false
        
        val webservicesField = ICloudService::class.java.getDeclaredField("webservices")
        webservicesField.isAccessible = true
        val webservices = webservicesField.get(this) as? com.google.gson.JsonObject
        
        val clientIdField = ICloudService::class.java.getDeclaredField("clientId")
        clientIdField.isAccessible = true
        val clientId = clientIdField.get(this) as String
        
        // 序列化 webservices
        val webservicesJson = webservices?.toString()
        
        // Batch 3: the password is not forwarded - SessionManager never
        // persists it (nor any fingerprint of it); storage is encrypted.
        sessionManager.saveSession(
            username = email,
            sessionToken = sessionToken,
            sessionId = sessionId,
            scnt = scnt,
            trustToken = trustToken,
            accountCountry = accountCountry,
            clientId = clientId,
            dsid = dsid,
            findmeUrl = findme_url,
            accountName = accountName,
            accountLocked = accountLocked,
            webservices = webservicesJson
        )
        
    } catch (e: Exception) {
    }
}

/**
 * 清除保存的会话
 */
fun ICloudService.clearSavedSession() {
    try {
        val sessionManagerField = ICloudService::class.java.getDeclaredField("sessionManager")
        sessionManagerField.isAccessible = true
        val sessionManager = sessionManagerField.get(this) as? SessionManager
        sessionManager?.clearSession()
    } catch (e: Exception) {
    }
}

/**
 * 持久化 Cookie 管理器
 * 
 *  关键修复: 支持根域名共享 (如 .icloud.com)
 * 
 * 问题: Cookies 保存在 setup.icloud.com，但请求发送到 p234-fmipweb.icloud.com
 * 解决: 同时将 cookies 保存到根域名，让所有子域名都能访问
 * 
 * 参考 iCloud3 V3: Python requests.Session 自动处理 cookie 域共享
 */
class PersistentCookieJar : CookieJar {
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        
        //  关键修复: 同时保存到当前域名和根域名
        val rootDomain = getRootDomain(host)
        
        // 保存到当前域名
        saveToHost(host, cookies)
        
        // 同时保存到根域名 (如果不同)
        if (host != rootDomain) {
            saveToHost(rootDomain, cookies)
        } else {
        }
    }
    
    private fun saveToHost(host: String, cookies: List<Cookie>) {
        val existing = cookieStore.getOrPut(host) { mutableListOf() }
        
        // 更新或添加 cookies
        cookies.forEach { newCookie ->
            existing.removeIf { it.name == newCookie.name }
            existing.add(newCookie)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val rootDomain = getRootDomain(host)
        
        //  关键修复: 从当前域名和根域名加载 cookies
        val hostCookies = cookieStore[host] ?: emptyList()
        val rootCookies = if (host != rootDomain) {
            cookieStore[rootDomain] ?: emptyList()
        } else {
            emptyList()
        }
        
        // 合并并去重
        val allCookies = (hostCookies + rootCookies)
            .distinctBy { it.name }  // 去重（优先使用当前域名的 cookie）
            .filter { cookie ->
                cookie.expiresAt > System.currentTimeMillis()  // 过滤过期 cookie
            }
        
        if (allCookies.isNotEmpty()) {
            // 调试: 打印 cookie 名称
        } else {
        }
        
        return allCookies
    }
    
    /**
     * 提取根域名
     * 例如: 
     *   p234-fmipweb.icloud.com -> icloud.com
     *   setup.icloud.com -> icloud.com
     *   www.apple.com -> apple.com
     */
    private fun getRootDomain(host: String): String {
        val parts = host.split(".")
        return when {
            // 对于 .com、.co.uk 等双后缀域名，保留最后 3 段
            parts.size >= 3 && parts[parts.size - 2].length <= 3 -> {
                parts.takeLast(3).joinToString(".")
            }
            // 对于普通域名，保留最后 2 段
            parts.size >= 2 -> {
                parts.takeLast(2).joinToString(".")
            }
            // 否则返回原域名
            else -> host
        }
    }
    
    fun getCookiesAsString(host: String): String {
        return cookieStore[host]?.joinToString("; ") { "${it.name}=${it.value}" } ?: ""
    }
}
