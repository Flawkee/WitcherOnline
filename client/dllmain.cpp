#include "pch.h"
#include "script.h"
#include <iostream>
#include "DebugExecClient.h"
#include <windows.h>
#include <thread>
#include <atomic>
#include <string>
#include <sstream>
#include <vector>
#include <regex>
#include <filesystem>
#include "pugixml\pugixml.hpp"
#include <unordered_set>
#include <unordered_map>
#define ASIO_STANDALONE
#include <asio.hpp>
namespace fs = std::filesystem;
using namespace w3mp;

static DebugExecClient g_client;
static std::thread g_poll;
static std::thread g_game;

static std::string username = "Player";
static std::atomic<int> g_localPlayerId{ 0 };
static std::string ip = "46.62.255.79";
static std::string port = "40000";

static HANDLE g_initThread = NULL;

asio::io_context io;
asio::ip::udp::resolver resolver(io);
asio::ip::udp::socket theSocket(io);
asio::ip::udp::endpoint serverEndpoint;

static std::atomic<bool> g_usernameTaken{ false };
static std::atomic<bool> g_banned{ false };
static std::atomic<bool> g_notWhitelisted{ false };
static std::atomic<bool> g_kicked{ false };
static std::atomic<bool> g_shutdown{ false };
static std::atomic<bool> g_run{ false };

struct ExecJob {
	std::string code, tag;
	int timeoutMs;
};

static std::mutex g_qMu;
static std::vector<ExecJob> g_jobs;

static int g_sequenceSeed = static_cast<int>(((GetTickCount64() / 20ULL) % 1000000000ULL) + 1ULL);
static int g_movementSequence = g_sequenceSeed;
static int g_update1Sequence = g_sequenceSeed;
static int g_update2Sequence = g_sequenceSeed;
static int g_update3Sequence = g_sequenceSeed;
static int g_update4Sequence = g_sequenceSeed;

fs::path getExecutablePath() {
	char buffer[MAX_PATH];
	GetModuleFileNameA(NULL, buffer, MAX_PATH);
	return fs::path(buffer).parent_path().parent_path();
}

struct ParsedHalves
{
	std::vector<std::string> first;
	std::vector<std::string> second;
};

static ParsedHalves ParseValuesSplitHalf(const std::string& input)
{
	static const std::string kStartMarker = "_s";
	static const std::string kEndMarker = "_e";
	static const std::string kHalfMarker = "half";

	std::istringstream iss(input);
	std::string word;

	ParsedHalves out;
	std::vector<std::string>* current = &out.first;

	if (!(iss >> word))
		return out;

	bool inBlock = false;
	std::string blockAccum;

	while (iss >> word)
	{
		if (!inBlock)
		{
			if (word == kStartMarker)
			{
				inBlock = true;
				blockAccum.clear();
				continue;
			}

			if (word == kHalfMarker)
			{
				current = &out.second;
				continue;
			}

			if (word == kEndMarker)
			{
				current->push_back(word);
				continue;
			}

			current->push_back(word);
		}
		else
		{
			if (word == kEndMarker)
			{
				current->push_back(blockAccum);
				inBlock = false;
				blockAccum.clear();
			}
			else
			{
				if (!blockAccum.empty())
					blockAccum += ' ';
				blockAccum += word;
			}
		}
	}

	if (inBlock && !blockAccum.empty())
	{
		current->push_back(blockAccum);
	}

	return out;
}

void PostExec(const std::string& code, const std::string& tag = "", int to = 300) {
	if (!g_run.load())
		return;

	std::lock_guard<std::mutex> lk(g_qMu);
	g_jobs.push_back({ code, tag, to });
}

static std::string EscapeField(const std::string& s)
{
	std::string out;
	out.reserve(s.size());

	for (char c : s)
	{
		if (c == '\\') out += "\\\\";
		else if (c == '\t') out += "\\t";
		else if (c == '\n') out += "\\n";
		else if (c == '\r') out += "\\r";
		else out += c;
	}

	return out;
}

static std::string BuildPacket(const std::string& opcode, const std::string& id, const std::vector<std::string>& fields)
{
	std::string packet = opcode + "\t" + id;

	for (const auto& f : fields)
	{
		packet += "\t";
		packet += EscapeField(f);
	}

	return packet;
}

static std::vector<std::string> SplitTabs(const std::string& s)
{
	std::vector<std::string> parts;
	std::string cur;
	bool esc = false;

	for (char c : s)
	{
		if (esc) {
			if (c == 't') cur += '\t';
			else if (c == 'n') cur += '\n';
			else if (c == 'r') cur += '\r';
			else if (c == '\\') cur += '\\';
			else cur += c;
			esc = false;
		}
		else if (c == '\\') {
			esc = true;
		}
		else if (c == '\t') {
			parts.push_back(cur);
			cur.clear();
		}
		else {
			cur += c;
		}
	}

	parts.push_back(cur);
	return parts;
}

static std::string EscapeExecQuoted(const std::string& s, char quote)
{
	std::string out;
	out.reserve(s.size() + 8);

	for (char c : s)
	{
		if (c == '\\')
			out += "\\\\";
		else if (c == quote)
		{
			out += '\\';
			out += c;
		}
		else if (c == '\n')
			out += "\\n";
		else if (c == '\r')
			out += "\\r";
		else if (c == '\t')
			out += "\\t";
		else
			out += c;
	}

	return out;
}

static void AppendExecField(std::string& code, const std::string& value)
{
	code += ", ";

	if (value.find_first_of(" \t\r\n") != std::string::npos)
	{
		code += "'";
		code += EscapeExecQuoted(value, '\'');
		code += "'";
	}
	else
	{
		code += value;
	}
}

static int NextSequence(int& value)
{
    if (value <= 0 || value >= 2000000000)
        value = 1;
    else
        value++;
    return value;
}

static bool ParsePositiveInt(const std::string& text, int& value)
{
    try
    {
        value = std::stoi(text);
        return value > 0;
    }
    catch (...)
    {
        value = 0;
        return false;
    }
}

static bool ExtractMovementPrefix(const ParsedHalves& halves, std::vector<std::string>& movement)
{
    if (halves.first.size() < 7)
        return false;

    movement.assign(halves.first.begin(), halves.first.begin() + 7);
    return true;
}

static bool RemoveMovementPrefix(ParsedHalves& halves, std::vector<std::string>& movement)
{
    if (!ExtractMovementPrefix(halves, movement))
        return false;

    halves.first.erase(halves.first.begin(), halves.first.begin() + 7);
    return true;
}

static void SendUdpPacket(const std::string& packet, const char* label)
{
    try
    {
        theSocket.send(asio::buffer(packet));
    }
    catch (const std::exception& e)
    {
        std::cout << "Send error (" << label << "): " << e.what() << "\n";
    }
}

static void SendMovementPacket(const std::string& packetId, const std::vector<std::string>& movement, int movementSequence)
{
    if (movement.size() < 7 || movementSequence <= 0)
        return;

    std::vector<std::string> fields;
    fields.reserve(8);
    fields.push_back(std::to_string(movementSequence));
    fields.insert(fields.end(), movement.begin(), movement.begin() + 7);
    SendUdpPacket(BuildPacket("MOVE", packetId, fields), "MOVE");
}

static void pushPlayer1(
    int playerId,
    const std::string& username,
    int movementSequence,
    const std::vector<std::string>& update1A,
    const std::vector<std::string>& update1B)
{
    if (playerId <= 0 || username.empty() || movementSequence <= 0)
        return;

    std::vector<std::string> fields;
    fields.reserve(update1A.size() + update1B.size());
    fields.insert(fields.end(), update1A.begin(), update1A.end());
    fields.insert(fields.end(), update1B.begin(), update1B.end());

    if (fields.empty())
        return;

    std::string playerIdStr = std::to_string(playerId);
    std::string code = "wo_update(" + playerIdStr + ", \"" + EscapeExecQuoted(username, '"') + "\", " + std::to_string(movementSequence);

    for (const auto& field : fields)
        AppendExecField(code, field);

    code += ")";
    g_client.ExecNoWaitLatest("wo1:" + playerIdStr, code);
}

static void pushPlayer2(
    int playerId,
    const std::string& username,
    const std::vector<std::string>& update2A,
    const std::vector<std::string>& update2B)
{
    if (playerId <= 0 || username.empty())
        return;

    std::vector<std::string> fields;
    fields.reserve(update2A.size() + update2B.size());
    fields.insert(fields.end(), update2A.begin(), update2A.end());
    fields.insert(fields.end(), update2B.begin(), update2B.end());

    if (fields.empty())
        return;

    std::string playerIdStr = std::to_string(playerId);
    std::string code = "wo_update2(" + playerIdStr + ", \"" + EscapeExecQuoted(username, '"') + "\"";

    for (const auto& field : fields)
        AppendExecField(code, field);

    code += ")";
    g_client.ExecNoWaitLatest("wo2:" + playerIdStr, code);
}

static void pushPlayerMovement(int playerId, const std::string& username, const std::vector<std::string>& movement)
{
    if (playerId <= 0 || username.empty() || movement.size() < 8)
        return;

    int movementSequence = 0;
    if (!ParsePositiveInt(movement[0], movementSequence))
        return;

    std::string playerIdStr = std::to_string(playerId);
    std::string code = "wo_move(" + playerIdStr + ", \"" + EscapeExecQuoted(username, '"') + "\", " + std::to_string(movementSequence);

    for (size_t i = 1; i < 8; ++i)
        AppendExecField(code, movement[i]);

    code += ")";
    g_client.ExecPriorityLatest("move:" + playerIdStr, code);
}

static void pushPlayer3(int playerId, const std::string& username, const std::vector<std::string>& update3)
{
	if (playerId <= 0 || username.empty() || update3.size() < 6)
		return;

	const std::string& outgoingGwentTo = update3[0];
	const std::string& outgoingGwentRequest = update3[1];
	const std::string& outgoingGwentBet = update3[2];
	const std::string& outgoingGwentSeed = update3[3];
	const std::string& lastGwentAction = update3[4];
	const std::string& lastGwentActionTime = update3[5];

	std::string gwentData;
	for (size_t i = 6; i < update3.size(); ++i)
	{
		if (!gwentData.empty())
			gwentData += " ";

		gwentData += update3[i];
	}

	std::string playerIdStr = std::to_string(playerId);

	std::string code3 = "wo_update3(";
	code3 += playerIdStr;

	code3 += ", \"";
	code3 += EscapeExecQuoted(username, '"');
	code3 += "\"";

	code3 += ", \"";
	code3 += EscapeExecQuoted(outgoingGwentTo, '"');
	code3 += "\"";

	code3 += ", ";
	code3 += outgoingGwentRequest;

	code3 += ", ";
	code3 += outgoingGwentBet;

	code3 += ", ";
	code3 += outgoingGwentSeed;

	code3 += ", \"";
	code3 += EscapeExecQuoted(lastGwentAction, '"');
	code3 += "\"";

	code3 += ", ";
	code3 += lastGwentActionTime;

	code3 += ", \"";
	code3 += EscapeExecQuoted(gwentData, '"');
	code3 += "\")";

	g_client.ExecNoWaitLatest("wo3:" + playerIdStr, code3);
}

static void pushPlayer4(int playerId, const std::string& username, const std::vector<std::string>& update4)
{
	if (playerId <= 0 || username.empty() || update4.size() < 16)
		return;

	const std::string& inParty = update4[0];
	const std::string& joinedParty = update4[1];
	const std::string& weather = update4[2];
	const std::string& day = update4[3];
	const std::string& hour = update4[4];
	const std::string& minute = update4[5];
	const std::string& second = update4[6];
	const std::string& lastDialogIndex = update4[7];
	const std::string& lastDialogCount = update4[8];
	const std::string& dialogChoices = update4[9];
	const std::string& dialogChoicesActive = update4[10];
	const std::string& armorDye = update4[11];
	const std::string& gloveDye = update4[12];
	const std::string& pantDye = update4[13];
	const std::string& bootDye = update4[14];
	const std::string& health = update4[15];

	std::string playerIdStr = std::to_string(playerId);

	std::string code4 = "wo_update4(";
	code4 += playerIdStr;

	code4 += ", \"";
	code4 += EscapeExecQuoted(username, '"');
	code4 += "\"";

	code4 += ", ";
	code4 += inParty;

	code4 += ", \"";
	code4 += EscapeExecQuoted(joinedParty, '"');
	code4 += "\"";

	code4 += ", \"";
	code4 += EscapeExecQuoted(weather, '"');
	code4 += "\"";

	code4 += ", ";
	code4 += day;

	code4 += ", ";
	code4 += hour;

	code4 += ", ";
	code4 += minute;

	code4 += ", ";
	code4 += second;

	code4 += ", ";
	code4 += lastDialogIndex;

	code4 += ", ";
	code4 += lastDialogCount;

	code4 += ", \"";
	code4 += EscapeExecQuoted(dialogChoices, '"');
	code4 += "\"";

	code4 += ", ";
	code4 += dialogChoicesActive;

	code4 += ", ";
	code4 += armorDye;

	code4 += ", ";
	code4 += gloveDye;

	code4 += ", ";
	code4 += pantDye;

	code4 += ", ";
	code4 += bootDye;

	code4 += ", ";
	code4 += health;

	code4 += ")";

	g_client.ExecNoWaitLatest("wo4:" + playerIdStr, code4);
}

static void CloseOnlineSession()
{
	try
	{
		if (theSocket.is_open())
			theSocket.close();
	}
	catch (...)
	{
	}
}

struct RemotePlayerChunks
{
    std::string username;

    std::vector<std::string> update1A;
    std::vector<std::string> update1B;
    std::vector<std::string> update2A;
    std::vector<std::string> update2B;

    int update1ASequence = 0;
    int update1BSequence = 0;
    int update1AMovementSequence = 0;
    int update1BMovementSequence = 0;
    int update2ASequence = 0;
    int update2BSequence = 0;
    int lastPushed1Sequence = 0;
    int lastPushed2Sequence = 0;
    int lastPushed3Sequence = 0;
    int lastPushed4Sequence = 0;
};

std::mutex remoteMu;
std::unordered_map<int, RemotePlayerChunks> remotePlayers;

static void HandleServerPacket(const std::string& msg)
{
    auto parts = SplitTabs(msg);
    if (parts.empty())
        return;

    if (parts[0] == "ERROR")
    {
        if (parts.size() >= 2 && parts[1] == "USERNAME_TAKEN")
        {
            g_usernameTaken.store(true);
            CloseOnlineSession();
        }
        else if (parts.size() >= 2 && parts[1] == "BANNED")
        {
            g_banned.store(true);
            CloseOnlineSession();
        }
        else if (parts.size() >= 2 && parts[1] == "NOT_WHITELISTED")
        {
            g_notWhitelisted.store(true);
            CloseOnlineSession();
        }
        return;
    }

    if (parts[0] == "KICK")
    {
        g_kicked.store(true);
        CloseOnlineSession();
        return;
    }

    const std::string& opcode = parts[0];
    if (opcode != "MOVE" && opcode != "UPDATE1A" && opcode != "UPDATE1B" && opcode != "UPDATE2A" && opcode != "UPDATE2B" && opcode != "UPDATE3" && opcode != "UPDATE4")
        return;

    if (parts.size() < 3)
        return;

    int playerId = 0;
    if (!ParsePositiveInt(parts[1], playerId))
        return;

    std::string playerUsername = parts[2];
    if (playerUsername.empty())
        return;

    if (playerUsername == ::username)
        g_localPlayerId.store(playerId);

    std::vector<std::string> fields(parts.begin() + 3, parts.end());

    if (opcode == "MOVE")
    {
        pushPlayerMovement(playerId, playerUsername, fields);
        return;
    }

    if (fields.empty())
        return;

    int packetSequence = 0;
    if (!ParsePositiveInt(fields[0], packetSequence))
        return;
    fields.erase(fields.begin());

    if (opcode == "UPDATE3" || opcode == "UPDATE4")
    {
        bool shouldPush = false;
        {
            std::lock_guard<std::mutex> lk(remoteMu);
            auto& rp = remotePlayers[playerId];
            rp.username = playerUsername;

            if (opcode == "UPDATE3" && packetSequence > rp.lastPushed3Sequence)
            {
                rp.lastPushed3Sequence = packetSequence;
                shouldPush = true;
            }
            else if (opcode == "UPDATE4" && packetSequence > rp.lastPushed4Sequence)
            {
                rp.lastPushed4Sequence = packetSequence;
                shouldPush = true;
            }
        }

        if (!shouldPush)
            return;

        if (opcode == "UPDATE3")
            pushPlayer3(playerId, playerUsername, fields);
        else
            pushPlayer4(playerId, playerUsername, fields);
        return;
    }

    int movementSequence = 0;
    if (opcode == "UPDATE1A" || opcode == "UPDATE1B")
    {
        if (fields.empty() || !ParsePositiveInt(fields[0], movementSequence))
            return;
        fields.erase(fields.begin());
    }

    bool push1 = false;
    bool push2 = false;
    int pushMovementSequence = 0;
    std::vector<std::string> u1a;
    std::vector<std::string> u1b;
    std::vector<std::string> u2a;
    std::vector<std::string> u2b;
    std::string pushUsername;

    {
        std::lock_guard<std::mutex> lk(remoteMu);
        auto& rp = remotePlayers[playerId];
        rp.username = playerUsername;

        if (opcode == "UPDATE1A" && packetSequence > rp.lastPushed1Sequence)
        {
            rp.update1A = std::move(fields);
            rp.update1ASequence = packetSequence;
            rp.update1AMovementSequence = movementSequence;
        }
        else if (opcode == "UPDATE1B" && packetSequence > rp.lastPushed1Sequence)
        {
            rp.update1B = std::move(fields);
            rp.update1BSequence = packetSequence;
            rp.update1BMovementSequence = movementSequence;
        }
        else if (opcode == "UPDATE2A" && packetSequence > rp.lastPushed2Sequence)
        {
            rp.update2A = std::move(fields);
            rp.update2ASequence = packetSequence;
        }
        else if (opcode == "UPDATE2B" && packetSequence > rp.lastPushed2Sequence)
        {
            rp.update2B = std::move(fields);
            rp.update2BSequence = packetSequence;
        }

        if (rp.update1ASequence > rp.lastPushed1Sequence && rp.update1ASequence == rp.update1BSequence && rp.update1AMovementSequence == rp.update1BMovementSequence)
        {
            rp.lastPushed1Sequence = rp.update1ASequence;
            u1a = rp.update1A;
            u1b = rp.update1B;
            pushMovementSequence = rp.update1AMovementSequence;
            pushUsername = rp.username;
            push1 = true;
        }

        if (rp.update2ASequence > rp.lastPushed2Sequence && rp.update2ASequence == rp.update2BSequence)
        {
            rp.lastPushed2Sequence = rp.update2ASequence;
            u2a = rp.update2A;
            u2b = rp.update2B;
            pushUsername = rp.username;
            push2 = true;
        }
    }

    if (push1)
        pushPlayer1(playerId, pushUsername, pushMovementSequence, u1a, u1b);
    if (push2)
        pushPlayer2(playerId, pushUsername, u2a, u2b);
}

static std::string BuildLocalPacketId(int localPlayerId)
{
    if (localPlayerId > 0)
        return std::to_string(localPlayerId) + "\t" + EscapeField(username);
    return EscapeField(username);
}

static bool PollUpdate1(int localPlayerId, const std::string& packetId)
{
    std::string out;
    bool ok = g_client.ExecTagged("wo_get(" + std::to_string(localPlayerId) + ", \"" + EscapeExecQuoted(username, '"') + "\")", "wo", out, 500);
    if (!ok)
        return false;

    ParsedHalves halves = ParseValuesSplitHalf(out);
    std::vector<std::string> movement;
    if (!ExtractMovementPrefix(halves, movement))
        return false;

    int movementSequence = NextSequence(g_movementSequence);
    int updateSequence = NextSequence(g_update1Sequence);
    SendMovementPacket(packetId, movement, movementSequence);

    std::vector<std::string> first = halves.first;
    std::vector<std::string> second = halves.second;
    first.insert(first.begin(), std::to_string(movementSequence));
    first.insert(first.begin(), std::to_string(updateSequence));
    second.insert(second.begin(), std::to_string(movementSequence));
    second.insert(second.begin(), std::to_string(updateSequence));

    if (!halves.first.empty())
        SendUdpPacket(BuildPacket("UPDATE1A", packetId, first), "UPDATE1A");
    if (!halves.second.empty())
        SendUdpPacket(BuildPacket("UPDATE1B", packetId, second), "UPDATE1B");
    return true;
}

static bool PollUpdate2(int localPlayerId, const std::string& packetId)
{
    std::string out;
    bool ok = g_client.ExecTagged("wo_get2(" + std::to_string(localPlayerId) + ", \"" + EscapeExecQuoted(username, '"') + "\")", "wo2", out, 500);
    if (!ok)
        return false;

    ParsedHalves halves = ParseValuesSplitHalf(out);
    std::vector<std::string> movement;
    if (!RemoveMovementPrefix(halves, movement))
        return false;

    int movementSequence = NextSequence(g_movementSequence);
    int updateSequence = NextSequence(g_update2Sequence);
    SendMovementPacket(packetId, movement, movementSequence);

    std::vector<std::string> first = halves.first;
    std::vector<std::string> second = halves.second;
    first.insert(first.begin(), std::to_string(updateSequence));
    second.insert(second.begin(), std::to_string(updateSequence));

    if (!halves.first.empty())
        SendUdpPacket(BuildPacket("UPDATE2A", packetId, first), "UPDATE2A");
    if (!halves.second.empty())
        SendUdpPacket(BuildPacket("UPDATE2B", packetId, second), "UPDATE2B");
    return true;
}

static bool PollUpdate3(int localPlayerId, const std::string& packetId)
{
    std::string out;
    bool ok = g_client.ExecTagged("wo_get3(" + std::to_string(localPlayerId) + ", \"" + EscapeExecQuoted(username, '"') + "\")", "wo3", out, 500);
    if (!ok)
        return false;

    ParsedHalves halves = ParseValuesSplitHalf(out);
    std::vector<std::string> movement;
    if (!RemoveMovementPrefix(halves, movement))
        return false;

    int movementSequence = NextSequence(g_movementSequence);
    int updateSequence = NextSequence(g_update3Sequence);
    SendMovementPacket(packetId, movement, movementSequence);

    std::vector<std::string> fields;
    fields.reserve(halves.first.size() + halves.second.size() + 1);
    fields.push_back(std::to_string(updateSequence));
    fields.insert(fields.end(), halves.first.begin(), halves.first.end());
    fields.insert(fields.end(), halves.second.begin(), halves.second.end());

    if (fields.size() > 1)
        SendUdpPacket(BuildPacket("UPDATE3", packetId, fields), "UPDATE3");
    return true;
}

static bool PollUpdate4(int localPlayerId, const std::string& packetId)
{
    std::string out;
    bool ok = g_client.ExecTagged("wo_get4(" + std::to_string(localPlayerId) + ", \"" + EscapeExecQuoted(username, '"') + "\")", "wo4", out, 500);
    if (!ok)
        return false;

    ParsedHalves halves = ParseValuesSplitHalf(out);
    std::vector<std::string> movement;
    if (!RemoveMovementPrefix(halves, movement))
        return false;

    int movementSequence = NextSequence(g_movementSequence);
    int updateSequence = NextSequence(g_update4Sequence);
    SendMovementPacket(packetId, movement, movementSequence);

    std::vector<std::string> fields;
    fields.reserve(halves.first.size() + halves.second.size() + 1);
    fields.push_back(std::to_string(updateSequence));
    fields.insert(fields.end(), halves.first.begin(), halves.first.end());
    fields.insert(fields.end(), halves.second.begin(), halves.second.end());

    if (fields.size() > 1)
        SendUdpPacket(BuildPacket("UPDATE4", packetId, fields), "UPDATE4");
    return true;
}

static void PollPoseThread()
{
    Sleep(500);
    static const int schedule[] = { 1, 4, 1, 3, 1, 4, 1, 2, 1, 3, 1, 4 };
    size_t scheduleIndex = 0;

    while (g_run.load())
    {
        try
        {
            std::vector<ExecJob> jobs;
            {
                std::lock_guard<std::mutex> lk(g_qMu);
                jobs.swap(g_jobs);
            }

            for (auto& job : jobs)
            {
                if (!g_client.IsConnected())
                    break;
                std::string out;
                g_client.ExecTagged(job.code, job.tag, out, job.timeoutMs);
            }

            if (g_client.IsConnected() && g_usernameTaken.load())
            {
                PostExec("usernameTaken(\"" + username + "\")", "", 150);
                Sleep(250);
                continue;
            }
            if (g_client.IsConnected() && g_kicked.load())
            {
                PostExec("kickedMsg()", "", 150);
                Sleep(250);
                continue;
            }
            if (g_client.IsConnected() && g_banned.load())
            {
                PostExec("bannedMsg()", "", 150);
                Sleep(250);
                continue;
            }
            if (g_client.IsConnected() && g_notWhitelisted.load())
            {
                PostExec("notWhitelistedMsg()", "", 150);
                Sleep(250);
                continue;
            }

            if (!g_client.IsConnected())
            {
                Sleep(100);
                continue;
            }

            int localPlayerId = g_localPlayerId.load();
            std::string packetId = BuildLocalPacketId(localPlayerId);
            int pollType = schedule[scheduleIndex % (sizeof(schedule) / sizeof(schedule[0]))];
            scheduleIndex++;

            bool ok = false;
            if (pollType == 1)
                ok = PollUpdate1(localPlayerId, packetId);
            else if (pollType == 2)
                ok = PollUpdate2(localPlayerId, packetId);
            else if (pollType == 3)
                ok = PollUpdate3(localPlayerId, packetId);
            else
                ok = PollUpdate4(localPlayerId, packetId);

            if (!ok)
                Sleep(10);
        }
        catch (const std::exception& e)
        {
            std::cout << "caught exception in poll loop: " << e.what() << std::endl;
            Sleep(10);
        }
        catch (...)
        {
            std::cout << "caught exception in poll loop" << std::endl;
            Sleep(10);
        }
    }
}

static void SendToGameThread()
{
	Sleep(1000);
	std::vector<char> data(8192);

	while (g_run.load())
	{
		try
		{
			asio::ip::udp::endpoint senderEndpoint;

			std::size_t len = theSocket.receive_from(
				asio::buffer(data),
				senderEndpoint
			);

			std::string msg(data.data(), len);
			HandleServerPacket(msg);
			//std::cout << "Receive packet: " << msg << "\n";
		}
		catch (const std::exception& e) {
			std::cout << "Receive error: " << e.what() << "\n";
			Sleep(500);
		}
	}
}

void connectServer()
{
	if (g_shutdown.load())
		return;

	g_client.Start();

	g_run.store(true);

	theSocket.open(asio::ip::udp::v4());
	theSocket.bind(asio::ip::udp::endpoint(asio::ip::udp::v4(), 0));
	serverEndpoint = *resolver.resolve(asio::ip::udp::v4(), ip, port).begin();
	theSocket.connect(serverEndpoint);

	g_poll = std::thread(PollPoseThread);
	g_game = std::thread(SendToGameThread);
}

void initScript()
{
	fs::path baseDir = getExecutablePath();
	fs::path fullPath = baseDir / "WitcherOnline" / "config.xml";

	pugi::xml_document doc;
	pugi::xml_parse_result result = doc.load_file(fullPath.c_str());

	if (result)
	{
		pugi::xml_node xml = doc.child("Config");

		if (xml)
		{
			std::string user = xml.child("Username").text().as_string();

			username = std::regex_replace(user, std::regex("[^A-Za-z0-9_]"), "");

			if (username.length() > 16)
			{
				username.resize(16);
			}

			ip = xml.child("ServerIP").text().as_string();
			port = xml.child("Port").text().as_string();

			if (username.length() < 2 || username == "none")
			{
				username = "Player";
			}
		}
	}

	connectServer();
}

static DWORD WINAPI InitThreadProc(LPVOID)
{
	if (g_shutdown.load())
		return 0;

	//activateConsole();

	if (g_shutdown.load())
		return 0;

	initScript();
	return 0;
}

BOOL APIENTRY DllMain(HMODULE hModule, DWORD reason, LPVOID) {
	switch (reason) {
	case DLL_PROCESS_ATTACH:
		DisableThreadLibraryCalls(hModule);
		g_initThread = CreateThread(nullptr, 0, InitThreadProc, nullptr, 0, nullptr);
		if (g_initThread) {
			CloseHandle(g_initThread);
			g_initThread = NULL;
		}
		break;
	case DLL_PROCESS_DETACH:
	{
		g_shutdown.store(true);
		g_run.store(false);
		theSocket.close();
		g_client.Stop();

		{
			std::lock_guard<std::mutex> lk(g_qMu);
			g_jobs.clear();
		}

		if (g_poll.joinable())
			g_poll.join();

		if (g_game.joinable())
			g_game.join();

		//FreeConsole();
		break;
	}
	}
	return TRUE;
}