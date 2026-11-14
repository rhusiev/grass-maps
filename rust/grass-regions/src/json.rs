//! Small JSON accessors shared by both component parsers.
//!
//! They mirror the kotlinx.serialization accessors the Kotlin reader used, so
//! a document that the old reader accepted still parses and one it rejected
//! still fails. In particular kotlinx parses a quoted number with
//! `doubleOrNull`/`longOrNull`, so a quoted id or coordinate is accepted here
//! too, while a field the schema declares as a number is read from a JSON
//! number only.

use serde_json::{Map, Value};

use crate::model::LatLon;

const MIN_LATITUDE: f64 = -90.0;
const MAX_LATITUDE: f64 = 90.0;
const MIN_LONGITUDE: f64 = -180.0;
const MAX_LONGITUDE: f64 = 180.0;
const POINT_COORDINATES: usize = 2;

/// A non-blank string value, the rule every identity field uses.
pub fn string(object: &Map<String, Value>, key: &str) -> Option<String> {
    object
        .get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.trim().is_empty())
        .map(str::to_owned)
}

/// A finite float from a JSON number or a quoted number.
pub fn number(value: Option<&Value>) -> Option<f64> {
    match value {
        Some(Value::Number(number)) => number.as_f64(),
        Some(Value::String(text)) => text.parse().ok(),
        _ => None,
    }
}

/// An integer from a JSON number only, the rule the relations schema's own
/// header and ids use.
pub fn strict_integer(object: &Map<String, Value>, key: &str) -> Option<i64> {
    object.get(key).and_then(Value::as_i64)
}

/// A float from a JSON number only, the rule the relations schema's distance
/// uses.
pub fn strict_number(object: &Map<String, Value>, key: &str) -> Option<f64> {
    object.get(key).and_then(Value::as_f64)
}

/// A `[longitude, latitude]` pair, in GeoJSON's own order.
pub fn coordinate(value: Option<&Value>) -> Option<LatLon> {
    pair(value?.as_array()?)
}

/// The same pair, when the caller already has the JSON array.
pub fn pair(pair: &[Value]) -> Option<LatLon> {
    if pair.len() != POINT_COORDINATES {
        return None;
    }
    lat_lon(number(pair.first())?, number(pair.get(1))?)
}

/// A coordinate from its two values, already order-swapped into the model.
pub fn lat_lon(longitude: f64, latitude: f64) -> Option<LatLon> {
    if !longitude.is_finite() || !latitude.is_finite() {
        return None;
    }
    if !(MIN_LATITUDE..=MAX_LATITUDE).contains(&latitude)
        || !(MIN_LONGITUDE..=MAX_LONGITUDE).contains(&longitude)
    {
        return None;
    }
    Some(LatLon {
        latitude,
        longitude,
    })
}
