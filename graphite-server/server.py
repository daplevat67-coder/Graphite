#!/usr/bin/env python3
"""Graphite Tunnel 1, an authenticated TCP relay over TLS 1.3."""
import argparse
import asyncio
import base64
import hmac
import ipaddress
import socket
import ssl
import struct
from pathlib import Path

MAGIC = b"GTP1"
CHUNK = 16384
IDLE = 180


async def dial(host, port, allow_loopback):
    loop = asyncio.get_running_loop()
    addresses = await loop.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    for _, _, _, _, address in addresses:
        ip = ipaddress.ip_address(address[0])
        ip = getattr(ip, "ipv4_mapped", None) or ip
        if not ((ip.is_global and not ip.is_multicast) or (allow_loopback and ip.is_loopback)):
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


async def relay(reader, writer, token, allow_loopback):
    upstream = None
    tasks = []
    try:
        header = await asyncio.wait_for(reader.readexactly(37), 8)
        if header[:4] != MAGIC or not hmac.compare_digest(header[5:], token):
            return
        if header[4] == 0:
            writer.write(b"\0")
            await writer.drain()
            return
        if header[4] != 1:
            return
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
    active = 0

    async def handle(reader, writer):
        nonlocal active
        if active >= 96:
            writer.close()
            return
        active += 1
        try:
            await relay(reader, writer, token, args.allow_loopback)
        finally:
            active -= 1

    server = await asyncio.start_server(handle, args.listen, args.port, ssl=context, ssl_handshake_timeout=8)
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
