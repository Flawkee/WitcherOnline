struct r_VisualProjectile
{
    var eventId : int;
    var kind : int;
    var entity : CEntity;
    var origin : Vector;
    var target : Vector;
    var startedAt : float;
    var duration : float;
}

function wo_nextVfxSequence() : int
{
    return theGame.r_getMultiplayerClient().nextVfxSequence();
}

function wo_vfxFlightDuration(origin : Vector, target : Vector) : float
{
    var duration : float;

    duration = VecDistance(origin, target) / 20.0;

    if(duration < 0.08)
    {
        duration = 0.08;
    }
    else if(duration > 2.5)
    {
        duration = 2.5;
    }

    return duration;
}

function wo_sendVfxSpawn(eventId : int, kind : int, itemName : name, origin : Vector, target : Vector)
{
    WO_Send("wo_vfxspawn " + eventId + " " + kind + " _s " + NameToString(itemName) + " _e "
        + origin.X + " " + origin.Y + " " + origin.Z + " "
        + target.X + " " + target.Y + " " + target.Z + " "
        + wo_vfxFlightDuration(origin, target));
}

function wo_sendVfxImpact(eventId : int, kind : int, itemName : name, position : Vector, water : bool, phase : int)
{
    WO_Send("wo_vfximpact " + eventId + " " + kind + " _s " + NameToString(itemName) + " _e "
        + position.X + " " + position.Y + " " + position.Z + " " + (int)water + " " + phase);
}

function wo_createVisualProjectile(owner : CActor, itemName : name, position : Vector) : CEntity
{
    var inventory : CInventoryComponent;
    var ids : array<SItemUniqueId>;
    var entity : CEntity;

    if(!owner || itemName == '')
    {
        return NULL;
    }

    inventory = owner.GetInventory();

    if(!inventory)
    {
        return NULL;
    }

    ids = inventory.AddAnItem(itemName, 1, true, true);

    if(ids.Size() == 0)
    {
        return NULL;
    }

    entity = inventory.GetDeploymentItemEntity(ids[0], position, owner.GetWorldRotation(), true);

    if(!entity)
    {
        return NULL;
    }

    entity.AddTag('wo_visual_projectile');
    entity.Teleport(position);
    return entity;
}

function wo_startVisualProjectile(entity : CEntity)
{
    var petard : W3Petard;
    var arrow : W3ArrowProjectile;

    if(!entity)
    {
        return;
    }

    petard = (W3Petard)entity;

    if(petard)
    {
        petard.wo_beginVisualProjectile();
        return;
    }

    arrow = (W3ArrowProjectile)entity;

    if(arrow)
    {
        arrow.wo_beginVisualProjectile();
    }
}

function wo_finishVisualProjectile(entity : CEntity, kind : int, water : bool, phase : int)
{
    var petard : W3Petard;
    var explosiveBolt : W3ExplosiveBolt;
    var arrow : W3ArrowProjectile;

    if(!entity)
    {
        return;
    }

    petard = (W3Petard)entity;

    if(petard)
    {
        petard.wo_finishVisualProjectile(phase);
        return;
    }

    explosiveBolt = (W3ExplosiveBolt)entity;

    if(explosiveBolt)
    {
        if(phase == 1)
        {
            explosiveBolt.wo_finishVisualExplosiveBolt(water);
        }
        return;
    }

    arrow = (W3ArrowProjectile)entity;

    if(arrow && phase == 1)
    {
        arrow.wo_finishVisualProjectile();
    }
}

@addField(W3Petard)
var wo_vfxEventId : int;

@addField(W3Petard)
var wo_vfxImpactSent : bool;

@addField(W3AdvancedProjectile)
var wo_vfxEventId : int;

@addField(W3AdvancedProjectile)
var wo_vfxImpactSent : bool;

@addMethod(W3Petard)
public function wo_beginVisualProjectile()
{
    PlayEffectSingle('fx_trail');
}

@addMethod(W3Petard)
public function wo_finishVisualProjectile(phase : int)
{
    if(phase == 1)
    {
        StopEffect('fx_trail');
        ProcessEffectPlayFXs(true);
        SoundEvent(audioImpactName);
        return;
    }

    if(phase == 2)
    {
        ProcessEffectPlayFXs(false);
        return;
    }

    StopAllEffects();
    DestroyAfter(1.0);
}

@addMethod(W3ArrowProjectile)
public function wo_beginVisualProjectile()
{
    PlayEffectSingle(defaultTrail);
    SoundEvent('cmb_arrow_swoosh');
}

@addMethod(W3ArrowProjectile)
public function wo_finishVisualProjectile()
{
    StopAllEffects();
    DestroyAfter(2.0);
}

@addMethod(W3ExplosiveBolt)
public function wo_finishVisualExplosiveBolt(water : bool)
{
    StopAllEffects();

    if(water)
    {
        PlayEffect('explode_water');
    }
    else
    {
        PlayEffect('explosion');
    }

    DestroyAfter(2.0);
}

@wrapMethod(W3Petard)
function ThrowProjectile(targetPosIn : Vector)
{
    var owner : CActor;
    var eventId : int;
    var itemName : name;

    owner = GetOwner();

    if(owner == thePlayer && !HasTag('wo_visual_projectile'))
    {
        itemName = owner.GetInventory().GetItemName(itemId);
        eventId = wo_nextVfxSequence();
        wo_vfxEventId = eventId;
        wo_vfxImpactSent = false;
        wo_sendVfxSpawn(eventId, 1, itemName, GetWorldPosition(), targetPosIn);
    }

    wrappedMethod(targetPosIn);
}

@wrapMethod(W3Petard)
function ProcessEffect(optional explosionPosition : Vector, optional collidedTarget : CGameplayEntity)
{
    if(HasTag('wo_visual_projectile'))
    {
        wo_finishVisualProjectile(1);
        return;
    }

    wrappedMethod(explosionPosition, collidedTarget);
}

@wrapMethod(W3Petard)
function ProcessEffectPlayFXs(isImpact : bool)
{
    var owner : CActor;
    var itemName : name;
    var phase : int;

    owner = GetOwner();

    if(owner == thePlayer && !HasTag('wo_visual_projectile') && wo_vfxEventId > 0)
    {
        itemName = owner.GetInventory().GetItemName(itemId);
        phase = 2;

        if(isImpact)
        {
            phase = 1;
        }

        wo_sendVfxImpact(wo_vfxEventId, 1, itemName, GetWorldPosition(), isInWater, phase);
    }

    wrappedMethod(isImpact);
}

@wrapMethod(W3Petard)
function OnTimeEndedFunction(dt : float)
{
    var owner : CActor;
    var itemName : name;

    owner = GetOwner();

    if(owner == thePlayer && !HasTag('wo_visual_projectile') && wo_vfxEventId > 0)
    {
        itemName = owner.GetInventory().GetItemName(itemId);
        wo_sendVfxImpact(wo_vfxEventId, 1, itemName, GetWorldPosition(), isInWater, 3);
    }

    wrappedMethod(dt);
}

@wrapMethod(W3BoltProjectile)
function ThrowProjectile(targetPosIn : Vector)
{
    var owner : CActor;
    var itemName : name;
    var eventId : int;

    owner = GetOwner();

    if(owner == thePlayer && !HasTag('wo_visual_projectile'))
    {
        itemName = owner.GetInventory().GetItemName(itemId);
        eventId = wo_nextVfxSequence();
        wo_vfxEventId = eventId;
        wo_vfxImpactSent = false;
        wo_sendVfxSpawn(eventId, 2, itemName, GetWorldPosition(), targetPosIn);
    }

    wrappedMethod(targetPosIn);
}

@wrapMethod(W3ArrowProjectile)
function OnProjectileCollision(pos, normal : Vector, collidingComponent : CComponent, hitCollisionsGroups : array<name>, actorIndex : int, shapeIndex : int)
{
    var bolt : W3BoltProjectile;
    var owner : CActor;
    var itemName : name;

    bolt = (W3BoltProjectile)this;

    if(HasTag('wo_visual_projectile'))
    {
        return true;
    }

    if(bolt)
    {
        owner = bolt.GetOwner();

        if(owner == thePlayer && bolt.wo_vfxEventId > 0 && !bolt.wo_vfxImpactSent)
        {
            bolt.wo_vfxImpactSent = true;
            itemName = owner.GetInventory().GetItemName(bolt.itemId);
            wo_sendVfxImpact(bolt.wo_vfxEventId, 2, itemName, pos, hitCollisionsGroups.Contains('Water'), 1);
        }
    }

    wrappedMethod(pos, normal, collidingComponent, hitCollisionsGroups, actorIndex, shapeIndex);
    return true;
}

@wrapMethod(W3ArrowProjectile)
function OnRangeReached()
{
    var bolt : W3BoltProjectile;
    var owner : CActor;
    var itemName : name;

    bolt = (W3BoltProjectile)this;

    if(HasTag('wo_visual_projectile'))
    {
        return true;
    }

    if(bolt)
    {
        owner = bolt.GetOwner();

        if(owner == thePlayer && bolt.wo_vfxEventId > 0 && !bolt.wo_vfxImpactSent)
        {
            bolt.wo_vfxImpactSent = true;
            itemName = owner.GetInventory().GetItemName(bolt.itemId);
            wo_sendVfxImpact(bolt.wo_vfxEventId, 2, itemName, GetWorldPosition(), false, 1);
        }
    }

    wrappedMethod();
    return true;
}

@wrapMethod(W3ExplosiveBolt)
function OnProjectileCollision(pos, normal : Vector, collidingComponent : CComponent, hitCollisionsGroups : array<name>, actorIndex : int, shapeIndex : int)
{
    var owner : CActor;
    var itemName : name;

    if(HasTag('wo_visual_projectile'))
    {
        return true;
    }

    owner = GetOwner();

    if(owner == thePlayer && wo_vfxEventId > 0 && !wo_vfxImpactSent)
    {
        wo_vfxImpactSent = true;
        itemName = owner.GetInventory().GetItemName(itemId);
        wo_sendVfxImpact(wo_vfxEventId, 2, itemName, pos, hitCollisionsGroups.Contains('Water'), 1);
    }

    wrappedMethod(pos, normal, collidingComponent, hitCollisionsGroups, actorIndex, shapeIndex);
    return true;
}
