//! The uniffi surface: one index object, one session per search box, one
//! cancellation token. Nothing else crosses into Kotlin.

use std::collections::HashMap;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use crate::error::CoreError;
use crate::features::{OsmElementType, PlaceFeature};
use crate::index::Index;
use crate::search::{Cancel, Session, TypoAutomata};

/// The largest result count a caller may ask for.
const MAX_LIMIT: u32 = 50;
const CANCEL_CHECK_EVERY: u32 = 4_096;
const MIN_LATITUDE: f64 = -90.0;
const MAX_LATITUDE: f64 = 90.0;
const MIN_LONGITUDE: f64 = -180.0;
const MAX_LONGITUDE: f64 = 180.0;

struct Core {
    index: Arc<Index>,
    typos: Arc<TypoAutomata>,
}

/// One place search result. `id` is the row in the region's place table; the
/// Kotlin side prefixes it with the region build to make it stable.
///
/// `brand` is the brand or operator the place carries (`brand`, `brand:uk`,
/// `operator`, ...), from the features component; null when the place has
/// none. It is a separate value from `kind`, which stays the exact OSM
/// category the `pois` filter and the probes match on.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PlaceHit {
    pub id: u32,
    pub score: f32,
    pub name: String,
    pub kind: String,
    pub brand: Option<String>,
    pub context: String,
    pub latitude: f64,
    pub longitude: f64,
    pub is_area: bool,
    pub importance: u32,
}

/// One place with everything the reader knows. `context` is the joined entity
/// list, which the Kotlin side exposes as the address line.
///
/// `osm_type` and `osm_id` are the canonical OpenStreetMap identity from the
/// region's features component. Both are null when the component has no entry
/// for this place. `tags` is the raw OSM tag map for the place, empty when it
/// carries none; a place with an entry and no tags returns an empty map, never
/// null and never an error.
#[derive(Clone, Debug, uniffi::Record)]
pub struct PlaceDetail {
    pub id: u32,
    pub name: String,
    pub kind: String,
    pub context: String,
    pub latitude: f64,
    pub longitude: f64,
    pub is_area: bool,
    pub osm_type: Option<OsmElementType>,
    pub osm_id: Option<i64>,
    pub tags: HashMap<String, String>,
}

/// A token the caller sets when the keystroke is superseded. It carries no
/// callback: Rust polls the flag and returns [CoreError::Cancelled].
#[derive(Default, uniffi::Object)]
pub struct CancelToken {
    cancelled: AtomicBool,
}

#[uniffi::export]
impl CancelToken {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }

    pub fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }
}

/// A mounted region search section. Reads are concurrent; only the session's
/// previous-word cache needs a lock.
#[derive(uniffi::Object)]
pub struct SearchIndex {
    core: Arc<Core>,
}

impl SearchIndex {
    fn source(&self) -> &Index {
        &self.core.index
    }

    fn detail(&self, id: u32) -> Result<PlaceDetail, CoreError> {
        let record = self.source().place(id)?;
        let (osm_type, osm_id, tags) = match self.source().feature(id)? {
            Some(PlaceFeature { osm_type, osm_id, tags }) => {
                (Some(osm_type), Some(osm_id), tags.into_iter().collect())
            }
            None => (None, None, HashMap::new()),
        };
        Ok(PlaceDetail {
            id,
            name: self.source().place_name(&record)?.to_owned(),
            kind: self.source().kind_name(record.kind)?.to_owned(),
            context: self
                .source()
                .context_entities(&record)?
                .into_iter()
                .filter_map(|entity| self.source().entity_name(entity).ok())
                .collect::<Vec<_>>()
                .join(", "),
            latitude: f64::from(record.lat_e7) / crate::format::COORD_SCALE,
            longitude: f64::from(record.lon_e7) / crate::format::COORD_SCALE,
            is_area: self.source().is_area(&record),
            osm_type,
            osm_id,
            tags,
        })
    }
}

#[uniffi::export]
impl SearchIndex {
    #[uniffi::constructor]
    pub fn open(directory: String) -> Result<Arc<Self>, CoreError> {
        let index = Index::open(Path::new(&directory))?;
        Ok(Arc::new(SearchIndex {
            core: Arc::new(Core {
                index: Arc::new(index),
                typos: Arc::new(TypoAutomata::new()),
            }),
        }))
    }

    pub fn place_count(&self) -> u32 {
        self.source().place_count()
    }

    /// The section's on-disk version. A section built before `format.bin`
    /// existed is version 1; a section with house-number rows is version 2.
    pub fn format_version(&self) -> u16 {
        self.source().format_version()
    }

    pub fn details(&self, place_id: u32) -> Result<PlaceDetail, CoreError> {
        self.detail(place_id)
    }

    /// One session per search box. A null origin disables distance ranking.
    pub fn new_session(
        &self,
        origin_latitude: Option<f64>,
        origin_longitude: Option<f64>,
    ) -> Result<Arc<SearchSession>, CoreError> {
        let origin = match (origin_latitude, origin_longitude) {
            (Some(lat), Some(lon)) => Some(validate_point(lat, lon)?),
            (None, None) => None,
            _ => return Err(CoreError::InvalidInput),
        };
        Ok(Arc::new(SearchSession {
            state: Mutex::new(Session::new(
                Arc::clone(&self.core.index),
                Arc::clone(&self.core.typos),
                origin,
            )),
        }))
    }

    /// Places inside an inclusive bounding box. An empty `categories` list
    /// matches every kind; a category is the exact kind string ("amenity=cafe").
    pub fn pois(
        &self,
        south: f64,
        west: f64,
        north: f64,
        east: f64,
        categories: Vec<String>,
        limit: u32,
        cancel: Arc<CancelToken>,
    ) -> Result<Vec<PlaceDetail>, CoreError> {
        if limit == 0 || limit > MAX_LIMIT {
            return Err(CoreError::InvalidInput);
        }
        if !south.is_finite()
            || !west.is_finite()
            || !north.is_finite()
            || !east.is_finite()
            || south > north
            || west > east
            || !(MIN_LATITUDE..=MAX_LATITUDE).contains(&south)
            || !(MIN_LATITUDE..=MAX_LATITUDE).contains(&north)
            || !(MIN_LONGITUDE..=MAX_LONGITUDE).contains(&west)
            || !(MIN_LONGITUDE..=MAX_LONGITUDE).contains(&east)
        {
            return Err(CoreError::InvalidInput);
        }
        let mut out = Vec::new();
        for id in 0..self.source().place_count() {
            if id % CANCEL_CHECK_EVERY == 0 && cancel.is_cancelled() {
                return Err(CoreError::Cancelled);
            }
            let record = self.source().place(id)?;
            if self.source().is_address(&record) {
                continue;
            }
            let latitude = f64::from(record.lat_e7) / crate::format::COORD_SCALE;
            let longitude = f64::from(record.lon_e7) / crate::format::COORD_SCALE;
            if latitude < south || latitude > north || longitude < west || longitude > east {
                continue;
            }
            if !categories.is_empty() {
                let kind = self.source().kind_name(record.kind)?;
                if !categories.iter().any(|category| category == kind) {
                    continue;
                }
            }
            out.push(self.detail(id)?);
            if out.len() == limit as usize {
                break;
            }
        }
        Ok(out)
    }
}

/// The previous-word cache and its origin for one search box.
#[derive(uniffi::Object)]
pub struct SearchSession {
    state: Mutex<Session>,
}

impl SearchSession {
    fn with_session<T>(
        &self,
        operation: impl FnOnce(&mut Session) -> Result<T, CoreError>,
    ) -> Result<T, CoreError> {
        let mut session = self.state.lock().map_err(|_| CoreError::NotReady)?;
        operation(&mut session)
    }
}

#[uniffi::export]
impl SearchSession {
    pub fn query(
        &self,
        text: String,
        limit: u32,
        cancel: Arc<CancelToken>,
    ) -> Result<Vec<PlaceHit>, CoreError> {
        if text.trim().is_empty() || limit == 0 || limit > MAX_LIMIT {
            return Err(CoreError::InvalidInput);
        }
        if cancel.is_cancelled() {
            return Err(CoreError::Cancelled);
        }
        let signal = Cancel {
            flag: &cancel.cancelled,
        };
        let hits = self.with_session(|session| session.query(&text, limit as usize, &signal))?;
        Ok(hits
            .into_iter()
            .map(|hit| PlaceHit {
                id: hit.id,
                score: hit.score,
                name: hit.name,
                kind: hit.kind,
                brand: hit.brand,
                context: hit.context,
                latitude: hit.lat,
                longitude: hit.lon,
                is_area: hit.is_area,
                importance: u32::from(hit.importance),
            })
            .collect())
    }

    pub fn reset(&self) -> Result<(), CoreError> {
        self.with_session(|session| {
            session.reset();
            Ok(())
        })
    }

    pub fn set_origin(&self, latitude: f64, longitude: f64) -> Result<(), CoreError> {
        let origin = validate_point(latitude, longitude)?;
        self.with_session(|session| {
            session.set_origin(Some(origin));
            Ok(())
        })
    }

    pub fn clear_origin(&self) -> Result<(), CoreError> {
        self.with_session(|session| {
            session.set_origin(None);
            Ok(())
        })
    }
}

fn validate_point(latitude: f64, longitude: f64) -> Result<(f64, f64), CoreError> {
    if !latitude.is_finite()
        || !longitude.is_finite()
        || !(MIN_LATITUDE..=MAX_LATITUDE).contains(&latitude)
        || !(MIN_LONGITUDE..=MAX_LONGITUDE).contains(&longitude)
    {
        return Err(CoreError::InvalidInput);
    }
    Ok((latitude, longitude))
}
