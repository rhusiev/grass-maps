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
//!   osm-extract --pbf <file.osm.pbf> --way-alerts --out <way-alerts.geojson>
//!               [--bbox west,south,east,north]
//!   osm-extract --pbf <file.osm.pbf> --route-relations --out <relations.json>
//!               [--bbox west,south,east,north]
//!
//! The place output is the nine-column TSV `rust/grass-core`'s builder reads.
//! See `tools/pipeline/README.md` for the documented runs and measurements.

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

/// Highway values a person can travel on. A tunnel, ford or hazard on any other
/// value (a construction site, a platform, a traffic-signal node tag) is not a
/// feature a route passes through (T-0319A).
const TRAVEL_HIGHWAY_VALUES: [&str; 26] = [
    "motorway",
    "motorway_link",
    "trunk",
    "trunk_link",
    "primary",
    "primary_link",
    "secondary",
    "secondary_link",
    "tertiary",
    "tertiary_link",
    "unclassified",
    "residential",
    "living_street",
    "service",
    "track",
    "path",
    "footway",
    "cycleway",
    "bridleway",
    "pedestrian",
    "steps",
    "corridor",
    "road",
    "escape",
    "busway",
    "bus_guideway",
];

/// Railway way values that cross a road at grade. Trams and light rail run
/// along streets and share nodes with the highway for long stretches, so they
/// are not a crossing by themselves; a tagged `railway=level_crossing` node
/// still carries a tram crossing (T-0319A).
const RAIL_WAY_VALUES: [&str; 2] = ["rail", "narrow_gauge"];

/// Tunnel values that are not a passage a rider goes through: a building
/// passage, a water culvert, a floodway or a pipe. `tunnel=no` is not a tunnel
/// at all (T-0319A).
const SKIPPED_TUNNEL_VALUES: [&str; 6] = [
    "no",
    "building_passage",
    "culvert",
    "floodway",
    "canal",
    "pipe",
];

/// Barriers a bike or a walker passes without a warning: a kerb, a bollard, a
/// building entrance, or a restriction that only limits height or width. Every
/// other barrier is a point where the route can be blocked (T-0319A).
const SKIPPED_BARRIER_VALUES: [&str; 7] = [
    "no",
    "none",
    "kerb",
    "bollard",
    "entrance",
    "height_restrictor",
    "width_restrictor",
];

/// Railway node values that mark infrastructure rather than a crossing. A node
/// shared by a rail way and a highway way at one of these is a mapping
/// coincidence, not a place to warn about (T-0319A).
/// States a route relation can carry while it is not a route a person walks
/// today. `state=proposed` is the common form; the others appear on abandoned
/// trails that still have their relation (T-0418).
const SKIPPED_ROUTE_STATES: [&str; 5] = [
    "proposed",
    "abandoned",
    "disused",
    "construction",
    "planned",
];

/// The route relation component this extractor writes.
const ROUTE_RELATIONS_FORMAT: &str = "grass-maps-route-relations";
const ROUTE_RELATIONS_VERSION: u32 = 1;

const NON_CROSSING_RAILWAY_NODE_VALUES: [&str; 13] = [
    "signal",
    "switch",
    "buffer_stop",
    "station",
    "halt",
    "stop",
    "derail",
    "milestone",
    "traverser",
    "turntable",
    "water_tower",
    "roundhouse",
    "junction",
];

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
    way_alerts: bool,
    route_relations: bool,
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

/// One feature the way-alert extraction writes (T-0319A).
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
enum WayAlertKind {
    RailwayCrossing,
    Tunnel,
    Hazard,
    Ford,
    Barrier,
}

impl WayAlertKind {
    fn as_str(self) -> &'static str {
        match self {
            WayAlertKind::RailwayCrossing => "railway_crossing",
            WayAlertKind::Tunnel => "tunnel",
            WayAlertKind::Hazard => "hazard",
            WayAlertKind::Ford => "ford",
            WayAlertKind::Barrier => "barrier",
        }
    }
}

#[derive(Default)]
struct WayAlertStats {
    railway_crossings: u64,
    tunnels: u64,
    hazards: u64,
    fords: u64,
    barriers: u64,
    shared_crossings: u64,
    skipped_tunnels: u64,
    skipped_barriers: u64,
    skipped_shared_nodes: u64,
    ways_without_coordinates: u64,
}

struct NodeAlert {
    id: i64,
    lon: f64,
    lat: f64,
    kind: WayAlertKind,
}

struct WayAlert {
    id: i64,
    kind: WayAlertKind,
    refs: Vec<i64>,
}

struct WayAlertFeature {
    kind: WayAlertKind,
    element_type: &'static str,
    id: i64,
    coordinates: Vec<(f64, f64)>,
}

/// A way member of a route relation, in relation order. A node member is not
/// part of the geometry and is skipped (T-0418).
struct RouteMember {
    way_id: i64,
    role: String,
}

struct RouteRelation {
    id: i64,
    network: Option<String>,
    reference: Option<String>,
    name: Option<String>,
    osmc_symbol: Option<String>,
    colour: Option<String>,
    distance: Option<String>,
    members: Vec<RouteMember>,
}

struct RouteWay {
    id: i64,
    refs: Vec<i64>,
}

#[derive(Default)]
struct RouteRelationStats {
    relations: u64,
    ways: u64,
    skipped_route: u64,
    skipped_state: u64,
    skipped_no_members: u64,
    skipped_missing_ways: u64,
    skipped_outside: u64,
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
    if options.way_alerts {
        return match run_way_alerts(&options) {
            Ok(stats) => {
                eprintln!(
                    "osm-extract: way-alerts: {} railway crossings, {} tunnels, {} hazards, \
                     {} fords, {} barriers ({} shared crossings, {} skipped tunnels, \
                     {} skipped barriers, {} skipped shared nodes, {} ways without coordinates)",
                    stats.railway_crossings,
                    stats.tunnels,
                    stats.hazards,
                    stats.fords,
                    stats.barriers,
                    stats.shared_crossings,
                    stats.skipped_tunnels,
                    stats.skipped_barriers,
                    stats.skipped_shared_nodes,
                    stats.ways_without_coordinates,
                );
                ExitCode::SUCCESS
            }
            Err(message) => {
                eprintln!("osm-extract: {message}");
                ExitCode::FAILURE
            }
        };
    }
    if options.route_relations {
        return match run_route_relations(&options) {
            Ok(stats) => {
                eprintln!(
                    "osm-extract: route-relations: {} hiking relations, {} member ways \
                     ({} skipped: {} not route=hiking, {} proposed or retired, \
                     {} without way members, {} without any complete member way, \
                     {} outside the bbox; {} member ways without coordinates)",
                    stats.relations,
                    stats.ways,
                    stats.skipped_route
                        + stats.skipped_state
                        + stats.skipped_no_members
                        + stats.skipped_missing_ways
                        + stats.skipped_outside,
                    stats.skipped_route,
                    stats.skipped_state,
                    stats.skipped_no_members,
                    stats.skipped_missing_ways,
                    stats.skipped_outside,
                    stats.ways_without_coordinates,
                );
                ExitCode::SUCCESS
            }
            Err(message) => {
                eprintln!("osm-extract: {message}");
                ExitCode::FAILURE
            }
        };
    }

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
    let mut way_alerts = false;
    let mut route_relations = false;
    let mut arguments = std::env::args().skip(1);
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "--pbf" => pbf = Some(PathBuf::from(next(&mut arguments, "--pbf")?)),
            "--out" => out = Some(PathBuf::from(next(&mut arguments, "--out")?)),
            "--bbox" => bbox = Some(parse_bbox(&next(&mut arguments, "--bbox")?)?),
            "--no-places" => places = false,
            "--no-addresses" => addresses = false,
            "--way-alerts" => way_alerts = true,
            "--route-relations" => route_relations = true,
            "-h" | "--help" => {
                return Err(
                    "usage: osm-extract --pbf <file.osm.pbf> --out <places.tsv> \
                            [--bbox west,south,east,north] [--no-places | --no-addresses]\n\
                            osm-extract --pbf <file.osm.pbf> --way-alerts --out <way-alerts.geojson> \
                            [--bbox west,south,east,north]\n\
                            osm-extract --pbf <file.osm.pbf> --route-relations --out <relations.json> \
                            [--bbox west,south,east,north]"
                        .to_owned(),
                );
            }
            other => return Err(format!("unknown argument: {other}")),
        }
    }
    let pbf = pbf.ok_or("--pbf is required")?;
    let out = out.ok_or("--out is required")?;
    if way_alerts && route_relations {
        return Err("--way-alerts and --route-relations cannot be combined".to_owned());
    }
    if (way_alerts || route_relations) && (!places || !addresses) {
        return Err(
            "--way-alerts and --route-relations cannot be combined with --no-places or --no-addresses"
                .to_owned(),
        );
    }
    if !way_alerts && !route_relations && !places && !addresses {
        return Err("nothing to write: both --no-places and --no-addresses were given".to_owned());
    }
    Ok(Options {
        pbf,
        out,
        bbox,
        places,
        addresses,
        way_alerts,
        route_relations,
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

fn run_way_alerts(options: &Options) -> Result<WayAlertStats, String> {
    let mut stats = WayAlertStats::default();
    let mut rail_nodes: HashSet<i64> = HashSet::new();
    let mut ways: Vec<WayAlert> = Vec::new();
    let mut needed: HashSet<i64> = HashSet::new();

    // First pass: the rail nodes that a highway way may share, and the ways
    // that carry a tunnel, hazard or ford.
    read_elements(&options.pbf, |element| {
        let Element::Way(way) = element else {
            return;
        };
        let tags = collect_tags(way.tags());
        let refs: Vec<i64> = way.refs().collect();
        if is_rail_way(&tags) && !is_grade_separated(&tags) {
            rail_nodes.extend(refs.iter().copied());
        }
        match way_alert_kind(&tags) {
            Some(kind) => {
                needed.extend(refs.iter().copied());
                ways.push(WayAlert {
                    id: way.id(),
                    kind,
                    refs,
                });
            }
            None => {
                if tag(&tags, "tunnel").is_some_and(|value| value != "no") {
                    stats.skipped_tunnels += 1;
                }
            }
        }
    })?;

    // Second pass: a node shared by a railway way and a highway way is a level
    // crossing where the region maps it that way. A road or rail way that is
    // grade-separated (a bridge or a tunnel) cannot share a crossing node.
    let mut shared: HashSet<i64> = HashSet::new();
    read_elements(&options.pbf, |element| {
        let Element::Way(way) = element else {
            return;
        };
        let tags = collect_tags(way.tags());
        if !is_travel_highway(&tags) || is_grade_separated(&tags) {
            return;
        }
        for reference in way.refs() {
            if rail_nodes.contains(&reference) {
                shared.insert(reference);
            }
        }
    })?;
    needed.extend(shared.iter().copied());

    // Third pass: node coordinates for the way geometries and the shared
    // crossings, plus the tagged point features inside the box.
    let mut coordinates: HashMap<i64, (f64, f64)> = HashMap::with_capacity(needed.len());
    let mut nodes: Vec<NodeAlert> = Vec::new();
    let mut shared_tags: HashMap<i64, Vec<(String, String)>> = HashMap::new();
    read_elements(&options.pbf, |element| {
        let (id, lon, lat, tags) = match element {
            Element::Node(node) => (node.id(), node.lon(), node.lat(), collect_tags(node.tags())),
            Element::DenseNode(node) => {
                (node.id(), node.lon(), node.lat(), collect_tags(node.tags()))
            }
            _ => return,
        };
        if needed.contains(&id) {
            coordinates.insert(id, (lon, lat));
            if shared.contains(&id) {
                shared_tags.insert(id, tags.clone());
            }
        }
        if !contains(&options.bbox, lon, lat) {
            return;
        }
        if let Some(kind) = node_alert_kind(&tags) {
            nodes.push(NodeAlert { id, lon, lat, kind });
        } else if tag(&tags, "barrier").is_some() {
            stats.skipped_barriers += 1;
        }
    })?;

    let tagged: HashSet<i64> = nodes.iter().map(|node| node.id).collect();
    let mut features: Vec<WayAlertFeature> = Vec::with_capacity(nodes.len() + ways.len());
    for node in nodes {
        stats.record(node.kind);
        features.push(WayAlertFeature {
            kind: node.kind,
            element_type: "node",
            id: node.id,
            coordinates: vec![(node.lon, node.lat)],
        });
    }

    let mut shared_ids: Vec<i64> = shared
        .into_iter()
        .filter(|id| !tagged.contains(id))
        .collect();
    shared_ids.sort_unstable();
    for id in shared_ids {
        let Some(&(lon, lat)) = coordinates.get(&id) else {
            stats.ways_without_coordinates += 1;
            continue;
        };
        if !contains(&options.bbox, lon, lat) {
            continue;
        }
        let tags = shared_tags.get(&id).map(Vec::as_slice).unwrap_or(&[]);
        if has_non_crossing_railway_tag(tags) {
            stats.skipped_shared_nodes += 1;
            continue;
        }
        stats.shared_crossings += 1;
        stats.record(WayAlertKind::RailwayCrossing);
        features.push(WayAlertFeature {
            kind: WayAlertKind::RailwayCrossing,
            element_type: "node",
            id,
            coordinates: vec![(lon, lat)],
        });
    }

    ways.sort_unstable_by_key(|way| (way.kind, way.id));
    for way in &ways {
        let mut points: Vec<(f64, f64)> = Vec::with_capacity(way.refs.len());
        let mut complete = true;
        for reference in &way.refs {
            match coordinates.get(reference) {
                Some(&point) => points.push(point),
                None => {
                    complete = false;
                    break;
                }
            }
        }
        if !complete || points.len() < 2 {
            stats.ways_without_coordinates += 1;
            continue;
        }
        if !points
            .iter()
            .any(|(lon, lat)| contains(&options.bbox, *lon, *lat))
        {
            continue;
        }
        stats.record(way.kind);
        features.push(WayAlertFeature {
            kind: way.kind,
            element_type: "way",
            id: way.id,
            coordinates: points,
        });
    }

    features
        .sort_unstable_by_key(|feature| (feature.kind, feature.element_type == "way", feature.id));
    let mut writer = BufWriter::new(
        File::create(&options.out)
            .map_err(|error| format!("{}: {error}", options.out.display()))?,
    );
    write_way_alert_collection(&mut writer, &features)
        .map_err(|error| format!("{}: {error}", options.out.display()))?;
    writer
        .flush()
        .map_err(|error| format!("{}: {error}", options.out.display()))?;
    Ok(stats)
}

fn run_route_relations(options: &Options) -> Result<RouteRelationStats, String> {
    let mut stats = RouteRelationStats::default();
    let mut relations: Vec<RouteRelation> = Vec::new();
    let mut needed_ways: HashSet<i64> = HashSet::new();

    // First pass: the hiking relations and their way members. Node members
    // (guideposts, route markers) are not geometry and are skipped.
    read_elements(&options.pbf, |element| {
        let Element::Relation(relation) = element else {
            return;
        };
        let tags = collect_tags(relation.tags());
        if tag(&tags, "type") != Some("route") {
            return;
        }
        if tag(&tags, "route") != Some("hiking") {
            stats.skipped_route += 1;
            return;
        }
        if route_state_is_skipped(&tags) {
            stats.skipped_state += 1;
            return;
        }
        let mut members: Vec<RouteMember> = Vec::new();
        for member in relation.members() {
            if member.member_type != osmpbf::RelMemberType::Way {
                continue;
            }
            members.push(RouteMember {
                way_id: member.member_id,
                role: member.role().unwrap_or("").to_owned(),
            });
        }
        if members.is_empty() {
            stats.skipped_no_members += 1;
            return;
        }
        for member in &members {
            needed_ways.insert(member.way_id);
        }
        relations.push(RouteRelation {
            id: relation.id(),
            network: tag(&tags, "network").map(str::to_owned),
            reference: tag(&tags, "ref").map(str::to_owned),
            name: tag(&tags, "name").map(str::to_owned),
            osmc_symbol: tag(&tags, "osmc:symbol").map(str::to_owned),
            colour: tag(&tags, "colour").map(str::to_owned),
            distance: tag(&tags, "distance").map(str::to_owned),
            members,
        });
    })?;

    // Second pass: the member ways, and the nodes their geometry needs.
    let mut ways: Vec<RouteWay> = Vec::new();
    let mut needed_nodes: HashSet<i64> = HashSet::new();
    read_elements(&options.pbf, |element| {
        let Element::Way(way) = element else {
            return;
        };
        if !needed_ways.contains(&way.id()) {
            return;
        }
        let refs: Vec<i64> = way.refs().collect();
        needed_nodes.extend(refs.iter().copied());
        ways.push(RouteWay { id: way.id(), refs });
    })?;

    // Third pass: node coordinates for every member way.
    let mut coordinates: HashMap<i64, (f64, f64)> = HashMap::with_capacity(needed_nodes.len());
    read_elements(&options.pbf, |element| {
        let (id, lon, lat) = match element {
            Element::Node(node) => (node.id(), node.lon(), node.lat()),
            Element::DenseNode(node) => (node.id(), node.lon(), node.lat()),
            _ => return,
        };
        if needed_nodes.contains(&id) {
            coordinates.insert(id, (lon, lat));
        }
    })?;

    let mut way_geometry: HashMap<i64, Vec<(f64, f64)>> = HashMap::with_capacity(ways.len());
    for way in &ways {
        let mut points: Vec<(f64, f64)> = Vec::with_capacity(way.refs.len());
        let mut complete = true;
        for reference in &way.refs {
            match coordinates.get(reference) {
                Some(&point) => points.push(point),
                None => {
                    complete = false;
                    break;
                }
            }
        }
        if !complete || points.len() < 2 {
            stats.ways_without_coordinates += 1;
            continue;
        }
        way_geometry.insert(way.id, points);
    }

    // A relation keeps only the way members with usable geometry. It is
    // skipped when none is left or when every point falls outside the bbox.
    let mut kept: Vec<RouteRelation> = Vec::with_capacity(relations.len());
    for mut relation in relations {
        relation
            .members
            .retain(|member| way_geometry.contains_key(&member.way_id));
        if relation.members.is_empty() {
            stats.skipped_missing_ways += 1;
            continue;
        }
        let inside = relation.members.iter().any(|member| {
            way_geometry.get(&member.way_id).is_some_and(|points| {
                points
                    .iter()
                    .any(|(lon, lat)| contains(&options.bbox, *lon, *lat))
            })
        });
        if !inside && options.bbox.is_some() {
            stats.skipped_outside += 1;
            continue;
        }
        kept.push(relation);
    }
    kept.sort_unstable_by_key(|relation| relation.id);

    let mut used_ways: HashSet<i64> = HashSet::new();
    for relation in &kept {
        for member in &relation.members {
            used_ways.insert(member.way_id);
        }
    }
    let mut way_ids: Vec<i64> = used_ways.into_iter().collect();
    way_ids.sort_unstable();
    stats.relations = kept.len() as u64;
    stats.ways = way_ids.len() as u64;

    let mut writer = BufWriter::new(
        File::create(&options.out)
            .map_err(|error| format!("{}: {error}", options.out.display()))?,
    );
    write_route_relation_document(&mut writer, &kept, &way_ids, &way_geometry)
        .map_err(|error| format!("{}: {error}", options.out.display()))?;
    writer
        .flush()
        .map_err(|error| format!("{}: {error}", options.out.display()))?;
    Ok(stats)
}

/// A retired, planned or abandoned route is not a route a person walks today.
/// `state=proposed` is the common form; `proposed=yes` covers the older style.
fn route_state_is_skipped(tags: &[(String, String)]) -> bool {
    tag(tags, "state").is_some_and(|value| SKIPPED_ROUTE_STATES.contains(&value))
        || has_significant_value(tags, "proposed")
}

fn write_route_relation_document(
    writer: &mut impl Write,
    relations: &[RouteRelation],
    way_ids: &[i64],
    way_geometry: &HashMap<i64, Vec<(f64, f64)>>,
) -> std::io::Result<()> {
    writeln!(writer, "{{")?;
    writeln!(writer, "  \"format\": \"{ROUTE_RELATIONS_FORMAT}\",")?;
    writeln!(writer, "  \"version\": {ROUTE_RELATIONS_VERSION},")?;
    writeln!(writer, "  \"ways\": [")?;
    for (index, way_id) in way_ids.iter().enumerate() {
        let separator = if index + 1 == way_ids.len() { "" } else { "," };
        write!(writer, "    {{\"id\":{way_id},\"coordinates\":[")?;
        if let Some(points) = way_geometry.get(way_id) {
            for (point_index, (lon, lat)) in points.iter().enumerate() {
                if point_index > 0 {
                    write!(writer, ",")?;
                }
                write!(writer, "[{lon:.7},{lat:.7}]")?;
            }
        }
        writeln!(writer, "]}}{separator}")?;
    }
    writeln!(writer, "  ],")?;
    writeln!(writer, "  \"relations\": [")?;
    for (index, relation) in relations.iter().enumerate() {
        let separator = if index + 1 == relations.len() {
            ""
        } else {
            ","
        };
        write!(
            writer,
            "    {{\"id\":{},\"type\":\"route\",\"route\":\"hiking\"",
            relation.id
        )?;
        write_optional_string(writer, "network", &relation.network)?;
        write_optional_string(writer, "ref", &relation.reference)?;
        write_optional_string(writer, "name", &relation.name)?;
        write_optional_string(writer, "osmcSymbol", &relation.osmc_symbol)?;
        write_optional_string(writer, "colour", &relation.colour)?;
        if let Some(distance) = relation.distance.as_deref().and_then(parse_distance) {
            write!(writer, ",\"distanceMeters\":{distance}")?;
        }
        write!(writer, ",\"members\":[")?;
        for (member_index, member) in relation.members.iter().enumerate() {
            if member_index > 0 {
                write!(writer, ",")?;
            }
            write!(writer, "{{\"way\":{},\"role\":", member.way_id)?;
            write_json_string(writer, &member.role)?;
            write!(writer, "}}")?;
        }
        writeln!(writer, "]}}{separator}")?;
    }
    writeln!(writer, "  ]")?;
    writeln!(writer, "}}")?;
    Ok(())
}

fn write_optional_string(
    writer: &mut impl Write,
    key: &str,
    value: &Option<String>,
) -> std::io::Result<()> {
    if let Some(value) = value.as_deref().filter(|value| !value.is_empty()) {
        write!(writer, ",\"{key}\":")?;
        write_json_string(writer, value)?;
    }
    Ok(())
}

fn write_json_string(writer: &mut impl Write, value: &str) -> std::io::Result<()> {
    write!(writer, "\"")?;
    for character in value.chars() {
        match character {
            '"' => write!(writer, "\\\"")?,
            '\\' => write!(writer, "\\\\")?,
            '\n' => write!(writer, "\\n")?,
            '\r' => write!(writer, "\\r")?,
            '\t' => write!(writer, "\\t")?,
            control if control < ' ' => write!(writer, "\\u{:04x}", control as u32)?,
            other => write!(writer, "{other}")?,
        }
    }
    write!(writer, "\"")
}

fn parse_distance(value: &str) -> Option<f64> {
    let parsed = value.trim().parse::<f64>().ok()?;
    (parsed.is_finite() && parsed >= 0.0).then_some(parsed)
}

impl WayAlertStats {
    fn record(&mut self, kind: WayAlertKind) {
        match kind {
            WayAlertKind::RailwayCrossing => self.railway_crossings += 1,
            WayAlertKind::Tunnel => self.tunnels += 1,
            WayAlertKind::Hazard => self.hazards += 1,
            WayAlertKind::Ford => self.fords += 1,
            WayAlertKind::Barrier => self.barriers += 1,
        }
    }
}

/// Every kind a point feature can carry, strongest first. A node with several
/// tags (a ford at a gate) is one feature: a crossing, then a hazard, then a
/// ford, then a barrier (T-0319A).
fn node_alert_kind(tags: &[(String, String)]) -> Option<WayAlertKind> {
    if tag(tags, "railway")
        .is_some_and(|value| value == "level_crossing" || value == "tram_level_crossing")
    {
        return Some(WayAlertKind::RailwayCrossing);
    }
    if has_significant_value(tags, "hazard") {
        return Some(WayAlertKind::Hazard);
    }
    if has_significant_value(tags, "ford") {
        return Some(WayAlertKind::Ford);
    }
    if tag(tags, "barrier")
        .is_some_and(|value| !value.trim().is_empty() && !SKIPPED_BARRIER_VALUES.contains(&value))
    {
        return Some(WayAlertKind::Barrier);
    }
    None
}

/// A tunnel, hazard or ford is a way feature only when a travel highway carries
/// it. A hazard node is not a way.
fn way_alert_kind(tags: &[(String, String)]) -> Option<WayAlertKind> {
    if !is_travel_highway(tags) {
        return None;
    }
    if tag(tags, "tunnel")
        .is_some_and(|value| !value.trim().is_empty() && !is_skipped_tunnel(value))
    {
        return Some(WayAlertKind::Tunnel);
    }
    if has_significant_value(tags, "hazard") {
        return Some(WayAlertKind::Hazard);
    }
    if has_significant_value(tags, "ford") {
        return Some(WayAlertKind::Ford);
    }
    None
}

fn has_significant_value(tags: &[(String, String)], key: &str) -> bool {
    tag(tags, key).is_some_and(|value| {
        let value = value.trim();
        !value.is_empty() && value != "no" && value != "none"
    })
}

fn is_skipped_tunnel(value: &str) -> bool {
    SKIPPED_TUNNEL_VALUES.contains(&value)
}

fn is_travel_highway(tags: &[(String, String)]) -> bool {
    tag(tags, "highway").is_some_and(|value| TRAVEL_HIGHWAY_VALUES.contains(&value))
}

fn is_rail_way(tags: &[(String, String)]) -> bool {
    tag(tags, "railway").is_some_and(|value| RAIL_WAY_VALUES.contains(&value))
}

fn is_grade_separated(tags: &[(String, String)]) -> bool {
    tag(tags, "bridge").is_some_and(|value| value != "no")
        || tag(tags, "tunnel").is_some_and(|value| value != "no")
}

fn has_non_crossing_railway_tag(tags: &[(String, String)]) -> bool {
    tag(tags, "railway").is_some_and(|value| NON_CROSSING_RAILWAY_NODE_VALUES.contains(&value))
}

fn write_way_alert_collection(
    writer: &mut impl Write,
    features: &[WayAlertFeature],
) -> std::io::Result<()> {
    writeln!(writer, "{{")?;
    writeln!(writer, "  \"type\": \"FeatureCollection\",")?;
    writeln!(writer, "  \"features\": [")?;
    for (index, feature) in features.iter().enumerate() {
        let separator = if index + 1 == features.len() { "" } else { "," };
        let geometry = if feature.element_type == "node" {
            "Point"
        } else {
            "LineString"
        };
        write!(
            writer,
            "    {{\"type\":\"Feature\",\"properties\":{{\"kind\":\"{}\",\"osmType\":\"{}\",\"osmId\":{}}},\"geometry\":{{\"type\":\"{}\",\"coordinates\":",
            feature.kind.as_str(),
            feature.element_type,
            feature.id,
            geometry,
        )?;
        if feature.element_type == "node" {
            let (lon, lat) = feature.coordinates[0];
            write!(writer, "[{lon:.7},{lat:.7}]")?;
        } else {
            write!(writer, "[")?;
            for (index, (lon, lat)) in feature.coordinates.iter().enumerate() {
                if index > 0 {
                    write!(writer, ",")?;
                }
                write!(writer, "[{lon:.7},{lat:.7}]")?;
            }
            write!(writer, "]")?;
        }
        writeln!(writer, "}}}}{separator}")?;
    }
    writeln!(writer, "  ]")?;
    writeln!(writer, "}}")?;
    Ok(())
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
    let keys: &[&str] = if BRAND_TAG_KEYS.iter().any(|key| tag(tags, key).is_some()) {
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
            vec![
                "Zolochiv".to_owned(),
                "Złoczów".to_owned(),
                "Золочев".to_owned()
            ],
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

    fn tags(pairs: &[(&str, &str)]) -> Vec<(String, String)> {
        pairs
            .iter()
            .map(|(key, value)| ((*key).to_owned(), (*value).to_owned()))
            .collect()
    }

    #[test]
    fn a_tram_crossing_and_a_level_crossing_are_both_railway_crossings() {
        assert_eq!(
            node_alert_kind(&tags(&[("railway", "level_crossing")])),
            Some(WayAlertKind::RailwayCrossing),
        );
        assert_eq!(
            node_alert_kind(&tags(&[("railway", "tram_level_crossing")])),
            Some(WayAlertKind::RailwayCrossing),
        );
        assert_eq!(
            node_alert_kind(&tags(&[("railway", "rail")])),
            None,
            "a rail node alone is not a crossing",
        );
    }

    #[test]
    fn a_node_with_several_alert_tags_is_one_feature_strongest_first() {
        assert_eq!(
            node_alert_kind(&tags(&[("ford", "yes"), ("barrier", "gate")])),
            Some(WayAlertKind::Ford),
        );
        assert_eq!(
            node_alert_kind(&tags(&[("hazard", "flooding"), ("ford", "yes")])),
            Some(WayAlertKind::Hazard),
        );
        assert_eq!(node_alert_kind(&tags(&[("hazard", "no")])), None);
        assert_eq!(node_alert_kind(&tags(&[("ford", "no")])), None);
    }

    #[test]
    fn bike_passable_barriers_are_not_a_warning() {
        assert_eq!(
            node_alert_kind(&tags(&[("barrier", "gate")])),
            Some(WayAlertKind::Barrier),
        );
        assert_eq!(
            node_alert_kind(&tags(&[("barrier", "lift_gate")])),
            Some(WayAlertKind::Barrier),
        );
        for skipped in ["bollard", "kerb", "entrance", "height_restrictor", "no"] {
            assert_eq!(
                node_alert_kind(&tags(&[("barrier", skipped)])),
                None,
                "{skipped} must not warn a bike",
            );
        }
    }

    #[test]
    fn a_tunnel_needs_a_travel_highway_and_is_not_a_building_or_a_culvert() {
        assert_eq!(
            way_alert_kind(&tags(&[("highway", "path"), ("tunnel", "yes")])),
            Some(WayAlertKind::Tunnel),
        );
        assert_eq!(
            way_alert_kind(&tags(&[("highway", "footway"), ("tunnel", "covered")])),
            Some(WayAlertKind::Tunnel),
        );
        for skipped in [
            "building_passage",
            "culvert",
            "floodway",
            "canal",
            "pipe",
            "no",
        ] {
            assert_eq!(
                way_alert_kind(&tags(&[("highway", "path"), ("tunnel", skipped)])),
                None,
                "{skipped} is not a passage a rider goes through",
            );
        }
        assert_eq!(
            way_alert_kind(&tags(&[("highway", "construction"), ("tunnel", "yes")])),
            None,
        );
        assert_eq!(
            way_alert_kind(&tags(&[("waterway", "stream"), ("tunnel", "culvert")])),
            None,
        );
    }

    #[test]
    fn a_hazard_or_a_ford_is_a_way_feature_on_a_travel_highway() {
        assert_eq!(
            way_alert_kind(&tags(&[("highway", "secondary"), ("hazard", "turns")])),
            Some(WayAlertKind::Hazard),
        );
        assert_eq!(
            way_alert_kind(&tags(&[("highway", "track"), ("ford", "yes")])),
            Some(WayAlertKind::Ford),
        );
        assert_eq!(
            way_alert_kind(&tags(&[("hazard", "yes"), ("waterway", "stream")])),
            None,
        );
    }

    #[test]
    fn the_fixture_pbf_produces_the_expected_way_alert_features() {
        let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
        let root = manifest.join("../../..");
        let out = std::env::temp_dir().join(format!(
            "osm-extract-way-alerts-{}.geojson",
            std::process::id()
        ));
        let options = Options {
            pbf: root.join("test-data/regions/fixture-mini/way-alerts.osm.pbf"),
            out: out.clone(),
            bbox: Some(parse_bbox("23.940,49.940,23.960,49.960").expect("fixture bbox")),
            places: true,
            addresses: true,
            way_alerts: true,
            route_relations: false,
        };
        let stats = run_way_alerts(&options).expect("way-alert extraction");
        let text = std::fs::read_to_string(&out).expect("read the component");
        std::fs::remove_file(&out).ok();

        assert_eq!(stats.railway_crossings, 2, "a tagged and a shared crossing");
        assert_eq!(stats.shared_crossings, 1);
        assert_eq!(stats.tunnels, 1);
        assert_eq!(stats.hazards, 3, "two nodes and one way");
        assert_eq!(stats.fords, 1);
        assert_eq!(stats.barriers, 1);
        assert_eq!(stats.skipped_tunnels, 2);
        assert_eq!(stats.skipped_barriers, 1);

        for present in [9001, 9002, 9003, 9004, 9005, 9007, 9010, 9011] {
            assert!(text.contains(&format!("\"osmId\":{present}")), "{present}");
        }
        for absent in [9006, 9012, 9013] {
            assert!(
                !text.contains(&format!("\"osmId\":{absent}")),
                "{absent} must be skipped",
            );
        }
        assert!(text.contains("\"kind\":\"tunnel\",\"osmType\":\"way\",\"osmId\":9002"));
        assert!(text.contains("\"kind\":\"hazard\",\"osmType\":\"way\",\"osmId\":9011"));
        assert!(text.contains("\"type\":\"LineString\""));
        assert!(text.contains("\"type\":\"Point\""));
    }

    #[test]
    fn a_proposed_or_retired_route_is_skipped() {
        assert!(route_state_is_skipped(&vec![(
            "state".to_owned(),
            "proposed".to_owned()
        )]));
        assert!(route_state_is_skipped(&vec![(
            "proposed".to_owned(),
            "yes".to_owned()
        )]));
        assert!(!route_state_is_skipped(&vec![(
            "state".to_owned(),
            "".to_owned()
        )]));
        assert!(!route_state_is_skipped(&vec![(
            "proposed".to_owned(),
            "no".to_owned()
        )]));
    }

    #[test]
    fn a_relation_name_with_quotes_is_json_escaped() {
        let mut buffer = Vec::new();
        write_json_string(&mut buffer, "Шлях \"Гора\"\n").expect("write");
        assert_eq!(
            String::from_utf8(buffer).expect("utf8"),
            "\"Шлях \\\"Гора\\\"\\n\""
        );
    }

    #[test]
    fn the_fixture_pbf_produces_the_expected_hiking_relations() {
        let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
        let root = manifest.join("../../..");
        let out = std::env::temp_dir().join(format!(
            "osm-extract-route-relations-{}.json",
            std::process::id()
        ));
        let options = Options {
            pbf: root.join("test-data/regions/fixture-mini/hiking-relations.osm.pbf"),
            out: out.clone(),
            bbox: Some(parse_bbox("23.940,49.940,23.960,49.960").expect("fixture bbox")),
            places: true,
            addresses: true,
            way_alerts: false,
            route_relations: true,
        };
        let stats = run_route_relations(&options).expect("route relation extraction");
        let text = std::fs::read_to_string(&out).expect("read the component");
        std::fs::remove_file(&out).ok();

        assert_eq!(stats.relations, 3);
        assert_eq!(stats.ways, 3);
        assert_eq!(stats.skipped_route, 2, "one bus and one foot route");
        assert_eq!(stats.skipped_state, 1);
        assert_eq!(stats.skipped_no_members, 1);
        assert_eq!(stats.skipped_missing_ways, 0);

        for present in [9101, 9102, 9107] {
            assert!(
                text.contains(&format!(
                    "\"id\":{present},\"type\":\"route\",\"route\":\"hiking\""
                )),
                "{present}"
            );
        }
        for absent in [9103, 9104, 9105, 9106] {
            assert!(
                !text.contains(&format!("\"id\":{absent},")),
                "{absent} must be skipped"
            );
        }
        assert!(text.contains("\"osmcSymbol\":\"red:white:red_bar\""));
        assert!(text.contains("\"network\":\"rwn\""));
        assert!(
            text.contains(
                "\"members\":[{\"way\":896757861,\"role\":\"\"},{\"way\":306496773,\"role\":\"\"}]"
            ),
            "member order must be the relation's order: {text}"
        );
    }
}
