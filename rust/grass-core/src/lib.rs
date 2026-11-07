//! Grass Maps core: the offline search reader behind `PlaceProvider`.
//!
//! The on-disk format is the one the P4a spike measured: an FST of folded
//! terms, postings, a fixed place table, context references and a string pool.
//! This crate only reads a section; region builds produce one elsewhere.

mod api;
mod error;
mod fold;
mod format;
mod index;
mod search;

pub use api::{CancelToken, PlaceDetail, PlaceHit, SearchIndex, SearchSession};
pub use error::CoreError;

uniffi::setup_scaffolding!();
