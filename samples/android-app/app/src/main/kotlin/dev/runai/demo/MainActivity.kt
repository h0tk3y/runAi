package dev.runai.demo

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Shows every kind of string the translation task handles: placeholders, plurals, markup and xliff. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val noteCount = 3
        val lines = listOf(
            getString(R.string.greeting, "Alex"),
            resources.getQuantityString(R.plurals.notes_count, noteCount, noteCount),
            getString(R.string.last_synced, 5),
            getString(R.string.storage_used, 42.5),
            getText(R.string.empty_state),
            getString(R.string.confirm_delete),
        )
        setContentView(
            TextView(this).apply {
                setPadding(48, 48, 48, 48)
                textSize = 18f
                text = lines.joinToString("\n\n")
            }
        )
    }
}
