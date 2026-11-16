# Region package server

The server behind the app's map downloads. The default is `grassmaps.r1a.nl`.
Anyone can run the same server and type its URL into the app (Custom map
download server). It is one small Python service with no dependencies beyond the
standard library, packed as a container. Design: `design/server-protocol.md`
sections 1, 5 and 6 in the planning notes.

What the service does:

- serves the static layout below to `GET` and `HEAD`, with `Range` support
- checks a bearer token on every request, or runs open when tokens are off
- limits the request rate, the simultaneous downloads and the bytes per month
  for each key
- bans an IP for an hour after repeated failed token checks

It holds no TLS and publishes no port. It sits behind a reverse proxy.

## Served layout

```
<packs>/catalog.json
<packs>/outlines/<region>.geojson
<packs>/<region>/<version>/package.zip
<packs>/<region>/<version>/manifest.json
```

These are the only paths the client requests: `catalog.json` and
`<region>/<version>/package.zip`. `manifest.json` and the outlines are served
for completeness. Every other path is 404. A version directory never changes
after it is published.

`package.zip` is the archive the app downloads, resumes with `Range`, extracts
and checks against the manifest. The pipeline writes a directory per version,
so `publish-packs.py` builds the archive from it.

## Configuration

All settings are environment variables. Put them in `.env` (see `.env.example`).

| Variable | Default | Meaning |
|---|---|---|
| `GM_REQUIRE_TOKENS` | `1` | `1`: a request needs a valid token. `0`: open server |
| `GM_ANON_KEY` | `ip` | With tokens off, the limit key: `ip` (per client) or `global` (one shared bucket) |
| `GM_RATE_PER_MINUTE` | `60` | Requests per key per 60 s. `0` disables the limit |
| `GM_MAX_CONCURRENT` | `4` | Simultaneous responses per key. `0` disables |
| `GM_QUOTA_GIB` | `20` | Bytes per key per UTC month. `0` disables |
| `GM_BAN_FAILURES` | `20` | Failed token checks from one IP in `GM_BAN_WINDOW_S` that ban it. `0` disables |
| `GM_BAN_WINDOW_S` | `600` | Window of that count |
| `GM_BAN_SECONDS` | `3600` | Length of a ban |
| `GM_TRUST_FORWARDED` | `1` in compose, `0` without | Take the client IP from the last `X-Forwarded-For` entry. With no header, the socket address is used. Set it in `.env` to change it |
| `GM_MAX_CONNECTIONS` | `256` | Open connections. More get `503` with `Retry-After` |
| `GM_HEADER_DEADLINE_S` | `10` | Wall-clock time a client has to send its request headers |
| `GM_PACKS_HOST_DIR` | `./packs` | Host directory mounted read-only at `/packs` |
| `GM_UID`, `GM_GID` | `10001` | User of the container process. It must own `config/` and `state/` |

The rate, concurrency and quota numbers are chosen, not derived. Adjust them
from the logs. Quota and rate limit use one key per client:

- tokens on: the token. Each token may carry its own quota (`--quota-gib`)
- tokens off: the client IP, or one shared bucket with `GM_ANON_KEY=global`

A request over a limit gets `429` with `Retry-After`: seconds to the end of the
window for the rate, five seconds for the concurrency cap, seconds to the start
of the next UTC month for the quota. A banned IP gets `429` for every path.
The app already honours `Retry-After`.

`GM_TRUST_FORWARDED=1` is only safe behind a proxy that sets `X-Forwarded-For`
itself. Caddy does. When the server is reachable directly, leave it at `0`:
a client could then pick its own key.

Any container on the shared `caddy` network can reach the service directly and
set `X-Forwarded-For` itself. This is accepted here: every container on that
network belongs to the owner.

IPv6 clients count by their `/64` prefix, IPv4 clients by the full address.
That applies to the ban list and to the tokens-off limit and quota key.

A client that reads its body slowly holds one concurrency slot of its key for
as long as it takes. The slots per key (`GM_MAX_CONCURRENT`) and the global
connection cap bound that. The 30 s socket timeout limits each single read or
write, not the whole response.

A token file that cannot be read or parsed makes every request answer `503`
and does not count as a failed login. The log names the file once per version.

With tokens on and no enabled token in the file, every request gets 401 and the
log says so at start.

## Tokens

A token is a random 256-bit value, shown once when made. The file keeps only its
SHA-256, a label, the creation date, an enabled flag and an optional quota. The
service reads the file again whenever it changes, so disabling a token takes
effect on the next request.

```sh
mkdir -p config state packs && cp .env.example .env
docker compose run --rm gm-server gm-token create --label "my phone"
docker compose exec gm-server gm-token list
docker compose exec gm-server gm-token disable <id>     # also: enable, delete
```

Without a container: `./gm-token --file ./config/tokens.json create --label x`.
Give the printed token to its user. In the app it goes into the token field of
the server setting. A wrong token on a server with tokens off is ignored.

The token file is never committed. `config/`, `state/`, `packs/` and `.env` are
in `.gitignore`.

## Publish packs

Build the packs with `tools/pipeline/build-oblasts.sh --out <dir>`, then:

```sh
server/publish-packs.py <dir> --dest server/packs
```

The script zips every `<region>/<version>/` directory (nested directories such
as routing tiles keep their folders) into `package.zip`, copies `manifest.json`,
the outlines and, last, `catalog.json`. A version that already has a
`package.zip` is skipped, so a re-run adds only what is new, and a client never
reads a catalog entry whose archive is missing. The server needs no restart.

`size_bytes` in the catalog is the unpacked size the pipeline measured, not the
size of `package.zip`.

## Run

```sh
cd server
docker compose up -d --build
docker compose logs -f gm-server
```

The compose file attaches the service to an external network named `caddy`
(`docker network create caddy` if it does not exist) and publishes no port. The
container runs read-only, without capabilities, as `GM_UID`, with `packs/`
mounted read-only. The service answers `GET /healthz` without a token for the
container health check.

Caddy block on the same network, for example:

```
maps.example.org {
    reverse_proxy grassmaps-server:8080
}
```

Caddy sets `X-Forwarded-For` itself and streams large responses without
buffering them. The service sends `Connection: close`, so Caddy opens one
upstream connection per request.

A container started without compose (`docker run`) needs the same mounts
(`/packs` read-only, `/config`, `/state`) and `GM_TRUST_FORWARDED=1` when it is
behind a proxy.

## Tests

```sh
python3 -m unittest discover -s server/tests -p '*_test.py'      # no container
GM_CONTAINER_TEST=1 python3 -m unittest discover -s server/tests -p 'container_test.py'
```

The first line starts the service in-process and checks the catalog, the package,
`Range` with `206` and `Content-Range`, `416`, `401` with a missing, wrong and
disabled token, the open mode, the rate, concurrency and quota limits, the ban
and the publisher. `./check.sh` runs it. The second builds the image and runs
the same requests against a container. It needs podman or docker and pulls the
Python base image. `RegionStoreSelfHostedServerTest` in `regions` starts the
service as a child process and downloads a region through the real client.
