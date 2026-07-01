# Shopware Shop Manager

A native Android companion app for [Shopware 6](https://www.shopware.com/) merchants.
Connect your shop with your admin login and run day-to-day operations from your phone —
orders, reviews, promotions and stock, no desktop needed.

Built with Kotlin, Jetpack Compose and Material 3 Expressive. It talks exclusively to the
Shopware **Admin API** of the shops you connect — there is no app backend and no
demo/sample data; every screen renders live API data.

## Features

- **Dashboard** — today's revenue against a daily target, order counts, a 7-day revenue
  chart, and a "needs attention" feed (pending reviews, overdue payments, low stock).
- **Orders** — browse, search and filter (status, payment, delivery, date, value, payment
  method, sales channel, documents). Open an order for line items, totals and addresses;
  change order/payment/delivery state, edit tracking codes, add internal notes, and
  generate or share invoice and delivery-note PDFs.
- **Customers** — searchable listing with per-customer detail and order history.
- **Reviews** — approve or reject product reviews from a mobile inbox.
- **Promotions** — active/scheduled/ended status, toggle promotions, generate codes.
- **Products** — fix stock, price and visibility from the low-stock list; shoot a new
  product photo with the camera.
- **Reports** — 7-day revenue, top products, cross-shop comparison.
- **Multi-shop & multi-language** — connect several shops, per-shop content language and
  locale-aware money formatting.
- **Order push** — instant order notifications via FCM (requires the external Shopware app
  server; see [Push notifications](#push-notifications)).

## Security & privacy

- Authentication uses the Shopware Admin API OAuth2 **password grant** for the initial
  sign-in only. After that the app persists only a **rotating refresh token**, encrypted
  with AES-GCM via the Android KeyStore — the password is never stored.
- There is no backend operated by this project; the app communicates directly with the
  shops you connect. See [`store/PRIVACY.md`](store/PRIVACY.md).

## Architecture

Two Gradle modules, three strictly-ordered layers (`api` → `data` → `ui`):

- **`:shopware-admin-api`** — a pure-JVM Shopware Admin API client (no Android/Compose
  deps), package `de.shyim.shopware.api`. Owns the DAL `Criteria` query builder, the
  OAuth session (`ShopwareClient`, with refresh-token rotation and refresh-on-401), and
  the per-shop `ShopApi` facade.
- **`:app`** — the Android app. `data/` holds the repository layer and per-shop state
  (typed-JSON persistence, no Room); `ui/` holds the Compose screens, including a reusable
  listing pattern (`ui/listing`) ported from the web admin's filter panel.

For a deeper tour of the architecture and conventions see [`CLAUDE.md`](CLAUDE.md); for the
roadmap and per-feature API notes see [`FUTURE.md`](FUTURE.md).

## Building

Requires a recent JDK (17+) and the Android SDK. The build uses AGP 9, Kotlin 2.2, and
compiles against SDK 37 (min SDK 29).

```bash
# Standard verification gate
./gradlew :shopware-admin-api:test :app:compileDebugKotlin :app:testDebugUnitTest

# Debug build / install to a running emulator or device
./gradlew :app:assembleDebug
./gradlew :app:installDebug

# Release AAB (R8-optimized; signed only when keystore.properties is present)
./gradlew :app:bundleRelease
```

Release signing reads from a gitignored `keystore.properties`; builds stay unsigned where
the file is absent (e.g. CI).

### Firebase

`app/google-services.json` is committed. The value is a public client configuration (not a
secret) and the `google-services` plugin is applied only when the file is present, so the
project also builds without your own Firebase project.

## Local development

Features are verified against a real Shopware instance, not just unit tests:

```bash
docker run -d --name sw-demo -p 8000:8000 \
  ghcr.io/friendsofshopware/shopware-demo-environment:6.7.8
python3 tools/seed_shopware.py   # seeds orders/deliveries/promos/low stock
```

Sign in with `admin` / `shopware`. The Android emulator reaches the instance at
`http://10.0.2.2:8000`.

## Push notifications

Instant order pushes are delivered via Firebase Cloud Messaging. This requires an
**external Shopware app server** (a separate project, not part of this repo) that
subscribes to `checkout.order.placed` and creates the `ce_fcn` entity the app writes its
FCM token into. Without it, the rest of the app works normally — only order push
notifications are unavailable.

## License

[MIT](LICENSE) © FriendsOfShopware
