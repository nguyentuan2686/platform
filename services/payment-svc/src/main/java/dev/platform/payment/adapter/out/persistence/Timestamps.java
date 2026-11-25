package dev.platform.payment.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Conversions between Instant and the driver's timestamptz types. */
final class Timestamps {

  private Timestamps() {}

  static OffsetDateTime toDb(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  static Instant read(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
