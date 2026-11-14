//! The `way-alerts.geojson` parser.
//!
//! The document is a GeoJSON `FeatureCollection`. A feature carries its kind,
//! its OSM element identity and one point (a node) or a line (a way). Every
//! rule here is the one the Kotlin reader enforced: a point feature has
//! exactly one point, a way feature at least two, a tunnel is always a way, an
//! id is positive, and a coordinate is finite and inside the WGS84 range.
//!
//! The document is decoded into typed values rather than a JSON tree: the
//! Lviv extract is 18 009 features, and a value tree costs more than the
//! query it feeds.

use serde::Deserialize as DeriveDeserialize;
use serde::de::{self, Deserialize, Deserializer, IgnoredAny, SeqAccess, Visitor};
use std::fmt;

use crate::error::RegionReadError;
use crate::json;
use crate::model::{AlertFeature, AlertKind, LatLon, OsmElementType};

const FEATURE_COLLECTION: &str = "FeatureCollection";
const FEATURE: &str = "Feature";
const POINT: &str = "Point";
const LINE_STRING: &str = "LineString";
const MIN_WAY_POINTS: usize = 2;

/// Parses the component. Any feature that breaks the schema fails the whole
/// component, so a caller never receives a partial list.
pub fn parse(bytes: &[u8]) -> Result<Vec<AlertFeature>, RegionReadError> {
    let document: Document =
        serde_json::from_slice(bytes).map_err(|_| RegionReadError::CorruptData)?;
    if document.kind != FEATURE_COLLECTION {
        return Err(RegionReadError::CorruptData);
    }
    document.features.into_iter().map(feature).collect()
}

#[derive(DeriveDeserialize)]
struct Document {
    #[serde(rename = "type")]
    kind: String,
    features: Vec<RawFeature>,
}

#[derive(DeriveDeserialize)]
struct RawFeature {
    #[serde(rename = "type")]
    kind: String,
    properties: RawProperties,
    geometry: RawGeometry,
}

#[derive(DeriveDeserialize)]
struct RawProperties {
    kind: String,
    #[serde(rename = "osmType")]
    osm_type: String,
    #[serde(rename = "osmId", deserialize_with = "lenient_integer")]
    osm_id: i64,
}

#[derive(DeriveDeserialize)]
struct RawGeometry {
    #[serde(rename = "type")]
    kind: String,
    #[serde(deserialize_with = "coordinates")]
    coordinates: Coordinates,
}

enum Coordinates {
    Point(LatLon),
    Line(Vec<LatLon>),
}

fn feature(raw: RawFeature) -> Result<AlertFeature, RegionReadError> {
    if raw.kind != FEATURE {
        return Err(RegionReadError::CorruptData);
    }
    let kind = kind(&raw.properties.kind)?;
    let element_type = element_type(&raw.properties.osm_type)?;
    if raw.properties.osm_id <= 0 {
        return Err(RegionReadError::CorruptData);
    }
    let points = match (raw.geometry.kind.as_str(), raw.geometry.coordinates) {
        (POINT, Coordinates::Point(point)) => vec![point],
        (LINE_STRING, Coordinates::Line(points)) if points.len() >= MIN_WAY_POINTS => points,
        _ => return Err(RegionReadError::CorruptData),
    };
    match element_type {
        OsmElementType::Node if points.len() != 1 => return Err(RegionReadError::CorruptData),
        OsmElementType::Way if points.len() < MIN_WAY_POINTS => {
            return Err(RegionReadError::CorruptData);
        }
        OsmElementType::Node | OsmElementType::Way => {}
    }
    if kind == AlertKind::Tunnel && element_type != OsmElementType::Way {
        return Err(RegionReadError::CorruptData);
    }
    Ok(AlertFeature {
        kind,
        element_type,
        osm_id: raw.properties.osm_id,
        geometry: points,
    })
}

fn kind(kind: &str) -> Result<AlertKind, RegionReadError> {
    match kind {
        "railway_crossing" => Ok(AlertKind::RailwayCrossing),
        "tunnel" => Ok(AlertKind::Tunnel),
        "hazard" => Ok(AlertKind::Hazard),
        "ford" => Ok(AlertKind::Ford),
        "barrier" => Ok(AlertKind::Barrier),
        _ => Err(RegionReadError::CorruptData),
    }
}

fn element_type(element_type: &str) -> Result<OsmElementType, RegionReadError> {
    match element_type {
        "node" => Ok(OsmElementType::Node),
        "way" => Ok(OsmElementType::Way),
        _ => Err(RegionReadError::CorruptData),
    }
}

/// One coordinate in a JSON number or a quoted number; kotlinx accepted a
/// quoted value, so the same document still parses.
struct LenientF64(f64);

impl<'de> Deserialize<'de> for LenientF64 {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct LenientVisitor;

        impl Visitor<'_> for LenientVisitor {
            type Value = LenientF64;

            fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                formatter.write_str("a number or a quoted number")
            }

            fn visit_f64<E: de::Error>(self, value: f64) -> Result<Self::Value, E> {
                Ok(LenientF64(value))
            }

            fn visit_i64<E: de::Error>(self, value: i64) -> Result<Self::Value, E> {
                Ok(LenientF64(value as f64))
            }

            fn visit_u64<E: de::Error>(self, value: u64) -> Result<Self::Value, E> {
                Ok(LenientF64(value as f64))
            }

            fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
                value
                    .parse()
                    .map(LenientF64)
                    .map_err(|_| de::Error::custom("a quoted coordinate is not a number"))
            }
        }

        deserializer.deserialize_any(LenientVisitor)
    }
}

/// An integer in a JSON number or a quoted integer, the rule `osmId` follows.
struct LenientI64(i64);

impl<'de> Deserialize<'de> for LenientI64 {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct LenientVisitor;

        impl Visitor<'_> for LenientVisitor {
            type Value = LenientI64;

            fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                formatter.write_str("an integer or a quoted integer")
            }

            fn visit_i64<E: de::Error>(self, value: i64) -> Result<Self::Value, E> {
                Ok(LenientI64(value))
            }

            fn visit_u64<E: de::Error>(self, value: u64) -> Result<Self::Value, E> {
                i64::try_from(value)
                    .map(LenientI64)
                    .map_err(|error| de::Error::custom(error))
            }

            fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
                value
                    .parse()
                    .map(LenientI64)
                    .map_err(|_| de::Error::custom("a quoted id is not an integer"))
            }
        }

        deserializer.deserialize_any(LenientVisitor)
    }
}

fn lenient_integer<'de, D: Deserializer<'de>>(deserializer: D) -> Result<i64, D::Error> {
    LenientI64::deserialize(deserializer).map(|value| value.0)
}

/// One element of a GeoJSON coordinate array: a number of a point's pair, or a
/// nested pair of a line's list.
enum Element {
    Number(f64),
    Pair(LatLon),
}

impl<'de> Deserialize<'de> for Element {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct ElementVisitor;

        impl<'de> Visitor<'de> for ElementVisitor {
            type Value = Element;

            fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                formatter.write_str("a coordinate number or a coordinate pair")
            }

            fn visit_f64<E: de::Error>(self, value: f64) -> Result<Self::Value, E> {
                Ok(Element::Number(value))
            }

            fn visit_i64<E: de::Error>(self, value: i64) -> Result<Self::Value, E> {
                Ok(Element::Number(value as f64))
            }

            fn visit_u64<E: de::Error>(self, value: u64) -> Result<Self::Value, E> {
                Ok(Element::Number(value as f64))
            }

            fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
                value
                    .parse()
                    .map(Element::Number)
                    .map_err(|_| de::Error::custom("a quoted coordinate is not a number"))
            }

            fn visit_seq<A: SeqAccess<'de>>(
                self,
                mut sequence: A,
            ) -> Result<Self::Value, A::Error> {
                pair(&mut sequence).map(Element::Pair)
            }
        }

        deserializer.deserialize_any(ElementVisitor)
    }
}

/// Reads `[longitude, latitude]` and nothing else.
fn pair<'de, A: SeqAccess<'de>>(sequence: &mut A) -> Result<LatLon, A::Error> {
    let longitude = sequence
        .next_element::<LenientF64>()?
        .ok_or_else(|| de::Error::custom("a coordinate pair needs a longitude"))?;
    let latitude = sequence
        .next_element::<LenientF64>()?
        .ok_or_else(|| de::Error::custom("a coordinate pair needs a latitude"))?;
    if sequence.next_element::<IgnoredAny>()?.is_some() {
        return Err(de::Error::custom("a coordinate pair has two values"));
    }
    json::lat_lon(longitude.0, latitude.0)
        .ok_or_else(|| de::Error::custom("a coordinate is outside the WGS84 range"))
}

/// A GeoJSON coordinate array: one pair for a point, a list of pairs for a line.
fn coordinates<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Coordinates, D::Error> {
    struct CoordinatesVisitor;

    impl<'de> Visitor<'de> for CoordinatesVisitor {
        type Value = Coordinates;

        fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
            formatter.write_str("a coordinate pair or a list of coordinate pairs")
        }

        fn visit_seq<A: SeqAccess<'de>>(self, mut sequence: A) -> Result<Self::Value, A::Error> {
            match sequence.next_element::<Element>()? {
                None => Err(de::Error::custom("a geometry needs coordinates")),
                Some(Element::Number(longitude)) => {
                    let latitude = sequence
                        .next_element::<LenientF64>()?
                        .ok_or_else(|| de::Error::custom("a coordinate pair needs a latitude"))?;
                    if sequence.next_element::<IgnoredAny>()?.is_some() {
                        return Err(de::Error::custom("a coordinate pair has two values"));
                    }
                    json::lat_lon(longitude, latitude.0)
                        .map(Coordinates::Point)
                        .ok_or_else(|| de::Error::custom("a coordinate is outside the WGS84 range"))
                }
                Some(Element::Pair(first)) => {
                    let mut points = vec![first];
                    loop {
                        match sequence.next_element::<Element>()? {
                            None => break,
                            Some(Element::Pair(point)) => points.push(point),
                            Some(Element::Number(_)) => {
                                return Err(de::Error::custom(
                                    "a line mixes coordinates and pairs",
                                ));
                            }
                        }
                    }
                    Ok(Coordinates::Line(points))
                }
            }
        }
    }

    deserializer.deserialize_seq(CoordinatesVisitor)
}
