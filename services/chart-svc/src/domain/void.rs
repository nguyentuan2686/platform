//! Tuần and Triệt: the two "void" pairs of branches a chart marks.
//!
//! Tuần (Tuần Trung Không Vong) is the pair of branches left out of the sexagenary
//! decade the birth year belongs to; Triệt (Triệt Lộ Không Vong) is a fixed pair per year
//! stem.

use crate::domain::calendar::{Branch, CanChi, Stem};

fn position<T: PartialEq + Copy>(all: &[T], v: T) -> i64 {
    all.iter()
        .position(|&x| x == v)
        .map_or(0, |i| i64::try_from(i).unwrap_or_default())
}

/// Tuần of a year: the two branches missing from the ten-year run (Giáp to Quý) starting at
/// the Giáp that precedes (or equals) the year's can chi. With stem index `s` and branch
/// index `b` they are `(b - s + 10) mod 12` and `(b - s + 11) mod 12`.
pub fn tuan(year: CanChi) -> [Branch; 2] {
    let gap = position(&Branch::ALL, year.branch) - position(&Stem::ALL, year.stem);
    [gap + 10, gap + 11].map(|n| Branch::ALL[usize::try_from(n.rem_euclid(12)).unwrap_or_default()])
}

/// Triệt of a year stem: Giáp/Kỷ Thân-Dậu, Ất/Canh Ngọ-Mùi, Bính/Tân Thìn-Tỵ,
/// Đinh/Nhâm Dần-Mão, Mậu/Quý Tý-Sửu.
pub fn triet(year_stem: Stem) -> [Branch; 2] {
    match year_stem {
        Stem::Jia | Stem::Ji => [Branch::Shen, Branch::You],
        Stem::Yi | Stem::Geng => [Branch::Wu, Branch::Wei],
        Stem::Bing | Stem::Xin => [Branch::Chen, Branch::Si],
        Stem::Ding | Stem::Ren => [Branch::Yin, Branch::Mao],
        Stem::Wu | Stem::Gui => [Branch::Zi, Branch::Chou],
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use Branch::{Chen, Chou, Hai, Mao, Shen, Si, Wei, Xu, Yin, You, Zi};
    use Stem::{Bing, Ding, Geng, Gui, Ji, Jia, Ren, Xin, Yi};

    const WU_STEM: Stem = Stem::Wu;
    const WU_BRANCH: Branch = Branch::Wu;

    fn cc(stem: Stem, branch: Branch) -> CanChi {
        CanChi { stem, branch }
    }

    #[test]
    fn tuan_of_known_years() {
        assert_eq!(tuan(cc(Jia, Zi)), [Xu, Hai]);
        assert_eq!(tuan(cc(Jia, Xu)), [Shen, You]);
        assert_eq!(tuan(cc(Yi, Chou)), [Xu, Hai]);
        assert_eq!(tuan(cc(Gui, Hai)), [Zi, Chou]);
        assert_eq!(tuan(cc(Bing, Yin)), [Xu, Hai]);
        assert_eq!(tuan(cc(Geng, WU_BRANCH)), [Xu, Hai]);
        assert_eq!(tuan(cc(Jia, Shen)), [WU_BRANCH, Wei]);
    }

    #[test]
    fn tuan_is_the_same_for_a_whole_decade_of_the_cycle() {
        // Giáp Tý .. Quý Dậu share Tuần Tuất-Hợi.
        for (i, &stem) in Stem::ALL.iter().enumerate() {
            assert_eq!(tuan(cc(stem, Branch::ALL[i])), [Xu, Hai]);
        }
    }

    #[test]
    fn triet_for_every_stem() {
        let want = [
            (Jia, [Shen, You]),
            (Yi, [WU_BRANCH, Wei]),
            (Bing, [Chen, Si]),
            (Ding, [Yin, Mao]),
            (WU_STEM, [Zi, Chou]),
            (Ji, [Shen, You]),
            (Geng, [WU_BRANCH, Wei]),
            (Xin, [Chen, Si]),
            (Ren, [Yin, Mao]),
            (Gui, [Zi, Chou]),
        ];
        for (stem, pair) in want {
            assert_eq!(triet(stem), pair, "{stem:?}");
        }
    }
}
