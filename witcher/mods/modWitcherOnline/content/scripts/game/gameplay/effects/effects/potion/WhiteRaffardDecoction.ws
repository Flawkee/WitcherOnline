class W3Potion_WhiteRaffardDecoction extends CBaseGameplayEffect
{
    default effectType = EET_WhiteRaffardDecoction;

    event OnEffectAdded(optional customParams : W3BuffCustomParams)
    {
        var vitality : float;
        var vitAtt, min, max : SAbilityAttributeValue;

        super.OnEffectAdded(customParams);

        theGame.GetDefinitionsManager().GetAbilityAttributeValue(
            abilityName, StatEnumToName(BCS_Vitality), min, max);
        vitAtt = GetAttributeRandomizedValue(min, max);
        vitality = target.GetStatMax(BCS_Vitality) * vitAtt.valueMultiplicative + vitAtt.valueAdditive;
        target.GainStat(BCS_Vitality, vitality);

        if(target == GetWitcherPlayer() && theGame.r_getMultiplayerClient().getDuelController().isActive())
        {
            theGame.r_getMultiplayerClient().getDuelController().noteHealing(
                vitality, target.GetStatMax(BCS_Vitality));
        }

        if(GetBuffLevel() == 3)
        {
            target.SetImmortalityMode(AIM_Invulnerable, AIC_WhiteRaffardsPotion);
        }
    }

    event OnEffectRemoved()
    {
        super.OnEffectRemoved();

        if(GetBuffLevel() == 3)
        {
            target.SetImmortalityMode(AIM_None, AIC_WhiteRaffardsPotion);
        }
    }
}
