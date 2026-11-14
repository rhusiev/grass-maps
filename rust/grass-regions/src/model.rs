//! The values that cross the uniffi boundary.
//!
//! They mirror the plugin-api model the Kotlin side has always exposed:
//! [`LatLon`] is `GeoPoint`, [`AlertKind`] is `WayAlertFeatureKind`,
//! [`OsmElementType`] is `WayAlertElementType`, and the relation records are
//! `RouteRelation`, `RouteRelationMember`, `RouteNetworkNode` and
//! `RouteRelations`. The network tags stay raw strings: the Kotlin side maps
//! them onto `RouteNetwork` with the one function that already knows OSM's
//! `iwn`/`nwn`/`rwn`/`lwn` and `icn`/`ncn`/`rcn`/`lcn`.

/// One WGS84 coordinate, in degrees.
#[derive(Clone, Copy, Debug, PartialEq, uniffi::Record)]
pub struct LatLon {
    pub latitude: f64,
    pub longitude: f64,
}

/// Which OpenStreetMap element a way-alert feature came from.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum OsmElementType {
    Node,
    Way,
}

/// The kind of feature a way alert can announce.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum AlertKind {
    RailwayCrossing,
    Tunnel,
    Hazard,
    Ford,
    Barrier,
}

/// One feature of the `way-alerts` component.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct AlertFeature {
    pub kind: AlertKind,
    pub element_type: OsmElementType,
    pub osm_id: i64,
    pub geometry: Vec<LatLon>,
}

/// One feature inside a route's corridor, with its position on the route.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct AlertHit {
    pub feature: AlertFeature,
    /// The earliest distance along the route where the feature enters the corridor.
    pub along_route_meters: f64,
    /// The closest separation between the route line and the feature.
    pub distance_from_route_meters: f64,
}

/// The kind of route a relation carries.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum RouteKind {
    Hiking,
    Cycling,
}

/// One way member of a route relation, in relation order.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct RelationMember {
    pub way_id: i64,
    pub role: Option<String>,
}

/// One OSM route relation as the component stores it.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct RegionRelation {
    pub id: i64,
    pub route: RouteKind,
    /// The raw OSM `network` tag, empty when the relation has none.
    pub network: String,
    pub reference: Option<String>,
    pub name: Option<String>,
    pub osmc_symbol: Option<String>,
    pub colour: Option<String>,
    pub distance_meters: Option<f64>,
    pub members: Vec<RelationMember>,
}

/// One numbered junction of a cycling node network.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct NetworkNode {
    pub id: i64,
    pub point: LatLon,
    /// The OSM `network` tag the node carries; always a cycling network.
    pub network: String,
    pub reference: String,
}

/// The geometry of one member way, in the way's own direction.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct RelationWay {
    pub id: i64,
    pub points: Vec<LatLon>,
}

/// The route-relations component.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct RegionRelations {
    pub ways: Vec<RelationWay>,
    pub relations: Vec<RegionRelation>,
    pub nodes: Vec<NetworkNode>,
}
