//! A successful top-up (`PaymentSucceeded`) and its Telegram text.
//!
//! The text names the package, the amount and the local time only: never an account, order, email, phone or name.

use serde::Deserialize;
use time::{Duration, OffsetDateTime, UtcOffset};

/// `Asia/Ho_Chi_Minh`: UTC+7 all year (no daylight saving).
pub const VN_OFFSET: UtcOffset = match UtcOffset::from_hms(7, 0, 0) {
    Ok(o) => o,
    Err(_) => UtcOffset::UTC, // unreachable: 7h is a valid offset
};

/// An event older than this when consumed is recorded (it counts in revenue) but not announced: a new consumer group
/// reads the topic from the start and must not replay old top-ups to the phone.
pub const ANNOUNCE_WITHIN: Duration = Duration::hours(1);

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum TopUpError {
    #[error("amount must match ^[0-9]+\\.[0-9]{{2}}$")]
    InvalidAmount,
    #[error("product code must not be blank")]
    BlankProduct,
}

/// The `data` fields of `PaymentSucceeded` v1 this service uses (others are ignored).
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct PaymentSucceeded {
    pub order_id: uuid::Uuid,
    pub product_code: String,
    pub amount: String,
    pub currency: String,
    #[serde(with = "time::serde::rfc3339")]
    pub paid_at: OffsetDateTime,
}

/// An amount in minor units (hundredths) of `currency`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Money {
    pub minor: i64,
    pub currency: String,
}

impl Money {
    /// Parses the event's `^[0-9]+\.[0-9]{2}$` amount.
    pub fn parse(amount: &str, currency: &str) -> Result<Self, TopUpError> {
        let (int, frac) = amount.split_once('.').ok_or(TopUpError::InvalidAmount)?;
        let digits = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit());
        if !digits(int) || frac.len() != 2 || !digits(frac) || int.len() > 15 {
            return Err(TopUpError::InvalidAmount);
        }
        let int: i64 = int.parse().map_err(|_| TopUpError::InvalidAmount)?;
        let frac: i64 = frac.parse().map_err(|_| TopUpError::InvalidAmount)?;
        Ok(Self {
            minor: int * 100 + frac,
            currency: currency.to_owned(),
        })
    }

    /// `50.000đ` for VND (whole dong, `.` thousands), `5.00 USD` otherwise.
    #[must_use]
    pub fn display(&self) -> String {
        if self.currency == "VND" {
            format!("{}đ", group_thousands(self.minor / 100))
        } else {
            format!(
                "{}.{:02} {}",
                self.minor / 100,
                self.minor % 100,
                self.currency
            )
        }
    }
}

fn group_thousands(n: i64) -> String {
    let digits = n.unsigned_abs().to_string();
    let mut out = String::new();
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i).is_multiple_of(3) {
            out.push('.');
        }
        out.push(c);
    }
    if n < 0 {
        out.insert(0, '-');
    }
    out
}

/// `CREDIT_5 (5 lượt)`; an unknown (newer) code is shown as it is.
#[must_use]
pub fn package_label(code: &str) -> String {
    let what = match code {
        "CREDIT_1" => "1 lượt",
        "CREDIT_5" => "5 lượt",
        "PASS_MONTH" => "gói tháng",
        "PASS_YEAR" => "gói năm",
        _ => return code.to_owned(),
    };
    format!("{code} ({what})")
}

/// `HH:MM dd/MM` in `Asia/Ho_Chi_Minh`.
#[must_use]
pub fn local_time(t: OffsetDateTime) -> String {
    let t = t.to_offset(VN_OFFSET);
    format!(
        "{:02}:{:02} {:02}/{:02}",
        t.hour(),
        t.minute(),
        t.day(),
        u8::from(t.month())
    )
}

/// A validated top-up, as stored and announced.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TopUp {
    pub order_id: uuid::Uuid,
    pub product_code: String,
    pub money: Money,
    pub paid_at: OffsetDateTime,
}

impl TopUp {
    pub fn from_event(e: &PaymentSucceeded) -> Result<Self, TopUpError> {
        if e.product_code.trim().is_empty() {
            return Err(TopUpError::BlankProduct);
        }
        Ok(Self {
            order_id: e.order_id,
            product_code: e.product_code.clone(),
            money: Money::parse(&e.amount, &e.currency)?,
            paid_at: e.paid_at,
        })
    }

    /// True when consumed within [`ANNOUNCE_WITHIN`] of the payment (a clock skew into the future counts as fresh).
    #[must_use]
    pub fn announce_at(&self, now: OffsetDateTime) -> bool {
        now - self.paid_at <= ANNOUNCE_WITHIN
    }

    #[must_use]
    pub fn message(&self) -> String {
        format!(
            "💰 Nạp tiền\nGói: {}\nSố tiền: {}\nLúc: {}",
            package_label(&self.product_code),
            self.money.display(),
            local_time(self.paid_at)
        )
    }
}

#[cfg(test)]
mod tests {
    use time::macros::datetime;

    use super::*;

    fn event(product: &str, amount: &str, currency: &str) -> PaymentSucceeded {
        PaymentSucceeded {
            order_id: uuid::Uuid::from_u128(9),
            product_code: product.into(),
            amount: amount.into(),
            currency: currency.into(),
            paid_at: datetime!(2026-11-28 07:05:09 UTC),
        }
    }

    #[test]
    fn message_is_short_vietnamese_in_local_time() {
        let t = TopUp::from_event(&event("CREDIT_5", "50000.00", "VND")).unwrap();
        assert_eq!(
            t.message(),
            "💰 Nạp tiền\nGói: CREDIT_5 (5 lượt)\nSố tiền: 50.000đ\nLúc: 14:05 28/11"
        );
    }

    #[test]
    fn local_time_crosses_midnight() {
        assert_eq!(local_time(datetime!(2026-11-28 17:30 UTC)), "00:30 29/11");
    }

    #[test]
    fn money_formats_vnd_and_usd() {
        assert_eq!(Money::parse("0.00", "VND").unwrap().display(), "0đ");
        assert_eq!(Money::parse("999.00", "VND").unwrap().display(), "999đ");
        assert_eq!(Money::parse("1000.00", "VND").unwrap().display(), "1.000đ");
        assert_eq!(
            Money::parse("1234567.00", "VND").unwrap().display(),
            "1.234.567đ"
        );
        assert_eq!(Money::parse("5.00", "USD").unwrap().display(), "5.00 USD");
        assert_eq!(Money::parse("12.05", "USD").unwrap().display(), "12.05 USD");
    }

    #[test]
    fn invalid_amounts_are_rejected() {
        for a in [
            "",
            "5",
            "5.0",
            "5.000",
            ".00",
            "a.00",
            "5.a0",
            "-5.00",
            "1234567890123456.00",
        ] {
            assert_eq!(
                Money::parse(a, "VND"),
                Err(TopUpError::InvalidAmount),
                "{a:?}"
            );
        }
        assert_eq!(
            TopUp::from_event(&event(" ", "1.00", "VND")),
            Err(TopUpError::BlankProduct)
        );
    }

    #[test]
    fn packages_have_labels_and_unknown_codes_pass_through() {
        assert_eq!(package_label("CREDIT_1"), "CREDIT_1 (1 lượt)");
        assert_eq!(package_label("PASS_MONTH"), "PASS_MONTH (gói tháng)");
        assert_eq!(package_label("PASS_YEAR"), "PASS_YEAR (gói năm)");
        assert_eq!(package_label("CREDIT_50"), "CREDIT_50");
    }

    #[test]
    fn only_recent_payments_are_announced() {
        let t = TopUp::from_event(&event("CREDIT_1", "10000.00", "VND")).unwrap();
        assert!(t.announce_at(t.paid_at + Duration::minutes(59)));
        assert!(t.announce_at(t.paid_at + ANNOUNCE_WITHIN));
        assert!(
            t.announce_at(t.paid_at - Duration::minutes(5)),
            "clock skew"
        );
        assert!(!t.announce_at(t.paid_at + Duration::minutes(61)));
    }
}
