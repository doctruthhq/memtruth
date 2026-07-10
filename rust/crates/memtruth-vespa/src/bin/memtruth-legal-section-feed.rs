use std::env;
use std::fs;
use std::path::PathBuf;

use memtruth_vespa::{legal_section_doc_from_value, legal_section_doc_put};
use serde_json::Value;

#[derive(Debug)]
struct Args {
    input_path: PathBuf,
    output_path: PathBuf,
}

#[derive(Debug, Default)]
struct Counts {
    read: usize,
    written: usize,
    malformed: usize,
    invalid: usize,
}

fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let args = parse_args(env::args().skip(1).collect())?;
    let input = fs::read_to_string(&args.input_path)
        .map_err(|error| format!("failed to read {}: {error}", args.input_path.display()))?;
    let (output, counts) = convert_jsonl(&input);

    if counts.malformed > 0 || counts.invalid > 0 {
        return Err(format!(
            "refusing to write Vespa feed with malformed or invalid input: read={} written={} malformed={} invalid={}",
            counts.read, counts.written, counts.malformed, counts.invalid
        ));
    }

    if let Some(parent) = args.output_path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("failed to create {}: {error}", parent.display()))?;
    }
    fs::write(&args.output_path, output)
        .map_err(|error| format!("failed to write {}: {error}", args.output_path.display()))?;
    eprintln!(
        "memtruth-legal-section-feed: read={} written={} malformed={} invalid={}",
        counts.read, counts.written, counts.malformed, counts.invalid
    );
    Ok(())
}

fn parse_args(argv: Vec<String>) -> Result<Args, String> {
    let mut input_path = None;
    let mut output_path = None;
    let mut iter = argv.into_iter();
    while let Some(arg) = iter.next() {
        match arg.as_str() {
            "--in" => input_path = iter.next().map(PathBuf::from),
            "--out" => output_path = iter.next().map(PathBuf::from),
            _ => {
                return Err(
                    "usage: memtruth-legal-section-feed --in input.jsonl --out output.jsonl"
                        .to_string(),
                );
            }
        }
    }
    Ok(Args {
        input_path: input_path.ok_or("missing --in")?,
        output_path: output_path.ok_or("missing --out")?,
    })
}

fn convert_jsonl(input: &str) -> (String, Counts) {
    let mut counts = Counts::default();
    let mut output = Vec::new();

    for line in input.lines() {
        if line.trim().is_empty() {
            continue;
        }
        counts.read += 1;
        let value = match serde_json::from_str::<Value>(line) {
            Ok(value) => value,
            Err(_) => {
                counts.malformed += 1;
                continue;
            }
        };
        match legal_section_doc_from_value(&value)
            .and_then(|section| legal_section_doc_put(&section))
        {
            Ok(put) => {
                output.push(put.to_string());
                counts.written += 1;
            }
            Err(_) => counts.invalid += 1,
        }
    }

    let body = if output.is_empty() {
        String::new()
    } else {
        format!("{}\n", output.join("\n"))
    };
    (body, counts)
}
