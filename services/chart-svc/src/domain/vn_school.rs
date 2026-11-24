//! Vietnamese school (phái Việt) rules applied to a `calendar=VN` chart.
//!
//! The brightness table (miếu, vượng, đắc, bình, hãm) and the natal Tứ Hóa table are
//! transcribed from `lasotuvi` (<https://github.com/doanguyen/lasotuvi>, MIT, Copyright (c)
//! 2016 doanguyen) at commit `ace8379a3ea033674e8a9096f5b98a9e2041eace`: `dacTinhSao` in
//! `lasotuvi/DiaBan.py` and the "Tứ Hóa" block (the ten verses of "Số tử vi dưới mắt khoa
//! học") in `lasotuvi/App.py`. Attribution and licence text: `THIRD_PARTY.md`.
//!
//! Only data is taken, nothing of lasotuvi's code. A star that has no row in lasotuvi's
//! table (Thiên Phủ and the minor stars it does not rate) keeps the level derived from the
//! engine.

use crate::domain::calendar::{Branch, CanChi, Stem};
use crate::domain::chart::{AdjStarKey, Brightness, Chart, Flow, Gender, Mutagen, StarKey};

/// One brightness row of `dacTinhSao`, from Tý to Hợi: `M` miếu, `V` vượng, `D` đắc,
/// `B` bình, `H` hãm, `-` no level. Stars without a row are not listed.
const BRIGHTNESS: [(StarKey, &str); 22] = [
    (StarKey::TuVi, "BDMBVMMDMBVB"),
    (StarKey::LiemTrinh, "VDVHMHVDVHMH"),
    (StarKey::ThienDong, "VHMDHDHHMHHD"),
    (StarKey::VuKhuc, "VMVDMHVMVDMH"),
    (StarKey::ThaiDuong, "HDVVVMMDHHHH"),
    (StarKey::ThienCo, "DDHMMVDDVMMH"),
    (StarKey::ThaiAm, "VDHHHHHDVMMM"),
    (StarKey::ThamLang, "HMDHVHHMDHVH"),
    (StarKey::CuMon, "VHVMHHVHDMHD"),
    (StarKey::ThienTuong, "VDMHVDVDMHVD"),
    (StarKey::ThienLuong, "VDVVMHMDVHMH"),
    (StarKey::ThatSat, "MDMHHVMDMHHV"),
    (StarKey::PhaQuan, "MVHHDHMVHHDH"),
    (StarKey::DaLa, "HDHHDHHDHHDH"),
    (StarKey::KinhDuong, "HDHHDHHDHHDH"),
    (StarKey::LinhTinh, "HHDDDDDHHHHH"),
    (StarKey::HoaTinh, "HHDDDDDHHHHH"),
    (StarKey::VanXuong, "HDHDHDHDHHDD"),
    (StarKey::VanKhuc, "HDHDHDHDHHDD"),
    (StarKey::DiaKhong, "HHDHHDHHDHHD"),
    (StarKey::DiaKiep, "HHDHHDHHDHHD"),
    (StarKey::ThienMa, "--D--D------"),
];

/// Natal Tứ Hóa by year stem (Giáp to Quý): Lộc, Quyền, Khoa, Kỵ.
const TU_HOA: [[StarKey; 4]; 10] = [
    [
        StarKey::LiemTrinh,
        StarKey::PhaQuan,
        StarKey::VuKhuc,
        StarKey::ThaiDuong,
    ],
    [
        StarKey::ThienCo,
        StarKey::ThienLuong,
        StarKey::TuVi,
        StarKey::ThaiAm,
    ],
    [
        StarKey::ThienDong,
        StarKey::ThienCo,
        StarKey::VanXuong,
        StarKey::LiemTrinh,
    ],
    [
        StarKey::ThaiAm,
        StarKey::ThienDong,
        StarKey::ThienCo,
        StarKey::CuMon,
    ],
    [
        StarKey::ThamLang,
        StarKey::ThaiAm,
        StarKey::HuuBat,
        StarKey::ThienCo,
    ],
    [
        StarKey::VuKhuc,
        StarKey::ThamLang,
        StarKey::ThienLuong,
        StarKey::VanKhuc,
    ],
    [
        StarKey::ThaiDuong,
        StarKey::VuKhuc,
        StarKey::ThienDong,
        StarKey::ThaiAm,
    ],
    [
        StarKey::CuMon,
        StarKey::ThaiDuong,
        StarKey::VanKhuc,
        StarKey::VanXuong,
    ],
    [
        StarKey::ThienLuong,
        StarKey::TuVi,
        StarKey::ThienPhu,
        StarKey::VuKhuc,
    ],
    [
        StarKey::PhaQuan,
        StarKey::CuMon,
        StarKey::ThaiAm,
        StarKey::ThamLang,
    ],
];

fn row(star: StarKey) -> Option<&'static str> {
    BRIGHTNESS.iter().find(|(s, _)| *s == star).map(|(_, r)| *r)
}

/// Does lasotuvi's table have a brightness row for this star? Only these stars are
/// overridden; the others keep the engine level.
pub fn vn_rated(star: StarKey) -> bool {
    row(star).is_some()
}

/// Vietnamese brightness of `star` in the palace at `branch`; `None` where lasotuvi gives
/// no level (or has no row for the star).
pub fn vn_brightness(star: StarKey, branch: Branch) -> Option<Brightness> {
    // `Branch` variants are declared Tý to Hợi, the column order of the rows.
    match row(star)?.as_bytes()[branch as usize] {
        b'M' => Some(Brightness::Mieu),
        b'V' => Some(Brightness::Vuong),
        b'D' => Some(Brightness::Dac),
        b'B' => Some(Brightness::Binh),
        b'H' => Some(Brightness::Ham),
        _ => None,
    }
}

/// Natal Tứ Hóa of a year stem in Lộc, Quyền, Khoa, Kỵ order.
pub fn vn_tu_hoa(stem: Stem) -> [(StarKey, Mutagen); 4] {
    // `Stem` variants are declared Giáp to Quý, the order of `TU_HOA`.
    let stars = TU_HOA[stem as usize];
    [0, 1, 2, 3].map(|i| (stars[i], Mutagen::ORDER[i]))
}

/// Branch for lasotuvi's 1-based palace number (Tý = 1 to Hợi = 12, any integer wraps).
fn at(n: i32) -> Branch {
    Branch::ALL[usize::try_from((n - 1).rem_euclid(12)).unwrap_or(0)]
}

/// Thiên Khôi by year stem (Giáp to Quý) as lasotuvi's palace numbers (`khoiViet` in
/// `timThienKhoi`, Tý = 1).
const KHOI: [i32; 10] = [2, 1, 12, 10, 8, 1, 8, 7, 6, 4];

/// Thiên Khôi and Thiên Việt by year stem (`timThienKhoi`: Khôi from a table, Việt the
/// mirror `dichCung(5, 5 - khôi)`).
pub fn vn_khoi_viet(stem: Stem) -> (Branch, Branch) {
    let khoi = KHOI[stem as usize];
    (at(khoi), at(10 - khoi))
}

/// Hỏa Tinh and Linh Tinh by year can chi, hour branch and gender (`timHoaLinh`): the
/// start palaces come from the year branch group, the direction from the polarity
/// (gender sign times the year stem's yin/yang) and the hour counts the steps.
pub fn vn_hoa_linh(year: CanChi, hour: Branch, gender: Gender) -> (Branch, Branch) {
    // Start palaces of Hỏa and Linh by year branch (Tý to Hợi).
    let (hoa, linh) = match year.branch {
        Branch::Yin | Branch::Wu | Branch::Xu => (2, 4),
        Branch::Zi | Branch::Chen | Branch::Shen => (3, 11),
        Branch::Si | Branch::You | Branch::Chou => (11, 4),
        Branch::Hai | Branch::Mao | Branch::Wei => (10, 11),
    };
    // Hour number 1 (Tý) to 12 (Hợi).
    let hour = hour as i32 + 1;
    let sign = if gender == Gender::Male { 1 } else { -1 };
    // Yang stems (Giáp, Bính, ...) are the even indices.
    let yang = if (year.stem as usize).is_multiple_of(2) {
        1
    } else {
        -1
    };
    if sign * yang == -1 {
        (at(hoa + 1 - hour), at(linh - 1 + hour))
    } else {
        (at(hoa - 1 + hour), at(linh + 1 - hour))
    }
}

/// Giải Thần sits with Phượng Các, which `lasotuvi` places from the year branch.
pub fn vn_giai_than(year_branch: Branch) -> Branch {
    // Long Trì at Thìn + (n - 1); Phượng Các is its mirror about the Mão-Dậu axis.
    at(-(year_branch as i32 + 1))
}

/// Phá Toái cycles Tỵ, Dậu, Sửu with the year branch number modulo 3 (`timPhaToai`).
pub fn vn_pha_toai(year_branch: Branch) -> Branch {
    match (year_branch as i32 + 1) % 3 {
        0 => at(6),
        1 => at(10),
        _ => at(2),
    }
}

/// Rewrites a flow's three layers with the Vietnamese Tứ Hóa of each layer's stem.
pub fn apply_vn_flow(flow: &mut Flow) {
    for layer in [&mut flow.yearly, &mut flow.monthly, &mut flow.daily] {
        layer.mutagens = vn_tu_hoa(layer.stem);
    }
}

/// Moves `star` to the palace at `branch`: appended to its minor stars, after the engine's order.
fn move_minor(chart: &mut Chart, star: StarKey, branch: Branch) {
    let mut moved = None;
    for palace in &mut chart.palaces {
        if let Some(i) = palace.minor.iter().position(|s| s.key == star) {
            moved = Some(palace.minor.remove(i));
        }
    }
    if let Some(star) = moved {
        if let Some(p) = chart.palaces.iter_mut().find(|p| p.branch == branch) {
            p.minor.push(star);
        }
    }
}

/// Moves an adjective star to the palace at `branch`.
fn move_adjective(chart: &mut Chart, star: AdjStarKey, branch: Branch) {
    let mut found = false;
    for palace in &mut chart.palaces {
        let before = palace.adjective.len();
        palace.adjective.retain(|a| *a != star);
        found |= palace.adjective.len() != before;
    }
    if found {
        if let Some(p) = chart.palaces.iter_mut().find(|p| p.branch == branch) {
            p.adjective.push(star);
        }
    }
}

/// Lưu Hà by year stem (Giáp to Quý) as lasotuvi's palace numbers (`maTranLuuHa`, Tý = 1).
const LUU_HA: [i32; 10] = [10, 11, 8, 5, 6, 7, 9, 4, 12, 3];

/// Lưu Hà by year stem (`timLuuTru`; lasotuvi marks this table as unverified).
pub fn vn_luu_ha(stem: Stem) -> Branch {
    at(LUU_HA[stem as usize])
}

/// Thiên Giải: Thân is month 1 and the count skips a palace per month (`2 * month - 2`).
pub fn vn_thien_giai(month: u8) -> Branch {
    at(9 + 2 * i32::from(month) - 2)
}

/// Đẩu Quân: from the year branch, back by the lunar month and forward by the hour.
pub fn vn_dau_quan(year_branch: Branch, month: u8, hour: Branch) -> Branch {
    at(year_branch as i32 + 1 - i32::from(month) + hour as i32 + 1)
}

/// The palace `n` steps after `branch` (negative steps go back).
fn after(branch: Branch, n: i32) -> Branch {
    at(branch as i32 + 1 + n)
}

/// The lunar month a chart is cast on: a leap month counts as the next month from day 16
/// (the engine's `fixLeap`), as for Mệnh.
fn chart_month(chart: &Chart) -> u8 {
    let l = chart.lunar;
    if l.leap && l.day > 15 {
        l.month % 12 + 1
    } else {
        l.month
    }
}

/// The palace branch holding a minor star of the chart.
fn minor_branch(chart: &Chart, star: StarKey) -> Option<Branch> {
    chart
        .palaces
        .iter()
        .find(|p| p.minor.iter().any(|s| s.key == star))
        .map(|p| p.branch)
}

/// The palace branch holding an adjective star of the chart.
fn adjective_branch(chart: &Chart, star: AdjStarKey) -> Option<Branch> {
    chart
        .palaces
        .iter()
        .find(|p| p.adjective.contains(&star))
        .map(|p| p.branch)
}

/// Adds an adjective star to the palace at `branch`, after the stars already there.
fn add_adjective(chart: &mut Chart, star: AdjStarKey, branch: Branch) {
    if let Some(p) = chart.palaces.iter_mut().find(|p| p.branch == branch) {
        p.adjective.push(star);
    }
}

/// Replaces an adjective star by another in the same palace and position.
fn relabel_adjective(chart: &mut Chart, from: AdjStarKey, to: AdjStarKey) {
    for palace in &mut chart.palaces {
        for star in &mut palace.adjective {
            if *star == from {
                *star = to;
            }
        }
    }
}

/// Adds the stars only the Vietnamese school places. Their anchors (Kình Dương, Tả Phụ,
/// Thiên Riêu) agree between the schools, so they are read from the chart.
fn add_vn_only_stars(chart: &mut Chart) {
    let year = chart.year;
    let month = chart_month(chart);
    let kinh = minor_branch(chart, StarKey::KinhDuong);
    let ta_phu = minor_branch(chart, StarKey::TaPhu);
    let rieu = adjective_branch(chart, AdjStarKey::ThienRieu);
    let van_tinh = kinh.map(|k| after(k, 2));
    let duong_phu = van_tinh.map(|v| after(v, 2));
    let stars = [
        (AdjStarKey::ThienGiai, Some(vn_thien_giai(month))),
        (AdjStarKey::DiaGiai, ta_phu.map(|t| after(t, 3))),
        (AdjStarKey::ThienY, rieu),
        (AdjStarKey::QuocAn, duong_phu.map(|d| after(d, 3))),
        (AdjStarKey::DuongPhu, duong_phu),
        (AdjStarKey::VanTinh, van_tinh),
        (AdjStarKey::LuuHa, Some(vn_luu_ha(year.stem))),
        (
            AdjStarKey::DauQuan,
            Some(vn_dau_quan(year.branch, month, chart.hour_branch)),
        ),
        (AdjStarKey::ThienLa, Some(Branch::Chen)),
        (AdjStarKey::DiaVong, Some(Branch::Xu)),
    ];
    for (star, branch) in stars {
        if let Some(branch) = branch {
            add_adjective(chart, star, branch);
        }
    }
}

/// Applies the Vietnamese school to a chart cast by the engine: the Vietnamese placement of
/// Khôi/Việt, Hỏa/Linh, Giải Thần and Phá Toái, brightness from [`vn_brightness`] and the
/// natal Tứ Hóa of the year stem. Flows are handled by [`apply_vn_flow`].
pub fn apply_vn_school(chart: &mut Chart) {
    let (khoi, viet) = vn_khoi_viet(chart.year.stem);
    let (hoa, linh) = vn_hoa_linh(chart.year, chart.hour_branch, chart.gender);
    move_minor(chart, StarKey::ThienKhoi, khoi);
    move_minor(chart, StarKey::ThienViet, viet);
    move_minor(chart, StarKey::HoaTinh, hoa);
    move_minor(chart, StarKey::LinhTinh, linh);
    move_adjective(chart, AdjStarKey::GiaiThan, vn_giai_than(chart.year.branch));
    move_adjective(chart, AdjStarKey::PhaToai, vn_pha_toai(chart.year.branch));
    // lasotuvi's Đào Hoa is iztro's Hàm Trì (same palace in all 50 cross-check charts; see
    // docs/tuvi/vn-school-diff.md), so the star is relabelled, not added.
    relabel_adjective(chart, AdjStarKey::HamTri, AdjStarKey::DaoHoa);
    add_vn_only_stars(chart);
    let tu_hoa = vn_tu_hoa(chart.year.stem);
    for palace in &mut chart.palaces {
        let branch = palace.branch;
        for star in palace.major.iter_mut().chain(&mut palace.minor) {
            if vn_rated(star.key) {
                star.brightness = vn_brightness(star.key, branch);
            }
            star.mutagen = tu_hoa
                .iter()
                .find(|(key, _)| *key == star.key)
                .map(|&(_, mutagen)| mutagen);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use Brightness::{Binh, Dac, Ham, Mieu, Vuong};

    fn at(star: StarKey, branch: Branch) -> Option<Brightness> {
        vn_brightness(star, branch)
    }

    #[test]
    fn tu_hoa_of_all_ten_stems() {
        use Mutagen::{Khoa, Ky, Loc, Quyen};
        use StarKey::*;
        let want: [[(StarKey, Mutagen); 4]; 10] = [
            [
                (LiemTrinh, Loc),
                (PhaQuan, Quyen),
                (VuKhuc, Khoa),
                (ThaiDuong, Ky),
            ],
            [
                (ThienCo, Loc),
                (ThienLuong, Quyen),
                (TuVi, Khoa),
                (ThaiAm, Ky),
            ],
            [
                (ThienDong, Loc),
                (ThienCo, Quyen),
                (VanXuong, Khoa),
                (LiemTrinh, Ky),
            ],
            [
                (ThaiAm, Loc),
                (ThienDong, Quyen),
                (ThienCo, Khoa),
                (CuMon, Ky),
            ],
            [
                (ThamLang, Loc),
                (ThaiAm, Quyen),
                (HuuBat, Khoa),
                (ThienCo, Ky),
            ],
            [
                (VuKhuc, Loc),
                (ThamLang, Quyen),
                (ThienLuong, Khoa),
                (VanKhuc, Ky),
            ],
            [
                (ThaiDuong, Loc),
                (VuKhuc, Quyen),
                (ThienDong, Khoa),
                (ThaiAm, Ky),
            ],
            [
                (CuMon, Loc),
                (ThaiDuong, Quyen),
                (VanKhuc, Khoa),
                (VanXuong, Ky),
            ],
            [
                (ThienLuong, Loc),
                (TuVi, Quyen),
                (ThienPhu, Khoa),
                (VuKhuc, Ky),
            ],
            [
                (PhaQuan, Loc),
                (CuMon, Quyen),
                (ThaiAm, Khoa),
                (ThamLang, Ky),
            ],
        ];
        for (stem, want) in Stem::ALL.iter().zip(want) {
            assert_eq!(vn_tu_hoa(*stem), want, "{stem:?}");
        }
    }

    #[test]
    fn brightness_spot_values_from_dac_tinh_sao() {
        use StarKey::*;
        assert_eq!(at(ThienCo, Branch::You), Some(Mieu));
        assert_eq!(at(TuVi, Branch::Zi), Some(Binh));
        assert_eq!(at(TuVi, Branch::Yin), Some(Mieu));
        assert_eq!(at(LiemTrinh, Branch::Chen), Some(Mieu));
        assert_eq!(at(ThienDong, Branch::Yin), Some(Mieu));
        assert_eq!(at(VuKhuc, Branch::Chou), Some(Mieu));
        assert_eq!(at(ThaiDuong, Branch::Si), Some(Mieu));
        assert_eq!(at(ThaiAm, Branch::Hai), Some(Mieu));
        assert_eq!(at(ThamLang, Branch::Chou), Some(Mieu));
        assert_eq!(at(CuMon, Branch::Mao), Some(Mieu));
        assert_eq!(at(ThienTuong, Branch::Yin), Some(Mieu));
        assert_eq!(at(ThienLuong, Branch::Chen), Some(Mieu));
        assert_eq!(at(ThatSat, Branch::Zi), Some(Mieu));
        assert_eq!(at(PhaQuan, Branch::Zi), Some(Mieu));
        assert_eq!(at(ThaiDuong, Branch::Zi), Some(Ham));
        assert_eq!(at(ThienCo, Branch::Zi), Some(Dac));
        assert_eq!(at(ThienCo, Branch::Si), Some(Vuong));
        assert_eq!(at(KinhDuong, Branch::Chou), Some(Dac));
        assert_eq!(at(DaLa, Branch::Zi), Some(Ham));
        assert_eq!(at(HoaTinh, Branch::Yin), Some(Dac));
        assert_eq!(at(LinhTinh, Branch::Zi), Some(Ham));
        assert_eq!(at(VanXuong, Branch::Chou), Some(Dac));
        assert_eq!(at(VanKhuc, Branch::Zi), Some(Ham));
        assert_eq!(at(DiaKhong, Branch::Yin), Some(Dac));
        assert_eq!(at(DiaKiep, Branch::Zi), Some(Ham));
    }

    #[test]
    fn thien_ma_is_rated_only_at_dan_and_ti() {
        for b in &Branch::ALL {
            let want = matches!(*b, Branch::Yin | Branch::Si).then_some(Dac);
            assert_eq!(at(StarKey::ThienMa, *b), want, "{b:?}");
        }
    }

    #[test]
    fn unrated_stars_have_no_vn_level() {
        for star in [
            StarKey::ThienPhu,
            StarKey::TaPhu,
            StarKey::LocTon,
            StarKey::ThienKhoi,
        ] {
            assert!(!vn_rated(star), "{star:?}");
            assert_eq!(at(star, Branch::Zi), None);
        }
        for star in [StarKey::TuVi, StarKey::DiaKhong, StarKey::ThienMa] {
            assert!(vn_rated(star), "{star:?}");
        }
    }

    #[test]
    fn every_row_has_twelve_known_cells() {
        for (star, row) in BRIGHTNESS {
            assert_eq!(row.len(), 12, "{star:?}");
            assert!(row.bytes().all(|c| b"MVDBH-".contains(&c)), "{star:?}");
        }
    }

    #[test]
    fn khoi_viet_of_all_ten_stems() {
        use Branch::{Chou, Hai, Mao, Shen, Si, Wei, Wu, Yin, You, Zi};
        let want = [
            (Chou, Wei),
            (Zi, Shen),
            (Hai, You),
            (You, Hai),
            (Wei, Chou),
            (Zi, Shen),
            (Wei, Chou),
            (Wu, Yin),
            (Si, Mao),
            (Mao, Si),
        ];
        for (stem, want) in Stem::ALL.iter().zip(want) {
            assert_eq!(vn_khoi_viet(*stem), want, "{stem:?}");
        }
    }

    #[test]
    fn hoa_linh_follow_polarity_and_hour() {
        use Branch::{Chou, Hai, Mao, Si, Wei, Yin, Zi};
        let jia_zi = CanChi {
            stem: Stem::Jia,
            branch: Zi,
        };
        // Yang year, male: "+" polarity, Hỏa counts forward from Dần - 1, Linh backward.
        assert_eq!(vn_hoa_linh(jia_zi, Zi, Gender::Male), (Yin, Branch::Xu));
        assert_eq!(vn_hoa_linh(jia_zi, Mao, Gender::Male), (Si, Wei));
        // Same chart, female: "-" polarity runs the other way.
        assert_eq!(vn_hoa_linh(jia_zi, Mao, Gender::Female), (Hai, Chou));
    }

    #[test]
    fn giai_than_follows_phuong_cac_and_pha_toai_cycles() {
        use Branch::{Chen, Chou, Si, Wu, Xu, Yin, You, Zi};
        assert_eq!(vn_giai_than(Zi), Xu);
        assert_eq!(vn_giai_than(Chou), You);
        assert_eq!(vn_giai_than(Wu), Chen);
        assert_eq!(vn_pha_toai(Zi), You);
        assert_eq!(vn_pha_toai(Chou), Chou);
        assert_eq!(vn_pha_toai(Yin), Si);
    }

    #[test]
    fn luu_ha_thien_giai_and_dau_quan_follow_lasotuvi() {
        use Branch::{Chen, Hai, Mao, Shen, Si, Wei, Wu, Xu, Yin, You, Zi};
        let luu_ha = [You, Xu, Wei, Chen, Si, Wu, Shen, Mao, Hai, Yin];
        for (stem, want) in Stem::ALL.iter().zip(luu_ha) {
            assert_eq!(vn_luu_ha(*stem), want, "{stem:?}");
        }
        assert_eq!(vn_thien_giai(1), Shen);
        assert_eq!(vn_thien_giai(2), Xu);
        assert_eq!(vn_thien_giai(7), super::at(9 + 12), "month 7 wraps");
        assert_eq!(vn_dau_quan(Zi, 1, Zi), Zi);
        assert_eq!(vn_dau_quan(Wu, 3, Mao), super::at(7 - 3 + 4));
    }
}
