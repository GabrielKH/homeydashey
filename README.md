# Homey Dashboard

A native Android kitchen dashboard for a Homey Pro, built for the TCL NXTPAPER 11 Plus (Android 15, 2200×1440, landscape).
It talks to the Homey directly over the local network with an API key, builds itself from your zones and devices, and stays live over Homey's realtime socket.

## Install

Every push builds a debug APK. The newest one is always at **Releases → Latest test build** (`releases/download/dev/homey-dashboard.apk`); sideload it on the tablet.

Every build is signed with the same key (`app/signing/homeydash.jks`) and numbered by its CI run, so a new APK installs over the old one and keeps the settings. The app checks the release's `version.txt` every few hours and offers to install a newer build (Android asks once to allow installs from the app). The key is in the repo because this is a sideloaded kiosk app; use a private key before publishing it anywhere.

To build locally you need the Android SDK (API 35) and JDK 17:

```sh
./gradlew :homey-api:test :app:assembleDebug
```

## First launch

The setup screen lists the Homeys it finds on the Wi-Fi (mDNS `_homey._tcp`, plus a ping of every address on the tablet's /24), so usually only the API key needs typing.

1. In the Homey web app (my.homey.app), open **Settings → API Keys** and create a key that can view zones and devices, control devices, and view and set moods, and view and run Flows. Flow access lets the dashboard use app cards such as the Apple TV remote keys and Sonos "Play favorite".
2. Find the Homey's address in **Settings → General** (for example `192.168.1.20`), or use its `…homey.homeylocal.com` address.
3. Enter both in the app. They are saved on the tablet, so this happens once.

## Layout

| Module | What it is |
|---|---|
| `homey-api` | Pure Kotlin. HTTP client (`HomeyClient`), realtime Socket.IO client (`HomeyRealtime`), in-memory mirror of the Homey (`HomeyStore`), and the rules that turn zones and devices into dashboard sections and card types (`DashboardLayout`). Unit-tested on the JVM. |
| `app` | Jetpack Compose UI: setup screen and the glass dashboard (side menu, overview, rooms, moods, media, calendar). |

### Design

The look is iOS-style glass in light and dark (Settings → Appearance: Auto, Light or Dark). Glass is faked cheaply for the tablet's Mali GPU: `Wallpaper` draws soft colour fields once, and `Modifier.glass()` gives panels a translucent tint, a hairline and a bright top edge; nothing is blurred live. Photo backgrounds and album art are blurred once, when loaded. Colours live in `GlassColors` in `ui/theme/Theme.kt`; shared pieces (panels, bubbles, round buttons, chips) are in `ui/components/Glass.kt`.

- Lights: tap to toggle, drag sideways to dim. The slider button (or a long press) opens the lamp: brightness, a colour wheel and white temperature when the lamp supports them. Lit tiles show the lamp's actual colour.
- Blinds: tap to open or close, drag sideways for position.
- Locks: tap to lock, long-press to unlock.
- Thermostats: − and + collect taps and send once you pause.
- Moods come from the Homey's Moods; the overview shows all of them and each room shows its own.
- Devices hidden in the Homey app are left out.
- Media: an Apple Music-style player with the album art blurred behind it. A Remote button appears on TVs and media boxes: keys come from the app's key cards (the Apple TV app), from a "send key" card with a list of keys (Samsung and other TV apps), and from Homey's TV capabilities (volume, channel, mute, power), and a Music button for speakers that can play favourites, playlists or albums (the Sonos app). The remote's volume keys turn the TV's volume when a TV is in the same room in Homey. Apps on the box show as tiles, streaming services first with their logos (Simple Icons, CC0); the sliders button chooses and orders them. Music can be started from any room, on any of those speakers.
- The side menu shrinks to icons with the arrow next to the home name (tap the home icon to open it again); the app remembers the choice.
- Press and hold any card and drag it to move it, like on an iPhone: the cards jiggle and make room, and the order is saved when you let go (in a room, cards move within their group; on the overview, cards stack in invisible lanes, two to four by screen width, and any card can go in any lane; Auto arrange lays them out tidily: the weather and calendar in the last lane, the player and pinned devices first, the rooms spread so the lanes end evenly). Tap Done to stop. Letting go without dragging opens the card's menu: add it to the overview, move it earlier or later, put it in a group of your own (e.g. "Ceiling"), or hide it on this tablet; lamp controls and unlocking are there too. Room cards on the overview have a menu to hide them or give them a background.
- Any device can be added to the overview (its menu → Add to overview). It shows on the overview as the same card it has in its room: a player as a player, a lamp as a lamp.
- Goodnight (on the overview) shows what is on and turns it all off in one tap, pausing what plays, except the devices you keep on. It can also start one of your Homey Flows (or Advanced Flows), for anything else bedtime should do.
- Media page: one big player, the one playing (the one that started last when several play), else the one that played last. The list beside it shows every player with what it plays; tap one to show it big.
- Calendar page (side menu, or the arrow on the overview's calendar card): a month with each day's events, a week in columns, or a day on a timeline, with the chosen day's agenda and the next two weeks alongside.
- Settings → Theme: six colour themes (Aurora, Ocean, Forest, Sunset, Lilac, Graphite) and a Flat colours style instead of glass and gradients.
- Settings → Screen: dim after 1–30 minutes without a touch (black with a faint clock); a touch, or the proximity sensor when the tablet has one, wakes it. Kiosk mode pins the app and lets it be chosen as the home app.
- The weather card shows 0–7 forecast days, chosen in the weather dialog. Room temperatures skip readings no room has (boilers, ovens) and convert °F.
- Settings → Display size: "Fit screen" scales the whole dashboard to the tablet's screen, or pick 80–110%.
- Overview: a weather card (Open-Meteo, no account) for the Homey's location, the tablet's GPS, the internet address or a city you search for; tap it to change. Tap the weather for the full forecast and location. Below it, a Today, Week or Month view of the calendars synced on the tablet (for example a Google family calendar), read through Android's calendar provider; the sliders icon picks which calendars show.
- Settings: hide rooms and change their order (per tablet); highlight colour (gradients) and its opacity, and a background for the home; each room's header has a picture button for its own background (preset or photo).

### Recipes

The Recipes page (side menu) is the kitchen's recipe book, kept on the tablet (`files/recipes`: one JSON file and a folder of photos shrunk to 1600 px).

- **Add from a link**, like Recipe Keeper: paste the address of any recipe page (or share it from the browser to the dashboard) and it reads the name, photo, ingredients, steps, times and servings. It reads schema.org `Recipe` data in JSON-LD, which nearly every recipe site publishes for Google (ICA, Arla, Köket, Coop, Matprat, BBC Good Food, WordPress recipe plugins…), then schema.org microdata, then the lists under headings like "Ingredienser" and "Gör så här" on plain blogs. Sites that turn away apps or build the page with JavaScript are loaded in a hidden browser instead. Before saving, pick its categories; the ones you already use that the site also names are picked for you.
- **Import from Recipe Keeper**: in Recipe Keeper, Settings → Export, then pick the .zip (or `recipes.html`) on the Recipes page. Courses, categories, collections, favourites, ratings, notes, nutrition and photos come along. Importing a newer export updates the recipes already imported instead of adding copies.
- Recipes can be written or changed by hand: one ingredient or step per line, and a line like `# Sauce` starts a section.
- Filter by course, category or favourites, and search by name or ingredient.
- A recipe's page has the photo, ingredients to tick off and the steps; tap a step to mark where you are. **Start cooking** shows one step at a time in big type, with Back and Next (or swipe), a timer button for times in the step ("bake 25–30 minutes" starts a 30 minute timer), and the ingredients alongside.
- The voice assistant knows which recipe is open and which step you are on, so "what's next?", "nästa steg", "go back", "step 4" or "how much flour?" just work. Step commands are answered at once without the AI and move the screen along; it can also find and open a saved recipe ("open the lasagne").

### Fridge

The Fridge page (side menu) and the overview's fridge card track what is in the fridge, freezer and pantry, kept on the tablet (`files/fridge/fridge.json`). Homey has nowhere to keep a food list, so it lives on the tablet like the recipes. Research and the shelf-life table: `plan/fridge-research.md` in the project files.

- **Scan** with the front camera (or the back one): hold the barcode 15 to 25 cm in front of the camera above the screen. Google ML Kit reads EAN/UPC barcodes, QR codes and Data Matrix offline. The product's name, brand, size and kind come from [Open Food Facts](https://world.openfoodfacts.org) (open data, ODbL); you confirm them once and the tablet remembers that barcode, so the next scan adds it at once (with an undo).
- **Weighed packs** from the meat, fish and cheese counter carry an in-store barcode (starting with 2) that no database knows: name it once and the tablet remembers it by the store's item number.
- **GS1 2D codes** (Data Matrix, GS1 QR, GS1 Digital Link) on fresh food can carry the use-by or best-before date and weight; those are filled in from the scan.
- **Shelf life**: the date printed on the pack when known, otherwise the purchase date plus how long that kind of food keeps (mince 2 days, chicken 2, fish 1, milk 7, hard cheese 2 months…); once opened, the shorter of that and the opened shelf life (milk 5 days, yoghurt 7, cold cuts 4). The freezer stops the clock; taking it out gives thawed food a day or two. Meat, fish, mince, cold cuts, ready meals and leftovers are "use by": past it they're marked Expired. Everything else is "best before": past it, look, smell and taste.
- The kind is guessed from Open Food Facts' categories, then Swedish and English words in the name ("kycklingfärs" is mince, "prästost" is cheese), and can be changed.
- The voice assistant can list what's there and what goes off first, add things ("lägg till två paket köttfärs i kylen"), mark them opened or frozen, and take them out ("we finished the milk"). Its instructions include the few things to eat first, so "what should we cook tonight?" can use them.

Type is Inter (OFL), the closest open typeface to Apple's SF Pro. Icons are [Phosphor](https://phosphoricons.com) (MIT): each icon's Regular and Fill SVGs from `@phosphor-icons/core` are converted to vector drawables `res/drawable/ph_<name>.xml` and `ph_<name>_fill.xml`, and listed in `Sym` in `ui/components/Icons.kt`.

## How it talks to Homey

- `GET /api/manager/system/ping` (no key) to confirm the address and learn the Homey id from the `X-Homey-ID` header.
- `GET /api/manager/zones/zone` and `GET /api/manager/devices/device` for the initial load.
- `GET /api/manager/moods/mood` for moods (skipped if the key may not read them) and `POST /api/manager/moods/mood/:id/set` to set one.
- `PUT /api/manager/devices/device/:id/capability/:capability` to control a device. The card updates at once and rolls back if Homey refuses.
- `GET /api/manager/flow/flowcardaction` for app "Then" cards; `POST /api/manager/flow/flowcardaction/:uri/:id/run` runs one (remote keys, play favourite) and `GET …/:uri/:id/autocomplete?name=&query=` lists the choices (favourites, playlists, apps). Skipped if the key has no Flow access.
- `GET /api/manager/flow/flow` and `/advancedflow` list the Flows Goodnight can start; `POST …/flow/:id/trigger` (or `…/advancedflow/:id/trigger`) starts one.
- `GET /api/manager/geolocation/option/location` (or `/state`) for the Homey's location, used by the weather card.
- Socket.IO: `handshakeClient {token, homeyId}`, then `subscribe` to `homey:manager:devices`, `homey:manager:zones`, `homey:manager:moods` and `homey:device:<id>`. After a reconnect the app reloads everything.

If the `homeylocal.com` address doesn't resolve (some routers block it), the app falls back to the plain IP it encodes.
