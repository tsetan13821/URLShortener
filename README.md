# URL Shortener

[![CI](https://github.com/tsetan13821/URLShortener/actions/workflows/ci.yml/badge.svg)](https://github.com/tsetan13821/URLShortener/actions/workflows/ci.yml)

Spring Boot 4.1 · Java 21 · Spring Data JPA · PostgreSQL · Redis · Maven

Short codes are the Base62 encoding (`[0-9A-Za-z]`) of auto-incremented PostgreSQL IDs:
ID `1` → `1`, `62` → `10`, `1,000,000` → `4C92`. Redirect lookups go through a Redis
cache-aside layer and fall back to PostgreSQL whenever Redis is unavailable.

## Prerequisites

| | Version used |
|---|---|
| JDK | 21+ (built and tested on 27) |
| Maven | 3.9+ (or the bundled `./mvnw`) |
| PostgreSQL | 18 (any recent version works) |
| Docker | for Redis via `compose.yaml` |

One-time database setup (as a PostgreSQL superuser):

```sql
CREATE ROLE url_shortener LOGIN PASSWORD 'url_shortener';
CREATE DATABASE url_shortener OWNER url_shortener;
CREATE DATABASE url_shortener_test OWNER url_shortener;  -- used by the tests
```

The table, sequence and index are created at startup by Flyway from
`src/main/resources/db/migration/` (see [Schema migrations](#schema-migrations)).

## Run

```bash
docker compose up -d        # Redis on localhost:6379
mvn spring-boot:run         # app on http://localhost:8080
```

## Web UI

Open **http://localhost:8080/** in a browser:

- paste a URL (the `https://` is optional) and press **Shorten**
- **Copy** or **Open** the short link; the target is shown underneath
- **Recent in this browser** lists your last 8 links (kept in `localStorage`, not on the server)
  with each link's **click count** (hover for when it was last clicked)
- dead links (`/zzzzz`, `/not-a-code`) show a readable "not found" page instead of JSON

It is plain HTML/CSS/JS served by Spring Boot from `src/main/resources/static/`: no build
step, no framework, same origin as the API (no CORS). It follows the system light/dark setting
and works down to phone width.

## API

### Create a short link: `POST /api/urls`

```bash
curl -i -X POST http://localhost:8080/api/urls \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://spring.io/projects/spring-boot"}'
```

```http
HTTP/1.1 201 Created
Location: http://localhost:8080/1
Content-Type: application/json

{"shortCode":"1","shortUrl":"http://localhost:8080/1",
 "originalUrl":"https://spring.io/projects/spring-boot","createdAt":"2026-11-05T06:03:51.277447Z"}
```

Rules for `url`: required, at most 2048 characters, absolute `http`/`https`, and the host must be
publicly reachable:

- a domain ending in a real top-level domain (checked offline against the IANA list via
  Apache Commons Validator): `example.com` ✓, `server.dash` ✗, `hjfktgdkyfluytliu` ✗
- or a public IP address: `8.8.8.8` ✓; `localhost`, `127.0.0.1`, `192.168.x.x`, `10.x.x.x`,
  `169.254.169.254` and other private/loopback ranges ✗ (a short link to them would only work
  on the creator's own machine or network)

No DNS lookup is done, so a well-formed domain that isn't registered (`no-such-site-xyz.com`)
is still accepted. Surrounding whitespace is trimmed. Submitting the same URL twice creates two codes.

**Rate limit:** each client IP can create 20 links per minute (configurable). Request 21 gets
`429 Too Many Requests` with a `Retry-After` header (seconds until the window ends); the web UI shows
"Try again in N seconds". Requests rejected by validation don't count. Redirects and stats are never
limited.

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 37
Content-Type: application/problem+json

{"title":"Too many requests","status":429,
 "detail":"You can create up to 20 links per minute. Try again in 37 seconds.",
 "limit":20,"windowSeconds":60,"retryAfterSeconds":37,"instance":"/api/urls"}
```

### Redirect: `GET /{shortCode}`

```bash
curl -i http://localhost:8080/1
# HTTP/1.1 302 Found
# Location: https://spring.io/projects/spring-boot
```

A trailing slash (`/1/`) redirects the same way. Each `GET` counts as a click; `HEAD` requests
(mostly link-preview bots and uptime checks) are not counted.

### Click stats: `GET /api/urls/{shortCode}/stats`

```bash
curl http://localhost:8080/api/urls/3/stats
```

```json
{"shortCode":"3","shortUrl":"http://localhost:8080/3","originalUrl":"https://github.com/tsetan13821/URLShortener",
 "createdAt":"2026-11-05T06:21:21.587259Z","clicks":11,"lastClickedAt":"2026-11-05T08:10:19.465158Z"}
```

`clicks` is always current (it includes clicks not yet written to PostgreSQL). `lastClickedAt` is
accurate to the flush interval (30 s by default) and `null` until the first flush after a click.
Unknown codes return 404.

### Errors

API clients get RFC 9457 problem details (`application/problem+json`). Requests that explicitly
accept `text/html` (browsers opening a link) get an HTML "not found" page for 404s instead;
same status code, so nothing changes for API clients or `curl`.

| Case | Status | Example body |
|---|---|---|
| Unknown short code | 404 | `{"title":"Short URL not found","status":404,"detail":"No URL found for short code 'abc'","shortCode":"abc","instance":"/abc"}` |
| Path that can't be a code (`/not-a-code`, > 11 chars) | 404 | Spring's standard "No static resource" problem detail |
| Invalid / missing URL | 400 | `{"title":"Invalid request","status":400,"errors":[{"field":"url","code":"HttpUrl","message":"must be a valid http or https URL with a public domain name"}]}` |
| Malformed JSON | 400 | |
| Wrong `Content-Type` | 415 | |
| Too many links created | 429 | See [Rate limit](#create-a-short-link-post-apiurls); has a `Retry-After` header |
| Anything unexpected | 500 | Generic message; details logged server-side only |

`errors[].code` is the violated constraint (`NotBlank`, `Size` or `HttpUrl`). It's stable,
so clients can map it to their own wording (the web UI does), while `message` is for humans.

## Configuration

Every setting has a local-dev default and can be overridden with an environment variable.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/url_shortener` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `url_shortener` / `url_shortener` | DB credentials |
| `DB_POOL_SIZE` | `10` | Hikari max pool size |
| `DB_MIGRATE` | `true` | Apply pending Flyway migrations at startup; `false` if migrations are run separately |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / *(none)* | Redis connection |
| `REDIS_CONNECT_TIMEOUT` / `REDIS_COMMAND_TIMEOUT` | `500ms` / `500ms` | Kept short so a slow Redis fails fast |
| `APP_BASE_URL` | `http://localhost:8080` | Prefix for returned short URLs |
| `CACHE_TTL` | `24h` | Redis TTL per mapping |
| `CACHE_KEY_PREFIX` | `url:` | Redis key namespace |
| `CACHE_FAILURE_BACKOFF` | `10s` | After a Redis error, skip Redis (cache and click counting) for this long |
| `CLICKS_PENDING_KEY` | `clicks:pending` | Redis hash holding clicks not yet written to PostgreSQL |
| `CLICKS_FLUSH_INTERVAL` | `30s` | How often pending clicks are written to PostgreSQL |
| `RATE_LIMIT_ENABLED` | `true` | Limit link creation per client IP |
| `RATE_LIMIT_CREATE` / `RATE_LIMIT_WINDOW` | `20` / `1m` | Links one client may create per window |
| `RATE_LIMIT_KEY_PREFIX` | `rl:create:` | Redis key namespace for the counters |
| `FORWARD_HEADERS_STRATEGY` | `none` | Set to `native` only behind a trusted reverse proxy, so the client IP is read from `X-Forwarded-For` |
| `SERVER_PORT` | `8080` | HTTP port |

## Design

```
controller/  UrlController            HTTP mapping: 201 create, 302 redirect, click stats
service/     UrlShortenerService      create (nextval -> Base62 -> INSERT), cache-aside resolve, stats
             UrlCacheService          Redis get/put with TTL; never throws
             ClickCounter             clicks -> Redis HINCRBY; scheduled batch flush to PostgreSQL
             RateLimiter              per-client create limit (Redis fixed window, fails open)
             RedisAvailability        shared "Redis is down, skip it for N seconds" backoff
             Base62                   encode/decode, overflow-safe
repository/  UrlMappingRepository     findByShortCode, nextId()
             ClickStatsRepository     batched click_count updates (JDBC)
entity/      UrlMapping               immutable; Persistable so save() is a single INSERT
dto/         CreateShortUrlRequest, ShortUrlResponse, @HttpUrl validator (real TLD / public IP)
exception/   GlobalExceptionHandler   ProblemDetail for API clients, HTML 404 page for browsers
config/      AppProperties, RedisConfig, ClockConfig

resources/static/   index.html, styles.css, app.js, favicon.svg   (web UI, served at /)
resources/pages/    not-found.html   (browser 404 page; not publicly served as a file)
```

**Create:** `nextval('url_mappings_id_seq')` → `Base62.encode(id)` → one `INSERT` with ID and code.
The service reserves the ID first so the code can be written in the same statement, which lets
`short_code` be `NOT NULL` with a unique index. Sequences never repeat a value, so concurrent
creates can't collide.

**Redirect (cache-aside):** Redis `GET url:<code>` → on miss, PostgreSQL `findByShortCode`
(unique index) → Redis `SET ... EX <ttl>`. Mappings never change, so no invalidation is needed.
Create does not write to the cache; the first redirect does.

**Redis failure handling:** Redis is treated as an optional accelerator:
- every Redis error is caught and treated as a cache miss, so the request is served from PostgreSQL;
- Lettuce is configured to *reject* commands while disconnected instead of queueing them, so
  an outage costs microseconds, not a timeout, per request;
- after a failure, Redis is skipped for `CACHE_FAILURE_BACKOFF`, which covers outages where
  connections hang rather than being refused and keeps the logs to one warning per window;
- the app starts and works with Redis down, and recovers on its own when it returns.

Try it: `docker compose stop redis`, redirects keep working; `docker compose start redis`, and
after the backoff the cache fills again.

**Click analytics without database writes on redirects:**
- a redirect records its click with `HINCRBY clicks:pending <code> 1` in Redis (atomic,
  sub-millisecond), so cached redirects still never touch PostgreSQL;
- every 30 s a scheduled job takes the whole hash *and deletes it* in one atomic Lua script, then
  writes all counts in a single JDBC batch (`click_count = click_count + n`). Clicks arriving
  mid-flush go into a fresh hash, and multiple app instances can never flush the same clicks twice;
- if PostgreSQL fails during a flush, the counts are put back into Redis for the next attempt;
- while Redis is unavailable, clicks are written to PostgreSQL directly, so none are lost.

Measured locally: 7,300 cached redirects/s with counting (7,900 without), and 20,200 clicks
cost **one** PostgreSQL transaction.

**Rate limiting link creation:**
- one Redis counter per client, `rl:create:<ip>`: a Lua script does `INCR` and, on the first
  request, `PEXPIRE <window>` in one step, so a counter can never be left without an expiry and the
  limit holds across any number of app instances;
- over the limit → 429 with `Retry-After` = the counter's remaining TTL. Refused attempts still
  count, so a client that keeps hammering stays blocked until the window ends;
- IPv6 clients are limited per /64 network, because one connection usually owns a whole /64 and
  could otherwise use a new address for every request;
- **fails open:** while Redis is down, creation is not limited, rather than refusing every user
  because the accelerator is missing;
- the client IP is the TCP connection's address. Behind a reverse proxy every request would come
  from the proxy, so set `FORWARD_HEADERS_STRATEGY=native` there. Never set it without a trusted
  proxy in front: anyone could then send a fake `X-Forwarded-For` to dodge the limit.

## Schema migrations

The schema is managed by [Flyway](https://documentation.red-gate.com/flyway). Each change is a
numbered SQL file in `src/main/resources/db/migration/`, applied in order at startup and recorded
in the `flyway_schema_history` table, so every database (local, test, CI, production) ends up
with the same schema.

| File | Change |
|---|---|
| `V1__create_url_mappings.sql` | `url_mappings` table, ID sequence, unique index on `short_code` |
| `V2__add_click_stats.sql` | `click_count` (default 0) and `last_clicked_at` columns |

- **Changing the schema:** add a new file (`V2__add_something.sql`). Never edit a migration that
  has already been applied; Flyway checks their checksums and refuses to start if one changed.
- **Databases created before Flyway** already contain the V1 schema. On first start Flyway records
  them as version 1 ("baseline") without running V1, so existing links are untouched.
- Hibernate runs with `ddl-auto=validate`: it never changes the schema, and startup fails if
  the entities and the migrated schema disagree.

## Tests

```bash
mvn test
```

Needs the PostgreSQL test database and Redis running (`docker compose up -d`). They use
`url_shortener_test` and `test:url:` Redis keys, never your dev data.

**CI:** `.github/workflows/ci.yml` runs the same suite on every push to `main` and every pull
request, against fresh PostgreSQL 18 and Redis 8 service containers. When a run fails, the
Surefire reports are attached to it as a downloadable artifact.

| Test | Covers |
|---|---|
| `Base62Test` | known values, round trips, `Long.MAX_VALUE`, invalid/overflowing input |
| `UrlNormalizerTest` | browser-accepted URLs (`\|`, `{}`, spaces, Unicode domains) cleaned up; valid ones untouched |
| `HttpUrlValidatorTest` | real domains / public IPs accepted; bare words, fake TLDs, localhost, private IPs, bad schemes rejected |
| `UrlMappingRepositoryTest` | real PostgreSQL: schema matches entity, sequence, unique index |
| `UrlShortenerServiceTest` | create flow, cache hit / miss / not-found (mocked) |
| `UrlCacheServiceTest` | TTL, failures swallowed, backoff window and recovery |
| `RateLimiterTest` | IPv4 / IPv6 /64 / IPv4-mapped client keys; `Retry-After` rounding |
| `UrlControllerTest` | status codes, validation + error codes, problem-detail format, route pattern, trailing slash, HTML vs JSON 404, 429 format |
| `UrlShortenerIntegrationTest` | full stack: create → redirect → Redis populated with TTL → served from cache; web UI files served |
| `ClickAnalyticsIntegrationTest` | clicks counted in Redis, flushed to PostgreSQL, stats current; 1,000 concurrent clicks with racing flushes counted exactly; HEAD / unknown codes not counted |
| `RateLimitIntegrationTest` | real Redis: limit + 1 refused with `Retry-After`, per-client allowance, redirects/stats unlimited, invalid URLs not counted, allowance back after the window |
| `RedisUnavailableIntegrationTest` | app starts and serves with Redis down; requests in the backoff window never wait on Redis; clicks written straight to PostgreSQL; rate limit fails open |
| `FlywayMigrationTest` | database is at the newest migration, none pending |

## Known trade-offs / next steps

- **Codes are sequential and guessable** (`1`, `2`, `3`…). Fine for public links; for private
  links, start the sequence higher or switch to random codes.
- **Unknown codes aren't cached**, so scanning random codes reaches PostgreSQL every time.
  Creation is rate limited, but redirects aren't (limiting them would also slow real visitors);
  add short-lived negative caching, or limit at the proxy, if scanning becomes a problem.
- **Fixed-window rate limit** allows a burst of up to 2× the limit across a window boundary
  (20 at 0:59, 20 more at 1:00). Fine for abuse protection; a sliding window would be exact.
- **Click counts can lose up to one flush interval** of clicks if Redis itself crashes (it runs
  without persistence). Enable Redis AOF persistence if exact counts matter. Counts also include
  bots that send `GET`; only `HEAD` requests are excluded.
- **Top-level-domain list is bundled** with Apache Commons Validator, so a newly created TLD is
  only recognised after upgrading that dependency (new TLDs are rare).
- **Redirect chains:** locally, the app's own short links are rejected because `localhost` is
  blocked. Once `APP_BASE_URL` is a public domain, shortening an existing short link is possible;
  block the app's own host in `HttpUrlValidator` if that matters.
