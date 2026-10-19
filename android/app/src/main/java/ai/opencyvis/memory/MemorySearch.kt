package ai.opencyvis.memory

/** Keep English/app identifiers intact; adjacent Chinese characters form searchable terms. */
object MemorySearch {
    private val words = Regex("[\\p{IsHan}]+|[\\p{L}\\p{N}_]+")
    fun tokens(text: String): List<String> = words.findAll(text.lowercase()).flatMap { match ->
        val word = match.value
        if (word.first().code in 0x3400..0x9fff) {
            if (word.length == 1) sequenceOf(word) else word.windowed(2).asSequence()
        } else sequenceOf(word)
    }.distinct().toList()

    fun index(text: String): String = tokens(text).joinToString(" ")
    fun query(text: String): String = tokens(text).take(80).joinToString(" OR ") { "\"$it\"" }
    fun score(item: MemoryItem, query: String, appScope: String?): Double {
        val terms = tokens(query).toSet()
        val title = tokens(item.title + " " + item.summary).toSet()
        val body = tokens(item.bodyJson).toSet()
        val overlap = terms.count { it in title } * 3 + terms.count { it in body }
        val preference = if (item.origin in setOf("explicit_user", "user_edit")) 6 else 0
        val scope = if (appScope != null && item.appScope == appScope) 3 else 0
        val evidence = when (item.evidenceLevel) { "observed_repeatedly" -> 2; "observed_once" -> 1; else -> 0 }
        return (overlap + preference + scope + evidence).toDouble()
    }
}
