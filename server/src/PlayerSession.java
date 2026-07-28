import java.util.Collections;
import java.util.List;

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

    public void storePosition(double x, double y, double z, int area)
    {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.area = area;
        this.hasPosition = true;
    }
}
