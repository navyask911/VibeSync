package com.example.util

import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.truecaller.android.sdk.ITrueCallback
import com.truecaller.android.sdk.TrueError
import com.truecaller.android.sdk.TrueProfile
import com.truecaller.android.sdk.TruecallerSDK
import com.truecaller.android.sdk.TruecallerSdkScope
import java.security.SecureRandom

data class TruecallerProfileResult(
    val phoneNumber: String,
    val firstName: String,
    val lastName: String,
    val avatarUrl: String,
    val payload: String,
    val signature: String,
    val signatureAlgorithm: String,
    val requestNonce: String
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
    fun clear()
}

object PlatformTruecallerVerificationClient : TruecallerVerificationClient {
    private const val TAG = "TruecallerVerification"
    private const val CLIENT_ID = "z3rfwgowtpkt-4_5hmsogbcjf2wc8igmb_bjpvzk8xc"
    private val secureRandom = SecureRandom()

    @Volatile private var callback: TruecallerVerificationClient.Callback? = null
    @Volatile private var initialized = false
    @Volatile private var lastRequestNonce: String = ""
    @Volatile private var lastActivity: FragmentActivity? = null

    override fun setCallback(callback: TruecallerVerificationClient.Callback?) {
        this.callback = callback
    }

    override fun initialize(activity: FragmentActivity) {
        lastActivity = activity
        if (initialized) return

        synchronized(this) {
            lastActivity = activity
            if (initialized) return

            try {
                val sdkCallback = object : ITrueCallback {
                    override fun onSuccessProfileShared(trueProfile: TrueProfile) {
                        val nonce = trueProfile.requestNonce.orEmpty()
                        if (lastRequestNonce.isNotBlank() && nonce != lastRequestNonce) {
                            Log.w(TAG, "Rejected Truecaller response: request nonce mismatch")
                            callback?.onFailure("Truecaller verification response could not be correlated.")
                            return
                        }

                        callback?.onSuccess(
                            TruecallerProfileResult(
                                phoneNumber = trueProfile.phoneNumber.orEmpty(),
                                firstName = trueProfile.firstName.orEmpty(),
                                lastName = trueProfile.lastName.orEmpty(),
                                avatarUrl = trueProfile.avatarUrl.orEmpty(),
                                payload = trueProfile.payload.orEmpty(),
                                signature = trueProfile.signature.orEmpty(),
                                signatureAlgorithm = trueProfile.signatureAlgorithm.orEmpty(),
                                requestNonce = nonce
                            )
                        )
                    }

                    override fun onFailureProfileShared(trueError: TrueError) {
                        callback?.onFailure(
                            "Truecaller verification was not completed (code ${trueError.errorType})."
                        )
                    }

                    override fun onVerificationRequired(trueError: TrueError?) {
                        callback?.onVerificationRequired(
                            trueError?.errorType?.let {
                                "Truecaller manual verification required (code $it)."
                            }
                        )
                    }
                }

                val trueScope = TruecallerSdkScope.Builder(activity, sdkCallback)
                    .consentMode(TruecallerSdkScope.CONSENT_MODE_BOTTOMSHEET)
                    .footerType(TruecallerSdkScope.FOOTER_TYPE_CONTINUE)
                    .consentTitleOption(TruecallerSdkScope.SDK_CONSENT_TITLE_LOG_IN)
                    .sdkOptions(TruecallerSdkScope.SDK_OPTION_WITH_OTP)
                    .build()

                TruecallerSDK.init(trueScope)
                initialized = true
            } catch (t: Throwable) {
                initialized = false
                Log.w(TAG, "Truecaller SDK unavailable: ${t.message}")
            }
        }
    }

    override fun isUsable(): Boolean {
        return try {
            initialize(lastActivity ?: return false)
            TruecallerSDK.getInstance().isUsable()
        } catch (t: Throwable) {
            Log.w(TAG, "Truecaller isUsable() failed: ${t.message}")
            false
        }
    }

    override fun launch(activity: FragmentActivity): Boolean {
        lastActivity = activity
        return try {
            initialize(activity)
            if (!TruecallerSDK.getInstance().isUsable()) {
                callback?.onFailure("Truecaller is not available on this device.")
                return false
            }

            val nonceBytes = ByteArray(24).also(secureRandom::nextBytes)
            val nonce = Base64.encodeToString(
                nonceBytes,
                Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE
            )
            lastRequestNonce = nonce

            TruecallerSDK.getInstance().setRequestNonce(nonce)
            TruecallerSDK.getInstance().getUserProfile(activity)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Truecaller launch failed: ${t.message}")
            callback?.onFailure("Truecaller could not be opened.")
            false
        }
    }

    override fun onActivityResult(
        activity: FragmentActivity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        if (requestCode != TruecallerSDK.SHARE_PROFILE_REQUEST_CODE) return

        try {
            TruecallerSDK.getInstance().onActivityResultObtained(
                activity,
                requestCode,
                resultCode,
                data
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Truecaller activity-result handling failed: ${t.message}")
            callback?.onFailure("Truecaller verification could not be completed.")
        }
    }

    override fun clear() {
        try {
            if (initialized) TruecallerSDK.clear()
        } catch (t: Throwable) {
            Log.w(TAG, "Truecaller SDK clear failed: ${t.message}")
        } finally {
            initialized = false
            lastRequestNonce = ""
            lastActivity = null
        }
    }
}
