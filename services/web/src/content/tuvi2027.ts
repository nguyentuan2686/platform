/**
 * "Tử vi 2027" landing pages: the year overview and one general reading per con giáp.
 * Written once at build time by Claude in Lão Thiên Cơ's voice (knowledge/tuvi/STYLE.md rules,
 * checked by tuvi2027.test.ts). Grounded in the year's can chi (Đinh Mùi, nạp âm Thiên Hà Thủy,
 * Tết 2027-02-06), the Tứ Hóa of can Đinh (Thái Âm Lộc, Thiên Đồng Quyền, Thiên Cơ Khoa, Cự Môn Kỵ)
 * and each chi's relation to Mùi (tam hợp Hợi-Mão-Mùi, lục hợp Ngọ, xung Sửu, tam hình Sửu-Tuất-Mùi,
 * lục hại Tý, năm tuổi Mùi). General per-sign text only: the call to action leads to a real chart.
 */

export const YEAR = 2027;
export const YEAR_PATH = "/vi/tu-vi-2027";

export type SectionKey = "tongQuan" | "congViec" | "taiChinh" | "tinhCam" | "sucKhoe" | "loiKhuyen";

export const SECTION_TITLES: Readonly<Record<SectionKey, string>> = {
  tongQuan: "Tổng quan",
  congViec: "Công việc",
  taiChinh: "Tiền bạc",
  tinhCam: "Tình cảm",
  sucKhoe: "Sức khỏe",
  loiKhuyen: "Lời khuyên của Lão Thiên Cơ",
};

export const SECTION_ORDER: readonly SectionKey[] = ["tongQuan", "congViec", "taiChinh", "tinhCam", "sucKhoe", "loiKhuyen"];

export type Zodiac = Readonly<{
  /** 0 = Tý … 11 = Hợi. */
  index: number;
  chi: string;
  animal: string;
  slug: string;
  /** The chi's relation to Mùi, shown as a tag. */
  relation: string;
  sections: Readonly<Record<SectionKey, string>>;
}>;

export const YEAR_INTRO: readonly string[] = [
  "Năm 2027 là năm Đinh Mùi, bắt đầu từ Tết Nguyên đán ngày 6 tháng 2 dương lịch. Đinh thuộc Hỏa, Mùi thuộc Thổ, nạp âm là Thiên Hà Thủy, nước trên trời, ta xem đó là hình ảnh của sự nuôi dưỡng chậm rãi mà bền, nhắc con đi từng bước cho chắc.",
  "Theo Tứ Hóa của can Đinh, Thái Âm hóa Lộc, Thiên Đồng hóa Quyền, Thiên Cơ hóa Khoa, Cự Môn hóa Kỵ. Ta đọc đó là một năm hợp với việc chăm chút nhà cửa, tích lũy từ từ và học thêm, còn lời ăn tiếng nói thì con nên giữ cho mềm mỏng.",
  "Những dòng dưới đây chỉ là nét chung theo con giáp, ai cùng tuổi đều đọc như nhau. Năm 2027 đi vào cung nào trong lá số của riêng con thì phải lập lá số mới thấy được.",
];

/** People born in January or early February belong to the previous lunar year's sign. */
export const LUNAR_NOTE =
  "Người sinh tháng 1 hoặc đầu tháng 2 dương lịch có thể thuộc con giáp của năm âm lịch trước. Lá số tính theo âm lịch Việt sẽ cho con biết chính xác.";

export const ZODIAC: readonly Zodiac[] = [
  {
    index: 0,
    chi: "Tý",
    animal: "Chuột",
    slug: "tuoi-ty",
    relation: "Lục hại với Mùi",
    sections: {
      tongQuan:
        "Tý và Mùi là cặp lục hại, nên năm 2027 với người tuổi Tý thường có những hiểu lầm nhỏ hơn là biến động lớn. Ta thấy con càng nói rõ, viết rõ thì năm càng nhẹ.",
      congViec:
        "Công việc của con có xu hướng tiến chậm mà chắc, nhất là những việc cần phối hợp với nhiều người. Con nên ghi lại thỏa thuận bằng văn bản, tránh để việc chung trôi theo lời hứa miệng.",
      taiChinh:
        "Tiền bạc năm này hợp với giữ hơn là mạo hiểm. Con nên cân nhắc kỹ trước khi cho vay hay góp vốn cùng người mới quen.",
      tinhCam:
        "Trong tình cảm, con dễ để bụng một câu nói vô tình của người thương. Ta khuyên con hỏi lại cho rõ trước khi buồn, nhiều chuyện sẽ tan ngay.",
      sucKhoe:
        "Nhịp sống của con năm nay dễ bị cuốn theo việc của người khác. Con hãy giữ giờ ngủ đều và dành vài buổi mỗi tuần để đi bộ cho đầu óc thư thái.",
      loiKhuyen:
        "Năm Đinh Mùi, chữ tín là vốn quý nhất của con. Con giữ trọn những lời hứa nhỏ thì việc lớn cũng dễ thành.",
    },
  },
  {
    index: 1,
    chi: "Sửu",
    animal: "Trâu",
    slug: "tuoi-suu",
    relation: "Xung với Mùi",
    sections: {
      tongQuan:
        "Sửu và Mùi đứng đối nhau, là thế xung, nên năm 2027 thường mang đến cho người tuổi Sửu nhiều đổi thay như chuyển chỗ làm, chuyển nhà hay đi xa. Ta thấy đổi thay ấy không đáng ngại, miễn là con chủ động chọn hướng đi.",
      congViec:
        "Con có thể gặp cơ hội đổi việc hoặc đổi vai trò trong năm nay. Con nên chuẩn bị kỹ trước khi quyết, đừng rời chỗ cũ chỉ vì một lúc chán nản.",
      taiChinh:
        "Thu chi của con dễ lên xuống theo những lần dịch chuyển. Con hãy để riêng một khoản dự phòng cho các chi phí phát sinh khi đi lại hay sửa sang nhà cửa.",
      tinhCam:
        "Tình cảm năm này cần thêm kiên nhẫn, vì hai người dễ bận theo hai hướng khác nhau. Con nên dành thời gian cố định cho nhau, dù chỉ là một bữa cơm cuối tuần.",
      sucKhoe:
        "Những chuyến đi và việc mới dễ làm con mệt hơn thường lệ. Con nhớ nghỉ ngơi đủ sau mỗi đợt bận và đừng thức khuya nhiều ngày liền.",
      loiKhuyen:
        "Năm xung là năm chuyển động, ta khuyên con xem nó như dịp làm mới mình. Con lên kế hoạch rõ ràng thì đổi thay sẽ thành bước tiến.",
    },
  },
  {
    index: 2,
    chi: "Dần",
    animal: "Hổ",
    slug: "tuoi-dan",
    relation: "Bình hòa với Mùi",
    sections: {
      tongQuan:
        "Dần với Mùi không xung cũng không hợp, nên năm 2027 với người tuổi Dần khá bình ổn. Ta thấy đây là năm hợp để con vun lại những việc đang dang dở.",
      congViec:
        "Trong công việc, con có lợi khi học thêm một kỹ năng mới hoặc hoàn thiện chuyên môn. Thiên Cơ hóa Khoa của năm Đinh là hình ảnh đẹp cho người chịu học như con.",
      taiChinh:
        "Tiền bạc của con đi theo nhịp đều, ít khoản bất ngờ. Con nên tập thói quen ghi chép chi tiêu hằng tháng để thấy rõ tiền đi đâu.",
      tinhCam:
        "Người tuổi Dần năm nay dễ mải việc mà quên hỏi han người thân. Con thử dành một buổi mỗi tuần chỉ để trò chuyện cùng gia đình, ta tin không khí sẽ ấm hơn.",
      sucKhoe:
        "Sức khỏe của con nhìn chung ổn nếu giữ được nếp sinh hoạt. Con nên vận động ngoài trời đều đặn, nhất là khi công việc phải ngồi nhiều.",
      loiKhuyen:
        "Một năm bình ổn là món quà cho người biết tận dụng. Con đặt vài mục tiêu nhỏ, đo được, rồi đi đều từng bước.",
    },
  },
  {
    index: 3,
    chi: "Mão",
    animal: "Mèo",
    slug: "tuoi-mao",
    relation: "Tam hợp với Mùi",
    sections: {
      tongQuan:
        "Hợi, Mão, Mùi là bộ tam hợp, nên năm 2027 khá thuận cho người tuổi Mão. Ta thấy con dễ gặp người cùng chí hướng và được giúp đỡ đúng lúc.",
      congViec:
        "Công việc của con có xu hướng mở rộng nhờ các mối quan hệ. Con nên mạnh dạn nhận việc mới và hợp tác với người con tin tưởng.",
      taiChinh:
        "Tiền bạc năm này khá hanh thông, nhất là những khoản đến từ hợp tác. Con hãy giữ sổ sách rõ ràng với cộng sự để niềm vui không thành khúc mắc.",
      tinhCam:
        "Người độc thân tuổi Mão dễ gặp người hợp ý qua bạn bè giới thiệu. Người đã có đôi thì con nên cùng nhau lên một dự định chung, ta thấy năm nay hợp để vun đắp.",
      sucKhoe:
        "Năm thuận dễ khiến con nhận quá nhiều việc. Con nhớ chừa thời gian nghỉ, ăn uống đúng bữa để giữ sức cho cả năm.",
      loiKhuyen:
        "Thời thuận là lúc nên gieo hạt cho những năm sau. Con hãy dùng năm này để xây những mối quan hệ bền và những kỹ năng lâu dài.",
    },
  },
  {
    index: 4,
    chi: "Thìn",
    animal: "Rồng",
    slug: "tuoi-thin",
    relation: "Bình hòa với Mùi",
    sections: {
      tongQuan:
        "Thìn và Mùi cùng thuộc Thổ, năm 2027 với người tuổi Thìn khá vững vàng, ít sóng gió. Ta thấy con hợp với việc củng cố nền móng hơn là mở rộng gấp.",
      congViec:
        "Trong công việc, con dễ được giao trách nhiệm lớn hơn nhờ sự đáng tin. Con nên nhận việc vừa sức và làm cho trọn, uy tín sẽ tự tăng.",
      taiChinh:
        "Tiền bạc của con hợp với tích lũy dài hạn như để dành hay sửa sang nhà cửa. Thái Âm hóa Lộc của năm Đinh là hình ảnh đẹp cho việc con chăm chút tổ ấm.",
      tinhCam:
        "Con có xu hướng giữ ý, ít nói ra điều mình mong muốn. Ta khuyên con chủ động bày tỏ, người thương sẽ hiểu con nhiều hơn.",
      sucKhoe:
        "Công việc nhiều trách nhiệm dễ làm con căng thẳng âm thầm. Con hãy tìm một thú vui nhẹ nhàng như làm vườn, đọc sách hay đi bộ buổi tối.",
      loiKhuyen:
        "Năm này con đi được xa nhờ bền bỉ chứ không nhờ tốc độ. Con giữ nhịp đều, cuối năm nhìn lại sẽ thấy mình đã tiến một quãng dài.",
    },
  },
  {
    index: 5,
    chi: "Tỵ",
    animal: "Rắn",
    slug: "tuoi-ti",
    relation: "Bình hòa với Mùi",
    sections: {
      tongQuan:
        "Tỵ với Mùi không có thế xung hợp rõ rệt, nên năm 2027 với người tuổi Tỵ là năm của sự kiên nhẫn. Ta thấy việc của con dễ đến muộn hơn mong đợi nhưng vẫn đến.",
      congViec:
        "Con có thể thấy công việc tiến chậm ở nửa đầu năm. Con nên dùng thời gian đó chuẩn bị kỹ, để khi cơ hội tới thì sẵn sàng nắm lấy.",
      taiChinh:
        "Tiền bạc năm này hợp với cách chi tiêu thận trọng. Con nên tránh những lời mời hứa hẹn lời nhanh, ta thấy chậm mà chắc hợp với con hơn.",
      tinhCam:
        "Trong tình cảm, con dễ nghĩ nhiều mà nói ít. Con thử chia sẻ một điều con đang lo với người thương, gánh sẽ nhẹ đi một nửa.",
      sucKhoe:
        "Những lúc phải chờ đợi dễ khiến con sốt ruột, ngủ không sâu. Con hãy tập thở chậm, giãn cơ trước khi ngủ và bớt dùng điện thoại lúc khuya.",
      loiKhuyen:
        "Kiên nhẫn là chìa khóa của con trong năm Đinh Mùi. Con làm tốt phần việc của mình mỗi ngày, kết quả sẽ tự tìm đến.",
    },
  },
  {
    index: 6,
    chi: "Ngọ",
    animal: "Ngựa",
    slug: "tuoi-ngo",
    relation: "Lục hợp với Mùi",
    sections: {
      tongQuan:
        "Ngọ và Mùi là cặp lục hợp, nên năm 2027 khá êm thuận với người tuổi Ngọ. Ta thấy con dễ được người xung quanh ủng hộ khi bắt đầu việc mới.",
      congViec:
        "Công việc của con có xu hướng suôn sẻ, nhất là việc cần làm cùng đối tác. Con nên tranh thủ năm này để đề xuất những ý tưởng con ấp ủ lâu nay.",
      taiChinh:
        "Tiền bạc khá thuận, có thể có thêm nguồn thu phụ. Con hãy dành một phần cho tích lũy, đừng để năm thuận trôi qua mà túi vẫn trống.",
      tinhCam:
        "Năm lục hợp là năm đẹp cho tình cảm của người tuổi Ngọ. Người độc thân thì con cứ mở lòng, người đã có đôi thì con nên cùng nhau làm một việc chung có ý nghĩa.",
      sucKhoe:
        "Năng lượng của con năm nay khá dồi dào nên dễ làm quá sức. Con nhớ xen kẽ ngày nghỉ và ăn uống điều độ để giữ sức bền.",
      loiKhuyen:
        "Được người giúp thì con cũng nên giúp lại người. Ta thấy chữ hòa sẽ giữ cho năm thuận của con kéo dài sang năm sau.",
    },
  },
  {
    index: 7,
    chi: "Mùi",
    animal: "Dê",
    slug: "tuoi-mui",
    relation: "Năm tuổi",
    sections: {
      tongQuan:
        "Năm 2027 là năm Mùi, tức năm tuổi của người tuổi Mùi. Ta không coi năm tuổi là năm đáng sợ, mà là năm con được nhìn lại chính mình và đặt lại mục tiêu.",
      congViec:
        "Con dễ được chú ý nhiều hơn trong công việc, cả lời khen lẫn lời góp ý. Con nên đón nhận góp ý bình tĩnh, đừng vội phản ứng khi đang nóng.",
      taiChinh:
        "Tiền bạc năm này hợp với kế hoạch rõ ràng hơn là quyết định theo cảm hứng. Con nên cân nhắc thêm vài ngày trước mọi khoản chi lớn.",
      tinhCam:
        "Trong tình cảm, con có xu hướng đòi hỏi cao hơn với chính mình và người thương. Ta khuyên con nhẹ nhàng với nhau, nói lời cảm ơn nhiều hơn lời trách.",
      sucKhoe:
        "Năm tuổi là dịp tốt để con chăm lại nếp sống. Con thử ngủ sớm hơn, vận động đều và nghỉ ngơi thật sự sau giờ làm.",
      loiKhuyen:
        "Năm của mình thì con là người cầm lái. Con chọn vài việc quan trọng nhất và làm cho tới, ta tin cuối năm con sẽ thấy mình vững hơn.",
    },
  },
  {
    index: 8,
    chi: "Thân",
    animal: "Khỉ",
    slug: "tuoi-than",
    relation: "Bình hòa với Mùi",
    sections: {
      tongQuan:
        "Thân với Mùi không xung khắc, năm 2027 với người tuổi Thân khá cởi mở. Ta thấy con dễ có ý tưởng mới và nhiều dịp gặp gỡ.",
      congViec:
        "Công việc của con thuận cho việc học hỏi và thử hướng mới. Con nên chọn lọc, theo đuổi một hai việc đến cùng thay vì bắt đầu quá nhiều thứ.",
      taiChinh:
        "Tiền bạc của con dễ đến từ nhiều nguồn nhỏ. Con hãy gom lại và ghi chép đều, ta thấy tiền nhỏ tích lâu thành khoản đáng kể.",
      tinhCam:
        "Con hoạt ngôn, dễ được quý mến, nhưng đôi khi khiến người thương thấy mình bị lơ là. Con nên dành sự chú tâm trọn vẹn khi ở bên người ấy.",
      sucKhoe:
        "Lịch hẹn dày dễ làm con ăn uống thất thường. Con nhớ giữ bữa sáng và uống đủ nước, những việc nhỏ ấy giúp con bền sức.",
      loiKhuyen:
        "Năm Đinh Mùi khuyên con đi sâu hơn là đi rộng. Con chọn một mục tiêu chính và dồn sức cho nó, ta tin kết quả sẽ rõ ràng.",
    },
  },
  {
    index: 9,
    chi: "Dậu",
    animal: "Gà",
    slug: "tuoi-dau",
    relation: "Bình hòa với Mùi",
    sections: {
      tongQuan:
        "Dậu với Mùi không có thế xung hợp rõ, năm 2027 với người tuổi Dậu là năm hợp với sự chỉn chu. Ta thấy con được ghi nhận nhờ làm việc cẩn thận.",
      congViec:
        "Trong công việc, con có lợi thế ở những việc cần tỉ mỉ, sắp xếp và chuẩn bị. Con nên lên kế hoạch theo quý để thấy rõ tiến độ của mình.",
      taiChinh:
        "Tiền bạc khá ổn định nếu con giữ được nguyên tắc chi tiêu. Con nên để riêng một phần tiết kiệm ngay khi nhận thu nhập rồi mới chi phần còn lại.",
      tinhCam:
        "Con có xu hướng muốn mọi thứ thật hoàn hảo, kể cả trong tình cảm. Ta khuyên con bớt soi xét chuyện nhỏ, để người thương thấy thoải mái bên con.",
      sucKhoe:
        "Tính cầu toàn dễ làm con ôm việc về nhà. Con hãy đặt giờ kết thúc công việc mỗi ngày và giữ đúng nó.",
      loiKhuyen:
        "Cẩn thận là điểm mạnh, nhưng con cũng cần biết buông những gì không quan trọng. Ta thấy nhẹ lòng thì việc mới trôi.",
    },
  },
  {
    index: 10,
    chi: "Tuất",
    animal: "Chó",
    slug: "tuoi-tuat",
    relation: "Tam hình với Mùi",
    sections: {
      tongQuan:
        "Sửu, Tuất, Mùi là bộ tam hình, nên năm 2027 người tuổi Tuất dễ gặp va chạm trong lời nói hơn mọi năm. Ta thấy chỉ cần con mềm mỏng, những va chạm ấy sẽ nhỏ và qua nhanh.",
      congViec:
        "Công việc của con dễ có lúc bất đồng với đồng nghiệp hay cấp trên. Con nên trình bày ý kiến bằng số liệu và giữ giọng ôn hòa, người khác sẽ dễ nghe hơn.",
      taiChinh:
        "Tiền bạc năm này cần rõ ràng trong mọi giao dịch với người khác. Con hãy làm giấy tờ đầy đủ, kể cả với người thân quen.",
      tinhCam:
        "Trong tình cảm, con dễ nói thẳng đến mức làm người thương chạnh lòng. Ta khuyên con nghĩ một nhịp trước khi nói, nhất là khi đang mệt.",
      sucKhoe:
        "Những va chạm nhỏ dễ làm con căng thẳng kéo dài. Con hãy tìm cách xả bớt bằng chạy bộ, bơi hay một môn thể thao con thích.",
      loiKhuyen:
        "Cự Môn hóa Kỵ của năm Đinh là lời nhắc về lời ăn tiếng nói, và người tuổi Tuất nên để tâm nhất. Con nói chậm, nói ấm thì năm nay sẽ êm.",
    },
  },
  {
    index: 11,
    chi: "Hợi",
    animal: "Lợn",
    slug: "tuoi-hoi",
    relation: "Tam hợp với Mùi",
    sections: {
      tongQuan:
        "Hợi cùng Mão và Mùi làm thành bộ tam hợp, nên năm 2027 khá thuận cho người tuổi Hợi. Ta thấy con dễ có người giúp và nhiều dịp vui trong nhà.",
      congViec:
        "Công việc của con có xu hướng được mở đường nhờ người quen giới thiệu. Con nên giữ liên lạc với bạn cũ và đồng nghiệp cũ, cơ hội dễ đến từ đó.",
      taiChinh:
        "Tiền bạc năm này khá thuận, hợp với những dự định cho gia đình. Con hãy ưu tiên những khoản chi mang lại giá trị lâu dài cho tổ ấm.",
      tinhCam:
        "Tình cảm của người tuổi Hợi năm nay ấm áp và dễ có tin vui. Con nên dành nhiều thời gian cho gia đình, ta thấy niềm vui sẽ nhân lên.",
      sucKhoe:
        "Năm thuận với nhiều cuộc vui dễ làm con ăn uống quá đà. Con nhớ cân bằng bữa ăn và vận động đều để giữ người nhẹ nhõm.",
      loiKhuyen:
        "Năm thuận là lúc con nên gieo những thói quen tốt. Con giữ nếp đều đặn thì cái thuận của năm nay sẽ thành nền cho những năm sau.",
    },
  },
];

/** Gregorian birth years (lunar years, see LUNAR_NOTE) of a sign from 1940 up to the year before YEAR. */
export function birthYears(index: number): number[] {
  const out: number[] = [];
  for (let y = 1940; y < YEAR; y++) if ((((y - 4) % 12) + 12) % 12 === index) out.push(y);
  return out;
}

export function findZodiac(slug: string): Zodiac | undefined {
  return ZODIAC.find((z) => z.slug === slug);
}

/** The public title of a sign's page, shared by metadata, JSON-LD and the heading. */
export const zodiacTitle = (z: Zodiac): string => `Tử vi ${YEAR} tuổi ${z.chi} (năm Đinh Mùi)`;
