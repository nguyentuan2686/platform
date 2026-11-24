//! Zi Wei Dou Shu engine adapter over `x-iztro` (a Rust port of JS iztro, default config).
//!
//! The engine casts by lunar date with its own calendar (the official Chinese one). The
//! adapter always feeds it the lunar date chart-svc computed (VN or CN), so a VN chart
//! follows the Vietnamese calendar. A natal chart depends on the lunar date only through
//! the year's can chi, the month index (a leap month counts as its month for days 1-15
//! and as the next month for days 16-30, iztro's `fixLeap`) and the day. When the engine's
//! calendar cannot take the date verbatim, an equivalent date with the same three values
//! is fed instead; see [`engine_lunar_date`].
//!
//! The flow of a day is cast the same way: the engine gets the date whose engine lunar
//! date equals the day's lunar date in the chart's calendar, so lưu nguyệt and lưu nhật
//! follow the VN calendar too. The flow layers depend on that date only through the
//! year's can chi, the month (with the same leap rule) and the day, except the daily stem,
//! which is the real day's can chi; the adapter sets it and its Tứ Hóa.

use crate::application::birth::NormalizedBirth;
use crate::application::ports::ZiweiEngine;
use crate::domain::calendar::{
    day_can_chi, solar_to_lunar, year_can_chi, Branch, CalendarKind, LunarDate, Stem,
};
use crate::domain::chart::{
    Brightness, Chart, Cuc, Element, Flow, FlowLayer, Gender, Mutagen, Palace, PalaceKey,
    StarInPalace, StarKey,
};
use anyhow::{anyhow, Context};
use time::Date;
use x_iztro as iz;

/// `school` of a chart cast on the Vietnamese calendar.
pub const SCHOOL_VN: &str = "iztro-default+vn-calendar";
/// `school` of a chart cast on the Chinese calendar.
pub const SCHOOL_CN: &str = "iztro-default";

/// [`ZiweiEngine`] backed by `x-iztro` with iztro's default configuration.
#[derive(Debug, Clone, Copy, Default)]
pub struct IztroAdapter;

impl ZiweiEngine for IztroAdapter {
    fn chart(&self, b: &NormalizedBirth, g: Gender) -> anyhow::Result<Chart> {
        let astrolabe = astrolabe(b, g)?;
        map_chart(b, g, &astrolabe)
    }

    fn flow(&self, b: &NormalizedBirth, g: Gender, day: Date) -> anyhow::Result<Flow> {
        let astrolabe = astrolabe(b, g)?;
        let lunar = solar_to_lunar(day, b.calendar);
        let engine_lunar = engine_lunar_date(lunar)?;
        let engine_day = iz::lunar_to_solar(
            engine_lunar.year,
            engine_lunar.month,
            engine_lunar.day,
            engine_lunar.leap,
        )
        .with_context(|| format!("engine date for flow day {day}"))?;
        let target = format!(
            "{}-{}-{}",
            engine_day.solar_year, engine_day.solar_month, engine_day.solar_day
        );
        let horoscope = iz::get_horoscope(&astrolabe, &target, 0, iz::Language::ZhCN)
            .with_context(|| format!("engine horoscope for {target}"))?;
        let day_can_chi = day_can_chi(day);
        let mut daily = map_layer(&horoscope.daily)?;
        daily.stem = day_can_chi.stem;
        daily.mutagens = mutagens_of(day_can_chi.stem)?;
        Ok(Flow {
            date: day,
            lunar,
            day: day_can_chi,
            yearly: map_layer(&horoscope.yearly.base)?,
            monthly: map_layer(&horoscope.monthly)?,
            daily,
        })
    }
}

/// A lunar date in the engine's calendar: year, month, day and whether the month is the
/// leap month.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EngineLunar {
    pub year: i64,
    pub month: u32,
    pub day: u32,
    pub leap: bool,
}

/// Days in lunar month `month` (leap or not) of `year` in the engine's calendar.
fn engine_month_days(year: i64, month: u32, leap: bool) -> Option<u32> {
    iz::lunar_months(year)
        .ok()?
        .into_iter()
        .find(|m| m.month == month && m.is_leap == leap)
        .map(|m| m.day_count)
}

/// How many 60-year cycles either way [`engine_lunar_date`] searches (the engine covers
/// years 1-9999).
const SHIFT_CYCLES: i64 = 30;

/// The lunar date to feed the engine for lunar date `l` (a birth or a flow day).
///
/// 1. `l` as is, when the engine's calendar has that month and day.
/// 2. A leap month the engine's calendar lacks (Vietnam had leap months China did not,
///    e.g. 1985) becomes the plain month it charts as: month `m` for days 1-15, `m + 1`
///    for days 16-30.
/// 3. A day the engine's month lacks (day 30 where its month has 29 days) moves by
///    multiples of 60 years, which keeps the year's can chi, to a year whose month has
///    that day.
pub fn engine_lunar_date(l: LunarDate) -> anyhow::Result<EngineLunar> {
    let (month, day) = (u32::from(l.month), u32::from(l.day));
    let year = i64::from(l.year);
    if engine_month_days(year, month, l.leap).is_some_and(|n| day <= n) {
        return Ok(EngineLunar {
            year,
            month,
            day,
            leap: l.leap,
        });
    }
    let month = if l.leap && day > 15 { month + 1 } else { month };
    if month > 12 {
        return Err(anyhow!(
            "no engine equivalent for lunar {}-{}-{} (leap 12th month, second half)",
            l.year,
            l.month,
            l.day
        ));
    }
    (0..=SHIFT_CYCLES)
        .flat_map(|k| [year - 60 * k, year + 60 * k])
        .find(|&y| engine_month_days(y, month, false).is_some_and(|n| day <= n))
        .map(|year| EngineLunar {
            year,
            month,
            day,
            leap: false,
        })
        .ok_or_else(|| {
            anyhow!(
                "no engine equivalent for lunar {}-{}-{}",
                l.year,
                l.month,
                l.day
            )
        })
}

/// iztro's time index of an hour branch: Tý 0 (early Zi; late Zi, 12, never occurs since
/// 23:xx counts as the next day), Sửu 1, ..., Hợi 11.
fn time_index(h: Branch) -> u8 {
    match h {
        Branch::Zi => 0,
        Branch::Chou => 1,
        Branch::Yin => 2,
        Branch::Mao => 3,
        Branch::Chen => 4,
        Branch::Si => 5,
        Branch::Wu => 6,
        Branch::Wei => 7,
        Branch::Shen => 8,
        Branch::You => 9,
        Branch::Xu => 10,
        Branch::Hai => 11,
    }
}

fn astrolabe(b: &NormalizedBirth, g: Gender) -> anyhow::Result<iz::Astrolabe> {
    let l = engine_lunar_date(b.lunar)?;
    let date = format!("{}-{}-{}", l.year, l.month, l.day);
    let gender = match g {
        Gender::Male => iz::Gender::Male,
        Gender::Female => iz::Gender::Female,
    };
    // fixLeap = true, as iztro's default.
    let leap = iz::LeapMonth::from_flags(l.leap, true);
    iz::by_lunar(
        &date,
        time_index(b.hour_branch),
        gender,
        leap,
        iz::Language::ZhCN,
        iz::Config::default(),
    )
    .with_context(|| format!("engine chart for lunar {date} (leap {})", l.leap))
}

fn map_chart(b: &NormalizedBirth, g: Gender, a: &iz::Astrolabe) -> anyhow::Result<Chart> {
    let palaces: Vec<Palace> = a
        .palaces
        .iter()
        .map(map_palace)
        .collect::<anyhow::Result<_>>()?;
    let palaces: [Palace; 12] = palaces
        .try_into()
        .map_err(|_| anyhow!("engine returned other than 12 palaces"))?;
    let than = a
        .palaces
        .iter()
        .find(|p| p.is_body_palace)
        .ok_or_else(|| anyhow!("engine chart has no body palace"))?;
    Ok(Chart {
        lunar: b.lunar,
        year: year_can_chi(b.lunar.year),
        hour_branch: b.hour_branch,
        gender: g,
        cuc: cuc(a.five_elements_class),
        menh_chu: star(a.soul)?,
        than_chu: star(a.body)?,
        than_palace: palace_key(than.name),
        palaces,
        school: match b.calendar {
            CalendarKind::Vn => SCHOOL_VN,
            CalendarKind::Cn => SCHOOL_CN,
        },
    })
}

fn map_palace(p: &iz::PalaceData) -> anyhow::Result<Palace> {
    let stars = |list: &[iz::Star]| -> anyhow::Result<Vec<StarInPalace>> {
        list.iter()
            .map(|s| {
                Ok(StarInPalace {
                    key: star(s.key)?,
                    brightness: s.brightness.map(brightness),
                    mutagen: s.mutagen.map(mutagen),
                })
            })
            .collect()
    };
    let age = |n: u32| u8::try_from(n).context("decade age out of range");
    Ok(Palace {
        key: palace_key(p.name),
        branch: branch(p.earthly_branch),
        stem: stem(p.heavenly_stem),
        major: stars(&p.major_stars)?,
        minor: stars(&p.minor_stars)?,
        decade: (age(p.decadal.range.0)?, age(p.decadal.range.1)?),
    })
}

fn map_layer(h: &iz::HoroscopeItem) -> anyhow::Result<FlowLayer> {
    Ok(FlowLayer {
        // Engine palace index 0 is the Dần (Yin) palace.
        palace_branch: Branch::ALL[(h.index + 2) % 12],
        stem: stem(h.heavenly_stem),
        mutagens: tu_hoa(&h.mutagen)?,
    })
}

/// Stars in Lộc, Quyền, Khoa, Kỵ order, paired with their transformation.
fn tu_hoa(stars: &[iz::StarKey]) -> anyhow::Result<[(StarKey, Mutagen); 4]> {
    let keys: Vec<StarKey> = stars
        .iter()
        .map(|&k| star(k))
        .collect::<anyhow::Result<_>>()?;
    let keys: [StarKey; 4] = keys
        .try_into()
        .map_err(|_| anyhow!("engine Tứ Hóa has other than 4 stars"))?;
    Ok([0, 1, 2, 3].map(|i| (keys[i], Mutagen::ORDER[i])))
}

/// The engine's (default) Tứ Hóa of a stem.
fn mutagens_of(s: Stem) -> anyhow::Result<[(StarKey, Mutagen); 4]> {
    let i = Stem::ALL.iter().position(|&x| x == s).unwrap_or_default();
    tu_hoa(&iz::Config::default().mutagens_of(iz::HeavenlyStem::from_index(i)))
}

/// Maps an engine star. Only the 14 major and 14 minor stars appear in a chart; any other
/// key is an error (a golden test covers every star the engine places).
pub fn star(k: iz::StarKey) -> anyhow::Result<StarKey> {
    use iz::StarKey as S;
    Ok(match k {
        S::ZiweiMaj => StarKey::TuVi,
        S::TianjiMaj => StarKey::ThienCo,
        S::TaiyangMaj => StarKey::ThaiDuong,
        S::WuquMaj => StarKey::VuKhuc,
        S::TiantongMaj => StarKey::ThienDong,
        S::LianzhenMaj => StarKey::LiemTrinh,
        S::TianfuMaj => StarKey::ThienPhu,
        S::TaiyinMaj => StarKey::ThaiAm,
        S::TanlangMaj => StarKey::ThamLang,
        S::JumenMaj => StarKey::CuMon,
        S::TianxiangMaj => StarKey::ThienTuong,
        S::TianliangMaj => StarKey::ThienLuong,
        S::QishaMaj => StarKey::ThatSat,
        S::PojunMaj => StarKey::PhaQuan,
        S::ZuofuMin => StarKey::TaPhu,
        S::YoubiMin => StarKey::HuuBat,
        S::WenchangMin => StarKey::VanXuong,
        S::WenquMin => StarKey::VanKhuc,
        S::LucunMin => StarKey::LocTon,
        S::TianmaMin => StarKey::ThienMa,
        S::QingyangMin => StarKey::KinhDuong,
        S::TuoluoMin => StarKey::DaLa,
        S::HuoxingMin => StarKey::HoaTinh,
        S::LingxingMin => StarKey::LinhTinh,
        S::DikongMin => StarKey::DiaKhong,
        S::DijieMin => StarKey::DiaKiep,
        S::TiankuiMin => StarKey::ThienKhoi,
        S::TianyueMin => StarKey::ThienViet,
        other => return Err(anyhow!("unmapped engine star {other:?}")),
    })
}

/// iztro's seven brightness levels on the Vietnamese five-level scale.
pub fn brightness(b: iz::Brightness) -> Brightness {
    match b {
        iz::Brightness::Miao => Brightness::Mieu,
        iz::Brightness::Wang => Brightness::Vuong,
        iz::Brightness::De | iz::Brightness::Li => Brightness::Dac,
        iz::Brightness::Ping | iz::Brightness::Bu => Brightness::Binh,
        iz::Brightness::Xian => Brightness::Ham,
    }
}

fn mutagen(m: iz::Mutagen) -> Mutagen {
    match m {
        iz::Mutagen::Lu => Mutagen::Loc,
        iz::Mutagen::Quan => Mutagen::Quyen,
        iz::Mutagen::Ke => Mutagen::Khoa,
        iz::Mutagen::Ji => Mutagen::Ky,
    }
}

fn palace_key(p: iz::Palace) -> PalaceKey {
    match p {
        iz::Palace::Soul => PalaceKey::Menh,
        iz::Palace::Parents => PalaceKey::PhuMau,
        iz::Palace::Spirit => PalaceKey::PhucDuc,
        iz::Palace::Property => PalaceKey::DienTrach,
        iz::Palace::Career => PalaceKey::QuanLoc,
        iz::Palace::Friends => PalaceKey::NoBoc,
        iz::Palace::Surface => PalaceKey::ThienDi,
        iz::Palace::Health => PalaceKey::TatAch,
        iz::Palace::Wealth => PalaceKey::TaiBach,
        iz::Palace::Children => PalaceKey::TuTuc,
        iz::Palace::Spouse => PalaceKey::PhuThe,
        iz::Palace::Siblings => PalaceKey::HuynhDe,
    }
}

fn cuc(c: iz::FiveElementsClass) -> Cuc {
    let (element, number) = match c {
        iz::FiveElementsClass::Water2nd => (Element::Thuy, 2),
        iz::FiveElementsClass::Wood3rd => (Element::Moc, 3),
        iz::FiveElementsClass::Metal4th => (Element::Kim, 4),
        iz::FiveElementsClass::Earth5th => (Element::Tho, 5),
        iz::FiveElementsClass::Fire6th => (Element::Hoa, 6),
    };
    Cuc { element, number }
}

fn stem(s: iz::HeavenlyStem) -> Stem {
    match s {
        iz::HeavenlyStem::Jia => Stem::Jia,
        iz::HeavenlyStem::Yi => Stem::Yi,
        iz::HeavenlyStem::Bing => Stem::Bing,
        iz::HeavenlyStem::Ding => Stem::Ding,
        iz::HeavenlyStem::Wu => Stem::Wu,
        iz::HeavenlyStem::Ji => Stem::Ji,
        iz::HeavenlyStem::Geng => Stem::Geng,
        iz::HeavenlyStem::Xin => Stem::Xin,
        iz::HeavenlyStem::Ren => Stem::Ren,
        iz::HeavenlyStem::Gui => Stem::Gui,
    }
}

fn branch(b: iz::EarthlyBranch) -> Branch {
    match b {
        iz::EarthlyBranch::Zi => Branch::Zi,
        iz::EarthlyBranch::Chou => Branch::Chou,
        iz::EarthlyBranch::Yin => Branch::Yin,
        iz::EarthlyBranch::Mao => Branch::Mao,
        iz::EarthlyBranch::Chen => Branch::Chen,
        iz::EarthlyBranch::Si => Branch::Si,
        iz::EarthlyBranch::Wu => Branch::Wu,
        iz::EarthlyBranch::Wei => Branch::Wei,
        iz::EarthlyBranch::Shen => Branch::Shen,
        iz::EarthlyBranch::You => Branch::You,
        iz::EarthlyBranch::Xu => Branch::Xu,
        iz::EarthlyBranch::Hai => Branch::Hai,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;
    use time::Month;

    fn lunar(year: i32, month: u8, day: u8, leap: bool) -> LunarDate {
        LunarDate {
            year,
            month,
            day,
            leap,
        }
    }

    #[test]
    fn every_major_and_minor_engine_star_maps_to_a_distinct_key() {
        use iz::StarKey as S;
        let engine = [
            S::ZiweiMaj,
            S::TianjiMaj,
            S::TaiyangMaj,
            S::WuquMaj,
            S::TiantongMaj,
            S::LianzhenMaj,
            S::TianfuMaj,
            S::TaiyinMaj,
            S::TanlangMaj,
            S::JumenMaj,
            S::TianxiangMaj,
            S::TianliangMaj,
            S::QishaMaj,
            S::PojunMaj,
            S::ZuofuMin,
            S::YoubiMin,
            S::WenchangMin,
            S::WenquMin,
            S::LucunMin,
            S::TianmaMin,
            S::QingyangMin,
            S::TuoluoMin,
            S::HuoxingMin,
            S::LingxingMin,
            S::DikongMin,
            S::DijieMin,
            S::TiankuiMin,
            S::TianyueMin,
        ];
        let mapped: HashSet<StarKey> = engine.iter().map(|&k| star(k).unwrap()).collect();
        assert_eq!(mapped.len(), StarKey::ALL.len());
    }

    #[test]
    fn an_adjective_star_is_an_error_not_a_panic() {
        assert!(star(iz::StarKey::Tianxi).is_err());
    }

    #[test]
    fn brightness_seven_to_five() {
        use iz::Brightness as B;
        let got: Vec<_> = [B::Miao, B::Wang, B::De, B::Li, B::Ping, B::Bu, B::Xian]
            .map(|b| brightness(b).key())
            .to_vec();
        assert_eq!(got, ["Mieu", "Vuong", "Dac", "Dac", "Binh", "Binh", "Ham"]);
    }

    #[test]
    fn engine_date_is_verbatim_when_the_engine_has_it() {
        let l = engine_lunar_date(lunar(2023, 2, 20, true)).unwrap();
        assert_eq!((l.year, l.month, l.day, l.leap), (2023, 2, 20, true));
    }

    #[test]
    fn vn_only_leap_month_charts_as_its_plain_month() {
        // 1985 had a leap 2nd month in Vietnam only.
        let first = engine_lunar_date(lunar(1985, 2, 10, true)).unwrap();
        assert_eq!(
            (first.year, first.month, first.day, first.leap),
            (1985, 2, 10, false)
        );
        let second = engine_lunar_date(lunar(1985, 2, 21, true)).unwrap();
        assert_eq!(
            (second.year, second.month, second.day, second.leap),
            (1985, 3, 21, false)
        );
    }

    #[test]
    fn missing_day_moves_by_sixty_years() {
        // Find a 29-day engine month and ask for its day 30.
        let (year, month) = (1990..2000)
            .flat_map(|y| (1..=12).map(move |m| (y, m)))
            .find(|&(y, m)| engine_month_days(i64::from(y), m, false) == Some(29))
            .unwrap();
        let l = engine_lunar_date(lunar(year, u8::try_from(month).unwrap(), 30, false)).unwrap();
        assert_eq!((l.month, l.day, l.leap), (month, 30, false));
        assert_eq!((l.year - i64::from(year)).rem_euclid(60), 0);
        assert_ne!(l.year, i64::from(year));
    }

    #[test]
    fn every_vn_and_cn_day_1900_2100_has_an_engine_date() {
        let mut day = Date::from_calendar_date(1900, Month::January, 1).unwrap();
        let end = Date::from_calendar_date(2100, Month::December, 31).unwrap();
        let mut failures = vec![];
        let mut seen = HashSet::new();
        while day <= end {
            for cal in [CalendarKind::Vn, CalendarKind::Cn] {
                let l = solar_to_lunar(day, cal);
                if seen.insert(l) && engine_lunar_date(l).is_err() {
                    failures.push(format!("{cal:?} {l:?}"));
                }
            }
            day = day.next_day().unwrap();
        }
        assert!(failures.is_empty(), "{failures:?}");
    }

    #[test]
    fn time_index_of_branches() {
        let got: Vec<u8> = Branch::ALL.iter().map(|&b| time_index(b)).collect();
        assert_eq!(got, (0..12).collect::<Vec<u8>>());
    }
}
