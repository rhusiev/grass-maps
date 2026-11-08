//! The one error type that crosses the uniffi boundary.

/// A typed failure. The Kotlin side maps each variant onto `AppError`.
#[derive(Debug, thiserror::Error, uniffi::Error, PartialEq, Eq)]
pub enum CoreError {
    #[error("the index file is missing or unreadable")]
    Io,
    #[error("the index data is corrupt")]
    CorruptData,
    #[error("the input is invalid")]
    InvalidInput,
    #[error("no such place")]
    NotFound,
    #[error("the call was cancelled")]
    Cancelled,
    #[error("the search index is not open")]
    NotReady,
    #[error("the data format version is not supported")]
    Unsupported,
}
