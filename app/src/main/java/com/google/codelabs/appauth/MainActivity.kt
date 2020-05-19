// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.codelabs.appauth

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.os.UserManager
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.codelabs.appauth.databinding.ActivityMainBinding
import com.squareup.picasso.Picasso
import kotlinx.coroutines.*
import net.openid.appauth.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.util.*
import kotlin.coroutines.CoroutineContext


class MainActivity : AppCompatActivity(), CoroutineScope {

    // Coroutines
    private lateinit var mJob: Job
    override val coroutineContext: CoroutineContext
        get() = mJob + Dispatchers.Main

    private var mMainApplication: MainApplication? = null
    private lateinit var binding: ActivityMainBinding

    // state
    private var mAuthState: AuthState? = null

    // login hint
    var loginHint: String? = null
        protected set

    // broadcast receiver for app restrictions changed broadcast
    var mRestrictionsReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mJob = Job()

        mMainApplication = application as MainApplication

        enablePostAuthorizationFlows()

        // wire click listeners
        binding.authorize.setOnClickListener {
            authorizeListener().invoke(it)
        }

        // Retrieve app restrictions and take appropriate action
        appRestrictions
    }

    override fun onResume() {
        super.onResume()

        // Retrieve app restrictions and take appropriate action
        appRestrictions

        // Register a receiver for app restrictions changed broadcast
        registerRestrictionsReceiver()
    }

    override fun onStop() {
        super.onStop()

        // Unregister receiver for app restrictions changed broadcast
        unregisterReceiver(mRestrictionsReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkIntent(intent)
    }

    private fun checkIntent(intent: Intent?) {
        if (intent != null) {
            when (intent.action) {
                "com.google.codelabs.appauth.HANDLE_AUTHORIZATION_RESPONSE" -> if (!intent.hasExtra(
                        USED_INTENT
                    )
                ) {
                    handleAuthorizationResponse(intent)
                    intent.putExtra(USED_INTENT, true)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        checkIntent(intent)

        // Register a receiver for app restrictions changed broadcast
        registerRestrictionsReceiver()
    }

    private fun enablePostAuthorizationFlows() {
        mAuthState = restoreAuthState()
        if (mAuthState?.isAuthorized == true) {
            if (binding.makeApiCall.visibility == View.GONE) {
                binding.makeApiCall.visibility = View.VISIBLE
                binding.makeApiCall.setOnClickListener {
                    launch(handler) {
                        //Working on UI thread
                        print(Thread.currentThread().name)
                        //Use withContext(Dispatchers.Default) to background thread
                        val deferred = withContext(Dispatchers.Default) {
                            getFromCallback(mAuthState!!, AuthorizationService(this@MainActivity))
                        }
                        //Working on UI thread
                        setUserInfo(deferred)
                    }
                }
            }
            if (binding.signOut.visibility == View.GONE) {
                binding.signOut.visibility = View.VISIBLE
                binding.signOut.setOnClickListener {
                    this@MainActivity.mAuthState = null
                    this@MainActivity.clearAuthState()
                    this@MainActivity.enablePostAuthorizationFlows()
                }
            }
        } else {
            binding.userInfoData.visibility = View.GONE
            binding.makeApiCall.visibility = View.GONE
            binding.signOut.visibility = View.GONE
        }
    }

    private val handler = CoroutineExceptionHandler { _, throwable ->
        Log.e("Exception", ":$throwable")
    }

    /**
     * Exchanges the code, for the [TokenResponse].
     *
     * @param intent represents the [Intent] from the Custom Tabs or the System Browser.
     */
    private fun handleAuthorizationResponse(intent: Intent) {
        val response = AuthorizationResponse.fromIntent(intent)
        val error = AuthorizationException.fromIntent(intent)
        val authState = AuthState(response, error)
        if (response != null) {
            Log.i(
                MainApplication.LOG_TAG,
                String.format("Handled Authorization Response %s ", authState.toJsonString())
            )
            val service = AuthorizationService(this)
            service.performTokenRequest(
                response.createTokenExchangeRequest()
            ) { tokenResponse, exception ->
                if (exception != null) {
                    Log.w(
                        MainApplication.LOG_TAG,
                        "Token Exchange failed",
                        exception
                    )
                } else {
                    if (tokenResponse != null) {
                        authState.update(tokenResponse, exception)
                        persistAuthState(authState)
                        Log.i(
                            MainApplication.LOG_TAG,
                            String.format(
                                "Token Response [ Access Token: %s, ID Token: %s ]",
                                tokenResponse.accessToken,
                                tokenResponse.idToken
                            )
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun persistAuthState(authState: AuthState) {
        getSharedPreferences(
            SHARED_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(AUTH_STATE, authState.toJsonString())
            .commit()
        enablePostAuthorizationFlows()
    }

    private fun clearAuthState() {
        getSharedPreferences(
            SHARED_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().remove(AUTH_STATE).apply()
    }

    private fun restoreAuthState(): AuthState? {
        val jsonString = getSharedPreferences(
            SHARED_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
            .getString(AUTH_STATE, null)
        if (!TextUtils.isEmpty(jsonString)) {
            try {
                return AuthState.fromJson(jsonString!!)
            } catch (jsonException: JSONException) {
                // should never happen
            }
        }
        return null
    }

    /**
     * Kicks off the authorization flow.
     */
    private fun authorizeListener(): (view: View) -> Unit = {
        val serviceConfiguration =
            AuthorizationServiceConfiguration(
                Uri.parse("https://accounts.google.com/o/oauth2/v2/auth") /* auth endpoint */,
                Uri.parse("https://www.googleapis.com/oauth2/v4/token") /* token endpoint */
            )
        val authorizationService = AuthorizationService(it.context)
        val clientId =
            "511828570984-fuprh0cm7665emlne3rnf9pk34kkn86s.apps.googleusercontent.com"
        val redirectUri =
            Uri.parse("com.google.codelabs.appauth:/oauth2callback")
        val builder = AuthorizationRequest.Builder(
            serviceConfiguration,
            clientId,
            AuthorizationRequest.RESPONSE_TYPE_CODE,
            redirectUri
        )
        builder.setScopes("profile")
        if (this@MainActivity.loginHint != null) {
            val loginHintMap: MutableMap<String, String> =
                HashMap<String, String>()
            this@MainActivity.loginHint?.run {
                loginHintMap[LOGIN_HINT] = this
            }
            builder.setAdditionalParameters(loginHintMap)
            Log.i(
                MainApplication.LOG_TAG,
                String.format("login_hint: %s", this@MainActivity.loginHint)
            )
        }
        val request = builder.build()
        val action = "com.google.codelabs.appauth.HANDLE_AUTHORIZATION_RESPONSE"
        val postAuthorizationIntent = Intent(action)
        val pendingIntent = PendingIntent.getActivity(
            it.context,
            request.hashCode(),
            postAuthorizationIntent,
            0
        )
        authorizationService.performAuthorizationRequest(request, pendingIntent)
    }

    private suspend fun getFromCallback(
        mAuthState: AuthState,
        mAuthorizationService: AuthorizationService
    ) = suspendCancellableCoroutine<JSONObject?> {
        mAuthState.performActionWithFreshTokens(
            mAuthorizationService
        ) { accessToken, idToken, exception ->
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://www.googleapis.com/oauth2/v3/userinfo")
                .addHeader(
                    "Authorization",
                    String.format("Bearer %s", accessToken)
                )
                .build()
            try {
                val response =
                    client.newCall(request).execute()
                val jsonBody = response.body!!.string()
                Log.i(
                    MainApplication.LOG_TAG,
                    String.format("User Info Response %s", jsonBody)
                )
                it.resumeWith(Result.success(JSONObject(jsonBody)))
            } catch (exception: Exception) {
                it.resumeWith(Result.success(null))
                Log.w(MainApplication.LOG_TAG, exception)
            }
        }
    }

    private fun setUserInfo(userInfo: JSONObject?) {
        userInfo?.let { user ->
            val fullName = user.optString("name", "")
            val givenName =
                user.optString("given_name", "")
            val familyName =
                user.optString("family_name", "")
            val imageUrl =
                user.optString("picture", "")
            if (!TextUtils.isEmpty(imageUrl)) {
                Picasso.with(this@MainActivity)
                    .load(imageUrl)
                    .placeholder(R.drawable.ic_account_circle_black_48dp)
                    .into(this@MainActivity.binding.profileImage)
            }
            if (!TextUtils.isEmpty(fullName)) {
                this@MainActivity.binding.fullName.text = fullName
            }
            if (!TextUtils.isEmpty(givenName)) {
                this@MainActivity.binding.givenName.text = givenName
            }
            if (!TextUtils.isEmpty(familyName)) {
                this@MainActivity.binding.familyName.text = familyName
            }
            val message: String = if (user.has("error")) {
                String.format(
                    "%s [%s]",
                    this@MainActivity.getString(R.string.request_failed),
                    user.optString(
                        "error_description",
                        "No description"
                    )
                )
            } else {
                this@MainActivity.getString(R.string.request_complete)
            }
            binding.userInfoData.visibility = View.VISIBLE
            Snackbar.make(
                this@MainActivity.binding.profileImage,
                message,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    // Block user if KEY_RESTRICTIONS_PENDING is true, and save login hint if available
    private val appRestrictions: Unit
        get() {
            val restrictionsManager = this
                .getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
            val appRestrictions = restrictionsManager.applicationRestrictions

            // Block user if KEY_RESTRICTIONS_PENDING is true, and save login hint if available
            if (!appRestrictions.isEmpty) {
                if (!appRestrictions.getBoolean(UserManager.KEY_RESTRICTIONS_PENDING)) {
                    loginHint = appRestrictions.getString(LOGIN_HINT)
                } else {
                    Toast.makeText(
                        this, R.string.restrictions_pending_block_user,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }

    private fun registerRestrictionsReceiver() {
        val restrictionsFilter =
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)
        mRestrictionsReceiver = object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent
            ) {
                appRestrictions
            }
        }
        registerReceiver(mRestrictionsReceiver, restrictionsFilter)
    }

    override fun onDestroy() {
        super.onDestroy()
        mJob.cancel()
    }

    companion object {
        private const val SHARED_PREFERENCES_NAME = "AuthStatePreference"
        private const val AUTH_STATE = "AUTH_STATE"
        private const val USED_INTENT = "USED_INTENT"
        private const val LOGIN_HINT = "login_hint"
    }
}