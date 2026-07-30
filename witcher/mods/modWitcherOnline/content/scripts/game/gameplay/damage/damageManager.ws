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
		var healthBefore : float;
		var maxHealth : float;

		if(!act || !act.victim)
			return;

		wasAlive = act.victim.IsAlive();


		if(!wasAlive && act.GetEffectsCount() == 0)
			return;

		playerAttacker = (CR4Player)act.attacker;
		npc = (CNewNPC)act.victim;


		if ( playerAttacker && npc && !npc.isAttackableByPlayer )
			return;

		replicaVictim = NULL;
		healthBefore = 0.0;
		maxHealth = 0.0;

		if(playerAttacker == thePlayer && npc)
		{
			replicaVictim = npc;
			healthBefore = npc.GetHealth();
			maxHealth = npc.GetMaxHealth();
		}

		proc = new W3DamageManagerProcessor in this;
		proc.ProcessAction(act);
		delete proc;

		if(replicaVictim && maxHealth > 0.0)
		{
			if(replicaVictim.HasTag('WOReplica'))
			{
				wo_reportReplicaDamage(replicaVictim, healthBefore - replicaVictim.GetHealth(), maxHealth);
			}
			else
			{
				wo_creditOwnedDamage(replicaVictim, healthBefore - replicaVictim.GetHealth());
			}
		}
	}
}
