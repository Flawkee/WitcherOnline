#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace w3mp {

	enum class InboundOpcode
	{
		None = 0,
		Move = 1,
		Update1 = 2,
		Update2 = 3,
		Update3 = 4,
		Update4 = 5,
		Pose = 6,
		NpcOwn = 7,
		NpcNew = 8,
		NpcMove = 9,
		NpcEnd = 10,
		NpcHit = 11
	};

	enum class ClientStatus
	{
		Ok = 0,
		UsernameTaken = 1,
		Kicked = 2,
		Banned = 3,
		NotWhitelisted = 4
	};

	struct InboundMessage
	{
		InboundOpcode opcode = InboundOpcode::None;
		int playerId = 0;
		int sequence = 0;
		std::string sender;
		std::vector<std::string> fields;
	};

	struct ScriptApi
	{
		void* alloc = nullptr;
		void* memset = nullptr;
		void* namePool = nullptr;
		void* addName = nullptr;
		void* functionCtor = nullptr;
		void* scriptSystem = nullptr;
		void* registerGlobal = nullptr;

		void* opcodeTable = nullptr;
		void* bufferAlloc = nullptr;
		void* bufferCopy = nullptr;
		const wchar_t** emptyString = nullptr;
		const int* emptyStringLength = nullptr;

		size_t matches = 0;
		size_t consistent = 0;

		bool IsComplete() const
		{
			return alloc && memset && namePool && addName && functionCtor && scriptSystem && registerGlobal;
		}

		bool CanMarshalStrings() const
		{
			return opcodeTable && bufferAlloc && bufferCopy && emptyString && emptyStringLength;
		}
	};

	class ScriptBinding {
	public:
		static bool Resolve();
		static bool InstallRegistrationHook();
		static void RemoveRegistrationHook();

		static void* FindNative(const std::string& name);
		static const ScriptApi& Api() { return api_; }
		static bool IsResolved() { return resolved_; }
		static bool IsHooked();
		static bool NativesRegistered();
		static std::string Report();
		static std::string RegistrationReport();
	static unsigned long long LastScriptTickMs();
	static bool ScriptPaused();

		static bool PopOutbound(std::string& payload);
		static void PushInbound(InboundMessage&& message);
		static size_t InboundDepth();

		static void SetLocalId(int id);
		static void SetUsername(const std::string& name);
		static void SetConnected(bool connected);
		static void SetStatus(ClientStatus status);
		static std::string TransportReport();
		static void CountSuppressed();
		static void CountDowngraded();
		static void CountFieldChange(int index);
		static std::string ChurnReport();
		static std::string AccessorReport();
		static void ReportIdle();

	private:
		static ScriptApi api_;
		static bool resolved_;
		static bool attempted_;
	};

}
