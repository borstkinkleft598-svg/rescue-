package AndroidRoboTeam.module.complex.paper;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static AndroidRoboTeam.module.complex.paper.PaperProtocol.*;

/** Loopback authenticated synchronous transport, not an allocation server.
 * No costs, schedules or BMS updates are computed here. */
public final class PaperRelay implements AutoCloseable {
    private final ServerSocket server;
    private final String token;
    private final int timeout;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Round> rounds = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private static final class Round {
        final List<Integer> members;
        final Map<Integer, Serializable> values = new TreeMap<>();
        final long deadline;
        int returned;
        String error;
        Round(List<Integer> members, int timeout) {
            this.members = List.copyOf(members);
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        }
    }
    public PaperRelay(int port, String token, int timeout) throws IOException {
        if (token == null || token.length() < 16) throw new IllegalArgumentException("relay token must have >=16 characters");
        this.token = token;
        this.timeout = timeout;
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        threads.submit(() -> {
            while (!closed) try {
                Socket socket = server.accept();
                threads.submit(() -> serve(socket));
            } catch (IOException e) { if (!closed) System.err.println("[PAPER-RELAY] accept failed"); }
        });
    }
    public int port() { return server.getLocalPort(); }

    static ObjectInputStream input(InputStream input) throws IOException {
        ObjectInputStream stream = new ObjectInputStream(input);
        stream.setObjectInputFilter(info -> {
            if (info.depth() > 40 || info.arrayLength() > 200_000)
                return ObjectInputFilter.Status.REJECTED;
            Class<?> type = info.serialClass();
            if (type == null) return ObjectInputFilter.Status.UNDECIDED;
            while (type.isArray()) type = type.getComponentType();
            String name = type.getName();
            return type.isPrimitive() || name.startsWith("java.lang.") || name.startsWith("java.util.")
                    || name.startsWith("AndroidRoboTeam.module.complex.paper.PaperProtocol$")
                    || name.equals("AndroidRoboTeam.module.complex.common.TemporalSchedule$Task")
                    ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
        });
        return stream;
    }
    private void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(timeout * 3);
            socket.setTcpNoDelay(true);
            String supplied = new DataInputStream(socket.getInputStream()).readUTF();
            if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) return;
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            ObjectInputStream in = input(socket.getInputStream());
            while (!closed) {
                Object object = in.readObject();
                if (!(object instanceof Request request)) throw new IOException("invalid request");
                out.writeObject(exchange(request));
                out.flush();
                out.reset();
                // Drop timed-out tombstones after one minute, even if an actor disappeared.
                long expiry = System.nanoTime() - TimeUnit.MINUTES.toNanos(1);
                rounds.entrySet().removeIf(e -> e.getValue().deadline < expiry);
            }
        } catch (EOFException | SocketException ignored) {
        } catch (Exception e) {
            if (!closed) System.err.println("[PAPER-RELAY] client failure: " + e.getClass().getSimpleName());
        }
    }
    private Reply exchange(Request request) throws InterruptedException {
        List<Integer> members = request.members();
        if (members.isEmpty() || members.size() > 256 || !members.contains(request.actor())
                || !members.equals(members.stream().distinct().sorted().toList()) || request.phase().length() > 120)
            return new Reply(Map.of(), "invalid membership/phase");
        // Different memberships for the same epoch/phase are an error, not two independent consensuses.
        String key = request.epoch() + ":" + request.phase();
        Round round = rounds.computeIfAbsent(key, k -> new Round(members, timeout));
        synchronized (round) {
            if (!round.members.equals(members)) round.error = "membership disagreement";
            if (round.values.containsKey(request.actor())) round.error = "duplicate actor";
            round.values.put(request.actor(), request.payload());
            round.notifyAll();
            while (round.error == null && round.values.size() < members.size()) {
                long left = round.deadline - System.nanoTime();
                if (left <= 0) {
                    Set<Integer> missing = new TreeSet<>(members);
                    missing.removeAll(round.values.keySet());
                    round.error = "barrier timeout epoch=" + request.epoch() + " phase=" + request.phase() + " missing=" + missing;
                    break;
                }
                TimeUnit.NANOSECONDS.timedWait(round, left);
            }
            round.notifyAll();
            Reply reply = new Reply(new TreeMap<>(round.values), round.error);
            if (++round.returned == members.size()) rounds.remove(key, round);
            return reply;
        }
    }
    @Override public void close() throws IOException {
        closed = true;
        server.close();
        threads.shutdownNow();
    }
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 28033;
        String token = System.getenv("PAPER_LDCOP_TOKEN");
        PaperRelay relay = new PaperRelay(port, token, 45000);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { relay.close(); } catch (IOException ignored) {} }));
        System.out.println("[PAPER-RELAY] listening on loopback:" + relay.port());
        new CountDownLatch(1).await();
    }
}
