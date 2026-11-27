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

fn violations_in(root: &Path, forbidden: &[&str]) -> Vec<String> {
    let mut files = vec![];
    rs_files(root, &mut files);
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

fn violations(sub: &str, forbidden: &[&str]) -> Vec<String> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("src").join(sub);
    violations_in(&root, forbidden)
}

const DOMAIN_FORBIDDEN: &[&str] = &[
    "tokio",
    "axum",
    "sqlx",
    "rdkafka",
    "tower",
    "hyper",
    "reqwest",
    "opentelemetry",
    "serde_json",
    "crate::adapters",
];

#[test]
fn domain_is_pure() {
    let bad = violations("domain", DOMAIN_FORBIDDEN);
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

/// The purity check is not vacuous: it flags a file that imports a forbidden crate.
#[test]
fn purity_check_flags_a_violation() {
    let dir = std::env::temp_dir().join(format!("arch-fixture-{}", std::process::id()));
    fs::create_dir_all(&dir).expect("create fixture dir");
    fs::write(dir.join("leak.rs"), "use axum::Router;\n").expect("write fixture");
    let bad = violations_in(&dir, DOMAIN_FORBIDDEN);
    fs::remove_dir_all(&dir).expect("remove fixture dir");
    assert_eq!(bad.len(), 1, "{bad:?}");
}
