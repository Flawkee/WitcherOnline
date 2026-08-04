class r_TrackedNpc
{
    public var npcId      : int;
    public var actor      : CNewNPC;
    public var typeCode   : string;
    public var appearance : name;
    public var lastSeenAt : float;
    public var lastHp     : int;
    public var lastTargetId : int;
    public var offerLocalCount : int;
    public var actionSeq  : int;
    public var actionCode : int;
    public var actionType : int;
    public var actionDir  : int;
    public var lastAttackType : int;
    public var lastPerform : int;
    public var baseMaxHealth : float;
    public var appliedScale : int;
    public var questTag   : string;
    public var appearanceText : string;
    public var isMonster  : bool;
    public var cachedForcedTarget : int;
    public var terminalState : int;
    public var lastAttackerId : int;

    default questTag = "";
    default appearanceText = "";
    default isMonster = false;
    default cachedForcedTarget = 0;
    default terminalState = 0;
    default lastAttackerId = 0;
    default baseMaxHealth = -1.0;
    default appliedScale = 1000;
    default lastAttackType = -1;
    default lastPerform = -1;
    default lastHp = -1;
    default lastTargetId = 0;
    default offerLocalCount = 1;
    default actionSeq = 0;
    default actionCode = 0;
    default actionType = 0;
    default actionDir = 0;
}

class r_Replica
{
    public var canonicalId  : int;
    public var localGuid    : int;
    public var actor        : CNewNPC;
    public var appearance   : name;
    public var typeCode     : string;
    public var aiForceId    : int;
    public var appliedTarget : int;
    public var lastLocalHp  : int;
    public var lastPredictAt : float;
    public var nextDriveAt  : float;
    public var prepared     : bool;
    public var lastActionSeq : int;
    public var lastActionAt  : float;
    public var pendingActionAt : float;
    public var pendingType   : int;
    public var pendingDir    : int;
    public var baseMaxHealth : float;
    public var appliedScale  : int;
    public var appliedHostile : int;
    public var authorityHostile : int;
    public var questTag      : string;
    public var boundLocal    : bool;
    public var synthetic     : bool;
    public var hasTicket     : bool;
    public var authorityReady : bool;
    public var questOriginalImmortality : int;
    public var questOriginalAttackable : bool;
    public var questOriginalHitAnim : bool;
    public var questHealthProtectionApplied : bool;
    public var terminalState : int;
    public var terminalRevision : int;
    public var terminalAttackerId : int;
    public var terminalAppliedRevision : int;
    public var terminalVerifyAt : float;
    public var terminalAttempts : int;

    default questTag = "";
    default boundLocal = false;
    default synthetic = false;
    default hasTicket = false;
    default authorityReady = false;
    default questOriginalImmortality = -1;
    default questOriginalAttackable = false;
    default questOriginalHitAnim = false;
    default questHealthProtectionApplied = false;
    default terminalState = 0;
    default terminalRevision = 0;
    default terminalAttackerId = 0;
    default terminalAppliedRevision = 0;
    default terminalVerifyAt = 0.0;
    default terminalAttempts = 0;
    default baseMaxHealth = -1.0;
    default appliedScale = 1000;
    default appliedHostile = -1;
    default authorityHostile = -1;
    default lastActionSeq = -1;
    default lastActionAt = 0.0;
    default pendingActionAt = -1.0;
    default pendingType = 0;
    default pendingDir = 0;
    default aiForceId = -1;
    default appliedTarget = -1;
    default lastLocalHp = -1;
    default lastPredictAt = 0.0;
    default nextDriveAt = 0.0;
    default prepared = false;
}

class r_NpcSync
{
    private var enabled       : bool;
    private var debugLogging  : bool;
    private var suspended     : bool;
    private var gateOpen      : bool;
    private var gateEverSet   : bool;
    private var gateAwaySince : float;
    private var gateFlushUntil : float;
    private var nextGateAt    : float;

    private var tracked       : array<r_TrackedNpc>;
    private var replicas      : array<r_Replica>;

    private var rejectedGuids : array<int>;
    private var rejectedAt    : array<float>;

    private var pathCache      : MP_SU_HashMap;
    private var pathCacheCount : int;

    private var aggro         : r_NpcAggro;
    private var actions       : r_NpcActions;

    private var lastTickAt    : float;
    private var nextScanAt    : float;
    private var nextPushAt    : float;
    private var nextAggroAt   : float;
    private var nextDebugAt   : float;
    private var nextDriveTickAt : float;
    private var nextRejectPruneAt : float;
    private var nextModeCheckAt : float;
    private var offerWindowAt : float;
    private var offerCount    : int;
    private var nextSkipLogAt : float;
    private var nextScaleAt   : float;
    private var lastScaleGen  : int;
    private var scaleSkipReason : string;
    private var cachedIsolated : bool;
    private var reportedPartyId : int;
    private var scanEntities  : array<CGameplayEntity>;

    private var statRegistered : int;
    private var statSpawned    : int;
    private var statDespawned  : int;
    private var statKilled     : int;
    private var statPrefiltered : int;
    private var statDropped    : int;
    private var statDroppedUntracked : int;
    private var statTargets    : int;
    private var statHitsSent   : int;
    private var statHitsApplied : int;
    private var statAcks       : int;
    private var statRejected   : int;
    private var statHealthSync : int;
    private var statTeleports  : int;
    private var statNoTemplate : int;
    private var statTemplateResolves : int;
    private var statPromoted   : int;
    private var statLocalDamage : int;
    private var statSuspends   : int;
    private var statActionSent : int;
    private var statActionApplied : int;
    private var statActionForced : int;
    private var statActionRejected : int;
    private var statActionUnknown : int;
    private var statActionProbes : int;
    private var statActionSuppressed : int;
    private var statActionAllowed : int;

    private var actionLogging : bool;
    private var actionForceMode : int;

    private var reportedAway : bool;

    default reportedAway = false;

    private var reportedSyncMode : int;
    default reportedSyncMode = -1;

    default actionLogging = false;
    default actionForceMode = 1;

    default enabled = true;
    default debugLogging = false;
    default suspended = false;
    default gateOpen = false;
    default gateEverSet = false;
    default gateAwaySince = 0.0;
    default gateFlushUntil = 0.0;
    default nextGateAt = 0.0;
    default lastTickAt = 0.0;
    default nextScanAt = 0.0;
    default nextPushAt = 0.0;
    default nextAggroAt = 0.0;
    default nextDebugAt = 0.0;
    default nextDriveTickAt = 0.0;
    default nextRejectPruneAt = 0.0;
    default nextModeCheckAt = 0.0;
    default offerWindowAt = 0.0;
    default offerCount = 0;
    default nextSkipLogAt = 0.0;
    default nextScaleAt = 0.0;
    default lastScaleGen = -1;
    default cachedIsolated = false;
    default reportedPartyId = -1;

    private var SCAN_RADIUS    : float;
    private var KEEP_RADIUS    : float;
    private var TRACK_GRACE    : float;
    private var PUSH_INTERVAL  : float;
    private var SCAN_INTERVAL  : float;
    private var DRIVE_INTERVAL : float;
    private var AGGRO_INTERVAL : float;
    private var SCALE_INTERVAL : float;
    private var DEBUG_INTERVAL : float;
    private var HARD_SNAP      : float;
    private var QUEST_HARD_SNAP : float;
    private var FREE_RADIUS    : float;
    private var QUEST_FREE_RADIUS : float;
    private var ACTION_FREE_RADIUS : float;
    private var ACTION_FREE_WINDOW : float;
    private var ACTION_COMMAND_WINDOW : float;
    private var SLIDE_WINDOW   : float;
    private var QUEST_SLIDE_WINDOW : float;
    private var HEALTH_TOLERANCE : float;
    private var PREDICT_GRACE  : float;
    private var RESERVATION_RADIUS : float;
    private var DAMAGE_CREDIT_RANGE : float;
    private var HIT_REPORT_RANGE : float;
    private var REJECT_MEMORY  : float;
    private var PEER_RADIUS_ON : float;
    private var PEER_RADIUS_OFF : float;
    private var PEER_CLOSE_DELAY : float;
    private var PEER_STALE     : float;
    private var GATE_INTERVAL  : float;
    private var GATE_FLUSH     : float;
    private var OFFERS_PER_SECOND : int;
    private var MAX_TRACKED    : int;
    private var MAX_REPLICAS   : int;
    private var PATH_CACHE_MAX : int;

    default SCAN_RADIUS = 80.0;
    default KEEP_RADIUS = 85.0;
    default TRACK_GRACE = 3.0;
    default PUSH_INTERVAL = 0.025;
    default SCAN_INTERVAL = 0.25;
    default DRIVE_INTERVAL = 0.025;
    default AGGRO_INTERVAL = 0.4;
    default SCALE_INTERVAL = 0.5;
    default DEBUG_INTERVAL = 2.0;
    default HARD_SNAP = 15.0;
    default QUEST_HARD_SNAP = 45.0;
    default FREE_RADIUS = 2.0;
    default QUEST_FREE_RADIUS = 0.75;
    default ACTION_FREE_RADIUS = 6.0;
    default ACTION_FREE_WINDOW = 1.5;
    default ACTION_COMMAND_WINDOW = 1.2;
    default SLIDE_WINDOW = 0.03;
    default QUEST_SLIDE_WINDOW = 0.12;
    default HEALTH_TOLERANCE = 0.05;
    default PREDICT_GRACE = 1.5;
    default RESERVATION_RADIUS = 30.0;
    default DAMAGE_CREDIT_RANGE = 25.0;
    default HIT_REPORT_RANGE = 12.0;
    default REJECT_MEMORY = 60.0;
    default PEER_RADIUS_ON = 95.0;
    default PEER_RADIUS_OFF = 105.0;
    default PEER_CLOSE_DELAY = 3.0;
    default PEER_STALE = 5.0;
    default GATE_INTERVAL = 0.25;
    default GATE_FLUSH = 1.5;
    default OFFERS_PER_SECOND = 12;
    default MAX_TRACKED = 250;
    default MAX_REPLICAS = 250;
    default PATH_CACHE_MAX = 4096;

    public function isEnabled() : bool
    {
        return enabled;
    }

    public function setEnabled(value : bool)
    {
        if(enabled == value)
        {
            return;
        }

        enabled = value;

        if(!enabled)
        {
            releaseAll();
        }
    }

    public function setDebugLogging(value : bool)
    {
        debugLogging = value;
        nextDebugAt = 0.0;

        getAggro().setDebugLogging(value);

        WO_Note("[npc_debug] logging=" + value
            + " (tags: npc_stats npc_net npc_spawn npc_kill npc_hit npc_target npc_offer npc_handover npc_suspend)");
    }

    private function dbg(tag : string, line : string)
    {
        if(!debugLogging)
        {
            return;
        }

        WO_Note("[" + tag + "] " + line);
    }

    public function getAggro() : r_NpcAggro
    {
        if(!aggro)
        {
            aggro = new r_NpcAggro in this;
        }

        return aggro;
    }

    private function getActions() : r_NpcActions
    {
        if(!actions)
        {
            actions = new r_NpcActions in this;
        }

        return actions;
    }

    public function setActionLogging(value : bool)
    {
        actionLogging = value;

        WO_Note("[npc_action] logging=" + value
            + " forceMode=" + actionForceMode
            + " tableSize=" + getActions().count());
    }

    public function setActionForceMode(value : int)
    {
        if(value < 0)
        {
            value = 0;
        }

        if(value > 2)
        {
            value = 2;
        }

        actionForceMode = value;

        WO_Note("[npc_action] forceMode=" + actionForceMode
            + " (0=never force, 1=force after reject, 2=always force)");
    }

    private function dbgAction(line : string)
    {
        if(!actionLogging && !debugLogging)
        {
            return;
        }

        WO_Note("[npc_action] " + line);
    }

    public function shouldSuppressAction(actor : CNewNPC) : bool
    {
        if(!actor)
        {
            return false;
        }

        return actor.HasTag('WOReplica');
    }

    public function probeAction(actor : CNewNPC, source : name)
    {
        var index : int;

        if(!actor || (!actionLogging && !debugLogging))
        {
            return;
        }

        index = findTrackedByActor(actor);

        statActionProbes += 1;

        WO_Note("[npc_probe] source=" + NameToString(source)
            + " owned=" + (index >= 0)
            + " replica=" + actor.HasTag('WOReplica')
            + " app=" + NameToString(actor.GetAppearance())
            + " attackType=" + (int)actor.GetBehaviorVariable('AttackType')
            + " performAttack=" + actor.GetBehaviorVariable('PerformAttack')
            + " dir=" + (int)actor.GetBehaviorVariable('targetDirection'));
    }

    private function pollOwnedAttackVars(now : float)
    {
        var i : int;
        var attackType : int;
        var perform : int;
        var npc : CNewNPC;

        if(!actionLogging && !debugLogging)
        {
            return;
        }

        for(i = 0; i < tracked.Size(); i += 1)
        {
            npc = tracked[i].actor;

            if(!npc)
            {
                continue;
            }

            attackType = (int)npc.GetBehaviorVariable('AttackType');
            perform = (int)npc.GetBehaviorVariable('PerformAttack');

            if(attackType == tracked[i].lastAttackType && perform == tracked[i].lastPerform)
            {
                continue;
            }

            tracked[i].lastAttackType = attackType;
            tracked[i].lastPerform = perform;

            statActionProbes += 1;

            WO_Note("[npc_probe] var guid=" + tracked[i].npcId
                + " app=" + NameToString(tracked[i].appearance)
                + " attackType=" + attackType
                + " performAttack=" + perform
                + " alive=" + npc.IsAlive());
        }
    }

    public function reportOwnedAction(actor : CNewNPC, eventName : name)
    {
        var index : int;
        var code : int;

        if(!actor || !enabled || suspended)
        {
            return;
        }

        index = findTrackedByActor(actor);

        if(index < 0)
        {
            return;
        }

        code = getActions().codeFor(eventName);

        tracked[index].actionSeq = (tracked[index].actionSeq + 1) & 63;
        tracked[index].actionCode = code;
        tracked[index].actionType = (int)actor.GetBehaviorVariable('AttackType');
        tracked[index].actionDir = (int)actor.GetBehaviorVariable('targetDirection');

        statActionSent += 1;

        if(code == 8191)
        {
            statActionUnknown += 1;

            WO_Note("[npc_action] UNKNOWN event=" + NameToString(eventName)
                + " app=" + NameToString(tracked[index].appearance)
                + " code=" + tracked[index].typeCode);
        }

        if(actionLogging || debugLogging)
        {
            dbgAction("send guid=" + tracked[index].npcId
                + " event=" + NameToString(eventName)
                + " code=" + code
                + " type=" + tracked[index].actionType
                + " dir=" + tracked[index].actionDir
                + " seq=" + tracked[index].actionSeq
                + " app=" + tracked[index].appearanceText);
        }
    }

    private function applyReplicaAction(record : r_Replica, npc : CNewNPC, flags : int)
    {
        var seq : int;
        var code : int;
        var attackType : int;
        var dir : int;
        var eventName : name;
        var raised : bool;
        var forced : bool;

        seq = (flags / 8) & 63;

        if(seq == record.lastActionSeq)
        {
            return;
        }

        if(record.lastActionSeq < 0)
        {
            record.lastActionSeq = seq;
            return;
        }

        record.lastActionSeq = seq;

        code = (flags / 262144) & 8191;
        attackType = (flags / 512) & 63;
        dir = (flags / 32768) & 7;

        eventName = getActions().nameFor(code);

        record.pendingActionAt = theGame.GetEngineTimeAsSeconds();
        record.pendingType = attackType;
        record.pendingDir = dir;

        raised = false;
        forced = false;

        if(actionForceMode == 2)
        {
            if(attackType > 0)
            {
                npc.SetBehaviorVariable('AttackType', (float)attackType, true);
            }

            raised = npc.RaiseForceEvent(eventName);
            forced = true;

            if(raised)
            {
                statActionForced += 1;
                record.pendingActionAt = -1.0;
            }
        }

        statActionApplied += 1;
        record.lastActionAt = record.pendingActionAt;

        if(actionLogging || debugLogging)
        {
            dbgAction("cmd canonical=" + record.canonicalId
                + " event=" + NameToString(eventName)
                + " code=" + code
                + " type=" + attackType
                + " dir=" + dir
                + " seq=" + seq
                + " forcedRaise=" + raised
                + " app=" + NameToString(record.appearance));
        }
    }

    public function gateAction(actor : CNewNPC) : bool
    {
        var index : int;
        var now : float;

        if(!actor || !enabled)
        {
            return true;
        }

        if(!actor.HasTag('WOReplica'))
        {
            reportOwnedAction(actor, 'Attack');
            return true;
        }

        index = findReplicaByActor(actor);

        if(index < 0)
        {
            return true;
        }

        now = theGame.GetEngineTimeAsSeconds();

        if(replicas[index].pendingActionAt < 0.0
            || (now - replicas[index].pendingActionAt) > ACTION_COMMAND_WINDOW)
        {
            statActionSuppressed += 1;

            if(actionLogging || debugLogging)
            {
                dbgAction("suppress canonical=" + replicas[index].canonicalId
                    + " app=" + NameToString(replicas[index].appearance));
            }

            return false;
        }

        if(replicas[index].pendingType > 0)
        {
            actor.SetBehaviorVariable('AttackType', (float)replicas[index].pendingType, true);
        }

        if(replicas[index].pendingDir > 0)
        {
            actor.SetBehaviorVariable('targetDirection', (float)replicas[index].pendingDir, true);
        }

        replicas[index].pendingActionAt = -1.0;
        replicas[index].lastActionAt = now;
        statActionAllowed += 1;

        if(actionLogging || debugLogging)
        {
            dbgAction("allow canonical=" + replicas[index].canonicalId
                + " type=" + replicas[index].pendingType
                + " app=" + NameToString(replicas[index].appearance));
        }

        return true;
    }

    private function findReplicaByActor(actor : CNewNPC) : int
    {
        var i : int;

        for(i = 0; i < replicas.Size(); i += 1)
        {
            if(replicas[i].actor == actor)
            {
                return i;
            }
        }

        return -1;
    }

    public function releaseAll()
    {
        var i : int;

        for(i = 0; i < replicas.Size(); i += 1)
        {
            destroyReplica(replicas[i]);
        }

        for(i = 0; i < tracked.Size(); i += 1)
        {
            releaseTrackedState(tracked[i]);
        }

        replicas.Clear();
        tracked.Clear();
        rejectedGuids.Clear();
        rejectedAt.Clear();

        if(pathCache)
        {
            pathCache.init();
            pathCacheCount = 0;
        }

        getAggro().reset();
    }

    private function currentArea() : int
    {
        return (int)theGame.GetCommonMapManager().GetCurrentArea();
    }

    private function resetCadence(now : float)
    {
        var i : int;

        nextScanAt = 0.0;
        nextPushAt = 0.0;
        nextGateAt = 0.0;
        nextAggroAt = 0.0;
        nextScaleAt = 0.0;
        nextDriveTickAt = 0.0;
        nextRejectPruneAt = 0.0;
        nextModeCheckAt = 0.0;
        nextDebugAt = 0.0;
        nextSkipLogAt = 0.0;
        offerWindowAt = 0.0;
        offerCount = 0;
        gateAwaySince = 0.0;
        gateFlushUntil = 0.0;

        for(i = 0; i < replicas.Size(); i += 1)
        {
            replicas[i].nextDriveAt = 0.0;
            replicas[i].lastPredictAt = 0.0;
            replicas[i].lastActionAt = 0.0;
            replicas[i].pendingActionAt = -1.0;
        }

        for(i = 0; i < tracked.Size(); i += 1)
        {
            tracked[i].lastSeenAt = now;
        }

        rejectedGuids.Clear();
        rejectedAt.Clear();

        WO_Note("[npc_sync] engine clock jumped, cadence reset");
    }

    public function update()
    {
        var classifier : r_EntityClassifier;
        var coop : bool;
        var now : float;

        if(!enabled || !thePlayer)
        {
            return;
        }

        now = theGame.GetEngineTimeAsSeconds();

        if(lastTickAt != 0.0 && AbsF(now - lastTickAt) > 5.0)
        {
            resetCadence(now);
        }

        lastTickAt = now;

        updateSyncMode(now);

        if(updateSuspension(now))
        {
            return;
        }

        updateSyncGate(now);
        verifyQuestTerminals(now);

        if(now >= nextScanAt)
        {
            nextScanAt = now + SCAN_INTERVAL;
            coop = coopActive();

            if(gateOpen || coop)
            {
                classifier = theGame.r_getMultiplayerClient().getEntityClassifier();
                classifier.beginScanTick(now);

                scanEntities.Clear();
                FindGameplayEntitiesInSphere(scanEntities, thePlayer.GetWorldPosition(), KEEP_RADIUS, 512,,,,'CNewNPC');
            }
            else if(scanEntities.Size() > 0)
            {
                scanEntities.Clear();
            }

            if(gateOpen)
            {
                scanOwned(now);
            }

            scanQuestEnemies(now);
            updateQuestBindings(now);
        }

        if(now >= nextAggroAt)
        {
            nextAggroAt = now + AGGRO_INTERVAL;
            getAggro().update(now, tracked);
        }

        if(now >= nextPushAt)
        {
            nextPushAt = now + PUSH_INTERVAL;

            if(shouldPushOwned(now))
            {
                pushOwned(now);
            }
        }

        if(now >= nextScaleAt)
        {
            nextScaleAt = now + SCALE_INTERVAL;
            updateHealthScaling();
        }

        applyDrops(now);
        applyKillOrders();

        if(now >= nextDriveTickAt)
        {
            nextDriveTickAt = now + DRIVE_INTERVAL;
            driveReplicas(now);
        }

        applyInboundHits(now);
        applyAcks();

        if(now >= nextRejectPruneAt)
        {
            nextRejectPruneAt = now + 1.0;
            pruneRejected(now);
        }

        if(debugLogging && now >= nextDebugAt)
        {
            nextDebugAt = now + DEBUG_INTERVAL;
            writeDebug(now);
        }

    }

    private function updateSuspension(now : float) : bool
    {
        var shouldSuspend : bool;
        var awayNow : bool;
        var i : int;

        awayNow = theGame.IsPaused() || !theGame.IsActive();

        if(awayNow != reportedAway)
        {
            reportedAway = awayNow;
            WO_SetPaused(awayNow);
        }

        shouldSuspend = !thePlayer.IsAlive() || theGame.IsBlackscreen()
            || theGame.IsLoadingScreenVideoPlaying() || awayNow;

        if(shouldSuspend == suspended)
        {
            return suspended;
        }

        suspended = shouldSuspend;
        WO_NpcSuspend(suspended);

        if(suspended)
        {
            for(i = 0; i < tracked.Size(); i += 1)
            {
                releaseTrackedState(tracked[i]);
            }

            tracked.Clear();
            statSuspends += 1;

            if(pathCache)
            {
                pathCache.init();
                pathCacheCount = 0;
            }

            rejectedGuids.Clear();
            rejectedAt.Clear();

            dbg("npc_suspend", "suspended: ownership released, "
                + replicas.Size() + " replicas kept alive");
        }
        else
        {
            nextScanAt = 0.0;
            nextPushAt = 0.0;
            nextGateAt = 0.0;

            dbg("npc_suspend", "resumed");
        }

        return suspended;
    }

    private function hasSyncPeer() : bool
    {
        var client : r_MultiplayerClient;
        var list : array<r_RemotePlayer>;
        var peer : r_RemotePlayer;
        var myPos : Vector;
        var myArea : int;
        var now : float;
        var limit : float;
        var i : int;

        client = theGame.r_getMultiplayerClient();

        if(cachedIsolated)
        {
            return false;
        }

        list = client.getPlayers();

        if(list.Size() == 0)
        {
            return false;
        }

        myPos = thePlayer.GetWorldPosition();
        myArea = currentArea();
        now = theGame.GetEngineTimeAsSeconds();

        if(gateOpen)
        {
            limit = PEER_RADIUS_OFF;
        }
        else
        {
            limit = PEER_RADIUS_ON;
        }

        for(i = 0; i < list.Size(); i += 1)
        {
            peer = list[i];

            if(!peer || peer.serverPlayerId <= 0)
            {
                continue;
            }

            if((int)peer.area != myArea)
            {
                continue;
            }

            if((now - peer.lastUpdate) > PEER_STALE)
            {
                continue;
            }

            if(!client.canShareNpcsWith(peer))
            {
                continue;
            }

            if(VecDistance(myPos, peer.pos) <= limit)
            {
                return true;
            }
        }

        return false;
    }

    private function updateSyncGate(now : float)
    {
        var peer : bool;

        if(now < nextGateAt)
        {
            return;
        }

        nextGateAt = now + GATE_INTERVAL;
        peer = hasSyncPeer();

        if(peer)
        {
            gateAwaySince = 0.0;

            if(gateOpen && gateEverSet)
            {
                return;
            }

            gateOpen = true;
            gateEverSet = true;
            nextScanAt = 0.0;
            nextPushAt = 0.0;

            rejectedGuids.Clear();
            rejectedAt.Clear();

            dbg("npc_gate", "open: a peer is within " + PEER_RADIUS_ON + "m");
            return;
        }

        if(!gateOpen)
        {
            if(!gateEverSet)
            {
                gateEverSet = true;
                dbg("npc_gate", "closed: no peer in range");
            }

            return;
        }

        if(gateAwaySince == 0.0)
        {
            gateAwaySince = now;
            return;
        }

        if((now - gateAwaySince) < PEER_CLOSE_DELAY)
        {
            return;
        }

        gateOpen = false;
        gateAwaySince = 0.0;
        gateFlushUntil = now + GATE_FLUSH;

        releaseOwnedForGate();

        dbg("npc_gate", "closed: no peer within " + PEER_RADIUS_OFF + "m");
    }

    private function releaseOwnedForGate()
    {
        var released : int;
        var i : int;

        for(i = tracked.Size() - 1; i >= 0; i -= 1)
        {
            if(tracked[i].questTag == "")
            {
                releaseTrackedState(tracked[i]);
                tracked.Erase(i);
                released += 1;
            }
        }

        rejectedGuids.Clear();
        rejectedAt.Clear();

        if(released > 0)
        {
            dbg("npc_gate", "released " + released + " owned npc(s)");
        }
    }

    private function shouldPushOwned(now : float) : bool
    {
        return gateOpen || now < gateFlushUntil || tracked.Size() > 0;
    }

    private function scheduleOwnedFlush(now : float)
    {
        if(gateFlushUntil < now + GATE_FLUSH)
        {
            gateFlushUntil = now + GATE_FLUSH;
        }
    }

    private function gateLabel() : string
    {
        if(gateOpen)
        {
            return "open";
        }

        return "closed";
    }

    private function isRejected(guid : int) : bool
    {
        var i : int;

        for(i = 0; i < rejectedGuids.Size(); i += 1)
        {
            if(rejectedGuids[i] == guid)
            {
                return true;
            }
        }

        return false;
    }

    private function rememberRejected(guid : int, now : float)
    {
        if(isRejected(guid))
        {
            return;
        }

        rejectedGuids.PushBack(guid);
        rejectedAt.PushBack(now);
    }

    private function pruneRejected(now : float)
    {
        var i : int;

        for(i = rejectedGuids.Size() - 1; i >= 0; i -= 1)
        {
            if(rejectedAt[i] > now || (now - rejectedAt[i]) > REJECT_MEMORY)
            {
                rejectedGuids.Erase(i);
                rejectedAt.Erase(i);
            }
        }
    }

    private function allowOffer(now : float) : bool
    {
        if(now >= offerWindowAt)
        {
            offerWindowAt = now + 1.0;
            offerCount = 0;
        }

        if(offerCount >= OFFERS_PER_SECOND)
        {
            return false;
        }

        offerCount += 1;
        return true;
    }

    private function updateHealthScaling()
    {
        var baseMax : float;
        var applied : int;
        var scale   : int;
        var scaled  : int;
        var seen    : int;
        var i : int;

        scaled = 0;
        seen = 0;

        for(i = 0; i < tracked.Size(); i += 1)
        {
            if(!tracked[i].actor)
            {
                continue;
            }

            baseMax = tracked[i].baseMaxHealth;
            applied = tracked[i].appliedScale;
            scale = WO_NpcScale(tracked[i].npcId);

            if(scale != 1000)
            {
                seen += 1;
            }

            if(scale == applied && baseMax > 0.0)
            {
                if(applied != 1000)
                {
                    scaled += 1;
                }

                continue;
            }

            applyHealthScale(tracked[i].actor, scale, baseMax, applied);

            tracked[i].baseMaxHealth = baseMax;
            tracked[i].appliedScale = applied;

            if(applied != 1000)
            {
                scaled += 1;
            }
        }

        for(i = 0; i < replicas.Size(); i += 1)
        {
            if(!replicas[i].actor)
            {
                continue;
            }

            baseMax = replicas[i].baseMaxHealth;
            applied = replicas[i].appliedScale;
            scale = WO_NpcScale(replicas[i].canonicalId);

            if(scale != 1000)
            {
                seen += 1;
            }

            if(scale == applied && baseMax > 0.0)
            {
                if(applied != 1000)
                {
                    scaled += 1;
                }

                continue;
            }

            applyHealthScale(replicas[i].actor, scale, baseMax, applied);

            replicas[i].baseMaxHealth = baseMax;
            replicas[i].appliedScale = applied;

            if(applied != 1000)
            {
                scaled += 1;
            }
        }

        if(debugLogging)
        {
            WO_Note("[npc_scale] tracked=" + tracked.Size()
                + " replicas=" + replicas.Size()
                + " gotScale=" + seen
                + " applied=" + scaled
                + " skip=" + scaleSkipReason);
        }

        scaleSkipReason = "";
        lastScaleGen = WO_NpcScaleGen();
    }

    private function scaleStatOf(npc : CNewNPC) : EBaseCharacterStats
    {
        if(npc.UsesVitality())
        {
            return BCS_Vitality;
        }

        if(npc.UsesEssence())
        {
            return BCS_Essence;
        }

        return BCS_Undefined;
    }

    private function applyHealthScale(npc : CNewNPC, scaleMilli : int, out baseMax : float, out appliedScale : int)
    {
        var stat       : EBaseCharacterStats;
        var currentMax : float;
        var desiredMax : float;
        var fraction   : float;

        if(scaleMilli <= 0)
        {
            scaleMilli = 1000;
        }

        if(!npc.IsAlive())
        {
            scaleSkipReason = "dead";
            return;
        }

        stat = scaleStatOf(npc);

        if(stat == BCS_Undefined)
        {
            scaleSkipReason = "stat";
            return;
        }

        if(!npc.abilityManager)
        {
            scaleSkipReason = "noAbilityMgr";
            return;
        }

        if(!npc.abilityManager.IsInitialized())
        {
            scaleSkipReason = "abilityMgrNotInit";
            return;
        }

        currentMax = npc.GetMaxHealth();

        if(currentMax <= 0.0)
        {
            scaleSkipReason = "maxHp" + currentMax;
            return;
        }

        if(baseMax <= 0.0)
        {
            baseMax = currentMax / ((float)appliedScale / 1000.0);
        }
        else if(AbsF(currentMax - baseMax) < 0.5)
        {
            appliedScale = 1000;
        }
        else if(AbsF(currentMax - (baseMax * ((float)appliedScale / 1000.0))) >= 0.5)
        {
            baseMax = currentMax;
            appliedScale = 1000;
        }

        desiredMax = baseMax * ((float)scaleMilli / 1000.0);

        if(AbsF(currentMax - desiredMax) < 0.5)
        {
            appliedScale = scaleMilli;
            return;
        }

        fraction = npc.GetHealth() / currentMax;

        if(fraction < 0.0)
        {
            fraction = 0.0;
        }

        if(fraction > 1.0)
        {
            fraction = 1.0;
        }

        npc.abilityManager.SetStatPointMax(stat, desiredMax);
        npc.SetHealthPerc(fraction);

        appliedScale = scaleMilli;

        if(debugLogging)
        {
            dbg("npc_scale", "base=" + baseMax
                + " scale=" + scaleMilli
                + " max=" + npc.GetMaxHealth()
                + " frac=" + fraction);
        }
    }

    private function healthPermille(npc : CNewNPC) : int
    {
        var current : float;
        var maximum : float;

        current = npc.GetHealth();
        maximum = npc.GetMaxHealth();

        if(current < 0.0 || maximum <= 0.0)
        {
            return -1;
        }

        return (int)((current / maximum) * 1000.0);
    }

    private function questTerminalState(npc : CNewNPC) : int
    {
        if(!npc)
        {
            return 0;
        }

        if(npc.IsInAgony())
        {
            return 3;
        }

        if(npc.IsKnockedUnconscious())
        {
            return 2;
        }

        if(!npc.IsAlive())
        {
            if(npc.WillBeUnconscious())
            {
                return 2;
            }

            return 1;
        }

        return 0;
    }

    private function buildFlags(npc : CNewNPC, record : r_TrackedNpc) : int
    {
        var flags : int;

        flags = 0;

        if(npc.IsAlive())
        {
            flags += 1;
        }

        if(npc.GetAttitude(thePlayer) == AIA_Hostile)
        {
            flags += 2;
        }

        if(record.isMonster)
        {
            flags += 4;
        }

        flags += (record.actionSeq & 63) * 8;
        flags += (record.actionType & 63) * 512;
        flags += (record.actionDir & 7) * 32768;
        flags += (record.actionCode & 8191) * 262144;

        return flags;
    }

    private function isReplicaActor(npc : CNewNPC) : bool
    {
        return npc.HasTag('WOReplica');
    }

    private function isSyncable(npc : CNewNPC, classifier : r_EntityClassifier) : bool
    {
        var sample : r_SEntityClassSample;

        if(!npc || npc == thePlayer)
        {
            return false;
        }

        if(npc.HasTag('MPEntity') || npc.HasTag('online_horse') || npc.HasTag('wo_horse'))
        {
            return false;
        }

        if(isReplicaActor(npc))
        {
            return false;
        }

        classifier.classify(npc, sample);

        if(!sample.syncEligible)
        {
            return false;
        }

        if(sample.entityClass == REC_Quest)
        {
            noteQuestLeak(npc, classifier);
            return false;
        }

        return true;
    }

    private function noteQuestLeak(npc : CNewNPC, classifier : r_EntityClassifier)
    {
        var sample : r_SEntityClassSample;

        if(!debugLogging)
        {
            return;
        }

        classifier.classify(npc, sample);

        WO_Note("[npc_quest] blocked from world sync app=" + NameToString(npc.GetAppearance())
            + " name=" + npc.GetName()
            + " class=" + (int)sample.entityClass
            + " reason=" + sample.reason
            + " eligible=" + sample.syncEligible
            + " tags=" + sample.tags);
    }

    private function findTrackedByActor(actor : CNewNPC) : int
    {
        var i : int;

        for(i = 0; i < tracked.Size(); i += 1)
        {
            if(tracked[i].actor == actor)
            {
                return i;
            }
        }

        return -1;
    }

    private function findTrackedById(npcId : int) : int
    {
        var i : int;

        for(i = 0; i < tracked.Size(); i += 1)
        {
            if(tracked[i].npcId == npcId)
            {
                return i;
            }
        }

        return -1;
    }

    private function countReplicasOfSpecies(typeCode : string, pos : Vector) : int
    {
        var replicaPos : Vector;
        var count : int;
        var i : int;

        for(i = 0; i < replicas.Size(); i += 1)
        {
            if(!replicas[i].actor || replicas[i].typeCode != typeCode)
            {
                continue;
            }

            if(!replicas[i].actor.IsAlive())
            {
                continue;
            }

            replicaPos = replicas[i].actor.GetWorldPosition();

            if(VecDistance(replicaPos, pos) <= RESERVATION_RADIUS)
            {
                count += 1;
            }
        }

        return count;
    }

    private function countLocalOfSpecies(typeCode : string, pos : Vector, out entities : array<CGameplayEntity>, classifier : r_EntityClassifier) : int
    {
        var npc : CNewNPC;
        var otherPos : Vector;
        var count : int;
        var i : int;
        var index : int;

        for(i = 0; i < entities.Size(); i += 1)
        {
            npc = (CNewNPC)entities[i];

            if(!isSyncable(npc, classifier) || !npc.IsAlive())
            {
                continue;
            }

            otherPos = npc.GetWorldPosition();

            if(VecDistance(otherPos, pos) > RESERVATION_RADIUS)
            {
                continue;
            }

            index = findTrackedByActor(npc);

            if(index >= 0)
            {
                if(tracked[index].typeCode == typeCode)
                {
                    count += 1;
                }

                continue;
            }

            if(classifyType(npc) == typeCode)
            {
                count += 1;
            }
        }

        return count;
    }

    private function noteSkipped(npc : CNewNPC, classifier : r_EntityClassifier, now : float)
    {
        var sample : r_SEntityClassSample;
        var playerPos : Vector;
        var npcPos : Vector;

        if(!debugLogging || !npc || npc == thePlayer || now < nextSkipLogAt)
        {
            return;
        }

        if(npc.HasTag('MPEntity') || npc.HasTag('WOReplica') || npc.HasTag('online_horse') || npc.HasTag('wo_horse'))
        {
            return;
        }

        playerPos = thePlayer.GetWorldPosition();
        npcPos = npc.GetWorldPosition();

        if(VecDistance(playerPos, npcPos) > 40.0)
        {
            return;
        }

        classifier.classify(npc, sample);

        nextSkipLogAt = now + 1.0;

        dbg("npc_skip", "app=" + NameToString(npc.GetAppearance())
            + " class=" + (int)sample.entityClass
            + " reason=" + sample.reason
            + " tags=" + sample.tags
            + " monster=" + npc.IsMonster()
            + " human=" + npc.IsHuman()
            + " maxHp=" + npc.GetMaxHealth()
            + " alive=" + npc.IsAlive());
    }

    private function scanOwned(now : float)
    {
        var npc : CNewNPC;
        var classifier : r_EntityClassifier;
        var record : r_TrackedNpc;
        var pos : Vector;
        var playerPos : Vector;
        var typeCode : string;
        var guid : int;
        var localCount : int;
        var replicaCount : int;
        var index : int;
        var i : int;
        var j : int;

        for(i = tracked.Size() - 1; i >= 0; i -= 1)
        {
            if(!tracked[i].actor || (now - tracked[i].lastSeenAt) > TRACK_GRACE)
            {
                releaseTrackedState(tracked[i]);
                tracked.Erase(i);
            }
        }

        classifier = theGame.r_getMultiplayerClient().getEntityClassifier();
        playerPos = thePlayer.GetWorldPosition();

        for(i = 0; i < scanEntities.Size(); i += 1)
        {
            npc = (CNewNPC)scanEntities[i];

            if(!npc || npc == thePlayer)
            {
                continue;
            }

            index = findTrackedByActor(npc);

            if(index >= 0)
            {
                if(tracked[index].questTag != "" || isSyncable(npc, classifier))
                {
                    tracked[index].lastSeenAt = now;
                }

                continue;
            }

            if(!npc.IsAlive())
            {
                continue;
            }

            guid = npc.GetGuidHash();

            if(guid == 0 || tracked.Size() >= MAX_TRACKED)
            {
                continue;
            }

            if(isRejected(guid))
            {
                continue;
            }

            pos = npc.GetWorldPosition();

            if(VecDistance(playerPos, pos) > SCAN_RADIUS)
            {
                continue;
            }

            if(!isSyncable(npc, classifier))
            {
                noteSkipped(npc, classifier, now);
                continue;
            }

            if(healthPermille(npc) <= 0)
            {
                continue;
            }

            typeCode = classifyType(npc);

            if(typeCode == "")
            {
                rememberRejected(guid, now);
                statNoTemplate += 1;
                continue;
            }

            replicaCount = countReplicasOfSpecies(typeCode, pos);

            if(replicaCount > 0)
            {
                localCount = countLocalOfSpecies(typeCode, pos, scanEntities, classifier);

                if(replicaCount >= localCount)
                {
                    for(j = 0; j < replicas.Size(); j += 1)
                    {
                        if(!replicas[j].synthetic || !replicas[j].actor
                            || replicas[j].typeCode != typeCode
                            || VecDistance(replicas[j].actor.GetWorldPosition(), pos) > RESERVATION_RADIUS)
                        {
                            continue;
                        }

                        destroyReplica(replicas[j]);
                        bindRegularReplica(replicas[j], pos, now);
                        break;
                    }

                    if(debugLogging)
                    {
                        dbg("npc_offer", "prefiltered duplicate app=" + NameToString(npc.GetAppearance())
                            + " code=" + typeCode
                            + " replicas=" + replicaCount
                            + " local=" + localCount);
                    }

                    statPrefiltered += 1;
                    continue;
                }
            }
            else
            {
                localCount = 1;
            }

            if(!allowOffer(now))
            {
                continue;
            }

            record = new r_TrackedNpc in this;
            record.npcId = guid;
            record.actor = npc;
            record.appearance = npc.GetAppearance();
            record.appearanceText = NameToString(record.appearance);
            record.isMonster = npc.IsMonster();
            record.typeCode = typeCode;
            record.lastSeenAt = now;
            record.lastHp = -1;
            record.offerLocalCount = localCount;

            tracked.PushBack(record);
            statRegistered += 1;

            if(debugLogging)
            {
                dbg("npc_offer", "offered guid=" + guid
                    + " code=" + typeCode
                    + " app=" + record.appearanceText
                    + " localCount=" + localCount);
            }
        }
    }

    private function coopActive() : bool
    {
        return theGame.r_getMultiplayerClient().isCoopSession();
    }

    private function isQuestBoundActor(npc : CNewNPC) : bool
    {
        return npc.HasTag('WOQuestBound');
    }

    private function findQuestTrackedByTag(questTag : string) : int
    {
        var i : int;

        for(i = 0; i < tracked.Size(); i += 1)
        {
            if(tracked[i].questTag == questTag)
            {
                return i;
            }
        }

        return -1;
    }

    private function findQuestReplicaByTag(questTag : string) : int
    {
        var i : int;

        for(i = 0; i < replicas.Size(); i += 1)
        {
            if(replicas[i].questTag == questTag)
            {
                return i;
            }
        }

        return -1;
    }

    private function scanQuestEnemies(now : float)
    {
        var classifier : r_EntityClassifier;
        var record : r_TrackedNpc;
        var npc : CNewNPC;
        var questTag : string;
        var guid : int;
        var index : int;
        var i : int;

        if(!coopActive())
        {
            for(i = tracked.Size() - 1; i >= 0; i -= 1)
            {
                if(tracked[i].questTag != "")
                {
                    releaseTrackedState(tracked[i]);
                    tracked.Erase(i);
                    scheduleOwnedFlush(now);
                }
            }

            return;
        }

        classifier = theGame.r_getMultiplayerClient().getEntityClassifier();

        for(i = tracked.Size() - 1; i >= 0; i -= 1)
        {
            if(tracked[i].questTag == "")
            {
                continue;
            }

            if(findQuestReplicaByTag(tracked[i].questTag) >= 0)
            {
                dbg("npc_quest", "owned tag=" + tracked[i].questTag + " now bound as replica, releasing claim");
                releaseTrackedState(tracked[i]);
                tracked.Erase(i);
                scheduleOwnedFlush(now);
                continue;
            }

            if(!tracked[i].actor || classifier.questEnemyTag(tracked[i].actor) == "")
            {
                dbg("npc_quest", "dropped tag=" + tracked[i].questTag);
                releaseTrackedState(tracked[i]);
                tracked.Erase(i);
                scheduleOwnedFlush(now);
            }
        }

        for(i = 0; i < scanEntities.Size(); i += 1)
        {
            npc = (CNewNPC)scanEntities[i];

            if(!npc || npc == thePlayer || isReplicaActor(npc) || isQuestBoundActor(npc))
            {
                continue;
            }

            if(!npc.IsAlive() || npc.GetAttitude(thePlayer) != AIA_Hostile)
            {
                continue;
            }

            questTag = classifier.questEnemyTag(npc);

            if(questTag == "")
            {
                continue;
            }

            if(findQuestReplicaByTag(questTag) >= 0)
            {
                continue;
            }

            guid = npc.GetGuidHash();

            if(guid == 0)
            {
                continue;
            }

            index = findQuestTrackedByTag(questTag);

            if(index >= 0)
            {
                tracked[index].lastSeenAt = now;

                if(tracked[index].actor != npc || tracked[index].npcId != guid)
                {
                    releaseTrackedState(tracked[index]);
                    tracked[index].actor = npc;
                    tracked[index].npcId = guid;
                    tracked[index].appearance = npc.GetAppearance();
                    tracked[index].appearanceText = NameToString(tracked[index].appearance);
                    tracked[index].isMonster = npc.IsMonster();
                    tracked[index].baseMaxHealth = -1.0;
                    tracked[index].appliedScale = 1000;
                    tracked[index].cachedForcedTarget = 0;

                    dbg("npc_quest", "owned tag=" + questTag + " rebound to guid=" + guid);
                }

                continue;
            }

            if(tracked.Size() >= MAX_TRACKED)
            {
                continue;
            }

            record = new r_TrackedNpc in this;
            record.npcId = guid;
            record.actor = npc;
            record.appearance = npc.GetAppearance();
            record.appearanceText = NameToString(record.appearance);
            record.isMonster = npc.IsMonster();
            record.typeCode = "quest:" + questTag;
            record.questTag = questTag;
            record.lastSeenAt = now;
            record.lastHp = -1;
            record.offerLocalCount = 1;

            tracked.PushBack(record);

            dbg("npc_quest", "offered tag=" + questTag + " guid=" + guid);
        }
    }

    private function resetQuestActorState(record : r_Replica)
    {
        record.localGuid = 0;
        record.aiForceId = -1;
        record.appliedTarget = -1;
        record.lastLocalHp = -1;
        record.lastPredictAt = 0.0;
        record.nextDriveAt = 0.0;
        record.prepared = false;
        record.lastActionSeq = -1;
        record.lastActionAt = 0.0;
        record.pendingActionAt = -1.0;
        record.pendingType = 0;
        record.pendingDir = 0;
        record.baseMaxHealth = -1.0;
        record.appliedScale = 1000;
        record.appliedHostile = -1;
        record.boundLocal = false;
        record.synthetic = false;
        record.hasTicket = false;
        record.authorityReady = false;
        record.questOriginalImmortality = -1;
        record.questOriginalAttackable = false;
        record.questOriginalHitAnim = false;
        record.questHealthProtectionApplied = false;
    }

    private function questDefaultImmortality(npc : CNewNPC) : int
    {
        var channels : array<EActorImmortalityChanel>;

        if(!npc)
        {
            return (int)AIM_None;
        }

        channels = npc.GetImmortalityModeChannels(AIM_Invulnerable);

        if(channels.Contains(AIC_Default))
        {
            return (int)AIM_Invulnerable;
        }

        channels = npc.GetImmortalityModeChannels(AIM_Immortal);

        if(channels.Contains(AIC_Default))
        {
            return (int)AIM_Immortal;
        }

        channels = npc.GetImmortalityModeChannels(AIM_Unconscious);

        if(channels.Contains(AIC_Default))
        {
            return (int)AIM_Unconscious;
        }

        return (int)AIM_None;
    }

    private function applyQuestHealthProtection(record : r_Replica, npc : CNewNPC)
    {
        if(!record || !npc)
        {
            return;
        }

        record.questOriginalImmortality = questDefaultImmortality(npc);
        record.questOriginalAttackable = npc.IsAttackableByPlayer();
        record.questOriginalHitAnim = npc.CanPlayHitAnim();
        record.questHealthProtectionApplied = false;

        if(record.questOriginalImmortality == (int)AIM_None)
        {
            npc.SetImmortalityMode(AIM_Immortal, AIC_Default);
            record.questHealthProtectionApplied = questDefaultImmortality(npc) == (int)AIM_Immortal;
        }
    }

    private function releaseQuestHealthProtection(record : r_Replica, npc : CNewNPC)
    {
        if(!record || !npc || !record.questHealthProtectionApplied)
        {
            return;
        }

        npc.SetImmortalityMode(
            (EActorImmortalityMode)record.questOriginalImmortality,
            AIC_Default);
        record.questHealthProtectionApplied = false;
    }

    private function bindQuestReplica(record : r_Replica, now : float) : bool
    {
        var classifier : r_EntityClassifier;
        var npc : CNewNPC;
        var preservedBaseMax : float;
        var preservedScale : int;
        var preserveScale : bool;
        var guid : int;
        var index : int;
        var i : int;

        if(record.questTag == "")
        {
            return false;
        }

        classifier = theGame.r_getMultiplayerClient().getEntityClassifier();

        for(i = 0; i < scanEntities.Size(); i += 1)
        {
            npc = (CNewNPC)scanEntities[i];

            if(!npc || npc == thePlayer || isReplicaActor(npc) || isQuestBoundActor(npc))
            {
                continue;
            }

            if(!npc.IsAlive())
            {
                continue;
            }

            if(!npc.GetVisibility() || !npc.GetGameplayVisibility())
            {
                continue;
            }

            if(npc.GetAttitude(thePlayer) != AIA_Hostile)
            {
                continue;
            }

            if(!npc.IsMonster() && !classifier.isFightCapable(npc))
            {
                continue;
            }

            if(!classifier.hasExactQuestTag(npc, record.questTag))
            {
                continue;
            }

            guid = npc.GetGuidHash();

            if(guid == 0)
            {
                continue;
            }

            index = findQuestTrackedByTag(record.questTag);

            if(index >= 0)
            {
                preserveScale = tracked[index].actor == npc;

                if(preserveScale)
                {
                    preservedBaseMax = tracked[index].baseMaxHealth;
                    preservedScale = tracked[index].appliedScale;
                    wo_releaseNpcTarget(tracked[index].actor);
                    getAggro().forget(tracked[index].npcId);
                }
                else
                {
                    releaseTrackedState(tracked[index]);
                }

                tracked.Erase(index);
                scheduleOwnedFlush(now);
            }

            resetQuestActorState(record);

            if(preserveScale)
            {
                record.baseMaxHealth = preservedBaseMax;
                record.appliedScale = preservedScale;
            }

            npc.AddTag('WOQuestBound');
            npc.AddTag('WOReplica');
            applyQuestHealthProtection(record, npc);

            record.actor = npc;
            record.localGuid = guid;
            record.boundLocal = true;
            record.prepared = true;
            record.appearance = npc.GetAppearance();
            record.lastLocalHp = healthPermille(npc);

            WO_NpcBindId(record.canonicalId, record.localGuid);

            dbg("npc_quest", "bound canonical=" + record.canonicalId
                + " tag=" + record.questTag + " guid=" + record.localGuid
                + " originalImm=" + record.questOriginalImmortality
                + " protected=" + record.questHealthProtectionApplied
                + " attackable=" + record.questOriginalAttackable
                + " hitAnim=" + record.questOriginalHitAnim);

            return true;
        }

        return false;
    }

    private function bindRegularReplica(record : r_Replica, target : Vector, now : float) : bool
    {
        var classifier : r_EntityClassifier;
        var npc : CNewNPC;
        var best : CNewNPC;
        var distance : float;
        var bestDistance : float;
        var preservedBaseMax : float;
        var preservedScale : int;
        var index : int;
        var i : int;

        if(!record || record.typeCode == "" || record.questTag != "")
        {
            return false;
        }

        classifier = theGame.r_getMultiplayerClient().getEntityClassifier();
        bestDistance = RESERVATION_RADIUS + 1.0;

        for(i = 0; i < scanEntities.Size(); i += 1)
        {
            npc = (CNewNPC)scanEntities[i];

            if(!npc || npc == thePlayer || isReplicaActor(npc) || !npc.IsAlive())
            {
                continue;
            }

            if(!npc.GetVisibility() || !npc.GetGameplayVisibility() || !isSyncable(npc, classifier))
            {
                continue;
            }

            if(classifyType(npc) != record.typeCode)
            {
                continue;
            }

            distance = VecDistance(npc.GetWorldPosition(), target);

            if(distance < bestDistance)
            {
                bestDistance = distance;
                best = npc;
            }
        }

        if(!best)
        {
            return false;
        }

        index = findTrackedByActor(best);

        if(index >= 0)
        {
            preservedBaseMax = tracked[index].baseMaxHealth;
            preservedScale = tracked[index].appliedScale;
            wo_releaseNpcTarget(tracked[index].actor);
            getAggro().forget(tracked[index].npcId);
            tracked.Erase(index);
            scheduleOwnedFlush(now);
        }

        resetQuestActorState(record);
        record.baseMaxHealth = preservedBaseMax;
        record.appliedScale = preservedScale > 0 ? preservedScale : 1000;
        record.actor = best;
        record.localGuid = best.GetGuidHash();
        record.boundLocal = true;
        record.synthetic = false;
        record.prepared = true;
        record.appearance = best.GetAppearance();
        record.lastLocalHp = healthPermille(best);

        best.AddTag('WOReplica');
        applyQuestHealthProtection(record, best);
        WO_NpcBindId(record.canonicalId, record.localGuid);

        dbg("npc_bind", "canonical=" + record.canonicalId
            + " guid=" + record.localGuid
            + " code=" + record.typeCode);

        return true;
    }

    private function cancelReplicaMovement(record : r_Replica)
    {
        var agent : CMovingAgentComponent;
        var adjustor : CMovementAdjustor;

        if(!record || !record.actor || !record.hasTicket)
        {
            return;
        }

        agent = record.actor.GetMovingAgentComponent();

        if(agent)
        {
            adjustor = agent.GetMovementAdjustor();

            if(adjustor)
            {
                adjustor.Cancel(adjustor.GetRequest('wo_replica'));
            }
        }

        record.hasTicket = false;
    }

    private function unbindQuestReplica(record : r_Replica, keepNetwork : bool)
    {

        if(record.actor)
        {
            if(record.aiForceId >= 0)
            {
                record.actor.CancelAIBehavior(record.aiForceId);
                record.aiForceId = -1;
            }

            cancelReplicaMovement(record);

            wo_releaseNpcTarget(record.actor);
            restoreHealthScale(record.actor, record.baseMaxHealth, record.appliedScale);
            releaseQuestHealthProtection(record, record.actor);

            record.actor.RemoveTag('WOQuestBound');
            record.actor.RemoveTag('WOReplica');

        }

        record.actor = NULL;
        resetQuestActorState(record);

        if(keepNetwork)
        {
            WO_NpcBindId(record.canonicalId, 0);
        }
    }

    private function restoreHealthScale(npc : CNewNPC, baseMax : float, appliedScale : int)
    {
        if(!npc || baseMax <= 0.0 || appliedScale == 1000)
        {
            return;
        }

        applyHealthScale(npc, 1000, baseMax, appliedScale);
    }

    private function releaseTrackedState(record : r_TrackedNpc)
    {
        if(!record)
        {
            return;
        }

        if(record.actor)
        {
            restoreHealthScale(record.actor, record.baseMaxHealth, record.appliedScale);
            wo_releaseNpcTarget(record.actor);
        }

        getAggro().forget(record.npcId);
        record.cachedForcedTarget = 0;
    }

    private function updateQuestBindings(now : float)
    {
        var classifier : r_EntityClassifier;
        var actorValid : bool;
        var i : int;

        classifier = theGame.r_getMultiplayerClient().getEntityClassifier();

        for(i = replicas.Size() - 1; i >= 0; i -= 1)
        {
            if(replicas[i].questTag == "")
            {
                continue;
            }

            if(!coopActive())
            {
                unbindQuestReplica(replicas[i], false);
                WO_NpcForget(replicas[i].canonicalId);
                replicas.Erase(i);
                continue;
            }

            if(replicas[i].terminalState > 0 && replicas[i].terminalRevision > 0)
            {
                if(replicas[i].terminalAppliedRevision < replicas[i].terminalRevision
                    && replicas[i].terminalVerifyAt <= 0.0)
                {
                    replicas[i].terminalVerifyAt = now;
                }

                continue;
            }

            actorValid = false;

            if(replicas[i].actor)
            {
                actorValid = replicas[i].actor.IsAlive()
                    && replicas[i].actor.GetVisibility()
                    && replicas[i].actor.GetGameplayVisibility()
                    && classifier.hasExactQuestTag(replicas[i].actor, replicas[i].questTag)
                    && (replicas[i].authorityHostile == 1
                        || replicas[i].actor.GetAttitude(thePlayer) == AIA_Hostile);

                if(!actorValid)
                {
                    unbindQuestReplica(replicas[i], true);
                }
            }

            if(!replicas[i].actor && bindQuestReplica(replicas[i], now))
            {
                dbg("npc_quest", "rebound tag=" + replicas[i].questTag
                    + " guid=" + replicas[i].localGuid);
            }
        }
    }

    private function getPathCache() : MP_SU_HashMap
    {
        if(!pathCache)
        {
            pathCache = new MP_SU_HashMap in this;
            pathCache.init();
            pathCacheCount = 0;
        }

        return pathCache;
    }

    private function classifyType(npc : CNewNPC) : string
    {
        var cache : MP_SU_HashMap;
        var stored : MP_SU_HashMapValueString;
        var path : string;
        var guid : int;

        if(!npc)
        {
            return "";
        }

        guid = npc.GetGuidHash();
        cache = getPathCache();

        if(guid != 0)
        {
            stored = (MP_SU_HashMapValueString)cache.get(guid);

            if(stored)
            {
                return stored.value;
            }
        }

        path = WO_EntityTemplatePath(npc);

        if(path == "")
        {
            WO_Note("[npc_species] no template path app=" + NameToString(npc.GetAppearance())
                + " name=" + npc.GetName()
                + " monster=" + npc.IsMonster()
                + " maxHp=" + npc.GetMaxHealth());
        }

        if(guid != 0)
        {
            if(pathCacheCount >= PATH_CACHE_MAX)
            {
                cache.init();
                pathCacheCount = 0;
            }

            cache.insert(guid, hm_fromString(path));
            pathCacheCount += 1;
            statTemplateResolves += 1;
        }

        return path;
    }

    public function creditOwnedDamage(actor : CNewNPC, dealt : float, attacker : CGameplayEntity)
    {
        var attackerActor : CActor;
        var attackerId : int;
        var localId : int;
        var index : int;

        if(!actor)
        {
            return;
        }

        index = findTrackedByActor(actor);

        if(index < 0)
        {
            return;
        }

        attackerActor = (CActor)attacker;
        attackerId = theGame.r_getMultiplayerClient().encodeTargetPlayerId(attackerActor);
        tracked[index].lastAttackerId = attackerId;

        localId = theGame.r_getMultiplayerClient().getServerId();

        if(localId <= 0)
        {
            return;
        }

        if(attackerId > 0 && dealt > 0.0)
        {
            getAggro().noteDamage(tracked[index].npcId, attackerId, dealt, theGame.GetEngineTimeAsSeconds());
        }

        if(dealt > 0.0)
        {
            statLocalDamage += 1;
        }
    }

    private function syncScopeLabel() : string
    {
        var client : r_MultiplayerClient;

        client = theGame.r_getMultiplayerClient();

        if(client.getPartyId() != 0)
        {
            return "party#" + client.getPartyId();
        }

        if(client.getNpcSyncMode() == 0)
        {
            return "world";
        }

        return "standalone";
    }

    private function updateSyncMode(now : float)
    {
        var client : r_MultiplayerClient;
        var mode : int;
        var partyId : int;

        if(now < nextModeCheckAt)
        {
            return;
        }

        nextModeCheckAt = now + 0.5;

        client = theGame.r_getMultiplayerClient();
        mode = client.getNpcSyncMode();
        partyId = client.getPartyId();

        cachedIsolated = (mode == 1 && partyId == 0);

        if(mode == reportedSyncMode && partyId == reportedPartyId)
        {
            return;
        }

        if(mode != reportedSyncMode)
        {
            WO_NpcSyncMode(mode);
        }

        reportedSyncMode = mode;
        reportedPartyId = partyId;

        WO_Note("[npc_sync] config=" + modeLabel(mode) + " scope=" + syncScopeLabel());
    }

    private function modeLabel(mode : int) : string
    {
        if(mode == 0)
        {
            return "world";
        }

        return "party";
    }

    private function pushOwned(now : float)
    {
        var client : r_MultiplayerClient;
        var npc : CNewNPC;
        var pos : Vector;
        var area : int;
        var localId : int;
        var forced : int;
        var targetId : int;
        var hp : int;
        var i : int;

        WO_NpcBeginOwned();

        if(cachedIsolated)
        {
            WO_NpcEndOwned();
            pollOwnedAttackVars(now);
            return;
        }

        area = currentArea();
        client = theGame.r_getMultiplayerClient();
        localId = client.getServerId();

        for(i = 0; i < tracked.Size(); i += 1)
        {
            npc = tracked[i].actor;

            if(!npc)
            {
                continue;
            }

            pos = npc.GetWorldPosition();
            hp = healthPermille(npc);
            tracked[i].terminalState = questTerminalState(npc);

            forced = tracked[i].cachedForcedTarget;

            if(forced > 0)
            {
                targetId = forced;
            }
            else
            {
                targetId = client.encodeTargetPlayerId(npc.GetTarget());
            }

            if(targetId > 0
                && tracked[i].questTag != ""
                && !client.isQuestCoopPlayer(targetId))
            {
                targetId = 0;
            }
            else if(targetId > 0
                && targetId != localId
                && !client.sharesNpcScope(targetId))
            {
                targetId = 0;
            }

            WO_NpcPushOwned(
                tracked[i].npcId,
                area,
                tracked[i].offerLocalCount,
                tracked[i].typeCode,
                tracked[i].appearanceText,
                pos.X, pos.Y, pos.Z,
                npc.GetHeading(),
                hp,
                buildFlags(npc, tracked[i]),
                targetId,
                tracked[i].terminalState,
                tracked[i].lastAttackerId);

            tracked[i].lastHp = hp;
            tracked[i].lastTargetId = targetId;
        }

        WO_NpcEndOwned();

        pollOwnedAttackVars(now);
    }

    public function reportReplicaDamage(actor : CNewNPC, dealt : float, maximum : float)
    {
        var permille : int;
        var i : int;

        if(!actor || dealt <= 0.0 || maximum <= 0.0)
        {
            return;
        }

        permille = (int)((dealt / maximum) * 1000.0);

        if(permille <= 0)
        {
            return;
        }

        if(permille > 1000)
        {
            permille = 1000;
        }

        for(i = 0; i < replicas.Size(); i += 1)
        {
            if(replicas[i].actor != actor)
            {
                continue;
            }

            if(actor.IsImmortal()
                && actor.GetHealth() > 0.0 && actor.GetHealth() <= 1.01)
            {
                dealt += actor.GetHealth();
                permille = (int)((dealt / maximum) * 1000.0);

                if(permille <= 0)
                {
                    permille = 1;
                }

                if(permille > 1000)
                {
                    permille = 1000;
                }
            }

            if(WO_NpcReportHit(replicas[i].canonicalId, permille) != 0)
            {
                replicas[i].lastPredictAt = theGame.GetEngineTimeAsSeconds();
                replicas[i].lastLocalHp = healthPermille(actor);
                statHitsSent += 1;

                if(debugLogging)
                {
                    dbg("npc_hit", "sent (exact) canonical=" + replicas[i].canonicalId
                        + " permille=" + permille
                        + " dealt=" + dealt);
                }
            }

            return;
        }
    }

    private function findReplica(canonicalId : int) : int
    {
        var i : int;

        for(i = 0; i < replicas.Size(); i += 1)
        {
            if(replicas[i].canonicalId == canonicalId)
            {
                return i;
            }
        }

        return -1;
    }

    private function convertTrackedToReplica(index : int, canonicalId : int, now : float)
    {
        var owned : r_TrackedNpc;
        var record : r_Replica;
        var replicaIndex : int;

        if(index < 0 || index >= tracked.Size() || canonicalId <= 0 || !tracked[index].actor)
        {
            return;
        }

        owned = tracked[index];
        replicaIndex = findReplica(canonicalId);

        if(replicaIndex >= 0)
        {
            record = replicas[replicaIndex];

            if(record.actor && record.actor != owned.actor)
            {
                destroyReplica(record);
            }
        }
        else
        {
            record = new r_Replica in this;
            record.canonicalId = canonicalId;
            replicas.PushBack(record);
        }

        resetQuestActorState(record);
        record.canonicalId = canonicalId;
        record.actor = owned.actor;
        record.localGuid = owned.npcId;
        record.appearance = owned.appearance;
        record.typeCode = owned.typeCode;
        record.questTag = owned.questTag;
        record.baseMaxHealth = owned.baseMaxHealth;
        record.appliedScale = owned.appliedScale;
        record.boundLocal = true;
        record.synthetic = false;
        record.prepared = true;
        record.lastLocalHp = healthPermille(owned.actor);

        getAggro().forget(owned.npcId);
        wo_releaseNpcTarget(owned.actor);
        owned.actor.AddTag('WOReplica');

        if(owned.questTag != "")
        {
            owned.actor.AddTag('WOQuestBound');
        }

        applyQuestHealthProtection(record, owned.actor);
        tracked.Erase(index);
        scheduleOwnedFlush(now);
        WO_NpcBindId(canonicalId, record.localGuid);

        dbg("npc_bind", "converted canonical=" + canonicalId
            + " guid=" + record.localGuid
            + " code=" + record.typeCode);
    }

    private function applyDrops(now : float)
    {
        var count : int;
        var guid : int;
        var canonicalId : int;
        var index : int;
        var i : int;

        count = WO_NpcDropCount();

        if(count <= 0)
        {
            return;
        }

        for(i = 0; i < count; i += 1)
        {
            guid = WO_NpcDropGuid(i);
            canonicalId = WO_NpcDropCanonical(i);
            index = findTrackedById(guid);

            rememberRejected(guid, now);

            if(index >= 0)
            {
                if(canonicalId > 0)
                {
                    convertTrackedToReplica(index, canonicalId, now);
                    statDropped += 1;
                    continue;
                }

                releaseTrackedState(tracked[index]);
                tracked.Erase(index);
                scheduleOwnedFlush(now);
                statDropped += 1;

                dbg("npc_handover", "released local guid=" + guid);
                continue;
            }

            dbg("npc_handover", "drop unmatched guid=" + guid
                + " canonical=" + canonicalId);
        }

        WO_NpcDropsDone();
    }

    private function applyKillOrders()
    {
        var count : int;
        var attackerId : int;
        var attacker : CActor;
        var guid : int;
        var index : int;
        var i : int;

        count = WO_NpcKillCount();

        if(count <= 0)
        {
            return;
        }

        for(i = 0; i < count; i += 1)
        {
            guid = WO_NpcKillGuid(i);
            attackerId = WO_NpcKillAttacker(i);
            index = findTrackedById(guid);

            if(index < 0)
            {
                continue;
            }

            if(tracked[index].actor && tracked[index].actor.IsAlive())
            {
                attacker = theGame.r_getMultiplayerClient().resolveTargetActor(attackerId);
                tracked[index].lastAttackerId = attackerId;

                tracked[index].actor.Kill('WitcherOnlineQuestSync', false, attacker);
                statKilled += 1;

                dbg("npc_kill", "server-confirmed kill of owned guid=" + guid);
            }
        }

        WO_NpcKillsDone();
    }

    private function driveReplicas(now : float)
    {
        var count : int;
        var op : int;
        var canonicalId : int;
        var index : int;
        var i : int;

        count = WO_NpcPull();

        for(i = 0; i < count; i += 1)
        {
            canonicalId = WO_NpcId(i);
            op = WO_NpcOp(i);
            index = findReplica(canonicalId);

            if(op == 1)
            {
                if(index < 0)
                {
                    spawnReplica(i, canonicalId, now);
                }

                continue;
            }

            if(op == 2)
            {
                if(index >= 0)
                {
                    destroyReplica(replicas[index]);
                    replicas.Erase(index);
                    statDespawned += 1;
                }

                continue;
            }

            if(index < 0)
            {
                WO_NpcForget(canonicalId);
                continue;
            }

            if(op == 3)
            {
                killReplica(replicas[index], i, now);
                continue;
            }

            if(op == 4)
            {
                promoteReplica(replicas[index], now);
                replicas.Erase(index);
                continue;
            }

            if(op == 5)
            {
                recoverQuestReplicaLocally(replicas[index], now);
                replicas.Erase(index);
                continue;
            }

            driveReplica(replicas[index], i, now);
        }
    }

    private function spawnReplica(commandIndex : int, canonicalId : int, now : float)
    {
        var record : r_Replica;
        var template : CEntityTemplate;
        var npc : CNewNPC;
        var rot : EulerAngles;
        var pos : Vector;
        var path : string;
        var typeCode : string;
        var appearance : name;

        if(replicas.Size() >= MAX_REPLICAS)
        {
            return;
        }

        typeCode = WO_NpcType(commandIndex);
        appearance = WO_NpcAppearance(commandIndex);
        path = typeCode;

        if(StrLeft(typeCode, 6) == "quest:")
        {
            record = new r_Replica in this;
            record.canonicalId = canonicalId;
            record.questTag = StrAfterFirst(typeCode, "quest:");
            record.typeCode = typeCode;
            record.appearance = appearance;
            record.terminalState = WO_NpcTerminalState(commandIndex);
            record.terminalRevision = WO_NpcTerminalRevision(commandIndex);
            record.terminalAttackerId = WO_NpcTerminalAttacker(commandIndex);

            if(!bindQuestReplica(record, now))
            {
                dbg("npc_quest", "waiting canonical=" + canonicalId
                    + " tag=" + record.questTag);
            }

            replicas.PushBack(record);
            return;
        }

        if(path == "")
        {
            statNoTemplate += 1;
            WO_Note("[npc_spawn] empty template path canonical=" + canonicalId
                + " app=" + NameToString(appearance));
            WO_NpcUnspawnable(canonicalId);
            return;
        }

        pos = Vector(WO_NpcX(commandIndex), WO_NpcY(commandIndex), WO_NpcZ(commandIndex));
        record = new r_Replica in this;
        record.canonicalId = canonicalId;
        record.appearance = appearance;
        record.typeCode = typeCode;

        if(bindRegularReplica(record, pos, now))
        {
            replicas.PushBack(record);
            return;
        }

        template = (CEntityTemplate)LoadResource(path, true);

        if(!template)
        {
            statNoTemplate += 1;
            WO_Note("[npc_spawn] template load failed canonical=" + canonicalId + " path=" + path);
            WO_NpcUnspawnable(canonicalId);
            return;
        }

        rot.Pitch = 0.0;
        rot.Yaw = WO_NpcHeading(commandIndex);
        rot.Roll = 0.0;

        npc = (CNewNPC)theGame.CreateEntity(template, pos, rot, true, false, false, PM_DontPersist);

        if(!npc)
        {
            dbg("npc_spawn", "create failed canonical=" + canonicalId + " path=" + path);
            return;
        }

        record.actor = npc;
        record.localGuid = npc.GetGuidHash();
        record.synthetic = true;

        replicas.PushBack(record);

        WO_NpcBind(commandIndex, record.localGuid);
        prepareReplica(record);

        statSpawned += 1;

        dbg("npc_spawn", "canonical=" + canonicalId
            + " code=" + typeCode
            + " app=" + NameToString(appearance)
            + " guid=" + record.localGuid);
    }

    private function applyReplicaHostility(record : r_Replica, npc : CNewNPC, flags : int)
    {
        var desired : int;

        if((flags & 2) != 0)
        {
            desired = 1;
        }
        else
        {
            desired = 0;
        }

        if(record.appliedHostile == desired)
        {
            return;
        }

        record.appliedHostile = desired;

        if(desired == 1)
        {
            npc.SetAttitude(thePlayer, AIA_Hostile);
        }
        else
        {
            npc.ResetAttitude(thePlayer);
        }

        if(debugLogging)
        {
            dbg("npc_attitude", "canonical=" + record.canonicalId
                + " hostile=" + desired
                + " app=" + NameToString(record.appearance));
        }
    }

    private function prepareReplica(record : r_Replica)
    {
        var npc : CNewNPC;

        npc = record.actor;

        if(!npc || record.prepared)
        {
            return;
        }

        npc.AddTag('WOReplica');

        if(record.appearance != '' && npc.GetAppearance() != record.appearance)
        {
            npc.ApplyAppearance(NameToString(record.appearance));
        }

        applyQuestHealthProtection(record, npc);

        record.lastLocalHp = healthPermille(npc);
        record.prepared = true;
    }

    private function destroyReplica(record : r_Replica)
    {
        if(!record)
        {
            return;
        }

        if(record.questTag != "" || !record.synthetic)
        {
            unbindQuestReplica(record, false);
            return;
        }

        if(record.actor)
        {
            if(record.aiForceId >= 0)
            {
                record.actor.CancelAIBehavior(record.aiForceId);
            }

            cancelReplicaMovement(record);
            wo_releaseNpcTarget(record.actor);
            restoreHealthScale(record.actor, record.baseMaxHealth, record.appliedScale);
            releaseQuestHealthProtection(record, record.actor);
            record.actor.Destroy();
        }

        record.actor = NULL;
    }

    private function questTerminalMatches(record : r_Replica) : bool
    {
        if(!record.actor)
        {
            return true;
        }

        if(record.terminalState == 2)
        {
            return record.actor.IsKnockedUnconscious();
        }

        if(record.terminalState == 3)
        {
            return record.actor.IsInAgony();
        }

        if(record.terminalState == 1)
        {
            return !record.actor.IsAlive()
                && !record.actor.IsKnockedUnconscious()
                && !record.actor.IsInAgony();
        }

        return record.actor.IsAlive();
    }

    private function detachQuestTerminalActor(record : r_Replica)
    {
        if(!record.actor)
        {
            return;
        }

        if(record.aiForceId >= 0)
        {
            record.actor.CancelAIBehavior(record.aiForceId);
            record.aiForceId = -1;
        }

        cancelReplicaMovement(record);
        wo_releaseNpcTarget(record.actor);
        releaseQuestHealthProtection(record, record.actor);

        record.actor.RemoveTag('WOReplica');
        record.actor.RemoveTag('WOQuestBound');
        record.prepared = false;
        record.authorityReady = false;
    }

    private function applyQuestTerminalNative(record : r_Replica, now : float)
    {
        var attacker : CActor;

        if(!record || record.terminalState <= 0
            || record.terminalRevision <= 0)
        {
            return;
        }

        if(!record.actor)
        {
            record.terminalAppliedRevision = record.terminalRevision;
            record.terminalVerifyAt = 0.0;
            WO_NpcTerminalAck(record.canonicalId, record.terminalRevision);
            return;
        }

        detachQuestTerminalActor(record);
        attacker = theGame.r_getMultiplayerClient().resolveTargetActor(record.terminalAttackerId);

        if(record.terminalState == 2)
        {
            record.actor.SetImmortalityMode(AIM_Unconscious, AIC_Default, true);
        }
        else if(record.terminalState == 3)
        {
            record.actor.SignalGameplayEvent('ForceAgony');
        }

        if(record.actor.IsAlive())
        {
            record.actor.Kill('WitcherOnlineQuestSync', false, attacker);
            statKilled += 1;
        }

        record.terminalAppliedRevision = record.terminalRevision;
        record.terminalAttempts += 1;
        record.terminalVerifyAt = now + 0.15;

    }

    private function verifyQuestTerminals(now : float)
    {
        var i : int;

        for(i = 0; i < replicas.Size(); i += 1)
        {
            if(replicas[i].terminalState <= 0
                || replicas[i].terminalRevision <= 0 || replicas[i].terminalVerifyAt <= 0.0
                || now < replicas[i].terminalVerifyAt)
            {
                continue;
            }

            if(replicas[i].terminalAppliedRevision == replicas[i].terminalRevision
                && questTerminalMatches(replicas[i]))
            {
                replicas[i].terminalVerifyAt = 0.0;
                WO_NpcTerminalAck(replicas[i].canonicalId, replicas[i].terminalRevision);
                continue;
            }

            if(replicas[i].terminalAttempts < 3)
            {
                applyQuestTerminalNative(replicas[i], now);
                continue;
            }

            replicas[i].terminalAttempts = 0;
            replicas[i].terminalVerifyAt = now + 1.0;
        }
    }

    private function killReplica(record : r_Replica, commandIndex : int, now : float)
    {
        record.terminalState = WO_NpcTerminalState(commandIndex);
        record.terminalRevision = WO_NpcTerminalRevision(commandIndex);
        record.terminalAttackerId = WO_NpcTerminalAttacker(commandIndex);

        if(record.terminalState <= 0)
        {
            record.terminalState = 1;
        }

        if(record.terminalRevision <= record.terminalAppliedRevision)
        {
            return;
        }

        record.terminalAttempts = 0;
        record.terminalVerifyAt = now;
        applyQuestTerminalNative(record, now);
    }

    private function promoteReplica(record : r_Replica, now : float)
    {
        var npc : CNewNPC;
        var owned : r_TrackedNpc;
        var preservedTargetId : int;
        var preservedTarget : CActor;

        npc = record.actor;

        if(!npc)
        {
            WO_NpcForget(record.canonicalId);
            return;
        }

        if(record.appliedTarget > 0)
        {
            preservedTargetId = record.appliedTarget;
        }

        if(record.aiForceId >= 0)
        {
            npc.CancelAIBehavior(record.aiForceId);
            record.aiForceId = -1;
        }

        cancelReplicaMovement(record);
        wo_releaseNpcTarget(npc);

        releaseQuestHealthProtection(record, npc);
        npc.RemoveTag('WOReplica');
        npc.RemoveTag('WOQuestBound');

        if(preservedTargetId > 0)
        {
            preservedTarget = theGame.r_getMultiplayerClient().resolveTargetActor(preservedTargetId);

            if(preservedTarget)
            {
                wo_forceNpcTarget(npc, preservedTarget);
            }
        }

        owned = new r_TrackedNpc in this;
        owned.npcId = npc.GetGuidHash();
        owned.actor = npc;
        owned.appearance = npc.GetAppearance();
        owned.appearanceText = NameToString(owned.appearance);
        owned.isMonster = npc.IsMonster();
        owned.typeCode = record.typeCode;
        owned.questTag = record.questTag;
        owned.lastSeenAt = now;
        owned.lastHp = -1;
        owned.baseMaxHealth = record.baseMaxHealth;
        owned.appliedScale = record.appliedScale;
        owned.cachedForcedTarget = 0;

        if(preservedTarget)
        {
            owned.cachedForcedTarget = preservedTargetId;
        }

        getAggro().forget(owned.npcId);
        tracked.PushBack(owned);
        statPromoted += 1;
        nextAggroAt = 0.0;

        WO_NpcTake(record.canonicalId, owned.npcId);

        dbg("npc_handover", "promoted canonical=" + record.canonicalId
            + " guid=" + owned.npcId
            + " app=" + NameToString(owned.appearance)
            + " target=" + owned.cachedForcedTarget);
    }

    private function recoverQuestReplicaLocally(record : r_Replica, now : float)
    {
        var npc : CNewNPC;
        var owned : r_TrackedNpc;
        var preservedTargetId : int;
        var preservedTarget : CActor;

        npc = record.actor;

        if(!npc)
        {
            WO_NpcForget(record.canonicalId);
            return;
        }

        if(record.aiForceId >= 0)
        {
            npc.CancelAIBehavior(record.aiForceId);
            record.aiForceId = -1;
        }

        cancelReplicaMovement(record);
        wo_releaseNpcTarget(npc);

        preservedTargetId = record.appliedTarget;

        npc.RemoveTag('WOReplica');
        npc.RemoveTag('WOQuestBound');
        releaseQuestHealthProtection(record, npc);

        if(preservedTargetId > 0)
        {
            preservedTarget = theGame.r_getMultiplayerClient().resolveTargetActor(preservedTargetId);

            if(preservedTarget)
            {
                wo_forceNpcTarget(npc, preservedTarget);
            }
        }

        owned = new r_TrackedNpc in this;
        owned.npcId = npc.GetGuidHash();
        owned.actor = npc;
        owned.appearance = npc.GetAppearance();
        owned.appearanceText = NameToString(owned.appearance);
        owned.isMonster = npc.IsMonster();
        owned.typeCode = record.typeCode;
        owned.questTag = record.questTag;
        owned.lastSeenAt = now;
        owned.lastHp = -1;
        owned.baseMaxHealth = record.baseMaxHealth;
        owned.appliedScale = record.appliedScale;
        owned.cachedForcedTarget = 0;

        if(preservedTarget)
        {
            owned.cachedForcedTarget = preservedTargetId;
        }

        getAggro().forget(owned.npcId);
        tracked.PushBack(owned);
        nextAggroAt = 0.0;
        nextPushAt = 0.0;

        WO_NpcForget(record.canonicalId);

    }

    private function driveReplica(record : r_Replica, commandIndex : int, now : float)
    {
        var npc : CNewNPC;
        var target : Vector;
        var current : Vector;
        var rot : EulerAngles;
        var adjustor : CMovementAdjustor;
        var ticket : SMovementAdjustmentRequestTicket;
        var agent : CMovingAgentComponent;
        var heading : float;
        var speed : float;
        var distance : float;
        var snapLimit : float;
        var freeRadius : float;
        var slideWindow : float;
        var hp : int;
        var flags : int;
        record.terminalState = WO_NpcTerminalState(commandIndex);
        record.terminalRevision = WO_NpcTerminalRevision(commandIndex);
        record.terminalAttackerId = WO_NpcTerminalAttacker(commandIndex);

        if(record.terminalState == 0 && record.terminalAppliedRevision > 0)
        {
            record.terminalAppliedRevision = 0;
            record.terminalAttempts = 0;
            record.terminalVerifyAt = 0.0;

            if(record.actor && record.actor.IsAlive())
            {
                if(record.questTag != "")
                {
                    record.actor.AddTag('WOQuestBound');
                }

                record.actor.AddTag('WOReplica');
                applyQuestHealthProtection(record, record.actor);
                record.prepared = true;
            }
        }

        npc = record.actor;

        if(!npc)
        {
            return;
        }

        if(!record.prepared)
        {
            prepareReplica(record);
        }

        target = Vector(WO_NpcX(commandIndex), WO_NpcY(commandIndex), WO_NpcZ(commandIndex));
        heading = WO_NpcHeading(commandIndex);
        speed = WO_NpcSpeed(commandIndex);
        hp = WO_NpcHp(commandIndex);

        flags = WO_NpcFlags(commandIndex);

        record.authorityHostile = ((flags & 2) != 0) ? 1 : 0;

        applyReplicaTarget(record, npc, WO_NpcTarget(commandIndex));

        applyReplicaAction(record, npc, flags);
        reconcileReplicaHealth(record, npc, hp, now);

        if(!npc.IsAlive())
        {
            return;
        }

        current = npc.GetWorldPosition();
        distance = VecDistance(current, target);
        snapLimit = QUEST_HARD_SNAP;
        freeRadius = QUEST_FREE_RADIUS;
        slideWindow = QUEST_SLIDE_WINDOW;

        if((!record.authorityReady && distance > freeRadius)
            || distance > snapLimit)
        {
            cancelReplicaMovement(record);

            rot.Pitch = 0.0;
            rot.Yaw = heading;
            rot.Roll = 0.0;

            npc.TeleportWithRotation(target, rot);
            record.authorityReady = true;
            record.nextDriveAt = now + DRIVE_INTERVAL;
            statTeleports += 1;
            return;
        }

        record.authorityReady = true;

        if(now < record.nextDriveAt)
        {
            return;
        }

        record.nextDriveAt = now + DRIVE_INTERVAL;

        if(distance < freeRadius)
        {
            cancelReplicaMovement(record);

            return;
        }

        agent = npc.GetMovingAgentComponent();

        if(!agent)
        {
            return;
        }

        adjustor = agent.GetMovementAdjustor();

        if(adjustor)
        {
            adjustor.Cancel(adjustor.GetRequest('wo_replica'));
        }

        if(distance > 0.15)
        {
            agent.SetGameplayMoveDirection(VecHeading(target - current));
        }

        if(speed > 3.0)
        {
            agent.SetMoveType(MT_Run);
        }
        else
        {
            agent.SetMoveType(MT_Walk);
        }

        if(!adjustor)
        {
            return;
        }

        ticket = adjustor.CreateNewRequest('wo_replica');

        adjustor.AdjustmentDuration(ticket, slideWindow);
        adjustor.AdjustLocationVertically(ticket, true);
        adjustor.ScaleAnimationLocationVertically(ticket, true);
        adjustor.RotateTo(ticket, heading);
        adjustor.SlideTo(ticket, target);

        record.hasTicket = true;
    }

    private function applyReplicaTarget(record : r_Replica, npc : CNewNPC, targetPlayerId : int)
    {
        var resolved : CActor;

        if(record.appliedTarget == targetPlayerId)
        {
            return;
        }

        if(targetPlayerId <= 0)
        {
            wo_releaseNpcTarget(npc);
            record.appliedTarget = targetPlayerId;
            return;
        }

        resolved = theGame.r_getMultiplayerClient().resolveTargetActor(targetPlayerId);

        if(!resolved)
        {
            return;
        }

        wo_forceNpcTarget(npc, resolved);
        record.appliedTarget = targetPlayerId;
        statTargets += 1;

        if(debugLogging)
        {
            dbg("npc_target", "canonical=" + record.canonicalId + " player=" + targetPlayerId);
        }
    }

    private function reconcileReplicaHealth(record : r_Replica, npc : CNewNPC, authorityPermille : int, now : float)
    {
        var localPermille : int;
        var drop : int;
        var targetFraction : float;
        var localFraction : float;

        localPermille = healthPermille(npc);

        if(localPermille < 0)
        {
            return;
        }

        record.lastLocalHp = localPermille;

        if(authorityPermille < 0)
        {
            return;
        }

        targetFraction = (float)authorityPermille / 1000.0;
        localFraction = (float)localPermille / 1000.0;

        if(AbsF(localFraction - targetFraction) < HEALTH_TOLERANCE)
        {
            return;
        }

        if(targetFraction > localFraction && (now - record.lastPredictAt) < PREDICT_GRACE)
        {
            return;
        }

        if(targetFraction <= 0.0)
        {
            return;
        }

        npc.SetHealthPerc(targetFraction);
        record.lastLocalHp = healthPermille(npc);
        statHealthSync += 1;
    }

    private function applyInboundHits(now : float)
    {
        var count : int;
        var index : int;
        var guid : int;
        var attackerId : int;
        var permille : int;
        var applied : float;
        var resulting : int;
        var i : int;

        count = WO_NpcHitCount();

        if(count <= 0)
        {
            return;
        }

        for(i = 0; i < count; i += 1)
        {
            guid = WO_NpcHitGuid(i);
            attackerId = WO_NpcHitAttacker(i);
            permille = WO_NpcHitPermille(i);
            index = findTrackedById(guid);

            if(index < 0)
            {
                WO_NpcHitAck(i, -1, false);
                statRejected += 1;
                continue;
            }

            applied = inflictRemoteDamage(tracked[index], attackerId, permille, now);
            resulting = healthPermille(tracked[index].actor);

            WO_NpcHitAck(i, resulting, applied > 0.0);

            if(applied > 0.0)
            {
                statHitsApplied += 1;

                if(debugLogging)
                {
                    dbg("npc_hit", "applied npc=" + guid
                        + " attacker=" + attackerId
                        + " permille=" + permille
                        + " damage=" + applied
                        + " hp=" + resulting);
                }
            }
            else
            {
                statRejected += 1;
            }
        }

        WO_NpcHitsDone();
    }

    private function inflictRemoteDamage(record : r_TrackedNpc, attackerId : int, permille : int, now : float) : float
    {
        var action : W3DamageAction;
        var attacker : CActor;
        var npc : CNewNPC;
        var maximum : float;
        var amount : float;

        npc = record.actor;

        if(!npc || !npc.IsAlive() || permille <= 0)
        {
            return 0.0;
        }

        if(permille > 1000)
        {
            permille = 1000;
        }

        maximum = npc.GetMaxHealth();

        if(maximum <= 0.0)
        {
            return 0.0;
        }

        amount = (((float)permille) / 1000.0) * maximum;

        if(amount <= 0.0)
        {
            return 0.0;
        }

        attacker = theGame.r_getMultiplayerClient().resolveTargetActor(attackerId);

        action = new W3DamageAction in this;
        action.Initialize(attacker, npc, NULL, "WitcherOnline", EHRT_None, CPS_Undefined, false, false, false, true);
        action.SetIgnoreArmor(true);
        action.AddDamage(theGame.params.DAMAGE_NAME_DIRECT, amount);

        theGame.damageMgr.ProcessAction(action);

        delete action;

        getAggro().noteDamage(record.npcId, attackerId, amount, now);

        return amount;
    }

    private function applyAcks()
    {
        var count : int;
        var canonicalId : int;
        var hp : int;
        var index : int;
        var i : int;

        count = WO_NpcAckCount();

        if(count <= 0)
        {
            return;
        }

        for(i = 0; i < count; i += 1)
        {
            canonicalId = WO_NpcAckId(i);
            hp = WO_NpcAckHp(i);
            index = findReplica(canonicalId);
            statAcks += 1;

            if(index < 0 || hp < 0)
            {
                continue;
            }

            if(!replicas[index].actor)
            {
                continue;
            }

            if(!WO_NpcAckOk(i))
            {
                statRejected += 1;
            }

            if(hp > 1000)
            {
                hp = 1000;
            }

            replicas[index].actor.SetHealthPerc((float)hp / 1000.0);
            replicas[index].lastLocalHp = hp;
            replicas[index].lastPredictAt = 0.0;
        }

        WO_NpcAcksDone();
    }

    private function writeDebug(now : float)
    {
        var i : int;

        dbg("npc_stats", "tracked=" + tracked.Size()
            + " replicas=" + replicas.Size()
            + " offered=" + statRegistered
            + " prefiltered=" + statPrefiltered
            + " dropped=" + statDropped
            + " droppedUntracked=" + statDroppedUntracked
            + " spawned=" + statSpawned
            + " despawned=" + statDespawned
            + " killed=" + statKilled
            + " promoted=" + statPromoted
            + " teleports=" + statTeleports
            + " targets=" + statTargets
            + " hitsSent=" + statHitsSent
            + " hitsApplied=" + statHitsApplied
            + " acks=" + statAcks
            + " rejected=" + statRejected
            + " healthSync=" + statHealthSync
            + " localDamage=" + statLocalDamage
            + " noTemplate=" + statNoTemplate
            + " scope=" + syncScopeLabel()
            + " gate=" + gateLabel()
            + " templateResolves=" + statTemplateResolves
            + " pathCache=" + pathCacheCount
            + " suspends=" + statSuspends
            + " rejectMemory=" + rejectedGuids.Size());

        dbg("npc_stats", "actionsSent=" + statActionSent
            + " applied=" + statActionApplied
            + " forced=" + statActionForced
            + " rejected=" + statActionRejected
            + " unknown=" + statActionUnknown
            + " probes=" + statActionProbes
            + " allowed=" + statActionAllowed
            + " suppressed=" + statActionSuppressed
            + " forceMode=" + actionForceMode);

        dbg("npc_net", WO_NpcReport() + " latencyMs=" + WO_NpcLatency());

        for(i = 0; i < replicas.Size(); i += 1)
        {
            if(i >= 6)
            {
                dbg("npc_net", "... " + (replicas.Size() - i) + " more replicas");
                break;
            }

            dbg("npc_net", "replica canonical=" + replicas[i].canonicalId
                + " code=" + replicas[i].typeCode
                + " guid=" + replicas[i].localGuid
                + " hp=" + replicas[i].lastLocalHp
                + " target=" + replicas[i].appliedTarget);
        }

        getAggro().writeDebug(debugLogging);
    }

    public function reportStats()
    {
        var previous : bool;

        previous = debugLogging;
        debugLogging = true;
        writeDebug(theGame.GetEngineTimeAsSeconds());
        debugLogging = previous;
    }
}

exec function wo_npc(enable : bool)
{
    theGame.r_getMultiplayerClient().getNpcSync().setEnabled(enable);
}

exec function wo_npc_debug(enable : bool)
{
    theGame.r_getMultiplayerClient().getNpcSync().setDebugLogging(enable);
}

exec function wo_npc_action_debug(enable : bool)
{
    theGame.r_getMultiplayerClient().getNpcSync().setActionLogging(enable);
}

exec function wo_npc_action_force(mode : int)
{
    theGame.r_getMultiplayerClient().getNpcSync().setActionForceMode(mode);
}

function wo_npcActionSuppress(npc : CNewNPC) : bool
{
    var client : r_MultiplayerClient;

    if(!npc)
    {
        return false;
    }

    client = theGame.r_getMultiplayerClient();

    if(!client)
    {
        return false;
    }

    return client.getNpcSync().shouldSuppressAction(npc);
}

function wo_npcActionGate(npc : CNewNPC) : bool
{
    var client : r_MultiplayerClient;

    if(!npc)
    {
        return true;
    }

    client = theGame.r_getMultiplayerClient();

    if(!client)
    {
        return true;
    }

    return client.getNpcSync().gateAction(npc);
}

function wo_npcActionReport(npc : CNewNPC, eventName : name)
{
    var client : r_MultiplayerClient;

    if(!npc)
    {
        return;
    }

    client = theGame.r_getMultiplayerClient();

    if(!client)
    {
        return;
    }

    client.getNpcSync().reportOwnedAction(npc, eventName);
}

exec function wo_npc_stats()
{
    theGame.r_getMultiplayerClient().getNpcSync().reportStats();
}

exec function wo_npc_tune(interpDelayMs : int, snapshotHz : int)
{
    WO_NpcTune(interpDelayMs, snapshotHz);
    WO_Note("[npc_debug] interpDelayMs=" + interpDelayMs + " snapshotHz=" + snapshotHz);
}

function wo_creditOwnedDamage(victim : CNewNPC, dealt : float, attacker : CGameplayEntity)
{
    if(!victim)
    {
        return;
    }

    theGame.r_getMultiplayerClient().getNpcSync().creditOwnedDamage(victim, dealt, attacker);
}

function wo_reportReplicaDamage(victim : CNewNPC, dealt : float, maximum : float)
{
    if(!victim || dealt <= 0.0)
    {
        return;
    }

    theGame.r_getMultiplayerClient().getNpcSync().reportReplicaDamage(victim, dealt, maximum);
}
