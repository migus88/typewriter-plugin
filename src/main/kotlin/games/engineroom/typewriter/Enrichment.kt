package games.engineroom.typewriter

import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import kotlin.random.Random

/**
 * Persisted, per-language list of keywords the user has configured for "enrichment" — replacing
 * matching words in a script with `{{complete:N:Word}}` so the typewriter demos auto-completion.
 *
 * Language-builtin keywords carry `builtin = true` and can only be disabled (not removed); custom
 * keywords the user types in are removable.
 */
@Tag("enrichmentPreset")
class EnrichmentPreset {
    @get:Attribute("language")
    var language: String = ""

    @get:XCollection(style = XCollection.Style.v2)
    var keywords: MutableList<EnrichmentKeyword> = mutableListOf()
}

@Tag("enrichmentKeyword")
class EnrichmentKeyword {
    @get:Attribute("word")
    var word: String = ""

    @get:Attribute("enabled")
    var enabled: Boolean = true

    @get:Attribute("min")
    var min: Int = 1

    @get:Attribute("max")
    var max: Int = 3

    /** True for entries seeded from the built-in language preset; user-added keywords are false. */
    @get:Attribute("builtin")
    var builtin: Boolean = false
}

enum class EnrichmentMode(val descriptionKey: String, val probability: Double) {
    /** Wraps every enabled keyword. */
    ALL("enrich.mode.all.description", 1.0),

    /** Wraps with high probability — most occurrences of an enabled keyword end up wrapped. */
    HEAVY("enrich.mode.heavy.description", 0.6),

    /** Wraps with low probability — the typed-out script gets the occasional auto-completion beat. */
    LIGHT("enrich.mode.light.description", 0.2),
}

/**
 * Built-in keyword sets shipped with the plugin. Stored under the canonical language key returned
 * by [canonicalLanguageName]; any unrecognised file type falls through to an empty preset that the
 * user can grow themselves.
 */
object EnrichmentPresets {
    val builtins: Map<String, List<String>> = mapOf(
        "Kotlin" to listOf(
            "class", "interface", "object", "fun", "val", "var", "when", "return",
            "override", "suspend", "private", "public", "internal", "protected",
            "companion", "init", "this", "super", "null", "true", "false", "import",
            "package", "lateinit", "throw", "try", "catch", "finally", "while", "for",
            "break", "continue", "if", "else", "sealed", "data", "open", "abstract",
            "final", "operator", "infix", "inline", "reified", "typealias", "enum",
        ),
        "Java" to listOf(
            "class", "interface", "void", "public", "private", "protected", "static",
            "final", "return", "if", "else", "for", "while", "switch", "case", "break",
            "continue", "throw", "throws", "try", "catch", "finally", "this", "super",
            "null", "true", "false", "new", "import", "package", "abstract", "extends",
            "implements", "synchronized", "volatile", "transient", "instanceof", "enum",
            "default", "record", "sealed", "permits", "var",
        ),
        "C#" to listOf(
            "class", "struct", "interface", "void", "public", "private", "protected",
            "internal", "static", "readonly", "return", "if", "else", "for", "foreach",
            "while", "switch", "case", "break", "continue", "throw", "try", "catch",
            "finally", "this", "base", "null", "true", "false", "new", "using",
            "namespace", "abstract", "virtual", "override", "sealed", "partial", "async",
            "await", "var", "string", "int", "bool", "double", "float", "get", "set",
            "yield", "default", "params", "ref", "out", "record", "init",
        ),
        "Python" to listOf(
            "def", "class", "if", "elif", "else", "for", "while", "return", "import",
            "from", "try", "except", "finally", "raise", "with", "lambda", "yield",
            "pass", "break", "continue", "True", "False", "None", "self", "async",
            "await", "global", "nonlocal", "assert", "match", "case",
        ),
        "JavaScript" to listOf(
            "function", "class", "const", "let", "var", "if", "else", "for", "while",
            "return", "import", "export", "from", "async", "await", "try", "catch",
            "finally", "throw", "new", "this", "super", "null", "undefined", "true",
            "false", "typeof", "instanceof", "switch", "case", "break", "continue",
            "default", "yield", "static",
        ),
        "TypeScript" to listOf(
            "function", "class", "const", "let", "var", "if", "else", "for", "while",
            "return", "import", "export", "from", "async", "await", "try", "catch",
            "finally", "throw", "new", "this", "super", "null", "undefined", "true",
            "false", "typeof", "instanceof", "switch", "case", "break", "continue",
            "default", "yield", "static", "interface", "type", "enum", "public",
            "private", "protected", "readonly", "namespace", "declare", "abstract",
            "implements", "extends", "keyof", "as",
        ),
        "C++" to listOf(
            "class", "struct", "namespace", "public", "private", "protected", "virtual",
            "override", "void", "const", "return", "if", "else", "for", "while",
            "switch", "case", "break", "continue", "throw", "try", "catch", "this",
            "nullptr", "true", "false", "new", "delete", "typedef", "template",
            "typename", "auto", "static", "inline", "friend", "operator", "using",
            "enum", "constexpr", "noexcept", "explicit", "mutable", "decltype",
        ),
        "C" to listOf(
            "void", "int", "char", "short", "long", "float", "double", "signed",
            "unsigned", "struct", "union", "enum", "typedef", "const", "static",
            "extern", "volatile", "return", "if", "else", "for", "while", "switch",
            "case", "break", "continue", "goto", "sizeof", "NULL", "default",
        ),
        "PHP" to listOf(
            "class", "interface", "trait", "function", "public", "private", "protected",
            "static", "abstract", "final", "return", "if", "else", "elseif", "for",
            "foreach", "while", "switch", "case", "break", "continue", "throw", "try",
            "catch", "finally", "this", "null", "true", "false", "new", "namespace",
            "use", "extends", "implements", "echo", "print", "require", "include",
        ),
        "Ruby" to listOf(
            "class", "module", "def", "end", "if", "elsif", "else", "unless", "case",
            "when", "while", "until", "return", "yield", "begin", "rescue", "ensure",
            "raise", "true", "false", "nil", "self", "super", "attr_accessor",
            "attr_reader", "attr_writer", "require", "require_relative", "include",
            "extend", "private", "public", "protected",
        ),
        "Go" to listOf(
            "package", "import", "func", "type", "struct", "interface", "var", "const",
            "return", "if", "else", "for", "range", "switch", "case", "default",
            "break", "continue", "fallthrough", "goto", "defer", "go", "chan",
            "select", "true", "false", "nil", "map", "make", "new",
        ),
    )

    /**
     * Map an IDE [FileType.name] to the canonical preset key. Returns `null` when no matching
     * built-in preset exists — the caller can still create a custom preset under the file type's
     * own name.
     */
    fun canonicalLanguageName(fileTypeName: String): String? {
        val normalised = fileTypeName.trim()
        if (normalised.isEmpty()) return null
        for (key in builtins.keys) {
            if (key.equals(normalised, ignoreCase = true)) return key
        }
        // Common aliases for IDE-specific naming.
        return when (normalised.lowercase()) {
            "java", "java file" -> "Java"
            "c#", "csharp", "c# file" -> "C#"
            "javascript", "js" -> "JavaScript"
            "typescript", "ts" -> "TypeScript"
            "c++", "cpp", "cplusplus" -> "C++"
            "c", "c file" -> "C"
            "kotlin", "kt" -> "Kotlin"
            "python", "py" -> "Python"
            "php" -> "PHP"
            "ruby", "rb" -> "Ruby"
            "go", "golang" -> "Go"
            else -> null
        }
    }

    /**
     * Build a fresh keyword list for [language] from the built-in defaults. Returns an empty list
     * when there's no preset for the language.
     */
    fun seedKeywords(language: String): MutableList<EnrichmentKeyword> {
        val source = builtins[language] ?: return mutableListOf()
        return source.mapTo(mutableListOf()) { word ->
            EnrichmentKeyword().also {
                it.word = word
                it.enabled = true
                it.min = 1
                it.max = pickDefaultMax(word)
                it.builtin = true
            }
        }
    }

    private fun pickDefaultMax(word: String): Int = when {
        word.length <= 2 -> 1
        word.length <= 4 -> 2
        else -> 3
    }
}

/**
 * Resolve (or create + persist) the preset for [language] inside [settings]. New presets are
 * seeded with the language's built-in keyword list; subsequent calls reuse the stored copy so any
 * user edits survive.
 *
 * Built-in keywords absent from a stored preset (because they were added in a later plugin
 * version) get folded in on each call — keeping persisted presets compatible with shipped updates.
 */
fun resolveEnrichmentPreset(
    settings: TypeWriterSettings,
    fileTypeName: String,
): EnrichmentPreset {
    val canonical = EnrichmentPresets.canonicalLanguageName(fileTypeName) ?: fileTypeName
    val existing = settings.enrichmentPresets.firstOrNull { it.language.equals(canonical, ignoreCase = true) }
    if (existing != null) {
        // Add any newly-shipped built-ins that weren't in the saved preset; preserve user state
        // (enabled/min/max) for ones that were.
        val builtinSource = EnrichmentPresets.builtins[canonical].orEmpty()
        val have = existing.keywords.map { it.word }.toHashSet()
        for (word in builtinSource) {
            if (word !in have) {
                existing.keywords += EnrichmentKeyword().apply {
                    this.word = word
                    builtin = true
                }
            }
        }
        return existing
    }
    val fresh = EnrichmentPreset().apply {
        this.language = canonical
        keywords = EnrichmentPresets.seedKeywords(canonical)
    }
    settings.enrichmentPresets.add(fresh)
    return fresh
}

/**
 * Wrap occurrences of the enabled keywords in [text] as `{{complete:N:Word}}` template calls.
 *
 * Skipped:
 * - Words inside an existing template marker pair (we don't want to enrich already-enriched text).
 * - Words shorter than 2 chars or whose `min..max` window doesn't fit (`N` must be ≥ 1 and leave at
 *   least one character for the completion tail).
 *
 * Per occurrence:
 * - Roll once against [mode].probability — if the roll fails, the occurrence is left as-is.
 * - Roll N uniformly in `[max(1, min), min(max, word.length - 1)]`.
 *
 * Matching is whole-word: a keyword only matches when both its boundaries are non-identifier
 * characters (so `class` matches `class Foo` but not `subclass`).
 */
fun enrichText(
    text: String,
    keywords: List<EnrichmentKeyword>,
    mode: EnrichmentMode,
    openingSequence: String,
    closingSequence: String,
    random: Random = Random.Default,
): String {
    val active = keywords.filter { it.enabled && it.word.isNotBlank() && it.word.length >= 2 }
    if (active.isEmpty()) return text

    val markerRanges = templateMarkerRanges(text, openingSequence, closingSequence)
    val byWord = active.associateBy { it.word }
    val pattern = active.joinToString("|") { Regex.escape(it.word) }
    val regex = Regex("(?<![\\p{L}\\p{N}_])(?:$pattern)(?![\\p{L}\\p{N}_])")

    val out = StringBuilder()
    var cursor = 0
    for (match in regex.findAll(text)) {
        val start = match.range.first
        val end = match.range.last + 1
        if (markerRanges.any { start in it }) continue

        val word = match.value
        val cfg = byWord[word] ?: continue

        val lo = cfg.min.coerceAtLeast(1)
        val hi = cfg.max.coerceAtMost(word.length - 1)
        if (lo > hi) continue

        if (mode != EnrichmentMode.ALL && random.nextDouble() > mode.probability) continue

        val n = if (lo == hi) lo else random.nextInt(lo, hi + 1)

        out.append(text, cursor, start)
        out.append(openingSequence).append("complete:").append(n).append(':').append(word).append(closingSequence)
        cursor = end
    }
    out.append(text, cursor, text.length)
    return out.toString()
}

/**
 * Strip every macro from [text]. For `{{complete:N:Word}}` the bare `Word` is preserved in place;
 * every other macro (pause, reformat, import, caret, …) is removed entirely.
 */
fun unenrichText(
    text: String,
    openingSequence: String,
    closingSequence: String,
): String {
    val open = Regex.escape(openingSequence)
    val close = Regex.escape(closingSequence)
    val complete = Regex("$open\\s*complete:\\s*\\d+:((?:(?!$close).)*)$close")
    val any = Regex("$open(?:(?!$close).)*$close")
    return any.replace(complete.replace(text) { it.groupValues[1] }) { "" }
}

/**
 * Index ranges of every `<open>...<close>` span in the source so the enricher can avoid wrapping
 * keywords that are already inside a macro.
 */
private fun templateMarkerRanges(
    text: String,
    openingSequence: String,
    closingSequence: String,
): List<IntRange> {
    if (openingSequence.isEmpty() || closingSequence.isEmpty()) return emptyList()
    val regex = Regex("${Regex.escape(openingSequence)}(.*?)${Regex.escape(closingSequence)}")
    return regex.findAll(text).map { it.range }.toList()
}
