//! The place-features component: one canonical OpenStreetMap identity and tag
//! map per place row.
//!
//! The search table has no OSM id, so a feature entry is keyed by the place row
//! id the search index issued (`regionBuild:rowIndex` on the Kotlin side). The
//! section is sparse: a place without an entry has no known identity or tags,
//! and a place with an entry but no tags has an empty tag map. The whole file
//! is validated once at open, so a lookup never reads past an entry.
//!
//! Layout, all integers little-endian:
//!
//! - `magic`: four bytes `GMFT`
//! - `version`: one byte, currently 1
//! - `entry_count`: u32
//! - `offsets`: `entry_count` u32 offsets, relative to `data_start`, strictly
//!   increasing and in place-id order
//! - `data_start = 9 + 4 * entry_count`
//! - one entry per offset: `place_id` u32, `osm_type` u8 (0 node, 1 way,
//!   2 relation), `osm_id` i64, `tag_count` u16, then per tag a u16 key length,
//!   the UTF-8 key, a u16 value length and the UTF-8 value

use std::fs::File;
use std::io::ErrorKind;
use std::path::Path;

use memmap2::Mmap;

use crate::error::CoreError;

pub const FEATURES_FILE: &str = "features.bin";
pub const FEATURES_MAGIC: [u8; 4] = *b"GMFT";
pub const FEATURES_VERSION: u8 = 1;

const MAGIC_BYTES: usize = 4;
const VERSION_BYTES: usize = 1;
const COUNT_BYTES: usize = 4;
const OFFSET_BYTES: usize = 4;
const HEADER_BYTES: usize = MAGIC_BYTES + VERSION_BYTES + COUNT_BYTES;
const ENTRY_HEADER_BYTES: usize = 4 + 1 + 8 + 2;
const MAX_STRING_BYTES: usize = u16::MAX as usize;
const MAX_STRING_FIELD_BYTES: usize = 2;

/// The canonical OpenStreetMap element a place row came from.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum OsmElementType {
    Node,
    Way,
    Relation,
}

impl OsmElementType {
    fn from_byte(byte: u8) -> Result<Self, CoreError> {
        match byte {
            0 => Ok(Self::Node),
            1 => Ok(Self::Way),
            2 => Ok(Self::Relation),
            _ => Err(CoreError::CorruptData),
        }
    }

    fn as_byte(self) -> u8 {
        match self {
            Self::Node => 0,
            Self::Way => 1,
            Self::Relation => 2,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Node => "node",
            Self::Way => "way",
            Self::Relation => "relation",
        }
    }
}

/// One place's identity and tags, as the feature component carries them.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlaceFeature {
    pub osm_type: OsmElementType,
    pub osm_id: i64,
    pub tags: Vec<(String, String)>,
}

/// One entry of the component, before it is encoded.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FeatureRecord {
    pub place_id: u32,
    pub feature: PlaceFeature,
}

/// A validated feature component.
pub struct Features {
    bytes: Mmap,
    offsets: Vec<u32>,
    data_start: usize,
}

impl Features {
    /// Maps and validates the component at [path].
    ///
    /// A missing file is [CoreError::NotReady], because the caller asked for a
    /// component the region package does not have. A wrong magic, header,
    /// offset, entry or tag string is [CoreError::CorruptData]. A version this
    /// reader does not know is [CoreError::Unsupported].
    pub fn open(path: &Path) -> Result<Self, CoreError> {
        let file =
            File::open(path).map_err(|error| match error.kind() {
                ErrorKind::NotFound => CoreError::NotReady,
                _ => CoreError::Io,
            })?;
        let length = file.metadata().map_err(|_| CoreError::Io)?.len();
        if length == 0 {
            return Err(CoreError::CorruptData);
        }
        // SAFETY: the section file is written once by the region builder and
        // replaced by a directory swap, never modified while mapped.
        let bytes = unsafe { Mmap::map(&file) }.map_err(|_| CoreError::CorruptData)?;
        Self::parse(bytes)
    }

    fn parse(bytes: Mmap) -> Result<Self, CoreError> {
        if bytes.len() < HEADER_BYTES {
            return Err(CoreError::CorruptData);
        }
        if bytes.get(0..MAGIC_BYTES) != Some(FEATURES_MAGIC.as_slice()) {
            return Err(CoreError::CorruptData);
        }
        if bytes.get(MAGIC_BYTES) != Some(&FEATURES_VERSION) {
            return Err(CoreError::Unsupported);
        }
        let count = u32_at(&bytes, MAGIC_BYTES + VERSION_BYTES)? as usize;
        let offsets_end =
            HEADER_BYTES
                .checked_add(count.checked_mul(OFFSET_BYTES).ok_or(CoreError::CorruptData)?)
                .ok_or(CoreError::CorruptData)?;
        if offsets_end > bytes.len() {
            return Err(CoreError::CorruptData);
        }
        let mut offsets = Vec::with_capacity(count);
        let mut previous: Option<u32> = None;
        for index in 0..count {
            let offset = u32_at(&bytes, HEADER_BYTES + index * OFFSET_BYTES)?;
            if previous.is_some_and(|value| offset <= value) {
                return Err(CoreError::CorruptData);
            }
            previous = Some(offset);
            offsets.push(offset);
        }
        let features = Features {
            bytes,
            offsets,
            data_start: offsets_end,
        };
        features.validate()?;
        Ok(features)
    }

    fn validate(&self) -> Result<(), CoreError> {
        if self.offsets.is_empty() && self.data_start != self.bytes.len() {
            return Err(CoreError::CorruptData);
        }
        let mut previous: Option<u32> = None;
        for index in 0..self.offsets.len() {
            let (place_id, _, consumed) = self.entry(index)?;
            let end = self.entry_end(index)?;
            if consumed != end - self.entry_start(index)? {
                return Err(CoreError::CorruptData);
            }
            if previous.is_some_and(|value| place_id <= value) {
                return Err(CoreError::CorruptData);
            }
            previous = Some(place_id);
        }
        Ok(())
    }

    /// Entries in the component.
    pub fn len(&self) -> usize {
        self.offsets.len()
    }

    /// The place id of entry [index]. The caller validates it against the
    /// place table, so a features file cannot name a row that does not exist.
    pub fn place_id_at(&self, index: usize) -> Result<u32, CoreError> {
        self.entry(index).map(|(place_id, _, _)| place_id)
    }

    /// The feature for [place_id], or null when the component has no entry.
    pub fn lookup(&self, place_id: u32) -> Result<Option<PlaceFeature>, CoreError> {
        let mut low = 0usize;
        let mut high = self.offsets.len();
        while low < high {
            let middle = low + (high - low) / 2;
            let (id, feature, _) = self.entry(middle)?;
            match id.cmp(&place_id) {
                std::cmp::Ordering::Less => low = middle + 1,
                std::cmp::Ordering::Greater => high = middle,
                std::cmp::Ordering::Equal => return Ok(Some(feature)),
            }
        }
        Ok(None)
    }

    fn entry_start(&self, index: usize) -> Result<usize, CoreError> {
        let offset = *self.offsets.get(index).ok_or(CoreError::CorruptData)?;
        self.data_start
            .checked_add(offset as usize)
            .filter(|start| *start < self.bytes.len())
            .ok_or(CoreError::CorruptData)
    }

    fn entry_end(&self, index: usize) -> Result<usize, CoreError> {
        match self.offsets.get(index + 1) {
            Some(offset) => self
                .data_start
                .checked_add(*offset as usize)
                .filter(|end| *end <= self.bytes.len())
                .ok_or(CoreError::CorruptData),
            None => Ok(self.bytes.len()),
        }
    }

    fn entry(&self, index: usize) -> Result<(u32, PlaceFeature, usize), CoreError> {
        let start = self.entry_start(index)?;
        let end = self.entry_end(index)?;
        if end <= start {
            return Err(CoreError::CorruptData);
        }
        decode_entry(&self.bytes[start..end])
    }
}

/// Encodes the component from [records]. The records are sorted by place id,
/// so the reader can binary search them.
pub fn encode(records: &mut [FeatureRecord]) -> Result<Vec<u8>, CoreError> {
    records.sort_by_key(|record| record.place_id);
    for pair in records.windows(2) {
        if pair[0].place_id == pair[1].place_id {
            return Err(CoreError::InvalidInput);
        }
    }
    let mut data = Vec::new();
    let mut offsets = Vec::with_capacity(records.len());
    for record in records.iter() {
        if record.feature.osm_id <= 0 {
            return Err(CoreError::InvalidInput);
        }
        offsets.push(u32::try_from(data.len()).map_err(|_| CoreError::InvalidInput)?);
        encode_entry(&mut data, record)?;
    }
    let count = u32::try_from(records.len()).map_err(|_| CoreError::InvalidInput)?;
    let mut out = Vec::with_capacity(HEADER_BYTES + offsets.len() * OFFSET_BYTES + data.len());
    out.extend_from_slice(&FEATURES_MAGIC);
    out.push(FEATURES_VERSION);
    out.extend_from_slice(&count.to_le_bytes());
    for offset in offsets {
        out.extend_from_slice(&offset.to_le_bytes());
    }
    out.extend_from_slice(&data);
    Ok(out)
}

fn encode_entry(out: &mut Vec<u8>, record: &FeatureRecord) -> Result<(), CoreError> {
    let feature = &record.feature;
    let mut tags: Vec<(&str, &str)> =
        feature.tags.iter().map(|(key, value)| (key.as_str(), value.as_str())).collect();
    tags.sort_unstable();
    for pair in tags.windows(2) {
        if pair[0].0 == pair[1].0 {
            return Err(CoreError::InvalidInput);
        }
    }
    if tags.len() > u16::MAX as usize
        || feature.tags.iter().any(|(key, value)| {
            key.is_empty() || key.len() > MAX_STRING_BYTES || value.len() > MAX_STRING_BYTES
        })
    {
        return Err(CoreError::InvalidInput);
    }
    out.extend_from_slice(&record.place_id.to_le_bytes());
    out.push(feature.osm_type.as_byte());
    out.extend_from_slice(&feature.osm_id.to_le_bytes());
    out.extend_from_slice(&(tags.len() as u16).to_le_bytes());
    for (key, value) in tags {
        out.extend_from_slice(&(key.len() as u16).to_le_bytes());
        out.extend_from_slice(key.as_bytes());
        out.extend_from_slice(&(value.len() as u16).to_le_bytes());
        out.extend_from_slice(value.as_bytes());
    }
    Ok(())
}

fn decode_entry(bytes: &[u8]) -> Result<(u32, PlaceFeature, usize), CoreError> {
    if bytes.len() < ENTRY_HEADER_BYTES {
        return Err(CoreError::CorruptData);
    }
    let place_id = u32_at(bytes, 0)?;
    let osm_type = OsmElementType::from_byte(*bytes.get(4).ok_or(CoreError::CorruptData)?)?;
    let osm_id = i64_at(bytes, 5)?;
    if osm_id <= 0 {
        return Err(CoreError::CorruptData);
    }
    let tag_count = u16_at(bytes, 13)? as usize;
    let mut position = ENTRY_HEADER_BYTES;
    let mut tags = Vec::with_capacity(tag_count.min(32));
    for _ in 0..tag_count {
        let key = read_string(bytes, &mut position)?;
        if key.is_empty() {
            return Err(CoreError::CorruptData);
        }
        let value = read_string(bytes, &mut position)?;
        tags.push((key, value));
    }
    Ok((place_id, PlaceFeature { osm_type, osm_id, tags }, position))
}

fn read_string(bytes: &[u8], position: &mut usize) -> Result<String, CoreError> {
    let length = u16_at(bytes, *position)? as usize;
    *position += MAX_STRING_FIELD_BYTES;
    let end = (*position).checked_add(length).ok_or(CoreError::CorruptData)?;
    let slice = bytes.get(*position..end).ok_or(CoreError::CorruptData)?;
    *position = end;
    std::str::from_utf8(slice)
        .map(str::to_owned)
        .map_err(|_| CoreError::CorruptData)
}

fn u16_at(bytes: &[u8], index: usize) -> Result<u16, CoreError> {
    let slice = bytes.get(index..index + 2).ok_or(CoreError::CorruptData)?;
    let array: [u8; 2] = slice.try_into().map_err(|_| CoreError::CorruptData)?;
    Ok(u16::from_le_bytes(array))
}

fn u32_at(bytes: &[u8], index: usize) -> Result<u32, CoreError> {
    let slice = bytes.get(index..index + 4).ok_or(CoreError::CorruptData)?;
    let array: [u8; 4] = slice.try_into().map_err(|_| CoreError::CorruptData)?;
    Ok(u32::from_le_bytes(array))
}

fn i64_at(bytes: &[u8], index: usize) -> Result<i64, CoreError> {
    let slice = bytes.get(index..index + 8).ok_or(CoreError::CorruptData)?;
    let array: [u8; 8] = slice.try_into().map_err(|_| CoreError::CorruptData)?;
    Ok(i64::from_le_bytes(array))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn cafe() -> FeatureRecord {
        FeatureRecord {
            place_id: 7,
            feature: PlaceFeature {
                osm_type: OsmElementType::Node,
                osm_id: 4_815_162_342,
                tags: vec![
                    ("wheelchair".to_owned(), "yes".to_owned()),
                    ("amenity".to_owned(), "cafe".to_owned()),
                ],
            },
        }
    }

    fn bench() -> FeatureRecord {
        FeatureRecord {
            place_id: 9,
            feature: PlaceFeature {
                osm_type: OsmElementType::Way,
                osm_id: 12,
                tags: Vec::new(),
            },
        }
    }

    fn write(bytes: &[u8]) -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!(
            "grass-features-{}-{:?}.bin",
            std::process::id(),
            std::thread::current().id()
        ));
        let mut file = File::create(&path).expect("create");
        file.write_all(bytes).expect("write");
        path
    }

    #[test]
    fn round_trips_entries_and_an_empty_tag_map() {
        let mut records = vec![bench(), cafe()];
        let bytes = encode(&mut records).expect("encode");
        let path = write(&bytes);
        let features = Features::open(&path).expect("open");
        assert_eq!(features.len(), 2);
        let cafe = features.lookup(7).expect("lookup").expect("entry");
        assert_eq!(cafe.osm_type, OsmElementType::Node);
        assert_eq!(cafe.osm_id, 4_815_162_342);
        assert_eq!(
            cafe.tags,
            vec![
                ("amenity".to_owned(), "cafe".to_owned()),
                ("wheelchair".to_owned(), "yes".to_owned()),
            ],
        );
        let bench = features.lookup(9).expect("lookup").expect("entry");
        assert!(bench.tags.is_empty());
        assert_eq!(features.lookup(8).expect("lookup"), None);
        std::fs::remove_file(path).ok();
    }

    #[test]
    fn an_empty_component_is_valid_and_has_no_entries() {
        let bytes = encode(&mut []).expect("encode");
        let path = write(&bytes);
        let features = Features::open(&path).expect("open");
        assert_eq!(features.len(), 0);
        std::fs::remove_file(path).ok();
    }

    #[test]
    fn a_missing_file_is_not_ready() {
        assert_eq!(
            Features::open(Path::new("/nonexistent/grass-features.bin")).err(),
            Some(CoreError::NotReady),
        );
    }

    #[test]
    fn a_truncated_header_is_corrupt() {
        let path = write(b"GMFT\x01");
        assert_eq!(Features::open(&path).err(), Some(CoreError::CorruptData));
        std::fs::remove_file(path).ok();
    }

    #[test]
    fn a_wrong_version_is_unsupported() {
        let mut bytes = encode(&mut [cafe()]).expect("encode");
        bytes[4] = FEATURES_VERSION + 1;
        let path = write(&bytes);
        assert_eq!(Features::open(&path).err(), Some(CoreError::Unsupported));
        std::fs::remove_file(path).ok();
    }

    #[test]
    fn a_bad_magic_is_corrupt() {
        let mut bytes = encode(&mut [cafe()]).expect("encode");
        bytes[0] = b'X';
        let path = write(&bytes);
        assert_eq!(Features::open(&path).err(), Some(CoreError::CorruptData));
        std::fs::remove_file(path).ok();
    }

    #[test]
    fn a_past_the_end_offset_is_corrupt() {
        let mut bytes = encode(&mut [cafe()]).expect("encode");
        let offset_start = HEADER_BYTES;
        bytes[offset_start..offset_start + OFFSET_BYTES].copy_from_slice(&u32::MAX.to_le_bytes());
        let path = write(&bytes);
        assert_eq!(Features::open(&path).err(), Some(CoreError::CorruptData));
        std::fs::remove_file(path).ok();
    }

    #[test]
    fn a_truncated_entry_is_corrupt() {
        let mut bytes = encode(&mut [cafe()]).expect("encode");
        bytes.truncate(bytes.len() - 1);
        let path = write(&bytes);
        assert_eq!(Features::open(&path).err(), Some(CoreError::CorruptData));
        std::fs::remove_file(path).ok();
    }
}
