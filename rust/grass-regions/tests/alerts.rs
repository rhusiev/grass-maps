//! The way-alert corridor query through the uniffi reader, against the
//! committed region components.

use std::path::PathBuf;

use grass_regions::{AlertHit, AlertKind, LatLon, OsmElementType, RegionReadError, RegionReader};

const START: LatLon = LatLon {
    latitude: 49.9490,
    longitude: 23.9430,
};
const END: LatLon = LatLon {
    latitude: 49.9490,
    longitude: 23.9530,
};
const CORRIDOR_METERS: f64 = 30.0;

fn component(region: &str, file: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../test-data/regions")
        .join(region)
        .join(file)
}

fn route_of(points: &[LatLon]) -> Vec<LatLon> {
    points.to_vec()
}

fn file_component(path: &PathBuf) -> (String, i64) {
    let bytes = std::fs::metadata(path).expect("component metadata").len() as i64;
    (path.to_string_lossy().into_owned(), bytes)
}

fn hits_of(region: &str, file: &str, corridor_meters: f64, route: &[LatLon]) -> Vec<AlertHit> {
    let (path, bytes) = file_component(&component(region, file));
    RegionReader::new()
        .way_alert_hits(path, bytes, route_of(route), 0.0, corridor_meters)
        .expect("the fixture query failed")
}

#[test]
fn the_fixture_carries_the_crossing_the_tunnel_and_the_hazard() {
    let hits = hits_of(
        "fixture-mini/2026-11-03-1",
        "way-alerts.geojson",
        CORRIDOR_METERS,
        &[START, END],
    );

    let kinds: Vec<AlertKind> = hits.iter().map(|hit| hit.feature.kind).collect();
    assert_eq!(
        vec![
            AlertKind::RailwayCrossing,
            AlertKind::Tunnel,
            AlertKind::Hazard
        ],
        kinds
    );
    let ids: Vec<i64> = hits.iter().map(|hit| hit.feature.osm_id).collect();
    assert_eq!(vec![9001, 9002, 9003], ids);
    assert_eq!(OsmElementType::Way, hits[1].feature.element_type);
    assert_eq!(2, hits[1].feature.geometry.len());
}

#[test]
fn the_hits_are_ordered_by_their_distance_along_the_route() {
    let hits = hits_of(
        "fixture-mini/2026-11-03-1",
        "way-alerts.geojson",
        CORRIDOR_METERS,
        &[START, END],
    );

    assert!(hits[0].along_route_meters < hits[1].along_route_meters);
    assert!(hits[1].along_route_meters < hits[2].along_route_meters);
    assert!(hits.iter().all(|hit| hit.distance_from_route_meters < 1.0));
}

#[test]
fn a_feature_outside_the_corridor_is_not_returned() {
    let hits = hits_of(
        "fixture-mini/2026-11-03-1",
        "way-alerts.geojson",
        CORRIDOR_METERS,
        &[START, END],
    );

    assert!(
        hits.iter().all(|hit| hit.feature.osm_id != 9004),
        "the hazard 167 m north of the line is outside 30 m"
    );
}

#[test]
fn a_wider_corridor_includes_the_off_route_hazard() {
    let hits = hits_of(
        "fixture-mini/2026-11-03-1",
        "way-alerts.geojson",
        200.0,
        &[START, END],
    );

    let hit = hits
        .iter()
        .find(|hit| hit.feature.osm_id == 9004)
        .expect("the off-corridor hazard is inside 200 m");
    assert!((hit.distance_from_route_meters - 166.0).abs() < 3.0);
}

#[test]
fn a_non_positive_corridor_is_invalid_input() {
    let (path, bytes) = file_component(&component(
        "fixture-mini/2026-11-03-1",
        "way-alerts.geojson",
    ));
    let error = RegionReader::new()
        .way_alert_hits(path, bytes, vec![START, END], 0.0, 0.0)
        .expect_err("a zero corridor is invalid");
    assert_eq!(RegionReadError::InvalidInput, error);
}

#[test]
fn a_short_route_has_no_hits_and_reads_nothing() {
    let reader = RegionReader::new();
    let hits = reader
        .way_alert_hits(
            "/does/not/exist".to_owned(),
            100,
            vec![START],
            0.0,
            CORRIDOR_METERS,
        )
        .expect("a short route answers before any read");
    assert!(hits.is_empty());
}

#[test]
fn a_declared_size_that_does_not_match_the_file_is_corrupt_data() {
    let (path, bytes) = file_component(&component(
        "fixture-mini/2026-11-03-1",
        "way-alerts.geojson",
    ));
    let error = RegionReader::new()
        .way_alert_hits(path, bytes + 1, vec![START, END], 0.0, CORRIDOR_METERS)
        .expect_err("a wrong size is corrupt data");
    assert_eq!(RegionReadError::CorruptData, error);
}

#[test]
fn a_feature_collection_with_no_features_reports_none() {
    let directory = tempfile::tempdir().expect("temp dir");
    let path = directory.path().join("way-alerts.geojson");
    std::fs::write(&path, r#"{"type":"FeatureCollection","features":[]}"#).expect("write fixture");
    let hits = RegionReader::new()
        .way_alert_hits(
            path.to_string_lossy().into_owned(),
            std::fs::metadata(&path).expect("metadata").len() as i64,
            vec![START, END],
            0.0,
            CORRIDOR_METERS,
        )
        .expect("an empty collection is valid");
    assert!(hits.is_empty());
}

#[test]
fn a_tunnel_must_be_a_way() {
    let directory = tempfile::tempdir().expect("temp dir");
    let path = directory.path().join("way-alerts.geojson");
    std::fs::write(
        &path,
        r#"{"type":"FeatureCollection","features":[
            {"type":"Feature","properties":{"kind":"tunnel","osmType":"node","osmId":1},
             "geometry":{"type":"Point","coordinates":[23.9431,49.949]}}
        ]}"#,
    )
    .expect("write fixture");
    let error = RegionReader::new()
        .way_alert_hits(
            path.to_string_lossy().into_owned(),
            std::fs::metadata(&path).expect("metadata").len() as i64,
            vec![START, END],
            0.0,
            CORRIDOR_METERS,
        )
        .expect_err("a point tunnel is corrupt");
    assert_eq!(RegionReadError::CorruptData, error);
}

#[test]
fn the_lviv_extract_answers_its_three_probes() {
    let component = component("lviv-way-alerts", "way-alerts.geojson");
    let bytes = std::fs::metadata(&component).expect("metadata").len() as i64;
    let path = component.to_string_lossy().into_owned();
    let reader = RegionReader::new();

    let crossing = reader
        .way_alert_hits(
            path.clone(),
            bytes,
            vec![
                LatLon {
                    latitude: 49.8432638,
                    longitude: 24.0226514,
                },
                LatLon {
                    latitude: 49.8432638,
                    longitude: 24.0230514,
                },
            ],
            0.0,
            CORRIDOR_METERS,
        )
        .expect("crossing query")
        .into_iter()
        .find(|hit| hit.feature.osm_id == 30_508_730)
        .expect("the railway crossing");
    assert_eq!(AlertKind::RailwayCrossing, crossing.feature.kind);

    let tunnel = reader
        .way_alert_hits(
            path.clone(),
            bytes,
            vec![
                LatLon {
                    latitude: 49.8429774,
                    longitude: 24.0223819,
                },
                LatLon {
                    latitude: 49.8431053,
                    longitude: 24.0224582,
                },
            ],
            0.0,
            CORRIDOR_METERS,
        )
        .expect("tunnel query")
        .into_iter()
        .find(|hit| hit.feature.kind == AlertKind::Tunnel)
        .expect("the tunnel");
    assert_eq!(770_843_895, tunnel.feature.osm_id);
    assert_eq!(2, tunnel.feature.geometry.len());

    let hazard = reader
        .way_alert_hits(
            path,
            bytes,
            vec![
                LatLon {
                    latitude: 49.8371076,
                    longitude: 24.0218212,
                },
                LatLon {
                    latitude: 49.8368417,
                    longitude: 24.021362,
                },
            ],
            0.0,
            CORRIDOR_METERS,
        )
        .expect("hazard query")
        .into_iter()
        .find(|hit| hit.feature.kind == AlertKind::Hazard)
        .expect("the hazard");
    assert_eq!(160_911_208, hazard.feature.osm_id);
}

#[test]
fn the_engine_names_the_rust_crate() {
    assert!(
        RegionReader::new()
            .engine_name()
            .starts_with("grass-regions ")
    );
}
