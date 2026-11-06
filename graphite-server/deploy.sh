#!/usr/bin/env bash
# Deploy the adjacent Graphite server to a trusted SSH host.
set -euo pipefail

if (( $# < 4 || $# > 6 )); then
    echo "Usage: bash deploy.sh root@SSH_HOST VPN_DOMAIN FULLCHAIN_PATH PRIVATE_KEY_PATH [VPN_PORT=8443] [SSH_PORT=22]" >&2
    exit 2
fi

target=$1 domain=$2 cert=$3 key=$4 port=${5:-8443} ssh_port=${6:-22}
if [[ ! $target =~ ^root@[[:alnum:]][[:alnum:].:-]*$ ||
      ! $domain =~ ^[[:alnum:]][[:alnum:].-]*$ ||
      ! $cert =~ ^/[[:alnum:]_./-]+$ ||
      ! $key =~ ^/[[:alnum:]_./-]+$ ||
      ! $port =~ ^[0-9]{1,5}$ || ! $ssh_port =~ ^[0-9]{1,5}$ ]]; then
    echo 'Invalid SSH destination, VPN domain, port, or certificate path.' >&2
    exit 2
fi
if (( 10#$port < 1024 || 10#$port > 65535 || 10#$ssh_port < 1 || 10#$ssh_port > 65535 )); then
    echo 'VPN port must be 1024–65535 and SSH port 1–65535.' >&2
    exit 2
fi

cd -- "$(dirname -- "$0")"
payload=$(base64 < server.py | tr -d '\r\n')
{
    printf "GRAPHITE_SERVER_B64='%s'\n" "$payload"
    cat install-systemd.sh
} | ssh -T -o StrictHostKeyChecking=yes -o ConnectTimeout=10 -p "$ssh_port" "$target" \
    "bash -s -- '$domain' '$cert' '$key' '$((10#$port))'"
