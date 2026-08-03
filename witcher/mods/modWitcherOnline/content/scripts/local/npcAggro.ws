class r_AggroActor
{
    public var playerId  : int;
    public var threat    : float;
    public var lastHitAt : float;

    default playerId = 0;
    default threat = 0.0;
    default lastHitAt = 0.0;
}

class r_AggroNpc
{
    public var npcId          : int;
    public var actors         : array<r_AggroActor>;
    public var forcedTargetId : int;
    public var lastSwitchAt   : float;
    public var lastAssertAt   : float;
    public var lastUpdateAt   : float;

    default forcedTargetId = 0;
    default lastSwitchAt = 0.0;
    default lastAssertAt = 0.0;
    default lastUpdateAt = 0.0;
}

class r_NpcAggro
{
    private var states       : array<r_AggroNpc>;
    private var decayedAt    : float;
    private var debugLogging : bool;

    default debugLogging = false;

    private var statSwitches : int;
    private var statReports  : int;
    private var statForced   : int;
    private var statReleased : int;

    private var AGGRO_RANGE       : float;
    private var PAUSED_GRACE      : float;
    private var W_DAMAGE          : float;
    private var W_PROXIMITY       : float;
    private var W_LOW_HEALTH      : float;
    private var SWITCH_RATIO      : float;
    private var SWITCH_MARGIN     : float;
    private var SWITCH_COOLDOWN   : float;
    private var REASSERT_INTERVAL : float;
    private var THREAT_DECAY_TIME : float;
    private var THREAT_CAP        : float;
    private var STATE_GRACE       : float;

    default decayedAt = 0.0;
    default AGGRO_RANGE = 40.0;
    default PAUSED_GRACE = 1.5;
    default W_DAMAGE = 1.0;
    default W_PROXIMITY = 0.55;
    default W_LOW_HEALTH = 0.20;
    default SWITCH_RATIO = 1.30;
    default SWITCH_MARGIN = 0.10;
    default SWITCH_COOLDOWN = 3.0;
    default REASSERT_INTERVAL = 5.0;
    default THREAT_DECAY_TIME = 12.0;
    default THREAT_CAP = 100000.0;
    default STATE_GRACE = 60.0;

    private function clamp01(value : float) : float
    {
        if(value < 0.0)
        {
            return 0.0;
        }

        if(value > 1.0)
        {
            return 1.0;
        }

        return value;
    }

    private function healthFraction(value : float) : float
    {
        if(value < 0.0)
        {
            return 1.0;
        }

        return clamp01(value);
    }

    private function indexOfState(npcId : int) : int
    {
        var i : int;

        for(i = 0; i < states.Size(); i += 1)
        {
            if(states[i].npcId == npcId)
            {
                return i;
            }
        }

        return -1;
    }

    private function createState(npcId : int, now : float) : int
    {
        var record : r_AggroNpc;

        record = new r_AggroNpc in this;
        record.npcId = npcId;
        record.lastUpdateAt = now;

        states.PushBack(record);

        return states.Size() - 1;
    }

    private function indexOfActor(record : r_AggroNpc, playerId : int) : int
    {
        var i : int;

        for(i = 0; i < record.actors.Size(); i += 1)
        {
            if(record.actors[i].playerId == playerId)
            {
                return i;
            }
        }

        return -1;
    }

    private function threatOf(record : r_AggroNpc, playerId : int) : float
    {
        var index : int;

        index = indexOfActor(record, playerId);

        if(index < 0)
        {
            return 0.0;
        }

        return record.actors[index].threat;
    }

    public function noteDamage(npcId : int, attackerId : int, amount : float, now : float)
    {
        var record : r_AggroNpc;
        var actor : r_AggroActor;
        var recordIndex : int;
        var actorIndex : int;

        if(npcId == 0 || attackerId <= 0 || amount <= 0.0)
        {
            return;
        }

        recordIndex = indexOfState(npcId);

        if(recordIndex < 0)
        {
            recordIndex = createState(npcId, now);
        }

        record = states[recordIndex];
        actorIndex = indexOfActor(record, attackerId);

        if(actorIndex < 0)
        {
            actor = new r_AggroActor in this;
            actor.playerId = attackerId;
            record.actors.PushBack(actor);
            actorIndex = record.actors.Size() - 1;
        }

        record.actors[actorIndex].threat = MinF(THREAT_CAP, record.actors[actorIndex].threat + amount);
        record.actors[actorIndex].lastHitAt = now;
        record.lastUpdateAt = now;

        statReports += 1;
    }

    public function forcedTargetOf(npcId : int) : int
    {
        var index : int;

        index = indexOfState(npcId);

        if(index < 0)
        {
            return 0;
        }

        return states[index].forcedTargetId;
    }

    private function decayThreat(record : r_AggroNpc, dt : float)
    {
        var factor : float;
        var i : int;

        if(dt <= 0.0)
        {
            return;
        }

        factor = 1.0 - (dt / THREAT_DECAY_TIME);

        if(factor <= 0.0)
        {
            factor = 0.0;
        }

        for(i = 0; i < record.actors.Size(); i += 1)
        {
            record.actors[i].threat = record.actors[i].threat * factor;

            if(record.actors[i].threat < 0.01)
            {
                record.actors[i].threat = 0.0;
            }
        }
    }

    private function scoreOf(record : r_AggroNpc, playerId : int, distance : float, healthValue : float, maxThreat : float) : float
    {
        var damageTerm    : float;
        var proximityTerm : float;
        var healthTerm    : float;

        damageTerm = 0.0;

        if(maxThreat > 0.0)
        {
            damageTerm = threatOf(record, playerId) / maxThreat;
        }

        proximityTerm = clamp01(1.0 - (distance / AGGRO_RANGE));
        healthTerm = clamp01(1.0 - healthValue);

        return (W_DAMAGE * damageTerm) + (W_PROXIMITY * proximityTerm) + (W_LOW_HEALTH * healthTerm);
    }

    private function applyTarget(record : r_AggroNpc, npc : CNewNPC, playerId : int, now : float, isSwitch : bool)
    {
        var resolved : CActor;

        resolved = theGame.r_getMultiplayerClient().resolveTargetActor(playerId);

        if(!resolved)
        {
            return;
        }

        wo_forceNpcTarget(npc, resolved);

        if(debugLogging && record.forcedTargetId != playerId)
        {
            WO_Note("[npc_aggro] switch npc=" + record.npcId
                + " " + record.forcedTargetId + " -> " + playerId
                + " threat=" + threatOf(record, playerId));
        }

        record.forcedTargetId = playerId;
        record.lastAssertAt = now;
        statForced += 1;

        if(isSwitch)
        {
            record.lastSwitchAt = now;
            statSwitches += 1;
        }
    }

    public function setDebugLogging(value : bool)
    {
        debugLogging = value;
    }

    public function writeDebug(enabled : bool)
    {
        var line : string;
        var shown : int;
        var i : int;
        var j : int;

        debugLogging = enabled;

        if(!enabled)
        {
            return;
        }

        WO_Note("[npc_aggro] states=" + states.Size()
            + " switches=" + statSwitches
            + " forced=" + statForced
            + " released=" + statReleased
            + " damageReports=" + statReports);

        for(i = 0; i < states.Size(); i += 1)
        {
            if(states[i].forcedTargetId == 0 && states[i].actors.Size() == 0)
            {
                continue;
            }

            if(shown >= 6)
            {
                return;
            }

            line = "npc=" + states[i].npcId + " target=" + states[i].forcedTargetId;

            for(j = 0; j < states[i].actors.Size(); j += 1)
            {
                line += " p" + states[i].actors[j].playerId + "=" + states[i].actors[j].threat;
            }

            WO_Note("[npc_aggro] " + line);
            shown += 1;
        }
    }

    private function containsId(out ids : array<int>, value : int) : bool
    {
        var i : int;

        for(i = 0; i < ids.Size(); i += 1)
        {
            if(ids[i] == value)
            {
                return true;
            }
        }

        return false;
    }

    private function clearThreat(record : r_AggroNpc, playerId : int)
    {
        var i : int;

        for(i = 0; i < record.actors.Size(); i += 1)
        {
            if(record.actors[i].playerId == playerId)
            {
                record.actors[i].threat = 0.0;
                return;
            }
        }
    }

    private function releaseTarget(record : r_AggroNpc, npc : CNewNPC)
    {
        if(record.forcedTargetId == 0)
        {
            return;
        }

        if(npc)
        {
            wo_releaseNpcTarget(npc);
        }

        record.forcedTargetId = 0;
        statReleased += 1;
    }

    private function evaluate(record : r_AggroNpc, npc : CNewNPC, now : float, dt : float,
        out ids : array<int>, out actors : array<CActor>, out healths : array<float>)
    {
        var npcPos       : Vector;
        var actorPos     : Vector;
        var distance     : float;
        var threat       : float;
        var maxThreat    : float;
        var score        : float;
        var bestScore    : float;
        var currentScore : float;
        var bestId       : int;
        var i            : int;
        var engineTarget : CActor;
        var engineId     : int;

        decayThreat(record, dt);

        engineTarget = npc.GetTarget();

        if(engineTarget)
        {
            engineId = theGame.r_getMultiplayerClient().encodeTargetPlayerId(engineTarget);

            if(engineId > 0 && !containsId(ids, engineId))
            {
                if(debugLogging)
                {
                    WO_Note("[npc_aggro] release npc=" + record.npcId
                        + " target=" + engineId
                        + " reason=out_of_scope");
                }

                clearThreat(record, engineId);

                if(record.forcedTargetId == engineId)
                {
                    releaseTarget(record, npc);
                }
                else
                {
                    wo_releaseNpcTarget(npc);
                }
            }
        }

        if(record.forcedTargetId != 0 && !containsId(ids, record.forcedTargetId))
        {
            if(debugLogging)
            {
                WO_Note("[npc_aggro] release npc=" + record.npcId
                    + " target=" + record.forcedTargetId
                    + " reason=paused_or_absent");
            }

            clearThreat(record, record.forcedTargetId);
            releaseTarget(record, npc);
        }

        npcPos = npc.GetWorldPosition();
        maxThreat = 0.0;

        for(i = 0; i < ids.Size(); i += 1)
        {
            threat = threatOf(record, ids[i]);

            if(threat > maxThreat)
            {
                maxThreat = threat;
            }
        }

        bestId = 0;
        bestScore = 0.0;
        currentScore = -1.0;

        for(i = 0; i < ids.Size(); i += 1)
        {
            if(!actors[i] || !actors[i].IsAlive())
            {
                continue;
            }

            actorPos = actors[i].GetWorldPosition();
            distance = VecDistance(npcPos, actorPos);

            if(distance > AGGRO_RANGE)
            {
                continue;
            }

            score = scoreOf(record, ids[i], distance, healths[i], maxThreat);

            if(ids[i] == record.forcedTargetId)
            {
                currentScore = score;
            }

            if(score > bestScore)
            {
                bestScore = score;
                bestId = ids[i];
            }
        }

        if(bestId == 0)
        {
            releaseTarget(record, npc);
            return;
        }

        if(record.forcedTargetId == bestId)
        {
            if((now - record.lastAssertAt) >= REASSERT_INTERVAL)
            {
                applyTarget(record, npc, bestId, now, false);
            }

            return;
        }

        if(currentScore < 0.0)
        {
            applyTarget(record, npc, bestId, now, true);
            return;
        }

        if((now - record.lastSwitchAt) < SWITCH_COOLDOWN)
        {
            return;
        }

        if(bestScore < (currentScore * SWITCH_RATIO))
        {
            return;
        }

        if((bestScore - currentScore) < SWITCH_MARGIN)
        {
            return;
        }

        applyTarget(record, npc, bestId, now, true);
    }

    public function update(now : float, out trackedList : array<r_TrackedNpc>)
    {
        var client   : r_MultiplayerClient;
        var players  : array<r_RemotePlayer>;
        var ids      : array<int>;
        var actors   : array<CActor>;
        var healths  : array<float>;
        var questIds : array<int>;
        var questActors : array<CActor>;
        var questHealths : array<float>;
        var npc      : CNewNPC;
        var localId  : int;
        var index    : int;
        var dt       : float;
        var i        : int;

        client = theGame.r_getMultiplayerClient();

        if(!client || !thePlayer)
        {
            return;
        }

        localId = client.getServerId();

        if(localId <= 0)
        {
            return;
        }

        dt = now - decayedAt;

        if(dt < 0.0 || dt > 30.0)
        {
            dt = 0.0;
        }

        decayedAt = now;

        ids.PushBack(localId);
        actors.PushBack(thePlayer);
        healths.PushBack(healthFraction(thePlayer.GetHealthPercents()));

        if(client.isQuestCoopPlayer(localId))
        {
            questIds.PushBack(localId);
            questActors.PushBack(thePlayer);
            questHealths.PushBack(healthFraction(thePlayer.GetHealthPercents()));
        }

        players = client.getPlayers();

        for(i = 0; i < players.Size(); i += 1)
        {
            if(!players[i].ghost || players[i].serverPlayerId <= 0)
            {
                continue;
            }

            if(WO_IsPlayerPaused(players[i].serverPlayerId))
            {
                continue;
            }

            if((now - players[i].lastUpdate) > PAUSED_GRACE)
            {
                continue;
            }

            if(!client.sharesNpcScope(players[i].serverPlayerId))
            {
                continue;
            }

            ids.PushBack(players[i].serverPlayerId);
            actors.PushBack(players[i].ghost);
            healths.PushBack(healthFraction(players[i].health));

            if(client.isQuestCoopPlayer(players[i].serverPlayerId))
            {
                questIds.PushBack(players[i].serverPlayerId);
                questActors.PushBack(players[i].ghost);
                questHealths.PushBack(healthFraction(players[i].health));
            }
        }

        for(i = 0; i < trackedList.Size(); i += 1)
        {
            npc = trackedList[i].actor;

            if(!npc)
            {
                continue;
            }

            index = indexOfState(trackedList[i].npcId);

            if(!npc.IsAlive()
                || (trackedList[i].questTag == "" && !npc.IsInCombat()))
            {
                if(index >= 0)
                {
                    releaseTarget(states[index], npc);
                    states[index].lastUpdateAt = now;
                }

                trackedList[i].cachedForcedTarget = 0;
                continue;
            }

            if(index < 0)
            {
                index = createState(trackedList[i].npcId, now);
            }

            states[index].lastUpdateAt = now;

            if(trackedList[i].questTag != "")
            {
                evaluate(states[index], npc, now, dt, questIds, questActors, questHealths);
            }
            else
            {
                evaluate(states[index], npc, now, dt, ids, actors, healths);
            }

            trackedList[i].cachedForcedTarget = states[index].forcedTargetId;
        }

        pruneStates(now);
    }

    private function pruneStates(now : float)
    {
        var i : int;

        for(i = states.Size() - 1; i >= 0; i -= 1)
        {
            if((now - states[i].lastUpdateAt) > STATE_GRACE)
            {
                states.Erase(i);
            }
        }
    }

    public function forget(npcId : int)
    {
        var index : int;

        index = indexOfState(npcId);

        if(index >= 0)
        {
            states.Erase(index);
        }
    }

    public function reset()
    {
        states.Clear();
    }

    public function reportStats()
    {
        WO_Note("npcaggro states=" + states.Size()
            + " switches=" + statSwitches
            + " forced=" + statForced
            + " released=" + statReleased
            + " damageReports=" + statReports);
    }

    public function describe(maxEntries : int)
    {
        var line : string;
        var shown : int;
        var i : int;
        var j : int;

        shown = 0;

        for(i = 0; i < states.Size(); i += 1)
        {
            if(states[i].forcedTargetId == 0 && states[i].actors.Size() == 0)
            {
                continue;
            }

            line = "aggro npc=" + states[i].npcId + " target=" + states[i].forcedTargetId;

            for(j = 0; j < states[i].actors.Size(); j += 1)
            {
                line += " | p" + states[i].actors[j].playerId + "=" + states[i].actors[j].threat;
            }

            WO_Note(line);
            shown += 1;

            if(shown >= maxEntries)
            {
                return;
            }
        }

        if(shown == 0)
        {
            WO_Note("aggro: no active states");
        }
    }
}
