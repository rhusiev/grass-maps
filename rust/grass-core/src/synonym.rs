//! Per-language search synonyms and street abbreviations, read from
//! `data/synonyms.tsv`.
//!
//! The table is data rather than code so an entry is one line, reviewable
//! without a rebuild of the reader's logic. Its shape, the meaning of each
//! column and the measured index cost are documented in
//! `tools/pipeline/README.md`, "Search synonyms and brands".
//!
//! Two kinds of row exist:
//!
//! - `kind`: a typed word and the OpenStreetMap category value it means. It is
//!   applied on the query side only: [`kind_expansions`] returns the value
//!   words, and [`crate::search`] resolves them as the same query word. The
//!   index keeps no synonym bytes, and the value word is already indexed from
//!   the kind string.
//! - `abbreviation`: a short street word and its full word. It is applied on
//!   both sides: [`expand_abbreviations`] rewrites the typed query and the
//!   builder rewrites a place's own name, so "пр-т Червоної Калини" in the
//!   data and "проспект Червоної Калини" typed by a person are the same
//!   search.
//!
//! Every language in the table is active for every query. A place name is not
//! tied to the interface language, and a person may type Ukrainian, Russian or
//! English regardless of the labels around the search box; the language column
//! records provenance and is the key a future language filter would use.

use std::sync::OnceLock;

use crate::error::CoreError;
use crate::fold;

/// The shipped table. It is compiled into the reader.
const TABLE_TEXT: &str = include_str!("../data/synonyms.tsv");

const SECTION_KIND: &str = "kind";
const SECTION_ABBREVIATION: &str = "abbreviation";
const COLUMNS: usize = 4;
const COMMENT_PREFIX: char = '#';
const KEY_VALUE_SEPARATOR: char = '\t';

/// A kind synonym is consulted only for a typed word at least this long, so a
/// first keystroke ("а") does not fan out into every category. Chosen, not
/// derived.
const MIN_KIND_PREFIX_CHARS: usize = 3;

/// One row of the table, after folding.
struct Entry {
    /// Provenance and a future language filter; every row is active today.
    #[allow(dead_code)]
    language: String,
    term: String,
    expansion: String,
}

/// The parsed table, split by section. Abbreviations are sorted longest first
/// so the longest match wins at a position.
#[derive(Default)]
struct Table {
    kinds: Vec<Entry>,
    abbreviations: Vec<Entry>,
}

impl Table {
    fn parse(text: &str) -> Result<Self, CoreError> {
        let mut table = Table::default();
        for line in text.lines() {
            let line = line.trim_end_matches('\r');
            if line.trim().is_empty() || line.starts_with(COMMENT_PREFIX) {
                continue;
            }
            let columns: Vec<&str> = line.split(KEY_VALUE_SEPARATOR).collect();
            if columns.len() != COLUMNS {
                return Err(CoreError::InvalidInput);
            }
            let section = columns[0].trim();
            let language = columns[1].trim();
            let term = fold::normalise(columns[2].trim());
            let expansion = fold::normalise(columns[3].trim());
            if language.is_empty() || term.is_empty() || expansion.is_empty() {
                return Err(CoreError::InvalidInput);
            }
            // A row that expands to itself ("pharmacy" -> the kind word
            // "pharmacy") is true but adds nothing; drop it rather than fail.
            if term == expansion {
                continue;
            }
            let entry = Entry {
                language: language.to_owned(),
                term,
                expansion,
            };
            match section {
                SECTION_KIND => table.kinds.push(entry),
                SECTION_ABBREVIATION => table.abbreviations.push(entry),
                _ => return Err(CoreError::InvalidInput),
            }
        }
        table
            .abbreviations
            .sort_by(|a, b| b.term.chars().count().cmp(&a.term.chars().count()));
        Ok(table)
    }

    fn empty() -> Self {
        Table::default()
    }

    fn expand(&self, text: &str) -> String {
        let normalised = fold::normalise(text);
        if self.abbreviations.is_empty() {
            return normalised;
        }
        let characters: Vec<char> = normalised.chars().collect();
        let mut out = String::with_capacity(normalised.len());
        let mut index = 0;
        while index < characters.len() {
            let at_word_start = index == 0 || !characters[index - 1].is_alphanumeric();
            let matched = at_word_start
                .then(|| self.match_abbreviation(&characters, index))
                .flatten();
            match matched {
                Some((end, expansion)) => {
                    out.push_str(expansion);
                    index = end;
                    if characters.get(index) == Some(&'.') {
                        index += 1;
                    }
                    // Keep exactly one separator: the source space, if there is
                    // one, is copied by the next iteration.
                    if characters
                        .get(index)
                        .is_some_and(|next| !next.is_whitespace())
                    {
                        out.push(' ');
                    }
                }
                None => {
                    out.push(characters[index]);
                    index += 1;
                }
            }
        }
        out.trim_end().to_owned()
    }

    fn match_abbreviation(&self, characters: &[char], start: usize) -> Option<(usize, &str)> {
        for entry in &self.abbreviations {
            let end = start + entry.term.chars().count();
            let Some(candidate) = characters.get(start..end) else {
                continue;
            };
            let at_word_end = characters
                .get(end)
                .is_none_or(|next| !next.is_alphanumeric());
            if candidate.iter().copied().eq(entry.term.chars()) && at_word_end {
                return Some((end, entry.expansion.as_str()));
            }
        }
        None
    }

    fn kind_expansions(&self, word: &str) -> Vec<&str> {
        if word.chars().count() < MIN_KIND_PREFIX_CHARS {
            return Vec::new();
        }
        let mut out: Vec<&str> = Vec::new();
        for entry in &self.kinds {
            if entry.term.starts_with(word) && !out.contains(&entry.expansion.as_str()) {
                out.push(entry.expansion.as_str());
            }
        }
        out
    }
}

fn table() -> &'static Table {
    static TABLE: OnceLock<Table> = OnceLock::new();
    // A malformed table compiled into the reader must not take search down:
    // parse errors are caught by `synonym::tests`, and at run time the table
    // degrades to no synonyms.
    TABLE.get_or_init(|| Table::parse(TABLE_TEXT).unwrap_or_else(|_| Table::empty()))
}

/// The category value words `word` stands for, e.g. "pharmacy" for "аптека".
/// An empty list when `word` is shorter than [`MIN_KIND_PREFIX_CHARS`] or
/// matches no row.
pub fn kind_expansions(word: &str) -> Vec<&'static str> {
    table().kind_expansions(word)
}

/// `text` with every street abbreviation replaced by its full word. The query
/// side and the builder both call this, so a typed abbreviation and a name
/// that stores one reach the same folded terms.
pub fn expand_abbreviations(text: &str) -> String {
    table().expand(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_shipped_table_parses() {
        let table = Table::parse(TABLE_TEXT).expect("the shipped synonym table parses");
        assert!(!table.kinds.is_empty());
        assert!(!table.abbreviations.is_empty());
    }

    #[test]
    fn a_malformed_row_is_invalid_input() {
        assert_eq!(
            Table::parse("kind\tuk\tаптека\n").err(),
            Some(CoreError::InvalidInput)
        );
        assert_eq!(
            Table::parse("other\tuk\tаптека\tpharmacy\n").err(),
            Some(CoreError::InvalidInput)
        );
        assert_eq!(
            Table::parse("kind\tuk\tаптека\t\n").err(),
            Some(CoreError::InvalidInput)
        );
    }

    #[test]
    fn a_row_that_expands_to_itself_is_dropped() {
        let table = Table::parse("kind\ten\tpharmacy\tpharmacy\n").expect("parse");
        assert!(table.kinds.is_empty());
    }

    #[test]
    fn kinds_expand_by_full_word_and_prefix() {
        assert_eq!(kind_expansions("аптека"), vec!["pharmacy"]);
        assert_eq!(kind_expansions("апте"), vec!["pharmacy"]);
        assert!(kind_expansions("ап").is_empty());
        assert!(kind_expansions("вигадане").is_empty());
    }

    #[test]
    fn abbreviations_expand_at_word_boundaries_only() {
        assert_eq!(expand_abbreviations("вул. Городоцька"), "вулиця городоцька");
        assert_eq!(expand_abbreviations("пр-т Шевченка"), "проспект шевченка");
        assert_eq!(
            expand_abbreviations("проспект Шевченка"),
            "проспект шевченка"
        );
        // "вулиця" must not match the "вул" entry.
        assert_eq!(expand_abbreviations("вулиця"), "вулиця");
        // A term inside a longer word must not match either.
        assert_eq!(expand_abbreviations("перевіл"), "перевіл");
    }

    #[test]
    fn an_abbreviation_inside_a_sentence_expands_in_place() {
        assert_eq!(
            expand_abbreviations("Львів, вул. Стрийська 45"),
            "львів, вулиця стрииська 45"
        );
    }
}
