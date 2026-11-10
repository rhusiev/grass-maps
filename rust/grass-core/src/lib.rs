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
//!
//! A place's terms are its names in the scripts OSM has them, plus a
//! romanisation of a Cyrillic name that has no Latin spelling of its own
//! ([`transliteration`]). Everything the query does to text is
//! [`fold::normalise`] and [`fold::canonical_words`]; transliteration never runs
//! on a query, and `test-data/search/normalisation-vectors.tsv` pins that fold
//! for both sides.

mod api;
pub mod builder;
mod error;
mod features;
mod fold;
mod format;
mod index;
mod search;
mod synonym;
mod transliteration;

pub use api::{CancelToken, PlaceDetail, PlaceHit, SearchIndex, SearchSession};
pub use error::CoreError;
pub use features::{OsmElementType, PlaceFeature};

uniffi::setup_scaffolding!();
