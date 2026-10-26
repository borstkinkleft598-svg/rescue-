package AndroidRoboTeam.module.complex.paper;

import java.io.*;
import java.net.*;
import java.util.*;
import static AndroidRoboTeam.module.complex.paper.PaperProtocol.*;

public final class SocketPaperExchange implements PaperExchange {
    private final Socket socket;
    private final ObjectOutputStream out;
    private final ObjectInputStream in;
    public SocketPaperExchange(int port, String token, int timeout) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeout);
        socket.setSoTimeout(timeout * 3);
        socket.setTcpNoDelay(true);
        DataOutputStream auth = new DataOutputStream(socket.getOutputStream());
        auth.writeUTF(Objects.requireNonNull(token, "PAPER_LDCOP_TOKEN is missing"));
        auth.flush();
        out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        in = PaperRelay.input(socket.getInputStream());
    }
    @Override public synchronized Map<Integer, Serializable> gather(int epoch, String phase, int actor,
            List<Integer> members, Serializable payload) throws IOException {
        out.writeObject(new Request(epoch, phase, actor, List.copyOf(members), payload));
        out.flush(); out.reset();
        try {
            Reply reply = (Reply) in.readObject();
            if (reply.error() != null) throw new IOException("paper synchronization failed: " + reply.error());
            if (!reply.values().keySet().equals(new TreeSet<>(members))) throw new IOException("incomplete synchronization");
            return reply.values();
        } catch (ClassNotFoundException | ClassCastException e) { throw new IOException("invalid relay response", e); }
    }
    @Override public void close() throws IOException { socket.close(); }
}
