package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import gg.jte.ContentType
import gg.jte.TemplateEngine
import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.after
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.config.RoutesConfig
import io.javalin.http.Context
import io.javalin.http.HandlerType
import io.javalin.http.HttpStatus
import io.javalin.http.NotFoundResponse
import io.javalin.http.RedirectResponse
import io.javalin.http.UnauthorizedResponse
import io.javalin.json.JavalinJackson3
import io.javalin.rendering.template.JavalinJte
import io.javalin.websocket.WsContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.jetty.server.ServerConnector
import suwayomi.tachidesk.global.GlobalAPI
import suwayomi.tachidesk.graphql.GraphQL
import suwayomi.tachidesk.graphql.types.AuthMode
import suwayomi.tachidesk.i18n.LocalizationHelper
import suwayomi.tachidesk.manga.MangaAPI
import suwayomi.tachidesk.opds.OpdsAPI
import suwayomi.tachidesk.server.database.DatabaseMigrationService
import suwayomi.tachidesk.server.generated.BuildConfig
import suwayomi.tachidesk.server.user.ForbiddenException
import suwayomi.tachidesk.server.user.SetupManager
import suwayomi.tachidesk.server.user.UnauthorizedException
import suwayomi.tachidesk.server.user.UserType
import suwayomi.tachidesk.server.user.getUserFromContext
import suwayomi.tachidesk.server.user.getUserFromWsContext
import suwayomi.tachidesk.server.util.Browser
import suwayomi.tachidesk.server.util.ServerSubpath
import suwayomi.tachidesk.server.util.WebInterfaceManager
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

object JavalinSetup {
    private val logger = KotlinLogging.logger {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun <T> future(block: suspend CoroutineScope.() -> T): CompletableFuture<T> = scope.future(block = block)

    fun javalinSetup() {
        val app =
            Javalin.start { config ->
                val templateEngine = TemplateEngine.createPrecompiled(ContentType.Html)
                config.fileRenderer(JavalinJte(templateEngine))

                config.jsonMapper(JavalinJackson3())

                WebInterfaceManager.setup(config)

                // config.registerPlugin(OpenApiPlugin(getOpenApiOptions()))

                var connectorAdded = false
                config.jetty.modifyServer { server ->
                    if (!connectorAdded) {
                        val connector =
                            ServerConnector(server).apply {
                                host = serverConfig.ip.value
                                port = serverConfig.port.value
                            }
                        server.addConnector(connector)

                        serverConfig.subscribeTo(
                            combine(
                                serverConfig.ip,
                                serverConfig.port,
                            ) { ip, port -> Pair(ip, port) },
                            { (newIp, newPort) ->
                                val oldIp = connector.host
                                val oldPort = connector.port

                                connector.host = newIp
                                connector.port = newPort
                                connector.stop()
                                connector.start()

                                logger.info { "Server ip and/or port changed from $oldIp:$oldPort to $newIp:$newPort " }
                            },
                        )
                        connectorAdded = true
                    }
                }

                config.bundledPlugins.enableCors { cors ->
                    cors.addRule {
                        it.allowCredentials = true
                        it.reflectClientOrigin = true
                    }
                }

                config.routes.defineCore()
                config.routes.apiBuilder {
                    path(ServerSubpath.maybeAddAsPrefix("api/")) {
                        path("v1/") {
                            GlobalAPI.defineEndpoints()
                            MangaAPI.defineEndpoints()
                        }

                        OpdsAPI.defineEndpoints()
                        GraphQL.defineEndpoints()

                        after { ctx ->
                            // If not matched, the request was for an invalid endpoint
                            // Return a 404 instead of redirecting to the UI for usability
                            if (ctx.endpoints().lastHttpEndpoint()?.path == "*") {
                                throw NotFoundResponse()
                            }
                        }
                    }
                }

                config.events.serverStarted {
                    val addresses =
                        try {
                            when (serverConfig.ip.value) {
                                "0.0.0.0" -> listOf("127.0.0.1") + getIPv4Addresses()
                                else -> listOf("127.0.0.1", serverConfig.ip.value)
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "Error getting IPv4 addresses" }
                            listOf("127.0.0.1")
                        }

                    logger.info {
                        val port = serverConfig.port.value
                        buildString {
                            appendLine("Server is available at:")

                            addresses
                                .distinct()
                                .forEach { address ->
                                    appendLine("  http://$address:$port")
                                    appendLine("  Database migration: http://$address:$port/database")
                                }
                        }.trimEnd()
                    }
                    if (serverConfig.initialOpenInBrowserEnabled.value) {
                        scope.launch {
                            withTimeoutOrNull(10.seconds) {
                                WebInterfaceManager.isSetupComplete.first { it }
                            }
                            Browser.openInBrowser()
                        }
                    }
                }
            }

        // when JVM is prompted to shutdown, stop javalin gracefully
        Runtime.getRuntime().addShutdownHook(
            thread(start = false) {
                app.stop()
            },
        )
    }

    fun RoutesConfig.defineCore() {
        val loginPath = ServerSubpath.maybeAddAsPrefix("/login.html")

        get(loginPath) { ctx ->
            val locale: Locale = LocalizationHelper.ctxToLocale(ctx)
            ctx.header("content-type", "text/html")
            val httpCacheSeconds = 1.days.inWholeSeconds
            ctx.header("cache-control", "max-age=$httpCacheSeconds")
            ctx.render(
                "Login.jte",
                mapOf(
                    "locale" to locale,
                    "error" to "",
                ),
            )
        }

        post(loginPath) { ctx ->
            val username = ctx.formParam("user")
            val password = ctx.formParam("pass")
            val user = username?.let { u -> password?.let { p -> suwayomi.tachidesk.server.user.UserManager.authenticate(u, p) } }
            val isConfigValid =
                !username.isNullOrEmpty() &&
                    !password.isNullOrEmpty() &&
                    username == serverConfig.authUsername.value &&
                    password == serverConfig.authPassword.value
            val isValid = user != null || isConfigValid

            if (isValid) {
                val redirect = ctx.queryParam("redirect") ?: ServerSubpath.maybeAddAsPrefix("/")
                val uri = URI(redirect)
                if (uri.host != null || uri.scheme != null) {
                    throw IllegalArgumentException("Given redirect is not relative, refusing")
                }
                val token = user?.let { suwayomi.tachidesk.global.impl.util.Jwt.generateAccessToken(it.id, it.username, it.role) }
                    ?: suwayomi.tachidesk.server.user.UserManager.getUser(1)?.let { suwayomi.tachidesk.global.impl.util.Jwt.generateAccessToken(it.id, it.username, it.role) }
                if (token != null) {
                    ctx.cookie(
                        io.javalin.http.Cookie(
                            name = "suwayomi-server-token",
                            value = token,
                            maxAge = 30 * 86400,
                            path = "/",
                            sameSite = io.javalin.http.SameSite.LAX,
                            secure = false,
                            isHttpOnly = false,
                        ),
                    )
                }
                ctx.header("Location", redirect)
                ctx.sessionAttribute("logged-in", username)
                throw RedirectResponse(HttpStatus.SEE_OTHER)
            }

            val locale: Locale = LocalizationHelper.ctxToLocale(ctx)
            ctx.header("content-type", "text/html")
            ctx.req().session.invalidate()
            ctx.render(
                "Login.jte",
                mapOf(
                    "locale" to locale,
                    "error" to "Invalid username or password",
                ),
            )
        }

        val setupPath = ServerSubpath.maybeAddAsPrefix("/setup")
        val rootPath = ServerSubpath.maybeAddAsPrefix("/")

        fun renderSetup(
            ctx: Context,
            error: String,
            username: String = "",
        ) {
            ctx.header("content-type", "text/html")
            // inline script/style only with this page's nonce, so injected markup could not run even if it got through
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }
            ctx.header(
                "Content-Security-Policy",
                "default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce' https://fonts.googleapis.com; " +
                    "font-src https://fonts.gstatic.com; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
            )
            ctx.header("X-Frame-Options", "DENY")
            ctx.header("X-Content-Type-Options", "nosniff")
            ctx.header("Referrer-Policy", "no-referrer")
            ctx.header("Cache-Control", "no-store")
            ctx.render(
                "Setup.jte",
                mapOf(
                    "nonce" to nonce,
                    "error" to error,
                    "username" to username,
                    "needsCode" to SetupManager.codeRequired(ctx),
                    "version" to BuildConfig.VERSION,
                ),
            )
        }

        get(setupPath) { ctx ->
            if (!SetupManager.isUnclaimed()) {
                ctx.header("Location", rootPath)
                throw RedirectResponse(HttpStatus.SEE_OTHER)
            }
            renderSetup(ctx, "")
        }

        post(setupPath) { ctx ->
            if (!SetupManager.isUnclaimed()) {
                ctx.header("Location", rootPath)
                throw RedirectResponse(HttpStatus.SEE_OTHER)
            }
            val password = ctx.formParam("pass").orEmpty()
            val error =
                when {
                    SetupManager.codeRequired(ctx) && !SetupManager.codeMatches(ctx.formParam("code")) -> {
                        "Wrong setup code"
                    }

                    password != ctx.formParam("pass2") -> {
                        "The passwords do not match"
                    }

                    else -> {
                        try {
                            SetupManager.claim(ctx.formParam("user").orEmpty(), password)
                            null
                        } catch (e: IllegalArgumentException) {
                            e.message ?: "Invalid username or password"
                        }
                    }
                }
            if (error != null) {
                renderSetup(ctx, error, ctx.formParam("user").orEmpty())
                return@post
            }
            ctx.header("Location", rootPath)
            throw RedirectResponse(HttpStatus.SEE_OTHER)
        }

        val databasePath = ServerSubpath.maybeAddAsPrefix("/database")
        val databaseHtmlPath = ServerSubpath.maybeAddAsPrefix("/database.html")

        val renderDatabaseSetup: (Context) -> Unit = { ctx ->
            ctx.header("content-type", "text/html")
            val stats = DatabaseMigrationService.getDatabaseStats()
            ctx.render(
                "DatabaseSetup.jte",
                mapOf(
                    "stats" to stats,
                    "defaultHost" to "localhost",
                    "defaultPort" to 5432,
                    "defaultDatabase" to "suwayomi",
                    "defaultUsername" to "postgres",
                    "version" to BuildConfig.VERSION,
                ),
            )
        }

        get(databasePath, renderDatabaseSetup)
        get(databaseHtmlPath, renderDatabaseSetup)

        beforeMatched { ctx ->
            val isWebManifest =
                listOf("site.webmanifest", "manifest.json", "login.html").any {
                    ctx.path().endsWith(it)
                }
            val isPageIcon =
                ctx.path().startsWith('/') &&
                    !ctx.path().substring(1).contains('/') &&
                    listOf(".png", ".jpg", ".ico").any { ctx.path().endsWith(it) }
            val isPreFlight = ctx.method() == HandlerType.OPTIONS
            val isApi = ctx.path().startsWith(ServerSubpath.maybeAddAsPrefix("/api/"))

            val requiresAuthentication = !isPreFlight && !isPageIcon && !isWebManifest
            if (!requiresAuthentication) {
                return@beforeMatched
            }

            val authMode = serverConfig.authMode.value

            if (authMode != AuthMode.NONE && !isApi && !ctx.path().startsWith(setupPath) &&
                !ctx.path().startsWith(databasePath) && SetupManager.isUnclaimed()
            ) {
                ctx.header("Location", setupPath)
                throw RedirectResponse(HttpStatus.SEE_OTHER)
            }

            val basicUser =
                ctx.basicAuthCredentials()?.let { (user, pass) ->
                    suwayomi.tachidesk.server.user.UserManager.authenticate(user, pass)
                }

            fun credentialsValid(): Boolean = basicUser != null

            fun cookieValid(): Boolean {
                val username = ctx.sessionAttribute<String>("logged-in") ?: return false
                return username == serverConfig.authUsername.value
            }

            if (authMode == AuthMode.SIMPLE_LOGIN && !cookieValid() && !isApi) {
                val url =
                    "$loginPath?redirect=" +
                        URLEncoder.encode(ctx.path() + (ctx.queryString()?.let { "?" + it } ?: ""), Charsets.UTF_8)
                ctx.header("Location", url)
                throw RedirectResponse(HttpStatus.SEE_OTHER)
            }

            if (authMode == AuthMode.BASIC_AUTH && !credentialsValid()) {
                ctx.header("WWW-Authenticate", "Basic")
                throw UnauthorizedResponse()
            }

            val userType =
                if (basicUser != null) {
                    if (basicUser.role.equals("ADMIN", ignoreCase = true)) {
                        suwayomi.tachidesk.server.user.UserType.Admin(basicUser.id)
                    } else {
                        suwayomi.tachidesk.server.user.UserType.Member(basicUser.id)
                    }
                } else {
                    getUserFromContext(ctx)
                }

            ctx.setAttribute(Attribute.TachideskUser, userType)
            ctx.setAttribute(Attribute.TachideskBasic, credentialsValid())
        }

        wsBefore {
            it.onConnect { ctx ->
                ctx.setAttribute(Attribute.TachideskUser, getUserFromWsContext(ctx))
            }
        }

        exception(NullPointerException::class.java) { e, ctx ->
            logger.error(e) { "NullPointerException while handling the request" }
            ctx.status(404)
        }
        exception(NoSuchElementException::class.java) { e, ctx ->
            logger.error(e) { "NoSuchElementException while handling the request" }
            ctx.status(404)
        }
        exception(IOException::class.java) { e, ctx ->
            logger.error(e) { "IOException while handling the request" }
            ctx.status(500)
            ctx.result(e.message ?: "Internal Server Error")
        }

        exception(IllegalArgumentException::class.java) { e, ctx ->
            logger.error(e) { "IllegalArgumentException while handling the request" }
            ctx.status(400)
            ctx.result(e.message ?: "Bad Request")
        }

        exception(UnauthorizedException::class.java) { e, ctx ->
            logger.error(e) { "UnauthorizedException while handling the request" }
            ctx.status(HttpStatus.UNAUTHORIZED)
            ctx.result(e.message ?: "Unauthorized")
        }

        exception(ForbiddenException::class.java) { e, ctx ->
            logger.error(e) { "ForbiddenException while handling the request" }
            ctx.status(HttpStatus.FORBIDDEN)
            ctx.result(e.message ?: "Forbidden")
        }
    }

    // private fun getOpenApiOptions(): OpenApiOptions {
    //     val applicationInfo =
    //         Info().apply {
    //             version("1.0")
    //             description("Suwayomi-Server Api")
    //         }
    //     return OpenApiOptions(applicationInfo).apply {
    //         path("/api/openapi.json")
    //         swagger(
    //             SwaggerOptions("/api/swagger-ui").apply {
    //                 title("Suwayomi-Server Swagger Documentation")
    //             },
    //         )
    //     }
    // }

    sealed class Attribute<T : Any>(
        val name: String,
    ) {
        data object TachideskUser : Attribute<UserType>("user")

        data object TachideskBasic : Attribute<Boolean>("basicAuthValid")
    }

    private fun <T : Any> Context.setAttribute(
        attribute: Attribute<T>,
        value: T,
    ) {
        attribute(attribute.name, value)
    }

    private fun <T : Any> WsContext.setAttribute(
        attribute: Attribute<T>,
        value: T,
    ) {
        attribute(attribute.name, value)
    }

    fun <T : Any> Context.getAttribute(attribute: Attribute<T>): T = attribute(attribute.name)!!

    fun <T : Any> WsContext.getAttribute(attribute: Attribute<T>): T = attribute(attribute.name)!!

    fun <T : Any> WsContext.getAttributeOrSet(
        attribute: Attribute<T>,
        replaceIf: (T) -> Boolean = { false },
        set: () -> T,
    ): T {
        var item: T? = attribute(attribute.name)

        if (item != null && replaceIf(item)) {
            item = null
        }

        return item ?: set().also { setAttribute(attribute, it) }
    }

    fun getIPv4Addresses(): List<String> =
        NetworkInterface
            .getNetworkInterfaces()
            .asSequence()
            .flatMap { networkInterface ->
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    return@flatMap emptySequence()
                }

                networkInterface.inetAddresses
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .map { address -> address.hostAddress }
            }.toList()
}
