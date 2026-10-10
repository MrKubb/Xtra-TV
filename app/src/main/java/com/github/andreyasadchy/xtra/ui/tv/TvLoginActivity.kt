package com.github.andreyasadchy.xtra.ui.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ActivityTvLoginBinding
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TV login: shows a code to enter on twitch.tv/activate from a phone/PC, then polls for the token.
 * Same device-code flow and token storage as LoginActivity's "external code login".
 * Xtra's regular LoginActivity stays available as a fallback.
 */
class TvLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvLoginBinding
    private val authRepository get() = (application as XtraApp).xtraModule.authRepository
    private val networkLibrary get() = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
    private var pollJob: Job? = null

    private val fallbackLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            setResult(RESULT_OK)
            finish()
        }
    }

    // Twitch Android TV client, the one LoginActivity also uses for the device-code flow.
    private val clientId: String
        get() = prefs().getString(C.GQL_CLIENT_ID2, null)
            ?.takeUnless { it.isBlank() || it == "kd1unb4b3q4t58fwlpcbzcbnm76a8fp" }
            ?: "ue6666qo983tsx6so1t0vnawi233wa"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        binding = ActivityTvLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (intent.getBooleanExtra(EXTRA_LOGOUT, false)) {
            logout()
        }
        binding.newCode.setOnClickListener { requestCode() }
        binding.otherMethod.setOnClickListener { fallbackLauncher.launch(Intent(this, LoginActivity::class.java)) }
        binding.newCode.requestFocus()
        requestCode()
    }

    private fun requestCode() {
        pollJob?.cancel()
        binding.code.text = "…"
        pollJob = lifecycleScope.launch {
            val deviceCode = try {
                authRepository.getDeviceCode(networkLibrary, "client_id=$clientId&scopes=$SCOPES").also {
                    binding.code.text = it.userCode
                }.deviceCode
            } catch (e: Exception) {
                null
            }
            if (deviceCode == null) {
                binding.code.text = "—"
                return@launch
            }
            // Poll until the user authorizes on twitch.tv/activate; a new code is requested if this one dies.
            while (true) {
                delay(5000)
                val response = try {
                    authRepository.getToken(networkLibrary, "client_id=$clientId&device_code=$deviceCode&grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")
                } catch (e: Exception) {
                    continue
                }
                val token = response.token
                when {
                    !token.isNullOrBlank() -> {
                        onToken(token)
                        return@launch
                    }
                    response.message?.contains("pending") == true || response.message?.contains("slow_down") == true -> continue
                    else -> {
                        requestCode()
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun onToken(token: String) {
        val validation = try {
            authRepository.validate(networkLibrary, TwitchApiHelper.addTokenPrefixGQL(token))
        } catch (e: Exception) {
            null
        }
        if (validation == null || validation.clientId != clientId) {
            requestCode()
            return
        }
        TwitchApiHelper.checkedValidation = true
        prefs().edit { putString(C.GQL_CLIENT_ID2, clientId) }
        tokenPrefs().edit {
            putString(C.GQL_TOKEN2, token)
            putString(C.USER_ID, validation.userId)
            putString(C.USERNAME, validation.login)
        }
        setResult(RESULT_OK)
        finish()
    }

    /** Same cleanup as LoginActivity does when opened while logged in. */
    private fun logout() {
        val helixHeaders = TwitchApiHelper.getHelixHeaders(this)
        val gqlHeaders = TwitchApiHelper.getGQLHeaders(this, true)
        val revoke = listOf(
            helixHeaders[C.HEADER_CLIENT_ID] to helixHeaders[C.HEADER_TOKEN]?.removePrefix("Bearer "),
            gqlHeaders[C.HEADER_CLIENT_ID] to gqlHeaders[C.HEADER_TOKEN]?.removePrefix("OAuth "),
            prefs().getString(C.GQL_CLIENT_ID_WEB, "kimne78kx3ncx6brgo4mv6wki5h1ko") to tokenPrefs().getString(C.GQL_TOKEN_WEB, null),
        ).filter { (id, token) -> !id.isNullOrBlank() && !token.isNullOrBlank() }.distinctBy { it.second }
        TwitchApiHelper.checkedValidation = false
        tokenPrefs().edit {
            putString(C.TOKEN, null)
            putString(C.GQL_HEADERS, null)
            putLong(C.INTEGRITY_EXPIRATION, 0)
            putString(C.GQL_TOKEN2, null)
            putString(C.GQL_TOKEN_WEB, null)
            putString(C.USER_ID, null)
            putString(C.USERNAME, null)
        }
        setResult(RESULT_OK)
        lifecycleScope.launch {
            revoke.forEach { (id, token) ->
                try {
                    authRepository.revoke(networkLibrary, "client_id=$id&token=$token")
                } catch (e: Exception) {
                }
            }
        }
    }

    companion object {
        const val EXTRA_LOGOUT = "logout"
        private const val SCOPES = "channel_read+chat%3Aread+user_blocks_edit+user_blocks_read+user_follows_edit+user_read"
    }
}
