//! The uniffi surface: one reader object. Nothing else crosses into Kotlin.

use std::path::Path;
use std::sync::{Arc, Mutex};

use crate::corridor;
use crate::error::RegionReadError;
use crate::geojson;
use crate::model::{AlertFeature, AlertHit, LatLon, RegionRelations};
use crate::relations;

/// The largest component the reader accepts; the Kotlin side checks the same
/// bound from the manifest before it passes the file here.
const MAX_COMPONENT_BYTES: i64 = 64 * 1024 * 1024;
const MIN_ROUTE_POINTS: usize = 2;

/// The parsed features of one component file, kept for the reader's lifetime.
struct CachedAlerts {
    path: String,
    expected_bytes: i64,
    features: Arc<Vec<AlertFeature>>,
}

/// Answers the way-alert corridor and route-relation queries for one mounted
/// region component file.
///
/// The Kotlin `RegionReader` owns the mount: it parses the manifest, selects
/// the component, and checks its format, version and declared size. This type
/// takes the resolved file and does the rest - read, parse, query - so the
/// caller holds no parser and no geometry.
///
/// The parsed feature list is cached against the file's path and declared size,
/// so a corridor query on a mounted region pays the parse once and only the
/// query afterwards. A mount swap changes the version directory, hence the
/// path, so a stale list cannot answer for a new version.
#[derive(uniffi::Object)]
pub struct RegionReader {
    alerts: Mutex<Option<CachedAlerts>>,
}

#[uniffi::export]
impl RegionReader {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            alerts: Mutex::new(None),
        })
    }

    /// The crate and version answering the queries. A diagnostics value, not a
    /// contract; the Kotlin side never shows it.
    pub fn engine_name(&self) -> String {
        format!("grass-regions {}", env!("CARGO_PKG_VERSION"))
    }

    /// The features within `corridor_meters` of `route`, ordered by their
    /// distance along the route.
    ///
    /// `route_distance_meters` is the route's own total; the reader scales its
    /// projected along-route figure by it so the hits sit on the route the
    /// caller shows. A route shorter than two points answers with an empty
    /// list; a package with no component never reaches this call, because the
    /// Kotlin side answers that one.
    pub fn way_alert_hits(
        &self,
        component_path: String,
        expected_bytes: i64,
        route: Vec<LatLon>,
        route_distance_meters: f64,
        corridor_meters: f64,
    ) -> Result<Vec<AlertHit>, RegionReadError> {
        if !corridor_meters.is_finite() || corridor_meters <= 0.0 {
            return Err(RegionReadError::InvalidInput);
        }
        if route.len() < MIN_ROUTE_POINTS {
            return Ok(Vec::new());
        }
        let features = self.alerts(&component_path, expected_bytes)?;
        Ok(corridor::hits(
            &route,
            route_distance_meters,
            corridor_meters,
            &features,
        ))
    }

    /// The component's route relations, member-way geometry and node-network
    /// numbers. A package with no component is the Kotlin side's empty value,
    /// so this is only called for a file the manifest declares.
    pub fn route_relations(
        &self,
        component_path: String,
        expected_bytes: i64,
    ) -> Result<RegionRelations, RegionReadError> {
        let bytes = read_component(&component_path, expected_bytes)?;
        relations::parse(&bytes)
    }
}

impl RegionReader {
    /// The parsed features for the component, from the cache when the same
    /// file is asked for again.
    fn alerts(
        &self,
        path: &str,
        expected_bytes: i64,
    ) -> Result<Arc<Vec<AlertFeature>>, RegionReadError> {
        {
            let guard = self
                .alerts
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            if let Some(cache) = guard.as_ref()
                && cache.path == path
                && cache.expected_bytes == expected_bytes
            {
                return Ok(Arc::clone(&cache.features));
            }
        }
        let bytes = read_component(path, expected_bytes)?;
        let features = Arc::new(geojson::parse(&bytes)?);
        let mut guard = self
            .alerts
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *guard = Some(CachedAlerts {
            path: path.to_owned(),
            expected_bytes,
            features: Arc::clone(&features),
        });
        Ok(features)
    }
}

/// Reads the component after repeating the checks the mount already made. A
/// file that changed since the manifest was read, is not a regular file, or is
/// a symlink is corrupt data.
fn read_component(path: &str, expected_bytes: i64) -> Result<Vec<u8>, RegionReadError> {
    if expected_bytes <= 0 || expected_bytes > MAX_COMPONENT_BYTES {
        return Err(RegionReadError::CorruptData);
    }
    let metadata = std::fs::symlink_metadata(Path::new(path)).map_err(|_| RegionReadError::Io)?;
    if !metadata.file_type().is_file() {
        return Err(RegionReadError::CorruptData);
    }
    let bytes = std::fs::read(path).map_err(|_| RegionReadError::Io)?;
    if bytes.len() as i64 != expected_bytes {
        return Err(RegionReadError::CorruptData);
    }
    Ok(bytes)
}
