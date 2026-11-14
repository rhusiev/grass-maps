//! The corridor query.
//!
//! The route is projected onto a local metre grid around its first point; at a
//! route's scale the equirectangular error is far below the corridor width, and
//! a projection keeps the segment-to-segment maths simple. A feature is a hit
//! when any part of it comes within the corridor: the hit carries the earliest
//! along-route distance where the route meets the corridor and the closest
//! separation, both scaled by the route's own distance so the two figures sit
//! on the route the caller shows.
//!
//! This is a line-for-line port of the Kotlin `CorridorQuery` the reader used
//! before T-0321; the fixture results are unchanged.

use crate::model::{AlertFeature, AlertHit, LatLon};

/// Above this dot product two segments count as parallel; the normal path
/// stays stable.
const PARALLEL_EPSILON: f64 = 1e-12;
const METRES_PER_DEGREE: f64 = 111_320.0;
const MIN_ROUTE_POINTS: usize = 2;

/// Answers one corridor query.
pub fn hits(
    route: &[LatLon],
    route_distance_meters: f64,
    corridor_meters: f64,
    features: &[AlertFeature],
) -> Vec<AlertHit> {
    if route.len() < MIN_ROUTE_POINTS || features.is_empty() {
        return Vec::new();
    }
    let projection = LocalProjection::new(route[0]);
    let route_points: Vec<Point> = route
        .iter()
        .map(|point| projection.project(*point))
        .collect();
    let route_length = polyline_length(&route_points);
    if route_length <= 0.0 {
        return Vec::new();
    }
    let route_along = cumulative_along(&route_points);
    let route_box = inflated_box(&route_points, corridor_meters);
    let scale = route_scale(route_distance_meters, route_length);
    let total = scaled_total(route_distance_meters, route_length);
    let mut hits = Vec::new();
    for feature in features {
        let feature_points: Vec<Point> = feature
            .geometry
            .iter()
            .map(|point| projection.project(*point))
            .collect();
        if !boxes_touch(&inflated_box(&feature_points, corridor_meters), &route_box) {
            continue;
        }
        let Some(contact) = contact_with_route(
            &feature_points,
            &route_points,
            &route_along,
            corridor_meters,
        ) else {
            continue;
        };
        hits.push(AlertHit {
            feature: feature.clone(),
            along_route_meters: (contact.along * scale).clamp(0.0, total),
            distance_from_route_meters: contact.distance,
        });
    }
    hits.sort_by(|first, second| {
        first
            .along_route_meters
            .total_cmp(&second.along_route_meters)
            .then_with(|| first.feature.osm_id.cmp(&second.feature.osm_id))
    });
    hits
}

fn route_scale(route_distance_meters: f64, route_length: f64) -> f64 {
    if route_distance_meters.is_finite() && route_distance_meters > 0.0 {
        route_distance_meters / route_length
    } else {
        1.0
    }
}

fn scaled_total(route_distance_meters: f64, route_length: f64) -> f64 {
    if route_distance_meters.is_finite() && route_distance_meters > 0.0 {
        route_distance_meters
    } else {
        route_length
    }
}

fn contact_with_route(
    feature: &[Point],
    route: &[Point],
    route_along: &[f64],
    corridor_meters: f64,
) -> Option<Contact> {
    let mut earliest_along: Option<f64> = None;
    let mut closest_distance = f64::MAX;
    for route_index in 0..route.len() - 1 {
        let start = route[route_index];
        let end = route[route_index + 1];
        let start_along = route_along[route_index];
        let segment_length = distance(start, end);
        if feature.len() == 1 {
            let projection = project_on_segment(feature[0], start, end);
            if projection.distance <= corridor_meters {
                let along = start_along + projection.t * segment_length;
                earliest_along = Some(earliest_along.map_or(along, |current| current.min(along)));
                closest_distance = closest_distance.min(projection.distance);
            }
        } else {
            for feature_index in 0..feature.len() - 1 {
                let closest = closest_between(
                    start,
                    end,
                    feature[feature_index],
                    feature[feature_index + 1],
                );
                if closest.distance <= corridor_meters {
                    let along = start_along + closest.route_t * segment_length;
                    earliest_along =
                        Some(earliest_along.map_or(along, |current| current.min(along)));
                    closest_distance = closest_distance.min(closest.distance);
                }
            }
        }
    }
    earliest_along.map(|along| Contact {
        along,
        distance: closest_distance,
    })
}

fn cumulative_along(route: &[Point]) -> Vec<f64> {
    let mut along = vec![0.0; route.len()];
    for index in 1..route.len() {
        along[index] = along[index - 1] + distance(route[index - 1], route[index]);
    }
    along
}

fn polyline_length(points: &[Point]) -> f64 {
    let mut total = 0.0;
    for index in 0..points.len() - 1 {
        total += distance(points[index], points[index + 1]);
    }
    total
}

/// The closest point to `point` on the segment `start`-`end`, as a fraction
/// and a distance.
fn project_on_segment(point: Point, start: Point, end: Point) -> Projection {
    let dx = end.x - start.x;
    let dy = end.y - start.y;
    let length_squared = dx * dx + dy * dy;
    if length_squared <= 0.0 {
        return Projection {
            t: 0.0,
            distance: distance(point, start),
        };
    }
    let t =
        (((point.x - start.x) * dx + (point.y - start.y) * dy) / length_squared).clamp(0.0, 1.0);
    let closest = Point {
        x: start.x + t * dx,
        y: start.y + t * dy,
    };
    Projection {
        t,
        distance: distance(point, closest),
    }
}

/// The closest approach of two segments, after Ericson, Real-Time Collision
/// Detection section 5.1.9. `route_t` is the fraction along the route segment
/// and `distance` the separation at the closest points.
fn closest_between(
    route_start: Point,
    route_end: Point,
    feature_start: Point,
    feature_end: Point,
) -> SegmentContact {
    let d1x = route_end.x - route_start.x;
    let d1y = route_end.y - route_start.y;
    let d2x = feature_end.x - feature_start.x;
    let d2y = feature_end.y - feature_start.y;
    let rx = route_start.x - feature_start.x;
    let ry = route_start.y - feature_start.y;
    let a = d1x * d1x + d1y * d1y;
    let e = d2x * d2x + d2y * d2y;
    let f = d2x * rx + d2y * ry;
    let mut s: f64;
    let mut t: f64;
    if a <= PARALLEL_EPSILON && e <= PARALLEL_EPSILON {
        s = 0.0;
        t = 0.0;
    } else if a <= PARALLEL_EPSILON {
        s = 0.0;
        t = (f / e).clamp(0.0, 1.0);
    } else {
        let c = d1x * rx + d1y * ry;
        if e <= PARALLEL_EPSILON {
            t = 0.0;
            s = (-c / a).clamp(0.0, 1.0);
        } else {
            let b = d1x * d2x + d1y * d2y;
            let denominator = a * e - b * b;
            s = if denominator.abs() > PARALLEL_EPSILON {
                ((b * f - c * e) / denominator).clamp(0.0, 1.0)
            } else {
                0.0
            };
            t = (b * s + f) / e;
            if t < 0.0 {
                t = 0.0;
                s = (-c / a).clamp(0.0, 1.0);
            } else if t > 1.0 {
                t = 1.0;
                s = ((b - c) / a).clamp(0.0, 1.0);
            }
        }
    }
    let route_point = Point {
        x: route_start.x + s * d1x,
        y: route_start.y + s * d1y,
    };
    let feature_point = Point {
        x: feature_start.x + t * d2x,
        y: feature_start.y + t * d2y,
    };
    SegmentContact {
        route_t: s,
        distance: distance(route_point, feature_point),
    }
}

fn inflated_box(points: &[Point], padding: f64) -> Rect {
    let mut min_x = f64::MAX;
    let mut min_y = f64::MAX;
    let mut max_x = f64::MIN;
    let mut max_y = f64::MIN;
    for point in points {
        min_x = min_x.min(point.x);
        min_y = min_y.min(point.y);
        max_x = max_x.max(point.x);
        max_y = max_y.max(point.y);
    }
    Rect {
        min_x: min_x - padding,
        min_y: min_y - padding,
        max_x: max_x + padding,
        max_y: max_y + padding,
    }
}

fn boxes_touch(first: &Rect, second: &Rect) -> bool {
    first.min_x <= second.max_x
        && second.min_x <= first.max_x
        && first.min_y <= second.max_y
        && second.min_y <= first.max_y
}

fn distance(first: Point, second: Point) -> f64 {
    (first.x - second.x).hypot(first.y - second.y)
}

#[derive(Clone, Copy)]
struct Point {
    x: f64,
    y: f64,
}

struct Projection {
    t: f64,
    distance: f64,
}

struct SegmentContact {
    route_t: f64,
    distance: f64,
}

struct Contact {
    along: f64,
    distance: f64,
}

struct Rect {
    min_x: f64,
    min_y: f64,
    max_x: f64,
    max_y: f64,
}

/// WGS84 degrees to local metres, around the route's first point.
struct LocalProjection {
    origin_latitude: f64,
    origin_longitude: f64,
    metres_per_degree_longitude: f64,
}

impl LocalProjection {
    fn new(origin: LatLon) -> Self {
        Self {
            origin_latitude: origin.latitude,
            origin_longitude: origin.longitude,
            metres_per_degree_longitude: METRES_PER_DEGREE * origin.latitude.to_radians().cos(),
        }
    }

    fn project(&self, point: LatLon) -> Point {
        Point {
            x: (point.longitude - self.origin_longitude) * self.metres_per_degree_longitude,
            y: (point.latitude - self.origin_latitude) * METRES_PER_DEGREE,
        }
    }
}
