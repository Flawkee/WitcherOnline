enum r_EEntityClass
{
    REC_Unknown     = 0,
    REC_Multiplayer = 1,
    REC_Quest       = 2,
    REC_Ambient     = 3,
    REC_Wildlife    = 4,
    REC_Monster     = 5,
    REC_Vehicle     = 6
}

struct r_SEntityClassSample
{
    var entityClass  : r_EEntityClass;
    var reason       : string;
    var tags         : string;
    var syncEligible : bool;
    var hostileNow   : bool;
    var questTag     : string;
    var questEnemy   : bool;
}

class r_EntityClassifier
{
    private var enabled           : bool;
    private var overlayEnabled    : bool;
    private var showTags          : bool;
    private var scanRadius        : float;
    private var scanInterval      : float;
    private var nextScanAt        : float;
    private var overlayIntTagSeed : int;

    private var sampleTotal       : int;
    private var sampleUnknown     : int;
    private var sampleMultiplayer : int;
    private var sampleQuest       : int;
    private var sampleAmbient     : int;
    private var sampleWildlife    : int;
    private var sampleMonster     : int;
    private var sampleVehicle     : int;

    private var lastVisibleCount  : int;

    default enabled = false;
    default overlayEnabled = true;
    default showTags = false;
    default scanRadius = 60.0;
    default scanInterval = 1.0;
    default nextScanAt = 0.0;
    default overlayIntTagSeed = 900000;

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
        nextScanAt = 0.0;

        if(!enabled)
        {
            clearOverlay();
        }
    }

    public function setOverlayEnabled(value : bool)
    {
        overlayEnabled = value;

        if(!overlayEnabled)
        {
            clearOverlay();
        }
    }

    public function setShowTags(value : bool)
    {
        showTags = value;
    }

    public function setScanRadius(value : float)
    {
        if(value < 5.0)
        {
            value = 5.0;
        }

        if(value > 250.0)
        {
            value = 250.0;
        }

        scanRadius = value;
    }

    public function resetSamples()
    {
        sampleTotal = 0;
        sampleUnknown = 0;
        sampleMultiplayer = 0;
        sampleQuest = 0;
        sampleAmbient = 0;
        sampleWildlife = 0;
        sampleMonster = 0;
        sampleVehicle = 0;
    }

    private function tagsToString(tags : array<name>) : string
    {
        var joined : string;
        var i : int;

        for(i = 0; i < tags.Size(); i += 1)
        {
            if(i > 0)
            {
                joined += ",";
            }

            joined += NameToString(tags[i]);
        }

        if(joined == "")
        {
            joined = "-";
        }

        return joined;
    }

    private function isVehicleActor(npc : CNewNPC) : bool
    {
        return npc.HasTag('vehicle')
            || npc.HasTag('horse')
            || npc.HasTag('playerHorse')
            || npc.HasTag('PLAYER_horse')
            || npc.HasTag('online_horse')
            || npc.HasTag('wo_horse')
            || npc.HasTag('boat');
    }

    private function questTagPrefixes() : array<string>
    {
        var prefixes : array<string>;

        prefixes.PushBack("mq");
        prefixes.PushBack("sq");
        prefixes.PushBack("q0");
        prefixes.PushBack("q1");
        prefixes.PushBack("q2");
        prefixes.PushBack("q3");
        prefixes.PushBack("q4");
        prefixes.PushBack("q5");
        prefixes.PushBack("q6");
        prefixes.PushBack("q7");
        prefixes.PushBack("q8");
        prefixes.PushBack("q9");
        prefixes.PushBack("ep1_");
        prefixes.PushBack("ep2_");

        return prefixes;
    }

    private function looksLikeQuestTag(text : string, prefix : string) : bool
    {
        var rest : string;
        var head : string;

        rest = StrAfterFirst(text, prefix);

        if(rest == "")
        {
            return false;
        }

        head = StrLeft(rest, 1);

        return head == "0" || head == "1" || head == "2" || head == "3" || head == "4"
            || head == "5" || head == "6" || head == "7" || head == "8" || head == "9";
    }

    private function isDigit(text : string) : bool
    {
        return text == "0" || text == "1" || text == "2" || text == "3" || text == "4"
            || text == "5" || text == "6" || text == "7" || text == "8" || text == "9";
    }

    private function stripLeadingUnderscores(text : string) : string
    {
        var length : int;
        var start  : int;

        length = StrLen(text);
        start = 0;

        while(start < length && StrMid(text, start, 1) == "_")
        {
            start += 1;
        }

        if(start == 0)
        {
            return text;
        }

        return StrMid(text, start, length - start);
    }

    private function looksLikeQuestName(text : string) : bool
    {
        var body : string;
        var head : string;
        var at   : int;

        if(text == "")
        {
            return false;
        }

        body = stripLeadingUnderscores(StrLower(text));

        if(body == "")
        {
            return false;
        }

        at = 0;
        head = StrMid(body, 0, 1);

        if(head == "m" || head == "s")
        {
            at = 1;
        }

        if(StrMid(body, at, 1) != "q")
        {
            return false;
        }

        return isDigit(StrMid(body, at + 1, 1));
    }

    public function isFightCapable(npc : CNewNPC) : bool
    {
        var maximum : float;

        maximum = npc.GetMaxHealth();

        if(maximum > 5.0)
        {
            return true;
        }

        return npc.GetAttitude(thePlayer) == AIA_Hostile;
    }

    private function hasQuestTag(tags : array<name>, out matched : string) : bool
    {
        var prefixes : array<string>;
        var text : string;
        var i : int;
        var p : int;

        prefixes = questTagPrefixes();

        for(i = 0; i < tags.Size(); i += 1)
        {
            text = NameToString(tags[i]);

            for(p = 0; p < prefixes.Size(); p += 1)
            {
                if(!StrBeginsWith(text, prefixes[p]))
                {
                    continue;
                }

                if(!looksLikeQuestTag(text, prefixes[p]))
                {
                    continue;
                }

                matched = text;
                return true;
            }
        }

        return false;
    }

    public function classify(npc : CNewNPC, out sample : r_SEntityClassSample)
    {
        var tags : array<name>;
        var questTag : string;
        var appearance : string;
        var entityName : string;

        sample.entityClass = REC_Unknown;
        sample.reason = "unclassified";
        sample.tags = "-";
        sample.syncEligible = false;
        sample.hostileNow = false;
        sample.questTag = "";
        sample.questEnemy = false;

        if(!npc)
        {
            return;
        }

        tags = npc.GetTags();
        sample.tags = tagsToString(tags);
        sample.hostileNow = npc.GetAttitude(thePlayer) == AIA_Hostile;

        if(npc == thePlayer)
        {
            sample.entityClass = REC_Multiplayer;
            sample.reason = "local player";
            return;
        }

        if(npc.HasTag('MPEntity') || npc.HasTag('WOReplica') || npc.HasTag('online_horse') || npc.HasTag('wo_horse'))
        {
            sample.entityClass = REC_Multiplayer;
            sample.reason = "mp entity";
            return;
        }

        if(isVehicleActor(npc))
        {
            sample.entityClass = REC_Vehicle;
            sample.reason = "mount or vehicle";
            return;
        }

        if(npc.IsVIP())
        {
            sample.entityClass = REC_Quest;
            sample.reason = "vip";
            return;
        }

        if(hasQuestTag(tags, questTag))
        {
            sample.entityClass = REC_Quest;
            sample.reason = "quest tag " + questTag;
            sample.questTag = questTag;
            sample.questEnemy = questEnemyNow(npc, sample.hostileNow);
            return;
        }

        appearance = NameToString(npc.GetAppearance());

        if(looksLikeQuestName(appearance))
        {
            sample.entityClass = REC_Quest;
            sample.reason = "quest appearance " + appearance;
            return;
        }

        entityName = npc.GetName();

        if(looksLikeQuestName(entityName))
        {
            sample.entityClass = REC_Quest;
            sample.reason = "quest name " + entityName;
            return;
        }

        if(npc.IsMonster())
        {
            sample.entityClass = REC_Monster;
            sample.reason = "monster";
            sample.syncEligible = true;
            return;
        }

        if(!npc.IsHuman())
        {
            sample.entityClass = REC_Wildlife;
            sample.reason = "animal";
            sample.syncEligible = true;
            return;
        }

        if(!npc.GetVisibility() || !npc.GetGameplayVisibility())
        {
            sample.entityClass = REC_Unknown;
            sample.reason = "hidden";
            return;
        }

        if(isFightCapable(npc))
        {
            sample.entityClass = REC_Ambient;
            sample.reason = "human combatant hp=" + npc.GetMaxHealth();
            sample.syncEligible = true;
            return;
        }

        sample.entityClass = REC_Ambient;
        sample.reason = "human cosmetic hp=" + npc.GetMaxHealth();
    }

    public function isSyncEligible(npc : CNewNPC) : bool
    {
        var sample : r_SEntityClassSample;

        classify(npc, sample);

        return sample.syncEligible;
    }

    public function looksQuestBound(npc : CNewNPC) : bool
    {
        var tags : array<name>;
        var matched : string;

        if(!npc)
        {
            return false;
        }

        tags = npc.GetTags();

        if(hasQuestTag(tags, matched))
        {
            return true;
        }

        if(looksLikeQuestName(NameToString(npc.GetAppearance())))
        {
            return true;
        }

        if(looksLikeQuestName(npc.GetName()))
        {
            return true;
        }

        return npc.IsVIP();
    }

    private function questEnemyNow(npc : CNewNPC, hostile : bool) : bool
    {
        if(!hostile || !npc.IsAlive())
        {
            return false;
        }

        if(!npc.GetVisibility() || !npc.GetGameplayVisibility())
        {
            return false;
        }

        return npc.IsMonster() || isFightCapable(npc);
    }

    public function questEnemyTag(npc : CNewNPC) : string
    {
        var sample : r_SEntityClassSample;

        classify(npc, sample);

        if(!sample.questEnemy)
        {
            return "";
        }

        return sample.questTag;
    }

    public function update()
    {
        var now : float;

        if(!enabled)
        {
            return;
        }

        now = theGame.GetEngineTimeAsSeconds();

        if(nextScanAt > now + scanInterval)
        {
            nextScanAt = 0.0;
        }

        if(now < nextScanAt)
        {
            return;
        }

        nextScanAt = now + scanInterval;
        scan();
    }

    private function collectNearby(out entities : array<CGameplayEntity>)
    {
        FindGameplayEntitiesInSphere(entities, thePlayer.GetWorldPosition(), scanRadius, 512,,,,'CNewNPC');
    }

    private function scan()
    {
        var entities : array<CGameplayEntity>;
        var npc : CNewNPC;
        var sample : r_SEntityClassSample;
        var i : int;
        var visible : int;

        collectNearby(entities);
        clearOverlay();

        for(i = 0; i < entities.Size(); i += 1)
        {
            npc = (CNewNPC)entities[i];

            if(!npc || npc == thePlayer)
            {
                continue;
            }

            classify(npc, sample);
            recordSample(sample.entityClass);

            if(overlayEnabled)
            {
                createOverlayLabel(npc, sample, visible);
                visible += 1;
            }
        }

        lastVisibleCount = visible;
    }

    public function dumpNearby()
    {
        var entities : array<CGameplayEntity>;
        var npc : CNewNPC;
        var sample : r_SEntityClassSample;
        var i : int;

        collectNearby(entities);

        Log("woclass dump begin radius=" + scanRadius);

        for(i = 0; i < entities.Size(); i += 1)
        {
            npc = (CNewNPC)entities[i];

            if(!npc || npc == thePlayer)
            {
                continue;
            }

            classify(npc, sample);

            Log("woclass entity"
                + " class=" + classLabel(sample.entityClass)
                + " sync=" + sample.syncEligible
                + " human=" + npc.IsHuman()
                + " monster=" + npc.IsMonster()
                + " vip=" + npc.IsVIP()
                + " alive=" + npc.IsAlive()
                + " hostile=" + sample.hostileNow
                + " dist=" + VecDistance(thePlayer.GetWorldPosition(), npc.GetWorldPosition())
                + " tags=[" + sample.tags + "]");
        }

        Log("woclass dump end");
    }

    private function recordSample(entityClass : r_EEntityClass)
    {
        sampleTotal += 1;

        switch(entityClass)
        {
            case REC_Multiplayer:
                sampleMultiplayer += 1;
                break;

            case REC_Quest:
                sampleQuest += 1;
                break;

            case REC_Ambient:
                sampleAmbient += 1;
                break;

            case REC_Wildlife:
                sampleWildlife += 1;
                break;

            case REC_Monster:
                sampleMonster += 1;
                break;

            case REC_Vehicle:
                sampleVehicle += 1;
                break;

            default:
                sampleUnknown += 1;
                break;
        }
    }

    private function createOverlayLabel(npc : CNewNPC, sample : r_SEntityClassSample, index : int)
    {
        var oneliner : MP_SU_OnelinerEntity;
        var text : string;

        text = classLabel(sample.entityClass);

        if(sample.syncEligible)
        {
            text += " [sync]";
        }

        if(showTags)
        {
            text += " " + sample.tags;
        }
        else
        {
            text += " (" + sample.reason + ")";
        }

        oneliner = new MP_SU_OnelinerEntity in theInput;

        oneliner.text = (new MP_SUOL_TagBuilder in theInput)
            .tag("font")
            .attr("size", "14")
            .attr("color", classColor(sample.entityClass))
            .text(text);

        oneliner.visible = true;
        oneliner.entity = npc;
        oneliner.head = false;
        oneliner.offset = Vector(0.0, 0.0, 2.0);
        oneliner.tag = "WOClassifier";
        oneliner.intTag = overlayIntTagSeed + index;
        oneliner.render_distance = (int)scanRadius;

        MP_SUOL_getManager().createOneliner(oneliner);
    }

    private function clearOverlay()
    {
        MP_SUOL_getManager().deleteByTag("WOClassifier");
    }

    public function classLabel(entityClass : r_EEntityClass) : string
    {
        if(entityClass == REC_Multiplayer)
        {
            return "MP";
        }

        if(entityClass == REC_Quest)
        {
            return "QUEST";
        }

        if(entityClass == REC_Ambient)
        {
            return "AMBIENT";
        }

        if(entityClass == REC_Wildlife)
        {
            return "WILDLIFE";
        }

        if(entityClass == REC_Monster)
        {
            return "MONSTER";
        }

        if(entityClass == REC_Vehicle)
        {
            return "VEHICLE";
        }

        return "UNKNOWN";
    }

    private function classColor(entityClass : r_EEntityClass) : string
    {
        if(entityClass == REC_Multiplayer)
        {
            return "#5ACCF6";
        }

        if(entityClass == REC_Quest)
        {
            return "#F65A5A";
        }

        if(entityClass == REC_Ambient)
        {
            return "#7CF65A";
        }

        if(entityClass == REC_Wildlife)
        {
            return "#C8F65A";
        }

        if(entityClass == REC_Monster)
        {
            return "#F6A85A";
        }

        if(entityClass == REC_Vehicle)
        {
            return "#D18AF6";
        }

        return "#BBBBBB";
    }

    public function logSummary()
    {
        var area : int;

        area = (int)theGame.GetCommonMapManager().GetCurrentArea();

        Log("woclass area=" + area
            + " radius=" + scanRadius
            + " visible=" + lastVisibleCount
            + " total=" + sampleTotal
            + " ambient=" + sampleAmbient
            + " wildlife=" + sampleWildlife
            + " monster=" + sampleMonster
            + " vehicle=" + sampleVehicle
            + " quest=" + sampleQuest
            + " mp=" + sampleMultiplayer
            + " unknown=" + sampleUnknown);
    }
}

exec function wo_classify(enable : bool)
{
    theGame.r_getMultiplayerClient().getEntityClassifier().setEnabled(enable);
}

exec function wo_classify_overlay(enable : bool)
{
    theGame.r_getMultiplayerClient().getEntityClassifier().setOverlayEnabled(enable);
}

exec function wo_classify_tags(enable : bool)
{
    theGame.r_getMultiplayerClient().getEntityClassifier().setShowTags(enable);
}

exec function wo_classify_radius(radius : float)
{
    theGame.r_getMultiplayerClient().getEntityClassifier().setScanRadius(radius);
}

exec function wo_classify_dump()
{
    theGame.r_getMultiplayerClient().getEntityClassifier().dumpNearby();
}

exec function wo_classify_stats()
{
    theGame.r_getMultiplayerClient().getEntityClassifier().logSummary();
}

exec function wo_classify_reset()
{
    theGame.r_getMultiplayerClient().getEntityClassifier().resetSamples();
}
