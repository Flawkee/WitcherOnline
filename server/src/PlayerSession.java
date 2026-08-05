import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerSession
{
    public static final class QueuedPacket
    {
        public final String opcode;
        public final List<String> fields;
        public final long queuedAtNanos;

        QueuedPacket(String opcode, List<String> fields, long queuedAtNanos)
        {
            this.opcode = opcode;
            this.fields = fields;
            this.queuedAtNanos = queuedAtNanos;
        }
    }

    public static final class ChunkSlot
    {
        public volatile List<String> fields = Collections.emptyList();
        public volatile long revision = 0L;
        public volatile long sentRevision = -1L;
        public volatile long sentNanos = 0L;

        public void store(List<String> value)
        {
            fields = value;
            revision++;
        }
    }

    public final int playerId;
    public final String username;
    public volatile ClientEndpoint endpoint;
    public volatile String remoteIp;
    public volatile TcpTransport.Connection tcpConnection;
    public volatile boolean udpAvailable = false;
    public volatile boolean tcpAvailable = false;
    public volatile boolean udpWorked = false;
    public volatile boolean tcpWorked = false;
    public volatile long lastUdpSeen = 0L;
    public volatile long lastTcpSeen = 0L;
    public volatile long lastSeen;
    public volatile long transportIncompleteSince;
    public volatile SpatialIndex.CellKey spatialCell;
    public final AtomicLong udpPacketsSent = new AtomicLong();
    public final AtomicLong tcpPacketsSent = new AtomicLong();
    public final AtomicLong udpPacketsReceived = new AtomicLong();
    public final AtomicLong tcpPacketsReceived = new AtomicLong();
    public final AtomicLong udpBytesSent = new AtomicLong();
    public final AtomicLong tcpBytesSent = new AtomicLong();
    public final AtomicLong udpBytesReceived = new AtomicLong();
    public final AtomicLong tcpBytesReceived = new AtomicLong();

    public final ChunkSlot update1A = new ChunkSlot();
    public final ChunkSlot update1B = new ChunkSlot();
    public final ChunkSlot update2A = new ChunkSlot();
    public final ChunkSlot update2B = new ChunkSlot();
    public final ChunkSlot update3 = new ChunkSlot();
    public final ChunkSlot update4 = new ChunkSlot();

    public volatile Set<Integer> claimedCells = Collections.emptySet();
    public volatile long lastClaimNanos = 0L;
    public final Set<Integer> knownNpcs = ConcurrentHashMap.newKeySet();
    public final Set<Integer> goneGuids = ConcurrentHashMap.newKeySet();
    public volatile boolean paused = false;
    public volatile boolean pausedBroadcast = false;
    public final java.util.Queue<String[]> pendingHits = new java.util.concurrent.ConcurrentLinkedQueue<>();
    public final java.util.concurrent.ArrayBlockingQueue<QueuedPacket> pendingOutbound =
            new java.util.concurrent.ArrayBlockingQueue<>(256);
    public final java.util.concurrent.ArrayBlockingQueue<QueuedPacket> pendingSaveOutbound =
            new java.util.concurrent.ArrayBlockingQueue<>(192);
    public final java.util.concurrent.ArrayBlockingQueue<String> pendingTcpReplay =
            new java.util.concurrent.ArrayBlockingQueue<>(4096);
    public final AtomicLong outboundEnqueued = new AtomicLong();
    public final AtomicLong outboundDrained = new AtomicLong();
    public final AtomicLong outboundDropped = new AtomicLong();
    public final AtomicLong outboundQueueNanos = new AtomicLong();
    public final AtomicLong outboundQueueSamples = new AtomicLong();
    public final AtomicLong saveEnqueued = new AtomicLong();
    public final AtomicLong saveDrained = new AtomicLong();
    public final AtomicLong saveDropped = new AtomicLong();
    public final AtomicLong saveQueueNanos = new AtomicLong();
    public final AtomicLong saveQueueSamples = new AtomicLong();
    public final AtomicInteger outboundHighWater = new AtomicInteger();
    public final AtomicInteger saveHighWater = new AtomicInteger();
    public volatile String ownedCellsSignature = "";
    public volatile long ownedCellsSentNanos = 0L;

    public static final class NpcView
    {
        public long lastSentNanos = 0L;
        public long lastFastSentNanos = 0L;
        public int fastSequence = 0;
        public double x = 0.0;
        public double y = 0.0;
        public double z = 0.0;
        public double heading = 0.0;
        public int hpPermille = Integer.MIN_VALUE;
        public int flags = Integer.MIN_VALUE;
        public int targetPlayerId = Integer.MIN_VALUE;
        public int terminalState = Integer.MIN_VALUE;
        public int terminalRevision = Integer.MIN_VALUE;
        public int terminalAttackerId = Integer.MIN_VALUE;
        public int authorityRevision = Integer.MIN_VALUE;
        public boolean valid = false;
    }

    public final Map<Integer, NpcView> npcViews = new ConcurrentHashMap<>();
    public final Set<Integer> npcDesiredScratch = new HashSet<>();

    public final Set<Integer> visiblePlayers = ConcurrentHashMap.newKeySet();
    public volatile long visibilitySentNanos = 0L;

    public final Map<Integer, Integer> sentScales = new ConcurrentHashMap<>();
    public volatile long scalesSentNanos = 0L;
    public volatile int scaleSetId = 0;
    public volatile long lastScaleEpoch = -1L;
    public volatile int lastScaleKnownCount = -1;

    public volatile int partyId = 0;
    public volatile boolean coopMode = false;
    public volatile int npcSyncMode = 0;
    public volatile int partyScaleStepMilli = 500;
    public volatile int partyScaleMaxMilli = 4000;
    public volatile int rttMs = UNKNOWN_RTT_MS;
    public volatile long lastReleaseNanos = 0L;

    public static final int UNKNOWN_RTT_MS = 1999;

    public volatile boolean hasPosition = false;
    public volatile double posX = 0.0;
    public volatile double posY = 0.0;
    public volatile double posZ = 0.0;
    public volatile int area = -1;

    public PlayerSession(int playerId, String username, ClientEndpoint endpoint, long lastSeen)
    {
        this.playerId = playerId;
        this.username = username;
        this.endpoint = endpoint;
        this.remoteIp = endpoint == null ? "" : endpoint.address.getHostAddress();
        this.udpAvailable = endpoint != null;
        this.udpWorked = endpoint != null;
        this.lastUdpSeen = endpoint == null ? 0L : lastSeen;
        this.lastSeen = lastSeen;
        this.transportIncompleteSince = lastSeen;
    }

    public void markUdp(ClientEndpoint value, long now)
    {
        endpoint = value;
        remoteIp = value.address.getHostAddress();
        udpAvailable = true;
        udpWorked = true;
        lastUdpSeen = now;
        lastSeen = now;
        refreshTransportState(now);
    }

    public void markTcp(TcpTransport.Connection value, long now)
    {
        TcpTransport.Connection previous = tcpConnection;
        tcpConnection = value;
        remoteIp = value.remoteIp();
        tcpAvailable = true;
        tcpWorked = true;
        lastTcpSeen = now;
        lastSeen = now;
        value.bind(this);
        refreshTransportState(now);

        if (previous != null && previous != value)
        {
            previous.close();
        }
    }

    public void clearTcp(TcpTransport.Connection value)
    {
        if (tcpConnection == value)
        {
            tcpConnection = null;
            tcpAvailable = false;
            refreshTransportState(System.nanoTime());
        }
    }

    public boolean transportReady()
    {
        TcpTransport.Connection tcp = tcpConnection;
        return udpAvailable && endpoint != null && tcpAvailable && tcp != null && tcp.isOpen();
    }

    public void expireTransportPaths(long now)
    {
        if (udpAvailable && (now - lastUdpSeen) > 7_000_000_000L)
        {
            udpAvailable = false;
        }
        TcpTransport.Connection tcp = tcpConnection;
        if (tcpAvailable && (tcp == null || !tcp.isOpen()))
        {
            tcpAvailable = false;
        }
        refreshTransportState(now);
    }

    private void refreshTransportState(long now)
    {
        if (transportReady())
        {
            transportIncompleteSince = 0L;
        }
        else if (transportIncompleteSince == 0L)
        {
            transportIncompleteSince = now;
        }
    }

    public static final class Sample
    {
        public final long timeMs;
        public final double x;
        public final double y;
        public final double z;

        Sample(long timeMs, double x, double y, double z)
        {
            this.timeMs = timeMs;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final int HISTORY_SAMPLES = 40;
    private static final long HISTORY_WINDOW_MS = 2000L;
    private final java.util.ArrayDeque<Sample> history = new java.util.ArrayDeque<>();

    public volatile long lastTimeSyncMs = 0L;
    public volatile int lastHandoverLogged = -1;

    public void storePosition(double x, double y, double z, int area)
    {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.area = area;
        this.hasPosition = true;

        SpatialIndex.update(this, area, x, y);

        recordPosition(WitcherServer.serverMs(), x, y, z);
    }

    public synchronized void recordPosition(long timeMs, double x, double y, double z)
    {
        history.addLast(new Sample(timeMs, x, y, z));

        while (history.size() > HISTORY_SAMPLES)
        {
            history.removeFirst();
        }

        while (!history.isEmpty() && (timeMs - history.peekFirst().timeMs) > HISTORY_WINDOW_MS)
        {
            history.removeFirst();
        }
    }

    public synchronized Sample rewind(long atMs)
    {
        if (history.isEmpty())
        {
            return new Sample(atMs, posX, posY, posZ);
        }

        Sample previous = null;

        for (Sample sample : history)
        {
            if (sample.timeMs >= atMs)
            {
                if (previous == null)
                {
                    return sample;
                }

                long span = sample.timeMs - previous.timeMs;

                if (span <= 0)
                {
                    return sample;
                }

                double alpha = (double) (atMs - previous.timeMs) / (double) span;

                return new Sample(atMs,
                        previous.x + (sample.x - previous.x) * alpha,
                        previous.y + (sample.y - previous.y) * alpha,
                        previous.z + (sample.z - previous.z) * alpha);
            }

            previous = sample;
        }

        return history.peekLast();
    }

}
