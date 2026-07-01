# FUTURE.md — Boutique Manager Roadmap

A native Android companion app for Shopware 6 shops. This document captures where the app
stands, what comes next (with concrete API + code pointers), and what we deliberately won't build.

---

## 1. Current state (June 2026)

| Area | Status |
|---|---|
| Onboarding | 3-page animated carousel + 4-step connect wizard (URL probe → admin login → ACL verify checklist → personalize with tint, currency, content language, daily target) |
| Auth | OAuth2 `password` grant for sign-in only; the app persists a rotating `refresh_token` (AES-GCM via AndroidKeyStore, `data/store/Crypto.kt`) — never the password. Revoked/expired sessions surface as `ApiError.AuthExpired` with sign-in-again in shop settings. Integration (`client_credentials`) support was removed June 2026 |
| Dashboard | Single-shop scoped; revenue/orders via `/_admin/dashboard/order-amount/{since}?timezone=` (timezone-aware, falls back to DAL histogram), open/unpaid orders, low stock, needs-attention, recent orders |
| Orders | Detail screen (line items, totals, customer, payment/shipping method, tracking codes) + live state transitions for order / order_transaction / order_delivery via generic state-machine endpoints |
| Customers | Live searchable listing + detail screen (order history); summary tiles show real customer count, top spender and repeat buyers |
| Promotions | Read-only list with derived Active/Scheduled/Ended status, discounts as detail text, `orderCount` redemptions |
| Reports | 7-day revenue, top products (line-item terms aggregation), cross-shop comparison |
| Listing pattern | Reusable `ui/listing` foundation (admin sw-filter-panel pattern): `ListingFilter` (Text/NumberRange/DateRange/Options/Existence), `ListingState<T>` pager with stale-fetch guard, `ListingScaffold` (search, quick chips, filter sheet, LazyColumn auto-paging, **slide-down pull-to-refresh** — Material3 `PullToRefreshBox` wraps the list; `ListingState.refresh()` reloads page 1 behind a dedicated `refreshing` flag so current rows stay visible and the load-more spinner is suppressed during the pull). Consumers: Orders (8 admin-derived filters incl. payment/delivery status, date presets, value range, documents existence), Reviews (server-side status filter), Customers (live searchable listing), Promos (live listing). Replaced the former device-local Tasks feature |
| Language | `sw-language-id` + `sw-inheritance` headers; per-shop language picker (wizard + Manage shops); locale-aware money formatting from `language.locale.code` |
| Multi-shop | Shop switcher, Manage shops (add/remove/language). Demo mode (Thread & Co sample data) was removed June 2026 — every shop is a real Admin API connection; legacy persisted demo shops are purged at load |
| Persistence | Typed DataStore JSON (`AppData`), offline-first snapshots with background refresh |

**Dev environment:** `docker run -d --name sw-demo -p 8000:8000 ghcr.io/friendsofshopware/shopware-demo-environment:6.7.8`
(admin/shopware), seeded via `tools/seed_shopware.py` (orders, promos, low stock); the app connects
with the admin login.
Emulator reaches it at `http://10.0.2.2:8000`.

---

## 2. Near-term increments

> **Status (June 2026): all of §2 is shipped** — review inbox (2.1), product
> quick-actions (2.2), localized transition labels (2.3), order documents with
> PDF share (2.4), promo toggle + individual code generation (2.5), and
> camera → product-cover upload (2.6). Each was verified end-to-end against a
> live 6.7.8 instance on the emulator. The sections below are kept as the
> implementation/API reference.

Ordered by value-per-effort. Effort: S (≤½ day), M (1–2 days), L (multi-day).

### 2.1 Review approval inbox — S ✅
The smallest admin module (`sw-review`, 6 components) and a perfect mobile inbox.

- **Read:** `POST /api/search/product-review` — fields `title`, `content`, `points`, `status`,
  `createdAt`, associations `product` (translated name), `customer`.
- **Action:** PATCH `/api/product-review/{id}` with `{"status": true|false}` (approve/reject).
- **UI:** badge-counted queue (status = null/false), swipe or button approve/reject, star rating display.
- **ACL:** `product_review:read`, `product_review:update` — add to wizard verify probes.
- **Push-notification candidate** once polling lands (see 3.1).

### 2.2 Product quick-actions — M ✅
Not the 8-tab product editor — just the actions a manager needs standing in the stockroom.
Pairs directly with the existing low-stock attention list.

- **Stock update:** PATCH `/api/product/{id}` `{"stock": n}`.
- **Active toggle:** PATCH `{"active": bool}`.
- **Price (simple products):** PATCH `price` array (gross/net/linked) — guard variants/advanced prices
  (`parentId != null`, `prices` association non-empty → read-only with hint).
- **Entry points:** tap low-stock attention row → product sheet; later a product search screen
  (`POST /api/search/product` with `term`).
- **ACL:** `product:read`, `product:update`.
- **Caveat:** stock writes race with checkout; always re-read after write (admin does the same — reload after save).

### 2.3 Translated transition labels — S ✅
The transition sheet currently humanizes `technicalName`s. Resolve display names by loading the
target states once: `POST /api/search/state-machine-state` filtered by
`stateMachine.technicalName` IN (`order.state`, `order_transaction.state`, `order_delivery.state`)
with the shop's `sw-language-id`; cache per shop in the snapshot. Then "Bezahlt" instead of "Paid".

### 2.4 Order documents — M ✅
Round out the order detail screen.

- **List:** association `documents` (+ `documentType`) on the order fetch.
- **Generate:** `POST /api/_action/order/document/{documentType}/create` (batch payload
  `[{orderId, config: {...}}]`) — types `invoice`, `delivery_note`, `credit_note`, `storno`.
- **Download/share/open:** `GET /api/_action/document/{documentId}/{deepLinkCode}` → cache PDF,
  then ACTION_VIEW (open in viewer) or ACTION_SEND (share sheet).
- **ACL:** `document:read`, `document:create`.

**Rich state-change dialog ✅ (June 2026):** the Order/Payment/Delivery cards' Change action now
opens a two-phase sheet (pick destination → options): send-confirmation-email toggle (default on),
a checklist of the order's documents to attach to that mail, and an internal-comment field —
mirroring the admin. Posts to the entity-specific `POST /_action/{entity}/{id}/state/{actionName}`
(entity ∈ order / order_transaction / order_delivery) with `{sendMail, documentIds, internalComment}`
in the body (the generic `/_action/state-machine/...` URL can't carry these). Built from the
transition's `actionName` + the card's `entity`/`entityId`.

**Order timeline ✅ (June 2026):** an "Activity" card at the bottom of the order detail shows the
state history. `fetchOrderTimeline` (in `OrderQueries.kt`) searches `state-machine-history` with
`equalsAny("referencedId", …)` over the order + transaction + delivery ids (taken from
`OrderDetail.states[].entityId`), sorted by `createdAt`, with `toStateMachineState` + `user`
associations. Renders newest-first as a dot+rail vertical timeline: each row is
`{entityLabel} · {translated target state}` with a relative time and the acting user (null user →
"automatic", e.g. webhook-driven `paid`). Dot color reuses `stateTone` (Done=primary, Cancelled=error,
In-Progress=tertiary, else outline). Loaded after the detail in the VM and refreshed on transition; a
history fetch failure degrades to an empty list (the card just hides), never blocking the detail.
`OrderTimelineEntry` model carries epoch millis (formatted in the UI per the no-stored-strings rule).
Verified live on 6.7.8: order #12200's 8 order-level transitions matched the API exactly.

### 2.5 Promo write-support — M ✅
- **Toggle active:** PATCH `/api/promotion/{id}` `{"active": bool}`.
- **Generate codes:** `POST /api/_action/promotion/codes/generate-individual?codePattern=…&amount=n`
  then `POST /api/_action/promotion/codes/add-individual` (admin service:
  `sw-promotion-v2/service/promotion-code.api.service.js:41`).
- Skip the rule/condition builder entirely (desktop-only).

### 2.6 Camera → media upload — M ✅
The one place mobile beats desktop. Product photo from the phone camera straight into the shop.

- **Upload:** `POST /api/_action/media/{mediaId}/upload?extension=jpg&fileName=…` with binary body
  (create the `media` entity first, optionally in a folder).
- **Assign to product:** create `product_media` + set `coverId`.
- **UI:** from the product quick-action sheet (2.2) — CameraX or `ActivityResultContracts.TakePicture`.
- **ACL:** `media:create`, `media:update`, `product:update`.

---

## 3. Medium-term

### 3.1 Background sync + notifications — L ✅ → superseded by FCM push (June 2026)
- ~~`WorkManager` periodic sync per shop (15-min minimum interval) reusing `ShopDataSource`;
  local notifications on deltas (new orders, payment overdue, review waiting, stock dropped).~~
  **Removed June 2026:** the polling `SyncWorker` was deleted in favour of FCM push (below). Order
  alerts now arrive instantly via push instead of up to 15 min later, and there's no battery drain
  from periodic background work. The trade-off (accepted): the review / low-stock / unpaid local
  alerts and the periodic Home-snapshot + widget refresh are gone — Home and the widgets now refresh
  only when the app is opened (or on manual pull-to-refresh). `ShopwareApp` cancels any legacy
  `shop-sync` work on startup so existing installs stop polling after the update. The notification
  channel plumbing survives in `data/sync/Notifications` (`ensureChannels`, `EXTRA_SHOP_ID`).

**FCM order push ✅ (June 2026):** the "true server push" replacement, via an external Shopware
app server (a separate project, not in this repo). Android side: Firebase Messaging
(`firebase-bom` + `firebase-messaging`, `google-services` plugin applied only when
`google-services.json` is present so CI without it still builds — the config is public, not secret).
On connect (and on `FcmService.onNewToken` rotation, and `ShopwareApp.onCreate`) the app upserts its
FCM token into each connected shop's `ce_fcn` entity via `POST /api/ce-fcn`, keyed by a stable
per-install id (`AppData.pushInstallId`) so repeats update in place. Background/startup registration
is best-effort per shop. The **connect flow** instead uses `registerPushForShop` →
`PushRegisterResult`: a `POST /api/ce-fcn` **404** (`FRAMEWORK__DEFINITION_NOT_FOUND` — the entity
doesn't exist because the FroshMobilePush app isn't installed) → `ApiError.NotFound` →
`AppNotInstalled`, and the wizard shows a **"Push app not installed"** dialog before opening the
dashboard (other failures are treated as Ok so transient errors don't nag). We deliberately **don't**
call the app server's `/sync/{shopId}` — its 15-min TTL backstop picks up new tokens.
`FcmService.onMessageReceived` posts a notification on the existing `orders` channel and deep-links:
the push `data` carries `shopUrl` + `orderId`, and `MainActivity`/`AppRoot` match the shop by APP_URL
and navigate to `order/{shopId}/{orderId}`. Verified live on 6.7.8: connecting with the push app wrote
a real FCM token into `ce_fcn` (row id == persisted `pushInstallId`); deactivating the app made
`ce_fcn` 404 and the connect dialog appeared. The actual push delivery needs the app server's real
`FCM_SERVICE_ACCOUNT` secret configured (external app server).

### 3.2 Customer detail — M ✅ (contact + address editing shipped)
Upgrade the read-only list (`sw-customer` is only 26 components, nothing desktop-bound):
addresses, per-customer order history (`POST /api/search/order` filtered by
`orderCustomer.customerId`), contact data edit.

**Contact + address editing ✅ (June 2026):** an Edit (pencil) action on the customer detail
header opens `CustomerEditSheet` (`ui/screens/CustomerEditSheet.kt`) — a `ModalBottomSheet`
editing customer-level fields (salutation, first/last name, email, title, company →
`PATCH /customer/{id}`) plus the default billing and shipping addresses (street, line 2, zip,
city, country, phone, company → `PATCH /customer-address/{id}`). When billing and shipping share
one record (`defaultBillingAddressId == defaultShippingAddressId`) only one "Address" block shows.
Salutation + country pickers load once (`fetchSalutations`, `fetchCountries` — 250 active
countries) via a read-only-TextField + `DropdownMenu` anchor (`DropdownAnchor`, avoids
ExposedDropdownMenuBox API churn). Blank optional fields are sent as JSON null to clear them.
Save patches customer then each changed address, calls `repo.refresh`, reloads detail. No write-ACL
probe exists, so a login without `customer:update` surfaces the server's `missingPrivileges` error in
the sheet. Verified live on 6.7.8: edited Max Mustermann's company → persisted (`company: "Acme"`),
reverted. New queries/saves in `data/source/CustomerQueries.kt`; structured fields added to
`CustomerDetail` + `EditableAddress`/`SalutationOption`/`CountryOption` in `CustomerModels.kt`.

### 3.3 App UI localization — S/M ✅
Content language is done; the app chrome is English-only. Add `values-de/strings.xml`
(and extract hardcoded strings into resources first — they're currently inline composable literals).
Do **not** reuse admin snippets — their keys map to Vue components, not these screens.
Shipped: ~250 strings + plurals extracted into `values/strings.xml`, full German translation in
`values-de/`, verified live with per-app locale `de-DE`. Relative timestamps and week-chart day
labels were moved from persisted formatted strings to epoch millis formatted in the UI
(`ui/RelativeTime.kt`), so they re-localize with the app language.

### 3.4 Adaptive layouts — M ✅
The 560 dp centered column works on tablets but wastes the canvas. Material 3 adaptive:
list-detail pane for orders **and customers** (`OrdersTab`/`CustomersTab` — list left, detail
right, `NavigableListDetailPaneScaffold`, gated on `isExpandedWidth()`; compact stays
single-pane via nav), navigation rail instead of bottom bar on expanded width, two-column Home.
The design handoff explicitly targeted phone + tablet.

**Listing grid on tablets ✅ (June 2026):** the listing screens that have no list-detail pane —
Products, Promotions, Reviews — were still a narrow 560 dp centered column on tablets. `ListingScaffold`
gained a `gridOnExpanded` flag: when set AND `isExpandedWidth()`, the rows flow into a 2-column
`LazyVerticalGrid` (`GridCells.Fixed(2)`) and the content cap widens to 1100 dp; the toolbar
(search/chips/results) and the `header` slot span both columns (`GridItemSpan(maxLineSpan)`). The
infinite-scroll trigger reads the grid's layout when active. Compact width and the non-opted-in screens
keep the original `LazyColumn` verbatim. Cards already `fillMaxWidth()`, so they fill the cell.

**More sub-screens keep the rail ✅ (June 2026):** Products/Promos/Media/Reviews used to push
standalone nav routes that replaced the whole screen, hiding the navigation rail/bottom bar. They now
render **inside** `MainScreen`'s More tab via a `MoreDest` enum + `moreDest` state (the More tab shows
its menu when `moreDest == null`, else the chosen screen with `onBack = { moreDest = null }`). The
`NavigationSuiteScaffold` (rail/bar) therefore stays visible. A `BackHandler` returns to the More menu;
re-tapping the More tab also resets it. Home's "reviews awaiting approval" deep-link switches to
`tab = More; moreDest = Reviews` so it keeps the rail too. The old `products/promos/media/reviews`
routes and the now-unused `MainScreen` `onOpen*` params were removed (`order`/`customer` detail keep
their routes — those are list-detail panes).

**More menu tablet grid ✅ (June 2026):** the menu itself (the `MoreTab` entries) was a narrow
560 dp single column wasting the tablet canvas. On `isExpandedWidth()` it now lays the entries out as a
2-column `FlowRow` of taller tiles (icon in a `primaryContainer` badge above title/description), and
the `ScrollingTab` cap widens to 1100 dp; compact width keeps the original full-width chevron rows.
Entries are built into a `MoreItem` list once (still ACL-gated) and rendered by either path; an odd
count pads the last grid row so the lone tile stays half-width.

**Pull-to-refresh ✅ (June 2026):** slide-down-to-refresh on every scrollable tab. `ScrollingTab`
(the snapshot-backed Home/Reports column) gained optional `isRefreshing`/`onRefresh` params — when
passed it wraps its content in a Material3 `PullToRefreshBox`; Home/Reports bind `isRefreshing` to the
shop's `SyncState.Syncing` and `onRefresh` to `vm.refresh(shopId)` (Reports additionally bumps a
`refreshSignal` int that a `LaunchedEffect` in `ReportsScreen` turns into `ReportsViewModel.reload()`
so the 12 analytics KPIs re-fetch too). Listing tabs get it through `ListingScaffold` (see the Listing
pattern row). The More *menu* (static) intentionally has no pull-to-refresh.

### 3.5 Per-shop settings screen — S ✅
Manage shops row currently only switches language. Consolidate: rename, tint, daily target,
low-stock threshold (already in `AppData.lowStockThreshold`, global — should be per shop),
content language, remove.
Shipped: `shop-settings/{shopId}` route from the Manage shops rows (`ShopSettingsScreen`);
the low-stock threshold moved to `ConnectedShop.lowStockThreshold` (per shop, default 5;
changing it re-fetches the snapshot), language picker and shop removal moved off the
Manage screen into settings.

### 3.6 Sales-channel filter — M ✅
A connected shop is currently a whole instance. Optional per-shop sales-channel scope:
filter order queries by `salesChannelId`, pick channel in the wizard (the channel list is
already fetched for name prefill). Useful for instances hosting multiple brands.
Shipped as a **listing filter instead of per-shop scoping** (deliberate descope — a filter in
the listings covers the multi-brand need without bifurcating the dashboard/wizard): shared
`salesChannelFilter()` factory in `ui/listing/ListingFilter.kt`, wired into Orders (replacing
its inline copy), Customers (registration channel), Reviews (`salesChannelId` FK each), and
Promotions (m:n path `salesChannels.salesChannelId`). Home/snapshot stays instance-wide.

### 3.7 Media manager — M ✅
Browse media folders/files, create folders, upload from the photo picker, capture with the
camera, file detail sheet with delete. `MediaScreen` (`ListingViewModel` for the paged file
grid + a folder stack), `MediaApi.uploadImage`/`createFolder`, Coil 3 for thumbnails
(pinned 3.3.0 — 3.4+ ships Kotlin-2.4 metadata, too new for the project's 2.2.10).
**Navigation change**: the Promos bottom-tab was replaced by a **More** tab
(`MoreTab.kt`) — an ACL-gated menu listing Promotions, Media and Reviews; those open as
`promos/{shopId}` / `media/{shopId}` / `reviews/{shopId}` routes. `media.url` is rebased
onto the connected base URL (`rebaseMediaUrl`) so thumbnails load when APP_URL ≠ the
connection host (e.g. localhost vs 10.0.2.2).

### 3.8 Home-screen widget — M ✅
A Glance home-screen widget mirroring the Home hero card for the **selected** shop: "Sales
today" label, today's revenue, +/-% delta, daily-target progress bar (when set), shop name;
tap opens the app. `widget/WidgetData` reads the cache directly (app-data.json is plain JSON,
per-shop snapshot files) with the pure formatters — no DataStore/suspend/Compose, unit-tested
via a `filesDir` overload. `SalesWidget`/`SalesWidgetReceiver` (Glance 1.1.1, forest palette);
repainted via `refreshSalesWidgets()` from `AppRepository.refresh`/`selectShop`. Cache-only —
never fetches. Receiver must be `exported=true` (the launcher host binds cross-process).
A second **weekly** widget (`WeeklySalesWidget`, 3×2) shows the week total + a 7-day bar chart
(weighted Glance `Box` bars, today highlighted) + momentum delta; `WidgetData.readWeeklySnapshot`.
Each widget has its own `previewImage` PNG (`widget_preview_today`/`_week`) — a single shared
preview drawable rendered as a black blob in the picker.

### 3.9 Products list — M ✅
Browse the catalog from a **More** entry (`products/{shopId}`). `ProductsScreen` reuses the listing
pattern: cover thumbnail (Coil, `rebaseMediaUrl`), name, productNumber · manufacturer, gross price,
color-coded stock badge; Active/Inactive quick chips. `productListCriteria()` is **parents only
(`parentId = null`, variants hidden)**, sorted by name, with `cover.media` + `manufacturer`
associations. Filters (mobile-relevant subset of the admin's `sw-product-list`): active, stock
range, price range (range filter works directly on the `price` field), manufacturer (options from
`product-manufacturer`), sales channel (`visibilities.salesChannelId`), has-images existence,
product number, release date. ACL-gated on `canRead("product")`.

### 3.10 Product detail page — M/L ✅
Tapping a product row opens `ProductDetailScreen` (`ui/screens/ProductDetailScreen.kt`) **inside the
More tab** (a `productDetailId` state alongside `moreDest`, so the nav rail stays — same pattern as the
other More sub-screens; system back unwinds detail → list → menu). Read sections: cover + gallery
thumbnail strip (Coil, `rebaseMediaUrl`), title/number/manufacturer/rating, a Pricing card
(gross/net/tax-rate, "not editable" note for advanced/rule prices), stock badge + availableStock,
description, a Placement card (categories · sales channels from `visibilities.salesChannel`), and a
Variants card for configurable products (option labels like "Blue · M", number, price, stock).
`fetchProductDetail` pulls manufacturer/tax/cover.media/media.media/categories/visibilities/prices;
`fetchProductVariants` pulls children with `options.group` (+ tax, falling back to the parent's tax
rate). Editing (`ui/screens/ProductEditSheets.kt`): a base-data sheet (name, active, stock, price) →
`PATCH /product/{id}`, and a per-variant sheet (stock, price) → `PATCH /product/{variantId}`.
**Description is intentionally not editable** (it is rich HTML). The price editor mirrors the admin's
`sw-price-field`: side-by-side **gross + net fields with a link toggle** — when linked, editing one
recomputes the other from the tax rate client-side (`net = gross / (1 + rate/100)`); the save writes
explicit `{gross, net, linked}` onto the default-currency entry (other currency entries untouched).
Shared `PriceEditState`/`PriceEditor` used by both sheets. Verified live on 6.7.8: variant stock
50→52, and a linked edit gross 23.80 → net auto-computed 20.00 → persisted as
`{gross:23.8, net:20.0, linked:true}`. Still open vs the admin: editing tax/manufacturer/categories,
per-channel visibility toggles, media reorder/cover.

### 3.11 Analytics suite (Reports tab) — M/L ✅
The thin Reports tab (week chart + top products) is now a full analytics surface modeled on the
official **SwagAnalytics** app's KPI registry. Ships the **12 Admin-API-achievable KPIs** (the 4
storefront-tracking ones — visits/unique-visitors/conversion/search-terms — need the
SwagAnalyticsGateway, out of scope). Architecture:
- `data/model/Analytics.kt`: `TimeSeriesKpi`/`BreakdownKpi`/`SingleKpi` (3 card shapes), `KpiType` (12),
  `KpiState` (per-card loading/error), `AnalyticsFilters` + `DateRange` (presets + auto interval).
- `data/source/AnalyticsQueries.kt`: 12 `ShopApi` extension queries (`terms`/`histogram`/`sum`/`count`
  aggregations over `order`/`order_line_item`/`customer`), a shared `orderFilters()` builder (the one
  point the filter bar drives every order card — mirrors `kpi/common/order.criteria.ts`), the
  currency-factor normalization reused from the dashboard (nest under `terms(currencyFactor)`, divide
  by the key), line-item queries joined to the parent via an `order.`-prefixed filter, and a two-step
  `resolveNames` (ids→`translated("name")`) for payment/shipping/country/product/manufacturer; plus
  `fetchAnalyticsFilterOptions` (sales channels, customer groups, countries, the 3 state-machine state
  sets). `previous()` shifts the window back one period for the delta badge.
- `AppRepository.loadKpi(shop, type, filters)` dispatches to the right query → `KpiState`.
- `ReportsViewModel`: filter state, per-KPI `KpiState` map, **parallel independent reload** (one launch
  per KPI so a slow/failing card never blocks others), 300 ms debounce on filter change, a generation
  guard dropping stale results, shop/language re-init.
- UI: `reports/AnalyticsCards.kt` (`TrendCard` bar-chart+delta, `BreakdownCard` top-N `RankBar`s,
  `SingleNumberCard`) each switching on `KpiState`; `reports/AnalyticsFilterBar.kt` (date preset chips +
  a `Filters (n)` sheet of multi-selects); `ReportsScreen` rewritten into filter bar + KPI grid (tablet
  two-column via `Pair2`/`isExpandedWidth`, cap widened to 1100 dp). Cross-shop comparison now
  tracks the selected period (June 2026 fix): it used `snapshots[id].weekRevenue` (the fixed Home
  7-day window), so it showed the same value for every date range. Now `ReportsViewModel` fetches a
  per-shop normalized revenue total for the active `AnalyticsFilters` via `analyticsRevenueTotal`
  (lean `terms(currencyFactor)→sum(amountTotal)`, no histogram) into a `shopRevenues` map that
  reloads with the KPIs; `CrossShopComparison` ranks off that (loading shops show "—").
  en+de strings (`reports_kpi_*`, filter labels, presets). Verified live on 6.7.8: Total sales
  €53,611 (+163%) with trend, Orders 49, AOV €1,105, new/total customers, and breakdowns for sales
  channel / best-selling / payment / shipping / country / manufacturer / promo codes — all with resolved
  names and currency normalization. (One edge: a shipping method with no name shows "—".)

---

## 3b. Next-up backlog — admin-source research (June 2026)

Researched the 6.7 admin (`src/Administration/.../src/module/`: sw-order, sw-customer, sw-product,
sw-promotion-v2, sw-settings-payment/-shipping, sw-flow, sw-dashboard, sw-review, sw-newsletter-recipient)
to find capabilities the app lacks. Routes spot-verified live on 6.7.8 (a `400`/`500` on an empty body
means the route resolves; `404` would mean it doesn't): `/_action/order/{id}/recalculate` → 400,
`/_action/order/{id}/lineItem` → 500, `/_action/mail-template/send` → 400,
`/_action/customer-group-registration/accept` → 400; payment/shipping methods carry an `active` toggle
(Direct Debit ships inactive — a ready demo). Effort: S ≈ 1 screen/sheet, M ≈ screen + write flow + recalc/verify.

Ordered by value ÷ effort:

### Tier 1 — high value, small/medium effort (do first)

1. **Edit customer contact data + addresses — M. ✅ Done June 2026** (see §3.2). `CustomerEditSheet`
   patches customer-level fields + default billing/shipping addresses, with salutation/country pickers;
   verified live. No write-ACL probe yet → server `missingPrivileges` surfaces in-sheet.
2. **Payment & shipping method active toggle — S.** A More-tab "Checkout methods" screen (or a section in
   shop settings) listing payment + shipping methods with an active switch (`PATCH /payment_method|shipping_method
   {active}`). One-tap "disable a failing PSP / pause a carrier" — exactly the kind of emergency action a
   phone is for. Read names from search, toggle in place (optimistic). New probe entities
   `payment_method`/`shipping_method`.
3. **Order timeline / state history — S. ✅ Done June 2026** (see §2.4). An "Activity" card at the
   bottom of the order detail reads `state_machine_history` across the order + payment + delivery and
   shows each transition (target state, relative time, user) newest-first.
4. **Send existing document by email — M.** We generate + open/share invoices already; add "Email to customer"
   using `mailService.getDataAndSendMailTemplate` / `/_action/mail-template/send` with the document attached.
   Saves the share-to-mail-app dance. Needs a template pick + the send-mail toggle UI we already built for
   transitions — reuse it.

### Tier 2 — high value, larger effort

5. **Order line-item editing + recalculate — L.** The biggest functional gap vs the admin: edit qty/price on a
   line (`PATCH /order_line_item/{id}`), add an existing product (`POST /_action/order/{id}/product/{productId}`),
   add a custom line or **credit** (`POST /_action/order/{id}/lineItem` / `/creditItem`), then
   `POST /_action/order/{id}/recalculate`. Versioned-entity rules apply (we already PATCH live versions for
   transitions). High value (real fulfilment/refund work) but multi-step + careful recalc/verify — schedule as
   its own milestone, not a quick win.
6. **Product detail edit (beyond quick-actions) — M/L. ✅ Done June 2026** (see §3.10). A tappable
   `ProductDetailScreen` (cover/gallery, pricing/net/tax, stock, description, categories, sales channels,
   rating, variants) with a base-data edit sheet (name/description/active/stock/price) and per-variant
   stock/price edit sheets. Verified live (variant stock 50→52 persisted). Still open from the admin's full
   surface: tax/manufacturer/category *editing*, per-channel visibility toggles, media reorder/cover.

### Tier 3 — niche / opportunistic

7. **Promotion code generation — S.** Extend promo write-support: fixed code
   (`GET /_action/promotion/codes/generate-fixed`) and individual codes
   (`POST /_action/promotion/codes/add-individual`). Cheap add-on to the promo screen.
8. **Flow active toggle — S.** List flows with an active switch (`PATCH /flow {active}`) — emergency "pause this
   automation" without the desktop flow builder (which stays out of scope). Read-only flow logs optional.
9. **Customer group-change requests — S.** Accept/decline B2B group requests
   (`/_action/customer-group-registration/accept|decline`); only relevant to B2B shops — gate on having
   `requestedGroup` customers.
10. **Tag CRUD — S.** Create/edit/delete tags (`tag` entity) and assign to orders/customers/products — small
    forms, helps organization, but lower urgency.

**Deliberately left for desktop (confirmed out of scope):** flow builder authoring, promotion discount
rules/conditions, product-stream filters, CMS/SEO/layout, dimensions/packaging, number-range setup,
bulk-edit multi-select. Newsletter-recipient management is low value (seed shows 0; export endpoint doesn't
exist server-side).

### Round 2 research (June 2026) — verified on 6.7.8

Three more admin-source surveys + live verification. Net new, ranked:

11. **Sales-channel maintenance mode + checkout-method toggles — M.** A "Storefront controls" screen:
    `PATCH /sales-channel/{id} {maintenance}` (take the shop offline from a phone — the most mobile-native
    emergency action; **verified** set→true→revert 204) plus payment/shipping method `active` toggles
    (backlog #2; disable a failing PSP / pause a carrier). Three verified emergency actions in one ops screen.
12. **Reports/analytics expansion — M/L. ✅ Done June 2026** (see §3.11). The full 12-KPI suite from
    SwagAnalytics' Admin-API-achievable set, shipped.
13. **Email an existing document to the customer — M** (backlog #4). `POST /_action/mail-template/send`
    (verified: route exists, needs recipients/salesChannelId/templateId/documentIds) with the document
    attached — completes the documents feature (we generate/open/share; email is the missing verb).

**Confirmed NOT available on 6.7.8 (don't attempt):** order returns / RMA (`order_return` → 404, it's a
later/commercial feature), abandoned carts (`cart` entity → 404, session-based not DAL), refunds are
modelled (`order_transaction_capture_refund` exists) but **not exercisable without a payment gateway that
creates captures** (`order_transaction_capture` table empty on the seed) — defer until a real PSP is in play.

---

## 4. Technical debt / correctness

| Item                   | Detail                                                                                                                                                                                                                                                     | Effort |
|------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|
| ~~Currency normalization~~ ✅ | Done June 2026. The `/_admin/dashboard/order-amount` endpoint already normalizes by `currency_factor` server-side (verified on 6.7.8 — the earlier caveat here was wrong). The DAL fallback and top-products now nest their sums under `terms(currencyFactor)` and divide client-side; per-order amounts render in the order's own currency (`RecentOrder.currencyIso`/`OrderDetail.currencyIso`). | —      |
| ~~Refresh tokens~~ ✅  | Done June 2026: password grant only signs in; a rotating `refresh_token` is persisted (encrypted), rotated on every grant (server revokes the old one), `ApiError.AuthExpired` → sign-in-again card in shop settings. Integration auth removed entirely.    | —      |
| ~~Snapshot growth~~ ✅ | Done June 2026: snapshots split out of `app-data.json` into per-shop cache files (`files/snapshots/{shopId}.json`, atomic writes, corrupt files dropped); `AppRepository` recombines them into the `AppData` flow so consumers are unchanged. The settings/auth file is no longer rewritten every sync. | —      |
| ~~ACL-aware UI~~ ✅    | Done June 2026: wizard probe results persist as `ConnectedShop.scopes`, re-probed on sign-in-again; tabs declare `AppTab.requiredScope` and hide without read access; `fetchSnapshot` skips unreadable sections instead of failing. Verified with a restricted (orders-only) acl_role user. | —      |
| ~~API module~~ ✅      | Done June 2026: `api/` extracted into the pure-JVM `:shopware-admin-api` module (sources + value types + its 35 unit tests; no Android deps). The app depends on it via `implementation(project(...))`; ktor deps live only in the module.                  | —      |
| Tests / query-layer testability ✅ | June 2026: the query/parsing layer is now unit-testable. `ShopApi` got a `withClient()` test seam (private primary constructor takes an injectable `ShopwareClient`; production path unchanged) so query extension fns can be driven by ktor's `MockEngine` with canned JSON — no server. 87 tests total (added `AnalyticsQueriesTest` covering the multi-currency normalization + name resolution + criteria shapes, and `ErrorsTest`). The tests already caught a redundant nested `count` aggregation (removed) and confirmed `/api/search` returns flat entities, not JSON:API. App test deps gained `ktor-client-mock` + `coroutines-test`. **Still uncovered (lower priority): ViewModels** — they do `(app as ShopwareApp).repository` in the ctor, so unit-testing them needs an `AppRepository` interface + factory injection (deferred — query-layer coverage is higher value). No Compose/screenshot tests (the live-emulator workflow covers UI in practice). | M (VMs) |
| Robustness ✅          | June 2026: `runCatchingCancellable` (re-throws `CancellationException`) applied to the query-layer + repo suspend wraps so structured-concurrency cancellation isn't swallowed (the hot UI paths already had explicit catches). `Throwable.userMessage(context)` centralizes ApiError→friendly-string mapping (network/forbidden/not-found/generic), with a pure `(Int)->String` overload that's unit-tested; replaces ad-hoc `getString` fallbacks in VMs. | —      |
| ~~CI~~ ✅              | Done June 2026: `.github/workflows/ci.yml` runs the standard gate (`:shopware-admin-api:test` + `:app:compileDebugKotlin` + unit tests + `assembleDebug`) on push/PR; test reports upload on failure. Nightly emulator run still optional/future.            | —      |
| material3 pin          | `1.5.0-alpha21` (BOM's 1.4.0 keeps `MaterialExpressiveTheme` internal). Re-checked June 2026: alpha21 is still the newest material3; BOM bumped to 2026.05.01 (builds fine with the pin). Drop the pin once expressive theming lands in a stable release.   | S      |
| Old snapshot migration | `AppData` decodes with `ignoreUnknownKeys` + defaults, so additive changes are safe. Breaking field changes need a version field + migration in `AppDataSerializer`.                                                                                       | S      |

---

## 4b. Release (Play Store beta)

**Status June 2026: release-ready.** Done in-repo: app renamed "Shopware Shop Manager"
(`applicationId` stays `de.shyim.shopware`, permanent on Play); `versionName 1.0.0-beta.1`;
R8 enabled for release (AGP 9 `optimization.enable` + `android.r8.gradual.support` flag,
APK ~4.3 MB); upload-key signing via gitignored `keystore.properties`
(key: `~/keystores/shopware-shop-manager-upload.jks` — **back it up; passwords only in
keystore.properties**); backup/device-transfer excludes the credential store and snapshots
(`backup_rules.xml`/`data_extraction_rules.xml`) and `plainAuth()` degrades decrypt failures
to sign-in-again instead of crashing; custom adaptive launcher icon (storefront on forest
green, monochrome variant; legacy webp mipmaps removed — minSdk 29 is adaptive-only);
`localeConfig` (en/de); store collateral in `store/` (listing texts en/de, PRIVACY.md,
data-safety cheat-sheet, 512px icon, 1024×500 feature graphics en/de).

**Manual steps left for the developer:**
1. Play Console: create the app (Business category), enroll in Play App Signing, upload
   `app/build/outputs/bundle/release/app-release.aab` to a closed/open testing track.
2. Host `store/PRIVACY.md` (e.g. GitHub Pages) and set the URL in the Console.
3. Fill the data-safety form per `store/data-safety.md`; prepare a demo shop login for
   app review.
4. ~~Screenshots~~ done (refreshed June 2026, now marketing-framed): `store/screenshots/` —
   phone 8× (1080×1920, 9:16: dashboard, analytics, orders, order detail, product, reviews,
   promotions, customers), 7" tablet 4× (1500×2400 portrait: dashboard, orders, order detail,
   reports — rail layout), 10" tablet 3× (2400×1500 landscape: two-column dashboard,
   list-detail orders, two-column analytics — proving the wider adaptive layouts). Each shot is
   a raw device capture wrapped in a branded green gradient + white headline + clean redrawn
   status bar by `tools/frame_screenshots.py` (Pillow; brand palette primary `#1E6B3C` /
   surface `#F5FBF2` / mint `#A6F2BF`, fonts from `/System/Library/Fonts/SFNS.ttf`). To
   regenerate: re-seed (`tools/seed_shopware.py`), capture screens off the emulator with `adb
   exec-out screencap`, then run `frame_screenshots.py <spec.json> <out-dir>` where the spec
   lists `{src,out,headline,sub,device,status_h?}` per shot (`device`:
   `phone`/`tablet-land`/`tablet-port`; `status_h` overrides the redrawn status-bar band height
   in capture px when an app header sits flush under the system bar, e.g. order detail). EN-only
   is the Play default for all locales (no localized screenshot dirs).

**In-app updates ✅ (June 2026):** Play in-app updates via `com.google.android.play:app-update(-ktx)`.
`update/InAppUpdateController` (a `DefaultLifecycleObserver` constructed in `MainActivity.onCreate` —
`registerForActivityResult` must run before STARTED) checks on every resume: **flexible** update by
default (background download, then a Compose `Snackbar` "An update has been downloaded · Restart" →
`completeUpdate()`), **immediate** (full-screen blocking) when Play `updatePriority() >= 4`. Resumes a
stalled immediate update (`DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS`) and surfaces a flexible update that
finished while backgrounded (`installStatus() == DOWNLOADED`). The snackbar is hosted in `AppRoot` via
a `SnackbarHostState` driven by the controller's Compose-observable `downloadReady`. Degrades to a
logged warning on non-Play installs (sideloaded APKs get `ERROR_INSTALL_NOT_ALLOWED`, never a crash).
Lint's `InvalidFragmentVersionForActivityResult` is a false positive here (we use a `ComponentActivity`,
not a Fragment) and is suppressed. The real update flow can only be exercised from a Play track, not a
sideloaded build.

---

## 5. Explicitly out of scope

Researched against the admin codebase (Administration `src/module/`, ~68 modules) and rejected:

- **sw-cms** (219 components) — drag-drop page builder, fundamentally desktop.
- **sw-flow** — visual workflow/sequence builder.
- **sw-bulk-edit / sw-import-export** — grid-selection workflows and field-mapping wizards.
- **Rule builder** in any form (promotion conditions, product streams, shipping rules).
- **sw-extension** (store/marketplace), **sw-users-permissions**, **sw-first-run-wizard**,
  settings modules — desktop admin jobs; the phone app manages *operations*, not configuration.
- **Order creation / POS** — creating orders via Admin API requires recreating cart logic
  client-side; if POS ever matters, it should go through the Store API as a separate effort.

---

## 6. API quick-reference (verified against 6.7.8)

```
# Auth
POST /api/oauth/token                      {grant_type: client_credentials|password, ...}

# Dashboard (timezone-aware day buckets)
GET  /api/_admin/dashboard/order-amount/{since}?timezone={tz}&paid={bool}
     → {statistic: [{date, count, amount}]}

# State machine (generic; entities: order, order_transaction, order_delivery)
GET  /api/_action/state-machine/{entity}/{id}/state          → {transitions: [{actionName, toStateName, url}]}
POST /api/_action/state-machine/{entity}/{id}/state/{action}

# Search (plain JSON via Accept: application/json)
POST /api/search/{entity}                  criteria: filter/sort/aggregations/associations/includes
     headers: sw-language-id: {uuid}, sw-inheritance: true

# Languages
POST /api/search/language                  associations: {locale: {}} → name + locale.code
     system language id: 2fbb5fe2e29a4d70aa5854ce7ce3e20b

# Reviews (2.1)
POST /api/search/product-review            PATCH /api/product-review/{id} {status}

# Products (2.2)
PATCH /api/product/{id}                    {stock} | {active} | {price: [...]}

# Documents (2.4)
POST /api/_action/order/document/{type}/create
GET  /api/_action/document/{id}/{deepLinkCode}

# Promotion codes (2.5)
POST /api/_action/promotion/codes/generate-individual?codePattern=…&amount=n
POST /api/_action/promotion/codes/add-individual

# Media upload (2.6)
POST /api/_action/media/{mediaId}/upload?extension=…&fileName=…
```

**Gotchas learned the hard way**
- OAuth error `detail` can be JSON `null` — never call `.content` on a `JsonPrimitive` without a `JsonNull` check.
- `customer.orderCount` / `orderTotalAmount` are **not** updated for API-imported orders (checkout-only indexer).
- Line-item writes require `payload.productNumber` alongside `productId`/`referencedId`.
- Translation fallback is fully server-side — always read `translated.{field} ?: {field}`, never chain languages client-side.
