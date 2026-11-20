package dev.graidle

/**
 * Controls whether grAIdle tasks may call the model.
 *
 * Results are committed with a lock file. In every mode except [OFF], a task whose committed result matches its
 * inputs only checks that result. The mode decides what happens when the result is missing or out of date.
 * The mode is not a task input, so switching it does not make tasks run again.
 */
enum class AiMode {
    /** Call the model to generate a missing or out-of-date result, and update the lock file. The default outside CI. */
    LIVE,

    /** Never call the model. A missing or out-of-date result fails the task. The default on CI. */
    REPLAY,

    /** Skip grAIdle tasks and use the committed results without checking them. */
    OFF;

    companion object {
        fun parse(value: String): AiMode =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown grAIdle mode '$value'. Use one of: ${entries.joinToString { it.name.lowercase() }}."
                )
    }
}
