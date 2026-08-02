package com.logos.bibletranslate.data

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/**
 * Thin wrapper around Firebase Auth — Google Sign-In and email/password, the two methods this
 * app supports. FirebaseAuth persists the signed-in session itself (survives app restarts,
 * clears on sign-out or on the app's data/APK being removed), so "show the login page once per
 * device, and again after a reinstall" needs no bespoke logic here: it falls straight out of
 * checking [currentUser] on launch.
 *
 * [isConfigured] is the load-bearing guard for this whole file: Firebase only auto-initializes
 * when google-services.json existed at build time (see the app build.gradle.kts guard). Without
 * it, FirebaseAuth.getInstance() throws immediately — so every entry point here checks
 * [isConfigured] first and fails soft (null/no-op) instead of crashing the entire app on launch
 * before the Firebase console setup is even done. MainActivity treats "not configured" the same
 * as "signed in" so the reader works exactly as it did before this feature existed in the
 * meantime.
 */
class AuthRepository(private val context: Context) {

    val isConfigured: Boolean by lazy { FirebaseApp.getApps(context).isNotEmpty() }

    private val auth: FirebaseAuth? get() = if (isConfigured) FirebaseAuth.getInstance() else null

    val currentUser: FirebaseUser? get() = auth?.currentUser

    /** Emits the current user (or null) immediately, then again on every sign-in/out. Emits a
     *  single null and completes if Firebase isn't configured — MainActivity doesn't gate on
     *  this alone, see [isConfigured]. */
    fun authState(): Flow<FirebaseUser?> {
        val firebaseAuth = auth ?: return flowOf(null)
        return callbackFlow {
            val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
            firebaseAuth.addAuthStateListener(listener)
            awaitClose { firebaseAuth.removeAuthStateListener(listener) }
        }
    }

    /**
     * "default_web_client_id" is generated into resources by the google-services Gradle plugin
     * from google-services.json, which only exists after the one-time Firebase console setup
     * (see the app build.gradle.kts guard). Looked up by name at runtime — via getIdentifier,
     * not a direct R.string reference — specifically so this file compiles cleanly whether or
     * not that resource has been generated yet; referencing it directly would make the whole
     * app fail to build until Firebase is configured, defeating the point of that guard.
     */
    private fun webClientIdOrNull(): String? {
        val resId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (resId != 0) context.getString(resId) else null
    }

    private val googleSignInClient: GoogleSignInClient? by lazy {
        val webClientId = webClientIdOrNull() ?: return@lazy null
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    /** Launch this Intent via an ActivityResultLauncher; feed the result to [handleGoogleSignInResult].
     *  Null if Firebase hasn't been configured yet (see [webClientIdOrNull]) — callers should
     *  disable/hide the Google Sign-In button in that case rather than call this. */
    fun googleSignInIntent() = googleSignInClient?.signInIntent

    suspend fun handleGoogleSignInResult(data: android.content.Intent?): Result<FirebaseUser> {
        val firebaseAuth = auth ?: return Result.failure(Exception("Sign-in isn't set up yet."))
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).await()
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            val result = firebaseAuth.signInWithCredential(credential).await()
            result.user?.let { Result.success(it) } ?: Result.failure(Exception("Sign-in returned no user"))
        } catch (e: ApiException) {
            Result.failure(Exception(describeGoogleSignInError(e.statusCode)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser> {
        val firebaseAuth = auth ?: return Result.failure(Exception("Sign-in isn't set up yet."))
        return try {
            val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            result.user?.let { Result.success(it) } ?: Result.failure(Exception("Sign-in returned no user"))
        } catch (e: Exception) {
            Result.failure(Exception(describeAuthError(e)))
        }
    }

    suspend fun createAccountWithEmail(email: String, password: String): Result<FirebaseUser> {
        val firebaseAuth = auth ?: return Result.failure(Exception("Sign-in isn't set up yet."))
        return try {
            val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
            result.user?.let { Result.success(it) } ?: Result.failure(Exception("Account creation returned no user"))
        } catch (e: Exception) {
            Result.failure(Exception(describeAuthError(e)))
        }
    }

    fun signOut() {
        auth?.signOut()
        googleSignInClient?.signOut()
    }

    private fun describeAuthError(e: Exception): String {
        val message = e.message ?: return "Something went wrong — try again."
        return when {
            "INVALID_LOGIN_CREDENTIALS" in message || "password is invalid" in message ->
                "Incorrect email or password."
            "EMAIL_EXISTS" in message -> "An account already exists for that email."
            "WEAK_PASSWORD" in message -> "Password should be at least 6 characters."
            "badly formatted" in message -> "That doesn't look like a valid email address."
            "NETWORK_ERROR" in message -> "No internet connection — try again."
            else -> message
        }
    }

    private fun describeGoogleSignInError(statusCode: Int): String = when (statusCode) {
        12501 -> "Sign-in cancelled." // SIGN_IN_CANCELLED
        7 -> "No internet connection — try again." // NETWORK_ERROR
        else -> "Google sign-in failed (code $statusCode)."
    }
}
