/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import app.opentv.data.parser.ChannelTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Theme classification, ported from the user's tested atualizar.py `tema()` asserts. The order of
 * checks matters: Adultos / 24 Horas / Internacional win before the generic theme list, so e.g. a
 * foreign sports channel reads as Internacional, not Esportes.
 */
class ChannelThemeTest {

    @Test fun `24h prefix wins`() = assertThat(ChannelTheme.themeOf("24H-SOUTH PARK S15")).isEqualTo("24 Horas")

    @Test fun `adult wins over everything`() = assertThat(ChannelTheme.themeOf("XXX • Dorcel TV")).isEqualTo("Adultos")

    @Test fun `foreign prefix beats its genre`() = assertThat(ChannelTheme.themeOf("PY • ESPN")).isEqualTo("Internacional")

    @Test fun `sports`() = assertThat(ChannelTheme.themeOf("SporTV 1")).isEqualTo("Esportes")

    @Test fun `free-to-air is Abertos`() = assertThat(ChannelTheme.themeOf("Rede Globo São Paulo")).isEqualTo("Abertos")

    @Test fun `news`() = assertThat(ChannelTheme.themeOf("CNN Brasil")).isEqualTo("Notícias")

    @Test fun `documentary by keyword`() = assertThat(ChannelTheme.themeOf("Futura HD")).isEqualTo("Documentários")

    @Test fun `unmatched falls back to Variedades`() = assertThat(ChannelTheme.themeOf("Canal Qualquer")).isEqualTo("Variedades")

    @Test fun `excluded set is what we drop`() {
        assertThat(ChannelTheme.EXCLUDED).containsExactly("24 Horas", "Internacional", "Adultos", "Abertos")
    }
}
