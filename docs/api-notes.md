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
