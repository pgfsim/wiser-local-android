# Wiser Local

A small, unofficial Android app for Drayton Wiser heating hubs. It talks straight
to the hub over your home network using the hub's local REST API, with no cloud
account involved.

- **App:** room temperatures, target temperature up/down in 0.5° steps, resume
  schedule, hot water boost, and a weekly schedule editor for each room and
  for hot water.
- **Home screen widget:** current and target temperature for one room.
- **Quick settings tile:** start or cancel a hot water boost.

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
| Read schedules | `GET /data/v2/schedules/` |
| Save room schedule | `PATCH /data/v2/schedules/Heating/{id}` `{"Monday":{"Time":[630,830],"DegreesC":[200,160]}, ...}` |
| Save hot water schedule | `PATCH /data/v2/schedules/OnOff/{id}` `{"Monday":[630,-830], ...}` |

Schedule times are `HHMM` integers (`630` is 06:30). In a heating schedule
`-200` means off. In an on/off schedule a positive time switches on, a negative
time switches off, and off at midnight is `-2400`.

The payloads follow [msp1974/wiserheatapiv2](https://github.com/msp1974/wiserheatapiv2),
the library behind the Home Assistant integration.

The app has no third-party dependencies: plain Android views, `HttpURLConnection`
and `org.json`.

## Limitations

- The widget refreshes about every 30 minutes (Android's minimum for this kind
  of widget), when you tap its refresh icon, and whenever the app loads data.
- The hub secret is stored in the app's private storage, unencrypted. Backups
  are disabled so it stays on the device.
- The first request to the hub is plain HTTP, so the app allows cleartext
  traffic. On HTTPS the hub certificate cannot be verified, so this is no
  protection against someone already on your home network.
- `targetSdk` is held at 35. Raising it needs the newer local-network permission
  and edge-to-edge layout handling.
- Schedule editing needs firmware with the `/data/v2/` API, and is limited to
  eight changes a day.
- The hub keeps no history and does not say what kind of boiler it controls,
  so there are no graphs or system-type details.
- No away mode or per-device details yet.
