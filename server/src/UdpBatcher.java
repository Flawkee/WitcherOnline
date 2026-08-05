import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class UdpBatcher implements AutoCloseable
{
    public interface Listener
    {
        void sent(PlayerSession session, int bytes, int logicalPackets);
        void failed(PlayerSession session, int logicalPackets);
    }

    private static final int MAX_DATAGRAM_BYTES = 1200;
    private static final int MAX_PENDING_PER_SESSION = 512;
    private static final long FLUSH_MILLIS = 8L;
    private static final byte[] HEADER_BYTES = new byte[] { (byte) 0xD7, 0x32 };

    private static final class Queued
    {
        final byte[] bytes;

        Queued(byte[] value)
        {
            bytes = value;
        }
    }

    private static final class Bucket
    {
        PlayerSession session;
        final Map<String, Queued> replaceable = new LinkedHashMap<>();
        final ArrayDeque<Queued> ordered = new ArrayDeque<>();
        final ArrayList<Queued> drainScratch = new ArrayList<>();
        final byte[] wireBuffer = new byte[MAX_DATAGRAM_BYTES];
    }

    private final DatagramSocket socket;
    private final Listener listener;
    private final Map<Integer, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private final AtomicLong logicalQueued = new AtomicLong();
    private final AtomicLong logicalSuperseded = new AtomicLong();
    private final AtomicLong physicalSent = new AtomicLong();
    private final AtomicLong batchesSent = new AtomicLong();

    public UdpBatcher(DatagramSocket socket, Listener listener)
    {
        this.socket = socket;
        this.listener = listener;
        worker = new Thread(this::loop, "udp-batcher");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean enqueue(PlayerSession session, String replaceKey, byte[] packetBytes)
    {
        if (!running.get() || session == null || session.endpoint == null
                || packetBytes == null || packetBytes.length == 0)
        {
            return false;
        }

        Bucket bucket = buckets.computeIfAbsent(session.playerId, ignored -> new Bucket());
        synchronized (bucket)
        {
            bucket.session = session;
            if (replaceKey != null)
            {
                Queued previous = bucket.replaceable.put(replaceKey, new Queued(packetBytes));
                if (previous != null)
                {
                    logicalSuperseded.incrementAndGet();
                }
            }
            else
            {
                if (bucket.ordered.size() + bucket.replaceable.size() >= MAX_PENDING_PER_SESSION)
                {
                    return false;
                }
                bucket.ordered.addLast(new Queued(packetBytes));
            }
        }
        logicalQueued.incrementAndGet();
        return true;
    }

    public String report()
    {
        return "udpBatch logical=" + logicalQueued.get()
                + " superseded=" + logicalSuperseded.get()
                + " physical=" + physicalSent.get()
                + " batches=" + batchesSent.get()
                + " pendingSessions=" + buckets.size();
    }

    private void loop()
    {
        while (running.get())
        {
            try
            {
                Thread.sleep(FLUSH_MILLIS);
            }
            catch (InterruptedException e)
            {
                if (!running.get())
                {
                    break;
                }
            }
            flush();
        }
        flush();
    }

    private void flush()
    {
        for (Map.Entry<Integer, Bucket> entry : buckets.entrySet())
        {
            Bucket bucket = entry.getValue();
            List<Queued> pending = bucket.drainScratch;
            PlayerSession session;
            synchronized (bucket)
            {
                pending.clear();
                session = bucket.session;
                while (!bucket.ordered.isEmpty())
                {
                    pending.add(bucket.ordered.removeFirst());
                }
                pending.addAll(bucket.replaceable.values());
                bucket.replaceable.clear();
            }

            if (pending.isEmpty())
            {
                if (session == null || !session.transportReady())
                {
                    buckets.remove(entry.getKey(), bucket);
                }
                continue;
            }

            sendPending(session, pending, bucket.wireBuffer);
            pending.clear();
        }
    }

    private void sendPending(PlayerSession session, List<Queued> pending, byte[] batch)
    {
        if (pending.size() == 1)
        {
            Queued queued = pending.get(0);
            sendDatagram(session, queued.bytes, queued.bytes.length, 1, false);
            return;
        }

        int batchLength = 0;
        int batchCount = 0;

        for (Queued queued : pending)
        {
            if (queued.bytes.length + HEADER_BYTES.length + 2 > MAX_DATAGRAM_BYTES)
            {
                flushBatch(session, batch, batchLength, batchCount);
                batchLength = 0;
                batchCount = 0;
                sendDatagram(session, queued.bytes, queued.bytes.length, 1, false);
                continue;
            }

            int extra = queued.bytes.length + 2 + (batchCount == 0 ? HEADER_BYTES.length : 0);
            if (batchCount > 0 && batchLength + extra > MAX_DATAGRAM_BYTES)
            {
                flushBatch(session, batch, batchLength, batchCount);
                batchLength = 0;
                batchCount = 0;
            }

            if (batchCount == 0)
            {
                System.arraycopy(HEADER_BYTES, 0, batch, 0, HEADER_BYTES.length);
                batchLength = HEADER_BYTES.length;
            }
            batch[batchLength++] = (byte) ((queued.bytes.length >>> 8) & 0xff);
            batch[batchLength++] = (byte) (queued.bytes.length & 0xff);
            System.arraycopy(queued.bytes, 0, batch, batchLength, queued.bytes.length);
            batchLength += queued.bytes.length;
            batchCount++;
        }

        flushBatch(session, batch, batchLength, batchCount);
    }

    private void flushBatch(PlayerSession session, byte[] batch, int length, int count)
    {
        if (count <= 0)
        {
            return;
        }
        sendDatagram(session, batch, length, count, true);
    }

    private void sendDatagram(
            PlayerSession session,
            byte[] data,
            int length,
            int logicalCount,
            boolean batch)
    {
        ClientEndpoint endpoint = session == null ? null : session.endpoint;
        if (endpoint == null || socket.isClosed())
        {
            listener.failed(session, logicalCount);
            return;
        }

        try
        {
            socket.send(new DatagramPacket(data, 0, length, endpoint.address, endpoint.port));
            physicalSent.incrementAndGet();
            if (batch)
            {
                batchesSent.incrementAndGet();
            }
            listener.sent(session, length, logicalCount);
        }
        catch (Exception e)
        {
            listener.failed(session, logicalCount);
        }
    }

    @Override
    public void close()
    {
        if (running.compareAndSet(true, false))
        {
            worker.interrupt();
            try
            {
                worker.join(1000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }
}
