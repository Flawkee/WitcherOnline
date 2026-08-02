function WitcherOnline_CoopActive() : bool
{
    var client : r_MultiplayerClient;

    if(!theGame)
    {
        return false;
    }

    client = theGame.r_getMultiplayerClient();

    if(!client)
    {
        return false;
    }

    return client.getCoopSave().isActive();
}

function WitcherOnline_DenyWithNotice(stringId : int)
{
    theGame.GetGuiManager().ShowNotification(GetLocStringById(stringId));
    theSound.SoundEvent("gui_global_denied");
}

@wrapMethod(CR4GuiManager)
function DisplayLockedSavePopup() : void
{
    if(WitcherOnline_CoopActive())
    {
        WitcherOnline_DenyWithNotice(2111114298);
        return;
    }

    wrappedMethod();
}

@wrapMethod(CR4IngameMenu)
function SendSaveData() : void
{
    if(WitcherOnline_CoopActive())
    {
        CloseMenu();
        WitcherOnline_DenyWithNotice(2111114298);
        return;
    }

    wrappedMethod();
}

@wrapMethod(CR4IngameMenu)
function SendLoadData() : void
{
    if(WitcherOnline_CoopActive())
    {
        CloseMenu();
        WitcherOnline_DenyWithNotice(2111114299);
        return;
    }

    wrappedMethod();
}

@wrapMethod(CR4IngameMenu)
function LoadSaveRequested(saveSlotRef : SSavegameInfo) : void
{
    if(WitcherOnline_CoopActive())
    {
        CloseMenu();
        WitcherOnline_DenyWithNotice(2111114299);
        return;
    }

    wrappedMethod(saveSlotRef);
}

@wrapMethod(CR4IngameMenu)
function LoadLastSave() : void
{
    if(WitcherOnline_CoopActive())
    {
        CloseMenu();
        WitcherOnline_DenyWithNotice(2111114299);
        return;
    }

    wrappedMethod();
}
