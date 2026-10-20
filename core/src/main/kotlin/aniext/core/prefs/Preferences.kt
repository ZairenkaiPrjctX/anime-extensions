package aniext.core.prefs

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The per-source preferences file the host app also uses for its own source settings
 * (`source_<id>`). Resolve it lazily: [Injekt] is only populated inside the app (and the CI
 * inspector), never during class construction in unit tests.
 */
fun AnimeSource.sourcePreferences(): SharedPreferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
