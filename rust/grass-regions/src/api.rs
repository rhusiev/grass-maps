//! The uniffi surface: one reader object. Nothing else crosses into Kotlin.

use std::fs::Metadata;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use crate::corridor;
use crate::error::RegionReadError;
use crate::geojson;
use crate::model::{AlertFeature, AlertHit, LatLon, RegionRelations};
use crate::relations;

/// The largest component the reader accepts; the Kotlin side checks the same
/// bound from the manifest before it passes the file here.
const MAX_COMPONENT_BYTES: i64 = 64 * 1024 * 1024;
const MIN_ROUTE_POINTS: usize = 2;

/// The installed file behind a component path. A reinstall replaces the
/// directory entry, so the device and inode change even when the path, the
/// declared size and the modification time do not.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct FileIdentity {
    device: u64,
    inode: u64,
    len: u64,
    modified: Option<SystemTime>,
}

impl FileIdentity {
    fn of(metadata: &Metadata) -> Self {
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;

            Self {
                device: metadata.dev(),
                inode: metadata.ino(),
                len: metadata.len(),
                modified: metadata.modified().ok(),
            }
        }
        #[cfg(not(unix))]
        {
            Self {
                device: 0,
                inode: 0,
                len: metadata.len(),
                modified: metadata.modified().ok(),
            }
        }
    }
}

/// One component's parsed features, or the failure that reading or parsing it
/// produced, together with the file identity the answer belongs to.
struct CachedAlerts {
    path: String,
    expected_bytes: i64,
    identity: FileIdentity,
    result: Result<Arc<Vec<AlertFeature>>, RegionReadError>,
}

/// The component answers for one mounted file. The first load holds the lock
/// while it stats, reads and parses, so a concurrent first caller waits for
/// that parse and shares its result instead of repeating it. A load that fails
/// stores the failure under the same identity, so a second query returns it
/// without reading the file again.
#[derive(Default)]
struct AlertCache {
    entry: Mutex<Option<CachedAlerts>>,
}

impl AlertCache {
    fn load(
        &self,
        path: &str,
        expected_bytes: i64,
    ) -> Result<Arc<Vec<AlertFeature>>, RegionReadError> {
        self.load_with(path, expected_bytes, || {
            let bytes = read_component(path, expected_bytes)?;
            geojson::parse(&bytes).map(Arc::new)
        })
    }

    fn load_with(
        &self,
        path: &str,
        expected_bytes: i64,
        parse: impl FnOnce() -> Result<Arc<Vec<AlertFeature>>, RegionReadError>,
    ) -> Result<Arc<Vec<AlertFeature>>, RegionReadError> {
        let mut guard = self
            .entry
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let identity = component_identity(path, expected_bytes)?;
        if let Some(cache) = guard.as_ref()
            && cache.path == path
            && cache.expected_bytes == expected_bytes
            && cache.identity == identity
        {
            return cache.result.clone();
        }
        let result = parse();
        *guard = Some(CachedAlerts {
            path: path.to_owned(),
            expected_bytes,
            identity,
            result: result.clone(),
        });
        result
    }
}

/// Answers the way-alert corridor and route-relation queries for one mounted
/// region component file.
///
/// The Kotlin `RegionReader` owns the mount: it parses the manifest, selects
/// the component, and checks its format, version and declared size. This type
/// takes the resolved file and does the rest - read, parse, query - so the
/// caller holds no parser and no geometry.
///
/// The parsed feature list, or the failure that reading or parsing it produced,
/// is cached against the file's path, declared size and identity (device,
/// inode, size and modification time), so a corridor query on a mounted region
/// pays the parse once and only the query afterwards. A mount swap changes the
/// version directory, hence the path; a same-version reinstall replaces the
/// file behind the same path, hence the identity. Either way a stale answer
/// cannot survive for the replacement.
#[derive(uniffi::Object)]
pub struct RegionReader {
    alerts: AlertCache,
}

#[uniffi::export]
impl RegionReader {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            alerts: AlertCache::default(),
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
        self.alerts.load(path, expected_bytes)
    }
}

/// The identity of the component after repeating the checks the mount already
/// made. A file that changed size since the manifest was read, is not a regular
/// file, or is a symlink is corrupt data.
fn component_identity(path: &str, expected_bytes: i64) -> Result<FileIdentity, RegionReadError> {
    if expected_bytes <= 0 || expected_bytes > MAX_COMPONENT_BYTES {
        return Err(RegionReadError::CorruptData);
    }
    let metadata = std::fs::symlink_metadata(Path::new(path)).map_err(|_| RegionReadError::Io)?;
    if !metadata.file_type().is_file() || metadata.len() as i64 != expected_bytes {
        return Err(RegionReadError::CorruptData);
    }
    Ok(FileIdentity::of(&metadata))
}

/// Reads the component after repeating the checks the mount already made. A
/// file that changed since the manifest was read, is not a regular file, or is
/// a symlink is corrupt data.
fn read_component(path: &str, expected_bytes: i64) -> Result<Vec<u8>, RegionReadError> {
    component_identity(path, expected_bytes)?;
    let bytes = std::fs::read(path).map_err(|_| RegionReadError::Io)?;
    if bytes.len() as i64 != expected_bytes {
        return Err(RegionReadError::CorruptData);
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Barrier;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::thread;
    use std::time::Duration;

    const START: LatLon = LatLon {
        latitude: 49.9490,
        longitude: 23.9430,
    };
    const END: LatLon = LatLon {
        latitude: 49.9490,
        longitude: 23.9530,
    };
    const CORRIDOR_METERS: f64 = 30.0;
    const COMPONENT: &str = "way-alerts.geojson";

    fn component(osm_id: i64) -> String {
        format!(
            concat!(
                "{{\"type\":\"FeatureCollection\",\"features\":[",
                "{{\"type\":\"Feature\",\"properties\":",
                "{{\"kind\":\"ford\",\"osmType\":\"node\",\"osmId\":{}}},",
                "\"geometry\":{{\"type\":\"Point\",\"coordinates\":[23.9431,49.949]}}}}",
                "]}}"
            ),
            osm_id
        )
    }

    fn write_component(directory: &Path, osm_id: i64) -> (String, i64) {
        let file = directory.join(COMPONENT);
        let body = component(osm_id);
        std::fs::write(&file, body.as_bytes()).expect("write the component");
        (file.to_string_lossy().into_owned(), body.len() as i64)
    }

    fn route() -> Vec<LatLon> {
        vec![START, END]
    }

    #[test]
    fn a_successful_parse_is_reused() {
        let directory = tempfile::tempdir().expect("temp dir");
        let (path, bytes) = write_component(directory.path(), 7001);
        let cache = AlertCache::default();
        let parses = AtomicUsize::new(0);
        let parse = || {
            parses.fetch_add(1, Ordering::SeqCst);
            Ok(Arc::new(Vec::new()))
        };

        let first = cache.load_with(&path, bytes, parse).expect("first load");
        let second = cache.load_with(&path, bytes, parse).expect("second load");

        assert_eq!(1, parses.load(Ordering::SeqCst));
        assert!(Arc::ptr_eq(&first, &second));
    }

    #[test]
    fn a_failed_parse_is_reused() {
        let directory = tempfile::tempdir().expect("temp dir");
        let (path, bytes) = write_component(directory.path(), 7001);
        let cache = AlertCache::default();
        let parses = AtomicUsize::new(0);
        let parse = || {
            parses.fetch_add(1, Ordering::SeqCst);
            Err(RegionReadError::CorruptData)
        };

        assert_eq!(
            Err(RegionReadError::CorruptData),
            cache.load_with(&path, bytes, parse)
        );
        assert_eq!(
            Err(RegionReadError::CorruptData),
            cache.load_with(&path, bytes, parse)
        );
        assert_eq!(1, parses.load(Ordering::SeqCst));
    }

    #[test]
    fn concurrent_first_callers_parse_once() {
        let directory = tempfile::tempdir().expect("temp dir");
        let (path, bytes) = write_component(directory.path(), 7001);
        let cache = Arc::new(AlertCache::default());
        let parses = Arc::new(AtomicUsize::new(0));
        let barrier = Arc::new(Barrier::new(3));
        let handles: Vec<_> = (0..2)
            .map(|_| {
                let cache = Arc::clone(&cache);
                let parses = Arc::clone(&parses);
                let barrier = Arc::clone(&barrier);
                let path = path.clone();
                thread::spawn(move || {
                    barrier.wait();
                    cache
                        .load_with(&path, bytes, || {
                            parses.fetch_add(1, Ordering::SeqCst);
                            thread::sleep(Duration::from_millis(100));
                            Ok(Arc::new(Vec::new()))
                        })
                        .expect("concurrent load")
                })
            })
            .collect();
        barrier.wait();

        let results: Vec<_> = handles
            .into_iter()
            .map(|handle| handle.join().expect("join the loader"))
            .collect();

        assert_eq!(1, parses.load(Ordering::SeqCst));
        assert!(Arc::ptr_eq(&results[0], &results[1]));
    }

    #[test]
    fn a_same_version_reinstall_is_parsed_again() {
        let directory = tempfile::tempdir().expect("temp dir");
        let (path, bytes) = write_component(directory.path(), 7001);
        let reader = RegionReader::new();
        let first = reader
            .way_alert_hits(path.clone(), bytes, route(), 0.0, CORRIDOR_METERS)
            .expect("the first component");
        assert_eq!(7001, first[0].feature.osm_id);

        // The installer quarantines the old directory and moves the new one
        // in. The path and the declared size stay the same; the file behind
        // them does not.
        let quarantine = directory.path().join("invalid-reinstall");
        std::fs::rename(&path, &quarantine).expect("quarantine the old file");
        let replacement = component(7002);
        assert_eq!(bytes, replacement.len() as i64);
        std::fs::write(&path, replacement.as_bytes()).expect("write the replacement");
        std::fs::remove_file(&quarantine).expect("drop the quarantine");

        let second = reader
            .way_alert_hits(path, bytes, route(), 0.0, CORRIDOR_METERS)
            .expect("the replacement component");
        assert_eq!(7002, second[0].feature.osm_id);
    }
}
