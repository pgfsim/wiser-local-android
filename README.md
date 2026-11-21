# Wiser Local

A small, unofficial Android app for Drayton Wiser heating hubs. It talks straight
to the hub over your home network using the hub's local REST API, with no cloud
account involved.

The app has four tabs, plus a settings page behind the gear icon:

- **Home:** whether the boiler is being called for heat, away mode, boost all
  rooms and cancel overrides; your favourite rooms and hot water with their
  controls (choose and order them with Edit); and your Moments, one tap to run.
- **Automations:** Moments (create, edit, rename, delete and run), Schedules
  (a weekly editor for each heating schedule and for hot water; add, rename,
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
- **Settings:** hub connection (with automatic hub discovery), rooms (each
  with its device count; a room page to rename it, choose or edit its schedule,
  switch open window detection, see its devices and delete it), devices
  (a page each with battery, signal and readings, its room, button lock,
  firmware, identify and delete; add a new device), away mode (away temperature, whether hot water turns off too),
  smart modes (eco, comfort), system settings (hub firmware, automatic firmware updates, restarting the hub, cloud and
  Wi-Fi details, changing the heat source and boiler type, OpenTherm hot water and
  heating water temperatures, hot water channel, legacy PowerTag energy mode, brand, OpenTherm, valve
  protection, hub clock, boiler readings over OpenTherm), home screen
  favourites, and light or dark theme.
- **Home screen widget:** current and target temperature for one room.
- **Quick settings tiles:** hot water boost, and away mode.

It only works while the phone can reach the hub, so on your home Wi-Fi or over a
VPN into your home network.

Not affiliated with or endorsed by Drayton or Schneider Electric. The local API
is undocumented and could change with a firmware update.

## Setup

1. Give the hub a fixed IP address (a DHCP reservation on your router).
2. Get the hub's secret:
   - Press the **Setup** button on the hub so its light flashes.
   - Join the `WiserHeatXXXXXX` Wi-Fi network from your phone.
   - Open `http://192.168.8.1/secret/` in a browser and copy the text.
   - Press **Setup** again to return the hub to normal.
3. Open the app, enter the hub's IP address and the secret, and tap
   **Save and test connection**.
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

The payloads follow [msp1974/wiserheatapiv2](https://github.com/msp1974/wiserheatapiv2),
the library behind the Home Assistant integration.

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

Only Insights uses the internet. It signs in to Wiser's cloud the same way the
official app does: `POST https://api-nl.wiserair.com/Login` with the official
app's client id in a `Basic` header and `grant_type=password` (or
`grant_type=refresh_token` to renew), and `POST /api/account/logout` to sign
out. The password is sent only in that sign-in request and is never stored.
The refresh token is kept encrypted with a key in the Android Keystore; the
access token, valid for an hour, is kept in memory only.

The heat report comes from `https://insights.wiserhome.com`, the site the
official app shows in a web view. It has no data API, so the app requests
`/Home/DayTemperature?Date=11/18/2026 00:00:00` (or `WeekTemperature`,
`MonthTemperature`) with the access token as a `Bearer` header, keeps the
site's session cookie in memory, and reads the readings out of the chart
script in the returned page. A change to that page could break it.

## Limitations

- The widget refreshes about every 30 minutes (Android's minimum for this kind
  of widget), when you tap its refresh icon, and whenever the app loads data.
- The hub secret is stored in the app's private storage, unencrypted. Backups
  are disabled so it stays on the device.
- The first request to the hub is plain HTTP, so the app allows cleartext
  traffic. On HTTPS the hub certificate cannot be verified, so this is no
  protection against someone already on your home network.
- `targetSdk` is held at 35. Raising it needs the newer local-network permission.
- Schedule editing needs firmware with the `/data/v2/` API, and is limited to
  eight changes a day.
- The hub keeps no history, so there are no graphs. It reports a boiler fuel
  type setting but not whether the boiler is a combi, system or heat pump.
- "Off" for a room is manual mode with the target set to off, as the hub has
  no separate off mode.
- Hub discovery, OpenTherm readings and the window detection switch have not
  been tried against a real hub. Neither have room and schedule management,
  adding a device, device lock and identify, or the clock settings; those
  requests are taken from the reference library.
- A Moment made or changed here can have up to 60 actions, each setting a room
  or turning the hot water on, off or on for a set time. Room boosts, waits and
  other device types are left to the official app; a Moment using them can be
  run, renamed, given another icon or deleted here but not edited.
- Icons: the hub stores a number (IconId 0 to 75) for each Moment and automation.
  This app draws its own icon for each one; the SVGs are in `icons/moment`, and
  `python tools/icons_to_drawables.py` turns them into the app's drawables.
- The time zone offset is assumed to be in minutes from UTC.
- Whether a firmware update is pending is not shown, because the hub's data
  does not say so clearly.
