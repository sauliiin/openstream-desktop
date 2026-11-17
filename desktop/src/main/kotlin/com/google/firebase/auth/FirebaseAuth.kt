package com.google.firebase.auth

import android.net.Uri
import com.google.android.gms.tasks.Task
import com.mdblisthub.desktop.AppDirs
import com.mdblisthub.tv.BuildConfig
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * Firebase Authentication for the desktop, over its REST API — the same calls
 * the web app makes (`google-auth.service.ts` in mdblist-hub): a Google ID
 * token is exchanged at `accounts:signInWithIdp`, and the resulting refresh
 * token keeps the session alive across launches.
 *
 * Shaped like the Android SDK so `AuthRepository` runs unchanged.
 */
class FirebaseAuth private constructor() {

    fun interface AuthStateListener {
        fun onAuthStateChanged(auth: FirebaseAuth)
    }

    @Serializable
    internal data class Stored(
        val uid: String,
        val email: String? = null,
        val displayName: String? = null,
        val photoUrl: String? = null,
        val idToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
    )

    private val file = File(AppDirs.data, "firebase-auth.json")
    private val listeners = CopyOnWriteArrayList<AuthStateListener>()

    @Volatile
    internal var stored: Stored? = runCatching { json.decodeFromString<Stored>(file.readText()) }.getOrNull()
        private set

    val currentUser: FirebaseUser?
        get() = stored?.let { FirebaseUser(this, it) }

    fun addAuthStateListener(listener: AuthStateListener) {
        listeners += listener
        listener.onAuthStateChanged(this)
    }

    fun removeAuthStateListener(listener: AuthStateListener) {
        listeners -= listener
    }

    fun signInWithCredential(credential: AuthCredential): Task<AuthResult> = Task.run {
        val body = buildJsonObject {
            put("postBody", "id_token=${credential.idToken}&providerId=${credential.provider}")
            put("requestUri", ORIGIN)
            put("returnIdpCredential", true)
            put("returnSecureToken", true)
        }.toString().toRequestBody("application/json".toMediaType())
        val response = call(
            Request.Builder().url("$IDENTITY/accounts:signInWithIdp?key=$apiKey").post(body),
        )
        fun field(name: String) = response[name]?.jsonPrimitive?.content
        val next = Stored(
            uid = field("localId") ?: error("Firebase did not return a user id"),
            email = field("email"),
            displayName = field("displayName") ?: field("fullName"),
            photoUrl = field("photoUrl"),
            idToken = field("idToken") ?: error("Firebase did not return a token"),
            refreshToken = field("refreshToken") ?: error("Firebase did not return a refresh token"),
            expiresAtMs = System.currentTimeMillis() + (field("expiresIn")?.toLongOrNull() ?: 3600L) * 1000L,
        )
        save(next)
        AuthResult(FirebaseUser(this, next))
    }

    fun signOut() {
        save(null)
    }

    /** A valid ID token, refreshed through securetoken when it is close to expiry. */
    internal fun token(forceRefresh: Boolean): String {
        val current = stored ?: throw FirebaseAuthException("ERROR_NO_SIGNED_IN_USER", "Not signed in")
        if (!forceRefresh && current.expiresAtMs - System.currentTimeMillis() > REFRESH_MARGIN_MS) {
            return current.idToken
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", current.refreshToken)
            .build()
        val response = call(Request.Builder().url("$SECURE_TOKEN/token?key=$apiKey").post(form))
        fun field(name: String) = response[name]?.jsonPrimitive?.content
        val refreshed = current.copy(
            idToken = field("id_token") ?: error("Token refresh returned no token"),
            refreshToken = field("refresh_token") ?: current.refreshToken,
            expiresAtMs = System.currentTimeMillis() + (field("expires_in")?.toLongOrNull() ?: 3600L) * 1000L,
        )
        save(refreshed, notify = false)
        return refreshed.idToken
    }

    @Synchronized
    private fun save(value: Stored?, notify: Boolean = true) {
        stored = value
        runCatching {
            if (value == null) file.delete() else file.writeText(json.encodeToString(Stored.serializer(), value))
        }
        if (notify) listeners.forEach { it.onAuthStateChanged(this) }
    }

    private fun call(request: Request.Builder): JsonObject {
        if (apiKey.isBlank()) throw FirebaseAuthException("ERROR_API_KEY", "FIREBASE_WEB_API_KEY was not set at build time")
        // The web key is restricted to the app's origins; the desktop app
        // speaks for the same loopback origin its sign-in page is served from.
        request.header("Referer", "$ORIGIN/").header("Origin", ORIGIN)
        client.newCall(request.build()).execute().use { response ->
            val text = response.body.string()
            val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
            if (!response.isSuccessful || obj == null) {
                val message = runCatching {
                    (obj?.get("error") as? JsonObject)?.get("message")?.jsonPrimitive?.content
                }.getOrNull() ?: "HTTP ${response.code}"
                throw FirebaseAuthException("ERROR_${response.code}", message)
            }
            return obj
        }
    }

    companion object {
        /** The loopback origin registered with the Google OAuth client — see README. */
        const val ORIGIN = "http://127.0.0.1:47311"

        private const val IDENTITY = "https://identitytoolkit.googleapis.com/v1"
        private const val SECURE_TOKEN = "https://securetoken.googleapis.com/v1"
        private const val REFRESH_MARGIN_MS = 5 * 60_000L
        private val apiKey = BuildConfig.FIREBASE_WEB_API_KEY
        private val json = Json { ignoreUnknownKeys = true }
        private val client = OkHttpClient()
        private val shared by lazy { FirebaseAuth() }

        @JvmStatic fun getInstance(): FirebaseAuth = shared
    }
}

class FirebaseAuthException(val errorCode: String, message: String) : Exception(message)

class AuthResult internal constructor(val user: FirebaseUser?)

open class AuthCredential internal constructor(val provider: String, internal val idToken: String)

object GoogleAuthProvider {
    const val PROVIDER_ID = "google.com"
    @JvmStatic fun getCredential(idToken: String?, accessToken: String?): AuthCredential {
        @Suppress("UNUSED_VARIABLE") val unused = accessToken
        return AuthCredential(PROVIDER_ID, idToken.orEmpty())
    }
}

class GetTokenResult internal constructor(val token: String?)

class FirebaseUser internal constructor(
    private val auth: FirebaseAuth,
    private val data: FirebaseAuth.Stored,
) {
    val uid: String get() = data.uid
    val email: String? get() = data.email
    val displayName: String? get() = data.displayName
    val photoUrl: Uri? get() = data.photoUrl?.let(Uri::parse)

    fun getIdToken(forceRefresh: Boolean): Task<GetTokenResult> =
        Task.run { GetTokenResult(auth.token(forceRefresh)) }
}
