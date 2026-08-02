class r_CharSnapshot
{
    private var lines : array<string>;
    private var pendingFree : int;
    private var pendingUsed : int;
    private var havePendingPoints : bool;
    private var pendingSlots : array<string>;
    private var pendingEquip : array<string>;
    private var pendingHorseEquip : array<string>;
    private var pendingMutation : int;
    private var restoredItems : int;

    default pendingFree = 0;
    default pendingUsed = 0;
    default havePendingPoints = false;

    public function capture() : string
    {
        var witcher : W3PlayerWitcher;
        var abilities : W3PlayerAbilityManager;

        witcher = GetWitcherPlayer();

        if(!witcher)
        {
            return "";
        }

        abilities = (W3PlayerAbilityManager)witcher.abilityManager;

        lines.Clear();
        lines.PushBack("WOCHAR|2");

        captureLevel(witcher);
        captureMoney(witcher);
        captureKnowledge(witcher);
        captureInventory(witcher);
        captureSkills(witcher, abilities);
        captureMutations(witcher);
        captureTutorials();
        captureGwent();

        return join();
    }

    private function join() : string
    {
        var buffer : string;
        var i : int;

        for(i = 0; i < lines.Size(); i += 1)
        {
            if(i > 0)
            {
                buffer += "#";
            }

            buffer += lines[i];
        }

        return buffer;
    }

    private function captureLevel(witcher : W3PlayerWitcher)
    {
        lines.PushBack("LVL|" + witcher.GetLevel()
            + "|" + witcher.levelManager.GetPointsTotal(EExperiencePoint)
            + "|" + witcher.levelManager.GetPointsFree(ESkillPoint)
            + "|" + witcher.levelManager.GetPointsUsed(ESkillPoint));
    }

    private function captureMoney(witcher : W3PlayerWitcher)
    {
        lines.PushBack("MONEY|" + witcher.GetInventory().GetMoney());
    }

    private function captureInventory(witcher : W3PlayerWitcher)
    {
        var inv : CInventoryComponent;
        var ids : array<SItemUniqueId>;
        var enhancements : array<name>;
        var row : string;
        var i : int;
        var j : int;

        inv = witcher.GetInventory();
        inv.GetAllItems(ids);

        for(i = 0; i < ids.Size(); i += 1)
        {
            if(!inv.IsIdValid(ids[i]) || isExcludedItem(inv, ids[i]))
            {
                continue;
            }

            row = "ITEM|" + NameToString(inv.GetItemName(ids[i]))
                + "|" + inv.GetItemQuantity(ids[i]);

            enhancements.Clear();
            inv.GetItemEnhancementItems(ids[i], enhancements);

            for(j = 0; j < enhancements.Size(); j += 1)
            {
                row += "|" + NameToString(enhancements[j]);
            }

            lines.PushBack(row);
        }

        captureEquipment(witcher, inv);
        captureHorse(witcher);
    }

    private function captureHorse(witcher : W3PlayerWitcher)
    {
        var manager : W3HorseManager;
        var horseInv : CInventoryComponent;
        var playerInv : CInventoryComponent;
        var ids : array<SItemUniqueId>;
        var id : SItemUniqueId;
        var equippedName : name;
        var row : string;
        var slot : int;
        var i : int;

        manager = witcher.GetHorseManager();

        if(!manager)
        {
            return;
        }

        horseInv = manager.GetInventoryComponent();

        if(horseInv)
        {
            horseInv.GetAllItems(ids);

            for(i = 0; i < ids.Size(); i += 1)
            {
                if(!horseInv.IsIdValid(ids[i]) || isExcludedItem(horseInv, ids[i]))
                {
                    continue;
                }

                lines.PushBack("HITEM|" + NameToString(horseInv.GetItemName(ids[i]))
                    + "|" + horseInv.GetItemQuantity(ids[i]));
            }
        }

        playerInv = witcher.GetInventory();
        row = "HEQUIP";

        for(slot = (int)EES_HorseBlinders; slot <= (int)EES_HorseTrophy; slot += 1)
        {
            id = manager.GetItemInSlot((EEquipmentSlots)slot);
            equippedName = '';

            if(horseInv && horseInv.IsIdValid(id))
            {
                equippedName = horseInv.GetItemName(id);
            }
            else if(playerInv && playerInv.IsIdValid(id))
            {
                equippedName = playerInv.GetItemName(id);
            }

            if(equippedName == '')
            {
                continue;
            }

            row += "|" + slot + ":" + NameToString(equippedName);

            if(horseInv && !horseInv.IsIdValid(id))
            {
                lines.PushBack("HITEM|" + NameToString(equippedName) + "|1");
            }
        }

        lines.PushBack(row);
    }

    private function isQuestNamePattern(itemName : name) : bool
    {
        var text : string;
        var head : string;
        var digits : string;
        var start : int;
        var cut : int;

        text = StrLower(NameToString(itemName));

        while(StrBeginsWith(text, "_"))
        {
            text = StrMid(text, 1);
        }

        if(StrBeginsWith(text, "mq") || StrBeginsWith(text, "sq"))
        {
            start = 2;
        }
        else if(StrBeginsWith(text, "q"))
        {
            start = 1;
        }
        else
        {
            return false;
        }

        head = StrMid(text, start);
        cut = StrFindFirst(head, "_");

        if(cut <= 0)
        {
            return false;
        }

        digits = StrLeft(head, cut);

        if(IntToString(StringToInt(digits, -1)) != digits)
        {
            return false;
        }

        return true;
    }

    private function isTrophyItem(inv : CInventoryComponent, id : SItemUniqueId) : bool
    {
        return inv.ItemHasTag(id, 'Trophy') || inv.IsItemTrophy(id);
    }

    public function isQuestBoundItem(inv : CInventoryComponent, id : SItemUniqueId) : bool
    {
        return isQuestBound(inv, id);
    }

    private function isQuestBound(inv : CInventoryComponent, id : SItemUniqueId) : bool
    {
        var dm : CDefinitionsManagerAccessor;
        var itemName : name;

        if(isTrophyItem(inv, id))
        {
            return false;
        }

        if(inv.ItemHasTag(id, 'Quest')
            || inv.ItemHasTag(id, 'QuestEP1')
            || inv.ItemHasTag(id, 'QuestEP2'))
        {
            return true;
        }

        itemName = inv.GetItemName(id);

        if(isQuestNamePattern(itemName))
        {
            return true;
        }

        dm = theGame.GetDefinitionsManager();

        return dm.ItemHasTag(itemName, 'Quest')
            || dm.ItemHasTag(itemName, 'QuestEP1')
            || dm.ItemHasTag(itemName, 'QuestEP2');
    }

    private function isProtectedItem(inv : CInventoryComponent, id : SItemUniqueId) : bool
    {
        if(isQuestBound(inv, id))
        {
            return true;
        }

        return inv.ItemHasTag(id, 'NotTransferableToNGP')
            || inv.ItemHasTag(id, 'NoticeBoardNote');
    }

    private function isExcludedItem(inv : CInventoryComponent, id : SItemUniqueId) : bool
    {
        if(isProtectedItem(inv, id))
        {
            return true;
        }

        return inv.ItemHasTag(id, 'GwintCard');
    }

    private function captureEquipment(witcher : W3PlayerWitcher, inv : CInventoryComponent)
    {
        var id : SItemUniqueId;
        var row : string;
        var slot : int;

        row = "EQUIP";

        for(slot = (int)EES_SilverSword; slot <= (int)EES_Potion4; slot += 1)
        {
            if(!witcher.GetItemEquippedOnSlot((EEquipmentSlots)slot, id))
            {
                continue;
            }

            if(!inv.IsIdValid(id) || isExcludedItem(inv, id))
            {
                continue;
            }

            row += "|" + slot + ":" + NameToString(inv.GetItemName(id));
        }

        lines.PushBack(row);
    }

    private function captureKnowledge(witcher : W3PlayerWitcher)
    {
        var names : array<name>;
        var row : string;
        var i : int;

        names = witcher.GetCraftingSchematicsNames();
        row = "SCHEM";

        for(i = 0; i < names.Size(); i += 1)
        {
            row += "|" + NameToString(names[i]);
        }

        lines.PushBack(row);

        names = witcher.GetAlchemyRecipes();
        row = "ALCH";

        for(i = 0; i < names.Size(); i += 1)
        {
            row += "|" + NameToString(names[i]);
        }

        lines.PushBack(row);
    }

    private function captureSkills(witcher : W3PlayerWitcher, abilities : W3PlayerAbilityManager)
    {
        var row : string;
        var level : int;
        var i : int;

        if(!abilities)
        {
            return;
        }

        row = "SKILL";

        for(i = 0; i < (int)S_Skill_MAX; i += 1)
        {
            level = abilities.GetSkillLevel((ESkill)i);

            if(level > 0)
            {
                row += "|" + i + ":" + level;
            }
        }

        lines.PushBack(row);

        captureSkillSlots(abilities);
    }

    private function captureSkillSlots(abilities : W3PlayerAbilityManager)
    {
        var slots : array<SSkillSlot>;
        var row : string;
        var i : int;

        slots = abilities.GetSkillSlots();
        row = "SLOT";

        for(i = 0; i < slots.Size(); i += 1)
        {
            if(slots[i].socketedSkill == S_SUndefined)
            {
                continue;
            }

            row += "|" + slots[i].id + ":" + (int)slots[i].socketedSkill;
        }

        lines.PushBack(row);
    }

    private function captureTutorials()
    {
        var manager : CWitcherJournalManager;
        var entries : array<CJournalBase>;
        var row : string;
        var i : int;

        manager = theGame.GetJournalManager();

        if(!manager)
        {
            return;
        }

        manager.GetActivatedOfType('CJournalTutorial', entries);
        row = "TUT";

        for(i = 0; i < entries.Size(); i += 1)
        {
            if(entries[i])
            {
                row += "|" + NameToString(entries[i].GetUniqueScriptTag());
            }
        }

        lines.PushBack(row);
    }

    private function captureMutations(witcher : W3PlayerWitcher)
    {
        lines.PushBack("MUT|" + (int)witcher.GetEquippedMutationType());
    }

    private function captureGwent()
    {
        var manager : CR4GwintManager;
        var cards : array<CollectionCard>;
        var row : string;
        var i : int;

        manager = theGame.GetGwintManager();

        if(!manager)
        {
            return;
        }

        cards = manager.GetPlayerCollection();
        row = "GWENT";

        for(i = 0; i < cards.Size(); i += 1)
        {
            row += "|" + cards[i].cardID + ":" + cards[i].numCopies;
        }

        lines.PushBack(row);

        cards = manager.GetPlayerLeaderCollection();
        row = "GWENTL";

        for(i = 0; i < cards.Size(); i += 1)
        {
            row += "|" + cards[i].cardID + ":" + cards[i].numCopies;
        }

        lines.PushBack(row);

        captureDeck(manager, GwintFaction_NothernKingdom);
        captureDeck(manager, GwintFaction_Nilfgaard);
        captureDeck(manager, GwintFaction_Scoiatael);
        captureDeck(manager, GwintFaction_NoMansLand);
        captureDeck(manager, GwintFaction_Skellige);

        lines.PushBack("GWENTSEL|" + (int)manager.GetSelectedPlayerDeck());
    }

    private function captureDeck(manager : CR4GwintManager, faction : eGwintFaction)
    {
        var deck : SDeckDefinition;
        var row : string;
        var i : int;

        if(!manager.GetFactionDeck(faction, deck))
        {
            return;
        }

        row = "DECK|" + (int)faction
            + "|" + deck.leaderIndex
            + "|" + deck.specialCard
            + "|" + manager.IsDeckUnlocked(faction);

        for(i = 0; i < deck.cardIndices.Size(); i += 1)
        {
            row += "|" + deck.cardIndices[i];
        }

        lines.PushBack(row);
    }

    public function restore(body : string) : string
    {
        var witcher : W3PlayerWitcher;
        var rows : array<string>;
        var report : string;
        var i : int;

        witcher = GetWitcherPlayer();

        if(!witcher || body == "")
        {
            return "no snapshot";
        }

        rows = woSplit(body, "#");

        if(rows.Size() < 1 || !StrBeginsWith(rows[0], "WOCHAR|"))
        {
            return "bad snapshot header";
        }

        report = "";
        havePendingPoints = false;
        restoredItems = 0;
        pendingSlots.Clear();
        pendingEquip.Clear();
        pendingHorseEquip.Clear();
        pendingMutation = -1;

        report += purgeCharacterDev(witcher) + " ";
        report += purgeForeignItems(witcher) + " ";
        report += "hpurged=" + purgeHorseItems(witcher) + " ";
        report += "gpurged=" + purgeGwentCollection() + " ";

        for(i = 1; i < rows.Size(); i += 1)
        {
            report += applyRow(witcher, rows[i]) + " ";
        }

        if(restoredItems > 0)
        {
            report += "items=" + restoredItems + " ";
        }

        if(pendingEquip.Size() > 1)
        {
            report += applyEquipment(witcher) + " ";
        }

        if(pendingHorseEquip.Size() > 1)
        {
            report += applyHorseEquip(witcher) + " ";
        }

        if(pendingMutation > 0)
        {
            witcher.SetEquippedMutation((EPlayerMutationType)pendingMutation);
            report += "mut=" + pendingMutation + " ";
        }

        if(pendingSlots.Size() > 1)
        {
            report += applySkillSlots(witcher) + " ";
        }

        if(havePendingPoints)
        {
            witcher.levelManager.NGE_SetFreePoints(pendingFree);
            witcher.levelManager.NGE_SetUsedPoints(pendingUsed);

            report += "pts=" + pendingFree + "/" + pendingUsed;
        }

        return report;
    }

    private function applyRow(witcher : W3PlayerWitcher, row : string) : string
    {
        var parts : array<string>;

        parts = woSplit(row, "|");

        if(parts.Size() < 1)
        {
            return "";
        }

        if(parts[0] == "LVL")
        {
            return applyLevel(witcher, parts);
        }

        if(parts[0] == "MONEY")
        {
            return applyMoney(witcher, parts);
        }

        if(parts[0] == "SCHEM")
        {
            return applySchematics(witcher, parts);
        }

        if(parts[0] == "ALCH")
        {
            return applyRecipes(witcher, parts);
        }

        if(parts[0] == "GWENT")
        {
            return applyGwent(parts, false);
        }

        if(parts[0] == "GWENTL")
        {
            return applyGwent(parts, true);
        }

        if(parts[0] == "DECK")
        {
            return applyDeck(parts);
        }

        if(parts[0] == "GWENTSEL")
        {
            return applySelectedDeck(parts);
        }

        if(parts[0] == "SKILL")
        {
            return applySkills(witcher, parts);
        }

        if(parts[0] == "SLOT")
        {
            pendingSlots = parts;
            return "slots?";
        }

        if(parts[0] == "ITEM")
        {
            return applyItem(witcher, parts);
        }

        if(parts[0] == "EQUIP")
        {
            pendingEquip = parts;
            return "";
        }

        if(parts[0] == "HITEM")
        {
            return applyHorseItem(witcher, parts);
        }

        if(parts[0] == "HEQUIP")
        {
            pendingHorseEquip = parts;
            return "";
        }

        if(parts[0] == "MUT")
        {
            pendingMutation = StringToInt(parts[1], 0);
            return "";
        }

        if(parts[0] == "TUT")
        {
            return applyTutorials(parts);
        }

        return "";
    }

    private function applyTutorials(parts : array<string>) : string
    {
        var tutorials : CR4TutorialSystem;
        var manager : CWitcherJournalManager;
        var entries : array<CJournalBase>;
        var cleared : int;
        var marked : int;
        var i : int;

        tutorials = theGame.GetTutorialSystem();

        if(!tutorials)
        {
            return "tut?";
        }

        manager = theGame.GetJournalManager();

        if(manager)
        {
            manager.GetActivatedOfType('CJournalTutorial', entries);

            for(i = 0; i < entries.Size(); i += 1)
            {
                if(entries[i])
                {
                    tutorials.UnmarkMessageAsSeen(entries[i].GetUniqueScriptTag());
                    cleared += 1;
                }
            }
        }

        for(i = 1; i < parts.Size(); i += 1)
        {
            tutorials.MarkMessageAsSeen(WO_ToName(parts[i]));
            marked += 1;
        }

        return "tut-" + cleared + "+" + marked;
    }

    private function applyHorseItem(witcher : W3PlayerWitcher, parts : array<string>) : string
    {
        var manager : W3HorseManager;
        var horseInv : CInventoryComponent;
        var itemName : name;
        var want : int;
        var have : int;

        manager = witcher.GetHorseManager();

        if(!manager || parts.Size() < 3)
        {
            return "";
        }

        horseInv = manager.GetInventoryComponent();

        if(!horseInv)
        {
            return "";
        }

        itemName = WO_ToName(parts[1]);
        want = StringToInt(parts[2], 1);
        have = horseInv.GetItemQuantityByName(itemName);

        if(have < want)
        {
            horseInv.AddAnItem(itemName, want - have, true, true);
        }

        return "";
    }

    private function applyHorseEquip(witcher : W3PlayerWitcher) : string
    {
        var manager : W3HorseManager;
        var horseInv : CInventoryComponent;
        var pair : array<string>;
        var ids : array<SItemUniqueId>;
        var equipped : int;
        var i : int;

        manager = witcher.GetHorseManager();

        if(!manager)
        {
            return "";
        }

        horseInv = manager.GetInventoryComponent();

        if(!horseInv)
        {
            return "";
        }

        for(i = 1; i < pendingHorseEquip.Size(); i += 1)
        {
            pair = woSplit(pendingHorseEquip[i], ":");

            if(pair.Size() < 2)
            {
                continue;
            }

            ids = horseInv.GetItemsByName(WO_ToName(pair[1]));

            if(ids.Size() > 0)
            {
                manager.EquipItem(ids[0]);
                equipped += 1;
            }
        }

        return "horse=" + equipped;
    }

    private function purgeCharacterDev(witcher : W3PlayerWitcher) : string
    {
        witcher.ResetCharacterDev();
        witcher.RemoveAllCraftingSchematics();

        return "devreset";
    }

    private function purgeGwentCollection() : int
    {
        var manager : CR4GwintManager;
        var cards : array<CollectionCard>;
        var removed : int;
        var i : int;
        var j : int;

        manager = theGame.GetGwintManager();

        if(!manager)
        {
            return 0;
        }

        cards = manager.GetPlayerCollection();

        for(i = 0; i < cards.Size(); i += 1)
        {
            for(j = 0; j < cards[i].numCopies; j += 1)
            {
                manager.RemoveCardFromCollection(cards[i].cardID);
                removed += 1;
            }
        }

        cards = manager.GetPlayerLeaderCollection();

        for(i = 0; i < cards.Size(); i += 1)
        {
            for(j = 0; j < cards[i].numCopies; j += 1)
            {
                manager.RemoveCardFromCollection(cards[i].cardID);
                removed += 1;
            }
        }

        return removed;
    }

    private function purgeHorseItems(witcher : W3PlayerWitcher) : int
    {
        var manager : W3HorseManager;
        var horseInv : CInventoryComponent;
        var ids : array<SItemUniqueId>;
        var stripped : SItemUniqueId;
        var removed : int;
        var i : int;

        manager = witcher.GetHorseManager();

        if(!manager)
        {
            return 0;
        }

        horseInv = manager.GetInventoryComponent();

        if(!horseInv)
        {
            return 0;
        }

        for(i = (int)EES_HorseBlinders; i <= (int)EES_HorseTrophy; i += 1)
        {
            stripped = manager.UnequipItem((EEquipmentSlots)i);

            if(witcher.GetInventory().IsIdValid(stripped))
            {
                witcher.GetInventory().RemoveItem(stripped, witcher.GetInventory().GetItemQuantity(stripped));
                removed += 1;
            }
        }

        horseInv.GetAllItems(ids);

        for(i = ids.Size() - 1; i >= 0; i -= 1)
        {
            if(!horseInv.IsIdValid(ids[i]) || isProtectedItem(horseInv, ids[i]))
            {
                continue;
            }

            if(horseInv.IsItemMounted(ids[i]))
            {
                horseInv.UnmountItem(ids[i], true);
            }

            horseInv.RemoveItem(ids[i], horseInv.GetItemQuantity(ids[i]));
            removed += 1;
        }

        return removed;
    }

    private function purgeForeignItems(witcher : W3PlayerWitcher) : string
    {
        var inv : CInventoryComponent;
        var ids : array<SItemUniqueId>;
        var removed : int;
        var kept : int;
        var i : int;

        inv = witcher.GetInventory();

        for(i = (int)EES_SilverSword; i <= (int)EES_Potion4; i += 1)
        {
            witcher.UnequipItemFromSlot((EEquipmentSlots)i);
        }

        inv.GetAllItems(ids);

        for(i = ids.Size() - 1; i >= 0; i -= 1)
        {
            if(!inv.IsIdValid(ids[i]))
            {
                continue;
            }

            if(isProtectedItem(inv, ids[i]))
            {
                kept += 1;
                continue;
            }

            if(inv.IsItemMounted(ids[i]))
            {
                inv.UnmountItem(ids[i], true);
            }

            inv.RemoveItem(ids[i], inv.GetItemQuantity(ids[i]));
            removed += 1;
        }

        return "purged=" + removed + " keptQuest=" + kept;
    }

    private function applyItem(witcher : W3PlayerWitcher, parts : array<string>) : string
    {
        var inv : CInventoryComponent;
        var made : array<SItemUniqueId>;
        var runes : array<SItemUniqueId>;
        var itemName : name;
        var want : int;
        var have : int;
        var i : int;

        if(parts.Size() < 3)
        {
            return "";
        }

        inv = witcher.GetInventory();
        itemName = WO_ToName(parts[1]);
        want = StringToInt(parts[2], 1);

        have = inv.GetItemQuantityByName(itemName);

        if(have < want)
        {
            made = inv.AddAnItem(itemName, want - have, true, true);
        }

        if(parts.Size() > 3 && made.Size() > 0)
        {
            for(i = 3; i < parts.Size(); i += 1)
            {
                runes = inv.AddAnItem(WO_ToName(parts[i]), 1, true, true);

                if(runes.Size() > 0)
                {
                    inv.EnhanceItemScript(made[0], runes[0]);
                }
            }
        }

        restoredItems += 1;

        return "";
    }

    private function applyEquipment(witcher : W3PlayerWitcher) : string
    {
        var inv : CInventoryComponent;
        var pair : array<string>;
        var ids : array<SItemUniqueId>;
        var slot : int;
        var mounted : int;
        var i : int;

        inv = witcher.GetInventory();

        for(i = 1; i < pendingEquip.Size(); i += 1)
        {
            pair = woSplit(pendingEquip[i], ":");

            if(pair.Size() < 2)
            {
                continue;
            }

            slot = StringToInt(pair[0], -1);

            if(slot < 0)
            {
                continue;
            }

            ids = inv.GetItemsByName(WO_ToName(pair[1]));

            if(ids.Size() > 0 && witcher.EquipItemInGivenSlot(ids[0], (EEquipmentSlots)slot, false))
            {
                mounted += 1;
            }
        }

        return "equip=" + mounted;
    }

    private function applySkillSlots(witcher : W3PlayerWitcher) : string
    {
        var pair : array<string>;
        var slotId : int;
        var skillId : int;
        var equipped : int;
        var i : int;

        for(i = 1; i < pendingSlots.Size(); i += 1)
        {
            pair = woSplit(pendingSlots[i], ":");

            if(pair.Size() < 2)
            {
                continue;
            }

            slotId = StringToInt(pair[0], -1);
            skillId = StringToInt(pair[1], -1);

            if(slotId < 0 || skillId < 0)
            {
                continue;
            }

            if(witcher.GetSkillSlotID((ESkill)skillId) == slotId)
            {
                equipped += 1;
                continue;
            }

            witcher.UnequipSkill(slotId);

            if(witcher.EquipSkill((ESkill)skillId, slotId))
            {
                equipped += 1;
            }
        }

        return "slots=" + equipped;
    }

    private function applyDeck(parts : array<string>) : string
    {
        var manager : CR4GwintManager;
        var deck : SDeckDefinition;
        var faction : eGwintFaction;
        var i : int;

        manager = theGame.GetGwintManager();

        if(!manager || parts.Size() < 5)
        {
            return "deck?";
        }

        faction = (eGwintFaction)StringToInt(parts[1], 0);

        manager.GetFactionDeck(faction, deck);

        deck.leaderIndex = StringToInt(parts[2], 0);
        deck.specialCard = StringToInt(parts[3], 0);
        deck.cardIndices.Clear();

        for(i = 5; i < parts.Size(); i += 1)
        {
            deck.cardIndices.PushBack(StringToInt(parts[i], 0));
        }

        manager.SetFactionDeck(faction, deck);

        if(parts[4] == "true" && !manager.IsDeckUnlocked(faction))
        {
            manager.UnlockDeck(faction);
        }

        return "deck" + (int)faction + "=" + deck.cardIndices.Size();
    }

    private function applySelectedDeck(parts : array<string>) : string
    {
        var manager : CR4GwintManager;

        manager = theGame.GetGwintManager();

        if(!manager || parts.Size() < 2)
        {
            return "";
        }

        manager.SetSelectedPlayerDeck((eGwintFaction)StringToInt(parts[1], 0));

        return "deckSel";
    }

    private function applySkills(witcher : W3PlayerWitcher, parts : array<string>) : string
    {
        var abilities : W3PlayerAbilityManager;
        var pair : array<string>;
        var skillId : int;
        var wantLevel : int;
        var haveLevel : int;
        var added : int;
        var i : int;
        var j : int;

        abilities = (W3PlayerAbilityManager)witcher.abilityManager;

        if(!abilities)
        {
            return "skill?";
        }

        for(i = 1; i < parts.Size(); i += 1)
        {
            pair = woSplit(parts[i], ":");

            if(pair.Size() < 2)
            {
                continue;
            }

            skillId = StringToInt(pair[0], -1);
            wantLevel = StringToInt(pair[1], 0);

            if(skillId < 0 || wantLevel <= 0)
            {
                continue;
            }

            haveLevel = abilities.GetSkillLevel((ESkill)skillId);

            for(j = haveLevel; j < wantLevel; j += 1)
            {
                abilities.AddSkill((ESkill)skillId, false);
                added += 1;
            }
        }

        return "skill+" + added;
    }

    private function applyLevel(witcher : W3PlayerWitcher, parts : array<string>) : string
    {
        var wantLevel : int;
        var wantExp : int;
        var wantFree : int;
        var wantUsed : int;
        var baseExp : int;

        if(parts.Size() < 5)
        {
            return "lvl?";
        }

        wantLevel = StringToInt(parts[1], 1);
        wantExp = StringToInt(parts[2], 0);
        wantFree = StringToInt(parts[3], 0);
        wantUsed = StringToInt(parts[4], 0);

        witcher.levelManager.Hack_EP2StandaloneLevelShrink(wantLevel);

        baseExp = witcher.levelManager.GetTotalExpForGivenLevel(wantLevel);

        if(wantExp > baseExp)
        {
            witcher.levelManager.AddPoints(EExperiencePoint, wantExp - baseExp, false);
        }

        pendingFree = wantFree;
        pendingUsed = wantUsed;
        havePendingPoints = true;

        return "lvl=" + witcher.GetLevel();
    }

    private function applyMoney(witcher : W3PlayerWitcher, parts : array<string>) : string
    {
        var want : int;
        var have : int;

        if(parts.Size() < 2)
        {
            return "money?";
        }

        want = StringToInt(parts[1], 0);
        have = witcher.GetInventory().GetMoney();

        if(want > have)
        {
            witcher.GetInventory().AddMoney(want - have);
        }
        else if(have > want)
        {
            witcher.GetInventory().RemoveMoney(have - want);
        }

        return "money=" + witcher.GetInventory().GetMoney();
    }

    private function applySchematics(witcher : W3PlayerWitcher, parts : array<string>) : string
    {
        var added : int;
        var i : int;

        for(i = 1; i < parts.Size(); i += 1)
        {
            if(witcher.AddCraftingSchematic(WO_ToName(parts[i]), true, true))
            {
                added += 1;
            }
        }

        return "schem+" + added;
    }

    private function applyRecipes(witcher : W3PlayerWitcher, parts : array<string>) : string
    {
        var added : int;
        var i : int;

        for(i = 1; i < parts.Size(); i += 1)
        {
            if(witcher.AddAlchemyRecipe(WO_ToName(parts[i]), true, true))
            {
                added += 1;
            }
        }

        return "alch+" + added;
    }

    private function applyGwent(parts : array<string>, leaders : bool) : string
    {
        var manager : CR4GwintManager;
        var pair : array<string>;
        var cardId : int;
        var copies : int;
        var have : int;
        var added : int;
        var i : int;
        var j : int;

        manager = theGame.GetGwintManager();

        if(!manager)
        {
            return "gwent?";
        }

        for(i = 1; i < parts.Size(); i += 1)
        {
            pair = woSplit(parts[i], ":");

            if(pair.Size() < 2)
            {
                continue;
            }

            cardId = StringToInt(pair[0], -1);
            copies = StringToInt(pair[1], 0);

            if(cardId < 0 || copies <= 0)
            {
                continue;
            }

            have = countGwentCopies(manager, cardId, leaders);

            for(j = have; j < copies; j += 1)
            {
                manager.AddCardToCollection(cardId);
                added += 1;
            }
        }

        if(leaders)
        {
            return "gwentL+" + added;
        }

        return "gwent+" + added;
    }

    private function countGwentCopies(manager : CR4GwintManager, cardId : int, leaders : bool) : int
    {
        var cards : array<CollectionCard>;
        var i : int;

        if(leaders)
        {
            cards = manager.GetPlayerLeaderCollection();
        }
        else
        {
            cards = manager.GetPlayerCollection();
        }

        for(i = 0; i < cards.Size(); i += 1)
        {
            if(cards[i].cardID == cardId)
            {
                return cards[i].numCopies;
            }
        }

        return 0;
    }
}

function woSplit(text : string, divider : string) : array<string>
{
    var items : array<string>;
    var left : string;
    var right : string;
    var rest : string;

    rest = text;

    while(StrSplitFirst(rest, divider, left, right))
    {
        items.PushBack(left);
        rest = right;
    }

    if(rest != "")
    {
        items.PushBack(rest);
    }

    return items;
}

function wo_charSnapshotSlot() : string
{
    return "local";
}

exec function wo_char_save()
{
    var snapshot : r_CharSnapshot;
    var body : string;

    snapshot = new r_CharSnapshot in theGame;
    body = snapshot.capture();

    if(body == "")
    {
        WO_Note("[char] capture failed");
        return;
    }

    if(WO_CharStore(wo_charSnapshotSlot(), body))
    {
        WO_Note("[char] saved " + StrLen(body) + " bytes");
        GetWitcherPlayer().DisplayHudMessage("Character snapshot saved");
    }
    else
    {
        WO_Note("[char] store failed");
    }
}

exec function wo_char_load()
{
    var snapshot : r_CharSnapshot;
    var body : string;
    var report : string;

    body = WO_CharFetch(wo_charSnapshotSlot());

    if(body == "")
    {
        WO_Note("[char] no snapshot stored");
        return;
    }

    snapshot = new r_CharSnapshot in theGame;
    report = snapshot.restore(body);

    WO_Note("[char] restored: " + report);
    GetWitcherPlayer().DisplayHudMessage("Character restored");
}

exec function wo_char_dump()
{
    var snapshot : r_CharSnapshot;

    snapshot = new r_CharSnapshot in theGame;

    WO_Note("[char] " + snapshot.capture());
}
