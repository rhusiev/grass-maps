//! On-disk layout of one region's search section. All integers little-endian.
//!
//! - `terms.fst`: folded token -> byte offset into `postings.bin`
//! - `postings.bin`: per token, `varint n_entities` and ascending delta-varints
//!   of entity ids, then `varint n_places` and that many ascending delta-varints
//!   of `place << FIELD_BITS | field`. Entities come first so that a reader out
//!   of place budget can stop mid-list
//! - `places.bin`: fixed `PLACE_RECORD_BYTES` records, see `PlaceRecord`
//! - `context.bin`: per place, `varint n` then `n` varint entity ids
//! - `entities.bin`: one u32 offset into `strings.bin` per entity
//! - `kinds.bin`: one u32 offset into `strings.bin` per category ("amenity=cafe")
//! - `strings.bin`: varint byte length then UTF-8, for names and categories

use crate::error::CoreError;

pub const TERMS_FILE: &str = "terms.fst";
pub const POSTINGS_FILE: &str = "postings.bin";
pub const PLACES_FILE: &str = "places.bin";
pub const CONTEXT_FILE: &str = "context.bin";
pub const ENTITIES_FILE: &str = "entities.bin";
pub const KINDS_FILE: &str = "kinds.bin";
pub const STRINGS_FILE: &str = "strings.bin";

pub const FIELD_BITS: u32 = 2;
pub const FIELD_MASK: u32 = (1 << FIELD_BITS) - 1;

/// Where in a place a token was found; lower is a stronger match.
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Debug)]
pub enum Field {
    PrimaryName = 0,
    OtherName = 1,
    KindWord = 2,
}

impl Field {
    pub fn from_bits(bits: u32) -> Self {
        match bits {
            0 => Field::PrimaryName,
            1 => Field::OtherName,
            _ => Field::KindWord,
        }
    }
}

pub const COORD_SCALE: f64 = 1e7;
pub const PLACE_RECORD_BYTES: usize = 20;
pub const OFFSET_BYTES: usize = 4;

pub const FLAG_AREA: u8 = 1;
pub const FLAG_STREET: u8 = 2;

/// One fixed-width row of `places.bin`.
#[derive(Clone, Copy, Debug)]
pub struct PlaceRecord {
    pub lat_e7: i32,
    pub lon_e7: i32,
    pub name_offset: u32,
    pub context_offset: u32,
    pub kind: u16,
    pub importance: u8,
    pub flags: u8,
}

impl PlaceRecord {
    pub fn decode(bytes: &[u8]) -> Result<Self, CoreError> {
        if bytes.len() < PLACE_RECORD_BYTES {
            return Err(CoreError::CorruptData);
        }
        let u32_at = |i: usize| -> Result<u32, CoreError> {
            let slice = bytes.get(i..i + OFFSET_BYTES).ok_or(CoreError::CorruptData)?;
            let array: [u8; OFFSET_BYTES] = slice.try_into().map_err(|_| CoreError::CorruptData)?;
            Ok(u32::from_le_bytes(array))
        };
        Ok(PlaceRecord {
            lat_e7: u32_at(0)? as i32,
            lon_e7: u32_at(4)? as i32,
            name_offset: u32_at(8)?,
            context_offset: u32_at(12)?,
            kind: u16::from_le_bytes([bytes[16], bytes[17]]),
            importance: bytes[18],
            flags: bytes[19],
        })
    }
}

/// Reads a LEB128 varint at `*pos`, advancing it. A varint longer than 10
/// bytes or a read past the end is corrupt data, never a panic.
pub fn get_varint(bytes: &[u8], pos: &mut usize) -> Result<u64, CoreError> {
    let mut value = 0u64;
    let mut shift = 0u32;
    loop {
        let byte = *bytes.get(*pos).ok_or(CoreError::CorruptData)?;
        *pos += 1;
        value |= u64::from(byte & 0x7f)
            .checked_shl(shift)
            .ok_or(CoreError::CorruptData)?;
        if byte < 0x80 {
            return Ok(value);
        }
        shift += 7;
        if shift >= 64 {
            return Err(CoreError::CorruptData);
        }
    }
}

/// Reads a length-prefixed UTF-8 string at `offset`.
pub fn get_string(bytes: &[u8], offset: u32) -> Result<&str, CoreError> {
    let mut pos = offset as usize;
    let len = get_varint(bytes, &mut pos)? as usize;
    let end = pos.checked_add(len).ok_or(CoreError::CorruptData)?;
    let slice = bytes.get(pos..end).ok_or(CoreError::CorruptData)?;
    std::str::from_utf8(slice).map_err(|_| CoreError::CorruptData)
}

/// Reads one u32 from a table of u32 offsets.
pub fn u32_at(bytes: &[u8], index: usize) -> Result<u32, CoreError> {
    let start = index.checked_mul(OFFSET_BYTES).ok_or(CoreError::CorruptData)?;
    let slice = bytes
        .get(start..start + OFFSET_BYTES)
        .ok_or(CoreError::CorruptData)?;
    let array: [u8; OFFSET_BYTES] = slice.try_into().map_err(|_| CoreError::CorruptData)?;
    Ok(u32::from_le_bytes(array))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varints_round_trip() {
        let mut out = Vec::new();
        let values = [0u64, 1, 127, 128, 300, 1 << 40];
        for value in values {
            let mut written = value;
            while written >= 0x80 {
                out.push((written as u8) | 0x80);
                written >>= 7;
            }
            out.push(written as u8);
        }
        let mut pos = 0;
        for value in values {
            assert_eq!(get_varint(&out, &mut pos), Ok(value));
        }
        assert_eq!(get_varint(&out, &mut pos), Err(CoreError::CorruptData));
    }

    #[test]
    fn truncated_strings_are_corrupt() {
        assert_eq!(get_string(&[3, b'a'], 0), Err(CoreError::CorruptData));
        assert_eq!(u32_at(&[1, 2], 0), Err(CoreError::CorruptData));
    }
}
