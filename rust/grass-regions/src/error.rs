//! The failure a region component read can report.

/// Why a component query failed.
///
/// `Io` and `CorruptData` are one bucket on the Kotlin side: a component that
/// is missing, unreadable or malformed is corrupt data, because the package
/// verifier already accepted the mount. `Unsupported` is a format or version
/// this reader does not know, and `InvalidInput` is a query the caller built
/// wrongly.
#[derive(Debug, thiserror::Error, uniffi::Error, PartialEq, Eq)]
pub enum RegionReadError {
    #[error("the component file is missing or unreadable")]
    Io,
    #[error("the component data is corrupt")]
    CorruptData,
    #[error("the component format version is not supported")]
    Unsupported,
    #[error("the input is invalid")]
    InvalidInput,
}
