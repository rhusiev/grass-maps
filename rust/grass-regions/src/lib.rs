//! Grass Maps region reader: the way-alert corridor and route-relation queries
//! behind `RegionAccess`.
//!
//! The Kotlin `RegionReader` owns the mount: it parses the manifest, checks the
//! component's format, version and size, and passes the resolved file to this
//! crate. Everything after that - reading the component, parsing either
//! component and answering the query - runs here.
//!
//! Two components are read. `way-alerts.geojson` is a GeoJSON
//! `FeatureCollection` of point and line features; the corridor query returns
//! each feature that comes within a route's corridor, with the earliest
//! along-route distance and the closest separation. `relations.json` is the
//! route-relations document: member ways, their geometry, hiking and cycling
//! relations, and cycling node-network numbers.
//!
//! Both documents are untrusted input. A document that parses but breaks the
//! schema is [`RegionReadError::CorruptData`], never a partial value.

mod api;
mod corridor;
mod error;
mod geojson;
mod json;
mod model;
mod relations;

pub use api::RegionReader;
pub use error::RegionReadError;
pub use model::{
    AlertFeature, AlertHit, AlertKind, LatLon, NetworkNode, OsmElementType, RegionRelation,
    RegionRelations, RelationMember, RelationWay, RouteKind,
};

uniffi::setup_scaffolding!();
