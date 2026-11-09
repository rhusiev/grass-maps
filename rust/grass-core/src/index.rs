//! The memory-mapped search section and its accessors.
//!
//! Every read is checked: a truncated or inconsistent section is
//! [CoreError::CorruptData], never a panic. The whole place table and both
//! offset tables are validated once at open.

use std::fs::File;
use std::path::Path;

use fst::Map;
use memmap2::Mmap;

use crate::error::CoreError;
use crate::features::{Features, PlaceFeature};
use crate::format::{self, PlaceRecord, FLAG_ADDRESS, FLAG_AREA};

pub struct Index {
    terms: Map<Mmap>,
    postings: Mmap,
    places: Mmap,
    context: Mmap,
    entities: Mmap,
    kinds: Mmap,
    strings: Mmap,
    features: Features,
    format_version: u16,
    place_count: u32,
    kind_count: u32,
}

fn map_file(dir: &Path, name: &str) -> Result<Mmap, CoreError> {
    let file = File::open(dir.join(name)).map_err(|_| CoreError::Io)?;
    let length = file.metadata().map_err(|_| CoreError::Io)?.len();
    if length == 0 {
        return Err(CoreError::CorruptData);
    }
    // SAFETY: the section files are written once by the region builder and
    // never modified while mapped. A writer must replace the directory, not a
    // mapped file, which the mount swap guarantees.
    unsafe { Mmap::map(&file) }.map_err(|_| CoreError::CorruptData)
}

fn read_format_version(dir: &Path) -> Result<u16, CoreError> {
    match std::fs::read(dir.join(format::FORMAT_FILE)) {
        Ok(bytes) => format::decode_format_header(Some(&bytes)),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            format::decode_format_header(None)
        }
        Err(_) => Err(CoreError::Io),
    }
}

impl Index {
    pub fn open(dir: &Path) -> Result<Self, CoreError> {
        let format_version = read_format_version(dir)?;
        let places = map_file(dir, format::PLACES_FILE)?;
        let entities = map_file(dir, format::ENTITIES_FILE)?;
        let kinds = map_file(dir, format::KINDS_FILE)?;
        let strings = map_file(dir, format::STRINGS_FILE)?;
        let context = map_file(dir, format::CONTEXT_FILE)?;
        let postings = map_file(dir, format::POSTINGS_FILE)?;
        let terms = Map::new(map_file(dir, format::TERMS_FILE)?).map_err(|_| CoreError::CorruptData)?;
        let features = Features::open(&dir.join(crate::features::FEATURES_FILE))?;

        if places.len() % format::PLACE_RECORD_BYTES != 0
            || entities.len() % format::OFFSET_BYTES != 0
            || kinds.len() % format::OFFSET_BYTES != 0
        {
            return Err(CoreError::CorruptData);
        }
        let place_count = (places.len() / format::PLACE_RECORD_BYTES) as u32;
        let kind_count = (kinds.len() / format::OFFSET_BYTES) as u32;
        let index = Index {
            terms,
            postings,
            places,
            context,
            entities,
            kinds,
            strings,
            features,
            format_version,
            place_count,
            kind_count,
        };
        index.validate()?;
        Ok(index)
    }

    /// Checks every offset the readers will follow. A region section is
    /// untrusted input, so this pass runs before the index is handed out.
    fn validate(&self) -> Result<(), CoreError> {
        for id in 0..self.place_count {
            let record = self.place(id)?;
            if record.name_offset as usize >= self.strings.len()
                || record.context_offset as usize >= self.context.len()
                || u32::from(record.kind) >= self.kind_count
            {
                return Err(CoreError::CorruptData);
            }
            self.place_name(&record)?;
            self.kind_name(record.kind)?;
            let mut pos = record.context_offset as usize;
            let count = format::get_varint(&self.context, &mut pos)?;
            for _ in 0..count {
                let entity = format::get_varint(&self.context, &mut pos)? as u32;
                if entity >= self.entity_count() {
                    return Err(CoreError::CorruptData);
                }
            }
        }
        for entity in 0..self.entity_count() {
            self.entity_name(entity)?;
        }
        for kind in 0..self.kind_count {
            format::get_string(&self.strings, format::u32_at(&self.kinds, kind as usize)?)?;
        }
        for index in 0..self.features.len() {
            if self.features.place_id_at(index)? >= self.place_count {
                return Err(CoreError::CorruptData);
            }
        }
        Ok(())
    }

    pub fn place_count(&self) -> u32 {
        self.place_count
    }

    /// The section's on-disk version. A section built before `format.bin`
    /// existed is version 1.
    pub fn format_version(&self) -> u16 {
        self.format_version
    }

    fn entity_count(&self) -> u32 {
        (self.entities.len() / format::OFFSET_BYTES) as u32
    }

    pub fn place(&self, id: u32) -> Result<PlaceRecord, CoreError> {
        if id >= self.place_count {
            return Err(CoreError::NotFound);
        }
        let start = id as usize * format::PLACE_RECORD_BYTES;
        let bytes = self
            .places
            .get(start..start + format::PLACE_RECORD_BYTES)
            .ok_or(CoreError::CorruptData)?;
        PlaceRecord::decode(bytes)
    }

    pub fn place_name(&self, record: &PlaceRecord) -> Result<&str, CoreError> {
        format::get_string(&self.strings, record.name_offset)
    }

    pub fn kind_name(&self, kind: u16) -> Result<&str, CoreError> {
        if u32::from(kind) >= self.kind_count {
            return Err(CoreError::CorruptData);
        }
        format::get_string(&self.strings, format::u32_at(&self.kinds, kind as usize)?)
    }

    pub fn entity_name(&self, id: u32) -> Result<&str, CoreError> {
        if id >= self.entity_count() {
            return Err(CoreError::CorruptData);
        }
        format::get_string(&self.strings, format::u32_at(&self.entities, id as usize)?)
    }

    pub fn context_entities(&self, record: &PlaceRecord) -> Result<Vec<u32>, CoreError> {
        let mut pos = record.context_offset as usize;
        let count = format::get_varint(&self.context, &mut pos)?;
        let mut out = Vec::with_capacity(count.min(64) as usize);
        for _ in 0..count {
            out.push(format::get_varint(&self.context, &mut pos)? as u32);
        }
        Ok(out)
    }

    pub fn is_area(&self, record: &PlaceRecord) -> bool {
        record.flags & FLAG_AREA != 0
    }

    /// True for a house-number row: its name carries the street and number.
    pub fn is_address(&self, record: &PlaceRecord) -> bool {
        record.flags & FLAG_ADDRESS != 0
    }

    /// The canonical identity and tags for [place_id], or null when the
    /// component has no entry. An entry whose row id is outside the place
    /// table was rejected at open.
    pub fn feature(&self, place_id: u32) -> Result<Option<PlaceFeature>, CoreError> {
        if place_id >= self.place_count {
            return Err(CoreError::NotFound);
        }
        self.features.lookup(place_id)
    }

    pub fn search(&self) -> &Map<Mmap> {
        &self.terms
    }

    pub fn postings(&self) -> &[u8] {
        &self.postings
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_directory_is_io() {
        assert!(matches!(
            Index::open(Path::new("/nonexistent/grass-index")),
            Err(CoreError::Io)
        ));
    }
}
