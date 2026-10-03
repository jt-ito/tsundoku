package suwayomi.tachidesk.global.impl.util

import android.app.Application
import android.content.Context
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import io.github.oshai.kotlinlogging.KotlinLogging
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.user.UserManager
import suwayomi.tachidesk.server.user.UserSessions
import suwayomi.tachidesk.server.user.UserType
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object Jwt {
    private val preferenceStore =
        Injekt.get<Application>().getSharedPreferences("jwt", Context.MODE_PRIVATE)
    private val logger = KotlinLogging.logger {}

    private const val ALGORITHM = "HmacSHA256"
    private val accessTokenExpiry get() = serverConfig.jwtTokenExpiry.value
    private val refreshTokenExpiry get() = serverConfig.jwtRefreshExpiry.value
    private const val ISSUER = "suwayomi-server"
    private val AUDIENCE get() = serverConfig.jwtAudience.value

    private const val PREF_KEY = "jwt_key"

    @OptIn(ExperimentalEncodingApi::class)
    fun generateSecret(): String {
        val byteString = preferenceStore.getString(PREF_KEY, "")
        val decodedKeyBytes =
            try {
                Base64.Default.decode(byteString)
            } catch (e: IllegalArgumentException) {
                logger.warn(e) { "Invalid key specified, regenerating" }
                null
            }

        val keyBytes =
            if (decodedKeyBytes?.size == 32) {
                decodedKeyBytes
            } else {
                val k = ByteArray(32)
                SecureRandom().nextBytes(k)
                preferenceStore.edit().putString(PREF_KEY, Base64.Default.encode(k)).apply()
                k
            }

        val secretKey = SecretKeySpec(keyBytes, ALGORITHM)

        return Base64.encode(secretKey.encoded)
    }

    private val algorithm: Algorithm = Algorithm.HMAC256(generateSecret())
    private val verifier: JWTVerifier = JWT.require(algorithm).build()

    class JwtTokens(
        val accessToken: String,
        val refreshToken: String,
    )

    fun generateJwt(
        userId: Int = 1,
        username: String = "admin",
        role: String = "ADMIN",
    ): JwtTokens {
        val accessToken = createAccessToken(userId, username, role)
        val refreshToken = createRefreshToken(userId, username, role, UserSessions.create(userId))

        return JwtTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
        )
    }

    /** An access token on its own, without a session: it cannot be refreshed. */
    fun generateAccessToken(
        userId: Int,
        username: String,
        role: String,
    ): String = createAccessToken(userId, username, role)

    /** Ends the session of a refresh token. Works for expired tokens, a forged one is rejected. */
    fun revokeRefreshToken(refreshToken: String) {
        val decoded = JWT.decode(refreshToken)
        algorithm.verify(decoded)
        require(decoded.getClaim("token_type").asString() == "refresh") { "Not a refresh token" }
        decoded.getClaim("sid").asString()?.let { UserSessions.delete(it) }
    }

    fun refreshJwt(refreshToken: String): String {
        val jwt = verifier.verify(refreshToken)
        require(jwt.getClaim("token_type").asString() == "refresh") {
            "Cannot use access token to refresh"
        }
        require(jwt.audience.single() == AUDIENCE) {
            "Token intended for different audience ${jwt.audience}"
        }
        val userId = jwt.getClaim("user_id").asInt() ?: 1
        // a refresh token only works while its session exists (signed out, password changed, account deleted: gone)
        val sessionId = jwt.getClaim("sid").asString()
        require(sessionId != null && UserSessions.touch(sessionId, userId)) { "The session has ended, log in again" }
        // same as verifyJwt: refreshing must not resurrect deleted accounts or outdated roles
        val currentUser = UserManager.getUser(userId)
        require(currentUser != null || userId == 1) { "The account no longer exists" }
        val username = currentUser?.username ?: jwt.getClaim("username").asString() ?: "admin"
        val role = currentUser?.role ?: jwt.getClaim("role").asString() ?: "ADMIN"
        return createAccessToken(userId, username, role)
    }

    fun verifyJwt(jwt: String): UserType {
        try {
            val decodedJWT = verifier.verify(jwt)

            require(decodedJWT.getClaim("token_type").asString() == "access") {
                "Cannot use refresh token to access"
            }
            require(decodedJWT.audience.single() == AUDIENCE) {
                "Token intended for different audience ${decodedJWT.audience}"
            }

            val userId = decodedJWT.getClaim("user_id").asInt() ?: 1
            // the role claim may be stale (demoted while a token is still valid), so the database decides; a token
            // of a deleted account is worthless
            val currentUser = UserManager.getUser(userId)
            if (currentUser == null && userId != 1) {
                return UserType.Visitor
            }
            val role = currentUser?.role ?: decodedJWT.getClaim("role").asString() ?: "ADMIN"

            return if (role.equals("ADMIN", ignoreCase = true)) {
                UserType.Admin(userId)
            } else {
                UserType.Member(userId)
            }
        } catch (e: TokenExpiredException) {
            // Expected whenever a client uses an access token that expired since its last refresh. The client refreshes
            // the token and retries, so this is not worth a warning with a stack trace.
            logger.debug { "Received expired token: ${e.message}" }
            return UserType.Visitor
        } catch (e: JWTDecodeException) {
            // Not a token at all, for example the "Sec-WebSocket-Protocol" value that a WebSocket connection sends
            // ("graphql-transport-ws"): not worth a warning with a stack trace on every connection.
            logger.debug { "Received something that is not a token: ${e.message}" }
            return UserType.Visitor
        } catch (e: JWTVerificationException) {
            logger.warn(e) { "Received invalid token" }
            return UserType.Visitor
        }
    }

    private fun createAccessToken(
        userId: Int = 1,
        username: String = "admin",
        role: String = "ADMIN",
    ): String {
        val jwt =
            JWT
                .create()
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withClaim("token_type", "access")
                .withClaim("user_id", userId)
                .withClaim("username", username)
                .withClaim("role", role)
                .withExpiresAt(Instant.now().plusSeconds(accessTokenExpiry.inWholeSeconds))

        return jwt.sign(algorithm)
    }

    private fun createRefreshToken(
        userId: Int = 1,
        username: String = "admin",
        role: String = "ADMIN",
        sessionId: String,
    ): String =
        JWT
            .create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withClaim("token_type", "refresh")
            .withClaim("sid", sessionId)
            .withClaim("user_id", userId)
            .withClaim("username", username)
            .withClaim("role", role)
            .withExpiresAt(Instant.now().plusSeconds(refreshTokenExpiry.inWholeSeconds))
            .sign(algorithm)
}
