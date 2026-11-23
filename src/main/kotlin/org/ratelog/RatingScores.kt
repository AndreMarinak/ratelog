package org.ratelog

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure

/** The five detailed rating categories, named as in the UI, the DB columns and the `ratingCategory` filter. */
enum class RatingCategory {
    directing, cinematography, acting, soundtrack, screenplay;

    companion object {
        /** Blank or unknown values mean "rank by the overall score". */
        fun parse(value: String?): RatingCategory? = entries.find { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/** The five category scores of a detailed rating. Its overall score is their average. */
data class CategoryScores(
    val directing: Score,
    val cinematography: Score,
    val acting: Score,
    val soundtrack: Score,
    val screenplay: Score,
) {
    val average: Score
        get() = Score((directing.value + cinematography.value + acting.value + soundtrack.value + screenplay.value) / 5.0)

    operator fun get(category: RatingCategory): Score = when (category) {
        RatingCategory.directing -> directing
        RatingCategory.cinematography -> cinematography
        RatingCategory.acting -> acting
        RatingCategory.soundtrack -> soundtrack
        RatingCategory.screenplay -> screenplay
    }

    companion object {
        /** All five or nothing: null unless every category is present. */
        fun of(directing: Score?, cinematography: Score?, acting: Score?, soundtrack: Score?, screenplay: Score?): CategoryScores? =
            if (directing != null && cinematography != null && acting != null && soundtrack != null && screenplay != null) {
                CategoryScores(directing, cinematography, acting, soundtrack, screenplay)
            } else {
                null
            }
    }
}

/**
 * The scores a user submits for a movie or a season: a quick rating (one overall score)
 * or a detailed one (all five categories, overall score = their average).
 */
sealed interface RatingInput {
    val score: Score
    val categories: CategoryScores?

    data class Quick(override val score: Score) : RatingInput {
        override val categories: CategoryScores? get() = null
    }

    data class Detailed(override val categories: CategoryScores) : RatingInput {
        override val score: Score get() = categories.average
    }

    companion object {
        /**
         * Accepts `score` alone (quick) or all five categories without `score` (detailed), each within 0..10.
         * Anything else (some categories only, or a score plus categories) is an invalid combination.
         */
        fun parse(
            score: Double?,
            directing: Double?,
            cinematography: Double?,
            acting: Double?,
            soundtrack: Double?,
            screenplay: Double?,
        ): Either<RatingInputError, RatingInput> = either {
            val categoryValues = listOf(directing, cinematography, acting, soundtrack, screenplay)
            ensure((categoryValues + score).filterNotNull().all { it in 0.0..10.0 }) { RatingInputError.OutOfRange }

            val categories = CategoryScores.of(
                directing?.let(::Score),
                cinematography?.let(::Score),
                acting?.let(::Score),
                soundtrack?.let(::Score),
                screenplay?.let(::Score),
            )
            when {
                score != null && categoryValues.all { it == null } -> Quick(Score(score))
                score == null && categories != null -> Detailed(categories)
                else -> raise(RatingInputError.InvalidCombination)
            }
        }
    }
}

sealed interface RatingInputError {
    data object OutOfRange : RatingInputError
    data object InvalidCombination : RatingInputError
}
