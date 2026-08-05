import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class PacketRegistry
{
    public enum Route
    {
        CONTROL,
        REALTIME,
        RELIABLE,
        BULK
    }

    public static final class Spec
    {
        public final int id;
        public final String opcode;
        public final Route route;
        public final boolean clientToServer;
        public final boolean serverToClient;

        Spec(int id, String opcode, Route route, boolean clientToServer, boolean serverToClient)
        {
            this.id = id;
            this.opcode = opcode;
            this.route = route;
            this.clientToServer = clientToServer;
            this.serverToClient = serverToClient;
        }
    }

    private static final Map<Integer, Spec> byId = new HashMap<>();
    private static final Map<String, Spec> byOpcode = new HashMap<>();

    static
    {
        register(1, "HELLO", Route.CONTROL, true, false);
        register(2, "HELLOACK", Route.CONTROL, false, true);
        register(3, "PING", Route.REALTIME, true, false);
        register(4, "PONG", Route.REALTIME, false, true);
        register(5, "ERROR", Route.CONTROL, false, true);
        register(6, "KICK", Route.RELIABLE, false, true);

        register(10, "MOVE", Route.REALTIME, true, true);
        register(11, "UPDATE1A", Route.REALTIME, true, true);
        register(12, "UPDATE1B", Route.REALTIME, true, true);
        register(13, "UPDATE2A", Route.REALTIME, true, true);
        register(14, "UPDATE2B", Route.REALTIME, true, true);
        register(15, "UPDATE3", Route.REALTIME, true, true);
        register(16, "UPDATE4", Route.REALTIME, true, true);

        register(20, "PRESP", Route.RELIABLE, true, false);
        register(21, "PCOOP", Route.RELIABLE, true, false);
        register(22, "SCENE", Route.RELIABLE, true, true);
        register(23, "QITEM", Route.RELIABLE, true, true);
        register(24, "PJOIN", Route.RELIABLE, true, false);
        register(25, "PLEAVE", Route.RELIABLE, true, false);
        register(26, "PSTATE", Route.RELIABLE, true, false);
        register(27, "PARTY", Route.RELIABLE, false, true);
        register(28, "PINVITE", Route.RELIABLE, false, true);
        register(29, "PSTATEF", Route.RELIABLE, false, true);
        register(30, "PVIS", Route.REALTIME, false, true);
        register(31, "TPREQ", Route.RELIABLE, true, false);
        register(32, "TPPOS", Route.RELIABLE, false, true);

        register(40, "SAVEBEG", Route.BULK, true, true);
        register(41, "SAVECHK", Route.BULK, true, true);
        register(42, "SAVEEND", Route.BULK, true, true);
        register(43, "SAVENACK", Route.BULK, true, true);
        register(44, "SAVEACK", Route.BULK, true, true);
        register(45, "SAVEWANT", Route.BULK, true, false);
        register(46, "SAVENEED", Route.BULK, false, true);

        register(60, "NPCADD", Route.RELIABLE, true, false);
        register(61, "NPCUPD", Route.RELIABLE, true, false);
        register(62, "NPCDEL", Route.RELIABLE, true, false);
        register(63, "NPCHIT", Route.RELIABLE, true, false);
        register(64, "NPCACK", Route.RELIABLE, true, false);
        register(65, "NPCTERM", Route.RELIABLE, true, false);
        register(66, "NPCTAKE", Route.RELIABLE, true, false);
        register(67, "NPCNOPE", Route.RELIABLE, true, false);
        register(68, "NPCFREE", Route.RELIABLE, true, false);
        register(69, "NPCWANT", Route.RELIABLE, true, false);
        register(70, "NPCNEW", Route.RELIABLE, false, true);
        register(71, "NPCMOV", Route.RELIABLE, false, true);
        register(72, "NPCEND", Route.RELIABLE, false, true);
        register(73, "NPCDEAD", Route.RELIABLE, false, true);
        register(74, "NPCHITF", Route.RELIABLE, false, true);
        register(75, "NPCACKF", Route.RELIABLE, false, true);
        register(76, "NPCKILL", Route.RELIABLE, false, true);
        register(77, "NPCGIVE", Route.RELIABLE, false, true);
        register(78, "NPCDROP", Route.RELIABLE, false, true);
        register(79, "NPCGONE", Route.RELIABLE, false, true);
        register(80, "NPCSCALE", Route.RELIABLE, false, true);
        register(81, "NPCREG", Route.RELIABLE, false, true);
        register(82, "NPCBIND", Route.RELIABLE, true, false);
        register(83, "NPCFAST", Route.REALTIME, true, true);

        register(90, "TSYNC", Route.REALTIME, true, false);
        register(91, "TSYNCR", Route.REALTIME, false, true);
    }

    private PacketRegistry()
    {
    }

    private static void register(
            int id,
            String opcode,
            Route route,
            boolean clientToServer,
            boolean serverToClient)
    {
        if (byId.containsKey(id) || byOpcode.containsKey(opcode))
        {
            throw new IllegalStateException("duplicate packet registration " + opcode);
        }
        Spec spec = new Spec(id, opcode, route, clientToServer, serverToClient);
        byId.put(id, spec);
        byOpcode.put(opcode, spec);
    }

    public static Spec byId(int id)
    {
        return byId.get(id);
    }

    public static Spec byOpcode(String opcode)
    {
        return byOpcode.get(opcode);
    }

    public static boolean acceptsClient(String opcode)
    {
        Spec spec = byOpcode(opcode);
        return spec != null && spec.clientToServer;
    }

    public static boolean sendsToClient(String opcode)
    {
        Spec spec = byOpcode(opcode);
        return spec != null && spec.serverToClient;
    }

    public static Route route(String opcode)
    {
        Spec spec = byOpcode(opcode);
        return spec == null ? null : spec.route;
    }

    public static Collection<Spec> all()
    {
        return Collections.unmodifiableCollection(byId.values());
    }
}
