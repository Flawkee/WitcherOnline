#pragma once
#include <string>

namespace w3mp {

	enum class RejectReason
	{
		Update1Arity,
		Update2Arity,
		Update3Arity,
		Update4Arity,
		MovementArity,
		InvalidUsername,
		StaleSequence
	};

	class Diagnostics {
	public:
		static void Init(const std::string& path);
		static void Shutdown();

		static void Log(const std::string& message);
		static void CountAccepted();
		static void CountRejected(RejectReason reason);
		static void FlushSummary(bool force);

	private:
		static std::string Timestamp();
		static const char* ReasonName(RejectReason reason);
	};

}
