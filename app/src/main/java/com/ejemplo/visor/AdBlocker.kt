package com.ejemplo.visor

import android.content.Context
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Adblocker estilo Brave: parsea filtros EasyList/uBO (red + cosmético + scriptlets)
 * y hace un refresh en background desde las listas oficiales.
 */
class AdBlocker(private val context: Context) {

    var strictIframes: Boolean = false

    // -------- Red --------
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private val allowed = ConcurrentHashMap.newKeySet<String>()
    private val pathPatterns = ArrayList<Regex>(2048)

    // -------- Cosmético --------
    private val genericCosmetic = ArrayList<String>(8192)
    private val domainCosmetic = ConcurrentHashMap<String, MutableList<String>>()

    // -------- Scriptlets --------
    private val domainScriptlets = ConcurrentHashMap<String, MutableList<String>>()

    private val whitelistSites = setOf(
        "jkanime.net",
        "www.jkanime.net"
    )

    /**
     * Rutas del propio sitio que NUNCA deben pasar por filtros de ruta.
     */
    private val trustedPaths = listOf(
        "/buscar", "/search", "/ajax/", "/api/",
        "/banner.php", "/banner/buscar", "/includes/ajax",
        "/ver/", "/episodio/", "/capitulo/"
    )

    fun isTrustedPath(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return trustedPaths.any { url.contains(it, ignoreCase = true) }
    }

    private val whitelistPlayers = setOf(
        "mp4upload.com", "streamtape.com", "streamtape.to",
        "dood.to", "dood.so", "doodstream.com", "dood.ws",
        "fembed.com", "ok.ru", "yourupload.com",
        "mixdrop.co", "mixdrop.to", "mixdrop.club",
        "videobin.co", "filemoon.sx", "filemoon.to", "filemoon.in",
        "streamlare.com", "voe.sx", "vidhide.com",
        "vidsrc.to", "vidsrc.me", "vidsrc.net",
        "dailymotion.com", "dmcdn.net", "vimeo.com", "vimeocdn.com",
        "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com",
        "gstatic.com", "googleapis.com", "google.com", "gvt1.com", "gvt2.com",
        "akamaihd.net", "akamaized.net", "cloudflare.com", "cloudflarestream.com",
        "jwplayer.com", "jwpcdn.com", "jwplatform.com", "jwpsrv.com"
    )

    /** Carga la base offline y lanza el refresh en background. */
    fun load() {
        runCatching {
            context.assets.open("adblock/hosts.txt").bufferedReader().forEachLine(::parseHostLine)
        }
        runCatching {
            context.assets.open("adblock/filters.txt").bufferedReader().forEachLine(::parseFilterLine)
        }

        Thread {
            downloadAndParse(
                "https://easylist.to/easylist/easylist.txt",
                "https://easylist.to/easylist/easyprivacy.txt",
                "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
                "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
                "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/resource-abuse.txt",
                "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
                "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt"
            )
        }.apply { isDaemon = true }.start()
    }

    // ----------------------------------------------------------- Parsers

    private fun parseHostLine(line: String) {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) return
        val parts = l.split(Regex("\\s+"))
        val host = parts.last().lowercase().removePrefix("www.")
        if (host.contains('.') && host.matches(Regex("^[a-z0-9.\\-]+$")) &&
            host != "localhost" && !host.startsWith("127.") &&
            !host.startsWith("0.0.0.0") && !host.startsWith("::")
        ) {
            blocked.add(host)
        }
    }

    private fun parseFilterLine(raw: String) {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("!")) return
        if (line.length > 400) return
        if (line.contains("\$csp=") || line.contains("\$replace=")) return

        var isException = false
        if (line.startsWith("@@")) { isException = true; line = line.substring(2) }

        val idx = line.indexOf("##")
        if (idx >= 0 && !line.contains("#@#")) {
            val domainPart = line.substring(0, idx)
            val selector = line.substring(idx + 2).trim()
            if (selector.isEmpty()) return

            if (selector.startsWith("+js(")) {
                val domains = if (domainPart.isEmpty()) listOf("*")
                              else domainPart.split(",").map { it.trim() }
                domains.forEach { d ->
                    domainScriptlets.getOrPut(d) { mutableListOf() }.add(selector)
                }
            } else if (!isException) {
                if (selector.contains(":has-text(") ||
                    selector.contains(":matches-") ||
                    selector.contains(":upward(") ||
                    selector.contains(":remove(") ||
                    selector.contains(":style(")) return

                if (domainPart.isEmpty()) {
                    genericCosmetic.add(selector)
                } else {
                    domainPart.split(",").forEach { d ->
                        domainCosmetic.getOrPut(d.trim()) { mutableListOf() }.add(selector)
                    }
                }
            }
            return
        }

        val clean = line.substringBefore('$')

        val m = Regex("""^\|\|([a-z0-9.\-]+)\^?$""", RegexOption.IGNORE_CASE).find(clean)
        if (m != null) {
            val h = m.groupValues[1].lowercase().removePrefix("www.")
            if (h.contains('.')) {
                if (isException) allowed.add(h) else blocked.add(h)
            }
            return
        }

        if (clean.length in 3..200 && clean.startsWith("/") && clean.endsWith("/")) {
            runCatching {
                val r = Regex(clean.substring(1, clean.length - 1))
                if (!isException) synchronized(pathPatterns) { pathPatterns.add(r) }
            }
        }
    }

    private fun downloadAndParse(vararg urls: String) {
        for (u in urls) {
            runCatching {
                val text = URL(u).openStream().bufferedReader().use { it.readText() }
                text.lineSequence().forEach { line ->
                    if (line.isNotEmpty() && !line.startsWith("!")) parseFilterLine(line)
                }
            }
        }
    }

    // ----------------------------------------------------------- Consultas

    fun isSiteHost(host: String?): Boolean = matchDomain(host, whitelistSites)
    fun isPlayerHost(host: String?): Boolean = matchDomain(host, whitelistPlayers)

    fun isBlockedHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase().removePrefix("www.")
        if (matchDomain(h, whitelistSites)) return false
        if (matchDomain(h, whitelistPlayers)) return false
        if (isInSet(h, allowed)) return false
        return isInSet(h, blocked)
    }

    fun isBlockedUrl(url: String): Boolean {
        if (pathPatterns.isEmpty()) return false
        val snapshot = synchronized(pathPatterns) { pathPatterns.toList() }
        return snapshot.any { it.containsMatchIn(url) }
    }

    private fun isInSet(host: String, set: Set<String>): Boolean {
        var cur = host
        while (true) {
            if (set.contains(cur)) return true
            val dot = cur.indexOf('.')
            if (dot < 0) return false
            cur = cur.substring(dot + 1)
            if (!cur.contains('.')) return false
        }
    }

    private fun matchDomain(host: String?, set: Set<String>): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase().removePrefix("www.")
        return set.any { h == it || h.endsWith(".$it") }
    }

    // ----------------------------------------------------------- Cosmético / scriptlets

    fun cosmeticCssFor(url: String?): String {
        val host = hostOf(url) ?: return ""
        val sels = LinkedHashSet<String>()
        sels.addAll(genericCosmetic)
        var cur = host
        while (true) {
            domainCosmetic[cur]?.let { sels.addAll(it) }
            val dot = cur.indexOf('.')
            if (dot < 0) break
            cur = cur.substring(dot + 1)
            if (!cur.contains('.')) break
        }
        if (sels.isEmpty()) return ""
        val safe = sels.asSequence()
            .filter { it.isNotBlank() && !it.startsWith("+js(") }
            .joinToString(",")
        return if (safe.isEmpty()) "" else
            "$safe{display:none !important;visibility:hidden !important;}"
    }

    fun scriptletsFor(url: String?): List<String> {
        val host = hostOf(url) ?: return emptyList()
        val out = LinkedHashSet<String>()
        domainScriptlets["*"]?.let { out.addAll(it) }
        var cur = host
        while (true) {
            domainScriptlets[cur]?.let { out.addAll(it) }
            val dot = cur.indexOf('.')
            if (dot < 0) break
            cur = cur.substring(dot + 1)
            if (!cur.contains('.')) break
        }
        return out.toList()
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
    }
}
