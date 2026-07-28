#include "pch.h"
#include "ScriptBinding.h"
#include "Diagnostics.h"
#include "GameModule.h"
#include "InlineHook.h"
#include "SignatureScanner.h"
#include <algorithm>
#include <atomic>
#include <deque>
#include <map>
#include <mutex>
#include <vector>

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
	static const size_t kMaxQueueDepth = 1024;

	static InlineHook g_registerHook;
	static std::atomic<bool> g_registrationDone{ false };
	static std::atomic<int> g_registeredCount{ 0 };
	static std::string g_registrationError;

	static std::mutex g_queueMutex;
	static std::deque<std::string> g_outbound;
	static std::deque<InboundMessage> g_inbound;
	static InboundMessage g_current;

	static std::mutex g_stateMutex;
	static int g_localId = 0;
	static std::string g_username;
	static std::atomic<bool> g_connected{ false };
	static std::atomic<int> g_status{ 0 };

	static std::atomic<unsigned long long> g_pollCount{ 0 };
	static std::atomic<unsigned long long> g_tickCount{ 0 };
	static std::atomic<unsigned long long> g_sendCount{ 0 };
	static std::atomic<unsigned long long> g_recvCount{ 0 };
	static std::atomic<unsigned long long> g_dropCount{ 0 };

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

		if (g_outbound.empty())
			return false;

		payload = std::move(g_outbound.front());
		g_outbound.pop_front();
		return true;
	}

	void ScriptBinding::PushInbound(InboundMessage&& message)
	{
		std::lock_guard<std::mutex> lock(g_queueMutex);

		if (g_inbound.size() >= kMaxQueueDepth)
		{
			g_inbound.pop_front();
			g_dropCount.fetch_add(1);
		}

		g_inbound.push_back(std::move(message));
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
	}

	void ScriptBinding::SetStatus(ClientStatus status)
	{
		g_status.store(static_cast<int>(status));
	}

	static std::atomic<unsigned long long> g_deltaSuppressed{ 0 };
	static std::atomic<unsigned long long> g_deltaDowngraded{ 0 };

	static std::mutex g_churnMutex;
	static std::map<int, unsigned long long> g_fieldChurn;

	void ScriptBinding::CountFieldChange(int index)
	{
		std::lock_guard<std::mutex> lock(g_churnMutex);
		g_fieldChurn[index]++;
	}

	std::string ScriptBinding::ChurnReport()
	{
		std::vector<std::pair<unsigned long long, int>> ranked;

		{
			std::lock_guard<std::mutex> lock(g_churnMutex);

			for (const auto& entry : g_fieldChurn)
				ranked.push_back({ entry.second, entry.first });
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

		return "transport sent=" + std::to_string(g_sendCount.load())
			+ " recv=" + std::to_string(applied)
			+ " dropped=" + std::to_string(g_dropCount.load())
			+ " queued=" + std::to_string(InboundDepth())
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

				if (g_outbound.size() >= kMaxQueueDepth)
				{
					g_outbound.pop_front();
					g_dropCount.fetch_add(1);
				}

				g_outbound.push_back(std::move(payload));
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

	static const std::string& CurrentField(int index)
	{
		static const std::string empty;

		if (index < 0 || static_cast<size_t>(index) >= g_current.fields.size())
			return empty;

		return g_current.fields[static_cast<size_t>(index)];
	}

	static void WO_Str(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);
		WriteStringResult(result, CurrentField(index));
	}

	static void WO_NameAt(void* context, void* frame, void* result)
	{
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);
		WriteNameResult(result, CurrentField(index));
	}

	static void WO_Int(void* context, void* frame, void* result)
	{
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
		const int index = ReadIntParameter(frame);
		AdvanceFrame(frame);

		const std::string& text = CurrentField(index);
		const bool value = (text == "true" || text == "1");

		if (result)
			*static_cast<bool*>(result) = value;
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

	static void WO_Tick(void* context, void* frame, void* result)
	{
		AdvanceFrame(frame);

		g_tickCount.fetch_add(1);

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
