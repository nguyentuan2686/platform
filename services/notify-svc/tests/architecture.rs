use std::fs;
use std::path::{Path, PathBuf};

fn rs_files(dir: &Path, out: &mut Vec<PathBuf>) {
    for entry in fs::read_dir(dir).expect("read dir") {
        let p = entry.expect("entry").path();
        if p.is_dir() {
            rs_files(&p, out);
        } else if p.extension().is_some_and(|e| e == "rs") {
            out.push(p);
        }
    }
}

fn violations(sub: &str, forbidden: &[&str]) -> Vec<String> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("src").join(sub);
    let mut files = vec![];
    rs_files(&root, &mut files);
    assert!(!files.is_empty(), "no files under {}", root.display());
    let mut bad = vec![];
    for f in files {
        let text = fs::read_to_string(&f).expect("read");
        for (i, line) in text.lines().enumerate() {
            let t = line.trim_start();
            let t = t.strip_prefix("pub ").unwrap_or(t);
            let Some(rest) = t.strip_prefix("use ") else {
                continue;
            };
            let rest = rest
                .trim_start_matches("::")
                .trim_start_matches('{')
                .trim_start();
            for name in forbidden {
                let hit = rest == *name
                    || rest.starts_with(&format!("{name}::"))
                    || rest.starts_with(&format!("{name};"))
                    || rest.starts_with(&format!("{name},"));
                if hit {
                    bad.push(format!("{}:{}: {}", f.display(), i + 1, line.trim()));
                }
            }
        }
    }
    bad
}

#[test]
fn domain_is_pure() {
    let bad = violations(
        "domain",
        &[
            "tokio",
            "axum",
            "sqlx",
            "rdkafka",
            "tower",
            "hyper",
            "reqwest",
            "opentelemetry",
            "crate::adapters",
        ],
    );
    assert!(
        bad.is_empty(),
        "forbidden imports in domain:\n{}",
        bad.join("\n")
    );
}

#[test]
fn application_does_not_import_adapters() {
    let bad = violations("application", &["crate::adapters"]);
    assert!(
        bad.is_empty(),
        "adapter imports in application:\n{}",
        bad.join("\n")
    );
}
