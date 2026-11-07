package ru.hexteam.stenographer

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hexteam.stenographer.asr.AnglicismNormalizer

class AnglicismNormalizerTest {

    @Test
    fun `restores latin spelling of tech terms with case endings`() {
        assertEquals(
            "Посмотри мой pull request на GitHub, там фикс для backend.",
            AnglicismNormalizer.normalize("Посмотри мой пул-реквест на гидхабе, там фикс для бэкэнда."),
        )
    }

    @Test
    fun `handles capitalized words and keeps punctuation`() {
        assertEquals("Python и Docker!", AnglicismNormalizer.normalize("Питон и докер!"))
        assertEquals("Логи в Grafana.", AnglicismNormalizer.normalize("Логи в графане."))
    }

    @Test
    fun `fixes casing of latin terms emitted by the model`() {
        assertEquals("Посмотри на GitHub, потом deploy.", AnglicismNormalizer.normalize("Посмотри на Github, потом деплей."))
    }

    @Test
    fun `does not touch words that only contain a term inside`() {
        val text = "Зуммер, апельсин и зубы."
        assertEquals(text, AnglicismNormalizer.normalize(text))
    }

    @Test
    fun `plain russian text stays unchanged`() {
        val text = "Добрый день, коллеги. Начинаем заседание."
        assertEquals(text, AnglicismNormalizer.normalize(text))
    }
}
