#!/usr/bin/env python3
"""Graphite Tunnel 1, an authenticated TCP relay over TLS 1.3."""
import argparse
import asyncio
import base64
from collections import Counter
import hmac
import ipaddress
import socket
import ssl
import struct
from pathlib import Path

MAGIC = b"GTP1"
CHUNK = 16384
IDLE = 180
AUTH_TIMEOUT = 3
MAX_PENDING = 96
MAX_PENDING_PER_IP = 8
MAX_ACTIVE = 96
NAT64 = tuple(map(ipaddress.ip_network, ("64:ff9b::/96", "64:ff9b:1::/48")))


def allowed_destination(ip, allow_loopback):
    if ip.version == 6:
        if any(ip in prefix for prefix in NAT64):
            return False
        ip = ip.ipv4_mapped or ip
    return (ip.is_global and not ip.is_multicast) or (allow_loopback and ip.is_loopback)


async def dial(host, port, allow_loopback):
    loop = asyncio.get_running_loop()
    addresses = await loop.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    for _, _, _, _, address in addresses:
        ip = ipaddress.ip_address(address[0])
        if not allowed_destination(ip, allow_loopback):
            continue
        try:
            return await asyncio.wait_for(asyncio.open_connection(address[0], port), 4)
        except (OSError, TimeoutError):
            continue
    raise OSError("No allowed destination was reachable")


async def to_frames(reader, writer):
    while True:
        data = await asyncio.wait_for(reader.read(CHUNK), IDLE)
        writer.write(struct.pack("!H", len(data)) + data)
        await asyncio.wait_for(writer.drain(), IDLE)
        if not data:
            return


async def from_frames(reader, writer):
    while True:
        length, = struct.unpack("!H", await asyncio.wait_for(reader.readexactly(2), IDLE))
        if not length:
            writer.write_eof()
            return
        if length > CHUNK:
            raise ValueError("Frame too large")
        writer.write(await asyncio.wait_for(reader.readexactly(length), IDLE))
        await asyncio.wait_for(writer.drain(), IDLE)


async def relay(reader, writer, allow_loopback):
    upstream = None
    tasks = []
    try:
        port, length = struct.unpack("!HH", await asyncio.wait_for(reader.readexactly(4), 8))
        if port == 0 or not 1 <= length <= 253:
            return
        host = (await asyncio.wait_for(reader.readexactly(length), 8)).decode("ascii")
        if not all(c.isalnum() or c in ".-:" for c in host):
            return
        try:
            incoming, upstream = await asyncio.wait_for(dial(host, port, allow_loopback), 10)
        except (OSError, TimeoutError):
            writer.write(b"\x01")
            await writer.drain()
            return
        writer.write(b"\0")
        await writer.drain()
        tasks = [asyncio.create_task(from_frames(reader, upstream)), asyncio.create_task(to_frames(incoming, writer))]
        await asyncio.gather(*tasks)
    except (OSError, TimeoutError, ValueError, asyncio.IncompleteReadError):
        pass
    finally:
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        if upstream is not None:
            upstream.close()
        writer.close()


async def main(args):
    if args.allow_loopback and args.listen != "127.0.0.1":
        raise SystemExit("--allow-loopback requires --listen 127.0.0.1")
    encoded = Path(args.token_file).read_text().strip()
    token = base64.b64decode(encoded + "=", altchars=b"-_", validate=True)
    if len(token) != 32 or base64.urlsafe_b64encode(token).decode().rstrip("=") != encoded:
        raise SystemExit("Expected a canonical 32-byte Base64url token")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.load_cert_chain(args.cert, args.key)
    await serve(args, context, token)


async def serve(args, context, token):
    active = pending = 0
    pending_by_ip = Counter()

    async def handle(reader, writer):
        nonlocal active, pending
        peer = writer.get_extra_info("peername")
        if not peer:
            writer.close()
            return
        ip = ipaddress.ip_address(peer[0])
        if ip.version == 6:
            ip = ip.ipv4_mapped or ip
        if pending >= MAX_PENDING or pending_by_ip[ip] >= MAX_PENDING_PER_IP:
            writer.close()
            return
        pending += 1
        pending_by_ip[ip] += 1
        try:
            try:
                header = await asyncio.wait_for(reader.readexactly(37), AUTH_TIMEOUT)
            finally:
                pending -= 1
                pending_by_ip[ip] -= 1
                if pending_by_ip[ip] == 0:
                    del pending_by_ip[ip]
            if header[:4] != MAGIC or not hmac.compare_digest(header[5:], token):
                return
            if header[4] == 0:
                writer.write(b"\x00")
                await asyncio.wait_for(writer.drain(), AUTH_TIMEOUT)
                return
            if header[4] != 1 or active >= MAX_ACTIVE:
                return
            active += 1
            try:
                await relay(reader, writer, args.allow_loopback)
            finally:
                active -= 1
        except (OSError, TimeoutError, asyncio.IncompleteReadError):
            pass
        finally:
            writer.close()

    server = await asyncio.start_server(handle, args.listen, args.port, ssl=context, ssl_handshake_timeout=AUTH_TIMEOUT, ssl_shutdown_timeout=AUTH_TIMEOUT)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", default="0.0.0.0")
    parser.add_argument("--port", default=8443, type=int)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--allow-loopback", action="store_true", help="isolated local tests only")
    asyncio.run(main(parser.parse_args()))
