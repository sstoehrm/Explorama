FROM caddy:2-alpine AS caddy
FROM quay.io/oauth2-proxy/oauth2-proxy:latest AS oauth2-proxy

FROM casbin/casdoor:latest

COPY --from=caddy /usr/bin/caddy /usr/local/bin/caddy
COPY --from=oauth2-proxy /bin/oauth2-proxy /usr/local/bin/oauth2-proxy

RUN apk add --no-cache supervisor openssl

COPY docker/entrypoint.sh /entrypoint.sh
COPY docker/supervisord.conf /etc/supervisord.conf
COPY docker/caddy/Caddyfile /etc/caddy/Caddyfile
COPY docker/casdoor/init_data.json.template /conf/init_data.json.template

RUN chmod +x /entrypoint.sh

EXPOSE 80 443 8000

VOLUME /data

ENTRYPOINT ["/entrypoint.sh"]
