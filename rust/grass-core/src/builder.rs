//! The reference search-section builder.
//!
//! Real oblast packs are built by the pipeline from the OSM extract; the
//! committed fixture is built by this module from one authored place list, so
//! the search index and its feature component always come from the same pass.
//! The source is a tab-separated line per place:
//!
//! `osm_type  osm_id  lat  lon  name  kind  context  tags  [aliases]`
//!
//! `osm_type` is `node`, `way` or `relation`; `kind` is an OSM category such as
//! `amenity=cafe` or `addr=house`; `context` is a comma-separated address;
//! `tags` is `key=value` pairs separated by `|`, empty for a place with no
//! tags; `aliases` is optional and holds `|`-separated alternative names,
//! indexed one field weaker than the primary name. A house number is a place
//! whose name is the street and number ("вулиця Стрийська 45"); its kind
//! starts with `addr=` and the row carries [format::FLAG_ADDRESS]. Blank lines
//! and lines starting with `#` are ignored.

use std::collections::{BTreeMap, HashMap};
use std::fs::{self, File};
use std::path::Path;

use crate::error::CoreError;
use crate::features::{self, FeatureRecord, OsmElementType, PlaceFeature};
use crate::fold;
use crate::format::{self, Field, PlaceRecord};
use crate::index::Index;

const COLUMNS: usize = 8;
const COLUMNS_WITH_ALIASES: usize = 9;
const TAG_PAIR_SEPARATOR: char = '|';
const TAG_VALUE_SEPARATOR: char = '=';
const ALIAS_SEPARATOR: char = '|';
const CONTEXT_SEPARATOR: char = ',';
const COORDINATE_SCALE: f64 = format::COORD_SCALE;
const MAX_LATITUDE: f64 = 90.0;
const MAX_LONGITUDE: f64 = 180.0;
const KIND_AREA_PREFIXES: [&str; 2] = ["place=", "boundary="];
const KIND_STREET_PREFIX: &str = "highway=";
const KIND_ADDRESS_PREFIX: &str = "addr=";
const IMPORTANCE: u8 = 0;

/// What one build wrote.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct BuildStats {
    pub places: usize,
    pub entities: usize,
    pub terms: usize,
}

struct SourcePlace {
    osm_type: OsmElementType,
    osm_id: i64,
    latitude: f64,
    longitude: f64,
    name: String,
    aliases: Vec<String>,
    kind: String,
    context: Vec<String>,
    tags: BTreeMap<String, String>,
}

#[derive(Default)]
struct TermPostings {
    places: Vec<u32>,
    entities: Vec<u32>,
}

#[derive(Default)]
struct Interner {
    ids: HashMap<String, u32>,
    names: Vec<String>,
}

impl Interner {
    fn id(&mut self, name: &str) -> u32 {
        if let Some(id) = self.ids.get(name) {
            return *id;
        }
        let id = self.names.len() as u32;
        self.ids.insert(name.to_owned(), id);
        self.names.push(name.to_owned());
        id
    }
}

/// Builds the seven search-section files and the feature component from
/// [source] into [out_dir].
pub fn build(source: &Path, out_dir: &Path) -> Result<BuildStats, CoreError> {
    let text = fs::read_to_string(source).map_err(|_| CoreError::Io)?;
    let places = parse_source(&text)?;
    write_section(&places, out_dir)
}

/// Writes a valid feature component with no entries.
pub fn write_empty_features(path: &Path) -> Result<(), CoreError> {
    let bytes = features::encode(&mut [])?;
    fs::write(path, bytes).map_err(|_| CoreError::Io)
}

fn parse_source(text: &str) -> Result<Vec<SourcePlace>, CoreError> {
    let mut places = Vec::new();
    for line in text.lines() {
        let line = line.trim_end_matches('\r');
        if line.trim().is_empty() || line.starts_with('#') {
            continue;
        }
        let columns: Vec<&str> = line.split('\t').collect();
        if !(COLUMNS..=COLUMNS_WITH_ALIASES).contains(&columns.len()) {
            return Err(CoreError::InvalidInput);
        }
        places.push(parse_place(&columns)?);
    }
    Ok(places)
}

fn parse_place(columns: &[&str]) -> Result<SourcePlace, CoreError> {
    let osm_type = parse_osm_type(columns[0])?;
    let osm_id = columns[1].parse::<i64>().map_err(|_| CoreError::InvalidInput)?;
    if osm_id <= 0 {
        return Err(CoreError::InvalidInput);
    }
    let latitude = parse_coordinate(columns[2], MAX_LATITUDE)?;
    let longitude = parse_coordinate(columns[3], MAX_LONGITUDE)?;
    let name = columns[4].trim();
    if name.is_empty() {
        return Err(CoreError::InvalidInput);
    }
    let kind = columns[5].trim();
    if !kind.contains(TAG_VALUE_SEPARATOR) {
        return Err(CoreError::InvalidInput);
    }
    let context = columns[6]
        .split(CONTEXT_SEPARATOR)
        .map(str::trim)
        .filter(|part| !part.is_empty())
        .map(str::to_owned)
        .collect();
    let tags = parse_tags(columns[7])?;
    let aliases = columns
        .get(COLUMNS)
        .map(|value| parse_aliases(value, name))
        .unwrap_or_default();
    Ok(SourcePlace {
        osm_type,
        osm_id,
        latitude,
        longitude,
        name: name.to_owned(),
        aliases,
        kind: kind.to_owned(),
        context,
        tags,
    })
}

/// The alternative names in column nine. An alias identical to the primary
/// name adds nothing, so it is dropped rather than indexed twice.
fn parse_aliases(value: &str, primary: &str) -> Vec<String> {
    value
        .split(ALIAS_SEPARATOR)
        .map(str::trim)
        .filter(|alias| !alias.is_empty() && *alias != primary)
        .map(str::to_owned)
        .collect()
}

fn parse_osm_type(value: &str) -> Result<OsmElementType, CoreError> {
    match value.trim() {
        "node" => Ok(OsmElementType::Node),
        "way" => Ok(OsmElementType::Way),
        "relation" => Ok(OsmElementType::Relation),
        _ => Err(CoreError::InvalidInput),
    }
}

fn parse_coordinate(value: &str, limit: f64) -> Result<f64, CoreError> {
    let coordinate = value.trim().parse::<f64>().map_err(|_| CoreError::InvalidInput)?;
    if !coordinate.is_finite() || coordinate.abs() > limit {
        return Err(CoreError::InvalidInput);
    }
    Ok(coordinate)
}

fn parse_tags(value: &str) -> Result<BTreeMap<String, String>, CoreError> {
    let mut tags = BTreeMap::new();
    for pair in value.split(TAG_PAIR_SEPARATOR) {
        let pair = pair.trim();
        if pair.is_empty() {
            continue;
        }
        let (key, value) = pair.split_once(TAG_VALUE_SEPARATOR).ok_or(CoreError::InvalidInput)?;
        let key = key.trim();
        if key.is_empty() || value.is_empty() {
            return Err(CoreError::InvalidInput);
        }
        tags.insert(key.to_owned(), value.to_owned());
    }
    Ok(tags)
}

fn write_section(places: &[SourcePlace], out_dir: &Path) -> Result<BuildStats, CoreError> {
    fs::create_dir_all(out_dir).map_err(|_| CoreError::Io)?;
    let mut strings = Vec::new();
    let mut kinds = Interner::default();
    let mut entities = Interner::default();
    let mut terms: BTreeMap<String, TermPostings> = BTreeMap::new();
    let mut places_bin = Vec::new();
    let mut context_bin = Vec::new();
    let mut records = Vec::with_capacity(places.len());

    for (index, place) in places.iter().enumerate() {
        let place_id = u32::try_from(index).map_err(|_| CoreError::InvalidInput)?;
        add_name_terms(&mut terms, &place.name, place_id, Field::PrimaryName, &place.kind);
        for alias in &place.aliases {
            add_name_terms(&mut terms, alias, place_id, Field::OtherName, &place.kind);
        }
        add_term(&mut terms, &place.kind, place_id, Field::KindWord);

        let kind_id = kinds.id(&place.kind);
        if kind_id > u32::from(u16::MAX) {
            return Err(CoreError::InvalidInput);
        }
        let context_offset =
            u32::try_from(context_bin.len()).map_err(|_| CoreError::InvalidInput)?;
        format::put_varint(&mut context_bin, place.context.len() as u64);
        for part in &place.context {
            let new = !entities.ids.contains_key(part);
            let entity = entities.id(part);
            format::put_varint(&mut context_bin, u64::from(entity));
            if new {
                add_entity_term(&mut terms, part, entity);
            }
        }

        PlaceRecord {
            lat_e7: (place.latitude * COORDINATE_SCALE).round() as i32,
            lon_e7: (place.longitude * COORDINATE_SCALE).round() as i32,
            name_offset: format::put_string(&mut strings, &place.name)?,
            context_offset,
            kind: kind_id as u16,
            importance: IMPORTANCE,
            flags: flags_for(&place.kind),
        }
        .encode(&mut places_bin);

        records.push(FeatureRecord {
            place_id,
            feature: PlaceFeature {
                osm_type: place.osm_type,
                osm_id: place.osm_id,
                tags: place.tags.iter().map(|(k, v)| (k.clone(), v.clone())).collect(),
            },
        });
    }

    let mut kinds_bin = Vec::new();
    for kind in &kinds.names {
        kinds_bin.extend_from_slice(&format::put_string(&mut strings, kind)?.to_le_bytes());
    }
    let mut entities_bin = Vec::new();
    for entity in &entities.names {
        entities_bin.extend_from_slice(&format::put_string(&mut strings, entity)?.to_le_bytes());
    }

    let mut postings_bin = Vec::new();
    let terms_file =
        File::create(out_dir.join(format::TERMS_FILE)).map_err(|_| CoreError::Io)?;
    let mut fst = fst::MapBuilder::new(terms_file).map_err(|_| CoreError::InvalidInput)?;
    for (term, postings) in terms.iter_mut() {
        let offset = u64::try_from(postings_bin.len()).map_err(|_| CoreError::InvalidInput)?;
        fst.insert(term.as_str(), offset).map_err(|_| CoreError::InvalidInput)?;
        encode_postings(postings, &mut postings_bin);
    }
    fst.finish().map_err(|_| CoreError::InvalidInput)?;

    write_file(out_dir, format::POSTINGS_FILE, &postings_bin)?;
    write_file(out_dir, format::PLACES_FILE, &places_bin)?;
    write_file(out_dir, format::CONTEXT_FILE, &context_bin)?;
    write_file(out_dir, format::ENTITIES_FILE, &entities_bin)?;
    write_file(out_dir, format::KINDS_FILE, &kinds_bin)?;
    write_file(out_dir, format::STRINGS_FILE, &strings)?;
    let mut header = Vec::with_capacity(format::FORMAT_HEADER_BYTES);
    format::encode_format_header(&mut header);
    write_file(out_dir, format::FORMAT_FILE, &header)?;
    fs::write(out_dir.join(features::FEATURES_FILE), features::encode(&mut records)?)
        .map_err(|_| CoreError::Io)?;

    let index = Index::open(out_dir)?;
    if index.place_count() != places.len() as u32 {
        return Err(CoreError::CorruptData);
    }
    Ok(BuildStats {
        places: places.len(),
        entities: entities.names.len(),
        terms: terms.len(),
    })
}

fn write_file(out_dir: &Path, name: &str, bytes: &[u8]) -> Result<(), CoreError> {
    fs::write(out_dir.join(name), bytes).map_err(|_| CoreError::Io)
}

fn add_term(
    terms: &mut BTreeMap<String, TermPostings>,
    text: &str,
    place_id: u32,
    field: Field,
) {
    for word in fold::words(text) {
        terms
            .entry(word)
            .or_default()
            .places
            .push(place_id << format::FIELD_BITS | field as u32);
    }
}

/// A name's terms. An address drops the street-type words, which every address
/// on that kind of street shares and the query also drops.
fn add_name_terms(
    terms: &mut BTreeMap<String, TermPostings>,
    text: &str,
    place_id: u32,
    field: Field,
    kind: &str,
) {
    let is_address = kind.starts_with(KIND_ADDRESS_PREFIX);
    for word in fold::words(text) {
        if is_address && fold::is_street_type_word(&word) {
            continue;
        }
        terms
            .entry(word)
            .or_default()
            .places
            .push(place_id << format::FIELD_BITS | field as u32);
    }
}

fn add_entity_term(terms: &mut BTreeMap<String, TermPostings>, text: &str, entity: u32) {
    for word in fold::words(text) {
        terms.entry(word).or_default().entities.push(entity);
    }
}

fn encode_postings(postings: &mut TermPostings, out: &mut Vec<u8>) {
    postings.places.sort_unstable();
    postings.places.dedup_by(|later, earlier| {
        *later >> format::FIELD_BITS == *earlier >> format::FIELD_BITS
    });
    postings.entities.sort_unstable();
    postings.entities.dedup();
    for list in [&postings.entities, &postings.places] {
        format::put_varint(out, list.len() as u64);
        let mut previous = 0u32;
        for value in list.iter() {
            format::put_varint(out, u64::from(value - previous));
            previous = *value;
        }
    }
}

fn flags_for(kind: &str) -> u8 {
    if kind.starts_with(KIND_ADDRESS_PREFIX) {
        format::FLAG_ADDRESS
    } else if KIND_AREA_PREFIXES.iter().any(|prefix| kind.starts_with(prefix)) {
        format::FLAG_AREA
    } else if kind.starts_with(KIND_STREET_PREFIX) {
        format::FLAG_STREET
    } else {
        0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_DIRECTORY: AtomicU64 = AtomicU64::new(0);

    fn temp_directory() -> std::path::PathBuf {
        let directory = std::env::temp_dir().join(format!(
            "grass-section-{}-{}",
            std::process::id(),
            NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed),
        ));
        fs::create_dir_all(&directory).expect("create temp directory");
        directory
    }

    const SOURCE: &str = concat!(
        "# osm_type\tosm_id\tlat\tlon\tname\tkind\tcontext\ttags\n",
        "node\t4815162342\t49.9419\t23.9419\tКав'ярня Левада\tamenity=cafe\t",
        "смт Івано-Франкове, вул. Стрийська 45\t",
        "amenity=cafe|name=Кав'ярня Левада|phone=+380322970545|wheelchair=yes\n",
        "node\t4815162344\t49.9431\t23.9435\tЛавочка\tamenity=bench\t",
        "смт Івано-Франкове, парк\t\n",
    );

    #[test]
    fn builds_a_section_that_reopens() {
        let directory = temp_directory();
        let source = directory.join("places.tsv");
        fs::write(&source, SOURCE).expect("write source");
        let section = directory.join("section");
        let stats = build(&source, &section).expect("build");
        assert_eq!(stats.places, 2);
        let index = Index::open(&section).expect("open");
        assert_eq!(index.place_count(), 2);
        let record = index.place(0).expect("place");
        assert_eq!(index.place_name(&record).expect("name"), "Кав'ярня Левада");
        let cafe = index.feature(0).expect("feature").expect("entry");
        assert_eq!(cafe.osm_type, OsmElementType::Node);
        assert_eq!(cafe.osm_id, 4_815_162_342);
        assert_eq!(cafe.tags.len(), 4);
        let bench = index.feature(1).expect("feature").expect("entry");
        assert!(bench.tags.is_empty());
        fs::remove_dir_all(directory).ok();
    }

    /// A street, a cafe on it, and two houses. The second house is written
    /// without the "вулиця" prefix and carries the prefixed form as an alias.
    const ADDRESS_SOURCE: &str = concat!(
        "# osm_type\tosm_id\tlat\tlon\tname\tkind\tcontext\ttags\taliases\n",
        "node\t10\t49.8400\t24.0300\tвулиця Стрийська\thighway=residential\t",
        "Львів\thighway=residential\n",
        "node\t11\t49.8401\t24.0301\tКав'ярня\tamenity=cafe\tЛьвів\t",
        "amenity=cafe|name=Кав'ярня\n",
        "way\t12\t49.8402\t24.0302\tвулиця Стрийська 45\taddr=house\tЛьвів\t",
        "addr:housenumber=45|addr:street=вулиця Стрийська\n",
        "way\t13\t49.8403\t24.0303\tСтрийська 1-А\taddr=house\tЛьвів\t",
        "addr:housenumber=1-А|addr:street=Стрийська\tвулиця Стрийська 1-А|Стрийська 1а\n",
    );

    fn query(section: &Path, text: &str, limit: usize) -> Vec<crate::search::Hit> {
        let index = std::sync::Arc::new(Index::open(section).expect("open"));
        let typos = std::sync::Arc::new(crate::search::TypoAutomata::new());
        let mut session = crate::search::Session::new(index, typos, None);
        let flag = std::sync::atomic::AtomicBool::new(false);
        let cancel = crate::search::Cancel { flag: &flag };
        session.query(text, limit, &cancel).expect("query")
    }

    #[test]
    fn a_house_number_is_found_by_street_and_by_street_with_its_type_word() {
        let directory = temp_directory();
        let source = directory.join("places.tsv");
        fs::write(&source, ADDRESS_SOURCE).expect("write source");
        let section = directory.join("section");
        build(&source, &section).expect("build");

        let index = Index::open(&section).expect("open");
        assert_eq!(index.format_version(), format::FORMAT_VERSION);
        let house = index.place(2).expect("place");
        assert!(index.is_address(&house));
        assert!(!index.is_address(&index.place(0).expect("street")));

        for text in ["Стрийська 45", "вулиця стрийська 45", "стрийська 45"] {
            let hits = query(&section, text, 5);
            assert_eq!(hits.first().map(|hit| hit.id), Some(2), "{text}: {hits:?}");
        }
        let hits = query(&section, "вулиця стрийська 1а", 5);
        assert_eq!(hits.first().map(|hit| hit.id), Some(3), "{hits:?}");

        fs::remove_dir_all(directory).ok();
    }

    #[test]
    fn a_bare_house_number_returns_nothing() {
        let directory = temp_directory();
        let source = directory.join("places.tsv");
        fs::write(&source, ADDRESS_SOURCE).expect("write source");
        let section = directory.join("section");
        build(&source, &section).expect("build");

        assert!(query(&section, "45", 50).is_empty());
        assert!(query(&section, "1", 50).is_empty());

        fs::remove_dir_all(directory).ok();
    }

    #[test]
    fn the_committed_version_one_fixture_still_opens() {
        let fixture = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../test-data/regions/fixture-mini/2026-11-03-1");
        let index = Index::open(&fixture).expect("open the committed fixture");
        assert_eq!(index.format_version(), format::LEGACY_FORMAT_VERSION);
        assert!(index.place_count() > 0);
    }

    #[test]
    fn a_malformed_tag_pair_is_invalid_input() {
        let directory = temp_directory();
        let source = directory.join("places.tsv");
        fs::write(
            &source,
            "node\t1\t49.9\t23.9\tA\tamenity=cafe\t\tbroken\n",
        )
        .expect("write source");
        assert_eq!(
            build(&source, &directory.join("section")).err(),
            Some(CoreError::InvalidInput),
        );
        fs::remove_dir_all(directory).ok();
    }

    #[test]
    fn writes_an_empty_features_component() {
        let directory = temp_directory();
        let path = directory.join(features::FEATURES_FILE);
        write_empty_features(&path).expect("write");
        let features = crate::features::Features::open(&path).expect("open");
        assert_eq!(features.len(), 0);
        fs::remove_dir_all(directory).ok();
    }
}
