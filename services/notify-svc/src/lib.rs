//! notify-svc library: hexagonal layers live here; `main.rs` only wires them.
pub mod adapters;
pub mod app;
pub mod application;
pub mod config;
pub mod domain;
