//! The daily Telegram summary (23:59 `Asia/Ho_Chi_Minh`): which day is due, and its text.

use time::{Date, Duration, OffsetDateTime, Time};

use super::topup::{package_label, Money, VN_OFFSET};

/// Local time of day at which a day's summary becomes due.
pub const DUE_AT: Time = match Time::from_hms(23, 59, 0) {
    Ok(t) => t,
    Err(_) => Time::MIDNIGHT, // unreachable: 23:59:00 is valid
};

/// The latest local day whose 23:59 has passed at `now`: at 23:59 on D it is D; from 00:00 until 23:58 on D+1 it is
/// still D (a pod that was down at 23:59 catches up), then D+1.
#[must_use]
pub fn due_day(now: OffsetDateTime) -> Date {
    let local = now.to_offset(VN_OFFSET);
    let shift = Duration::hours(23) + Duration::minutes(59);
    (local - shift).date()
}

/// `[day 00:00, day+1 00:00)` local time, as instants.
#[must_use]
pub fn day_range(day: Date) -> (OffsetDateTime, OffsetDateTime) {
    let start = day.with_time(Time::MIDNIGHT).assume_offset(VN_OFFSET);
    (start, start + Duration::days(1))
}

/// One package's revenue in one currency.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RevenueLine {
    pub product_code: String,
    pub count: i64,
    pub total: Money,
}

/// Uses of the day, from the reading-tuvi request counters.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Uses {
    pub basic_readings: u64,
    pub qa: u64,
    pub number_checks: u64,
}

/// 5xx share of one service's requests over the day, in percent.
#[derive(Debug, Clone, PartialEq)]
pub struct ErrorRate {
    pub service: String,
    pub percent: f64,
}

/// What the summary says; a `None` section could not be read in time and is shown as `không lấy được`.
#[derive(Debug, Clone, PartialEq)]
pub struct DailySummary {
    pub day: Date,
    pub revenue: Vec<RevenueLine>,
    pub uses: Option<Uses>,
    pub error_rates: Option<Vec<ErrorRate>>,
    pub alerts_fired: Option<u64>,
}

const UNAVAILABLE: &str = "không lấy được";

/// `2,3%` (one decimal, Vietnamese comma); a share above 0 that rounds to 0 is `<0,1%`.
fn percent(p: f64) -> String {
    let tenths = (p * 10.0).round();
    if tenths < 1.0 {
        return "<0,1%".to_owned();
    }
    let s = format!("{:.1}", tenths / 10.0);
    format!("{}%", s.replace('.', ","))
}

impl DailySummary {
    #[must_use]
    pub fn message(&self) -> String {
        let mut lines = vec![format!(
            "📊 Tổng kết {:02}/{:02}/{}",
            self.day.day(),
            u8::from(self.day.month()),
            self.day.year()
        )];
        lines.push(self.revenue_line());
        for r in &self.revenue {
            lines.push(format!(
                "· {}: {} lần, {}",
                package_label(&r.product_code),
                r.count,
                r.total.display()
            ));
        }
        lines.push(match self.uses {
            Some(u) => format!(
                "Lượt dùng: luận giải {}, hỏi đáp {}, xem số {}",
                u.basic_readings, u.qa, u.number_checks
            ),
            None => format!("Lượt dùng: {UNAVAILABLE}"),
        });
        lines.push(match &self.error_rates {
            None => format!("Lỗi 5xx: {UNAVAILABLE}"),
            Some(rates) => {
                let mut shown: Vec<&ErrorRate> = rates.iter().filter(|r| r.percent > 0.0).collect();
                shown.sort_by(|a, b| {
                    b.percent
                        .total_cmp(&a.percent)
                        .then_with(|| a.service.cmp(&b.service))
                });
                if shown.is_empty() {
                    "Lỗi 5xx: không có".to_owned()
                } else {
                    let parts: Vec<String> = shown
                        .iter()
                        .map(|r| format!("{} {}", r.service, percent(r.percent)))
                        .collect();
                    format!("Lỗi 5xx: {}", parts.join(", "))
                }
            }
        });
        lines.push(match self.alerts_fired {
            Some(0) => "Cảnh báo: không có".to_owned(),
            Some(n) => format!("Cảnh báo: {n} lần"),
            None => format!("Cảnh báo: {UNAVAILABLE}"),
        });
        lines.join("\n")
    }

    /// `Doanh thu: 150.000đ (3 lần nạp)`, with a ` + 5.00 USD` part when `PayPal` paid too.
    fn revenue_line(&self) -> String {
        let count: i64 = self.revenue.iter().map(|r| r.count).sum();
        if count == 0 {
            return "Doanh thu: 0đ (chưa có lần nạp nào)".to_owned();
        }
        let mut currencies: Vec<&str> = self
            .revenue
            .iter()
            .map(|r| r.total.currency.as_str())
            .collect();
        currencies.sort_by_key(|c| (*c != "VND", c.to_string()));
        currencies.dedup();
        let totals: Vec<String> = currencies
            .iter()
            .map(|c| {
                let minor = self
                    .revenue
                    .iter()
                    .filter(|r| r.total.currency == *c)
                    .map(|r| r.total.minor)
                    .sum();
                Money {
                    minor,
                    currency: (*c).to_owned(),
                }
                .display()
            })
            .collect();
        format!("Doanh thu: {} ({count} lần nạp)", totals.join(" + "))
    }
}

#[cfg(test)]
mod tests {
    use time::macros::{date, datetime};

    use super::*;

    fn vnd(minor_dong: i64) -> Money {
        Money {
            minor: minor_dong * 100,
            currency: "VND".into(),
        }
    }

    #[test]
    fn due_day_is_the_latest_day_whose_2359_has_passed() {
        // 23:58 VN on 28/11 = 16:58 UTC: 28/11 is not due yet, 27/11 is
        assert_eq!(
            due_day(datetime!(2026-11-28 16:58 UTC)),
            date!(2026 - 11 - 27)
        );
        assert_eq!(
            due_day(datetime!(2026-11-28 16:59 UTC)),
            date!(2026 - 11 - 28)
        );
        // the next morning (pod was down at 23:59): still 28/11
        assert_eq!(
            due_day(datetime!(2026-11-29 03:00 UTC)),
            date!(2026 - 11 - 28)
        );
    }

    #[test]
    fn day_range_is_local_midnight_to_midnight() {
        let (from, to) = day_range(date!(2026 - 11 - 28));
        assert_eq!(from, datetime!(2026-11-27 17:00 UTC));
        assert_eq!(to, datetime!(2026-11-28 17:00 UTC));
    }

    #[test]
    fn full_summary_text() {
        let s = DailySummary {
            day: date!(2026 - 11 - 28),
            revenue: vec![
                RevenueLine {
                    product_code: "CREDIT_5".into(),
                    count: 2,
                    total: vnd(100_000),
                },
                RevenueLine {
                    product_code: "CREDIT_1".into(),
                    count: 1,
                    total: vnd(10_000),
                },
                RevenueLine {
                    product_code: "PASS_MONTH".into(),
                    count: 1,
                    total: Money {
                        minor: 500,
                        currency: "USD".into(),
                    },
                },
            ],
            uses: Some(Uses {
                basic_readings: 12,
                qa: 5,
                number_checks: 7,
            }),
            error_rates: Some(vec![
                ErrorRate {
                    service: "web".into(),
                    percent: 0.04,
                },
                ErrorRate {
                    service: "reading-tuvi".into(),
                    percent: 2.34,
                },
                ErrorRate {
                    service: "identity-svc".into(),
                    percent: 0.0,
                },
            ]),
            alerts_fired: Some(2),
        };
        assert_eq!(
            s.message(),
            "📊 Tổng kết 28/11/2026\n\
             Doanh thu: 110.000đ + 5.00 USD (4 lần nạp)\n\
             · CREDIT_5 (5 lượt): 2 lần, 100.000đ\n\
             · CREDIT_1 (1 lượt): 1 lần, 10.000đ\n\
             · PASS_MONTH (gói tháng): 1 lần, 5.00 USD\n\
             Lượt dùng: luận giải 12, hỏi đáp 5, xem số 7\n\
             Lỗi 5xx: reading-tuvi 2,3%, web <0,1%\n\
             Cảnh báo: 2 lần"
        );
    }

    #[test]
    fn quiet_day_and_unavailable_sections() {
        let s = DailySummary {
            day: date!(2026 - 01 - 05),
            revenue: vec![],
            uses: None,
            error_rates: Some(vec![ErrorRate {
                service: "web".into(),
                percent: 0.0,
            }]),
            alerts_fired: None,
        };
        assert_eq!(
            s.message(),
            "📊 Tổng kết 05/01/2026\n\
             Doanh thu: 0đ (chưa có lần nạp nào)\n\
             Lượt dùng: không lấy được\n\
             Lỗi 5xx: không có\n\
             Cảnh báo: không lấy được"
        );
        let s = DailySummary {
            error_rates: None,
            alerts_fired: Some(0),
            ..s
        };
        assert!(
            s.message()
                .ends_with("Lỗi 5xx: không lấy được\nCảnh báo: không có"),
            "{}",
            s.message()
        );
    }

    #[test]
    fn percent_rounds_to_one_decimal() {
        assert_eq!(percent(2.34), "2,3%");
        assert_eq!(percent(2.35), "2,4%");
        assert_eq!(percent(100.0), "100,0%");
        assert_eq!(percent(0.05), "0,1%");
        assert_eq!(percent(0.049), "<0,1%");
    }
}
