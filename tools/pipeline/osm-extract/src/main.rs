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
    relations_skipped: u64,
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
                "osm-extract: {} places, {} addresses ({} duplicates), {} localities, \
                 {} relations not indexed, {} ways without coordinates",
                stats.places,
                stats.addresses,
                stats.addresses_deduped,
                stats.settlements,
                stats.relations_skipped,
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
            if let Some(candidate) = make_way(way.id(), &tags, way.refs().collect()) {
                for reference in &candidate.refs {
                    needed.insert(*reference);
                }
                ways.push(candidate);
            }
        }
        Element::Relation(_) => stats.relations_skipped += 1,
    })?;
    stats.settlements = settlements.len() as u64;
    let grid = SettlementGrid::new(&settlements);

    let mut writer = BufWriter::new(
        File::create(&options.out)
            .map_err(|error| format!("{}: {error}", options.out.display()))?,
    );
    let mut coordinates: HashMap<i64, (f64, f64)> = HashMap::with_capacity(needed.len());
    let mut seen_addresses: HashSet<String> = HashSet::new();
    let mut write_error: Option<String> = None;

    read_elements(&options.pbf, |element| {
        if write_error.is_some() {
            return;
        }
        match element {
            Element::Node(node) => {
                let (id, lon, lat) = (node.id(), node.lon(), node.lat());
                if needed.contains(&id) {
                    coordinates.insert(id, (lon, lat));
                }
                if !contains(&options.bbox, lon, lat) {
                    return;
                }
                let tags = collect_tags(node.tags());
                if let Err(error) = write_node(
                    &mut writer,
                    id,
                    lon,
                    lat,
                    &tags,
                    options,
                    &grid,
                    &mut seen_addresses,
                    &mut stats,
                ) {
                    write_error = Some(error.to_string());
                }
            }
            Element::DenseNode(node) => {
                let (id, lon, lat) = (node.id(), node.lon(), node.lat());
                if needed.contains(&id) {
                    coordinates.insert(id, (lon, lat));
                }
                if !contains(&options.bbox, lon, lat) {
                    return;
                }
                let tags = collect_tags(node.tags());
                if let Err(error) = write_node(
                    &mut writer,
                    id,
                    lon,
                    lat,
                    &tags,
                    options,
                    &grid,
                    &mut seen_addresses,
                    &mut stats,
                ) {
                    write_error = Some(error.to_string());
                }
            }
            _ => {}
        }
    })?;
    if let Some(error) = write_error {
        return Err(error);
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
                let centroid = way
                    .refs
                    .iter()
                    .filter_map(|id| coordinates.get(id))
                    .fold((0.0, 0.0, 0usize), |(lon, lat, count), (x, y)| {
                        (lon + x, lat + y, count + 1)
                    });
                let (lon, lat) = if centroid.2 == 0 {
                    (first_lon, first_lat)
                } else {
                    (
                        centroid.0 / centroid.2 as f64,
                        centroid.1 / centroid.2 as f64,
                    )
                };
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

fn collect_settlement(tags: &[(String, String)], lon: f64, lat: f64, out: &mut Vec<Settlement>) {
    let Some(rank) = tag(tags, "place") else {
        return;
    };
    let Some(position) = SETTLEMENT_RANKS
        .iter()
        .position(|candidate| candidate == &rank)
    else {
        return;
    };
    let Some(name) = tag(tags, "name").filter(|name| !name.trim().is_empty()) else {
        return;
    };
    let tier = if position < SETTLEMENT_TIER { 0 } else { 1 };
    out.push(Settlement {
        name: name.to_owned(),
        lon,
        lat,
        tier,
    });
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
        .find_map(|key| tag(tags, key).map(|value| format!("{key}={value}")))?;
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
        street.to_lowercase(),
        house_number_key(&number),
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
        .filter(|(key, _)| key == "name" || key.starts_with("addr:"))
        .map(|(key, value)| format!("{key}={}", clean(value)))
        .collect::<Vec<_>>()
        .join("|");
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
        "",
    )?;
    stats.places += 1;
    Ok(())
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
        // The caller's vector is moved in by copying names once; the list is a
        // few tens of thousands of entries, far below the place rows.
        for settlement in settlements {
            let index = grid.settlements.len();
            grid.settlements.push(Settlement {
                name: settlement.name.clone(),
                lon: settlement.lon,
                lat: settlement.lat,
                tier: settlement.tier,
            });
            grid.cells
                .entry(cell_of(settlement.lon, settlement.lat))
                .or_default()
                .push(index);
        }
        grid
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
    fn a_street_with_its_type_word_is_not_given_one() {
        assert!(has_street_type("вулиця Стрийська"));
        assert!(has_street_type("Стрийська вулиця"));
        assert!(has_street_type("проспект Тараса Шевченка"));
        assert!(!has_street_type("Стрийська"));
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
