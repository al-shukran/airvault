package app.airvault.core.conformance

/** Maven groups of the HTTP clients that a signer may not depend on (spec 8.1). */
private val NETWORK_GROUPS = listOf("com.squareup.okhttp3", "io.ktor", "com.squareup.retrofit2")

/** Imports and fully qualified uses of those clients, and of the java.net HTTP classes. */
private val NETWORK_SOURCE = Regex("""\b(okhttp3|io\.ktor|retrofit2)\.|java\.net\.(http\.|HttpURLConnection\b|URL\b)""")

/** The coordinates (group:artifact) in [coordinates] that name a network library, in their input order (spec 8.1). */
fun networkDependencyViolations(coordinates: Collection<String>): List<String> =
    coordinates.filter { coordinate ->
        val group = coordinate.substringBefore(':')
        NETWORK_GROUPS.any { group == it || group.startsWith("$it.") }
    }

/** The "file:line" positions in [sources] (path to text) that use a network library or java.net HTTP (spec 8.1). */
fun networkSourceViolations(sources: Map<String, String>): List<String> =
    sources.flatMap { (file, text) ->
        text.lineSequence().withIndex()
            .filter { NETWORK_SOURCE.containsMatchIn(it.value) }
            .map { "$file:${it.index + 1}" }
            .toList()
    }
