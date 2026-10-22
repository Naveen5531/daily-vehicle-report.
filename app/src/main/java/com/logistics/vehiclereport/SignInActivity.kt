package com.logistics.vehiclereport

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider

/** Sign in with a Google account or an email + password. Each account keeps its own data. */
class SignInActivity : AppCompatActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var email: TextInputEditText
    private lateinit var password: TextInputEditText
    private val buttons = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (Account.isSignedIn(this)) { openApp(); return }
        setContentView(R.layout.activity_sign_in)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.signRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        progress = findViewById(R.id.signProgress)
        email = findViewById(R.id.emailInput)
        password = findViewById(R.id.passwordInput)
        val google = findViewById<MaterialButton>(R.id.googleButton)
        val signIn = findViewById<MaterialButton>(R.id.emailButton)
        val create = findViewById<MaterialButton>(R.id.createButton)
        val forgot = findViewById<MaterialButton>(R.id.forgotButton)
        val local = findViewById<MaterialButton>(R.id.localButton)
        buttons += listOf(google, signIn, create, forgot, local)

        findViewById<TextView>(R.id.privacyText).setOnClickListener {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_url)))) } catch (e: Exception) { }
        }

        if (!Account.cloudReady(this)) {
            // Build without the Firebase settings: accounts are not available, use the phone only.
            // The data moves into your online account the first time you sign in on a build that has them.
            listOf(google, signIn, create, forgot).forEach { it.isEnabled = false }
            listOf(R.id.googleButton, R.id.signOr, R.id.emailLayout, R.id.passwordLayout, R.id.emailButtons, R.id.forgotButton)
                .forEach { findViewById<View>(it).visibility = View.GONE }
            findViewById<TextView>(R.id.signSub).setText(R.string.sign_sub_local)
            local.visibility = View.VISIBLE
            local.setOnClickListener { finishSignIn(Account.LOCAL_UID, "", getString(R.string.sign_local_name)) }
            return
        }
        google.setOnClickListener { googleSignIn() }
        signIn.setOnClickListener { emailSignIn(create = false) }
        create.setOnClickListener { emailSignIn(create = true) }
        forgot.setOnClickListener {
            val e = email.text?.toString()?.trim().orEmpty()
            if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) { toast(R.string.sign_need_email); return@setOnClickListener }
            busy(true)
            FirebaseAuth.getInstance().sendPasswordResetEmail(e)
                .addOnCompleteListener { t ->
                    busy(false)
                    toast(if (t.isSuccessful) R.string.sign_reset_sent else R.string.sign_failed)
                }
        }
    }

    private fun googleSignIn() {
        val id = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (id == 0) { toast(R.string.sign_google_not_ready); return }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(getString(id))
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        busy(true)
        CredentialManager.create(this).getCredentialAsync(this, request, null, androidx.core.content.ContextCompat.getMainExecutor(this),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val c = result.credential
                    if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val token = GoogleIdTokenCredential.createFrom(c.data).idToken
                        FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                            .addOnSuccessListener { r -> r.user?.let { done(it) } ?: failed(null) }
                            .addOnFailureListener { failed(it.localizedMessage) }
                    } else failed(null)
                }
                override fun onError(e: GetCredentialException) {
                    if (e is GetCredentialCancellationException) busy(false) else failed(e.localizedMessage)
                }
            })
    }

    private fun emailSignIn(create: Boolean) {
        val e = email.text?.toString()?.trim().orEmpty()
        val p = password.text?.toString().orEmpty()
        if (!Patterns.EMAIL_ADDRESS.matcher(e).matches()) { toast(R.string.sign_need_email); return }
        if (p.length < 6) { toast(R.string.sign_need_password); return }
        busy(true)
        val auth = FirebaseAuth.getInstance()
        val task = if (create) auth.createUserWithEmailAndPassword(e, p) else auth.signInWithEmailAndPassword(e, p)
        task.addOnSuccessListener { r ->
            val user = r.user ?: return@addOnSuccessListener failed(null)
            if (create) try { user.sendEmailVerification() } catch (x: Exception) { }
            done(user)
        }.addOnFailureListener { failed(it.localizedMessage) }
    }

    private fun done(user: FirebaseUser) =
        finishSignIn(user.uid, user.email.orEmpty(), user.displayName.orEmpty().ifBlank { user.email.orEmpty() })

    private fun finishSignIn(uid: String, mail: String, name: String) {
        Account.signedIn(this, uid, mail, name)
        busy(true)
        CloudSync.restoreOrUpload(this) { restored ->
            busy(false)
            if (restored) toast(R.string.sign_restored)
            openApp()
        }
    }

    private fun failed(msg: String?) {
        busy(false)
        Toast.makeText(this, getString(R.string.sign_failed) + (msg?.let { "\n$it" } ?: ""), Toast.LENGTH_LONG).show()
    }

    private fun busy(on: Boolean) {
        if (!::progress.isInitialized) return
        progress.visibility = if (on) View.VISIBLE else View.GONE
        buttons.forEach { it.isEnabled = !on && (it.id == R.id.localButton || Account.cloudReady(this)) }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            intent?.let { src -> if (src.action == Intent.ACTION_SEND || src.action == Intent.ACTION_SEND_MULTIPLE) { action = src.action; type = src.type; putExtras(src) } }
        })
        finish()
    }
}
