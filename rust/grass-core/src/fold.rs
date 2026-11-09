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

/// A house-number letter is at most this long; "2-А" splits into "2" and "а",
/// and the second token is one character.
const MAX_HOUSE_LETTER_CHARS: usize = 2;

/// A token that starts with a digit, which a house number does.
pub fn is_number_word(word: &str) -> bool {
    word.chars().next().is_some_and(|c| c.is_ascii_digit())
}

/// The canonical form of a digit-led token: letters and digits only, with the
/// Cyrillic letters that share a glyph with Latin ones folded. "2а" and "2a"
/// both become "2a".
fn canonical_number(word: &str) -> String {
    word.chars()
        .flat_map(char::to_lowercase)
        .filter_map(|c| match c {
            'а' => Some('a'),
            'е' => Some('e'),
            'і' => Some('i'),
            'о' => Some('o'),
            'р' => Some('p'),
            'с' => Some('c'),
            'у' => Some('y'),
            'х' => Some('x'),
            other if other.is_alphanumeric() => Some(other),
            _ => None,
        })
        .collect()
}

/// Tokens as the index stores them and as a query produces them. A digit-led
/// token absorbs the short letter token that follows it ("2", "а" from
/// "2-А") and folds to [canonical_number], so "2-А", "2а" and "2a" all become
/// the one term "2a". The builder and the query side both use this, which is
/// what makes a letter suffix match however it is typed.
pub fn canonical_words(text: &str) -> Vec<String> {
    let words = words(text);
    let mut out = Vec::with_capacity(words.len());
    let mut index = 0;
    while index < words.len() {
        let word = &words[index];
        if !is_number_word(word) {
            out.push(word.clone());
            index += 1;
            continue;
        }
        let mut number = canonical_number(word);
        index += 1;
        if let Some(next) = words.get(index) {
            if next.chars().count() <= MAX_HOUSE_LETTER_CHARS
                && next.chars().all(char::is_alphabetic)
            {
                number.push_str(&canonical_number(next));
                index += 1;
            }
        }
        out.push(number);
    }
    out
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

    #[test]
    fn every_spelling_of_a_letter_suffix_folds_to_one_token() {
        assert_eq!(canonical_words("Сербська 2-А"), vec!["сербська", "2a"]);
        assert_eq!(canonical_words("Сербська 2а"), vec!["сербська", "2a"]);
        assert_eq!(canonical_words("Сербська 2a"), vec!["сербська", "2a"]);
        assert_eq!(canonical_words("Сербська 2A"), vec!["сербська", "2a"]);
    }

    #[test]
    fn a_slash_stays_two_number_tokens_and_an_ordinal_joins_its_letters() {
        assert_eq!(
            canonical_words("Стрийська 12/2"),
            vec!["стрииська", "12", "2"]
        );
        assert_eq!(
            canonical_words("1-й Заозерний провулок"),
            vec!["1и", "заозернии", "провулок"]
        );
    }
}
