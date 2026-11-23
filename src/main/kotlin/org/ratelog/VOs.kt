package org.ratelog

import arrow.core.Either
import arrow.core.raise.either
import java.util.Locale

data class TmdbId(val value: Int) {
    init {
        require(value > 0) { "TmdbId must be greater than 0" }
    }
}

data class Title(val value: String)

data class Overview(val value: String)

data class Url(val value: String)

data class WatchRegion(val value: String) {
    init {
        require(value.matches(Regex("^[A-Z]{2}$"))) { "WatchRegion must be a 2-letter ISO-3166-1 code" }
    }

    companion object {
        val DEFAULT = WatchRegion("ES")

        fun fromLang(lang: Lang): WatchRegion = when (lang) {
            Lang.en -> WatchRegion("US")
            Lang.de -> WatchRegion("DE")
            Lang.es -> WatchRegion("ES")
            Lang.fr -> WatchRegion("FR")
            Lang.it -> WatchRegion("IT")
            Lang.ja -> WatchRegion("JP")
            Lang.pt -> WatchRegion("BR")
            Lang.ru -> WatchRegion("RU")
            Lang.zh -> WatchRegion("CN")
        }
    }
}

data class ProviderId(val value: Int) {
    init {
        require(value > 0) { "ProviderId must be greater than 0" }
    }
}

data class ProviderName(val value: String)

data class ProviderLogoPath(val value: String)

data class WatchProvider(
    val id: ProviderId,
    val name: ProviderName,
    val logoPath: ProviderLogoPath,
)

data class WatchProviders(
    val region: WatchRegion,
    val flatrate: List<WatchProvider>,
    val link: Url?,
)

enum class MediaType {
    movie, tvshow
}

enum class Genre(val tmdbId: Int) {
    ACTION(28),
    ADVENTURE(12),
    ANIMATION(16),
    COMEDY(35),
    CRIME(80),
    DOCUMENTARY(99),
    DRAMA(18),
    FAMILY(10751),
    FANTASY(14),
    HISTORY(36),
    HORROR(27),
    MUSIC(10402),
    MYSTERY(9648),
    ROMANCE(10749),
    SCIENCE_FICTION(878),
    TV_MOVIE(10770),
    THRILLER(53),
    WAR(10752),
    WESTERN(37),
    ACTION_ADVENTURE(10759),
    KIDS(10762),
    NEWS(10763),
    REALITY(10764),
    SCI_FI_FANTASY(10765),
    SOAP(10766),
    TALK(10767),
    WAR_POLITICS(10768);

    companion object {
        fun fromTmdbId(tmdbId: Int): Genre? = entries.find { it.tmdbId == tmdbId }
    }
}

enum class Status(val value: String) {
    RUMORED("Rumored"),
    PLANNED("Planned"),
    IN_PRODUCTION("In Production"),
    POST_PRODUCTION("Post Production"),
    RELEASED("Released"),
    CANCELED("Canceled"),
    RETURNING_SERIES("Returning Series"),
    ENDED("Ended"),
    PILOT("Pilot");

    companion object {
        fun fromValue(value: String): Status? = entries.find { it.value == value }
    }
}

data class Rank(val value: Int) {
    init {
        require(value > 0) { "Rank must be greater than 0" }
    }
}

data class SeasonNumber(val value: Int) {
    init {
        require(value > 0) { "SeasonNumber must be greater than 0" }
    }
}

data class Username(val value: String) {
    companion object {
        fun parse(value: String): Either<ParseError, Username> = either {
            if (!value.matches(Regex("^[a-zA-Z0-9_-]{3,50}$"))) {
                raise(ParseError.InvalidUsername)
            }
            Username(value)
        }
    }
}

data class Email(val value: String) {
    companion object {
        fun parse(value: String): Either<ParseError, Email> = either {
            if (!value.matches(Regex("^[\\w.-]+@[\\w.-]+\\.\\w{2,}$"))) {
                raise(ParseError.InvalidEmail)
            }
            Email(value)
        }
    }
}

data class Password(val value: String) {
    companion object {
        /** Fork: rules for new and changed passwords only. Signing in (and the current-password check) never parses. */
        const val MIN_LENGTH = 8
        /** BCrypt ignores (and Spring Security rejects) anything past 72 bytes. */
        const val MAX_BYTES = 72

        fun parse(value: String): Either<ParseError, Password> = either {
            if (value.isBlank()) {
                raise(ParseError.InvalidPassword)
            }
            if (value.length < MIN_LENGTH) {
                raise(ParseError.PasswordTooShort)
            }
            if (value.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                raise(ParseError.PasswordTooLong)
            }
            Password(value)
        }
    }
}

data class Score(val value: Double) {
    init {
        require(value in 0.0..10.0) { "Score must be between 0.0 and 10.0" }
    }
}

enum class Lang {
    en, de, es, fr, it, ja, pt, ru, zh;

    val locale: Locale = Locale.of(name)

    companion object {
        fun parse(value: String): Lang = entries.find { it.name == value } ?: en
    }
}

enum class Role {
    /** Fork: MANAGER is a USER who can also add (plain USER) accounts; nothing else admin-like. Requested 2026-11-23. */
    USER, MANAGER, ADMIN, SUPERADMIN;

    val authority: String get() = "ROLE_$name"

    val isAdminLike: Boolean get() = this == ADMIN || this == SUPERADMIN

    /** Fork: may add accounts (a manager only plain USER accounts, see CreateUserHandler). */
    val canCreateAccounts: Boolean get() = this == MANAGER || isAdminLike

    companion object {
        fun parse(value: String): Either<ParseError, Role> = either {
            entries.find { it.name.equals(value, ignoreCase = true) }
                ?: raise(ParseError.InvalidRole)
        }
    }
}

data class ReviewTitle(val value: String) {
    companion object {
        private const val MAX_LENGTH = 100

        fun parse(value: String): Either<ParseError, ReviewTitle> = either {
            val trimmed = value.trim()
            if (trimmed.length > MAX_LENGTH) {
                raise(ParseError.InvalidReviewTitle)
            }
            ReviewTitle(trimmed)
        }
    }
}

data class ReviewText(val value: String) {
    companion object {
        private const val MAX_LENGTH = 5000

        fun parse(raw: String): Either<ParseError, ReviewText> = either {
            val sanitized = raw
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace(Regex("<[^>]*>"), "")
                .replace(Regex("&[^;]+;"), "")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
            if (sanitized.length > MAX_LENGTH) {
                raise(ParseError.InvalidReviewText)
            }
            ReviewText(sanitized)
        }
    }
}

data class Review(val title: ReviewTitle?, val text: ReviewText?) {
    init {
        require(title != null || text != null) { "Review must have at least title or text" }
    }

    companion object {
        fun parse(title: String?, text: String?): Either<ParseError, Review?> = either {
            val parsedTitle = title?.takeIf { it.isNotBlank() }?.let { ReviewTitle.parse(it).bind() }
            val parsedText = text?.takeIf { it.isNotBlank() }?.let { ReviewText.parse(it).bind() }
            if (parsedTitle == null && parsedText == null) null else Review(parsedTitle, parsedText)
        }
    }
}

data class ListName(val value: String) {
    companion object {
        fun parse(value: String): Either<ParseError, ListName> = either {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed.length > 100) {
                raise(ParseError.InvalidListName)
            }
            ListName(trimmed)
        }
    }
}

/** Fork: message key for a rejected new password (length rules get their own message). */
fun passwordErrorKey(error: ParseError, fallback: String): String = when (error) {
    ParseError.PasswordTooShort -> "security.password.too.short"
    ParseError.PasswordTooLong -> "security.password.too.long"
    else -> fallback
}

sealed interface ParseError {
    data object InvalidUsername : ParseError
    data object InvalidEmail : ParseError
    data object InvalidPassword : ParseError
    data object PasswordTooShort : ParseError
    data object PasswordTooLong : ParseError
    data object InvalidListName : ParseError
    data object InvalidRole : ParseError
    data object InvalidConfigKey : ParseError
    data object InvalidReviewTitle : ParseError
    data object InvalidReviewText : ParseError
}
