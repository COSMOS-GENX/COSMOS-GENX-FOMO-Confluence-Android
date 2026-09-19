# Crypto Short Scanner Android v3.0

Android + server-side crypto short scanner for Binance USDⓈ-M perpetual altcoins, tuned for a 1–3 minute scalping workflow.

## v3 upgrades applied

- Keeps the large **SCAN NOW / SCAN AGAIN** button.
- Manual scan still runs locally against Binance market data.
- **SCAN NOW also triggers the remote backend** when a server is configured.
- Added a **24/7 server-side scanner** that can continue when the Android app is closed.
- Added **Firebase Cloud Messaging (FCM)** push alerts.
- Added a server configuration dialog inside the app for:
  - HTTPS backend URL
  - optional backend API key
- Added multi-source catalyst aggregation on the backend:
  - Binance public announcement/CMS feeds
  - CoinDesk RSS
  - Cointelegraph RSS
  - optional custom RSS/Atom feeds
  - optional CryptoPanic API integration
- Added negative-catalyst weighting for delisting, suspension, exploit/hack, investigation, insolvency, shutdown and related terms.
- Added push-alert deduplication and cooldown logic so the same symbol does not spam alerts every minute.
- Keeps Top 10 short ranking, 1m/5m/15m/24h momentum, volume acceleration, lower-high/lower-low scoring, anti-chase logic, live mark-price WebSocket, token detail view, and local alerts.
- Trade execution is still disabled.

## Project layout

- `app/` — Android application.
- `server/` — FastAPI background scanner + FCM push service.

## 1. Android build

Open the `CryptoShortScanner` folder in Android Studio and allow Gradle to sync. Build with **Build > Build APK(s)**.

Expected debug APK location:

`app/build/outputs/apk/debug/app-debug.apk`

## 2. Firebase setup for push alerts

Create a Firebase project and add an Android app with package name:

`com.cosmosgenx.cryptoshortscanner`

In Firebase Project Settings, copy the Android app values into:

`app/src/main/res/values/firebase.xml`

Fill:

- `firebase_app_id`
- `firebase_api_key`
- `firebase_project_id`
- `firebase_sender_id`

The project intentionally does **not** require `google-services.json`, so the source can build before Firebase is configured. Push token registration simply stays disabled until the four values are present.

Then create/download a Firebase Admin SDK service-account JSON for the **server only**. Never place that service-account JSON inside the APK.

## 3. Run the background server

From the `server` folder:

```bash
python -m venv .venv
# Windows: .venv\Scripts\activate
# Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
```

Edit `.env` and set at minimum:

```env
BACKEND_API_KEY=use-a-long-random-value
GOOGLE_APPLICATION_CREDENTIALS=/absolute/path/to/firebase-service-account.json
```

Run:

```bash
uvicorn main:app --host 0.0.0.0 --port 8080
```

Check:

`GET /health`

The scanner runs every 60 seconds by default. Android cannot connect to plain HTTP in this project, so deploy the backend behind **HTTPS** for phone use.

## 4. Configure the app

Open the app, tap **SERVER**, then enter:

- your deployed HTTPS backend URL, e.g. `https://scanner.example.com`
- the same `BACKEND_API_KEY` configured on the server

After saving, the app requests an FCM token and registers the device with the backend.

## 5. Background behavior

The server performs scans even when the Android app is not open. When an `ENTER WATCH` candidate reaches the configured score threshold, the server sends a high-priority FCM data message. The Android `FirebaseMessagingService` turns it into a notification.

Important Android limitation: if the user **force-stops** the app from Android Settings, Android may block FCM delivery until the app is manually opened again. OEM battery-management settings can also delay notifications on some phones.

## 6. Multi-source news configuration

The server uses Binance announcements plus public RSS feeds by default. Optional additions:

```env
CRYPTOPANIC_AUTH_TOKEN=your_token_here
NEWS_RSS_URLS=https://example.com/feed.xml,https://another.example/rss
```

All private API credentials stay server-side.

## 7. Server tuning

Available `.env` settings:

```env
SCAN_INTERVAL_SECONDS=60
ALERT_SCORE_THRESHOLD=80
ALERT_COOLDOWN_MINUTES=15
BACKEND_API_KEY=replace-with-a-long-random-value
```

The minimum practical scan interval is intentionally 60 seconds.

## 8. Docker deployment

The `server/Dockerfile` is included. Example:

```bash
docker build -t crypto-short-scanner .
docker run -p 8080:8080 \
  -e BACKEND_API_KEY='your-key' \
  -e GOOGLE_APPLICATION_CREDENTIALS=/run/secrets/firebase.json \
  -v /absolute/path/firebase.json:/run/secrets/firebase.json:ro \
  crypto-short-scanner
```

Put the container behind an HTTPS reverse proxy or deploy it to a managed HTTPS container platform.

## Security notes

- No Binance trading API key is required.
- Firebase Admin credentials stay on the server only.
- The backend supports `X-Scanner-Key` authentication for device registration and manual remote scans.
- The Android-side server key is user-entered rather than hard-coded, but it is still a client-side credential and should be treated as an access token, not a high-value master secret.
- Use HTTPS only.
- Trade execution remains disabled.

## Signal warning

The scanner is a ranking/alert system, not a guarantee of profitable trades. News matching is heuristic and can produce false positives. Confirm the actual announcement and live order-flow/price structure before entering a position.
