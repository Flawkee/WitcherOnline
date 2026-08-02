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
    private var uploadPending : bool;
    private var uploadRequestedAt : float;
    private var adoptStage : int;
    private var adoptStartedAt : float;
    private var adoptLoadAt : float;
    private var resumeChecked : bool;
    private var uploadedFile : string;
    private var adoptAttempts : int;
    private var vanillaBaseline : array<string>;
    private var purgeUntil : float;
    private var nextPurgeAt : float;
    private var saveHoldUntil : float;
    private var nextGateReportAt : float;

    private var SAVE_INTERVAL : float;
    private var SAVE_SETTLE   : float;
    private var SAVE_TIMEOUT  : float;

    default active = false;
    default haveLock = false;
    default lockId = -1;
    default nextSaveAt = 0.0;
    default slotIndex = -1;
    default savePending = false;
    default pendingSince = 0.0;
    default manifestLoaded = false;
    default uploadPending = false;
    default uploadRequestedAt = 0.0;
    default adoptStage = 0;
    default adoptStartedAt = 0.0;
    default adoptLoadAt = 0.0;
    default adoptAttempts = 0;
    default resumeChecked = false;

    default purgeUntil = 0.0;
    default nextPurgeAt = 0.0;
    default saveHoldUntil = 0.0;
    default nextGateReportAt = 0.0;

    default SAVE_INTERVAL = 300.0;
    default SAVE_SETTLE = 1.0;
    default SAVE_TIMEOUT = 60.0;

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
        captureVanillaBaseline();

        nextSaveAt = theGame.GetEngineTimeAsSeconds() + SAVE_INTERVAL;

        WO_SaveIncomingDir(WO_SaveDirectory());

        WO_Note("[coopsave] session started, saves locked, interval=" + SAVE_INTERVAL
            + " saveDir=" + WO_SaveDirectory());

        if(!theGame.r_getMultiplayerClient().isPartyLeader())
        {
            adoptAttempts = 0;
            WO_SaveReset();
            WO_SaveWant();
            awaitIncomingSave();

            WO_Note("[coopsave] requested leader save");
        }
    }

    public function resumeSession()
    {
        if(active)
        {
            return;
        }

        active = true;
        loadManifest();
        acquireLock();
        captureVanillaBaseline();

        nextSaveAt = theGame.GetEngineTimeAsSeconds() + SAVE_INTERVAL;

        WO_SaveIncomingDir(WO_SaveDirectory());
        WO_Note("[coopsave] session resumed after adoption load");
    }

    public function abandonSession()
    {
        if(!active)
        {
            return;
        }

        active = false;
        savePending = false;
        releaseLock();

        WO_Note("[coopsave] session abandoned for the main menu");
    }

    public function endSession()
    {
        if(!active)
        {
            return;
        }

        sweepVanillaSaves();

        active = false;
        savePending = false;
        releaseLock();

        WO_Note("[coopsave] session ended, saves unlocked");
    }

    private function isVanillaAutoSave(slotType : ESaveGameType) : bool
    {
        return slotType == SGT_AutoSave
            || slotType == SGT_CheckPoint
            || slotType == SGT_ForcedCheckPoint
            || slotType == SGT_QuickSave;
    }

    private function captureVanillaBaseline()
    {
        var saves : array<SSavegameInfo>;
        var i : int;

        vanillaBaseline.Clear();

        theGame.ListSavedGames(saves);

        for(i = 0; i < saves.Size(); i += 1)
        {
            if(isVanillaAutoSave(saves[i].slotType))
            {
                vanillaBaseline.PushBack(saves[i].filename);
            }
        }
    }

    private function wasVanillaBefore(fileName : string) : bool
    {
        var i : int;

        for(i = 0; i < vanillaBaseline.Size(); i += 1)
        {
            if(vanillaBaseline[i] == fileName)
            {
                return true;
            }
        }

        return false;
    }

    private function sweepVanillaSaves()
    {
        var saves : array<SSavegameInfo>;

        theGame.ListSavedGames(saves);
        sweepListedSaves(saves);
    }

    private function sweepListedSaves(saves : array<SSavegameInfo>)
    {
        var removed : int;
        var i : int;

        for(i = saves.Size() - 1; i >= 0; i -= 1)
        {
            if(!isVanillaAutoSave(saves[i].slotType))
            {
                continue;
            }

            if(wasVanillaBefore(saves[i].filename))
            {
                continue;
            }

            theGame.DeleteSavedGame(saves[i]);
            removed += 1;
        }

        if(removed > 0)
        {
            WO_Note("[coopsave] discarded " + removed + " checkpoint/autosave(s) made during co-op");
        }
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

        resumeAfterLoad();
        retireUploadedSave();
        retryTransferPurge();

        serveSaveRequests();
        updateAdoption();

        now = theGame.GetEngineTimeAsSeconds();

        if(now >= nextGateReportAt)
        {
            nextGateReportAt = now + 30.0;

            WO_Note("[coopsave] gate active=" + active + " pending=" + savePending
                + " adopt=" + adoptStage + " now=" + now
                + " hold=" + saveHoldUntil + " next=" + nextSaveAt);
        }

        if(savePending)
        {
            if((now - pendingSince) >= SAVE_SETTLE)
            {
                if(newSaveAppeared() || (now - pendingSince) >= SAVE_TIMEOUT)
                {
                    finishSave();
                }
            }

            return;
        }

        if(!active)
        {
            return;
        }

        if(adoptStage != 0 || now < saveHoldUntil)
        {
            return;
        }

        if(now >= nextSaveAt)
        {
            beginSave(now);
        }
    }

    public function updateAdoption()
    {
        var transferState : int;
        var now : float;

        now = theGame.GetEngineTimeAsSeconds();

        if(adoptStage == 1)
        {
            transferState = WO_SaveState();

            if(transferState == 3)
            {
                beginAdoption();
                return;
            }

            if(transferState == 4)
            {
                adoptStage = 0;
                retryOrFail();
                return;
            }

            if((now - adoptStartedAt) > 600.0)
            {
                adoptStage = 0;
                retryOrFail();
            }

            return;
        }

        if(adoptStage == 2 && now >= adoptLoadAt)
        {
            adoptStage = 0;
            performAdoptionLoad();
        }
    }

    public function awaitIncomingSave()
    {
        adoptStage = 1;
        adoptStartedAt = theGame.GetEngineTimeAsSeconds();
    }

    private function retryOrFail()
    {
        adoptAttempts += 1;

        if(adoptAttempts >= 3)
        {
            notifyTransferFailed();
            return;
        }

        WO_Note("[coopsave] transfer attempt " + adoptAttempts + " failed, retrying");

        discardTransferSaves();
        WO_SaveReset();
        WO_SaveWant();
        awaitIncomingSave();
    }

    private function retireUploadedSave()
    {
        var saves : array<SSavegameInfo>;
        var i : int;

        if(uploadedFile == "" || WO_SaveState() != 3)
        {
            return;
        }

        if(active)
        {
            uploadedFile = "";
            return;
        }

        theGame.ListSavedGames(saves);

        for(i = saves.Size() - 1; i >= 0; i -= 1)
        {
            if(saves[i].filename == uploadedFile)
            {
                theGame.DeleteSavedGame(saves[i]);
                WO_Note("[coopsave] removed transfer save " + uploadedFile);
                break;
            }
        }

        forgetSave(uploadedFile);
        sweepLeftovers(uploadedFile);

        uploadedFile = "";
        WO_SaveReset();
    }

    private function sweepLeftovers(marker : string)
    {
        var remaining : int;
        var purged : int;

        if(marker == "")
        {
            return;
        }

        remaining = WO_SaveLeftovers(marker);

        if(remaining == 0)
        {
            return;
        }

        purged = WO_SavePurge(marker);

        WO_Note("[coopsave] engine left " + remaining + " file(s) for " + marker
            + ", purged " + purged);
    }

    private function discardTransferSaves()
    {
        var saves : array<SSavegameInfo>;
        var removed : int;
        var i : int;

        theGame.ListSavedGames(saves);

        for(i = saves.Size() - 1; i >= 0; i -= 1)
        {
            if(StrContains(StrLower(saves[i].filename), "woparty"))
            {
                theGame.DeleteSavedGame(saves[i]);
                removed += 1;
            }
        }

        WO_Note("[coopsave] engine delete removed " + removed + " transferred save(s), "
            + WO_SaveLeftovers("woparty") + " file(s) left on disk");

        purgeUntil = theGame.GetEngineTimeAsSeconds() + 180.0;
        nextPurgeAt = 0.0;
    }

    private function retryTransferPurge()
    {
        var saves : array<SSavegameInfo>;
        var now : float;
        var i : int;

        if(purgeUntil <= 0.0)
        {
            return;
        }

        now = theGame.GetEngineTimeAsSeconds();

        if(now < nextPurgeAt)
        {
            return;
        }

        nextPurgeAt = now + 3.0;

        if(WO_SaveLeftovers("woparty") == 0)
        {
            purgeUntil = 0.0;
            WO_Note("[coopsave] transferred save cleared");
            return;
        }

        if(now >= purgeUntil)
        {
            purgeUntil = 0.0;
            WO_Note("[coopsave] gave up clearing the transferred save");
            return;
        }

        theGame.ListSavedGames(saves);

        for(i = saves.Size() - 1; i >= 0; i -= 1)
        {
            if(StrContains(StrLower(saves[i].filename), "woparty"))
            {
                theGame.DeleteSavedGame(saves[i]);
            }
        }

        WO_SavePurge("woparty");
    }

    private function notifyTransferFailed()
    {
        var reason : string;

        reason = WO_SaveError();

        WO_Note("[coopsave] transfer failed: " + reason);

        discardTransferSaves();

        GetWitcherPlayer().DisplayHudMessage(GetLocStringById(2111114295));

        theGame.r_getMultiplayerClient().setCoopMode(false);
    }

    private function beginAdoption()
    {
        var snapshot : r_CharSnapshot;
        var body : string;

        snapshot = new r_CharSnapshot in this;
        body = snapshot.capture();

        if(body == "")
        {
            WO_Note("[coopsave] character capture failed, aborting adoption");
            notifyTransferFailed();
            return;
        }

        if(!WO_CoopStashChar(body))
        {
            WO_Note("[coopsave] character stash failed, aborting adoption");
            notifyTransferFailed();
            return;
        }

        WO_Note("[coopsave] character captured (" + StrLen(body) + " bytes), loading leader save "
            + WO_SaveFile());

        adoptStage = 2;
        adoptLoadAt = theGame.GetEngineTimeAsSeconds() + 2.0;
    }

    private function performAdoptionLoad()
    {
        var saves : array<SSavegameInfo>;
        var wanted : string;
        var i : int;

        wanted = WO_SaveFile();

        if(wanted == "")
        {
            return;
        }

        wanted = StrLower(wanted);

        theGame.ListSavedGames(saves);

        for(i = 0; i < saves.Size(); i += 1)
        {
            if(StrLower(saves[i].filename) == wanted)
            {
                WO_Note("[coopsave] loading " + saves[i].filename);

                releaseLock();
                WO_CoopMarkRestore();
                theGame.LoadGameInit(saves[i], false);
                return;
            }
        }

        WO_Note("[coopsave] leader save " + wanted + " not listed by the engine");
        notifyTransferFailed();
    }

    public function resumeAfterLoad()
    {
        var snapshot : r_CharSnapshot;
        var body : string;
        var report : string;

        if(adoptStage != 0)
        {
            return;
        }

        if(!WO_CoopTakeRestore())
        {
            return;
        }

        body = WO_CoopFetchChar();

        if(body == "")
        {
            WO_Note("[coopsave] no stored character to restore");
            return;
        }

        snapshot = new r_CharSnapshot in this;
        report = snapshot.restore(body);

        WO_Note("[coopsave] character restored after adoption: " + report);

        saveHoldUntil = theGame.GetEngineTimeAsSeconds() + SAVE_INTERVAL;
        nextSaveAt = saveHoldUntil;

        WO_Note("[coopsave] rolling save held until " + saveHoldUntil);

        discardTransferSaves();

        theGame.r_getMultiplayerClient().restoreCoopAfterLoad();

        GetWitcherPlayer().DisplayHudMessage(GetLocStringById(2111114297));
    }

    private function serveSaveRequests()
    {
        var asker : string;

        asker = WO_SaveNeededBy();

        if(asker == "")
        {
            return;
        }

        if(!theGame.r_getMultiplayerClient().isPartyLeader())
        {
            return;
        }

        if(savePending || uploadPending)
        {
            WO_Note("[coopsave] " + asker + " folded into the save already running");
            return;
        }

        WO_Note("[coopsave] leader asked to supply a save for " + asker);

        uploadPending = true;
        uploadRequestedAt = theGame.GetEngineTimeAsSeconds();

        beginSave(theGame.GetEngineTimeAsSeconds());
    }

    private function publishLatestSave()
    {
        var saves : array<SSavegameInfo>;
        var newest : string;
        var i : int;

        theGame.ListSavedGames(saves);

        for(i = 0; i < saves.Size(); i += 1)
        {
            if(isCoopSave(saves[i].filename))
            {
                newest = saves[i].filename;
            }
        }

        if(newest == "")
        {
            WO_Note("[coopsave] no co-op save to upload");
            return;
        }

        if(WO_SaveSend(WO_SaveDirectory() + "\\" + newest + ".sav"))
        {
            uploadedFile = newest;
            WO_Note("[coopsave] uploading " + newest);
        }
        else
        {
            WO_Note("[coopsave] upload failed to start: " + WO_SaveError());
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

    private function newSaveAppeared() : bool
    {
        var saves : array<SSavegameInfo>;
        var i : int;

        theGame.ListSavedGames(saves);

        for(i = 0; i < saves.Size(); i += 1)
        {
            if(!wasPresentBefore(saves[i].filename))
            {
                return true;
            }
        }

        return false;
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

        sweepListedSaves(saves);

        for(i = 0; i < saves.Size(); i += 1)
        {
            if(isVanillaAutoSave(saves[i].slotType))
            {
                continue;
            }

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

        if(uploadPending)
        {
            uploadPending = false;
            publishLatestSave();
        }

        if(active)
        {
            acquireLock();
        }

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

    private function forgetSave(fileName : string)
    {
        var kept : array<string>;
        var i : int;

        if(fileName == "")
        {
            return;
        }

        for(i = 0; i < manifest.Size(); i += 1)
        {
            if(manifest[i] != fileName)
            {
                kept.PushBack(manifest[i]);
            }
        }

        if(kept.Size() == manifest.Size())
        {
            return;
        }

        manifest = kept;
        storeManifest();
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

function WitcherOnline_RefreshCoopSaves()
{
    var client : r_MultiplayerClient;

    if(!theGame)
    {
        return;
    }

    client = theGame.r_getMultiplayerClient();

    if(client)
    {
        client.getCoopSave().refreshManifest();
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
