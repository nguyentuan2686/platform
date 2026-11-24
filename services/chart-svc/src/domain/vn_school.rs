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

use crate::domain::calendar::{Branch, Stem};
use crate::domain::chart::{Brightness, Chart, Flow, Mutagen, StarKey};

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

/// Applies the Vietnamese school to a chart cast by the engine: brightness from
/// [`vn_brightness`] and the natal Tứ Hóa of the year stem. `flows` is the entry point for
/// the flow layers (extended by later tasks); it is not touched yet.
// The flow is consumed by the flow Tứ Hóa step of a later task; the signature is fixed now.
#[allow(clippy::needless_pass_by_value)]
pub fn apply_vn_school(chart: &mut Chart, flows: Option<&mut Flow>) {
    // Flow Tứ Hóa is applied by a later step; the flow is accepted so the signature is stable.
    let _ = flows;
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
}
