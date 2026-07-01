# Play Console "Data safety" form — cheat sheet

Answers for Shopware Shop Manager 1.0.0-beta.1. The guiding fact: the app is a pure
client for the user's own server; nothing is collected by the developer, nothing is
shared, nothing leaves the device except API calls to the user's own shop.

## Overview questions

- **Does your app collect or share any of the required user data types?** → **No.**
  Rationale: Google's definition of "collect" = data transmitted *off the device to
  the developer or third parties*. The app transmits data only to the server the
  user themself configures (their own shop), which Google's guidance treats as
  user-to-own-service communication, not developer collection. Locally stored data
  (encrypted session token, cache) is not "collected" either, and it is excluded
  from backup.

If reviewers push back and you prefer the conservative path, declare instead:

- Collected: **none**; Shared: **none**; but mention under "Data handled ephemerally":
  credentials sent directly to the user's own server over the connection the user
  configured.

## Supporting facts (for the review notes / appeals)

- No analytics/ads/crash-reporting SDKs (dependency list: AndroidX, Compose, Ktor,
  kotlinx-serialization only).
- Password → used once for OAuth password grant against the user's own host; only a
  rotating refresh token is stored, AES-GCM encrypted via Android Keystore.
- Backup/device-transfer of credentials and cache is disabled
  (`data_extraction_rules.xml`, `backup_rules.xml`).
- Cleartext HTTP is enabled deliberately: self-hosted Shopware instances on a LAN
  are a supported use case; the user chooses the URL.
- All data deletable in-app (remove shop) or via clear-storage.

## Other Play Console bits

- **App category:** Business
- **Target audience:** 18+ (business tool)
- **Ads:** none
- **Privacy policy URL:** host `store/PRIVACY.md` (e.g. GitHub Pages / repo README
  link) and paste the URL.
- **Login credentials for review:** Google may ask for a demo login since the app
  requires a shop. Options: spin up a public demo instance, or state that reviewers
  can use any Shopware 6 demo (e.g. a disposable cloud trial) — prepare one before
  submitting.
