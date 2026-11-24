"""Cross-check fixture: casts every case of cases.json with `lasotuvi` and prints JSON.

Run only inside the python:3.12-alpine container (see README.md); the lasotuvi code is
untrusted and must never run on the host. The output is read by
services/chart-svc/tests/vn_crosscheck.rs.

Inputs are normalised so both tools see the same lunar birth moment: a case is a solar date
that is already the birthplace's local standard date (23:xx already rolled to the next day)
plus an hour branch 0-11 (Tý = 0). No clock time reaches lasotuvi, so its giờ Tý and
time-zone handling never come into play. lasotuvi converts the solar date to a lunar date at
UTC+7 itself. Like chart-svc it numbers branches from the lunar month, but it ignores the
leap flag (a leap month is charted as the plain month); generate.py feeds it the month
chart-svc uses for days 16-30 of a leap month (see cast() and README.md).
"""

import json
import sys

from lasotuvi.AmDuong import ngayThangNam, ngayThangNamCanChi, nguHanh, timCuc
from lasotuvi.App import lapDiaBan
from lasotuvi.DiaBan import diaBan
import lasotuvi.Sao as sao_module

# lasotuvi branch / stem numbers (Tý = 1, Giáp = 1) to chart-svc keys.
BRANCHES = ["Zi", "Chou", "Yin", "Mao", "Chen", "Si", "Wu", "Wei", "Shen", "You", "Xu", "Hai"]
STEMS = ["Jia", "Yi", "Bing", "Ding", "Wu", "Ji", "Geng", "Xin", "Ren", "Gui"]

# lasotuvi saoID to chart-svc StarKey, by group. Mapping by id (not by display name) keeps
# the duplicated names (Quan phù, Thiên hỷ vs Hỷ thần) apart.
MAJOR = {
    1: "TuVi", 2: "LiemTrinh", 3: "ThienDong", 4: "VuKhuc", 5: "ThaiDuong", 6: "ThienCo",
    7: "ThienPhu", 8: "ThaiAm", 9: "ThamLang", 10: "CuMon", 11: "ThienTuong",
    12: "ThienLuong", 13: "ThatSat", 14: "PhaQuan",
}
MINOR = {
    61: "TaPhu", 62: "HuuBat", 57: "VanXuong", 58: "VanKhuc", 27: "LocTon", 98: "ThienMa",
    52: "KinhDuong", 51: "DaLa", 56: "HoaTinh", 55: "LinhTinh", 53: "DiaKhong",
    54: "DiaKiep", 59: "ThienKhoi", 60: "ThienViet",
}
# Adjective stars lasotuvi has under the same name as chart-svc's AdjStarKey.
ADJECTIVE = {
    79: "HongLoan", 80: "ThienHy", 74: "ThienRieu", 83: "GiaiThan", 65: "TamThai",
    66: "BatToa", 67: "AnQuang", 68: "ThienQuy", 63: "LongTri", 64: "PhuongCac",
    86: "ThienTai", 87: "ThienTho", 84: "ThaiPhu", 85: "PhongCao", 105: "HoaCai",
    100: "ThienQuan", 101: "ThienPhuc", 103: "ThienTru", 71: "ThienDuc", 72: "NguyetDuc",
    108: "ThienKhong", 96: "CoThan", 97: "QuaTu", 99: "PhaToai", 73: "ThienHinh",
    69: "ThienKhoc", 70: "ThienHu", 89: "ThienSu", 88: "ThienThuong",
}
# The rings of twelve gods: lasotuvi's Tràng sinh, Bác sỹ and Thái tuế rings (the Thái tuế
# ring is mapped by position to chart-svc's Tuế Tiền names: Thiếu dương = Hối khí, Thiếu âm =
# Quán sách, Tử phù = Tiểu hao, Tuế phá = Đại hao, Trực phù = Bệnh phù).
CHANGSHENG = {
    39: "TrangSinh", 40: "MocDuc", 41: "QuanDoi", 42: "LamQuan", 43: "DeVuong", 44: "Suy",
    45: "Benh", 46: "Tu", 47: "Mo", 48: "Tuyet", 49: "Thai", 50: "Duong",
}
BOSHI = {
    109: "BacSi", 28: "LucSi", 29: "ThanhLong", 30: "TieuHao", 31: "TuongQuan",
    32: "TauThu", 33: "PhiLiem", 34: "HyThan", 35: "BenhPhu", 36: "DaiHao",
    37: "PhucBinh", 38: "QuanPhu",
}
SUIQIAN = {
    15: "TueKien", 16: "HoiKhi", 17: "TangMon", 18: "QuanTac", 19: "QuanPhuTue",
    20: "TieuHao", 21: "DaiHao", 22: "LongDuc", 23: "BachHo", 24: "ThienDuc",
    25: "DieuKhach", 26: "BenhPhu",
}
# Tứ Hóa stars: saoID to mutagen key.
MUTAGEN = {94: "Loc", 93: "Quyen", 92: "Khoa", 95: "Ky"}
BRIGHTNESS = {"M": "Mieu", "V": "Vuong", "Đ": "Dac", "B": "Binh", "H": "Ham"}
CUC = {2: "Thủy nhị cục", 3: "Mộc tam cục", 4: "Kim tứ cục", 5: "Thổ ngũ cục", 6: "Hỏa lục cục"}


def branch(n):
    return BRANCHES[n - 1]


def reset_stars():
    """lasotuvi keeps one module-level object per star and only sets its brightness when the
    star's table has a value for the palace, so a value from the previous case would leak
    into the next one. Clear them all before each case."""
    for obj in vars(sao_module).values():
        if isinstance(obj, sao_module.Sao):
            obj.saoDacTinh = None


def cast(case):
    reset_stars()
    year, month, day = (int(p) for p in case["solar"].split("-"))
    hour = case["hourBranch"] + 1  # lasotuvi: Tý = 1
    sex = 1 if case["gender"] == "male" else -1
    lunar_day, lunar_month, lunar_year, leap = ngayThangNam(day, month, year, True, 7)
    # lasotuvi charts a leap-month birth as the plain month. chart-svc (iztro's fixLeap, the
    # default) charts days 1-15 of a leap month m as month m and days 16-30 as month m + 1.
    # To compare the same chart, such births are fed to lasotuvi as the lunar date with the
    # month chart-svc uses; the raw lasotuvi Mệnh/Thân are kept under "leapRaw".
    leap_raw = None
    if leap and lunar_day > 15:
        assert lunar_month < 12, "leap 12th month is not handled"
        raw = lapDiaBan(diaBan, day, month, year, hour, sex, True, 7)
        leap_raw = {"month": lunar_month, "menh": branch(raw.cungMenh), "than": branch(raw.cungThan)}
        db = lapDiaBan(diaBan, lunar_day, lunar_month + 1, lunar_year, hour, sex, False, 7)
    else:
        db = lapDiaBan(diaBan, day, month, year, hour, sex, True, 7)
    can_month, can_year, chi_year = ngayThangNamCanChi(lunar_day, lunar_month, lunar_year, False, 7)
    cuc_number = nguHanh(timCuc(db.cungMenh, can_year))["cuc"]

    out = {
        "id": case["id"],
        "solar": case["solar"],
        "hourBranch": case["hourBranch"],
        "gender": case["gender"],
        "lunar": [lunar_year, lunar_month, lunar_day, bool(leap)],
        "yearCanChi": [STEMS[can_year - 1], BRANCHES[chi_year - 1]],
        "leapRaw": leap_raw,
        "cuc": {"name": CUC[cuc_number], "number": cuc_number},
        "menh": branch(db.cungMenh),
        "than": branch(db.cungThan),
        "major": {},
        "brightness": {},
        "minor": {},
        "adjective": {},
        "changsheng": {},
        "boshi": {},
        "suiqian": {},
        "mutagen": {},
        "tuan": [],
        "triet": [],
    }
    groups = [
        (MAJOR, "major"), (MINOR, "minor"), (ADJECTIVE, "adjective"),
        (CHANGSHENG, "changsheng"), (BOSHI, "boshi"), (SUIQIAN, "suiqian"),
        (MUTAGEN, "mutagen"),
    ]
    for palace in db.thapNhiCung[1:]:
        b = branch(palace.cungSo)
        for sao in palace.cungSao:
            for table, name in groups:
                key = table.get(sao["saoID"])
                if key is None:
                    continue
                if name in ("adjective", "mutagen", "changsheng", "boshi", "suiqian"):
                    out[name].setdefault(key, []).append(b)
                else:
                    out[name][key] = b
            if sao["saoID"] in MAJOR or sao["saoID"] in MINOR:
                bright = BRIGHTNESS.get(sao["saoDacTinh"])
                if bright:
                    out["brightness"][(MAJOR | MINOR)[sao["saoID"]]] = bright
        if getattr(palace, "tuanTrung", False):
            out["tuan"].append(b)
        if getattr(palace, "trietLo", False):
            out["triet"].append(b)
    return out


def main():
    cases = json.load(open(sys.argv[1], encoding="utf-8"))["cases"]
    print(json.dumps({"cases": [cast(c) for c in cases]}, ensure_ascii=False, indent=1, sort_keys=True))


if __name__ == "__main__":
    main()
