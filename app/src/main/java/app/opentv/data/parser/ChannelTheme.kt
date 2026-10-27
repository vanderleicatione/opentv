/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import java.text.Normalizer

/**
 * Classifies a live-channel name into a theme, so the add-on list can be grouped into categories
 * and the bulk the user does not want (24h VOD loops, foreign feeds, adult, free-to-air broadcast
 * they already get over the air) can be dropped before resolving.
 *
 * The patterns are a faithful port of the user's own tested `atualizar.py` `TEMAS`/`tema()`, matched
 * against an accent-folded lowercase name. Order matters: the first theme whose pattern matches wins,
 * with Adultos, "24 Horas" and Internacional checked first (as in the original) so they never fall
 * through to Filmes/Esportes.
 */
object ChannelTheme {

    /** Themes left out of the add-on list by default (see [themeOf] callers). */
    val EXCLUDED = setOf("24 Horas", "Internacional", "Adultos", "Abertos")

    /** Display order for the themes that are kept, used as the category sort index. */
    val ORDER = listOf(
        "Notícias", "Esportes", "Filmes e Séries", "Documentários",
        "Infantil", "Música", "Religiosos", "Variedades",
    )

    // Ordered; first match wins. Internacional/Adultos/"24 Horas"/Variedades are handled in themeOf.
    private val TEMAS: List<Pair<String, Regex>> = listOf(
        "Abertos" to Regex(
            "rede globo|^globo |rbs tv|^record(?! news)|^sbt (tv|vtv)|^band\\b(?! ?sports)|redetv|rede tv|" +
                "^tv (brasil|cultura|gazeta|meio norte)|^rede (minas|brasil)|^tve |amazon sat|^ngt"),
        "Notícias" to Regex(
            "news|noticias|\\bcnn\\b|jovem pan|\\bjp\\b|\\bc5n\\b|^\\d+ \\| tn |france 24|bm&c|canal gov|" +
                "tv (camara|senado|justica)"),
        "Esportes" to Regex(
            "espn|sportv|sport|premier|combate|\\bnba\\b|\\bnfl\\b|nsports|caze|eleven|fuel tv|tigo|\\btyc\\b|" +
                "win sports|\\bufc\\b|dazn|motorvision|real madrid|futebol|fish tv|woohoo"),
        "Infantil" to Regex(
            "kids|cartoon|disney|nick|gloob|ra tim bum|tooncast|zoomoo|baby|anime|otaku|toon|" +
                "bob esponja|icarly|pokemon|barbie|avatar"),
        "Filmes e Séries" to Regex(
            "hbo|telecine|^tc |cine|megapix|mega pix|paramount|universal|warner|\\btnt\\b|space|axn|" +
                "sony|\\bamc\\b|\\bfx\\b|star channel|tcm|syfy|^fox |^usa\\b|runtime|hallo (movies|series|classic)|" +
                "urban (movies|series|retro)|film&arts|canal brasil|novelas|globo ?play|lifetime|series|filmes"),
        "Documentários" to Regex(
            "discovery|history|nat ?geo|animal planet|hgtv|\\btlc\\b|\\bid\\b|investiga|a&e|\\ba e\\b|love nature|" +
                "odisseia|travel|curta|arte 1|sesc|futura|educacao|escola|rural|agro|terra viva|canal do boi|" +
                "receitas|sabor|food|\\bgnt\\b|modo viagem|home ?and ?health|\\bh h\\b|docs?\\b|folha do campo"),
        "Música" to Regex(
            "music|\\bmtv\\b|vevo|\\bbis\\b|multishow|\\bfm\\b|radio|sertanejo|box brazil|mcm pop"),
        "Religiosos" to Regex(
            "gospel|igreja|cancao nova|aparecida|pai eterno|novo tempo|rede vida|seculo 21|genesis|" +
                "boa vontade|\\brit tv\\b|esne|enlace|caritas"),
    )

    private val INTERNACIONAL = Regex(
        "^(py|arg|col|uy|ur)\\b|\\barg\\b|argentina|paraguay|^\\d+ \\||porto canal|\\brtp\\b|\\btvi\\b|sporting")

    private val ADULTOS = Regex(
        "xxx|hetero|homossexual|lesbica|sexy|\\bteens|exxxtra|empregada|ensinando|entead[ao]|fodendo|" +
            "gozando|comendo|comeu|chantageando|acordando|karyanne|ketlin|lara ruiva|layz|mc mirella|maikelly|" +
            "rita cadillac|stephanie silveira|suelen lucca|tati zaq|thais safitt|venus media|transexual|yesgirlz|^tiny$")

    private val COMBINING = Regex("\\p{Mn}+")

    private fun fold(s: String): String =
        COMBINING.replace(Normalizer.normalize(s.trim(), Normalizer.Form.NFD), "").lowercase()

    /** The theme for a channel name; "Variedades" when nothing more specific matches. */
    fun themeOf(rawName: String): String {
        val n = fold(rawName)
        if (ADULTOS.containsMatchIn(n)) return "Adultos"         // before all, so it never reads as "Filmes"
        if (n.startsWith("24h")) return "24 Horas"
        if (INTERNACIONAL.containsMatchIn(n)) return "Internacional"
        return TEMAS.firstOrNull { it.second.containsMatchIn(n) }?.first ?: "Variedades"
    }
}
