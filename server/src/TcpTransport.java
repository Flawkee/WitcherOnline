import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TcpTransport implements AutoCloseable
{
    public interface Handler
    {
        void onMessage(Connection connection, String message);
        void onClosed(Connection connection);
    }

    private static final int MAX_FRAME_BYTES = 1 << 20;
    private static final int INITIAL_READ_BYTES = 1 << 16;
    private static final int RELIABLE_QUEUE_LIMIT = 1024;
    private static final int BULK_QUEUE_LIMIT = 256;
    private static final int REALTIME_KEY_LIMIT = 1024;
    private static final int INBOUND_QUEUE_LIMIT = 2048;
    private static final int WRITE_FRAMES_PER_SELECT = 128;

    private static final class OutboundFrame
    {
        final String message;
        final byte[] payload;
        final ByteBuffer wire;

        OutboundFrame(String message)
        {
            this.message = message;
            payload = BinaryPacketCodec.encodeText(message);
            wire = ByteBuffer.allocate(payload.length + 4);
            wire.putInt(payload.length);
            wire.put(payload);
            wire.flip();
        }
    }

    public final class Connection implements AutoCloseable
    {
        private final SocketChannel channel;
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean closeNotified = new AtomicBoolean(false);
        private final ArrayBlockingQueue<OutboundFrame> reliable = new ArrayBlockingQueue<>(RELIABLE_QUEUE_LIMIT);
        private final ArrayBlockingQueue<OutboundFrame> bulk = new ArrayBlockingQueue<>(BULK_QUEUE_LIMIT);
        private final ConcurrentHashMap<String, OutboundFrame> realtime = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<String> realtimeOrder = new ConcurrentLinkedQueue<>();
        private final ArrayBlockingQueue<String> inbound = new ArrayBlockingQueue<>(INBOUND_QUEUE_LIMIT);
        private final AtomicBoolean inboundScheduled = new AtomicBoolean(false);
        private ByteBuffer readBuffer = ByteBuffer.allocate(INITIAL_READ_BYTES);
        private final String remoteIp;
        private final int remotePort;

        private volatile SelectionKey key;
        private volatile PlayerSession session;
        private volatile String helloUsername = "";
        private volatile long sentFrames;
        private volatile long receivedFrames;
        private volatile int expectedFrame = -1;
        private volatile OutboundFrame writing;
        private volatile boolean closeAfterWrite;

        Connection(SocketChannel channel) throws Exception
        {
            this.channel = channel;
            InetSocketAddress remote = (InetSocketAddress) channel.getRemoteAddress();
            InetAddress address = remote == null ? null : remote.getAddress();
            remoteIp = address == null ? "" : address.getHostAddress();
            remotePort = remote == null ? 0 : remote.getPort();
        }

        public String remoteIp()
        {
            return remoteIp;
        }

        public String remoteAddress()
        {
            return remoteIp + ":" + remotePort;
        }

        public boolean isOpen()
        {
            return open.get() && channel.isOpen();
        }

        public String helloUsername()
        {
            return helloUsername;
        }

        public void setHelloUsername(String value)
        {
            helloUsername = value == null ? "" : value;
        }

        public PlayerSession session()
        {
            return session;
        }

        public void bind(PlayerSession value)
        {
            session = value;
        }

        public long sentFrames()
        {
            return sentFrames;
        }

        public long receivedFrames()
        {
            return receivedFrames;
        }

        public List<String> drainPending()
        {
            List<String> messages = new ArrayList<>();
            OutboundFrame active = writing;
            if (active != null)
            {
                messages.add(active.message);
                writing = null;
            }

            OutboundFrame frame;
            while ((frame = reliable.poll()) != null)
            {
                messages.add(frame.message);
            }
            while ((frame = bulk.poll()) != null)
            {
                messages.add(frame.message);
            }
            for (OutboundFrame value : realtime.values())
            {
                messages.add(value.message);
            }
            realtime.clear();
            realtimeOrder.clear();
            return messages;
        }

        public boolean sendReliable(String message)
        {
            OutboundFrame frame = createFrame(message);
            if (frame == null || !reliable.offer(frame))
            {
                close();
                return false;
            }
            requestWrite();
            return true;
        }

        public void sendAndClose(String message)
        {
            if (sendReliable(message))
            {
                closeWhenDrained();
            }
        }

        public void closeWhenDrained()
        {
            closeAfterWrite = true;
            requestWrite();
        }

        public boolean sendRealtime(String keyValue, String message)
        {
            OutboundFrame frame = createFrame(message);
            if (frame == null)
            {
                return false;
            }

            String safeKey = keyValue == null || keyValue.isEmpty() ? "realtime" : keyValue;
            if (!realtime.containsKey(safeKey) && realtime.size() >= REALTIME_KEY_LIMIT)
            {
                return false;
            }

            OutboundFrame previous = realtime.put(safeKey, frame);
            if (previous == null)
            {
                realtimeOrder.offer(safeKey);
            }
            requestWrite();
            return true;
        }

        public boolean sendBulk(String message)
        {
            OutboundFrame frame = createFrame(message);
            if (frame == null || !bulk.offer(frame))
            {
                close();
                return false;
            }
            requestWrite();
            return true;
        }

        private OutboundFrame createFrame(String message)
        {
            if (!isOpen() || message == null)
            {
                return null;
            }
            try
            {
                OutboundFrame frame = new OutboundFrame(message);
                return frame.payload.length <= 0 || frame.payload.length > MAX_FRAME_BYTES ? null : frame;
            }
            catch (IllegalArgumentException invalid)
            {
                return null;
            }
        }

        private void requestWrite()
        {
            SelectionKey current = key;
            if (current == null || !current.isValid())
            {
                return;
            }
            selector.wakeup();
            current.interestOpsOr(SelectionKey.OP_WRITE);
        }

        private void read() throws Exception
        {
            int count = channel.read(readBuffer);
            if (count < 0)
            {
                close();
                return;
            }
            if (count == 0)
            {
                return;
            }

            readBuffer.flip();
            while (true)
            {
                if (expectedFrame < 0)
                {
                    if (readBuffer.remaining() < 4)
                    {
                        break;
                    }
                    expectedFrame = readBuffer.getInt();
                    if (expectedFrame <= 0 || expectedFrame > MAX_FRAME_BYTES)
                    {
                        close();
                        return;
                    }
                }

                if (readBuffer.remaining() < expectedFrame)
                {
                    break;
                }

                byte[] data = new byte[expectedFrame];
                readBuffer.get(data);
                expectedFrame = -1;
                receivedFrames++;

                String message;
                try
                {
                    message = BinaryPacketCodec.decodePacket(data);
                }
                catch (IllegalArgumentException invalid)
                {
                    close();
                    return;
                }

                if (!inbound.offer(message))
                {
                    close();
                    return;
                }
                scheduleInbound();
            }
            readBuffer.compact();
            if (expectedFrame > 0 && readBuffer.capacity() < expectedFrame)
            {
                int capacity = readBuffer.capacity();
                while (capacity < expectedFrame)
                {
                    capacity = Math.min(MAX_FRAME_BYTES, capacity << 1);
                }
                ByteBuffer expanded = ByteBuffer.allocate(capacity);
                readBuffer.flip();
                expanded.put(readBuffer);
                readBuffer = expanded;
            }
        }

        private void scheduleInbound()
        {
            if (inboundScheduled.compareAndSet(false, true))
            {
                workers.execute(this::drainInbound);
            }
        }

        private void drainInbound()
        {
            try
            {
                String message;
                while (isOpen() && (message = inbound.poll()) != null)
                {
                    handler.onMessage(this, message);
                }
            }
            finally
            {
                inboundScheduled.set(false);
                if (!inbound.isEmpty())
                {
                    scheduleInbound();
                }
            }
        }

        private void write() throws Exception
        {
            int budget = WRITE_FRAMES_PER_SELECT;
            while (budget-- > 0)
            {
                OutboundFrame frame = writing;
                if (frame == null)
                {
                    frame = nextOutbound();
                    writing = frame;
                }
                if (frame == null)
                {
                    if (closeAfterWrite)
                    {
                        close();
                        return;
                    }
                    SelectionKey current = key;
                    if (current != null && current.isValid())
                    {
                        current.interestOpsAnd(~SelectionKey.OP_WRITE);
                    }
                    return;
                }

                channel.write(frame.wire);
                if (frame.wire.hasRemaining())
                {
                    return;
                }

                writing = null;
                sentFrames++;
            }
        }

        private OutboundFrame nextOutbound()
        {
            OutboundFrame frame = reliable.poll();
            if (frame != null)
            {
                return frame;
            }

            String realtimeKey;
            while ((realtimeKey = realtimeOrder.poll()) != null)
            {
                frame = realtime.remove(realtimeKey);
                if (frame != null)
                {
                    return frame;
                }
            }

            return bulk.poll();
        }

        @Override
        public void close()
        {
            if (!open.compareAndSet(true, false))
            {
                return;
            }
            SelectionKey current = key;
            if (current != null)
            {
                current.cancel();
            }
            try
            {
                channel.close();
            }
            catch (Exception ignored)
            {
            }
            connections.remove(this);
            selector.wakeup();
            if (closeNotified.compareAndSet(false, true))
            {
                handler.onClosed(this);
            }
        }
    }

    private final Selector selector;
    private final ServerSocketChannel server;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers;
    private final Thread eventLoop;

    public TcpTransport(int port, Handler handler) throws Exception
    {
        this.handler = handler;
        int workerCount = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 4));
        workers = Executors.newFixedThreadPool(workerCount, runnable ->
        {
            Thread thread = new Thread(runnable, "tcp-worker");
            thread.setDaemon(true);
            return thread;
        });
        selector = Selector.open();
        server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        server.bind(new InetSocketAddress(port));
        server.register(selector, SelectionKey.OP_ACCEPT);
        eventLoop = new Thread(this::runLoop, "tcp-io");
        eventLoop.setDaemon(true);
        eventLoop.start();
    }

    private void runLoop()
    {
        while (running.get())
        {
            try
            {
                selector.select(1000);
                java.util.Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext())
                {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (!key.isValid())
                    {
                        continue;
                    }
                    if (key.isAcceptable())
                    {
                        accept();
                        continue;
                    }

                    Connection connection = (Connection) key.attachment();
                    try
                    {
                        if (key.isReadable())
                        {
                            connection.read();
                        }
                        if (key.isValid() && key.isWritable())
                        {
                            connection.write();
                        }
                    }
                    catch (Exception e)
                    {
                        connection.close();
                    }
                }
            }
            catch (Exception e)
            {
                if (running.get())
                {
                    e.printStackTrace();
                }
            }
        }
    }

    private void accept() throws Exception
    {
        SocketChannel channel;
        while ((channel = server.accept()) != null)
        {
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            channel.setOption(StandardSocketOptions.SO_RCVBUF, 1 << 20);
            channel.setOption(StandardSocketOptions.SO_SNDBUF, 1 << 20);
            Connection connection = new Connection(channel);
            connection.key = channel.register(selector, SelectionKey.OP_READ, connection);
            connections.add(connection);
        }
    }

    public int connectionCount()
    {
        return connections.size();
    }

    @Override
    public void close()
    {
        if (!running.compareAndSet(true, false))
        {
            return;
        }
        selector.wakeup();
        try
        {
            server.close();
        }
        catch (Exception ignored)
        {
        }
        for (Connection connection : new ArrayList<>(connections))
        {
            connection.close();
        }
        workers.shutdown();
        try
        {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        try
        {
            selector.close();
        }
        catch (Exception ignored)
        {
        }
    }
}
