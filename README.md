# Homey Dashboard

A native Android kitchen dashboard for a Homey Pro, built for the TCL NXTPAPER 11 Plus (Android 15, 2200×1440, landscape).
It talks to the Homey directly over the local network with an API key, builds itself from your zones and devices, and stays live over Homey's realtime socket.

## Install

Every push builds a debug APK. Open the latest run under **Actions → Build**, download `homey-dashboard-debug`, and sideload the APK on the tablet.

To build locally you need the Android SDK (API 35) and JDK 17:

```sh
./gradlew :homey-api:test :app:assembleDebug
```

## First launch

1. In the Homey web app (my.homey.app), open **Settings → API Keys** and create a key that can view zones and devices and control devices.
2. Find the Homey's address in **Settings → General** (for example `192.168.1.20`), or use its `…homey.homeylocal.com` address.
3. Enter both in the app. They are saved on the tablet, so this happens once.

## Layout

| Module | What it is |
|---|---|
| `homey-api` | Pure Kotlin. HTTP client (`HomeyClient`), realtime Socket.IO client (`HomeyRealtime`), in-memory mirror of the Homey (`HomeyStore`), and the rules that turn zones and devices into dashboard sections and card types (`DashboardLayout`). Unit-tested on the JVM. |
| `app` | Jetpack Compose UI: setup screen, dashboard with a zone rail and a grid of device cards. |

All colours, shapes and borders come from `DashboardStyle` in `ui/theme/Theme.kt`, and every card is drawn by `DashboardCard` in `ui/dashboard/DeviceCards.kt`. The glass design changes those two places.

## How it talks to Homey

- `GET /api/manager/system/local` (no key) to confirm the address and learn the Homey id.
- `GET /api/manager/zones/zone` and `GET /api/manager/devices/device` for the initial load.
- `PUT /api/manager/devices/device/:id/capability/:capability` to control a device. The card updates at once and rolls back if Homey refuses.
- Socket.IO: `handshakeClient {token, homeyId}`, then `subscribe` to `homey:manager:devices`, `homey:manager:zones` and `homey:device:<id>`. After a reconnect the app reloads everything.

If the `homeylocal.com` address doesn't resolve (some routers block it), the app falls back to the plain IP it encodes.
