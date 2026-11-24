# Vietnamese school vs iztro default: chart-svc against lasotuvi

Source: `services/chart-svc/tests/vn_crosscheck.rs` (the `--ignored` report test) over the 50
charts of `tools/fixtures/lasotuvi/` (lasotuvi commit `ace8379`, MIT; see its README). chart-svc
follows iztro 2.6.3 defaults. This is the F1.x backlog of Vietnamese-school differences
(spec section 11). **No engine change in this plan.**

## Agreement (asserted, 50 of 50 charts)

Lunar date and leap flag, year can chi, cục, Mệnh and Thân branch, the branch of all 14 major
stars, the Tuần pair and the Triệt pair. The Tử Vi and Thiên Phủ systems, Lộc Tồn, Kình/Đà,
Tả/Hữu, Xương/Khúc, Thiên Mã, Địa Không/Kiếp, Hóa Lộc/Quyền, Long Trì/Phượng Các and most
adjective stars also agree in every case; the Bác Sỹ ring and the Thái Tuế ring agree in
every case; so do the first ten Tràng Sinh stars.

One input-convention difference (not a disagreement): leap-month births on days 16-30
(2009-07-14 and 1985-04-05 of the 5 leap-month cases). lasotuvi charts a leap month m as plain
month m; iztro's `fixLeap` charts days 16-30 as month m + 1, so Mệnh and Thân differ by one
palace unless the input month is normalised (done in `generate.py`). Days 1-15 agree either
way. Which rule Vietnamese practice prefers is open; lasotuvi's source states no convention.

## Placement differences (counts are charts out of 50 where the placement differs)

| Group | Star | Charts | Pattern (from the data; the lasotuvi source states no convention unless quoted) |
|---|---|---|---|
| minor | Hỏa Tinh | 32 | lasotuvi flips the direction with polarity (year yin/yang times gender, `timHoaLinh`); chart-svc matches it for the "+" polarity group only, so the differences sit in the "-" group (24 of 32). |
| minor | Linh Tinh | 30 | Mirror of Hỏa Tinh: chart-svc matches the "-" group only, so the differences sit in the "+" group (23 of 30). The two stars run in opposite directions in lasotuvi. |
| minor | Thiên Khôi | 17 | Khôi and Việt are swapped for years of stem Đinh, Mậu, Canh, Nhâm (`timThienKhoi` table versus iztro's). |
| minor | Thiên Việt | 17 | Same cases as Thiên Khôi. |
| adjective | Giải Thần | 48 | lasotuvi puts Giải Thần with Phượng Các (year based, "saoPhuongCac, saoGiaiThan"); chart-svc places it by another rule (not the year-branch one). |
| adjective | Thiên Quý | 41 | lasotuvi mirrors it from Ân Quang across the Sửu-Mùi axis; its comments say Thiên Quý "mới chính xác???" (uncertain). chart-svc places it by a different rule (not verified against iztro source here). Ân Quang itself agrees in all 50. |
| adjective | Phá Toái | 31 | lasotuvi cycles Tỵ, Dậu, Sửu with the year branch modulo 3; chart-svc does not follow that three-year cycle. |
| adjective | Thiên Trù | 6 | Only years of stem Quý. lasotuvi notes its Lưu Hà and Thiên Trù table is unverified ("Mong mọi người kiểm chứng"). |
| ring Tràng Sinh | Thai | 50 | lasotuvi puts Thai one palace before Tràng Sinh and Dưỡng two before. The standard order is Tuyệt, Thai, Dưỡng, Tràng Sinh, so lasotuvi appears to swap Thai and Dưỡng (its `viTriThai` / `viTriDuong`). |
| ring Tràng Sinh | Dưỡng | 50 | Same as Thai. |
| Tứ Hóa | Hóa Khoa | 8 | Years of stem Canh and Nhâm. lasotuvi's table follows "Số tử vi dưới mắt khoa học" (Thiên Lương), iztro uses its own. |
| Tứ Hóa | Hóa Kỵ | 4 | Years of stem Canh (lasotuvi: Khoa Thiên Đồng, Kỵ Thái Âm; iztro the other way round). |

lasotuvi places no Hóa Lộc or Hóa Quyền differently from chart-svc.

## Brightness (miếu, vượng, đắc, bình, hãm) differences

lasotuvi has its own table (`dacTinhSao` in `DiaBan.py`, no comment on its source); chart-svc maps
iztro's five Chinese levels to the Vietnamese scale (得 and 利 to Đắc, 平 and 不 to Bình).
Charts where the level differs, counting only stars lasotuvi gives a level:

| Star | Charts | Star | Charts | Star | Charts |
|---|---|---|---|---|---|
| Thiên Cơ | 50 | Thất Sát | 45 | Văn Khúc | 45 |
| Tử Vi | 42 | Liêm Trinh | 36 | Thái Âm | 36 |
| Thiên Đồng | 36 | Hỏa Tinh | 35 | Linh Tinh | 35 |
| Tham Lang | 29 | Kình Dương | 28 | Văn Xương | 28 |
| Cự Môn | 27 | Thái Dương | 26 | Thiên Mã | 25 |
| Phá Quân | 24 | Thiên Lương | 24 | Đà La | 22 |
| Thiên Tướng | 18 | Vũ Khúc | 15 | Địa Không | 50 |
| Địa Kiếp | 50 | | | | |

Địa Không, Địa Kiếp and Thiên Mã differ because chart-svc reports no brightness for them,
where lasotuvi gives Đắc or Hãm (Địa Không/Kiếp) or Đắc at Dần and Tỵ (Thiên Mã). Thiên Phủ has
no row in lasotuvi's table.

## Stars on one side only

- lasotuvi places, chart-svc has no key for: Thiên Giải, Địa Giải, Thiên Y, Quốc Ấn, Đường
  Phù, Đào Hoa, Văn Tinh, Lưu Hà, Đẩu Quân, Thiên La, Địa Võng.
- chart-svc places, lasotuvi does not: Hàm Trì, Thiên Vu, Thiên Nguyệt, Âm Sát, Niên Giải
  (and the Tướng Tiền ring, of which lasotuvi has only Hoa Cái and Kiếp Sát).

## Backlog reading

Differences that look like a rule choice worth a school switch (spec section 11): Hỏa/Linh
direction, Khôi/Việt table, Giải Thần and Thiên Quý, Phá Toái, Hóa Khoa/Kỵ of Canh and Nhâm,
leap-month handling, the brightness table. The Thai/Dưỡng order looks like a lasotuvi defect,
not a school. Nothing here changes chart-svc in this plan.
