#pragma once

#include <string>
#include <vector>

namespace w3mp {

	namespace SaveTransfer {

		enum class State
		{
			Idle = 0,
			Sending = 1,
			Receiving = 2,
			Complete = 3,
			Failed = 4
		};

		// Sender: read a .sav off disk and upload it once; the server fans it out.
		bool BeginSend(const std::string& filePath);

		// Member: ask the server for the leader's current save.
		void RequestSave();

		// Leader: username the server wants a save for, or "" if none pending.
		std::string TakePendingRequest();

		// Receiver: where a completed transfer should be written.
		void SetIncomingPath(const std::string& directory);

		// Driven from the client tick; paces chunk output and drives retries.
		void Pump();

		// Inbound relayed packets.
		void OnPacket(const std::string& opcode, const std::vector<std::string>& fields);

		State Status();
		int ProgressPermille();
		std::string LastError();
		std::string CompletedFile();

		int PurgeIncoming(const std::string& marker);

		int CountIncoming(const std::string& marker);

		void Reset();
	}
}
