//! Vietnamese school: flow Tứ Hóa on `calendar=VN` charts (CN keeps iztro's). The "each
//! star exactly once per chart" checks live in `golden.rs` and `vn_crosscheck.rs`.

use chart_svc::adapters::engine::IztroAdapter;
use chart_svc::application::birth::NormalizedBirth;
use chart_svc::application::service::{ChartRequest, ChartService};
use chart_svc::domain::calendar::{day_can_chi, Branch, CalendarKind, Stem};
use chart_svc::domain::chart::{Gender, Mutagen, StarKey};
use chart_svc::domain::vn_school::vn_tu_hoa;
use time::macros::date;
use time::Date;

fn request(cal: CalendarKind) -> ChartRequest {
    ChartRequest {
        birth: NormalizedBirth::from_parts(date!(1994 - 03 - 12), Branch::Chen, cal),
        gender: Gender::Male,
    }
}

/// First date from 2026-11-24 on whose day stem is Canh.
fn canh_day() -> Date {
    let mut d = date!(2026 - 11 - 24);
    while day_can_chi(d).stem != Stem::Geng {
        d = d.next_day().unwrap();
    }
    assert_eq!(day_can_chi(d).stem, Stem::Geng);
    assert!(d >= date!(2026 - 11 - 24) && d <= date!(2026 - 12 - 03));
    d
}

#[test]
fn vn_daily_on_a_canh_day_uses_the_vietnamese_tu_hoa() {
    let flow = ChartService::new(IztroAdapter)
        .daily(&request(CalendarKind::Vn), canh_day())
        .unwrap();
    assert_eq!(flow.daily.stem, Stem::Geng);
    assert_eq!(
        flow.daily.mutagens,
        [
            (StarKey::ThaiDuong, Mutagen::Loc),
            (StarKey::VuKhuc, Mutagen::Quyen),
            (StarKey::ThienDong, Mutagen::Khoa),
            (StarKey::ThaiAm, Mutagen::Ky),
        ]
    );
    // Yearly and monthly layers follow the same table for their own stems.
    for layer in [flow.yearly, flow.monthly, flow.daily] {
        assert_eq!(layer.mutagens, vn_tu_hoa(layer.stem), "{:?}", layer.stem);
    }
}

#[test]
fn cn_daily_on_the_same_day_keeps_iztros_tu_hoa() {
    let flow = ChartService::new(IztroAdapter)
        .daily(&request(CalendarKind::Cn), canh_day())
        .unwrap();
    assert_eq!(
        flow.daily.mutagens,
        [
            (StarKey::ThaiDuong, Mutagen::Loc),
            (StarKey::VuKhuc, Mutagen::Quyen),
            (StarKey::ThaiAm, Mutagen::Khoa),
            (StarKey::ThienDong, Mutagen::Ky),
        ]
    );
}
