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
    private var alternateCast : bool;
    private var casterSkillLevel : int;
    private var casterDuration : float;
    private var casterCharges : int;

    public function InitGhost(ghostActor : CActor, alternate : bool, skillLevel : int, duration : float, charges : int)
    {
        BaseInit(ghostActor);

        alternateCast = alternate;
        casterSkillLevel = skillLevel;
        casterDuration = duration;
        casterCharges = charges;
    }

    public function GetSkillLevel(skill : ESkill) : int
    {
        if(casterSkillLevel > 0)
        {
            return casterSkillLevel;
        }

        return 1;
    }

    public function GetSkillAttributeValue(skill : ESkill, attributeName : name, addBaseCharAttribute : bool, addSkillModsAttribute : bool) : SAbilityAttributeValue
    {
        var value : SAbilityAttributeValue;
        var witcher : W3PlayerWitcher;

        if(casterDuration > 0.0 && (attributeName == 'shield_duration' || attributeName == 'trap_duration'))
        {
            value.valueAdditive = casterDuration;
            return value;
        }

        if(casterCharges > 0 && attributeName == 'charge_count')
        {
            value.valueAdditive = casterCharges;
            return value;
        }

        witcher = GetWitcherPlayer();

        if(witcher)
        {
            return witcher.GetSkillAttributeValue(skill, attributeName, addBaseCharAttribute, addSkillModsAttribute);
        }

        return value;
    }

    public function GetPowerStatValue(stat : ECharacterPowerStats, optional abilityTag : name) : SAbilityAttributeValue
    {
        var value : SAbilityAttributeValue;
        var witcher : W3PlayerWitcher;

        witcher = GetWitcherPlayer();

        if(witcher)
        {
            return witcher.GetPowerStatValue(stat, abilityTag);
        }

        return value;
    }

    public function ChangeAspect(signEntity : W3SignEntity, newSkill : ESkill) : bool
    {
        if(!alternateCast || !signEntity)
        {
            return false;
        }

        signEntity.SetAlternateCast(newSkill);

        return true;
    }

    public function HasStaminaToUseSkill(skill : ESkill, optional perSec : bool, optional signHack : bool) : bool
    {
        return true;
    }
}

function wo_spawnGhostSign(ghost : CActor, signType : ESignType, alternate : bool,
                          skillLevel : int, duration : float, charges : int) : W3SignEntity
{
    var witcher : W3PlayerWitcher;
    var template : CEntityTemplate;
    var signEnt : W3SignEntity;
    var signOwner : r_GhostSignOwner;
    var pos : Vector;
    var rot : EulerAngles;

    if(!ghost || signType == ST_None)
    {
        return NULL;
    }

    witcher = GetWitcherPlayer();

    if(!witcher)
    {
        return NULL;
    }

    template = witcher.GetSignTemplate(signType);

    if(!template)
    {
        return NULL;
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
        return NULL;
    }

    signOwner = new r_GhostSignOwner in signEnt;
    signOwner.InitGhost(ghost, alternate, skillLevel, duration, charges);

    if(!signEnt.Init(signOwner, NULL, true, true))
    {
        return NULL;
    }

    return signEnt;
}

function wo_signEventName(code : int) : name
{
    switch(code)
    {
        case 1: return 'cast_begin';
        case 2: return 'cast_throw';
        case 3: return 'cast_end';
        case 4: return 'cast_friendly_begin';
        case 5: return 'cast_friendly_throw';
    }

    return '';
}

function wo_applyGhostSignEvent(signEnt : W3SignEntity, code : int)
{
    var eventName : name;

    if(!signEnt)
    {
        return;
    }

    eventName = wo_signEventName(code);

    if(eventName == '')
    {
        return;
    }

    signEnt.OnProcessSignEvent(eventName);
}

function wo_endGhostSign(signEnt : W3SignEntity)
{
    if(!signEnt)
    {
        return;
    }

    signEnt.OnEnded(true);
}
