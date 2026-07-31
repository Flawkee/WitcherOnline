exec function wo_probe()
{
    var npcs : array<CGameplayEntity>;
    var npc : CNewNPC;
    var i : int;
    var shown : int;

    WO_Note("[probe] ---- object marshalling test ----");

    WO_Note("[probe] NULL      -> " + WO_EntityProbe(NULL));
    WO_Note("[probe] player #1 -> " + WO_EntityProbe(thePlayer));
    WO_Note("[probe] player #2 -> " + WO_EntityProbe(thePlayer));

    FindGameplayEntitiesInSphere(npcs, thePlayer.GetWorldPosition(), 40.0, 40,,,,'CNewNPC');

    shown = 0;

    for(i = 0; i < npcs.Size(); i += 1)
    {
        npc = (CNewNPC)npcs[i];

        if(!npc)
        {
            continue;
        }

        WO_Note("[probe] npc app=" + NameToString(npc.GetAppearance())
            + " -> " + WO_EntityProbe(npc));

        shown += 1;

        if(shown >= 3)
        {
            break;
        }
    }

    if(shown == 0)
    {
        WO_Note("[probe] no NPCs in range - stand near one and retry");
    }

    WO_Note("[probe] ---- end ----");
}
