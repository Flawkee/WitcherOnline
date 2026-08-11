class r_DuelController
{
    private var opponentId : int;
    private var opponentName : string;
    private var active : bool;
    private var countdownUntil : float;
    private var countdownSafetyAt : float;
    private var opponentHealth : int;
    private var localHealth : int;
    private var pendingDamage : int;
    private var pendingHealing : float;
    private var lastDamageSentAt : float;
    private var lastHealingSentAt : float;
    private var cancelSent : bool;
    private var vitalityHudModule : CHudModule;
    private var vitalityHudFunction : CScriptedFlashFunction;

    default opponentId = 0;
    default opponentName = "";
    default active = false;
    default countdownUntil = -1.0;
    default countdownSafetyAt = -1.0;
    default opponentHealth = 100000;
    default localHealth = 100000;
    default pendingDamage = 0;
    default pendingHealing = 0.0;
    default lastDamageSentAt = -1.0;
    default lastHealingSentAt = -1.0;
    default cancelSent = false;
    public function hasOpponent(remote : r_RemotePlayer) : bool
    {
        return remote && opponentId > 0 && remote.serverPlayerId == opponentId;
    }

    public function isActiveWith(remote : r_RemotePlayer) : bool
    {
        return active && hasOpponent(remote);
    }

    public function isActive() : bool
    {
        return active;
    }

    public function getOpponentName() : string
    {
        return opponentName;
    }

    public function canRequest() : bool
    {
        if(!thePlayer || !WO_Connected() || !theGame.r_getMultiplayerClient().getInGame())
        {
            return false;
        }

        if(!thePlayer.IsAlive() || thePlayer.IsInCombat() || theGame.IsDialogOrCutscenePlaying()
            || theGame.IsCurrentlyPlayingNonGameplayScene() || theGame.GetGuiManager().IsAnyMenu())
        {
            return false;
        }

        return true;
    }

    public function canContinue() : bool
    {
        if(!thePlayer || !thePlayer.IsAlive() || theGame.IsDialogOrCutscenePlaying()
            || theGame.IsCurrentlyPlayingNonGameplayScene() || theGame.GetGuiManager().IsAnyMenu())
        {
            return false;
        }

        return true;
    }

    public function canAccept() : bool
    {
        if(!thePlayer || !WO_Connected())
        {
            return false;
        }

        if(!thePlayer.IsAlive() || thePlayer.IsInCombat() || theGame.IsDialogOrCutscenePlaying()
            || theGame.IsCurrentlyPlayingNonGameplayScene())
        {
            return false;
        }

        return true;
    }

    public function localHealthUnits() : int
    {
        var value : int;

        if(!thePlayer)
        {
            return 100000;
        }

        value = (int)(thePlayer.GetHealthPercents() * 100000.0);
        return wo_clampDuelHealth(value);
    }

    public function beginCountdown(playerName : string)
    {
        opponentName = playerName;
        active = false;
        countdownUntil = theGame.GetEngineTimeAsSeconds() + 5.0;
        countdownSafetyAt = theGame.GetEngineTimeAsSeconds() + 1.0;
        opponentHealth = 100000;
        localHealth = localHealthUnits();
        pendingDamage = 0;
        pendingHealing = 0.0;
        lastDamageSentAt = -1.0;
        lastHealingSentAt = -1.0;
        cancelSent = false;
        GetWitcherPlayer().DisplayHudMessage(StrReplace(GetLocStringById(2111114327), "%s", opponentName));
    }

    public function begin(playerName : string)
    {
        opponentName = playerName;
        active = true;
        countdownUntil = -1.0;
        countdownSafetyAt = -1.0;
        pendingDamage = 0;
        pendingHealing = 0.0;
        lastDamageSentAt = -1.0;
        lastHealingSentAt = -1.0;
        cancelSent = false;
        vitalityHudModule = NULL;
        vitalityHudFunction = NULL;
        thePlayer.SetHealthPerc(1.0);
        thePlayer.SetImmortalityMode(AIM_Immortal, AIC_IsAttackableByPlayer, true);
        refreshGhosts();
    }

    public function setHealth(firstId : int, firstHealth : int, secondId : int, secondHealth : int)
    {
        applyHealth(firstId, firstHealth);
        applyHealth(secondId, secondHealth);

        if(active && (firstHealth <= 0 || secondHealth <= 0))
        {
            finish();
        }
    }

    private function applyHealth(playerId : int, health : int)
    {
        var remote : r_RemotePlayer;
        var value : float;

        if(playerId == theGame.r_getMultiplayerClient().getServerId())
        {
            localHealth = wo_clampDuelHealth(health);
            applyLocalHealthHud();
            return;
        }

        value = MaxF(0.05, wo_clampDuelHealth(health) / 100000.0);

        remote = theGame.r_getMultiplayerClient().getPlayerByServerId(playerId);

        if(remote && remote.ghost)
        {
            if(playerId == opponentId)
            {
                opponentHealth = wo_clampDuelHealth(health);
            }

            remote.ghost.SetHealthPerc(value);
        }
    }

    public function restoreGhostHealth(remote : r_RemotePlayer)
    {
        if(isActiveWith(remote) && remote.ghost)
        {
            remote.ghost.SetHealthPerc(MaxF(0.05, opponentHealth / 100000.0));
        }
    }

    public function noteDamage(remote : r_RemotePlayer, damage : float, maximum : float)
    {
        var units : int;
        var now : float;

        if(!isActiveWith(remote) || damage <= 0.0 || maximum <= 0.0)
        {
            return;
        }

        units = wo_clampDuelHealth((int)((damage / maximum) * 100000.0));

        if(units < 1)
        {
            units = 1;
        }

        pendingDamage = wo_clampDuelHealth(pendingDamage + units);
        now = theGame.GetEngineTimeAsSeconds();

        if(lastDamageSentAt < 0.0 || (now - lastDamageSentAt) >= 0.05)
        {
            WO_DuelHit(opponentId, pendingDamage);
            pendingDamage = 0;
            lastDamageSentAt = now;
        }
    }

    public function noteHealing(amount : float, maximum : float)
    {
        if(!active || amount <= 0.0 || maximum <= 0.0 || localHealth >= 100000)
        {
            return;
        }

        pendingHealing += (amount / maximum) * 100000.0;
    }

    public function update()
    {
        var now : float;
        var healing : int;
        var remote : r_RemotePlayer;

        now = theGame.GetEngineTimeAsSeconds();

        if(countdownUntil > 0.0)
        {
            if(now >= countdownSafetyAt && (thePlayer.IsInCombat() || !canContinue()) && !cancelSent)
            {
                cancelSent = true;
                WO_DuelCancel(opponentName);
            }
        }

        if(!active)
        {
            return;
        }

        thePlayer.SetImmortalityMode(AIM_Immortal, AIC_IsAttackableByPlayer, true);

        if(thePlayer.GetHealth() < thePlayer.GetMaxHealth())
        {
            thePlayer.ForceSetStat(BCS_Vitality, thePlayer.GetMaxHealth());
        }

        applyLocalHealthHud();

        remote = theGame.r_getMultiplayerClient().getPlayerByServerId(opponentId);

        if(remote && remote.ghost)
        {
            remote.ghost.SetHealthPerc(MaxF(0.05, opponentHealth / 100000.0));
        }

        if(!canContinue())
        {
            if(!cancelSent)
            {
                cancelSent = true;
                WO_DuelCancel(opponentName);
            }

            return;
        }

        if(pendingDamage > 0 && (now - lastDamageSentAt) >= 0.05)
        {
            WO_DuelHit(opponentId, pendingDamage);
            pendingDamage = 0;
            lastDamageSentAt = now;
        }

        if(pendingHealing >= 1.0 && (now - lastHealingSentAt) >= 0.10)
        {
            healing = (int)pendingHealing;
            pendingHealing -= healing;
            WO_DuelHeal(healing);
            lastHealingSentAt = now;
        }
    }

    public function finish()
    {
        var remote : r_RemotePlayer;
        var wasActive : bool;

        wasActive = active;
        pendingDamage = 0;
        pendingHealing = 0.0;
        lastDamageSentAt = -1.0;
        lastHealingSentAt = -1.0;
        active = false;
        countdownUntil = -1.0;
        countdownSafetyAt = -1.0;
        opponentHealth = 100000;
        localHealth = 100000;
        cancelSent = false;

        if(thePlayer && wasActive)
        {
            thePlayer.SetHealthPerc(1.0);
            thePlayer.SetImmortalityMode(AIM_None, AIC_IsAttackableByPlayer, true);
            applyLocalHealthHud();
        }

        remote = theGame.r_getMultiplayerClient().getPlayerByServerId(opponentId);

        if(remote && remote.ghost && wasActive)
        {
            remote.ghost.SetHealthPerc(1.0);
        }

        opponentId = 0;
        opponentName = "";
        refreshGhosts();
        vitalityHudModule = NULL;
        vitalityHudFunction = NULL;
    }

    public function setOpponentId(id : int)
    {
        opponentId = id;
    }

    private function applyLocalHealthHud()
    {
        var hud : CR4ScriptedHud;
        var module : CHudModule;
        var flashModule : CScriptedFlashSprite;
        var wolfHead : CR4HudModuleWolfHead;

        hud = (CR4ScriptedHud)theGame.GetHud();

        if(!hud)
        {
            return;
        }

        module = hud.GetHudModule("WolfHeadModule");

        if(!module)
        {
            return;
        }

        flashModule = module.GetModuleFlash();

        if(!flashModule)
        {
            return;
        }

        if(module != vitalityHudModule || !vitalityHudFunction)
        {
            vitalityHudModule = module;
            vitalityHudFunction = flashModule.GetMemberFlashFunction("setVitality");
        }

        if(vitalityHudFunction)
        {
            vitalityHudFunction.InvokeSelfOneArg(FlashArgNumber(localHealth / 100000.0));
        }

        if(active)
        {
            wolfHead = (CR4HudModuleWolfHead)module;

            if(wolfHead)
            {
                wolfHead.SetAlwaysDisplayed(true);
            }
        }
    }

    public function applyGhost(remote : r_RemotePlayer, force : bool)
    {
        var ghost : CActor;
        var npc : CNewNPC;

        if(!remote || !remote.ghost)
        {
            return;
        }

        ghost = remote.ghost;
        npc = (CNewNPC)ghost;
        ghost.SetGameplayVisibility(true);
        ghost.EnableStaticCollisions(true);
        ghost.EnableCharacterCollisions(false);
        if(isActiveWith(remote))
        {
            if(npc)
            {
                npc.SetLevel(thePlayer.GetLevel());
            }

            ghost.SetImmortalityMode(AIM_Immortal, AIC_IsAttackableByPlayer, true);
            ghost.SetCanPlayHitAnim(true);
            ghost.SetAttackableByPlayerPersistent(true);
            ghost.SetAttackableByPlayerRuntime(true);
            ghost.SetTemporaryAttitudeGroup('hostile_to_player', AGP_Default);
            thePlayer.SetAttitude(ghost, AIA_Hostile);
            ghost.SetAttitude(thePlayer, AIA_Hostile);

            if(npc && remote.duelAiForceId < 0)
            {
                remote.duelAiForceId = wo_idleDuelAi(npc);
            }

            return;
        }

        if(remote.duelAiForceId >= 0)
        {
            ghost.CancelAIBehavior(remote.duelAiForceId);
            remote.duelAiForceId = -1;
        }

        if(npc)
        {
            npc.SetLevel(1);
        }

        ghost.SetImmortalityMode(AIM_Invulnerable, AIC_IsAttackableByPlayer, true);
        ghost.SetCanPlayHitAnim(false);
        ghost.SetAttackableByPlayerPersistent(false);
        ghost.SetAttackableByPlayerRuntime(false);
        ghost.SetTemporaryAttitudeGroup('friendly_to_player', AGP_Default);
        thePlayer.SetAttitude(ghost, AIA_Neutral);
        ghost.SetAttitude(thePlayer, AIA_Neutral);
    }

    public function refreshGhosts()
    {
        var players : array<r_RemotePlayer>;
        var i : int;

        players = theGame.r_getMultiplayerClient().getPlayers();

        for(i = 0; i < players.Size(); i += 1)
        {
            applyGhost(players[i], true);
        }
    }
}

function wo_clampDuelHealth(value : int) : int
{
    if(value < 0)
    {
        return 0;
    }

    if(value > 100000)
    {
        return 100000;
    }

    return value;
}

function wo_reportDuelDamage(victim : CActor, damage : float, maximum : float)
{
    var remote : r_RemotePlayer;

    remote = theGame.r_getMultiplayerClient().findRemoteByActor(victim);

    if(remote)
    {
        theGame.r_getMultiplayerClient().getDuelController().noteDamage(remote, damage, maximum);
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

function wo_idleDuelAi(npc : CNewNPC) : int
{
    var idle : CAIIdleTree;
    var forceId : int;

    if(!npc)
    {
        return -1;
    }

    idle = new CAIIdleTree in npc;
    forceId = npc.ForceAIBehavior(idle, BTAP_AboveEmergency2, 'WO_Duel');

    return forceId;
}
