//! The negative validation rules the reader enforces on untrusted components.
//!
//! One test per rule, each naming it and asserting the typed failure: a
//! symlinked component, a declared size above the reader's cap, a non-finite
//! coordinate, a coordinate outside WGS84, a position with three values and a
//! duplicated relation id. A document that breaks a rule is
//! [`RegionReadError::CorruptData`], never a partial value.

use std::path::Path;

use grass_regions::{AlertHit, LatLon, RegionReadError, RegionReader, RegionRelations};

/// The reader's cap on a component's declared size; the Kotlin mount checks the
/// same bound before it passes the file here.
const MAX_COMPONENT_BYTES: i64 = 64 * 1024 * 1024;
const OVERSIZE_BYTES: usize = MAX_COMPONENT_BYTES as usize + 1;
const CORRIDOR_METERS: f64 = 30.0;
const START: LatLon = LatLon {
    latitude: 49.9490,
    longitude: 23.9430,
};
const END: LatLon = LatLon {
    latitude: 49.9490,
    longitude: 23.9530,
};

fn point_alert(coordinates: &str) -> String {
    format!(
        concat!(
            "{{\"type\":\"FeatureCollection\",\"features\":[",
            "{{\"type\":\"Feature\",\"properties\":",
            "{{\"kind\":\"ford\",\"osmType\":\"node\",\"osmId\":1}},",
            "\"geometry\":{{\"type\":\"Point\",\"coordinates\":{}}}}}]}}"
        ),
        coordinates
    )
}

fn line_alert(coordinates: &str) -> String {
    format!(
        concat!(
            "{{\"type\":\"FeatureCollection\",\"features\":[",
            "{{\"type\":\"Feature\",\"properties\":",
            "{{\"kind\":\"hazard\",\"osmType\":\"way\",\"osmId\":1}},",
            "\"geometry\":{{\"type\":\"LineString\",\"coordinates\":{}}}}}]}}"
        ),
        coordinates
    )
}

fn relations_with_node(coordinates: &str) -> String {
    format!(
        concat!(
            "{{\"format\":\"grass-maps-route-relations\",\"version\":1,",
            "\"ways\":[{{\"id\":1,\"coordinates\":[[23.94,49.94],[23.95,49.95]]}}],",
            "\"nodes\":[{{\"id\":5,\"network\":\"rcn\",\"ref\":\"7\",",
            "\"coordinates\":{}}}],",
            "\"relations\":[{{\"id\":9,\"type\":\"route\",\"route\":\"bicycle\",",
            "\"members\":[{{\"way\":1,\"role\":\"\"}}]}}]}}"
        ),
        coordinates
    )
}

/// A valid collection padded with whitespace to exactly one byte over the cap,
/// so only the cap can reject it.
fn oversize_alert() -> String {
    let base = point_alert("[23.9431,49.949]");
    let padding = OVERSIZE_BYTES - base.len();
    let mut text = String::with_capacity(OVERSIZE_BYTES);
    text.push_str(&base[..base.len() - 1]);
    text.push_str(&" ".repeat(padding));
    text.push('}');
    text
}

fn write(directory: &Path, name: &str, text: &str) -> (String, i64) {
    let file = directory.join(name);
    std::fs::write(&file, text.as_bytes()).expect("write the fixture");
    (file.to_string_lossy().into_owned(), text.len() as i64)
}

fn alerts(directory: &Path, text: &str) -> Result<Vec<AlertHit>, RegionReadError> {
    let (path, bytes) = write(directory, "way-alerts.geojson", text);
    RegionReader::new().way_alert_hits(path, bytes, vec![START, END], 0.0, CORRIDOR_METERS)
}

fn relations(directory: &Path, text: &str) -> Result<RegionRelations, RegionReadError> {
    let (path, bytes) = write(directory, "relations.json", text);
    RegionReader::new().route_relations(path, bytes)
}

#[test]
fn a_symlinked_component_is_corrupt_data() {
    let directory = tempfile::tempdir().expect("temp dir");
    let (target, bytes) = write(
        directory.path(),
        "target.geojson",
        &point_alert("[23.9431,49.949]"),
    );
    let link = directory.path().join("way-alerts.geojson");
    std::os::unix::fs::symlink(&target, &link).expect("symlink the component");
    let path = link.to_string_lossy().into_owned();

    let error = RegionReader::new()
        .way_alert_hits(path.clone(), bytes, vec![START, END], 0.0, CORRIDOR_METERS)
        .expect_err("a symlink is not a component file");
    assert_eq!(RegionReadError::CorruptData, error);
    assert_eq!(
        Err(RegionReadError::CorruptData),
        RegionReader::new().route_relations(path, bytes)
    );
}

#[test]
fn an_oversize_component_is_corrupt_data() {
    let directory = tempfile::tempdir().expect("temp dir");
    let text = oversize_alert();
    assert_eq!(OVERSIZE_BYTES, text.len(), "the fixture is one byte over");
    let (path, bytes) = write(directory.path(), "way-alerts.geojson", &text);
    assert_eq!(MAX_COMPONENT_BYTES + 1, bytes);

    assert_eq!(
        Err(RegionReadError::CorruptData),
        RegionReader::new().way_alert_hits(path, bytes, vec![START, END], 0.0, CORRIDOR_METERS)
    );
}

#[test]
fn a_non_finite_coordinate_is_corrupt_data() {
    let directory = tempfile::tempdir().expect("temp dir");

    // JSON cannot spell a non-finite number, so the document quotes one. Both
    // parsers accept a quoted coordinate, so the value reaches the coordinate
    // rule instead of failing as malformed JSON.
    assert_eq!(
        Err(RegionReadError::CorruptData),
        alerts(directory.path(), &point_alert("[\"NaN\",49.949]"))
    );
    assert_eq!(
        Err(RegionReadError::CorruptData),
        relations(directory.path(), &relations_with_node("[\"inf\",49.94]"))
    );
}

#[test]
fn an_out_of_range_coordinate_is_corrupt_data() {
    let directory = tempfile::tempdir().expect("temp dir");

    for latitude in ["90.001", "-90.001"] {
        assert_eq!(
            Err(RegionReadError::CorruptData),
            alerts(
                directory.path(),
                &point_alert(&format!("[23.9431,{latitude}]"))
            ),
            "latitude {latitude}"
        );
    }
    for longitude in ["180.001", "-180.001"] {
        assert_eq!(
            Err(RegionReadError::CorruptData),
            relations(
                directory.path(),
                &relations_with_node(&format!("[{longitude},49.94]"))
            ),
            "longitude {longitude}"
        );
    }
}

#[test]
fn a_three_value_position_is_corrupt_data() {
    let directory = tempfile::tempdir().expect("temp dir");

    assert_eq!(
        Err(RegionReadError::CorruptData),
        alerts(directory.path(), &point_alert("[23.9431,49.949,120]")),
        "a point position"
    );
    assert_eq!(
        Err(RegionReadError::CorruptData),
        alerts(
            directory.path(),
            &line_alert("[[23.9431,49.949,120],[23.9432,49.949,120]]")
        ),
        "a line position"
    );
    assert_eq!(
        Err(RegionReadError::CorruptData),
        relations(directory.path(), &relations_with_node("[23.94,49.94,120]")),
        "a node position"
    );
}

#[test]
fn duplicate_relation_ids_are_corrupt_data() {
    let directory = tempfile::tempdir().expect("temp dir");
    let text = concat!(
        "{\"format\":\"grass-maps-route-relations\",\"version\":1,",
        "\"ways\":[{\"id\":1,\"coordinates\":[[23.94,49.94],[23.95,49.95]]}],",
        "\"relations\":[",
        "{\"id\":9,\"type\":\"route\",\"route\":\"bicycle\",\"members\":[{\"way\":1}]},",
        "{\"id\":9,\"type\":\"route\",\"route\":\"hiking\",\"members\":[{\"way\":1}]}]}"
    );

    assert_eq!(
        Err(RegionReadError::CorruptData),
        relations(directory.path(), text)
    );
}
