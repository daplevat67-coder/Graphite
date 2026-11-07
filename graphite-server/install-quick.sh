#!/usr/bin/env bash
# Automatic TLS bootstrap, invoked only over verified SSH.
set -euo pipefail
umask 077
host=${1:?} port=${2:?}
[[ $EUID == 0 ]] || { echo 'Root is required.' >&2; exit 1; }
[[ $host =~ ^[[:alnum:]][[:alnum:].-]{0,252}$ && $port =~ ^[1-9][0-9]{3,4}$ ]] &&
    (( port >= 1024 && port <= 65535 )) || { echo 'Invalid address or port.' >&2; exit 2; }
: "${GRAPHITE_SERVER_B64:?}"
: "${GRAPHITE_INSTALLER_B64:?}"
state=/etc/graphite-tunnel
install -d -m 0700 "$state"
exec 8>"$state/bootstrap.lock"
flock -x 8
stage=$(mktemp -d "$state/.bootstrap.XXXXXX")
created=false
installed=false
cleanup() {
    status=$?
    trap - EXIT
    if $created && ! $installed; then rm -rf -- "$state/auto-tls"; fi
    rm -rf -- "$stage"
    exit "$status"
}
trap cleanup EXIT
if [[ ! -e $state/auto-tls ]]; then
    mkdir "$stage/tls"
    san=$(/usr/bin/python3 - "$host" <<'PY'
import ipaddress
import sys
host = sys.argv[1]
try:
    print("IP:" + str(ipaddress.IPv4Address(host)))
except ValueError:
    print("DNS:" + host)
PY
)
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
        -days 3650 -subj "/CN=$host" -addext "subjectAltName=$san" \
        -keyout "$stage/tls/key.pem" -out "$stage/tls/cert.pem" >&2
    mv -T -- "$stage/tls" "$state/auto-tls"
    created=true
fi
cert=$state/auto-tls/cert.pem
key=$state/auto-tls/key.pem
/usr/bin/python3 - "$cert" "$key" <<'PY'
import ssl
import sys
if sys.version_info < (3, 11):
    sys.exit("Graphite requires Python 3.11+")
ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER).load_cert_chain(sys.argv[1], sys.argv[2])
PY
printf %s "$GRAPHITE_INSTALLER_B64" | base64 -d > "$stage/install.sh"
# Reuse the normal installer, including rollback and its local TLS/token probe.
export GRAPHITE_SERVER_B64
profile=$(SSL_CERT_FILE="$cert" bash "$stage/install.sh" "$host" "$cert" "$key" "$port")
installed=true
/usr/bin/python3 - "$profile" "$cert" "$host" "$port" <<'PY'
import hashlib
from pathlib import Path
import re
import ssl
import sys
from urllib.parse import urlsplit

profile, certificate, host, port = sys.argv[1:]
uri = urlsplit(profile)
assert uri.scheme == "graphite" and uri.hostname == host.lower() and uri.port == int(port)
assert re.fullmatch(r"[A-Za-z0-9_-]{43}", uri.username or "")
assert not uri.query and not uri.fragment and not uri.path
pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert(Path(certificate).read_text())).hexdigest()
print(profile + "?pin=" + pin)
PY
