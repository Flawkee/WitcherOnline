class r_NpcTemplates
{
    private var keys  : array<string>;
    private var paths : array<string>;
    private var segments : array<string>;
    private var initialised : bool;

    default initialised = false;

    private function add(key : string, path : string)
    {
        keys.PushBack(key);
        paths.PushBack(path);
    }

    private function ensure()
    {
        if(initialised)
        {
            return;
        }

        initialised = true;

        add("alghoul", "characters/npc_entities/monsters/alghoul_lvl1.w2ent");
        add("arachas", "characters/npc_entities/monsters/arachas_lvl1.w2ent");
        add("arachas_armored", "characters/npc_entities/monsters/arachas_lvl2__armored.w2ent");
        add("arachas_poison", "characters/npc_entities/monsters/arachas_lvl3__poison.w2ent");
        add("barghest", "dlc/bob/data/characters/npc_entities/monsters/barghest.w2ent");
        add("barghest_wight_minion", "dlc/bob/data/characters/npc_entities/monsters/barghest_wight_minion.w2ent");
        add("basilisk", "characters/npc_entities/monsters/basilisk_lvl1.w2ent");
        add("bat", "characters/npc_entities/animals/bat.w2ent");
        add("bear_berserker", "characters/npc_entities/monsters/bear_berserker_lvl1.w2ent");
        add("bear_black", "characters/npc_entities/monsters/bear_lvl1__black.w2ent");
        add("bear_grizzly", "characters/npc_entities/monsters/bear_lvl2__grizzly.w2ent");
        add("bear_white", "characters/npc_entities/monsters/bear_lvl3__white.w2ent");
        add("bies", "characters/npc_entities/monsters/bies_lvl1.w2ent");
        add("black_mage", "characters/npc_entities/monsters/black_mage_lvl1.w2ent");
        add("black_spider", "dlc/bob/data/characters/npc_entities/monsters/black_spider_ep2.w2ent");
        add("black_spider_large", "dlc/bob/data/characters/npc_entities/monsters/black_spider_large_ep2.w2ent");
        add("blood_flies", "characters/npc_entities/monsters/blood_flies.w2ent");
        add("bruxa", "dlc/bob/data/characters/npc_entities/monsters/bruxa.w2ent");
        add("bruxa_alp", "dlc/bob/data/characters/npc_entities/monsters/bruxa_alp.w2ent");
        add("bruxa_alp_cloak_always_spawn", "dlc/bob/data/characters/npc_entities/monsters/bruxa_alp_cloak_always_spawn.w2ent");
        add("bruxa_cloak", "dlc/bob/data/characters/npc_entities/monsters/bruxa_cloak.w2ent");
        add("burnedman", "characters/npc_entities/monsters/burnedman_lvl1.w2ent");
        add("cat", "characters/npc_entities/animals/cat.w2ent");
        add("chicken", "characters/npc_entities/animals/chicken.w2ent");
        add("cockatrice", "characters/npc_entities/monsters/cockatrice_lvl1.w2ent");
        add("cow", "characters/npc_entities/animals/cow.w2ent");
        add("crab", "characters/npc_entities/animals/crab.w2ent");
        add("crow", "characters/npc_entities/animals/crow.w2ent");
        add("cyclop", "characters/npc_entities/monsters/cyclop_lvl1.w2ent");
        add("czart", "characters/npc_entities/monsters/czart_lvl1.w2ent");
        add("deer", "characters/npc_entities/animals/deer.w2ent");
        add("deer_roe", "characters/npc_entities/animals/deer_roe.w2ent");
        add("demonic_cat", "dlc/ep1/data/characters/npc_entities/animals/demonic_cat.w2ent");
        add("demonic_cat_mimic", "dlc/ep1/data/characters/npc_entities/animals/demonic_cat_mimic.w2ent");
        add("demonic_dog", "dlc/ep1/data/characters/npc_entities/animals/demonic_dog.w2ent");
        add("demonic_dog_mimic", "dlc/ep1/data/characters/npc_entities/animals/demonic_dog_mimic.w2ent");
        add("dog", "characters/npc_entities/animals/dog.w2ent");
        add("drowner", "characters/npc_entities/monsters/drowner_lvl1.w2ent");
        add("drowner_dead", "characters/npc_entities/monsters/drowner_lvl4__dead.w2ent");
        add("drowner_underwater", "characters/npc_entities/monsters/drowner_lvl1__underwater.w2ent");
        add("elemental_dao", "characters/npc_entities/monsters/elemental_dao_lvl1.w2ent");
        add("elemental_dao_ice", "characters/npc_entities/monsters/elemental_dao_lvl3__ice.w2ent");
        add("endriaga_spikey", "characters/npc_entities/monsters/endriaga_lvl3__spikey.w2ent");
        add("endriaga_tailed", "characters/npc_entities/monsters/endriaga_lvl2__tailed.w2ent");
        add("endriaga_worker", "characters/npc_entities/monsters/endriaga_lvl1__worker.w2ent");
        add("ethernal", "dlc/ep1/data/characters/npc_entities/monsters/ethernal.w2ent");
        add("fish_kingfish", "characters/npc_entities/animals/fish_kingfish.w2ent");
        add("fish_mackerel", "characters/npc_entities/animals/fish_mackerel.w2ent");
        add("fish_roach", "characters/npc_entities/animals/fish_roach.w2ent");
        add("fish_tuna", "characters/npc_entities/animals/fish_tuna.w2ent");
        add("fogling", "characters/npc_entities/monsters/fogling_lvl1.w2ent");
        add("fogling_doppelganger", "characters/npc_entities/monsters/fogling_lvl1__doppelganger.w2ent");
        add("fogling_willowisp", "characters/npc_entities/monsters/fogling_lvl3__willowisp.w2ent");
        add("forktail", "characters/npc_entities/monsters/forktail_lvl1.w2ent");
        add("fugas", "characters/npc_entities/monsters/fugas_lvl1.w2ent");
        add("gargoyle", "characters/npc_entities/monsters/gargoyle_lvl1.w2ent");
        add("garkain", "dlc/bob/data/characters/npc_entities/monsters/garkain_mh.w2ent");
        add("ghoul", "characters/npc_entities/monsters/ghoul_lvl1.w2ent");
        add("ghoul_ghost", "dlc/ep1/data/characters/npc_entities/monsters/ghoul_ghost.w2ent");
        add("goat", "characters/npc_entities/animals/goat.w2ent");
        add("golem", "characters/npc_entities/monsters/golem_lvl1.w2ent");
        add("golem_boss", "characters/npc_entities/monsters/golem_lvl1_boss.w2ent");
        add("golem_ifryt", "characters/npc_entities/monsters/golem_lvl2__ifryt.w2ent");
        add("goose", "characters/npc_entities/animals/goose.w2ent");
        add("goose_leader", "characters/npc_entities/animals/goose_leader.w2ent");
        add("grizzly_bear", "characters/npc_entities/animals/grizzly_bear.w2ent");
        add("gryphon", "characters/npc_entities/monsters/gryphon_lvl1.w2ent");
        add("gryphon_volcanic", "characters/npc_entities/monsters/gryphon_lvl3__volcanic.w2ent");
        add("guard_dog", "characters/npc_entities/animals/guard_dog.w2ent");
        add("hag_grave", "characters/npc_entities/monsters/hag_grave_lvl1.w2ent");
        add("hag_grave_barons_wife", "characters/npc_entities/monsters/hag_grave_lvl1__barons_wife.w2ent");
        add("hag_grave_mh", "characters/npc_entities/monsters/hag_grave__mh.w2ent");
        add("hag_water", "characters/npc_entities/monsters/hag_water_lvl1.w2ent");
        add("hare", "characters/npc_entities/animals/hare.w2ent");
        add("harpy", "characters/npc_entities/monsters/harpy_lvl1.w2ent");
        add("harpy_customize", "characters/npc_entities/monsters/harpy_lvl2_customize.w2ent");
        add("harpy_erynia", "characters/npc_entities/monsters/harpy_lvl3__erynia.w2ent");
        add("harpy_helmet", "dlc/ep1/data/characters/npc_entities/monsters/harpy_helmet_lvl1.w2ent");
        add("harpy_helmet_erynia", "dlc/ep1/data/characters/npc_entities/monsters/harpy_helmet_lvl3__erynia.w2ent");
        add("horse_background", "characters/npc_entities/animals/horse/horse_background.w2ent");
        add("horse_background_no_saddle", "characters/npc_entities/animals/horse/horse_background_no_saddle.w2ent");
        add("horse_background_wild_hunt", "characters/npc_entities/animals/horse/horse_background_wild_hunt.w2ent");
        add("horse_racing_fast", "characters/npc_entities/animals/horse/horse_racing_fast.w2ent");
        add("horse_racing_ofir", "dlc/ep1/data/characters/npc_entities/animals/horse/horse_racing_ofir.w2ent");
        add("horse_racing_slow", "characters/npc_entities/animals/horse/horse_racing_slow.w2ent");
        add("horse_rideable_no_saddle", "characters/npc_entities/animals/horse/horse_rideable_no_saddle.w2ent");
        add("horse_vehicle", "characters/npc_entities/animals/horse/horse_vehicle.w2ent");
        add("horse_vehicle_wild_hunt", "characters/npc_entities/animals/horse/horse_vehicle_wild_hunt.w2ent");
        add("horse_wild_regular", "characters/npc_entities/animals/horse/horse_wild_regular.w2ent");
        add("horse_wild_skellige", "characters/npc_entities/animals/horse/horse_wild_skellige.w2ent");
        add("ice_giant", "characters/npc_entities/monsters/ice_giant.w2ent");
        add("kikimore", "dlc/bob/data/characters/npc_entities/monsters/kikimore.w2ent");
        add("kikimore_small", "dlc/bob/data/characters/npc_entities/monsters/kikimore_small.w2ent");
        add("lessog", "characters/npc_entities/monsters/lessog_lvl1.w2ent");
        add("lessog_ancient", "characters/npc_entities/monsters/lessog_lvl2__ancient.w2ent");
        add("mountain_goat", "characters/npc_entities/animals/mountain_goat.w2ent");
        add("mouse", "characters/npc_entities/animals/mouse.w2ent");
        add("mq7023_panther_magic", "dlc/bob/data/characters/npc_entities/monsters/mq7023_panther_magic.w2ent");
        add("nekker", "characters/npc_entities/monsters/nekker_lvl1.w2ent");
        add("nekker_customize", "characters/npc_entities/monsters/nekker_lvl2_customize.w2ent");
        add("nekker_warrior", "characters/npc_entities/monsters/nekker_lvl3__warrior.w2ent");
        add("nightwraith", "characters/npc_entities/monsters/nightwraith_lvl1.w2ent");
        add("nightwraith_doppelganger", "characters/npc_entities/monsters/nightwraith_lvl1__doppelganger.w2ent");
        add("nightwraith_iris", "dlc/ep1/data/characters/npc_entities/monsters/nightwraith_iris.w2ent");
        add("noonwraith", "characters/npc_entities/monsters/noonwraith_lvl1.w2ent");
        add("noonwraith_doppelganger", "characters/npc_entities/monsters/noonwraith_lvl1__doppelganger.w2ent");
        add("owl", "characters/npc_entities/animals/owl.w2ent");
        add("owl_filippa", "characters/npc_entities/animals/owl_filippa.w2ent");
        add("panther_black", "dlc/bob/data/characters/npc_entities/monsters/panther_black.w2ent");
        add("panther_leopard", "dlc/bob/data/characters/npc_entities/monsters/panther_leopard.w2ent");
        add("panther_mountain", "dlc/bob/data/characters/npc_entities/monsters/panther_mountain.w2ent");
        add("pig", "characters/npc_entities/animals/pig.w2ent");
        add("pigeon", "characters/npc_entities/animals/pigeon.w2ent");
        add("pit_victims", "characters/npc_entities/monsters/pit_victims.w2ent");
        add("player_horse", "characters/npc_entities/animals/horse/player_horse.w2ent");
        add("player_horse_manager", "characters/npc_entities/animals/horse/player_horse_manager.w2ent");
        add("race_pig", "dlc/ep1/data/characters/npc_entities/animals/race_pig.w2ent");
        add("ram", "characters/npc_entities/animals/ram.w2ent");
        add("rat", "characters/npc_entities/animals/rat.w2ent");
        add("rat_aggressive", "characters/npc_entities/animals/rat_aggressive.w2ent");
        add("rooster", "characters/npc_entities/animals/rooster.w2ent");
        add("rotfiend", "characters/npc_entities/monsters/rotfiend_lvl1.w2ent");
        add("scolopendromorph", "dlc/bob/data/characters/npc_entities/monsters/scolopendromorph.w2ent");
        add("seagull", "characters/npc_entities/animals/seagull.w2ent");
        add("sheep", "characters/npc_entities/animals/sheep.w2ent");
        add("siren", "characters/npc_entities/monsters/siren_lvl1.w2ent");
        add("siren_lamia", "characters/npc_entities/monsters/siren_lvl2__lamia.w2ent");
        add("snow_deer", "characters/npc_entities/animals/snow_deer.w2ent");
        add("snow_rabbit", "characters/npc_entities/animals/snow_rabbit.w2ent");
        add("sparrow", "characters/npc_entities/animals/sparrow.w2ent");
        add("spider_ghost", "dlc/ep1/data/characters/npc_entities/monsters/spider_ghost.w2ent");
        add("swallow", "characters/npc_entities/animals/swallow.w2ent");
        add("toad", "characters/npc_entities/animals/toad.w2ent");
        add("troll_cave", "characters/npc_entities/monsters/troll_cave_lvl1.w2ent");
        add("troll_cave_black", "characters/npc_entities/monsters/troll_cave_mh__black.w2ent");
        add("troll_cave_ice", "characters/npc_entities/monsters/troll_cave_lvl3__ice.w2ent");
        add("troll_ice", "characters/npc_entities/monsters/troll_ice_lvl13.w2ent");
        add("vampire_ekima", "characters/npc_entities/monsters/vampire_ekima_lvl1.w2ent");
        add("vampire_katakan", "characters/npc_entities/monsters/vampire_katakan_lvl1.w2ent");
        add("werewolf", "characters/npc_entities/monsters/werewolf_lvl1.w2ent");
        add("werewolf_lycan", "characters/npc_entities/monsters/werewolf_lvl3__lycan.w2ent");
        add("whale", "characters/npc_entities/animals/whale.w2ent");
        add("wild_dog", "characters/npc_entities/monsters/wild_dog_lvl1.w2ent");
        add("wildhunt_minion", "characters/npc_entities/monsters/wildhunt_minion_lvl1.w2ent");
        add("wolf", "characters/npc_entities/monsters/wolf_lvl1.w2ent");
        add("wolf_alpha", "characters/npc_entities/monsters/wolf_lvl1__alpha.w2ent");
        add("wolf_summon", "characters/npc_entities/monsters/wolf_lvl1__summon.w2ent");
        add("wolf_summon_were", "characters/npc_entities/monsters/wolf_lvl1__summon_were.w2ent");
        add("wolf_white", "characters/npc_entities/monsters/wolf_white_lvl2.w2ent");
        add("wolf_white_alpha", "characters/npc_entities/monsters/wolf_white_lvl3__alpha.w2ent");
        add("wraith", "characters/npc_entities/monsters/wraith_lvl1.w2ent");
        add("wraith_customize", "characters/npc_entities/monsters/wraith_lvl2_customize.w2ent");
        add("wyvern", "characters/npc_entities/monsters/wyvern_lvl1.w2ent");

        add("human_m",      "characters/npc_entities/crowd_npc/novigrad_citizen/novigrad_citizen.w2ent");
        add("human_w",      "characters/npc_entities/crowd_npc/novigrad_citizen/novigrad_citizen_woman.w2ent");
        add("villager_m",   "characters/npc_entities/crowd_npc/nml_villager/nml_villager.w2ent");
        add("villager_w",   "characters/npc_entities/crowd_npc/nml_villager/nml_villager_woman.w2ent");
        add("prolog_m",     "characters/npc_entities/crowd_npc/prolog_villager/prolog_villager.w2ent");
        add("prolog_w",     "characters/npc_entities/crowd_npc/prolog_villager/prolog_villager_woman.w2ent");
        add("child_m",      "characters/npc_entities/crowd_npc/nml_villager/nml_child_boy.w2ent");
        add("child_w",      "characters/npc_entities/crowd_npc/nml_villager/nml_child_girl.w2ent");
        add("bandit",       "characters/npc_entities/crowd_npc/bandit/bandit_lvl1.w2ent");
        add("guard",        "characters/npc_entities/crowd_npc/novigrad_soldier/novigrad_guard_lvl2.w2ent");
        add("nilfgaard",    "characters/npc_entities/crowd_npc/nilfgaard_soldier/nilfgaard_squire_lvl2.w2ent");
        add("nilfknight",   "characters/npc_entities/crowd_npc/nilfgaard_soldier/nilfgaard_knight_lvl3.w2ent");
        add("skellige_m",   "characters/npc_entities/crowd_npc/skellige_villager/skellige_villager__skinny.w2ent");
        add("skellige_w",   "characters/npc_entities/crowd_npc/skellige_villager/skellige_villager_woman.w2ent");
        add("pirate",       "characters/npc_entities/crowd_npc/skellige_pirate/skellige_pirate_lvl1.w2ent");
        add("bear",         "characters/npc_entities/monsters/bear_lvl1__black.w2ent");
        add("griffin",      "characters/npc_entities/monsters/gryphon_lvl1.w2ent");
        add("craftsman",    "characters/npc_entities/crowd_npc/nml_craftsman/nml_craftsman.w2ent");
        add("prolog_craftsman", "characters/npc_entities/crowd_npc/prolog_craftsman/prolog_craftsman.w2ent");
        add("citizen_m",    "characters/npc_entities/crowd_npc/novigrad_citizen/novigrad_citizen.w2ent");
        add("citizen_w",    "characters/npc_entities/crowd_npc/novigrad_citizen/novigrad_citizen_woman.w2ent");
        add("nilfgaard_ranged", "characters/npc_entities/crowd_npc/nilfgaard_soldier/nilfgaard_ranged.w2ent");
    }

    private function buildSegments(text : string)
    {
        var rest : string;
        var left : string;
        var right : string;
        var token : string;

        segments.Clear();
        rest = text;

        while(rest != "")
        {
            if(StrSplitFirst(rest, "_", left, right))
            {
                token = left;
                rest = right;
            }
            else
            {
                token = rest;
                rest = "";
            }

            if(token != "")
            {
                segments.PushBack(token);
            }
        }
    }

    private function hasSegment(token : string) : bool
    {
        var i : int;

        for(i = 0; i < segments.Size(); i += 1)
        {
            if(segments[i] == token)
            {
                return true;
            }
        }

        return false;
    }

    private function keyMatches(key : string, out score : int) : bool
    {
        var rest : string;
        var left : string;
        var right : string;
        var token : string;

        score = 0;
        rest = key;

        while(rest != "")
        {
            if(StrSplitFirst(rest, "_", left, right))
            {
                token = left;
                rest = right;
            }
            else
            {
                token = rest;
                rest = "";
            }

            if(token == "")
            {
                continue;
            }

            if(!hasSegment(token))
            {
                return false;
            }

            score += StrLen(token);
        }

        return score > 0;
    }

    private function scanSegments(out best : string, out bestScore : int, out bestIsMonster : bool)
    {
        var score : int;
        var isMonster : bool;
        var i : int;

        for(i = 0; i < keys.Size(); i += 1)
        {
            if(!keyMatches(keys[i], score))
            {
                continue;
            }

            isMonster = StrContains(paths[i], "/monsters/");

            if(score > bestScore || (score == bestScore && isMonster && !bestIsMonster))
            {
                bestScore = score;
                best = keys[i];
                bestIsMonster = isMonster;
            }
        }
    }

    public function resolveSpecies(appearance : name, soundName : name) : string
    {
        var best : string;
        var bestScore : int;
        var bestIsMonster : bool;
        var text : string;

        ensure();

        best = "";
        bestScore = 0;
        bestIsMonster = false;

        text = StrLower(NameToString(appearance));

        if(text != "" && text != "none")
        {
            buildSegments(text);
            scanSegments(best, bestScore, bestIsMonster);
        }

        if(best != "")
        {
            return best;
        }

        text = StrLower(NameToString(soundName));

        if(text != "" && text != "none")
        {
            buildSegments(text);
            scanSegments(best, bestScore, bestIsMonster);
        }

        if(best != "")
        {
            return best;
        }

        return resolveHumanoid(StrLower(NameToString(appearance)));
    }

    private function isTradeSegment() : bool
    {
        return hasSegment("craftsman") || hasSegment("blacksmith") || hasSegment("smith")
            || hasSegment("innkeeper") || hasSegment("merchant") || hasSegment("trader")
            || hasSegment("herbalist") || hasSegment("armorer");
    }

    public function resolveHumanoid(text : string) : string
    {
        var female : bool;

        ensure();

        if(text == "" || text == "none")
        {
            return "";
        }

        buildSegments(text);

        female = hasSegment("woman") || hasSegment("women") || hasSegment("wa")
            || hasSegment("girl") || hasSegment("cwa") || hasSegment("female");

        if(hasSegment("nilfgaard") || hasSegment("nilf"))
        {
            if(hasSegment("knight"))
            {
                return "nilfknight";
            }

            if(hasSegment("ranged") || hasSegment("crossbow") || hasSegment("archer"))
            {
                return "nilfgaard_ranged";
            }

            return "nilfgaard";
        }

        if(hasSegment("bandit") || hasSegment("deserter") || hasSegment("thug")
            || hasSegment("cannibal") || hasSegment("robber"))
        {
            return "bandit";
        }

        if(hasSegment("guard") || hasSegment("soldier") || hasSegment("militia")
            || hasSegment("watchman") || hasSegment("inquisitor") || hasSegment("inquisition"))
        {
            return "guard";
        }

        if(hasSegment("pirate"))
        {
            return "pirate";
        }

        if(hasSegment("skellige"))
        {
            if(female)
            {
                return "skellige_w";
            }

            return "skellige_m";
        }

        if(hasSegment("prolog"))
        {
            if(isTradeSegment())
            {
                return "prolog_craftsman";
            }

            if(hasSegment("boy") || hasSegment("child"))
            {
                return "child_m";
            }

            if(hasSegment("girl"))
            {
                return "child_w";
            }

            if(female)
            {
                return "prolog_w";
            }

            return "prolog_m";
        }

        if(hasSegment("boy"))
        {
            return "child_m";
        }

        if(hasSegment("girl"))
        {
            return "child_w";
        }

        if(hasSegment("child"))
        {
            if(female)
            {
                return "child_w";
            }

            return "child_m";
        }

        if(isTradeSegment())
        {
            return "craftsman";
        }

        if(hasSegment("villager") || hasSegment("peasant"))
        {
            if(female)
            {
                return "villager_w";
            }

            return "villager_m";
        }

        if(hasSegment("citizen") || hasSegment("novigrad") || hasSegment("beggar")
            || hasSegment("bard") || hasSegment("worker") || hasSegment("sailor"))
        {
            if(female)
            {
                return "citizen_w";
            }

            return "citizen_m";
        }

        return "";
    }

    public function pathForToken(token : string) : string
    {
        ensure();

        if(token == "" || token == "-")
        {
            return "";
        }

        if(StrContains(token, ".w2ent"))
        {
            return token;
        }

        return lookup(StrLower(token));
    }

    private function lookup(key : string) : string
    {
        var i : int;

        ensure();

        for(i = 0; i < keys.Size(); i += 1)
        {
            if(keys[i] == key)
            {
                return paths[i];
            }
        }

        return "";
    }

    public function pathFor(code : name) : string
    {
        return lookup(StrLower(NameToString(code)));
    }

    public function hasCode(code : name) : bool
    {
        return pathFor(code) != "";
    }

    public function codeFromAppearance(appearance : name) : name
    {
        var text : string;

        ensure();

        text = StrLower(NameToString(appearance));

        if(text == "" || text == "none")
        {
            return '';
        }

        if(StrBeginsWith(text, "wolf") || StrContains(text, "_wolf"))
        {
            if(StrContains(text, "white"))
            {
                if(StrContains(text, "alpha"))
                {
                    return 'wolf_white_alpha';
                }

                return 'wolf_white';
            }

            if(StrContains(text, "alpha") || StrContains(text, "warg") || StrContains(text, "leader"))
            {
                return 'wolf_alpha';
            }

            if(StrContains(text, "were"))
            {
                return 'werewolf';
            }

            return 'wolf';
        }

        if(StrBeginsWith(text, "bear") || StrContains(text, "_bear"))
        {
            if(StrContains(text, "berserk"))
            {
                return 'bear_berserker';
            }

            if(StrContains(text, "white") || StrContains(text, "polar"))
            {
                return 'bear_white';
            }

            if(StrContains(text, "grizzly") || StrContains(text, "brown"))
            {
                return 'bear_grizzly';
            }

            return 'bear_black';
        }

        if(StrContains(text, "white_stallion") || StrBeginsWith(text, "horse"))
        {
            return 'horse';
        }

        if(StrBeginsWith(text, "snow_deer"))
        {
            return 'snowdeer';
        }

        if(StrBeginsWith(text, "deer_roe") || StrBeginsWith(text, "roe"))
        {
            return 'roe';
        }

        if(StrBeginsWith(text, "deer"))
        {
            return 'deer';
        }

        if(StrBeginsWith(text, "snow_rabbit"))
        {
            return 'snowhare';
        }

        if(StrBeginsWith(text, "hare") || StrBeginsWith(text, "rabbit"))
        {
            return 'hare';
        }

        if(StrBeginsWith(text, "sheep"))
        {
            return 'sheep';
        }

        if(StrBeginsWith(text, "ram"))
        {
            return 'ram';
        }

        if(StrBeginsWith(text, "mountain_goat"))
        {
            return 'mountaingoat';
        }

        if(StrBeginsWith(text, "goat"))
        {
            return 'goat';
        }

        if(StrBeginsWith(text, "cow") || StrBeginsWith(text, "bull"))
        {
            return 'cow';
        }

        if(StrBeginsWith(text, "pig") || StrBeginsWith(text, "boar"))
        {
            return 'pig';
        }

        if(StrBeginsWith(text, "guard_dog"))
        {
            return 'guarddog';
        }

        if(StrBeginsWith(text, "dog"))
        {
            return 'dog';
        }

        if(StrBeginsWith(text, "rooster"))
        {
            return 'rooster';
        }

        if(StrBeginsWith(text, "chicken") || StrBeginsWith(text, "hen"))
        {
            return 'chicken';
        }

        if(StrBeginsWith(text, "goose"))
        {
            return 'goose';
        }

        if(StrBeginsWith(text, "cat"))
        {
            return 'cat';
        }

        if(StrBeginsWith(text, "rat") || StrBeginsWith(text, "mouse"))
        {
            return 'rat';
        }

        if(StrBeginsWith(text, "crow") || StrBeginsWith(text, "raven"))
        {
            return 'crow';
        }

        if(StrContains(text, "villager_woman") || StrContains(text, "woman_villager"))
        {
            return 'villager_w';
        }

        if(StrContains(text, "prolog_villager_woman"))
        {
            return 'prolog_w';
        }

        if(StrContains(text, "prolog_villager"))
        {
            return 'prolog_m';
        }

        if(StrContains(text, "villager"))
        {
            return 'villager_m';
        }

        if(StrContains(text, "nilfgaard_knight"))
        {
            return 'nilfknight';
        }

        if(StrContains(text, "nilfgaard"))
        {
            return 'nilfgaard';
        }

        if(StrContains(text, "bandit") || StrContains(text, "cannibal"))
        {
            return 'bandit';
        }

        if(StrContains(text, "guard") || StrContains(text, "soldier"))
        {
            return 'guard';
        }

        if(StrContains(text, "pirate"))
        {
            return 'pirate';
        }

        if(StrContains(text, "skellige_woman") || StrContains(text, "skellige_villager_woman"))
        {
            return 'skellige_w';
        }

        if(StrContains(text, "skellige"))
        {
            return 'skellige_m';
        }

        if(StrBeginsWith(text, "girl") || StrContains(text, "child_girl"))
        {
            return 'child_w';
        }

        if(StrBeginsWith(text, "boy") || StrContains(text, "child"))
        {
            return 'child_m';
        }

        return '';
    }

    public function tokenForCategory(category : EMonsterCategory) : string
    {
        switch(category)
        {
            case MC_Necrophage:
                return "drowner";

            case MC_Beast:
                return "wolf";

            case MC_Insectoid:
                return "endriaga";

            case MC_Specter:
                return "wraith";

            case MC_Cursed:
                return "werewolf";

            case MC_Vampire:
                return "katakan";

            case MC_Draconide:
                return "forktail";

            case MC_Hybrid:
                return "harpy";

            case MC_Troll:
                return "troll";

            case MC_Relic:
                return "fugas";

            case MC_Magicals:
                return "golem";

            case MC_Animal:
                return "deer";

            default:
                break;
        }

        return "deer";
    }

    public function pathForCategory(category : EMonsterCategory, human : bool, woman : bool) : string
    {
        ensure();

        switch(category)
        {
            case MC_Necrophage:
                return lookup("drowner");

            case MC_Beast:
                return lookup("wolf");

            case MC_Insectoid:
                return lookup("endriaga");

            case MC_Specter:
                return lookup("wraith");

            case MC_Cursed:
                return lookup("werewolf");

            case MC_Vampire:
                return lookup("katakan");

            case MC_Draconide:
                return lookup("forktail");

            case MC_Hybrid:
                return lookup("harpy");

            case MC_Troll:
                return lookup("troll");

            case MC_Relic:
                return lookup("fugas");

            case MC_Magicals:
                return lookup("golem");

            case MC_Animal:
                return lookup("deer");

            default:
                break;
        }

        if(human)
        {
            if(woman)
            {
                return lookup("human_w");
            }

            return lookup("human_m");
        }

        return lookup("deer");
    }
}
