package eu.kanade.tachiyomi.animeextension.all.jellyfin

import aniext.core.parsing.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl

internal object Fixtures {
    val server = "http://192.168.1.10:8096".toHttpUrl()

    fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("fixtures/$name")) { "missing fixture $name" }.readText()

    inline fun <reified T> load(name: String): T = text(name).parseAs<T>(context = name, json = jellyfinJson)
}
