import function WO_Send(payload : string) : bool;
import function WO_Poll() : int;
import function WO_Opcode() : int;
import function WO_PlayerId() : int;
import function WO_Sequence() : int;
import function WO_Sender() : string;
import function WO_Str(index : int) : string;
import function WO_Int(index : int) : int;
import function WO_Float(index : int) : float;
import function WO_Bool(index : int) : bool;
import function WO_LocalId() : int;
import function WO_Username() : string;
import function WO_Connected() : bool;
import function WO_Status() : int;
import function WO_FieldCount() : int;
import function WO_SenderName() : name;
import function WO_NameAt(index : int) : name;
import function WO_Tick() : int;
import function WO_IsPlayerPaused(playerId : int) : bool;
import function WO_SetPaused(paused : bool);
import function WO_ResetDeltas();
import function WO_PartyJoin(target : string);
import function WO_PartyLeave();
import function WO_PartyRespond(target : string, approved : bool);
import function WO_PartyCoopMode(enabled : bool);
import function WO_ToName(text : string) : name;
import function WO_SaveSend(filePath : string) : bool;
import function WO_SaveWant();
import function WO_SaveDirectory() : string;
import function WO_CoopMarkRestore();
import function WO_CoopTakeRestore() : bool;
import function WO_CoopStashChar(body : string) : bool;
import function WO_CoopFetchChar() : string;
import function WO_SaveNeededBy() : string;
import function WO_SaveIncomingDir(directory : string);
import function WO_SaveState() : int;
import function WO_SaveProgress() : int;
import function WO_SaveError() : string;
import function WO_SaveFile() : string;
import function WO_SaveReset();
import function WO_SceneStart(scenePath : string, actorTag : string, voiceTag : string,
    actorX : float, actorY : float, actorZ : float,
    sceneX : float, sceneY : float, sceneZ : float,
    entryX : float, entryY : float, entryZ : float,
    entryHeading : float, area : int, kind : int,
    choicePhase : int, choiceSignature : string, choiceCount : int);
import function WO_QuestItem(kind : int, subject : string, quantity : int);
import function WO_SavePurge(marker : string) : int;
import function WO_SaveLeftovers(marker : string) : int;
import function WO_CharStore(slot : string, body : string) : bool;
import function WO_CharFetch(slot : string) : string;
import function WO_EntityProbe(target : CEntity) : string;
import function WO_EntityTemplatePath(target : CEntity) : string;
import function WO_NpcBeginOwned();
import function WO_NpcPushOwned(guid : int, area : int, localCount : int, typeCode : string, appearance : string,
    x : float, y : float, z : float, heading : float,
    hpPermille : int, flags : int, targetPlayerId : int, terminalState : int, terminalAttackerId : int) : bool;
import function WO_NpcEndOwned();
import function WO_NpcPull() : int;
import function WO_NpcId(index : int) : int;
import function WO_NpcOp(index : int) : int;
import function WO_NpcGuid(index : int) : int;
import function WO_NpcX(index : int) : float;
import function WO_NpcY(index : int) : float;
import function WO_NpcZ(index : int) : float;
import function WO_NpcHeading(index : int) : float;
import function WO_NpcSpeed(index : int) : float;
import function WO_NpcHp(index : int) : int;
import function WO_NpcFlags(index : int) : int;
import function WO_NpcTarget(index : int) : int;
import function WO_NpcTerminalState(index : int) : int;
import function WO_NpcTerminalRevision(index : int) : int;
import function WO_NpcTerminalAttacker(index : int) : int;
import function WO_NpcType(index : int) : string;
import function WO_NpcAppearance(index : int) : name;
import function WO_NpcBind(index : int, localGuid : int);
import function WO_NpcBindId(canonicalId : int, localGuid : int);
import function WO_NpcForget(canonicalId : int);
import function WO_NpcTake(canonicalId : int, localGuid : int);
import function WO_NpcUnspawnable(canonicalId : int);
import function WO_NpcSuspend(suspended : bool);
import function WO_NpcDropCount() : int;
import function WO_NpcDropGuid(index : int) : int;
import function WO_NpcDropsDone();
import function WO_NpcKillCount() : int;
import function WO_NpcKillGuid(index : int) : int;
import function WO_NpcKillAttacker(index : int) : int;
import function WO_NpcKillsDone();
import function WO_NpcReportHit(canonicalId : int, permille : int) : int;
import function WO_NpcHitCount() : int;
import function WO_NpcHitGuid(index : int) : int;
import function WO_NpcHitAttacker(index : int) : int;
import function WO_NpcHitPermille(index : int) : int;
import function WO_NpcHitAck(index : int, resultPermille : int, accepted : bool);
import function WO_NpcHitsDone();
import function WO_NpcAckCount() : int;
import function WO_NpcAckId(index : int) : int;
import function WO_NpcAckHp(index : int) : int;
import function WO_NpcAckOk(index : int) : bool;
import function WO_NpcAcksDone();
import function WO_NpcReport() : string;
import function WO_NpcLatency() : int;
import function WO_NpcTune(interpDelayMs : int, snapshotHz : int);
import function WO_NpcSyncMode(mode : int);
import function WO_NpcScale(npcId : int) : int;
import function WO_NpcScaleGen() : int;

function WO_ApplyMovement()
{
    theGame.r_getMultiplayerClient().updatePlayerMovement(
        WO_PlayerId(),
        WO_SenderName(),
        WO_Sequence(),
        WO_Float(0), WO_Float(1), WO_Float(2), WO_Float(3),
        WO_Float(4), WO_Float(5), WO_Int(6));
}

function WO_ApplyPose()
{
    theGame.r_getMultiplayerClient().updatePlayerPose(
        WO_PlayerId(),
        WO_SenderName(),
        WO_Sequence(),
        WO_Float(0), WO_Float(1), WO_Float(2), WO_Float(3),
        WO_Float(4), WO_Float(5), WO_Int(6),
        WO_Float(7));
}

function WO_ApplyUpdate1()
{
    theGame.r_getMultiplayerClient().updatePlayerData(
        WO_PlayerId(),
        WO_SenderName(),
        WO_Sequence(),
        WO_Float(0), WO_Float(1), WO_Float(2), WO_Float(3),
        WO_Float(4), WO_Float(5), WO_Int(6),
        WO_Bool(7), WO_Str(8), WO_Str(9), WO_Bool(10), WO_Bool(11),
        WO_NameAt(12), WO_Float(13),
        (EJumpType)WO_Int(14), (EClimbHeightType)WO_Int(15),
        WO_Bool(16), WO_Bool(17),
        WO_Float(18), WO_Float(19), WO_Float(20), WO_Float(21), WO_Bool(22),
        WO_Float(23), WO_Float(24), WO_Float(25), WO_Bool(26),
        (ESignType)WO_Int(27), WO_Float(28), WO_Bool(29), WO_Bool(30),
        WO_Float(31), WO_Bool(32), WO_Bool(33), WO_NameAt(34), WO_Bool(35), WO_Bool(36),
        WO_Int(37), WO_Float(38), WO_Float(39), WO_Str(40), WO_NameAt(41),
        WO_Float(42), WO_Float(43), WO_Bool(44), WO_Bool(45),
        WO_Bool(46), WO_Str(47), WO_Float(48),
        (EPlayerExplorationAction)WO_Int(49),
        WO_NameAt(50), WO_NameAt(51), WO_NameAt(52), WO_NameAt(53), WO_NameAt(54), WO_NameAt(55),
        WO_NameAt(56), WO_NameAt(57), WO_NameAt(58), WO_NameAt(59), WO_NameAt(60), WO_NameAt(61),
        WO_Bool(62), WO_Int(63), WO_Str(64), WO_NameAt(65), WO_Int(66), WO_Int(67), WO_Str(68),
        WO_Bool(69), WO_NameAt(70), WO_NameAt(71), WO_Float(72),
        (ESignType)WO_Int(73), WO_Bool(74), WO_Float(75), WO_Int(76), WO_Int(77),
        WO_Int(78), WO_Float(79), WO_Int(80));
}

function WO_ApplyUpdate2()
{
    theGame.r_getMultiplayerClient().updatePlayerData2(
        WO_PlayerId(),
        WO_SenderName(),
        (ENR_PlayerType)WO_Int(0),
        WO_NameAt(1), WO_Str(2), WO_Str(3), WO_Str(4), WO_Str(5), WO_Str(6),
        WO_Str(7), WO_Str(8), WO_Str(9), WO_Str(10),
        WO_Str(11), WO_Str(12), WO_Str(13), WO_Str(14), WO_Str(15),
        WO_Str(16), WO_Str(17), WO_Str(18), WO_Str(19), WO_Str(20));
}

function WO_ApplyUpdate3()
{
    var gwentData : string;
    var count : int;
    var i : int;

    count = WO_FieldCount();
    gwentData = "";

    for(i = 6; i < count; i += 1)
    {
        if(i > 6)
        {
            gwentData += " ";
        }

        gwentData += WO_Str(i);
    }

    theGame.r_getMultiplayerClient().updatePlayerData3(
        WO_PlayerId(),
        WO_SenderName(),
        WO_Str(0),
        (E_GwentRequest)WO_Int(1),
        WO_Int(2), WO_Int(3),
        WO_Str(4), WO_Float(5),
        gwentData);
}

function WO_ApplyUpdate4()
{
    theGame.r_getMultiplayerClient().updatePlayerData4(
        WO_PlayerId(),
        WO_SenderName(),
        WO_Bool(0), WO_Str(1), WO_NameAt(2),
        WO_Int(3), WO_Int(4), WO_Int(5), WO_Int(6),
        WO_Int(7), WO_Int(8), WO_Str(9), WO_Bool(10),
        WO_Int(11), WO_Int(12), WO_Int(13), WO_Int(14),
        WO_Float(15), WO_Int(16));
}

function WO_PumpInbound(maxMessages : int)
{
    var processed : int;
    var opcode : int;

    for(processed = 0; processed < maxMessages; processed += 1)
    {
        if(WO_Poll() < 0)
        {
            return;
        }

        opcode = WO_Opcode();

        switch(opcode)
        {
            case 1:
                WO_ApplyMovement();
                break;

            case 2:
                WO_ApplyUpdate1();
                break;

            case 3:
                WO_ApplyUpdate2();
                break;

            case 4:
                WO_ApplyUpdate3();
                break;

            case 5:
                WO_ApplyUpdate4();
                break;

            case 6:
                WO_ApplyPose();
                break;

            case 12:
                WO_ApplyVisibility();
                break;

            case 13:
                WO_ApplyParty();
                break;

            case 14:
                WO_ApplyPartyInvite();
                break;

            case 15:
                WO_ApplySceneStart();
                break;

            case 16:
                WO_ApplyQuestItem();
                break;



            default:
                break;
        }
    }
}


function WO_ApplyVisibility()
{
    var ids : array<int>;
    var scopeIds : array<int>;
    var count : int;
    var base : int;
    var i : int;

    count = WO_Int(0);

    for(i = 0; i < count; i += 1)
    {
        base = 1 + (i * 2);

        if(base + 1 >= WO_FieldCount())
        {
            break;
        }

        ids.PushBack(WO_Int(base));

        if(WO_Int(base + 1) == 1)
        {
            scopeIds.PushBack(WO_Int(base));
        }
    }

    theGame.r_getMultiplayerClient().applyVisibility(ids, scopeIds);
}

function WO_ApplyPartyInvite()
{
    theGame.r_getMultiplayerClient().onPartyInvite(WO_Str(0), WO_Str(1), WO_Str(2));
}

function WO_ApplyQuestItem()
{
    if(WO_FieldCount() < 4)
    {
        return;
    }

    theGame.r_getMultiplayerClient().onRemoteQuestItem(WO_Str(0), WO_Int(1), WO_Str(2), WO_Int(3));
}

function WO_ApplySceneStart()
{
    if(WO_FieldCount() < 19)
    {
        return;
    }

    theGame.r_getMultiplayerClient().onRemoteSceneStart(
        WO_Str(0), WO_Str(1), WO_Str(2), WO_Str(3),
        WO_Float(4), WO_Float(5), WO_Float(6),
        WO_Float(7), WO_Float(8), WO_Float(9),
        WO_Float(10), WO_Float(11), WO_Float(12),
        WO_Float(13), WO_Int(14), WO_Int(15),
        WO_Int(16), WO_Str(17), WO_Int(18));
}

function WO_ApplyParty()
{
    var names : array<string>;
    var partyId : int;
    var count : int;
    var i : int;

    partyId = WO_Int(0);
    count = WO_Int(1);

    for(i = 0; i < count; i += 1)
    {
        if((2 + (i * 2) + 1) >= WO_FieldCount())
        {
            break;
        }

        names.PushBack(WO_Str(2 + (i * 2) + 1));
    }

    theGame.r_getMultiplayerClient().applyPartyState(partyId, names);
}

function WO_PumpStatus()
{
    var client : r_MultiplayerClient;

    client = theGame.r_getMultiplayerClient();

    switch(WO_Status())
    {
        case 1:
            client.setUsernameTaken(WO_Username());
            break;

        case 2:
            client.setKicked();
            break;

        case 3:
            client.setBanned();
            break;

        case 4:
            client.setNotWhitelisted();
            break;

        default:
            break;
    }
}
