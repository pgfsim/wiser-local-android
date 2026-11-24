# Hub local API notes

Reverse-engineering notes on the Wiser hub's local API, for anyone extending
this app. Nothing here is official or guaranteed; it was pieced together from
the hub's own responses and the official app, and could change with firmware.

Replace `<hub-ip>` with your hub's address and `<secret>` with its secret below.
Never commit a real secret.

## Two API surfaces

The hub exposes two different naming schemes over your LAN:

- **`/data/…` — the facade.** This is what the official app and this app use.
  It's a curated, versioned view of the hub. **This is the one you call.**
- **`/db/…` — the internal store.** The hub's raw internal database and RPC
  tree. It is documented (see below) but **not served over the network** — the
  prefix is bound internally, so requests to `/db/…` fail (HTTP 502 over HTTPS,
  refused over HTTP). The destructive bits that live only under `/db`
  (factory reset, raw Wi-Fi reconfigure) are therefore not reachable from the
  LAN.

### The hub documents itself

The hub serves its own OpenAPI 3.0 spec:

```
https://<hub-ip>/swagger
```

Title "Wiser Home local API", version tracks the firmware (e.g. 4.52.3). It
documents the **internal `/db/…` tree** (~220 paths). It's an excellent field
reference — you just have to translate the paths to the `/data` facade to
actually call them (see the mapping below).

## Auth and transport

- **Auth:** a single API key. Send the hub secret in a `secret` header on every
  request. One global scheme gates everything — there is no separate admin tier
  and no unauthenticated endpoint.
- **HTTP:** plain `http://<hub-ip>/` on port 80 works for the `/data` facade.
- **HTTPS:** hubs that support "secure hub connection" also serve HTTPS with a
  **self-signed certificate** which the official app pins (it fetches the cert
  key from `api/ota-manager/info`). In a tool like Postman/curl you must disable
  certificate verification to use HTTPS. The HTTPS front-end only routes the
  `/data` facade — hitting `/db/…` over HTTPS returns **502 Bad Gateway**.

## Path mapping: `/db` (swagger) → `/data` (live)

The app builds the live URL as:

```
/data  +  versionPrefix  +  resourcePath
versionPrefix = "/v2"   on modern (V2) hubs, ""  on older ones
```

So, to call anything you find in the swagger, drop `/db` and prepend `/data`
(with `/v2` for the domain tree):

| Swagger (`/db/…`)            | Live on the hub (`/data/…`)        |
|------------------------------|------------------------------------|
| `/db/domain/Room/{id}`       | `/data/v2/domain/Room/{id}`        |
| `/db/domain/System`          | `/data/v2/domain/System`           |
| `/db/domain/Device/{id}`     | `/data/v2/domain/Device/{id}`      |
| `/db/cache/NET/…`            | `/data/network/…` (unversioned)    |
| `/db/api`, `/db/cache/Modbus`, `/db/matter/…` | internal only — no `/data` route |

Network paths are **not** versioned (no `/v2`); the domain tree **is**. If
`/data/v2/domain/` 404s, your hub is pre-V2 — use `/data/domain/` instead.

## Useful calls

Whole-system read (the practical equivalent of "dump the database"):

```
curl 'http://<hub-ip>/data/v2/domain/' --header 'secret: <secret>'
```

Returns every room, device, schedule and system setting in one response. Drill
in with, for example:

- `GET /data/v2/domain/Room` / `…/Room/{id}`
- `GET /data/v2/domain/Device` / `…/Device/{id}`
- `GET /data/v2/domain/System`
- `GET /data/v2/domain/Schedule`
- `GET /data/network/Station/ConnectionStatus`

Writes are `PATCH` to the same resource path. The request-style actions the
swagger lists as RPC calls (e.g. `System/RequestReboot`,
`Room/{id}/RequestOverride`, `HotWater/{id}/RequestOverride`) are sent to the
same `/data/v2/domain/…` path — match the method the swagger shows for each.

## Not reachable over the LAN

These exist in the swagger but only under the internal `/db` tree, so they can't
be called remotely on a stock hub: `GET /db` (full raw dump), `System/FactoryReset`,
`System/PrivacyReset`, the raw `NET/wifi/call/Connect` / `StartApMode` /
`FactoryReset`, `api/cloud/publishEvent`, Modbus discovery/log, and the Matter
join internals. Firmware update is separate again — it's a `PATCH /ota` on the
hub's setup access point (port 8080), not part of either tree above.

## Confirmed from the hub firmware

The hub app is a stripped 32-bit ARM binary (`/usr/bin/lmp/WiserHomeLinux`,
musl libc). Static inspection of it confirms and adds a few things:

- **The `/db` names come from the embedded database.** The hub uses an embedded
  C++ JSON database library ("jude"); the Swagger spec describes that database's
  schema, rooted at `/db`. The live HTTP binding is `/data`. There is no `/db`
  HTTP route, so the app's own router answers `/db/…` with **502 Bad Gateway**
  (that string is baked into the binary — the 502 is the hub app itself, not a
  separate proxy).
- **Swagger UI is served at `/swagger`** (it pulls the swagger-ui assets from a
  CDN, so the page needs internet to render, but the spec is generated locally).
  `GET /swagger` returns the spec. You can pull it off any hub.
- **The version prefix is optional.** The route pattern is
  `/data/(v[0-9]+/)?domain/…`, and the hub's own built-in web UI calls
  `data/domain/…` with no `/v2`. So both `/data/domain/…` and
  `/data/v2/domain/…` work; `/v2` just pins the schema version.
- **Reboot, concretely:** `PATCH /data/domain/System` with body
  `{"RequestReboot": true}` (taken verbatim from the hub's built-in UI).
- **Moments are created at `/data/Moment`, not `/data/domain/Moment`.** The
  binary explicitly rejects creation via the domain path
  ("use /Moment NOT /domain/Moment"). Reads/edits of existing Moments are under
  `/data/v2/domain/Moment`.
- **Other endpoints seen in the firmware** (not all verified live, handle with
  care): `/newSecret` (rotates the hub secret), `/secret` (read it, setup-mode),
  `/data/private/log/wiser-home.txt` and `/data/private/.wiserhome.history`
  (device log and history), `/data/shared/ota/ota-set` (OTA image selection).
  Treat `/newSecret` as destructive — rotating the secret would lock out every
  existing client, this app included.

## Events and MQTT (AsyncAPI)

The hub also publishes an AsyncAPI 2.6 spec ("Wiser Home Async API") describing
its internal **MQTT bus**, broker `mqtt://mqtt:{port}` (1883 plain / 8883 TLS).
The broker host is an internal service name, so it's a bus between the hub's own
services and the cloud bridge — not meant to be the client-facing API. Topics
are rooted at `WHC/v2/…` (WHC = Wiser Home Controller) and use the same
`dbPath` addressing as everything else. Three families:

- **`WHC/v2/REQ/{GET|PATCH|POST|DELETE}/{dbPath}`** — request/response. The
  request must set a `CorrelationData` header and a `responseTopic`; the reply
  comes back on `{responseTopic}/REQ/{verb}/{path}`. This is the MQTT twin of
  the `/data` REST calls.
- **`WHC/v2/CMD/…`** — RPC commands, reply on `{responseTopic}/RSP/…`. Includes
  `api/cloud/publishEvent` ("opaquely publishes an event to the cloud") — which
  is exactly the mechanism this app's experimental away-from-home control rides
  on — plus internal/destructive ones (`cache/NET/call/FactoryReset`,
  `matter/Join/StartJoin`, `network/AccessPoint/ResetIdleTimeout`,
  `services/API/MOD/DiscoveryRTU`, `rus/syncFilesystemWithMatrix`).
- **`WHC/v2/DATA/{dbPath}`** — change events. This is the engine behind the
  app's live updates: whenever a resource changes the hub publishes a **partial
  JSON** payload for that path. **Semantics to respect:** it's a patch over the
  last value, and **a field set to `null` means that field was removed** (not
  "set to null"). Event roots seen: `domain`, `schedules`, `network`, `cache`,
  `openTherm`, `weather`, `status`, `services`, `featureflags`,
  `seasonalComfort`, `matter`, `ocf`, `rus`.

The local HTTP SSE stream the app subscribes to is a bridge of these `DATA`
events. This app uses each event only as a signal to re-fetch the full state
(see `HubViewModel`), so it never applies the partial payloads and the
`null`-means-deleted rule doesn't bite it. The rule only matters if you choose
to merge the diffs instead of reloading.

Resources that show up here but aren't used by the app yet, and might be worth
surfacing: **`weather/Forecast/{id}`** (the hub holds a weather forecast, with a
schema), `status` / `services/Status` (hub health), and `featureflags`. Each
should be readable over REST at `/data/v2/<path>` (e.g.
`/data/v2/weather/Forecast`), though that's not yet verified live.

Note the AsyncAPI declares **no security scheme** on the broker. That's expected
for an internal bus, but it's worth confirming the broker isn't exposed on the
LAN — i.e. that ports 1883/8883 are closed on the hub's own IP — since a client
that could publish to `WHC/v2/CMD/…` would be unauthenticated.
