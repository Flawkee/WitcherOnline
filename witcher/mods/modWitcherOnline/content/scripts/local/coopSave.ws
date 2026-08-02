class r_CoopSave
{
    private var active       : bool;
    private var lockId       : int;
    private var haveLock     : bool;
    private var nextSaveAt   : float;
    private var slotIndex    : int;
    private var savePending  : bool;
    private var pendingSince : float;
    private var manifest     : array<string>;
    private var beforeFiles  : array<string>;
    private var manifestLoaded : bool;

    private var SAVE_INTERVAL : float;
    private var SAVE_SETTLE   : float;

    default active = false;
    default haveLock = false;
    default lockId = -1;
    default nextSaveAt = 0.0;
    default slotIndex = -1;
    default savePending = false;
    default pendingSince = 0.0;
    default manifestLoaded = false;

    default SAVE_INTERVAL = 300.0;
    default SAVE_SETTLE = 3.0;

    public function isActive() : bool
    {
        return active;
    }

    public function beginSession()
    {
        if(active)
        {
            return;
        }

        active = true;
        loadManifest();
        acquireLock();

        nextSaveAt = theGame.GetEngineTimeAsSeconds() + SAVE_INTERVAL;

        WO_Note("[coopsave] session started, saves locked, interval=" + SAVE_INTERVAL);
    }

    public function endSession()
    {
        if(!active)
        {
            return;
        }

        active = false;
        savePending = false;
        releaseLock();

        WO_Note("[coopsave] session ended, saves unlocked");
    }

    private function acquireLock()
    {
        if(haveLock)
        {
            return;
        }

        theGame.CreateNoSaveLock("WitcherOnlineCoop", lockId, true, false);
        haveLock = true;
    }

    private function releaseLock()
    {
        if(!haveLock)
        {
            return;
        }

        theGame.ReleaseNoSaveLock(lockId);
        haveLock = false;
    }

    public function update()
    {
        var now : float;

        if(!active)
        {
            return;
        }

        now = theGame.GetEngineTimeAsSeconds();

        if(savePending)
        {
            if((now - pendingSince) >= SAVE_SETTLE)
            {
                finishSave();
            }

            return;
        }

        if(now >= nextSaveAt)
        {
            beginSave(now);
        }
    }

    public function saveNow()
    {
        if(!active || savePending)
        {
            return;
        }

        beginSave(theGame.GetEngineTimeAsSeconds());
    }

    private function beginSave(now : float)
    {
        if(!thePlayer || !thePlayer.IsAlive() || theGame.IsBlackscreen() || theGame.IsCurrentlyPlayingNonGameplayScene())
        {
            nextSaveAt = now + 20.0;
            return;
        }

        snapshotExisting();
        slotIndex = resolveSlot();
        releaseLock();

        theGame.SaveGame(SGT_Manual, slotIndex);

        savePending = true;
        pendingSince = now;

        WO_Note("[coopsave] saving slot=" + slotIndex + " existing=" + beforeFiles.Size());
    }

    private function snapshotExisting()
    {
        var saves : array<SSavegameInfo>;
        var i : int;

        beforeFiles.Clear();

        theGame.ListSavedGames(saves);

        for(i = 0; i < saves.Size(); i += 1)
        {
            beforeFiles.PushBack(saves[i].filename);
        }
    }

    private function wasPresentBefore(fileName : string) : bool
    {
        var i : int;

        for(i = 0; i < beforeFiles.Size(); i += 1)
        {
            if(beforeFiles[i] == fileName)
            {
                return true;
            }
        }

        return false;
    }

    private function finishSave()
    {
        var saves : array<SSavegameInfo>;
        var added : int;
        var i : int;

        savePending = false;

        theGame.ListSavedGames(saves);

        for(i = 0; i < saves.Size(); i += 1)
        {
            if(!wasPresentBefore(saves[i].filename))
            {
                rememberSave(saves[i].filename);
                added += 1;

                WO_Note("[coopsave] saved file=" + saves[i].filename
                    + " slotIndex=" + saves[i].slotIndex);
            }
        }

        if(added == 0)
        {
            WO_Note("[coopsave] save produced no new file");
        }

        pruneManifest(saves);
        acquireLock();

        nextSaveAt = theGame.GetEngineTimeAsSeconds() + SAVE_INTERVAL;
    }

    private function pruneManifest(saves : array<SSavegameInfo>)
    {
        var kept : array<string>;
        var found : bool;
        var i : int;
        var j : int;

        for(i = 0; i < manifest.Size(); i += 1)
        {
            found = false;

            for(j = 0; j < saves.Size(); j += 1)
            {
                if(saves[j].filename == manifest[i])
                {
                    found = true;
                    break;
                }
            }

            if(found)
            {
                kept.PushBack(manifest[i]);
            }
        }

        if(kept.Size() != manifest.Size())
        {
            manifest = kept;
            storeManifest();
        }
    }

    private function resolveSlot() : int
    {
        var saves : array<SSavegameInfo>;
        var i : int;

        theGame.ListSavedGames(saves);

        for(i = 0; i < saves.Size(); i += 1)
        {
            if(saves[i].slotType == SGT_Manual && isCoopSave(saves[i].filename))
            {
                return saves[i].slotIndex;
            }
        }

        return -1;
    }

    public function isCoopSave(fileName : string) : bool
    {
        var i : int;

        if(fileName == "")
        {
            return false;
        }

        if(!manifestLoaded)
        {
            loadManifest();
        }

        for(i = 0; i < manifest.Size(); i += 1)
        {
            if(manifest[i] == fileName)
            {
                return true;
            }
        }

        return false;
    }

    private function rememberSave(fileName : string)
    {
        if(fileName == "" || isCoopSave(fileName))
        {
            return;
        }

        manifest.PushBack(fileName);
        storeManifest();
    }

    private function loadManifest()
    {
        var body : string;

        manifest.Clear();
        manifestLoaded = true;

        body = WO_CharFetch("coopmanifest");

        if(body == "")
        {
            return;
        }

        manifest = woSplit(body, "#");
    }

    private function storeManifest()
    {
        var body : string;
        var i : int;

        for(i = 0; i < manifest.Size(); i += 1)
        {
            if(i > 0)
            {
                body += "#";
            }

            body += manifest[i];
        }

        WO_CharStore("coopmanifest", body);
    }

    public function refreshManifest()
    {
        loadManifest();
    }
}

function WitcherOnline_TagCoopSave(fileName : string, label : string) : string
{
    var client : r_MultiplayerClient;

    if(fileName == "" || !theGame)
    {
        return label;
    }

    client = theGame.r_getMultiplayerClient();

    if(!client || !client.getCoopSave().isCoopSave(fileName))
    {
        return label;
    }

    return GetLocStringById(2111114294) + " " + label;
}

exec function wo_coop_save()
{
    theGame.r_getMultiplayerClient().getCoopSave().saveNow();
}

exec function wo_coop_saves()
{
    var coop : r_CoopSave;
    var saves : array<SSavegameInfo>;
    var listed : int;
    var i : int;

    coop = theGame.r_getMultiplayerClient().getCoopSave();
    coop.refreshManifest();

    theGame.ListSavedGames(saves);

    for(i = 0; i < saves.Size(); i += 1)
    {
        if(coop.isCoopSave(saves[i].filename))
        {
            WO_Note("[coopsave] " + saves[i].filename
                + " | " + theGame.GetDisplayNameForSavedGame(saves[i]));
            listed += 1;
        }
    }

    WO_Note("[coopsave] " + listed + " co-op saves of " + saves.Size() + " total");
}
