#include "pch.h"
#include <iostream>
#include "Diagnostics.h"
#include "ScriptBinding.h"
#include "NpcNet.h"
#include "PacketCodec.h"
#include "SaveTransfer.h"
#include <windows.h>
#include <thread>
#include <atomic>
#include <string>
#include <string_view>
#include <vector>
#include <deque>
#include <array>
#include <algorithm>
#include <cctype>
#include <regex>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <shlobj.h>
#include "pugixml\pugixml.hpp"
#include <unordered_map>
#define ASIO_STANDALONE
#include <asio.hpp>
namespace fs = std::filesystem;
using namespace w3mp;

static std::thread g_sender;
static std::thread g_receiver;
static std::thread g_tcpReceiver;

static std::string username = "Player";
static std::atomic<int> g_localPlayerId{ 0 };
static std::string ip = "46.62.255.79";
static std::string port = "40000";

static HANDLE g_initThread = NULL;

asio::io_context io;
asio::ip::udp::resolver resolver(io);
asio::ip::udp::socket theSocket(io);
asio::ip::udp::endpoint serverEndpoint;
asio::ip::tcp::resolver tcpResolver(io);
asio::ip::tcp::socket tcpSocket(io);

static std::mutex g_tcpSendMutex;
static std::mutex g_transportMutex;
static std::atomic<bool> g_udpAvailable{ false };
static std::atomic<bool> g_tcpAvailable{ false };
static std::atomic<bool> g_tcpConnecting{ false };
static std::atomic<unsigned long long> g_lastUdpPongMs{ 0 };
static std::atomic<unsigned long long> g_transportOpenedMs{ 0 };
static std::mutex g_reliableBacklogMutex;
static std::deque<std::pair<std::string, std::string>> g_reliableBacklog;
static constexpr size_t kReliableBacklogLimit = 4096;

static std::atomic<bool> g_shutdown{ false };
static std::atomic<bool> g_run{ false };

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

static bool NextToken(const std::string& input, size_t& pos, std::string_view& token)
{
	while (pos < input.size() && static_cast<unsigned char>(input[pos]) <= ' ')
		++pos;

	if (pos >= input.size())
		return false;

	const size_t start = pos;

	while (pos < input.size() && static_cast<unsigned char>(input[pos]) > ' ')
		++pos;

	token = std::string_view(input.data() + start, pos - start);
	return true;
}

static ParsedHalves ParseValuesSplitHalf(const std::string& input)
{
	static const std::string_view kStartMarker = "_s";
	static const std::string_view kEndMarker = "_e";
	static const std::string_view kHalfMarker = "half";

	ParsedHalves out;
	out.first.reserve(64);
	out.second.reserve(32);

	std::vector<std::string>* current = &out.first;

	size_t pos = 0;
	std::string_view token;

	if (!NextToken(input, pos, token))
		return out;

	bool inBlock = false;
	std::string blockAccum;

	while (NextToken(input, pos, token))
	{
		if (!inBlock)
		{
			if (token == kStartMarker)
			{
				inBlock = true;
				blockAccum.clear();
				continue;
			}

			if (token == kHalfMarker)
			{
				current = &out.second;
				continue;
			}

			current->emplace_back(token);
		}
		else
		{
			if (token == kEndMarker)
			{
				current->push_back(blockAccum);
				inBlock = false;
				blockAccum.clear();
			}
			else
			{
				if (!blockAccum.empty())
					blockAccum += ' ';
				blockAccum.append(token);
			}
		}
	}

	if (inBlock && !blockAccum.empty())
		current->push_back(blockAccum);

	return out;
}

static std::string PayloadTag(const std::string& input)
{
	size_t pos = 0;
	std::string_view token;

	if (!NextToken(input, pos, token))
		return std::string();

	return std::string(token);
}

static void AppendEscaped(std::string& out, const std::string& value)
{
	if (value.find_first_of("\\\t\n\r") == std::string::npos)
	{
		out.append(value);
		return;
	}

	for (char c : value)
	{
		switch (c)
		{
		case '\\': out += "\\\\"; break;
		case '\t': out += "\\t"; break;
		case '\n': out += "\\n"; break;
		case '\r': out += "\\r"; break;
		default: out += c; break;
		}
	}
}

static std::string EscapeField(const std::string& s)
{
	std::string out;
	out.reserve(s.size());
	AppendEscaped(out, s);
	return out;
}

static std::string BuildPacket(const std::string& opcode, const std::string& id, const std::vector<std::string>& fields)
{
	size_t estimate = opcode.size() + id.size() + 2;

	for (const auto& f : fields)
		estimate += f.size() + 1;

	std::string packet;
	packet.reserve(estimate + 16);

	packet += opcode;
	packet += '\t';
	packet += id;

	for (const auto& f : fields)
	{
		packet += '\t';
		AppendEscaped(packet, f);
	}

	return packet;
}

static std::vector<std::string> SplitTabs(const std::string& s)
{
	std::vector<std::string> parts;
	std::string cur;
	bool esc = false;

	parts.reserve(32);
	cur.reserve(24);

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
			parts.push_back(std::move(cur));
			cur = std::string();
			cur.reserve(24);
		}
		else {
			cur += c;
		}
	}

	parts.push_back(std::move(cur));
	return parts;
}

static bool IsIntegerLiteral(const std::string& value)
{
	if (value.empty() || value.size() > 20)
		return false;

	size_t index = (value[0] == '-' || value[0] == '+') ? 1 : 0;
	if (index >= value.size())
		return false;

	for (; index < value.size(); ++index)
	{
		if (value[index] < '0' || value[index] > '9')
			return false;
	}

	return true;
}

static bool IsValidUsername(const std::string& value)
{
	if (value.size() < 2 || value.size() > 16)
		return false;

	for (char c : value)
	{
		const bool allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
		if (!allowed)
			return false;
	}

	return true;
}

static bool ParsePositiveInt(const std::string& text, int& value)
{
	value = 0;

	if (!IsIntegerLiteral(text))
		return false;

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

static const int kSequenceSpan = 2000000000;

static bool IsSequenceNewer(int candidate, int last)
{
	if (last <= 0)
		return candidate > 0;

	const int delta = candidate - last;
	if (delta > 0)
		return delta < (kSequenceSpan / 2);

	return delta < -(kSequenceSpan / 2);
}

static int NextSequence(int& value)
{
	if (value <= 0 || value >= kSequenceSpan)
		value = 1;
	else
		value++;
	return value;
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

static void RequestReconnect(const char* reason);
static void HandleServerPacket(const std::string& msg);

static std::atomic<unsigned long long> g_bytesSent{ 0 };
static std::atomic<unsigned long long> g_packetsSent{ 0 };
static std::atomic<unsigned long long> g_bytesRecv{ 0 };
static std::atomic<unsigned long long> g_packetsRecv{ 0 };
static std::atomic<unsigned long long> g_udpPacketsSent{ 0 };
static std::atomic<unsigned long long> g_tcpPacketsSent{ 0 };
static std::atomic<unsigned long long> g_udpPacketsRecv{ 0 };
static std::atomic<unsigned long long> g_tcpPacketsRecv{ 0 };
static std::atomic<unsigned long long> g_outboundNanos{ 0 };
static std::atomic<unsigned long long> g_outboundCalls{ 0 };

static bool IsRealtimePacket(const std::string& opcode)
{
	return RouteForPacket(opcode) == PacketRoute::Realtime;
}

static bool ParseIntValue(const std::string& text, int& value)
{
	value = 0;

	if (!IsIntegerLiteral(text))
		return false;

	try
	{
		value = std::stoi(text);
		return true;
	}
	catch (...)
	{
		value = 0;
		return false;
	}
}

static bool TransportReady()
{
    return g_udpAvailable.load() && g_tcpAvailable.load();
}

static void QueueReliablePacket(const std::string& packet, const std::string& opcode)
{
    std::lock_guard<std::mutex> lock(g_reliableBacklogMutex);
    if (g_reliableBacklog.size() >= kReliableBacklogLimit)
    {
        Diagnostics::Log("reliable backlog full; reconnect required");
        RequestReconnect("reliable backlog full");
        return;
    }
    g_reliableBacklog.push_back({ packet, opcode });
}

static bool SendRawUdp(const std::string& packet)
{
	try
	{
		if (!theSocket.is_open())
			return false;

		std::vector<std::uint8_t> encoded;
		if (!EncodePacketText(packet, encoded))
			return false;
		theSocket.send(asio::buffer(encoded));
		g_bytesSent.fetch_add(encoded.size());
		g_packetsSent.fetch_add(1);
		g_udpPacketsSent.fetch_add(1);
		return true;
	}
	catch (...)
	{
		g_udpAvailable.store(false);
		return false;
	}
}

static bool SendTcpFrame(const std::string& packet)
{
	std::lock_guard<std::mutex> lock(g_tcpSendMutex);

	try
	{
		if (!tcpSocket.is_open())
			return false;

		std::vector<std::uint8_t> encoded;
		if (!EncodePacketText(packet, encoded))
			return false;
		const uint32_t length = static_cast<uint32_t>(encoded.size());
		unsigned char header[4] = {
			static_cast<unsigned char>((length >> 24) & 0xFF),
			static_cast<unsigned char>((length >> 16) & 0xFF),
			static_cast<unsigned char>((length >> 8) & 0xFF),
			static_cast<unsigned char>(length & 0xFF)
		};

		std::array<asio::const_buffer, 2> buffers = {
			asio::buffer(header, sizeof(header)),
			asio::buffer(encoded)
		};
		asio::write(tcpSocket, buffers);
		g_bytesSent.fetch_add(encoded.size() + sizeof(header));
		g_packetsSent.fetch_add(1);
		g_tcpPacketsSent.fetch_add(1);
		return true;
	}
	catch (...)
	{
		g_tcpAvailable.store(false);
		try
		{
			if (tcpSocket.is_open())
				tcpSocket.close();
		}
		catch (...)
		{
		}
		return false;
	}
}

static void SendPacket(const std::string& packet, const char* label)
{
    const std::string opcode = label == nullptr ? std::string() : std::string(label);
    const bool realtime = IsRealtimePacket(opcode);

    if (realtime)
    {
        if (g_udpAvailable.load())
            SendRawUdp(packet);
        return;
    }

    if (!g_tcpAvailable.load() || !SendTcpFrame(packet))
        QueueReliablePacket(packet, opcode);
}

static void FlushReliableBacklog()
{
    if (!TransportReady())
        return;

    for (int sent = 0; sent < 128; ++sent)
    {
        std::pair<std::string, std::string> pending;
        {
            std::lock_guard<std::mutex> lock(g_reliableBacklogMutex);
            if (g_reliableBacklog.empty())
                return;
            pending = std::move(g_reliableBacklog.front());
            g_reliableBacklog.pop_front();
        }

        if (!SendTcpFrame(pending.first))
        {
            std::lock_guard<std::mutex> lock(g_reliableBacklogMutex);
            g_reliableBacklog.push_front(std::move(pending));
            return;
        }
    }
}

static std::string BuildLocalPacketId()
{
	static std::mutex cacheMutex;
	static std::string cached;
	static int cachedPlayerId = -999;
	static std::string cachedUsername;

	const int localPlayerId = g_localPlayerId.load();

	std::lock_guard<std::mutex> lock(cacheMutex);

	if (localPlayerId == cachedPlayerId && username == cachedUsername)
		return cached;

	cachedPlayerId = localPlayerId;
	cachedUsername = username;

	if (localPlayerId > 0)
		cached = std::to_string(localPlayerId) + "\t" + EscapeField(username);
	else
		cached = EscapeField(username);

	return cached;
}

static void SendMovementPacket(const std::string& packetId, const std::vector<std::string>& movement, int movementSequence)
{
	if (movement.size() < 7 || movementSequence <= 0)
		return;

	std::vector<std::string> fields;
	fields.reserve(8);
	fields.push_back(std::to_string(movementSequence));
	fields.insert(fields.end(), movement.begin(), movement.begin() + 7);
	SendPacket(BuildPacket("MOVE", packetId, fields), "MOVE");
}

static void SendUpdate1(const std::string& payload)
{
	ParsedHalves halves = ParseValuesSplitHalf(payload);
	std::vector<std::string> movement;

	if (!ExtractMovementPrefix(halves, movement))
		return;

	const std::string packetId = BuildLocalPacketId();
	const int movementSequence = NextSequence(g_movementSequence);
	const int updateSequence = NextSequence(g_update1Sequence);

	SendMovementPacket(packetId, movement, movementSequence);

	std::vector<std::string> first = halves.first;
	std::vector<std::string> second = halves.second;

	first.insert(first.begin(), std::to_string(movementSequence));
	first.insert(first.begin(), std::to_string(updateSequence));
	second.insert(second.begin(), std::to_string(movementSequence));
	second.insert(second.begin(), std::to_string(updateSequence));

	if (!halves.first.empty())
		SendPacket(BuildPacket("UPDATE1A", packetId, first), "UPDATE1A");
	if (!halves.second.empty())
		SendPacket(BuildPacket("UPDATE1B", packetId, second), "UPDATE1B");
}

static void SendUpdate2(const std::string& payload)
{
	ParsedHalves halves = ParseValuesSplitHalf(payload);
	std::vector<std::string> movement;

	if (!RemoveMovementPrefix(halves, movement))
		return;

	const std::string packetId = BuildLocalPacketId();
	const int movementSequence = NextSequence(g_movementSequence);
	const int updateSequence = NextSequence(g_update2Sequence);

	SendMovementPacket(packetId, movement, movementSequence);

	std::vector<std::string> first = halves.first;
	std::vector<std::string> second = halves.second;

	first.insert(first.begin(), std::to_string(updateSequence));
	second.insert(second.begin(), std::to_string(updateSequence));

	if (!halves.first.empty())
		SendPacket(BuildPacket("UPDATE2A", packetId, first), "UPDATE2A");
	if (!halves.second.empty())
		SendPacket(BuildPacket("UPDATE2B", packetId, second), "UPDATE2B");
}

static void SendCombined(const std::string& payload, const char* opcode, int& sequence)
{
	ParsedHalves halves = ParseValuesSplitHalf(payload);
	std::vector<std::string> movement;

	if (!RemoveMovementPrefix(halves, movement))
		return;

	const std::string packetId = BuildLocalPacketId();
	const int movementSequence = NextSequence(g_movementSequence);
	const int updateSequence = NextSequence(sequence);

	SendMovementPacket(packetId, movement, movementSequence);

	std::vector<std::string> fields;
	fields.reserve(halves.first.size() + halves.second.size() + 1);
	fields.push_back(std::to_string(updateSequence));
	fields.insert(fields.end(), halves.first.begin(), halves.first.end());
	fields.insert(fields.end(), halves.second.begin(), halves.second.end());

	if (fields.size() > 1)
		SendPacket(BuildPacket(opcode, packetId, fields), opcode);
}

static void ProcessOutbound(const std::string& payload)
{
	const long long started = Profiler::Now();
	g_outboundCalls.fetch_add(1);

	struct Timing
	{
		long long start;
		~Timing() { g_outboundNanos.fetch_add(static_cast<unsigned long long>(Profiler::MicrosSince(start) * 1000.0)); }
	} timing{ started };

	const std::string tag = PayloadTag(payload);

	if (tag == "wo")
		SendUpdate1(payload);
	else if (tag == "wo2")
		SendUpdate2(payload);
	else if (tag == "wo3")
		SendCombined(payload, "UPDATE3", g_update3Sequence);
	else if (tag == "wo4")
		SendCombined(payload, "UPDATE4", g_update4Sequence);
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

	unsigned long long lastSeenMs = 0;
};

static std::mutex remoteMu;
static std::unordered_map<int, RemotePlayerChunks> remotePlayers;

static const unsigned long long kRemotePlayerExpiryMs = 30000;
static const unsigned long long kRemotePlayerPruneIntervalMs = 5000;
static unsigned long long g_lastRemotePruneMs = 0;

static void PruneRemotePlayers(unsigned long long nowMs)
{
	if (nowMs - g_lastRemotePruneMs < kRemotePlayerPruneIntervalMs)
		return;

	g_lastRemotePruneMs = nowMs;

	for (auto it = remotePlayers.begin(); it != remotePlayers.end();)
	{
		if (nowMs - it->second.lastSeenMs > kRemotePlayerExpiryMs)
			it = remotePlayers.erase(it);
		else
			++it;
	}
}

static const size_t kMovementFieldSpan = 7;
static const size_t kYawFieldIndex = 42;

static bool IsPoseField(size_t index)
{
	return index < kMovementFieldSpan || index == kYawFieldIndex;
}

static std::mutex g_deltaMutex;
static std::unordered_map<int, std::vector<std::string>> g_lastUpdate1;
static std::unordered_map<int, std::vector<std::string>> g_lastOther;

void SendPartyRequest(const char* opcode, const std::string& argument)
{
	std::vector<std::string> fields;

	if (!argument.empty())
		fields.push_back(argument);
	else
		fields.push_back("-");

	SendPacket(BuildPacket(opcode, BuildLocalPacketId(), fields), opcode);
}

static fs::path CharSlotPath(const std::string& slot)
{
	std::string safe;

	for (size_t i = 0; i < slot.size() && safe.size() < 32; ++i)
	{
		const char c = slot[i];

		if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')
			safe.push_back(c);
	}

	if (safe.empty())
		safe = "default";

	return getExecutablePath() / "WitcherOnline" / ("charsnap_" + safe + ".wosnap");
}

static const char kSnapAlphabet[] =
	"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static const unsigned char kSnapKey[] = { 0x5A, 0xC3, 0x1F, 0x76, 0xB8, 0x2E, 0x91, 0x4D };

static void SnapScramble(std::string& data)
{
	for (size_t i = 0; i < data.size(); ++i)
		data[i] = static_cast<char>(static_cast<unsigned char>(data[i]) ^ kSnapKey[i % sizeof(kSnapKey)]);
}

static std::string SnapEncode(const std::string& plain)
{
	std::string data = plain;
	SnapScramble(data);

	std::string encoded;
	encoded.reserve(((data.size() + 2) / 3) * 4);

	for (size_t i = 0; i < data.size(); i += 3)
	{
		const unsigned int b0 = static_cast<unsigned char>(data[i]);
		const unsigned int b1 = (i + 1 < data.size()) ? static_cast<unsigned char>(data[i + 1]) : 0u;
		const unsigned int b2 = (i + 2 < data.size()) ? static_cast<unsigned char>(data[i + 2]) : 0u;
		const unsigned int triple = (b0 << 16) | (b1 << 8) | b2;

		encoded.push_back(kSnapAlphabet[(triple >> 18) & 0x3F]);
		encoded.push_back(kSnapAlphabet[(triple >> 12) & 0x3F]);
		encoded.push_back((i + 1 < data.size()) ? kSnapAlphabet[(triple >> 6) & 0x3F] : '=');
		encoded.push_back((i + 2 < data.size()) ? kSnapAlphabet[triple & 0x3F] : '=');
	}

	return encoded;
}

static std::string SnapDecode(const std::string& encoded)
{
	int lookup[256];

	for (int i = 0; i < 256; ++i)
		lookup[i] = -1;

	for (int i = 0; i < 64; ++i)
		lookup[static_cast<unsigned char>(kSnapAlphabet[i])] = i;

	std::string data;
	data.reserve((encoded.size() / 4) * 3);

	unsigned int accum = 0;
	int bits = 0;

	for (size_t i = 0; i < encoded.size(); ++i)
	{
		const int value = lookup[static_cast<unsigned char>(encoded[i])];

		if (value < 0)
			continue;

		accum = (accum << 6) | static_cast<unsigned int>(value);
		bits += 6;

		if (bits >= 8)
		{
			bits -= 8;
			data.push_back(static_cast<char>((accum >> bits) & 0xFF));
		}
	}

	SnapScramble(data);
	return data;
}

bool WriteCharSnapshot(const std::string& slot, const std::string& text)
{
	try
	{
		const fs::path path = CharSlotPath(slot);

		fs::create_directories(path.parent_path());

		std::ofstream out(path, std::ios::out | std::ios::trunc | std::ios::binary);

		if (!out.is_open())
			return false;

		const std::string encoded = SnapEncode(text);

		out << "WOSNAP1" << '\n' << encoded;
		out.close();

		Diagnostics::Log("char snapshot written: " + path.string()
			+ " (" + std::to_string(text.size()) + " raw -> " + std::to_string(encoded.size()) + " encoded)");
		return true;
	}
	catch (const std::exception& e)
	{
		Diagnostics::Log(std::string("char snapshot write failed: ") + e.what());
		return false;
	}
}

std::string ReadCharSnapshot(const std::string& slot)
{
	try
	{
		const fs::path path = CharSlotPath(slot);

		std::ifstream in(path, std::ios::in | std::ios::binary);

		if (!in.is_open())
		{
			Diagnostics::Log("char snapshot missing: " + path.string());
			return std::string();
		}

		std::ostringstream buffer;
		buffer << in.rdbuf();
		in.close();

		std::string raw = buffer.str();

		if (raw.size() >= 8 && raw.compare(0, 7, "WOSNAP1") == 0)
			raw = SnapDecode(raw.substr(8));

		Diagnostics::Log("char snapshot read: " + path.string() + " (" + std::to_string(raw.size()) + " bytes)");
		return raw;
	}
	catch (const std::exception& e)
	{
		Diagnostics::Log(std::string("char snapshot read failed: ") + e.what());
		return std::string();
	}
}

std::string ResolveSaveDirectory()
{
	PWSTR docs = nullptr;

	if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Documents, 0, nullptr, &docs)))
	{
		const fs::path base = fs::path(docs) / "The Witcher 3" / "gamesaves";
		CoTaskMemFree(docs);

		std::error_code ec;

		if (fs::exists(base, ec))
			return base.string();
	}

	char profile[MAX_PATH]{};

	if (GetEnvironmentVariableA("USERPROFILE", profile, MAX_PATH) > 0)
	{
		const fs::path fallback = fs::path(profile) / "Documents" / "The Witcher 3" / "gamesaves";
		std::error_code ec;

		if (fs::exists(fallback, ec))
			return fallback.string();
	}

	return std::string();
}

void SendSaveChunk(const char* opcode, const std::vector<std::string>& fields)
{
	SendPacket(BuildPacket(opcode, BuildLocalPacketId(), fields), opcode);
}

void SendPartyRequest2(const char* opcode, const std::string& first, const std::string& second)
{
	std::vector<std::string> fields;

	fields.push_back(first.empty() ? "-" : first);
	fields.push_back(second.empty() ? "-" : second);

	SendPacket(BuildPacket(opcode, BuildLocalPacketId(), fields), opcode);
}

void SendPartyScaling(int stepMilli, int maxMilli)
{
	std::vector<std::string> fields;

	fields.push_back(std::to_string(stepMilli));
	fields.push_back(std::to_string(maxMilli));

	SendPacket(BuildPacket("PSCALE", BuildLocalPacketId(), fields), "PSCALE");
}

void ResetInboundDeltaCaches()
{
	std::lock_guard<std::mutex> lock(g_deltaMutex);

	g_lastUpdate1.clear();
	g_lastOther.clear();
}

static int ClassifyUpdate1Change(int playerId, const std::vector<std::string>& fields)
{
	std::lock_guard<std::mutex> lock(g_deltaMutex);

	auto it = g_lastUpdate1.find(playerId);

	if (it == g_lastUpdate1.end() || it->second.size() != fields.size())
	{
		g_lastUpdate1[playerId] = fields;
		return 2;
	}

	std::vector<std::string>& previous = it->second;
	bool movementChanged = false;
	bool stateChanged = false;

	for (size_t i = 0; i < fields.size(); ++i)
	{
		if (previous[i] == fields[i])
			continue;

		ScriptBinding::CountFieldChange(static_cast<int>(i));

		if (IsPoseField(i))
			movementChanged = true;
		else
			stateChanged = true;
	}

	previous = fields;

	if (stateChanged)
		return 2;

	return movementChanged ? 1 : 0;
}

static bool OtherChunkChanged(int playerId, InboundOpcode opcode, const std::vector<std::string>& fields)
{
	const int key = playerId * 16 + static_cast<int>(opcode);

	std::lock_guard<std::mutex> lock(g_deltaMutex);

	auto it = g_lastOther.find(key);

	if (it != g_lastOther.end() && it->second == fields)
		return false;

	g_lastOther[key] = fields;
	return true;
}

static void QueueInbound(InboundOpcode opcode, int playerId, int sequence, const std::string& sender, std::vector<std::string>&& fields);

static void QueueMovementFromUpdate1(int playerId, int sequence, const std::string& sender, const std::vector<std::string>& fields)
{
	std::vector<std::string> pose(fields.begin(), fields.begin() + kMovementFieldSpan);

	if (fields.size() > kYawFieldIndex)
		pose.push_back(fields[kYawFieldIndex]);
	else
		pose.push_back("0");

	QueueInbound(InboundOpcode::Pose, playerId, sequence, sender, std::move(pose));
}

static void QueueInbound(InboundOpcode opcode, int playerId, int sequence, const std::string& sender, std::vector<std::string>&& fields)
{
	if (opcode == InboundOpcode::Update1 && fields.size() >= kMovementFieldSpan)
	{
		const int change = ClassifyUpdate1Change(playerId, fields);

		if (change == 0)
		{
			ScriptBinding::CountSuppressed();
			return;
		}

		if (change == 1)
		{
			ScriptBinding::CountDowngraded();
			QueueMovementFromUpdate1(playerId, sequence, sender, fields);
			return;
		}
	}
	else if (opcode == InboundOpcode::Update2 || opcode == InboundOpcode::Update3 || opcode == InboundOpcode::Update4)
	{
		if (!OtherChunkChanged(playerId, opcode, fields))
		{
			ScriptBinding::CountSuppressed();
			return;
		}
	}

	InboundMessage message;
	message.opcode = opcode;
	message.playerId = playerId;
	message.sequence = sequence;
	message.sender = sender;
	message.fields = std::move(fields);

	ScriptBinding::PushInbound(std::move(message));
}

static std::atomic<bool> g_reconnectRequested{ false };
static std::atomic<bool> g_sessionFatal{ false };

static void CloseOnlineSession()
{
	std::lock_guard<std::mutex> lock(g_transportMutex);
	g_udpAvailable.store(false);
	g_tcpAvailable.store(false);

	try
	{
		if (theSocket.is_open())
			theSocket.close();
	}
	catch (...)
	{
	}

	try
	{
		if (tcpSocket.is_open())
			tcpSocket.close();
	}
	catch (...)
	{
	}
}

static bool OpenUdpPath()
{
    if (theSocket.is_open())
		return theSocket.is_open();

	try
	{
		theSocket.open(asio::ip::udp::v4());
		theSocket.bind(asio::ip::udp::endpoint(asio::ip::udp::v4(), 0));
		serverEndpoint = *resolver.resolve(asio::ip::udp::v4(), ip, port).begin();
		theSocket.connect(serverEndpoint);
		if (!SendRawUdp("HELLO\t2\t" + EscapeField(username) + "\tUDP"))
			throw std::runtime_error("UDP handshake send failed");
		return true;
	}
	catch (const std::exception& e)
	{
		Diagnostics::Log(std::string("UDP open failed: ") + e.what());
		try
		{
			if (theSocket.is_open())
				theSocket.close();
		}
		catch (...)
		{
		}
		return false;
	}
}

static bool OpenTcpPath()
{
    if (tcpSocket.is_open() || g_tcpConnecting.exchange(true))
		return tcpSocket.is_open();

	try
	{
		auto endpoints = tcpResolver.resolve(ip, port);
		const auto endpoint = *endpoints.begin();
		tcpSocket.open(endpoint.endpoint().protocol());
		tcpSocket.non_blocking(true);

		asio::error_code connectError;
		tcpSocket.connect(endpoint.endpoint(), connectError);

		if (connectError == asio::error::would_block || connectError == asio::error::in_progress)
		{
			fd_set writable;
			fd_set failed;
			FD_ZERO(&writable);
			FD_ZERO(&failed);
			FD_SET(tcpSocket.native_handle(), &writable);
			FD_SET(tcpSocket.native_handle(), &failed);
			timeval timeout{};
			timeout.tv_sec = 1;
			timeout.tv_usec = 0;

			const int ready = select(0, nullptr, &writable, &failed, &timeout);
			if (ready <= 0 || FD_ISSET(tcpSocket.native_handle(), &failed))
				throw std::runtime_error("connection timeout");

			int socketError = 0;
			int socketErrorLength = sizeof(socketError);
			if (getsockopt(tcpSocket.native_handle(), SOL_SOCKET, SO_ERROR,
				reinterpret_cast<char*>(&socketError), &socketErrorLength) != 0 || socketError != 0)
				throw std::runtime_error("connection refused");
		}
		else if (connectError)
		{
			throw asio::system_error(connectError);
		}

		tcpSocket.non_blocking(false);
		tcpSocket.set_option(asio::ip::tcp::no_delay(true));
		tcpSocket.set_option(asio::socket_base::keep_alive(true));
		if (!SendTcpFrame("HELLO\t2\t" + EscapeField(username) + "\tTCP"))
			throw std::runtime_error("TCP handshake send failed");
		g_tcpConnecting.store(false);
		return true;
	}
	catch (const std::exception& e)
	{
		Diagnostics::Log(std::string("TCP open failed: ") + e.what());
		try
		{
			if (tcpSocket.is_open())
				tcpSocket.close();
		}
		catch (...)
		{
		}
		g_tcpConnecting.store(false);
		return false;
	}
}

static bool OpenSocket()
{
	CloseOnlineSession();
	bool opened = OpenUdpPath();
	opened = OpenTcpPath() || opened;

	g_localPlayerId.store(0);
	ScriptBinding::SetLocalId(0);
	ScriptBinding::SetConnected(false);
    g_transportOpenedMs.store(GetTickCount64());
    return opened;
}

static void RequestReconnect(const char* reason)
{
	if (g_sessionFatal.load())
		return;

	if (g_reconnectRequested.exchange(true))
		return;

	Diagnostics::Log(std::string("reconnect requested: ") + reason);
	ScriptBinding::SetConnected(false);
}

static void HandleServerPacket(const std::string& msg)
{
	auto parts = SplitTabs(msg);
	if (parts.empty())
		return;

    if (parts[0] == "HELLOACK" && parts.size() >= 3 && parts[1] == "2")
    {
		if (parts.size() >= 4)
		{
			int assignedId = 0;
			if (ParsePositiveInt(parts[3], assignedId))
			{
				g_localPlayerId.store(assignedId);
				ScriptBinding::SetLocalId(assignedId);
			}
		}

		if (parts[2] == "UDP")
		{
			g_udpAvailable.store(true);
			g_lastUdpPongMs.store(GetTickCount64());
		}
        else if (parts[2] == "TCP")
        {
            g_tcpAvailable.store(true);
            SendRawUdp("HELLO\t2\t" + EscapeField(username) + "\tUDP");
        }

        ScriptBinding::SetConnected(TransportReady());
		Diagnostics::Log("transport ready UDP=" + std::string(g_udpAvailable.load() ? "yes" : "no")
			+ " TCP=" + std::string(g_tcpAvailable.load() ? "yes" : "no"));
		return;
	}

	if (parts[0] == "PONG" && parts.size() >= 3 && parts[1] == "2" && parts[2] == "UDP")
	{
		const bool restored = !g_udpAvailable.exchange(true);
		g_lastUdpPongMs.store(GetTickCount64());
        ScriptBinding::SetConnected(TransportReady());
		return;
	}

	if (parts[0] == "ERROR")
	{
		if (parts.size() >= 2 && parts[1] == "USERNAME_TAKEN")
		{
			ScriptBinding::SetStatus(ClientStatus::UsernameTaken);
			RequestReconnect("username taken");
			return;
		}

		if (parts.size() >= 2 && parts[1] == "BANNED")
			ScriptBinding::SetStatus(ClientStatus::Banned);
		else if (parts.size() >= 2 && parts[1] == "NOT_WHITELISTED")
			ScriptBinding::SetStatus(ClientStatus::NotWhitelisted);

		g_sessionFatal.store(true);
		ScriptBinding::SetConnected(false);
		CloseOnlineSession();
		return;
	}

	if (parts[0] == "KICK")
	{
		ScriptBinding::SetStatus(ClientStatus::Kicked);
		g_sessionFatal.store(true);
		ScriptBinding::SetConnected(false);
		CloseOnlineSession();
		return;
	}

	const std::string& opcode = parts[0];

	const bool isNetOpcode = opcode == "NPCNEW" || opcode == "NPCMOV" || opcode == "NPCFAST" || opcode == "NPCEND"
		|| opcode == "NPCDEAD" || opcode == "NPCHITF" || opcode == "NPCACKF" || opcode == "TSYNCR" || opcode == "NPCKILL"
		|| opcode == "NPCGIVE" || opcode == "NPCDROP" || opcode == "NPCGONE" || opcode == "PSTATEF"
		|| opcode == "NPCSCALE" || opcode == "NPCREG" || opcode == "NPCEVTF";

	const bool isSaveOpcode = opcode == "SAVEBEG" || opcode == "SAVECHK" || opcode == "SAVEEND"
		|| opcode == "SAVENACK" || opcode == "SAVEACK" || opcode == "SAVENEED";

	if (isNetOpcode)
	{
		if (parts.size() < 3)
			return;

		const std::string opcodeCopy = opcode;
		parts.erase(parts.begin(), parts.begin() + 3);
		NpcNet::OnPacket(opcodeCopy, parts);
		return;
	}

	if (!isSaveOpcode
		&& opcode != "PVIS"
		&& opcode != "PARTY"
		&& opcode != "PINVITE"
		&& opcode != "SCENE"
		&& opcode != "QITEM"
		&& opcode != "TPPOS"
		&& opcode != "PSCALEACK"
		&& opcode != "MOVE" && opcode != "UPDATE1A" && opcode != "UPDATE1B" && opcode != "UPDATE2A" && opcode != "UPDATE2B" && opcode != "UPDATE3" && opcode != "UPDATE4")
		return;

	if (parts.size() < 3)
		return;

	int playerId = 0;
	if (!ParsePositiveInt(parts[1], playerId))
		return;

	std::string playerUsername = parts[2];
	if (!IsValidUsername(playerUsername))
		return;

	if (playerUsername == ::username)
	{
		g_localPlayerId.store(playerId);
		ScriptBinding::SetLocalId(playerId);
	}

	std::vector<std::string> fields(parts.begin() + 3, parts.end());
	const unsigned long long nowMs = GetTickCount64();

	if (opcode == "PSCALEACK")
	{
		int stepMilli = 0;
		int maxMilli = 0;

		if (fields.size() >= 2
			&& ParseIntValue(fields[0], stepMilli)
			&& ParseIntValue(fields[1], maxMilli))
		{
			ScriptBinding::SetPartyScaleAck(stepMilli, maxMilli);
		}

		return;
	}

	if (opcode == "PVIS")
	{
		QueueInbound(InboundOpcode::Visibility, playerId, 0, playerUsername, std::move(fields));
		return;
	}

	if (opcode == "PARTY")
	{
		QueueInbound(InboundOpcode::PartyState, playerId, 0, playerUsername, std::move(fields));
		return;
	}

	if (opcode == "PINVITE")
	{
		QueueInbound(InboundOpcode::PartyInvite, playerId, 0, playerUsername, std::move(fields));
		return;
	}

	if (opcode == "SCENE")
	{
		QueueInbound(InboundOpcode::SceneStart, playerId, 0, playerUsername, std::move(fields));
		return;
	}

	if (opcode == "QITEM")
	{
		QueueInbound(InboundOpcode::QuestItem, playerId, 0, playerUsername, std::move(fields));
		return;
	}

	if (opcode == "TPPOS")
	{
		QueueInbound(InboundOpcode::PlayerPosition, playerId, 0, playerUsername, std::move(fields));
		return;
	}

	if (isSaveOpcode)
	{
		SaveTransfer::OnPacket(opcode, fields);
		return;
	}

	if (opcode == "MOVE")
	{
		if (fields.size() < 8)
			return;

		int movementSequence = 0;
		if (!ParsePositiveInt(fields[0], movementSequence))
			return;

		std::vector<std::string> values(fields.begin() + 1, fields.begin() + 8);
		QueueInbound(InboundOpcode::Move, playerId, movementSequence, playerUsername, std::move(values));
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
			PruneRemotePlayers(nowMs);

			auto& rp = remotePlayers[playerId];
			rp.username = playerUsername;
			rp.lastSeenMs = nowMs;

			if (opcode == "UPDATE3" && IsSequenceNewer(packetSequence, rp.lastPushed3Sequence))
			{
				rp.lastPushed3Sequence = packetSequence;
				shouldPush = true;
			}
			else if (opcode == "UPDATE4" && IsSequenceNewer(packetSequence, rp.lastPushed4Sequence))
			{
				rp.lastPushed4Sequence = packetSequence;
				shouldPush = true;
			}
		}

		if (!shouldPush)
			return;

		QueueInbound(opcode == "UPDATE3" ? InboundOpcode::Update3 : InboundOpcode::Update4,
			playerId, packetSequence, playerUsername, std::move(fields));
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
	int push1MovementSequence = 0;
	std::vector<std::string> combined1;
	std::vector<std::string> combined2;
	std::string pushUsername;

	{
		std::lock_guard<std::mutex> lk(remoteMu);
		PruneRemotePlayers(nowMs);

		auto& rp = remotePlayers[playerId];
		rp.username = playerUsername;
		rp.lastSeenMs = nowMs;

		if (opcode == "UPDATE1A" && IsSequenceNewer(packetSequence, rp.lastPushed1Sequence))
		{
			rp.update1A = std::move(fields);
			rp.update1ASequence = packetSequence;
			rp.update1AMovementSequence = movementSequence;
		}
		else if (opcode == "UPDATE1B" && IsSequenceNewer(packetSequence, rp.lastPushed1Sequence))
		{
			rp.update1B = std::move(fields);
			rp.update1BSequence = packetSequence;
			rp.update1BMovementSequence = movementSequence;
		}
		else if (opcode == "UPDATE2A" && IsSequenceNewer(packetSequence, rp.lastPushed2Sequence))
		{
			rp.update2A = std::move(fields);
			rp.update2ASequence = packetSequence;
		}
		else if (opcode == "UPDATE2B" && IsSequenceNewer(packetSequence, rp.lastPushed2Sequence))
		{
			rp.update2B = std::move(fields);
			rp.update2BSequence = packetSequence;
		}

		if (IsSequenceNewer(rp.update1ASequence, rp.lastPushed1Sequence)
			&& rp.update1ASequence == rp.update1BSequence
			&& rp.update1AMovementSequence == rp.update1BMovementSequence)
		{
			rp.lastPushed1Sequence = rp.update1ASequence;
			combined1 = rp.update1A;
			combined1.insert(combined1.end(), rp.update1B.begin(), rp.update1B.end());
			push1MovementSequence = rp.update1AMovementSequence;
			pushUsername = rp.username;
			push1 = true;
		}

		if (IsSequenceNewer(rp.update2ASequence, rp.lastPushed2Sequence) && rp.update2ASequence == rp.update2BSequence)
		{
			rp.lastPushed2Sequence = rp.update2ASequence;
			combined2 = rp.update2A;
			combined2.insert(combined2.end(), rp.update2B.begin(), rp.update2B.end());
			pushUsername = rp.username;
			push2 = true;
		}
	}

	if (push1)
		QueueInbound(InboundOpcode::Update1, playerId, push1MovementSequence, pushUsername, std::move(combined1));

	if (push2)
		QueueInbound(InboundOpcode::Update2, playerId, 0, pushUsername, std::move(combined2));
}

static constexpr unsigned long long kScriptStallMs = 600;
static bool g_reportedPaused = false;

static void ReportPauseState()
{
	if (g_localPlayerId.load() <= 0 || g_reconnectRequested.load())
		return;

	const unsigned long long lastTick = ScriptBinding::LastScriptTickMs();

	if (lastTick == 0)
		return;

	const unsigned long long nowMs = GetTickCount64();
	const bool stalled = (nowMs - lastTick) > kScriptStallMs;
	const bool paused = ScriptBinding::ScriptPaused() || stalled;

	if (paused == g_reportedPaused)
		return;

	g_reportedPaused = paused;

	std::vector<std::string> fields;
	fields.push_back(paused ? "1" : "0");

	SendPacket(BuildPacket("PSTATE", BuildLocalPacketId(), fields), "PSTATE");
	Diagnostics::Log(paused ? "script tick stalled -> reported paused" : "script tick resumed -> reported active");
}

static void SenderThread()
{
	std::string payload;
	unsigned long long nextReconnectMs = 0;
	unsigned long long nextTransportProbeMs = 0;

	while (g_run.load())
	{
		if (g_reconnectRequested.load() && !g_sessionFatal.load())
		{
			const unsigned long long nowMs = GetTickCount64();

			if (nowMs >= nextReconnectMs)
			{
				if (OpenSocket())
				{
					Diagnostics::Log("reconnected to " + ip + ":" + port);
					ScriptBinding::SetStatus(ClientStatus::Ok);
					g_reconnectRequested.store(false);
				}
				else
				{
					nextReconnectMs = nowMs + 2000;
				}
			}
		}

		const unsigned long long transportNow = GetTickCount64();
		if (transportNow >= nextTransportProbeMs && !g_sessionFatal.load())
		{
			nextTransportProbeMs = transportNow + 2000;

            if (!theSocket.is_open())
                OpenUdpPath();

            if (theSocket.is_open())
            {
                SendRawUdp("PING\t2\t" + EscapeField(username) + "\tUDP");
                const unsigned long long lastPong = g_lastUdpPongMs.load();
                if (lastPong != 0 && transportNow - lastPong > 6500)
                {
                    g_udpAvailable.store(false);
                }
            }

            if (!tcpSocket.is_open())
                OpenTcpPath();

            ScriptBinding::SetConnected(TransportReady());
        }

        bool worked = false;

        if (TransportReady())
        {
            FlushReliableBacklog();

            while (g_run.load() && ScriptBinding::PopOutbound(payload))
            {
                ProcessOutbound(payload);
                worked = true;
            }

            NpcNet::Tick();
            ReportPauseState();
        }

        Diagnostics::FlushSummary(false);
		ScriptBinding::ReportIdle();

		if (!worked)
			Sleep(2);
	}
}

static bool HandleUdpDatagram(const std::string& datagram)
{
	std::vector<std::string> packets;
	if (!DecodeDatagram(
		reinterpret_cast<const std::uint8_t*>(datagram.data()),
		datagram.size(),
		packets))
		return false;

	for (const auto& packet : packets)
	{
		HandleServerPacket(packet);
	}
	return true;
}

static void ReceiverThread()
{
	std::vector<char> data(8192);

	while (g_run.load())
	{
		try
		{
			asio::ip::udp::endpoint senderEndpoint;

			std::size_t len = theSocket.receive_from(asio::buffer(data), senderEndpoint);

			g_bytesRecv.fetch_add(len);
			g_packetsRecv.fetch_add(1);
			g_udpPacketsRecv.fetch_add(1);

			std::string msg(data.data(), len);
			if (!HandleUdpDatagram(msg))
				Diagnostics::Log("invalid binary UDP datagram dropped");
		}
		catch (const std::exception& e)
		{
			if (g_run.load() && !g_sessionFatal.load())
			{
				if (g_udpAvailable.exchange(false))
				{
					Diagnostics::Log(std::string("UDP receive failed: ") + e.what());
                }

				try
				{
					if (theSocket.is_open())
						theSocket.close();
				}
				catch (...)
				{
				}

                ScriptBinding::SetConnected(false);
			}
			Sleep(200);
		}
	}
}

static void TcpReceiverThread()
{
	while (g_run.load())
	{
		try
		{
			if (g_tcpConnecting.load() || !tcpSocket.is_open())
			{
				Sleep(200);
				continue;
			}

			unsigned char header[4]{};
			asio::read(tcpSocket, asio::buffer(header, sizeof(header)));
			const uint32_t length = (static_cast<uint32_t>(header[0]) << 24)
				| (static_cast<uint32_t>(header[1]) << 16)
				| (static_cast<uint32_t>(header[2]) << 8)
				| static_cast<uint32_t>(header[3]);

			if (length == 0 || length > (1u << 20))
				throw std::runtime_error("invalid TCP frame length");

			std::vector<std::uint8_t> frame(length);
			asio::read(tcpSocket, asio::buffer(frame));
			g_bytesRecv.fetch_add(length + sizeof(header));
			g_packetsRecv.fetch_add(1);
			g_tcpPacketsRecv.fetch_add(1);
			std::string msg;
			if (!DecodePacket(frame.data(), frame.size(), msg))
				throw std::runtime_error("invalid binary TCP packet");
			HandleServerPacket(msg);
		}
		catch (const std::exception& e)
		{
			if (g_run.load() && !g_sessionFatal.load())
			{
				if (g_tcpAvailable.exchange(false))
					Diagnostics::Log(std::string("TCP receive failed: ") + e.what());

				try
				{
					if (tcpSocket.is_open())
						tcpSocket.close();
				}
				catch (...)
				{
				}

                ScriptBinding::SetConnected(false);
			}
			Sleep(200);
		}
	}
}

void connectServer()
{
	if (g_shutdown.load())
		return;

	g_run.store(true);

	OpenSocket();

	NpcNet::Reset();
	NpcNet::SetSender([](const char* opcode, const std::vector<std::string>& fields)
		{
			SendPacket(BuildPacket(opcode, BuildLocalPacketId(), fields), opcode);
		});

	g_sender = std::thread(SenderThread);
    g_receiver = std::thread(ReceiverThread);
    g_tcpReceiver = std::thread(TcpReceiverThread);
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
				username.resize(16);

			ip = xml.child("ServerIP").text().as_string();
			port = xml.child("Port").text().as_string();

            if (username.length() < 2 || username == "none")
                username = "Player";
		}
	}

    Diagnostics::Init((baseDir / "WitcherOnline" / "witcheronline.log").string());
    Diagnostics::Log("client starting user=" + username + " server=" + ip + ":" + port
        + " transport=TCP+UDP required");

	ScriptBinding::SetUsername(username);
	ScriptBinding::Resolve();
	Diagnostics::Log(ScriptBinding::Report());

	if (ScriptBinding::IsResolved())
	{
		ScriptBinding::InstallRegistrationHook();
		Diagnostics::Log(ScriptBinding::RegistrationReport());
	}
	else
	{
		Diagnostics::Log("ScriptBinding unavailable - no transport");
	}

	connectServer();
}

static DWORD WINAPI InitThreadProc(LPVOID)
{
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
		Diagnostics::FlushSummary(true);
		Diagnostics::Log(ScriptBinding::TransportReport());
		Diagnostics::Log(ScriptBinding::ChurnReport());
		Diagnostics::Log(ScriptBinding::AccessorReport());

		{
			const unsigned long long packetsOut = g_packetsSent.load();
			const unsigned long long packetsIn = g_packetsRecv.load();
			const unsigned long long calls = g_outboundCalls.load();

			std::string wire = "wire sentBytes=" + std::to_string(g_bytesSent.load())
				+ " sentPackets=" + std::to_string(packetsOut)
				+ " avgOut=" + std::to_string(packetsOut ? g_bytesSent.load() / packetsOut : 0)
				+ " recvBytes=" + std::to_string(g_bytesRecv.load())
				+ " recvPackets=" + std::to_string(packetsIn)
				+ " avgIn=" + std::to_string(packetsIn ? g_bytesRecv.load() / packetsIn : 0)
				+ " udpOut=" + std::to_string(g_udpPacketsSent.load())
				+ " tcpOut=" + std::to_string(g_tcpPacketsSent.load())
				+ " udpIn=" + std::to_string(g_udpPacketsRecv.load())
				+ " tcpIn=" + std::to_string(g_tcpPacketsRecv.load())
				+ " | outboundParse calls=" + std::to_string(calls)
				+ " totalUs=" + std::to_string(g_outboundNanos.load() / 1000)
				+ " avgUs=" + std::to_string(calls ? (g_outboundNanos.load() / 1000) / calls : 0);

			Diagnostics::Log(wire);
		}
		Diagnostics::Shutdown();
		CloseOnlineSession();

		if (g_sender.joinable())
			g_sender.join();

		if (g_receiver.joinable())
			g_receiver.join();

		if (g_tcpReceiver.joinable())
			g_tcpReceiver.join();

		break;
	}
	}
	return TRUE;
}
