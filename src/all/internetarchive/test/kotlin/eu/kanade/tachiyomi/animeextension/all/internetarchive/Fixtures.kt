package eu.kanade.tachiyomi.animeextension.all.internetarchive

import aniext.core.parsing.parseAs

internal object Fixtures {
    fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("fixtures/$name")) { "missing fixture $name" }.readText()

    fun metadata(name: String): MetadataResponseDto = text(name).parseAs<MetadataResponseDto>(context = name)
}
