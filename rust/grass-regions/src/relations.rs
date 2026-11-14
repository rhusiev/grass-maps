//! The `relations.json` parser.
//!
//! The component is written by `tools/pipeline/osm-extract --route-relations`
//! and holds the region's hiking and cycling route relations, the geometry of
//! every member way, and the numbered junctions of the cycling node networks.
//! Any malformed member, missing way, unknown route kind or unknown format
//! version is corrupt data; a caller never receives a partially built value.
//!
//! Every rule is the one `RouteRelationsJson` enforced, including which fields
//! must be JSON numbers and which may be quoted.

use std::collections::HashSet;

use serde_json::{Map, Value};

use crate::error::RegionReadError;
use crate::json;
use crate::model::{
    LatLon, NetworkNode, RegionRelation, RegionRelations, RelationMember, RelationWay, RouteKind,
};

const FORMAT: &str = "grass-maps-route-relations";
const VERSION: i64 = 1;
const MIN_WAY_POINTS: usize = 2;
const NODE_NETWORKS: [&str; 4] = ["icn", "ncn", "rcn", "lcn"];

/// Parses the component.
pub fn parse(bytes: &[u8]) -> Result<RegionRelations, RegionReadError> {
    let root: Value = serde_json::from_slice(bytes).map_err(|_| RegionReadError::CorruptData)?;
    let object = root.as_object().ok_or(RegionReadError::CorruptData)?;
    if json::string(object, "format").as_deref() != Some(FORMAT)
        || json::strict_integer(object, "version") != Some(VERSION)
    {
        return Err(RegionReadError::CorruptData);
    }
    let way_elements = array(object, "ways")?;
    let node_elements = optional_array(object, "nodes")?;
    let relation_elements = array(object, "relations")?;
    let ways = ways(way_elements)?;
    let way_ids: HashSet<i64> = ways.iter().map(|way| way.id).collect();
    Ok(RegionRelations {
        nodes: nodes(node_elements)?,
        relations: relations(relation_elements, &way_ids)?,
        ways,
    })
}

fn array<'a>(object: &'a Map<String, Value>, key: &str) -> Result<&'a Vec<Value>, RegionReadError> {
    object
        .get(key)
        .and_then(Value::as_array)
        .ok_or(RegionReadError::CorruptData)
}

fn optional_array<'a>(
    object: &'a Map<String, Value>,
    key: &str,
) -> Result<&'a [Value], RegionReadError> {
    match object.get(key) {
        None => Ok(&[]),
        Some(value) => value
            .as_array()
            .map(Vec::as_slice)
            .ok_or(RegionReadError::CorruptData),
    }
}

fn ways(elements: &[Value]) -> Result<Vec<RelationWay>, RegionReadError> {
    let mut ways = Vec::with_capacity(elements.len());
    let mut ids = HashSet::with_capacity(elements.len());
    for element in elements {
        let way = element.as_object().ok_or(RegionReadError::CorruptData)?;
        let id = json::strict_integer(way, "id").ok_or(RegionReadError::CorruptData)?;
        if id <= 0 || !ids.insert(id) {
            return Err(RegionReadError::CorruptData);
        }
        let coordinates = way
            .get("coordinates")
            .and_then(Value::as_array)
            .ok_or(RegionReadError::CorruptData)?;
        if coordinates.len() < MIN_WAY_POINTS {
            return Err(RegionReadError::CorruptData);
        }
        let points: Vec<LatLon> = coordinates
            .iter()
            .map(|point| json::coordinate(Some(point)).ok_or(RegionReadError::CorruptData))
            .collect::<Result<_, _>>()?;
        ways.push(RelationWay { id, points });
    }
    Ok(ways)
}

fn nodes(elements: &[Value]) -> Result<Vec<NetworkNode>, RegionReadError> {
    let mut nodes = Vec::with_capacity(elements.len());
    let mut ids = HashSet::with_capacity(elements.len());
    for element in elements {
        let node = element.as_object().ok_or(RegionReadError::CorruptData)?;
        let id = json::strict_integer(node, "id").ok_or(RegionReadError::CorruptData)?;
        if id <= 0 || !ids.insert(id) {
            return Err(RegionReadError::CorruptData);
        }
        let network = json::string(node, "network").ok_or(RegionReadError::CorruptData)?;
        if !NODE_NETWORKS.contains(&network.as_str()) {
            return Err(RegionReadError::CorruptData);
        }
        let reference = json::string(node, "ref").ok_or(RegionReadError::CorruptData)?;
        let point =
            json::coordinate(node.get("coordinates")).ok_or(RegionReadError::CorruptData)?;
        nodes.push(NetworkNode {
            id,
            point,
            network,
            reference,
        });
    }
    Ok(nodes)
}

fn relations(
    elements: &[Value],
    way_ids: &HashSet<i64>,
) -> Result<Vec<RegionRelation>, RegionReadError> {
    let mut relations = Vec::with_capacity(elements.len());
    let mut ids = HashSet::with_capacity(elements.len());
    for element in elements {
        let relation = element.as_object().ok_or(RegionReadError::CorruptData)?;
        let id = json::strict_integer(relation, "id").ok_or(RegionReadError::CorruptData)?;
        if id <= 0 || !ids.insert(id) {
            return Err(RegionReadError::CorruptData);
        }
        if json::string(relation, "type").as_deref() != Some("route") {
            return Err(RegionReadError::CorruptData);
        }
        let route = route(relation)?;
        let members = members(relation, way_ids)?;
        let distance = json::strict_number(relation, "distanceMeters");
        if distance.is_some_and(|value| !value.is_finite() || value < 0.0) {
            return Err(RegionReadError::CorruptData);
        }
        relations.push(RegionRelation {
            id,
            route,
            network: json::string(relation, "network").unwrap_or_default(),
            reference: json::string(relation, "ref"),
            name: json::string(relation, "name"),
            osmc_symbol: json::string(relation, "osmcSymbol"),
            colour: json::string(relation, "colour"),
            distance_meters: distance,
            members,
        });
    }
    Ok(relations)
}

fn route(relation: &Map<String, Value>) -> Result<RouteKind, RegionReadError> {
    match json::string(relation, "route").as_deref() {
        Some("hiking") => Ok(RouteKind::Hiking),
        Some("bicycle") => Ok(RouteKind::Cycling),
        _ => Err(RegionReadError::CorruptData),
    }
}

fn members(
    relation: &Map<String, Value>,
    way_ids: &HashSet<i64>,
) -> Result<Vec<RelationMember>, RegionReadError> {
    let elements = array(relation, "members")?;
    if elements.is_empty() {
        return Err(RegionReadError::CorruptData);
    }
    elements
        .iter()
        .map(|element| {
            let member = element.as_object().ok_or(RegionReadError::CorruptData)?;
            let way_id = json::strict_integer(member, "way").ok_or(RegionReadError::CorruptData)?;
            if way_id <= 0 || !way_ids.contains(&way_id) {
                return Err(RegionReadError::CorruptData);
            }
            Ok(RelationMember {
                way_id,
                role: json::string(member, "role"),
            })
        })
        .collect()
}
