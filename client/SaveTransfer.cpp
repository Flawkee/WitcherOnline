#include "pch.h"
#include "SaveTransfer.h"
#include "Diagnostics.h"

#include <windows.h>
#include <bcrypt.h>
#include <atomic>
#include <fstream>
#include <sstream>
#include <mutex>
#include <unordered_map>

#pragma comment(lib, "bcrypt.lib")

extern void SendPartyRequest2(const char* opcode, const std::string& first, const std::string& second);
extern void SendSaveChunk(const char* opcode, const std::vector<std::string>& fields);

namespace w3mp {

	namespace {

		const size_t kChunkBytes = 700;
		const int kMaxChunksPerPump = 64;
		const unsigned long long kNackIntervalMs = 1500;
		const unsigned long long kStallTimeoutMs = 45000;
		const unsigned long long kReceiveHardLimitMs = 90000;
		const size_t kNackBatch = 200;

		const double kRateStart = 40.0;
		const double kRateMin = 20.0;
		const double kRateMax = 300.0;
		const double kRateStep = 10.0;
		const unsigned long long kRateStepMs = 1000;

		const unsigned long long kMaxSaveBytes = 32ull * 1024ull * 1024ull;
		const char kSaveMagic[] = "SNFHFZLC";
		const size_t kSaveMagicLen = 8;

		const char kB64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

		std::mutex g_mutex;

		SaveTransfer::State g_state = SaveTransfer::State::Idle;
		std::string g_error;
		std::string g_incomingDir;
		std::string g_completedFile;

		int g_transferId = 0;

		// sender
		std::vector<std::string> g_encodedChunks;
		size_t g_sendCursor = 0;
		std::vector<int> g_resendQueue;
		unsigned long long g_lastActivityMs = 0;
		unsigned long long g_lastSendMs = 0;
		unsigned long long g_lastRateStepMs = 0;
		double g_sendCredit = 0.0;
		double g_sendRate = kRateStart;
		bool g_sentEnd = false;

		// receiver
		std::vector<std::string> g_received;
		std::vector<bool> g_have;
		int g_expectedChunks = 0;
		unsigned long long g_expectedBytes = 0;
		std::string g_expectedHash;
		bool g_requested = false;
		int g_haveCount = 0;
		unsigned long long g_lastNackMs = 0;
		unsigned long long g_lastChunkMs = 0;
		unsigned long long g_recvBeganMs = 0;
		bool g_endSeen = false;

		std::string g_pendingRequest;

		std::string Sha256Hex(const unsigned char* data, size_t len)
		{
			BCRYPT_ALG_HANDLE alg = NULL;

			if (!BCRYPT_SUCCESS(BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, NULL, 0)))
				return std::string();

			unsigned char digest[32] = { 0 };

			const NTSTATUS status = BCryptHash(alg, NULL, 0,
				const_cast<PUCHAR>(data), static_cast<ULONG>(len), digest, sizeof(digest));

			BCryptCloseAlgorithmProvider(alg, 0);

			if (!BCRYPT_SUCCESS(status))
				return std::string();

			static const char* hex = "0123456789abcdef";
			std::string out;
			out.reserve(64);

			for (size_t i = 0; i < sizeof(digest); ++i)
			{
				out.push_back(hex[(digest[i] >> 4) & 0x0F]);
				out.push_back(hex[digest[i] & 0x0F]);
			}

			return out;
		}

		bool HasSaveMagic(const std::string& body)
		{
			return body.size() > kSaveMagicLen
				&& memcmp(body.data(), kSaveMagic, kSaveMagicLen) == 0;
		}

		std::string EncodeB64(const unsigned char* data, size_t len)
		{
			std::string out;
			out.reserve(((len + 2) / 3) * 4);

			for (size_t i = 0; i < len; i += 3)
			{
				const unsigned int b0 = data[i];
				const unsigned int b1 = (i + 1 < len) ? data[i + 1] : 0u;
				const unsigned int b2 = (i + 2 < len) ? data[i + 2] : 0u;
				const unsigned int triple = (b0 << 16) | (b1 << 8) | b2;

				out.push_back(kB64[(triple >> 18) & 0x3F]);
				out.push_back(kB64[(triple >> 12) & 0x3F]);
				out.push_back((i + 1 < len) ? kB64[(triple >> 6) & 0x3F] : '=');
				out.push_back((i + 2 < len) ? kB64[triple & 0x3F] : '=');
			}

			return out;
		}

		std::string DecodeB64(const std::string& text)
		{
			int lookup[256];

			for (int i = 0; i < 256; ++i)
				lookup[i] = -1;

			for (int i = 0; i < 64; ++i)
				lookup[static_cast<unsigned char>(kB64[i])] = i;

			std::string out;
			out.reserve((text.size() / 4) * 3);

			unsigned int accum = 0;
			int bits = 0;

			for (size_t i = 0; i < text.size(); ++i)
			{
				const int v = lookup[static_cast<unsigned char>(text[i])];

				if (v < 0)
					continue;

				accum = (accum << 6) | static_cast<unsigned int>(v);
				bits += 6;

				if (bits >= 8)
				{
					bits -= 8;
					out.push_back(static_cast<char>((accum >> bits) & 0xFF));
				}
			}

			return out;
		}

		void Fail(const std::string& why)
		{
			g_state = SaveTransfer::State::Failed;
			g_error = why;
			Diagnostics::Log("save transfer failed: " + why);
		}

		void ClearLocked()
		{
			g_encodedChunks.clear();
			g_resendQueue.clear();
			g_received.clear();
			g_have.clear();
			g_sendCursor = 0;
			g_lastSendMs = 0;
			g_sendCredit = 0.0;
			g_expectedChunks = 0;
			g_expectedBytes = 0;
			g_expectedHash.clear();
			g_haveCount = 0;
			g_lastChunkMs = 0;
			g_endSeen = false;
			g_sentEnd = false;
			g_recvBeganMs = 0;
			g_lastRateStepMs = 0;
			g_sendRate = kRateStart;
		}

		void SendChunk(int index)
		{
			if (index < 0 || static_cast<size_t>(index) >= g_encodedChunks.size())
				return;

			std::vector<std::string> fields;
			fields.push_back(std::to_string(g_transferId));
			fields.push_back(std::to_string(index));
			fields.push_back(g_encodedChunks[index]);

			SendSaveChunk("SAVECHK", fields);
		}
	}

	void SaveTransfer::RequestSave()
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		ClearLocked();
		g_state = State::Idle;
		g_error.clear();
		g_completedFile.clear();
		g_requested = true;

		SendPartyRequest2("SAVEWANT", "1", "1");

		Diagnostics::Log("save transfer requested from leader");
	}

	std::string SaveTransfer::TakePendingRequest()
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		const std::string who = g_pendingRequest;
		g_pendingRequest.clear();

		return who;
	}

	bool SaveTransfer::BeginSend(const std::string& filePath)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (g_state == State::Sending)
		{
			Diagnostics::Log("save transfer already uploading, request coalesced");
			return true;
		}

		ClearLocked();

		if (filePath.find("..") != std::string::npos)
		{
			Fail("rejected path with traversal: " + filePath);
			return false;
		}

		if (!g_incomingDir.empty())
		{
			std::string head = filePath.substr(0, g_incomingDir.size());
			std::string want = g_incomingDir;

			for (char& c : head)
				c = static_cast<char>(tolower(static_cast<unsigned char>(c)));

			for (char& c : want)
				c = static_cast<char>(tolower(static_cast<unsigned char>(c)));

			if (head != want)
			{
				Fail("rejected path outside save directory: " + filePath);
				return false;
			}
		}

		std::ifstream in(filePath, std::ios::in | std::ios::binary);

		if (!in.is_open())
		{
			Fail("cannot open " + filePath);
			return false;
		}

		std::ostringstream buffer;
		buffer << in.rdbuf();
		in.close();

		const std::string body = buffer.str();

		if (body.empty())
		{
			Fail("empty save file");
			return false;
		}

		if (body.size() > kMaxSaveBytes)
		{
			Fail("save too large: " + std::to_string(body.size()) + " bytes");
			return false;
		}

		if (!HasSaveMagic(body))
		{
			Fail("not a witcher save: " + filePath);
			return false;
		}

		const std::string hash = Sha256Hex(reinterpret_cast<const unsigned char*>(body.data()), body.size());

		if (hash.empty())
		{
			Fail("cannot hash save");
			return false;
		}

		for (size_t offset = 0; offset < body.size(); offset += kChunkBytes)
		{
			const size_t take = (body.size() - offset < kChunkBytes) ? (body.size() - offset) : kChunkBytes;
			g_encodedChunks.push_back(EncodeB64(reinterpret_cast<const unsigned char*>(body.data()) + offset, take));
		}

		g_transferId = static_cast<int>(GetTickCount64() & 0x7FFFFFFF);
		g_state = State::Sending;
		g_error.clear();
		g_lastActivityMs = GetTickCount64();
		g_lastRateStepMs = g_lastActivityMs;

		std::vector<std::string> fields;
		fields.push_back(std::to_string(g_transferId));
		fields.push_back(std::to_string(g_encodedChunks.size()));
		fields.push_back(std::to_string(body.size()));
		fields.push_back(hash);

		SendSaveChunk("SAVEBEG", fields);

		Diagnostics::Log("save transfer upload id=" + std::to_string(g_transferId)
			+ " bytes=" + std::to_string(body.size())
			+ " chunks=" + std::to_string(g_encodedChunks.size())
			+ " sha256=" + hash.substr(0, 16));

		return true;
	}

	void SaveTransfer::SetIncomingPath(const std::string& directory)
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		g_incomingDir = directory;
	}

	void SaveTransfer::Pump()
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		const unsigned long long now = GetTickCount64();

		if (g_state == State::Sending)
		{
			if (g_lastSendMs == 0)
				g_lastSendMs = now;

			if (g_lastRateStepMs == 0)
				g_lastRateStepMs = now;

			if ((now - g_lastRateStepMs) >= kRateStepMs)
			{
				g_sendRate += kRateStep;

				if (g_sendRate > kRateMax)
					g_sendRate = kRateMax;

				g_lastRateStepMs = now;
			}

			const unsigned long long elapsed = now - g_lastSendMs;
			g_lastSendMs = now;

			g_sendCredit += (static_cast<double>(elapsed) / 1000.0) * g_sendRate;

			if (g_sendCredit > kMaxChunksPerPump)
				g_sendCredit = kMaxChunksPerPump;

			int budget = static_cast<int>(g_sendCredit);

			if (budget <= 0)
				return;

			g_sendCredit -= budget;

			while (budget > 0 && !g_resendQueue.empty())
			{
				SendChunk(g_resendQueue.back());
				g_resendQueue.pop_back();
				--budget;
			}

			while (budget > 0 && g_sendCursor < g_encodedChunks.size())
			{
				SendChunk(static_cast<int>(g_sendCursor));
				++g_sendCursor;
				--budget;
			}

			if (g_sendCursor >= g_encodedChunks.size() && g_resendQueue.empty() && !g_sentEnd)
			{
				std::vector<std::string> fields;
				fields.push_back(std::to_string(g_transferId));
				SendSaveChunk("SAVEEND", fields);
				g_sentEnd = true;
				g_lastActivityMs = now;
			}

			if (g_sentEnd && (now - g_lastActivityMs) > kStallTimeoutMs)
				Fail("no response from receiver");

			return;
		}

		if (g_state == State::Receiving)
		{
			if (g_recvBeganMs != 0 && (now - g_recvBeganMs) > kReceiveHardLimitMs && g_haveCount < g_expectedChunks)
			{
				Fail("transfer too slow: " + std::to_string(g_haveCount) + "/" + std::to_string(g_expectedChunks)
					+ " chunks after " + std::to_string((now - g_recvBeganMs) / 1000) + "s");
				return;
			}

			if ((now - g_lastNackMs) < kNackIntervalMs)
				return;

			if (g_expectedChunks <= 0)
				return;

			if (!g_endSeen && g_lastChunkMs != 0 && (now - g_lastChunkMs) < kNackIntervalMs)
				return;

			g_lastNackMs = now;

			std::string missing;
			size_t listed = 0;

			for (int i = 0; i < g_expectedChunks && listed < kNackBatch; ++i)
			{
				if (g_have[i])
					continue;

				if (!missing.empty())
					missing += ",";

				missing += std::to_string(i);
				++listed;
			}

			if (listed > 0)
			{
				SendPartyRequest2("SAVENACK", std::to_string(g_transferId), missing);

				if ((now - g_lastActivityMs) > kStallTimeoutMs)
					Fail("transfer stalled with " + std::to_string(g_expectedChunks - g_haveCount) + " chunks missing");
			}
		}
	}

	void SaveTransfer::OnPacket(const std::string& opcode, const std::vector<std::string>& fields)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (opcode == "SAVEBEG")
		{
			if (fields.size() < 4)
				return;

			const int incomingId = atoi(fields[0].c_str());

			if (!g_requested)
			{
				Diagnostics::Log("save transfer ignored unsolicited offer id=" + std::to_string(incomingId));
				return;
			}

			if (g_state == State::Receiving)
			{
				if (incomingId != g_transferId)
					Diagnostics::Log("save transfer ignored competing offer id=" + std::to_string(incomingId));

				return;
			}

			ClearLocked();

			g_transferId = incomingId;
			g_expectedChunks = atoi(fields[1].c_str());
			g_expectedBytes = strtoull(fields[2].c_str(), nullptr, 10);
			g_expectedHash = fields[3];

			if (g_expectedChunks <= 0 || g_expectedChunks > 200000)
			{
				Fail("implausible chunk count");
				return;
			}

			if (g_expectedBytes == 0 || g_expectedBytes > kMaxSaveBytes)
			{
				Fail("implausible save size: " + std::to_string(g_expectedBytes));
				return;
			}

			if (g_expectedHash.size() != 64)
			{
				Fail("missing save digest");
				return;
			}

			g_received.assign(g_expectedChunks, std::string());
			g_have.assign(g_expectedChunks, false);
			g_haveCount = 0;
			g_state = State::Receiving;
			g_error.clear();
			g_lastActivityMs = GetTickCount64();
			g_lastNackMs = g_lastActivityMs;
			g_recvBeganMs = g_lastActivityMs;

			Diagnostics::Log("save transfer incoming id=" + std::to_string(g_transferId)
				+ " chunks=" + std::to_string(g_expectedChunks)
				+ " bytes=" + std::to_string(g_expectedBytes));
			return;
		}

		if (opcode == "SAVECHK")
		{
			if (fields.size() < 3 || g_state != State::Receiving)
				return;

			if (atoi(fields[0].c_str()) != g_transferId)
				return;

			const int index = atoi(fields[1].c_str());

			if (index < 0 || index >= g_expectedChunks || g_have[index])
				return;

			g_received[index] = fields[2];
			g_have[index] = true;
			++g_haveCount;
			g_lastActivityMs = GetTickCount64();
			g_lastChunkMs = g_lastActivityMs;
			return;
		}

		if (opcode == "SAVENACK")
		{
			if (fields.size() < 2 || g_state != State::Sending)
				return;

			if (atoi(fields[0].c_str()) != g_transferId)
				return;

			g_lastActivityMs = GetTickCount64();

			g_sendRate *= 0.5;

			if (g_sendRate < kRateMin)
				g_sendRate = kRateMin;

			g_lastRateStepMs = g_lastActivityMs;

			size_t start = 0;
			const std::string& list = fields[1];

			while (start < list.size())
			{
				const size_t comma = list.find(',', start);
				const std::string piece = list.substr(start, comma == std::string::npos ? std::string::npos : comma - start);

				if (!piece.empty())
					g_resendQueue.push_back(atoi(piece.c_str()));

				if (comma == std::string::npos)
					break;

				start = comma + 1;
			}

			g_sentEnd = false;
			return;
		}

		if (opcode == "SAVEEND")
		{
			if (g_state != State::Receiving)
				return;

			if (fields.empty() || atoi(fields[0].c_str()) != g_transferId)
				return;

			g_endSeen = true;

			if (g_haveCount < g_expectedChunks)
			{
				g_lastNackMs = 0;
				return;
			}

			std::string body;
			body.reserve(static_cast<size_t>(g_expectedBytes));

			for (int i = 0; i < g_expectedChunks; ++i)
				body += DecodeB64(g_received[i]);

			if (body.size() != g_expectedBytes)
			{
				Fail("size mismatch: got " + std::to_string(body.size())
					+ " expected " + std::to_string(g_expectedBytes));
				return;
			}

			const std::string hash = Sha256Hex(reinterpret_cast<const unsigned char*>(body.data()), body.size());

			if (hash.empty() || hash != g_expectedHash)
			{
				Fail("digest mismatch");
				return;
			}

			if (!HasSaveMagic(body))
			{
				Fail("payload is not a witcher save");
				return;
			}

			if (g_incomingDir.empty())
			{
				Fail("no incoming directory set");
				return;
			}

			const std::string stem = "ManualSave_woparty_" + std::to_string(g_transferId);
			const std::string target = g_incomingDir + "\\" + stem + ".sav";

			std::ofstream out(target, std::ios::out | std::ios::trunc | std::ios::binary);

			if (!out.is_open())
			{
				Fail("cannot write " + target);
				return;
			}

			out.write(body.data(), static_cast<std::streamsize>(body.size()));
			out.close();

			g_completedFile = stem;
			g_state = State::Complete;
			g_requested = false;

			SendPartyRequest2("SAVEACK", std::to_string(g_transferId), "1");

			Diagnostics::Log("save transfer complete -> " + target
				+ " (" + std::to_string(body.size()) + " bytes, digest ok)");
			return;
		}

		if (opcode == "SAVENEED")
		{
			g_pendingRequest = fields.empty() ? std::string("?") : fields[0];
			Diagnostics::Log("save transfer requested for " + g_pendingRequest);
			return;
		}

		if (opcode == "SAVEACK")
		{
			if (g_state != State::Sending)
				return;

			g_state = State::Complete;
			Diagnostics::Log("save transfer acknowledged by receiver");
		}
	}

	SaveTransfer::State SaveTransfer::Status()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		return g_state;
	}

	int SaveTransfer::ProgressPermille()
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (g_state == State::Sending && !g_encodedChunks.empty())
			return static_cast<int>((g_sendCursor * 1000) / g_encodedChunks.size());

		if (g_state == State::Receiving && g_expectedChunks > 0)
			return (g_haveCount * 1000) / g_expectedChunks;

		if (g_state == State::Complete)
			return 1000;

		return 0;
	}

	std::string SaveTransfer::LastError()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		return g_error;
	}

	std::string SaveTransfer::CompletedFile()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		return g_completedFile;
	}

	namespace {

		int SweepIncoming(const std::string& marker, bool remove)
		{
			std::string directory;

			{
				std::lock_guard<std::mutex> lock(g_mutex);
				directory = g_incomingDir;
			}

			if (directory.empty() || marker.empty())
				return 0;

			WIN32_FIND_DATAA find;
			const HANDLE handle = FindFirstFileA((directory + "\\*").c_str(), &find);

			if (handle == INVALID_HANDLE_VALUE)
				return 0;

			std::string needle = marker;
			for (char& c : needle)
				c = static_cast<char>(tolower(static_cast<unsigned char>(c)));

			int hits = 0;

			do
			{
				if (find.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)
					continue;

				std::string name = find.cFileName;
				for (char& c : name)
					c = static_cast<char>(tolower(static_cast<unsigned char>(c)));

				if (name.find(needle) == std::string::npos)
					continue;

				const std::string path = directory + "\\" + find.cFileName;

				if (!remove)
				{
					hits++;
					continue;
				}

				if (DeleteFileA(path.c_str()))
				{
					hits++;
					Diagnostics::Log("purged transfer file " + path);
				}
				else
				{
					Diagnostics::Log("could not purge " + path
						+ " (win32 error " + std::to_string(GetLastError()) + ")");
				}
			}
			while (FindNextFileA(handle, &find));

			FindClose(handle);

			return hits;
		}
	}

	int SaveTransfer::PurgeIncoming(const std::string& marker)
	{
		return SweepIncoming(marker, true);
	}

	int SaveTransfer::CountIncoming(const std::string& marker)
	{
		return SweepIncoming(marker, false);
	}

	void SaveTransfer::Reset()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		ClearLocked();
		g_state = State::Idle;
		g_error.clear();
		g_completedFile.clear();
		g_requested = false;
	}
}
