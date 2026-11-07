package ru.hexteam.stenographer.asr

/**
 * GigaAM is a Russian model: it spells many English terms in Cyrillic
 * ("гитхаб", "пул-реквест"). This restores the conventional Latin spelling of
 * common tech/business terms, keeping surrounding punctuation. Russian case
 * endings after the stem ("в гитхабе") are dropped, as is usual in Latin spelling.
 */
object AnglicismNormalizer {

    /** Cyrillic stem variants → Latin spelling. Multi-word stems are matched first. */
    private val TERMS: List<Pair<List<String>, String>> = listOf(
        listOf("пул реквест", "пул-реквест", "пулл реквест", "пулреквест") to "pull request",
        listOf("мерж реквест", "мерж-реквест", "мердж реквест", "мердж-реквест") to "merge request",
        listOf("гитхаб", "гидхаб", "гит хаб") to "GitHub",
        listOf("гитлаб", "гит лаб") to "GitLab",
        listOf("пайтон", "питон") to "Python",
        listOf("джава скрипт", "джаваскрипт") to "JavaScript",
        listOf("тайпскрипт", "тайп скрипт") to "TypeScript",
        listOf("котлин") to "Kotlin",
        listOf("докер") to "Docker",
        listOf("кубернетес", "кубернетис") to "Kubernetes",
        listOf("графан") to "Grafana",
        listOf("джир") to "Jira",
        listOf("конфлюенс") to "Confluence",
        listOf("слак") to "Slack",
        listOf("зум") to "Zoom",
        listOf("телеграм") to "Telegram",
        listOf("ютуб", "ютьюб") to "YouTube",
        listOf("гугл") to "Google",
        listOf("эппл") to "Apple",
        listOf("андроид") to "Android",
        listOf("бэкенд", "бэкэнд", "бекенд") to "backend",
        listOf("фронтенд", "фронтэнд") to "frontend",
        listOf("деплой", "деплей") to "deploy",
        listOf("коммит") to "commit",
        listOf("апи") to "API",
        listOf("эскуэль", "эс кью эль") to "SQL",
        listOf("чат джипити", "чатгпт", "чат гпт") to "ChatGPT",
    )

    /** Endings that may follow a stem: Russian case/number forms. */
    private val ENDINGS = listOf(
        "", "а", "у", "е", "ом", "ы", "ов", "ам", "ами", "ах", "ой", "ей", "ю", "я",
    )

    private val patterns: List<Pair<Regex, String>> = TERMS.flatMap { (variants, latin) ->
        variants.sortedByDescending { it.length }.map { stem ->
            val body = stem.split(' ', '-').joinToString("[\\s-]?") { Regex.escape(it) }
            val endings = ENDINGS.sortedByDescending { it.length }.joinToString("|")
            Regex("(?<![\\p{L}])($body)(?:$endings)(?![\\p{L}])", RegexOption.IGNORE_CASE) to latin
        }
    }

    /** The model sometimes emits Latin already but in odd case ("Github"): restore canonical casing. */
    private val latinCasing: List<Pair<Regex, String>> = TERMS.map { it.second }.distinct()
        .filter { it.any(Char::isUpperCase) }
        .map { Regex("(?<![\\p{L}])${Regex.escape(it)}(?![\\p{L}])", RegexOption.IGNORE_CASE) to it }

    fun normalize(text: String): String =
        (patterns + latinCasing).fold(text) { acc, (regex, latin) -> regex.replace(acc) { latin } }
}
