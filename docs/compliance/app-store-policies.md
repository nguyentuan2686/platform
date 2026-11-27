# App store policies (Apple App Store, Google Play) for the tử vi product

- Date: 2026-11-26 · Author: Claude · Status: research for a decision, **not legal advice**
- Sources: Apple App Review Guidelines (page dated 2026-10-16), Google Play Policy Center and Play Console Help, plus news sources where marked. Re-check every rule before submitting a build: both stores change these pages several times a year.

## 1. Is there a mobile app?

**No.** `services/` holds `chart-svc`, `reading-tuvi`, `identity-svc`, `payment-svc`, `notify-svc`, `order-svc` and `web` (Next.js). No iOS, Android, React Native, Capacitor or Flutter project exists. The fortune spec (`docs/superpowers/specs/2026-11-24-fortune-platform-design.md` §2) lists **"app mobile" as a non-goal for the first version**, and the roadmap (§9: F1 to F4) has no app phase.

So no store rule applies today. This document says what an app would need, checks which of those needs the current web and backend already meet, and lists what to change before an app is started. Some items (account deletion, AI disclosure, disclaimers) also matter now for the web under Vietnamese law (spec §15).

## 2. The rules that matter, and how they apply

### 2.1 Payments for digital goods (biggest business impact)

| Rule | What it says | Effect on us |
|---|---|---|
| Apple [3.1.1](https://developer.apple.com/app-store/review/guidelines/#in-app-purchase) | Credits, in-app currencies, premium content and subscriptions unlocked in the app must use In-App Purchase. Purchased credits must not expire. | Lượt and thẻ tháng/năm are digital goods. In an iOS app they must be sold by IAP (consumable for lượt; auto-renewable or non-renewing subscription for thẻ). Our lượt already never expire (spec §13.2). |
| Apple [3.1.3(b) Multiplatform services](https://developer.apple.com/app-store/review/guidelines/#other-purchase-methods) | An app may let users use content bought on your website **only if those items are also sold by IAP in the app**. | Lượt bought by VietQR on the web can be used in the app only if the app also sells them by IAP. |
| Apple [3.1.1(a), 3.1.3](https://developer.apple.com/app-store/review/guidelines/#other-purchase-methods) | Outside the US storefront (and a few entitlement countries), the app and its metadata may not link to or mention buying elsewhere. | On the Vietnamese storefront the app may not say "nạp lượt trên web" or show the VietQR flow. |
| US anti-steering ruling (Epic v. Apple) | Since 2025 the US storefront may link out to web purchase. | **US storefront only.** No effect on a Vietnam listing. Commission terms still in court (news sources). |
| Regional programs (Apple) | Alternative payments exist in South Korea ([KR](https://developer.apple.com/support/storekit-external-entitlement-kr)), the EU ([DMA](https://developer.apple.com/support/alternative-payment-options-on-the-app-store-in-the-eu)), Japan (MSCA, [Dec 2025](https://www.apple.com/newsroom/2025/12/apple-announces-changes-to-ios-in-japan/)). | **Vietnam has none.** Full IAP rules apply. |
| Google Play [Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738) | In-app digital goods must use Play Billing; no steering to other payment methods (buttons, links, webviews, wording). | Same as Apple: lượt sold in the Android app must use Play Billing. |
| Google [User Choice Billing](https://support.google.com/googleplay/android-developer/answer/13821247) | Alternative billing only in listed countries (EEA, UK, AU, BR, ID, JP, ZA, plus KR, IN, US programs). | **Vietnam is not listed.** |
| Google US changes ([Epic v. Google](https://support.google.com/googleplay/android-developer/answer/15582165)) | From 2025-10-29, US users may be shown external offers and links. | US users only. |
| Google "consumption-only" apps ([Play Console Help](https://support.google.com/googleplay/android-developer/answer/10281818)) | An app that sells nothing and does not steer may let users use content bought elsewhere. | Option for Android only: an app with **no purchase screen at all** where logged-in users spend lượt bought on the web. Apple has no equivalent for consumables: 3.1.3(b) still demands IAP. |

**Decision for Tuấn:** in-app prices must absorb the store fee (15% under the small-business programs: [Apple](https://developer.apple.com/app-store/small-business-program/), [Google](https://support.google.com/googleplay/android-developer/answer/112622); up to 30%). Options: (a) same VND price in app and web, lower margin in app; (b) higher in-app price (allowed; the app may not tell users the web is cheaper); (c) Android consumption-only first, iOS later.

### 2.2 Spam, minimum functionality, webview wrappers (biggest rejection risk)

| Rule | What it says | Effect on us |
|---|---|---|
| Apple [4.3(b) Spam](https://developer.apple.com/app-store/review/guidelines/#spam) | Names **fortune telling** among saturated categories (with dating, flashlights, wallpapers); such apps are rejected unless they offer a unique, high-quality experience. | Tử vi is fortune telling. The app must show what is ours: a computed Tử Vi Đẩu Số chart (not a template horoscope), the Vietnamese lunar calendar, the curated knowledge base, the Lão Thiên Cơ character. |
| Apple [4.2 / 4.2.2 Minimum functionality](https://developer.apple.com/app-store/review/guidelines/#minimum-functionality) | An app must be more than a repackaged website. | A plain WebView around the Next.js site will very likely be rejected (4.2 together with 4.3(b)). |
| Google [Minimum functionality](https://support.google.com/googleplay/android-developer/answer/9898783), [Spam / webview](https://support.google.com/googleplay/android-developer/answer/9899034) | No apps with very limited function; webview apps only for sites you own. | We own the site, so webview spam is not the issue; low function is. |

**What to build if an app is wanted:** native chart rendering and saved charts offline, a native "Hôm nay" home-screen widget and daily notification, native IAP and Sign in with Apple, native share sheet for the share card. Keep the web for SEO.

### 2.3 Accounts

| Rule | What it says | Status now (code) |
|---|---|---|
| Apple [5.1.1(v)](https://developer.apple.com/app-store/review/guidelines/#data-collection-and-storage) | If an app supports account creation, users must be able to **delete the account from inside the app**. Features that do not need an account should work without one. | **Gap.** `/vi/tai-khoan` deletes the chart and Q&A history (`deleteProfile` in `services/web/src/app/vi/tai-khoan/actions.ts`: `DELETE /tuvi/qa/readings` then `DELETE /me/profile`), but there is no endpoint or page that deletes the **account** itself (email, sessions, credit ledger, share link). Free chart without login: **met** (guest flow). |
| Google [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111) | In-app deletion **and** a public web page where users can ask for deletion without the app; answers in the Data safety "data deletion" section. Data kept for legal reasons (tax, fraud) must be disclosed. | **Gap**, same as above, plus the public web page. |
| Apple [4.8 Login services](https://developer.apple.com/app-store/review/guidelines/#login-services) | An app that uses a third-party login (Google) for the main account must also offer an equivalent privacy-preserving login (Sign in with Apple qualifies). The app's own account system (our email magic link) does not count as third-party. | **Gap if an iOS app keeps Google login.** identity-svc has magic link + Google OIDC only. Either add Sign in with Apple (OIDC, same shape as the Google adapter) or ship the iOS app with magic link only. |

### 2.4 Privacy

| Rule | What it says | Status now (code) |
|---|---|---|
| Apple [5.1.1(i)](https://developer.apple.com/app-store/review/guidelines/#data-collection-and-storage), Google [User Data](https://support.google.com/googleplay/android-developer/answer/10144311) | A privacy policy linked in the store listing and inside the app: what is collected, how it is used, third parties, retention, deletion, how to withdraw consent. | **Mostly met** by `/vi/chinh-sach-rieng-tu`: data collected, AES-GCM at rest, 90-day free-chat retention, anonymised cross-border AI transfer, deletion, revoking share links. **Missing:** who the data controller is (name/legal entity, contact address), and the names of the AI providers. Both need Tuấn (spec §15: legal entity not decided; the repo must not contain his email or phone). |
| Apple [5.1.2(i)](https://developer.apple.com/app-store/review/guidelines/#data-use-and-sharing) (updated Nov 2025, [TechCrunch](https://techcrunch.com/2025/11/13/apples-new-app-review-guidelines-clamp-down-on-apps-sharing-personal-data-with-third-party-ai)) | Before sharing personal data with third parties, **including third-party AI**, disclose it clearly and get explicit permission. | **Met in substance**: `QaAsk` asks for consent before the first question ("gửi lá số ẩn danh và câu hỏi tới nhà cung cấp AI ở nước ngoài"); reading-tuvi strips PII (`PiiScrubber`) and never sends name/email/full birth date. **To do for an app:** name the provider(s) in the consent text once prod providers are fixed (spec §6.4: Groq, then Gemini). |
| Apple [App Privacy labels](https://developer.apple.com/app-store/app-privacy-details/), [privacy manifests](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files) / [required-reason APIs](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api) | Declare data types and purposes in App Store Connect; ship a privacy manifest. | Not applicable yet. Data to declare: email (account), birth date/time and gender (app function, linked to user), purchase history, user content (questions), no tracking, no ads. |
| Google [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469) | Form required for every app; a processor acting only for us is not "sharing". | Not applicable yet. Whether the LLM provider is a processor or a third party depends on its terms (free tiers may train on inputs, spec §7): check before filling in. |

### 2.5 AI-generated content, harm, disclaimers

| Rule | What it says | Status now (code) |
|---|---|---|
| Google [AI-Generated Content](https://support.google.com/googleplay/android-developer/answer/14094294) | Apps whose central feature is an AI chatbot must let users **report or flag offensive AI output inside the app** and use reports to improve filtering. | **Gap.** Q&A has no "Báo cáo câu trả lời" button. Filtering is strong (below), but the reporting channel is required. |
| Apple [1.2 / 4.7](https://developer.apple.com/app-store/review/guidelines/#user-generated-content) | Chatbots and AI content need filtering, a reporting mechanism and published contact details. | Filtering **met**; reporting **gap** (same button); contact details **gap** (needs Tuấn). |
| Apple [1.4.1](https://developer.apple.com/app-store/review/guidelines/#physical-harm), [1.1.6](https://developer.apple.com/app-store/review/guidelines/#objectionable-content); Google [Deceptive behavior](https://support.google.com/googleplay/android-developer/answer/9888077) | No harmful medical/financial guidance; "for entertainment" labels do not excuse false claims; no claims of impossible accuracy in listings. | **Met** in code: `QuestionGuard` refuses MEDICAL, LEGAL, FINANCE, GAMBLING, DEATH, SELF_HARM, INJECTION before the LLM; `AnswerFilter` checks the output; refused questions cost no lượt. Disclaimer "Nội dung mang tính tham khảo văn hóa và giải trí" in the footer of every page; AI label "Nội dung do AI biên soạn từ kho tư liệu." under each answer (`QaMessages`, `qaText.ts`). Store listing copy must not promise accurate predictions. |

### 2.6 Age rating, minors

| Rule | What it says | Effect on us |
|---|---|---|
| Apple [age ratings](https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions/) (new 13+/16+/18+ questionnaire, [2025](https://developer.apple.com/news/upcoming-requirements?id=07242025a)); Google IARC questionnaire in Play Console | Astrology is not itself a rating item. An in-app browser with unrestricted web access raises the rating to 16+; medical information and gambling items raise it further. | Keep in-app navigation locked to our domain. Lượt are not chance-based: answer "no" to gambling. The terms already require 18+ to buy and `PackPicker` has a "Tôi đủ 18 tuổi" checkbox; consider rating the app 16+ or 18+ to match. |

### 2.7 User-generated content

| Rule | What it says | Effect on us |
|---|---|---|
| Apple [1.2](https://developer.apple.com/app-store/review/guidelines/#user-generated-content), Google [UGC](https://support.google.com/googleplay/android-developer/answer/9876937) | Content users post that other users see needs moderation, reporting, blocking, terms. | Share pages show a **computed** chart and the rule-based basic reading, not free text typed by users, so they are arguably not UGC (no explicit ruling found). Keep it that way: never put a user's question or an LLM answer on a public page without adding UGC tools. Paid readings are shareable only one by one (spec §13.4), which would fall under this rule if built. |

### 2.8 Vietnam-specific

- **Sales app notification (Bộ Công Thương, online.gov.vn):** an app that sells your own services must be notified like a sales website (Decree 52/2013 as amended, Circular 59/2015; [secondary source](https://luatvietan.vn/thong-bao-ung-dung-ban-hang-voi-bo-cong-thuong.html)). The 2025 E-commerce Law 122/2025/QH15 (in force 2026-07-01) may change the procedure: **not verified, ask a lawyer**. Spec §15 already lists the website notification.
- **Decree 147/2024** (internet services): mainly social networks, games and app stores; no general registration duty found for this kind of app.
- **Personal Data Protection Law 91/2025/QH15** (spec §15): right to delete applies to the web now, which is why account deletion below is worth doing before any app.
- Neither store has a Vietnam-specific developer requirement that we found (the EU DSA "trader status" applies only when distributing in the EU).

### 2.9 Technical gates for a future build

- Google Play: new apps and updates must target **Android 16 (API 36)** since 2026-08-31 ([target SDK](https://developer.android.com/google/play/requirements/target-sdk)).
- Apple: privacy manifest; every third-party SDK on Apple's list must ship its own manifest and signature ([third-party SDK requirements](https://developer.apple.com/support/third-party-SDK-requirements)).

## 3. Requirement → status table (against the code on 2026-11-26)

Legend: **met** · **gap** · **n/a yet** (only matters once an app exists).

| # | Requirement | Source | Status | Evidence / what is missing |
|---|---|---|---|---|
| 1 | Free core function works without an account | Apple 5.1.1(v) | **met** | Guest chart, basic reading, "Hôm nay" on `/vi`, `/vi/la-so` |
| 2 | Delete the whole account from inside the product | Apple 5.1.1(v), Google account deletion, VN PDP law | **gap** | Only chart + Q&A history deletion (`/vi/tai-khoan` → "Xóa lá số"). No `DELETE /me` in identity-svc |
| 3 | Public web page to request account deletion | Google account deletion | **gap** | Follows from #2: `/vi/xoa-tai-khoan` |
| 4 | Privacy policy, reachable from every page | Apple 5.1.1(i), Google User Data | **met** | `/vi/chinh-sach-rieng-tu`, footer link on every page |
| 5 | Privacy policy names the controller and a contact | Apple 5.1.1(i), Apple 1.2, VN PDP law | **gap** | Needs Tuấn's legal entity (hộ kinh doanh) and a support address (not his personal email in the repo) |
| 6 | Consent before sending data to third-party AI | Apple 5.1.2(i) | **met** (web) | `QaAsk` consent checkbox on the first question; PII scrubbed |
| 7 | AI provider named in the consent / policy | Apple 5.1.2(i) (strict reading) | **gap** | Name them once prod providers are configured |
| 8 | AI-generated content labelled | Good practice, VN AI rules (spec §15) | **met** | `AI_LABEL` under every answer |
| 9 | In-product reporting of an AI answer | Google AI-Generated Content, Apple 1.2/4.7 | **gap** | No report button or report store |
| 10 | Refuse medical / legal / financial / self-harm advice | Apple 1.4.1, 1.1.6 | **met** | `QuestionGuard` (7 reasons) + `AnswerFilter`; refusal does not spend a lượt |
| 11 | Entertainment / cultural disclaimer | Spec §15, Apple 1.1.6 (necessary, not sufficient) | **met** | Footer on every page; terms "Tính chất dịch vụ" |
| 12 | 18+ to buy | Spec §15 | **met** | Terms + `PackPicker` checkbox |
| 13 | Digital goods sold through IAP / Play Billing in the app | Apple 3.1.1, Google Payments | **n/a yet** | payment-svc has SePay (and PayPal later) only; an app needs StoreKit 2 / Play Billing adapters with server-side receipt validation → `PaymentSucceeded` |
| 14 | No steering to web purchase in VN storefronts | Apple 3.1.3, Google Payments | **n/a yet** | App build must hide `/vi/mua` and VietQR |
| 15 | Sign in with Apple (or equivalent) if Google login is offered | Apple 4.8 | **n/a yet** | identity-svc: magic link + Google only |
| 16 | Not a webview wrapper; unique experience | Apple 4.2, 4.3(b) | **n/a yet** | Product decision (§2.2) |
| 17 | Public pages carry no full birth date | Spec §13.4, privacy | **met** | `toPublicChart` strips the lunar date; share page `noindex` + `no-store` |
| 18 | Data export (right of access) | VN PDP law (not a store rule) | **gap** (minor) | Users can view chart and history; no download. Optional |
| 19 | Store listing claims no guaranteed accuracy | Google Deceptive behavior, Apple 2.3 | **n/a yet** | Listing copy, when written |
| 20 | Sales app notified to Bộ Công Thương | VN e-commerce rules | **n/a yet** | Owner action, with the website notification |

## 4. Concrete changes we can make now (web + backend)

Ordered by value. None is done in this PR: #1 and #2 change identity-svc, reading-tuvi and payment-svc contracts, and #1 needs Tuấn's decisions below.

1. **Account deletion ("Xóa tài khoản")** — closes #2 and #3, and is the VN PDP law's right to delete.
   - identity-svc `DELETE /me` (contract first in `contracts/openapi/identity-svc.yaml`): delete chart profile, share link, sessions, Google link, email; keep the credit ledger and orders **anonymised** (account id replaced by a tombstone) because accounting records must be kept (spec §15: tax). Publish an `AccountDeleted{account_id}` event (outbox) so reading-tuvi deletes Q&A readings idempotently.
   - web: "Xóa tài khoản" in `/vi/tai-khoan` behind a confirmation dialog (same pattern as `DeleteChart`), plus a public page `/vi/xoa-tai-khoan` that explains what is deleted and kept and links to the in-account flow (Google needs a URL that works without the app).
   - Privacy policy: add the deletion and retention text.
   - **Decisions for Tuấn:** what happens to unused paid lượt on deletion (forfeit after a warning, or a refund request first); how long anonymised orders are kept.
2. **"Báo cáo câu trả lời" button** on each answer (closes #9): reading-tuvi `POST /tuvi/qa/readings/{id}/reports` {reason}; admin page lists reports next to the unmatched-payments queue.
3. **Privacy policy additions** (#5, #7): controller name, contact address (a dedicated support mailbox), AI provider names. Blocked on Tuấn.
4. **Data export** (#18, optional): "Tải dữ liệu của tôi" as JSON from `/vi/tai-khoan`.

Before an app starts (not now): IAP + Play Billing adapters in payment-svc with server-side validation (App Store Server API, Play Developer API), Sign in with Apple in identity-svc, a native shell with real native features (§2.2), store listing and privacy forms.

## 5. Owner actions and decisions

- Decide whether a mobile app is in scope at all (spec says no for v1). If yes: native features plan, IAP pricing (§2.1 options), Android-first or both.
- Apple Developer Program (99 USD/year) and Google Play Console (25 USD once) accounts; as a business (hộ kinh doanh/company) the account shows that name and needs a D-U-N-S number on Apple.
- Legal entity, support mailbox, Bộ Công Thương notification, lawyer check of the 2025 E-commerce Law (spec §15).
- Account-deletion policy for unused paid lượt.
- Fix the prod LLM providers so the privacy policy and consent can name them; check each provider's data-use terms.

## Sources

Apple: [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/), [age ratings](https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions/), [upcoming requirements](https://developer.apple.com/news/upcoming-requirements?id=07242025a), [privacy manifests](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files), [third-party SDKs](https://developer.apple.com/support/third-party-SDK-requirements), [KR entitlement](https://developer.apple.com/support/storekit-external-entitlement-kr), [EU alternative payments](https://developer.apple.com/support/alternative-payment-options-on-the-app-store-in-the-eu), [Japan changes](https://www.apple.com/newsroom/2025/12/apple-announces-changes-to-ios-in-japan/).
Google: [Payments](https://support.google.com/googleplay/android-developer/answer/9858738), [User Choice Billing countries](https://support.google.com/googleplay/android-developer/answer/13821247), [US program](https://support.google.com/googleplay/android-developer/answer/15582165), [Account deletion](https://support.google.com/googleplay/android-developer/answer/13327111), [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469), [AI-Generated Content](https://support.google.com/googleplay/android-developer/answer/14094294), [UGC](https://support.google.com/googleplay/android-developer/answer/9876937), [Deceptive behavior](https://support.google.com/googleplay/android-developer/answer/9888077), [Minimum functionality](https://support.google.com/googleplay/android-developer/answer/9898783), [Spam](https://support.google.com/googleplay/android-developer/answer/9899034), [Target SDK](https://developer.android.com/google/play/requirements/target-sdk).
Other: [TechCrunch on 5.1.2(i)](https://techcrunch.com/2025/11/13/apples-new-app-review-guidelines-clamp-down-on-apps-sharing-personal-data-with-third-party-ai), [Baker McKenzie on Decree 147/2024](https://insightplus.bakermckenzie.com/bm/data-technology/vietnam-decree-no-1472024-to-replace-decree-no-72), [Luật Việt An on sales-app notification](https://luatvietan.vn/thong-bao-ung-dung-ban-hang-voi-bo-cong-thuong.html).
