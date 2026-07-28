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
        WO_Bool(69), WO_NameAt(70), WO_NameAt(71), WO_Float(72));
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
        WO_Float(15));
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

            default:
                break;
        }
    }
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
