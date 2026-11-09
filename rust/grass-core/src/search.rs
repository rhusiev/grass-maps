//! Per-keystroke query over a memory-mapped search section.
//!
//! Every query word is matched as a prefix, with typos by a Levenshtein automaton
//! intersected with the term FST. A place is a candidate when some word hits its
//! own name, a variant or a kind word; the other words may then hit its context
//! entities instead. Words are scored, never intersected.

use std::collections::HashMap;
use std::hash::{BuildHasherDefault, Hasher};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use fst::automaton::Str;
use fst::{Automaton, IntoStreamer, Streamer};
use levenshtein_automata::{DFA, Distance, LevenshteinAutomatonBuilder, SINK_STATE};

use crate::error::CoreError;
use crate::fold;
use crate::format::{self, Field, PlaceRecord};
use crate::index::Index;

/// Organic Maps' typo budget: none under 4 letters, 1 under 8, else 2.
const ONE_EDIT_MIN_CHARS: usize = 4;
const TWO_EDITS_MIN_CHARS: usize = 8;

/// Most place postings walked per query word, best terms first. Chosen, not derived.
const POSTINGS_BUDGET: usize = 20_000;
/// Most entity postings walked per query word. Chosen, not derived.
const ENTITY_BUDGET: usize = 2_000;
/// How often (in postings or terms) a query checks whether it was cancelled.
const CANCEL_CHECK_EVERY: usize = 4_096;

// Word match quality, 0-1.
const EXACT_TERM: f32 = 1.0;
const PREFIX_BASE: f32 = 0.7;
const PREFIX_COVERAGE: f32 = 0.2;
const TYPO_TERM_BASE: f32 = 0.8;
const TYPO_PREFIX_BASE: f32 = 0.6;
const TYPO_PER_EDIT: f32 = 0.2;
const OTHER_NAME_PENALTY: f32 = 0.05;
const KIND_WORD_PENALTY: f32 = 0.1;
const CONTEXT_PENALTY: f32 = 0.25;

// The linear score. Weights picked by hand, not calibrated.
const W_WORDS: f32 = 1.0;
const W_ALL_WORDS: f32 = 0.3;
const W_EXACT_NAME_WORD: f32 = 0.1;
const W_AREA: f32 = 0.25;
const W_STREET: f32 = 0.15;
const W_IMPORTANCE: f32 = 0.2;
const W_LOG_DISTANCE: f32 = 0.04;
/// Between two address rows that matched the same words, the shorter name is
/// the more specific match: "Білоцерківська вулиця 1" beats the "1-А" two
/// doors away, which the query's number also prefixes. Chosen, not derived.
const W_ADDRESS_LENGTH: f32 = 0.01;

/// Ranked places considered for collapsing same-name neighbours.
const COLLAPSE_POOL: usize = 50;
/// Same name and kind closer than this is shown once. Chosen, not derived: an
/// area's node and its boundary centroid can sit kilometres apart.
const COLLAPSE_RADIUS_M: f64 = 100.0;
const AREA_COLLAPSE_RADIUS_M: f64 = 5_000.0;
const EARTH_RADIUS_M: f64 = 6_371_000.0;
const METRES_PER_KM: f64 = 1000.0;

#[derive(Default)]
pub struct FxHasher(u64);

impl Hasher for FxHasher {
    fn finish(&self) -> u64 {
        self.0
    }

    fn write(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.write_u64(u64::from(b));
        }
    }

    fn write_u32(&mut self, n: u32) {
        self.write_u64(u64::from(n));
    }

    fn write_u64(&mut self, n: u64) {
        self.0 = (self.0.rotate_left(5) ^ n).wrapping_mul(0x51_7c_c1_b7_27_22_0a_95);
    }
}

type FastMap<K, V> = HashMap<K, V, BuildHasherDefault<FxHasher>>;

/// What one query word resolved to: best score per place and per context entity.
#[derive(Default)]
pub struct WordMatches {
    places: FastMap<u32, f32>,
    entities: FastMap<u32, f32>,
}

fn max_edits(word: &str) -> u32 {
    let chars = word.chars().count();
    if word.chars().all(|c| c.is_ascii_digit()) || chars < ONE_EDIT_MIN_CHARS {
        0
    } else if chars < TWO_EDITS_MIN_CHARS {
        1
    } else {
        2
    }
}

/// Edits from `word` to the whole of `term`, and to its closest prefix.
fn edit_distances(word: &[char], term: &[char]) -> (usize, usize) {
    let mut row: Vec<usize> = (0..=term.len()).collect();
    for (i, &wc) in word.iter().enumerate() {
        let mut diagonal = row[0];
        row[0] = i + 1;
        for (j, &tc) in term.iter().enumerate() {
            let substitution = diagonal + usize::from(wc != tc);
            diagonal = row[j + 1];
            row[j + 1] = substitution.min(row[j] + 1).min(row[j + 1] + 1);
        }
    }
    (row[term.len()], *row.iter().min().unwrap_or(&0))
}

fn term_quality(word: &[char], term: &str) -> f32 {
    let term: Vec<char> = term.chars().collect();
    let coverage = word.len() as f32 / term.len().max(1) as f32;
    if term == word {
        return EXACT_TERM;
    }
    if term.starts_with(word) {
        return PREFIX_BASE + PREFIX_COVERAGE * coverage;
    }
    let (whole, prefix) = edit_distances(word, &term);
    if whole <= prefix {
        TYPO_TERM_BASE - TYPO_PER_EDIT * whole as f32
    } else {
        TYPO_PREFIX_BASE - TYPO_PER_EDIT * prefix as f32 + PREFIX_COVERAGE * coverage
    }
}

fn field_penalty(field: Field) -> f32 {
    match field {
        Field::PrimaryName => 0.0,
        Field::OtherName => OTHER_NAME_PENALTY,
        Field::KindWord => KIND_WORD_PENALTY,
    }
}

fn collect_terms<A: Automaton>(
    index: &Index,
    automaton: A,
    out: &mut Vec<(String, u64)>,
    cancel: &Cancel,
) -> Result<(), CoreError> {
    let mut stream = index.search().search(automaton).into_stream();
    let mut seen = 0usize;
    while let Some((key, offset)) = stream.next() {
        if seen % CANCEL_CHECK_EVERY == 0 && cancel.is_cancelled() {
            return Err(CoreError::Cancelled);
        }
        seen += 1;
        out.push((String::from_utf8_lossy(key).into_owned(), offset));
    }
    Ok(())
}

/// A prefix Levenshtein DFA from `levenshtein_automata`, run over the term FST.
/// fst 0.4.7's own `Levenshtein` missed Cyrillic terms at distance 1 ("двір" for "двір").
struct PrefixTypos(DFA);

impl Automaton for PrefixTypos {
    type State = u32;

    fn start(&self) -> u32 {
        self.0.initial_state()
    }

    fn is_match(&self, state: &u32) -> bool {
        matches!(self.0.distance(*state), Distance::Exact(_))
    }

    fn can_match(&self, state: &u32) -> bool {
        *state != SINK_STATE
    }

    fn accept(&self, state: &u32, byte: u8) -> u32 {
        self.0.transition(*state, byte)
    }
}

/// The parametric Levenshtein tables, one per edit budget; costly to build, so built once.
pub struct TypoAutomata {
    one_edit: LevenshteinAutomatonBuilder,
    two_edits: LevenshteinAutomatonBuilder,
}

impl TypoAutomata {
    pub fn new() -> Self {
        TypoAutomata {
            one_edit: LevenshteinAutomatonBuilder::new(1, true),
            two_edits: LevenshteinAutomatonBuilder::new(2, true),
        }
    }

    fn prefix_dfa(&self, word: &str, edits: u32) -> Option<PrefixTypos> {
        match edits {
            0 => None,
            1 => Some(PrefixTypos(self.one_edit.build_prefix_dfa(word))),
            _ => Some(PrefixTypos(self.two_edits.build_prefix_dfa(word))),
        }
    }
}

impl Default for TypoAutomata {
    fn default() -> Self {
        Self::new()
    }
}

/// Terms the word reaches: as a prefix, and within its typo budget with the first letter kept.
fn matching_terms(
    index: &Index,
    typos: &TypoAutomata,
    word: &str,
    cancel: &Cancel,
) -> Result<Vec<(String, u64)>, CoreError> {
    let mut out = Vec::new();
    let first: String = word.chars().take(1).collect();
    match typos.prefix_dfa(word, max_edits(word)) {
        Some(dfa) => collect_terms(
            index,
            dfa.intersection(Str::new(&first).starts_with()),
            &mut out,
            cancel,
        )?,
        None => collect_terms(index, Str::new(word).starts_with(), &mut out, cancel)?,
    }
    Ok(out)
}

/// Resolves one word; [CoreError::Cancelled] when a newer keystroke superseded it.
fn resolve(
    index: &Index,
    typos: &TypoAutomata,
    word: &str,
    cancel: &Cancel,
) -> Result<WordMatches, CoreError> {
    if cancel.is_cancelled() {
        return Err(CoreError::Cancelled);
    }
    let chars: Vec<char> = word.chars().collect();
    let mut terms: Vec<(f32, usize, u64)> = matching_terms(index, typos, word, cancel)?
        .into_iter()
        .map(|(term, offset)| (term_quality(&chars, &term), term.len(), offset))
        .collect();
    terms.sort_unstable_by(|a, b| b.0.total_cmp(&a.0).then(a.1.cmp(&b.1)));
    let mut matches = WordMatches::default();
    let (mut walked, mut walked_entities) = (0usize, 0usize);
    let bytes = index.postings();
    for (quality, _, offset) in terms {
        if walked >= POSTINGS_BUDGET && walked_entities >= ENTITY_BUDGET {
            break;
        }
        let mut pos = offset as usize;
        let entity_count = format::get_varint(bytes, &mut pos)? as usize;
        let mut entity = 0u32;
        for _ in 0..entity_count {
            let delta = format::get_varint(bytes, &mut pos)? as u32;
            entity = entity.saturating_add(delta);
            if walked_entities < ENTITY_BUDGET {
                matches
                    .entities
                    .entry(entity)
                    .and_modify(|best| *best = best.max(quality))
                    .or_insert(quality);
            }
            walked_entities += 1;
        }
        let count = format::get_varint(bytes, &mut pos)? as usize;
        let mut code = 0u32;
        for _ in 0..count {
            if walked >= POSTINGS_BUDGET {
                break;
            }
            let delta = format::get_varint(bytes, &mut pos)? as u32;
            code = code.saturating_add(delta);
            let field = Field::from_bits(code & format::FIELD_MASK);
            let score = quality - field_penalty(field);
            matches
                .places
                .entry(code >> format::FIELD_BITS)
                .and_modify(|best| *best = best.max(score))
                .or_insert(score);
            walked += 1;
            if walked % CANCEL_CHECK_EVERY == 0 && cancel.is_cancelled() {
                return Err(CoreError::Cancelled);
            }
        }
    }
    Ok(matches)
}

/// A query's view of the token the caller sets when the keystroke is superseded.
pub struct Cancel<'a> {
    pub flag: &'a AtomicBool,
}

impl Cancel<'_> {
    fn is_cancelled(&self) -> bool {
        self.flag.load(Ordering::Relaxed)
    }
}

#[derive(Debug, Clone)]
pub struct Hit {
    pub id: u32,
    pub score: f32,
    pub name: String,
    pub kind: String,
    pub context: String,
    pub lat: f64,
    pub lon: f64,
    pub is_area: bool,
    pub importance: u8,
}

fn distance_m(a: (f64, f64), b: (f64, f64)) -> f64 {
    let (lat1, lon1, lat2, lon2) = (
        a.0.to_radians(),
        a.1.to_radians(),
        b.0.to_radians(),
        b.1.to_radians(),
    );
    let h = ((lat2 - lat1) / 2.0).sin().powi(2)
        + lat1.cos() * lat2.cos() * ((lon2 - lon1) / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * h.sqrt().asin()
}

fn lat_lon(record: &PlaceRecord) -> (f64, f64) {
    (
        f64::from(record.lat_e7) / format::COORD_SCALE,
        f64::from(record.lon_e7) / format::COORD_SCALE,
    )
}

/// The linear score of one candidate against every query word.
fn score_place(
    index: &Index,
    place: u32,
    words: &[Arc<WordMatches>],
    origin: Option<(f64, f64)>,
) -> Result<f32, CoreError> {
    let record = index.place(place)?;
    let mut sum = 0.0;
    let mut used = 0;
    let mut exact_name_words = 0;
    for word in words {
        let named = word.places.get(&place).copied();
        let in_context = || {
            index
                .context_entities(&record)
                .ok()
                .into_iter()
                .flatten()
                .filter_map(|e| word.entities.get(&e))
                .fold(None, |best: Option<f32>, &s| {
                    Some(best.map_or(s, |b| b.max(s)))
                })
                .map(|s| s - CONTEXT_PENALTY)
        };
        if let Some(score) = named.or_else(in_context) {
            sum += score;
            used += 1;
            exact_name_words +=
                usize::from(named.is_some_and(|s| s >= EXACT_TERM - OTHER_NAME_PENALTY));
        }
    }
    let mut score =
        W_WORDS * sum / words.len() as f32 + W_EXACT_NAME_WORD * exact_name_words as f32;
    if used == words.len() {
        score += W_ALL_WORDS;
    }
    if record.flags & format::FLAG_AREA != 0 {
        score += W_AREA;
    } else if record.flags & format::FLAG_STREET != 0 {
        score += W_STREET;
    }
    if record.flags & format::FLAG_ADDRESS != 0 {
        score -= W_ADDRESS_LENGTH * index.place_name(&record)?.chars().count() as f32;
    }
    score += W_IMPORTANCE * f32::from(record.importance) / f32::from(u8::MAX);
    if let Some(origin) = origin {
        score -= W_LOG_DISTANCE
            * (1.0 + distance_m(origin, lat_lon(&record)) / METRES_PER_KM).ln() as f32;
    }
    Ok(score)
}

pub fn hit(index: &Index, place: u32, score: f32) -> Result<Hit, CoreError> {
    let record = index.place(place)?;
    let (lat, lon) = lat_lon(&record);
    let context: Vec<&str> = index
        .context_entities(&record)?
        .into_iter()
        .filter_map(|e| index.entity_name(e).ok())
        .collect();
    Ok(Hit {
        id: place,
        score,
        name: index.place_name(&record)?.to_owned(),
        kind: index.kind_name(record.kind)?.to_owned(),
        context: context.join(", "),
        lat,
        lon,
        is_area: index.is_area(&record),
        importance: record.importance,
    })
}

/// Keeps the best of same-name, same-kind places closer than the collapse radius.
fn collapse_neighbours(hits: Vec<Hit>, limit: usize) -> Vec<Hit> {
    let mut kept: Vec<Hit> = Vec::new();
    for hit in hits {
        let duplicate = kept.iter().any(|k| {
            let radius = if hit.is_area {
                AREA_COLLAPSE_RADIUS_M
            } else {
                COLLAPSE_RADIUS_M
            };
            k.name == hit.name
                && k.kind == hit.kind
                && distance_m((k.lat, k.lon), (hit.lat, hit.lon)) < radius
        });
        if !duplicate {
            kept.push(hit);
        }
        if kept.len() == limit {
            break;
        }
    }
    kept
}

/// One search box: keeps the previous keystroke's resolved words for reuse.
pub struct Session {
    index: Arc<Index>,
    typos: Arc<TypoAutomata>,
    pub origin: Option<(f64, f64)>,
    pub reuse_words: bool,
    previous: FastMap<String, Arc<WordMatches>>,
}

impl Session {
    pub fn new(index: Arc<Index>, typos: Arc<TypoAutomata>, origin: Option<(f64, f64)>) -> Self {
        Session {
            index,
            typos,
            origin,
            reuse_words: true,
            previous: FastMap::default(),
        }
    }

    pub fn reset(&mut self) {
        self.previous.clear();
    }

    pub fn set_origin(&mut self, origin: Option<(f64, f64)>) {
        self.origin = origin;
    }

    /// Results for `text`; [CoreError::Cancelled] when the token was set.
    pub fn query(
        &mut self,
        text: &str,
        limit: usize,
        cancel: &Cancel,
    ) -> Result<Vec<Hit>, CoreError> {
        let mut words = fold::words(text);
        // A house-number query names a street type every address on that kind
        // of street shares; the type word adds no discrimination and, as the
        // Lviv run measured, saturates the postings budget. Drop it only when
        // the query also carries a number, so "проспект Шевченка" still means
        // the avenue and not every "Шевченківський" place.
        let has_number = words.iter().any(|word| word.chars().any(|character| character.is_ascii_digit()));
        if has_number {
            words.retain(|word| !fold::is_street_type_word(word));
        }
        if words.is_empty() {
            return Ok(Vec::new());
        }
        // A bare house number names hundreds of houses and no street. Treat a
        // query with no letter as no search at all rather than walk number
        // postings that can only score an address row. A place whose whole
        // name is a number ("36", an allotment) therefore needs a word.
        if !words.iter().any(|word| word.chars().any(char::is_alphabetic)) {
            return Ok(Vec::new());
        }
        let mut resolved = Vec::with_capacity(words.len());
        let mut current = FastMap::default();
        for word in &words {
            let cached = self
                .reuse_words
                .then(|| self.previous.get(word).cloned())
                .flatten();
            let matches = match cached {
                Some(m) => m,
                None => Arc::new(resolve(&self.index, &self.typos, word, cancel)?),
            };
            current.insert(word.clone(), Arc::clone(&matches));
            resolved.push(matches);
        }
        self.previous = current;
        let mut candidates: Vec<u32> = resolved
            .iter()
            .flat_map(|w| w.places.keys().copied())
            .collect();
        candidates.sort_unstable();
        candidates.dedup();
        if cancel.is_cancelled() {
            return Err(CoreError::Cancelled);
        }
        let mut scored: Vec<(f32, u32)> = candidates
            .iter()
            .map(|&p| Ok((score_place(&self.index, p, &resolved, self.origin)?, p)))
            .collect::<Result<_, CoreError>>()?;
        let pool = COLLAPSE_POOL.min(scored.len());
        if pool < scored.len() {
            scored.select_nth_unstable_by(pool, |a, b| b.0.total_cmp(&a.0));
            scored.truncate(pool);
        }
        scored.sort_unstable_by(|a, b| b.0.total_cmp(&a.0).then(a.1.cmp(&b.1)));
        let hits = scored
            .into_iter()
            .map(|(s, p)| hit(&self.index, p, s))
            .collect::<Result<Vec<_>, CoreError>>()?;
        Ok(collapse_neighbours(hits, limit))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn edit_distances_cover_whole_and_prefix() {
        let chars = |s: &str| s.chars().collect::<Vec<_>>();
        assert_eq!(edit_distances(&chars("двир"), &chars("двір")), (1, 1));
        assert_eq!(
            edit_distances(&chars("славско"), &chars("славсько")),
            (1, 1)
        );
        assert_eq!(edit_distances(&chars("медов"), &chars("медовии")), (2, 0));
    }

    #[test]
    fn exact_beats_prefix_beats_typo() {
        let word: Vec<char> = "славсько".chars().collect();
        let exact = term_quality(&word, "славсько");
        let prefix = term_quality(&word, "славського");
        let typo = term_quality(&word, "славсъко");
        assert!(exact > prefix && prefix > typo, "{exact} {prefix} {typo}");
    }
}
