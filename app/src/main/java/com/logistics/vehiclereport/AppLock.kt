package com.logistics.vehiclereport

import android.content.Context
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/** Fingerprint / phone-PIN lock for the app, and screenshot blocking. */
object AppLock {
    private const val KEY_LOCK = "security_lock"
    private const val KEY_SECURE = "security_block_screens"
    private const val RELOCK_AFTER_MS = 60_000L

    private var unlockedUntilBackground = false
    private var authInProgress = false
    private var wentBackgroundAt = 0L

    fun lockEnabled(c: Context) = VehicleStore.prefs(c).getBoolean(KEY_LOCK, true)
    fun setLockEnabled(c: Context, on: Boolean) = VehicleStore.prefs(c).edit().putBoolean(KEY_LOCK, on).apply()
    fun blockScreens(c: Context) = VehicleStore.prefs(c).getBoolean(KEY_SECURE, true)
    fun setBlockScreens(c: Context, on: Boolean) = VehicleStore.prefs(c).edit().putBoolean(KEY_SECURE, on).apply()

    /** Can this phone ask for fingerprint / face / PIN / pattern? (Needs a screen lock set up.) */
    fun available(c: Context) =
        BiometricManager.from(c).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    fun applyScreenBlock(a: AppCompatActivity) {
        if (blockScreens(a)) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Another screen of the app is now in front (not the app going away): the unlock still holds. */
    fun stillInApp() {
        if (unlockedUntilBackground && !authInProgress) wentBackgroundAt = 0L
    }

    fun onBackground() {
        if (!authInProgress) wentBackgroundAt = System.currentTimeMillis()
    }

    /** Call from onStart. Hides the screen and asks for authentication when needed. */
    fun checkOnStart(a: AppCompatActivity) {
        if (!lockEnabled(a) || authInProgress) return
        val away = System.currentTimeMillis() - wentBackgroundAt
        if (unlockedUntilBackground && (wentBackgroundAt == 0L || away < RELOCK_AFTER_MS)) return
        if (!available(a)) {
            Toast.makeText(a, R.string.lock_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        unlockedUntilBackground = false
        authInProgress = true
        val content = a.window.decorView
        content.alpha = 0f

        val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlockedUntilBackground = true
                authInProgress = false
                wentBackgroundAt = 0L
                content.alpha = 1f
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelled or failed: close the app, nothing is shown.
                authInProgress = false
                a.finishAffinity()
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(a.getString(R.string.lock_title))
            .setSubtitle(a.getString(R.string.lock_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        content.post {
            try {
                prompt.authenticate(info)
            } catch (e: Exception) {
                // Never lock the user out because of a prompt problem.
                authInProgress = false
                unlockedUntilBackground = true
                content.alpha = 1f
            }
        }
    }
}
