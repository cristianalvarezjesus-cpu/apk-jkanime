package com.ejemplo.visor

import android.content.Context

/**
 * Decide qué dominios se bloquean o se permiten.
 *
 *  - assets/blocklist.txt         -> dominios de publicidad (se bloquean ellos y sus subdominios)
 *  - assets/allowlist_players.txt -> dominios de reproductores de video (nunca se bloquean)
 *  - SITE_DOMAINS                 -> el sitio principal (única navegación de página completa permitida)
 */
class AdBlocker(context: Context) {

    private val blocked: Set<String> = loadList(context, "blocklist.txt")
    private val players: Set<String> = loadList(context, "allowlist_players.txt")

    /** Si es true, los iframes de dominios que no estén en el sitio ni en la lista de reproductores se bloquean. */
    @Volatile
    var strictIframes: Boolean = false

    fun isSiteHost(host: String?): Boolean = matches(host, SITE_DOMAINS)

    fun isPlayerHost(host: String?): Boolean = matches(host, players)

    /** true si la petición a este host debe cancelarse. */
    fun isBlockedHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        if (isSiteHost(host) || isPlayerHost(host)) return false
        return matches(host, blocked)
    }

    /** Coincide con el dominio exacto o con cualquiera de sus dominios padre (a.b.c.com -> b.c.com -> c.com). */
    private fun matches(host: String?, set: Set<String>): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase()
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    companion object {
        /** Dominios del sitio. Si el sitio cambia de dirección, edítalo aquí. */
        val SITE_DOMAINS: Set<String> = setOf("jkanime.net")

        private fun loadList(context: Context, name: String): Set<String> =
            context.assets.open(name).bufferedReader().useLines { lines ->
                lines.map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toHashSet()
            }
    }
}
