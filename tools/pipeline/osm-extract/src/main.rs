//! OSM PBF -> the place list the search-section builder consumes.
//!
//! This is the extraction stage of the region pipeline. It rewrites, inside
//! this repository, the selection rules the P4a spike took from osm-mapidx:
//! a named element with a POI key becomes a place, an element with
//! `addr:housenumber` and a street becomes a house number, and the nearest
//! settlement supplies the locality that gives both their context.
//!
//! Usage:
//!   osm-extract --pbf <file.osm.pbf> --out <places.tsv>
//!               [--bbox west,south,east,north] [--no-places | --no-addresses]
//!
//! The output is the nine-column TSV `rust/grass-core`'s builder reads. See
//! `tools/pipeline/README.md` for the documented runs and their measurements.

use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use osmpbf::{Element, ElementReader};

/// Keys that make a named element a searchable place, strongest first. This is
/// the osm-mapidx set and order, rewritten here.
const POI_KEYS: [&str; 22] = [
    "amenity",
    "shop",
    "tourism",
    "leisure",
    "healthcare",
    "historic",
    "office",
    "craft",
    "emergency",
    "aeroway",
    "aerialway",
    "man_made",
    "military",
    "club",
    "sport",
    "public_transport",
    "railway",
    "natural",
    "place",
    "landuse",
    "waterway",
    "building",
];

/// Named roads a person looks a street up by. Paths, tracks and service roads
/// seldom carry a name worth finding, so they stay out, as in osm-mapidx.
const STREET_KINDS: [&str; 8] = [
    "trunk",
    "primary",
    "secondary",
    "tertiary",
    "unclassified",
    "residential",
    "living_street",
    "pedestrian",
];

/// Ranks that give an address its locality, nearest one wins. A settlement
/// (city, town, village, hamlet, borough) outranks a subdivision (suburb,
/// quarter, neighbourhood, locality) even when the subdivision is closer.
const SETTLEMENT_RANKS: [&str; 9] = [
    "city",
    "town",
    "village",
    "hamlet",
    "borough",
    "suburb",
    "quarter",
    "neighbourhood",
    "locality",
];

const SETTLEMENT_TIER: usize = 5;

/// Street type words. An `addr:street` that already carries one is also the
/// form a query types with "вулиця"; one that does not gets that alias.
const STREET_TYPE_WORDS: [&str; 18] = [
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

/// Tags that name a brand or its operator, most specific first. They are
/// carried into the feature tags and, when the name does not already say them,
/// into the search alias column, so "АТБ" finds a branch called "Магазин"
/// (T-0204).
const BRAND_TAG_KEYS: [&str; 3] = ["brand", "brand:uk", "brand:en"];
const OPERATOR_TAG_KEYS: [&str; 3] = ["operator", "operator:uk", "operator:en"];
const BRAND_KEYS: [&str; 6] = [
    "brand",
    "brand:uk",
    "brand:en",
    "operator",
    "operator:uk",
    "operator:en",
];

/// The tag prefix of a name in another language, e.g. `name:pl` (T-0205).
const NAME_LANGUAGE_PREFIX: &str = "name:";

fn is_brand_key(key: &str) -> bool {
    BRAND_KEYS.contains(&key)
}

/// A locality an address or place inherits: a named settlement node. A real
/// settlement outranks a subdivision at the same distance, because a house is
/// in Львів before it is in Знесіння.
struct Settlement {
    name: String,
    lon: f64,
    lat: f64,
    tier: u8,
}

/// A way kept from the first pass: its tags said it is a place or a house.
struct WayCandidate {
    osm_id: i64,
    refs: Vec<i64>,
    name: Option<String>,
    kind: Option<String>,
    street: Option<String>,
    number: Option<String>,
    feature_tags: Vec<(String, String)>,
}

/// A settlement mapped as a way, whose coordinate is known only after the
/// node pass. villages mapped as areas would otherwise lose their houses to a
/// neighbouring settlement's name.
struct SettlementWay {
    name: String,
    refs: Vec<i64>,
    tier: u8,
}

/// A tagged node kept until the settlement grid is complete.
struct NodeCandidate {
    id: i64,
    lon: f64,
    lat: f64,
    tags: Vec<(String, String)>,
}

struct BoundingBox {
    west: f64,
    south: f64,
    east: f64,
    north: f64,
}

impl BoundingBox {
    fn contains(&self, lon: f64, lat: f64) -> bool {
        (self.west..=self.east).contains(&lon) && (self.south..=self.north).contains(&lat)
    }
}

struct Options {
    pbf: PathBuf,
    out: PathBuf,
    bbox: Option<BoundingBox>,
    places: bool,
    addresses: bool,
}

#[derive(Default)]
struct Stats {
    places: u64,
    addresses: u64,
    addresses_deduped: u64,
    settlements: u64,
    settlement_ways: u64,
    relations_skipped: u64,
    relations_with_address: u64,
    relations_with_place: u64,
    ways_without_coordinates: u64,
}

fn main() -> ExitCode {
    let options = match parse_arguments() {
        Ok(options) => options,
        Err(message) => {
            eprintln!("osm-extract: {message}");
            return ExitCode::from(2);
        }
    };
    match run(&options) {
        Ok(stats) => {
            eprintln!(
                "osm-extract: {} places, {} addresses ({} duplicates), \
                 {} locality nodes and {} locality ways, \
                 {} of {} relations carry addr ({} carry place) and are not indexed, \
                 {} ways without coordinates",
                stats.places,
                stats.addresses,
                stats.addresses_deduped,
                stats.settlements,
                stats.settlement_ways,
                stats.relations_with_address,
                stats.relations_skipped,
                stats.relations_with_place,
                stats.ways_without_coordinates,
            );
            ExitCode::SUCCESS
        }
        Err(message) => {
            eprintln!("osm-extract: {message}");
            ExitCode::FAILURE
        }
    }
}

fn parse_arguments() -> Result<Options, String> {
    let mut pbf = None;
    let mut out = None;
    let mut bbox = None;
    let mut places = true;
    let mut addresses = true;
    let mut arguments = std::env::args().skip(1);
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "--pbf" => pbf = Some(PathBuf::from(next(&mut arguments, "--pbf")?)),
            "--out" => out = Some(PathBuf::from(next(&mut arguments, "--out")?)),
            "--bbox" => bbox = Some(parse_bbox(&next(&mut arguments, "--bbox")?)?),
            "--no-places" => places = false,
            "--no-addresses" => addresses = false,
            "-h" | "--help" => {
                return Err(
                    "usage: osm-extract --pbf <file.osm.pbf> --out <places.tsv> \
                            [--bbox west,south,east,north] [--no-places | --no-addresses]"
                        .to_owned(),
                );
            }
            other => return Err(format!("unknown argument: {other}")),
        }
    }
    let pbf = pbf.ok_or("--pbf is required")?;
    let out = out.ok_or("--out is required")?;
    if !places && !addresses {
        return Err("nothing to write: both --no-places and --no-addresses were given".to_owned());
    }
    Ok(Options {
        pbf,
        out,
        bbox,
        places,
        addresses,
    })
}

fn next(arguments: &mut impl Iterator<Item = String>, flag: &str) -> Result<String, String> {
    arguments
        .next()
        .ok_or_else(|| format!("{flag} needs a value"))
}

fn parse_bbox(value: &str) -> Result<BoundingBox, String> {
    let parts: Vec<f64> = value
        .split(',')
        .map(|part| part.trim().parse::<f64>())
        .collect::<Result<_, _>>()
        .map_err(|_| format!("--bbox must be west,south,east,north: {value}"))?;
    let [west, south, east, north] = parts.as_slice() else {
        return Err(format!("--bbox must be west,south,east,north: {value}"));
    };
    if !(west < east && south < north) || !(-180.0..=180.0).contains(west) {
        return Err(format!("--bbox is not a valid box: {value}"));
    }
    Ok(BoundingBox {
        west: *west,
        south: *south,
        east: *east,
        north: *north,
    })
}

fn run(options: &Options) -> Result<Stats, String> {
    let mut stats = Stats::default();
    let mut settlements: Vec<Settlement> = Vec::new();
    let mut settlement_ways: Vec<SettlementWay> = Vec::new();
    let mut ways: Vec<WayCandidate> = Vec::new();
    let mut needed: HashSet<i64> = HashSet::new();

    read_elements(&options.pbf, |element| match element {
        Element::Node(node) => {
            let tags = collect_tags(node.tags());
            collect_settlement(&tags, node.lon(), node.lat(), &mut settlements);
        }
        Element::DenseNode(node) => {
            let tags = collect_tags(node.tags());
            collect_settlement(&tags, node.lon(), node.lat(), &mut settlements);
        }
        Element::Way(way) => {
            let tags = collect_tags(way.tags());
            let refs: Vec<i64> = way.refs().collect();
            if let Some((name, tier)) = settlement_tags(&tags) {
                for reference in &refs {
                    needed.insert(*reference);
                }
                settlement_ways.push(SettlementWay {
                    name,
                    refs: refs.clone(),
                    tier,
                });
            }
            if let Some(candidate) = make_way(way.id(), &tags, refs) {
                for reference in &candidate.refs {
                    needed.insert(*reference);
                }
                ways.push(candidate);
            }
        }
        Element::Relation(relation) => {
            let tags = collect_tags(relation.tags());
            stats.relations_skipped += 1;
            if tag(&tags, "addr:housenumber").is_some()
                || tag(&tags, "addr:street").is_some()
                || tag(&tags, "addr:place").is_some()
            {
                stats.relations_with_address += 1;
            }
            if tag(&tags, "place").is_some() {
                stats.relations_with_place += 1;
            }
        }
    })?;
    stats.settlements = settlements.len() as u64;
    stats.settlement_ways = settlement_ways.len() as u64;

    // The second pass resolves the ways' node coordinates and keeps the
    // tagged nodes. Nothing is written yet: a node house needs the locality,
    // and a village mapped as a way only has a coordinate once this pass ends.
    let mut coordinates: HashMap<i64, (f64, f64)> = HashMap::with_capacity(needed.len());
    let mut nodes: Vec<NodeCandidate> = Vec::new();
    read_elements(&options.pbf, |element| match element {
        Element::Node(node) => collect_node(
            node.id(),
            node.lon(),
            node.lat(),
            node.tags(),
            options,
            &needed,
            &mut coordinates,
            &mut nodes,
        ),
        Element::DenseNode(node) => collect_node(
            node.id(),
            node.lon(),
            node.lat(),
            node.tags(),
            options,
            &needed,
            &mut coordinates,
            &mut nodes,
        ),
        _ => {}
    })?;

    let mut grid = SettlementGrid::new(&settlements);
    for settlement in &settlement_ways {
        match centroid(&settlement.refs, &coordinates) {
            Some((lon, lat)) => grid.add(&settlement.name, lon, lat, settlement.tier),
            None => stats.ways_without_coordinates += 1,
        }
    }

    let mut writer = BufWriter::new(
        File::create(&options.out)
            .map_err(|error| format!("{}: {error}", options.out.display()))?,
    );
    let mut seen_addresses: HashSet<String> = HashSet::new();
    for node in &nodes {
        write_node(
            &mut writer,
            node.id,
            node.lon,
            node.lat,
            &node.tags,
            options,
            &grid,
            &mut seen_addresses,
            &mut stats,
        )
        .map_err(|error| error.to_string())?;
    }

    ways.sort_unstable_by_key(|way| way.osm_id);
    for way in &ways {
        let Some(&(first_lon, first_lat)) = way.refs.first().and_then(|id| coordinates.get(id))
        else {
            stats.ways_without_coordinates += 1;
            continue;
        };
        if !contains(&options.bbox, first_lon, first_lat) {
            continue;
        }
        if let (Some(street), Some(number)) = (&way.street, &way.number) {
            if options.addresses {
                // A house outline is metres across; its first corner is the
                // house, and taking the centroid means assembling areas. This
                // is the osm-mapidx rule the phase-A slice used.
                let locality = grid.nearest(first_lon, first_lat);
                write_address(
                    &mut writer,
                    "way",
                    way.osm_id,
                    first_lon,
                    first_lat,
                    street,
                    number,
                    locality,
                    &mut seen_addresses,
                    &mut stats,
                )
                .map_err(|error| error.to_string())?;
            }
        }
        if let (Some(name), Some(kind)) = (&way.name, &way.kind) {
            if options.places {
                let (lon, lat) =
                    centroid(&way.refs, &coordinates).unwrap_or((first_lon, first_lat));
                write_place(
                    &mut writer,
                    "way",
                    way.osm_id,
                    lon,
                    lat,
                    name,
                    kind,
                    &way.feature_tags,
                    &grid,
                    &mut stats,
                )
                .map_err(|error| error.to_string())?;
            }
        }
    }
    writer
        .flush()
        .map_err(|error| format!("{}: {error}", options.out.display()))?;
    Ok(stats)
}

fn read_elements(path: &Path, mut visit: impl FnMut(Element<'_>)) -> Result<(), String> {
    let reader =
        ElementReader::from_path(path).map_err(|error| format!("{}: {error}", path.display()))?;
    reader
        .for_each(|element| visit(element))
        .map_err(|error| format!("{}: {error}", path.display()))
}

fn contains(bbox: &Option<BoundingBox>, lon: f64, lat: f64) -> bool {
    bbox.as_ref().is_none_or(|box_| box_.contains(lon, lat))
}

fn collect_tags<'a>(tags: impl Iterator<Item = (&'a str, &'a str)>) -> Vec<(String, String)> {
    tags.map(|(key, value)| (key.to_owned(), value.to_owned()))
        .collect()
}

fn tag<'a>(tags: &'a [(String, String)], key: &str) -> Option<&'a str> {
    tags.iter()
        .find(|(candidate, _)| candidate == key)
        .map(|(_, value)| value.as_str())
}

/// A settlement's name and tier, or None when the element is not one.
fn settlement_tags(tags: &[(String, String)]) -> Option<(String, u8)> {
    let rank = tag(tags, "place")?;
    let position = SETTLEMENT_RANKS
        .iter()
        .position(|candidate| candidate == &rank)?;
    let name = tag(tags, "name").filter(|name| !name.trim().is_empty())?;
    let tier = if position < SETTLEMENT_TIER { 0 } else { 1 };
    Some((name.to_owned(), tier))
}

fn collect_settlement(tags: &[(String, String)], lon: f64, lat: f64, out: &mut Vec<Settlement>) {
    if let Some((name, tier)) = settlement_tags(tags) {
        out.push(Settlement {
            name,
            lon,
            lat,
            tier,
        });
    }
}

/// The mean of a way's known node coordinates: good enough for a village area.
fn centroid(refs: &[i64], coordinates: &HashMap<i64, (f64, f64)>) -> Option<(f64, f64)> {
    let mut lon = 0.0;
    let mut lat = 0.0;
    let mut count = 0usize;
    for id in refs {
        if let Some(&(x, y)) = coordinates.get(id) {
            lon += x;
            lat += y;
            count += 1;
        }
    }
    (count > 0).then(|| (lon / count as f64, lat / count as f64))
}

/// Keeps a tagged node for emission once the locality grid exists.
#[allow(clippy::too_many_arguments)]
fn collect_node<'a>(
    id: i64,
    lon: f64,
    lat: f64,
    tags: impl Iterator<Item = (&'a str, &'a str)>,
    options: &Options,
    needed: &HashSet<i64>,
    coordinates: &mut HashMap<i64, (f64, f64)>,
    nodes: &mut Vec<NodeCandidate>,
) {
    if needed.contains(&id) {
        coordinates.insert(id, (lon, lat));
    }
    if !contains(&options.bbox, lon, lat) {
        return;
    }
    let tags = collect_tags(tags);
    let address = tag(&tags, "addr:housenumber")
        .filter(|value| !value.trim().is_empty())
        .zip(
            tag(&tags, "addr:street")
                .or_else(|| tag(&tags, "addr:place"))
                .filter(|value| !value.trim().is_empty()),
        )
        .is_some();
    let keep_address = options.addresses && address;
    let keep_place = options.places && poi_name_and_kind(&tags).is_some();
    if keep_address || keep_place {
        nodes.push(NodeCandidate { id, lon, lat, tags });
    }
}

fn make_way(osm_id: i64, tags: &[(String, String)], refs: Vec<i64>) -> Option<WayCandidate> {
    // An element can be both: a shop with an address is a place and a house
    // number, and the search index needs both rows.
    let number = tag(tags, "addr:housenumber").filter(|value| !value.trim().is_empty());
    let street = tag(tags, "addr:street")
        .or_else(|| tag(tags, "addr:place"))
        .filter(|value| !value.trim().is_empty());
    let address = number.zip(street);
    let poi = poi_name_and_kind(tags);
    if address.is_none() && poi.is_none() {
        return None;
    }
    let (street, number) = match address {
        Some((number, street)) => (Some(street.to_owned()), Some(number.to_owned())),
        None => (None, None),
    };
    let (name, kind) = match poi {
        Some((name, kind)) => (Some(name), Some(kind)),
        None => (None, None),
    };
    let mut feature_tags = Vec::new();
    if let (Some(street), Some(number)) = (&street, &number) {
        feature_tags.push(("addr:housenumber".to_owned(), number.clone()));
        feature_tags.push(("addr:street".to_owned(), street.clone()));
    }
    if let Some(name) = &name {
        feature_tags.push(("name".to_owned(), name.clone()));
    }
    for (key, value) in tags {
        if is_brand_key(key) {
            feature_tags.push((key.clone(), clean(value)));
        }
    }
    Some(WayCandidate {
        osm_id,
        refs,
        name,
        kind,
        street,
        number,
        feature_tags,
    })
}

fn poi_name_and_kind(tags: &[(String, String)]) -> Option<(String, String)> {
    let name = tag(tags, "name").filter(|name| !name.trim().is_empty())?;
    let kind = POI_KEYS
        .iter()
        .find_map(|key| tag(tags, key).map(|value| format!("{key}={value}")))
        .or_else(|| {
            let road = tag(tags, "highway")?;
            STREET_KINDS
                .contains(&road)
                .then(|| format!("highway={road}"))
        })?;
    if is_number_name(name) && kind.starts_with("building=") {
        return None;
    }
    Some((name.to_owned(), kind))
}

/// "45", "3і", "12/2б": a name that is only a house number. A building with
/// that name is the address, and the address row says it better.
fn is_number_name(name: &str) -> bool {
    let compact: Vec<char> = name
        .chars()
        .filter(|character| character.is_alphanumeric())
        .collect();
    compact
        .first()
        .is_some_and(|character| character.is_ascii_digit())
        && compact
            .iter()
            .filter(|character| character.is_alphabetic())
            .count()
            <= 2
}

#[allow(clippy::too_many_arguments)]
fn write_node(
    writer: &mut BufWriter<File>,
    id: i64,
    lon: f64,
    lat: f64,
    tags: &[(String, String)],
    options: &Options,
    grid: &SettlementGrid,
    seen_addresses: &mut HashSet<String>,
    stats: &mut Stats,
) -> std::io::Result<()> {
    if options.addresses {
        if let Some(number) = tag(tags, "addr:housenumber").filter(|value| !value.trim().is_empty())
        {
            if let Some(street) = tag(tags, "addr:street")
                .or_else(|| tag(tags, "addr:place"))
                .filter(|value| !value.trim().is_empty())
            {
                let locality = grid.nearest(lon, lat);
                write_address(
                    writer,
                    "node",
                    id,
                    lon,
                    lat,
                    street,
                    number,
                    locality,
                    seen_addresses,
                    stats,
                )?;
            }
        }
    }
    if options.places {
        if let Some((name, kind)) = poi_name_and_kind(tags) {
            write_place(
                writer, "node", id, lon, lat, &name, &kind, tags, grid, stats,
            )?;
        }
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn write_address(
    writer: &mut BufWriter<File>,
    osm_type: &str,
    osm_id: i64,
    lon: f64,
    lat: f64,
    street: &str,
    number: &str,
    locality: Option<&str>,
    seen_addresses: &mut HashSet<String>,
    stats: &mut Stats,
) -> std::io::Result<()> {
    let street = clean(street);
    let number = clean(number);
    if street.is_empty() || number.is_empty() {
        return Ok(());
    }
    let key = format!(
        "{}|{}|{}",
        street_key(&street),
        number_identity(&number),
        locality.unwrap_or_default(),
    );
    if !seen_addresses.insert(key) {
        stats.addresses_deduped += 1;
        return Ok(());
    }
    let name = format!("{street} {number}");
    let mut aliases: Vec<String> = Vec::new();
    let number_key = house_number_key(&number);
    if number_key != number.to_lowercase() {
        aliases.push(format!("{street} {number_key}"));
    }
    if !has_street_type(&street) {
        aliases.push(format!("вулиця {street} {number}"));
    }
    aliases.retain(|alias| alias != &name);
    aliases.dedup();
    let context = locality
        .map(clean)
        .filter(|value| !value.is_empty())
        .unwrap_or_default();
    let alias_text = aliases.join("|");
    let tags = format!("addr:housenumber={number}|addr:street={street}");
    write_row(
        writer,
        osm_type,
        osm_id,
        lat,
        lon,
        &name,
        "addr=house",
        &context,
        &tags,
        &alias_text,
    )?;
    stats.addresses += 1;
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn write_place(
    writer: &mut BufWriter<File>,
    osm_type: &str,
    osm_id: i64,
    lon: f64,
    lat: f64,
    name: &str,
    kind: &str,
    tags: &[(String, String)],
    grid: &SettlementGrid,
    stats: &mut Stats,
) -> std::io::Result<()> {
    let name = clean(name);
    let kind = clean(kind);
    if name.is_empty() || kind.is_empty() {
        return Ok(());
    }
    let context = grid.nearest(lon, lat).map(clean).unwrap_or_default();
    let feature_tags = tags
        .iter()
        .filter(|(key, _)| key == "name" || key.starts_with("addr:") || is_brand_key(key))
        .map(|(key, value)| format!("{key}={}", clean(value)))
        .collect::<Vec<_>>()
        .join("|");
    let aliases = place_aliases(tags, &name).join("|");
    write_row(
        writer,
        osm_type,
        osm_id,
        lat,
        lon,
        &name,
        &kind,
        &context,
        &feature_tags,
        &aliases,
    )?;
    stats.places += 1;
    Ok(())
}

/// The row's search aliases: the brand values, then the place's names in other
/// languages (`name:pl`, `name:en`, ...). A Latin query reaches a Cyrillic name
/// through these; "Złoczów" is the `name:pl` of Золочів, not a transliteration
/// a table could produce.
fn place_aliases(tags: &[(String, String)], name: &str) -> Vec<String> {
    let mut aliases = brand_values(tags, name);
    for value in name_language_values(tags, name) {
        if !aliases
            .iter()
            .any(|existing| existing.eq_ignore_ascii_case(&value))
        {
            aliases.push(value);
        }
    }
    aliases
}

/// The values of `name:<language>` tags whose name is not the row's own.
///
/// Only Latin and Cyrillic spellings are kept. A search box in this app is used
/// with those two scripts; an Arabic or CJK name would add bytes and terms that
/// no query in this region reaches. The language suffix must look like a
/// language tag, which keeps `name:prefix`, `name:left` and `name:source` out.
fn name_language_values(tags: &[(String, String)], name: &str) -> Vec<String> {
    let mut values: Vec<String> = Vec::new();
    for (key, raw) in tags {
        let Some(language) = key.strip_prefix(NAME_LANGUAGE_PREFIX) else {
            continue;
        };
        if !is_language_tag(language) {
            continue;
        }
        let value = clean(raw);
        if value.is_empty()
            || value.eq_ignore_ascii_case(name)
            || !is_latin_or_cyrillic(&value)
            || values.iter().any(|existing| existing == &value)
        {
            continue;
        }
        values.push(value);
    }
    values
}

/// `uk`, `en`, `crh`, `sr-Latn`: two or three letters, optionally followed by
/// one or more subtags.
fn is_language_tag(tag: &str) -> bool {
    let mut parts = tag.split('-');
    let Some(primary) = parts.next() else {
        return false;
    };
    if !(2..=3).contains(&primary.len()) || !primary.chars().all(|c| c.is_ascii_lowercase()) {
        return false;
    }
    parts.all(|part| {
        (2..=8).contains(&part.len()) && part.chars().all(|c| c.is_ascii_alphanumeric())
    })
}

/// Whether the value carries a Latin or Cyrillic letter.
fn is_latin_or_cyrillic(value: &str) -> bool {
    value.chars().any(|character| {
        matches!(character,
            'a'..='z' | 'A'..='Z'
                | '\u{00C0}'..='\u{024F}' | '\u{1E00}'..='\u{1EFF}'
                | '\u{0400}'..='\u{04FF}' | '\u{0500}'..='\u{052F}')
    })
}

/// The brand or, when no brand tag exists, the operator values a place
/// carries that its name does not already contain. They become the row's
/// search aliases, so a branch whose name is generic is still found by the
/// brand. When the brand is in the name the row is already findable, so the
/// operator's legal name ("ТОВ АТБ") is not added as noise.
fn brand_values(tags: &[(String, String)], name: &str) -> Vec<String> {
    let keys: &[&str] = if BRAND_TAG_KEYS
        .iter()
        .any(|key| tag(tags, key).is_some())
    {
        &BRAND_TAG_KEYS
    } else {
        &OPERATOR_TAG_KEYS
    };
    let folded_name = name.to_lowercase();
    let mut values: Vec<String> = Vec::new();
    for key in keys {
        let Some(raw) = tag(tags, key) else {
            continue;
        };
        let value = clean(raw);
        if value.is_empty() || folded_name.contains(&value.to_lowercase()) {
            continue;
        }
        if !values
            .iter()
            .any(|existing| existing.eq_ignore_ascii_case(&value))
        {
            values.push(value);
        }
    }
    values
}

#[allow(clippy::too_many_arguments)]
fn write_row(
    writer: &mut BufWriter<File>,
    osm_type: &str,
    osm_id: i64,
    lat: f64,
    lon: f64,
    name: &str,
    kind: &str,
    context: &str,
    tags: &str,
    aliases: &str,
) -> std::io::Result<()> {
    writeln!(
        writer,
        "{osm_type}\t{osm_id}\t{lat:.7}\t{lon:.7}\t{name}\t{kind}\t{context}\t{tags}\t{aliases}"
    )
}

/// One TSV field: tabs and line breaks would break the row, and a pipe would
/// break the tag or alias list.
fn clean(value: &str) -> String {
    value
        .chars()
        .map(|character| match character {
            '\t' | '\n' | '\r' => ' ',
            '|' => '/',
            other => other,
        })
        .collect::<String>()
        .trim()
        .to_owned()
}

/// The words that identify a street, independent of the type word and of word
/// order, so "Миргородська вулиця" and "вулиця Миргородська" are one key.
fn street_key(street: &str) -> String {
    let mut words: Vec<String> = street
        .split(|character: char| !character.is_alphanumeric())
        .filter(|word| !word.is_empty())
        .map(str::to_lowercase)
        .filter(|word| !STREET_TYPE_WORDS.contains(&word.as_str()))
        .collect();
    words.sort();
    words.dedup();
    words.join(" ")
}

/// What makes two spellings the same house: letters folded, a hyphen before a
/// letter dropped ("15-А" is "15а"), but a separator between numbers kept as
/// "/" so "12/2" is not "122".
fn number_identity(number: &str) -> String {
    let mut key = String::new();
    let mut pending_separator = false;
    for character in number
        .chars()
        .flat_map(|character| character.to_lowercase())
    {
        let folded = match character {
            'а' => 'a',
            'е' => 'e',
            'і' => 'i',
            'о' => 'o',
            'р' => 'p',
            'с' => 'c',
            'у' => 'y',
            'х' => 'x',
            other => other,
        };
        if folded.is_alphanumeric() {
            if pending_separator && folded.is_ascii_digit() && !key.is_empty() {
                key.push('/');
            }
            key.push(folded);
            pending_separator = false;
        } else {
            pending_separator = true;
        }
    }
    key
}

/// A house number reduced to what makes two spellings the same house: keep
/// letters and digits, drop separators, and fold the Cyrillic letters that
/// share a glyph with Latin ones. "1-А", "1а" and "1A" all key as "1a".
fn house_number_key(value: &str) -> String {
    let mut key = String::new();
    for character in value.chars().flat_map(|character| character.to_lowercase()) {
        let folded = match character {
            'а' => 'a',
            'е' => 'e',
            'і' => 'i',
            'о' => 'o',
            'р' => 'p',
            'с' => 'c',
            'у' => 'y',
            'х' => 'x',
            other => other,
        };
        if folded.is_alphanumeric() {
            key.push(folded);
        }
    }
    key
}

fn has_street_type(street: &str) -> bool {
    street.split_whitespace().any(|word| {
        let word = word.trim_end_matches('.').to_lowercase();
        STREET_TYPE_WORDS.contains(&word.as_str())
    })
}

/// The nearest settlement within a fixed radius. A grid of 0.2-degree cells
/// keeps the nearest lookup off the whole settlement list.
struct SettlementGrid {
    settlements: Vec<Settlement>,
    cells: HashMap<(i32, i32), Vec<usize>>,
}

const CELL_DEGREES: f64 = 0.2;
const MAX_SETTLEMENT_DISTANCE_M: f64 = 15_000.0;
const EARTH_RADIUS_M: f64 = 6_371_000.0;

impl SettlementGrid {
    fn new(settlements: &[Settlement]) -> Self {
        let mut grid = SettlementGrid {
            settlements: Vec::new(),
            cells: HashMap::new(),
        };
        // The list is a few tens of thousands of entries, far below the place
        // rows, so copying the names once is cheap.
        for settlement in settlements {
            grid.add(
                &settlement.name,
                settlement.lon,
                settlement.lat,
                settlement.tier,
            );
        }
        grid
    }

    fn add(&mut self, name: &str, lon: f64, lat: f64, tier: u8) {
        let index = self.settlements.len();
        self.settlements.push(Settlement {
            name: name.to_owned(),
            lon,
            lat,
            tier,
        });
        self.cells.entry(cell_of(lon, lat)).or_default().push(index);
    }

    fn nearest(&self, lon: f64, lat: f64) -> Option<&str> {
        let (cell_lon, cell_lat) = cell_of(lon, lat);
        let mut best: Option<(u8, f64, &str)> = None;
        for delta_lon in -1..=1 {
            for delta_lat in -1..=1 {
                let Some(indices) = self
                    .cells
                    .get(&(cell_lon + delta_lon, cell_lat + delta_lat))
                else {
                    continue;
                };
                for &index in indices {
                    let settlement = &self.settlements[index];
                    let distance = distance_m(lon, lat, settlement.lon, settlement.lat);
                    if distance > MAX_SETTLEMENT_DISTANCE_M {
                        continue;
                    }
                    let better = best.is_none_or(|(tier, best_distance, _)| {
                        (settlement.tier, distance) < (tier, best_distance)
                    });
                    if better {
                        best = Some((settlement.tier, distance, settlement.name.as_str()));
                    }
                }
            }
        }
        best.map(|(_, _, name)| name)
    }
}

fn cell_of(lon: f64, lat: f64) -> (i32, i32) {
    (
        (lon / CELL_DEGREES).floor() as i32,
        (lat / CELL_DEGREES).floor() as i32,
    )
}

fn distance_m(lon1: f64, lat1: f64, lon2: f64, lat2: f64) -> f64 {
    let (lat1, lat2) = (lat1.to_radians(), lat2.to_radians());
    let delta_lat = lat2 - lat1;
    let delta_lon = (lon2 - lon1).to_radians();
    let a =
        (delta_lat / 2.0).sin().powi(2) + lat1.cos() * lat2.cos() * (delta_lon / 2.0).sin().powi(2);
    2.0 * EARTH_RADIUS_M * a.sqrt().asin()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn house_numbers_fold_to_one_key() {
        assert_eq!(house_number_key("15-А"), "15a");
        assert_eq!(house_number_key("15а"), "15a");
        assert_eq!(house_number_key("15A"), "15a");
        assert_eq!(house_number_key("12/2б"), "122б");
    }

    #[test]
    fn a_street_key_ignores_type_words_and_word_order() {
        assert_eq!(
            street_key("Миргородська вулиця"),
            street_key("вулиця Миргородська")
        );
        assert_ne!(
            street_key("Миргородська вулиця"),
            street_key("Стрийська вулиця")
        );
    }

    #[test]
    fn a_number_identity_drops_a_suffix_hyphen_but_keeps_a_slash() {
        assert_eq!(number_identity("15-А"), "15a");
        assert_eq!(number_identity("15а"), "15a");
        assert_ne!(number_identity("12/2"), number_identity("122"));
        assert_eq!(number_identity("12/2"), "12/2");
    }

    #[test]
    fn a_street_with_its_type_word_is_not_given_one() {
        assert!(has_street_type("вулиця Стрийська"));
        assert!(has_street_type("Стрийська вулиця"));
        assert!(has_street_type("проспект Тараса Шевченка"));
        assert!(!has_street_type("Стрийська"));
    }

    #[test]
    fn a_brand_becomes_an_alias_only_when_the_name_does_not_say_it() {
        let tags = vec![
            ("name".to_owned(), "АТБ-Маркет".to_owned()),
            ("brand".to_owned(), "АТБ".to_owned()),
            ("operator".to_owned(), "ТОВ АТБ".to_owned()),
        ];
        assert_eq!(brand_values(&tags, "АТБ-Маркет"), Vec::<String>::new());

        let branch = vec![
            ("name".to_owned(), "Магазин".to_owned()),
            ("brand".to_owned(), "АТБ".to_owned()),
            ("brand:uk".to_owned(), "АТБ".to_owned()),
        ];
        assert_eq!(brand_values(&branch, "Магазин"), vec!["АТБ".to_owned()]);

        let operator_only = vec![
            ("name".to_owned(), "Крамниця".to_owned()),
            ("operator".to_owned(), "Сільпо".to_owned()),
        ];
        assert_eq!(
            brand_values(&operator_only, "Крамниця"),
            vec!["Сільпо".to_owned()]
        );
    }

    #[test]
    fn a_place_keeps_its_names_in_other_languages_as_aliases() {
        let tags = vec![
            ("name".to_owned(), "Золочів".to_owned()),
            ("name:en".to_owned(), "Zolochiv".to_owned()),
            ("name:pl".to_owned(), "Złoczów".to_owned()),
            ("name:ru".to_owned(), "Золочев".to_owned()),
            ("name:uk".to_owned(), "Золочів".to_owned()),
            // Not a language: the suffix must look like a language tag.
            ("name:prefix".to_owned(), "місто".to_owned()),
            ("name:signed".to_owned(), "no".to_owned()),
            // A script no search box in this region uses.
            ("name:ja".to_owned(), "ゾーロチウ".to_owned()),
        ];
        assert_eq!(
            name_language_values(&tags, "Золочів"),
            vec!["Zolochiv".to_owned(), "Złoczów".to_owned(), "Золочев".to_owned()],
        );
        assert_eq!(
            place_aliases(&tags, "Золочів"),
            name_language_values(&tags, "Золочів"),
        );
    }

    #[test]
    fn a_brand_and_a_name_in_another_language_share_the_alias_column() {
        let tags = vec![
            ("name".to_owned(), "Магазин".to_owned()),
            ("brand".to_owned(), "АТБ".to_owned()),
            ("name:en".to_owned(), "Shop".to_owned()),
        ];
        assert_eq!(
            place_aliases(&tags, "Магазин"),
            vec!["АТБ".to_owned(), "Shop".to_owned()],
        );
    }

    #[test]
    fn a_number_only_name_is_a_house_number() {
        assert!(is_number_name("45"));
        assert!(is_number_name("12/2б"));
        assert!(!is_number_name("3і Pharma"));
        assert!(!is_number_name("Львів"));
    }

    #[test]
    fn the_nearest_settlement_wins_and_far_ones_are_ignored() {
        let settlements = vec![
            Settlement {
                name: "Львів".to_owned(),
                lon: 24.03,
                lat: 49.84,
                tier: 0,
            },
            Settlement {
                name: "Винники".to_owned(),
                lon: 24.13,
                lat: 49.82,
                tier: 0,
            },
        ];
        let grid = SettlementGrid::new(&settlements);
        assert_eq!(grid.nearest(24.031, 49.841), Some("Львів"));
        assert_eq!(grid.nearest(24.129, 49.821), Some("Винники"));
        assert_eq!(grid.nearest(31.0, 49.0), None);
    }

    #[test]
    fn a_city_outranks_a_closer_neighbourhood() {
        let settlements = vec![
            Settlement {
                name: "Львів".to_owned(),
                lon: 24.03,
                lat: 49.84,
                tier: 0,
            },
            Settlement {
                name: "Знесіння".to_owned(),
                lon: 24.055,
                lat: 49.855,
                tier: 1,
            },
        ];
        let grid = SettlementGrid::new(&settlements);
        assert_eq!(grid.nearest(24.055, 49.855), Some("Львів"));
    }

    #[test]
    fn a_house_number_alias_carries_the_folded_number_and_the_type_word() {
        let mut stats = Stats::default();
        let mut seen = HashSet::new();
        let mut buffer = BufWriter::new(
            File::create(
                std::env::temp_dir().join(format!("osm-extract-{}.tsv", std::process::id())),
            )
            .expect("temp file"),
        );
        write_address(
            &mut buffer,
            "way",
            1,
            24.0,
            49.8,
            "Стрийська",
            "1-А",
            Some("Львів"),
            &mut seen,
            &mut stats,
        )
        .expect("write");
        buffer.flush().expect("flush");
        let text = std::fs::read_to_string(
            std::env::temp_dir().join(format!("osm-extract-{}.tsv", std::process::id())),
        )
        .expect("read");
        assert!(
            text.contains("Стрийська 1-А\taddr=house\tЛьвів\t"),
            "{text}"
        );
        assert!(text.contains("Стрийська 1a"), "{text}");
        assert!(text.contains("вулиця Стрийська 1-А"), "{text}");
        assert_eq!(stats.addresses, 1);
    }
}
