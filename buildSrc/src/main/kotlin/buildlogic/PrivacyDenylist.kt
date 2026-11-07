package buildlogic

/**
 * A checked-in list of dependency coordinates the app must never ship.
 *
 * The list is data, not code: `ci/privacy-denylist.txt` holds one rule per line
 * and names its source and review date. Matching is exact, so the guard gives
 * the same answer for the same resolved dependency graph and never needs the
 * network.
 */
class PrivacyDenylist private constructor(
    private val rules: List<Rule>,
) {
    sealed interface Rule {
        val description: String

        fun matches(group: String, name: String): Boolean

        data class Group(
            val group: String,
        ) : Rule {
            override val description: String = "group $group"

            override fun matches(group: String, name: String): Boolean = this.group == group
        }

        data class Module(
            val group: String,
            val name: String,
        ) : Rule {
            override val description: String = "module $group:$name"

            override fun matches(group: String, name: String): Boolean =
                this.group == group && this.name == name
        }
    }

    data class Violation(
        val coordinate: String,
        val rule: String,
    )

    /** Returns one violation for every coordinate matched by a rule. */
    fun violations(coordinates: Collection<String>): List<Violation> =
        coordinates.mapNotNull { coordinate ->
            matchingRule(coordinate)?.let { Violation(coordinate, it.description) }
        }

    private fun matchingRule(coordinate: String): Rule? {
        val parts = coordinate.split(':')
        if (parts.size < 2) return null
        val group = parts[0]
        val name = parts[1]
        return rules.firstOrNull { it.matches(group, name) }
    }

    companion object {
        private const val GROUP_DIRECTIVE = "group"
        private const val MODULE_DIRECTIVE = "module"
        private val WHITESPACE = Regex("\\s+")

        /**
         * Parses the denylist. Comments start at `#`; blank lines are ignored.
         * A malformed line fails with the file name and line number, because a
         * silently skipped rule would make the guard weaker than it looks.
         */
        fun parse(text: String, source: String): PrivacyDenylist {
            val rules =
                text.lineSequence()
                    .mapIndexedNotNull { index, rawLine ->
                        val line = rawLine.substringBefore('#').trim()
                        if (line.isEmpty()) null else parseRule(line, source, index + 1)
                    }.toList()
            require(rules.isNotEmpty()) { "Privacy denylist has no rules: $source" }
            return PrivacyDenylist(rules)
        }

        private fun parseRule(line: String, source: String, lineNumber: Int): Rule {
            val tokens = line.split(WHITESPACE)
            fun malformed(reason: String): Nothing =
                throw IllegalArgumentException("$source:$lineNumber: $reason (got '$line')")

            if (tokens.size != 2) malformed("expected 2 fields")
            val value = tokens[1]
            return when (tokens[0]) {
                GROUP_DIRECTIVE -> {
                    if (value.isBlank() || value.contains(':')) {
                        malformed("a group rule must name one group id")
                    }
                    Rule.Group(value)
                }
                MODULE_DIRECTIVE -> {
                    val parts = value.split(':')
                    if (parts.size != 2 || parts.any { it.isBlank() }) {
                        malformed("a module rule must be group:artifact")
                    }
                    Rule.Module(parts[0], parts[1])
                }
                else -> malformed("unknown rule '${tokens[0]}'; expected $GROUP_DIRECTIVE or $MODULE_DIRECTIVE")
            }
        }
    }
}
