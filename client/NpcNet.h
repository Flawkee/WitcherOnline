#pragma once
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace w3mp {

	enum class ReplicaOp
	{
		Drive = 0,
		Spawn = 1,
		Despawn = 2,
		Kill = 3,
		Promote = 4
	};

	struct ReplicaCommand
	{
		int canonicalId = 0;
		int op = 0;
		int localGuid = 0;
		float x = 0.0f;
		float y = 0.0f;
		float z = 0.0f;
		float heading = 0.0f;
		float speed = 0.0f;
		int hpPermille = -1;
		int flags = 0;
		int targetPlayerId = 0;
		std::string typeCode;
		std::string appearance;
	};

	struct InboundHit
	{
		int ownerGuid = 0;
		int attackerPlayerId = 0;
		int permille = 0;
		int eventId = 0;
		long long atServerMs = 0;
	};

	struct HitAck
	{
		int canonicalId = 0;
		int eventId = 0;
		int resultPermille = -1;
		bool accepted = false;
	};

	using PacketSender = std::function<void(const char*, const std::vector<std::string>&)>;

	class NpcNet
	{
	public:
		static void SetSender(PacketSender sender);
		static void Reset();

		static void OnPacket(const std::string& opcode, const std::vector<std::string>& fields);
		static void Tick();

		static void BeginOwned();
		static bool PushOwned(
			int guid,
			int area,
			int localCount,
			const std::string& typeCode,
			const std::string& appearance,
			float x,
			float y,
			float z,
			float heading,
			int hpPermille,
			int flags,
			int targetPlayerId);
		static void EndOwned();

		static int Pull();
		static const ReplicaCommand* Command(int index);
		static void Bind(int index, int localGuid);
		static void Forget(int canonicalId);
		static void Take(int canonicalId, int localGuid);
		static void MarkUnspawnable(int canonicalId);
		static void SetSuspended(bool suspended);
		static int PullDrops();
		static int Drop(int index);
		static void ClearDrops();
		static int PullKills();
		static int KillGuid(int index);
		static void ClearKills();

		static int ReportHit(int canonicalId, int permille);

		static int PullHits();
		static const InboundHit* Hit(int index);
		static void AckHit(int index, int resultPermille, bool accepted);
		static void ClearHits();

		static int PullAcks();
		static const HitAck* Ack(int index);
		static void ClearAcks();

		static void SetPlayerPaused(int playerId, bool paused);
		static bool IsPlayerPaused(int playerId);

		static int ScaleMilli(int npcId);
		static int ScaleGeneration();

		static long long ServerNowMs();
		static int LatencyMs();
		static std::string Report();
		static void SetTuning(int interpDelayMs, int snapshotHz);
		static void SetSyncMode(int mode);
	};

}
