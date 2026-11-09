//! Text folding and romanisation, shared by the builder and the query side.
//!
//! Follows osm-mapidx `mapidx/translit.py`: casefold, NFKD, drop combining marks,
//! fold the letters NFKD leaves whole (ł). Unlike osm-mapidx, apostrophes are
//! dropped before splitting, so "кав'ярня" is one word rather than two.

use unicode_normalization::UnicodeNormalization;
use unicode_normalization::char::is_combining_mark;

const APOSTROPHES: [char; 4] = ['\'', '’', 'ʼ', '`'];

/// Street-type words carry no discrimination: every address on "вулиця X" has
/// "вулиця", and the token saturates the postings budget, so an exact
/// street-and-number match loses to a nearer row that merely shares the type
/// word. The builder leaves them out of an address's indexed terms and the
/// query drops them, which keeps "вулиця Стрийська 45" and "Стрийська 45" the
/// same query.
pub const STREET_TYPE_WORDS: [&str; 18] = [
    "вулиця",
    "вул",
    "проспект",
    "пр",
    "площа",
    "пл",
    "провулок",
    "пров",
    "набережна",
    "наб",
    "шосе",
    "бульвар",
    "бр",
    "майдан",
    "узвіз",
    "тупик",
    "дорога",
    "алея",
];

pub fn is_street_type_word(word: &str) -> bool {
    STREET_TYPE_WORDS.contains(&word)
}

/// Folds case and accents so that й, ї, ё compare as и, і, е and Ł as l.
pub fn normalise(text: &str) -> String {
    let lowered: String = text
        .chars()
        .flat_map(char::to_lowercase)
        .flat_map(|c| match c {
            'ß' => "ss".chars().collect::<Vec<_>>(),
            'ς' => vec!['σ'],
            _ => vec![c],
        })
        .collect();
    lowered
        .nfkd()
        .filter(|c| !is_combining_mark(*c) && !APOSTROPHES.contains(c))
        .map(|c| if c == 'ł' { 'l' } else { c })
        .collect()
}

/// The folded words of `text`, split on anything that is not a letter or digit.
pub fn words(text: &str) -> Vec<String> {
    normalise(text)
        .split(|c: char| !c.is_alphanumeric())
        .filter(|w| !w.is_empty())
        .map(str::to_owned)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn folds_like_osm_mapidx() {
        assert_eq!(normalise("Київ"), "киів");
        assert_eq!(normalise("ЛЬВІВ"), "львів");
        assert_eq!(normalise("Хмельницький"), "хмельницькии");
        assert_eq!(normalise("Łódź"), "lodz");
        assert_eq!(normalise("Złoczów"), "zloczow");
    }

    #[test]
    fn drops_apostrophes_inside_words() {
        assert_eq!(words("Кав'ярня 5"), vec!["кавярня", "5"]);
    }

    #[test]
    fn street_type_words_are_recognised_after_folding() {
        assert!(is_street_type_word("вулиця"));
        assert!(is_street_type_word("площа"));
        assert!(!is_street_type_word("стрийська"));
    }
}
