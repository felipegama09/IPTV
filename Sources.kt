package br.nova.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.EnumMap

object Net {
    const val UA = "VLC/3.0.20 LibVLC/3.0.20"

    /** Abre a conexão seguindo redirecionamentos (inclusive http -> https). */
    fun open(start: String): HttpURLConnection {
        var url = start
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: error("Redirecionamento inválido")
                url = URL(URL(url), loc).toString()
                c.disconnect()
                return@repeat
            }
            if (code >= 400) {
                c.disconnect()
                error("Servidor respondeu HTTP $code")
            }
            return c
        }
        error("Muitos redirecionamentos")
    }

    fun text(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

private fun JSONObject.str(key: String): String {
    val v = opt(key)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

class XtreamSource(server: String, private val user: String, private val pass: String) : Source {

    private val base: String = server.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
    }
    private val u = URLEncoder.encode(user, "UTF-8")
    private val p = URLEncoder.encode(pass, "UTF-8")

    private fun api(action: String, extra: String = "") =
        "$base/player_api.php?username=$u&password=$p&action=$action$extra"

    private fun array(url: String): JSONArray {
        val t = Net.text(url).trim()
        return when {
            t.isEmpty() -> JSONArray()
            t.startsWith("[") -> JSONArray(t)
            else -> error("O servidor não devolveu uma lista (confira servidor, usuário e senha)")
        }
    }

    override suspend fun load(kind: Kind): Catalog = withContext(Dispatchers.IO) {
        val (catAction, itemAction) = when (kind) {
            Kind.LIVE -> "get_live_categories" to "get_live_streams"
            Kind.MOVIE -> "get_vod_categories" to "get_vod_streams"
            Kind.SERIES -> "get_series_categories" to "get_series"
        }
        val cj = array(api(catAction))
        val cats = ArrayList<Cat>(cj.length())
        for (i in 0 until cj.length()) {
            val o = cj.getJSONObject(i)
            cats.add(Cat(o.str("category_id"), o.str("category_name")))
        }
        val ij = array(api(itemAction))
        val items = ArrayList<Item>(ij.length())
        for (i in 0 until ij.length()) {
            val o = ij.getJSONObject(i)
            val name = o.str("name").trim()
            val cat = o.str("category_id")
            when (kind) {
                Kind.LIVE -> {
                    val id = o.str("stream_id")
                    items.add(
                        Item(id, name, o.str("stream_icon").ifBlank { null }, cat,
                            "$base/live/$u/$p/$id.m3u8", kind)
                    )
                }
                Kind.MOVIE -> {
                    val id = o.str("stream_id")
                    val ext = o.str("container_extension").ifBlank { "mp4" }
                    items.add(
                        Item(id, name, o.str("stream_icon").ifBlank { null }, cat,
                            "$base/movie/$u/$p/$id.$ext", kind)
                    )
                }
                Kind.SERIES -> {
                    val id = o.str("series_id")
                    items.add(
                        Item(id, name, o.str("cover").ifBlank { null }, cat, null, kind, id)
                    )
                }
            }
        }
        Catalog(cats, items)
    }

    override suspend fun episodes(item: Item): List<Ep> = withContext(Dispatchers.IO) {
        val root = JSONObject(Net.text(api("get_series_info", "&series_id=${item.seriesId}")))
        val raw = root.opt("episodes")
        val seasons = ArrayList<Pair<String, JSONArray>>()
        when (raw) {
            is JSONObject -> raw.keys().forEach { k -> raw.optJSONArray(k)?.let { seasons.add(k to it) } }
            is JSONArray -> for (i in 0 until raw.length()) {
                raw.optJSONArray(i)?.let { seasons.add((i + 1).toString() to it) }
            }
        }
        class Row(val season: Int, val num: Int, val ep: Ep)
        val rows = ArrayList<Row>()
        for ((season, arr) in seasons) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.str("id")
                if (id.isBlank()) continue
                val ext = o.str("container_extension").ifBlank { "mp4" }
                val num = o.str("episode_num").toIntOrNull() ?: (i + 1)
                val title = o.str("title").trim()
                val label = "T$season E$num" + if (title.isNotBlank()) "  ·  $title" else ""
                rows.add(Row(season.toIntOrNull() ?: 0, num, Ep(label, "$base/series/$u/$p/$id.$ext")))
            }
        }
        rows.sortedWith(compareBy({ it.season }, { it.num })).map { it.ep }
    }
}

class M3uSource(private val url: String) : Source {

    private var parsed: Map<Kind, Catalog>? = null
    private val attr = Regex("([\\w-]+)=\"([^\"]*)\"")
    private val movieGroup = Regex("filme|movie|vod|cinema")
    private val seriesGroup = Regex("s[eé]rie|series|novela|temporada")

    override suspend fun load(kind: Kind): Catalog = withContext(Dispatchers.IO) {
        val m = parsed ?: parse().also { parsed = it }
        m.getValue(kind)
    }

    override suspend fun episodes(item: Item): List<Ep> = emptyList()

    private fun classify(link: String, group: String): Kind {
        val l = link.lowercase()
        val g = group.lowercase()
        return when {
            "/movie/" in l -> Kind.MOVIE
            "/series/" in l -> Kind.SERIES
            movieGroup.containsMatchIn(g) -> Kind.MOVIE
            seriesGroup.containsMatchIn(g) -> Kind.SERIES
            l.endsWith(".mp4") || l.endsWith(".mkv") || l.endsWith(".avi") -> Kind.MOVIE
            else -> Kind.LIVE
        }
    }

    private fun parse(): Map<Kind, Catalog> {
        val items = EnumMap<Kind, ArrayList<Item>>(Kind::class.java)
        val cats = EnumMap<Kind, LinkedHashSet<String>>(Kind::class.java)
        Kind.values().forEach { items[it] = ArrayList(); cats[it] = LinkedHashSet() }

        val c = Net.open(url)
        try {
            c.inputStream.bufferedReader().use { r ->
                var name = ""
                var logo: String? = null
                var group = "Sem categoria"
                var pending = false
                var n = 0
                r.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.startsWith("#EXTINF")) {
                        val map = HashMap<String, String>()
                        attr.findAll(line).forEach { map[it.groupValues[1].lowercase()] = it.groupValues[2] }
                        name = line.substringAfterLast(',').trim()
                        logo = map["tvg-logo"]?.ifBlank { null }
                        group = map["group-title"]?.ifBlank { null } ?: "Sem categoria"
                        pending = true
                    } else if (pending && line.isNotEmpty() && !line.startsWith("#")) {
                        val k = classify(line, group)
                        n++
                        items.getValue(k).add(
                            Item(n.toString(), name.ifBlank { "Canal $n" }, logo, group, line, k)
                        )
                        cats.getValue(k).add(group)
                        pending = false
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return Kind.values().associateWith { k ->
            Catalog(cats.getValue(k).map { Cat(it, it) }, items.getValue(k))
        }
    }
}
