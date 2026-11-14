//! The route-relations query through the uniffi reader, against the committed
//! region components.

use std::path::PathBuf;

use grass_regions::{RegionReadError, RegionReader, RouteKind};

fn component(region: &str, file: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../test-data/regions")
        .join(region)
        .join(file)
}

fn read(region: &str, file: &str) -> grass_regions::RegionRelations {
    let path = component(region, file);
    let bytes = std::fs::metadata(&path).expect("component metadata").len() as i64;
    RegionReader::new()
        .route_relations(path.to_string_lossy().into_owned(), bytes)
        .expect("the relation component is corrupt")
}

fn read_text(text: &str) -> Result<grass_regions::RegionRelations, RegionReadError> {
    let directory = tempfile::tempdir().expect("temp dir");
    let path = directory.path().join("relations.json");
    std::fs::write(&path, text).expect("write fixture");
    RegionReader::new().route_relations(
        path.to_string_lossy().into_owned(),
        std::fs::metadata(&path).expect("metadata").len() as i64,
    )
}

#[test]
fn the_fixture_carries_its_hiking_and_cycling_relations() {
    let relations = read("fixture-mini/2026-11-03-1", "relations.json");

    let ids: Vec<i64> = relations
        .relations
        .iter()
        .map(|relation| relation.id)
        .collect();
    assert_eq!(vec![9101, 9102, 9107, 9108, 9109], ids);
    let member_counts: Vec<usize> = relations
        .relations
        .iter()
        .map(|relation| relation.members.len())
        .collect();
    assert_eq!(vec![2, 1, 1, 1, 1], member_counts);
    let networks: Vec<&str> = relations
        .relations
        .iter()
        .map(|relation| relation.network.as_str())
        .collect();
    assert_eq!(vec!["lwn", "rwn", "nwn", "lcn", "ncn"], networks);
    assert_eq!(3, relations.ways.len());
    assert_eq!(5, relations.nodes.len());
    let references: Vec<&str> = relations
        .nodes
        .iter()
        .map(|node| node.reference.as_str())
        .collect();
    assert_eq!(vec!["7", "48", "47", "13", "12"], references);
    assert_eq!("rcn", relations.nodes[0].network);
    assert_eq!(9108, relations.relations[3].id);
    assert_eq!(
        Some("blue:white:blue_rectangle".to_owned()),
        relations.relations[3].osmc_symbol
    );
}

#[test]
fn a_relation_keeps_its_member_order_and_way_geometry() {
    let relations = read("fixture-mini/2026-11-03-1", "relations.json");

    let first = relations
        .relations
        .iter()
        .find(|relation| relation.id == 9101)
        .expect("relation 9101");
    let members: Vec<i64> = first.members.iter().map(|member| member.way_id).collect();
    assert_eq!(vec![896_757_861, 306_496_773], members);
    assert_eq!(Some("Лісова Гряда".to_owned()), first.name);
    assert_eq!(Some("red:white:red_bar".to_owned()), first.osmc_symbol);
    let way = relations
        .ways
        .iter()
        .find(|way| way.id == 896_757_861)
        .expect("member way");
    assert_eq!(83, way.points.len());
    let other_way = relations
        .ways
        .iter()
        .find(|way| way.id == 306_496_773)
        .expect("second member way");
    assert_eq!(66, other_way.points.len());
}

#[test]
fn the_lviv_hiking_probe_keeps_its_relations() {
    let relations = read("lviv-hiking-relations", "relations.json");

    assert_eq!(6, relations.relations.len());
    assert_eq!(61, relations.ways.len());
    let ids: Vec<i64> = relations
        .relations
        .iter()
        .map(|relation| relation.id)
        .collect();
    assert_eq!(
        vec![
            4_868_853, 17_975_954, 19_709_971, 19_839_379, 19_839_382, 19_839_384
        ],
        ids
    );
    assert!(
        relations
            .relations
            .iter()
            .all(|relation| relation.route == RouteKind::Hiking && relation.network == "lwn")
    );
    let doob = relations
        .relations
        .iter()
        .find(|relation| relation.id == 19_709_971)
        .expect("the Doob relation");
    assert_eq!(Some("№501 Сколе – Скелі Довбуша".to_owned()), doob.name);
    assert_eq!(
        Some("orange:white:orange_bar:501:black".to_owned()),
        doob.osmc_symbol
    );
    assert_eq!(37, doob.members.len());
}

#[test]
fn a_component_without_the_nodes_array_still_decodes() {
    let relations = read_text(
        r#"{"format":"grass-maps-route-relations","version":1,
            "ways":[{"id":1,"coordinates":[[23.94,49.94],[23.95,49.95]]}],
            "relations":[{"id":9,"type":"route","route":"bicycle","network":"rcn",
                          "members":[{"way":1,"role":""}]}]}"#,
    )
    .expect("a component without nodes is valid");
    assert_eq!(1, relations.relations.len());
    assert!(relations.nodes.is_empty());
}

#[test]
fn a_header_without_ways_is_corrupt_data() {
    assert_eq!(
        Err(RegionReadError::CorruptData),
        read_text(r#"{"format":"grass-maps-route-relations","version":1}"#)
    );
}

#[test]
fn a_member_naming_an_unknown_way_is_corrupt_data() {
    assert_eq!(
        Err(RegionReadError::CorruptData),
        read_text(
            r#"{"format":"grass-maps-route-relations","version":1,
                "ways":[{"id":1,"coordinates":[[23.94,49.94],[23.95,49.95]]}],
                "relations":[{"id":9,"route":"hiking","members":[{"way":2,"role":""}]}]}"#,
        )
    );
}

#[test]
fn a_node_with_an_unknown_network_is_corrupt_data() {
    assert_eq!(
        Err(RegionReadError::CorruptData),
        read_text(
            r#"{"format":"grass-maps-route-relations","version":1,
                "ways":[{"id":1,"coordinates":[[23.94,49.94],[23.95,49.95]]}],
                "nodes":[{"id":5,"network":"iwn","ref":"7","coordinates":[23.94,49.94]}],
                "relations":[{"id":9,"route":"bicycle","members":[{"way":1}]}]}"#,
        )
    );
}
