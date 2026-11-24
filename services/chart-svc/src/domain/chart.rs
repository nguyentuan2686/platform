//! Tử Vi chart model: palaces, stars, Tứ Hóa and the flow (lưu niên / nguyệt / nhật) of a
//! day. Every enum variant name is its stable ASCII key; `name_vi` is the Vietnamese label.
//! No engine types here: the engine adapter maps into these.

use crate::domain::calendar::{Branch, CanChi, LunarDate, Stem};
use time::Date;

/// Sex of the native; decides the direction of the decades.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Gender {
    Male,
    Female,
}

/// The twelve palaces (cung).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum PalaceKey {
    Menh,
    PhuMau,
    PhucDuc,
    DienTrach,
    QuanLoc,
    NoBoc,
    ThienDi,
    TatAch,
    TaiBach,
    TuTuc,
    PhuThe,
    HuynhDe,
}

/// Stars the chart places: the 14 major stars, then the 14 minor stars.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum StarKey {
    TuVi,
    ThienCo,
    ThaiDuong,
    VuKhuc,
    ThienDong,
    LiemTrinh,
    ThienPhu,
    ThaiAm,
    ThamLang,
    CuMon,
    ThienTuong,
    ThienLuong,
    ThatSat,
    PhaQuan,
    TaPhu,
    HuuBat,
    VanXuong,
    VanKhuc,
    LocTon,
    ThienMa,
    KinhDuong,
    DaLa,
    HoaTinh,
    LinhTinh,
    DiaKhong,
    DiaKiep,
    ThienKhoi,
    ThienViet,
}

/// Adjective stars (tạp diệu) the chart places in a palace, as the default algorithm
/// lays them out. The Tuần/Triệt-type stars the engine also places (`xunkong`, `xunzhong`,
/// `jiekong`, `jielu`, `kongwang`) are not here: the chart reports Tuần and Triệt as
/// palace pairs instead.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum AdjStarKey {
    HongLoan,
    ThienHy,
    ThienRieu,
    HamTri,
    GiaiThan,
    TamThai,
    BatToa,
    AnQuang,
    ThienQuy,
    LongTri,
    PhuongCac,
    ThienTai,
    ThienTho,
    ThaiPhu,
    PhongCao,
    ThienVu,
    HoaCai,
    ThienQuan,
    ThienPhuc,
    ThienTru,
    ThienNguyet,
    ThienDuc,
    NguyetDuc,
    ThienKhong,
    CoThan,
    QuaTu,
    PhiLiem,
    PhaToai,
    ThienHinh,
    AmSat,
    ThienKhoc,
    ThienHu,
    ThienSu,
    ThienThuong,
    NienGiai,
    // Vietnamese-school stars (VN charts only): see `vn_school`.
    DaoHoa,
    ThienGiai,
    DiaGiai,
    ThienY,
    QuocAn,
    DuongPhu,
    VanTinh,
    LuuHa,
    DauQuan,
    ThienLa,
    DiaVong,
}

/// Members of the four rings of twelve gods: Tràng Sinh, Bác Sĩ, Tuế Tiền (Thái Tuế
/// ring) and Tướng Tiền. The rings share a few stars (Tiểu Hao, Đại Hao, Bệnh Phù), which
/// are one variant here. Quan Phủ (Bác Sĩ ring) and Quan Phù (Thái Tuế ring) fold to the
/// same ASCII, so the latter's key is `QuanPhuTue`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RingStarKey {
    // Tràng Sinh
    TrangSinh,
    MocDuc,
    QuanDoi,
    LamQuan,
    DeVuong,
    Suy,
    Benh,
    Tu,
    Mo,
    Tuyet,
    Thai,
    Duong,
    // Bác Sĩ
    BacSi,
    LucSi,
    ThanhLong,
    TieuHao,
    TuongQuan,
    TauThu,
    PhiLiem,
    HyThan,
    BenhPhu,
    DaiHao,
    PhucBinh,
    QuanPhu,
    // Thái Tuế (Tuế Tiền)
    TueKien,
    HoiKhi,
    TangMon,
    QuanTac,
    QuanPhuTue,
    LongDuc,
    BachHo,
    ThienDuc,
    DieuKhach,
    // Tướng Tiền
    TuongTinh,
    PhanAn,
    TueDich,
    TucThan,
    HoaCai,
    KiepSat,
    TaiSat,
    ThienSat,
    ChiBoi,
    HamTri,
    NguyetSat,
    VongThan,
}

/// Star brightness on the Vietnamese five-level scale.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Brightness {
    Mieu,
    Vuong,
    Dac,
    Binh,
    Ham,
}

/// The four transformations (Tứ Hóa).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Mutagen {
    Loc,
    Quyen,
    Khoa,
    Ky,
}

/// Element of the cục (ngũ hành cục).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Element {
    Thuy,
    Moc,
    Kim,
    Tho,
    Hoa,
}

macro_rules! labelled {
    ($ty:ty { $($variant:ident => $vi:literal),+ $(,)? }) => {
        impl $ty {
            /// Every value, in declaration order.
            pub const ALL: &'static [$ty] = &[$(<$ty>::$variant),+];

            /// Stable ASCII key.
            pub fn key(self) -> &'static str {
                match self { $(<$ty>::$variant => stringify!($variant)),+ }
            }

            /// Vietnamese label.
            pub fn name_vi(self) -> &'static str {
                match self { $(<$ty>::$variant => $vi),+ }
            }
        }
    };
}

labelled!(Gender { Male => "Nam", Female => "Nữ" });

labelled!(PalaceKey {
    Menh => "Mệnh",
    PhuMau => "Phụ Mẫu",
    PhucDuc => "Phúc Đức",
    DienTrach => "Điền Trạch",
    QuanLoc => "Quan Lộc",
    NoBoc => "Nô Bộc",
    ThienDi => "Thiên Di",
    TatAch => "Tật Ách",
    TaiBach => "Tài Bạch",
    TuTuc => "Tử Tức",
    PhuThe => "Phu Thê",
    HuynhDe => "Huynh Đệ",
});

labelled!(StarKey {
    TuVi => "Tử Vi",
    ThienCo => "Thiên Cơ",
    ThaiDuong => "Thái Dương",
    VuKhuc => "Vũ Khúc",
    ThienDong => "Thiên Đồng",
    LiemTrinh => "Liêm Trinh",
    ThienPhu => "Thiên Phủ",
    ThaiAm => "Thái Âm",
    ThamLang => "Tham Lang",
    CuMon => "Cự Môn",
    ThienTuong => "Thiên Tướng",
    ThienLuong => "Thiên Lương",
    ThatSat => "Thất Sát",
    PhaQuan => "Phá Quân",
    TaPhu => "Tả Phù",
    HuuBat => "Hữu Bật",
    VanXuong => "Văn Xương",
    VanKhuc => "Văn Khúc",
    LocTon => "Lộc Tồn",
    ThienMa => "Thiên Mã",
    KinhDuong => "Kình Dương",
    DaLa => "Đà La",
    HoaTinh => "Hỏa Tinh",
    LinhTinh => "Linh Tinh",
    DiaKhong => "Địa Không",
    DiaKiep => "Địa Kiếp",
    ThienKhoi => "Thiên Khôi",
    ThienViet => "Thiên Việt",
});

labelled!(AdjStarKey {
    HongLoan => "Hồng Loan",
    ThienHy => "Thiên Hỷ",
    ThienRieu => "Thiên Riêu",
    HamTri => "Hàm Trì",
    GiaiThan => "Giải Thần",
    TamThai => "Tam Thai",
    BatToa => "Bát Tọa",
    AnQuang => "Ân Quang",
    ThienQuy => "Thiên Quý",
    LongTri => "Long Trì",
    PhuongCac => "Phượng Các",
    ThienTai => "Thiên Tài",
    ThienTho => "Thiên Thọ",
    ThaiPhu => "Thai Phụ",
    PhongCao => "Phong Cáo",
    ThienVu => "Thiên Vu",
    HoaCai => "Hoa Cái",
    ThienQuan => "Thiên Quan",
    ThienPhuc => "Thiên Phúc",
    ThienTru => "Thiên Trù",
    ThienNguyet => "Thiên Nguyệt",
    ThienDuc => "Thiên Đức",
    NguyetDuc => "Nguyệt Đức",
    ThienKhong => "Thiên Không",
    CoThan => "Cô Thần",
    QuaTu => "Quả Tú",
    PhiLiem => "Phi Liêm",
    PhaToai => "Phá Toái",
    ThienHinh => "Thiên Hình",
    AmSat => "Âm Sát",
    ThienKhoc => "Thiên Khốc",
    ThienHu => "Thiên Hư",
    ThienSu => "Thiên Sứ",
    ThienThuong => "Thiên Thương",
    NienGiai => "Niên Giải",
    DaoHoa => "Đào Hoa",
    ThienGiai => "Thiên Giải",
    DiaGiai => "Địa Giải",
    ThienY => "Thiên Y",
    QuocAn => "Quốc Ấn",
    DuongPhu => "Đường Phù",
    VanTinh => "Văn Tinh",
    LuuHa => "Lưu Hà",
    DauQuan => "Đẩu Quân",
    ThienLa => "Thiên La",
    DiaVong => "Địa Võng",
});

labelled!(RingStarKey {
    TrangSinh => "Tràng Sinh",
    MocDuc => "Mộc Dục",
    QuanDoi => "Quan Đới",
    LamQuan => "Lâm Quan",
    DeVuong => "Đế Vượng",
    Suy => "Suy",
    Benh => "Bệnh",
    Tu => "Tử",
    Mo => "Mộ",
    Tuyet => "Tuyệt",
    Thai => "Thai",
    Duong => "Dưỡng",
    BacSi => "Bác Sĩ",
    LucSi => "Lực Sĩ",
    ThanhLong => "Thanh Long",
    TieuHao => "Tiểu Hao",
    TuongQuan => "Tướng Quân",
    TauThu => "Tấu Thư",
    PhiLiem => "Phi Liêm",
    HyThan => "Hỷ Thần",
    BenhPhu => "Bệnh Phù",
    DaiHao => "Đại Hao",
    PhucBinh => "Phục Binh",
    QuanPhu => "Quan Phủ",
    TueKien => "Tuế Kiện",
    HoiKhi => "Hối Khí",
    TangMon => "Tang Môn",
    QuanTac => "Quán Tác",
    QuanPhuTue => "Quan Phù",
    LongDuc => "Long Đức",
    BachHo => "Bạch Hổ",
    ThienDuc => "Thiên Đức",
    DieuKhach => "Điếu Khách",
    TuongTinh => "Tướng Tinh",
    PhanAn => "Phan Án",
    TueDich => "Tuế Dịch",
    TucThan => "Tức Thần",
    HoaCai => "Hoa Cái",
    KiepSat => "Kiếp Sát",
    TaiSat => "Tai Sát",
    ThienSat => "Thiên Sát",
    ChiBoi => "Chỉ Bối",
    HamTri => "Hàm Trì",
    NguyetSat => "Nguyệt Sát",
    VongThan => "Vong Thần",
});

labelled!(Brightness {
    Mieu => "Miếu",
    Vuong => "Vượng",
    Dac => "Đắc",
    Binh => "Bình",
    Ham => "Hãm",
});

labelled!(Mutagen {
    Loc => "Hóa Lộc",
    Quyen => "Hóa Quyền",
    Khoa => "Hóa Khoa",
    Ky => "Hóa Kỵ",
});

labelled!(Element {
    Thuy => "Thủy",
    Moc => "Mộc",
    Kim => "Kim",
    Tho => "Thổ",
    Hoa => "Hỏa",
});

impl StarKey {
    /// One of the 14 major stars (chính tinh)?
    pub fn is_major(self) -> bool {
        (self as usize) < 14
    }
}

impl AdjStarKey {
    /// A star only the Vietnamese school places (`calendar=VN` charts); CN charts have none.
    pub fn is_vn_only(self) -> bool {
        matches!(
            self,
            AdjStarKey::DaoHoa
                | AdjStarKey::ThienGiai
                | AdjStarKey::DiaGiai
                | AdjStarKey::ThienY
                | AdjStarKey::QuocAn
                | AdjStarKey::DuongPhu
                | AdjStarKey::VanTinh
                | AdjStarKey::LuuHa
                | AdjStarKey::DauQuan
                | AdjStarKey::ThienLa
                | AdjStarKey::DiaVong
        )
    }
}

impl Mutagen {
    /// The transformations in the order a stem's table lists them: Lộc, Quyền, Khoa, Kỵ.
    pub const ORDER: [Mutagen; 4] = [Mutagen::Loc, Mutagen::Quyen, Mutagen::Khoa, Mutagen::Ky];
}

/// The cục (ngũ hành cục): element and the starting age of the first decade.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Cuc {
    pub element: Element,
    pub number: u8,
}

impl Cuc {
    /// Vietnamese label, e.g. "Thủy nhị cục".
    pub fn name_vi(self) -> &'static str {
        match self.element {
            Element::Thuy => "Thủy nhị cục",
            Element::Moc => "Mộc tam cục",
            Element::Kim => "Kim tứ cục",
            Element::Tho => "Thổ ngũ cục",
            Element::Hoa => "Hỏa lục cục",
        }
    }
}

/// A star in a palace with its brightness (if the star has one) and birth-year
/// transformation (if any).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct StarInPalace {
    pub key: StarKey,
    pub brightness: Option<Brightness>,
    pub mutagen: Option<Mutagen>,
}

/// One palace of the board: its role, its stem and branch, its stars and its decade
/// (đại hạn, ages from..=to).
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Palace {
    pub key: PalaceKey,
    pub branch: Branch,
    pub stem: Stem,
    pub major: Vec<StarInPalace>,
    pub minor: Vec<StarInPalace>,
    pub decade: (u8, u8),
    /// Adjective stars in the order the engine places them.
    pub adjective: Vec<AdjStarKey>,
    /// The palace's god in each of the four rings.
    pub changsheng: RingStarKey,
    pub boshi: RingStarKey,
    pub suiqian: RingStarKey,
    pub jiangqian: RingStarKey,
    /// Nominal ages (tiểu hạn) that fall on this palace, ascending.
    pub small_limit_ages: Vec<u8>,
}

/// A natal chart. `palaces` is ordered by branch starting at Dần (Yin), the order the
/// board is drawn in.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct Chart {
    pub lunar: LunarDate,
    pub year: CanChi,
    pub hour_branch: Branch,
    pub gender: Gender,
    pub cuc: Cuc,
    pub menh_chu: StarKey,
    pub than_chu: StarKey,
    pub than_palace: PalaceKey,
    pub palaces: [Palace; 12],
    /// Tuần: the two branches whose palaces it covers.
    pub tuan: [Branch; 2],
    /// Triệt: the two branches whose palaces it covers.
    pub triet: [Branch; 2],
    pub school: &'static str,
}

/// One flow layer: the palace (by branch) the layer's Mệnh falls on, the layer's stem,
/// and the stem's Tứ Hóa in the order Lộc, Quyền, Khoa, Kỵ.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct FlowLayer {
    pub palace_branch: Branch,
    pub stem: Stem,
    pub mutagens: [(StarKey, Mutagen); 4],
}

/// The flow of one day for a chart: lưu niên, lưu nguyệt and lưu nhật.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct Flow {
    pub date: Date,
    pub lunar: LunarDate,
    pub day: CanChi,
    pub yearly: FlowLayer,
    pub monthly: FlowLayer,
    pub daily: FlowLayer,
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    fn assert_table<T: Copy>(all: &[T], key: fn(T) -> &'static str, vi: fn(T) -> &'static str) {
        let keys: HashSet<_> = all.iter().map(|&v| key(v)).collect();
        assert_eq!(keys.len(), all.len(), "duplicate key");
        assert!(keys.iter().all(|k| k.is_ascii() && !k.is_empty()));
        assert!(all.iter().all(|&v| !vi(v).is_empty()));
    }

    #[test]
    fn keys_are_unique_ascii_and_labelled() {
        assert_table(Gender::ALL, Gender::key, Gender::name_vi);
        assert_table(PalaceKey::ALL, PalaceKey::key, PalaceKey::name_vi);
        assert_table(StarKey::ALL, StarKey::key, StarKey::name_vi);
        assert_table(AdjStarKey::ALL, AdjStarKey::key, AdjStarKey::name_vi);
        assert_table(RingStarKey::ALL, RingStarKey::key, RingStarKey::name_vi);
        assert_table(Brightness::ALL, Brightness::key, Brightness::name_vi);
        assert_table(Mutagen::ALL, Mutagen::key, Mutagen::name_vi);
        assert_table(Element::ALL, Element::key, Element::name_vi);
    }

    #[test]
    fn palace_and_major_star_keys_match_the_contract() {
        let palaces: Vec<_> = PalaceKey::ALL.iter().map(|p| p.key()).collect();
        assert_eq!(
            palaces.join(" "),
            "Menh PhuMau PhucDuc DienTrach QuanLoc NoBoc ThienDi TatAch TaiBach TuTuc PhuThe HuynhDe"
        );
        let majors: Vec<_> = StarKey::ALL
            .iter()
            .filter(|s| s.is_major())
            .map(|s| s.key())
            .collect();
        assert_eq!(
            majors.join(" "),
            "TuVi ThienCo ThaiDuong VuKhuc ThienDong LiemTrinh ThienPhu ThaiAm ThamLang CuMon \
             ThienTuong ThienLuong ThatSat PhaQuan"
        );
        let mutagens: Vec<_> = Mutagen::ORDER.iter().map(|m| m.key()).collect();
        assert_eq!(mutagens.join(" "), "Loc Quyen Khoa Ky");
    }

    #[test]
    fn labels() {
        assert_eq!(StarKey::TuVi.name_vi(), "Tử Vi");
        assert_eq!(StarKey::KinhDuong.name_vi(), "Kình Dương");
        assert_eq!(PalaceKey::NoBoc.name_vi(), "Nô Bộc");
        assert_eq!(Mutagen::Ky.name_vi(), "Hóa Kỵ");
        assert_eq!(Brightness::Ham.name_vi(), "Hãm");
        let cuc = |element, number| Cuc { element, number }.name_vi();
        assert_eq!(cuc(Element::Thuy, 2), "Thủy nhị cục");
        assert_eq!(cuc(Element::Moc, 3), "Mộc tam cục");
        assert_eq!(cuc(Element::Kim, 4), "Kim tứ cục");
        assert_eq!(cuc(Element::Tho, 5), "Thổ ngũ cục");
        assert_eq!(cuc(Element::Hoa, 6), "Hỏa lục cục");
    }

    #[test]
    fn adjective_and_ring_labels_follow_vietnamese_convention() {
        assert_eq!(AdjStarKey::ThienRieu.name_vi(), "Thiên Riêu");
        assert_eq!(AdjStarKey::ThienRieu.key(), "ThienRieu");
        assert_eq!(AdjStarKey::PhuongCac.name_vi(), "Phượng Các");
        assert_eq!(AdjStarKey::ThaiPhu.name_vi(), "Thai Phụ");
        assert_eq!(RingStarKey::TrangSinh.name_vi(), "Tràng Sinh");
        assert_eq!(RingStarKey::BacSi.name_vi(), "Bác Sĩ");
        assert_eq!(RingStarKey::LucSi.name_vi(), "Lực Sĩ");
        assert_eq!(AdjStarKey::PhiLiem.key(), RingStarKey::PhiLiem.key());
        // 35 stars of the default algorithm plus the 11 Vietnamese-only ones.
        assert_eq!(AdjStarKey::ALL.len(), 46);
        assert_eq!(
            AdjStarKey::ALL.iter().filter(|k| k.is_vn_only()).count(),
            11
        );
        assert_eq!(AdjStarKey::DaoHoa.name_vi(), "Đào Hoa");
        assert_eq!(AdjStarKey::DiaVong.name_vi(), "Địa Võng");
        assert_eq!(RingStarKey::ALL.len(), 45);
    }

    #[test]
    fn fourteen_major_and_fourteen_minor_stars() {
        assert_eq!(StarKey::ALL.iter().filter(|s| s.is_major()).count(), 14);
        assert_eq!(StarKey::ALL.iter().filter(|s| !s.is_major()).count(), 14);
    }
}
