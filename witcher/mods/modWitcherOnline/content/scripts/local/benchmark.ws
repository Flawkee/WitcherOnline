import function WO_FrameReport() : bool;
import function WO_Note(text : string) : bool;

class r_Benchmark
{
    private var puppetsActive   : bool;
    private var puppetCount     : int;
    private var puppetAngle     : float;

    private var suppressActive  : bool;
    private var suppressRadius  : float;
    private var suppressDestroyed : int;
    private var suppressScans   : int;
    private var nextSuppressAt  : float;

    default puppetsActive = false;
    default puppetCount = 0;
    default puppetAngle = 0.0;
    default suppressActive = false;
    default suppressRadius = 100.0;
    default suppressDestroyed = 0;
    default suppressScans = 0;
    default nextSuppressAt = 0.0;

    public function isActive() : bool
    {
        return puppetsActive || suppressActive;
    }

    public function spawnPuppets(count : int)
    {
        var client : r_MultiplayerClient;
        var players : array<r_RemotePlayer>;
        var p : r_RemotePlayer;
        var base : Vector;
        var i : int;
        var angle : float;

        client = theGame.r_getMultiplayerClient();

        clearPuppets();

        if(count <= 0)
        {
            WO_Note("puppets cleared");
            return;
        }

        base = thePlayer.GetWorldPosition();

        for(i = 0; i < count; i += 1)
        {
            p = new r_RemotePlayer in client;
            p.Init();

            p.serverPlayerId = -1000 - i;
            p.id = "bench" + i;
            p.username = "bench" + i;
            p.idName = 'bench';
            p.area = theGame.GetCommonMapManager().GetCurrentArea();
            p.inGame = true;
            p.isAlive = true;
            p.lastUpdate = theGame.GetEngineTimeAsSeconds();

            angle = (360.0 * i) / count;
            p.pos = base + Vector(15.0 * CosF(Deg2Rad(angle)), 15.0 * SinF(Deg2Rad(angle)), 0.0);
            p.heading = angle;

            client.addBenchmarkPlayer(p);
        }

        puppetCount = count;
        puppetsActive = true;

        WO_Note("puppets spawned count=" + count);
    }

    public function clearPuppets()
    {
        if(!puppetsActive)
        {
            return;
        }

        theGame.r_getMultiplayerClient().removeBenchmarkPlayers();

        puppetsActive = false;
        puppetCount = 0;
    }

    public function setSuppress(enable : bool, radius : float)
    {
        suppressActive = enable;
        suppressRadius = radius;
        suppressDestroyed = 0;
        suppressScans = 0;
        nextSuppressAt = 0.0;

        if(enable)
        {
            WO_Note("suppress ON radius=" + radius);
        }
        else
        {
            WO_Note("suppress OFF destroyed=" + suppressDestroyed + " scans=" + suppressScans);
        }
    }

    public function reportSuppress()
    {
        WO_Note("suppress destroyed=" + suppressDestroyed + " scans=" + suppressScans + " radius=" + suppressRadius);
    }

    public function update()
    {
        if(puppetsActive)
        {
            drivePuppets();
        }

        if(suppressActive)
        {
            runSuppress();
        }
    }

    private function drivePuppets()
    {
        var client : r_MultiplayerClient;
        var players : array<r_RemotePlayer>;
        var base : Vector;
        var now : float;
        var i : int;
        var angle : float;

        client = theGame.r_getMultiplayerClient();
        players = client.getPlayers();
        base = thePlayer.GetWorldPosition();
        now = theGame.GetEngineTimeAsSeconds();

        puppetAngle += 0.6;

        for(i = 0; i < players.Size(); i += 1)
        {
            if(!players[i] || players[i].serverPlayerId > -1000)
            {
                continue;
            }

            angle = puppetAngle + (360.0 * i) / puppetCount;

            players[i].pos = base + Vector(15.0 * CosF(Deg2Rad(angle)), 15.0 * SinF(Deg2Rad(angle)), 0.0);
            players[i].heading = angle;
            players[i].speed = 1.0;
            players[i].lastUpdate = now;
            players[i].lastMovementSequence += 1;
        }
    }

    private function runSuppress()
    {
        var entities : array<CGameplayEntity>;
        var npc : CNewNPC;
        var classifier : r_EntityClassifier;
        var sample : r_SEntityClassSample;
        var now : float;
        var i : int;

        now = theGame.GetEngineTimeAsSeconds();

        if(nextSuppressAt > now + 1.0)
        {
            nextSuppressAt = 0.0;
        }

        if(now < nextSuppressAt)
        {
            return;
        }

        nextSuppressAt = now + 0.25;
        suppressScans += 1;

        classifier = theGame.r_getMultiplayerClient().getEntityClassifier();

        FindGameplayEntitiesInSphere(entities, thePlayer.GetWorldPosition(), suppressRadius, 512,,,,'CNewNPC');

        for(i = 0; i < entities.Size(); i += 1)
        {
            npc = (CNewNPC)entities[i];

            if(!npc || npc == thePlayer)
            {
                continue;
            }

            if(npc.HasTag('MPEntity'))
            {
                continue;
            }

            classifier.classify(npc, sample);

            if(!sample.syncEligible)
            {
                continue;
            }

            npc.Destroy();
            suppressDestroyed += 1;
        }
    }
}

exec function wo_puppets(count : int)
{
    theGame.r_getMultiplayerClient().getBenchmark().spawnPuppets(count);
}

exec function wo_suppress(enable : bool)
{
    theGame.r_getMultiplayerClient().getBenchmark().setSuppress(enable, 100.0);
}

exec function wo_suppress_stats()
{
    theGame.r_getMultiplayerClient().getBenchmark().reportSuppress();
}

exec function wo_frames()
{
    WO_FrameReport();
}
