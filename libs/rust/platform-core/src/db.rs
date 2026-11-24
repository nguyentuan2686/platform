//! Database helpers shared by services.

use anyhow::Context as _;
use sqlx::PgPool;

/// Schema holding platform-owned tables (`processed_event`) and their migrations table.
pub const PLATFORM_SCHEMA: &str = "platform";

/// Applies the platform migrations (`libs/rust/platform-core/migrations`, embedded at compile time).
///
/// Tracked separately from the service's own migrations: sqlx 0.9 `Migrator` supports a
/// schema-qualified migrations table (`dangerous_set_table_name`) and creating that schema first
/// (`create_schema`), so platform migrations are recorded in `platform._sqlx_migrations` while the
/// service keeps the default `public._sqlx_migrations`. Version numbers therefore never collide.
/// The name is fixed from the first release, so the "dangerous" rename caveat does not apply.
pub async fn migrate_platform(pool: &PgPool) -> anyhow::Result<()> {
    let mut migrator = sqlx::migrate!("./migrations");
    migrator.create_schema(PLATFORM_SCHEMA);
    migrator.dangerous_set_table_name("platform._sqlx_migrations");
    migrator
        .run(pool)
        .await
        .context("applying platform migrations")?;
    Ok(())
}
