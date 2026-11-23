# Wiser Local

A small, unofficial Android app for Drayton Wiser heating hubs. It talks straight
to the hub over your home network using the hub's local REST API, with no cloud
account involved*.

<p>
  <img src="docs/screenshots/home.png" width="180" alt="Home tab: boiler status, away mode and a favourite room">
  <img src="docs/screenshots/control.png" width="180" alt="Control tab: a room's temperature, mode and boost">
  <img src="docs/screenshots/moments.png" width="180" alt="Moments with their actions and run buttons">
  <img src="docs/screenshots/schedule.png" width="180" alt="Weekly schedule editor for a room">
  <img src="docs/screenshots/home-dark.png" width="180" alt="Home tab in dark theme">
</p>

<sub>Screenshots show example data, not a real home.</sub>

The app has four tabs, plus a settings page behind the gear icon:

- **Home:** whether the boiler or heat source is being called for heat, away mode, boost all
  rooms and cancel overrides; your favourite rooms and hot water with their
  controls (choose and order them with Edit); and your Moments, one tap to run.
  On hubs that support it, the app keeps a live connection to the hub while it is
  open, so changes made elsewhere (the official app, a thermostat) show within a
  second or two; the status line then says "live".
- **Automations:** Moments (create, edit, rename, delete and run; each action sets a
  room's temperature, boosts it for a time or sends it back to its schedule, or turns
  the hot water on, off, on for a time or back to its schedule; on hubs with
  them, a light on, off or dimmed, a smart plug on or off, a shutter lifted or
  tilted to a position, a cooling room's mode or on/off, or a room's heating
  level in percent, an EV charger started, paused or switched, a fan's speed or a
  PowerTag C output; waits and notifications added in the official app are
  kept), Schedules
  (a weekly editor for each heating schedule and for hot water, with copy and
  paste as text, like "Monday: 06:00 19.0, 08:00 17.5, 22:00 Off"; add, rename,
  copy and delete heating schedules), and automations (create and edit them:
  away mode or hot water conditions, times including sunrise and sunset, and
  notifications, waits, Moments, room and hot water actions; on/off, delete).
  Moments and automations show their icon, and you can choose it when editing.
- **Control:** every room and the hot water with full controls: target
  temperature, boost, hold a temperature for a set time, mode (schedule,
  manual, off), advance to the next scheduled setting, the next scheduled
  change, humidity and window state. One tab per room also lists its devices.
- **Insights:** from your Wiser account: energy saved this month and the
  monthly trend, hours saved by away and eco mode, heating hours over the last
  30 days against the outdoor minimum and maximum, activity counts, and the heat report: room temperatures,
  set points and outdoor temperature by day, week or month, drawn in the app.
  Tap or drag on the chart for the values at a time, and tap a name to hide it.
- **Settings:** hub connection (scan the QR code on the hub to get its secret,
  and automatic hub discovery), rooms (each
  with its device count; a room page to rename it, choose or edit its schedule,
  switch open window detection, set its heating type and occupancy settings,
  choose its heating channel on hubs with more than one, see
  its devices and delete it), devices (a page each with its name, battery,
  signal and readings, its room, button lock, radiator valve battery type,
  firmware, identify and delete; add a new device, with an install code if it
  has one, scanned from its QR code as the official app does for newer radiator
  valves and other devices, or stop adding devices), away mode (away temperature, whether hot water turns off too),
  smart modes (eco, comfort), system settings (hub firmware, automatic firmware updates, restarting the hub, cloud and
  Wi-Fi details, changing the heat source and boiler type, OpenTherm hot water and
  heating water temperatures, hot water channel, legacy PowerTag energy mode, brand, OpenTherm, valve
  protection, hub clock, Zigbee network details, boiler readings and burner and pump statistics over OpenTherm), home screen
  favourites, and light or dark theme.
- **Equipment** (only on hubs that have any): lights, shutters, smart plugs,
  smoke alarms, EV chargers, PowerTag energy meters and outputs, appliances,
  underfloor heating, heating actuators, fans, motion/door/window and light
  sensors, heat pumps and thermostat displays. Each gets a page with its
  readings and the official app's controls and settings (for example a light's
  brightness, mode, away action and power-on behaviour; a shutter's position and
  tilt; a heating actuator's output, cycle time and floor sensor limits), its
  name, its room and, for lights, shutters, fans, plugs and chargers, its
  schedule. Also: a light's dimming range, a shutter's motor timings and slat
  angles, a PowerTag's installation type and grid limit, high- and low-power
  alarms on appliances, what each button on a button panel does (any action a
  Moment can do), and Zigbee groups of lights and shutters (create, add or
  remove devices, rename, delete). Light, shutter and fan schedules ("Level" schedules, in percent)
  can be edited. System settings adds summer comfort, seasonal comfort, cooling
  and underfloor settings, the dynamic energy tariff (price now, today's
  average, cheapest and dearest times) and Matter pairing where the hub
  supports them, and
  room pages add summer comfort, cooling, heat pump mode and floor sensor
  settings. All of this follows the official app's requests but has only been
  tested against a simulated hub.
- **Home screen widget:** current and target temperature for one room.
- **Quick settings tiles:** hot water boost, and away mode.
- **App icon shortcuts:** long-press the app icon for away mode on/off, hot water
  boost, boost all rooms and your Moments. They run without opening the app and
  confirm with a short message; each can be dragged onto the home screen. Only
  what your hub supports is offered, and launchers show the first four or five.

It only works while the phone can reach the hub, so on your home Wi-Fi or over a
VPN into your home network*.

Not affiliated with or endorsed by Drayton or Schneider Electric. The local API
is undocumented and could change with a firmware update.

## Setup

1. Give the hub a fixed IP address (a DHCP reservation on your router).
2. Get the hub's secret. The easy way: in the app's **Hub connection** page,
   press **Setup** on the hub so its light flashes, tap **Scan the QR code on
   the hub** and scan the code on the hub's label. The phone joins the hub's
   own Wi-Fi for a moment (Android asks you to allow it), reads the secret and
   goes back to your Wi-Fi. Press **Setup** again afterwards. The scanner is
   Google's code scanner, so it needs Google Play services but no camera
   permission. By hand instead:
   - Press the **Setup** button on the hub so its light flashes.
   - Join the hub's Wi-Fi network (named on its label, `Wiser_XXXXXX` or
     `WiserHeatXXXXXX`) from your phone.
   - Open `http://192.168.8.1/secret/` in a browser and copy the text.
   - Press **Setup** again to return the hub to normal.
3. Enter the hub's IP address (or tap **Find hub on this network**) and the
   secret, and tap **Save and test connection**.
4. Optional: add the **Room temperature** widget from your launcher's widget
   picker, and add the **Water boost** tile by editing your quick settings.

The boost length (default 60 minutes) and the room shown on the widget are in
the app's Settings.

## Building

Open the folder in Android Studio, or from a terminal with JDK 17 or newer:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

How releases are built and signed is in [RELEASING.md](RELEASING.md).

## Installing and updating

Download the APK from the [Releases](https://github.com/pgfsim/wiser-local-android/releases)
page and open it on your phone. Android asks you to allow installs from your
browser or file manager the first time.

Every release is signed with the same key. To check an APK came from this
project, compare its signing certificate
(`apksigner verify --print-certs wiser-local-vX.Y.Z.apk`) with this SHA-256
fingerprint:

```
46:AB:15:6F:CF:A0:0C:D0:CA:FB:35:D7:0E:0D:A8:6E:69:2A:72:51:D0:6F:4A:50:8F:8F:01:E9:43:30:FE:6E
```

`apksigner` prints the same value in lower case without colons:
`46ab156fcfa00cd0cafb35d70e0da86e692a7251d06f4a508f8f01e94330fe6e`. Each
release also has a `.sha256` file with the APK's checksum.

The app looks for a newer release once a day, and whenever **Settings → Check
for updates** is tapped. If there is one it offers **Update** or **Later**;
updating is never required. Before installing, it checks the download against
the release's checksum and that it is signed with the same key as the installed
copy. Android then asks you to confirm, and the first time, to allow installs
from Wiser Local.

A copy you built yourself (or any debug build) is signed with a different key,
so it can't update to a release: uninstall it and install the release once.

## How it works

Everything hub-related is in [`WiserHub.kt`](app/src/main/java/dev/wiserlocal/app/WiserHub.kt).
Requests go to `http://<hub>/data/v2/domain/` (falling back to `/data/domain/`
on older firmware) with the secret in a `SECRET` header. Newer firmware redirects
to HTTPS with a self-signed certificate; the app then switches to HTTPS for that
hub and accepts its certificate without verifying it. Temperatures are sent
in tenths of a degree.

| Action | Request |
| --- | --- |
| Read everything | `GET /data/v2/domain/` |
| Set room target | `PATCH Room/{id}` `{"RequestOverride":{"Type":"Manual","SetPoint":205}}` |
| Resume schedule | `PATCH Room/{id}` `{"RequestOverride":{"Type":"None"}}` |
| Boost hot water | `PATCH HotWater/{id}` `{"RequestOverride":{"Type":"Manual","DurationMinutes":60,"SetPoint":1100}}` |
| Cancel boost | `PATCH HotWater/{id}` `{"RequestOverride":{"Type":"None"}}` |
| Boost room | `PATCH Room/{id}/RequestOverride/` `{"Type":"Boost","DurationMinutes":60}` (the hub picks the increase) |
| Room mode | `PATCH Room/{id}` `{"Mode":"Auto"}` or `{"Mode":"Manual"}` |
| Window detection | `PATCH Room/{id}` `{"WindowDetectionActive":true}` |
| Hot water mode | `PATCH HotWater/{id}` `{"Mode":"Manual","RequestOverride":{"Type":"None"}}` |
| Away mode | `PATCH System/RequestOverride` `{"Type":"Away"}` / `{"Type":"None"}` |
| Eco / Comfort mode | `PATCH System` `{"EcoModeEnabled":true}` / `{"ComfortModeEnabled":true}` |
| Timed hold | `PATCH Room/{id}` `{"RequestOverride":{"Type":"Manual","DurationMinutes":60,"SetPoint":200}}` |
| Rename / add / delete room | `PATCH Room/{id}` `{"Name":"Study"}` / `POST Room/` `{"Name":"Study"}` / `DELETE Room/{id}` |
| Boost all / cancel all | `PATCH System/RequestOverride` `{"Type":"Boost","DurationMinutes":60}` / `PATCH System` `{"RequestOverride":{"Type":"CancelUserOverrides"}}` |
| Away settings | `PATCH System` `{"AwayModeSetPointLimit":160}` / `PATCH System/AwayModeAffectsHotWater` `true` |
| Valve protection | `PATCH System` `{"ValveProtectionEnabled":true}` |
| Automatic firmware updates | `PATCH System/FotaEnabled` `true` / `false` |
| Restart the hub | `PATCH System/RequestReboot` `true` |
| Hub clock | `PATCH System/` `{"TimeZoneIdentifier":"Europe/London"}` / `{"TimeZoneOffset":0}` (minutes) / `{"UpdateUnixTime":<seconds>}` |
| Heat source / boiler type | `PATCH System/BoilerSettings/` `{"FuelType":"Oil"}` (Gas, Oil, Electric, HeatPump; the hub then sets the cycle rate) / `{"ControlType":"HeatSourceType_OpenTherm"}` (or `HeatSourceType_RelayControlled`, shown as Standard) |
| OpenTherm hot water / max heating water temperature | `PATCH /data/v2/openTherm/preDefinedRemoteBoilerParameters/` `{"dhwSetpoint":600}` / `{"maxChSetpoint":650}` (tenths of a degree) |
| Hot water channel on / off | `PATCH System/HotWaterEnabled` `true` / `false` (offered when `System.HotWaterChannelSupported`) |
| Legacy PowerTag energy mode | `PATCH Zigbee/RequestLegacyLongPacketSupport` `true` / `false` (read from `Zigbee.LegacyLongPacketSupport`) |
| Run a Moment | `PATCH System` `{"TriggerMoment":1}` |
| Create Moment | `POST Moment?Name=Bedtime&IconId=0` `{"Domain":"HotWater","Id":2,"Action":"RequestOverride","Value":"{\"type\":\"Manual\",\"setPoint\":-200}"}` |
| Read / change a Moment's action | `GET Moment/{id}` / `PATCH Moment/{id}?Name=Bedtime&IconId=51` with the same body as Create |
| Rename / change the icon of a Moment this app can't edit | `GET Moment/{id}`, then `PATCH Moment/{id}?Name=Bedtime&IconId=51` with the body exactly as read |
| Delete Moment | `DELETE Moment/{id}` |
| Add a device | `PATCH System/RequestPermitJoin` `120` |
| Lock / identify device | `PATCH Device/{id}` `{"DeviceLockEnabled":true}` / `{"Identify":true}` (`false` to stop) |
| Move room thermostat | `DELETE Room/{old}/RoomStatId`, then `PATCH Room/{new}/` `{"RoomStatId":id}` |
| Move radiator valve | `PATCH Room/{old}/` `{"SmartValveIds":[…without it]}`, then `PATCH Room/{new}/` `{"SmartValveIds":[…with it]}` |
| Move other device | `PATCH Device/{id}` `{"RoomId":room}`; out of its room: `DELETE Device/{id}/RoomId` |
| Delete device | `DELETE Device/{id}/` |
| Hub Wi-Fi details | `GET /data/v2/network/` |
| Boiler readings | `GET /data/v2/opentherm/` |
| Rename / delete schedule | `PATCH` or `DELETE /data/v2/schedules/Heating/{id}` (`{"Name":"Weekend"}`) |
| Create schedule | `POST /data/v2/schedules/Assign` `{"Assignments":[],"Heating":{"Name":"Weekend"}}` |
| Assign schedule to rooms | `PATCH /data/v2/schedules/Assign` `{"Assignments":[2],"Heating":{"id":3,"Name":"Weekend"}}` |
| Read schedules | `GET /data/v2/schedules/` |
| Save room schedule | `PATCH /data/v2/schedules/Heating/{id}` `{"Monday":{"Time":[630,830],"DegreesC":[200,160]}, ...}` |
| Save hot water schedule | `PATCH /data/v2/schedules/OnOff/{id}` `{"Monday":[630,-830], ...}` |

Schedule times are `HHMM` integers (`630` is 06:30). In a heating schedule
`-200` means off. In an on/off schedule a positive time switches on, a negative
time switches off, and off at midnight is `-2400`.

The UI is Jetpack Compose with Material 3 (in `app/src/main/java/dev/wiserlocal/app/ui/`).
Talking to the hub uses only `HttpURLConnection` and `org.json`.

## Hub firmware support

The app only offers what the hub's firmware supports. It reads the firmware version from the hub's controller device, plus the hub's own lists of the device types it can pair (`DeviceCapabilityMatrix`) and its automation limits (`FeatureCapability`). If the hub doesn't report one of these, the app doesn't hide anything because of it.

| Feature | Needs firmware |
|---|---|
| Away mode | 2.12.2 |
| Open window detection | 2.18.100 |
| Device lock | 2.19.78 |
| Boost all rooms, OpenTherm boiler type | 2.29.0 |
| Changing a room's schedule | 2.55.0 |
| Heat pump heat source | 3.3.3 |
| Moments | 3.7.14 |
| Automations | 4.6.0 |
| Legacy PowerTag energy mode | 4.34.11 |

The automation editor uses the hub's limits for conditions, times and actions (`FeatureCapability.Automation`), and offers Wait steps only when the hub allows them.

## Wiser account (Insights)

Only Insights (and the experimental remote control below) uses the internet. It signs in to Wiser's cloud the same way the
official app does. The password is sent only in that sign-in request and is never stored.
The refresh token is kept encrypted with a key in the Android Keystore; the
access token, valid for an hour, is kept in memory only.

The heat report comes from `https://insights.wiserhome.com`, the site the
official app shows in a web view. It has no data API, so the app requests
`/Home/DayTemperature?Date=11/18/2026 00:00:00` (or `WeekTemperature`,
`MonthTemperature`) with the access token as a `Bearer` header, keeps the
site's session cookie in memory, and reads the readings out of the chart
script in the returned page. A change to that page could break it.

### Control away from home (experimental)

Off by default, under **Settings → Wiser account**. When it is on and the hub
can't be reached on the current network, the app sends the same requests through
Wiser's cloud, as the official app does away from home.

## Limitations

- \*There is an [experimental setting](#control-away-from-home-experimental) to allow hub and device control through Wiser's servers using your Wiser login, so the hub can be controlled from outside the home network.
- **Airzone air conditioning isn't supported.** The official app adds an
  Airzone unit only after finding it on the local network with its own
  discovery step, then registers it with the hub (`POST /domain/Airzone`).
  Without that discovery, and without the hardware to test against, there is
  no reliable way to set one up from this app, so use the official app for
  Airzone.
- The widget refreshes about every 30 minutes (Android's minimum for this kind
  of widget), when you tap its refresh icon, and whenever the app loads data.
- The hub secret is stored in the app's private storage, unencrypted. Backups
  are disabled so it stays on the device.
- The first request to the hub is plain HTTP, so the app allows cleartext
  traffic. On HTTPS the hub certificate cannot be verified, so this is no
  protection against someone already on your home network.
- `targetSdk` is held at 35 for now. Raising it needs the newer local-network permission.
- Schedule editing needs firmware with the `/data/v2/` API, and is limited to
  eight changes a day.
- The hub keeps no history, so there are no graphs held on it, only on Wiser servers.
- "Off" for a room is manual mode with the target set to off, as the hub has
  no separate off mode.
- Hub discovery, OpenTherm readings and the window detection switch have not
  been tried against a real hub. Neither have room and schedule management,
  adding a device, device lock and identify, or the clock settings; those
  requests are taken from the reference library.
- A Moment made or changed here can have up to 60 actions. Waits and
  notifications can only be added in the official app (this app keeps them), and
  a Moment using an action this app doesn't know can be run, renamed, given
  another icon or deleted here but not edited.
- Whether a firmware update is pending is not shown, because the hub's data
  does not say so clearly.
