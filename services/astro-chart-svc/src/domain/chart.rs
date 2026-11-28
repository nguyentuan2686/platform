//! The natal chart as it goes on the wire (camelCase JSON). Angles, speeds and orbs are
//! rounded to 4 decimals by [`round4`] when the value is built.

use serde::Serialize;

use super::angles::norm360;

/// Decimals kept on every angle, speed and orb.
const SCALE: f64 = 10_000.0;

/// `x` rounded to 4 decimals (never `-0.0`).
pub fn round4(x: f64) -> f64 {
    (x * SCALE).round() / SCALE + 0.0
}

/// The house (1..=12) whose cusp interval contains `lon`; intervals may cross 360°.
pub fn house_of(lon: f64, cusps: &[f64; 12]) -> u8 {
    for (i, start) in cusps.iter().enumerate() {
        let end = cusps[(i + 1) % 12];
        let width = norm360(end - start);
        if norm360(lon - start) < width {
            #[allow(clippy::cast_possible_truncation)] // i <= 11
            return i as u8 + 1;
        }
    }
    // Unreachable for cusps going round once; the first house keeps the function total.
    1
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Chart {
    pub school: String,
    pub instant_utc: String,
    pub time_known: bool,
    pub house_system: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub house_fallback: Option<&'static str>,
    pub moon_sign_uncertain: bool,
    pub bodies: Vec<BodyOut>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub angles: Option<AnglesOut>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub houses: Option<Vec<HouseOut>>,
    pub aspects: Vec<AspectOut>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BodyOut {
    pub key: &'static str,
    pub lon: f64,
    pub lat: f64,
    pub sign: &'static str,
    pub deg: f64,
    pub speed: f64,
    pub retrograde: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub house: Option<u8>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AnglesOut {
    pub asc: AngleOut,
    pub mc: AngleOut,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AngleOut {
    pub lon: f64,
    pub sign: &'static str,
    pub deg: f64,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HouseOut {
    pub house: u8,
    pub cusp: f64,
    pub sign: &'static str,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AspectOut {
    pub a: &'static str,
    pub b: &'static str,
    #[serde(rename = "type")]
    pub kind: &'static str,
    pub orb: f64,
    pub applying: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn equal_from(first: f64) -> [f64; 12] {
        let mut c = [0.0; 12];
        for (i, v) in c.iter_mut().enumerate() {
            #[allow(clippy::cast_precision_loss)]
            let step = 30.0 * i as f64;
            *v = norm360(first + step);
        }
        c
    }

    #[test]
    fn rounds_to_four_decimals_and_drops_negative_zero() {
        assert!((round4(12.345_678) - 12.3457).abs() < 1e-12);
        assert!(round4(-0.000_01).is_sign_positive());
        assert!((round4(-1.234_56) - -1.2346).abs() < 1e-12);
    }

    #[test]
    fn house_of_handles_the_wrap_at_360() {
        let c = equal_from(345.0); // house 1 spans 345..15
        assert_eq!(house_of(350.0, &c), 1);
        assert_eq!(house_of(5.0, &c), 1);
        assert_eq!(house_of(15.0, &c), 2);
        assert_eq!(house_of(344.9, &c), 12);
        assert_eq!(house_of(345.0, &c), 1);
    }

    #[test]
    fn house_of_with_uneven_cusps() {
        let c = [
            10.0, 40.0, 75.0, 110.0, 140.0, 170.0, 190.0, 220.0, 255.0, 290.0, 320.0, 350.0,
        ];
        assert_eq!(house_of(100.0, &c), 3);
        assert_eq!(house_of(355.0, &c), 12);
        assert_eq!(house_of(2.0, &c), 12);
    }

    #[test]
    fn serialises_camel_case_and_skips_absent_options() {
        let chart = Chart {
            school: "s".into(),
            instant_utc: "t".into(),
            time_known: false,
            house_system: "placidus",
            house_fallback: None,
            moon_sign_uncertain: false,
            bodies: vec![BodyOut {
                key: "Sun",
                lon: 1.0,
                lat: 0.0,
                sign: "Aries",
                deg: 1.0,
                speed: 1.0,
                retrograde: false,
                house: None,
            }],
            angles: None,
            houses: None,
            aspects: vec![AspectOut {
                a: "Sun",
                b: "Moon",
                kind: "trine",
                orb: 1.0,
                applying: true,
            }],
        };
        let json = serde_json::to_value(&chart).unwrap();
        assert_eq!(json["instantUtc"], "t");
        assert_eq!(json["moonSignUncertain"], false);
        assert_eq!(json["aspects"][0]["type"], "trine");
        let obj = json.as_object().unwrap();
        assert!(!obj.contains_key("houseFallback"));
        assert!(!obj.contains_key("angles"));
        assert!(!obj.contains_key("houses"));
        assert!(!json["bodies"][0].as_object().unwrap().contains_key("house"));
    }
}
