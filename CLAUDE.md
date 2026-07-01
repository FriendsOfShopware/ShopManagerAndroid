# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Keep this file updated when architecture, commands, or conventions change — it is part of the
definition of done for structural changes. `FUTURE.md` tracks the roadmap and per-feature API
reference; this file covers how to work here.

## What this is

A native Android companion app for Shopware 6 shops ("Boutique Manager"). Kotlin, Jetpack Compose,
Material 3 Expressive, two modules: `:app` (Android) and `:shopware-admin-api` (pure-JVM API
client, package `de.shyim.shopware.api`). It talks exclusively to
the Shopware **Admin API** of user-connected shops (admin login / OAuth2 password grant; after the
initial sign-in only a rotating refresh token is kept — never the password).
There is no app backend and no demo/sample data — every screen renders live API data.

Order push notifications rely on an external Shopware *app server* (a separate project, not part
of this repo): shops install a Shopware app that subscribes to `checkout.order.placed` and creates
the `ce_fcn` entity the Android app writes its FCM token into, then fans out order pushes via FCM.
This repo contains only the Android side — the token upsert and FCM receiving.

The `ce_fcn` write is an **upsert via `/_action/sync`** (`EntityRepository.upsert`), not POST: the row
id is a stable per-install UUID (`AppData.pushInstallId`), so POST (insert-only) would 400 with
`FRAMEWORK__WRITE_TYPE_INTEND_ERROR` on every repeat. Registration is **shop-scoped and managed
per shop in Shop settings** (`PushNotificationCard` in `ShopSettingsScreen` — live status from a
`ce_fcn` lookup: Registered / Not registered / App-not-installed / Unavailable, plus
register/unregister and the Android-13 notification-permission prompt), not in the global
Manage-shops screen.

## Commands

```bash
./gradlew :shopware-admin-api:test :app:compileDebugKotlin :app:testDebugUnitTest   # the standard gate — run before claiming done
./gradlew :app:testDebugUnitTest --tests "de.shyim.shopware.api.CriteriaTest"   # single test class
./gradlew :app:assembleDebug
./gradlew :app:installDebug                                 # to a running emulator/device
./gradlew :app:bundleRelease                                # Play AAB; R8 + upload-key signing

```

There is no lint/format gradle task configured. Commits in this repo may be unsigned
(`git commit --no-gpg-sign`) when the user's signing agent is unavailable.

Release builds are R8-optimized (needs `android.r8.gradual.support=true` in
gradle.properties, AGP 9 DSL) and signed with the upload key from the gitignored
`keystore.properties` (keystore at `~/keystores/shopware-shop-manager-upload.jks`; builds
stay unsigned where the file is absent, e.g. CI). After touching release config or
upgrading R8-relevant libs, re-run the release smoke: install `assembleRelease` on the
emulator and walk connect wizard → Home → orders → document share. Play collateral lives
in `store/` (see FUTURE.md §4b for the release checklist).

## Dev environment (live verification is the norm)

Features here are verified against a **real Shopware instance**, not just unit tests:

```bash
docker run -d --name sw-demo -p 8000:8000 ghcr.io/friendsofshopware/shopware-demo-environment:6.7.8
python3 tools/seed_shopware.py    # seeds orders/deliveries/promos/low stock; connect the app with admin/shopware
```

- Admin login `admin`/`shopware`; the emulator reaches it at `http://10.0.2.2:8000`.
- Get a token and curl-verify API shapes **before** coding against them; the codebase grew this way.
- Emulator: `emulator -avd Medium_Phone_API_36.1 -no-snapshot -memory 4096` (tablet: `Pixel_Tablet`).
  `-memory 4096` is mandatory — the 2 GB default thrashes into System UI ANRs. `-no-snapshot` avoids
  a quickboot snapshot that rolls back userdata. `adb shell input text` drops characters in long
  strings — type credentials in ~5–10 char chunks with sleeps.

## Architecture

Three layers, strictly ordered: `api` → `data` → `ui`.

**`:shopware-admin-api` — the Admin API client** (a pure-JVM module, no Android/Compose deps;
a Kotlin port of the web admin's patterns; sources in `shopware-admin-api/src/main/kotlin`,
the module also owns the value types it produces — `LanguageOption`, `StateTransition` —
and all api-layer unit tests; version-less Kotlin plugins in its build file, versions come
from the root/AGP classpath):
- `Criteria` mirrors the admin DAL query language (filters/sort/associations with dot-path
  expansion/includes/aggregations/`total-count-mode`). Golden-JSON tested — criteria shapes are a
  server contract, not an implementation detail.
- `SwEntity`/`SearchResult` wrap response JSON with JsonNull-safe accessors; always read translated
  fields as `translated("field")` (server resolves the language fallback chain; 6.7 serializes an
  empty `translated` as `[]`, which these handle).
- `ShopwareClient` owns the OAuth session: mutex-guarded grants, refresh-token rotation (the
  server revokes the old token on every refresh — the rotated token is awaited into persistence
  via the `onRefreshToken` callback before first use), refresh-on-401, and `ApiError.AuthExpired`
  when the stored refresh token is rejected (UI maps this to "sign in again" in shop settings).
  It turns every non-2xx into the sealed
  `ApiError` (parses the Shopware error envelope incl. `missingPrivileges` on 403).
- `ShopApi` is the per-shop facade: `repository(entityName)` (generic search/get/create/patch/delete,
  kebab-case URLs) plus action services (`StateMachineApi`, `DashboardStatsApi`, `DocumentApi`,
  `MediaApi`, `PromotionApi`, `InstanceApi`) for `/_action`/`/_admin` endpoints.
- Deliberately NOT ported from the web admin: entity schema awareness, changeset/draft tracking,
  hydration codegen. Writes are small explicit PATCH payloads.

**`data/` — repository and per-shop state:**
- `AppRepository` (manual DI via `ShopwareApp.repository`) caches one `ShopApi` per shop
  (`apiFor`, evicted on language change) and exposes screen-level suspend functions.
- Persistence is typed JSON, deliberately no Room/KSP. `app-data.json` (DataStore via
  `AppDataSerializer` in `data/store/AppStore.kt`) holds shops/auth/settings and is only
  written on those changes; snapshots live in per-shop cache files
  (`files/snapshots/{shopId}.json`, `data/store/SnapshotStore.kt` — atomic tmp+rename,
  corrupt files dropped) and `AppRepository` combines both into the `AppData` flow, so
  consumers still read `AppData.snapshots`. Additive model changes are safe
  (`ignoreUnknownKeys` + defaults); breaking ones need migration (patterns: the
  legacy-demo-shop purge in the serializer, the snapshot split-out in `AppRepository.init`).
- Auth persistence is `ShopAuth.Admin(username, encRefreshToken)` — the rotating refresh token
  AES-GCM encrypted via AndroidKeyStore (`data/store/Crypto.kt`). `password`/`integration` are
  decode-only legacy variants: password-auth shops migrate themselves on the next grant,
  integration shops require sign-in-again (support was removed).
- `ShopwareDataSource.fetchSnapshot` composes the offline-first Home `ShopSnapshot` (parallel
  queries; revenue via `/_admin/dashboard/order-amount` with a DAL-histogram fallback). Rule:
  the snapshot is small overview data for Home only — listings and detail screens query live
  through repositories, never through the snapshot.
- `data/sync/SyncWorker`: 15-min WorkManager periodic sync of all shops, diffs old vs new snapshot
  into notifications (orders + reviews always; low stock + unpaid opt-in via `AppData` flags).

**`ui/` — Compose screens:**
- `ui/listing/` is the reusable listing pattern (ported from the admin's sw-filter-panel):
  `ListingFilter` (Text/NumberRange/DateRange/Options-with-async-loader/Existence/Bool → exact
  admin criteria JSON), `ListingState<T>` (pager with term, filter values, optimistic
  mutate/remove, stale-fetch generation guard), `ListingScaffold` (search, quick chips, filter
  sheet, header slot, LazyColumn that OWNS scrolling — never nest it in a verticalScroll), and
  `ListingViewModel` base (re-inits on shop/language change). **New listing screens: subclass
  `ListingViewModel`, declare filters, reuse the scaffold** — see `OrdersScreen` as the template.
- Navigation: nav-compose routes `onboarding`/`connect`/`main`/`manage`/`shop-settings/...`/
  `order/...`/`customer/...`/`reviews/...`/`promos/...`/`media/...`. Bottom tabs are Home/Orders/
  Customers/Reports/**More**; the More tab (`MoreTab.kt`) is an ACL-gated menu that routes to
  Promotions, Media and Reviews (Promos is no longer a bottom tab). Tabs live inside `MainScreen`
  as plain state. `NavigationSuiteScaffold` switches bottom bar ↔ rail; `ui/Adaptive.kt
  isExpandedWidth()` (≥840 dp) gates the Orders list-detail panes and the two-column Home.
- Remote images: Coil 3 (`coil-compose` + `coil-network-okhttp`), pinned `3.3.0` — 3.4+ ship
  Kotlin-2.4 metadata which the project's Kotlin 2.2.10 can't read. `media.url` carries the shop's
  APP_URL host; rebase it onto the connected base URL with `rebaseMediaUrl` before loading.
- Snapshot-backed tabs (Home, Reports) render inside `ScrollingTab`; listing tabs render
  fillMaxSize because the scaffold scrolls.
- Per-shop content language: `ApiContext` sends `sw-language-id` + `sw-inheritance`; money formats
  via `shop.fmt(amount)` using the shop's `locale.code` — never hardcode locale or currency.

## Conventions and hard-won facts

- material3 is pinned to `1.5.0-alpha21` (the BOM's stable keeps `MaterialExpressiveTheme`
  internal); this forces compileSdk 37 and the navigation-suite artifact must match the pin.
  Re-check on BOM updates before touching these versions (last check June 2026: alpha21 IS the
  newest material3 on Google Maven; BOM 2026.05.01 still maps stable 1.4.0 and builds fine with
  the pin).
- `primaryOrderTransaction`/`primaryOrderDelivery` are unpopulated for API-imported orders —
  order filters use `transactions.*`/`deliveries.*` paths (NOTE in `OrdersScreen`).
- Customer `orderCount`/`orderTotalAmount` are only maintained for checkout-placed orders, not
  API-imported ones — don't treat disagreement with a filtered order count as a bug.
- Money: `order.amountTotal` (and line-item prices) are in the **order's** currency.
  `/_admin/dashboard/order-amount` normalizes by `currency_factor` server-side; DAL aggregations
  don't — nest sums under `terms("byFactor", "currencyFactor")` and divide by the bucket key
  client-side (see `fetchDailyStats` fallback / top products). Per-order display uses the order's
  own currency (`currencyIso` on `RecentOrder`/`OrderDetail`); only dashboard sums are in the
  shop default currency.
- OAuth bad-credentials on the token endpoint is HTTP 400 with the standard errors envelope
  (parses as `ApiError.Validation`, not `Auth`).
- Versioned entities (order, order_delivery) accept direct PATCH on the live version — no
  createVersion/merge dance is needed for the writes this app does.
- The connect wizard probes entity ACLs (`InstanceApi.probe`) and shows a checklist; the results
  persist as `ConnectedShop.scopes` (re-probed on sign-in-again) and gate the UI: tabs declare
  `AppTab.requiredScope`, `fetchSnapshot` skips sections the login cannot read (empty scopes =
  legacy = all-granted). When adding a feature that needs a new privilege, extend
  `AppRepository.PROBE_ENTITIES`.
- UI strings live in `app/src/main/res/values/strings.xml` (snake_case keys with area prefixes:
  common_/nav_/home_/orders_/order_detail_/…; counts use `<plurals>`). Composables use
  `stringResource`/`pluralStringResource`; AndroidViewModels use `getApplication<Application>()
  .getString(...)`; SyncWorker uses `applicationContext.getString(...)`. Server messages pass
  through untranslated — only the `?:` fallbacks are resources. Content from the API is already
  localized server-side. German lives in `values-de/strings.xml` (Sie-form, Shopware admin
  terminology); every new string needs both files.
- Never persist or store formatted display strings in models — keep epoch millis (or raw data)
  and format in the UI so values re-localize on language change. Relative times and week-chart
  day labels go through `ui/RelativeTime.kt` (`relativeAgoText`, `weekDayLabels`); `placedMs <= 0`
  means "unknown" for legacy persisted snapshots and renders as "just now".
