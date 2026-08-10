import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Base64;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

public class WitcherServer
{
    private static final Object NPC_WORLD_LOCK = new Object();
    private static final Map<String, PlayerSession> players = new ConcurrentHashMap<>();
    private static final Map<Integer, PlayerSession> playersById = new ConcurrentHashMap<>();
    private static final Set<String> bannedIps = ConcurrentHashMap.newKeySet();
    private static final Set<String> whitelistedIps = ConcurrentHashMap.newKeySet();
    private static final Map<String, UsernameReservation> reservedUsernames = new ConcurrentHashMap<>();

    private static final Map<Integer, Party> parties = new ConcurrentHashMap<>();
    private static final Map<String, Integer> playerParty = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger nextPartyId =
            new java.util.concurrent.atomic.AtomicInteger(1);
    private static final long PARTY_RESEND_NANOS = 2_000_000_000L;
    private static final long PARTY_REQUEST_TIMEOUT_NANOS = 60_000_000_000L;

    private static final Map<String, PartyRequest> partyRequests = new ConcurrentHashMap<>();

    private static final class PartyRequest
    {
        final String requesterKey;
        final String targetKey;
        final long expiresAtNanos;

        PartyRequest(String requesterKey, String targetKey, long expiresAtNanos)
        {
            this.requesterKey = requesterKey;
            this.targetKey = targetKey;
            this.expiresAtNanos = expiresAtNanos;
        }
    }
    private static long lastPartyResendNanos = 0L;

    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static final AtomicBoolean whitelistEnabled = new AtomicBoolean(false);

    private static final AtomicLong totalPacketsSent = new AtomicLong(0);
    private static final AtomicLong totalSendFailures = new AtomicLong(0);
    private static final AtomicLong transportMisroutes = new AtomicLong(0);
    private static final AtomicLong totalBroadcastTicks = new AtomicLong(0);
    private static final Map<String, AtomicLong> transportRoutes = new ConcurrentHashMap<>();

    private static volatile long lastBroadcastTickNanos = 0L;

    private static final long PLAYER_TIMEOUT_NANOS = 5_000_000_000L;
    private static final long USERNAME_HOLD_NANOS = 60_000_000_000L;
    private static final long BROADCAST_HEARTBEAT_NANOS = 60_000_000_000L;
    private static final long CHUNK_KEEPALIVE_NANOS = 1_000_000_000L;

    private static final double INTEREST_RADIUS = 300.0;
    private static final double INTEREST_RADIUS_SQUARED = INTEREST_RADIUS * INTEREST_RADIUS;

    private static final double PLAYER_VIS_ENTER_RADIUS = 200.0;
    private static final double PLAYER_VIS_LEAVE_RADIUS = 240.0;
    private static final double PLAYER_VIS_ENTER_SQUARED = PLAYER_VIS_ENTER_RADIUS * PLAYER_VIS_ENTER_RADIUS;
    private static final double PLAYER_VIS_LEAVE_SQUARED = PLAYER_VIS_LEAVE_RADIUS * PLAYER_VIS_LEAVE_RADIUS;
    private static final long PLAYER_VIS_RESEND_NANOS = 1_000_000_000L;
    private static final int PLAYER_VIS_TICK_DIVIDER = 4;

    private static final long NPC_LOD_NEAR_NANOS = 33_000_000L;
    private static final long NPC_LOD_MID_NANOS = 50_000_000L;
    private static final long NPC_LOD_FAR_NANOS = 250_000_000L;
    private static final double NPC_LOD_NEAR_SQUARED = 40.0 * 40.0;
    private static final double NPC_LOD_MID_SQUARED = 55.0 * 55.0;
    private static final long NPC_VIEW_KEEPALIVE_NANOS = 2_000_000_000L;
    private static final double NPC_DELTA_POS_EPSILON = 0.06;
    private static final double NPC_DELTA_HEADING_EPSILON = 1.5;
    private static final long NPC_TICK_SLEEP_MS = 25L;
    private static final long NPC_TICK_NANOS = NPC_TICK_SLEEP_MS * 1_000_000L;
    private static final int NPC_SCALE_TICK_DIVIDER = 8;
    private static final int PLAYER_VIS_MAX = 48;

    public static final double CELL_SIZE = 128.0;

    private static final double NPC_INTEREST_RADIUS = 85.0;
    private static final double NPC_INTEREST_RADIUS_SQUARED = NPC_INTEREST_RADIUS * NPC_INTEREST_RADIUS;
    private static final double NPC_INTEREST_LEAVE_RADIUS = 95.0;
    private static final double NPC_INTEREST_LEAVE_SQUARED = NPC_INTEREST_LEAVE_RADIUS * NPC_INTEREST_LEAVE_RADIUS;
    private static final long NPC_OWNERSHIP_REFRESH_NANOS = 2_000_000_000L;
    private static final int NPC_SPAWN_BATCH = 14;
    private static final int NPC_MOVE_BATCH = 20;
    private static final int NPC_REMOVE_BATCH = 16;
    private static final int NPC_HIT_BATCH = 10;
    private static final int NPC_SCALE_BATCH = 48;
    private static final long NPC_SCALE_RESEND_NANOS = 1_000_000_000L;
    private static final int NPC_TARGET_LOG_LIMIT = 12;

    private static final long SERVER_START_NANOS = System.nanoTime();

    private static final AtomicLong npcHitsAccepted = new AtomicLong();
    private static final AtomicLong npcHitsRejected = new AtomicLong();
    private static final AtomicLong npcAcksRejected = new AtomicLong();
    private static final AtomicLong npcResends = new AtomicLong();
    private static final AtomicLong tickOverruns = new AtomicLong();
    private static final AtomicLong scaleEpoch = new AtomicLong();
    private static final AtomicBoolean scalingDirty = new AtomicBoolean(true);
    private static final AtomicLong passiveHitDistanceOutliers = new AtomicLong();
    private static final AtomicLong malformedPackets = new AtomicLong();
    private static final AtomicLong unknownPackets = new AtomicLong();
    private static final AtomicLong oversizedPackets = new AtomicLong();

    private static final int TICK_SAMPLE_SLOTS = 512;
    private static final long[] tickSamples = new long[TICK_SAMPLE_SLOTS];
    private static int tickSampleCursor = 0;

    private static void recordTickDuration(long nanos)
    {
        tickSamples[tickSampleCursor] = nanos;
        tickSampleCursor = (tickSampleCursor + 1) % TICK_SAMPLE_SLOTS;
    }

    private static double tickP99Ms()
    {
        long[] copy = tickSamples.clone();
        java.util.Arrays.sort(copy);

        return copy[(int) (copy.length * 0.99)] / 1_000_000.0;
    }

    private static final int MAX_PENDING_ACKS = 4096;
    private static final Map<Integer, int[]> pendingAcks = new ConcurrentHashMap<>();

    static long serverMs()
    {
        return (System.nanoTime() - SERVER_START_NANOS) / 1_000_000L;
    }

    private static List<PlayerSession> activeSessions()
    {
        List<PlayerSession> active = new ArrayList<>();
        for (PlayerSession session : players.values())
        {
            if (session.transportReady())
            {
                active.add(session);
            }
        }
        return active;
    }

    private static Long parseLongOrNull(String value)
    {
        if (value == null)
        {
            return null;
        }

        try
        {
            return Long.parseLong(value.trim());
        }
        catch (Exception e)
        {
            return null;
        }
    }
    private static final int MAX_PENDING_HITS = 64;
    private static final int NPC_SPAWN_PACKETS_PER_TICK = 18;
    private static final int NPC_MOVE_PACKETS_PER_TICK = 13;

    private static final long NPC_HEARTBEAT_NANOS = 30_000_000_000L;

    private static final AtomicLong totalNpcPacketsSent = new AtomicLong(0);
    private static final AtomicLong npcSpawnPacketsSent = new AtomicLong(0);
    private static final AtomicLong npcMovePacketsSent = new AtomicLong(0);
    private static final AtomicLong npcEndPacketsSent = new AtomicLong(0);
    private static final AtomicLong npcOwnPacketsSent = new AtomicLong(0);

    private static final int MOVE_POSITION_OFFSET = 1;
    private static final int UPDATE1A_POSITION_OFFSET = 2;

    private static final int MIN_USERNAME_LENGTH = 2;
    private static final int MAX_USERNAME_LENGTH = 16;
    private static final int MAX_PACKET_FIELDS = 512;

    private static final int DEFAULT_PORT = 40000;
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final int DEFAULT_STATUS_PORT = 40001;

    private static volatile HttpServer statusServer = null;
    private static volatile String cachedStatusFontBase64 = "";
    private static volatile DatagramSocket udpSocket = null;
    private static volatile TcpTransport tcpTransport = null;
    private static volatile UdpBatcher udpBatcher = null;

    private static final AtomicInteger nextPlayerId = new AtomicInteger(1);

    public static void main(String[] args) throws Exception
    {
        Properties serverProperties = loadServerProperties();
        int port = choosePort(args, serverProperties);
        whitelistEnabled.set(readBoolean(serverProperties, "whitelist", false));
        loadBannedIps();
        loadWhitelistIps();
        NpcScaling.load();
        startLogThread();

        DatagramSocket socket = new DatagramSocket(port);
        socket.setSendBufferSize(1 << 20);
        socket.setReceiveBufferSize(1 << 20);
        udpSocket = socket;
        udpBatcher = new UdpBatcher(socket, new UdpBatcher.Listener()
        {
            @Override
            public void sent(PlayerSession session, int bytes, int logicalPackets)
            {
                totalPacketsSent.incrementAndGet();
                if (session != null)
                {
                    session.udpPacketsSent.incrementAndGet();
                    session.udpBytesSent.addAndGet(bytes);
                }
            }

            @Override
            public void failed(PlayerSession session, int logicalPackets)
            {
                totalSendFailures.addAndGet(Math.max(1, logicalPackets));
            }
        });

        tcpTransport = new TcpTransport(port, new TcpTransport.Handler()
        {
            @Override
            public void onMessage(TcpTransport.Connection connection, String message)
            {
                handleTcpMessage(connection, message);
            }

            @Override
            public void onClosed(TcpTransport.Connection connection)
            {
                PlayerSession session = connection.session();
                if (session != null)
                {
                    for (String pending : connection.drainPending())
                    {
                        if (!isRealtimeOpcode(packetOpcode(pending))
                                && !session.pendingTcpReplay.offer(pending))
                        {
                            session.outboundDropped.incrementAndGet();
                        }
                    }
                    session.clearTcp(connection);
                }
            }
        });

        boolean svgEnabled = readBoolean(serverProperties, "svgEnabled", false);
        int svgPort = DEFAULT_STATUS_PORT;

        if (svgEnabled)
        {
            svgPort = chooseSvgPort(serverProperties);
            loadStatusAssets();
            statusServer = startStatusServer(svgPort);
        }

        dbgNotime("Launching Witcher Online for The Witcher 3: Wild Hunt...\n");
        dbgNotime("Author: rejuvenate7 - Github: https://github.com/rejuvenate7\n");
        dbg("Starting Witcher Online server on *:%d (UDP+TCP required)\n", port);

        if (svgEnabled)
        {
            dbg("Starting Witcher Online status SVG server on *:%d\n", svgPort);
            dbg("Status SVG: http://127.0.0.1:%d/status.svg\n", svgPort);
        }

        dbg("For help, type \"help\" or \"?\"\n");

        final DatagramSocket activeSocket = socket;
        Thread recvThread = startThread("udp-recv", () -> receiveLoop(activeSocket));
        Thread sendThread = startThread("broadcast", () -> broadcastLoop(activeSocket));
        Thread cleanupThread = startThread("udp-cleanup", WitcherServer::cleanupLoop);
        Thread npcThread = startThread("npc-sync", () -> npcLoop(activeSocket));
        Thread consoleThread = startThread("console", () -> consoleLoop(activeSocket));

        recvThread.join();
        sendThread.join();
        cleanupThread.join();
        npcThread.join();
        consoleThread.join();
    }

    private static int chooseSvgPort(Properties properties)
    {
        String propertyPort = properties.getProperty("svgPort");

        if (propertyPort != null)
        {
            Integer parsed = parsePort(propertyPort.trim());

            if (parsed != null)
            {
                return parsed;
            }

            System.err.println("Invalid server.properties svgPort: " + propertyPort + " (falling back)");
        }

        return DEFAULT_STATUS_PORT;
    }

    private static HttpServer startStatusServer(int statusPort) throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress(statusPort), 0);

        server.createContext("/status.svg", WitcherServer::handleStatusSvg);
        server.createContext("/players.svg", WitcherServer::handleStatusSvg);
        server.createContext("/status.json", WitcherServer::handleStatusJson);

        server.setExecutor(null);
        server.start();

        return server;
    }

    private static void handleStatusSvg(HttpExchange exchange) throws IOException
    {
        String svg = buildStatusSvg();

        sendHttp(exchange, 200, "image/svg+xml; charset=utf-8", svg);
    }

    private static void handleStatusJson(HttpExchange exchange) throws IOException
    {
        List<String> names = getConnectedPlayerNames();

        StringBuilder json = new StringBuilder();

        json.append("{");
        json.append("\"server\":\"Witcher Online\",");
        json.append("\"online\":").append(names.size()).append(",");
        json.append("\"players\":[");

        for (int i = 0; i < names.size(); i++)
        {
            if (i > 0)
            {
                json.append(",");
            }

            json.append("\"").append(jsonEscape(names.get(i))).append("\"");
        }

        json.append("]");
        json.append("}");

        sendHttp(exchange, 200, "application/json; charset=utf-8", json.toString());
    }

    private static void loadStatusAssets()
    {
        Path dir = statusAssetsDir();

        dbg("Status assets dir=%s\n", dir);

        cachedStatusFontBase64 = readBase64IfExists(dir.resolve("status-font.woff2"));

        dbg("Loaded status font from %s\n", dir);
    }

    private static Path statusAssetsDir()
    {
        return appDir().resolve("assets").resolve("status");
    }

    private static String readBase64IfExists(Path path)
    {
        try
        {
            if (!Files.exists(path))
            {
                return "";
            }

            byte[] data = Files.readAllBytes(path);
            return Base64.getEncoder().encodeToString(data);
        }
        catch (Exception e)
        {
            dbg("Failed to load status asset %s: %s\n", path, e.getMessage());
            return "";
        }
    }

    private static int estimateSvgTextWidth(String text, int fontSize)
    {
        if (text == null || text.isEmpty())
        {
            return fontSize;
        }

        double width = 0.0;

        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);

            if (c == ' ')
            {
                width += fontSize * 0.28;
            }
            else if (c == 'i' || c == 'l' || c == 'I' || c == '!' || c == '.' || c == ',' || c == '\'')
            {
                width += fontSize * 0.28;
            }
            else if (c == 'W' || c == 'M')
            {
                width += fontSize * 0.82;
            }
            else if (c >= '0' && c <= '9')
            {
                width += fontSize * 0.48;
            }
            else
            {
                width += fontSize * 0.58;
            }
        }

        width += text.length();

        return (int) Math.ceil(width) + 4;
    }

    private static String buildStatusSvg()
    {
        List<String> names = getConnectedPlayerNames();
        int count = names.size();

        String titleText = "Witcher Online";
        String playerCountText = count == 1 ? "1 player online" : count + " players online";

        StringBuilder svg = new StringBuilder();

        int paddingX = 4;
        int paddingTop = 4;
        int paddingBottom = 6;

        int titleFontSize = 58;
        int countFontSize = 32;

        int titleWidth = estimateSvgTextWidth(titleText, titleFontSize);
        int countWidth = estimateSvgTextWidth(playerCountText, countFontSize);

        int svgW = Math.max(titleWidth, countWidth) + paddingX * 2;

        int titleY = paddingTop + 54;
        int countY = titleY + 39;

        int svgH = countY + paddingBottom + 5;

        int textX = paddingX;

        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"");
        svg.append(svgW);
        svg.append("\" height=\"");
        svg.append(svgH);
        svg.append("\" viewBox=\"0 0 ");
        svg.append(svgW);
        svg.append(" ");
        svg.append(svgH);
        svg.append("\">");

        svg.append("<defs>");
        svg.append("<style>");

        if (!cachedStatusFontBase64.isEmpty())
        {
            svg.append("@font-face{");
            svg.append("font-family:'WitcherStatusFont';");
            svg.append("src:url('data:font/woff2;base64,");
            svg.append(cachedStatusFontBase64);
            svg.append("') format('woff2');");
            svg.append("font-weight:normal;");
            svg.append("font-style:normal;");
            svg.append("}");
        }

        svg.append(".text-outline{");
        svg.append("font-family:'WitcherStatusFont',Georgia,serif;");
        svg.append("stroke:#000000;");
        svg.append("stroke-width:2px;");
        svg.append("stroke-opacity:1;");
        svg.append("stroke-linejoin:round;");
        svg.append("paint-order:stroke fill;");
        svg.append("}");

        svg.append(".title-text{");
        svg.append("font-size:");
        svg.append(titleFontSize);
        svg.append("px;");
        svg.append("fill:#ffffff;");
        svg.append("letter-spacing:1px;");
        svg.append("}");

        svg.append(".count-text{");
        svg.append("font-size:");
        svg.append(countFontSize);
        svg.append("px;");
        svg.append("fill:#f7ead0;");
        svg.append("letter-spacing:1px;");
        svg.append("}");

        svg.append("</style>");
        svg.append("</defs>");

        svg.append("<text class=\"text-outline title-text\" x=\"");
        svg.append(textX);
        svg.append("\" y=\"");
        svg.append(titleY);
        svg.append("\">");
        svg.append(xmlEscape(titleText));
        svg.append("</text>");

        svg.append("<text class=\"text-outline count-text\" x=\"");
        svg.append(textX);
        svg.append("\" y=\"");
        svg.append(countY);
        svg.append("\">");
        svg.append(xmlEscape(playerCountText));
        svg.append("</text>");

        svg.append("</svg>");

        return svg.toString();
    }

    private static List<String> getConnectedPlayerNames()
    {
        List<String> names = new ArrayList<>();

        for (PlayerSession session : activeSessions())
        {
            if (session != null && session.username != null && !session.username.trim().isEmpty())
            {
                names.add(session.username.trim());
            }
        }

        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);

        return names;
    }

    private static void sendHttp(HttpExchange exchange, int status, String contentType, String body) throws IOException
    {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate, max-age=0, s-maxage=0");
        headers.set("Pragma", "no-cache");
        headers.set("Expires", "0");
        headers.set("Access-Control-Allow-Origin", "*");

        exchange.sendResponseHeaders(status, data.length);

        try (OutputStream os = exchange.getResponseBody())
        {
            os.write(data);
        }
    }

    private static String xmlEscape(String s)
    {
        if (s == null)
        {
            return "";
        }

        StringBuilder out = new StringBuilder(s.length() + 8);

        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);

            if (c == '&')
            {
                out.append("&amp;");
            }
            else if (c == '<')
            {
                out.append("&lt;");
            }
            else if (c == '>')
            {
                out.append("&gt;");
            }
            else if (c == '"')
            {
                out.append("&quot;");
            }
            else if (c == '\'')
            {
                out.append("&apos;");
            }
            else
            {
                out.append(c);
            }
        }

        return out.toString();
    }

    private static Thread startThread(String name, Runnable target)
    {
        Thread thread = new Thread(target, name);
        thread.setUncaughtExceptionHandler((t, e) ->
        {
            dbg("Thread %s crashed: %s\n", t.getName(), e.toString());
            e.printStackTrace();
        });
        thread.start();
        return thread;
    }

    private static void receiveLoop(DatagramSocket socket)
    {
        byte[] buffer = new byte[8192];

        while (running.get())
        {
            try
            {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                ClientEndpoint sender = new ClientEndpoint(packet.getAddress(), packet.getPort());
                String senderIp = normalizeIp(sender.address.getHostAddress());

                if (isIpBanned(senderIp))
                {
                    safeSend(socket, sender, "ERROR\tBANNED");
                    continue;
                }

                if (whitelistEnabled.get() && !isIpWhitelisted(senderIp))
                {
                    safeSend(socket, sender, "ERROR\tNOT_WHITELISTED");
                    continue;
                }

                List<String> messages;
                try
                {
                    messages = BinaryPacketCodec.decodeDatagram(packet.getData(), packet.getLength());
                }
                catch (IllegalArgumentException invalid)
                {
                    malformedPackets.incrementAndGet();
                    continue;
                }

                for (String msg : messages)
                {
                    if (!handleUdpTransportMessage(socket, sender, msg))
                    {
                        handleMessage(socket, sender, msg);
                    }
                }
            }
            catch (SocketException e)
            {
                if (running.get())
                {
                    e.printStackTrace();
                }
                break;
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private static final Set<String> unroutedOpcodes = ConcurrentHashMap.newKeySet();

    private static boolean isPlayerStateOpcode(String opcode)
    {
        return "MOVE".equals(opcode)
                || "UPDATE1A".equals(opcode)
                || "UPDATE1B".equals(opcode)
                || "UPDATE2A".equals(opcode)
                || "UPDATE2B".equals(opcode)
                || "UPDATE3".equals(opcode)
                || "UPDATE4".equals(opcode);
    }

    private static boolean isNpcWorldOpcode(String opcode)
    {
        return "PSTATE".equals(opcode) || opcode.startsWith("NPC");
    }

    private static boolean isUpdateOpcode(String opcode)
    {
        return PacketRegistry.acceptsClient(opcode) && !"HELLO".equals(opcode) && !"PING".equals(opcode);
    }

    private static boolean isValidUsername(String value)
    {
        if (value == null || value.length() < MIN_USERNAME_LENGTH || value.length() > MAX_USERNAME_LENGTH)
        {
            return false;
        }

        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';

            if (!allowed)
            {
                return false;
            }
        }

        return true;
    }

    private static boolean isInteger(String value)
    {
        if (value == null || value.isEmpty())
        {
            return false;
        }

        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);

            if (i == 0 && c == '-')
            {
                continue;
            }

            if (c < '0' || c > '9')
            {
                return false;
            }
        }

        return true;
    }

    private static void handleMessage(DatagramSocket socket, ClientEndpoint sender, String msg) throws Exception
    {
        handleMessage(socket, sender, null, msg);
    }

    private static void handleMessage(
            DatagramSocket socket,
            ClientEndpoint sender,
            TcpTransport.Connection tcpConnection,
            String msg) throws Exception
    {
        if (msg == null || msg.isEmpty())
        {
            malformedPackets.incrementAndGet();
            return;
        }

        if (msg.length() > (1 << 20))
        {
            oversizedPackets.incrementAndGet();
            return;
        }

        String[] parts = msg.split("\t", -1);

        if (parts.length < 2 || parts.length > MAX_PACKET_FIELDS)
        {
            malformedPackets.incrementAndGet();
            return;
        }

        String opcode = parts[0];

        if (!isUpdateOpcode(opcode))
        {
            unknownPackets.incrementAndGet();
            return;
        }

        int packetPlayerId = 0;
        String username;
        int fieldsStart;

        if (parts.length >= 3 && isInteger(parts[1].trim()))
        {
            try
            {
                packetPlayerId = Integer.parseInt(parts[1].trim());
            }
            catch (NumberFormatException e)
            {
                packetPlayerId = 0;
            }

            username = unescapeField(parts[2]).trim();
            fieldsStart = 3;
        }
        else
        {
            username = unescapeField(parts[1]).trim();
            fieldsStart = 2;
        }

        if (!isValidUsername(username))
        {
            safeReply(socket, sender, tcpConnection, "ERROR\tINVALID_USERNAME");
            return;
        }

        if (tcpConnection != null && !username.equalsIgnoreCase(tcpConnection.helloUsername()))
        {
            tcpConnection.close();
            return;
        }

        String usernameKey = normalizeUsernameKey(username);
        String senderIp = normalizeIp(sender.address.getHostAddress());
        long now = System.nanoTime();

        PlayerSession current = players.get(usernameKey);

        if (current != null)
        {
            String currentIp = normalizeIp(current.remoteIp);
            boolean sameEndpoint = tcpConnection == null && current.endpoint != null && current.endpoint.equals(sender);
            boolean sameIp = currentIp.equals(senderIp);
            boolean expired = (now - current.lastSeen) > PLAYER_TIMEOUT_NANOS;

            if (sameEndpoint || sameIp)
            {
            }
            else if (expired)
            {
                boolean reserved = reserveTimedOutPlayer(usernameKey, current, now);

                if (reserved)
                {
                    dbg("Rejected %s from %s because username is reserved for previous IP\n", username, sender);
                    safeReply(socket, sender, tcpConnection, "ERROR\tUSERNAME_TAKEN");
                    return;
                }

                current = players.get(usernameKey);

                if (current != null)
                {
                    dbg("Rejected duplicate username %s from %s because owned by %s\n",
                            username, sender, describeSessionAddress(current));
                    safeReply(socket, sender, tcpConnection, "ERROR\tUSERNAME_TAKEN");
                    return;
                }
            }
            else
            {
                dbg("Rejected duplicate username %s from %s because owned by %s\n",
                        username, sender, describeSessionAddress(current));
                safeReply(socket, sender, tcpConnection, "ERROR\tUSERNAME_TAKEN");
                return;
            }
        }

        if (!players.containsKey(usernameKey) && tcpConnection == null)
        {
            return;
        }

        if (!players.containsKey(usernameKey))
        {
            UsernameReservation reservation = reservedUsernames.get(usernameKey);

            if (reservation != null && now >= reservation.expiresAt)
            {
                reservedUsernames.remove(usernameKey, reservation);
                reservation = null;
            }

            if (reservation != null)
            {
                if (!reservation.ip.equals(senderIp))
                {
                    dbg("Rejected username %s from %s because it is reserved for IP %s\n",
                            username, sender, reservation.ip);
                    safeReply(socket, sender, tcpConnection, "ERROR\tUSERNAME_TAKEN");
                    return;
                }

                reservedUsernames.remove(usernameKey, reservation);
                dbg("Restored reserved username %s for %s\n", username, sender);
            }

            int playerIdToUse;

            if (packetPlayerId > 0)
            {
                PlayerSession idOwner = findPlayerById(packetPlayerId);

                if (idOwner != null && !normalizeUsernameKey(idOwner.username).equals(usernameKey))
                {
                    dbg("Rejected username %s from %s because requested id=%d is already owned by %s\n",
                            username,
                            sender,
                            packetPlayerId,
                            idOwner.username);

                    safeReply(socket, sender, tcpConnection, "ERROR\tID_TAKEN");
                    return;
                }

                playerIdToUse = packetPlayerId;
                bumpNextPlayerIdPast(playerIdToUse);
            }
            else
            {
                playerIdToUse = allocateNewPlayerId();
            }

            PlayerSession created = new PlayerSession(
                    playerIdToUse,
                    username,
                    tcpConnection == null ? sender : null,
                    now);
            created.remoteIp = senderIp;
            PlayerSession race = players.putIfAbsent(usernameKey, created);

            if (race == null)
            {
                current = created;
                playersById.put(created.playerId, created);
                dbg("Accepted username %s id=%d for %s\n", username, created.playerId, sender);
            }
            else
            {
                current = race;

                String currentIp = normalizeIp(current.remoteIp);

                if (!currentIp.equals(senderIp))
                {
                    dbg("Rejected duplicate username %s from %s because owned by %s\n",
                            username, sender, describeSessionAddress(current));
                    safeReply(socket, sender, tcpConnection, "ERROR\tUSERNAME_TAKEN");
                    return;
                }
            }
        }

        if (tcpConnection != null)
        {
            current.markTcp(tcpConnection, now);
            current.tcpPacketsReceived.incrementAndGet();
            current.tcpBytesReceived.addAndGet(msg.length());
            recordTransportRoute("TCP RX", opcode, msg.length());
        }
        else
        {
            current.markUdp(sender, now);
            current.udpPacketsReceived.incrementAndGet();
            current.udpBytesReceived.addAndGet(msg.length());
            recordTransportRoute("UDP RX", opcode, msg.length());
        }

        if (!current.transportReady())
        {
            return;
        }

        List<String> fields = new ArrayList<>();

        for (int i = fieldsStart; i < parts.length; i++)
        {
            fields.add(unescapeField(parts[i]));
        }

        List<String> frozenFields = Collections.unmodifiableList(fields);

        boolean realtimeRoute = isClientRealtimeOpcode(opcode);
        if ((tcpConnection != null) == realtimeRoute)
        {
            transportMisroutes.incrementAndGet();
            return;
        }

        if (!isPlayerStateOpcode(opcode))
        {
            if ("PVFXS".equals(opcode) || "PVFXI".equals(opcode))
            {
                handlePlayerVfx(socket, current, opcode, frozenFields);
            }
            else
            {
            if (isNpcWorldOpcode(opcode))
            {
                synchronized (NPC_WORLD_LOCK)
                {
                    handleNpcMessage(opcode, current, frozenFields, now);
                }
            }
            else
            {
                handleNpcMessage(opcode, current, frozenFields, now);
            }
            }
            return;
        }

        if ("MOVE".equals(opcode))
        {
            if (frozenFields.size() < 8)
            {
                return;
            }

            storePosition(current, frozenFields, MOVE_POSITION_OFFSET);

            sendChunk(socket, nearbyRecipients(current), current, "MOVE", frozenFields);
            return;
        }

        if ("UPDATE1A".equals(opcode))
        {
            storePosition(current, frozenFields, UPDATE1A_POSITION_OFFSET);
            current.update1A.store(frozenFields);
        }
        else if ("UPDATE1B".equals(opcode))
        {
            current.update1B.store(frozenFields);
        }
        else if ("UPDATE2A".equals(opcode))
        {
            current.update2A.store(frozenFields);
        }
        else if ("UPDATE2B".equals(opcode))
        {
            current.update2B.store(frozenFields);
        }
        else if ("UPDATE3".equals(opcode))
        {
            current.update3.store(frozenFields);
        }
        else if ("UPDATE4".equals(opcode))
        {
            current.update4.store(frozenFields);
        }
    }

    private static void handleNpcMessage(String opcode, PlayerSession session, List<String> fields, long now)
    {
        if (fields.isEmpty())
        {
            return;
        }

        if ("TSYNC".equals(opcode))
        {
            Long clientMs = parseLongOrNull(fields.get(0));

            if (clientMs == null)
            {
                return;
            }

            Integer reportedRtt = fields.size() > 1 ? parseIntegerOrNull(fields.get(1)) : null;

            if (reportedRtt != null && reportedRtt >= 0 && reportedRtt <= 2000)
            {
                session.rttMs = reportedRtt;
            }

            Integer reportedSyncMode = fields.size() > 2 ? parseIntegerOrNull(fields.get(2)) : null;

            if (reportedSyncMode != null)
            {
                session.npcSyncMode = (reportedSyncMode == 1) ? 1 : 0;
            }

            List<String> reply = new ArrayList<>();
            reply.add(Long.toString(clientMs));
            reply.add(Long.toString(serverMs()));

            session.lastTimeSyncMs = serverMs();
            queueOutbound(session, "TSYNCR", reply);
            return;
        }


        if ("NPCADD".equals(opcode))
        {
            final int stride = 17;

            Long snapshotMs = parseLongOrNull(fields.get(0));
            Integer count = fields.size() > 1 ? parseIntegerOrNull(fields.get(1)) : null;

            if (snapshotMs == null || count == null || count < 0)
            {
                return;
            }

            final long stamp = clampSnapshotTime(snapshotMs);
            Set<Integer> claimedBindings = new HashSet<>();
            List<String> registered = new ArrayList<>();

            for (int i = 0; i < count; i++)
            {
                int base = 2 + (i * stride);

                if (base + stride > fields.size())
                {
                    break;
                }

                Integer guid = parseIntegerOrNull(fields.get(base));

                if (guid == null || guid == 0)
                {
                    continue;
                }

                session.goneGuids.remove(guid);

                Integer area = parseIntegerOrNull(fields.get(base + 1));
                Double x = parseDoubleOrNull(fields.get(base + 4));
                Double y = parseDoubleOrNull(fields.get(base + 5));
                Double z = parseDoubleOrNull(fields.get(base + 6));
                Double heading = parseDoubleOrNull(fields.get(base + 7));
                Integer hp = parseIntegerOrNull(fields.get(base + 8));
                Integer flags = parseIntegerOrNull(fields.get(base + 9));
                Integer target = parseIntegerOrNull(fields.get(base + 10));
                Integer localCount = parseIntegerOrNull(fields.get(base + 11));
                Integer terminalState = parseIntegerOrNull(fields.get(base + 12));
                Integer terminalAttacker = parseIntegerOrNull(fields.get(base + 13));
                String identityKey = sanitizeToken(fields.get(base + 14));
                Integer identityFlags = parseIntegerOrNull(fields.get(base + 15));
                Integer replacementGuid = parseIntegerOrNull(fields.get(base + 16));

                if (area == null || x == null || y == null || z == null || heading == null
                        || hp == null || flags == null || target == null || localCount == null
                        || terminalState == null || terminalAttacker == null || identityFlags == null
                        || replacementGuid == null)
                {
                    continue;
                }

                String rawTypeCode = fields.get(base + 2);
                String typeCode = sanitizeNpcTypeToken(rawTypeCode);
                boolean rejectedQuestType = rawTypeCode != null
                        && rawTypeCode.trim().startsWith("quest:")
                        && "-".equals(typeCode);
                NpcRegistry.Npc admitted = null;
                NpcRegistry.Npc forcedBinding = null;

                if (!rejectedQuestType && replacementGuid != 0)
                {
                    admitted = NpcRegistry.remapPersistent(
                            session.playerId,
                            replacementGuid,
                            guid,
                            typeCode,
                            identityKey,
                            identityFlags,
                            now);

                    if (admitted != null)
                    {
                        dbg("NPC %s REMAPPED | owner=%s guid=%d -> %d\n",
                                NpcRegistry.describeNpc(admitted),
                                describePlayerId(session.playerId),
                                replacementGuid,
                                guid);
                    }
                }

                NpcRegistry.Binding existingBinding = NpcRegistry.bindingByGuid(session.playerId, guid);
                boolean exactBindingMatches = NpcRegistry.bindingIdentityMatches(
                        existingBinding, typeCode, sanitizeToken(fields.get(base + 3)), identityKey);
                if (admitted == null && exactBindingMatches)
                {
                    NpcRegistry.Npc bound = NpcRegistry.get(existingBinding.canonicalId);
                    if (bound != null && (!bound.alive
                            || (bound.ownerPlayerId != 0 && bound.ownerPlayerId != session.playerId)))
                    {
                        forcedBinding = bound;
                    }
                    else if (bound != null && bound.ownerPlayerId == 0)
                    {
                        NpcRegistry.reclaimExactBinding(session.playerId, guid, now);
                    }
                }

                if (admitted == null && forcedBinding == null && !exactBindingMatches && !rejectedQuestType)
                {
                    forcedBinding = NpcRegistry.findIdentityBindable(
                            session.playerId,
                            guid,
                            typeCode,
                            sanitizeToken(fields.get(base + 3)),
                            identityKey,
                            identityFlags);
                }

                if (admitted == null && forcedBinding == null && !exactBindingMatches && !rejectedQuestType)
                {
                    forcedBinding = NpcRegistry.findBindable(
                            session.playerId,
                            guid,
                            area,
                            typeCode,
                            sanitizeToken(fields.get(base + 3)),
                            identityKey,
                            identityFlags,
                            x, y, z,
                            claimedBindings);
                }

                if (admitted == null && forcedBinding != null)
                {
                    admitted = null;
                }
                else if (admitted == null && rejectedQuestType)
                {
                    dbg("QFOE invalid type rejected from %s guid=%d\n",
                            describePlayerId(session.playerId), guid);
                }
                else if (admitted == null)
                {
                    admitted = NpcRegistry.upsert(
                            session.playerId,
                            guid,
                            area,
                            typeCode,
                            sanitizeToken(fields.get(base + 3)),
                            identityKey,
                            identityFlags,
                            x, y, z, heading,
                            clampPermille(hp),
                            flags,
                            target,
                            NpcRegistry.sanitizeTerminalState(terminalState),
                            terminalAttacker,
                            localCount,
                            stamp,
                            now);
                }

                if (admitted == null)
                {
                    NpcRegistry.Npc bindable = forcedBinding != null ? forcedBinding
                            : rejectedQuestType ? null : NpcRegistry.findBindable(
                            session.playerId,
                            guid,
                            area,
                             typeCode,
                             sanitizeToken(fields.get(base + 3)),
                             identityKey,
                             identityFlags,
                             x, y, z,
                            claimedBindings);

                    if (bindable != null)
                    {
                        claimedBindings.add(bindable.npcId);

                        if (!bindable.alive)
                        {
                            NpcRegistry.requestTerminalRebind(bindable, session.playerId, guid, now);
                        }
                        NpcRegistry.bindObservation(
                                session.playerId, guid, bindable.npcId,
                                NpcRegistry.BINDING_NATIVE, false, now);
                    }
                    List<String> drop = new ArrayList<>();
                    drop.add("1");
                    drop.add(Integer.toString(guid));
                    drop.add(Integer.toString(bindable == null ? 0 : bindable.npcId));
                    drop.add(Integer.toString(bindable == null ? 0 : bindable.lifecycleRevision));

                    queueOutbound(session, "NPCDROP", drop);
                }
                else
                {
                    registered.add(Integer.toString(guid));
                    registered.add(Integer.toString(admitted.npcId));
                    registered.add(Integer.toString(admitted.authorityRevision));
                }
            }

            if (!registered.isEmpty())
            {
                registered.add(0, Integer.toString(registered.size() / 3));
                queueOutbound(session, "NPCREG", registered);
            }

            return;
        }

        if ("NPCBIND".equals(opcode))
        {
            Integer count = parseIntegerOrNull(fields.get(0));
            if (count == null || count < 0)
            {
                return;
            }
            for (int i = 0; i < count; i++)
            {
                int base = 1 + i * 4;
                if (base + 4 > fields.size())
                {
                    break;
                }
                Integer canonicalId = parseIntegerOrNull(fields.get(base));
                Integer localGuid = parseIntegerOrNull(fields.get(base + 1));
                Integer kind = parseIntegerOrNull(fields.get(base + 2));
                Integer lifecycleRevision = parseIntegerOrNull(fields.get(base + 3));
                if (canonicalId == null || localGuid == null || kind == null || lifecycleRevision == null)
                {
                    continue;
                }
                NpcRegistry.acknowledgeBinding(
                        session.playerId, canonicalId, localGuid, kind, lifecycleRevision, now);
            }
            return;
        }

        if ("NPCFAST".equals(opcode))
        {
            Long snapshotMs = parseLongOrNull(fields.get(0));
            Integer count = fields.size() > 1 ? parseIntegerOrNull(fields.get(1)) : null;
            if (snapshotMs == null || count == null || count < 0)
            {
                return;
            }
            final long stamp = clampSnapshotTime(snapshotMs);
            final int stride = 10;
            for (int i = 0; i < count; i++)
            {
                int base = 2 + i * stride;
                if (base + stride > fields.size())
                {
                    break;
                }
                Integer guid = parseIntegerOrNull(fields.get(base));
                Integer authorityRevision = parseIntegerOrNull(fields.get(base + 1));
                Integer sequence = parseIntegerOrNull(fields.get(base + 2));
                Double x = parseDoubleOrNull(fields.get(base + 3));
                Double y = parseDoubleOrNull(fields.get(base + 4));
                Double z = parseDoubleOrNull(fields.get(base + 5));
                Double heading = parseDoubleOrNull(fields.get(base + 6));
                Integer hp = parseIntegerOrNull(fields.get(base + 7));
                Integer flags = parseIntegerOrNull(fields.get(base + 8));
                Integer target = parseIntegerOrNull(fields.get(base + 9));
                if (guid == null || authorityRevision == null || sequence == null
                        || x == null || y == null || z == null || heading == null
                        || hp == null || flags == null || target == null)
                {
                    continue;
                }
                NpcRegistry.moveFast(
                        session.playerId, guid, authorityRevision, sequence,
                        x, y, z, heading, clampPermille(hp), flags, target, stamp, now);
            }
            return;
        }

        if ("NPCUPD".equals(opcode))
        {
            Long snapshotMs = parseLongOrNull(fields.get(0));
            Integer count = fields.size() > 1 ? parseIntegerOrNull(fields.get(1)) : null;

            if (snapshotMs == null || count == null || count < 0)
            {
                return;
            }

            final long stamp = clampSnapshotTime(snapshotMs);
            int cursor = 2;

            for (int i = 0; i < count; i++)
            {
                if (cursor + 2 > fields.size())
                {
                    break;
                }

                Integer guid = parseIntegerOrNull(fields.get(cursor));
                Integer mask = parseIntegerOrNull(fields.get(cursor + 1));

                if (guid == null || mask == null || mask < 0)
                {
                    break;
                }

                int entrySize = 2;

                if ((mask & 1) != 0)
                {
                    entrySize += 3;
                }

                if ((mask & 2) != 0)
                {
                    entrySize += 1;
                }

                if ((mask & 4) != 0)
                {
                    entrySize += 1;
                }

                if ((mask & 8) != 0)
                {
                    entrySize += 1;
                }

                if ((mask & 16) != 0)
                {
                    entrySize += 1;
                }

                if ((mask & 32) != 0)
                {
                    entrySize += 1;
                }

                if ((mask & 64) != 0)
                {
                    entrySize += 1;
                }

                if (cursor + entrySize > fields.size())
                {
                    break;
                }

                int at = cursor + 2;
                cursor += entrySize;

                if (guid == 0)
                {
                    continue;
                }

                NpcRegistry.Npc existing = NpcRegistry.getByOwnerGuid(session.playerId, guid);

                if (existing == null || existing.ownerPlayerId != session.playerId)
                {
                    session.goneGuids.add(guid);
                    continue;
                }

                double x = existing.x;
                double y = existing.y;
                double z = existing.z;
                double heading = existing.heading;
                int hp = existing.hpPermille;
                int flags = existing.flags;
                int target = existing.targetPlayerId;
                int terminalState = existing.terminalState;
                int terminalAttacker = existing.terminalAttackerId;
                boolean valid = true;

                if ((mask & 1) != 0)
                {
                    Double px = parseDoubleOrNull(fields.get(at));
                    Double py = parseDoubleOrNull(fields.get(at + 1));
                    Double pz = parseDoubleOrNull(fields.get(at + 2));
                    at += 3;

                    if (px == null || py == null || pz == null)
                    {
                        valid = false;
                    }
                    else
                    {
                        x = px;
                        y = py;
                        z = pz;
                    }
                }

                if (valid && (mask & 2) != 0)
                {
                    Double ph = parseDoubleOrNull(fields.get(at));
                    at += 1;

                    if (ph == null)
                    {
                        valid = false;
                    }
                    else
                    {
                        heading = ph;
                    }
                }

                if (valid && (mask & 4) != 0)
                {
                    Integer phsi = parseIntegerOrNull(fields.get(at));
                    at += 1;

                    if (phsi == null)
                    {
                        valid = false;
                    }
                    else
                    {
                        hp = clampPermille(phsi);
                    }
                }

                if (valid && (mask & 8) != 0)
                {
                    Integer pf = parseIntegerOrNull(fields.get(at));
                    at += 1;

                    if (pf == null)
                    {
                        valid = false;
                    }
                    else
                    {
                        flags = pf;
                    }
                }

                if (valid && (mask & 16) != 0)
                {
                    Integer pt = parseIntegerOrNull(fields.get(at));
                    at += 1;

                    if (pt == null)
                    {
                        valid = false;
                    }
                    else
                    {
                        target = pt;
                    }
                }

                if (valid && (mask & 32) != 0)
                {
                    Integer pts = parseIntegerOrNull(fields.get(at));
                    at += 1;

                    if (pts == null)
                    {
                        valid = false;
                    }
                    else
                    {
                        terminalState = NpcRegistry.sanitizeTerminalState(pts);
                    }
                }

                if (valid && (mask & 64) != 0)
                {
                    Integer pta = parseIntegerOrNull(fields.get(at));
                    at += 1;

                    if (pta == null)
                    {
                        valid = false;
                    }
                    else
                    {
                        terminalAttacker = pta;
                    }
                }

                if (!valid)
                {
                    continue;
                }

                NpcRegistry.Npc moved = NpcRegistry.move(
                        session.playerId,
                        guid,
                        x, y, z, heading,
                        hp,
                        flags,
                        target,
                        terminalState,
                        terminalAttacker,
                        stamp,
                        now);

                if (moved == null)
                {
                    session.goneGuids.add(guid);
                }
            }

            return;
        }

        if ("NPCDEL".equals(opcode))
        {
            Integer count = parseIntegerOrNull(fields.get(0));

            if (count == null || count < 0)
            {
                return;
            }

            for (int i = 0; i < count; i++)
            {
                int base = 1 + (i * 2);

                if (base + 2 > fields.size())
                {
                    break;
                }

                Integer guid = parseIntegerOrNull(fields.get(base));

                if (guid != null)
                {
                    NpcRegistry.Npc existing = NpcRegistry.getByOwnerGuid(session.playerId, guid);
                    boolean wasAlive = existing != null && existing.alive;

                    if (NpcRegistry.remove(session.playerId, guid) && wasAlive)
                    {
                        NpcRegistry.forgetKnown(session, existing.npcId);
                        session.goneGuids.remove(guid);
                    }
                }
            }

            return;
        }

        if ("NPCHIT".equals(opcode))
        {
            Integer count = parseIntegerOrNull(fields.get(0));

            if (count == null || count < 0)
            {
                return;
            }

            for (int i = 0; i < count; i++)
            {
                int base = 1 + (i * 4);

                if (base + 4 > fields.size())
                {
                    break;
                }

                Integer canonicalId = parseIntegerOrNull(fields.get(base));
                Integer permille = parseIntegerOrNull(fields.get(base + 1));
                Integer eventId = parseIntegerOrNull(fields.get(base + 2));
                Long atMs = parseLongOrNull(fields.get(base + 3));

                if (canonicalId == null || permille == null || eventId == null || atMs == null)
                {
                    continue;
                }

                handleHit(session, canonicalId, clampPermille(permille), eventId, clampSnapshotTime(atMs));
            }

            return;
        }

        if ("PSTATE".equals(opcode))
        {
            if (fields.isEmpty())
            {
                return;
            }

            Integer state = parseIntegerOrNull(fields.get(0));

            if (state == null)
            {
                return;
            }

            boolean nowPaused = state != 0;

            if (session.paused != nowPaused)
            {
                session.paused = nowPaused;

                if (nowPaused)
                {
                    int cleared = NpcRegistry.clearTargetsForPlayer(session.playerId);

                    if (cleared > 0)
                    {
                        dbg("NPC targets cleared for paused %s: %d\n",
                                describePlayerId(session.playerId), cleared);
                    }
                }
                else
                {
                    int replayed = NpcRegistry.requestDeathReplayForKnown(session, now);
                    NpcRegistry.requestBehaviorReplayForKnown(session);

                    if (replayed > 0)
                    {
                        dbg("NPC replay queued for resumed %s: %d deaths\n",
                                describePlayerId(session.playerId), replayed);
                    }
                }

                dbg("PLAYER %s %s\n",
                        describePlayerId(session.playerId),
                        nowPaused ? "PAUSED" : "RESUMED");
            }

            return;
        }

        if ("NPCNOPE".equals(opcode))
        {
            Integer count = parseIntegerOrNull(fields.get(0));

            if (count == null || count < 0)
            {
                return;
            }

            for (int i = 0; i < count; i++)
            {
                int base = 1 + i;

                if (base >= fields.size())
                {
                    break;
                }

                Integer canonicalId = parseIntegerOrNull(fields.get(base));

                if (canonicalId == null)
                {
                    continue;
                }

                NpcRegistry.declineHandover(session.playerId, canonicalId, now);
            }

            return;
        }

        if ("PJOIN".equals(opcode))
        {
            if (!fields.isEmpty())
            {
                partyRequest(session, fields.get(0));
            }

            return;
        }

        if ("SAVEBEG".equals(opcode) || "SAVECHK".equals(opcode) || "SAVEEND".equals(opcode)
                || "SAVENACK".equals(opcode) || "SAVEACK".equals(opcode) || "SAVEWANT".equals(opcode))
        {
            relaySaveTransfer(session, opcode, fields);
            return;
        }

        if ("PRESP".equals(opcode))
        {
            if (fields.size() >= 2)
            {
                partyRespond(session, fields.get(0), "1".equals(fields.get(1)));
            }

            return;
        }

        if ("SCENE".equals(opcode))
        {
            relaySceneStart(session, fields);
            return;
        }

        if ("QITEM".equals(opcode))
        {
            relayQuestItem(session, fields);
            return;
        }

        if ("PCOOP".equals(opcode))
        {
            if (!fields.isEmpty())
            {
                session.coopMode = "1".equals(fields.get(0));

                dbg("PARTY %s co-op mode %s\n",
                        normalizeUsernameKey(session.username),
                        session.coopMode ? "ENABLED" : "disabled");

                notifyLeaderOfCoop(session);
            }

            return;
        }

        if ("PLEAVE".equals(opcode))
        {
            partyRemoveMember(normalizeUsernameKey(session.username), "requested");
            return;
        }

        if ("NPCFREE".equals(opcode))
        {
            Integer count = parseIntegerOrNull(fields.get(0));

            if (count == null || count < 0)
            {
                return;
            }

            int released = 0;

            for (int i = 0; i < count; i++)
            {
                int base = 1 + i;

                if (base >= fields.size())
                {
                    break;
                }

                Integer localGuid = parseIntegerOrNull(fields.get(base));

                if (localGuid == null)
                {
                    continue;
                }

                if (NpcRegistry.releaseOwned(session.playerId, localGuid, now))
                {
                    released++;
                }
            }

            session.lastReleaseNanos = now;

            if (released > 0)
            {
                dbg("NPC released %d entities from %s (suspended)\n", released, describePlayerId(session.playerId));
            }

            return;
        }

        if ("NPCTAKE".equals(opcode))
        {
            Integer count = parseIntegerOrNull(fields.get(0));

            if (count == null || count < 0)
            {
                return;
            }

            for (int i = 0; i < count; i++)
            {
                int base = 1 + (i * 2);

                if (base + 2 > fields.size())
                {
                    break;
                }

                Integer canonicalId = parseIntegerOrNull(fields.get(base));
                Integer localGuid = parseIntegerOrNull(fields.get(base + 1));

                if (canonicalId == null || localGuid == null)
                {
                    continue;
                }

                int[] previous = NpcRegistry.take(session.playerId, canonicalId, localGuid, now);

                if (previous == null || previous[0] == 0 || previous[1] == 0)
                {
                    continue;
                }

                if (NpcRegistry.ownsGuid(previous[0], previous[1]))
                {
                    continue;
                }

                PlayerSession loser = findPlayerById(previous[0]);

                if (loser == null)
                {
                    continue;
                }

                List<String> drop = new ArrayList<>();
                drop.add("1");
                drop.add(Integer.toString(previous[1]));
                drop.add(Integer.toString(canonicalId));
                NpcRegistry.Npc taken = NpcRegistry.get(canonicalId);
                drop.add(Integer.toString(taken == null ? 0 : taken.lifecycleRevision));

                queueOutbound(loser, "NPCDROP", drop);
            }

            return;
        }

        if ("NPCWANT".equals(opcode))
        {
            Integer count = parseIntegerOrNull(fields.get(0));

            if (count == null || count < 0)
            {
                return;
            }

            int resent = 0;

            for (int i = 0; i < count; i++)
            {
                int base = 1 + i;

                if (base >= fields.size())
                {
                    break;
                }

                Integer canonicalId = parseIntegerOrNull(fields.get(base));

                if (canonicalId == null)
                {
                    continue;
                }

                if (session.knownNpcs.contains(canonicalId))
                {
                    NpcRegistry.forgetKnown(session, canonicalId);
                    resent++;
                }
            }

            if (resent > 0)
            {
                npcResends.addAndGet(resent);
            }

            return;
        }

        if ("NPCACK".equals(opcode))
        {
            Integer count = parseIntegerOrNull(fields.get(0));

            if (count == null || count < 0)
            {
                return;
            }

            for (int i = 0; i < count; i++)
            {
                int base = 1 + (i * 4);

                if (base + 4 > fields.size())
                {
                    break;
                }

                Integer attackerId = parseIntegerOrNull(fields.get(base));
                Integer eventId = parseIntegerOrNull(fields.get(base + 1));
                Integer resultPermille = parseIntegerOrNull(fields.get(base + 2));
                Integer accepted = parseIntegerOrNull(fields.get(base + 3));

                if (attackerId == null || eventId == null || resultPermille == null || accepted == null)
                {
                    continue;
                }

                int[] routed = pendingAcks.remove(eventId);

                if (routed == null || routed[0] != attackerId || routed[1] != session.playerId)
                {
                    npcAcksRejected.incrementAndGet();
                    continue;
                }

                NpcRegistry.Npc acked = NpcRegistry.get(routed[2]);

                if (acked != null)
                {
                    NpcRegistry.clearPendingDamage(acked);
                }

                PlayerSession attacker = findPlayerById(attackerId);

                if (attacker == null)
                {
                    continue;
                }

                List<String> ack = new ArrayList<>();
                ack.add("1");
                ack.add(Integer.toString(eventId));
                ack.add(Integer.toString(clampPermille(resultPermille)));
                ack.add(accepted != 0 ? "1" : "0");

                queueOutbound(attacker, "NPCACKF", ack);
            }

            return;
        }

        if ("PSCALE".equals(opcode))
        {
            handlePartyScaling(session, fields);
            return;
        }

        if ("TPREQ".equals(opcode))
        {
            teleportRequest(session, fields.get(0));
            return;
        }

        if ("NPCTERM".equals(opcode))
        {
            Integer count = parseIntegerOrNull(fields.get(0));

            if (count == null || count < 0)
            {
                return;
            }

            for (int i = 0; i < count; i++)
            {
                int base = 1 + (i * 2);

                if (base + 2 > fields.size())
                {
                    break;
                }

                Integer canonicalId = parseIntegerOrNull(fields.get(base));
                Integer revision = parseIntegerOrNull(fields.get(base + 1));

                if (canonicalId != null && revision != null)
                {
                    NpcRegistry.acknowledgeTerminal(session.playerId, canonicalId, revision);
                }
            }

            return;
        }

        if ("NPCEVT".equals(opcode))
        {
            if (fields.size() < 13)
            {
                malformedPackets.incrementAndGet();
                return;
            }

            Integer clientEventId = parseIntegerOrNull(fields.get(0));
            Integer canonicalId = parseIntegerOrNull(fields.get(1));
            Integer localGuid = parseIntegerOrNull(fields.get(2));
            Integer authorityRevision = parseIntegerOrNull(fields.get(3));
            Integer kind = parseIntegerOrNull(fields.get(4));
            Integer sourceSequence = parseIntegerOrNull(fields.get(5));
            String eventName = sanitizeToken(fields.get(6));
            Integer int0 = parseIntegerOrNull(fields.get(7));
            Integer int1 = parseIntegerOrNull(fields.get(8));
            Double x = parseDoubleOrNull(fields.get(9));
            Double y = parseDoubleOrNull(fields.get(10));
            Double z = parseDoubleOrNull(fields.get(11));
            Double heading = parseDoubleOrNull(fields.get(12));

            if (clientEventId == null || canonicalId == null || localGuid == null
                    || authorityRevision == null || kind == null || sourceSequence == null
                    || "-".equals(eventName) || int0 == null || int1 == null
                    || x == null || y == null || z == null || heading == null)
            {
                malformedPackets.incrementAndGet();
                return;
            }

            NpcRegistry.recordBehavior(
                    session.playerId,
                    clientEventId,
                    canonicalId,
                    localGuid,
                    authorityRevision,
                    kind,
                    sourceSequence,
                    eventName,
                    int0,
                    int1,
                    x,
                    y,
                    z,
                    heading,
                    activeSessions(),
                    now);
            return;
        }

        if ("NPCEACK".equals(opcode))
        {
            Integer count = parseIntegerOrNull(fields.get(0));
            if (count == null || count < 0)
            {
                malformedPackets.incrementAndGet();
                return;
            }

            for (int i = 0; i < count; i++)
            {
                int base = 1 + i * 3;
                if (base + 3 > fields.size())
                {
                    break;
                }
                Integer canonicalId = parseIntegerOrNull(fields.get(base));
                Integer lifecycleRevision = parseIntegerOrNull(fields.get(base + 1));
                Integer sequence = parseIntegerOrNull(fields.get(base + 2));
                if (canonicalId != null && lifecycleRevision != null && sequence != null)
                {
                    NpcRegistry.acknowledgeBehavior(
                            session.playerId, canonicalId, lifecycleRevision, sequence);
                }
            }
            return;
        }

        if (unroutedOpcodes.add(opcode))
        {
            dbg("WARNING unhandled opcode %s accepted but no handler claimed it (check isUpdateOpcode vs handleNpcMessage)\n",
                    opcode);
        }
    }

    private static int clampPermille(int value)
    {
        if (value < -1)
        {
            return -1;
        }

        return Math.min(value, 1000);
    }

    private static long clampSnapshotTime(long value)
    {
        final long nowMs = serverMs();

        if (value <= 0 || value > nowMs + 1000L)
        {
            return nowMs;
        }

        if (value < nowMs - 5000L)
        {
            return nowMs - 5000L;
        }

        return value;
    }

    private static void handleHit(PlayerSession attacker, int canonicalId, int permille, int eventId, long atMs)
    {
        final long nowMs = serverMs();

        NpcRegistry.Npc npc = NpcRegistry.get(canonicalId);

        if (npc == null)
        {
            rejectHit(attacker, eventId, -1, "unknown entity");
            return;
        }

        if (npc.ownerPlayerId == attacker.playerId)
        {
            return;
        }

        if (NpcRegistry.isQuestFoe(npc) && !questVisibleTo(attacker, npc))
        {
            rejectHit(attacker, eventId, npc.hpPermille, "outside co-op roster");
            npcHitsRejected.incrementAndGet();
            return;
        }

        if (!npc.alive)
        {
            rejectHit(attacker, eventId, 0, "already dead");
            return;
        }

        if (permille <= 0)
        {
            rejectHit(attacker, eventId, npc.hpPermille, "no damage");
            return;
        }

        final double distance = NpcRegistry.hitDistance(npc, attacker, atMs);

        if (distance > NpcRegistry.HIT_RANGE)
        {
            passiveHitDistanceOutliers.incrementAndGet();
        }

        PlayerSession owner = findPlayerById(npc.ownerPlayerId);

        if (owner == null)
        {
            rejectHit(attacker, eventId, npc.hpPermille, "owner gone");
            return;
        }

        if (owner.pendingHits.size() >= MAX_PENDING_HITS)
        {
            rejectHit(attacker, eventId, npc.hpPermille, "owner backlog");
            return;
        }

        owner.pendingHits.add(new String[]
        {
            Integer.toString(npc.ownerLocalGuid),
            Integer.toString(attacker.playerId),
            Integer.toString(permille),
            Integer.toString(eventId),
            Long.toString(atMs)
        });

        pendingAcks.put(eventId, new int[] { attacker.playerId, owner.playerId, npc.npcId });
        NpcRegistry.notePendingDamage(npc, permille, attacker.playerId, System.nanoTime());

        if (pendingAcks.size() > MAX_PENDING_ACKS)
        {
            pendingAcks.clear();
        }

        npcHitsAccepted.incrementAndGet();

        dbg("NPC %s HIT %d permille by %s | rewindMs=%d distance=%.1f hp=%d owner=%s\n",
                NpcRegistry.describeNpc(npc),
                permille,
                describePlayerId(attacker.playerId),
                nowMs - atMs,
                distance,
                npc.hpPermille,
                describePlayerId(npc.ownerPlayerId));
    }

    private static void rejectHit(PlayerSession attacker, int eventId, int hpPermille, String reason)
    {
        List<String> ack = new ArrayList<>();
        ack.add("1");
        ack.add(Integer.toString(eventId));
        ack.add(Integer.toString(hpPermille));
        ack.add("0");

        queueOutbound(attacker, "NPCACKF", ack);
    }

    private static String sanitizeToken(String value)
    {
        if (value == null || value.isEmpty())
        {
            return "-";
        }

        String trimmed = value.trim();

        if (trimmed.isEmpty() || trimmed.length() > 160)
        {
            return "-";
        }

        for (int i = 0; i < trimmed.length(); i++)
        {
            char c = trimmed.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.'
                    || c == '/' || c == '\\';

            if (!allowed)
            {
                return "-";
            }
        }

        return trimmed;
    }

    private static void handlePlayerVfx(
            DatagramSocket socket,
            PlayerSession source,
            String opcode,
            List<String> fields)
    {
        boolean impact = "PVFXI".equals(opcode);
        int requiredFields = "PVFXS".equals(opcode) ? 10 : 8;

        if (fields.size() != requiredFields)
        {
            return;
        }

        Integer eventId = parseIntegerOrNull(fields.get(0));
        Integer kind = parseIntegerOrNull(fields.get(1));
        Integer phase = impact ? parseIntegerOrNull(fields.get(7)) : 0;

        if (eventId == null || eventId <= 0 || kind == null || kind < 1 || kind > 2
                || fields.get(2).isEmpty() || fields.get(2).length() > 128
                || (impact && (phase == null || phase < 1 || phase > 3)))
        {
            return;
        }

        for (int index = 3; index < requiredFields - (impact ? 1 : 0); index++)
        {
            try
            {
                float value = Float.parseFloat(fields.get(index));
                if (!Float.isFinite(value) || Math.abs(value) > 1000000.0f)
                {
                    return;
                }
            }
            catch (NumberFormatException invalid)
            {
                return;
            }
        }

        List<PlayerSession> recipients = nearbyRecipients(source);
        recipients.remove(source);
        dbg("PVFX %s event=%d kind=%d item=%s source=%s recipients=%d\n",
                opcode,
                eventId,
                kind,
                fields.get(2),
                describePlayerId(source.playerId),
                recipients.size());
        sendChunk(socket, recipients, source, opcode, fields);
    }

    static String sanitizeNpcTypeToken(String value)
    {
        if (value == null)
        {
            return "-";
        }

        String trimmed = value.trim();

        if (!trimmed.startsWith("quest:"))
        {
            return sanitizeToken(trimmed);
        }

        if (trimmed.length() > 160)
        {
            return "-";
        }

        String questTag = sanitizeToken(trimmed.substring("quest:".length()));

        if ("-".equals(questTag))
        {
            return "-";
        }

        return "quest:" + questTag;
    }

    private static void npcLoop(DatagramSocket socket)
    {
        long lastHeartbeat = System.nanoTime();
        long tickCounter = 0;

        while (running.get())
        {
            try
            {
                long tickStart = System.nanoTime();
                long now = tickStart;
                tickCounter++;
                List<PlayerSession> sessions = activeSessions();

                Set<Integer> ownerOnline = new HashSet<>();

                for (PlayerSession session : sessions)
                {
                    ownerOnline.add(session.playerId);
                }

                synchronized (NPC_WORLD_LOCK)
                {
                    purgeExpiredPartyRequests(now);
                    purgeStaleSaveRelays(now);
                    pumpSaveRelays();

                    NpcRegistry.applyUnackedDamage(now);
                    NpcRegistry.pruneStale(ownerOnline, now);
                    if (scalingDirty.getAndSet(false) || tickCounter % NPC_SCALE_TICK_DIVIDER == 0)
                    {
                        if (NpcRegistry.recomputeScaling(sessions, NpcRegistry.HIT_RANGE_SQUARED))
                        {
                            scaleEpoch.incrementAndGet();
                        }
                    }
                    final long snapshotMs = serverMs();

                    for (PlayerSession session : sessions)
                    {
                        relaySessionWork(socket, session, now, snapshotMs);
                    }
                    relayPauseStates(socket, sessions);
                    relayDeaths(socket, sessions, now);
                    relayBehaviorEvents(sessions, now);
                    if (tickCounter % PLAYER_VIS_TICK_DIVIDER == 0)
                    {
                        relayVisibility(socket, sessions, now);
                    }
                    relayHandovers(socket, sessions, now);
                    relayPartyHeartbeat(now);

                    if (!sessions.isEmpty() && (now - lastHeartbeat) >= NPC_HEARTBEAT_NANOS)
                    {
                        lastHeartbeat = now;

                        dbg("NPC sync: players=%d npcs=%d admitted=%d rejectedDup=%d | packets spawn=%d move=%d end=%d | tick p99=%.2fms overruns=%d\n",
                                sessions.size(),
                                NpcRegistry.npcCount(),
                                NpcRegistry.admittedCount(),
                                NpcRegistry.rejectedDuplicateCount(),
                                npcSpawnPacketsSent.get(),
                                npcMovePacketsSent.get(),
                                npcEndPacketsSent.get(),
                                tickP99Ms(),
                                tickOverruns.get());

                        logSyncGroups(sessions);
                        logTargetSnapshot();
                    }
                }

                final long tickNanos = System.nanoTime() - tickStart;

                recordTickDuration(tickNanos);

                final long remainingNanos = NPC_TICK_NANOS - (System.nanoTime() - tickStart);

                if (remainingNanos > 0)
                {
                    Thread.sleep(remainingNanos / 1_000_000L, (int) (remainingNanos % 1_000_000L));
                }
                else
                {
                    tickOverruns.incrementAndGet();
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }


    static String describePlayerId(int playerId)
    {
        PlayerSession session = findPlayerById(playerId);
        return session == null ? ("#" + playerId) : (session.username + "#" + playerId);
    }

    static String describeTargetId(int playerId)
    {
        return playerId <= 0 ? "none" : describePlayerId(playerId);
    }

    private static void logTargetSnapshot()
    {
        List<NpcRegistry.Npc> targeted = NpcRegistry.targetedNpcs();

        if (targeted.isEmpty())
        {
            return;
        }

        dbg("NPC targets: %d locked\n", targeted.size());

        int shown = 0;

        for (NpcRegistry.Npc npc : targeted)
        {
            dbgNotime("    %s -> %s | hp=%d permille owner=%s spot=%s\n",
                    NpcRegistry.describeNpc(npc),
                    describeTargetId(npc.targetPlayerId),
                    npc.hpPermille,
                    describePlayerId(npc.ownerPlayerId),
                    NpcRegistry.describeSpot(npc));

            shown++;

            if (shown >= NPC_TARGET_LOG_LIMIT)
            {
                dbgNotime("    ... %d more\n", targeted.size() - shown);
                break;
            }
        }
    }

    private static void relayPauseStates(DatagramSocket socket, List<PlayerSession> sessions)
    {
        List<String> changed = new ArrayList<>();
        int count = 0;

        for (PlayerSession session : sessions)
        {
            if (session.paused == session.pausedBroadcast)
            {
                continue;
            }

            session.pausedBroadcast = session.paused;

            changed.add(Integer.toString(session.playerId));
            changed.add(session.paused ? "1" : "0");
            count++;
        }

        if (count == 0)
        {
            return;
        }

        changed.add(0, Integer.toString(count));

        for (PlayerSession session : sessions)
        {
            sendNpcPacket(socket, session, "PSTATEF", changed);
        }
    }

    private static long npcSendIntervalNanos(PlayerSession session, NpcRegistry.Npc npc)
    {
        if (!session.hasPosition)
        {
            return NPC_LOD_MID_NANOS;
        }

        double dx = session.posX - npc.x;
        double dy = session.posY - npc.y;
        double dz = session.posZ - npc.z;
        double squared = (dx * dx) + (dy * dy) + (dz * dz);

        if (squared <= NPC_LOD_NEAR_SQUARED)
        {
            return NPC_LOD_NEAR_NANOS;
        }

        if (squared <= NPC_LOD_MID_SQUARED)
        {
            return NPC_LOD_MID_NANOS;
        }

        return NPC_LOD_FAR_NANOS;
    }

    private static void logSyncGroups(List<PlayerSession> sessions)
    {
        StringBuilder sb = new StringBuilder();

        for (PlayerSession session : sessions)
        {
            int owned = NpcRegistry.countOwnedBy(session.playerId);
            String scope;

            if (session.partyId != 0)
            {
                scope = "party#" + session.partyId;
            }
            else if (session.npcSyncMode == 0)
            {
                scope = "world";
            }
            else
            {
                scope = "standalone";
            }

            if (sb.length() > 0)
            {
                sb.append(" | ");
            }

            sb.append(String.format("%s scope=%s cfg=%s owns=%d hpScale=%s",
                    describePlayerId(session.playerId),
                    scope,
                    session.npcSyncMode == 0 ? "world" : "party",
                    owned,
                    describeOwnedScale(session)));
        }

        if (sb.length() > 0)
        {
            dbg("NPC scopes: %s\n", sb.toString());
        }
    }

    private static String describeOwnedScale(PlayerSession session)
    {
        int peak = NpcScaling.SCALE_UNIT;
        int peakPlayers = 1;

        for (NpcRegistry.Npc npc : NpcRegistry.ownedBy(session.playerId))
        {
            if (npc.scaleMilli > peak)
            {
                peak = npc.scaleMilli;
                peakPlayers = npc.scalePlayerCount;
            }
        }

        return String.format("x%.2f/%dp", peak / (double) NpcScaling.SCALE_UNIT, peakPlayers);
    }

    public static boolean canShareNpcs(PlayerSession a, PlayerSession b)
    {
        if (a == null || b == null)
        {
            return false;
        }

        if (a.playerId == b.playerId)
        {
            return true;
        }

        if (a.partyId != 0 || b.partyId != 0)
        {
            return a.partyId != 0 && a.partyId == b.partyId;
        }

        return a.npcSyncMode == 0 && b.npcSyncMode == 0;
    }

    public static PlayerSession sessionByPlayerId(int playerId)
    {
        if (playerId == 0)
        {
            return null;
        }

        return playersById.get(playerId);
    }

    private static Party partyOf(String usernameKey)
    {
        Integer id = playerParty.get(usernameKey);

        return (id == null) ? null : parties.get(id);
    }

    private static void relayBehaviorEvents(List<PlayerSession> sessions, long now)
    {
        Map<Integer, PlayerSession> byId = new HashMap<>();
        for (PlayerSession session : sessions)
        {
            byId.put(session.playerId, session);
        }

        for (NpcRegistry.BehaviorEvent event : NpcRegistry.pendingBehaviorEvents(now))
        {
            NpcRegistry.Npc npc = NpcRegistry.get(event.canonicalId);
            for (Integer playerId : new ArrayList<>(event.pending))
            {
                PlayerSession session = byId.get(playerId);
                if (session == null || session.paused
                        || npc == null || !session.knownNpcs.contains(npc.boxedId)
                        || !NpcRegistry.sharesSyncGroup(session, npc)
                        || !NpcRegistry.behaviorReadyForSend(event, playerId, now))
                {
                    continue;
                }

                List<String> fields = new ArrayList<>();
                fields.add("1");
                fields.add(Integer.toString(event.canonicalId));
                fields.add(Integer.toString(event.lifecycleRevision));
                fields.add(Integer.toString(event.authorityRevision));
                fields.add(Integer.toString(event.sequence));
                fields.add(Integer.toString(event.kind));
                fields.add(Integer.toString(event.sourceSequence));
                fields.add(event.eventName);
                fields.add(Integer.toString(event.int0));
                fields.add(Integer.toString(event.int1));
                fields.add(Double.toString(event.x));
                fields.add(Double.toString(event.y));
                fields.add(Double.toString(event.z));
                fields.add(Double.toString(event.heading));
                queueOutbound(session, "NPCEVTF", fields);
                NpcRegistry.markBehaviorSent(event, playerId, now);
            }
        }
    }

    static int scaleMilliFor(int playerCount, int partyId)
    {
        Party party = partyId > 0 ? parties.get(partyId) : null;

        if (party == null)
        {
            return NpcScaling.scaleMilliFor(playerCount);
        }

        return NpcScaling.scaleMilliFor(playerCount, party.scaleStepMilli(), party.scaleMaxMilli());
    }

    private static void handlePartyScaling(PlayerSession session, List<String> fields)
    {
        if (fields.size() < 2)
        {
            malformedPackets.incrementAndGet();
            return;
        }

        Integer stepMilli = parseIntegerOrNull(fields.get(0));
        Integer maxMilli = parseIntegerOrNull(fields.get(1));

        if (stepMilli == null || maxMilli == null
                || stepMilli < 0 || stepMilli > 10000 || stepMilli % 500 != 0
                || maxMilli < 1000 || maxMilli > 80000 || maxMilli % 1000 != 0)
        {
            malformedPackets.incrementAndGet();
            return;
        }

        session.partyScaleStepMilli = stepMilli;
        session.partyScaleMaxMilli = maxMilli;

        Party party = partyOf(normalizeUsernameKey(session.username));
        if (party != null && party.applyLeaderScaling(
                normalizeUsernameKey(session.username), stepMilli, maxMilli))
        {
            scalingDirty.set(true);
        }

        List<String> ackFields = new ArrayList<>();
        ackFields.add(Integer.toString(stepMilli));
        ackFields.add(Integer.toString(maxMilli));
        queueOutbound(session, "PSCALEACK", ackFields);
    }

    private static void refreshPartyScaling(Party party)
    {
        if (party == null)
        {
            return;
        }

        String leaderKey = party.leader();
        PlayerSession leader = leaderKey == null ? null : players.get(leaderKey);

        if (leader != null && party.applyLeaderScaling(
                leaderKey, leader.partyScaleStepMilli, leader.partyScaleMaxMilli))
        {
            scalingDirty.set(true);
        }
    }

    private static void sendPartyState(PlayerSession session, Party party)
    {
        List<String> fields = new ArrayList<>();

        if (party == null || party.size() < 2)
        {
            fields.add("0");
            fields.add("0");
            queueOutbound(session, "PARTY", fields);
            return;
        }

        List<String> members = party.snapshot();

        fields.add(Integer.toString(party.partyId));
        fields.add(Integer.toString(members.size()));

        for (String memberKey : members)
        {
            PlayerSession member = players.get(memberKey);

            fields.add(member == null ? "0" : Integer.toString(member.playerId));
            fields.add(member == null ? memberKey : member.username);
        }

        queueOutbound(session, "PARTY", fields);
    }

    private static void broadcastParty(Party party)
    {
        if (party == null)
        {
            return;
        }

        for (String memberKey : party.snapshot())
        {
            PlayerSession member = players.get(memberKey);

            if (member != null)
            {
                sendPartyState(member, party);
            }
        }

        refreshLeaderCoop(party);
    }

    private static void disbandParty(Party party)
    {
        if (party == null)
        {
            return;
        }

        for (String memberKey : party.snapshot())
        {
            playerParty.remove(memberKey);

            PlayerSession member = players.get(memberKey);

            if (member != null)
            {
                member.partyId = 0;
                member.coopMode = false;
                sendPartyState(member, null);
            }
        }

        parties.remove(party.partyId);
        scalingDirty.set(true);

        int removedQuestFoes;
        synchronized (NPC_WORLD_LOCK)
        {
            removedQuestFoes = NpcRegistry.removeQuestParty(party.partyId);
        }

        if (removedQuestFoes > 0)
        {
            dbg("QFOE party #%d state cleared: %d slots\n", party.partyId, removedQuestFoes);
        }

        dbg("PARTY #%d disbanded\n", party.partyId);
    }

    private static void partyRemoveMember(String usernameKey, String reason)
    {
        Party party = partyOf(usernameKey);

        if (party == null)
        {
            return;
        }

        final String previousLeader = party.leader();

        party.remove(usernameKey);
        playerParty.remove(usernameKey);

        PlayerSession leaver = players.get(usernameKey);

        if (leaver != null)
        {
            leaver.partyId = 0;
            leaver.coopMode = false;
            sendPartyState(leaver, null);
        }

        dropPartyRequestsFor(usernameKey);

        dbg("PARTY #%d %s left (%s)\n", party.partyId, usernameKey, reason);

        if (party.size() < 2)
        {
            disbandParty(party);
            return;
        }

        if (!usernameKey.equals(previousLeader))
        {
            scalingDirty.set(true);
            broadcastParty(party);
            refreshLeaderCoop(party);
            return;
        }

        dbg("PARTY #%d leader %s left, promoted %s\n", party.partyId, previousLeader, party.leader());

        refreshPartyScaling(party);
        scalingDirty.set(true);
        broadcastParty(party);
        refreshLeaderCoop(party);
    }

    private static final class SaveTarget
    {
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        final Set<Integer> queued = new HashSet<>();
        boolean begun;
        boolean endPending;
        boolean live;
        double rate = SAVE_RATE_START;
        double credit;
        int cleanTicks;
    }

    private static final class SaveRelay
    {
        int partyId;
        int transferId;
        String ownerKey;
        int totalChunks;
        byte[][] chunks;
        List<String> beginFields;
        boolean uploadComplete;
        long lastActivityNanos;
        long cachedBytes;
        final Map<String, SaveTarget> targets = new ConcurrentHashMap<>();
        final Set<String> waiting = new HashSet<>();
    }

    private static final double SAVE_RATE_START = 2.0;
    private static final double SAVE_RATE_MIN = 0.5;
    private static final double SAVE_RATE_STEP = 0.25;
    private static final int SAVE_RATE_CLEAN_TICKS = 40;

    private static final int SAVE_RESEND_PER_TICK = 12;
    private static final int SAVE_DRAIN_PER_TICK = 24;
    private static final long SAVE_RELAY_RETAIN_NANOS = 60_000_000_000L;

    private static final long MAX_SAVE_BYTES = 32L * 1024L * 1024L;
    private static final int MAX_SAVE_CHUNKS = 65536;
    private static final int MAX_SAVE_CHUNK_BYTES = 2048;
    private static final long MAX_RELAY_CACHE_BYTES = 48L * 1024L * 1024L;
    private static final int MAX_ACTIVE_SAVE_RELAYS = 8;
    private static final long SAVE_RELAY_TIMEOUT_NANOS = 300_000_000_000L;

    private static final Map<Integer, SaveRelay> saveRelays = new ConcurrentHashMap<>();

    private static void saveWanted(PlayerSession asker)
    {
        String askerKey = normalizeUsernameKey(asker.username);
        Party party = partyOf(askerKey);

        if (party == null)
        {
            return;
        }

        String leaderKey = party.leader();

        if (askerKey.equals(leaderKey))
        {
            return;
        }

        long now = System.nanoTime();
        SaveRelay relay = saveRelays.get(party.partyId);

        if (relay != null && relay.uploadComplete
                && (now - relay.lastActivityNanos) <= SAVE_RELAY_RETAIN_NANOS)
        {
            long ageSeconds = (now - relay.lastActivityNanos) / 1000000000L;

            relay.lastActivityNanos = now;
            beginTarget(relay, askerKey, true);

            dbg("SAVE relay #%d reused from cache for %s (age %ds)\n",
                    relay.transferId, askerKey, ageSeconds);
            return;
        }

        if (relay != null && !relay.uploadComplete && relay.beginFields != null)
        {
            relay.waiting.add(askerKey);
            relay.lastActivityNanos = now;
            beginTarget(relay, askerKey, true);

            dbg("SAVE relay #%d upload in flight, %s attached\n", relay.transferId, askerKey);
            return;
        }

        if (relay != null && !relay.uploadComplete && !relay.waiting.isEmpty())
        {
            relay.waiting.add(askerKey);
            relay.lastActivityNanos = now;

            dbg("SAVE relay for party #%d already requested, %s queued\n", party.partyId, askerKey);
            return;
        }

        PlayerSession leader = players.get(leaderKey);

        if (leader == null)
        {
            return;
        }

        if (relay == null)
        {
            relay = new SaveRelay();
            relay.partyId = party.partyId;
            relay.ownerKey = leaderKey;
            saveRelays.put(party.partyId, relay);
        }

        relay.waiting.add(askerKey);
        relay.lastActivityNanos = now;

        List<String> fields = new ArrayList<>();
        fields.add(asker.username);

        queueSaveOutbound(leader, "SAVENEED", fields);

        dbg("SAVE requested from leader %s for %s\n", leaderKey, askerKey);
    }

    private static void beginTarget(SaveRelay relay, String memberKey, boolean fullPush)
    {
        PlayerSession member = players.get(memberKey);

        if (member == null || relay.beginFields == null)
        {
            return;
        }

        SaveTarget target = relay.targets.get(memberKey);

        if (target == null || fullPush)
        {
            if (fullPush)
            {
                member.pendingSaveOutbound.clear();
            }

            target = new SaveTarget();
            relay.targets.put(memberKey, target);
        }

        target.live = true;

        if (!target.begun)
        {
            queueSaveOutbound(member, "SAVEBEG", relay.beginFields);
            target.begun = true;
        }

        if (fullPush)
        {
            for (int i = 0; i < relay.totalChunks; i++)
            {
                if (relay.chunks[i] != null && target.queued.add(i))
                {
                    target.queue.addLast(i);
                }
            }

            target.endPending = relay.uploadComplete;
        }
    }

    private static void relaySaveTransfer(PlayerSession sender, String opcode, List<String> fields)
    {
        String senderKey = normalizeUsernameKey(sender.username);
        Party party = partyOf(senderKey);

        if (party == null)
        {
            return;
        }

        long now = System.nanoTime();

        if ("SAVEWANT".equals(opcode))
        {
            saveWanted(sender);
            return;
        }

        if (fields.isEmpty())
        {
            return;
        }

        Integer transferId = parseIntegerOrNull(fields.get(0));

        if (transferId == null)
        {
            return;
        }

        if ("SAVEBEG".equals(opcode))
        {
            if (!senderKey.equals(party.leader()))
            {
                dbg("SAVE rejected upload from non-leader %s in party #%d\n", senderKey, party.partyId);
                return;
            }

            SaveRelay relay = saveRelays.get(party.partyId);

            if (relay == null || relay.waiting.isEmpty())
            {
                dbg("SAVE rejected unsolicited upload from %s in party #%d\n", senderKey, party.partyId);
                return;
            }

            Integer totalChunks = fields.size() > 1 ? parseIntegerOrNull(fields.get(1)) : null;

            if (totalChunks == null || totalChunks <= 0 || totalChunks > MAX_SAVE_CHUNKS)
            {
                dbg("SAVE rejected upload from %s: chunk count %s\n", senderKey, String.valueOf(totalChunks));
                return;
            }

            Long declaredBytes = fields.size() > 2 ? parseLongOrNull(fields.get(2)) : null;

            if (declaredBytes == null || declaredBytes <= 0L || declaredBytes > MAX_SAVE_BYTES)
            {
                dbg("SAVE rejected upload from %s: declared size %s\n", senderKey, String.valueOf(declaredBytes));
                return;
            }

            if (saveRelays.size() > MAX_ACTIVE_SAVE_RELAYS)
            {
                dbg("SAVE rejected upload from %s: %d relays already active\n", senderKey, saveRelays.size());
                return;
            }

            relay.cachedBytes = 0L;
            relay.transferId = transferId;
            relay.ownerKey = senderKey;
            relay.totalChunks = totalChunks;
            relay.chunks = new byte[totalChunks][];
            relay.beginFields = new ArrayList<>(fields);
            relay.uploadComplete = false;
            relay.lastActivityNanos = now;
            relay.targets.clear();

            for (String memberKey : relay.waiting)
            {
                beginTarget(relay, memberKey, false);
            }

            dbg("SAVE relay #%d upload started by %s chunks=%d waiting=%d\n",
                    transferId, senderKey, totalChunks, relay.waiting.size());
            return;
        }

        SaveRelay relay = saveRelays.get(party.partyId);

        if (relay == null || relay.transferId != transferId)
        {
            return;
        }

        relay.lastActivityNanos = now;

        if ("SAVECHK".equals(opcode))
        {
            if (!senderKey.equals(relay.ownerKey))
            {
                return;
            }

            Integer index = fields.size() > 1 ? parseIntegerOrNull(fields.get(1)) : null;

            if (index == null || index < 0 || index >= relay.totalChunks || fields.size() < 3)
            {
                return;
            }

            String payload = fields.get(2);

            if (payload.length() > MAX_SAVE_CHUNK_BYTES)
            {
                return;
            }

            if (relay.chunks[index] == null)
            {
                if (relay.cachedBytes + payload.length() > MAX_RELAY_CACHE_BYTES)
                {
                    dbg("SAVE relay #%d dropped: cache over %d bytes\n", transferId, MAX_RELAY_CACHE_BYTES);
                    saveRelays.remove(relay.partyId);
                    return;
                }

                relay.chunks[index] = payload.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                relay.cachedBytes += payload.length();
            }

            for (SaveTarget target : relay.targets.values())
            {
                if (target.live && target.queued.add(index))
                {
                    target.queue.addLast(index);
                }
            }

            return;
        }

        if ("SAVEEND".equals(opcode))
        {
            if (!senderKey.equals(relay.ownerKey))
            {
                return;
            }

            StringBuilder missing = new StringBuilder();
            int missingCount = 0;

            for (int i = 0; i < relay.totalChunks; i++)
            {
                if (relay.chunks[i] != null)
                {
                    continue;
                }

                missingCount++;

                if (missingCount <= 200)
                {
                    if (missing.length() > 0)
                    {
                        missing.append(',');
                    }

                    missing.append(i);
                }
            }

            if (missingCount > 0)
            {
                List<String> ask = new ArrayList<>();
                ask.add(Integer.toString(transferId));
                ask.add(missing.toString());

                queueSaveOutbound(sender, "SAVENACK", ask);

                dbg("SAVE relay #%d incomplete upload, asked %s for %d missing chunks\n",
                        transferId, senderKey, missingCount);
                return;
            }

            relay.uploadComplete = true;

            for (SaveTarget target : relay.targets.values())
            {
                if (target.live)
                {
                    target.endPending = true;
                }
            }

            dbg("SAVE relay #%d upload complete, cached\n", transferId);
            return;
        }

        if ("SAVENACK".equals(opcode))
        {
            SaveTarget target = relay.targets.get(senderKey);

            if (target == null || fields.size() < 2)
            {
                return;
            }

            target.rate = Math.max(SAVE_RATE_MIN, target.rate * 0.5);
            target.cleanTicks = 0;

            int queued = 0;
            int unknown = 0;

            for (String piece : fields.get(1).split(","))
            {
                Integer index = parseIntegerOrNull(piece.trim());

                if (index == null || index < 0 || index >= relay.totalChunks)
                {
                    continue;
                }

                if (relay.chunks[index] == null)
                {
                    unknown++;
                    continue;
                }

                if (target.queued.add(index))
                {
                    target.queue.addLast(index);
                    queued++;
                }
            }

            if (relay.uploadComplete)
            {
                target.endPending = true;
            }

            if (unknown > 0)
            {
                PlayerSession owner = players.get(relay.ownerKey);

                if (owner != null)
                {
                    queueSaveOutbound(owner, "SAVENACK", fields);
                }
            }

            dbg("SAVE relay #%d queued %d resends for %s (%d not cached)\n",
                    transferId, queued, senderKey, unknown);
            return;
        }

        if ("SAVEACK".equals(opcode))
        {
            SaveTarget target = relay.targets.get(senderKey);

            if (target != null)
            {
                target.live = false;
                target.queue.clear();
                target.queued.clear();
            }

            relay.waiting.remove(senderKey);

            PlayerSession owner = players.get(relay.ownerKey);

            if (owner != null)
            {
                List<String> ack = new ArrayList<>();
                ack.add(Integer.toString(transferId));
                ack.add("1");

                queueSaveOutbound(owner, "SAVEACK", ack);
            }

            dbg("SAVE relay #%d delivered to %s, cache retained %ds\n",
                    transferId, senderKey, SAVE_RELAY_RETAIN_NANOS / 1000000000L);
        }
    }

    private static void pumpSaveRelays()
    {
        for (SaveRelay relay : saveRelays.values())
        {
            for (Map.Entry<String, SaveTarget> entry : relay.targets.entrySet())
            {
                SaveTarget target = entry.getValue();

                if (!target.live || (target.queue.isEmpty() && !target.endPending))
                {
                    continue;
                }

                PlayerSession member = players.get(entry.getKey());

                if (member == null)
                {
                    continue;
                }

                target.cleanTicks++;

                if (target.cleanTicks >= SAVE_RATE_CLEAN_TICKS)
                {
                    target.rate = Math.min(SAVE_RESEND_PER_TICK, target.rate + SAVE_RATE_STEP);
                    target.cleanTicks = 0;
                }

                target.credit += target.rate;

                if (target.credit > SAVE_RESEND_PER_TICK)
                {
                    target.credit = SAVE_RESEND_PER_TICK;
                }

                int space = member.pendingSaveOutbound.remainingCapacity();
                int budget = Math.min((int) target.credit, space);

                if (budget <= 0)
                {
                    continue;
                }

                target.credit -= budget;

                while (budget > 0 && !target.queue.isEmpty())
                {
                    int index = target.queue.pollFirst();
                    target.queued.remove(index);

                    byte[] payload = relay.chunks[index];

                    if (payload == null)
                    {
                        continue;
                    }

                    List<String> out = new ArrayList<>();
                    out.add(Integer.toString(relay.transferId));
                    out.add(Integer.toString(index));
                    out.add(new String(payload, java.nio.charset.StandardCharsets.US_ASCII));

                    queueSaveOutbound(member, "SAVECHK", out);
                    budget--;
                }

                if (target.queue.isEmpty() && target.endPending)
                {
                    List<String> endFields = new ArrayList<>();
                    endFields.add(Integer.toString(relay.transferId));

                    queueSaveOutbound(member, "SAVEEND", endFields);
                    target.endPending = false;
                }
            }
        }
    }

    private static void purgeStaleSaveRelays(long now)
    {
        for (SaveRelay relay : new ArrayList<>(saveRelays.values()))
        {
            boolean busy = false;

            for (SaveTarget target : relay.targets.values())
            {
                if (target.live)
                {
                    busy = true;
                    break;
                }
            }

            long idle = now - relay.lastActivityNanos;

            if (busy)
            {
                if (idle > SAVE_RELAY_TIMEOUT_NANOS)
                {
                    saveRelays.remove(relay.partyId);
                    dbg("SAVE relay #%d timed out mid-transfer, dropped\n", relay.transferId);
                }

                continue;
            }

            if (idle > SAVE_RELAY_RETAIN_NANOS)
            {
                saveRelays.remove(relay.partyId);
                dbg("SAVE relay #%d retention expired, cache dropped\n", relay.transferId);
            }
        }
    }

    private static void partyRequest(PlayerSession actor, String targetName)
    {
        String actorKey = normalizeUsernameKey(actor.username);
        String targetKey = normalizeUsernameKey(targetName);
        long now = System.nanoTime();

        if (targetKey.isEmpty() || targetKey.equals(actorKey))
        {
            return;
        }

        PlayerSession target = players.get(targetKey);

        if (target == null)
        {
            queuePartyNotice(actor, "REQFAIL", targetName, "offline");
            return;
        }

        if (!shouldSeePlayer(actor, target, actor.visiblePlayers.contains(target.playerId)))
        {
            queuePartyNotice(actor, "REQFAIL", target.username, "far");
            return;
        }

        Party targetParty = partyOf(targetKey);
        Party actorParty = partyOf(actorKey);

        if (targetParty != null && actorParty != null && targetParty.partyId == actorParty.partyId)
        {
            queuePartyNotice(actor, "REQFAIL", target.username, "same");
            return;
        }

        if (targetParty != null && targetParty.size() >= Party.MAX_MEMBERS)
        {
            queuePartyNotice(actor, "REQFAIL", target.username, "full");
            return;
        }

        purgeExpiredPartyRequests(now);

        if (partyRequests.containsKey(partyRequestKey(actorKey, targetKey)))
        {
            queuePartyNotice(actor, "REQFAIL", target.username, "pending");
            return;
        }

        partyRequests.put(partyRequestKey(actorKey, targetKey),
                new PartyRequest(actorKey, targetKey, now + PARTY_REQUEST_TIMEOUT_NANOS));

        queuePartyNotice(target, "REQUEST", actor.username, "");
        queuePartyNotice(actor, "REQSENT", target.username, "");

        dbg("PARTY request %s -> %s (expires in %ds)\n",
                actorKey, targetKey, PARTY_REQUEST_TIMEOUT_NANOS / 1_000_000_000L);
    }

    private static void teleportRequest(PlayerSession actor, String targetName)
    {
        String targetKey = normalizeUsernameKey(targetName);
        PlayerSession target = targetKey.isEmpty() ? null : players.get(targetKey);
        List<String> fields = new ArrayList<>();

        if (target == null)
        {
            fields.add("0");
            fields.add(targetName == null ? "" : targetName);
            fields.add("0");
            fields.add("0");
            fields.add("0");
            fields.add("0");
        }
        else if (!target.hasPosition)
        {
            fields.add("2");
            fields.add(target.username);
            fields.add("0");
            fields.add("0");
            fields.add("0");
            fields.add("0");
        }
        else
        {
            fields.add("1");
            fields.add(target.username);
            fields.add(Integer.toString(target.area));
            fields.add(Double.toString(target.posX));
            fields.add(Double.toString(target.posY));
            fields.add(Double.toString(target.posZ));
        }

        queueOutbound(actor, "TPPOS", fields);
    }

    private static void partyRespond(PlayerSession actor, String requesterName, boolean approved)
    {
        String actorKey = normalizeUsernameKey(actor.username);
        String requesterKey = normalizeUsernameKey(requesterName);
        long now = System.nanoTime();

        purgeExpiredPartyRequests(now);

        PartyRequest request = partyRequests.remove(partyRequestKey(requesterKey, actorKey));

        if (request == null)
        {
            queuePartyNotice(actor, "RESPFAIL", requesterName, "none");
            return;
        }

        PlayerSession requester = players.get(requesterKey);

        queuePartyNotice(actor, "RESOLVEDIN",
                requester != null ? requester.username : requesterName, "");

        if (requester == null)
        {
            queuePartyNotice(actor, "RESPFAIL", requesterName, "offline");
            return;
        }

        if (!approved)
        {
            queuePartyNotice(requester, "REJECTED", actor.username, "");
            dbg("PARTY request %s -> %s REJECTED\n", requesterKey, actorKey);
            return;
        }

        dbg("PARTY request %s -> %s APPROVED\n", requesterKey, actorKey);

        partyJoin(requester, actor.username);
    }

    private static String partyRequestKey(String requesterKey, String targetKey)
    {
        return requesterKey + ">" + targetKey;
    }

    private static void dropPartyRequestsFor(String usernameKey)
    {
        for (PartyRequest request : new ArrayList<>(partyRequests.values()))
        {
            if (request.requesterKey.equals(usernameKey) || request.targetKey.equals(usernameKey))
            {
                if (!partyRequests.remove(partyRequestKey(request.requesterKey, request.targetKey), request))
                {
                    continue;
                }

                if (request.requesterKey.equals(usernameKey))
                {
                    PlayerSession target = players.get(request.targetKey);

                    if (target != null)
                    {
                        queuePartyNotice(target, "CANCELLEDIN", usernameKey, "");
                    }
                }
            }
        }
    }

    private static void purgeExpiredPartyRequests(long now)
    {
        for (PartyRequest request : new ArrayList<>(partyRequests.values()))
        {
            if (now < request.expiresAtNanos)
            {
                continue;
            }

            partyRequests.remove(partyRequestKey(request.requesterKey, request.targetKey));

            PlayerSession requester = players.get(request.requesterKey);
            PlayerSession target = players.get(request.targetKey);

            if (requester != null)
            {
                queuePartyNotice(requester, "EXPIRED",
                        target != null ? target.username : request.targetKey, "");
            }

            if (target != null)
            {
                queuePartyNotice(target, "EXPIREDIN",
                        requester != null ? requester.username : request.requesterKey, "");
            }

            dbg("PARTY request %s -> %s EXPIRED\n", request.requesterKey, request.targetKey);
        }
    }

    private static void relaySceneStart(PlayerSession session, List<String> fields)
    {
        final String senderKey = normalizeUsernameKey(session.username);
        Party party = partyOf(senderKey);

        if (party == null || fields.size() < 18
                || !isQuestCoopParticipant(session, party.partyId))
        {
            return;
        }

        List<String> payload = new ArrayList<>();
        payload.add(session.username);
        payload.addAll(fields);

        for (String memberKey : party.snapshot())
        {
            if (memberKey.equals(senderKey))
            {
                continue;
            }

            PlayerSession member = players.get(memberKey);

            if (isQuestCoopParticipant(member, party.partyId))
            {
                queueOutbound(member, "SCENE", payload);
            }
        }

    }

    private static void relayQuestItem(PlayerSession session, List<String> fields)
    {
        final String senderKey = normalizeUsernameKey(session.username);
        Party party = partyOf(senderKey);

        if (party == null || fields.size() < 3)
        {
            return;
        }

        List<String> payload = new ArrayList<>();
        payload.add(session.username);
        payload.addAll(fields);

        int sent = 0;

        for (String memberKey : party.snapshot())
        {
            if (memberKey.equals(senderKey))
            {
                continue;
            }

            PlayerSession member = players.get(memberKey);

            if (member != null)
            {
                queueOutbound(member, "QITEM", payload);
                sent++;
            }
        }

        dbg("QITEM %s kind=%s subject=%s relayed to %d\n",
                senderKey, fields.get(0), fields.get(1), sent);
    }

    static int questPartyOf(int playerId)
    {
        PlayerSession session = sessionByPlayerId(playerId);

        if (session == null || !isQuestCoopParticipant(session, session.partyId))
        {
            return 0;
        }

        return session.partyId;
    }

    static boolean isQuestCoopParticipant(PlayerSession session, int partyId)
    {
        if (session == null || partyId <= 0 || session.partyId != partyId)
        {
            return false;
        }

        Party party = parties.get(partyId);

        if (party == null)
        {
            return false;
        }

        final String leaderKey = party.leader();
        boolean active = false;

        for (String memberKey : party.snapshot())
        {
            if (memberKey.equals(leaderKey))
            {
                continue;
            }

            PlayerSession member = players.get(memberKey);

            if (member != null && member.coopMode)
            {
                active = true;
                break;
            }
        }

        if (!active)
        {
            return false;
        }

        final String sessionKey = normalizeUsernameKey(session.username);
        return sessionKey.equals(leaderKey) || session.coopMode;
    }

    static boolean questVisibleTo(PlayerSession session, NpcRegistry.Npc npc)
    {
        if (session == null || npc == null || session.partyId != npc.questPartyId)
        {
            return false;
        }

        return isQuestCoopParticipant(session, npc.questPartyId);
    }

    static int sanitizeTerminalAttacker(NpcRegistry.Npc npc, int attackerPlayerId)
    {
        if (npc == null || attackerPlayerId <= 0)
        {
            return 0;
        }

        PlayerSession attacker = sessionByPlayerId(attackerPlayerId);

        if (NpcRegistry.isQuestFoe(npc))
        {
            return questVisibleTo(attacker, npc) ? attackerPlayerId : 0;
        }

        return canShareNpcs(attacker, sessionByPlayerId(npc.ownerPlayerId)) ? attackerPlayerId : 0;
    }

    private static void notifyLeaderOfCoop(PlayerSession session)
    {
        refreshLeaderCoop(partyOf(normalizeUsernameKey(session.username)));
    }

    private static void refreshLeaderCoop(Party party)
    {
        if (party == null)
        {
            return;
        }

        final String leaderKey = party.leader();
        PlayerSession leader = players.get(leaderKey);

        if (leader == null)
        {
            return;
        }

        boolean anyCoop = false;

        for (String memberKey : party.snapshot())
        {
            if (memberKey.equals(leaderKey))
            {
                continue;
            }

            PlayerSession member = players.get(memberKey);

            if (member != null && member.coopMode)
            {
                anyCoop = true;
                break;
            }
        }

        queuePartyNotice(leader, anyCoop ? "COOPON" : "COOPOFF", "", "");

        broadcastCoopRoster(party, anyCoop);
    }

    private static void broadcastCoopRoster(Party party, boolean anyCoop)
    {
        final String leaderKey = party.leader();
        StringBuilder roster = new StringBuilder();

        if (anyCoop)
        {
            for (String memberKey : party.snapshot())
            {
                PlayerSession member = players.get(memberKey);

                if (member == null)
                {
                    continue;
                }

                if (member.coopMode || memberKey.equals(leaderKey))
                {
                    if (roster.length() > 0)
                    {
                        roster.append(",");
                    }

                    roster.append(member.username);
                }
            }
        }

        final String list = roster.toString();

        for (String memberKey : party.snapshot())
        {
            PlayerSession member = players.get(memberKey);

            if (member != null)
            {
                queuePartyNotice(member, "COOPWHO", list.isEmpty() ? "-" : list, "");
            }
        }
    }

    private static void queuePartyNotice(PlayerSession session, String kind, String who, String reason)
    {
        List<String> fields = new ArrayList<>();

        fields.add(kind);
        fields.add(who == null ? "" : who);
        fields.add(reason == null ? "" : reason);

        queueOutbound(session, "PINVITE", fields);
    }

    private static void partyJoin(PlayerSession actor, String targetName)
    {
        String actorKey = normalizeUsernameKey(actor.username);
        String targetKey = normalizeUsernameKey(targetName);

        if (targetKey.isEmpty() || targetKey.equals(actorKey))
        {
            return;
        }

        PlayerSession target = players.get(targetKey);

        if (target == null)
        {
            return;
        }

        Party targetParty = partyOf(targetKey);
        Party actorParty = partyOf(actorKey);

        if (targetParty != null && actorParty != null && targetParty.partyId == actorParty.partyId)
        {
            return;
        }

        if (targetParty != null && targetParty.size() >= Party.MAX_MEMBERS)
        {
            dbg("PARTY #%d full, rejected %s\n", targetParty.partyId, actorKey);
            return;
        }

        if (actorParty != null)
        {
            partyRemoveMember(actorKey, "switching party");
        }

        if (targetParty == null)
        {
            targetParty = new Party(nextPartyId.getAndIncrement());
            targetParty.add(targetKey);
            refreshPartyScaling(targetParty);
            parties.put(targetParty.partyId, targetParty);
            playerParty.put(targetKey, targetParty.partyId);
            target.partyId = targetParty.partyId;

            dbg("PARTY #%d created, leader %s\n", targetParty.partyId, targetKey);
        }

        if (!targetParty.add(actorKey))
        {
            return;
        }

        playerParty.put(actorKey, targetParty.partyId);
        actor.partyId = targetParty.partyId;
        scalingDirty.set(true);

        dbg("PARTY #%d %s joined (leader %s, size %d)\n",
                targetParty.partyId, actorKey, targetParty.leader(), targetParty.size());

        broadcastParty(targetParty);
    }

    private static void relayPartyHeartbeat(long now)
    {
        if ((now - lastPartyResendNanos) < PARTY_RESEND_NANOS)
        {
            return;
        }

        lastPartyResendNanos = now;

        for (Party party : parties.values())
        {
            broadcastParty(party);
        }

        for (PartyRequest request : partyRequests.values())
        {
            PlayerSession requester = players.get(request.requesterKey);
            PlayerSession target = players.get(request.targetKey);

            if (requester != null && target != null && now < request.expiresAtNanos)
            {
                queuePartyNotice(target, "REQUEST", requester.username, "");
            }
        }
    }

    private static void relayNpcs(DatagramSocket socket, PlayerSession session, long nowNanos, long snapshotMs)
    {
        List<NpcRegistry.Npc> visible = NpcRegistry.visibleTo(
                session, NPC_INTEREST_RADIUS_SQUARED, NPC_INTEREST_LEAVE_SQUARED);
        Set<Integer> desired = session.npcDesiredScratch;
        desired.clear();

        List<String> spawn = new ArrayList<>();
        List<String> move = new ArrayList<>();
        List<String> fast = new ArrayList<>();
        int spawnCount = 0;
        int moveCount = 0;
        int fastCount = 0;
        int spawnPackets = 0;
        int movePackets = 0;
        int fastPackets = 0;

        for (NpcRegistry.Npc npc : visible)
        {
            int authorityToken = npc.ownerPlayerId == 0
                    ? -npc.authorityRevision
                    : npc.authorityRevision;
            if (NpcRegistry.isQuestFoe(npc) && !questVisibleTo(session, npc))
            {
                continue;
            }

            if (!npc.alive
                    && !NpcRegistry.isQuestFoe(npc)
                    && !session.knownNpcs.contains(npc.npcId))
            {
                continue;
            }

            desired.add(npc.npcId);

            if (session.knownNpcs.add(npc.npcId))
            {
                NpcRegistry.prepareRecipient(
                        session.playerId,
                        npc,
                        NpcRegistry.isQuestFoe(npc)
                                ? NpcRegistry.BINDING_NATIVE
                                : NpcRegistry.BINDING_SYNTHETIC,
                        nowNanos);
                NpcRegistry.addDurableBehaviorRecipient(npc, session.playerId);
                if (!npc.alive && NpcRegistry.isQuestFoe(npc))
                {
                    NpcRegistry.requestDeathReplay(npc, session.playerId, nowNanos);
                }

                if (spawnPackets >= NPC_SPAWN_PACKETS_PER_TICK)
                {
                    session.knownNpcs.remove(npc.npcId);
                    desired.remove(npc.npcId);
                    continue;
                }

                spawn.add(Integer.toString(npc.npcId));
                spawn.add(Integer.toString(npc.area));
                spawn.add(npc.typeCode);
                spawn.add(npc.appearance);
                spawn.add(formatCoord(npc.x));
                spawn.add(formatCoord(npc.y));
                spawn.add(formatCoord(npc.z));
                spawn.add(formatCoord(npc.heading));
                spawn.add(Integer.toString(npc.hpPermille));
                spawn.add(Integer.toString(npc.flags));
                spawn.add(Integer.toString(npc.targetPlayerId));
                spawn.add(Integer.toString(npc.terminalState));
                spawn.add(Integer.toString(npc.terminalRevision));
                spawn.add(Integer.toString(npc.terminalAttackerId));
                spawn.add(Integer.toString(authorityToken));
                spawn.add(Integer.toString(npc.lifecycleRevision));
                spawnCount++;

                if (spawnCount >= NPC_SPAWN_BATCH)
                {
                    sendSnapshot(socket, session, "NPCNEW", spawn, spawnCount, snapshotMs);
                    spawn = new ArrayList<>();
                    spawnCount = 0;
                    spawnPackets++;
                }

                continue;
            }

            if (!NpcRegistry.bindingReady(session.playerId, npc))
            {
                continue;
            }

            if (!npc.alive && npc.deathBroadcast)
            {
                continue;
            }

            PlayerSession.NpcView view = session.npcViews.get(npc.npcId);

            if (view == null)
            {
                view = new PlayerSession.NpcView();
                session.npcViews.put(npc.npcId, view);
            }

            if (npc.alive && fastPackets < NPC_MOVE_PACKETS_PER_TICK
                    && (nowNanos - view.lastFastSentNanos) >= npcSendIntervalNanos(session, npc))
            {
                view.fastSequence += 1;
                fast.add(Integer.toString(npc.npcId));
                fast.add(Integer.toString(npc.lifecycleRevision));
                fast.add(Integer.toString(npc.authorityRevision));
                fast.add(Integer.toString(view.fastSequence));
                fast.add(formatCoord(npc.x));
                fast.add(formatCoord(npc.y));
                fast.add(formatCoord(npc.z));
                fast.add(formatCoord(npc.heading));
                fast.add(Integer.toString(npc.hpPermille));
                fast.add(Integer.toString(npc.flags));
                fast.add(Integer.toString(npc.targetPlayerId));
                view.lastFastSentNanos = nowNanos;
                fastCount++;

                if (fastCount >= NPC_MOVE_BATCH)
                {
                    sendSnapshot(socket, session, "NPCFAST", fast, fastCount, snapshotMs);
                    fast = new ArrayList<>();
                    fastCount = 0;
                    fastPackets++;
                }
            }

            if (movePackets >= NPC_MOVE_PACKETS_PER_TICK)
            {
                continue;
            }

            if ((nowNanos - view.lastSentNanos) < NPC_VIEW_KEEPALIVE_NANOS
                    && view.valid
                    && view.hpPermille == npc.hpPermille
                    && view.flags == npc.flags
                    && view.targetPlayerId == npc.targetPlayerId
                    && view.terminalState == npc.terminalState
                    && view.terminalRevision == npc.terminalRevision
                    && view.terminalAttackerId == npc.terminalAttackerId
                    && view.authorityRevision == authorityToken)
            {
                continue;
            }

            int mask = 0;

            if (!view.valid || view.hpPermille != npc.hpPermille)
            {
                mask |= 4;
            }

            if (!view.valid || view.flags != npc.flags)
            {
                mask |= 8;
            }

            if (!view.valid || view.targetPlayerId != npc.targetPlayerId)
            {
                mask |= 16;
            }

            if (!view.valid
                    || view.terminalState != npc.terminalState
                    || view.terminalRevision != npc.terminalRevision)
            {
                mask |= 32;
            }

            if (!view.valid || view.terminalAttackerId != npc.terminalAttackerId)
            {
                mask |= 64;
            }

            if (!view.valid || view.authorityRevision != authorityToken)
            {
                mask |= 128;
            }

            if (mask == 0)
            {
                mask = 4 | 8 | 16 | 32 | 64 | 128;
            }

            move.add(Integer.toString(npc.npcId));
            move.add(Integer.toString(mask));

            if ((mask & 1) != 0)
            {
                move.add(formatCoord(npc.x));
                move.add(formatCoord(npc.y));
                move.add(formatCoord(npc.z));
            }

            if ((mask & 2) != 0)
            {
                move.add(formatCoord(npc.heading));
            }

            if ((mask & 4) != 0)
            {
                move.add(Integer.toString(npc.hpPermille));
            }

            if ((mask & 8) != 0)
            {
                move.add(Integer.toString(npc.flags));
            }

            if ((mask & 16) != 0)
            {
                move.add(Integer.toString(npc.targetPlayerId));
            }

            if ((mask & 32) != 0)
            {
                move.add(Integer.toString(npc.terminalState));
                move.add(Integer.toString(npc.terminalRevision));
            }

            if ((mask & 64) != 0)
            {
                move.add(Integer.toString(npc.terminalAttackerId));
            }

            if ((mask & 128) != 0)
            {
                move.add(Integer.toString(authorityToken));
            }

            view.x = npc.x;
            view.y = npc.y;
            view.z = npc.z;
            view.heading = npc.heading;
            view.hpPermille = npc.hpPermille;
            view.flags = npc.flags;
            view.targetPlayerId = npc.targetPlayerId;
            view.terminalState = npc.terminalState;
            view.terminalRevision = npc.terminalRevision;
            view.terminalAttackerId = npc.terminalAttackerId;
            view.authorityRevision = authorityToken;
            view.lastSentNanos = nowNanos;
            view.valid = true;

            moveCount++;

            if (moveCount >= NPC_MOVE_BATCH)
            {
                sendSnapshot(socket, session, "NPCMOV", move, moveCount, snapshotMs);
                move = new ArrayList<>();
                moveCount = 0;
                movePackets++;
            }
        }

        List<String> remove = new ArrayList<>();
        int removeCount = 0;

        for (java.util.Iterator<Integer> it = session.knownNpcs.iterator(); it.hasNext();)
        {
            Integer known = it.next();

            if (desired.contains(known))
            {
                continue;
            }

            it.remove();
            session.npcViews.remove(known);
            NpcRegistry.forgetTerminalRecipient(known, session.playerId);
            NpcRegistry.markRecipientUnready(session.playerId, known);

            if (removeCount < NPC_REMOVE_BATCH)
            {
                remove.add(Integer.toString(known));
                remove.add(NpcRegistry.get(known) == null ? "0" : "1");
                removeCount++;
            }
        }

        if (spawnCount > 0)
        {
            sendSnapshot(socket, session, "NPCNEW", spawn, spawnCount, snapshotMs);
        }

        if (moveCount > 0)
        {
            sendSnapshot(socket, session, "NPCMOV", move, moveCount, snapshotMs);
        }

        if (fastCount > 0)
        {
            sendSnapshot(socket, session, "NPCFAST", fast, fastCount, snapshotMs);
        }

        if (removeCount > 0)
        {
            remove.add(0, Integer.toString(removeCount));
            sendNpcPacket(socket, session, "NPCEND", remove);
        }

        if (!session.goneGuids.isEmpty())
        {
            List<String> gone = new ArrayList<>();
            int goneCount = 0;

            for (Integer guid : new ArrayList<>(session.goneGuids))
            {
                session.goneGuids.remove(guid);

                if (goneCount >= NPC_REMOVE_BATCH)
                {
                    break;
                }

                gone.add(Integer.toString(guid));
                goneCount++;
            }

            if (goneCount > 0)
            {
                gone.add(0, Integer.toString(goneCount));
                sendNpcPacket(socket, session, "NPCGONE", gone);

                dbg("NPC re-register requested: %d entities guids=%s -> %s\n",
                        goneCount,
                        String.join(",", gone.subList(1, gone.size())),
                        describePlayerId(session.playerId));
            }
        }
    }

    private static void sendSnapshot(
            DatagramSocket socket,
            PlayerSession session,
            String opcode,
            List<String> body,
            int count,
            long snapshotMs)
    {
        body.add(0, Integer.toString(count));
        body.add(0, Long.toString(snapshotMs));

        sendNpcPacket(socket, session, opcode, body);
    }

    private static void relayDeaths(DatagramSocket socket, List<PlayerSession> sessions, long now)
    {
        List<NpcRegistry.Npc> pending = NpcRegistry.pendingDeaths(now);

        if (pending.isEmpty())
        {
            return;
        }

        for (NpcRegistry.Npc npc : pending)
        {
            NpcRegistry.initializeTerminalRecipients(npc, sessions, now);
            npc.lastDeathSendNanos = now;

            for (PlayerSession session : sessions)
            {
                if (!npc.terminalPending.contains(session.playerId)
                        || !session.knownNpcs.contains(npc.npcId)
                        || !NpcRegistry.sharesSyncGroup(session, npc))
                {
                    continue;
                }

                Integer sent = npc.deathSends.get(session.playerId);
                int count = (sent == null) ? 0 : sent;
                Long lastSent = npc.terminalLastSends.get(session.playerId);

                if ((session.paused && count >= NpcRegistry.DEATH_BROADCAST_REPEATS)
                        || (!session.paused
                            && count >= NpcRegistry.DEATH_BROADCAST_REPEATS
                            && lastSent != null
                            && (now - lastSent) < NpcRegistry.TERMINAL_RETRY_NANOS))
                {
                    continue;
                }

                List<String> fields = new ArrayList<>();
                fields.add("1");
                fields.add(Integer.toString(npc.npcId));
                fields.add(Integer.toString(npc.terminalState));
                fields.add(Integer.toString(npc.terminalRevision));
                fields.add(Integer.toString(npc.terminalAttackerId));

                sendNpcPacket(socket, session, "NPCDEAD", fields);
                npc.deathSends.put(session.playerId, count + 1);
                npc.terminalLastSends.put(session.playerId, now);
            }

            if (!npc.deathBroadcast)
            {
                npc.deathBroadcast = true;

                dbg("NPC %s TERMINAL BROADCAST | owner=%s cell=%s\n",
                        NpcRegistry.describeNpc(npc),
                        describePlayerId(npc.ownerPlayerId),
                        NpcRegistry.describeSpot(npc));
            }
        }
    }

    private static boolean shouldSeePlayer(PlayerSession viewer, PlayerSession target, boolean alreadyVisible)
    {
        if (viewer == target)
        {
            return false;
        }

        if (viewer.partyId != 0 && viewer.partyId == target.partyId)
        {
            return true;
        }

        if (target.paused && alreadyVisible)
        {
            return true;
        }

        if (!viewer.hasPosition || !target.hasPosition)
        {
            return alreadyVisible;
        }

        if (viewer.area != target.area)
        {
            return false;
        }

        double dx = viewer.posX - target.posX;
        double dy = viewer.posY - target.posY;
        double dz = viewer.posZ - target.posZ;
        double squared = (dx * dx) + (dy * dy) + (dz * dz);

        if (alreadyVisible)
        {
            return squared <= PLAYER_VIS_LEAVE_SQUARED;
        }

        return squared <= PLAYER_VIS_ENTER_SQUARED;
    }

    private static void relayVisibility(DatagramSocket socket, List<PlayerSession> sessions, long now)
    {
        for (PlayerSession viewer : sessions)
        {
            Set<Integer> next = new HashSet<>();
            Map<Integer, PlayerSession> candidates = new HashMap<>();

            if (viewer.hasPosition)
            {
                for (PlayerSession target : SpatialIndex.query(
                        viewer.area, viewer.posX, viewer.posY, PLAYER_VIS_LEAVE_RADIUS))
                {
                    candidates.put(target.playerId, target);
                }
            }

            for (Integer visibleId : viewer.visiblePlayers)
            {
                PlayerSession target = sessionByPlayerId(visibleId);
                if (target != null)
                {
                    candidates.put(target.playerId, target);
                }
            }

            for (PlayerSession target : partySessions(viewer.partyId))
            {
                candidates.put(target.playerId, target);
            }

            List<PlayerSession> ordered = new ArrayList<>(candidates.values());
            ordered.sort((left, right) ->
            {
                boolean leftParty = viewer.partyId != 0 && viewer.partyId == left.partyId;
                boolean rightParty = viewer.partyId != 0 && viewer.partyId == right.partyId;
                if (leftParty != rightParty)
                {
                    return leftParty ? -1 : 1;
                }
                boolean leftVisible = viewer.visiblePlayers.contains(left.playerId);
                boolean rightVisible = viewer.visiblePlayers.contains(right.playerId);
                if (leftVisible != rightVisible)
                {
                    return leftVisible ? -1 : 1;
                }
                return Double.compare(distanceSquared(viewer, left), distanceSquared(viewer, right));
            });

            for (PlayerSession target : ordered)
            {
                if (!target.transportReady())
                {
                    continue;
                }

                boolean partyMember = viewer != target
                        && viewer.partyId != 0
                        && viewer.partyId == target.partyId;

                if (next.size() >= PLAYER_VIS_MAX && !partyMember)
                {
                    continue;
                }

                if (shouldSeePlayer(viewer, target, viewer.visiblePlayers.contains(target.playerId)))
                {
                    next.add(target.playerId);
                }
            }

            boolean changed = !next.equals(viewer.visiblePlayers);

            if (changed)
            {
                int entered = 0;
                int left = 0;
                for (Integer id : next)
                {
                    if (!viewer.visiblePlayers.contains(id))
                    {
                        entered++;
                    }
                }

                for (Integer id : viewer.visiblePlayers)
                {
                    if (!next.contains(id))
                    {
                        left++;
                    }
                }

                dbg("VIS %s entered=%d left=%d visible=%d\n",
                        describePlayerId(viewer.playerId), entered, left, next.size());

                viewer.visiblePlayers.clear();
                viewer.visiblePlayers.addAll(next);
            }

            if (!changed && (now - viewer.visibilitySentNanos) < PLAYER_VIS_RESEND_NANOS)
            {
                continue;
            }

            viewer.visibilitySentNanos = now;

            List<String> fields = new ArrayList<>();
            fields.add(Integer.toString(next.size()));

            for (Integer id : next)
            {
                fields.add(Integer.toString(id));
                fields.add(canShareNpcs(viewer, sessionByPlayerId(id)) ? "1" : "0");
            }

            sendNpcPacket(socket, viewer, "PVIS", fields);
        }
    }

    private static void relayHandovers(DatagramSocket socket, List<PlayerSession> sessions, long now)
    {
        int[] pending;

        while ((pending = NpcRegistry.pollPendingDrop()) != null)
        {
            PlayerSession loser = findPlayerById(pending[0]);

            if (loser == null)
            {
                continue;
            }

            List<String> drop = new ArrayList<>();
            drop.add("1");
            drop.add(Integer.toString(pending[1]));
            drop.add(Integer.toString(pending[2]));
            NpcRegistry.Npc handedOver = NpcRegistry.get(pending[2]);
            drop.add(Integer.toString(handedOver == null ? 0 : handedOver.lifecycleRevision));

            queueOutbound(loser, "NPCDROP", drop);
        }

        List<int[]> orders = NpcRegistry.planHandovers(sessions, NpcRegistry.HANDOVER_SUSTAIN_SQUARED, now);

        if (orders.isEmpty())
        {
            return;
        }

        for (PlayerSession session : sessions)
        {
            List<String> fields = new ArrayList<>();
            int count = 0;
            int offered = 0;

            for (int[] order : orders)
            {
                if (order[1] != session.playerId)
                {
                    continue;
                }

                fields.add(Integer.toString(order[0]));
                count++;
                offered++;

                if (count >= NPC_REMOVE_BATCH)
                {
                    fields.add(0, Integer.toString(count));
                    sendNpcPacket(socket, session, "NPCGIVE", fields);
                    fields = new ArrayList<>();
                    count = 0;
                }
            }

            if (count > 0)
            {
                fields.add(0, Integer.toString(count));
                sendNpcPacket(socket, session, "NPCGIVE", fields);
            }

            if (offered > 0 && session.lastHandoverLogged != offered)
            {
                session.lastHandoverLogged = offered;
                dbg("NPC handover offered: %d entities -> %s\n", offered, describePlayerId(session.playerId));
            }
        }
    }

    private static void relayScaling(DatagramSocket socket, PlayerSession session, long now)
    {
        final long epoch = scaleEpoch.get();
        final int knownVersion = session.knownNpcs.size();

        if (session.lastScaleEpoch == epoch
                && session.lastScaleKnownCount == knownVersion
                && (now - session.scalesSentNanos) < NPC_SCALE_RESEND_NANOS)
        {
            return;
        }

        session.lastScaleEpoch = epoch;
        session.lastScaleKnownCount = knownVersion;

        Map<Integer, Integer> current = new HashMap<>();
        List<int[]> wire = new ArrayList<>();

        for (NpcRegistry.Npc npc : NpcRegistry.ownedBy(session.playerId))
        {
            current.put(npc.npcId, npc.scaleMilli);
            wire.add(new int[] { npc.ownerLocalGuid, npc.scaleMilli });
        }

        for (Integer known : session.knownNpcs)
        {
            NpcRegistry.Npc npc = NpcRegistry.get(known);

            if (npc == null || npc.ownerPlayerId == session.playerId)
            {
                continue;
            }

            current.put(npc.npcId, npc.scaleMilli);
            wire.add(new int[] { npc.npcId, npc.scaleMilli });
        }

        boolean changed = !current.equals(session.sentScales);

        if (!changed && (now - session.scalesSentNanos) < NPC_SCALE_RESEND_NANOS)
        {
            return;
        }

        session.sentScales.clear();
        session.sentScales.putAll(current);
        session.scalesSentNanos = now;

        if (wire.isEmpty())
        {
            return;
        }

        int sent = 0;
        int setId = ++session.scaleSetId;

        while (sent < wire.size())
        {
            int chunk = Math.min(NPC_SCALE_BATCH, wire.size() - sent);

            List<String> fields = new ArrayList<>();
            fields.add(Integer.toString(setId));
            fields.add(Integer.toString(wire.size()));
            fields.add(Integer.toString(chunk));

            for (int i = 0; i < chunk; i++)
            {
                int[] pair = wire.get(sent + i);
                fields.add(Integer.toString(pair[0]));
                fields.add(Integer.toString(pair[1]));
            }

            sendNpcPacket(socket, session, "NPCSCALE", fields);
            sent += chunk;
        }

        if (changed)
        {
            int peak = NpcScaling.SCALE_UNIT;

            for (int[] pair : wire)
            {
                if (pair[1] > peak)
                {
                    peak = pair[1];
                }
            }

            dbg("NPC scale sent: %d entries peak=x%.2f -> %s\n",
                    wire.size(),
                    peak / (double) NpcScaling.SCALE_UNIT,
                    describePlayerId(session.playerId));
        }
    }

    private static void relayKillOrders(DatagramSocket socket, PlayerSession session)
    {
        final long now = System.nanoTime();
        List<NpcRegistry.Npc> orders = NpcRegistry.pendingKillOrders(session.playerId, now);

        if (orders.isEmpty())
        {
            return;
        }

        List<String> fields = new ArrayList<>();
        int count = 0;
        boolean logBatch = false;

        for (NpcRegistry.Npc npc : orders)
        {
            if (count >= NPC_REMOVE_BATCH)
            {
                break;
            }

            fields.add(Integer.toString(npc.ownerLocalGuid));
            fields.add(Integer.toString(npc.pendingDamageAttackerId));

            NpcRegistry.markKillOrderSent(npc, now);
            logBatch = logBatch || npc.killOrderSends == 1 || (npc.killOrderSends % 10) == 0;
            count++;
        }

        if (count == 0)
        {
            return;
        }

        fields.add(0, Integer.toString(count));
        sendNpcPacket(socket, session, "NPCKILL", fields);

        if (logBatch)
        {
            dbg("NPC kill orders: %d -> %s (server-confirmed deaths)\n",
                    count, describePlayerId(session.playerId));
        }
    }

    private static void relaySessionWork(
            DatagramSocket socket,
            PlayerSession session,
            long now,
            long snapshotMs)
    {
        relayNpcs(socket, session, now, snapshotMs);
        relayScaling(socket, session, now);
        relayHits(socket, session);
        relayKillOrders(socket, session);
        drainOutbound(socket, session);
    }

    private static double distanceSquared(PlayerSession source, PlayerSession target)
    {
        if (source == target)
        {
            return 0.0;
        }
        if (!source.hasPosition || !target.hasPosition || source.area != target.area)
        {
            return Double.MAX_VALUE;
        }
        double dx = source.posX - target.posX;
        double dy = source.posY - target.posY;
        double dz = source.posZ - target.posZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean handleUdpTransportMessage(DatagramSocket socket, ClientEndpoint sender, String msg)
    {
        String[] parts = msg.split("\t", -1);

        if (parts.length >= 4 && "HELLO".equals(parts[0]) && "2".equals(parts[1]))
        {
            String name = unescapeField(parts[2]).trim();
            if (isValidUsername(name) && "UDP".equals(parts[3]))
            {
                PlayerSession session = players.get(normalizeUsernameKey(name));
                if (session != null && normalizeIp(session.remoteIp).equals(normalizeIp(sender.address.getHostAddress())))
                {
                    session.markUdp(sender, System.nanoTime());
                    safeSend(socket, sender, "HELLOACK\t2\tUDP\t" + session.playerId);
                }
            }
            return true;
        }

        if (parts.length >= 4 && "PING".equals(parts[0]) && "2".equals(parts[1]) && "UDP".equals(parts[3]))
        {
            String name = unescapeField(parts[2]).trim();
            PlayerSession session = players.get(normalizeUsernameKey(name));
            if (session != null && normalizeIp(session.remoteIp).equals(normalizeIp(sender.address.getHostAddress())))
            {
                session.markUdp(sender, System.nanoTime());
            }
            safeSend(socket, sender, "PONG\t2\tUDP");
            return true;
        }

        return false;
    }

    private static void handleTcpMessage(TcpTransport.Connection connection, String msg)
    {
        try
        {
            String senderIp = normalizeIp(connection.remoteIp());

            if (isIpBanned(senderIp))
            {
                connection.sendAndClose("ERROR\tBANNED");
                return;
            }

            if (whitelistEnabled.get() && !isIpWhitelisted(senderIp))
            {
                connection.sendAndClose("ERROR\tNOT_WHITELISTED");
                return;
            }

            String[] parts = msg.split("\t", -1);

            if (parts.length >= 4 && "HELLO".equals(parts[0]) && "2".equals(parts[1]) && "TCP".equals(parts[3]))
            {
                String name = unescapeField(parts[2]).trim();
                if (!isValidUsername(name))
                {
                    connection.sendAndClose("ERROR\tINVALID_USERNAME");
                    return;
                }

                connection.setHelloUsername(name);
                if (!bindTcpHello(connection, name, senderIp))
                {
                    return;
                }
                connection.sendReliable("HELLOACK\t2\tTCP\t" + connection.session().playerId);
                replayTcpBacklog(connection.session());
                return;
            }

            if (connection.helloUsername().isEmpty())
            {
                connection.close();
                return;
            }

            ClientEndpoint sender = new ClientEndpoint(
                    java.net.InetAddress.getByName(connection.remoteIp()),
                    connection.session() == null ? 0 : connection.session().playerId);
            handleMessage(udpSocket, sender, connection, msg);
        }
        catch (Exception e)
        {
            if (running.get())
            {
                dbg("TCP message failed from %s: %s\n", connection.remoteAddress(), e.toString());
            }
        }
    }

    private static boolean bindTcpHello(TcpTransport.Connection connection, String username, String senderIp)
    {
        String usernameKey = normalizeUsernameKey(username);
        long now = System.nanoTime();
        PlayerSession session = players.get(usernameKey);

        if (session != null && !normalizeIp(session.remoteIp).equals(senderIp))
        {
            connection.sendAndClose("ERROR\tUSERNAME_TAKEN");
            return false;
        }

        if (session == null)
        {
            UsernameReservation reservation = reservedUsernames.get(usernameKey);
            if (reservation != null && now >= reservation.expiresAt)
            {
                reservedUsernames.remove(usernameKey, reservation);
                reservation = null;
            }
            if (reservation != null && !reservation.ip.equals(senderIp))
            {
                connection.sendAndClose("ERROR\tUSERNAME_TAKEN");
                return false;
            }
            if (reservation != null)
            {
                reservedUsernames.remove(usernameKey, reservation);
            }

            PlayerSession created = new PlayerSession(allocateNewPlayerId(), username, null, now);
            created.remoteIp = senderIp;
            PlayerSession race = players.putIfAbsent(usernameKey, created);
            session = race == null ? created : race;
            if (race == null)
            {
                playersById.put(created.playerId, created);
                dbg("Accepted username %s id=%d for TCP %s\n", username, created.playerId, connection.remoteAddress());
            }
            else if (!normalizeIp(race.remoteIp).equals(senderIp))
            {
                connection.sendAndClose("ERROR\tUSERNAME_TAKEN");
                return false;
            }
        }

        session.markTcp(connection, now);
        return true;
    }

    private static void replayTcpBacklog(PlayerSession session)
    {
        if (session == null || !session.tcpAvailable)
        {
            return;
        }

        String pending;
        int budget = 512;
        while (budget-- > 0 && (pending = session.pendingTcpReplay.poll()) != null)
        {
            String opcode = packetOpcode(pending);
            if (!sendToSession(session, pending, opcode, opcode + ":replay"))
            {
                session.pendingTcpReplay.offer(pending);
                break;
            }
        }
    }

    private static void relayHits(DatagramSocket socket, PlayerSession session)
    {
        List<String> fields = new ArrayList<>();
        int count = 0;

        while (count < NPC_HIT_BATCH)
        {
            String[] hit = session.pendingHits.poll();

            if (hit == null)
            {
                break;
            }

            fields.add(hit[0]);
            fields.add(hit[1]);
            fields.add(hit[2]);
            fields.add(hit[3]);
            fields.add(hit[4]);
            count++;
        }

        if (count == 0)
        {
            return;
        }

        fields.add(0, Integer.toString(count));
        sendNpcPacket(socket, session, "NPCHITF", fields);
    }

    private static void drainOutbound(DatagramSocket socket, PlayerSession session)
    {
        PlayerSession.QueuedPacket packet;
        int guard = 0;

        while (guard < 64 && (packet = session.pendingOutbound.poll()) != null)
        {
            guard++;
            session.outboundDrained.incrementAndGet();
            recordQueueDelay(session.outboundQueueNanos, session.outboundQueueSamples, packet);

            sendNpcPacket(socket, session, packet.opcode, packet.fields);
        }

        guard = 0;

        while (guard < SAVE_DRAIN_PER_TICK && (packet = session.pendingSaveOutbound.poll()) != null)
        {
            guard++;
            session.saveDrained.incrementAndGet();
            recordQueueDelay(session.saveQueueNanos, session.saveQueueSamples, packet);

            sendNpcPacket(socket, session, packet.opcode, packet.fields);
        }
    }

    static void queueOutbound(PlayerSession session, String opcode, List<String> fields)
    {
        if (session == null)
        {
            return;
        }

        if (!session.pendingOutbound.offer(new PlayerSession.QueuedPacket(opcode, fields, System.nanoTime())))
        {
            session.outboundDropped.incrementAndGet();
            return;
        }
        session.outboundEnqueued.incrementAndGet();
        updateHighWater(session.outboundHighWater, session.pendingOutbound.size());
    }

    static void queueSaveOutbound(PlayerSession session, String opcode, List<String> fields)
    {
        if (session == null)
        {
            return;
        }

        if (!session.pendingSaveOutbound.offer(new PlayerSession.QueuedPacket(opcode, fields, System.nanoTime())))
        {
            session.saveDropped.incrementAndGet();
            return;
        }
        session.saveEnqueued.incrementAndGet();
        updateHighWater(session.saveHighWater, session.pendingSaveOutbound.size());
    }

    private static void updateHighWater(java.util.concurrent.atomic.AtomicInteger target, int value)
    {
        int current = target.get();
        while (value > current && !target.compareAndSet(current, value))
        {
            current = target.get();
        }
    }

    private static void recordQueueDelay(
            AtomicLong total,
            AtomicLong samples,
            PlayerSession.QueuedPacket packet)
    {
        total.addAndGet(Math.max(0L, System.nanoTime() - packet.queuedAtNanos));
        samples.incrementAndGet();
    }

    private static String formatCoord(double value)
    {
        long scaled = Math.round(value * 100.0);
        final boolean negative = scaled < 0;

        if (negative)
        {
            scaled = -scaled;
        }

        StringBuilder sb = new StringBuilder(12);

        if (negative)
        {
            sb.append('-');
        }

        sb.append(scaled / 100);
        sb.append('.');

        final long cents = scaled % 100;

        if (cents < 10)
        {
            sb.append('0');
        }

        sb.append(cents);

        return sb.toString();
    }

    private static boolean isRealtimeOpcode(String opcode)
    {
        return PacketRegistry.route(opcode) == PacketRegistry.Route.REALTIME;
    }

    private static boolean isClientRealtimeOpcode(String opcode)
    {
        return isRealtimeOpcode(opcode);
    }

    private static String packetOpcode(String packetText)
    {
        int separator = packetText.indexOf('\t');
        return separator < 0 ? packetText : packetText.substring(0, separator);
    }

    private static boolean isSaveOpcode(String opcode)
    {
        return PacketRegistry.route(opcode) == PacketRegistry.Route.BULK;
    }

    private static boolean sendToSession(PlayerSession session, String packetText, String opcode, String realtimeKey)
    {
        if (session == null)
        {
            return false;
        }

        long now = System.nanoTime();
        boolean realtime = isRealtimeOpcode(opcode);
        TcpTransport.Connection tcp = session.tcpConnection;
        boolean tcpReady = session.tcpAvailable && tcp != null && tcp.isOpen();

        if (realtime)
        {
            boolean udpReady = session.udpAvailable
                    && session.endpoint != null
                    && (now - session.lastUdpSeen) <= 6_000_000_000L;
            UdpBatcher batcher = udpBatcher;
            String replaceKey = "NPCFAST".equals(opcode) ? null : realtimeKey;
            byte[] packetBytes;
            try
            {
                packetBytes = BinaryPacketCodec.encodeText(packetText);
            }
            catch (IllegalArgumentException invalid)
            {
                malformedPackets.incrementAndGet();
                return false;
            }
            if (udpReady && batcher != null && batcher.enqueue(session, replaceKey, packetBytes))
            {
                recordTransportRoute("UDP TX", opcode, packetText.length());
                return true;
            }
            totalSendFailures.incrementAndGet();
            return false;
        }

        if (tcpReady)
        {
            boolean sent = isSaveOpcode(opcode) ? tcp.sendBulk(packetText) : tcp.sendReliable(packetText);
            if (sent)
            {
                totalPacketsSent.incrementAndGet();
                session.tcpPacketsSent.incrementAndGet();
                session.tcpBytesSent.addAndGet(packetText.length() + 4L);
                recordTransportRoute("TCP TX", opcode, packetText.length() + 4);
                return true;
            }
        }

        if (!session.pendingTcpReplay.offer(packetText))
        {
            session.outboundDropped.incrementAndGet();
        }
        totalSendFailures.incrementAndGet();
        return false;
    }

    private static void recordTransportRoute(String direction, String opcode, int bytes)
    {
        String name = opcode == null || opcode.isEmpty() ? "?" : opcode;
        transportRoutes.computeIfAbsent(direction + " " + name, ignored -> new AtomicLong()).incrementAndGet();
    }

    private static boolean sendUdp(ClientEndpoint client, String packetText)
    {
        DatagramSocket socket = udpSocket;

        if (socket == null || socket.isClosed() || client == null)
        {
            return false;
        }

        byte[] data;
        try
        {
            data = BinaryPacketCodec.encodeText(packetText);
        }
        catch (IllegalArgumentException invalid)
        {
            malformedPackets.incrementAndGet();
            return false;
        }

        try
        {
            socket.send(new DatagramPacket(data, data.length, client.address, client.port));
            totalPacketsSent.incrementAndGet();
            return true;
        }
        catch (Exception e)
        {
            totalSendFailures.incrementAndGet();
            return false;
        }
    }

    private static void sendNpcPacket(DatagramSocket socket, PlayerSession session, String opcode, List<String> fields)
    {
        String packetText = buildTypedPacket(opcode, session.playerId, session.username, fields);
        if (sendToSession(session, packetText, opcode, opcode))
        {
            totalNpcPacketsSent.incrementAndGet();

            if ("NPCNEW".equals(opcode))
            {
                npcSpawnPacketsSent.incrementAndGet();
            }
            else if ("NPCMOV".equals(opcode))
            {
                npcMovePacketsSent.incrementAndGet();
            }
            else if ("NPCEND".equals(opcode))
            {
                npcEndPacketsSent.incrementAndGet();
            }
            else
            {
                npcOwnPacketsSent.incrementAndGet();
            }
        }
    }

    private static void storePosition(PlayerSession session, List<String> fields, int offset)
    {
        if (fields.size() < offset + 7)
        {
            return;
        }

        Double x = parseDoubleOrNull(fields.get(offset));
        Double y = parseDoubleOrNull(fields.get(offset + 1));
        Double z = parseDoubleOrNull(fields.get(offset + 2));
        Integer area = parseIntegerOrNull(fields.get(offset + 6));

        if (x == null || y == null || z == null || area == null)
        {
            return;
        }

        session.storePosition(x, y, z, area);
    }

    private static Double parseDoubleOrNull(String value)
    {
        if (value == null || value.isEmpty() || value.length() > 32)
        {
            return null;
        }

        try
        {
            double parsed = Double.parseDouble(value);

            if (Double.isNaN(parsed) || Double.isInfinite(parsed))
            {
                return null;
            }

            return parsed;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static Integer parseIntegerOrNull(String value)
    {
        if (!isInteger(value))
        {
            return null;
        }

        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static void cleanupLoop()
    {
        while (running.get())
        {
            try
            {
                long now = System.nanoTime();

                for (Map.Entry<String, PlayerSession> entry : players.entrySet())
                {
                    PlayerSession session = entry.getValue();
                    session.expireTransportPaths(now);
                    boolean negotiationExpired = !session.transportReady()
                            && session.transportIncompleteSince != 0L
                            && (now - session.transportIncompleteSince) > 10_000_000_000L;
                    if ((now - session.lastSeen) > PLAYER_TIMEOUT_NANOS || negotiationExpired)
                    {
                        reserveTimedOutPlayer(entry.getKey(), session, now);
                    }
                }

                for (Map.Entry<String, UsernameReservation> entry : reservedUsernames.entrySet())
                {
                    UsernameReservation reservation = entry.getValue();
                    if (now >= reservation.expiresAt)
                    {
                        if (reservedUsernames.remove(entry.getKey(), reservation))
                        {
                            dbg("Released reserved username %s for IP %s\n",
                                    reservation.username,
                                    reservation.ip);

                            partyRemoveMember(entry.getKey(), "username released");
                        }
                    }
                }

                Thread.sleep(1000);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private static void broadcastLoop(DatagramSocket socket)
    {
        long lastHeartbeat = System.nanoTime();

        while (running.get())
        {
            try
            {
                List<PlayerSession> sessions = activeSessions();
                long tickNanos = System.nanoTime();

                if (BotManager.count() > 0 && !sessions.isEmpty())
                {
                    BotManager.tick(sessions.get(0), tickNanos);

                    for (PlayerSession bot : BotManager.sessions())
                    {
                        sendChunk(socket, nearbyRecipients(sessions, bot), bot, "MOVE", BotManager.movementFields(bot));
                    }

                    sessions.addAll(BotManager.sessions());
                }

                List<PlayerSession> everyone = uniqueSessions(sessions);
                int packetsSentThisTick = 0;

                for (PlayerSession session : sessions)
                {
                    List<PlayerSession> nearby = nearbyRecipients(sessions, session);

                    packetsSentThisTick += broadcastChunk(socket, nearby, session, "UPDATE1A", session.update1A, tickNanos);
                    packetsSentThisTick += broadcastChunk(socket, nearby, session, "UPDATE1B", session.update1B, tickNanos);
                    packetsSentThisTick += broadcastChunk(socket, nearby, session, "UPDATE2A", session.update2A, tickNanos);
                    packetsSentThisTick += broadcastChunk(socket, nearby, session, "UPDATE2B", session.update2B, tickNanos);
                    packetsSentThisTick += broadcastChunk(socket, nearby, session, "UPDATE3", session.update3, tickNanos);
                    packetsSentThisTick += broadcastChunk(socket, nearby, session, "UPDATE4", session.update4, tickNanos);
                }

                totalBroadcastTicks.incrementAndGet();
                lastBroadcastTickNanos = System.nanoTime();

                long now = System.nanoTime();
                if ((now - lastHeartbeat) >= BROADCAST_HEARTBEAT_NANOS)
                {
                    dbg("Broadcast heartbeat: players=%d recipients=%d packetsSent=%d totalPacketsSent=%d totalSendFailures=%d\n",
                            players.size(),
                            everyone.size(),
                            packetsSentThisTick,
                            totalPacketsSent.get(),
                            totalSendFailures.get());
                    lastHeartbeat = now;
                }

                Thread.sleep(100);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private static List<PlayerSession> uniqueSessions(List<PlayerSession> sessions)
    {
        Set<Integer> ids = new HashSet<>();
        List<PlayerSession> unique = new ArrayList<>();
        for (PlayerSession session : sessions)
        {
            if (session != null && ids.add(session.playerId))
            {
                unique.add(session);
            }
        }
        return unique;
    }

    private static List<PlayerSession> nearbyRecipients(PlayerSession source)
    {
        Map<Integer, PlayerSession> candidates = new HashMap<>();
        candidates.put(source.playerId, source);

        if (source.hasPosition)
        {
            for (PlayerSession candidate : SpatialIndex.query(
                    source.area, source.posX, source.posY, INTEREST_RADIUS))
            {
                candidates.put(candidate.playerId, candidate);
            }
        }

        for (PlayerSession candidate : partySessions(source.partyId))
        {
            candidates.put(candidate.playerId, candidate);
        }

        return filterNearbyCandidates(source, candidates);
    }

    private static List<PlayerSession> nearbyRecipients(List<PlayerSession> sessions, PlayerSession source)
    {
        Map<Integer, PlayerSession> candidates = new HashMap<>();
        candidates.put(source.playerId, source);

        if (source.hasPosition)
        {
            for (PlayerSession candidate : SpatialIndex.query(
                    source.area, source.posX, source.posY, INTEREST_RADIUS))
            {
                candidates.put(candidate.playerId, candidate);
            }
        }

        for (PlayerSession candidate : partySessions(source.partyId))
        {
            candidates.put(candidate.playerId, candidate);
        }

        return filterNearbyCandidates(source, candidates);
    }

    private static List<PlayerSession> filterNearbyCandidates(
            PlayerSession source,
            Map<Integer, PlayerSession> candidates)
    {
        List<PlayerSession> result = new ArrayList<>();
        for (PlayerSession candidate : candidates.values())
        {
            if (candidate.transportReady() && isWithinInterest(source, candidate))
            {
                result.add(candidate);
            }
        }
        return result;
    }

    private static List<PlayerSession> partySessions(int partyId)
    {
        if (partyId == 0)
        {
            return Collections.emptyList();
        }

        Party party = parties.get(partyId);
        if (party == null)
        {
            return Collections.emptyList();
        }

        List<PlayerSession> result = new ArrayList<>();
        for (String usernameKey : party.snapshot())
        {
            PlayerSession session = players.get(usernameKey);
            if (session != null)
            {
                result.add(session);
            }
        }
        return result;
    }

    private static boolean isWithinInterest(PlayerSession source, PlayerSession target)
    {
        if (source == target)
        {
            return true;
        }

        if (source.partyId != 0 && source.partyId == target.partyId)
        {
            return true;
        }

        if (!target.visiblePlayers.isEmpty() || target.visibilitySentNanos != 0L)
        {
            return target.visiblePlayers.contains(source.playerId);
        }

        if (!source.hasPosition || !target.hasPosition)
        {
            return true;
        }

        if (source.area != target.area)
        {
            return false;
        }

        double dx = source.posX - target.posX;
        double dy = source.posY - target.posY;
        double dz = source.posZ - target.posZ;

        return (dx * dx + dy * dy + dz * dz) <= INTEREST_RADIUS_SQUARED;
    }

    private static int broadcastChunk(
            DatagramSocket socket,
            List<PlayerSession> recipients,
            PlayerSession session,
            String opcode,
            PlayerSession.ChunkSlot slot,
            long nowNanos)
    {
        List<String> fields = slot.fields;

        if (fields == null || fields.isEmpty())
        {
            return 0;
        }

        boolean changed = slot.revision != slot.sentRevision;
        boolean keepaliveDue = (nowNanos - slot.sentNanos) >= CHUNK_KEEPALIVE_NANOS;

        if (!changed && !keepaliveDue)
        {
            return 0;
        }

        slot.sentRevision = slot.revision;
        slot.sentNanos = nowNanos;

        return sendChunk(socket, recipients, session, opcode, fields);
    }

    private static int sendChunk(
            DatagramSocket socket,
            List<PlayerSession> recipients,
            PlayerSession session,
            String opcode,
            List<String> fields)
    {
        if (fields == null || fields.isEmpty())
        {
            return 0;
        }

        String packetText = buildTypedPacket(opcode, session.playerId, session.username, fields);
        byte[] data = packetText.getBytes(StandardCharsets.UTF_8);

        if (data.length > 1200)
        {
            dbg("Large packet: opcode=%s user=%s bytes=%d\n data=%s", opcode, session.username, data.length, packetText);
        }

        int sent = 0;

        for (PlayerSession recipient : recipients)
        {
            if (sendToSession(
                    recipient,
                    packetText,
                    opcode,
                    opcode + ":" + session.playerId))
            {
                sent++;
            }
        }

        return sent;
    }

    private static String buildTypedPacket(String opcode, int playerId, String username, List<String> fields)
    {
        int estimate = opcode.length() + username.length() + 16;

        for (String field : fields)
        {
            estimate += field.length() + 1;
        }

        StringBuilder sb = new StringBuilder(estimate);

        sb.append(opcode)
                .append('\t')
                .append(playerId)
                .append('\t')
                .append(escapeField(username));

        for (String field : fields)
        {
            sb.append('\t').append(escapeField(field));
        }

        return sb.toString();
    }

    private static void consoleLoop(DatagramSocket socket)
    {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)))
        {
            String line;
            while (running.get() && (line = reader.readLine()) != null)
            {
                handleConsoleCommand(socket, line.trim());
            }
        }
        catch (IOException e)
        {
            if (running.get())
            {
                e.printStackTrace();
            }
        }
    }

    private static void handleConsoleCommand(DatagramSocket socket, String line)
    {
        if (line.isEmpty())
        {
            return;
        }

        if (line.equals("list"))
        {
            dbg("---- Connected players (%d) ----\n", players.size());
            long now = System.nanoTime();

            for (PlayerSession session : players.values())
            {
                long secsSinceSeen = Math.max(0L, (now - session.lastSeen) / 1_000_000_000L);

                String locationRaw = getLocation(session.update1A.fields);
                String region = getRegion(locationRaw);

                List<String> coords = getCoords(session.update1A.fields);
                String coordsStr = "?";
                if (coords.size() == 3)
                {
                    coordsStr = "(" + coords.get(0) + ", " + coords.get(1) + ", " + coords.get(2) + ")";
                }

                String worked = session.udpWorked && session.tcpWorked
                        ? "UDP+TCP"
                        : session.tcpWorked ? "TCP" : session.udpWorked ? "UDP" : "none";
                boolean udpActive = session.udpAvailable
                        && session.endpoint != null
                        && (now - session.lastUdpSeen) <= 6_000_000_000L;
                boolean tcpActive = session.tcpConnection != null && session.tcpConnection.isOpen();
                String active = udpActive && tcpActive
                        ? "UDP+TCP"
                        : tcpActive ? "TCP" : udpActive ? "UDP" : "none";

                Party party = partyOf(normalizeUsernameKey(session.username));
                String partyScale = party == null
                        ? "server"
                        : String.format("x%.1f/x%.0f", party.scaleStepMilli() / 1000.0, party.scaleMaxMilli() / 1000.0);

                dbg("name=\"%s\"  ip=%s  ready=%s  transportWorked=%s  transportActive=%s  partyScale=%s  location=%s  coords=%s  lastSeen=%ds ago\n",
                        session.username,
                        session.remoteIp,
                        session.transportReady() ? "yes" : "no",
                        worked,
                        active,
                        partyScale,
                        locationRaw.isEmpty() ? "?" : region,
                        coordsStr,
                        secsSinceSeen);
            }

            dbgNotime("\n");
            return;
        }

        if (line.equals("announce") || line.startsWith("announce "))
        {
            String message = trimCommandArg(line, "announce");

            if (message.isEmpty() || message.length() > 256)
            {
                dbg("Usage: announce <message>  (1-256 characters)\n\n");
                return;
            }

            List<PlayerSession> recipients = new ArrayList<>(players.values());
            String packet = buildTypedPacket("ANNOUNCE", 0, "Server", List.of(message));
            int sent = 0;

            for (PlayerSession recipient : recipients)
            {
                if (sendToSession(recipient, packet, "ANNOUNCE", "ANNOUNCE"))
                {
                    sent++;
                }
            }

            dbg("Announcement sent to %d/%d connected player(s).\n\n", sent, recipients.size());
            return;
        }

        if (line.equals("stats"))
        {
            long now = System.nanoTime();
            long lastTickAgeMs = (lastBroadcastTickNanos == 0L)
                    ? -1L
                    : Math.max(0L, (now - lastBroadcastTickNanos) / 1_000_000L);

            dbg("---- Server stats ----\n");
            dbg("players=%d\n", players.size());
            dbg("activeDualTransport=%d tcpConnections=%d\n",
                    activeSessions().size(), tcpTransport == null ? 0 : tcpTransport.connectionCount());
            dbg("reservedUsernames=%d\n", reservedUsernames.size());
            dbg("broadcastTicks=%d\n", totalBroadcastTicks.get());
            dbg("totalPacketsSent=%d\n", totalPacketsSent.get());
            dbg("totalSendFailures=%d\n", totalSendFailures.get());
            dbg("transportMisroutes=%d\n", transportMisroutes.get());
            dbg("protocolMalformed=%d protocolUnknown=%d protocolOversized=%d passiveHitDistanceOutliers=%d\n",
                    malformedPackets.get(),
                    unknownPackets.get(),
                    oversizedPackets.get(),
                    passiveHitDistanceOutliers.get());
            UdpBatcher batcher = udpBatcher;
            if (batcher != null)
            {
                dbg("%s\n", batcher.report());
            }
            dbg("lastBroadcastTickAgeMs=%d\n", lastTickAgeMs);
            dbg("npcs=%d admitted=%d rejectedDuplicate=%d npcPacketsSent=%d\n\n",
                    NpcRegistry.npcCount(),
                    NpcRegistry.admittedCount(),
                    NpcRegistry.rejectedDuplicateCount(),
                    totalNpcPacketsSent.get());
            return;
        }

        if (line.startsWith("bots"))
        {
            String arg = trimCommandArg(line, "bots");
            int requested = 3;

            if (!arg.isEmpty())
            {
                Integer parsed = parseIntegerOrNull(arg);

                if (parsed == null || parsed < 0 || parsed > 8)
                {
                    dbg("Usage: bots <0-8>   (0 clears)\n\n");
                    return;
                }

                requested = parsed;
            }

            if (requested == 0)
            {
                BotManager.clear();
                dbg("Bots cleared.\n\n");
                return;
            }

            List<PlayerSession> live = new ArrayList<>(players.values());

            if (live.isEmpty())
            {
                dbg("No player connected to anchor bots to.\n\n");
                return;
            }

            dbg("%s\n\n", BotManager.spawn(live.get(0), requested));
            return;
        }

        if (line.equals("npc") || line.equals("npcstats"))
        {
            dbg("---- NPC sync ----\n");
            dbg("npcs=%d admitted=%d rejectedDuplicate=%d behaviorPending=%d\n",
                    NpcRegistry.npcCount(),
                    NpcRegistry.admittedCount(),
                    NpcRegistry.rejectedDuplicateCount(),
                    NpcRegistry.pendingBehaviorCount());

            for (PlayerSession session : players.values())
            {
                dbg("  %-18s owned=%d known=%d area=%d\n",
                        session.username,
                        NpcRegistry.countOwnedBy(session.playerId),
                        session.knownNpcs.size(),
                        session.area);
            }

            dbg("packetsTotal spawn=%d move=%d end=%d\n\n",
                    npcSpawnPacketsSent.get(),
                    npcMovePacketsSent.get(),
                    npcEndPacketsSent.get());
            return;
        }

        if (line.equals("transport") || line.equals("netstats"))
        {
            dbg("---- Transport routes ----\n");

            for (PlayerSession session : players.values())
            {
                dbg("name=\"%s\" txUdp=%d/%dB txTcp=%d/%dB rxUdp=%d/%dB rxTcp=%d/%dB\n",
                        session.username,
                        session.udpPacketsSent.get(),
                        session.udpBytesSent.get(),
                        session.tcpPacketsSent.get(),
                        session.tcpBytesSent.get(),
                        session.udpPacketsReceived.get(),
                        session.udpBytesReceived.get(),
                        session.tcpPacketsReceived.get(),
                        session.tcpBytesReceived.get());
            }

            for (Map.Entry<String, AtomicLong> route : new java.util.TreeMap<>(transportRoutes).entrySet())
            {
                dbg("%s=%d\n", route.getKey(), route.getValue().get());
            }

            UdpBatcher batcher = udpBatcher;
            if (batcher != null)
            {
                dbg("%s\n", batcher.report());
            }

            dbgNotime("\n");
            return;
        }

        if (line.equals("queues"))
        {
            dbg("---- Optimization queues ----\n");

            for (PlayerSession session : players.values())
            {
                long outboundSamples = session.outboundQueueSamples.get();
                long saveSamples = session.saveQueueSamples.get();
                double outboundAvgMs = outboundSamples == 0L
                        ? 0.0
                        : (session.outboundQueueNanos.get() / (double) outboundSamples) / 1_000_000.0;
                double saveAvgMs = saveSamples == 0L
                        ? 0.0
                        : (session.saveQueueNanos.get() / (double) saveSamples) / 1_000_000.0;

                dbg("name=\"%s\" normal=%d high=%d enq=%d drain=%d drop=%d avgMs=%.3f save=%d high=%d enq=%d drain=%d drop=%d avgMs=%.3f\n",
                        session.username,
                        session.pendingOutbound.size(),
                        session.outboundHighWater.get(),
                        session.outboundEnqueued.get(),
                        session.outboundDrained.get(),
                        session.outboundDropped.get(),
                        outboundAvgMs,
                        session.pendingSaveOutbound.size(),
                        session.saveHighWater.get(),
                        session.saveEnqueued.get(),
                        session.saveDrained.get(),
                        session.saveDropped.get(),
                        saveAvgMs);
            }

            dbgNotime("\n");
            return;
        }

        if (line.startsWith("kick"))
        {
            String arg = trimCommandArg(line, "kick");
            if (arg.isEmpty())
            {
                dbg("Usage: kick <name|ip>\n\n");
                return;
            }

            PlayerSession victim = findPlayer(arg);
            if (victim == null)
            {
                dbg("No connected player matches \"%s\".\n\n", arg);
                return;
            }

            dbg("Kicking %s (%s)\n", victim.username, victim.remoteIp);
            kickPlayer(socket, victim, "KICK\tKicked by server");
            dbgNotime("\n");
            return;
        }

        if (line.startsWith("ban"))
        {
            String arg = trimCommandArg(line, "ban");
            if (arg.isEmpty())
            {
                dbg("Usage: ban <name|ip>\n\n");
                return;
            }

            PlayerSession victim = findPlayer(arg);
            String ipToBan = (victim != null)
                    ? normalizeIp(victim.remoteIp)
                    : normalizeIp(stripPort(arg));

            if (ipToBan.isEmpty())
            {
                dbg("Could not resolve \"%s\" to an IP address to ban.\n\n", arg);
                return;
            }

            boolean newlyBanned = bannedIps.add(ipToBan);
            if (newlyBanned)
            {
                saveBannedIps();
                dbg("Added IP '%s' to ban list.\n", ipToBan);
            }
            else
            {
                dbg("IP '%s' is already in ban list.\n", ipToBan);
            }

            if (victim != null)
            {
                kickAllPlayersByIp(socket, ipToBan, "KICK\tBanned by server");
            }

            dbgNotime("\n");
            return;
        }

        if (line.startsWith("unban"))
        {
            String arg = trimCommandArg(line, "unban");
            if (arg.isEmpty())
            {
                dbg("Usage: unban <ip>\n\n");
                return;
            }

            String ip = normalizeIp(stripPort(arg));
            if (ip.isEmpty())
            {
                dbg("Usage: unban <ip>\n\n");
                return;
            }

            if (bannedIps.remove(ip))
            {
                saveBannedIps();
                dbg("'%s' has been unbanned.\n\n", ip);
            }
            else
            {
                dbg("IP '%s' is not banned.\n\n", ip);
            }
            return;
        }

        if (line.startsWith("whitelist"))
        {
            String arg = trimCommandArg(line, "whitelist");
            if (arg.isEmpty())
            {
                dbg("Usage: whitelist on|off - enable or disable the whitelist\n");
                dbg("Usage: whitelist <ip>|remove <ip> - add or remove IP to whitelist\n\n");
                return;
            }

            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.equals("on"))
            {
                whitelistEnabled.set(true);
                dbg("The whitelist is now enabled.\n\n");
                return;
            }

            if (lower.equals("off"))
            {
                whitelistEnabled.set(false);
                dbg("The whitelist is now disabled.\n\n");
                return;
            }

            if (lower.startsWith("remove"))
            {
                String ip = normalizeIp(stripPort(trimCommandArg(arg, "remove")));
                if (ip.isEmpty())
                {
                    dbg("Usage: whitelist remove <ip>\n\n");
                    return;
                }

                if (whitelistedIps.remove(ip))
                {
                    saveWhitelistIps();
                    dbg("Removed IP '%s' from whitelist.\n\n", ip);
                }
                else
                {
                    dbg("IP '%s' is not in the whitelist.\n\n", ip);
                }
                return;
            }

            String ip = normalizeIp(stripPort(arg));
            if (ip.isEmpty())
            {
                dbg("Usage: whitelist on|off - enable or disable the whitelist\n");
                dbg("Usage: whitelist <ip>|remove <ip> - add or remove IP to whitelist\n\n");
                return;
            }

            if (whitelistedIps.add(ip))
            {
                saveWhitelistIps();
                dbg("Added IP '%s' to whitelist.\n\n", ip);
            }
            else
            {
                dbg("IP '%s' is already in whitelist.\n\n", ip);
            }
            return;
        }

        if (line.equals("scaling") || line.startsWith("scaling "))
        {
            handleScalingCommand(trimCommandArg(line, "scaling"));
            return;
        }

        if (line.equals("stop"))
        {
            dbg("Shutting down...\n\n");
            shutdown(socket);
            return;
        }

        if (line.equals("help") || line.equals("?"))
        {
            dbg("--------- Help: Index ---------\n");
            dbg("kick <name|ip>            - remove a player and send a kick notice\n");
            dbg("ban <name|ip>             - ban by name/ip and prevent future updates\n");
            dbg("unban <ip>                - remove IP from ban list\n");
            dbg("whitelist on|off|<ip>     - toggle whitelist or add IP to whitelist\n");
            dbg("whitelist remove <ip>     - remove IP from whitelist\n");
            dbg("list                      - list connected players\n");
            dbg("announce <message>        - display a server message to all connected players\n");
            dbg("stats                     - show broadcast/server health counters\n");
            dbg("transport                 - show per-path and per-opcode transport counters\n");
            dbg("npc                       - show NPC sync cells, authority and counters\n");
            dbg("netstats                  - show per-opcode packet and byte counters\n");
            dbg("npcstats                  - show NPC registry and ownership counters\n");
            dbg("queues                    - show per-player queue pressure and latency\n");
            dbg("scaling                   - show NPC health scaling config and curve\n");
            dbg("scaling on|off            - enable or disable NPC health scaling\n");
            dbg("scaling step <value>      - extra health per additional player (0.5 = +50%%)\n");
            dbg("scaling max <value>       - clamp for the health multiplier\n");
            dbg("scaling reload            - re-read npc-scaling.json from disk\n");
            dbg("bots <0-8>                - spawn fake players orbiting the first connected player\n");
            dbg("about                     - info about Witcher Online\n");
            dbg("stop                      - stop server\n");
            dbg("help                      - show this help\n\n");
            return;
        }

        if (line.equals("about"))
        {
            dbg("--------- About ---------\n");
            dbg("Witcher Online v2.0\n");
            dbg("by rejuvenate7\n");
            dbg("https://github.com/rejuvenate7\n");
            dbg("https://discord.gg/KYu9c5TWej\n\n");
            return;
        }

        dbg("Unknown command: %s (type 'help')\n\n", line);
    }

    private static void handleScalingCommand(String arg)
    {
        if (arg.isEmpty() || arg.equals("show"))
        {
            printScaling();
            return;
        }

        if (arg.equals("on") || arg.equals("off"))
        {
            NpcScaling.setEnabled(arg.equals("on"));
            scalingDirty.set(true);
            dbg("NPC health scaling is now %s.\n", arg.equals("on") ? "enabled" : "disabled");
            printScaling();
            return;
        }

        if (arg.equals("reload"))
        {
            NpcScaling.load();
            scalingDirty.set(true);
            printScaling();
            return;
        }

        if (arg.startsWith("step"))
        {
            Double value = parseDouble(trimCommandArg(arg, "step"));

            if (value == null)
            {
                dbg("Usage: scaling step <value> - extra health per additional player\n\n");
                return;
            }

            NpcScaling.setPerExtraPlayer(value);
            scalingDirty.set(true);
            printScaling();
            return;
        }

        if (arg.startsWith("max"))
        {
            Double value = parseDouble(trimCommandArg(arg, "max"));

            if (value == null)
            {
                dbg("Usage: scaling max <value> - clamp for the health multiplier\n\n");
                return;
            }

            NpcScaling.setMaxMultiplier(value);
            scalingDirty.set(true);
            printScaling();
            return;
        }

        dbg("Usage: scaling [on|off|step <value>|max <value>|reload]\n\n");
    }

    private static void printScaling()
    {
        dbg("--------- NPC health scaling ---------\n");
        dbg("%s\n", NpcScaling.describe());
        dbg("config: %s\n", npcScalingPath());

        StringBuilder curve = new StringBuilder();

        for (int i = 1; i <= 8; i++)
        {
            if (i > 1)
            {
                curve.append("  ");
            }

            curve.append(String.format("%d:x%.2f", i, NpcScaling.scaleMilliFor(i) / (double) NpcScaling.SCALE_UNIT));
        }

        dbg("curve: %s\n\n", curve.toString());

        for (Party party : parties.values())
        {
            dbg("party #%d leader=%s step=x%.1f max=x%.0f\n",
                    party.partyId,
                    party.leader(),
                    party.scaleStepMilli() / 1000.0,
                    party.scaleMaxMilli() / 1000.0);
        }

        if (!parties.isEmpty())
        {
            dbgNotime("\n");
        }
    }

    private static Double parseDouble(String text)
    {
        if (text == null || text.isEmpty())
        {
            return null;
        }

        try
        {
            return Double.parseDouble(text.trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static void kickPlayer(DatagramSocket socket, PlayerSession victim, String kickText)
    {
        String usernameKey = normalizeUsernameKey(victim.username);

        PlayerSession removed = players.remove(usernameKey);
        reservedUsernames.remove(usernameKey);

        if (removed != null)
        {
            playersById.remove(removed.playerId, removed);
            SpatialIndex.remove(removed);
            sendToSession(removed, kickText, "KICK", "KICK");
            TcpTransport.Connection tcp = removed.tcpConnection;
            if (tcp != null)
            {
                tcp.closeWhenDrained();
            }
        }
    }

    private static void kickAllPlayersByIp(DatagramSocket socket, String ip, String kickText)
    {
        List<PlayerSession> victims = new ArrayList<>();
        for (PlayerSession session : activeSessions())
        {
            String sessionIp = normalizeIp(session.remoteIp);
            if (sessionIp.equals(ip))
            {
                victims.add(session);
            }
        }

        for (PlayerSession victim : victims)
        {
            dbg("Removing %s (%s) after ban\n", victim.username, victim.remoteIp);
            kickPlayer(socket, victim, kickText);
        }
    }

    private static PlayerSession findPlayer(String arg)
    {
        PlayerSession exact = players.get(normalizeUsernameKey(arg));
        if (exact != null)
        {
            return exact;
        }

        String ipArg = normalizeIp(stripPort(arg));

        for (PlayerSession session : players.values())
        {
            String sessionIp = normalizeIp(session.remoteIp);
            if (!ipArg.isEmpty() && sessionIp.equals(ipArg))
            {
                return session;
            }
        }

        return null;
    }

    private static void shutdown(DatagramSocket socket)
    {
        if (!running.compareAndSet(true, false))
        {
            return;
        }

        UdpBatcher batcher = udpBatcher;
        if (batcher != null)
        {
            batcher.close();
            udpBatcher = null;
        }

        if (socket != null)
        {
            socket.close();
        }

        TcpTransport transport = tcpTransport;
        if (transport != null)
        {
            transport.close();
            tcpTransport = null;
        }

        HttpServer server = statusServer;
        if (server != null)
        {
            server.stop(0);
            statusServer = null;
        }
    }

    private static void sendText(DatagramSocket socket, ClientEndpoint client, String text) throws Exception
    {
        if (socket == null || client == null)
        {
            return;
        }

        byte[] data = BinaryPacketCodec.encodeText(text);
        DatagramPacket packet = new DatagramPacket(data, data.length, client.address, client.port);
        socket.send(packet);
    }

    private static void safeSend(DatagramSocket socket, ClientEndpoint client, String text)
    {
        try
        {
            sendText(socket, client, text);
        }
        catch (Exception e)
        {
            if (running.get())
            {
                e.printStackTrace();
            }
        }
    }

    private static void safeReply(
            DatagramSocket socket,
            ClientEndpoint client,
            TcpTransport.Connection tcp,
            String text)
    {
        if (tcp != null && tcp.isOpen())
        {
            tcp.sendReliable(text);
            return;
        }

        safeSend(socket, client, text);
    }

    private static boolean isIpBanned(String ip)
    {
        return bannedIps.contains(normalizeIp(ip));
    }

    private static boolean isIpWhitelisted(String ip)
    {
        return whitelistedIps.contains(normalizeIp(ip));
    }

    private static void loadBannedIps()
    {
        Set<String> loaded = loadJsonStringArray(bannedPlayersPath());
        bannedIps.clear();
        bannedIps.addAll(loaded);
        if (!loaded.isEmpty())
        {
            dbg("Loaded %d banned IP(s) from %s\n", loaded.size(), bannedPlayersPath());
        }
    }

    private static void saveBannedIps()
    {
        saveJsonStringArray(bannedPlayersPath(), bannedIps, "banned IP list");
    }

    private static void loadWhitelistIps()
    {
        Set<String> loaded = loadJsonStringArray(whitelistedPlayersPath());
        whitelistedIps.clear();
        whitelistedIps.addAll(loaded);
        if (!loaded.isEmpty())
        {
            dbg("Loaded %d whitelisted IP(s) from %s\n", loaded.size(), whitelistedPlayersPath());
        }
    }

    private static void saveWhitelistIps()
    {
        saveJsonStringArray(whitelistedPlayersPath(), whitelistedIps, "whitelist IP list");
    }

    private static Set<String> loadJsonStringArray(Path path)
    {
        Set<String> out = new HashSet<>();

        if (!Files.exists(path))
        {
            return out;
        }

        try
        {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            StringBuilder cur = new StringBuilder();
            boolean inString = false;
            boolean escaping = false;

            for (int i = 0; i < content.length(); i++)
            {
                char c = content.charAt(i);

                if (!inString)
                {
                    if (c == '"')
                    {
                        inString = true;
                        cur.setLength(0);
                    }
                    continue;
                }

                if (escaping)
                {
                    cur.append(c);
                    escaping = false;
                    continue;
                }

                if (c == '\\')
                {
                    escaping = true;
                    continue;
                }

                if (c == '"')
                {
                    String value = normalizeIp(cur.toString());
                    if (!value.isEmpty())
                    {
                        out.add(value);
                    }
                    inString = false;
                    continue;
                }

                cur.append(c);
            }
        }
        catch (IOException e)
        {
            dbg("Failed to read %s: %s\n", path, e.getMessage());
        }

        return out;
    }

    private static void saveJsonStringArray(Path path, Set<String> values, String label)
    {
        try
        {
            Files.createDirectories(path.getParent());
            List<String> sorted = new ArrayList<>(values);
            Collections.sort(sorted);

            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < sorted.size(); i++)
            {
                if (i > 0)
                {
                    sb.append(",\n");
                }
                sb.append("  \"").append(jsonEscape(sorted.get(i))).append("\"");
            }
            sb.append("\n]\n");

            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            dbg("Failed to write %s to %s: %s\n", label, path, e.getMessage());
        }
    }

    private static Properties loadServerProperties()
    {
        Properties properties = new Properties();
        Path path = serverPropertiesPath();

        if (!Files.exists(path))
        {
            return properties;
        }

        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
        {
            properties.load(reader);
        }
        catch (IOException e)
        {
            dbg("Failed to read %s: %s\n", path, e.getMessage());
        }

        return properties;
    }

    private static int choosePort(String[] args, Properties properties)
    {
        if (args.length >= 1)
        {
            Integer cliPort = parsePort(args[0]);
            if (cliPort != null)
            {
                return cliPort;
            }
            System.err.println("Invalid CLI port: " + args[0] + " (falling back)");
        }

        String propertyPort = properties.getProperty("port");
        if (propertyPort != null)
        {
            Integer parsed = parsePort(propertyPort.trim());
            if (parsed != null)
            {
                return parsed;
            }
            System.err.println("Invalid server.properties port: " + propertyPort + " (falling back)");
        }

        return DEFAULT_PORT;
    }

    private static Integer parsePort(String text)
    {
        try
        {
            int port = Integer.parseInt(text);
            return (port >= 1 && port <= 65535) ? port : null;
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean defaultValue)
    {
        String value = properties.getProperty(key);
        if (value == null)
        {
            return defaultValue;
        }

        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.equals("true") || lower.equals("1") || lower.equals("yes") || lower.equals("on");
    }

    private static Path serverPropertiesPath()
    {
        return appDir().resolve("server.properties");
    }

    private static Path bannedPlayersPath()
    {
        return appDir().resolve("banned-players.json");
    }

    private static Path whitelistedPlayersPath()
    {
        return appDir().resolve("whitelisted-players.json");
    }

    static Path npcScalingPath()
    {
        return appDir().resolve("npc-scaling.json");
    }

    private static Path appDir()
    {
        try
        {
            CodeSource codeSource = WitcherServer.class.getProtectionDomain().getCodeSource();
            if (codeSource != null && codeSource.getLocation() != null)
            {
                Path path = Paths.get(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
                return Files.isRegularFile(path) ? path.getParent() : path;
            }
        }
        catch (Exception ignored)
        {
        }

        return Paths.get(".").toAbsolutePath().normalize();
    }

    static String normalizeIp(String ip)
    {
        String value = stripPort(ip == null ? "" : ip.trim());
        if (value.isEmpty())
        {
            return "";
        }

        int zone = value.indexOf('%');
        if (zone >= 0)
        {
            value = value.substring(0, zone);
        }

        try
        {
            java.net.InetAddress address = java.net.InetAddress.getByName(value);
            if (address.isLoopbackAddress())
            {
                return "loopback";
            }
            return address.getHostAddress().toLowerCase(Locale.ROOT);
        }
        catch (Exception ignored)
        {
            return value.toLowerCase(Locale.ROOT);
        }
    }

    private static String describeSessionAddress(PlayerSession session)
    {
        if (session == null)
        {
            return "unknown";
        }
        if (session.endpoint != null)
        {
            return session.endpoint.toString();
        }
        if (session.tcpConnection != null)
        {
            return session.tcpConnection.remoteAddress();
        }
        return session.remoteIp == null || session.remoteIp.isEmpty() ? "unknown" : session.remoteIp;
    }

    private static String stripPort(String value)
    {
        if (value == null)
        {
            return "";
        }

        String trimmed = value.trim();
        if (trimmed.isEmpty())
        {
            return "";
        }

        if (trimmed.startsWith("[") && trimmed.contains("]"))
        {
            int close = trimmed.indexOf(']');
            return trimmed.substring(1, close);
        }

        long colonCount = trimmed.chars().filter(ch -> ch == ':').count();
        if (colonCount == 1)
        {
            int idx = trimmed.indexOf(':');
            String left = trimmed.substring(0, idx);
            String right = trimmed.substring(idx + 1);
            if (!left.isEmpty() && right.chars().allMatch(Character::isDigit))
            {
                return left;
            }
        }

        return trimmed;
    }

    private static String trimCommandArg(String line, String command)
    {
        return line.length() <= command.length() ? "" : line.substring(command.length()).trim();
    }

    private static String escapeField(String s)
    {
        boolean clean = true;

        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);

            if (c == '\\' || c == '\t' || c == '\n' || c == '\r')
            {
                clean = false;
                break;
            }
        }

        if (clean)
        {
            return s;
        }

        StringBuilder out = new StringBuilder(s.length() + 8);

        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);

            switch (c)
            {
                case '\\':
                    out.append("\\\\");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }

        return out.toString();
    }

    private static String unescapeField(String s)
    {
        StringBuilder out = new StringBuilder();

        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);

            if (c == '\\' && i + 1 < s.length())
            {
                char n = s.charAt(i + 1);

                if (n == 't')
                {
                    out.append('\t');
                    i++;
                }
                else if (n == 'n')
                {
                    out.append('\n');
                    i++;
                }
                else if (n == 'r')
                {
                    out.append('\r');
                    i++;
                }
                else if (n == '\\')
                {
                    out.append('\\');
                    i++;
                }
                else
                {
                    out.append(c);
                }
            }
            else
            {
                out.append(c);
            }
        }

        return out.toString();
    }

    private static String jsonEscape(String s)
    {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '\\' || c == '"')
            {
                out.append('\\').append(c);
            }
            else if (c >= 0x20)
            {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<String> logQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger logQueueSize =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final AtomicLong logDropped = new AtomicLong();
    private static final int LOG_QUEUE_LIMIT = 8192;

    private static void enqueueLog(String line)
    {
        if (logQueueSize.get() >= LOG_QUEUE_LIMIT)
        {
            logDropped.incrementAndGet();
            return;
        }

        logQueue.add(line);
        logQueueSize.incrementAndGet();
    }

    static void startLogThread()
    {
        Thread thread = new Thread(() ->
        {
            StringBuilder batch = new StringBuilder(4096);

            while (true)
            {
                String line;
                batch.setLength(0);

                while ((line = logQueue.poll()) != null)
                {
                    logQueueSize.decrementAndGet();
                    batch.append(line);

                    if (batch.length() > 16384)
                    {
                        break;
                    }
                }

                if (batch.length() > 0)
                {
                    System.out.print(batch);
                    System.out.flush();
                }

                long dropped = logDropped.getAndSet(0);

                if (dropped > 0)
                {
                    System.out.print("[log] dropped " + dropped + " lines under pressure\n");
                }

                try
                {
                    Thread.sleep(50);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "log-writer");

        thread.setDaemon(true);
        thread.start();
    }

    private static void dbgNotime(String format, Object... args)
    {
        enqueueLog(String.format(format, args));
    }

    static void dbg(String format, Object... args)
    {
        enqueueLog("[" + LocalTime.now().format(LOG_TIME) + " INFO]: " + String.format(format, args));
    }

    private static String trim(String s)
    {
        return s == null ? "" : s.trim();
    }

    private static String getRegion(String region)
    {
        region = trim(region);

        if (region.equals("1") || region.equals("9"))
        {
            return "Novigrad/Velen";
        }
        else if (region.equals("2"))
        {
            return "Skellige";
        }
        else if (region.equals("3"))
        {
            return "Kaer Morhen";
        }
        else if (region.equals("4") || region.equals("8"))
        {
            return "White Orchard";
        }
        else if (region.equals("5"))
        {
            return "Vizima";
        }
        else if (region.equals("6"))
        {
            return "Isle of Mists";
        }
        else if (region.equals("7"))
        {
            return "Spiral";
        }
        else if (region.equals("11"))
        {
            return "Toussaint";
        }
        else
        {
            return "Unknown";
        }
    }

    private static String getLocation(List<String> fields)
    {
        if (fields == null || fields.size() < 9)
        {
            return "";
        }

        String name = trim(fields.get(8));

        if (name.equals("NR_PlayerManager.Init") || name.equals("Player") || name.isEmpty())
        {
            return "";
        }

        return name;
    }

    private static List<String> getCoords(List<String> fields)
    {
        if (fields == null || fields.size() < 5)
        {
            return Collections.emptyList();
        }

        List<String> out = new ArrayList<>(3);
        out.add(trim(fields.get(2)));
        out.add(trim(fields.get(3)));
        out.add(trim(fields.get(4)));
        return out;
    }

    private static String normalizeUsernameKey(String username)
    {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean reserveTimedOutPlayer(String usernameKey, PlayerSession session, long now)
    {
        if (!players.remove(usernameKey, session))
        {
            return false;
        }

        playersById.remove(session.playerId, session);
        SpatialIndex.remove(session);
        synchronized (NPC_WORLD_LOCK)
        {
            NpcRegistry.orphanNpcsOwnedBy(session.playerId, System.nanoTime());
            NpcRegistry.forgetBehaviorRecipient(session.playerId);
        }

        String ip = normalizeIp(session.remoteIp);
        TcpTransport.Connection tcp = session.tcpConnection;
        if (tcp != null)
        {
            tcp.close();
        }
        reservedUsernames.put(usernameKey, new UsernameReservation(
                session.username,
                ip,
                now + USERNAME_HOLD_NANOS
        ));

        dbg("Timed out player %s; reserving username for %d seconds to IP %s\n",
                session.username,
                USERNAME_HOLD_NANOS / 1_000_000_000L,
                ip);

        return true;
    }

    private static PlayerSession findPlayerById(int playerId)
    {
        if (playerId <= 0)
        {
            return null;
        }

        return playersById.get(playerId);
    }

    private static int allocateNewPlayerId()
    {
        int candidate;

        do
        {
            candidate = nextPlayerId.getAndIncrement();
        }
        while (candidate <= 0 || findPlayerById(candidate) != null);

        return candidate;
    }

    private static void bumpNextPlayerIdPast(int usedId)
    {
        if (usedId <= 0)
        {
            return;
        }

        while (true)
        {
            int current = nextPlayerId.get();

            if (current > usedId)
            {
                return;
            }

            if (nextPlayerId.compareAndSet(current, usedId + 1))
            {
                return;
            }
        }
    }
}
