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
  copy and delete heating schedules), and a placeholder for automations.
- **Control:** every room and the hot water with full controls: target
  temperature, boost, hold a temperature for a set time, mode (schedule,
  manual, off), advance to the next scheduled setting, the next scheduled
  change, humidity and window state. One tab per room also lists its devices.
- **Insights:** sign in to your Wiser account (needed for the heat report,
  which only Wiser's cloud keeps). The report itself is still to come.
- **Settings:** hub connection (with automatic hub discovery), rooms (add,
  rename, delete, choose a schedule), devices (battery, signal, readings,
  firmware, button lock, identify, add a new device), away mode (away
  temperature, whether hot water turns off too), smart modes (eco, comfort,
  open window detection per room), system settings (hub firmware, cloud and
  Wi-Fi details, heat source and boiler type, brand, OpenTherm, valve
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
| Boost room | `PATCH Room/{id}` `{"RequestOverride":{"Type":"Boost","DurationMinutes":60,"IncreaseSetPointBy":20}}` |
| Room mode | `PATCH Room/{id}` `{"Mode":"Auto"}` or `{"Mode":"Manual"}` |
| Window detection | `PATCH Room/{id}` `{"WindowDetectionActive":true}` |
| Hot water mode | `PATCH HotWater/{id}` `{"Mode":"Manual","RequestOverride":{"Type":"None"}}` |
| Away mode | `PATCH System` `{"RequestOverride":{"Type":2}}` (`0` to cancel) |
| Eco / Comfort mode | `PATCH System` `{"EcoModeEnabled":true}` / `{"ComfortModeEnabled":true}` |
| Timed hold | `PATCH Room/{id}` `{"RequestOverride":{"Type":"Manual","DurationMinutes":60,"SetPoint":200}}` |
| Rename / add / delete room | `PATCH Room/{id}` `{"Name":"Study"}` / `POST Room` `{"name":"Study"}` / `DELETE Room/{id}` |
| Boost all / cancel all | `PATCH System` `{"RequestOverride":{"Type":"Boost",...}}` / `{"RequestOverride":{"Type":"CancelUserOverrides"}}` |
| Away settings | `PATCH System` `{"AwayModeSetPointLimit":160}` / `{"AwayModeAffectsHotWater":"true"}` |
| Valve protection / clock | `PATCH System` `{"ValveProtectionEnabled":true}` / `{"AutomaticDaylightSaving":"true"}` / `{"TimeZoneOffset":0}` |
| Run a Moment | `PATCH System` `{"TriggerMoment":1}` |
| Create Moment | `POST Moment?Name=Bedtime&IconId=0` `{"Domain":"HotWater","Id":2,"Action":"RequestOverride","Value":"{\"type\":\"Manual\",\"setPoint\":-200}"}` |
| Read / change a Moment's action | `GET Moment/{id}` / `PATCH Moment/{id}?Name=Bedtime&IconId=51` with the same body as Create |
| Rename / delete Moment | `PATCH Moment/{id}` `{"Name":"Bedtime"}` / `DELETE Moment/{id}` |
| Add a device | `PATCH System/RequestPermitJoin` `120` |
| Lock / identify device | `PATCH Device/{id}` `{"DeviceLockEnabled":true}` / `{"Identify":true}` (`false` to stop) |
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

## Wiser account (Insights)

Only Insights uses the internet. It signs in to Wiser's cloud the same way the
official app does: `POST https://api-nl.wiserair.com/Login` with the official
app's client id in a `Basic` header and `grant_type=password` (or
`grant_type=refresh_token` to renew), and `POST /api/account/logout` to sign
out. The password is sent only in that sign-in request and is never stored.
The refresh token is kept encrypted with a key in the Android Keystore; the
access token, valid for an hour, is kept in memory only.

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
- A Moment made or changed here does one thing: sets one room, or turns the
  hot water on, off or on for a set time. Moments with several actions, room
  boosts, and other device types are left to the official app.
- The time zone offset is assumed to be in minutes from UTC.
- Whether a firmware update is pending is not shown, because the hub's data
  does not say so clearly.
