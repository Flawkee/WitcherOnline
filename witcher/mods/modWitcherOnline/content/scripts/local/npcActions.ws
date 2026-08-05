class r_NpcActions
{
    private var names : array<name>;
    private var initialised : bool;

    default initialised = false;

    private function add(eventName : name)
    {
        names.PushBack(eventName);
    }

    private function ensure()
    {
        if(initialised)
        {
            return;
        }

        initialised = true;
        add('Attack');
        add('3StateAttack');
        add('3StateIdle');
        add('actionFinished');
        add('actionShootEnd');
        add('actionStart');
        add('actionStop');
        add('AdditiveHit');
        add('AdditiveHitReaction');
        add('Agony');
        add('AllowBlend');
        add('AnimEndAUX');
        add('Appear');
        add('Attach');
        add('AttackEnd');
        add('AttackFail');
        add('AttackInterrupt');
        add('AttackStart');
        add('axiiCalmDown');
        add('BiesLure');
        add('BoatHit');
        add('BulbHit');
        add('Close');
        add('CloseAndExplode');
        add('CollideCenter');
        add('Collided');
        add('CollidedEnd');
        add('CollideLeft');
        add('CollideRight');
        add('CollideStop');
        add('CombatAction');
        add('CombatActionFriendly');
        add('CombatActionFriendlyEnd');
        add('CombatTaunt');
        add('CounterAttack');
        add('CriticalState');
        add('CriticalStateEnded');
        add('CustomHit');
        add('Danger');
        add('Death');
        add('DeathEndAUX');
        add('Despawn');
        add('destroy');
        add('dismount');
        add('dismountRagdoll');
        add('Dive');
        add('DiveEnd');
        add('DiveFail');
        add('DivingForceStop');
        add('Dodge');
        add('DrawCrossbow_Inv');
        add('DrawSilverSword_Inv');
        add('DrawSteelSword_Inv');
        add('DrawWeaponInstant');
        add('Explode');
        add('ExplodeGlobal');
        add('FinisherDeath');
        add('Fly');
        add('FocusHit');
        add('ForceAlertToNormalTransition');
        add('ForceBlendOut');
        add('ForcedUsableItemUnequip');
        add('ForceIdle');
        add('FromSwimming');
        add('GestureBothHands');
        add('GestureForceEnd');
        add('GestureLeftHand');
        add('GestureNoHands');
        add('GestureRightHand');
        add('hit');
        add('HorseSummon');
        add('IceSpikes');
        add('Idle');
        add('IgnoreSigns');
        add('IgnoreSignsEnd');
        add('ImmediateExplode');
        add('InstantMount');
        add('InterruptOverlay');
        add('ItemEndL');
        add('ItemUseL');
        add('Jump_Prepare_End');
        add('Jump_Prepare_Ended');
        add('LeaderMoves');
        add('LootHerb');
        add('MonsterInCombat');
        add('MonsterLeadEscape');
        add('Open');
        add('OpenDoor');
        add('OpenRift');
        add('PackRunsWild');
        add('ParryPerform');
        add('ParryStart');
        add('PerformAdditiveParry');
        add('PerformCounter');
        add('PerformParry');
        add('PerformParryOverlay');
        add('PhantomL');
        add('PhantomR');
        add('pickUpBox');
        add('PlayAround');
        add('playerActionStart');
        add('PlayerPresenceAction');
        add('putDownBox');
        add('QuickTurnWalk');
        add('Ragdoll');
        add('RecoverFromRagdoll');
        add('Recovery');
        add('RemoveCrossbow_Inv');
        add('RemoveSilverSword_Inv');
        add('RemoveSteelSword_Inv');
        add('RestBetweenBoxes');
        add('Rotate');
        add('RotateEnd');
        add('Run');
        add('SACiriAppear');
        add('ScaredOverlayEnd');
        add('ScaredWhileSitting');
        add('ScaredWithInfant');
        add('SearchesForTarget');
        add('Shake');
        add('shootAtPlayer');
        add('ShootTongue');
        add('ShowArmor_Inv');
        add('ShowBoots_Inv');
        add('ShowGlove_Inv');
        add('ShowPants_Inv');
        add('Spawn');
        add('SpawnEntity');
        add('StrongHitTest');
        add('Summon');
        add('SummonMeteorites');
        add('SwimmingStop');
        add('SwimUpStart');
        add('SwitchToRagdoll');
        add('SwitchWeaponEnd');
        add('TargetDir');
        add('Teleport');
        add('ToAwake');
        add('TouchedBottom');
        add('Unconscious');
        add('UseSpawner');
        add('Vanish');
        add('WeaponCrossbow_Loaded');
        add('WeaponCrossbow_Reload');
        add('WeaponCrossbow_Shoot');
        add('WeaponCrossbow_Unloaded');

    }

    public function codeFor(eventName : name) : int
    {
        var i : int;

        ensure();

        for(i = 0; i < names.Size(); i += 1)
        {
            if(names[i] == eventName)
            {
                return i;
            }
        }

        return 8191;
    }

    public function nameFor(code : int) : name
    {
        ensure();

        if(code < 0 || code >= names.Size())
        {
            return 'Attack';
        }

        return names[code];
    }

    public function kindFor(eventName : name) : int
    {
        if(eventName == 'Teleport' || eventName == 'Vanish' || eventName == 'Appear'
            || eventName == 'Dive' || eventName == 'DiveEnd')
        {
            return 2;
        }

        if(eventName == 'OpenRift' || eventName == 'Open' || eventName == 'Close'
            || eventName == 'OpenDoor')
        {
            return 3;
        }

        if(eventName == 'Spawn' || eventName == 'SpawnEntity' || eventName == 'Summon'
            || eventName == 'SummonMeteorites' || eventName == 'IceSpikes'
            || eventName == 'UseSpawner' || eventName == 'PhantomL' || eventName == 'PhantomR')
        {
            return 4;
        }

        if(eventName == 'BiesLure' || eventName == 'IgnoreSigns'
            || eventName == 'IgnoreSignsEnd' || eventName == 'CriticalState'
            || eventName == 'CriticalStateEnded')
        {
            return 5;
        }

        if(eventName == 'Ragdoll' || eventName == 'RecoverFromRagdoll'
            || eventName == 'SwitchToRagdoll' || eventName == 'Unconscious'
            || eventName == 'Agony')
        {
            return 6;
        }

        if(codeFor(eventName) == 8191)
        {
            return 8;
        }

        return 1;
    }

    public function usesMovement(eventName : name) : bool
    {
        return eventName == 'Attack'
            || eventName == '3StateAttack'
            || eventName == 'AttackStart'
            || eventName == 'CombatAction'
            || eventName == 'CombatActionFriendly'
            || eventName == 'CounterAttack'
            || eventName == 'Dodge'
            || eventName == 'ShootTongue';
    }

    public function count() : int
    {
        ensure();

        return names.Size();
    }
}
