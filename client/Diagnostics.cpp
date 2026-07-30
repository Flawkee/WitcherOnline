#include "pch.h"
#include "Diagnostics.h"
#include <windows.h>
#include <atomic>
#include <fstream>
#include <mutex>

namespace w3mp {

	static const size_t kReasonCount = 7;
	static const unsigned long long kSummaryIntervalMs = 15000;

	static std::mutex g_logMutex;
	static std::ofstream g_logFile;
	static bool g_enabled = false;

	static std::atomic<unsigned long long> g_accepted{ 0 };
	static std::atomic<unsigned long long> g_rejected[kReasonCount];
	static unsigned long long g_lastSummaryMs = 0;
	static unsigned long long g_lastReportedAccepted = 0;

	static long long QueryFrequency()
	{
		static long long frequency = 0;

		if (frequency == 0)
		{
			LARGE_INTEGER value{};
			QueryPerformanceFrequency(&value);
			frequency = value.QuadPart;
		}

		return frequency;
	}

	long long Profiler::Now()
	{
		LARGE_INTEGER value{};
		QueryPerformanceCounter(&value);
		return value.QuadPart;
	}

	double Profiler::MicrosSince(long long start)
	{
		const long long frequency = QueryFrequency();

		if (frequency == 0)
			return 0.0;

		return static_cast<double>(Now() - start) * 1000000.0 / static_cast<double>(frequency);
	}

	const char* Diagnostics::ReasonName(RejectReason reason)
	{
		switch (reason)
		{
		case RejectReason::Update1Arity:    return "update1Arity";
		case RejectReason::Update2Arity:    return "update2Arity";
		case RejectReason::Update3Arity:    return "update3Arity";
		case RejectReason::Update4Arity:    return "update4Arity";
		case RejectReason::MovementArity:   return "movementArity";
		case RejectReason::InvalidUsername: return "invalidUsername";
		case RejectReason::StaleSequence:   return "staleSequence";
		default:                            return "unknown";
		}
	}

	std::string Diagnostics::Timestamp()
	{
		SYSTEMTIME now{};
		GetLocalTime(&now);

		char buffer[32]{};
		sprintf_s(buffer, sizeof(buffer), "%02u:%02u:%02u.%03u",
			now.wHour, now.wMinute, now.wSecond, now.wMilliseconds);

		return std::string(buffer);
	}

	void Diagnostics::Init(const std::string& path)
	{
		std::lock_guard<std::mutex> lock(g_logMutex);

		try
		{
			const std::string previous = path + ".prev";
			DeleteFileA(previous.c_str());
			MoveFileA(path.c_str(), previous.c_str());
		}
		catch (...)
		{
		}

		g_logFile.open(path, std::ios::out | std::ios::trunc);
		g_enabled = g_logFile.is_open();
		g_lastSummaryMs = GetTickCount64();
	}

	void Diagnostics::Shutdown()
	{
		std::lock_guard<std::mutex> lock(g_logMutex);

		if (g_enabled)
		{
			g_logFile.flush();
			g_logFile.close();
		}

		g_enabled = false;
	}

	void Diagnostics::Log(const std::string& message)
	{
		std::lock_guard<std::mutex> lock(g_logMutex);

		if (!g_enabled)
			return;

		g_logFile << Timestamp() << " " << message << "\n";
		g_logFile.flush();
	}

	void Diagnostics::CountAccepted()
	{
		g_accepted.fetch_add(1);
	}

	void Diagnostics::CountRejected(RejectReason reason)
	{
		const size_t index = static_cast<size_t>(reason);

		if (index < kReasonCount)
			g_rejected[index].fetch_add(1);
	}

	void Diagnostics::FlushSummary(bool force)
	{
		const unsigned long long now = GetTickCount64();

		if (!force && (now - g_lastSummaryMs) < kSummaryIntervalMs)
			return;

		g_lastSummaryMs = now;

		unsigned long long total = 0;
		std::string detail;

		for (size_t i = 0; i < kReasonCount; ++i)
		{
			const unsigned long long value = g_rejected[i].load();
			total += value;

			if (value > 0)
			{
				detail += " ";
				detail += ReasonName(static_cast<RejectReason>(i));
				detail += "=";
				detail += std::to_string(value);
			}
		}

		const unsigned long long accepted = g_accepted.load();

		if (accepted == g_lastReportedAccepted && total == 0)
			return;

		g_lastReportedAccepted = accepted;

		Log("stats accepted=" + std::to_string(accepted) + " rejected=" + std::to_string(total) + detail);
	}

}
