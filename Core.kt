package br.nova.iptv

import android.content.Context
import android.content.SharedPreferences

enum class Kind { LIVE, MOVIE, SERIES }

data class Cat(val id: String, val name: String)

data class Item(
    val id: String,
    val name: String,
    val logo: String?,
    val cat: String,
    val url: String?,
    val kind: Kind,
    val seriesId: String? = null
)

data class Ep(val title: String, val url: String)

class Catalog(val cats: List<Cat>, val items: List<Item>) {
    val byCat: Map<String, List<Item>> by lazy { items.groupBy { it.cat } }
}

interface Source {
    suspend fun load(kind: Kind): Catalog
    suspend fun episodes(item: Item): List<Ep>
}

/** Estado em memória compartilhado entre as telas. */
object Store {
    var source: Source? = null
    val cache = HashMap<Kind, Catalog>()
    var playList: List<Item> = emptyList()
    var playIndex = 0
    var zap = false
    var autoNext = false

    fun reset() {
        source = null
        cache.clear()
        playList = emptyList()
    }
}

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("nova", Context.MODE_PRIVATE)

    fun save(c: Context, mode: String, server: String, user: String, pass: String) {
        sp(c).edit()
            .putString("mode", mode)
            .putString("server", server)
            .putString("user", user)
            .putString("pass", pass)
            .apply()
    }

    fun clear(c: Context) {
        sp(c).edit().clear().apply()
    }

    fun buildSource(c: Context): Source? {
        val s = sp(c)
        val server = s.getString("server", "").orEmpty()
        if (server.isBlank()) return null
        val mode = s.getString("mode", "xtream")
        return if (mode == "m3u") M3uSource(server)
        else XtreamSource(server, s.getString("user", "").orEmpty(), s.getString("pass", "").orEmpty())
    }
}
