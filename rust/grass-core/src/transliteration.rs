//! Cyrillic-to-Latin spelling variants, read from `data/transliteration.tsv`.
//!
//! A person may type a Ukrainian name in Cyrillic or in Latin, and neither
//! script is tied to the interface language. The index stores the Cyrillic name
//! OSM has; this module produces the Latin spellings a reader would type, so the
//! builder can index them beside it. It runs at build time only. The query side
//! is unchanged: it folds and prefixes, and a Latin query meets the Latin term
//! the builder wrote.
//!
//! Two conventions are produced per name (see the table's header for why):
//! the official 2010 transliteration and a pragmatic one that follows how
//! people actually type. A name that already has a Latin spelling in OSM keeps
//! that spelling and gets no generated one: see
//! [`crate::builder`] for the condition.
//!
//! Transliteration is lossy in both directions. г and ґ are both g in the
//! pragmatic scheme, и and і are both i, and е and є both become e or ie by
//! position. That is deliberate: the variants are alternatives to the Cyrillic
//! name, not a replacement, so two names that romanise alike still hold their
//! own Cyrillic terms and the search returns both instead of merging them.

use std::sync::OnceLock;

use crate::error::CoreError;
use crate::fold;

/// The shipped table. It is compiled into the reader.
const TABLE_TEXT: &str = include_str!("../data/transliteration.tsv");

const COLUMNS: usize = 4;
const COMMENT_PREFIX: char = '#';
const COLUMN_SEPARATOR: char = '\t';
const POSITION_ANY: &str = "any";
const POSITION_START: &str = "start";

/// One romanisation rule. `source` is one or more folded Cyrillic letters.
struct Rule {
    scheme: String,
    at_word_start_only: bool,
    source: Vec<char>,
    target: String,
}

impl Rule {
    fn matches_at(&self, characters: &[char], index: usize) -> bool {
        let Some(candidate) = characters.get(index..index + self.source.len()) else {
            return false;
        };
        candidate == self.source.as_slice()
    }
}

/// The parsed table: every rule, longest source first so "зг" wins over "з".
#[derive(Default)]
struct Table {
    rules: Vec<Rule>,
}

impl Table {
    fn parse(text: &str) -> Result<Self, CoreError> {
        let mut rules = Vec::new();
        for line in text.lines() {
            let line = line.trim_end_matches('\r');
            if line.trim().is_empty() || line.starts_with(COMMENT_PREFIX) {
                continue;
            }
            let columns: Vec<&str> = line.split(COLUMN_SEPARATOR).collect();
            if columns.len() != COLUMNS {
                return Err(CoreError::InvalidInput);
            }
            let scheme = columns[0].trim();
            let position = columns[1].trim();
            // The source is matched against the raw Cyrillic letter, so it is
            // only lower-cased: the fold would strip the mark that makes й
            // distinct from и and ї from і.
            let source: Vec<char> = columns[2].trim().to_lowercase().chars().collect();
            let target = columns[3].trim();
            if scheme.is_empty() || source.is_empty() || target.contains(fold::is_cyrillic) {
                return Err(CoreError::InvalidInput);
            }
            let at_word_start_only = match position {
                POSITION_ANY => false,
                POSITION_START => true,
                _ => return Err(CoreError::InvalidInput),
            };
            rules.push(Rule {
                scheme: scheme.to_owned(),
                at_word_start_only,
                source,
                target: fold::normalise(target),
            });
        }
        rules.sort_by(|a, b| {
            b.source
                .len()
                .cmp(&a.source.len())
                .then(b.at_word_start_only.cmp(&a.at_word_start_only))
        });
        let table = Table { rules };
        table.validate()?;
        Ok(table)
    }

    /// No two rules of one scheme claim the same source at the same position,
    /// which would make the result depend on table order.
    fn validate(&self) -> Result<(), CoreError> {
        let mut seen: Vec<(&str, bool, &[char])> = Vec::new();
        for rule in &self.rules {
            let key = (
                rule.scheme.as_str(),
                rule.at_word_start_only,
                rule.source.as_slice(),
            );
            if seen.contains(&key) {
                return Err(CoreError::InvalidInput);
            }
            seen.push(key);
        }
        Ok(())
    }

    fn schemes(&self) -> Vec<&str> {
        let mut schemes: Vec<&str> = Vec::new();
        for rule in &self.rules {
            if !schemes.contains(&rule.scheme.as_str()) {
                schemes.push(rule.scheme.as_str());
            }
        }
        schemes
    }

    fn romanise(&self, scheme: &str, text: &str) -> String {
        // Apostrophes are not romanised, and they are not word boundaries: in
        // "з'єднання" the є takes the inside-a-word form even though an
        // apostrophe stands before it.
        let characters: Vec<char> = text
            .to_lowercase()
            .chars()
            .filter(|character| !fold::APOSTROPHES.contains(character))
            .collect();
        let mut out = String::with_capacity(characters.len());
        let mut index = 0;
        while index < characters.len() {
            let at_word_start = index == 0 || !characters[index - 1].is_alphanumeric();
            let matched = self.rules.iter().find(|rule| {
                rule.scheme == scheme
                    && (!rule.at_word_start_only || at_word_start)
                    && rule.matches_at(&characters, index)
            });
            match matched {
                Some(rule) => {
                    out.push_str(&rule.target);
                    index += rule.source.len();
                }
                None => {
                    out.push(characters[index]);
                    index += 1;
                }
            }
        }
        out
    }
}

fn table() -> &'static Table {
    static TABLE: OnceLock<Table> = OnceLock::new();
    // A malformed table compiled into the reader must not take search down:
    // parse errors are caught by `transliteration::tests`, and at run time the
    // table degrades to no variants.
    TABLE.get_or_init(|| Table::parse(TABLE_TEXT).unwrap_or_default())
}

/// The Latin spellings of `text`, one per scheme, in table order. A spelling
/// identical to the folded input, or repeated, is dropped, so a name that is
/// already Latin yields nothing.
pub fn variants(text: &str) -> Vec<String> {
    let folded = fold::normalise(text);
    let mut out: Vec<String> = Vec::new();
    for scheme in table().schemes() {
        let variant = table().romanise(scheme, text);
        let variant = variant.trim().to_owned();
        if !variant.is_empty()
            && variant.contains(char::is_alphabetic)
            && variant != folded
            && !out.contains(&variant)
        {
            out.push(variant);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_shipped_table_parses_and_has_both_schemes() {
        let table = Table::parse(TABLE_TEXT).expect("the shipped table parses");
        assert_eq!(table.schemes(), vec!["national", "pragmatic"]);
    }

    #[test]
    fn a_malformed_row_is_invalid_input() {
        for text in [
            "national\tany\tа\n",
            "national\tmiddle\tа\ta\n",
            "\tany\tа\ta\n",
            "national\tany\t\tа\n",
            "national\tany\tа\tа\n",
        ] {
            assert_eq!(
                Table::parse(text).err(),
                Some(CoreError::InvalidInput),
                "{text:?}",
            );
        }
    }

    #[test]
    fn a_duplicate_rule_is_invalid_input() {
        assert_eq!(
            Table::parse("national\tany\tа\ta\nnational\tany\tа\tb\n").err(),
            Some(CoreError::InvalidInput),
        );
    }

    #[test]
    fn the_official_scheme_follows_the_2010_rules() {
        let table = Table::parse(TABLE_TEXT).expect("parse");
        assert_eq!(table.romanise("national", "Золочів"), "zolochiv");
        assert_eq!(table.romanise("national", "Львів"), "lviv");
        assert_eq!(table.romanise("national", "Київ"), "kyiv");
        assert_eq!(table.romanise("national", "Євпаторія"), "yevpatoriia");
        assert_eq!(table.romanise("national", "Згода"), "zghoda");
        assert_eq!(table.romanise("national", "Хмельницький"), "khmelnytskyi");
    }

    #[test]
    fn the_pragmatic_scheme_follows_how_people_type() {
        let table = Table::parse(TABLE_TEXT).expect("parse");
        assert_eq!(table.romanise("pragmatic", "Золочів"), "zolochiv");
        assert_eq!(table.romanise("pragmatic", "Львів"), "lviv");
        assert_eq!(table.romanise("pragmatic", "Городоцька"), "gorodocka");
        assert_eq!(table.romanise("pragmatic", "Грибовичі"), "gribovichi");
    }

    #[test]
    fn the_two_schemes_agree_on_letters_they_share() {
        // ч, в and і romanise alike, so a name made of them yields one variant,
        // not two identical ones.
        assert_eq!(variants("Золочів"), vec!["zolochiv"]);
        assert_eq!(variants("Городоцька"), vec!["horodotska", "gorodocka"]);
    }

    #[test]
    fn a_cyrillic_name_yields_its_latin_spellings() {
        assert!(variants("Zolochiv").is_empty());
        assert!(variants("вулиця Стрийська").len() >= 2);
    }

    #[test]
    fn a_word_start_rule_applies_only_at_a_word_start() {
        // ю is "yu" at the start of a word and "iu" inside it; the space keeps
        // them distinct in one name.
        assert_eq!(variants("Юрій Лютий")[0], "yurii liutyi");
    }
}
