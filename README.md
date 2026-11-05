# URL Shortener

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

The table, sequence and index are created at startup from `src/main/resources/schema.sql`.

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

Rules for `url`: required, absolute `http`/`https` with a host, at most 2048 characters.
Surrounding whitespace is trimmed. Submitting the same URL twice creates two codes.

### Redirect: `GET /{shortCode}`

```bash
curl -i http://localhost:8080/1
# HTTP/1.1 302 Found
# Location: https://spring.io/projects/spring-boot
```

A trailing slash (`/1/`) redirects the same way.

### Errors

API clients get RFC 9457 problem details (`application/problem+json`). Requests that explicitly
accept `text/html` (browsers opening a link) get an HTML "not found" page for 404s instead;
same status code, so nothing changes for API clients or `curl`.

| Case | Status | Example body |
|---|---|---|
| Unknown short code | 404 | `{"title":"Short URL not found","status":404,"detail":"No URL found for short code 'abc'","shortCode":"abc","instance":"/abc"}` |
| Path that can't be a code (`/not-a-code`, > 11 chars) | 404 | Spring's standard "No static resource" problem detail |
| Invalid / missing URL | 400 | `{"title":"Invalid request","status":400,"errors":[{"field":"url","code":"HttpUrl","message":"must be a valid http or https URL"}]}` |

`errors[].code` is the violated constraint (`NotBlank`, `Size` or `HttpUrl`). It's stable,
so clients can map it to their own wording (the web UI does), while `message` is for humans.
| Malformed JSON | 400 | |
| Wrong `Content-Type` | 415 | |
| Anything unexpected | 500 | Generic message; details logged server-side only |

## Configuration

Every setting has a local-dev default and can be overridden with an environment variable.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/url_shortener` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `url_shortener` / `url_shortener` | DB credentials |
| `DB_POOL_SIZE` | `10` | Hikari max pool size |
| `DB_SCHEMA_INIT` | `always` | Run `schema.sql` at startup; `never` if the schema is managed elsewhere |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / *(none)* | Redis connection |
| `REDIS_CONNECT_TIMEOUT` / `REDIS_COMMAND_TIMEOUT` | `500ms` / `500ms` | Kept short so a slow Redis fails fast |
| `APP_BASE_URL` | `http://localhost:8080` | Prefix for returned short URLs |
| `CACHE_TTL` | `24h` | Redis TTL per mapping |
| `CACHE_KEY_PREFIX` | `url:` | Redis key namespace |
| `CACHE_FAILURE_BACKOFF` | `10s` | After a Redis error, skip Redis for this long |
| `SERVER_PORT` | `8080` | HTTP port |

## Design

```
controller/  UrlController            HTTP mapping, 201 / 302
service/     UrlShortenerService      create (nextval -> Base62 -> INSERT) and cache-aside resolve
             UrlCacheService          Redis get/put with TTL; never throws; failure backoff
             Base62                   encode/decode, overflow-safe
repository/  UrlMappingRepository     findByShortCode, nextId()
entity/      UrlMapping               immutable; Persistable so save() is a single INSERT
dto/         CreateShortUrlRequest, ShortUrlResponse, @HttpUrl validator
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

## Tests

```bash
mvn test
```

Needs the PostgreSQL test database and Redis running (`docker compose up -d`). They use
`url_shortener_test` and `test:url:` Redis keys, never your dev data.

| Test | Covers |
|---|---|
| `Base62Test` | known values, round trips, `Long.MAX_VALUE`, invalid/overflowing input |
| `UrlMappingRepositoryTest` | real PostgreSQL: schema matches entity, sequence, unique index |
| `UrlShortenerServiceTest` | create flow, cache hit / miss / not-found (mocked) |
| `UrlCacheServiceTest` | TTL, failures swallowed, backoff window and recovery |
| `UrlControllerTest` | status codes, validation + error codes, problem-detail format, route pattern, trailing slash, HTML vs JSON 404 |
| `UrlShortenerIntegrationTest` | full stack: create → redirect → Redis populated with TTL → served from cache; web UI files served |
| `RedisUnavailableIntegrationTest` | app starts and serves with Redis down; requests in the backoff window never wait on Redis |

## Known trade-offs / next steps

- **Codes are sequential and guessable** (`1`, `2`, `3`…). Fine for public links; for private
  links, start the sequence higher or switch to random codes.
- **Unknown codes aren't cached**, so scanning random codes reaches PostgreSQL every time.
  Add short-lived negative caching or rate limiting if that becomes a problem.
- **Schema evolution:** `schema.sql` handles creation only; adopt Flyway before the schema changes.
- The app doesn't stop you shortening its own short URLs (redirect chains).
