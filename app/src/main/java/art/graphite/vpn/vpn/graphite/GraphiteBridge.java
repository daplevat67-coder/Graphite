package art.graphite.vpn.vpn.graphite;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.*;

/** Authenticated local SOCKS5 bridge for the versioned Graphite TLS tunnel. */
public final class GraphiteBridge implements Closeable {
    public interface SocketProtector { boolean protect(Socket socket); }

    private static final int CHUNK = 16384;
    private static final long IDLE_MILLIS = 180_000;
    private final SSLSocketFactory tls;
    private final InetSocketAddress server;
    private final String hostname;
    private final byte[] token;
    private final SocketProtector protector;
    private final byte[] localUser = randomCredential(), localPassword = randomCredential();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(48);
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ScheduledThreadPoolExecutor idleChecks = new ScheduledThreadPoolExecutor(1);
    private ServerSocket listener;
    private volatile boolean closed;

    public GraphiteBridge(SSLSocketFactory tls, InetSocketAddress server, String hostname,
                          byte[] token, SocketProtector protector) {
        this.tls = tls;
        this.server = server;
        this.hostname = hostname;
        this.token = token.clone();
        this.protector = protector;
        idleChecks.setRemoveOnCancelPolicy(true);
    }

    private static byte[] randomCredential() {
        byte[] data = new byte[24];
        new java.security.SecureRandom().nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encode(data);
    }

    public String username() { return new String(localUser, StandardCharsets.US_ASCII); }
    public String password() { return new String(localPassword, StandardCharsets.US_ASCII); }

    public int start() throws IOException {
        // Verify TLS identity and credentials before a VPN interface takes over routing.
        try (SSLSocket probe = open(0, "", 0)) { }
        listener = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
        workers.execute(this::accept);
        return listener.getLocalPort();
    }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept();
                if (!slots.tryAcquire()) { socket.close(); continue; }
                sockets.add(socket);
                try { workers.execute(() -> proxy(socket)); }
                catch (RejectedExecutionException ignored) {
                    sockets.remove(socket);
                    closeQuietly(socket);
                    slots.release();
                }
            } catch (IOException ignored) { return; }
        }
    }

    private SSLSocket open(int operation, String destination, int port) throws IOException {
        Socket plain = new Socket();
        sockets.add(plain);
        SSLSocket secure = null;
        try {
            if (closed || !protector.protect(plain)) throw new IOException("Socket protection failed");
            plain.connect(server, 5000);
            plain.setSoTimeout(8000);
            secure = (SSLSocket) tls.createSocket(plain, hostname, server.getPort(), true);
            SSLParameters parameters = secure.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setProtocols(new String[]{"TLSv1.3"});
            secure.setSSLParameters(parameters);
            secure.setSoTimeout(8000);
            secure.startHandshake();
            DataOutputStream out = new DataOutputStream(secure.getOutputStream());
            out.writeBytes("GTP1");
            out.writeByte(operation);
            out.write(token);
            if (operation == 1) {
                byte[] host = destination.getBytes(StandardCharsets.US_ASCII);
                if (host.length == 0 || host.length > 253) throw new IOException("Invalid destination");
                out.writeShort(port);
                out.writeShort(host.length);
                out.write(host);
            }
            out.flush();
            if (secure.getInputStream().read() != 0) throw new IOException("Graphite relay rejected request");
            if (operation == 1) sockets.add(secure);
            if (closed) throw new IOException("Bridge stopped");
            return secure;
        } catch (IOException | RuntimeException failure) {
            if (secure != null) sockets.remove(secure);
            closeQuietly(secure != null ? secure : plain);
            throw failure;
        } finally { sockets.remove(plain); }
    }

    private void proxy(Socket client) {
        SSLSocket upstream = null;
        try (Socket local = client) {
            local.setSoTimeout(8000);
            DataInputStream in = new DataInputStream(local.getInputStream());
            DataOutputStream out = new DataOutputStream(local.getOutputStream());
            if (in.readUnsignedByte() != 5) return;
            boolean auth = false;
            for (int left = in.readUnsignedByte(); left > 0; left--) auth |= in.readUnsignedByte() == 2;
            out.write(new byte[]{5, (byte) (auth ? 2 : 255)});
            if (!auth || in.readUnsignedByte() != 1) return;
            boolean valid = MessageDigest.isEqual(field(in, in.readUnsignedByte()), localUser);
            valid &= MessageDigest.isEqual(field(in, in.readUnsignedByte()), localPassword);
            out.write(new byte[]{1, (byte) (valid ? 0 : 1)});
            if (!valid || in.readUnsignedByte() != 5) return;
            int command = in.readUnsignedByte();
            if (in.readUnsignedByte() != 0) return;
            if (command != 1) { reply(out, 7); return; }
            int addressType = in.readUnsignedByte();
            String host;
            if (addressType == 3) host = new String(field(in, in.readUnsignedByte()), StandardCharsets.US_ASCII);
            else if (addressType == 1 || addressType == 4)
                host = InetAddress.getByAddress(field(in, addressType == 1 ? 4 : 16)).getHostAddress();
            else { reply(out, 8); return; }
            int port = in.readUnsignedShort();
            if (port == 0 || !host.matches("[A-Za-z0-9.:-]{1,253}")) { reply(out, 4); return; }
            try { upstream = open(1, host, port); }
            catch (IOException failure) { reply(out, 5); return; }
            reply(out, 0);
            local.setSoTimeout(0);
            upstream.setSoTimeout(0);
            SSLSocket remote = upstream;
            AtomicLong lastActivity = new AtomicLong(System.nanoTime());
            long idleNanos = TimeUnit.MILLISECONDS.toNanos(IDLE_MILLIS);
            long interval = Math.max(1, Math.min(1000, IDLE_MILLIS / 4));
            ScheduledFuture<?> idleCheck = idleChecks.scheduleWithFixedDelay(() -> {
                if (System.nanoTime() - lastActivity.get() >= idleNanos) {
                    closeQuietly(local);
                    closeQuietly(remote);
                }
            }, interval, interval, TimeUnit.MILLISECONDS);
            try {
                Future<?> upload = workers.submit(() -> {
                    try {
                        DataOutputStream frames = new DataOutputStream(remote.getOutputStream());
                        byte[] buffer = new byte[CHUNK];
                        for (int length; (length = in.read(buffer)) >= 0;) {
                            frames.writeShort(length);
                            frames.write(buffer, 0, length);
                            frames.flush();
                            lastActivity.set(System.nanoTime());
                        }
                        frames.writeShort(0);
                        frames.flush();
                    } catch (IOException failure) { closeQuietly(remote); }
                });
                try {
                    DataInputStream frames = new DataInputStream(remote.getInputStream());
                    for (int length; (length = frames.readUnsignedShort()) != 0;) {
                        if (length > CHUNK) throw new IOException("Frame too large");
                        out.write(field(frames, length));
                        lastActivity.set(System.nanoTime());
                    }
                    local.shutdownOutput();
                    upload.get();
                } finally { upload.cancel(true); }
            } finally { idleCheck.cancel(false); }
        } catch (IOException | ExecutionException | RejectedExecutionException ignored) {
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (upstream != null) {
                sockets.remove(upstream);
                closeQuietly(upstream);
            }
            sockets.remove(client);
            slots.release();
        }
    }

    private static byte[] field(DataInputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        input.readFully(result);
        return result;
    }

    private static void reply(DataOutputStream out, int status) throws IOException {
        out.write(new byte[]{5, (byte) status, 0, 1, 0, 0, 0, 0, 0, 0});
    }

    @Override public void close() {
        closed = true;
        closeQuietly(listener);
        idleChecks.shutdownNow();
        workers.shutdownNow();
        for (Socket socket : sockets) closeQuietly(socket);
        Arrays.fill(token, (byte) 0);
    }

    private static void closeQuietly(Closeable resource) {
        if (resource != null) try { resource.close(); } catch (IOException ignored) { }
    }
}
