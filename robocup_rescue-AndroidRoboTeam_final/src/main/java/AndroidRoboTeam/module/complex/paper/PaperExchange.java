package AndroidRoboTeam.module.complex.paper;

import java.io.*;
import java.util.*;

public interface PaperExchange extends AutoCloseable {
    Map<Integer, Serializable> gather(int epoch, String phase, int actor,
                                      List<Integer> members, Serializable payload) throws IOException;
    @Override default void close() throws IOException {}
}
