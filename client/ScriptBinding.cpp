#include "pch.h"
#include "ScriptBinding.h"
#include "NpcNet.h"
#include "Diagnostics.h"
#include "GameModule.h"
#include "InlineHook.h"
#include "SignatureScanner.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <deque>
#include <list>
#include <map>
#include <mutex>
#include <unordered_map>
#include <vector>

extern void ResetInboundDeltaCaches();
extern void SendPartyRequest(const char* opcode, const std::string& argument);
extern void SendPartyRequest2(const char* opcode, const std::string& first, const std::string& second);
extern void SendPartyScaling(int stepMilli, int maxMilli);
extern void SendSaveChunk(const char* opcode, const std::vector<std::string>& fields);
#include "SaveTransfer.h"

extern bool WriteCharSnapshot(const std::string& slot, const std::string& text);
extern std::string ReadCharSnapshot(const std::string& slot);
extern std::string ResolveSaveDirectory();

namespace w3mp {

	ScriptApi ScriptBinding::api_;
	bool ScriptBinding::resolved_ = false;
	bool ScriptBinding::attempted_ = false;

	static const char* kRegistrationSignature =
		"BA 10 00 00 00 B9 C0 00 00 00 E8 ?? ?? ?? ?? 48 8B F8 48 85 C0 74 ?? "
		"33 D2 41 B8 C0 00 00 00 48 8B C8 E8 ?? ?? ?? ?? E8 ?? ?? ?? ?? "
		"48 8D 15 ?? ?? ?? ?? 48 8B C8 E8 ?? ?? ?? ?? 4C 8D 05 ?? ?? ?? ?? "
		"89 45 10 48 8D 55 10 48 8B CF E8 ?? ?? ?? ?? "
		"48 8B F8 EB ?? 48 8B FB E8 ?? ?? ?? ?? 48 8B C8 48 8B D7 E8 ?? ?? ?? ??";

	static const int kOffsetName = 44;
	static const int kOffsetImpl = 59;
	static const int kOffsetAlloc = 10;
	static const int kOffsetMemset = 34;
	static const int kOffsetNamePool = 39;
	static const int kOffsetAddName = 54;
	static const int kOffsetFunctionCtor = 76;
	static const int kOffsetScriptSystem = 89;
	static const int kOffsetRegisterGlobal = 100;

	static const size_t kMinimumMatches = 32;

	static std::map<std::string, void*> g_natives;

	static void* ResolveCall(uint8_t* site, int offset)
	{
		return SignatureScanner::ResolveRelative(site + offset, 1, 5);
	}

	static void* ResolveLea(uint8_t* site, int offset)
	{
		return SignatureScanner::ResolveRelative(site + offset, 3, 7);
	}

	static std::string NarrowUtf16(const wchar_t* text, size_t limit = 96)
	{
		std::string out;

		for (size_t i = 0; i < limit && text[i]; ++i)
		{
			const wchar_t c = text[i];
			if (c < 32 || c > 126)
				return std::string();
			out += static_cast<char>(c);
		}

		return out;
	}

	static bool IsInsideImage(void* candidate)
	{
		const ModuleRegion& image = GameModule::Image();

		if (!candidate || !image.IsValid())
			return false;

		uint8_t* address = static_cast<uint8_t*>(candidate);
		return address >= image.base && address < image.base + image.size;
	}

	static bool IsPopulatedCodeTable(void* candidate, size_t required = 32)
	{
		if (!IsInsideImage(candidate))
			return false;

		const ModuleRegion& text = GameModule::Text();
		uint8_t** entries = static_cast<uint8_t**>(candidate);
		size_t valid = 0;

		for (size_t i = 0; i < required; ++i)
		{
			uint8_t* entry = entries[i];

			if (!entry)
				continue;

			if (entry < text.base || entry >= text.base + text.size)
				return false;

			valid++;
		}

		return valid * 2 >= required;
	}

	static void ResolveStringMarshalling(ScriptApi& api)
	{
		void* logChannel = ScriptBinding::FindNative("LogChannel");
		if (!logChannel)
			return;

		uint8_t* code = static_cast<uint8_t*>(logChannel);
		const ModuleRegion& text = GameModule::Text();

		for (int i = 0; i < 0x40; ++i)
		{
			if (code[i] != 0x48 && code[i] != 0x4C)
				continue;
			if (code[i + 1] != 0x8D)
				continue;
			if ((code[i + 2] & 0xC7) != 0x05)
				continue;

			void* candidate = SignatureScanner::ResolveRelative(code + i, 3, 7);
			if (IsInsideImage(candidate))
			{
				api.opcodeTable = candidate;
				break;
			}
		}

		SignaturePattern allocPattern = SignaturePattern::Parse(
			"44 8D 49 0E 44 8D 41 02 E8 ?? ?? ?? ??");

		for (int i = 0; i < 0xC0; ++i)
		{
			if (allocPattern.MatchesAt(code + i))
			{
				api.bufferAlloc = SignatureScanner::ResolveRelative(code + i + 8, 1, 5);
				break;
			}
		}

		for (int i = 0; i < 0xC0; ++i)
		{
			if (code[i] == 0x48 && code[i + 1] == 0x8B && code[i + 2] == 0x15)
			{
				void* slot = SignatureScanner::ResolveRelative(code + i, 3, 7);
				api.emptyString = static_cast<const wchar_t**>(slot);
				break;
			}
		}

		for (int i = 0; i < 0x40; ++i)
		{
			if (code[i] == 0x8B && code[i + 1] == 0x05)
			{
				api.emptyStringLength = reinterpret_cast<const int*>(SignatureScanner::ResolveRelative(code + i, 2, 6));
				break;
			}
		}

		if (api.bufferAlloc)
		{
			for (int i = 0; i < 0xC0; ++i)
			{
				if (code[i] == 0xE8)
				{
					void* target = SignatureScanner::ResolveRelative(code + i, 1, 5);
					if (target == api.bufferAlloc)
					{
						for (int j = i + 5; j < i + 0x30; ++j)
						{
							if (code[j] == 0xE8)
							{
								api.bufferCopy = SignatureScanner::ResolveRelative(code + j, 1, 5);
								break;
							}
						}
						break;
					}
				}
			}
		}
	}

	void* ScriptBinding::FindNative(const std::string& name)
	{
		auto it = g_natives.find(name);
		return it == g_natives.end() ? nullptr : it->second;
	}

	static void* Consensus(const std::vector<void*>& values, size_t& agreeing)
	{
		std::map<void*, size_t> tally;

		for (void* value : values)
		{
			if (value)
				tally[value]++;
		}

		void* best = nullptr;
		agreeing = 0;

		for (const auto& entry : tally)
		{
			if (entry.second > agreeing)
			{
				agreeing = entry.second;
				best = entry.first;
			}
		}

		return best;
	}

	bool ScriptBinding::Resolve()
	{
		if (attempted_)
			return resolved_;

		attempted_ = true;

		if (!GameModule::Resolve() || GameModule::IsSelfHosted())
			return false;

		SignaturePattern pattern = SignaturePattern::Parse(kRegistrationSignature);
		if (!pattern.IsValid())
			return false;

		std::vector<uint8_t*> sites = SignatureScanner::FindAll(GameModule::Text(), pattern);
		api_.matches = sites.size();

		if (sites.size() < kMinimumMatches)
			return false;

		std::vector<void*> allocs, memsets, pools, addNames, ctors, systems, registrars;
		allocs.reserve(sites.size());

		g_natives.clear();

		for (uint8_t* site : sites)
		{
			const wchar_t* nameText = static_cast<const wchar_t*>(ResolveLea(site, kOffsetName));
			void* impl = ResolveLea(site, kOffsetImpl);

			if (nameText && impl)
			{
				const std::string decoded = NarrowUtf16(nameText);
				if (!decoded.empty())
					g_natives[decoded] = impl;
			}

			allocs.push_back(ResolveCall(site, kOffsetAlloc));
			memsets.push_back(ResolveCall(site, kOffsetMemset));
			pools.push_back(ResolveCall(site, kOffsetNamePool));
			addNames.push_back(ResolveCall(site, kOffsetAddName));
			ctors.push_back(ResolveCall(site, kOffsetFunctionCtor));
			systems.push_back(ResolveCall(site, kOffsetScriptSystem));
			registrars.push_back(ResolveCall(site, kOffsetRegisterGlobal));
		}

		size_t agree = 0;
		size_t worst = sites.size();

		api_.alloc = Consensus(allocs, agree);
		worst = (agree < worst) ? agree : worst;

		api_.memset = Consensus(memsets, agree);
		worst = (agree < worst) ? agree : worst;

		api_.namePool = Consensus(pools, agree);
		worst = (agree < worst) ? agree : worst;

		api_.addName = Consensus(addNames, agree);
		worst = (agree < worst) ? agree : worst;

		api_.functionCtor = Consensus(ctors, agree);
		worst = (agree < worst) ? agree : worst;

		api_.scriptSystem = Consensus(systems, agree);
		worst = (agree < worst) ? agree : worst;

		api_.registerGlobal = Consensus(registrars, agree);
		worst = (agree < worst) ? agree : worst;

		api_.consistent = worst;

		resolved_ = api_.IsComplete() && worst == sites.size();

		if (resolved_)
			ResolveStringMarshalling(api_);

		return resolved_;
	}

	typedef void* (*AllocFn)(size_t size, size_t alignment);
	typedef void* (*MemsetFn)(void* destination, int value, size_t size);
	typedef void* (*NamePoolFn)();
	typedef int (*AddNameFn)(void* pool, const wchar_t* name);
	typedef void* (*FunctionCtorFn)(void* self, int* nameIndex, void* implementation);
	typedef void* (*ScriptSystemFn)();
	typedef void (*RegisterGlobalFn)(void* system, void* function);
	typedef void (*OpcodeHandlerFn)(void* context, void* frame, void* destination);
	typedef void* (*BufferAllocFn)(size_t zero, size_t bytes, size_t kind, size_t tag);
	typedef void* (*BufferCopyFn)(void* destination, const void* source, size_t bytes);

	struct RedString
	{
		wchar_t* data;
		uint32_t size;
		uint32_t padding;
	};

	static const size_t kFunctionSize = 0xC0;
	static const size_t kFunctionAlignment = 0x10;
	static const size_t kMaxQueueDepth = 4096;

	static InlineHook g_registerHook;
	static std::atomic<bool> g_registrationDone{ false };
	static std::atomic<int> g_registeredCount{ 0 };
	static std::string g_registrationError;

	static std::mutex g_queueMutex;
	static std::deque<std::string> g_outbound;
	static std::array<std::string, 4> g_outboundRealtime;
	static std::array<bool, 4> g_outboundRealtimePending{};
	static size_t g_outboundRealtimeCursor = 0;
	static std::list<InboundMessage> g_inbound;
	static std::unordered_map<unsigned long long, std::list<InboundMessage>::iterator> g_inboundReplaceable;
	static InboundMessage g_current;

	static std::mutex g_stateMutex;
	static int g_localId = 0;
	static std::string g_username;
	static std::atomic<bool> g_connected{ false };
	static std::atomic<int> g_status{ 0 };
	static std::atomic<int> g_partyScaleAckStep{ -1 };
	static std::atomic<int> g_partyScaleAckMax{ -1 };

	static std::atomic<unsigned long long> g_pollCount{ 0 };
	static std::atomic<unsigned long long> g_tickCount{ 0 };
	static std::atomic<unsigned long long> g_lastScriptTickMs{ 0 };
	static std::atomic<bool> g_scriptPaused{ false };
	static std::atomic<unsigned long long> g_sendCount{ 0 };
	static std::atomic<unsigned long long> g_recvCount{ 0 };
	static std::atomic<unsigned long long> g_dropCount{ 0 };
	static std::atomic<unsigned long long> g_inboundDrops{ 0 };
	static std::atomic<unsigned long long> g_outboundDrops{ 0 };
	static std::atomic<unsigned long long> g_inboundHighWater{ 0 };
	static std::atomic<unsigned long long> g_outboundHighWater{ 0 };
	static std::atomic<unsigned long long> g_outboundCoalesced{ 0 };
	static std::atomic<unsigned long long> g_inboundCoalesced{ 0 };

	static bool IsReplaceableInbound(InboundOpcode opcode)
	{
		return opcode == InboundOpcode::Move
			|| opcode == InboundOpcode::Update1
			|| opcode == InboundOpcode::Update2
			|| opcode == InboundOpcode::Update3
			|| opcode == InboundOpcode::Update4
			|| opcode == InboundOpcode::Pose
			|| opcode == InboundOpcode::Visibility;
	}

	static unsigned long long InboundKey(const InboundMessage& message)
	{
		return (static_cast<unsigned long long>(static_cast<unsigned int>(message.playerId)) << 8)
			| static_cast<unsigned int>(message.opcode);
	}

	static int OutboundRealtimeSlot(const std::string& payload)
	{
		if (payload.size() < 3 || payload[0] != 'w' || payload[1] != 'o')
			return -1;
		if (static_cast<unsigned char>(payload[2]) <= ' ')
			return 0;
		const char kind = payload[2];
		if (kind < '2' || kind > '4')
			return -1;
		if (payload.size() > 3 && static_cast<unsigned char>(payload[3]) > ' ')
			return -1;
		return kind - '1';
	}

	static size_t OutboundDepthLocked()
	{
		size_t depth = g_outbound.size();
		for (bool pending : g_outboundRealtimePending)
		{
			if (pending)
				depth++;
		}
		return depth;
	}

	static void AdvanceFrame(void* frame)
	{
		uint8_t** code = reinterpret_cast<uint8_t**>(static_cast<uint8_t*>(frame) + 0x30);
		if (*code)
			*code += 1;
	}

	static void ReadParameter(void* frame, void* destination)
	{
		uint8_t** code = reinterpret_cast<uint8_t**>(static_cast<uint8_t*>(frame) + 0x30);
		if (!*code)
			return;

		const uint8_t opcode = **code;
		*code += 1;

		void* context = *reinterpret_cast<void**>(frame);
		void* table = ScriptBinding::Api().opcodeTable;

		if (!IsPopulatedCodeTable(table))
			return;

		OpcodeHandlerFn handler = static_cast<OpcodeHandlerFn*>(table)[opcode];

		if (!handler)
			return;

		handler(context, frame, destination);
	}

	static bool MakeEmptyString(RedString& value)
	{
		const ScriptApi& api = ScriptBinding::Api();

		if (!api.CanMarshalStrings())
			return false;

		BufferAllocFn allocate = reinterpret_cast<BufferAllocFn>(api.bufferAlloc);
		BufferCopyFn copy = reinterpret_cast<BufferCopyFn>(api.bufferCopy);

		const int length = *api.emptyStringLength;
		const wchar_t* source = *api.emptyString;

		if (length < 0 || length > 64)
			return false;

		value.data = nullptr;
		value.size = static_cast<uint32_t>(length);
		value.padding = 0;

		if (length == 0)
			return true;

		if (!source)
			return false;

		void* buffer = allocate(0, static_cast<size_t>(length) * 2, 2, 14);
		if (!buffer)
			return false;

		copy(buffer, source, static_cast<size_t>(length) * 2);
		value.data = static_cast<wchar_t*>(buffer);
		return true;
	}

	static int ReadStringParameter(void* frame, RedString& text)
	{
		__try
		{
			if (!MakeEmptyString(text))
				return -2;

			ReadParameter(frame, &text);
			return static_cast<int>(text.size);
		}
		__except (EXCEPTION_EXECUTE_HANDLER)
		{
			return -3;
		}
	}

	static int ReadIntParameter(void* frame)
	{
		int value = 0;

		__try
		{
			ReadParameter(frame, &value);
		}
		__except (EXCEPTION_EXECUTE_HANDLER)
		{
			return 0;
		}

		return value;
	}

	static float ReadFloatParameter(void* frame)
	{
		float value = 0.0f;

		__try
		{
			ReadParameter(frame, &value);
		}
		__except (EXCEPTION_EXECUTE_HANDLER)
		{
			return 0.0f;
		}

		return value;
	}

	static bool ReadBoolParameter(void* frame)
	{
		bool value = false;

		__try
		{
			ReadParameter(frame, &value);
		}
		__except (EXCEPTION_EXECUTE_HANDLER)
		{
			return false;
		}

		return value;
	}

	static uint32_t LogicalLength(const RedString& text)
	{
		return (text.data && text.size > 0) ? text.size - 1 : 0;
	}

	static std::string NarrowPayload(const RedString& text)
	{
		std::string out;

		if (!text.data)
			return out;

		const uint32_t length = LogicalLength(text);
		out.reserve(length);

		for (uint32_t i = 0; i < length; ++i)
		{
			const wchar_t c = text.data[i];
			out += (c < 256) ? static_cast<char>(c) : '?';
		}

		return out;
	}

	static bool WriteStringResultUnguarded(void* result, const wchar_t* text, size_t length)
	{
		const ScriptApi& api = ScriptBinding::Api();

		if (!result || !api.CanMarshalStrings())
			return false;

		BufferAllocFn allocate = reinterpret_cast<BufferAllocFn>(api.bufferAlloc);
		BufferCopyFn copy = reinterpret_cast<BufferCopyFn>(api.bufferCopy);

		const size_t characters = length + 1;

		void* buffer = allocate(0, characters * sizeof(wchar_t), 2, 14);
		if (!buffer)
			return false;

		copy(buffer, text, characters * sizeof(wchar_t));

		RedString* destination = static_cast<RedString*>(result);
		destination->data = static_cast<wchar_t*>(buffer);
		destination->size = static_cast<uint32_t>(characters);

		return true;
	}

	static bool WriteStringGuarded(void* result, const wchar_t* text, size_t length)
	{
		__try
		{
			return WriteStringResultUnguarded(result, text, length);
		}
		__except (EXCEPTION_EXECUTE_HANDLER)
		{
			return false;
		}
	}

	static bool WriteStringResult(void* result, const std::string& text)
	{
		std::wstring wide(text.begin(), text.end());
		return WriteStringGuarded(result, wide.c_str(), wide.size());
	}

	bool ScriptBinding::PopOutbound(std::string& payload)
	{
		std::lock_guard<std::mutex> lock(g_queueMutex);

		if (!g_outbound.empty())
		{
			payload = std::move(g_outbound.front());
			g_outbound.pop_front();
			return true;
		}

		for (size_t checked = 0; checked < g_outboundRealtimePending.size(); ++checked)
		{
			const size_t slot = (g_outboundRealtimeCursor + checked) % g_outboundRealtimePending.size();
			if (!g_outboundRealtimePending[slot])
				continue;
			payload = std::move(g_outboundRealtime[slot]);
			g_outboundRealtime[slot].clear();
			g_outboundRealtimePending[slot] = false;
			g_outboundRealtimeCursor = (slot + 1) % g_outboundRealtimePending.size();
			return true;
		}

		return false;
	}

	void ScriptBinding::PushInbound(InboundMessage&& message)
	{
		std::lock_guard<std::mutex> lock(g_queueMutex);
		const bool replaceable = IsReplaceableInbound(message.opcode);
		const unsigned long long key = replaceable ? InboundKey(message) : 0;

		if (replaceable)
		{
			auto found = g_inboundReplaceable.find(key);
			if (found != g_inboundReplaceable.end())
			{
				*(found->second) = std::move(message);
				g_inboundCoalesced.fetch_add(1);
				return;
			}
		}

		if (g_inbound.size() >= kMaxQueueDepth)
		{
			if (IsReplaceableInbound(g_inbound.front().opcode))
				g_inboundReplaceable.erase(InboundKey(g_inbound.front()));
			g_inbound.pop_front();
			g_dropCount.fetch_add(1);
			g_inboundDrops.fetch_add(1);
		}

		g_inbound.push_back(std::move(message));
		if (replaceable)
		{
			auto inserted = g_inbound.end();
			--inserted;
			g_inboundReplaceable[key] = inserted;
		}
		g_inboundHighWater.store(std::max<unsigned long long>(g_inboundHighWater.load(), g_inbound.size()));
	}

	size_t ScriptBinding::InboundDepth()
	{
		std::lock_guard<std::mutex> lock(g_queueMutex);
		return g_inbound.size();
	}

	void ScriptBinding::SetLocalId(int id)
	{
		std::lock_guard<std::mutex> lock(g_stateMutex);
		g_localId = id;
	}

	void ScriptBinding::SetUsername(const std::string& name)
	{
		std::lock_guard<std::mutex> lock(g_stateMutex);
		g_username = name;
	}

	void ScriptBinding::SetConnected(bool connected)
	{
		g_connected.store(connected);

		if (!connected)
		{
			g_partyScaleAckStep.store(-1);
			g_partyScaleAckMax.store(-1);
		}
	}

	void ScriptBinding::SetPartyScaleAck(int stepMilli, int maxMilli)
	{
		g_partyScaleAckStep.store(stepMilli);
		g_partyScaleAckMax.store(maxMilli);
	}

	void ScriptBinding::SetStatus(ClientStatus status)
	{
		g_status.store(static_cast<int>(status));
	}

	static std::atomic<unsigned long long> g_deltaSuppressed{ 0 };
	static std::atomic<unsigned long long> g_deltaDowngraded{ 0 };

	static constexpr int kChurnSlots = 128;
	static std::atomic<unsigned long long> g_fieldChurn[kChurnSlots];

	void ScriptBinding::CountFieldChange(int index)
	{
		if (index >= 0 && index < kChurnSlots)
			g_fieldChurn[index].fetch_add(1, std::memory_order_relaxed);
	}

	std::string ScriptBinding::ChurnReport()
	{
		std::vector<std::pair<unsigned long long, int>> ranked;

		for (int i = 0; i < kChurnSlots; ++i)
		{
			const unsigned long long count = g_fieldChurn[i].load(std::memory_order_relaxed);

			if (count > 0)
				ranked.push_back({ count, i });
		}

		std::sort(ranked.begin(), ranked.end(), [](const auto& a, const auto& b) { return a.first > b.first; });

		std::string report = "field churn (top changers):";

		for (size_t i = 0; i < ranked.size() && i < 14; ++i)
			report += " [" + std::to_string(ranked[i].second) + "]x" + std::to_string(ranked[i].first);

		return report;
	}

	void ScriptBinding::CountSuppressed()
	{
		g_deltaSuppressed.fetch_add(1);
	}

	void ScriptBinding::CountDowngraded()
	{
		g_deltaDowngraded.fetch_add(1);
	}

	std::string ScriptBinding::TransportReport()
	{
		const unsigned long long applied = g_recvCount.load();
		const unsigned long long suppressed = g_deltaSuppressed.load();
		const unsigned long long downgraded = g_deltaDowngraded.load();
		const unsigned long long offered = applied + suppressed;

		unsigned long long saved = suppressed * 78 + downgraded * 67;
		size_t inboundDepth = 0;
		size_t outboundDepth = 0;

		{
			std::lock_guard<std::mutex> lock(g_queueMutex);
			inboundDepth = g_inbound.size();
			outboundDepth = OutboundDepthLocked();
		}

		return "transport sent=" + std::to_string(g_sendCount.load())
			+ " recv=" + std::to_string(applied)
			+ " dropped=" + std::to_string(g_dropCount.load())
			+ " inQueued=" + std::to_string(inboundDepth)
			+ " inHigh=" + std::to_string(g_inboundHighWater.load())
			+ " inDrop=" + std::to_string(g_inboundDrops.load())
			+ " outQueued=" + std::to_string(outboundDepth)
			+ " outHigh=" + std::to_string(g_outboundHighWater.load())
			+ " outDrop=" + std::to_string(g_outboundDrops.load())
			+ " outCoal=" + std::to_string(g_outboundCoalesced.load())
			+ " inCoal=" + std::to_string(g_inboundCoalesced.load())
			+ " | delta offered=" + std::to_string(offered)
			+ " suppressed=" + std::to_string(suppressed)
			+ " downgraded=" + std::to_string(downgraded)
			+ " nativeCallsSaved=" + std::to_string(saved);
	}

	void ScriptBinding::ReportIdle()
	{
		static unsigned long long lastSend = 0;
		static unsigned long long lastPoll = 0;
		static unsigned long long lastTick = 0;
		static unsigned long long lastCheckMs = 0;

		const unsigned long long nowMs = GetTickCount64();

		if (lastCheckMs == 0)
		{
			lastCheckMs = nowMs;
			lastSend = g_sendCount.load();
			lastPoll = g_pollCount.load();
			return;
		}

		if (nowMs - lastCheckMs < 5000)
			return;

		const unsigned long long sent = g_sendCount.load();
		const unsigned long long polled = g_pollCount.load();

		if (sent == lastSend)
		{
			Diagnostics::Log(std::string("transport STALLED:")
				+ " ticks=" + std::to_string(g_tickCount.load() - lastTick)
				+ " polls=" + std::to_string(polled - lastPoll)
				+ " connected=" + (g_connected.load() ? "yes" : "no")
				+ " status=" + std::to_string(g_status.load()));
		}

		lastCheckMs = nowMs;
		lastSend = sent;
		lastPoll = polled;
		lastTick = g_tickCount.load();
	}

	static void WO_Send(void* context, void* frame, void* result)
	{
		RedString text{};
		const int size = ReadStringParameter(frame, text);

		AdvanceFrame(frame);

		bool queued = false;

		if (size > 0)
		{
			std::string payload = NarrowPayload(text);

			if (!payload.empty())
			{
				std::lock_guard<std::mutex> lock(g_queueMutex);

				const int slot = OutboundRealtimeSlot(payload);
				if (slot >= 0)
				{
					if (g_outboundRealtimePending[slot])
						g_outboundCoalesced.fetch_add(1);
					g_outboundRealtime[slot] = std::move(payload);
					g_outboundRealtimePending[slot] = true;
				}
				else
				{
					if (g_outbound.size() >= kMaxQueueDepth)
					{
						g_outbound.pop_front();
						g_dropCount.fetch_add(1);
						g_outboundDrops.fetch_add(1);
					}
					g_outbound.push_back(std::move(payload));
				}

				g_outboundHighWater.store(std::max<unsigned long long>(g_outboundHighWater.load(), OutboundDepthLocked()));
				queued = true;
				g_sendCount.fetch_add(1);
			}
		}

		if (result)
			*static_cast<bool*>(result) = queued;
	}

	static void WO_Poll(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		g_pollCount.fetch_add(1);

		int count = 0;

		{
			std::lock_guard<std::mutex> lock(g_queueMutex);

			if (!g_inbound.empty())
			{
				g_current = std::move(g_inbound.front());
				if (IsReplaceableInbound(g_current.opcode))
					g_inboundReplaceable.erase(InboundKey(g_current));
				g_inbound.pop_front();
				count = static_cast<int>(g_current.fields.size());
				g_recvCount.fetch_add(1);
			}
			else
			{
				g_current = InboundMessage();
			}
		}

		if (result)
			*static_cast<int*>(result) = (g_current.opcode == InboundOpcode::None) ? -1 : count;
	}

	static void WO_Opcode(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = static_cast<int>(g_current.opcode);
	}

	static void WO_PlayerId(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = g_current.playerId;
	}

	static void WO_FieldCount(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = static_cast<int>(g_current.fields.size());
	}

	static void WO_Sequence(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = g_current.sequence;
	}

	static void WO_Sender(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);
		WriteStringResult(result, g_current.sender);
	}

	static int InternNameUnguarded(const wchar_t* text)
	{
		const ScriptApi& api = ScriptBinding::Api();

		NamePoolFn pool = reinterpret_cast<NamePoolFn>(api.namePool);
		AddNameFn addName = reinterpret_cast<AddNameFn>(api.addName);

		void* namePool = pool();
		if (!namePool)
			return 0;

		return addName(namePool, text);
	}

	static int InternNameGuarded(const wchar_t* text)
	{
		__try
		{
			return InternNameUnguarded(text);
		}
		__except (EXCEPTION_EXECUTE_HANDLER)
		{
			return 0;
		}
	}

	static void WriteNameResult(void* result, const std::string& text)
	{
		if (!result)
			return;

		if (text.empty() || text == "None")
		{
			*static_cast<int*>(result) = 0;
			return;
		}

		std::wstring wide(text.begin(), text.end());
		*static_cast<int*>(result) = InternNameGuarded(wide.c_str());
	}

	static void WO_SenderName(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);
		WriteNameResult(result, g_current.sender);
	}

	static std::atomic<unsigned long long> g_accessorNanos{ 0 };
	static std::atomic<unsigned long long> g_accessorCalls{ 0 };

	struct AccessorTimer
	{
		long long start;
		AccessorTimer() : start(Profiler::Now()) { g_accessorCalls.fetch_add(1); }
		~AccessorTimer() { g_accessorNanos.fetch_add(static_cast<unsigned long long>(Profiler::MicrosSince(start) * 1000.0)); }
	};

	std::string ScriptBinding::AccessorReport()
	{
		const unsigned long long calls = g_accessorCalls.load();
		const unsigned long long micros = g_accessorNanos.load() / 1000;

		return "accessors calls=" + std::to_string(calls)
			+ " totalUs=" + std::to_string(micros)
			+ " avgNs=" + std::to_string(calls ? g_accessorNanos.load() / calls : 0);
	}
	static const std::string& CurrentField(int index)
	{
		static const std::string empty;

		if (index < 0 || static_cast<size_t>(index) >= g_current.fields.size())
			return empty;

		return g_current.fields[static_cast<size_t>(index)];
	}

	static void WO_Str(void* context, void* frame, void* result)
	{
		AccessorTimer timer;
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);
		WriteStringResult(result, CurrentField(index));
	}

	static void WO_NameAt(void* context, void* frame, void* result)
	{
		AccessorTimer timer;
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);
		WriteNameResult(result, CurrentField(index));
	}

	static void WO_Int(void* context, void* frame, void* result)
	{
		AccessorTimer timer;
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		int value = 0;

		try
		{
			const std::string& text = CurrentField(index);
			if (!text.empty())
				value = std::stoi(text);
		}
		catch (...)
		{
			value = 0;
		}

		if (result)
			*static_cast<int*>(result) = value;
	}

	static void WO_Float(void* context, void* frame, void* result)
	{
		AccessorTimer timer;
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		float value = 0.0f;

		try
		{
			const std::string& text = CurrentField(index);
			if (!text.empty())
				value = std::stof(text);
		}
		catch (...)
		{
			value = 0.0f;
		}

		if (result)
			*static_cast<float*>(result) = value;
	}

	static void WO_Bool(void* context, void* frame, void* result)
	{
		AccessorTimer timer;
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		const std::string& text = CurrentField(index);
		const bool value = (text == "true" || text == "1");

		if (result)
			*static_cast<bool*>(result) = value;
	}

	static bool ProbeReadRaw(const void* address, unsigned long long& value)
	{
		__try
		{
			value = *reinterpret_cast<const unsigned long long*>(address);
			return true;
		}
		__except (EXCEPTION_EXECUTE_HANDLER)
		{
			return false;
		}
	}

	static bool ProbeReadPointer(const void* address, unsigned long long& value)
	{
		MEMORY_BASIC_INFORMATION mbi;

		if (!address)
			return false;

		if (VirtualQuery(address, &mbi, sizeof(mbi)) == 0)
			return false;

		if (mbi.State != MEM_COMMIT)
			return false;

		if (mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD))
			return false;

		return ProbeReadRaw(address, value);
	}

	static bool ProbeReadBytesRaw(const void* address, unsigned char* out, int count)
	{
		__try
		{
			const unsigned char* src = reinterpret_cast<const unsigned char*>(address);

			for (int i = 0; i < count; ++i)
				out[i] = src[i];

			return true;
		}
		__except (EXCEPTION_EXECUTE_HANDLER)
		{
			return false;
		}
	}

	static bool ProbeReadBytes(const void* address, unsigned char* out, int count)
	{
		MEMORY_BASIC_INFORMATION mbi;

		out[0] = 0;

		if (!address)
			return false;

		if (VirtualQuery(address, &mbi, sizeof(mbi)) == 0)
			return false;

		if (mbi.State != MEM_COMMIT || (mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD)))
			return false;

		return ProbeReadBytesRaw(address, out, count);
	}

	static bool ProbeReadWideRaw(const void* address, wchar_t* out, int maxChars)
	{
		__try
		{
			const wchar_t* src = reinterpret_cast<const wchar_t*>(address);
			int i = 0;

			for (; i < maxChars && src[i]; ++i)
				out[i] = src[i];

			out[i] = 0;
			return true;
		}
		__except (EXCEPTION_EXECUTE_HANDLER)
		{
			return false;
		}
	}

	static bool ProbeReadWide(const void* address, wchar_t* out, int maxChars)
	{
		MEMORY_BASIC_INFORMATION mbi;

		out[0] = 0;

		if (!address)
			return false;

		if (VirtualQuery(address, &mbi, sizeof(mbi)) == 0)
			return false;

		if (mbi.State != MEM_COMMIT || (mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD)))
			return false;

		return ProbeReadWideRaw(address, out, maxChars);
	}

	static std::string ProbeHex(unsigned long long value)
	{
		char buffer[32];
		sprintf_s(buffer, "0x%llX", value);
		return buffer;
	}

	static std::string ProbeDescribeCandidate(unsigned long long candidate)
	{
		const ModuleRegion& image = GameModule::Image();
		unsigned long long vtable = 0;

		if (candidate < 0x10000)
			return "null_or_small";

		if (!ProbeReadPointer(reinterpret_cast<const void*>(candidate), vtable))
			return "unreadable";

		if (!image.IsValid())
			return "vt=" + ProbeHex(vtable) + " (module unresolved)";

		const unsigned long long base = reinterpret_cast<unsigned long long>(image.base);
		const unsigned long long end = base + image.size;

		if (vtable >= base && vtable < end)
			return "OBJECT vt=" + ProbeHex(vtable) + " vtRva=" + ProbeHex(vtable - base);

		return "vt=" + ProbeHex(vtable) + " (outside module)";
	}

	static bool ProbeReadParam(void* frame, unsigned long long* slot)
	{
		__try
		{
			ReadParameter(frame, slot);
			return true;
		}
		__except (EXCEPTION_EXECUTE_HANDLER)
		{
			return false;
		}
	}

	static void WO_EntityProbe(void* context, void* frame, void* result)
	{
		unsigned long long slot[4] = { 0, 0, 0, 0 };
		const bool read = ProbeReadParam(frame, slot);

		AdvanceFrame(frame);

		std::string report = "read=";
		report += read ? "ok" : "EXCEPTION";

		unsigned long long entity = 0;

		if (slot[0] >= 0x10000)
			ProbeReadPointer(reinterpret_cast<const unsigned char*>(slot[0]) + 8, entity);

		report += " entity=" + ProbeHex(entity);

		if (entity >= 0x10000)
		{
			unsigned long long entVt = 0;
			ProbeReadPointer(reinterpret_cast<const void*>(entity), entVt);
			report += " entVtRva=" + ProbeHex(entVt - reinterpret_cast<unsigned long long>(GameModule::Image().base));
			unsigned long long candidate = 0;

			if (ProbeReadPointer(reinterpret_cast<const unsigned char*>(entity) + 0x18, candidate) && candidate >= 0x10000)
			{
				report += " T=" + ProbeHex(candidate);

				for (int w = 0; w < 24; ++w)
				{
					unsigned long long field = 0;
					const unsigned char* fa = reinterpret_cast<const unsigned char*>(candidate) + (w * 8);

					if (!ProbeReadPointer(fa, field) || field < 0x10000)
						continue;

					unsigned char bytes[80];

					if (!ProbeReadBytes(reinterpret_cast<const void*>(field), bytes, 80))
						continue;

					std::string text;
					int printable = 0;

					for (int c = 0; c < 80; ++c)
					{
						const unsigned char b = bytes[c];

						if (b > 31 && b < 127)
						{
							text += static_cast<char>(b);
							printable++;
						}
						else if (b == 0)
						{
							text += '.';
						}
						else
						{
							text += '?';
						}
					}

					if (printable >= 6)
						report += " s+" + ProbeHex(w * 8) + "='" + text + "'";
				}
			}

			report += " ptrs:";

			for (int off = 1; off < 96; ++off)
			{
				unsigned long long member = 0;
				const unsigned char* at = reinterpret_cast<const unsigned char*>(entity) + (off * 8);

				if (!ProbeReadPointer(at, member) || member < 0x10000)
					continue;

				unsigned long long memberVt = 0;

				if (!ProbeReadPointer(reinterpret_cast<const void*>(member), memberVt))
					continue;

				const unsigned long long base = reinterpret_cast<unsigned long long>(GameModule::Image().base);

				if (memberVt < base || memberVt >= base + GameModule::Image().size)
					continue;

				report += " +" + ProbeHex(off * 8) + "=" + ProbeHex(member) + "/" + ProbeHex(memberVt - base);
			}
		}

		WriteStringResult(result, report);
	}

	static const unsigned long long kEntityTemplateVtRva = 0x2A349F8;
	static const unsigned long long kDiskFileVtRva = 0x2A1EB80;
	static const unsigned long long kDirectoryVtRva = 0x2A20548;
	static const int kMaxDepotDepth = 32;

	static std::atomic<bool> g_templateLayoutWarned(false);

	static bool ProbeVtableIs(unsigned long long object, unsigned long long rva)
	{
		const ModuleRegion& image = GameModule::Image();
		unsigned long long vtable = 0;

		if (object < 0x10000 || !image.IsValid())
			return false;

		if (!ProbeReadPointer(reinterpret_cast<const void*>(object), vtable))
			return false;

		return vtable == reinterpret_cast<unsigned long long>(image.base) + rva;
	}

	static std::string ProbeNarrowWide(const wchar_t* text)
	{
		std::string out;

		for (int i = 0; text[i]; ++i)
			out += (text[i] < 256) ? static_cast<char>(text[i]) : '?';

		return out;
	}

	static bool ProbeRedString(unsigned long long object, unsigned long long dataOffset, unsigned long long sizeOffset, std::string& out)
	{
		unsigned long long dataPtr = 0;
		unsigned long long size = 0;
		wchar_t buffer[520];
		std::string text;

		if (object < 0x10000)
			return false;

		if (!ProbeReadPointer(reinterpret_cast<const unsigned char*>(object) + dataOffset, dataPtr) || dataPtr < 0x10000)
			return false;

		if (!ProbeReadPointer(reinterpret_cast<const unsigned char*>(object) + sizeOffset, size))
			return false;

		size &= 0xFFFFFFFFull;

		if (size < 2 || size > 512)
			return false;

		if (!ProbeReadWide(reinterpret_cast<const void*>(dataPtr), buffer, 511))
			return false;

		text = ProbeNarrowWide(buffer);

		if (text.empty() || text.size() + 1 != size)
			return false;

		out = text;
		return true;
	}

	static bool EndsWithW2Ent(const std::string& text)
	{
		const std::string suffix = ".w2ent";

		if (text.size() <= suffix.size())
			return false;

		return _stricmp(text.c_str() + (text.size() - suffix.size()), suffix.c_str()) == 0;
	}

	static bool TemplatePathFrom(unsigned long long tmpl, std::string& path)
	{
		unsigned long long disk = 0;
		unsigned long long directory = 0;
		std::vector<std::string> parts;
		std::string leaf;
		int depth;

		if (tmpl < 0x10000)
			return false;

		if (!ProbeReadPointer(reinterpret_cast<const unsigned char*>(tmpl) + 0x58, disk) || disk < 0x10000)
			return false;

		if (!ProbeRedString(disk, 0x18, 0x20, leaf) || !EndsWithW2Ent(leaf))
			return false;

		if (!ProbeReadPointer(reinterpret_cast<const unsigned char*>(disk) + 0x8, directory))
			directory = 0;

		for (depth = 0; depth < kMaxDepotDepth && directory >= 0x10000; ++depth)
		{
			unsigned long long parent = 0;
			std::string segment;

			if (!ProbeRedString(directory, 0x30, 0x38, segment))
				break;

			if (!ProbeReadPointer(reinterpret_cast<const unsigned char*>(directory) + 0x28, parent)
				|| parent < 0x10000)
				break;

			parts.push_back(segment);

			directory = parent;
		}

		if (!parts.empty() && _stricmp(parts.back().c_str(), "depot") == 0)
			parts.pop_back();

		path.clear();

		for (size_t i = parts.size(); i > 0; --i)
		{
			path += parts[i - 1];
			path += "\\";
		}

		path += leaf;

		return true;
	}

	static std::string EntityTemplatePath(unsigned long long entity)
	{
		unsigned long long handle = 0;
		unsigned long long candidate = 0;
		std::string path;

		if (entity < 0x10000)
			return std::string();

		if (ProbeReadPointer(reinterpret_cast<const unsigned char*>(entity) + 0x108, handle) && handle >= 0x10000)
		{
			if (ProbeReadPointer(reinterpret_cast<const unsigned char*>(handle) + 0x8, candidate)
				&& TemplatePathFrom(candidate, path))
				return path;
		}

		candidate = 0;

		if (ProbeReadPointer(reinterpret_cast<const unsigned char*>(entity) + 0x30, candidate)
			&& TemplatePathFrom(candidate, path))
			return path;

		return std::string();
	}

	static void WO_EntityTemplatePath(void* context, void* frame, void* result)
	{
		unsigned long long slot[4] = { 0, 0, 0, 0 };
		const bool read = ProbeReadParam(frame, slot);
		unsigned long long entity = 0;
		std::string path;

		AdvanceFrame(frame);

		if (read && slot[0] >= 0x10000)
			ProbeReadPointer(reinterpret_cast<const unsigned char*>(slot[0]) + 8, entity);

		if (entity >= 0x10000)
			path = EntityTemplatePath(entity);

		if (path.empty() && entity >= 0x10000 && !g_templateLayoutWarned.exchange(true))
		{
			const unsigned long long base = reinterpret_cast<unsigned long long>(GameModule::Image().base);
			unsigned long long entVt = 0;
			unsigned long long handle = 0;
			unsigned long long tmpl = 0;

			ProbeReadPointer(reinterpret_cast<const void*>(entity), entVt);
			ProbeReadPointer(reinterpret_cast<const unsigned char*>(entity) + 0x108, handle);

			if (handle >= 0x10000)
				ProbeReadPointer(reinterpret_cast<const unsigned char*>(handle) + 0x8, tmpl);

			unsigned long long disk = 0;

			if (tmpl >= 0x10000)
				ProbeReadPointer(reinterpret_cast<const unsigned char*>(tmpl) + 0x58, disk);

			Diagnostics::Log("ScriptBinding: WO_EntityTemplatePath FAILED - depot layout mismatch"
				" (game build changed?) entity=" + ProbeHex(entity)
				+ " entVtRva=" + ProbeHex(entVt >= base ? entVt - base : 0)
				+ " handle=" + ProbeHex(handle)
				+ " tmpl=" + ProbeHex(tmpl)
				+ " diskFile=" + ProbeHex(disk));
		}

		WriteStringResult(result, path);
	}

	static void WO_ResetDeltas(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		ResetInboundDeltaCaches();
	}

	static void WO_NpcSyncMode(void* context, void* frame, void* result)
	{
		const int mode = ReadIntParameter(frame);
		AdvanceFrame(frame);

		NpcNet::SetSyncMode(mode);
	}

	static void WO_PartyScaleConfig(void* context, void* frame, void* result)
	{
		const int stepMilli = ReadIntParameter(frame);
		const int maxMilli = ReadIntParameter(frame);
		AdvanceFrame(frame);

		SendPartyScaling(stepMilli, maxMilli);
	}

	static void WO_PartyScaleAckStep(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = g_partyScaleAckStep.load();
	}

	static void WO_PartyScaleAckMax(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = g_partyScaleAckMax.load();
	}

	static void WO_PartyJoin(void* context, void* frame, void* result)
	{
		RedString text;
		const int size = ReadStringParameter(frame, text);

		AdvanceFrame(frame);

		if (size > 0)
			SendPartyRequest("PJOIN", NarrowPayload(text));
	}

	static void WO_PartyLeave(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		SendPartyRequest("PLEAVE", std::string());
	}

	static void WO_PartyRespond(void* context, void* frame, void* result)
	{
		RedString text;
		const int size = ReadStringParameter(frame, text);
		const bool approved = ReadBoolParameter(frame);

		AdvanceFrame(frame);

		if (size > 0)
			SendPartyRequest2("PRESP", NarrowPayload(text), approved ? "1" : "0");
	}

	static void WO_SaveSend(void* context, void* frame, void* result)
	{
		RedString path;
		const int size = ReadStringParameter(frame, path);

		AdvanceFrame(frame);

		bool ok = false;

		if (size > 0)
			ok = SaveTransfer::BeginSend(NarrowPayload(path));

		if (result)
			*static_cast<bool*>(result) = ok;
	}

	static std::atomic<bool> g_coopRestorePending{ false };

	static std::mutex g_coopStashMutex;
	static std::string g_coopCharStash;

	static void WO_CoopStashChar(void* context, void* frame, void* result)
	{
		RedString body;
		const int size = ReadStringParameter(frame, body);

		AdvanceFrame(frame);

		std::lock_guard<std::mutex> lock(g_coopStashMutex);
		g_coopCharStash = (size > 0) ? NarrowPayload(body) : std::string();

		Diagnostics::Log("coop character stashed (" + std::to_string(g_coopCharStash.size()) + " bytes)");

		if (result)
			*static_cast<bool*>(result) = !g_coopCharStash.empty();
	}

	static void WO_CoopFetchChar(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		std::string body;

		{
			std::lock_guard<std::mutex> lock(g_coopStashMutex);
			body.swap(g_coopCharStash);
		}

		WriteStringResult(result, body);
	}

	static void WO_CoopMarkRestore(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		g_coopRestorePending.store(true);
		Diagnostics::Log("coop restore armed");
	}

	static void WO_CoopTakeRestore(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		const bool pending = g_coopRestorePending.exchange(false);

		if (pending)
			Diagnostics::Log("coop restore claimed");

		if (result)
			*static_cast<bool*>(result) = pending;
	}

	static void WO_SaveDirectory(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		WriteStringResult(result, ResolveSaveDirectory());
	}

	static void WO_SaveWant(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		SaveTransfer::RequestSave();
	}

	static void WO_SaveNeededBy(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		WriteStringResult(result, SaveTransfer::TakePendingRequest());
	}

	static void WO_SaveIncomingDir(void* context, void* frame, void* result)
	{
		RedString dir;
		const int size = ReadStringParameter(frame, dir);

		AdvanceFrame(frame);

		if (size > 0)
			SaveTransfer::SetIncomingPath(NarrowPayload(dir));
	}

	static void WO_SaveState(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = static_cast<int>(SaveTransfer::Status());
	}

	static void WO_SaveProgress(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = SaveTransfer::ProgressPermille();
	}

	static void WO_SaveError(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		WriteStringResult(result, SaveTransfer::LastError());
	}

	static void WO_SaveFile(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		WriteStringResult(result, SaveTransfer::CompletedFile());
	}

	static void WO_SaveReset(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		SaveTransfer::Reset();
	}

	static void WO_SceneStart(void* context, void* frame, void* result)
	{
		RedString scenePath;
		const int scenePathSize = ReadStringParameter(frame, scenePath);
		RedString actorTag;
		const int actorTagSize = ReadStringParameter(frame, actorTag);
		RedString voiceTag;
		const int voiceTagSize = ReadStringParameter(frame, voiceTag);
		const float actorX = ReadFloatParameter(frame);
		const float actorY = ReadFloatParameter(frame);
		const float actorZ = ReadFloatParameter(frame);
		const float sceneX = ReadFloatParameter(frame);
		const float sceneY = ReadFloatParameter(frame);
		const float sceneZ = ReadFloatParameter(frame);
		const float entryX = ReadFloatParameter(frame);
		const float entryY = ReadFloatParameter(frame);
		const float entryZ = ReadFloatParameter(frame);
		const float entryHeading = ReadFloatParameter(frame);
		const int area = ReadIntParameter(frame);
		const int kind = ReadIntParameter(frame);
		const int choicePhase = ReadIntParameter(frame);
		RedString choiceSignature;
		const int choiceSignatureSize = ReadStringParameter(frame, choiceSignature);
		const int choiceCount = ReadIntParameter(frame);

		AdvanceFrame(frame);

		std::vector<std::string> fields;
		fields.push_back(scenePathSize > 0 ? NarrowPayload(scenePath) : std::string("-"));
		fields.push_back(actorTagSize > 0 ? NarrowPayload(actorTag) : std::string("-"));
		fields.push_back(voiceTagSize > 0 ? NarrowPayload(voiceTag) : std::string("-"));
		fields.push_back(std::to_string(actorX));
		fields.push_back(std::to_string(actorY));
		fields.push_back(std::to_string(actorZ));
		fields.push_back(std::to_string(sceneX));
		fields.push_back(std::to_string(sceneY));
		fields.push_back(std::to_string(sceneZ));
		fields.push_back(std::to_string(entryX));
		fields.push_back(std::to_string(entryY));
		fields.push_back(std::to_string(entryZ));
		fields.push_back(std::to_string(entryHeading));
		fields.push_back(std::to_string(area));
		fields.push_back(std::to_string(kind));
		fields.push_back(std::to_string(choicePhase));
		fields.push_back(choiceSignatureSize > 0 ? NarrowPayload(choiceSignature) : std::string("-"));
		fields.push_back(std::to_string(choiceCount));

		SendSaveChunk("SCENE", fields);
	}

	static void WO_QuestItem(void* context, void* frame, void* result)
	{
		const int kind = ReadIntParameter(frame);
		RedString subject;
		const int subjectSize = ReadStringParameter(frame, subject);
		const int quantity = ReadIntParameter(frame);

		AdvanceFrame(frame);

		if (subjectSize <= 0)
			return;

		std::vector<std::string> fields;
		fields.push_back(std::to_string(kind));
		fields.push_back(NarrowPayload(subject));
		fields.push_back(std::to_string(quantity));

		SendSaveChunk("QITEM", fields);
	}

	static void WO_SavePurge(void* context, void* frame, void* result)
	{
		RedString marker;
		const int size = ReadStringParameter(frame, marker);

		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = SaveTransfer::PurgeIncoming(size > 0 ? NarrowPayload(marker) : std::string());
	}

	static void WO_SaveLeftovers(void* context, void* frame, void* result)
	{
		RedString marker;
		const int size = ReadStringParameter(frame, marker);

		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = SaveTransfer::CountIncoming(size > 0 ? NarrowPayload(marker) : std::string());
	}

	static void WO_ToName(void* context, void* frame, void* result)
	{
		RedString text;
		const int size = ReadStringParameter(frame, text);

		AdvanceFrame(frame);

		WriteNameResult(result, size > 0 ? NarrowPayload(text) : std::string());
	}

	static void WO_CharStore(void* context, void* frame, void* result)
	{
		RedString slot;
		const int slotSize = ReadStringParameter(frame, slot);

		RedString body;
		const int bodySize = ReadStringParameter(frame, body);

		AdvanceFrame(frame);

		bool ok = false;

		if (slotSize > 0)
			ok = WriteCharSnapshot(NarrowPayload(slot), bodySize > 0 ? NarrowPayload(body) : std::string());

		if (result)
			*static_cast<bool*>(result) = ok;
	}

	static void WO_CharFetch(void* context, void* frame, void* result)
	{
		RedString slot;
		const int slotSize = ReadStringParameter(frame, slot);

		AdvanceFrame(frame);

		std::string text;

		if (slotSize > 0)
			text = ReadCharSnapshot(NarrowPayload(slot));

		WriteStringResult(result, text);
	}

	static void WO_PartyCoopMode(void* context, void* frame, void* result)
	{
		const bool enabled = ReadBoolParameter(frame);

		AdvanceFrame(frame);

		SendPartyRequest("PCOOP", enabled ? std::string("1") : std::string("0"));
	}

	static void WO_SetPaused(void* context, void* frame, void* result)
	{
		const bool paused = ReadBoolParameter(frame);
		AdvanceFrame(frame);

		g_scriptPaused.store(paused);
	}

	static void WO_IsPlayerPaused(void* context, void* frame, void* result)
	{
		const int playerId = ReadIntParameter(frame);
		AdvanceFrame(frame);

		if (result)
			*static_cast<bool*>(result) = NpcNet::IsPlayerPaused(playerId);
	}

	static void WO_LocalId(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		std::lock_guard<std::mutex> lock(g_stateMutex);

		if (result)
			*static_cast<int*>(result) = g_localId;
	}

	static void WO_Username(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		std::string name;
		{
			std::lock_guard<std::mutex> lock(g_stateMutex);
			name = g_username;
		}

		WriteStringResult(result, name);
	}

	static void WO_Connected(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<bool*>(result) = g_connected.load();
	}

	static const int kTickSlots[] = { 1, 4, 1, 3, 1, 4, 1, 2, 1, 3, 1, 4 };
	static const size_t kTickSlotCount = sizeof(kTickSlots) / sizeof(kTickSlots[0]);
	static const unsigned long long kGatherIntervalMs = 50;

	static unsigned long long g_nextGatherMs = 0;
	static size_t g_tickSlot = 0;

	static std::mutex g_frameMutex;
	static long long g_lastTickStamp = 0;
	static double g_frameTotalMs = 0.0;
	static double g_frameMaxMs = 0.0;
	static unsigned long long g_frameSamples = 0;
	static unsigned long long g_frameOver20 = 0;

	static void RecordFrameInterval()
	{
		const long long now = Profiler::Now();

		std::lock_guard<std::mutex> lock(g_frameMutex);

		if (g_lastTickStamp != 0)
		{
			const double ms = Profiler::MicrosSince(g_lastTickStamp) / 1000.0;

			if (ms > 0.0 && ms < 500.0)
			{
				g_frameTotalMs += ms;
				g_frameSamples++;

				if (ms > g_frameMaxMs)
					g_frameMaxMs = ms;

				if (ms > 20.0)
					g_frameOver20++;
			}
		}

		g_lastTickStamp = now;
	}

	struct MatchRecord
	{
		int id = 0;
		std::string appearance;
		float x = 0.0f;
		float y = 0.0f;
		float z = 0.0f;
		bool taken = false;
	};

	static bool NextRecordField(const std::string& text, size_t& pos, std::string& out)
	{
		if (pos >= text.size())
			return false;

		const size_t start = pos;

		while (pos < text.size() && text[pos] != ':' && text[pos] != ';')
			++pos;

		out.assign(text, start, pos - start);

		if (pos < text.size() && text[pos] == ':')
			++pos;

		return true;
	}

	static void ParseMatchRecords(const std::string& text, std::vector<MatchRecord>& out)
	{
		size_t pos = 0;

		while (pos < text.size())
		{
			MatchRecord record;
			std::string field;

			if (!NextRecordField(text, pos, field))
				break;

			record.id = atoi(field.c_str());

			NextRecordField(text, pos, record.appearance);

			NextRecordField(text, pos, field);
			record.x = static_cast<float>(atof(field.c_str()));

			NextRecordField(text, pos, field);
			record.y = static_cast<float>(atof(field.c_str()));

			NextRecordField(text, pos, field);
			record.z = static_cast<float>(atof(field.c_str()));

			if (pos < text.size() && text[pos] == ';')
				++pos;

			if (record.id != 0 && !record.appearance.empty())
				out.push_back(record);
		}
	}

	struct MatchOp
	{
		int kind = 0;
		int a = 0;
		int b = 0;
	};

	static std::vector<MatchOp> g_matchOps;

	static const float kMatchRadius = 60.0f;

	static void WO_Match(void* context, void* frame, void* result)
	{
		RedString canonicalText{};
		ReadStringParameter(frame, canonicalText);

		RedString localText{};
		ReadStringParameter(frame, localText);

		AdvanceFrame(frame);

		std::vector<MatchRecord> canonical;
		std::vector<MatchRecord> local;

		ParseMatchRecords(NarrowPayload(canonicalText), canonical);
		ParseMatchRecords(NarrowPayload(localText), local);

		g_matchOps.clear();

		const float radiusSquared = kMatchRadius * kMatchRadius;

		for (size_t c = 0; c < canonical.size(); ++c)
		{
			int bestIndex = -1;
			float bestDistance = radiusSquared;

			for (size_t l = 0; l < local.size(); ++l)
			{
				if (local[l].taken || local[l].appearance != canonical[c].appearance)
					continue;

				const float dx = local[l].x - canonical[c].x;
				const float dy = local[l].y - canonical[c].y;
				const float dz = local[l].z - canonical[c].z;
				const float distance = dx * dx + dy * dy + dz * dz;

				if (distance <= bestDistance)
				{
					bestDistance = distance;
					bestIndex = static_cast<int>(l);
				}
			}

			MatchOp op;
			op.a = canonical[c].id;

			if (bestIndex >= 0)
			{
				local[bestIndex].taken = true;
				op.kind = 0;
				op.b = local[bestIndex].id;
			}
			else
			{
				op.kind = 1;
				op.b = 0;
			}

			g_matchOps.push_back(op);
		}

		for (size_t l = 0; l < local.size(); ++l)
		{
			if (local[l].taken)
				continue;

			MatchOp op;
			op.kind = 2;
			op.a = local[l].id;
			op.b = 0;
			g_matchOps.push_back(op);
		}

		if (result)
			*static_cast<int*>(result) = static_cast<int>(g_matchOps.size());
	}

	static void WO_MatchOp(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = (index >= 0 && index < static_cast<int>(g_matchOps.size())) ? g_matchOps[index].kind : -1;
	}

	static void WO_MatchA(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = (index >= 0 && index < static_cast<int>(g_matchOps.size())) ? g_matchOps[index].a : 0;
	}

	static void WO_MatchB(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = (index >= 0 && index < static_cast<int>(g_matchOps.size())) ? g_matchOps[index].b : 0;
	}

	static void WO_NpcBeginOwned(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);
		NpcNet::BeginOwned();
	}

	static void WO_NpcPushOwned(void* context, void* frame, void* result)
	{
		const int guid = ReadIntParameter(frame);
		const int area = ReadIntParameter(frame);
		const int localCount = ReadIntParameter(frame);

		RedString typeText{};
		ReadStringParameter(frame, typeText);

		RedString appearanceText{};
		ReadStringParameter(frame, appearanceText);

		RedString identityText{};
		ReadStringParameter(frame, identityText);
		const int identityFlags = ReadIntParameter(frame);

		const float x = ReadFloatParameter(frame);
		const float y = ReadFloatParameter(frame);
		const float z = ReadFloatParameter(frame);
		const float heading = ReadFloatParameter(frame);
		const int hpPermille = ReadIntParameter(frame);
		const int flags = ReadIntParameter(frame);
		const int targetPlayerId = ReadIntParameter(frame);
		const int terminalState = ReadIntParameter(frame);
		const int terminalAttackerId = ReadIntParameter(frame);

		AdvanceFrame(frame);

		const bool stored = NpcNet::PushOwned(
			guid,
			area,
			localCount,
			NarrowPayload(typeText),
			NarrowPayload(appearanceText),
			NarrowPayload(identityText),
			identityFlags,
			x, y, z, heading,
			hpPermille,
			flags,
			targetPlayerId,
			terminalState,
			terminalAttackerId);

		if (result)
			*static_cast<bool*>(result) = stored;
	}

	static void WO_NpcEndOwned(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);
		NpcNet::EndOwned();
	}

	static void WO_NpcPull(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		const int count = NpcNet::Pull();

		if (result)
			*static_cast<int*>(result) = count;
	}

	static const ReplicaCommand* CommandAt(void* frame)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);
		return NpcNet::Command(index);
	}

	static void WO_NpcId(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<int*>(result) = command ? command->canonicalId : 0;
	}

	static void WO_NpcOp(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<int*>(result) = command ? command->op : -1;
	}

	static void WO_NpcGuid(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<int*>(result) = command ? command->localGuid : 0;
	}

	static void WO_NpcX(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<float*>(result) = command ? command->x : 0.0f;
	}

	static void WO_NpcY(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<float*>(result) = command ? command->y : 0.0f;
	}

	static void WO_NpcZ(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<float*>(result) = command ? command->z : 0.0f;
	}

	static void WO_NpcHeading(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<float*>(result) = command ? command->heading : 0.0f;
	}

	static void WO_NpcSpeed(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<float*>(result) = command ? command->speed : 0.0f;
	}

	static void WO_NpcHp(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<int*>(result) = command ? command->hpPermille : -1;
	}

	static void WO_NpcFlags(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<int*>(result) = command ? command->flags : 0;
	}

	static void WO_NpcTarget(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<int*>(result) = command ? command->targetPlayerId : 0;
	}

	static void WO_NpcType(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);
		WriteStringResult(result, command ? command->typeCode : std::string());
	}

	static void WO_NpcAppearance(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);
		WriteNameResult(result, command ? command->appearance : std::string());
	}

	static void WO_NpcBind(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		const int localGuid = ReadIntParameter(frame);
		const int kind = ReadIntParameter(frame);
		AdvanceFrame(frame);

		NpcNet::Bind(index, localGuid, kind);
	}

	static void WO_TeleportRequest(void* context, void* frame, void* result)
	{
		RedString text;
		const int size = ReadStringParameter(frame, text);

		AdvanceFrame(frame);

		if (size > 0)
			SendPartyRequest("TPREQ", NarrowPayload(text));
	}

	static void WO_NpcTerminalState(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<int*>(result) = command ? command->terminalState : 0;
	}

	static void WO_NpcTerminalRevision(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<int*>(result) = command ? command->terminalRevision : 0;
	}

	static void WO_NpcTerminalAttacker(void* context, void* frame, void* result)
	{
		const ReplicaCommand* command = CommandAt(frame);

		if (result)
			*static_cast<int*>(result) = command ? command->terminalAttackerId : 0;
	}

	static void WO_NpcTerminalAck(void* context, void* frame, void* result)
	{
		const int canonicalId = ReadIntParameter(frame);
		const int revision = ReadIntParameter(frame);
		AdvanceFrame(frame);

		NpcNet::AckTerminal(canonicalId, revision);
	}

	static void WO_NpcBindId(void* context, void* frame, void* result)
	{
		const int canonicalId = ReadIntParameter(frame);
		const int localGuid = ReadIntParameter(frame);
		const int kind = ReadIntParameter(frame);
		AdvanceFrame(frame);

		NpcNet::BindCanonical(canonicalId, localGuid, kind);
	}

	static void WO_NpcForget(void* context, void* frame, void* result)
	{
		const int canonicalId = ReadIntParameter(frame);
		AdvanceFrame(frame);

		NpcNet::Forget(canonicalId);
	}

	static void WO_NpcDecline(void* context, void* frame, void* result)
	{
		const int canonicalId = ReadIntParameter(frame);
		AdvanceFrame(frame);

		NpcNet::Decline(canonicalId);
	}

	static void WO_NpcTake(void* context, void* frame, void* result)
	{
		const int canonicalId = ReadIntParameter(frame);
		const int localGuid = ReadIntParameter(frame);
		AdvanceFrame(frame);

		NpcNet::Take(canonicalId, localGuid);
	}

	static void WO_NpcUnspawnable(void* context, void* frame, void* result)
	{
		const int canonicalId = ReadIntParameter(frame);
		AdvanceFrame(frame);

		NpcNet::MarkUnspawnable(canonicalId);
	}

	static void WO_NpcSuspend(void* context, void* frame, void* result)
	{
		const bool suspended = ReadBoolParameter(frame);
		AdvanceFrame(frame);

		NpcNet::SetSuspended(suspended);
	}

	static void WO_NpcDropCount(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = NpcNet::PullDrops();
	}

	static void WO_NpcDropGuid(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = NpcNet::Drop(index);
	}

	static void WO_NpcDropsDone(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);
		NpcNet::ClearDrops();
	}

	static void WO_NpcKillCount(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = NpcNet::PullKills();
	}

	static void WO_NpcKillGuid(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = NpcNet::KillGuid(index);
	}

	static void WO_NpcKillsDone(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);
		NpcNet::ClearKills();
	}

	static void WO_NpcReportHit(void* context, void* frame, void* result)
	{
		const int canonicalId = ReadIntParameter(frame);
		const int permille = ReadIntParameter(frame);
		AdvanceFrame(frame);

		const int eventId = NpcNet::ReportHit(canonicalId, permille);

		if (result)
			*static_cast<int*>(result) = eventId;
	}

	static void WO_NpcHitCount(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = NpcNet::PullHits();
	}

	static void WO_NpcHitGuid(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		const InboundHit* hit = NpcNet::Hit(index);

		if (result)
			*static_cast<int*>(result) = hit ? hit->ownerGuid : 0;
	}

	static void WO_NpcHitAttacker(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		const InboundHit* hit = NpcNet::Hit(index);

		if (result)
			*static_cast<int*>(result) = hit ? hit->attackerPlayerId : 0;
	}

	static void WO_NpcHitPermille(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		const InboundHit* hit = NpcNet::Hit(index);

		if (result)
			*static_cast<int*>(result) = hit ? hit->permille : 0;
	}

	static void WO_NpcHitAck(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		const int resultPermille = ReadIntParameter(frame);
		const bool accepted = ReadBoolParameter(frame);
		AdvanceFrame(frame);

		NpcNet::AckHit(index, resultPermille, accepted);
	}

	static void WO_NpcHitsDone(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);
		NpcNet::ClearHits();
	}

	static void WO_NpcAckCount(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = NpcNet::PullAcks();
	}

	static void WO_NpcAckId(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		const HitAck* ack = NpcNet::Ack(index);

		if (result)
			*static_cast<int*>(result) = ack ? ack->canonicalId : 0;
	}

	static void WO_NpcAckHp(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		const HitAck* ack = NpcNet::Ack(index);

		if (result)
			*static_cast<int*>(result) = ack ? ack->resultPermille : -1;
	}

	static void WO_NpcAckOk(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		const HitAck* ack = NpcNet::Ack(index);

		if (result)
			*static_cast<bool*>(result) = ack ? ack->accepted : false;
	}

	static void WO_NpcAcksDone(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);
		NpcNet::ClearAcks();
	}

	static void WO_NpcReport(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);
		WriteStringResult(result, NpcNet::Report());
	}

	static void WO_NpcDropCanonical(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = NpcNet::DropCanonical(index);
	}

	static void WO_NpcKillAttacker(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = NpcNet::KillAttacker(index);
	}

	static void WO_NpcLatency(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = NpcNet::LatencyMs();
	}

	static void WO_NpcScale(void* context, void* frame, void* result)
	{
		const int npcId = ReadIntParameter(frame);
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = NpcNet::ScaleMilli(npcId);
	}

	static void WO_NpcScaleGen(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = NpcNet::ScaleGeneration();
	}

	static void WO_NpcTune(void* context, void* frame, void* result)
	{
		const int interpDelayMs = ReadIntParameter(frame);
		const int snapshotHz = ReadIntParameter(frame);
		AdvanceFrame(frame);

		NpcNet::SetTuning(interpDelayMs, snapshotHz);
	}

	static void WO_Note(void* context, void* frame, void* result)
	{
		RedString text{};
		const int size = ReadStringParameter(frame, text);

		AdvanceFrame(frame);

		if (size > 0)
			Diagnostics::Log("NOTE " + NarrowPayload(text));
	}

	static void WO_FrameReport(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		double avg = 0.0;
		double worst = 0.0;
		unsigned long long samples = 0;
		unsigned long long slow = 0;

		{
			std::lock_guard<std::mutex> lock(g_frameMutex);

			samples = g_frameSamples;
			slow = g_frameOver20;
			worst = g_frameMaxMs;
			avg = samples ? g_frameTotalMs / static_cast<double>(samples) : 0.0;

			g_frameTotalMs = 0.0;
			g_frameMaxMs = 0.0;
			g_frameSamples = 0;
			g_frameOver20 = 0;
		}

		char buffer[192]{};
		sprintf_s(buffer, sizeof(buffer),
			"FRAME samples=%llu avgMs=%.2f fps=%.1f maxMs=%.2f over20ms=%llu",
			samples, avg, avg > 0.0 ? 1000.0 / avg : 0.0, worst, slow);

		Diagnostics::Log(buffer);
	}

	static void WO_Tick(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		RecordFrameInterval();
		g_tickCount.fetch_add(1);
		g_lastScriptTickMs.store(GetTickCount64());

		SaveTransfer::Pump();

		int gather = 0;

		if (g_connected.load())
		{
			const unsigned long long nowMs = GetTickCount64();

			if (g_nextGatherMs == 0 || nowMs >= g_nextGatherMs)
			{
				g_nextGatherMs = nowMs + kGatherIntervalMs;
				gather = kTickSlots[g_tickSlot % kTickSlotCount];
				g_tickSlot++;
			}
		}
		else
		{
			g_nextGatherMs = 0;
		}

		if (result)
			*static_cast<int*>(result) = gather;
	}

	static void WO_Status(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		if (result)
			*static_cast<int*>(result) = g_status.load();
	}

	static void* RegisterNativeUnguarded(const wchar_t* name, void* implementation)
	{
		AllocFn alloc = reinterpret_cast<AllocFn>(ScriptBinding::Api().alloc);
		MemsetFn zero = reinterpret_cast<MemsetFn>(ScriptBinding::Api().memset);
		NamePoolFn pool = reinterpret_cast<NamePoolFn>(ScriptBinding::Api().namePool);
		AddNameFn addName = reinterpret_cast<AddNameFn>(ScriptBinding::Api().addName);
		FunctionCtorFn construct = reinterpret_cast<FunctionCtorFn>(ScriptBinding::Api().functionCtor);
		ScriptSystemFn system = reinterpret_cast<ScriptSystemFn>(ScriptBinding::Api().scriptSystem);

		RegisterGlobalFn registerGlobal = reinterpret_cast<RegisterGlobalFn>(
			g_registerHook.IsInstalled() ? g_registerHook.Trampoline() : ScriptBinding::Api().registerGlobal);

		void* storage = alloc(kFunctionSize, kFunctionAlignment);
		if (!storage)
			return nullptr;

		zero(storage, 0, kFunctionSize);

		void* namePool = pool();
		if (!namePool)
			return nullptr;

		int nameIndex = addName(namePool, name);

		void* function = construct(storage, &nameIndex, implementation);
		if (!function)
			return nullptr;

		void* scriptSystem = system();
		if (!scriptSystem)
			return nullptr;

		registerGlobal(scriptSystem, function);
		return function;
	}

	static bool RegisterNativeGuarded(const wchar_t* name, void* implementation)
	{
		__try
		{
			return RegisterNativeUnguarded(name, implementation) != nullptr;
		}
		__except (EXCEPTION_EXECUTE_HANDLER)
		{
			return false;
		}
	}

	static void RegisterOne(const wchar_t* name, void* implementation, const char* label)
	{
		if (RegisterNativeGuarded(name, implementation))
			g_registeredCount.fetch_add(1);
		else
			g_registrationError += std::string(" ") + label;
	}

	static void RegisterOurNatives()
	{
		if (g_registrationDone.exchange(true))
			return;

		if (!ScriptBinding::Api().CanMarshalStrings())
		{
			g_registrationError = "string marshalling unavailable";
			Diagnostics::Log("ScriptBinding: " + g_registrationError);
			return;
		}

		RegisterOne(L"WO_Send", reinterpret_cast<void*>(&WO_Send), "WO_Send");
		RegisterOne(L"WO_Poll", reinterpret_cast<void*>(&WO_Poll), "WO_Poll");
		RegisterOne(L"WO_Opcode", reinterpret_cast<void*>(&WO_Opcode), "WO_Opcode");
		RegisterOne(L"WO_PlayerId", reinterpret_cast<void*>(&WO_PlayerId), "WO_PlayerId");
		RegisterOne(L"WO_Sequence", reinterpret_cast<void*>(&WO_Sequence), "WO_Sequence");
		RegisterOne(L"WO_FieldCount", reinterpret_cast<void*>(&WO_FieldCount), "WO_FieldCount");
		RegisterOne(L"WO_Sender", reinterpret_cast<void*>(&WO_Sender), "WO_Sender");
		RegisterOne(L"WO_SenderName", reinterpret_cast<void*>(&WO_SenderName), "WO_SenderName");
		RegisterOne(L"WO_NameAt", reinterpret_cast<void*>(&WO_NameAt), "WO_NameAt");
		RegisterOne(L"WO_Str", reinterpret_cast<void*>(&WO_Str), "WO_Str");
		RegisterOne(L"WO_Int", reinterpret_cast<void*>(&WO_Int), "WO_Int");
		RegisterOne(L"WO_Float", reinterpret_cast<void*>(&WO_Float), "WO_Float");
		RegisterOne(L"WO_Bool", reinterpret_cast<void*>(&WO_Bool), "WO_Bool");
		RegisterOne(L"WO_LocalId", reinterpret_cast<void*>(&WO_LocalId), "WO_LocalId");
		RegisterOne(L"WO_Username", reinterpret_cast<void*>(&WO_Username), "WO_Username");
		RegisterOne(L"WO_Connected", reinterpret_cast<void*>(&WO_Connected), "WO_Connected");
		RegisterOne(L"WO_Status", reinterpret_cast<void*>(&WO_Status), "WO_Status");
		RegisterOne(L"WO_Tick", reinterpret_cast<void*>(&WO_Tick), "WO_Tick");
		RegisterOne(L"WO_IsPlayerPaused", reinterpret_cast<void*>(&WO_IsPlayerPaused), "WO_IsPlayerPaused");
		RegisterOne(L"WO_SetPaused", reinterpret_cast<void*>(&WO_SetPaused), "WO_SetPaused");
		RegisterOne(L"WO_ResetDeltas", reinterpret_cast<void*>(&WO_ResetDeltas), "WO_ResetDeltas");
		RegisterOne(L"WO_NpcSyncMode", reinterpret_cast<void*>(&WO_NpcSyncMode), "WO_NpcSyncMode");
		RegisterOne(L"WO_PartyScaleConfig", reinterpret_cast<void*>(&WO_PartyScaleConfig), "WO_PartyScaleConfig");
		RegisterOne(L"WO_PartyScaleAckStep", reinterpret_cast<void*>(&WO_PartyScaleAckStep), "WO_PartyScaleAckStep");
		RegisterOne(L"WO_PartyScaleAckMax", reinterpret_cast<void*>(&WO_PartyScaleAckMax), "WO_PartyScaleAckMax");
		RegisterOne(L"WO_PartyJoin", reinterpret_cast<void*>(&WO_PartyJoin), "WO_PartyJoin");
		RegisterOne(L"WO_TeleportRequest", reinterpret_cast<void*>(&WO_TeleportRequest), "WO_TeleportRequest");
		RegisterOne(L"WO_PartyLeave", reinterpret_cast<void*>(&WO_PartyLeave), "WO_PartyLeave");
		RegisterOne(L"WO_PartyRespond", reinterpret_cast<void*>(&WO_PartyRespond), "WO_PartyRespond");
		RegisterOne(L"WO_PartyCoopMode", reinterpret_cast<void*>(&WO_PartyCoopMode), "WO_PartyCoopMode");
		RegisterOne(L"WO_ToName", reinterpret_cast<void*>(&WO_ToName), "WO_ToName");
		RegisterOne(L"WO_SaveSend", reinterpret_cast<void*>(&WO_SaveSend), "WO_SaveSend");
		RegisterOne(L"WO_SaveWant", reinterpret_cast<void*>(&WO_SaveWant), "WO_SaveWant");
		RegisterOne(L"WO_SaveDirectory", reinterpret_cast<void*>(&WO_SaveDirectory), "WO_SaveDirectory");
		RegisterOne(L"WO_CoopMarkRestore", reinterpret_cast<void*>(&WO_CoopMarkRestore), "WO_CoopMarkRestore");
		RegisterOne(L"WO_CoopStashChar", reinterpret_cast<void*>(&WO_CoopStashChar), "WO_CoopStashChar");
		RegisterOne(L"WO_CoopFetchChar", reinterpret_cast<void*>(&WO_CoopFetchChar), "WO_CoopFetchChar");
		RegisterOne(L"WO_CoopTakeRestore", reinterpret_cast<void*>(&WO_CoopTakeRestore), "WO_CoopTakeRestore");
		RegisterOne(L"WO_SaveNeededBy", reinterpret_cast<void*>(&WO_SaveNeededBy), "WO_SaveNeededBy");
		RegisterOne(L"WO_SaveIncomingDir", reinterpret_cast<void*>(&WO_SaveIncomingDir), "WO_SaveIncomingDir");
		RegisterOne(L"WO_SaveState", reinterpret_cast<void*>(&WO_SaveState), "WO_SaveState");
		RegisterOne(L"WO_SaveProgress", reinterpret_cast<void*>(&WO_SaveProgress), "WO_SaveProgress");
		RegisterOne(L"WO_SaveError", reinterpret_cast<void*>(&WO_SaveError), "WO_SaveError");
		RegisterOne(L"WO_SaveFile", reinterpret_cast<void*>(&WO_SaveFile), "WO_SaveFile");
		RegisterOne(L"WO_SaveReset", reinterpret_cast<void*>(&WO_SaveReset), "WO_SaveReset");
		RegisterOne(L"WO_SceneStart", reinterpret_cast<void*>(&WO_SceneStart), "WO_SceneStart");
		RegisterOne(L"WO_QuestItem", reinterpret_cast<void*>(&WO_QuestItem), "WO_QuestItem");
		RegisterOne(L"WO_SavePurge", reinterpret_cast<void*>(&WO_SavePurge), "WO_SavePurge");
		RegisterOne(L"WO_SaveLeftovers", reinterpret_cast<void*>(&WO_SaveLeftovers), "WO_SaveLeftovers");
		RegisterOne(L"WO_CharStore", reinterpret_cast<void*>(&WO_CharStore), "WO_CharStore");
		RegisterOne(L"WO_CharFetch", reinterpret_cast<void*>(&WO_CharFetch), "WO_CharFetch");
		RegisterOne(L"WO_EntityProbe", reinterpret_cast<void*>(&WO_EntityProbe), "WO_EntityProbe");
		RegisterOne(L"WO_EntityTemplatePath", reinterpret_cast<void*>(&WO_EntityTemplatePath), "WO_EntityTemplatePath");
		RegisterOne(L"WO_FrameReport", reinterpret_cast<void*>(&WO_FrameReport), "WO_FrameReport");
		RegisterOne(L"WO_Note", reinterpret_cast<void*>(&WO_Note), "WO_Note");
		RegisterOne(L"WO_Match", reinterpret_cast<void*>(&WO_Match), "WO_Match");
		RegisterOne(L"WO_MatchOp", reinterpret_cast<void*>(&WO_MatchOp), "WO_MatchOp");
		RegisterOne(L"WO_MatchA", reinterpret_cast<void*>(&WO_MatchA), "WO_MatchA");
		RegisterOne(L"WO_MatchB", reinterpret_cast<void*>(&WO_MatchB), "WO_MatchB");
		RegisterOne(L"WO_NpcBeginOwned", reinterpret_cast<void*>(&WO_NpcBeginOwned), "WO_NpcBeginOwned");
		RegisterOne(L"WO_NpcPushOwned", reinterpret_cast<void*>(&WO_NpcPushOwned), "WO_NpcPushOwned");
		RegisterOne(L"WO_NpcEndOwned", reinterpret_cast<void*>(&WO_NpcEndOwned), "WO_NpcEndOwned");
		RegisterOne(L"WO_NpcPull", reinterpret_cast<void*>(&WO_NpcPull), "WO_NpcPull");
		RegisterOne(L"WO_NpcId", reinterpret_cast<void*>(&WO_NpcId), "WO_NpcId");
		RegisterOne(L"WO_NpcOp", reinterpret_cast<void*>(&WO_NpcOp), "WO_NpcOp");
		RegisterOne(L"WO_NpcGuid", reinterpret_cast<void*>(&WO_NpcGuid), "WO_NpcGuid");
		RegisterOne(L"WO_NpcX", reinterpret_cast<void*>(&WO_NpcX), "WO_NpcX");
		RegisterOne(L"WO_NpcY", reinterpret_cast<void*>(&WO_NpcY), "WO_NpcY");
		RegisterOne(L"WO_NpcZ", reinterpret_cast<void*>(&WO_NpcZ), "WO_NpcZ");
		RegisterOne(L"WO_NpcHeading", reinterpret_cast<void*>(&WO_NpcHeading), "WO_NpcHeading");
		RegisterOne(L"WO_NpcSpeed", reinterpret_cast<void*>(&WO_NpcSpeed), "WO_NpcSpeed");
		RegisterOne(L"WO_NpcHp", reinterpret_cast<void*>(&WO_NpcHp), "WO_NpcHp");
		RegisterOne(L"WO_NpcFlags", reinterpret_cast<void*>(&WO_NpcFlags), "WO_NpcFlags");
		RegisterOne(L"WO_NpcTarget", reinterpret_cast<void*>(&WO_NpcTarget), "WO_NpcTarget");
		RegisterOne(L"WO_NpcTerminalState", reinterpret_cast<void*>(&WO_NpcTerminalState), "WO_NpcTerminalState");
		RegisterOne(L"WO_NpcTerminalRevision", reinterpret_cast<void*>(&WO_NpcTerminalRevision), "WO_NpcTerminalRevision");
		RegisterOne(L"WO_NpcTerminalAttacker", reinterpret_cast<void*>(&WO_NpcTerminalAttacker), "WO_NpcTerminalAttacker");
		RegisterOne(L"WO_NpcTerminalAck", reinterpret_cast<void*>(&WO_NpcTerminalAck), "WO_NpcTerminalAck");
		RegisterOne(L"WO_NpcType", reinterpret_cast<void*>(&WO_NpcType), "WO_NpcType");
		RegisterOne(L"WO_NpcAppearance", reinterpret_cast<void*>(&WO_NpcAppearance), "WO_NpcAppearance");
		RegisterOne(L"WO_NpcBind", reinterpret_cast<void*>(&WO_NpcBind), "WO_NpcBind");
		RegisterOne(L"WO_NpcBindId", reinterpret_cast<void*>(&WO_NpcBindId), "WO_NpcBindId");
		RegisterOne(L"WO_NpcForget", reinterpret_cast<void*>(&WO_NpcForget), "WO_NpcForget");
		RegisterOne(L"WO_NpcDecline", reinterpret_cast<void*>(&WO_NpcDecline), "WO_NpcDecline");
		RegisterOne(L"WO_NpcTake", reinterpret_cast<void*>(&WO_NpcTake), "WO_NpcTake");
		RegisterOne(L"WO_NpcUnspawnable", reinterpret_cast<void*>(&WO_NpcUnspawnable), "WO_NpcUnspawnable");
		RegisterOne(L"WO_NpcSuspend", reinterpret_cast<void*>(&WO_NpcSuspend), "WO_NpcSuspend");
		RegisterOne(L"WO_NpcDropCount", reinterpret_cast<void*>(&WO_NpcDropCount), "WO_NpcDropCount");
		RegisterOne(L"WO_NpcDropGuid", reinterpret_cast<void*>(&WO_NpcDropGuid), "WO_NpcDropGuid");
		RegisterOne(L"WO_NpcDropCanonical", reinterpret_cast<void*>(&WO_NpcDropCanonical), "WO_NpcDropCanonical");
		RegisterOne(L"WO_NpcDropsDone", reinterpret_cast<void*>(&WO_NpcDropsDone), "WO_NpcDropsDone");
		RegisterOne(L"WO_NpcKillCount", reinterpret_cast<void*>(&WO_NpcKillCount), "WO_NpcKillCount");
		RegisterOne(L"WO_NpcKillGuid", reinterpret_cast<void*>(&WO_NpcKillGuid), "WO_NpcKillGuid");
		RegisterOne(L"WO_NpcKillAttacker", reinterpret_cast<void*>(&WO_NpcKillAttacker), "WO_NpcKillAttacker");
		RegisterOne(L"WO_NpcKillsDone", reinterpret_cast<void*>(&WO_NpcKillsDone), "WO_NpcKillsDone");
		RegisterOne(L"WO_NpcReportHit", reinterpret_cast<void*>(&WO_NpcReportHit), "WO_NpcReportHit");
		RegisterOne(L"WO_NpcHitCount", reinterpret_cast<void*>(&WO_NpcHitCount), "WO_NpcHitCount");
		RegisterOne(L"WO_NpcHitGuid", reinterpret_cast<void*>(&WO_NpcHitGuid), "WO_NpcHitGuid");
		RegisterOne(L"WO_NpcHitAttacker", reinterpret_cast<void*>(&WO_NpcHitAttacker), "WO_NpcHitAttacker");
		RegisterOne(L"WO_NpcHitPermille", reinterpret_cast<void*>(&WO_NpcHitPermille), "WO_NpcHitPermille");
		RegisterOne(L"WO_NpcHitAck", reinterpret_cast<void*>(&WO_NpcHitAck), "WO_NpcHitAck");
		RegisterOne(L"WO_NpcHitsDone", reinterpret_cast<void*>(&WO_NpcHitsDone), "WO_NpcHitsDone");
		RegisterOne(L"WO_NpcAckCount", reinterpret_cast<void*>(&WO_NpcAckCount), "WO_NpcAckCount");
		RegisterOne(L"WO_NpcAckId", reinterpret_cast<void*>(&WO_NpcAckId), "WO_NpcAckId");
		RegisterOne(L"WO_NpcAckHp", reinterpret_cast<void*>(&WO_NpcAckHp), "WO_NpcAckHp");
		RegisterOne(L"WO_NpcAckOk", reinterpret_cast<void*>(&WO_NpcAckOk), "WO_NpcAckOk");
		RegisterOne(L"WO_NpcAcksDone", reinterpret_cast<void*>(&WO_NpcAcksDone), "WO_NpcAcksDone");
		RegisterOne(L"WO_NpcReport", reinterpret_cast<void*>(&WO_NpcReport), "WO_NpcReport");
		RegisterOne(L"WO_NpcLatency", reinterpret_cast<void*>(&WO_NpcLatency), "WO_NpcLatency");
		RegisterOne(L"WO_NpcTune", reinterpret_cast<void*>(&WO_NpcTune), "WO_NpcTune");
		RegisterOne(L"WO_NpcScale", reinterpret_cast<void*>(&WO_NpcScale), "WO_NpcScale");
		RegisterOne(L"WO_NpcScaleGen", reinterpret_cast<void*>(&WO_NpcScaleGen), "WO_NpcScaleGen");

		Diagnostics::Log("ScriptBinding: registered=" + std::to_string(g_registeredCount.load())
			+ (g_registrationError.empty() ? "" : " failed:" + g_registrationError));
	}

	static void RegisterGlobalDetour(void* system, void* function)
	{
		RegisterGlobalFn original = reinterpret_cast<RegisterGlobalFn>(g_registerHook.Trampoline());
		original(system, function);

		if (!g_registrationDone.load())
			RegisterOurNatives();
	}

	bool ScriptBinding::InstallRegistrationHook()
	{
		if (!resolved_)
			return false;

		if (g_registerHook.IsInstalled())
			return true;

		const std::vector<uint8_t> prologue = {
			0x48, 0x89, 0x6C, 0x24, 0x18,
			0x48, 0x89, 0x74, 0x24, 0x20,
			0x57,
			0x48, 0x83, 0xEC, 0x20
		};

		if (!g_registerHook.Install(api_.registerGlobal, reinterpret_cast<void*>(&RegisterGlobalDetour), prologue))
		{
			g_registrationError = g_registerHook.Error();
			return false;
		}

		return true;
	}

	void ScriptBinding::RemoveRegistrationHook()
	{
		g_registerHook.Remove();
	}

	bool ScriptBinding::IsHooked()
	{
		return g_registerHook.IsInstalled();
	}

	bool ScriptBinding::NativesRegistered()
	{
		return g_registeredCount.load() > 0;
	}

	unsigned long long ScriptBinding::LastScriptTickMs()
	{
		return g_lastScriptTickMs.load();
	}

	bool ScriptBinding::ScriptPaused()
	{
		return g_scriptPaused.load();
	}

	std::string ScriptBinding::RegistrationReport()
	{
		std::string report = "ScriptBinding registration: hooked=";
		report += g_registerHook.IsInstalled() ? "yes" : "no";
		report += " fired=";
		report += g_registrationDone.load() ? "yes" : "no";
		report += " registered=" + std::to_string(g_registeredCount.load());

		if (!g_registrationError.empty())
			report += " failed:" + g_registrationError;

		return report;
	}

	static std::string Hex(void* value)
	{
		if (!value)
			return "null";

		char buffer[32]{};
		sprintf_s(buffer, sizeof(buffer), "0x%llX", static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(value)));
		return std::string(buffer);
	}

	std::string ScriptBinding::Report()
	{
		std::string report = "ScriptBinding: resolved=";
		report += resolved_ ? "yes" : "no";
		report += " matches=" + std::to_string(api_.matches);
		report += " consistent=" + std::to_string(api_.consistent);
		report += "\n  alloc=" + Hex(api_.alloc);
		report += " memset=" + Hex(api_.memset);
		report += " namePool=" + Hex(api_.namePool);
		report += "\n  addName=" + Hex(api_.addName);
		report += " functionCtor=" + Hex(api_.functionCtor);
		report += "\n  scriptSystem=" + Hex(api_.scriptSystem);
		report += " registerGlobal=" + Hex(api_.registerGlobal);
		report += "\n  natives=" + std::to_string(g_natives.size());
		report += " opcodeTable=" + Hex(api_.opcodeTable);
		report += " marshalStrings=";
		report += api_.CanMarshalStrings() ? "yes" : "no";

		return report;
	}

}
