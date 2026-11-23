package org.ratelog.import.letterboxd

import java.time.Duration

/**
 * Fork: bounds for one Letterboxd import, sized for a heavy user's ratings.csv (about 60 bytes a row: 10,000 rows is
 * roughly 600 KB) while keeping the shared TMDB budget, threads and DB connections free for everyone else.
 * The upload limit is also `spring.servlet.multipart.max-file-size` in application.yml (and `data-max-bytes` in
 * templates/fragments/letterboxd-import-modal.html); change all three together.
 */
object LetterboxdImportLimits {
    /** Largest accepted upload. */
    const val MAX_FILE_BYTES: Long = 2L * 1024 * 1024

    /** Most rated rows per file; a bigger export can be split and imported in parts (duplicates are skipped). */
    const val MAX_ROWS = 10_000

    /** TMDB requests per second for all running imports together, out of the app-wide 40 (pages keep the rest). */
    const val TMDB_REQUESTS_PER_SECOND = 10

    /** Lookup threads per import. */
    const val WORKERS_PER_IMPORT = 4

    /** Imports running at once across all users (each user: one at a time). */
    const val MAX_CONCURRENT_IMPORTS = 2

    /** An import stops looking up new rows after this long; the rest are reported as not processed. */
    val TIME_BUDGET: Duration = Duration.ofMinutes(60)
}
