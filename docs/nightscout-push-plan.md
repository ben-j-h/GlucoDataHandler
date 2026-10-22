# Central Nightscout + push delivery plan

Status: draft, October 2026. This describes how the family setup moves from "every phone polls Dexcom"
to "one server polls Dexcom and pushes to the phones". It is written so the pieces can be built
independently: **Part A** is infrastructure, **Part B** is the push relay (built in its own repo),
**Part C** is the GlucoDataHandler (GDH) app side in this repo. Section 5 is the contract between B and C.

## 1. Why

- Today each parent's phone runs GDH with the Dexcom Share source, logs in with the child's Dexcom
  account and polls Dexcom on its own. Two phones, two pollers, the Dexcom password stored on both.
- Tandem pump data (site changes, later IOB) can only reach us through Nightscout (tconnectsync or
  ControlX2), so a Nightscout server is needed anyway.
- Polling is wasteful and slow-ish: GDH asks when a reading is due and then once a minute until it
  arrives (1–3 requests per reading). A push arrives within seconds and needs no inbound access.

## 2. Target architecture

```
 Dexcom Share ──(poll, server side)──┐
 Tandem Source ──(tconnectsync)──────┤
                                     ▼
                     ┌────────── Proxmox ───────────┐
                     │ Nightscout (+ MongoDB)       │
                     │   ▲ local API                │
                     │   │                          │
                     │ push relay (Part B) ─────────┼──► Firebase Cloud Messaging ──► phones (GDH)
                     │ Home Assistant (REST sensors)│                                    │ Bluetooth
                     └──────────────┬───────────────┘                                    ▼
                                    │ Cloudflare tunnel (dashboard, HA, fallback polling) watches
```

- The watch never polls: the phone already forwards every value to the watch over Bluetooth.
- The relay only makes **outbound** connections (to FCM). The tunnel is only needed for viewing the
  dashboard / Home Assistant remotely and for the app's fallback polling of Nightscout.

### Fallback chain on the phone (most preferred first)

1. Push from the relay (Part B/C, future).
2. GDH polls Nightscout through the tunnel (works today with the existing Nightscout source).
3. GDH polls Dexcom Share directly, only once Nightscout has not reported a value for 10 minutes
   (implemented in this branch: Dexcom Share setting **"Only as fallback for Nightscout"**).
4. Independent of all of the above: the official **Dexcom Follow** app with its own alarms stays
   installed on at least one phone. Everything in this document has single points of failure
   (home internet, Proxmox host, Cloudflare, Firebase).

## 3. Part A – Infrastructure (no code)

1. **Nightscout** on Proxmox (Docker or LXC) with MongoDB. Recommended settings:
   - `AUTH_DEFAULT_ROLES=denied` – nothing is readable without a token.
   - Long random `API_SECRET` (admin only, never on the phones).
   - `ENABLE` must include `iob cob cage bridge` (or the newer `connect` plugin for Dexcom, whichever
     the current Nightscout release recommends) plus `careportal` for manual site changes.
   - Dexcom: Share credentials go into the Nightscout bridge/connect settings only.
2. **Tokens**: in Nightscout *Admin tools* create one subject per consumer with role `readable`
   (`dad-phone`, `mom-phone`, `home-assistant`, `push-relay`). Revoke individually if a phone is lost.
3. **tconnectsync** (https://github.com/jwoglom/tconnectsync, v3.x) as a container next to Nightscout,
   default features. It uploads cartridge/cannula/tubing fills as `Site Change` treatments.
   IOB from it is bolus-only and ~1 h delayed (Tandem uploads hourly) – treat as informational.
4. **Cloudflare tunnel** to Nightscout (and HA if wanted). Do not put Cloudflare Access in front of the
   API paths unless service tokens are added to the app; Nightscout's token auth is the gate. Add a
   Cloudflare rate-limit rule on the hostname.
5. **GDH on both phones**:
   - Enable source *Nightscout* with the phone's read token, *IOB/COB* on.
   - Keep *Dexcom Share* enabled with **Only as fallback for Nightscout** switched on.
   - Disable all local broadcast sources that are not used (xDrip, Juggluco, AAPS, BYODA, Eversense,
     Diabox, patched Libre) – they accept data from any app on the phone.
6. **Home Assistant**: built-in Nightscout integration for the glucose value; REST sensors on
   `GET /api/v2/properties/iob,cage?token=…` for IOB and site age (verify field names on the server).

## 4. Part B – Push relay (standalone service, separate repo)

A small service on Proxmox. It does **not** need to be a fork of Nightscout: it only reads Nightscout's
API on the LAN, so Nightscout can be upgraded independently.

### Responsibilities

1. Detect new glucose values in Nightscout:
   - Simplest and robust: poll `http://<nightscout-lan>/api/v1/entries/sgv.json?count=2` every 15–20 s
     with the `push-relay` token (LAN only, negligible cost).
   - Optional optimisation: Nightscout's socket.io `dataUpdate` events, keeping the poll as fallback.
2. Enrich with IOB (`/api/v2/properties/iob`) and the latest site change
   (`/api/v1/treatments.json?find[eventType]=Site%20Change&count=10`, same selection rule as the app:
   prefer the newest *Cannula Filled* entry if the newest entry is only a cartridge/tubing fill).
3. Decide the priority (below), encrypt the payload, send it to the FCM topic via the
   **FCM HTTP v1 API** using a Firebase service account (key stays on the relay only).
4. Expose a local health endpoint (last Nightscout value time, last FCM send result) for HA/monitoring.

### Priority policy

The goal: in-range readings may arrive late, anything that matters must wake the phone now.

Send with `android.priority = HIGH` when **any** of these is true, otherwise `NORMAL`:

| Rule | Default |
|---|---|
| Value at or below the low threshold | `LOW_MGDL = 90` |
| Value at or above the high threshold | `HIGH_MGDL = 250` |
| Predicted low: `sgv + 3 × delta_per_5min` ≤ low threshold (≈15 min ahead) | on |
| Fast change: \|delta\| ≥ threshold per 5 min | `FAST_DELTA_MGDL = 15` |
| Previous pushed value was HIGH priority (so the "back in range" message is prompt) | on |
| First value after a gap (no value for ≥ 15 min) | on |

- Thresholds must be **wider** than the app's alarm thresholds (e.g. app low alarm 70 → relay 90), so
  every value that can trigger an alarm in the app is sent HIGH.
- Why not always HIGH: Android/FCM may deprioritise an app's high-priority messages when they do not
  lead to a user-visible notification. Out-of-range values are exactly the ones where the app shows an
  alarm/notification, so they keep their high-priority budget. This must be verified on the real phones
  (section 7).
- All messages: `android.ttl = "600s"` (a reading older than 10 minutes is useless – the app's fallback
  polling takes over) and `android.collapse_key = "gdh-glucose"` (only the newest NORMAL message is
  kept while the phone sleeps).

### Configuration (environment variables)

`NIGHTSCOUT_URL`, `NIGHTSCOUT_TOKEN`, `FIREBASE_SERVICE_ACCOUNT_JSON` (path),
`FCM_TOPIC` (random, e.g. `gdh-` + 32 hex chars), `PUSH_KEY_ID` + `PUSH_KEY` (base64, 32 bytes),
`LOW_MGDL`, `HIGH_MGDL`, `FAST_DELTA_MGDL`, `POLL_SECONDS`.

## 5. Contract between relay (B) and app (C)

### FCM message (HTTP v1)

```json
{
  "message": {
    "topic": "gdh-<random>",
    "data": {
      "v": "1",
      "kid": "<PUSH_KEY_ID>",
      "payload": "<base64( nonce(12 bytes) || ciphertext || tag(16 bytes) )>"
    },
    "android": { "priority": "HIGH", "ttl": "600s", "collapse_key": "gdh-glucose" }
  }
}
```

- Only `data` messages (no `notification` block) – the app decides what to show.
- FCM data values are strings; the whole message must stay well below FCM's 4 KB limit.

### Encryption

- AES-256-GCM, 12-byte random nonce per message, 128-bit tag.
- Key: the 32-byte `PUSH_KEY`, entered in the app once (QR code or paste). `kid` allows key rotation.
- Additional authenticated data: the ASCII string `gdh-push-v1`.
- Rationale: the topic name and the Firebase config inside the app are not secrets, so anyone could in
  principle subscribe; encryption makes the content useless to them and to Google, and authenticates
  that the message came from the relay.

### Decrypted payload (JSON)

```json
{
  "seq": 1042,
  "sent": 1761140000000,
  "priority": "high",
  "reasons": ["low", "predicted_low"],
  "sgv": { "mgdl": 82, "date": 1761139990000, "direction": "SingleDown", "delta": -9.0, "device": "dexcom" },
  "iob": { "value": 1.35, "date": 1761139800000 },
  "site_change": 1760960000000
}
```

- `date`/`sent`/`site_change` are epoch milliseconds (UTC). `delta` is mg/dL per 5 minutes.
- `iob` and `site_change` are optional (omit when unknown).
- `seq` increases by one per message, so the app can detect gaps and replays (ignore `seq` ≤ last seen,
  except after the relay restarts – the relay should persist `seq`).
- `direction` uses Nightscout's names (`DoubleUp` … `DoubleDown`, `NOT COMPUTABLE`).

## 6. Part C – GDH app work

Done in branch `claude/watch-battery-tandem-pump-bmv8mk`:

- Watch battery fixes (no per-minute wake-ups from tiles; only time complications get minute updates).
- TLS certificates are verified for public Nightscout servers; credentials are masked in logs.
- Dexcom Share **fallback-only** mode (10 minutes without a Nightscout value).
- Pump **site age** from Nightscout `Site Change` treatments (checked every 15 min), passed to the
  watch with each value, shown on phone and watch main screens, the details tile, and a new
  *Pump site age* complication.

To do once the relay exists:

1. Firebase SDK + `FirebaseMessagingService` in the mobile app; subscribe to the configured topic.
2. Settings: topic, key id, key (paste/QR), enable switch.
3. Decrypt + validate (`v`, `kid`, GCM tag, `seq`, `sgv.date` not older than 15 min), then feed the value
   into `ReceiveData.handleIntent()` like the Nightscout source does, and call
   `DataSourceTask.setLatestServerValueTime(DataSource.NIGHTSCOUT, sgv.date)` so the Dexcom fallback
   treats push as "Nightscout is up to date". Pass `site_change` to `ReceiveData.setSiteChangeTime()`.
4. While push delivers, reduce the Nightscout polling (e.g. only poll if no push for 7 minutes) – this
   is where the battery saving comes from.

Not needed if push works: Cloudflare Access service-token support in the app.

## 7. Testing push before relying on it

- Doze: `adb shell dumpsys deviceidle force-idle`, then send NORMAL and HIGH messages and measure the
  delay until GDH logs the value. Repeat with the screen off for 1+ hour (real Doze, not forced).
- Check that HIGH messages are not downgraded: log send time (relay) and receive time (app) for every
  message and compare HIGH vs NORMAL latency over several days with real out-of-range events. Firebase's
  delivery reports may help; this has not been verified.
- Kill the relay / Nightscout / tunnel / home internet one at a time and confirm the app falls back
  (Nightscout polling, then Dexcom after 10 minutes) and that the Dexcom Follow app still alarms.

## 8. Open questions

- Exact Nightscout plugin for Dexcom (legacy `bridge` vs `connect`) in the current release.
- Whether Tandem's displayed IOB includes Control-IQ basal changes (affects how far Nightscout's
  treatment-based IOB from tconnectsync is off). Real-time IOB would need ControlX2 on the child's phone.
- Whether the app's foreground service changes FCM's high-priority downgrade behaviour.
