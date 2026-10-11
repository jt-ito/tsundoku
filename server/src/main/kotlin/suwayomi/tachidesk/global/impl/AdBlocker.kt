package suwayomi.tachidesk.global.impl

import eu.kanade.tachiyomi.network.NetworkHelper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.time.Duration.Companion.days

/**
 * Blocks requests of the WebView to known ad and tracker domains, based on community maintained lists: a hosts file and
 * the filter lists of uBlock Origin and EasyList.
 *
 * Only whole domains are blocked, from the lists' plain domain rules ("||ads.example.com^"). Rules with url patterns,
 * conditions on the page or element hiding are skipped, which keeps this fast and results in (almost) no false
 * positives, but does not catch everything a full content blocker would.
 */
object AdBlocker {
    private val logger = KotlinLogging.logger {}
    private val networkHelper: NetworkHelper by injectLazy()
    private val applicationDirs by lazy { Injekt.get<ApplicationDirs>() }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class Source(
        val fileName: String,
        val url: String,
    )

    private val SOURCES =
        listOf(
            Source("hosts.txt", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"),
            Source("ubo-filters.txt", "https://ublockorigin.github.io/uAssets/filters/filters.txt"),
            Source("ubo-badware.txt", "https://ublockorigin.github.io/uAssets/filters/badware.txt"),
            Source("ubo-resource-abuse.txt", "https://ublockorigin.github.io/uAssets/filters/resource-abuse.txt"),
            Source("easylist.txt", "https://easylist.to/easylist/easylist.txt"),
        )

    private val REFRESH_INTERVAL = 7.days

    // Rule options that only narrow where a rule applies. A rule with any other option (e.g. "domain=", "~third-party",
    // "redirect=") is skipped, since it would block more than it should once the options are ignored.
    private val ACCEPTED_RULE_OPTIONS =
        setOf("third-party", "3p", "popup", "all", "document", "doc", "script", "subdocument", "frame", "important")
    private val DOMAIN_REGEX = Regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")

    // The WebView is mainly used to solve challenges and to log in, which must never be blocked.
    private val ALLOWED_DOMAINS =
        setOf(
            "cloudflare.com",
            "google.com",
            "gstatic.com",
            "recaptcha.net",
            "hcaptcha.com",
            "arkoselabs.com",
        )

    // Popup and popunder ad networks, blocked with all their subdomains. The hosts file lists single hosts only, so an
    // ad served from a subdomain it does not know of (e.g. "crmrc.livejasmin.com") would get through.
    private val ALWAYS_BLOCKED_DOMAINS =
        setOf(
            "livejasmin.com",
            "exoclick.com",
            "exosrv.com",
            "realsrv.com",
            "juicyads.com",
            "trafficjunky.com",
            "trafficjunky.net",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "adsterra.com",
            "clickadu.com",
            "hilltopads.net",
            "adcash.com",
            "tsyndicate.com",
        )

    // entries of a hosts file that are not domains to block
    private val IGNORED_HOSTS = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0")
    private val BLOCKING_ADDRESSES = setOf("0.0.0.0", "127.0.0.1")

    @Volatile
    private var blockedDomains: Set<String> = emptySet()

    // domains the lists explicitly exempt ("@@||example.com^")
    @Volatile
    private var exemptDomains: Set<String> = emptySet()

    @Volatile
    private var isStarted = false

    private val blocklistDir get() = Path(applicationDirs.cacheDir) / "adblock"

    /**
     * Loads the cached lists and refreshes the missing or outdated ones in the background, does nothing if it was
     * already called.
     */
    @Synchronized
    fun start() {
        if (isStarted) return
        isStarted = true

        scope.launch {
            val files = SOURCES.map { blocklistDir / it.fileName }
            try {
                reload(files)

                var downloaded = false
                SOURCES.forEachIndexed { index, source ->
                    val file = files[index]
                    val isOutdated =
                        !file.exists() ||
                            System.currentTimeMillis() - file.getLastModifiedTime().toMillis() > REFRESH_INTERVAL.inWholeMilliseconds
                    if (!isOutdated) return@forEachIndexed

                    try {
                        download(source.url, file)
                        downloaded = true
                    } catch (e: Exception) {
                        logger.warn(e) { "AdBlocker: failed to update ${source.fileName}" }
                    }
                }
                if (downloaded) reload(files)
            } catch (e: Exception) {
                logger.warn(e) { "AdBlocker: failed to load/update the blocklists" }
            }
        }
    }

    private fun reload(files: List<java.nio.file.Path>) {
        val blocked = HashSet<String>()
        val exempt = HashSet<String>()
        files.filter { it.exists() }.forEach { file -> parseList(Files.readAllLines(file), blocked, exempt) }
        blockedDomains = blocked
        exemptDomains = exempt
        logger.info { "AdBlocker: loaded ${blocked.size} domains (${exempt.size} exceptions)" }
    }

    private fun download(
        url: String,
        target: java.nio.file.Path,
    ) {
        Files.createDirectories(target.parent)

        val request = Request.Builder().url(url).build()
        networkHelper.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Unexpected response: ${response.code}" }

            // download to a temp file first, to keep the old blocklist in case of a failure
            val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
            response.body.byteStream().use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // understands hosts files ("0.0.0.0 ads.example.com") and the plain domain rules of filter lists ("||ads.example.com^")
    private fun parseList(
        lines: List<String>,
        blocked: MutableSet<String>,
        exempt: MutableSet<String>,
    ) {
        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                line.isEmpty() || line.startsWith('!') || line.startsWith('[') || line.startsWith('#') -> Unit

                line.startsWith("||") -> addFilterRule(line.removePrefix("||"), blocked)

                line.startsWith("@@||") -> addFilterRule(line.removePrefix("@@||"), exempt, allowOptions = false)

                else -> parseHostsLine(line)?.let { blocked.add(it) }
            }
        }
    }

    // "example.com^$third-party,popup" (the rule without the leading "||") => "example.com"
    private fun addFilterRule(
        rule: String,
        target: MutableSet<String>,
        allowOptions: Boolean = true,
    ) {
        val optionsStart = rule.indexOf('$')
        val pattern = if (optionsStart == -1) rule else rule.substring(0, optionsStart)
        if (optionsStart != -1) {
            if (!allowOptions) return
            val options = rule.substring(optionsStart + 1).split(',')
            if (options.any { it !in ACCEPTED_RULE_OPTIONS }) return
        }

        // only "domain" or "domain^": anything with a path, wildcard or port is a pattern for single urls
        val domain = pattern.removeSuffix("^").lowercase()
        if ('.' in domain && DOMAIN_REGEX.matches(domain)) target.add(domain)
    }

    // "0.0.0.0 ads.example.com # comment" => "ads.example.com"
    private fun parseHostsLine(line: String): String? {
        val parts = line.substringBefore('#').trim().split(Regex("\\s+"))
        if (parts.size < 2 || parts[0] !in BLOCKING_ADDRESSES) return null

        val host = parts[1].lowercase()
        return host.takeIf { '.' in it && it !in IGNORED_HOSTS }
    }

    /**
     * [isMainFrame]: the request is a navigation of the page itself (e.g. an ad script redirecting it). Those are only
     * blocked for the popup ad networks, never because of the big hosts list, since the user may browse to any site.
     */
    fun shouldBlock(
        url: String,
        isMainFrame: Boolean = false,
    ): Boolean {
        if (!serverConfig.webViewAdBlockEnabled.value) return false

        val host = getHost(url) ?: return false
        if (isInDomains(host, ALLOWED_DOMAINS)) return false

        // checked first: these need no blocklist, which is still downloading/loading right after the server started
        if (isInDomains(host, ALWAYS_BLOCKED_DOMAINS)) return true

        if (isMainFrame) return false

        return isInDomains(host, blockedDomains) && !isInDomains(host, exemptDomains)
    }

    private fun getHost(url: String): String? =
        try {
            URI(url).host?.lowercase()
        } catch (e: Exception) {
            null
        }

    // checks the host and all its parent domains: "a.b.example.com" => "a.b.example.com", "b.example.com", "example.com"
    private fun isInDomains(
        host: String,
        domains: Set<String>,
    ): Boolean {
        var current = host
        while (true) {
            if (current in domains) return true

            val dot = current.indexOf('.')
            if (dot == -1) return false
            current = current.substring(dot + 1)
            // a top level domain alone is never a match
            if ('.' !in current) return false
        }
    }
}
