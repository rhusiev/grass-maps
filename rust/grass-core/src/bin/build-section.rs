//! Builds the committed fixture search section from the authored place list.
//!
//! Usage:
//!   build-section <places.tsv> <out-dir>
//!   build-section --empty-features <features.bin>

use std::path::Path;

use grass_core::builder;

fn main() {
    let arguments: Vec<String> = std::env::args().skip(1).collect();
    match arguments.as_slice() {
        [flag, path] if flag == "--empty-features" => match builder::write_empty_features(Path::new(path))
        {
            Ok(()) => println!("wrote an empty feature component to {path}"),
            Err(error) => fail(&error.to_string()),
        },
        [source, out] => match builder::build(Path::new(source), Path::new(out)) {
            Ok(stats) => println!(
                "built {} places, {} entities and {} terms into {out}",
                stats.places, stats.entities, stats.terms
            ),
            Err(error) => fail(&error.to_string()),
        },
        _ => {
            eprintln!("usage: build-section <places.tsv> <out-dir>");
            eprintln!("       build-section --empty-features <features.bin>");
            std::process::exit(2);
        }
    }
}

fn fail(message: &str) -> ! {
    eprintln!("build-section: {message}");
    std::process::exit(1);
}
