/***********************************************************************/
/** 	© 2015 CD PROJEKT S.A. All rights reserved.
/** 	THE WITCHER® is a trademark of CD PROJEKT S. A.
/** 	The Witcher game is based on the prose of Andrzej Sapkowski.
/***********************************************************************/





class W3DamageManager
{
	public function ProcessAction(act : W3DamageAction)
	{
		var proc : W3DamageManagerProcessor;
		var wasAlive : bool;
		var npc : CNewNPC;
		var playerAttacker : CR4Player;
		var replicaVictim : CNewNPC;
		var actorVictim : CActor;
		var actorAttacker : CActor;
		var signCauser : W3SignEntity;
		var duelVictim : CActor;
		var duelRemote : r_RemotePlayer;
		var duelLocalVictim : bool;
		var duelSwordHit : bool;
		var duelDamage : float;
		var duelMaximum : float;
		var duelLocalHealth : float;
		var healthBefore : float;
		var maxHealth : float;

		if(!act || !act.victim)
			return;

		wasAlive = act.victim.IsAlive();


		if(!wasAlive && act.GetEffectsCount() == 0)
			return;

		actorAttacker = (CActor)act.attacker;

		if(!actorAttacker)
		{
			signCauser = (W3SignEntity)act.causer;

			if(signCauser)
			{
				actorAttacker = signCauser.GetOwner();
			}
		}

		playerAttacker = (CR4Player)actorAttacker;
		npc = (CNewNPC)act.victim;
		actorVictim = (CActor)act.victim;
		replicaVictim = NULL;
		duelVictim = NULL;
		duelRemote = NULL;
		duelLocalVictim = false;
		duelSwordHit = false;
		duelDamage = 0.0;
		duelMaximum = 0.0;
		duelLocalHealth = 0.0;
		healthBefore = 0.0;
		maxHealth = 0.0;


		if ( playerAttacker && npc && !npc.isAttackableByPlayer )
			return;

		if(actorVictim == thePlayer && theGame.r_getMultiplayerClient().getDuelController().isActive())
		{
			duelLocalVictim = true;
			duelLocalHealth = thePlayer.GetMaxHealth();
			thePlayer.ForceSetStat(BCS_Vitality, duelLocalHealth);
			act.SetIgnoreImmortalityMode(false);
			act.SetHitAnimationPlayType(EAHA_ForceYes);
			act.ClearDamage();
		}

		if(playerAttacker == thePlayer && theGame.r_getMultiplayerClient().getDuelController().isActive())
		{
			duelRemote = theGame.r_getMultiplayerClient().findRemoteByActor(actorVictim);

			if(!duelRemote || !theGame.r_getMultiplayerClient().getDuelController().isActiveWith(duelRemote))
				return;

			duelVictim = actorVictim;
			duelDamage = act.GetDamageValueTotal();
			duelMaximum = duelVictim.GetMaxHealth();
			act.SetIgnoreImmortalityMode(false);
			duelSwordHit = act.IsActionMelee()
				&& (act.GetDamageValue(theGame.params.DAMAGE_NAME_SLASHING) > 0.0
					|| act.GetDamageValue(theGame.params.DAMAGE_NAME_SILVER) > 0.0
					|| act.GetDamageValue(theGame.params.DAMAGE_NAME_RENDING) > 0.0);
			act.ClearDamage();
		}

		if(npc && !duelVictim)
		{
			healthBefore = npc.GetHealth();
			maxHealth = npc.GetMaxHealth();

			if(playerAttacker == thePlayer && npc.HasTag('WOReplica'))
			{
				replicaVictim = npc;
			}
		}

		proc = new W3DamageManagerProcessor in this;
		proc.ProcessAction(act);
		delete proc;

		if(duelLocalVictim)
		{
			thePlayer.ForceSetStat(BCS_Vitality, MaxF(1.0, duelLocalHealth));
			return;
		}

		if(replicaVictim && maxHealth > 0.0)
		{
			if(replicaVictim.HasTag('WOReplica'))
			{
				wo_reportReplicaDamage(replicaVictim, healthBefore - replicaVictim.GetHealth(), maxHealth);
			}
		}

		if(duelVictim)
		{
			duelDamage = MaxF(duelDamage, act.GetDamageDealt());

			if(duelDamage > 0.0 && duelMaximum > 0.0)
			{
				wo_reportDuelDamage(duelVictim, duelDamage, duelMaximum);
			}

			if(duelRemote && act.IsActionMelee() && duelDamage > 0.0)
			{
				duelRemote.playDuelHitReaction(duelSwordHit);
			}

			theGame.r_getMultiplayerClient().getDuelController().restoreGhostHealth(duelRemote);
		}

		if(npc && !duelVictim && !npc.HasTag('WOReplica') && maxHealth > 0.0
			&& (healthBefore > npc.GetHealth() || (wasAlive && !npc.IsAlive())))
		{
			wo_creditOwnedDamage(npc, healthBefore - npc.GetHealth(), act.attacker);
		}
	}
}
