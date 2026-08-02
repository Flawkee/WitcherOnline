import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcRegistry
{
    public static final int CELL_SIZE_TAG = 128;
    public static final int CELL_AXIS_SPAN = 2048;

    public static final long NPC_STALE_NANOS = 60_000_000_000L;
    public static final long NPC_ORPHAN_RETENTION_NANOS = 180_000_000_000L;
    public static final long RELEASED_ORPHAN_RETENTION_NANOS = 20_000_000_000L;
    public static final long LATENCY_MIGRATION_INTERVAL_NANOS = 10_000_000_000L;
    public static final int LATENCY_MIGRATION_MARGIN_MS = 60;
    public static final long HANDOVER_RELEASE_GRACE_NANOS = 5_000_000_000L;
    public static final int DEATH_BROADCAST_REPEATS = 3;
    public static final long DEATH_BROADCAST_INTERVAL_NANOS = 200_000_000L;
    public static final long DEATH_BROADCAST_WINDOW_NANOS = 3_000_000_000L;
    public static final long HANDOVER_DECLINE_NANOS = 3_000_000_000L;
    public static final long NPC_STREAM_FRESH_NANOS = 12_000_000_000L;
    public static final long HANDOVER_SILENCE_NANOS = 1_500_000_000L;
    public static final long HANDOVER_RETRY_NANOS = 1_000_000_000L;
    public static final int HANDOVER_MAX_ATTEMPTS = 3;
    public static final long HANDOVER_COOLDOWN_NANOS = 15_000_000_000L;
    public static final long UNACKED_DAMAGE_GRACE_NANOS = 1_200_000_000L;
    public static final long DEAD_RETENTION_NANOS = 180_000_000_000L;

    public static final int MAX_NPCS_PER_OWNER = 250;
    public static final double RESERVATION_RADIUS = 30.0;

    public static final int HISTORY_SAMPLES = 40;
    public static final long HISTORY_WINDOW_MS = 2000L;
    public static final double HIT_RANGE = 60.0;
    public static final double HIT_RANGE_SQUARED = HIT_RANGE * HIT_RANGE;

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

    public static final class Npc
    {
        public final int npcId;
        public volatile int ownerPlayerId;
        public volatile int ownerLocalGuid;
        public volatile int area;
        public volatile String typeCode = "-";
        public volatile String appearance = "-";
        public volatile double x;
        public volatile double y;
        public volatile double z;
        public volatile double heading;
        public volatile int hpPermille = -1;
        public volatile int flags;
        public volatile int targetPlayerId;
        public volatile boolean alive = true;
        public volatile boolean deathBroadcast;
        public final java.util.Map<Integer, Integer> deathSends = new java.util.concurrent.ConcurrentHashMap<>();
        public volatile long lastDeathSendNanos;
        public volatile long lastUpdateNanos;
        public volatile long deadSinceNanos;
        public volatile int handoverTarget;
        public volatile long handoverSentNanos;
        public volatile int handoverAttempts;
        public volatile long handoverBlockedUntil;
        public volatile int releasedByPlayerId;
        public volatile int releasedLocalGuid;
        public volatile long releasedAtNanos;
        public volatile long lastMigrationNanos;
        public final java.util.Map<Integer, Long> declinedUntil = new java.util.concurrent.ConcurrentHashMap<>();
        public volatile boolean killOrderPending;
        public volatile int pendingDamagePermille;
        public volatile long pendingDamageSince;
        public volatile int scaleMilli = NpcScaling.SCALE_UNIT;
        public volatile int scalePlayerCount = 1;
        public volatile int questPartyId;

        final ArrayDeque<Sample> history = new ArrayDeque<>();

        Npc(int npcId)
        {
            this.npcId = npcId;
        }

        synchronized void record(long timeMs, double px, double py, double pz)
        {
            history.addLast(new Sample(timeMs, px, py, pz));

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
                return new Sample(atMs, x, y, z);
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

    private static final Map<Integer, Npc> npcs = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> ownerGuidToCanonical = new ConcurrentHashMap<>();
    private static final java.util.Queue<int[]> pendingDrops = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger nextCanonicalId =
            new java.util.concurrent.atomic.AtomicInteger(1);

    private static final java.util.concurrent.atomic.AtomicLong statAdmitted =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong statRejectedDuplicate =
            new java.util.concurrent.atomic.AtomicLong();

    private NpcRegistry()
    {
    }

    private static long ownerKey(int ownerPlayerId, int ownerLocalGuid)
    {
        return (((long) ownerPlayerId) << 32) | (ownerLocalGuid & 0xFFFFFFFFL);
    }

    public static boolean isQuestFoe(Npc npc)
    {
        return npc.typeCode != null && npc.typeCode.startsWith("quest:");
    }

    private static Npc findLiveQuestSlot(String typeCode, int partyId)
    {
        for (Npc npc : npcs.values())
        {
            if (npc.alive && npc.questPartyId == partyId && typeCode.equals(npc.typeCode))
            {
                return npc;
            }
        }

        return null;
    }

    public static String describeNpc(Npc npc)
    {
        String label = (npc.appearance == null || npc.appearance.equals("-")) ? npc.typeCode : npc.appearance;

        if (label == null || label.isEmpty() || label.equals("-"))
        {
            label = "npc";
        }

        return "#" + npc.npcId + " " + label;
    }

    public static String describeSpot(Npc npc)
    {
        int cx = (int) Math.floor(npc.x / CELL_SIZE_TAG);
        int cy = (int) Math.floor(npc.y / CELL_SIZE_TAG);

        return npc.area + ":" + cx + ":" + cy;
    }

    public static int npcCount()
    {
        return npcs.size();
    }

    public static long admittedCount()
    {
        return statAdmitted.get();
    }

    public static long rejectedDuplicateCount()
    {
        return statRejectedDuplicate.get();
    }

    public static int countOwnedBy(int ownerPlayerId)
    {
        int count = 0;

        for (Npc npc : npcs.values())
        {
            if (npc.ownerPlayerId == ownerPlayerId)
            {
                count++;
            }
        }

        return count;
    }

    public static Npc get(int npcId)
    {
        return npcs.get(npcId);
    }

    public static Npc getByOwnerGuid(int ownerPlayerId, int ownerLocalGuid)
    {
        Integer canonicalId = ownerGuidToCanonical.get(ownerKey(ownerPlayerId, ownerLocalGuid));
        return canonicalId == null ? null : npcs.get(canonicalId);
    }

    private static boolean withinReservation(Npc npc, int area, double x, double y, double z)
    {
        if (npc.area != area)
        {
            return false;
        }

        double dx = npc.x - x;
        double dy = npc.y - y;
        double dz = npc.z - z;

        return (dx * dx + dy * dy + dz * dz) <= (RESERVATION_RADIUS * RESERVATION_RADIUS);
    }

    private static boolean admit(
            int ownerPlayerId,
            String typeCode,
            int area,
            double x,
            double y,
            double z,
            int offeredLocalCount)
    {
        int foreign = 0;
        int mine = 0;

        PlayerSession owner = WitcherServer.sessionByPlayerId(ownerPlayerId);

        for (Npc npc : npcs.values())
        {
            if (!npc.alive || !npc.typeCode.equals(typeCode))
            {
                continue;
            }

            if (!withinReservation(npc, area, x, y, z))
            {
                continue;
            }

            if (npc.ownerPlayerId == ownerPlayerId)
            {
                mine++;
            }
            else if (npc.ownerPlayerId != 0)
            {
                if (!WitcherServer.canShareNpcs(owner, WitcherServer.sessionByPlayerId(npc.ownerPlayerId)))
                {
                    continue;
                }

                foreign++;
            }
        }

        if (foreign == 0)
        {
            return true;
        }

        final int claimed = Math.max(offeredLocalCount, 1);

        return (foreign + mine) < claimed;
    }

    private static Npc adoptOrphan(
            int ownerPlayerId,
            int ownerLocalGuid,
            String typeCode,
            String appearance,
            int area,
            double x,
            double y,
            double z,
            long now)
    {
        PlayerSession adopter = WitcherServer.sessionByPlayerId(ownerPlayerId);

        for (Npc npc : npcs.values())
        {
            if (npc.ownerPlayerId != 0 || !npc.alive)
            {
                continue;
            }

            if (!npc.typeCode.equals(typeCode) || !npc.appearance.equals(appearance))
            {
                continue;
            }

            if (npc.releasedByPlayerId != 0
                    && !WitcherServer.canShareNpcs(adopter, WitcherServer.sessionByPlayerId(npc.releasedByPlayerId)))
            {
                continue;
            }

            if (!withinReservation(npc, area, x, y, z))
            {
                continue;
            }

            npc.ownerPlayerId = ownerPlayerId;
            npc.ownerLocalGuid = ownerLocalGuid;
            npc.handoverTarget = 0;
            npc.handoverSentNanos = 0L;
            npc.handoverAttempts = 0;
            npc.handoverBlockedUntil = 0L;
            npc.declinedUntil.clear();
            npc.lastUpdateNanos = now;

            if (npc.releasedByPlayerId != 0 && npc.releasedByPlayerId != ownerPlayerId)
            {
                pendingDrops.add(new int[] { npc.releasedByPlayerId, npc.releasedLocalGuid });
            }

            npc.releasedByPlayerId = 0;
            npc.releasedLocalGuid = 0;

            ownerGuidToCanonical.put(ownerKey(ownerPlayerId, ownerLocalGuid), npc.npcId);

            WitcherServer.dbg("NPC %s READOPTED | owner=%s spot=%s\n",
                    describeNpc(npc),
                    WitcherServer.describePlayerId(ownerPlayerId),
                    describeSpot(npc));

            return npc;
        }

        return null;
    }

    private static void evictOwnStaleDuplicates(
            int ownerPlayerId,
            String typeCode,
            int area,
            double x,
            double y,
            double z,
            long now)
    {
        for (Npc npc : npcs.values())
        {
            if (npc.ownerPlayerId != ownerPlayerId || !npc.alive)
            {
                continue;
            }

            if (!npc.typeCode.equals(typeCode) || !withinReservation(npc, area, x, y, z))
            {
                continue;
            }

            if ((now - npc.lastUpdateNanos) < HANDOVER_SILENCE_NANOS)
            {
                continue;
            }

            npcs.remove(npc.npcId);
            ownerGuidToCanonical.remove(ownerKey(npc.ownerPlayerId, npc.ownerLocalGuid));

            WitcherServer.dbg("NPC %s EVICTED STALE DUPLICATE | owner=%s spot=%s\n",
                    describeNpc(npc),
                    WitcherServer.describePlayerId(ownerPlayerId),
                    describeSpot(npc));
        }
    }

    public static Npc upsert(
            int ownerPlayerId,
            int ownerLocalGuid,
            int area,
            String typeCode,
            String appearance,
            double x,
            double y,
            double z,
            double heading,
            int hpPermille,
            int flags,
            int targetPlayerId,
            int offeredLocalCount,
            long snapshotMs,
            long now)
    {
        if (ownerLocalGuid == 0)
        {
            return null;
        }

        final long key = ownerKey(ownerPlayerId, ownerLocalGuid);
        final boolean questFoe = typeCode != null && typeCode.startsWith("quest:");
        Integer canonicalId = ownerGuidToCanonical.get(key);
        Npc npc = (canonicalId == null) ? null : npcs.get(canonicalId);

        if (npc != null && !questFoe
                && (!npc.appearance.equals(appearance) || !npc.typeCode.equals(typeCode)))
        {
            npcs.remove(npc.npcId);
            ownerGuidToCanonical.remove(key);
            npc = null;
        }

        if (questFoe)
        {
            final int partyId = WitcherServer.questPartyOf(ownerPlayerId);

            if (partyId <= 0)
            {
                return null;
            }

            if (npc == null || !npc.alive)
            {
                Npc slot = findLiveQuestSlot(typeCode, partyId);

                if (slot != null && slot.ownerPlayerId != 0 && slot.ownerPlayerId != ownerPlayerId)
                {
                    return null;
                }

                if (slot != null)
                {
                    if (slot.ownerPlayerId != 0)
                    {
                        ownerGuidToCanonical.remove(ownerKey(slot.ownerPlayerId, slot.ownerLocalGuid));
                    }

                    slot.ownerPlayerId = ownerPlayerId;
                    slot.ownerLocalGuid = ownerLocalGuid;
                    slot.handoverTarget = 0;
                    slot.handoverSentNanos = 0L;
                    slot.handoverAttempts = 0;
                    slot.lastUpdateNanos = now;
                    ownerGuidToCanonical.put(key, slot.npcId);

                    npc = slot;

                    WitcherServer.dbg("QFOE %s slot #%d rebound to %s guid=%d\n",
                            typeCode, slot.npcId, WitcherServer.describePlayerId(ownerPlayerId), ownerLocalGuid);
                }
                else
                {
                    if ((flags & 1) == 0 || hpPermille <= 0)
                    {
                        return null;
                    }

                    npc = new Npc(nextCanonicalId.getAndIncrement());
                    npc.ownerPlayerId = ownerPlayerId;
                    npc.ownerLocalGuid = ownerLocalGuid;
                    npc.typeCode = typeCode;
                    npc.appearance = appearance;
                    npc.questPartyId = partyId;
                    npcs.put(npc.npcId, npc);
                    ownerGuidToCanonical.put(key, npc.npcId);
                    statAdmitted.incrementAndGet();

                    WitcherServer.dbg("QFOE %s slot #%d created by %s guid=%d\n",
                            typeCode, npc.npcId, WitcherServer.describePlayerId(ownerPlayerId), ownerLocalGuid);
                }
            }
        }

        boolean isNew = (npc == null);

        if (isNew)
        {
            Npc adopted = adoptOrphan(ownerPlayerId, ownerLocalGuid, typeCode, appearance, area, x, y, z, now);

            if (adopted != null)
            {
                npc = adopted;
                isNew = false;
            }
        }

        if (isNew && ((flags & 1) == 0 || hpPermille <= 0))
        {
            return null;
        }

        if (isNew)
        {
            evictOwnStaleDuplicates(ownerPlayerId, typeCode, area, x, y, z, now);

            if (countOwnedBy(ownerPlayerId) >= MAX_NPCS_PER_OWNER)
            {
                return null;
            }

            if (!admit(ownerPlayerId, typeCode, area, x, y, z, offeredLocalCount))
            {
                statRejectedDuplicate.incrementAndGet();
                return null;
            }

            npc = new Npc(nextCanonicalId.getAndIncrement());
            npc.ownerPlayerId = ownerPlayerId;
            npc.ownerLocalGuid = ownerLocalGuid;
            npc.typeCode = typeCode;
            npc.appearance = appearance;
            npcs.put(npc.npcId, npc);
            ownerGuidToCanonical.put(key, npc.npcId);
            statAdmitted.incrementAndGet();
        }

        if (npc.ownerPlayerId != ownerPlayerId)
        {
            return null;
        }

        applyState(npc, area, x, y, z, heading, hpPermille, flags, targetPlayerId, snapshotMs, now);

        if (isNew)
        {
            WitcherServer.dbg("NPC %s REGISTERED | owner=%s spot=%s hp=%d permille\n",
                    describeNpc(npc),
                    WitcherServer.describePlayerId(ownerPlayerId),
                    describeSpot(npc),
                    hpPermille);
        }

        return npc;
    }

    public static Npc move(
            int ownerPlayerId,
            int ownerLocalGuid,
            double x,
            double y,
            double z,
            double heading,
            int hpPermille,
            int flags,
            int targetPlayerId,
            long snapshotMs,
            long now)
    {
        Npc npc = getByOwnerGuid(ownerPlayerId, ownerLocalGuid);

        if (npc == null || npc.ownerPlayerId != ownerPlayerId)
        {
            return null;
        }

        applyState(npc, npc.area, x, y, z, heading, hpPermille, flags, targetPlayerId, snapshotMs, now);

        return npc;
    }

    private static void applyState(
            Npc npc,
            int area,
            double x,
            double y,
            double z,
            double heading,
            int hpPermille,
            int flags,
            int targetPlayerId,
            long snapshotMs,
            long now)
    {
        final int previousTarget = npc.targetPlayerId;
        final boolean wasAlive = npc.alive;

        npc.area = area;
        npc.x = x;
        npc.y = y;
        npc.z = z;
        npc.heading = heading;
        npc.hpPermille = hpPermille;
        npc.flags = flags;
        npc.targetPlayerId = targetPlayerId;
        npc.lastUpdateNanos = now;
        npc.record(snapshotMs, x, y, z);

        final boolean ownerSaysAlive = (flags & 1) != 0;

        if (!wasAlive && ownerSaysAlive)
        {
            npc.killOrderPending = true;
            npc.hpPermille = 0;
            npc.flags = flags & ~1;

            WitcherServer.dbg("NPC %s RESURRECT BLOCKED (server says dead) | owner=%s\n",
                    describeNpc(npc),
                    WitcherServer.describePlayerId(npc.ownerPlayerId));

            return;
        }

        npc.alive = ownerSaysAlive;

        if (wasAlive && !npc.alive)
        {
            npc.deadSinceNanos = now;

            WitcherServer.dbg("NPC %s DIED | owner=%s spot=%s\n",
                    describeNpc(npc),
                    WitcherServer.describePlayerId(npc.ownerPlayerId),
                    describeSpot(npc));
        }

        if (previousTarget != targetPlayerId)
        {
            WitcherServer.dbg("NPC %s TARGET %s -> %s | owner=%s\n",
                    describeNpc(npc),
                    WitcherServer.describeTargetId(previousTarget),
                    WitcherServer.describeTargetId(targetPlayerId),
                    WitcherServer.describePlayerId(npc.ownerPlayerId));
        }
    }

    public static boolean remove(int ownerPlayerId, int ownerLocalGuid)
    {
        final long key = ownerKey(ownerPlayerId, ownerLocalGuid);
        Integer canonicalId = ownerGuidToCanonical.get(key);
        Npc npc = (canonicalId == null) ? null : npcs.get(canonicalId);

        if (npc == null || npc.ownerPlayerId != ownerPlayerId)
        {
            return false;
        }

        ownerGuidToCanonical.remove(key);
        npcs.remove(npc.npcId);

        WitcherServer.dbg("NPC %s DESPAWNED | owner=%s spot=%s\n",
                describeNpc(npc),
                WitcherServer.describePlayerId(ownerPlayerId),
                describeSpot(npc));

        return true;
    }

    public static void orphanNpcsOwnedBy(int playerId, long now)
    {
        for (Npc npc : npcs.values())
        {
            if (npc.ownerPlayerId != playerId)
            {
                continue;
            }

            ownerGuidToCanonical.remove(ownerKey(playerId, npc.ownerLocalGuid));

            npc.ownerPlayerId = 0;
            npc.ownerLocalGuid = 0;
            npc.handoverTarget = 0;
        }
    }

    public static void pruneStale(java.util.Set<Integer> ownerOnline, long now)
    {
        for (Npc npc : npcs.values())
        {
            if (!npc.alive && npc.deathBroadcast && (now - npc.deadSinceNanos) > DEAD_RETENTION_NANOS)
            {
                npcs.remove(npc.npcId);
                ownerGuidToCanonical.remove(ownerKey(npc.ownerPlayerId, npc.ownerLocalGuid));
                continue;
            }

            if (npc.ownerPlayerId == 0
                    && npc.releasedByPlayerId != 0
                    && (now - npc.releasedAtNanos) > RELEASED_ORPHAN_RETENTION_NANOS)
            {
                npcs.remove(npc.npcId);

                WitcherServer.dbg("NPC %s EVICTED (released %.0fs ago, unclaimed)\n",
                        describeNpc(npc),
                        (now - npc.releasedAtNanos) / 1_000_000_000.0);
                continue;
            }

            if (npc.ownerPlayerId != 0 && ownerOnline.contains(npc.ownerPlayerId))
            {
                continue;
            }

            if ((now - npc.lastUpdateNanos) > NPC_ORPHAN_RETENTION_NANOS)
            {
                npcs.remove(npc.npcId);
                ownerGuidToCanonical.remove(ownerKey(npc.ownerPlayerId, npc.ownerLocalGuid));

                WitcherServer.dbg("NPC %s EVICTED (orphaned %.0fs, owner offline)\n",
                        describeNpc(npc),
                        (now - npc.lastUpdateNanos) / 1_000_000_000.0);
            }
        }
    }

    public static void notePendingDamage(Npc npc, int permille, long now)
    {
        if (npc.pendingDamagePermille == 0)
        {
            npc.pendingDamageSince = now;
        }

        npc.pendingDamagePermille = Math.min(1000, npc.pendingDamagePermille + permille);
    }

    public static void clearPendingDamage(Npc npc)
    {
        npc.pendingDamagePermille = 0;
        npc.pendingDamageSince = 0L;
    }

    public static void applyUnackedDamage(long now)
    {
        for (Npc npc : npcs.values())
        {
            if (npc.pendingDamagePermille <= 0 || !npc.alive)
            {
                continue;
            }

            if ((now - npc.pendingDamageSince) < UNACKED_DAMAGE_GRACE_NANOS)
            {
                continue;
            }

            final int before = npc.hpPermille;
            final int applied = npc.pendingDamagePermille;

            clearPendingDamage(npc);

            if (before < 0)
            {
                continue;
            }

            npc.hpPermille = Math.max(0, before - applied);

            WitcherServer.dbg("NPC %s SERVER-APPLIED %d permille (owner silent) | %d -> %d\n",
                    describeNpc(npc),
                    applied,
                    before,
                    npc.hpPermille);

            if (npc.hpPermille == 0)
            {
                npc.alive = false;
                npc.flags = npc.flags & ~1;
                npc.deadSinceNanos = now;

                WitcherServer.dbg("NPC %s DIED (server authority, owner silent) | owner=%s\n",
                        describeNpc(npc),
                        WitcherServer.describePlayerId(npc.ownerPlayerId));
            }
        }
    }

    public static boolean isDeadAuthoritative(Npc npc)
    {
        return !npc.alive;
    }

    public static List<Npc> pendingKillOrders(int ownerPlayerId)
    {
        List<Npc> orders = new ArrayList<>();

        for (Npc npc : npcs.values())
        {
            if (npc.killOrderPending && npc.ownerPlayerId == ownerPlayerId && npc.ownerLocalGuid != 0)
            {
                orders.add(npc);
            }
        }

        return orders;
    }

    public static List<Npc> pendingDeaths(long now)
    {
        List<Npc> pending = new ArrayList<>();

        for (Npc npc : npcs.values())
        {
            if (npc.alive)
            {
                continue;
            }

            if (npc.deathBroadcast && (now - npc.deadSinceNanos) > DEATH_BROADCAST_WINDOW_NANOS)
            {
                continue;
            }

            if ((now - npc.lastDeathSendNanos) < DEATH_BROADCAST_INTERVAL_NANOS)
            {
                continue;
            }

            pending.add(npc);
        }

        return pending;
    }

    public static List<Npc> visibleTo(PlayerSession session, double radiusSquared)
    {
        List<Npc> visible = new ArrayList<>();

        if (!session.hasPosition)
        {
            return visible;
        }

        final long now = System.nanoTime();

        for (Npc npc : npcs.values())
        {
            if (npc.ownerPlayerId == session.playerId)
            {
                continue;
            }

            if (!sharesSyncGroup(session, npc))
            {
                continue;
            }

            if (npc.area != session.area)
            {
                continue;
            }

            double dx = npc.x - session.posX;
            double dy = npc.y - session.posY;
            double dz = npc.z - session.posZ;

            if ((dx * dx + dy * dy + dz * dz) <= radiusSquared)
            {
                visible.add(npc);
            }
        }

        return visible;
    }

    public static boolean sharesSyncGroup(PlayerSession viewer, Npc npc)
    {
        int referenceId;
        PlayerSession reference;

        if (viewer == null || npc == null)
        {
            return false;
        }

        referenceId = (npc.ownerPlayerId != 0) ? npc.ownerPlayerId : npc.releasedByPlayerId;

        if (referenceId == 0 || referenceId == viewer.playerId)
        {
            return true;
        }

        reference = WitcherServer.sessionByPlayerId(referenceId);

        if (reference == null)
        {
            return true;
        }

        return WitcherServer.canShareNpcs(viewer, reference);
    }

    public static List<Npc> ownedBy(int ownerPlayerId)
    {
        List<Npc> owned = new ArrayList<>();

        if (ownerPlayerId == 0)
        {
            return owned;
        }

        for (Npc npc : npcs.values())
        {
            if (npc.ownerPlayerId == ownerPlayerId)
            {
                owned.add(npc);
            }
        }

        return owned;
    }

    public static void recomputeScaling(List<PlayerSession> sessions, double radiusSquared)
    {
        for (Npc npc : npcs.values())
        {
            int count = 0;

            for (PlayerSession session : sessions)
            {
                if (!session.hasPosition || session.area != npc.area)
                {
                    continue;
                }

                if (!sharesSyncGroup(session, npc))
                {
                    continue;
                }

                double dx = npc.x - session.posX;
                double dy = npc.y - session.posY;
                double dz = npc.z - session.posZ;

                if ((dx * dx + dy * dy + dz * dz) <= radiusSquared)
                {
                    count++;
                }
            }

            if (count < 1)
            {
                count = 1;
            }

            npc.scalePlayerCount = count;
            npc.scaleMilli = NpcScaling.scaleMilliFor(count);
        }
    }

    public static List<Npc> targetedNpcs()
    {
        List<Npc> targeted = new ArrayList<>();

        for (Npc npc : npcs.values())
        {
            if (npc.targetPlayerId != 0 && npc.alive)
            {
                targeted.add(npc);
            }
        }

        return targeted;
    }

    public static List<int[]> planHandovers(List<PlayerSession> sessions, double radiusSquared, long now)
    {
        List<int[]> orders = new ArrayList<>();

        for (Npc npc : npcs.values())
        {
            if (!npc.alive)
            {
                continue;
            }

            final boolean ownerHealthy = npc.ownerPlayerId != 0
                    && (now - npc.lastUpdateNanos) < HANDOVER_SILENCE_NANOS;

            if (ownerHealthy && (now - npc.lastMigrationNanos) < LATENCY_MIGRATION_INTERVAL_NANOS)
            {
                continue;
            }

            if (npc.handoverTarget != 0 && (now - npc.handoverSentNanos) < HANDOVER_RETRY_NANOS)
            {
                continue;
            }

            if (now < npc.handoverBlockedUntil)
            {
                continue;
            }

            if (npc.handoverAttempts >= HANDOVER_MAX_ATTEMPTS)
            {
                npc.handoverAttempts = 0;
                npc.handoverTarget = 0;
                npc.handoverBlockedUntil = now + HANDOVER_COOLDOWN_NANOS;
                npc.declinedUntil.clear();
                continue;
            }

            PlayerSession best = null;
            int bestLatency = Integer.MAX_VALUE;
            double bestDistance = Double.MAX_VALUE;
            boolean bestInHitRange = false;

            for (PlayerSession session : sessions)
            {
                if (session.playerId == npc.ownerPlayerId || !session.hasPosition)
                {
                    continue;
                }

                if (session.playerId == npc.releasedByPlayerId)
                {
                    continue;
                }

                if (session.paused)
                {
                    continue;
                }

                if ((now - session.lastReleaseNanos) < HANDOVER_RELEASE_GRACE_NANOS)
                {
                    continue;
                }

                if (!sharesSyncGroup(session, npc))
                {
                    continue;
                }

                if (session.area != npc.area)
                {
                    continue;
                }

                if (!session.knownNpcs.contains(npc.npcId))
                {
                    continue;
                }

                Long declinedUntil = npc.declinedUntil.get(session.playerId);

                if (declinedUntil != null)
                {
                    if (now < declinedUntil)
                    {
                        continue;
                    }

                    npc.declinedUntil.remove(session.playerId);
                }

                double dx = npc.x - session.posX;
                double dy = npc.y - session.posY;
                double dz = npc.z - session.posZ;
                double squared = (dx * dx) + (dy * dy) + (dz * dz);

                if (squared > radiusSquared)
                {
                    continue;
                }

                boolean inHitRange = squared <= HIT_RANGE_SQUARED;

                boolean better;

                if (best == null)
                {
                    better = true;
                }
                else if (inHitRange != bestInHitRange)
                {
                    better = inHitRange;
                }
                else if (session.rttMs != bestLatency)
                {
                    better = session.rttMs < bestLatency;
                }
                else
                {
                    better = squared < bestDistance;
                }

                if (better)
                {
                    bestLatency = session.rttMs;
                    bestDistance = squared;
                    bestInHitRange = inHitRange;
                    best = session;
                }
            }

            if (best == null)
            {
                continue;
            }

            if (ownerHealthy)
            {
                int ownerLatency = PlayerSession.UNKNOWN_RTT_MS;
                boolean ownerInHitRange = false;

                for (PlayerSession session : sessions)
                {
                    if (session.playerId == npc.ownerPlayerId)
                    {
                        ownerLatency = session.rttMs;

                        if (session.hasPosition && session.area == npc.area)
                        {
                            double odx = npc.x - session.posX;
                            double ody = npc.y - session.posY;
                            double odz = npc.z - session.posZ;

                            ownerInHitRange = ((odx * odx) + (ody * ody) + (odz * odz)) <= HIT_RANGE_SQUARED;
                        }

                        break;
                    }
                }

                boolean proximityWin = bestInHitRange && !ownerInHitRange;

                if (!proximityWin && (bestLatency + LATENCY_MIGRATION_MARGIN_MS) >= ownerLatency)
                {
                    continue;
                }

                npc.lastMigrationNanos = now;

                WitcherServer.dbg("NPC %s MIGRATE %s (rtt %dms hit=%s) -> %s (rtt %dms hit=%s) reason=%s\n",
                        describeNpc(npc),
                        WitcherServer.describePlayerId(npc.ownerPlayerId),
                        ownerLatency,
                        ownerInHitRange ? "yes" : "no",
                        WitcherServer.describePlayerId(best.playerId),
                        bestLatency,
                        bestInHitRange ? "yes" : "no",
                        proximityWin ? "proximity" : "latency");
            }

            npc.handoverTarget = best.playerId;
            npc.handoverSentNanos = now;
            npc.handoverAttempts++;

            orders.add(new int[] { npc.npcId, best.playerId });
        }

        return orders;
    }

    public static void declineHandover(int playerId, int canonicalId, long now)
    {
        Npc npc = npcs.get(canonicalId);

        if (npc == null || npc.handoverTarget != playerId)
        {
            return;
        }

        npc.declinedUntil.put(playerId, now + HANDOVER_DECLINE_NANOS);
        npc.handoverTarget = 0;
        npc.handoverSentNanos = 0L;
    }

    public static boolean ownsGuid(int playerId, int localGuid)
    {
        return ownerGuidToCanonical.containsKey(ownerKey(playerId, localGuid));
    }

    public static int[] pollPendingDrop()
    {
        int[] drop;

        while ((drop = pendingDrops.poll()) != null)
        {
            if (ownsGuid(drop[0], drop[1]))
            {
                continue;
            }

            return drop;
        }

        return null;
    }

    public static boolean releaseOwned(int playerId, int localGuid, long now)
    {
        final long key = ownerKey(playerId, localGuid);
        Integer canonicalId = ownerGuidToCanonical.get(key);
        Npc npc = (canonicalId == null) ? null : npcs.get(canonicalId);

        if (npc == null || npc.ownerPlayerId != playerId)
        {
            return false;
        }

        ownerGuidToCanonical.remove(key);

        npc.releasedByPlayerId = playerId;
        npc.releasedLocalGuid = localGuid;
        npc.releasedAtNanos = now;

        npc.ownerPlayerId = 0;
        npc.ownerLocalGuid = 0;
        npc.handoverTarget = 0;
        npc.handoverSentNanos = 0L;
        npc.handoverAttempts = 0;
        npc.handoverBlockedUntil = 0L;
        npc.declinedUntil.clear();

        return true;
    }

    public static int[] take(int playerId, int canonicalId, int localGuid, long now)
    {
        Npc npc = npcs.get(canonicalId);

        if (npc == null || localGuid == 0)
        {
            return null;
        }

        if (npc.handoverTarget != playerId || npc.ownerPlayerId == playerId)
        {
            return null;
        }

        int previousOwner = npc.ownerPlayerId;
        int previousGuid = npc.ownerLocalGuid;

        if (previousOwner != 0)
        {
            ownerGuidToCanonical.remove(ownerKey(previousOwner, npc.ownerLocalGuid));
        }
        else if (npc.releasedByPlayerId != 0 && npc.releasedByPlayerId != playerId)
        {
            previousOwner = npc.releasedByPlayerId;
            previousGuid = npc.releasedLocalGuid;
        }

        npc.releasedByPlayerId = 0;
        npc.releasedLocalGuid = 0;

        npc.ownerPlayerId = playerId;
        npc.ownerLocalGuid = localGuid;
        npc.handoverTarget = 0;
        npc.handoverAttempts = 0;
        npc.handoverBlockedUntil = 0L;
        npc.lastUpdateNanos = now;

        ownerGuidToCanonical.put(ownerKey(playerId, localGuid), npc.npcId);

        WitcherServer.dbg("NPC %s HANDOVER %s -> %s | spot=%s\n",
                describeNpc(npc),
                WitcherServer.describePlayerId(previousOwner),
                WitcherServer.describePlayerId(playerId),
                describeSpot(npc));

        return new int[] { previousOwner, previousGuid };
    }

    public static void forgetKnown(PlayerSession session, int canonicalId)
    {
        session.knownNpcs.remove(canonicalId);
    }

    public static double hitDistance(Npc npc, PlayerSession attacker, long atMs)
    {
        Sample entityAt = npc.rewind(atMs);
        PlayerSession.Sample attackerAt = attacker.rewind(atMs);

        double dx = entityAt.x - attackerAt.x;
        double dy = entityAt.y - attackerAt.y;
        double dz = entityAt.z - attackerAt.z;

        return Math.sqrt((dx * dx) + (dy * dy) + (dz * dz));
    }
}
