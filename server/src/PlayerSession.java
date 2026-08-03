import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerSession
{
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
    public volatile long lastSeen;

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
    public final java.util.Queue<Object[]> pendingOutbound = new java.util.concurrent.ConcurrentLinkedQueue<>();
    public final java.util.Queue<Object[]> pendingSaveOutbound = new java.util.concurrent.ConcurrentLinkedQueue<>();
    public volatile String ownedCellsSignature = "";
    public volatile long ownedCellsSentNanos = 0L;

    public static final class NpcView
    {
        public long lastSentNanos = 0L;
        public double x = 0.0;
        public double y = 0.0;
        public double z = 0.0;
        public double heading = 0.0;
        public int hpPermille = Integer.MIN_VALUE;
        public int flags = Integer.MIN_VALUE;
        public int targetPlayerId = Integer.MIN_VALUE;
        public boolean valid = false;
    }

    public final Map<Integer, NpcView> npcViews = new ConcurrentHashMap<>();

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
        this.lastSeen = lastSeen;
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
    private static final long HIT_WINDOW_MS = 1000L;
    private static final int MAX_HITS_PER_WINDOW = 24;

    private final java.util.ArrayDeque<Sample> history = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long> hitTimes = new java.util.ArrayDeque<>();

    public volatile long lastTimeSyncMs = 0L;
    public volatile int lastHandoverLogged = -1;

    public void storePosition(double x, double y, double z, int area)
    {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.area = area;
        this.hasPosition = true;

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

    public synchronized boolean allowHit(long nowMs)
    {
        while (!hitTimes.isEmpty() && (nowMs - hitTimes.peekFirst()) > HIT_WINDOW_MS)
        {
            hitTimes.removeFirst();
        }

        if (hitTimes.size() >= MAX_HITS_PER_WINDOW)
        {
            return false;
        }

        hitTimes.addLast(nowMs);
        return true;
    }
}
