#!/bin/sh
set -e

# Volumes and host folders are often created by root. Start as root only long enough to hand the two mount points to the
# unprivileged user, then run everything else (including the built-in PostgreSQL, which refuses to run as root) as that user.
# PUID and PGID (the convention of most self-hosting images, default 1000) pick the user id and group id it runs as,
# so files on a shared disk keep the owner you want.
if [ "$(id -u)" = 0 ]; then
    PUID="${PUID:-1000}"
    PGID="${PGID:-1000}"
    [ "$PGID" = "$(id -g tsundoku)" ] || groupmod -o -g "$PGID" tsundoku
    [ "$PUID" = "$(id -u tsundoku)" ] || usermod -o -u "$PUID" tsundoku
    for dir in /data /data/downloads /home/tsundoku; do
        mkdir -p "$dir"
        [ "$(stat -c %u:%g "$dir")" = "$PUID:$PGID" ] || chown "$PUID:$PGID" "$dir"
    done
    export HOME=/home/tsundoku
    exec setpriv --reuid="$PUID" --regid="$PGID" --init-groups "$0" "$@"
fi

# DATABASE picks the engine from the very first start, so nothing has to be migrated later:
#   h2        the embedded H2 file database (the default)
#   postgres  the PostgreSQL that is built into the server (kept in /data/postgres-data)
#   external  your own PostgreSQL: also set DATABASE_URL, DATABASE_USERNAME and DATABASE_PASSWORD
# It only fills in DATABASE_TYPE and USE_EMBEDDED_POSTGRES when they are not set explicitly.
case "$(printf '%s' "${DATABASE:-}" | tr 'A-Z' 'a-z')" in
    "") ;;
    h2)
        : "${DATABASE_TYPE:=H2}"
        export DATABASE_TYPE
        ;;
    postgres | embedded | builtin)
        : "${DATABASE_TYPE:=POSTGRESQL}"
        : "${USE_EMBEDDED_POSTGRES:=true}"
        export DATABASE_TYPE USE_EMBEDDED_POSTGRES
        if [ -f /data/database.mv.db ] && [ ! -d /data/postgres-data ]; then
            echo "WARNING: /data already holds an H2 library (database.mv.db) but DATABASE=postgres starts an empty PostgreSQL." >&2
            echo "WARNING: To keep that library, start with DATABASE=h2 and use the migration page (/database) to move it." >&2
        fi
        ;;
    external)
        : "${DATABASE_TYPE:=POSTGRESQL}"
        : "${USE_EMBEDDED_POSTGRES:=false}"
        export DATABASE_TYPE USE_EMBEDDED_POSTGRES
        ;;
    *)
        echo "ERROR: DATABASE must be h2, postgres or external (got \"$DATABASE\")." >&2
        exit 1
        ;;
esac
if [ "$(printf '%s' "${DATABASE_TYPE:-}" | tr 'a-z' 'A-Z')" = "H2" ] && [ -d /data/postgres-data ] && [ ! -f /data/database.mv.db ]; then
    echo "WARNING: /data holds a PostgreSQL library (postgres-data) but the H2 database is selected, which starts empty." >&2
    echo "WARNING: To keep that library, use DATABASE=postgres, or move it with the migration page (/database)." >&2
fi

# Chromium (the WebView) locks its profile with the hostname of the container that used it, and a recreated container has
# a new hostname, so the old lock would stop the WebView from starting. Only one container uses a data folder, so the
# lock files left behind are stale.
find /data/cache -maxdepth 4 \( -name SingletonLock -o -name SingletonCookie -o -name SingletonSocket \) -delete 2>/dev/null || true

# The built-in PostgreSQL leaves postmaster.pid behind when its container is killed. A new container starts counting
# process ids from scratch, so the old pid can belong to an unrelated process by then (here: a pid collision made
# PostgreSQL believe another server was running and the first start failed after 90 seconds). Nothing of this
# container has started yet, and only one container uses a data folder, so the file is stale.
rm -f /data/postgres-data/postmaster.pid 2>/dev/null || true

# The WebView's Chromium will not start without a display ("Missing X server or $DISPLAY"), and a container has none, so
# give it a virtual one. The lock files are left over when a stopped container is started again.
if [ "$(printf '%s' "${KCEF_ENABLED:-true}" | tr 'A-Z' 'a-z')" != "false" ] && command -v Xvfb >/dev/null 2>&1; then
    rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
    Xvfb :99 -screen 0 1280x800x24 -nolisten tcp >/dev/null 2>&1 &
    export DISPLAY=:99
    for _ in 1 2 3 4 5 6 7 8 9 10; do [ -S /tmp/.X11-unix/X99 ] && break; sleep 0.3; done
fi

# The WebUI that was built into the image replaces the copy in the data volume on every start, so an image update
# also updates the interface (the volume would otherwise keep serving an old one).
rm -rf /data/webUI
cp -r /opt/tsundoku/webUI /data/webUI

# Settings come from the environment variables listed in the README (AUTH_MODE, BIND_PORT, DATABASE_TYPE, ...).
# shellcheck disable=SC2086
exec java \
    -Djava.awt.headless=true \
    -Dsuwayomi.tachidesk.config.server.rootDir=/data \
    -Dsuwayomi.tachidesk.config.server.systemTrayEnabled=false \
    -Dsuwayomi.tachidesk.config.server.initialOpenInBrowserEnabled=false \
    $JAVA_OPTS \
    -jar /opt/tsundoku/tsundoku.jar
