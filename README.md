# Homey Dashboard

A native Android kitchen dashboard for a Homey Pro, built for the TCL NXTPAPER 11 Plus (Android 15, 2200×1440, landscape).
It talks to the Homey directly over the local network with an API key, builds itself from your zones and devices, and stays live over Homey's realtime socket.

## Install

Every push builds a debug APK. The newest one is always at **Releases → Latest test build** (`releases/download/dev/homey-dashboard.apk`); sideload it on the tablet.

To build locally you need the Android SDK (API 35) and JDK 17:

```sh
./gradlew :homey-api:test :app:assembleDebug
```

## First launch

1. In the Homey web app (my.homey.app), open **Settings → API Keys** and create a key that can view zones and devices, control devices, and view and set moods.
2. Find the Homey's address in **Settings → General** (for example `192.168.1.20`), or use its `…homey.homeylocal.com` address.
3. Enter both in the app. They are saved on the tablet, so this happens once.

## Layout

| Module | What it is |
|---|---|
| `homey-api` | Pure Kotlin. HTTP client (`HomeyClient`), realtime Socket.IO client (`HomeyRealtime`), in-memory mirror of the Homey (`HomeyStore`), and the rules that turn zones and devices into dashboard sections and card types (`DashboardLayout`). Unit-tested on the JVM. |
| `app` | Jetpack Compose UI: setup screen and the glass dashboard (side menu, overview, rooms, moods, media). |

### Design

The look is iOS-style glass in light and dark (Settings → Appearance: Auto, Light or Dark). Glass is faked cheaply for the tablet's Mali GPU: `Wallpaper` draws soft colour fields once, and `Modifier.glass()` gives panels a translucent tint, a hairline and a bright top edge; nothing is blurred live. Colours live in `GlassColors` in `ui/theme/Theme.kt`; shared pieces (panels, bubbles, round buttons, chips) are in `ui/components/Glass.kt`.

- Lights: tap to toggle, drag sideways to dim. Blinds work the same way for position.
- Locks: tap to lock, long-press to unlock.
- Thermostats: − and + collect taps and send once you pause.
- Moods come from the Homey's Moods; the overview shows all of them and each room shows its own.
- Devices hidden in the Homey app are left out.

Type is Manrope; icons are Material Symbols Rounded, subset into `res/font/symbols_outlined.ttf` and `symbols_filled.ttf` (both OFL/Apache, from Google Fonts). `Sym` in `ui/components/Icons.kt` lists the code points. To add an icon, add its name to the `icon_names=` list of the Google Fonts request for both fonts (FILL 0 and FILL 1, weight 500), replace the two files, and add its code point to `Sym`.

## How it talks to Homey

- `GET /api/manager/system/ping` (no key) to confirm the address and learn the Homey id from the `X-Homey-ID` header.
- `GET /api/manager/zones/zone` and `GET /api/manager/devices/device` for the initial load.
- `GET /api/manager/moods/mood` for moods (skipped if the key may not read them) and `POST /api/manager/moods/mood/:id/set` to set one.
- `PUT /api/manager/devices/device/:id/capability/:capability` to control a device. The card updates at once and rolls back if Homey refuses.
- Socket.IO: `handshakeClient {token, homeyId}`, then `subscribe` to `homey:manager:devices`, `homey:manager:zones`, `homey:manager:moods` and `homey:device:<id>`. After a reconnect the app reloads everything.

If the `homeylocal.com` address doesn't resolve (some routers block it), the app falls back to the plain IP it encodes.
