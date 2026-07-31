exec function wo_sign_vfx(enable : bool)
{
    var players : array<r_RemotePlayer>;
    var i : int;

    players = theGame.r_getMultiplayerClient().getPlayers();

    for(i = 0; i < players.Size(); i += 1)
    {
        if(players[i])
        {
            players[i].signVfxEnabled = enable;
        }
    }

    WO_Note("[ghost_sign] vfx=" + enable + " players=" + players.Size());
}

class r_GhostSignOwner extends W3SignOwner
{
    public function InitGhost(ghostActor : CActor)
    {
        BaseInit(ghostActor);
    }
}

function wo_spawnGhostSign(ghost : CActor, signType : ESignType) : bool
{
    var witcher : W3PlayerWitcher;
    var template : CEntityTemplate;
    var signEnt : W3SignEntity;
    var signOwner : r_GhostSignOwner;
    var pos : Vector;
    var rot : EulerAngles;

    if(!ghost || signType == ST_None)
    {
        return false;
    }

    witcher = GetWitcherPlayer();

    if(!witcher)
    {
        return false;
    }

    template = witcher.GetSignTemplate(signType);

    if(!template)
    {
        return false;
    }

    pos = ghost.GetWorldPosition();
    rot = ghost.GetWorldRotation();

    if(signType == ST_Aard)
    {
        pos = pos + VecFromHeading(ghost.GetHeading()) * 0.5;
    }

    signEnt = (W3SignEntity)theGame.CreateEntity(template, pos, rot);

    if(!signEnt)
    {
        return false;
    }

    signOwner = new r_GhostSignOwner in signEnt;
    signOwner.InitGhost(ghost);

    signEnt.Init(signOwner, NULL, true, true);

    return true;
}
