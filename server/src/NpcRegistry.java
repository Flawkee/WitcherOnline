import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcRegistry
{
    public static final int TERMINAL_ACTIVE = 0;
    public static final int TERMINAL_DEAD = 1;
    public static final int TERMINAL_UNCONSCIOUS = 2;
    public static final int TERMINAL_AGONY = 3;
    public static final int LIFECYCLE_ACTIVE = 1;
    public static final int LIFECYCLE_DORMANT = 2;
    public static final int LIFECYCLE_TERMINAL = 3;
    public static final int BINDING_NATIVE = 1;
    public static final int BINDING_SYNTHETIC = 2;
    public static final int IDENTITY_PERSISTENT = 1;
    public static final int IDENTITY_CROSS_AREA = 2;
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
    public static final long TERMINAL_RETRY_NANOS = 2_000_000_000L;
    public static final long TERMINAL_TOMBSTONE_MIN_NANOS = 5_000_000_000L;
    public static final long TERMINAL_TOMBSTONE_MAX_NANOS = 600_000_000_000L;
    public static final long HANDOVER_DECLINE_NANOS = 3_000_000_000L;
    public static final long NPC_STREAM_FRESH_NANOS = 12_000_000_000L;
    public static final long HANDOVER_SILENCE_NANOS = 1_500_000_000L;
    public static final long HANDOVER_RETRY_NANOS = 1_000_000_000L;
    public static final int HANDOVER_MAX_ATTEMPTS = 3;
    public static final long HANDOVER_COOLDOWN_NANOS = 15_000_000_000L;
    public static final long UNACKED_DAMAGE_GRACE_NANOS = 1_200_000_000L;
    public static final long QUEST_KILL_ORDER_RETRY_NANOS = 200_000_000L;
    public static final long HANDOVER_IDLE_EVAL_NANOS = 250_000_000L;

    public static final int MAX_NPCS_PER_OWNER = 250;
    public static final double RESERVATION_RADIUS = 30.0;
    public static final double HANDOVER_SUSTAIN_RADIUS = 70.0;
    public static final double HANDOVER_SUSTAIN_SQUARED = HANDOVER_SUSTAIN_RADIUS * HANDOVER_SUSTAIN_RADIUS;

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
        public final Integer boxedId;
        public volatile int ownerPlayerId;
        public volatile int ownerLocalGuid;
        public volatile int area;
        public volatile String typeCode = "-";
        public volatile String appearance = "-";
        public volatile String identityKey = "-";
        public volatile int identityFlags;
        public volatile double x;
        public volatile double y;
        public volatile double z;
        public volatile double heading;
        public volatile int hpPermille = -1;
        public volatile int flags;
        public volatile int targetPlayerId;
        public volatile int terminalState = TERMINAL_ACTIVE;
        public volatile int terminalRevision;
        public volatile int terminalAttackerId;
        public volatile int authorityRevision = 1;
        public volatile int lifecycleRevision = 1;
        public volatile int lifecycle = LIFECYCLE_ACTIVE;
        public volatile boolean alive = true;
        public volatile boolean deathBroadcast;
        public final java.util.Map<Integer, Integer> deathSends = new java.util.concurrent.ConcurrentHashMap<>();
        public final java.util.Map<Integer, Long> terminalLastSends = new java.util.concurrent.ConcurrentHashMap<>();
        public final java.util.Set<Integer> terminalPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
        public final java.util.Set<Integer> terminalAcked = java.util.concurrent.ConcurrentHashMap.newKeySet();
        public volatile boolean terminalRecipientsInitialized;
        public volatile long terminalExpiresNanos;
        public volatile long lastDeathSendNanos;
        public volatile long lastUpdateNanos;
        public volatile long lastSnapshotMs;
        public volatile long lastFastSnapshotMs;
        public volatile int lastFastSequence;
        public volatile long deadSinceNanos;
        public volatile int handoverTarget;
        public volatile long handoverSentNanos;
        public volatile int handoverAttempts;
        public volatile long handoverBlockedUntil;
        public volatile int releasedByPlayerId;
        public volatile int releasedLocalGuid;
        public volatile long releasedAtNanos;
        public volatile long lastMigrationNanos;
        public volatile long nextHandoverEvalNanos;
        public final java.util.Map<Integer, Long> declinedUntil = new java.util.concurrent.ConcurrentHashMap<>();
        public volatile boolean killOrderPending;
        public volatile long lastKillOrderSendNanos;
        public volatile int killOrderSends;
        public volatile int pendingDamagePermille;
        public volatile long pendingDamageSince;
        public volatile int pendingDamageAttackerId;
        public volatile int scaleMilli = NpcScaling.SCALE_UNIT;
        public volatile int scalePlayerCount = 1;
        public volatile int questPartyId;
        public volatile int syncPartyId;
        public volatile int syncMode;
        public volatile SpatialIndex.CellKey spatialCell;

        final ArrayDeque<Sample> history = new ArrayDeque<>();

        Npc(int npcId)
        {
            this.npcId = npcId;
            this.boxedId = Integer.valueOf(npcId);
            this.lastMigrationNanos = System.nanoTime();
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

    public static final class Binding
    {
        public final int playerId;
        public final int canonicalId;
        public volatile int localGuid;
        public volatile int kind;
        public volatile boolean ready;
        public volatile int readyLifecycleRevision;
        public volatile long lastSeenNanos;

        Binding(int playerId, int canonicalId)
        {
            this.playerId = playerId;
            this.canonicalId = canonicalId;
        }
    }

    private static final Map<Integer, Npc> npcs = new ConcurrentHashMap<>();
    private static final Map<SpatialIndex.CellKey, java.util.Set<Npc>> byCell = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> ownerGuidToCanonical = new ConcurrentHashMap<>();
    private static final Map<Long, Binding> bindingByGuid = new ConcurrentHashMap<>();
    private static final Map<Long, Binding> bindingByCanonicalPlayer = new ConcurrentHashMap<>();
    private static final java.util.Queue<int[]> pendingDrops = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger killOrdersPending =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile long lastDeathNanos = 0L;
    private static final List<Npc> EMPTY_NPCS = java.util.Collections.emptyList();

    private static void areaInsert(Npc npc)
    {
        cellMove(npc);
    }

    private static void areaRemove(Npc npc, int area)
    {
        cellRemove(npc);
    }

    private static void areaMove(Npc npc, int fromArea, int toArea)
    {
        if (fromArea == toArea)
        {
            return;
        }

        areaRemove(npc, fromArea);
        areaInsert(npc);
    }

    private static void cellMove(Npc npc)
    {
        synchronized (npc)
        {
            SpatialIndex.CellKey next = SpatialIndex.CellKey.at(npc.area, npc.x, npc.y);
            SpatialIndex.CellKey previous = npc.spatialCell;
            if (next.equals(previous))
            {
                return;
            }
            cellRemove(npc);
            byCell.computeIfAbsent(next, ignored -> ConcurrentHashMap.newKeySet()).add(npc);
            npc.spatialCell = next;
        }
    }

    private static void cellRemove(Npc npc)
    {
        synchronized (npc)
        {
            SpatialIndex.CellKey previous = npc.spatialCell;
            npc.spatialCell = null;
            if (previous == null)
            {
                return;
            }
            java.util.Set<Npc> bucket = byCell.get(previous);
            if (bucket != null)
            {
                bucket.remove(npc);
                if (bucket.isEmpty())
                {
                    byCell.remove(previous, bucket);
                }
            }
        }
    }

    private static List<Npc> nearby(int area, double x, double y, double radius)
    {
        int minX = SpatialIndex.cell(x - radius);
        int maxX = SpatialIndex.cell(x + radius);
        int minY = SpatialIndex.cell(y - radius);
        int maxY = SpatialIndex.cell(y + radius);
        List<Npc> result = new ArrayList<>();
        for (int cx = minX; cx <= maxX; cx++)
        {
            for (int cy = minY; cy <= maxY; cy++)
            {
                java.util.Set<Npc> bucket = byCell.get(new SpatialIndex.CellKey(area, cx, cy));
                if (bucket != null)
                {
                    result.addAll(bucket);
                }
            }
        }
        return result;
    }

    private static void unregister(Npc npc)
    {
        npcs.remove(npc.npcId);
        areaRemove(npc, npc.area);
        removeBindingsForCanonical(npc.npcId);

        if (npc.killOrderPending)
        {
            npc.killOrderPending = false;
            killOrdersPending.decrementAndGet();
        }
    }
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

    private static long canonicalPlayerKey(int playerId, int canonicalId)
    {
        return (((long) playerId) << 32) | (canonicalId & 0xFFFFFFFFL);
    }

    public static Binding bindingByGuid(int playerId, int localGuid)
    {
        return bindingByGuid.get(ownerKey(playerId, localGuid));
    }

    public static Binding bindingFor(int playerId, int canonicalId)
    {
        return bindingByCanonicalPlayer.get(canonicalPlayerKey(playerId, canonicalId));
    }

    public static Npc boundNpc(int playerId, int localGuid)
    {
        Binding binding = bindingByGuid(playerId, localGuid);
        return binding == null ? null : npcs.get(binding.canonicalId);
    }

    public static boolean bindingIdentityMatches(
            Binding binding,
            String typeCode,
            String appearance,
            String identityKey)
    {
        Npc npc = binding == null ? null : npcs.get(binding.canonicalId);
        if (npc == null || !npc.typeCode.equals(typeCode))
        {
            return false;
        }
        return npc.identityKey.equals("-") || identityKey == null || identityKey.isEmpty()
                || npc.identityKey.equals(identityKey);
    }

    public static Npc reclaimExactBinding(int playerId, int localGuid, long now)
    {
        Binding binding = bindingByGuid(playerId, localGuid);
        Npc npc = binding == null ? null : npcs.get(binding.canonicalId);
        if (npc == null || !npc.alive || npc.ownerPlayerId != 0
                || binding.kind != BINDING_NATIVE)
        {
            return null;
        }
        npc.ownerPlayerId = playerId;
        npc.ownerLocalGuid = localGuid;
        npc.releasedByPlayerId = 0;
        npc.releasedLocalGuid = 0;
        npc.handoverTarget = 0;
        npc.handoverSentNanos = 0L;
        npc.handoverAttempts = 0;
        npc.handoverBlockedUntil = 0L;
        npc.lifecycle = LIFECYCLE_ACTIVE;
        npc.authorityRevision += 1;
        npc.lastUpdateNanos = now;
        npc.lastSnapshotMs = 0L;
        npc.lastFastSnapshotMs = 0L;
        npc.lastFastSequence = 0;
        ownerGuidToCanonical.put(ownerKey(playerId, localGuid), npc.npcId);
        bindObservation(playerId, localGuid, npc.npcId, BINDING_NATIVE, true, now);
        captureSyncGroup(npc, playerId);
        return npc;
    }

    public static Binding bindObservation(
            int playerId,
            int localGuid,
            int canonicalId,
            int kind,
            boolean ready,
            long now)
    {
        Npc npc = npcs.get(canonicalId);
        if (playerId <= 0 || canonicalId <= 0 || localGuid < 0 || npc == null)
        {
            return null;
        }

        final long canonicalKey = canonicalPlayerKey(playerId, canonicalId);
        Binding previousCanonical = bindingByCanonicalPlayer.get(canonicalKey);
        if (previousCanonical != null && previousCanonical.localGuid != 0
                && previousCanonical.localGuid != localGuid)
        {
            bindingByGuid.remove(ownerKey(playerId, previousCanonical.localGuid), previousCanonical);
        }

        if (localGuid != 0)
        {
            Binding previousGuid = bindingByGuid.get(ownerKey(playerId, localGuid));
            if (previousGuid != null && previousGuid.canonicalId != canonicalId)
            {
                bindingByCanonicalPlayer.remove(
                        canonicalPlayerKey(playerId, previousGuid.canonicalId), previousGuid);
            }
        }

        Binding binding = previousCanonical;
        if (binding == null)
        {
            binding = new Binding(playerId, canonicalId);
        }

        binding.localGuid = localGuid;
        binding.kind = kind == BINDING_SYNTHETIC ? BINDING_SYNTHETIC : BINDING_NATIVE;
        binding.ready = ready;
        binding.readyLifecycleRevision = ready ? npc.lifecycleRevision : 0;
        binding.lastSeenNanos = now;
        bindingByCanonicalPlayer.put(canonicalKey, binding);
        if (localGuid != 0)
        {
            bindingByGuid.put(ownerKey(playerId, localGuid), binding);
        }
        return binding;
    }

    public static void prepareRecipient(int playerId, Npc npc, int kind, long now)
    {
        Binding binding = bindingFor(playerId, npc.npcId);
        if (binding == null)
        {
            bindObservation(playerId, 0, npc.npcId, kind, false, now);
            return;
        }
        if (binding.localGuid != 0 && binding.ready
                && binding.readyLifecycleRevision == npc.lifecycleRevision)
        {
            binding.lastSeenNanos = now;
            return;
        }
        binding.ready = false;
        binding.readyLifecycleRevision = 0;
        binding.lastSeenNanos = now;
    }

    public static boolean acknowledgeBinding(
            int playerId,
            int canonicalId,
            int localGuid,
            int kind,
            int lifecycleRevision,
            long now)
    {
        Npc npc = npcs.get(canonicalId);
        if (npc == null || localGuid == 0 || lifecycleRevision != npc.lifecycleRevision)
        {
            return false;
        }
        return bindObservation(playerId, localGuid, canonicalId, kind, true, now) != null;
    }

    public static boolean bindingReady(int playerId, Npc npc)
    {
        Binding binding = bindingFor(playerId, npc.npcId);
        return binding != null && binding.ready
                && binding.readyLifecycleRevision == npc.lifecycleRevision;
    }

    public static boolean authorityEligible(int playerId, int canonicalId)
    {
        Binding binding = bindingFor(playerId, canonicalId);
        return binding != null && binding.ready && binding.localGuid != 0;
    }

    public static void markRecipientUnready(int playerId, int canonicalId)
    {
        Binding binding = bindingFor(playerId, canonicalId);
        if (binding == null)
        {
            return;
        }
        binding.ready = false;
        binding.readyLifecycleRevision = 0;
        if (binding.kind == BINDING_SYNTHETIC)
        {
            bindingByCanonicalPlayer.remove(canonicalPlayerKey(playerId, canonicalId), binding);
            if (binding.localGuid != 0)
            {
                bindingByGuid.remove(ownerKey(playerId, binding.localGuid), binding);
            }
        }
    }

    public static void forgetPlayerBindings(int playerId)
    {
        for (Binding binding : new ArrayList<>(bindingByCanonicalPlayer.values()))
        {
            if (binding.playerId != playerId)
            {
                continue;
            }
            bindingByCanonicalPlayer.remove(canonicalPlayerKey(playerId, binding.canonicalId), binding);
            if (binding.localGuid != 0)
            {
                bindingByGuid.remove(ownerKey(playerId, binding.localGuid), binding);
            }
        }
    }

    private static void removeBindingsForCanonical(int canonicalId)
    {
        for (Binding binding : new ArrayList<>(bindingByCanonicalPlayer.values()))
        {
            if (binding.canonicalId != canonicalId)
            {
                continue;
            }
            bindingByCanonicalPlayer.remove(
                    canonicalPlayerKey(binding.playerId, canonicalId), binding);
            if (binding.localGuid != 0)
            {
                bindingByGuid.remove(ownerKey(binding.playerId, binding.localGuid), binding);
            }
        }
    }

    public static boolean isQuestFoe(Npc npc)
    {
        return npc.typeCode != null && npc.typeCode.startsWith("quest:");
    }

    private static Npc findQuestSlot(String typeCode, int partyId)
    {
        for (Npc npc : npcs.values())
        {
            if (npc.questPartyId == partyId && typeCode.equals(npc.typeCode))
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

    public static int sanitizeTerminalState(int terminalState)
    {
        if (terminalState < TERMINAL_ACTIVE || terminalState > TERMINAL_AGONY)
        {
            return TERMINAL_ACTIVE;
        }

        return terminalState;
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

    private static void captureSyncGroup(Npc npc, int ownerPlayerId)
    {
        PlayerSession owner = WitcherServer.sessionByPlayerId(ownerPlayerId);

        if (owner != null)
        {
            npc.syncPartyId = owner.partyId;
            npc.syncMode = owner.npcSyncMode;
        }
    }

    private static boolean sharesReservation(PlayerSession owner, Npc npc)
    {
        if (owner == null)
        {
            return false;
        }

        if (npc.syncPartyId != 0 || owner.partyId != 0)
        {
            return npc.syncPartyId != 0 && npc.syncPartyId == owner.partyId;
        }

        return npc.syncMode == 0 && owner.npcSyncMode == 0;
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

        for (Npc npc : nearby(area, x, y, RESERVATION_RADIUS))
        {
            if (!npc.alive || !npc.typeCode.equals(typeCode))
            {
                continue;
            }

            if (!withinReservation(npc, area, x, y, z))
            {
                continue;
            }

            if (npc.ownerPlayerId == ownerPlayerId || npc.releasedByPlayerId == ownerPlayerId)
            {
                mine++;
            }
            else if (sharesReservation(owner, npc))
            {
                foreign++;
            }
        }

        if (foreign + mine == 0)
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

        for (Npc npc : nearby(area, x, y, RESERVATION_RADIUS))
        {
            if (npc.ownerPlayerId != 0 || !npc.alive)
            {
                continue;
            }

            if (!npc.typeCode.equals(typeCode))
            {
                continue;
            }

            if (npc.releasedByPlayerId != 0
                    && !WitcherServer.canShareNpcs(adopter, WitcherServer.sessionByPlayerId(npc.releasedByPlayerId)))
            {
                continue;
            }

            if (npc.releasedByPlayerId != 0
                    && npc.releasedByPlayerId != ownerPlayerId
                    && now < npc.handoverBlockedUntil)
            {
                continue;
            }

            if (!withinReservation(npc, area, x, y, z))
            {
                continue;
            }

            npc.ownerPlayerId = ownerPlayerId;
            npc.ownerLocalGuid = ownerLocalGuid;
            npc.lifecycle = LIFECYCLE_ACTIVE;
            npc.handoverTarget = 0;
            npc.handoverSentNanos = 0L;
            npc.handoverAttempts = 0;
            npc.handoverBlockedUntil = 0L;
            npc.declinedUntil.clear();
            npc.lastUpdateNanos = now;
            npc.lastSnapshotMs = 0L;
            npc.lastFastSnapshotMs = 0L;
            npc.lastFastSequence = 0;
            captureSyncGroup(npc, ownerPlayerId);

            if (npc.releasedByPlayerId != 0 && npc.releasedByPlayerId != ownerPlayerId)
            {
                pendingDrops.add(new int[] { npc.releasedByPlayerId, npc.releasedLocalGuid, npc.npcId });
            }

            npc.releasedByPlayerId = 0;
            npc.releasedLocalGuid = 0;
            npc.authorityRevision += 1;

            ownerGuidToCanonical.put(ownerKey(ownerPlayerId, ownerLocalGuid), npc.npcId);
            bindObservation(ownerPlayerId, ownerLocalGuid, npc.npcId, BINDING_NATIVE, true, now);

            WitcherServer.dbg("NPC %s READOPTED | owner=%s spot=%s\n",
                    describeNpc(npc),
                    WitcherServer.describePlayerId(ownerPlayerId),
                    describeSpot(npc));

            return npc;
        }

        return null;
    }

    private static Npc adoptPersistent(
            int ownerPlayerId,
            int ownerLocalGuid,
            String typeCode,
            String appearance,
            String identityKey,
            int identityFlags,
            int area,
            long now)
    {
        if ((identityFlags & IDENTITY_PERSISTENT) == 0
                || identityKey == null || identityKey.isEmpty() || "-".equals(identityKey))
        {
            return null;
        }

        PlayerSession adopter = WitcherServer.sessionByPlayerId(ownerPlayerId);
        Npc match = null;
        for (Npc npc : npcs.values())
        {
            if (!npc.alive || npc.lifecycle == LIFECYCLE_TERMINAL || npc.ownerPlayerId != 0)
            {
                continue;
            }
            if (!npc.typeCode.equals(typeCode) || !npc.identityKey.equals(identityKey))
            {
                continue;
            }
            if ((identityFlags & IDENTITY_CROSS_AREA) == 0 && npc.area != area)
            {
                continue;
            }
            if (!sharesReservation(adopter, npc) || match != null)
            {
                return null;
            }
            match = npc;
        }

        if (match == null)
        {
            return null;
        }

        match.ownerPlayerId = ownerPlayerId;
        match.ownerLocalGuid = ownerLocalGuid;
        match.releasedByPlayerId = 0;
        match.releasedLocalGuid = 0;
        match.handoverTarget = 0;
        match.handoverSentNanos = 0L;
        match.handoverAttempts = 0;
        match.handoverBlockedUntil = 0L;
        match.lastUpdateNanos = now;
        match.lastSnapshotMs = 0L;
        match.lastFastSnapshotMs = 0L;
        match.lastFastSequence = 0;
        match.lifecycle = LIFECYCLE_ACTIVE;
        match.authorityRevision += 1;
        captureSyncGroup(match, ownerPlayerId);
        ownerGuidToCanonical.put(ownerKey(ownerPlayerId, ownerLocalGuid), match.npcId);
        bindObservation(ownerPlayerId, ownerLocalGuid, match.npcId, BINDING_NATIVE, true, now);
        return match;
    }

    public static Npc upsert(
            int ownerPlayerId,
            int ownerLocalGuid,
            int area,
            String typeCode,
            String appearance,
            String identityKey,
            int identityFlags,
            double x,
            double y,
            double z,
            double heading,
            int hpPermille,
            int flags,
            int targetPlayerId,
            int terminalState,
            int terminalAttackerId,
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

        if (npc != null && !questFoe && !npc.typeCode.equals(typeCode))
        {
            unregister(npc);
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

            if (npc != null && npc.questPartyId != partyId)
            {
                unregister(npc);
                ownerGuidToCanonical.remove(key);
                npc = null;
            }

            Npc slot = findQuestSlot(typeCode, partyId);

            if ((npc != null && !npc.alive) || (slot != null && !slot.alive))
            {
                Npc tombstone = (npc != null && !npc.alive) ? npc : slot;

                WitcherServer.dbg("QFOE %s dead slot #%d rejected from %s guid=%d\n",
                        typeCode,
                        tombstone.npcId,
                        WitcherServer.describePlayerId(ownerPlayerId),
                        ownerLocalGuid);

                statRejectedDuplicate.incrementAndGet();
                return null;
            }

            if (npc == null)
            {

                if (slot != null && slot.ownerPlayerId != 0 && slot.ownerPlayerId != ownerPlayerId)
                {
                    WitcherServer.dbg("QFOE %s claim rejected from %s; owned by %s\n",
                            typeCode,
                            WitcherServer.describePlayerId(ownerPlayerId),
                            WitcherServer.describePlayerId(slot.ownerPlayerId));
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
                    slot.lastSnapshotMs = 0L;
                    slot.authorityRevision += 1;
                    captureSyncGroup(slot, ownerPlayerId);
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
                    npc.area = area;
                    npc.x = x;
                    npc.y = y;
                    npc.z = z;
                    npc.lastUpdateNanos = now;
                    captureSyncGroup(npc, ownerPlayerId);
                    npcs.put(npc.npcId, npc);
                    areaInsert(npc);
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
            Npc adopted = adoptPersistent(
                    ownerPlayerId, ownerLocalGuid, typeCode, appearance,
                    identityKey, identityFlags, area, now);

            if (adopted == null)
            {
                adopted = adoptOrphan(ownerPlayerId, ownerLocalGuid, typeCode, appearance, area, x, y, z, now);
            }

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
            npc.identityKey = identityKey == null || identityKey.isEmpty() ? "-" : identityKey;
            npc.identityFlags = identityFlags;
            npc.area = area;
            npc.x = x;
            npc.y = y;
            npc.z = z;
            npc.lastUpdateNanos = now;
            captureSyncGroup(npc, ownerPlayerId);
            npcs.put(npc.npcId, npc);
            areaInsert(npc);
            ownerGuidToCanonical.put(key, npc.npcId);
            bindObservation(ownerPlayerId, ownerLocalGuid, npc.npcId, BINDING_NATIVE, true, now);
            statAdmitted.incrementAndGet();
        }

        if (npc.ownerPlayerId != ownerPlayerId)
        {
            return null;
        }

        captureSyncGroup(npc, ownerPlayerId);
        if (npc.identityKey.equals("-") && identityKey != null && !identityKey.isEmpty())
        {
            npc.identityKey = identityKey;
            npc.identityFlags = identityFlags;
        }
        bindObservation(ownerPlayerId, ownerLocalGuid, npc.npcId, BINDING_NATIVE, true, now);

        applyState(npc, area, x, y, z, heading, hpPermille, flags, targetPlayerId,
                terminalState, terminalAttackerId, snapshotMs, now);

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

    public static Npc findBindable(
            int ownerPlayerId,
            int localGuid,
            int area,
            String typeCode,
            String appearance,
            String identityKey,
            int identityFlags,
            double x,
            double y,
            double z,
            java.util.Set<Integer> excluded)
    {
        PlayerSession owner = WitcherServer.sessionByPlayerId(ownerPlayerId);
        Npc best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Npc npc : nearby(area, x, y, RESERVATION_RADIUS))
        {
            if (!npc.alive || npc.lifecycle != LIFECYCLE_ACTIVE
                    || npc.ownerPlayerId == 0 || npc.ownerPlayerId == ownerPlayerId)
            {
                continue;
            }

            if (excluded != null && excluded.contains(npc.boxedId))
            {
                continue;
            }

            if (!npc.typeCode.equals(typeCode))
            {
                continue;
            }

            if (!sharesReservation(owner, npc))
            {
                continue;
            }

            Binding existing = bindingFor(ownerPlayerId, npc.npcId);
            if (existing != null && existing.localGuid != localGuid
                    && existing.kind == BINDING_NATIVE)
            {
                continue;
            }

            if (!withinReservation(npc, area, x, y, z))
            {
                continue;
            }

            double dx = npc.x - x;
            double dy = npc.y - y;
            double dz = npc.z - z;
            double distance = dx * dx + dy * dy + dz * dz;

            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = npc;
            }
        }

        return best;
    }

    public static Npc findIdentityBindable(
            int playerId,
            int localGuid,
            String typeCode,
            String appearance,
            String identityKey,
            int identityFlags)
    {
        if ((identityFlags & IDENTITY_PERSISTENT) == 0
                || identityKey == null || identityKey.isEmpty() || "-".equals(identityKey))
        {
            return null;
        }

        PlayerSession observer = WitcherServer.sessionByPlayerId(playerId);
        Npc match = null;
        for (Npc npc : npcs.values())
        {
            if (!npc.alive || npc.lifecycle != LIFECYCLE_ACTIVE
                    || !npc.typeCode.equals(typeCode)
                    || !npc.identityKey.equals(identityKey) || !sharesReservation(observer, npc))
            {
                continue;
            }

            Binding existing = bindingFor(playerId, npc.npcId);
            if (existing != null && existing.localGuid != 0 && existing.localGuid != localGuid
                    && existing.kind == BINDING_NATIVE)
            {
                continue;
            }

            if (match != null)
            {
                return null;
            }
            match = npc;
        }
        return match;
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
            int terminalState,
            int terminalAttackerId,
            long snapshotMs,
            long now)
    {
        Npc npc = getByOwnerGuid(ownerPlayerId, ownerLocalGuid);

        if (npc == null || npc.ownerPlayerId != ownerPlayerId)
        {
            return null;
        }

        if (isQuestFoe(npc)
                && !WitcherServer.questVisibleTo(WitcherServer.sessionByPlayerId(ownerPlayerId), npc))
        {
            releaseOwned(ownerPlayerId, ownerLocalGuid, now);

            WitcherServer.dbg("QFOE %s released by ineligible owner %s\n",
                    npc.typeCode,
                    WitcherServer.describePlayerId(ownerPlayerId));

            return null;
        }

        captureSyncGroup(npc, ownerPlayerId);

        applyState(npc, npc.area, x, y, z, heading, hpPermille, flags, targetPlayerId,
                terminalState, terminalAttackerId, snapshotMs, now);

        return npc;
    }

    public static Npc moveFast(
            int ownerPlayerId,
            int ownerLocalGuid,
            int authorityRevision,
            int sequence,
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
        if (npc == null || npc.ownerPlayerId != ownerPlayerId || !npc.alive
                || authorityRevision != npc.authorityRevision
                || snapshotMs < npc.lastFastSnapshotMs
                || sequence <= npc.lastFastSequence)
        {
            return null;
        }

        PlayerSession targetSession = targetPlayerId == 0
                ? null
                : WitcherServer.sessionByPlayerId(targetPlayerId);
        if (targetPlayerId != 0
                && (targetSession == null || targetSession.paused
                    || (isQuestFoe(npc) && !WitcherServer.questVisibleTo(targetSession, npc))))
        {
            targetPlayerId = 0;
        }

        npc.x = x;
        npc.y = y;
        npc.z = z;
        cellMove(npc);
        npc.heading = heading;
        npc.hpPermille = Math.max(1, hpPermille);
        npc.flags = (flags | 1);
        npc.targetPlayerId = targetPlayerId;
        npc.lastFastSnapshotMs = snapshotMs;
        npc.lastFastSequence = sequence;
        npc.lastUpdateNanos = now;
        npc.record(snapshotMs, x, y, z);
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
            int terminalState,
            int terminalAttackerId,
            long snapshotMs,
            long now)
    {
        if (snapshotMs < npc.lastSnapshotMs)
        {
            return;
        }

        npc.lastSnapshotMs = snapshotMs;
        final PlayerSession targetSession = targetPlayerId == 0
                ? null
                : WitcherServer.sessionByPlayerId(targetPlayerId);

        if (targetPlayerId != 0
                && (targetSession == null
                    || targetSession.paused
                    || (isQuestFoe(npc) && !WitcherServer.questVisibleTo(targetSession, npc))))
        {
            targetPlayerId = 0;
        }

        final int previousTarget = npc.targetPlayerId;
        final boolean wasAlive = npc.alive;
        final int previousArea = npc.area;
        final int previousTerminalState = npc.terminalState;
        final int previousTerminalAttacker = npc.terminalAttackerId;

        npc.area = area;
        npc.x = x;
        npc.y = y;
        npc.z = z;
        areaMove(npc, previousArea, area);
        cellMove(npc);
        npc.heading = heading;
        npc.hpPermille = hpPermille;
        npc.flags = flags;
        npc.targetPlayerId = targetPlayerId;
        npc.lastUpdateNanos = now;
        npc.record(snapshotMs, x, y, z);

        final boolean ownerSaysAlive = (flags & 1) != 0;

        terminalState = sanitizeTerminalState(terminalState);

        if (ownerSaysAlive)
        {
            terminalState = TERMINAL_ACTIVE;
        }
        else if (terminalState == TERMINAL_ACTIVE)
        {
            terminalState = TERMINAL_DEAD;
        }

        if (!wasAlive && ownerSaysAlive)
        {
            if (isQuestFoe(npc) && hpPermille > 0)
            {
                npc.alive = true;
                npc.lifecycle = LIFECYCLE_ACTIVE;
                npc.terminalState = TERMINAL_ACTIVE;
                npc.terminalAttackerId = 0;
                npc.terminalRevision += 1;
                npc.deathBroadcast = false;
                clearTerminalDelivery(npc);
                npc.deadSinceNanos = 0L;

                return;
            }

            if (!npc.killOrderPending)
            {
                npc.killOrderPending = true;
                killOrdersPending.incrementAndGet();
            }

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
            int resolvedAttacker = WitcherServer.sanitizeTerminalAttacker(npc, terminalAttackerId);

            if (resolvedAttacker == 0)
            {
                resolvedAttacker = WitcherServer.sanitizeTerminalAttacker(
                        npc, npc.pendingDamageAttackerId);
            }

            clearKillOrder(npc);
            clearPendingDamage(npc);
            npc.terminalState = terminalState;
            npc.lifecycle = LIFECYCLE_TERMINAL;
            npc.terminalAttackerId = resolvedAttacker;
            npc.terminalRevision += 1;
            npc.deadSinceNanos = now;
            resetTerminalDelivery(npc, now);
            lastDeathNanos = now;

            WitcherServer.dbg("NPC %s DIED | owner=%s spot=%s\n",
                    describeNpc(npc),
                    WitcherServer.describePlayerId(npc.ownerPlayerId),
                    describeSpot(npc));
        }
        else if (!wasAlive && !npc.alive)
        {
            final int refinedState = previousTerminalState == TERMINAL_DEAD
                    && terminalState != TERMINAL_DEAD
                    ? terminalState
                    : previousTerminalState;
            final int claimedAttacker = WitcherServer.sanitizeTerminalAttacker(
                    npc, terminalAttackerId);
            final int refinedAttacker = previousTerminalAttacker == 0
                    ? claimedAttacker
                    : previousTerminalAttacker;

            if (refinedState != previousTerminalState
                    || refinedAttacker != previousTerminalAttacker)
            {
                npc.terminalState = refinedState;
                npc.terminalAttackerId = refinedAttacker;
                npc.terminalRevision += 1;
                npc.deathBroadcast = false;
                npc.deadSinceNanos = now;
                resetTerminalDelivery(npc, now);
                lastDeathNanos = now;

            }
        }
        else if (npc.alive)
        {
            npc.lifecycle = LIFECYCLE_ACTIVE;
            npc.terminalState = TERMINAL_ACTIVE;
            npc.terminalAttackerId = 0;
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

        if (npc.alive)
        {
            boolean released = releaseOwned(ownerPlayerId, ownerLocalGuid, System.nanoTime());

            if (released)
            {
                WitcherServer.dbg("NPC %s RELEASED after local actor loss | owner=%s spot=%s\n",
                        describeNpc(npc),
                        WitcherServer.describePlayerId(ownerPlayerId),
                        describeSpot(npc));
            }

            return released;
        }

        ownerGuidToCanonical.remove(key);

        if (!npc.alive)
        {
            npc.releasedByPlayerId = npc.ownerPlayerId;
            npc.releasedLocalGuid = npc.ownerLocalGuid;
            npc.releasedAtNanos = System.nanoTime();
            npc.ownerPlayerId = 0;
            npc.ownerLocalGuid = 0;
            npc.lifecycle = LIFECYCLE_TERMINAL;
            npc.handoverTarget = 0;
            npc.handoverSentNanos = 0L;
            npc.handoverAttempts = 0;
            npc.handoverBlockedUntil = 0L;
            npc.authorityRevision += 1;

            WitcherServer.dbg("NPC %s TOMBSTONED after owner despawn\n", describeNpc(npc));

            return true;
        }

        return false;
    }

    public static void orphanNpcsOwnedBy(int playerId, long now)
    {
        for (Npc npc : npcs.values())
        {
            forgetTerminalRecipient(npc, playerId);

            if (npc.ownerPlayerId != playerId)
            {
                continue;
            }

            ownerGuidToCanonical.remove(ownerKey(playerId, npc.ownerLocalGuid));

            npc.releasedByPlayerId = playerId;
            npc.releasedLocalGuid = npc.ownerLocalGuid;
            npc.releasedAtNanos = now;

            npc.ownerPlayerId = 0;
            npc.ownerLocalGuid = 0;
            npc.lifecycle = npc.alive ? LIFECYCLE_DORMANT : LIFECYCLE_TERMINAL;
            npc.handoverTarget = 0;
            npc.handoverBlockedUntil = isQuestFoe(npc)
                    ? 0L
                    : now + HANDOVER_RELEASE_GRACE_NANOS;
            npc.authorityRevision += 1;
        }
        forgetPlayerBindings(playerId);
    }

    public static void pruneStale(java.util.Set<Integer> ownerOnline, long now)
    {
        for (Npc npc : npcs.values())
        {
            if (!npc.alive && isQuestFoe(npc))
            {
                continue;
            }

            if (!npc.alive)
            {
                if (npc.terminalExpiresNanos > 0L && now >= npc.terminalExpiresNanos)
                {
                    unregister(npc);
                    ownerGuidToCanonical.remove(ownerKey(npc.ownerPlayerId, npc.ownerLocalGuid));
                }

                continue;
            }

            if (isQuestFoe(npc) && npc.ownerPlayerId != 0
                    && !WitcherServer.questVisibleTo(
                            WitcherServer.sessionByPlayerId(npc.ownerPlayerId), npc))
            {
                int ownerId = npc.ownerPlayerId;
                int ownerGuid = npc.ownerLocalGuid;

                if (releaseOwned(ownerId, ownerGuid, now))
                {
                    WitcherServer.dbg("QFOE %s owner %s left co-op roster\n",
                            npc.typeCode,
                            WitcherServer.describePlayerId(ownerId));
                }

                continue;
            }

            if (npc.ownerPlayerId == 0
                    && npc.releasedByPlayerId != 0
                    && (now - npc.releasedAtNanos) > RELEASED_ORPHAN_RETENTION_NANOS)
            {
                unregister(npc);

                WitcherServer.dbg("NPC %s EVICTED (released %.0fs ago, unclaimed)\n",
                        describeNpc(npc),
                        (now - npc.releasedAtNanos) / 1_000_000_000.0);
                continue;
            }

            if (npc.ownerPlayerId != 0 && ownerOnline.contains(npc.ownerPlayerId))
            {
                if ((now - npc.lastUpdateNanos) > NPC_STALE_NANOS)
                {
                    unregister(npc);
                    ownerGuidToCanonical.remove(ownerKey(npc.ownerPlayerId, npc.ownerLocalGuid));

                    WitcherServer.dbg("NPC %s EVICTED (online owner silent %.0fs)\n",
                            describeNpc(npc),
                            (now - npc.lastUpdateNanos) / 1_000_000_000.0);
                }

                continue;
            }

            if ((now - npc.lastUpdateNanos) > NPC_ORPHAN_RETENTION_NANOS)
            {
                unregister(npc);
                ownerGuidToCanonical.remove(ownerKey(npc.ownerPlayerId, npc.ownerLocalGuid));

                WitcherServer.dbg("NPC %s EVICTED (orphaned %.0fs, owner offline)\n",
                        describeNpc(npc),
                        (now - npc.lastUpdateNanos) / 1_000_000_000.0);
            }
        }
    }

    public static void notePendingDamage(Npc npc, int permille, int attackerPlayerId, long now)
    {
        if (npc.pendingDamagePermille == 0)
        {
            npc.pendingDamageSince = now;
        }

        npc.pendingDamagePermille = Math.min(1000, npc.pendingDamagePermille + permille);
        npc.pendingDamageAttackerId = attackerPlayerId;
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
                npc.hpPermille = 1;

                if (!npc.killOrderPending)
                {
                    npc.killOrderPending = true;
                    killOrdersPending.incrementAndGet();
                }
            }
        }
    }

    public static boolean isDeadAuthoritative(Npc npc)
    {
        return !npc.alive;
    }

    public static List<Npc> pendingKillOrders(int ownerPlayerId, long now)
    {
        if (killOrdersPending.get() <= 0)
        {
            return EMPTY_NPCS;
        }

        List<Npc> orders = new ArrayList<>();

        for (Npc npc : npcs.values())
        {
            if (npc.killOrderPending
                    && npc.ownerPlayerId == ownerPlayerId
                    && npc.ownerLocalGuid != 0
                    && (npc.lastKillOrderSendNanos == 0L
                        || (now - npc.lastKillOrderSendNanos) >= QUEST_KILL_ORDER_RETRY_NANOS))
            {
                orders.add(npc);
            }
        }

        return orders;
    }

    public static void clearKillOrder(Npc npc)
    {
        if (npc.killOrderPending)
        {
            npc.killOrderPending = false;
            killOrdersPending.decrementAndGet();
        }

        npc.lastKillOrderSendNanos = 0L;
        npc.killOrderSends = 0;
    }

    public static void markKillOrderSent(Npc npc, long now)
    {
        if (npc != null && npc.killOrderPending)
        {
            npc.lastKillOrderSendNanos = now;
            npc.killOrderSends += 1;
        }
    }

    public static List<Npc> pendingDeaths(long now)
    {
        if (lastDeathNanos == 0L)
        {
            return EMPTY_NPCS;
        }

        List<Npc> pending = new ArrayList<>();

        for (Npc npc : npcs.values())
        {
            if (npc.alive)
            {
                continue;
            }

            if (npc.terminalRecipientsInitialized && npc.terminalPending.isEmpty())
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

    public static boolean requestDeathReplay(Npc npc, int playerId, long now)
    {
        if (npc == null || npc.alive || playerId <= 0)
        {
            return false;
        }

        if (!isQuestFoe(npc) && npc.terminalExpiresNanos > 0L && now >= npc.terminalExpiresNanos)
        {
            return false;
        }

        if (npc.terminalAcked.contains(playerId))
        {
            return false;
        }

        npc.terminalPending.add(playerId);
        npc.deathSends.remove(playerId);
        npc.terminalLastSends.remove(playerId);
        npc.lastDeathSendNanos = 0L;
        lastDeathNanos = now;
        return true;
    }

    public static boolean requestTerminalRebind(Npc npc, int playerId, int localGuid, long now)
    {
        if (npc == null || npc.alive || playerId <= 0)
        {
            return false;
        }

        Binding binding = bindingByGuid(playerId, localGuid);
        if (binding == null || binding.canonicalId != npc.npcId)
        {
            return false;
        }

        npc.terminalAcked.remove(playerId);
        npc.terminalPending.add(playerId);
        npc.deathSends.remove(playerId);
        npc.terminalLastSends.remove(playerId);
        npc.lastDeathSendNanos = 0L;
        lastDeathNanos = now;
        return true;
    }

    public static int requestDeathReplayForKnown(PlayerSession session, long now)
    {
        if (session == null)
        {
            return 0;
        }

        int count = 0;

        for (Npc npc : npcs.values())
        {
            if (npc.alive
                    || !session.knownNpcs.contains(npc.boxedId)
                    || (isQuestFoe(npc) && !WitcherServer.questVisibleTo(session, npc)))
            {
                continue;
            }

            if (requestDeathReplay(npc, session.playerId, now))
            {
                count++;
            }
        }

        return count;
    }

    public static void initializeTerminalRecipients(Npc npc, List<PlayerSession> sessions, long now)
    {
        if (npc == null || npc.alive || npc.terminalRecipientsInitialized)
        {
            return;
        }

        synchronized (npc)
        {
            if (npc.terminalRecipientsInitialized)
            {
                return;
            }

            for (PlayerSession session : sessions)
            {
                if (session.playerId == npc.ownerPlayerId
                        || !session.knownNpcs.contains(npc.boxedId)
                        || !sharesSyncGroup(session, npc)
                        || npc.terminalAcked.contains(session.playerId))
                {
                    continue;
                }

                npc.terminalPending.add(session.playerId);
            }

            npc.terminalRecipientsInitialized = true;
            npc.lastDeathSendNanos = 0L;
            lastDeathNanos = now;
        }
    }

    public static boolean acknowledgeTerminal(int playerId, int canonicalId, int revision)
    {
        Npc npc = npcs.get(canonicalId);

        if (npc == null || npc.alive || revision != npc.terminalRevision
                || !npc.terminalPending.remove(playerId))
        {
            return false;
        }

        npc.terminalAcked.add(playerId);
        npc.deathSends.remove(playerId);
        npc.terminalLastSends.remove(playerId);
        return true;
    }

    public static void forgetTerminalRecipient(int canonicalId, int playerId)
    {
        forgetTerminalRecipient(npcs.get(canonicalId), playerId);
    }

    private static void forgetTerminalRecipient(Npc npc, int playerId)
    {
        if (npc == null)
        {
            return;
        }

        npc.terminalPending.remove(playerId);
        npc.terminalAcked.remove(playerId);
        npc.deathSends.remove(playerId);
        npc.terminalLastSends.remove(playerId);
    }

    private static void resetTerminalDelivery(Npc npc, long now)
    {
        long expires = npc.terminalExpiresNanos;
        clearTerminalDelivery(npc);
        npc.terminalExpiresNanos = expires > now
                ? expires
                : now + TERMINAL_TOMBSTONE_MAX_NANOS;
    }

    private static void clearTerminalDelivery(Npc npc)
    {
        npc.deathSends.clear();
        npc.terminalLastSends.clear();
        npc.terminalPending.clear();
        npc.terminalAcked.clear();
        npc.terminalRecipientsInitialized = false;
        npc.terminalExpiresNanos = 0L;
        npc.lastDeathSendNanos = 0L;
    }

    public static int removeQuestParty(int partyId)
    {
        if (partyId <= 0)
        {
            return 0;
        }

        int removed = 0;

        for (Npc npc : npcs.values())
        {
            if (!isQuestFoe(npc) || npc.questPartyId != partyId)
            {
                continue;
            }

            ownerGuidToCanonical.remove(ownerKey(npc.ownerPlayerId, npc.ownerLocalGuid));
            unregister(npc);
            removed++;
        }

        return removed;
    }

    public static List<Npc> visibleTo(PlayerSession session, double enterSquared, double leaveSquared)
    {
        List<Npc> visible = new ArrayList<>();

        if (!session.hasPosition)
        {
            return visible;
        }

        double radius = Math.sqrt(Math.max(enterSquared, leaveSquared));
        for (Npc npc : nearby(session.area, session.posX, session.posY, radius))
        {
            if (npc.ownerPlayerId == session.playerId)
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
            double squared = dx * dx + dy * dy + dz * dz;

            double limit = session.knownNpcs.contains(npc.boxedId) ? leaveSquared : enterSquared;

            if (squared > limit)
            {
                continue;
            }

            if (!sharesSyncGroup(session, npc))
            {
                continue;
            }

            visible.add(npc);
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

        if (isQuestFoe(npc))
        {
            return WitcherServer.questVisibleTo(viewer, npc);
        }

        referenceId = (npc.ownerPlayerId != 0) ? npc.ownerPlayerId : npc.releasedByPlayerId;

        if (referenceId == 0 || referenceId == viewer.playerId)
        {
            return true;
        }

        reference = WitcherServer.sessionByPlayerId(referenceId);

        if (reference == null)
        {
            return sharesReservation(viewer, npc);
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

    public static boolean recomputeScaling(List<PlayerSession> sessions, double radiusSquared)
    {
        boolean changed = false;

        for (Npc npc : npcs.values())
        {
            if (!npc.alive)
            {
                continue;
            }

            int count = 0;

            for (PlayerSession session : SpatialIndex.query(
                    npc.area, npc.x, npc.y, Math.sqrt(radiusSquared)))
            {
                if (!session.hasPosition || session.area != npc.area)
                {
                    continue;
                }

                double dx = npc.x - session.posX;
                double dy = npc.y - session.posY;
                double dz = npc.z - session.posZ;

                if ((dx * dx + dy * dy + dz * dz) > radiusSquared)
                {
                    continue;
                }

                if (!sharesSyncGroup(session, npc))
                {
                    continue;
                }

                count++;
            }

            if (count < 1)
            {
                count = 1;
            }

            final int milli = NpcScaling.scaleMilliFor(count);

            if (npc.scaleMilli != milli)
            {
                changed = true;
            }

            npc.scalePlayerCount = count;
            npc.scaleMilli = milli;
        }

        return changed;
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

    public static int clearTargetsForPlayer(int playerId)
    {
        int cleared = 0;

        if (playerId <= 0)
        {
            return cleared;
        }

        for (Npc npc : npcs.values())
        {
            if (npc.targetPlayerId != playerId)
            {
                continue;
            }

            npc.targetPlayerId = 0;
            cleared++;
        }

        return cleared;
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

            if (now < npc.nextHandoverEvalNanos)
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

            for (PlayerSession session : SpatialIndex.query(
                    npc.area, npc.x, npc.y, Math.sqrt(radiusSquared)))
            {
                if (session.playerId == npc.ownerPlayerId || !session.hasPosition)
                {
                    continue;
                }

                if (session.paused)
                {
                    continue;
                }

                if (session.area != npc.area)
                {
                    continue;
                }

                double dx = npc.x - session.posX;
                double dy = npc.y - session.posY;
                double dz = npc.z - session.posZ;
                double squared = (dx * dx) + (dy * dy) + (dz * dz);

                if (!session.knownNpcs.contains(npc.boxedId)
                        || !authorityEligible(session.playerId, npc.npcId))
                {
                    continue;
                }

                if (!sharesSyncGroup(session, npc))
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
                npc.nextHandoverEvalNanos = now + HANDOVER_IDLE_EVAL_NANOS;
                continue;
            }

            if (ownerHealthy)
            {
                int ownerLatency = PlayerSession.UNKNOWN_RTT_MS;
                boolean ownerInHitRange = false;
                PlayerSession owner = WitcherServer.sessionByPlayerId(npc.ownerPlayerId);

                if (owner != null)
                {
                    ownerLatency = owner.rttMs;

                    if (owner.hasPosition && owner.area == npc.area)
                    {
                        double odx = npc.x - owner.posX;
                        double ody = npc.y - owner.posY;
                        double odz = npc.z - owner.posZ;

                        ownerInHitRange = ((odx * odx) + (ody * ody) + (odz * odz)) <= HIT_RANGE_SQUARED;
                    }
                }

                boolean proximityWin = bestInHitRange && !ownerInHitRange;

                if (!proximityWin && ownerInHitRange && !bestInHitRange)
                {
                    continue;
                }

                if (!proximityWin)
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

            orders.add(0, new int[] { npc.npcId, best.playerId });
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
        npc.lifecycle = LIFECYCLE_DORMANT;
        npc.authorityRevision += 1;
        npc.lastFastSnapshotMs = 0L;
        npc.lastFastSequence = 0;
        npc.handoverTarget = 0;
        npc.handoverSentNanos = 0L;
        npc.handoverAttempts = 0;
        npc.handoverBlockedUntil = isQuestFoe(npc)
                ? 0L
                : now + HANDOVER_RELEASE_GRACE_NANOS;
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
        npc.lifecycle = LIFECYCLE_ACTIVE;
        captureSyncGroup(npc, playerId);
        npc.handoverTarget = 0;
        npc.handoverAttempts = 0;
        npc.handoverBlockedUntil = 0L;
        npc.lastUpdateNanos = now;
        npc.lastSnapshotMs = 0L;
        npc.lastFastSnapshotMs = 0L;
        npc.lastFastSequence = 0;
        npc.authorityRevision += 1;

        ownerGuidToCanonical.put(ownerKey(playerId, localGuid), npc.npcId);
        bindObservation(playerId, localGuid, npc.npcId, BINDING_NATIVE, true, now);

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
