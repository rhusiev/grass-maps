//! Grass Maps core: the offline search reader behind `PlaceProvider`.
//!
//! The on-disk format is the one the P4a spike measured: an FST of folded
//! terms, postings, a fixed place table, context references, a string pool and
//! the feature component that carries each place's OSM identity and tags. The
//! crate reads a section; real region builds produce one elsewhere, while the
//! committed fixture is built by [`builder`].
//!
//! Version 2 adds `format.bin` and house-number rows ([`format::FLAG_ADDRESS`]):
//! a place whose name is the street and number and whose context is the
//! locality. A section without the header is version 1 and still opens.

mod api;
pub mod builder;
mod error;
mod features;
mod fold;
mod format;
mod index;
mod search;
mod synonym;

pub use api::{CancelToken, PlaceDetail, PlaceHit, SearchIndex, SearchSession};
pub use error::CoreError;
pub use features::{OsmElementType, PlaceFeature};

uniffi::setup_scaffolding!();
