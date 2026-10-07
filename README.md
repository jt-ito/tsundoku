<h1><img src="docs/images/icon.svg" alt="" width="40" align="top"> tsundoku</h1>

A self-hosted manga reader server that runs [Mihon (Tachiyomi)](https://mihon.app/) extensions, with accounts, a first-run setup flow, a built-in PostgreSQL option and a faster in-app WebView.

**tsundoku is a fork of [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server).** It keeps Suwayomi's extension support, library, downloads, backups, trackers and APIs, and adds the features below. The web interface lives in [tsundoku-WebUI](https://github.com/jt-ito/tsundoku-WebUI), a fork of [Suwayomi-WebUI](https://github.com/Suwayomi/Suwayomi-WebUI).

> [!NOTE]
> This is an independent fork maintained by one person. It is not affiliated with the Suwayomi project, and Suwayomi's own support channels do not cover it. Please report problems here.

## Table of contents
- [What is different from Suwayomi](#what-is-different-from-suwayomi)
- [Getting started](#getting-started)
- [First run and logging in](#first-run-and-logging-in)
- [Security](#security)
- [Database: H2, built-in PostgreSQL and the migration page](#database-h2-built-in-postgresql-and-the-migration-page)
- [WebView](#webview)
- [Backups](#backups)
- [Configuration and Docker environment variables](#configuration-and-docker-environment-variables)
- [Moving over from Suwayomi](#moving-over-from-suwayomi)
- [Development](#development)
- [Credit and license](#credit-and-license)

## What is different from Suwayomi

| Area | Suwayomi | tsundoku |
| --- | --- | --- |
| Authentication | Off by default, one shared login | **On by default** (`ui_login`), real accounts, first-run setup page |
| Users | Single user | **Multiple accounts** (admin and member), each with their own library, categories, reading progress and trackers |
| Database | H2, or an external PostgreSQL you run yourself | H2 or a **built-in PostgreSQL** that the server starts, upgrades and protects for you |
| Switching databases | Manual | **Guided migration page** (`/database`) with an automatic backup, both directions |
| WebView | Pictures of a server-side Chromium over a websocket | **WebRTC video** with automatic fallback, ad blocking, phone friendly |
| Backups | Mihon-compatible | Also keeps **extensions and repositories**, installs missing extensions on restore, stays readable by the official server |
| Sessions | 60 day refresh token | 180 day refresh token, silently refreshed, so a phone stays logged in |
| Updates | Suwayomi releases | Checks **this fork's** releases |

### Accounts
- Admins and members, created and managed from the WebUI (profile card in the sidebar).
- Library entries, categories, read progress, tracker bindings and tracker logins (AniList, MAL, ...) are per account, and so are the WebUI settings (theme, library layout, ...), the per-manga reader settings and the source settings (pinned sources, saved searches). A new account starts completely empty. Existing single-user data moves to the first admin automatically, so updating the server or the Docker image loses nothing.
- A backup holds the data of the account that creates it, and a restore only writes into the account that runs it, so restoring an official Suwayomi backup as one account never touches another. Server settings and extensions belong to the whole server and are only part of an admin's backups and restores. The automatic backups (and sync) belong to the first account.
- Admins can also create a **server backup** (Backup page, a `.zip`): one backup per account, the server settings and extensions, and the accounts themselves. Restoring it brings every account back and recreates the missing ones with their original logins, existing accounts keep their password. The file contains the password hashes, so keep it private.
- A user's role and existence are read from the database on every token check and refresh, so demoting or deleting an account takes effect immediately rather than when the token expires.

## Getting started

Download an archive from the [releases page](https://github.com/jt-ito/tsundoku/releases), unpack it and start the launcher:

| Platform | Archive | Start |
| --- | --- | --- |
| Windows (x64) | `tsundoku-<version>-windows-x64.zip` | `tsundoku.bat` |
| macOS (Apple silicon) | `tsundoku-<version>-macOS-arm64.tar.gz` | `tsundoku.command` |
| macOS (Intel) | `tsundoku-<version>-macOS-x64.tar.gz` | `tsundoku.command` |
| Linux (x64) | `tsundoku-<version>-linux-x64.tar.gz` | `tsundoku.sh` |

Each archive brings its own Java runtime and the tsundoku interface, so nothing else needs installing. The archives are not signed: Windows SmartScreen and macOS Gatekeeper ask for confirmation the first time. There is also a plain `tsundoku-<version>.jar` (needs JDK 21), and a [Docker image](#docker). Then open `http://localhost:4567`. Installers (.msi, AppImage, .deb) are not offered yet.

The server checks the releases page for updates and treats "no release yet" as "up to date".

To build from source you need JDK 21:

```bash
./gradlew :server:run          # run from source, serves on http://localhost:4567
./gradlew :server:shadowJar    # or build a runnable jar (server/build)
```

### Docker

```bash
docker run -d --name tsundoku -p 4567:4567 -v tsundoku-data:/data jteaito/tsundoku:latest
```

Or use the [docker-compose.yml](docker-compose.yml). Images: `jteaito/tsundoku` on [Docker Hub](https://hub.docker.com/r/jteaito/tsundoku) and `ghcr.io/jt-ito/tsundoku`.

**Volumes**

| Path | What lives there |
| --- | --- |
| `/data` | everything the server keeps: database, settings, extensions, backups, logs |
| `/data/downloads` | downloaded chapters; mount a host folder here to keep them elsewhere |

**Common options** (all environment variables, [full list below](#configuration-and-docker-environment-variables))

| Variable | Purpose |
| --- | --- |
| `PUID`, `PGID` | user and group the server runs as (default `1000:1000`), for files on a shared disk |
| `TZ` | time zone, for example `Europe/Berlin` |
| `AUTH_USERNAME`, `AUTH_PASSWORD` | create the admin account on first start and skip the `/setup` page |
| `DATABASE` | `h2` (default), `postgres` (built in) or `external`, see [Database](#database-h2-built-in-postgresql-and-the-migration-page) |
| `FLARESOLVERR_ENABLED`, `FLARESOLVERR_URL` | use a FlareSolverr you run yourself |
| `KCEF_ENABLED` | `false` turns the WebView off if it does not start on your host |

**Good to know**
- The image runs as an unprivileged user and hands the two folders above to it on start, so a host folder needs no `chown`.
- It bundles [tsundoku-WebUI](https://github.com/jt-ito/tsundoku-WebUI), refreshed on every start, so updating the image updates the interface.

**Build it yourself**

```bash
docker build -t tsundoku .
```

This needs the `.git` folder (the version number is the commit count). The WebUI comes from the `master` branch of tsundoku-WebUI; use `--build-arg WEBUI_REPO=...` and `--build-arg WEBUI_REF=...` to build another one.

**Publishing** is done by `.github/workflows/docker_publish.yml`: a `v*` tag publishes `:latest` and the version, a push to `master` publishes `:edge`.

### Data folder

Data lives in the same folder as Suwayomi's (`%LOCALAPPDATA%\Tachidesk` on Windows, `~/.local/share/Tachidesk` on Linux, `~/Library/Application Support/Tachidesk` on macOS), so an existing library is picked up.

## First run and logging in

Authentication is on by default, so there has to be a way to create the first admin without shipping a default password. There isn't one:

- A fresh install has **no usable account**. The first visit redirects to `/setup`, where you choose the admin username and password (8 characters or more).
- Opened **from the machine the server runs on**, the setup page asks for nothing else.
- Opened from **anywhere else** (another device, a domain, a reverse proxy, Docker), it asks for a one-time **setup code** that the server prints to its log on startup. This stops a stranger who finds the port first from claiming the server.
- To skip the page entirely, set `AUTH_USERNAME` and `AUTH_PASSWORD` (see [below](#configuration-and-docker-environment-variables)). The admin account is created from them at startup, once.
- To run **without** authentication, set `AUTH_MODE=none` (or `server.authMode = "none"`). The server logs a warning on every start while auth is off, because anyone who can reach it then has full admin access. If you turn authentication on later, `/setup` opens.

Existing installs keep whatever `authMode` their `server.conf` already says.

## Security

What was changed beyond turning authentication on by default. This is hardening work by the fork's maintainer plus a security review of the changes; it has not had an independent audit.

- **No default credentials.** The first account is created by the owner through `/setup` or from configuration. The migration creates the account row with an empty password hash that cannot log in.
- **Setup page protections.** The setup code is compared in constant time. The code is skipped only for a request that provably comes from the machine's own browser: loopback peer, loopback `Host`, no proxy headers and a same-origin fetch. That blocks a cross-site form post or DNS rebinding from creating the admin. Any other request, including your own domain, only has to enter the code, so no domain is ever refused.
- **Output and script safety.** Values are escaped by the template engine, the setup page ships a Content-Security-Policy with a per-response nonce, `X-Frame-Options: DENY`, `nosniff` and no-referrer, and the old login page no longer follows `javascript:` or `//host` redirects.
- **Tokens.** Roles and account existence come from the database, not the token. Access tokens last 5 minutes and are refreshed by the WebUI; the refresh token lasts 180 days. Both lifetimes are configurable.
- **Built-in PostgreSQL** is protected by a randomly generated password (see below).
- **Tests** run against an isolated data folder and never touch your real configuration.

Known limits: CORS reflects the caller's origin, which is needed so a WebUI served from another domain can reach the API, and which only matters while `authMode` is `none`. Refresh tokens are not revoked when a password changes; deleting the account does revoke them. If you expose the server to the internet, keep authentication on and put it behind HTTPS.

## Database: H2, built-in PostgreSQL and the migration page

The default is the embedded **H2** file database, as in Suwayomi. tsundoku can also run its own **PostgreSQL**, with no separate install:

- **Built-in PostgreSQL.** The PostgreSQL binaries ship inside the server, so there is nothing extra to install. It starts PostgreSQL on a free local port, keeps its data in the data folder, protects it with a random password (stored next to the data, not in `server.conf`) and stops it with the server.
- **Automatic major-version upgrades.** When a new server release bundles a newer PostgreSQL, the old data is upgraded in a staging copy and a backup is kept. If anything fails, the original data is left untouched.
- **Migration page.** Open `/database` (also linked from Settings > Server > Database). It shows which engine is active and walks you through switching **from H2 to PostgreSQL and back**, with a confirmation dialog and an automatic backup first.
- **External PostgreSQL** works as before: set `DATABASE_TYPE=POSTGRESQL` and the `DATABASE_*` variables.
> [!WARNING]
> **Choose the built-in PostgreSQL on a new data folder.** It starts empty. Switching an existing H2 library over with `DATABASE=postgres` (or `DATABASE_TYPE=POSTGRESQL` with `USE_EMBEDDED_POSTGRES=true`) would **show an empty library**. Nothing stops it; only the `DATABASE=postgres` shortcut prints a warning in the container log, the two explicit variables do not. Your H2 file is not deleted, so removing those variables brings the library back. To move an existing library, start on H2, open `/database` and migrate there.

- **Choosing the engine in Docker.** `DATABASE=h2` (the default), `DATABASE=postgres` (the built-in PostgreSQL) or `DATABASE=external` picks the engine from the first start, so a new install never has to be migrated. It only fills in `DATABASE_TYPE` and `USE_EMBEDDED_POSTGRES` when you have not set them. Changing it later does not move data: if the volume already holds a library in the other engine the container prints a warning (the new engine would start empty), and the migration page moves the data.

PostgreSQL is a good choice for larger libraries. H2 is simpler and fine for a personal library.

## WebView

The WebView opens a page in a Chromium that runs on the server (via KCEF), for sources that need a real browser to pass a challenge. In tsundoku it:

- streams the page as **WebRTC video** instead of JPEG pictures, with the old JPEG stream as an automatic fallback;
- uses **less CPU** while idle;
- blocks known ad and tracker domains (switchable in settings);
- works on **phones**, and is themed with the app;
- exposes `GET /api/v1/webview/user-agent` and `POST /api/v1/webview/cookies` so a native client can use the same user agent and import cookies.

Clients on other networks may need STUN/TURN servers: set `TSUNDOKU_WEBRTC_ICE_SERVERS` to a comma-separated list of URLs. KCEF is not supported on macOS.

## Backups

Mihon-compatible backups, plus:

- an option to include **extensions and repositories**;
- **missing extensions are installed automatically** while restoring;
- backups stay **readable by the official Suwayomi server**;
- the WebUI keeps you logged in after a restore and applies the restored theme without a reload.

## Configuration and Docker environment variables

Settings live in `server.conf` in the data folder and in Settings > Server. `-D` overrides (`-Dsuwayomi.tachidesk.config.server.<setting>=...`) still work.

For containers, environment variables set the matching setting and **win over `server.conf`**. Empty values are ignored; lists and maps use JSON-style syntax, for example `EXTENSION_STORES=["https://example.com/index.min.json"]`.

| Group | Variables |
| --- | --- |
| Network | `BIND_IP`, `BIND_PORT`, `TZ` (read by the JVM) |
| SOCKS proxy | `SOCKS_PROXY_ENABLED`, `SOCKS_PROXY_VERSION`, `SOCKS_PROXY_HOST`, `SOCKS_PROXY_PORT`, `SOCKS_PROXY_USERNAME`, `SOCKS_PROXY_PASSWORD` |
| Authentication | `AUTH_MODE`, `AUTH_USERNAME`, `AUTH_PASSWORD`, `JWT_AUDIENCE`, `JWT_TOKEN_EXPIRY`, `JWT_REFRESH_EXPIRY` |
| Logging | `DEBUG`, `MAX_LOG_FILES`, `MAX_LOG_FILE_SIZE`, `MAX_LOG_FOLDER_SIZE` |
| Web interface | `WEB_UI_ENABLED`, `WEB_UI_FLAVOR`, `WEB_UI_CHANNEL`, `WEB_UI_UPDATE_INTERVAL` |
| Downloads and sources | `DOWNLOAD_AS_CBZ`, `DOWNLOAD_CONVERSIONS`, `EXTENSION_STORES`, `MAX_SOURCES_IN_PARALLEL` |
| Updates and backups | `UPDATE_INTERVAL`, `BACKUP_TIME`, `BACKUP_INTERVAL`, `BACKUP_TTL`, `AUTO_BACKUP_INCLUDE_MANGA`, `AUTO_BACKUP_INCLUDE_CATEGORIES`, `AUTO_BACKUP_INCLUDE_CHAPTERS`, `AUTO_BACKUP_INCLUDE_TRACKING`, `AUTO_BACKUP_INCLUDE_HISTORY`, `AUTO_BACKUP_INCLUDE_CLIENT_DATA`, `AUTO_BACKUP_INCLUDE_SERVER_SETTINGS` |
| FlareSolverr | `FLARESOLVERR_ENABLED`, `FLARESOLVERR_URL`, `FLARESOLVERR_TIMEOUT`, `FLARESOLVERR_SESSION_NAME`, `FLARESOLVERR_SESSION_TTL`, `FLARESOLVERR_RESPONSE_AS_FALLBACK` |
| Database | `DATABASE` (Docker only: `h2`, `postgres`, `external`), `DATABASE_TYPE`, `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `USE_EMBEDDED_POSTGRES`, `USE_HIKARI_CONNECTION_POOL` |
| WebView | `KCEF_ENABLED` |

> [!NOTE]
> Coming from Suwayomi's Docker image? The image, the volumes and a few variables have to change, see [Moving over from Suwayomi](#moving-over-from-suwayomi).

A complete setup with a login, FlareSolverr (which you run yourself) and the built-in PostgreSQL:

```yaml
services:
  tsundoku:
    image: jteaito/tsundoku:latest
    container_name: tsundoku
    restart: unless-stopped
    ports:
      - "4567:4567"
    volumes:
      - tsundoku-data:/data                      # database, settings, extensions, backups, logs
      - tsundoku-downloads:/data/downloads       # or a host folder, for example /mnt/manga:/data/downloads
    environment:
      - TZ=Etc/UTC
      - PUID=1000                                # the user and group the server runs as
      - PGID=1000
      # Login: the admin account is created from these on first start, so there is no /setup page.
      - AUTH_MODE=ui_login                       # none | basic_auth | simple_login | ui_login
      - AUTH_USERNAME=owner
      - AUTH_PASSWORD=change-me-please           # at least 8 characters
      # Database: the PostgreSQL that is built into the image, used from the very first start.
      # Only for a NEW data folder: on a folder that already holds an H2 library this starts an EMPTY database
      # (migrate that one on the /database page instead). DATABASE=postgres is the short form of these two lines.
      - DATABASE_TYPE=POSTGRESQL
      - USE_EMBEDDED_POSTGRES=true
      # FlareSolverr: a separate service you run yourself
      - FLARESOLVERR_ENABLED=true
      - FLARESOLVERR_URL=http://flaresolverr:8191
volumes:
  tsundoku-data:
  tsundoku-downloads:
```

Without `AUTH_USERNAME` and `AUTH_PASSWORD` the server shows the `/setup` page instead. `AUTH_MODE=none` turns the login off, which is only sensible on a network you trust.

**Differences from Suwayomi's Docker image**
- `AUTH_MODE` defaults to `ui_login`.
- `JWT_REFRESH_EXPIRY` defaults to `180d`.
- The image sets `WEB_UI_FLAVOR=CUSTOM`, so it serves its bundled tsundoku-WebUI.

The complete reference is in [docs/Configuring-Suwayomi‐Server.md](docs/Configuring-Suwayomi‐Server.md).

**Behind a reverse proxy** (any domain works):
- pass WebSocket upgrades through;
- send `X-Forwarded-For` and `X-Forwarded-Proto`;
- terminate HTTPS at the proxy;
- keep its access logs private, since the WebUI loads some images with the token in the URL.

## Moving over from Suwayomi

### Recommended: start fresh and import a backup

1. In Suwayomi, create a backup (Settings > Backup).
2. Start tsundoku with its **own new, empty data folder** (the [compose example](#configuration-and-docker-environment-variables) above). Do not point it at Suwayomi's folders.
3. Create the admin account with `AUTH_USERNAME` and `AUTH_PASSWORD`, or on the `/setup` page. A new folder is also the moment to choose the built-in PostgreSQL (`DATABASE=postgres`) if you want it; it cannot be added to an existing H2 folder this way.
4. In tsundoku, open Settings > Backup and restore the file. Your library, categories, chapters, reading progress and trackers come back, and missing extensions are installed during the restore.

This leaves your Suwayomi folder untouched, so you can always go back. There is no database upgrade to undo, and none of Suwayomi's old settings are carried over. Suwayomi's own backups can be restored here, and tsundoku's backups can be restored there.

### Or keep using the existing data folder

This also works and keeps everything as it is, but the first start upgrades the database for accounts, which cannot be undone.

> [!IMPORTANT]
> **Back up your data folder before the first start.**

Switching only the image is **not enough**. These settings of your existing compose file have to change:

| Setting | Suwayomi | tsundoku |
| --- | --- | --- |
| `image` | the Suwayomi image you use now | `jteaito/tsundoku:latest` |
| Data volume | `<your folder>:/home/suwayomi/.local/share/Tachidesk` | `<your folder>:/data` |
| Downloads volume | `<your downloads>:/home/suwayomi/.local/share/Tachidesk/downloads` | `<your downloads>:/data/downloads` |
| Volume order | downloads **first** | does not matter |
| `WEBUI_FLAVOR` | sometimes set | remove it; the image serves the tsundoku-WebUI by itself |

Keep the same host folders on the left of each volume line, so your library and downloads are picked up. Stay on H2 for now: do not add `DATABASE=postgres` (or the two PostgreSQL variables) to this folder: it would start an empty PostgreSQL and your library would look gone. To switch to PostgreSQL later, open `/database` and migrate. Everything else carries over: `TZ`, `PUID`, `PGID`, `AUTH_MODE`, `AUTH_USERNAME`, `AUTH_PASSWORD`, `FLARESOLVERR_ENABLED`, `FLARESOLVERR_URL`, `network_mode`, `depends_on` and the other variables work with the same names.

```yaml
services:
  tsundoku:
    image: jteaito/tsundoku:latest                  # was the Suwayomi image
    volumes:
      - /your/downloads:/data/downloads             # was .../Tachidesk/downloads
      - /your/suwayomi/data:/data                   # was .../.local/share/Tachidesk
    environment:
      - AUTH_USERNAME=owner                         # at least 8 characters for the password
      - AUTH_PASSWORD=change-me-please
```

After the first start check `docker logs`: it should say that the admin account was created, and the server should show the login page. Without `AUTH_USERNAME` and `AUTH_PASSWORD` it shows `/setup` instead.

### Coming from a bare install

Restore a backup into a fresh install (recommended), or point tsundoku at the same data folder (it is the same location, see [Data folder](#data-folder)). Set `AUTH_USERNAME` and `AUTH_PASSWORD`, or use `/setup`, before you expose the server.

## Development

```bash
./gradlew :server:compileKotlin   # compile
./gradlew :server:test            # tests; they use an isolated data folder
./gradlew :server:ktlintCheck     # style
./gradlew :server:run             # run on :4567 (restart after Kotlin or .kte changes)
```

The server is Kotlin on JDK 21 with Javalin, GraphQL (graphql-kotlin), Exposed, JTE templates for the server-rendered pages (`/setup`, `/database`, the WebView page, `login.html`), and a bundled web interface from [tsundoku-WebUI](https://github.com/jt-ito/tsundoku-WebUI). See [CONTRIBUTING.md](CONTRIBUTING.md). Settings reference pages are in [docs/](docs/).

## Credit and license

tsundoku exists because of the [Suwayomi](https://github.com/Suwayomi) project and its contributors, whose server this is forked from. Suwayomi in turn is a spiritual successor of [TachiWeb-Server](https://github.com/Tachiweb/TachiWeb-server). The `AndroidCompat` module was originally developed by [@null-dev](https://github.com/null-dev) for TachiWeb-Server and parts of [Mihon (Tachiyomi)](https://github.com/mihonapp/mihon) are adopted into this codebase; both are licensed under the [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0) (`Copyright 2015 Javier Tomás` for Mihon). Changes to both are licensed under MPL 2.0 like the rest of the project.

    Copyright (C) Contributors to the Suwayomi project

    This Source Code Form is subject to the terms of the Mozilla Public
    License, v. 2.0. If a copy of the MPL was not distributed with this
    file, You can obtain one at http://mozilla.org/MPL/2.0/.

## Disclaimer

The developer of this application does not have any affiliation with the content providers available.
