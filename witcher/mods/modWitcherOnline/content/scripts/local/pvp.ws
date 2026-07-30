enum r_EPvpMode
{
    PVP_Off     = 0,
    PVP_Neutral = 1,
    PVP_Enabled = 2
}

class r_PvpPolicy
{
    private var masterEnabled : bool;
    private var forcedMode    : r_EPvpMode;
    private var forced        : bool;
    private var passiveMode   : int;

    default masterEnabled = false;
    default forcedMode = PVP_Neutral;
    default forced = false;
    default passiveMode = 2;

    public function getPassiveMode() : int
    {
        return passiveMode;
    }

    public function setPassiveMode(value : int)
    {
        passiveMode = value;
    }

    public function setMasterEnabled(value : bool)
    {
        masterEnabled = value;
    }

    public function isMasterEnabled() : bool
    {
        return masterEnabled;
    }

    public function setForcedMode(mode : r_EPvpMode)
    {
        forcedMode = mode;
        forced = true;
    }

    public function clearForcedMode()
    {
        forced = false;
    }

    public function isForced() : bool
    {
        return forced;
    }

    public function describe() : string
    {
        return "master=" + masterEnabled + " forced=" + forced + " forcedMode=" + (int)forcedMode;
    }

    public function resolve(remote : r_RemotePlayer) : r_EPvpMode
    {
        if(forced)
        {
            return forcedMode;
        }

        if(!remote)
        {
            return PVP_Neutral;
        }

        if(!masterEnabled)
        {
            return PVP_Neutral;
        }

        if(isPartyMember(remote))
        {
            return PVP_Neutral;
        }

        if(hasDuelConsent(remote))
        {
            return PVP_Enabled;
        }

        if(bothInPvpZone(remote))
        {
            return PVP_Enabled;
        }

        if(bothPvpFlagged(remote))
        {
            return PVP_Enabled;
        }

        return PVP_Neutral;
    }

    private function isPartyMember(remote : r_RemotePlayer) : bool
    {
        var party : array<r_RemotePlayer>;
        var i : int;

        party = theGame.r_getMultiplayerClient().getPartyMembers();

        for(i = 0; i < party.Size(); i += 1)
        {
            if(party[i] == remote)
            {
                return true;
            }
        }

        return false;
    }

    private function hasDuelConsent(remote : r_RemotePlayer) : bool
    {
        return false;
    }

    private function bothInPvpZone(remote : r_RemotePlayer) : bool
    {
        return false;
    }

    private function bothPvpFlagged(remote : r_RemotePlayer) : bool
    {
        return false;
    }
}

function wo_forceNpcTarget(npc : CNewNPC, target : CActor)
{
    if(!npc || !target)
    {
        return;
    }

    npc.NoticeActor(target);
    npc.SignalGameplayEventParamObject('ForceTarget', target);
}

function wo_releaseNpcTarget(npc : CNewNPC)
{
    if(!npc)
    {
        return;
    }

    npc.SignalGameplayEvent('UnforceTarget');
}

function wo_idleReplicaAi(npc : CNewNPC) : int
{
    var idle : CAIMonsterIdleDefault;
    var forceId : int;

    if(!npc)
    {
        return -1;
    }

    idle = new CAIMonsterIdleDefault in npc;
    idle.Init();

    forceId = npc.ForceAIBehavior(idle, BTAP_AboveEmergency2, 'WO_Replica');

    if(forceId < 0)
    {
        WO_Note("[npc_spawn] idle AI force failed app=" + NameToString(npc.GetAppearance()));
    }

    return forceId;
}

function wo_setGhostAttackable(ghost : CActor, attackable : bool)
{
    if(!ghost)
    {
        return;
    }

    ghost.SetAttackableByPlayerPersistent(attackable);
    ghost.SetAttackableByPlayerRuntime(attackable);
}

function wo_ghostProtectionIntact(ghost : CActor, mode : r_EPvpMode) : bool
{
    if(!ghost)
    {
        return true;
    }

    if(mode == PVP_Enabled)
    {
        return ghost.IsAttackableByPlayer();
    }

    return ghost.IsInvulnerable() && !ghost.IsAttackableByPlayer();
}

function wo_applyGhostHostility(ghost : CActor, mode : r_EPvpMode)
{
    if(!ghost)
    {
        return;
    }

    if(mode == PVP_Enabled)
    {
        ghost.SetGameplayVisibility(true);
        ghost.EnableStaticCollisions(true);
        ghost.EnableCharacterCollisions(false);
        ghost.SetTemporaryAttitudeGroup('player', AGP_Default);
        ghost.SetImmortalityMode(AIM_None, AIC_Default, true);
        wo_setGhostAttackable(ghost, true);
        ghost.SetAttitude(thePlayer, AIA_Hostile);
        return;
    }

    if(mode == PVP_Neutral)
    {
        ghost.SetGameplayVisibility(true);
        ghost.EnableStaticCollisions(true);
        ghost.EnableCharacterCollisions(false);
        ghost.SetTemporaryAttitudeGroup('player', AGP_Default);
        ghost.SetImmortalityMode(AIM_Invulnerable, AIC_Default, true);
        wo_setGhostAttackable(ghost, false);
        ghost.SetAttitude(thePlayer, AIA_Neutral);
        return;
    }

    ghost.SetGameplayVisibility(false);
    ghost.EnableCollisions(false);
    ghost.EnableCharacterCollisions(false);
    ghost.SetImmortalityMode(AIM_Invulnerable, AIC_Default, true);
    wo_setGhostAttackable(ghost, false);
    ghost.SetTemporaryAttitudeGroup('friendly_to_player', AGP_Default);
    ghost.SetAttitude(thePlayer, AIA_Friendly);
}

exec function wo_pvp(enable : bool)
{
    var policy : r_PvpPolicy;

    policy = theGame.r_getMultiplayerClient().getPvpPolicy();

    if(enable)
    {
        policy.setForcedMode(PVP_Enabled);
    }
    else
    {
        policy.clearForcedMode();
    }

    theGame.r_getMultiplayerClient().refreshGhostHostility();
    WO_Note("pvp " + policy.describe());
}

exec function wo_ghost_mode(mode : int)
{
    var policy : r_PvpPolicy;

    policy = theGame.r_getMultiplayerClient().getPvpPolicy();

    if(mode < 0)
    {
        policy.clearForcedMode();
    }
    else
    {
        policy.setForcedMode((r_EPvpMode)mode);
    }

    theGame.r_getMultiplayerClient().refreshGhostHostility();
    WO_Note("ghost mode " + policy.describe());
}

exec function wo_npc_passive_mode(value : int)
{
    theGame.r_getMultiplayerClient().getPvpPolicy().setPassiveMode(value);
    WO_Note("npc passive mode=" + value + " (1=forget+combatoff 2=+friendly 3=forget only 4=combatoff only)");
}

exec function wo_pvp_master(enable : bool)
{
    theGame.r_getMultiplayerClient().getPvpPolicy().setMasterEnabled(enable);
    theGame.r_getMultiplayerClient().refreshGhostHostility();
    WO_Note("pvp master=" + enable);
}
