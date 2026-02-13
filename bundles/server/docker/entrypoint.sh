#!/bin/sh
set -e

DATA_DIR="/data"
SECRETS_FILE="$DATA_DIR/secrets.env"

mkdir -p "$DATA_DIR" /var/log /home/explorama /data/caddy /logs

# ---------------------------------------------------------------------------
# 1. Generate secrets on first run
# ---------------------------------------------------------------------------
if [ ! -f "$SECRETS_FILE" ]; then
    echo "First run — generating secrets..."
    CLIENT_ID=$(openssl rand -hex 16)
    CLIENT_SECRET=$(openssl rand -hex 32)
    COOKIE_SECRET=$(openssl rand -base64 24 | tr -d '\n')

    cat > "$SECRETS_FILE" <<EOF
CLIENT_ID=$CLIENT_ID
CLIENT_SECRET=$CLIENT_SECRET
COOKIE_SECRET=$COOKIE_SECRET
EOF
fi

. "$SECRETS_FILE"
export CLIENT_ID CLIENT_SECRET COOKIE_SECRET

# Defaults for the upstream dev server running on the host
export HOST_ADDR="${HOST_ADDR:-host.docker.internal}"
export HOST_FRONTEND_PORT="${HOST_FRONTEND_PORT:-8020}"

# ---------------------------------------------------------------------------
# 2. Write Casdoor app.conf (generated so the DB path points to the volume)
# ---------------------------------------------------------------------------
cat > /conf/app.conf <<EOF
appname = casdoor
httpport = 8000
runmode = prod
driverName = sqlite
dataSourceName = $DATA_DIR/casdoor.db
dbName = casdoor
showSql = false
origin = http://localhost:8000
initDataFile = /conf/init_data.json
initDataNewOnly = true
enableGzip = true
logPostOnly = true
EOF

# ---------------------------------------------------------------------------
# 3. Render init_data.json template (substitute CLIENT_ID / CLIENT_SECRET)
# ---------------------------------------------------------------------------
sed \
    -e "s|\${CLIENT_ID}|$CLIENT_ID|g" \
    -e "s|\${CLIENT_SECRET}|$CLIENT_SECRET|g" \
    < /conf/init_data.json.template \
    > /conf/init_data.json

# ---------------------------------------------------------------------------
# 4. Write oauth2-proxy config
# ---------------------------------------------------------------------------
cat > /etc/oauth2-proxy.cfg <<EOF
http_address = "0.0.0.0:4180"
provider = "oidc"
oidc_issuer_url = "http://localhost:8000"
redirect_url = "https://localhost/oauth2/callback"
client_id = "$CLIENT_ID"
client_secret = "$CLIENT_SECRET"
cookie_secret = "$COOKIE_SECRET"
cookie_secure = true
email_domains = ["*"]
upstreams = ["static://202"]
reverse_proxy = true
skip_provider_button = true
scope = "openid profile email"
EOF

# ---------------------------------------------------------------------------
# 5. Fix ownership and drop to unprivileged user
# ---------------------------------------------------------------------------
chown -R explorama:explorama /data /conf /var/log /var/run /home/explorama /logs

echo ""
echo "============================================"
echo "  Casdoor admin : http://localhost:8000"
echo "    login        : admin / 123"
echo "  Application   : https://localhost"
echo "    (proxies to ${HOST_ADDR}:${HOST_FRONTEND_PORT})"
echo "============================================"
echo ""

exec supervisord -c /etc/supervisord.conf
