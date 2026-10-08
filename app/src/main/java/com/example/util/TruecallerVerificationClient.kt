package com.example.util

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.fragment.app.FragmentActivity
import com.truecaller.android.sdk.CodeVerifierUtil
import com.truecaller.android.sdk.TcOAuthCallback
import com.truecaller.android.sdk.TcOAuthData
import com.truecaller.android.sdk.TcOAuthError
import com.truecaller.android.sdk.TcSdk
import com.truecaller.android.sdk.TcSdkOptions
import java.math.BigInteger
import java.security.SecureRandom
import java.util.concurrent.Executors

data class TruecallerProfileResult(
    val phoneNumber: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val avatarUrl: String = "",
    val authorizationCode: String = "",
    val codeVerifier: String = "",
    val state: String = "",
    val scopesGranted: List<String> = emptyList()
)

interface TruecallerVerificationClient {
    interface Callback {
        fun onSuccess(profile: TruecallerProfileResult)
        fun onFailure(reason: String)
        fun onVerificationRequired(reason: String?)
    }

    fun setCallback(callback: Callback?)
    fun initialize(activity: FragmentActivity)
    fun isUsable(): Boolean
    fun launch(activity: FragmentActivity): Boolean
    fun onActivityResult(activity: FragmentActivity, requestCode: Int, resultCode: Int, data: Intent?)
    fun setActivityResultLauncher(launcher: ActivityResultLauncher<Intent>) {}
    fun clear()
}

object PlatformTruecallerVerificationClient : TruecallerVerificationClient {
    private const val TAG = "TruecallerVerification"
    private const val EXPECTED_PACKAGE = "com.aistudio.freedating.mkwvxq"
    private const val EXPECTED_CLIENT_ID = "z3rfwgowtpkt-4_5hmsogbcjf2wc8igmb_bjpvzk8xc"
    private const val CLIENT_ID_METADATA = "com.truecaller.android.sdk.ClientId"

    private val secureRandom = SecureRandom()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initializationExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var callback: TruecallerVerificationClient.Callback? = null
    @Volatile private var initialized = false
    @Volatile private var initializing = false
    @Volatile private var lastActivity: FragmentActivity? = null
    @Volatile private var activityResultLauncher: ActivityResultLauncher<Intent>? = null
    @Volatile private var launchWhenReady = false
    @Volatile private var lastOAuthState = ""
    @Volatile private var lastCodeVerifier = ""

    override fun setCallback(callback: TruecallerVerificationClient.Callback?) {
        this.callback = callback
    }

    override fun setActivityResultLauncher(launcher: ActivityResultLauncher<Intent>) {
        activityResultLauncher = launcher
    }

    override fun initialize(activity: FragmentActivity) {
        lastActivity = activity
        if (initialized || initializing) return

        synchronized(this) {
            lastActivity = activity
            if (initialized || initializing) return
            initializing = true

            initializationExecutor.execute {
                try {
                    validateConfiguration(activity)

                    val sdkCallback = object : TcOAuthCallback {
                        override fun onSuccess(tcOAuthData: TcOAuthData) {
                            val state = tcOAuthData.state.orEmpty()
                            val expectedState = lastOAuthState
                            val authorizationCode = tcOAuthData.authorizationCode.orEmpty()
                            val verifier = lastCodeVerifier

                            if (expectedState.isBlank() || state != expectedState) {
                                clearRequestState()
                                notifyFailure("Truecaller authorization state mismatch.")
                                return
                            }
                            if (authorizationCode.isBlank() || verifier.isBlank()) {
                                clearRequestState()
                                notifyFailure("Truecaller authorization response was incomplete.")
                                return
                            }

                            clearRequestState()
                            notifySuccess(
                                TruecallerProfileResult(
                                    authorizationCode = authorizationCode,
                                    codeVerifier = verifier,
                                    state = state,
                                    scopesGranted = tcOAuthData.scopesGranted.orEmpty()
                                )
                            )
                        }

                        override fun onFailure(tcOAuthError: TcOAuthError) {
                            clearRequestState()
                            notifyFailure(
                                "Truecaller authorization was not completed (code ${tcOAuthError.errorCode})."
                            )
                        }
                    }

                    val sdkOptions = TcSdkOptions.Builder(activity, sdkCallback)
                        .consentMode(TcSdkOptions.CONSENT_MODE_BOTTOMSHEET)
                        .footerType(TcSdkOptions.FOOTER_TYPE_ANOTHER_METHOD)
                        .consentTitleOption(TcSdkOptions.SDK_CONSENT_TITLE_LOG_IN)
                        .heading(TcSdkOptions.SDK_CONSENT_HEADING_LOGIN_TO_WITH_ONE_TAP)
                        .ctaText(TcSdkOptions.CTA_TEXT_CONTINUE)
                        .sdkOptions(TcSdkOptions.OPTION_VERIFY_ONLY_TC_USERS)
                        .build()

                    TcSdk.init(sdkOptions)
                    initialized = true
                    initializing = false

                    if (launchWhenReady) {
                        launchWhenReady = false
                        mainHandler.post { launchOnMain(activity) }
                    }
                } catch (t: Throwable) {
                    initialized = false
                    initializing = false
                    launchWhenReady = false
                    Log.w(TAG, "Truecaller OAuth SDK unavailable: ${t.message}", t)
                    notifyFailure("Truecaller could not be initialized.")
                }
            }
        }
    }

    override fun isUsable(): Boolean {
        if (!initialized) return false
        return try {
            TcSdk.getInstance().isOAuthFlowUsable
        } catch (t: Throwable) {
            Log.w(TAG, "Truecaller OAuth usability check failed: ${t.message}")
            false
        }
    }

    override fun launch(activity: FragmentActivity): Boolean {
        lastActivity = activity
        return try {
            validateConfiguration(activity)
            if (!initialized) {
                launchWhenReady = true
                initialize(activity)
                true
            } else {
                mainHandler.post { launchOnMain(activity) }
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Truecaller OAuth launch rejected: ${t.message}", t)
            notifyFailure("Truecaller could not be opened.")
            false
        }
    }

    private fun launchOnMain(activity: FragmentActivity) {
        try {
            if (!initialized) {
                notifyFailure("Truecaller is not ready.")
                return
            }
            if (activityResultLauncher == null) {
                notifyFailure("Truecaller activity launcher is not configured.")
                return
            }
            if (!TcSdk.getInstance().isOAuthFlowUsable) {
                notifyFailure("Truecaller is not available on this device.")
                return
            }

            val state = BigInteger(130, secureRandom).toString(32)
            val codeVerifier = CodeVerifierUtil.generateRandomCodeVerifier()
            val codeChallenge = CodeVerifierUtil.getCodeChallenge(codeVerifier)
                ?: throw IllegalStateException("Truecaller PKCE code challenge could not be generated")

            lastOAuthState = state
            lastCodeVerifier = codeVerifier

            TcSdk.getInstance().setOAuthState(state)
            TcSdk.getInstance().setOAuthScopes(arrayOf("openid", "profile", "phone"))
            TcSdk.getInstance().setCodeChallenge(codeChallenge)
            TcSdk.getInstance().getAuthorizationCode(activity, activityResultLauncher)
        } catch (t: Throwable) {
            clearRequestState()
            Log.w(TAG, "Truecaller OAuth request failed: ${t.message}", t)
            notifyFailure("Truecaller could not be opened.")
        }
    }

    override fun onActivityResult(
        activity: FragmentActivity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        if (!initialized) return
        try {
            TcSdk.getInstance().onActivityResultObtained(activity, resultCode, data)
        } catch (t: Throwable) {
            Log.w(TAG, "Truecaller OAuth activity-result handling failed: ${t.message}", t)
            notifyFailure("Truecaller verification could not be completed.")
        }
    }

    private fun validateConfiguration(activity: FragmentActivity) {
        require(activity.packageName == EXPECTED_PACKAGE) {
            "Truecaller package mismatch: ${activity.packageName}"
        }

        val appInfo = activity.packageManager.getApplicationInfo(
            activity.packageName,
            PackageManager.GET_META_DATA
        )
        val configuredClientId = appInfo.metaData?.getString(CLIENT_ID_METADATA).orEmpty()
        require(configuredClientId == EXPECTED_CLIENT_ID) {
            "Truecaller Client ID mismatch"
        }
    }

    private fun clearRequestState() {
        lastOAuthState = ""
        lastCodeVerifier = ""
    }

    private fun notifySuccess(profile: TruecallerProfileResult) {
        mainHandler.post { callback?.onSuccess(profile) }
    }

    private fun notifyFailure(reason: String) {
        mainHandler.post { callback?.onFailure(reason) }
    }

    override fun clear() {
        try {
            if (initialized) TcSdk.clear()
        } catch (t: Throwable) {
            Log.w(TAG, "Truecaller OAuth SDK clear failed: ${t.message}")
        } finally {
            initialized = false
            initializing = false
            launchWhenReady = false
            lastActivity = null
            activityResultLauncher = null
            clearRequestState()
        }
    }
}
