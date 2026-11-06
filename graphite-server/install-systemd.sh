#!/usr/bin/env bash
# Invoked through deploy.sh. Certificate and key paths refer to files on the VPS.
set -euo pipefail
umask 077

domain=${1:?} cert=${2:?} key=${3:?} port=${4:?}
[[ $EUID == 0 ]] || { echo 'Root is required.' >&2; exit 1; }
[[ $domain =~ ^[[:alnum:]][[:alnum:].-]*$ &&
   $cert =~ ^/[[:alnum:]_./-]+$ && $key =~ ^/[[:alnum:]_./-]+$ &&
   $port =~ ^[1-9][0-9]{3,4}$ ]] && (( port >= 1024 && port <= 65535 )) ||
    { echo 'Invalid domain, certificate path, or VPN port.' >&2; exit 2; }
: "${GRAPHITE_SERVER_B64:?Use deploy.sh to supply server.py}"
systemctl show-environment >/dev/null
/usr/bin/python3 - "$cert" "$key" <<'PY'
import ssl
import sys

if sys.version_info < (3, 11):
    sys.exit("Graphite requires Python 3.11 or newer")
ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(sys.argv[1], sys.argv[2])
PY

name=graphite-tunnel.service
unit=/etc/systemd/system/$name
server=/usr/local/lib/graphite-tunnel/server.py
state=/etc/graphite-tunnel
install -d -m 0700 "$state"
exec 9>"$state/install.lock"
flock -x 9
if [[ -e $unit ]] && ! grep -Fxq '# Managed by Graphite self-hosted deploy' "$unit"; then
    echo "An unmanaged $unit already exists." >&2
    exit 1
fi
if [[ -e $server && ! -e $unit ]]; then
    echo "An unmanaged $server already exists." >&2
    exit 1
fi

stage=$(mktemp -d "$state/.install.XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
printf %s "$GRAPHITE_SERVER_B64" | base64 -d > "$stage/server.py"
unset GRAPHITE_SERVER_B64
/usr/bin/python3 -m py_compile "$stage/server.py"
cat > "$stage/$name" <<UNIT
# Managed by Graphite self-hosted deploy
[Unit]
Description=Graphite Tunnel
After=network.target

[Service]
DynamicUser=yes
LoadCredential=token:$state/token
LoadCredential=certificate:$cert
LoadCredential=private-key:$key
ExecStart=/usr/bin/python3 -I $server --listen 0.0.0.0 --port $port --cert \${CREDENTIALS_DIRECTORY}/certificate --key \${CREDENTIALS_DIRECTORY}/private-key --token-file \${CREDENTIALS_DIRECTORY}/token
Restart=on-failure
RestartSec=2
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
UMask=0077

[Install]
WantedBy=multi-user.target
UNIT

if [[ -e $unit ]]; then cp -p -- "$unit" "$stage/old.unit"; fi
if [[ -e $server ]]; then cp -p -- "$server" "$stage/old.server"; fi
was_active=false
if systemctl is-active --quiet "$name"; then was_active=true; fi
created_token=false
rollback() {
    status=$?
    trap - EXIT
    set +e
    systemctl stop "$name" >&2
    if [[ -f $stage/old.unit ]]; then install -m 0644 "$stage/old.unit" "$unit"; else rm -f -- "$unit"; fi
    if [[ -f $stage/old.server ]]; then install -m 0644 "$stage/old.server" "$server"; else rm -f -- "$server"; fi
    if $created_token; then rm -f -- "$state/token"; fi
    systemctl daemon-reload >&2
    if $was_active; then systemctl start "$name" >&2; fi
    rm -rf -- "$stage"
    echo 'Installation failed. Attempted to restore the previous Graphite service.' >&2
    exit "$status"
}
trap rollback EXIT
if [[ ! -f $state/token ]]; then
    created_token=true
    /usr/bin/python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > "$stage/token"
    install -m 0600 "$stage/token" "$state/token"
fi
install -d -m 0755 "$(dirname -- "$server")"
install -m 0644 "$stage/server.py" "$server"
install -m 0644 "$stage/$name" "$unit"
systemctl daemon-reload
systemctl restart "$name" >&2

/usr/bin/python3 - "$domain" "$port" "$state/token" <<'PY'
import base64
from pathlib import Path
import socket
import ssl
import sys
import time

domain, port, token_path = sys.argv[1:]
token = Path(token_path).read_text().strip()
raw = base64.urlsafe_b64decode(token + "=")
context = ssl.create_default_context()
context.minimum_version = ssl.TLSVersion.TLSv1_3
for attempt in range(12):
    try:
        with socket.create_connection(("127.0.0.1", int(port)), timeout=1) as connection:
            with context.wrap_socket(connection, server_hostname=domain) as tls:
                tls.sendall(b"GTP1\0" + raw)
                if tls.recv(1) != b"\0":
                    raise OSError("Graphite rejected the readiness probe")
        break
    except ssl.SSLCertVerificationError:
        raise
    except OSError:
        if attempt == 11:
            raise
        time.sleep(0.25)
PY

systemctl is-active --quiet "$name"
systemctl enable "$name" >&2
trap 'rm -rf -- "$stage"' EXIT
printf 'graphite://%s@%s:%s\n' "$(cat "$state/token")" "$domain" "$port"
