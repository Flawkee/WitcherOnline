#include "pch.h"
#include "NpcNet.h"
#include "Diagnostics.h"

#include <algorithm>
#include <atomic>
#include <charconv>
#include <cmath>
#include <deque>
#include <mutex>
#include <sstream>
#include <unordered_map>
#include <unordered_set>
#include <windows.h>

namespace w3mp {

	namespace {

		constexpr int kDefaultInterpDelayMs = 60;
		constexpr long long kMaxInterpDelayMs = 400;
		constexpr int kDefaultSnapshotHz = 40;
		constexpr int kMaxExtrapolationMs = 250;
		constexpr int kReplicaSampleCap = 12;
		constexpr int kAddPerPacket = 8;
		constexpr int kUpdPerPacket = 20;
		constexpr int kDelPerPacket = 40;
		constexpr int kMaxAddPacketsPerSend = 4;
		constexpr int kMaxUpdPacketsPerSend = 8;
		constexpr size_t kMaxPacketBytes = 1050;
		constexpr int kWantPerPacket = 16;
		constexpr int kWantIntervalMs = 700;
		constexpr int kSpawnAttemptLimit = 3;
		constexpr int kKeepaliveMs = 500;
		constexpr int kOwnedGraceMs = 900;
		constexpr int kReplicaStaleMs = 6000;
		constexpr int kDeathAnimGraceMs = 2000;
		constexpr float kPositionDeadband = 0.08f;
		constexpr float kHeadingDeadband = 2.0f;
		constexpr int kTimeSyncIntervalMs = 1000;
		constexpr int kMaxPendingHits = 64;

		struct TransformSample
		{
			long long t = 0;
			float x = 0.0f;
			float y = 0.0f;
			float z = 0.0f;
			float heading = 0.0f;
			int hpPermille = -1;
			int flags = 0;
			int targetPlayerId = 0;
		};

		struct OwnedEntity
		{
			int guid = 0;
			int area = 0;
			int localCount = 1;
			std::string typeCode;
			std::string appearance;
			float x = 0.0f;
			float y = 0.0f;
			float z = 0.0f;
			float heading = 0.0f;
			int hpPermille = -1;
			int flags = 0;
			int targetPlayerId = 0;

			bool registered = false;
			bool seenThisFrame = false;
			long long lastSeenMs = 0;
			long long lastSentMs = 0;

			float sentX = 0.0f;
			float sentY = 0.0f;
			float sentZ = 0.0f;
			float sentHeading = 0.0f;
			int sentHp = -2;
			int sentFlags = -1;
			int sentTarget = -1;
		};

		struct Replica
		{
			int canonicalId = 0;
			std::string typeCode;
			std::string appearance;
			std::deque<TransformSample> samples;
			int localGuid = 0;
			bool spawnRequested = false;
			bool spawnEmitted = false;
			bool despawn = false;
			bool dead = false;
			bool deathEmitted = false;
			bool promote = false;
			bool unspawnable = false;
			int spawnAttempts = 0;
			long long wantedAtMs = 0;
			long long lastPacketMs = 0;
			long long avgIntervalMs = 0;
			long long despawnAfterMs = 0;
		};

		struct PendingHit
		{
			int eventId = 0;
			int canonicalId = 0;
			int permille = 0;
			long long atServerMs = 0;
		};

		std::mutex g_mutex;
		PacketSender g_sender;

		std::unordered_map<int, OwnedEntity> g_owned;
		std::vector<int> g_ownedRemoved;
		std::unordered_map<int, Replica> g_replicas;

		std::vector<ReplicaCommand> g_commands;
		std::vector<InboundHit> g_hits;
		std::vector<HitAck> g_acks;
		std::vector<PendingHit> g_pending;
		std::vector<std::string> g_ackQueue;
		std::vector<int> g_dropGuids;
		std::vector<int> g_killGuids;
		std::vector<int> g_giveNacks;
		std::unordered_map<int, bool> g_pausedPlayers;
		std::unordered_map<int, int> g_scales;
		std::unordered_map<int, int> g_scaleStaging;
		unsigned int g_scaleGeneration = 0;
		int g_lastLoggedScale = 1000;
		int g_scaleSetId = -1;
		std::unordered_set<std::string> g_unroutedOpcodes;
		bool g_suspended = false;

		int g_interpDelayMs = kDefaultInterpDelayMs;
		int g_snapshotIntervalMs = 1000 / kDefaultSnapshotHz;

		long long g_clockOffsetMs = 0;
		bool g_clockReady = false;
		int g_latencyMs = 0;
		int g_rttMs = 0;
		int g_syncMode = 0;
		std::atomic<long long> g_nextTimeSyncMs{ 0 };
		std::atomic<int> g_pendingCount{ 0 };
		std::atomic<unsigned long long> g_commandsGeneration{ 0 };
		long long g_nextSnapshotMs = 0;
		long long g_batchNowMs = 0;
		long long g_batchServerMs = 0;

		using Outbox = std::vector<std::pair<const char*, std::vector<std::string>>>;

		int g_nextEventId = 1;

		unsigned long long g_statSnapshotsSent = 0;
		unsigned long long g_statAdds = 0;
		unsigned long long g_statUpdates = 0;
		unsigned long long g_statRemovals = 0;
		unsigned long long g_statAddBudgetHit = 0;
		unsigned long long g_statUpdBudgetHit = 0;
		unsigned long long g_statSpawns = 0;
		unsigned long long g_statDespawns = 0;
		unsigned long long g_statKills = 0;
		unsigned long long g_statPromotions = 0;
		unsigned long long g_statWants = 0;
		unsigned long long g_statReregisters = 0;
		unsigned long long g_statGiveNacks = 0;
		unsigned long long g_statUnspawnable = 0;
		unsigned long long g_statSuspends = 0;
		unsigned long long g_statHitsSent = 0;
		unsigned long long g_statHitsIn = 0;
		unsigned long long g_statAcksIn = 0;
		unsigned long long g_statExtrapolated = 0;
		unsigned long long g_statStarved = 0;

		long long NowMs()
		{
			static const long long frequency = []
			{
				LARGE_INTEGER value;
				QueryPerformanceFrequency(&value);
				return static_cast<long long>(value.QuadPart);
			}();

			LARGE_INTEGER counter;
			QueryPerformanceCounter(&counter);

			return (static_cast<long long>(counter.QuadPart) / frequency) * 1000ll
				+ ((static_cast<long long>(counter.QuadPart) % frequency) * 1000ll) / frequency;
		}

		long long ServerNow()
		{
			return NowMs() + g_clockOffsetMs;
		}

		int ParseInt(const std::vector<std::string>& fields, size_t index)
		{
			if (index >= fields.size())
				return 0;

			const std::string& text = fields[index];
			int value = 0;
			std::from_chars(text.data(), text.data() + text.size(), value);

			return value;
		}

		long long ParseLong(const std::vector<std::string>& fields, size_t index)
		{
			if (index >= fields.size())
				return 0;

			const std::string& text = fields[index];
			long long value = 0;
			std::from_chars(text.data(), text.data() + text.size(), value);

			return value;
		}

		float ParseFloat(const std::vector<std::string>& fields, size_t index)
		{
			if (index >= fields.size())
				return 0.0f;

			const std::string& text = fields[index];
			float value = 0.0f;
			std::from_chars(text.data(), text.data() + text.size(), value);

			return value;
		}

		const std::string& Field(const std::vector<std::string>& fields, size_t index)
		{
			static const std::string empty = "-";
			return index < fields.size() ? fields[index] : empty;
		}

		std::string FormatFloat(float value)
		{
			char buffer[32];
			long long scaled = llround(static_cast<double>(value) * 100.0);
			const bool negative = scaled < 0;

			if (negative)
				scaled = -scaled;

			sprintf_s(buffer, sizeof(buffer), negative ? "-%lld.%02lld" : "%lld.%02lld",
				scaled / 100, scaled % 100);

			return buffer;
		}

		void Send(const char* opcode, const std::vector<std::string>& fields)
		{
			if (g_sender && fields.size() > 1)
				g_sender(opcode, fields);
		}

		float ShortestAngle(float from, float to)
		{
			float delta = std::fmod(to - from + 540.0f, 360.0f) - 180.0f;
			return delta;
		}

		int ComputeMask(const OwnedEntity& entity)
		{
			int mask = 0;

			if (std::fabs(entity.x - entity.sentX) > kPositionDeadband
				|| std::fabs(entity.y - entity.sentY) > kPositionDeadband
				|| std::fabs(entity.z - entity.sentZ) > kPositionDeadband)
			{
				mask |= 1;
			}

			if (std::fabs(ShortestAngle(entity.sentHeading, entity.heading)) > kHeadingDeadband)
				mask |= 2;

			if (entity.hpPermille != entity.sentHp)
				mask |= 4;

			if (entity.flags != entity.sentFlags)
				mask |= 8;

			if (entity.targetPlayerId != entity.sentTarget)
				mask |= 16;

			return mask;
		}

		void MarkSent(OwnedEntity& entity, long long now)
		{
			entity.sentX = entity.x;
			entity.sentY = entity.y;
			entity.sentZ = entity.z;
			entity.sentHeading = entity.heading;
			entity.sentHp = entity.hpPermille;
			entity.sentFlags = entity.flags;
			entity.sentTarget = entity.targetPlayerId;
			entity.lastSentMs = now;
		}

		size_t FieldsBytes(const std::vector<std::string>& fields, size_t fromIndex)
		{
			size_t total = 0;

			for (size_t i = fromIndex; i < fields.size(); ++i)
				total += fields[i].size() + 1;

			return total;
		}

		void QueuePacket(Outbox& outbox, const char* opcode, std::vector<std::string>&& fields)
		{
			outbox.emplace_back(opcode, std::move(fields));
		}

		void BeginSnapshotFields(std::vector<std::string>& fields)
		{
			fields.clear();
			fields.push_back(std::string());
			fields.push_back(std::string());
		}

		void SealSnapshotFields(std::vector<std::string>& fields, long long snapshotMs, int count)
		{
			fields[0] = std::to_string(snapshotMs);
			fields[1] = std::to_string(count);
		}

		void FlushOwned(long long now, Outbox& outbox)
		{
			const long long snapshotMs = ServerNow();

			std::vector<std::string> addFields;
			std::vector<std::string> updFields;
			std::vector<std::string> delFields;
			int addCount = 0;
			int updCount = 0;
			int delCount = 0;
			size_t addBytes = 0;
			size_t updBytes = 0;

			addFields.reserve(kAddPerPacket * 12 + 2);
			updFields.reserve(kUpdPerPacket * 9 + 2);

			for (int guid : g_ownedRemoved)
			{
				delFields.push_back(std::to_string(guid));
				delFields.push_back("0");
				delCount++;
				g_statRemovals++;

				if (delCount >= kDelPerPacket)
				{
					delFields.insert(delFields.begin(), std::to_string(delCount));
					QueuePacket(outbox, "NPCDEL", std::move(delFields));
					delFields = std::vector<std::string>();
					delCount = 0;
				}
			}

			g_ownedRemoved.clear();

			if (delCount > 0)
			{
				delFields.insert(delFields.begin(), std::to_string(delCount));
				QueuePacket(outbox, "NPCDEL", std::move(delFields));
			}

			int addPackets = 0;
			int updPackets = 0;

			BeginSnapshotFields(addFields);
			BeginSnapshotFields(updFields);

			for (auto& pair : g_owned)
			{
				OwnedEntity& entity = pair.second;

				if (!entity.registered)
				{
					if (addPackets >= kMaxAddPacketsPerSend)
					{
						g_statAddBudgetHit++;
						continue;
					}

					const size_t before = addFields.size();

					addFields.push_back(std::to_string(entity.guid));
					addFields.push_back(std::to_string(entity.area));
					addFields.push_back(entity.typeCode.empty() ? "-" : entity.typeCode);
					addFields.push_back(entity.appearance.empty() ? "-" : entity.appearance);
					addFields.push_back(FormatFloat(entity.x));
					addFields.push_back(FormatFloat(entity.y));
					addFields.push_back(FormatFloat(entity.z));
					addFields.push_back(FormatFloat(entity.heading));
					addFields.push_back(std::to_string(entity.hpPermille));
					addFields.push_back(std::to_string(entity.flags));
					addFields.push_back(std::to_string(entity.targetPlayerId));
					addFields.push_back(std::to_string(entity.localCount));

					entity.registered = true;
					MarkSent(entity, now);
					addCount++;
					addBytes += FieldsBytes(addFields, before);
					g_statAdds++;

					if (addCount >= kAddPerPacket || addBytes >= kMaxPacketBytes)
					{
						SealSnapshotFields(addFields, snapshotMs, addCount);
						QueuePacket(outbox, "NPCADD", std::move(addFields));
						addFields = std::vector<std::string>();
						BeginSnapshotFields(addFields);
						addCount = 0;
						addBytes = 0;
						addPackets++;
					}

					continue;
				}

				const int mask = ComputeMask(entity);
				const bool keepalive = (now - entity.lastSentMs) >= kKeepaliveMs;

				if (mask == 0 && !keepalive)
					continue;

				if (updPackets >= kMaxUpdPacketsPerSend)
				{
					g_statUpdBudgetHit++;
					continue;
				}

				const size_t before = updFields.size();

				updFields.push_back(std::to_string(entity.guid));
				updFields.push_back(std::to_string(mask));

				if (mask & 1)
				{
					updFields.push_back(FormatFloat(entity.x));
					updFields.push_back(FormatFloat(entity.y));
					updFields.push_back(FormatFloat(entity.z));
				}

				if (mask & 2)
					updFields.push_back(FormatFloat(entity.heading));

				if (mask & 4)
					updFields.push_back(std::to_string(entity.hpPermille));

				if (mask & 8)
					updFields.push_back(std::to_string(entity.flags));

				if (mask & 16)
					updFields.push_back(std::to_string(entity.targetPlayerId));

				MarkSent(entity, now);
				updCount++;
				updBytes += FieldsBytes(updFields, before);
				g_statUpdates++;

				if (updCount >= kUpdPerPacket || updBytes >= kMaxPacketBytes)
				{
					SealSnapshotFields(updFields, snapshotMs, updCount);
					QueuePacket(outbox, "NPCUPD", std::move(updFields));
					updFields = std::vector<std::string>();
					BeginSnapshotFields(updFields);
					updCount = 0;
					updBytes = 0;
					updPackets++;
				}
			}

			if (addCount > 0)
			{
				SealSnapshotFields(addFields, snapshotMs, addCount);
				QueuePacket(outbox, "NPCADD", std::move(addFields));
			}

			if (updCount > 0)
			{
				SealSnapshotFields(updFields, snapshotMs, updCount);
				QueuePacket(outbox, "NPCUPD", std::move(updFields));
			}

			g_statSnapshotsSent++;
		}

		void SendTimeSync(long long now)
		{
			std::vector<std::string> fields;
			fields.push_back(std::to_string(now));
			fields.push_back(std::to_string(g_clockReady ? g_rttMs : -1));
			fields.push_back(std::to_string(g_syncMode));

			if (g_sender)
				g_sender("TSYNC", fields);
		}

		void FlushAcks(Outbox& outbox)
		{
			if (g_ackQueue.empty())
				return;

			std::vector<std::string> fields;
			int count = 0;

			for (size_t i = 0; i + 4 <= g_ackQueue.size(); i += 4)
			{
				fields.push_back(g_ackQueue[i]);
				fields.push_back(g_ackQueue[i + 1]);
				fields.push_back(g_ackQueue[i + 2]);
				fields.push_back(g_ackQueue[i + 3]);
				count++;
			}

			g_ackQueue.clear();

			if (count == 0)
				return;

			fields.insert(fields.begin(), std::to_string(count));
			QueuePacket(outbox, "NPCACK", std::move(fields));
		}

		bool Interpolate(Replica& replica, long long renderTime, ReplicaCommand& command)
		{
			if (replica.samples.empty())
				return false;

			const TransformSample& newest = replica.samples.back();
			const TransformSample& oldest = replica.samples.front();

			command.hpPermille = newest.hpPermille;
			command.flags = newest.flags;
			command.targetPlayerId = newest.targetPlayerId;

			if (replica.samples.size() == 1 || renderTime <= oldest.t)
			{
				command.x = oldest.x;
				command.y = oldest.y;
				command.z = oldest.z;
				command.heading = oldest.heading;
				command.speed = 0.0f;

				if (renderTime > newest.t + kMaxExtrapolationMs)
					g_statStarved++;

				return true;
			}

			if (renderTime >= newest.t)
			{
				const TransformSample& previous = replica.samples[replica.samples.size() - 2];
				const long long span = newest.t - previous.t;

				float vx = 0.0f;
				float vy = 0.0f;
				float vz = 0.0f;

				if (span > 0)
				{
					const float inv = 1.0f / static_cast<float>(span);
					vx = (newest.x - previous.x) * inv;
					vy = (newest.y - previous.y) * inv;
					vz = (newest.z - previous.z) * inv;
				}

				long long ahead = renderTime - newest.t;

				if (ahead > kMaxExtrapolationMs)
				{
					ahead = kMaxExtrapolationMs;
					g_statStarved++;
				}
				else if (ahead > 0)
				{
					g_statExtrapolated++;
				}

				command.x = newest.x + vx * static_cast<float>(ahead);
				command.y = newest.y + vy * static_cast<float>(ahead);
				command.z = newest.z + vz * static_cast<float>(ahead);
				command.heading = newest.heading;
				command.speed = std::sqrt(vx * vx + vy * vy) * 1000.0f;

				return true;
			}

			for (size_t i = replica.samples.size(); i-- > 1;)
			{
				const TransformSample& b = replica.samples[i];
				const TransformSample& a = replica.samples[i - 1];

				if (renderTime < a.t || renderTime > b.t)
					continue;

				const long long span = b.t - a.t;
				const float alpha = span > 0 ? static_cast<float>(renderTime - a.t) / static_cast<float>(span) : 1.0f;

				command.x = a.x + (b.x - a.x) * alpha;
				command.y = a.y + (b.y - a.y) * alpha;
				command.z = a.z + (b.z - a.z) * alpha;
				command.heading = a.heading + ShortestAngle(a.heading, b.heading) * alpha;

				if (span > 0)
				{
					const float dx = b.x - a.x;
					const float dy = b.y - a.y;
					command.speed = std::sqrt(dx * dx + dy * dy) / (static_cast<float>(span) / 1000.0f);
				}
				else
				{
					command.speed = 0.0f;
				}

				return true;
			}

			command.x = newest.x;
			command.y = newest.y;
			command.z = newest.z;
			command.heading = newest.heading;
			command.speed = 0.0f;

			return true;
		}

		void HandleSnapshot(const std::vector<std::string>& fields, bool isSpawn)
		{
			const long long snapshotMs = ParseLong(fields, 0);
			const int count = ParseInt(fields, 1);
			const long long now = ServerNow();
			size_t cursor = 2;

			for (int i = 0; i < count; ++i)
			{
				size_t entrySize = 0;
				int mask = 0;

				if (isSpawn)
				{
					entrySize = 11;
				}
				else
				{
					if (cursor + 2 > fields.size())
						break;

					mask = ParseInt(fields, cursor + 1);
					entrySize = 2;

					if (mask & 1)
						entrySize += 3;
					if (mask & 2)
						entrySize += 1;
					if (mask & 4)
						entrySize += 1;
					if (mask & 8)
						entrySize += 1;
					if (mask & 16)
						entrySize += 1;
				}

				if (cursor + entrySize > fields.size())
					break;

				const size_t base = cursor;
				cursor += entrySize;

				const int canonicalId = ParseInt(fields, base);

				if (canonicalId <= 0)
					continue;

				Replica& replica = g_replicas[canonicalId];
				replica.canonicalId = canonicalId;
				replica.lastPacketMs = now;

				TransformSample sample;
				sample.t = snapshotMs > 0 ? snapshotMs : now;

				if (isSpawn)
				{
					replica.typeCode = Field(fields, base + 2);
					replica.appearance = Field(fields, base + 3);
					sample.x = ParseFloat(fields, base + 4);
					sample.y = ParseFloat(fields, base + 5);
					sample.z = ParseFloat(fields, base + 6);
					sample.heading = ParseFloat(fields, base + 7);
					sample.hpPermille = ParseInt(fields, base + 8);
					sample.flags = ParseInt(fields, base + 9);
					sample.targetPlayerId = ParseInt(fields, base + 10);
				}
				else
				{
					size_t at = base + 2;

					if (!replica.samples.empty())
					{
						const TransformSample& previous = replica.samples.back();

						sample.x = previous.x;
						sample.y = previous.y;
						sample.z = previous.z;
						sample.heading = previous.heading;
						sample.hpPermille = previous.hpPermille;
						sample.flags = previous.flags;
						sample.targetPlayerId = previous.targetPlayerId;
					}

					if (mask & 1)
					{
						sample.x = ParseFloat(fields, at);
						sample.y = ParseFloat(fields, at + 1);
						sample.z = ParseFloat(fields, at + 2);
						at += 3;
					}

					if (mask & 2)
					{
						sample.heading = ParseFloat(fields, at);
						at += 1;
					}

					if (mask & 4)
					{
						sample.hpPermille = ParseInt(fields, at);
						at += 1;
					}

					if (mask & 8)
					{
						sample.flags = ParseInt(fields, at);
						at += 1;
					}

					if (mask & 16)
					{
						sample.targetPlayerId = ParseInt(fields, at);
						at += 1;
					}
				}

				if (!replica.samples.empty() && sample.t < replica.samples.back().t)
					sample.t = replica.samples.back().t;

				if (!replica.samples.empty())
				{
					const long long interval = sample.t - replica.samples.back().t;

					if (interval > 0 && interval < 2000)
					{
						replica.avgIntervalMs = (replica.avgIntervalMs > 0)
							? ((replica.avgIntervalMs * 3 + interval) / 4)
							: interval;
					}
				}

				replica.samples.push_back(sample);

				while (static_cast<int>(replica.samples.size()) > kReplicaSampleCap)
					replica.samples.pop_front();
			}
		}

	}

	void NpcNet::SetSender(PacketSender sender)
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		g_sender = std::move(sender);
	}

	void NpcNet::Reset()
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		g_owned.clear();
		g_ownedRemoved.clear();
		g_replicas.clear();
		g_commands.clear();
		g_hits.clear();
		g_acks.clear();
		g_pending.clear();
		g_pendingCount.store(0, std::memory_order_relaxed);
		g_ackQueue.clear();
		g_scales.clear();
		g_scaleStaging.clear();
		g_clockReady = false;
		g_clockOffsetMs = 0;
		g_latencyMs = 0;
		g_rttMs = 0;
	}

	void NpcNet::SetSyncMode(int mode)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		g_syncMode = (mode == 1) ? 1 : 0;
	}

	void NpcNet::SetTuning(int interpDelayMs, int snapshotHz)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (interpDelayMs >= 0 && interpDelayMs <= 500)
			g_interpDelayMs = interpDelayMs;

		if (snapshotHz >= 5 && snapshotHz <= 60)
			g_snapshotIntervalMs = 1000 / snapshotHz;
	}

	void NpcNet::OnPacket(const std::string& opcode, const std::vector<std::string>& fields)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (opcode == "TSYNCR")
		{
			const long long sentAt = ParseLong(fields, 0);
			const long long serverMs = ParseLong(fields, 1);
			const long long now = NowMs();
			const long long rtt = now - sentAt;

			if (rtt < 0 || rtt > 5000)
				return;

			const long long estimate = serverMs + rtt / 2 - now;

			if (!g_clockReady)
			{
				g_clockOffsetMs = estimate;
				g_latencyMs = static_cast<int>(rtt / 2);
				g_rttMs = static_cast<int>(rtt);
				g_clockReady = true;
			}
			else
			{
				g_clockOffsetMs = (g_clockOffsetMs * 7 + estimate) / 8;
				g_latencyMs = static_cast<int>((g_latencyMs * 7 + rtt / 2) / 8);
				g_rttMs = static_cast<int>((g_rttMs * 7 + rtt) / 8);
			}

			return;
		}

		if (opcode == "NPCNEW")
		{
			HandleSnapshot(fields, true);
			return;
		}

		if (opcode == "NPCMOV")
		{
			HandleSnapshot(fields, false);
			return;
		}

		if (opcode == "NPCEND")
		{
			const int count = ParseInt(fields, 0);

			for (int i = 0; i < count; ++i)
			{
				const size_t base = 1 + static_cast<size_t>(i) * 2;

				if (base + 2 > fields.size())
					break;

				const int canonicalId = ParseInt(fields, base);
				auto it = g_replicas.find(canonicalId);

				if (it != g_replicas.end())
					it->second.despawn = true;
			}

			return;
		}

		if (opcode == "NPCKILL")
		{
			const int count = ParseInt(fields, 0);

			for (int i = 0; i < count; ++i)
			{
				const size_t base = 1 + static_cast<size_t>(i);

				if (base >= fields.size())
					break;

				const int guid = ParseInt(fields, base);

				if (guid != 0)
					g_killGuids.push_back(guid);
			}

			return;
		}

		if (opcode == "NPCDROP")
		{
			const int count = ParseInt(fields, 0);

			for (int i = 0; i < count; ++i)
			{
				const size_t base = 1 + static_cast<size_t>(i);

				if (base >= fields.size())
					break;

				const int guid = ParseInt(fields, base);

				if (guid == 0)
					continue;

				g_owned.erase(guid);
				g_dropGuids.push_back(guid);
			}

			return;
		}

		if (opcode == "PSTATEF")
		{
			const int count = ParseInt(fields, 0);

			for (int i = 0; i < count; ++i)
			{
				const size_t base = 1 + static_cast<size_t>(i) * 2;

				if (base + 2 > fields.size())
					break;

				const int playerId = ParseInt(fields, base);
				const bool paused = ParseInt(fields, base + 1) != 0;

				if (playerId > 0)
					g_pausedPlayers[playerId] = paused;
			}

			return;
		}

		if (opcode == "NPCGONE")
		{
			const int count = ParseInt(fields, 0);

			for (int i = 0; i < count; ++i)
			{
				const size_t base = 1 + static_cast<size_t>(i);

				if (base >= fields.size())
					break;

				const int guid = ParseInt(fields, base);
				auto it = g_owned.find(guid);

				if (it != g_owned.end())
				{
					it->second.registered = false;
					it->second.lastSentMs = 0;
					g_statReregisters++;
				}
			}

			return;
		}

		if (opcode == "NPCGIVE")
		{
			const int count = ParseInt(fields, 0);

			for (int i = 0; i < count; ++i)
			{
				const size_t base = 1 + static_cast<size_t>(i);

				if (base >= fields.size())
					break;

				const int canonicalId = ParseInt(fields, base);
				auto it = g_replicas.find(canonicalId);

				if (it != g_replicas.end() && it->second.localGuid != 0 && !it->second.dead)
				{
					it->second.promote = true;
				}
				else
				{
					g_giveNacks.push_back(canonicalId);
					g_statGiveNacks++;
				}
			}

			if (!g_giveNacks.empty())
			{
				std::vector<std::string> nack;
				nack.push_back(std::to_string(static_cast<int>(g_giveNacks.size())));

				for (int id : g_giveNacks)
					nack.push_back(std::to_string(id));

				g_giveNacks.clear();
				Send("NPCNOPE", nack);
			}

			return;
		}

		if (opcode == "NPCDEAD")
		{
			const int count = ParseInt(fields, 0);

			for (int i = 0; i < count; ++i)
			{
				const size_t base = 1 + static_cast<size_t>(i);

				if (base >= fields.size())
					break;

				const int canonicalId = ParseInt(fields, base);
				auto it = g_replicas.find(canonicalId);

				if (it != g_replicas.end())
					it->second.dead = true;
			}

			return;
		}

		if (opcode == "NPCSCALE")
		{
			const int setId = ParseInt(fields, 0);
			const int total = ParseInt(fields, 1);
			const int count = ParseInt(fields, 2);

			if (setId != g_scaleSetId)
			{
				g_scaleStaging.clear();
				g_scaleSetId = setId;
			}

			for (int i = 0; i < count; ++i)
			{
				const size_t base = 3 + static_cast<size_t>(i) * 2;

				if (base + 2 > fields.size())
					break;

				const int npcId = ParseInt(fields, base);
				const int scaleMilli = ParseInt(fields, base + 1);

				if (npcId != 0 && scaleMilli > 0)
					g_scaleStaging[npcId] = scaleMilli;
			}

			if (total > 0 && static_cast<int>(g_scaleStaging.size()) >= total)
			{
				g_scales.swap(g_scaleStaging);
				g_scaleStaging.clear();
				g_scaleGeneration++;

				int peak = 1000;
				int peakId = 0;

				for (const auto& entry : g_scales)
				{
					if (entry.second > peak)
					{
						peak = entry.second;
						peakId = entry.first;
					}
				}

				if (peak != g_lastLoggedScale)
				{
					g_lastLoggedScale = peak;
					Diagnostics::Log("npc scale set: entries=" + std::to_string(g_scales.size())
						+ " peak=" + std::to_string(peak)
						+ " peakId=" + std::to_string(peakId));
				}
			}

			return;
		}

		if (opcode == "NPCHITF")
		{
			const int count = ParseInt(fields, 0);

			for (int i = 0; i < count; ++i)
			{
				const size_t base = 1 + static_cast<size_t>(i) * 5;

				if (base + 5 > fields.size())
					break;

				InboundHit hit;
				hit.ownerGuid = ParseInt(fields, base);
				hit.attackerPlayerId = ParseInt(fields, base + 1);
				hit.permille = ParseInt(fields, base + 2);
				hit.eventId = ParseInt(fields, base + 3);
				hit.atServerMs = ParseLong(fields, base + 4);

				if (hit.ownerGuid != 0 && hit.permille > 0)
				{
					g_hits.push_back(hit);
					g_statHitsIn++;
				}
			}

			return;
		}

		if (opcode == "NPCACKF")
		{
			const int count = ParseInt(fields, 0);

			for (int i = 0; i < count; ++i)
			{
				const size_t base = 1 + static_cast<size_t>(i) * 3;

				if (base + 3 > fields.size())
					break;

				const int eventId = ParseInt(fields, base);

				HitAck ack;
				ack.eventId = eventId;
				ack.resultPermille = ParseInt(fields, base + 1);
				ack.accepted = ParseInt(fields, base + 2) != 0;

				for (size_t p = 0; p < g_pending.size(); ++p)
				{
					if (g_pending[p].eventId != eventId)
						continue;

					ack.canonicalId = g_pending[p].canonicalId;
					g_pending.erase(g_pending.begin() + p);
					g_pendingCount.store(static_cast<int>(g_pending.size()), std::memory_order_relaxed);
					break;
				}

				if (ack.canonicalId != 0)
				{
					g_acks.push_back(ack);
					g_statAcksIn++;
				}
			}

			return;
		}

		if (g_unroutedOpcodes.insert(opcode).second)
		{
			Diagnostics::Log("WARNING unhandled opcode " + opcode
				+ " reached NpcNet::OnPacket but no handler claimed it (check isNetOpcode in dllmain.cpp)");
		}
	}

	void NpcNet::Tick()
	{
		const long long probe = NowMs();

		if (probe < g_nextTimeSyncMs.load(std::memory_order_relaxed)
			&& g_pendingCount.load(std::memory_order_relaxed) == 0)
		{
			return;
		}

		std::lock_guard<std::mutex> lock(g_mutex);

		const long long now = NowMs();

		if (now >= g_nextTimeSyncMs.load(std::memory_order_relaxed))
		{
			g_nextTimeSyncMs.store(now + kTimeSyncIntervalMs, std::memory_order_relaxed);
			SendTimeSync(now);
		}

		const long long cutoff = ServerNow();

		for (auto it = g_pending.begin(); it != g_pending.end();)
		{
			if (cutoff - it->atServerMs > 4000)
			{
				it = g_pending.erase(it);
				g_pendingCount.store(static_cast<int>(g_pending.size()), std::memory_order_relaxed);
			}
			else
			{
				++it;
			}
		}
	}

	void NpcNet::BeginOwned()
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		g_batchNowMs = NowMs();
		g_batchServerMs = g_batchNowMs + g_clockOffsetMs;

		for (auto& pair : g_owned)
			pair.second.seenThisFrame = false;
	}

	bool NpcNet::PushOwned(
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
		int targetPlayerId)
	{
		if (guid == 0)
			return false;

		std::lock_guard<std::mutex> lock(g_mutex);

		OwnedEntity& entity = g_owned[guid];

		if (entity.guid == 0)
		{
			entity.guid = guid;
			entity.typeCode = typeCode;
			entity.appearance = appearance;
		}
		else if (entity.appearance != appearance)
		{
			entity.registered = false;
			entity.appearance = appearance;
			entity.typeCode = typeCode;
		}

		entity.area = area;
		entity.localCount = localCount;
		entity.x = x;
		entity.y = y;
		entity.z = z;
		entity.heading = heading;
		entity.hpPermille = hpPermille;
		entity.flags = flags;
		entity.targetPlayerId = targetPlayerId;
		entity.seenThisFrame = true;
		entity.lastSeenMs = g_batchNowMs;

		return true;
	}

	void NpcNet::SetSuspended(bool suspended)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (g_suspended == suspended)
			return;

		g_suspended = suspended;

		if (suspended)
		{
			std::vector<std::string> freeFields;
			int freeCount = 0;

			for (auto& pair : g_owned)
			{
				if (!pair.second.registered)
					continue;

				freeFields.push_back(std::to_string(pair.first));
				freeCount++;

				if (freeCount >= kDelPerPacket)
				{
					freeFields.insert(freeFields.begin(), std::to_string(freeCount));
					Send("NPCFREE", freeFields);
					freeFields.clear();
					freeCount = 0;
				}
			}

			if (freeCount > 0)
			{
				freeFields.insert(freeFields.begin(), std::to_string(freeCount));
				Send("NPCFREE", freeFields);
			}

			g_ownedRemoved.clear();
			g_owned.clear();
			g_statSuspends++;
		}
	}

	void NpcNet::EndOwned()
	{
		Outbox outbox;
		PacketSender sender;

		{
			std::lock_guard<std::mutex> lock(g_mutex);

			const long long now = NowMs();

			if (g_suspended)
			{
				g_ownedRemoved.clear();
				return;
			}

			for (auto it = g_owned.begin(); it != g_owned.end();)
			{
				if (it->second.seenThisFrame || (now - it->second.lastSeenMs) < kOwnedGraceMs)
				{
					++it;
					continue;
				}

				if (it->second.registered)
					g_ownedRemoved.push_back(it->first);

				it = g_owned.erase(it);
			}

			if (now >= g_nextSnapshotMs)
			{
				g_nextSnapshotMs = now + g_snapshotIntervalMs;
				FlushOwned(now, outbox);
			}

			FlushAcks(outbox);

			if (outbox.empty())
				return;

			sender = g_sender;
		}

		if (!sender)
			return;

		for (const auto& packet : outbox)
		{
			if (packet.second.size() > 1)
				sender(packet.first, packet.second);
		}
	}

	int NpcNet::Pull()
	{
		std::vector<int> wanted;
		PacketSender sender;
		int result = 0;

		{
		std::lock_guard<std::mutex> lock(g_mutex);

		g_commands.clear();
		g_commandsGeneration.fetch_add(1, std::memory_order_relaxed);

		const long long serverNow = ServerNow();

		for (auto it = g_replicas.begin(); it != g_replicas.end();)
		{
			Replica& replica = it->second;

			if (replica.despawn)
			{
				if (replica.dead && !replica.deathEmitted && replica.localGuid != 0)
				{
					ReplicaCommand command;
					command.canonicalId = replica.canonicalId;
					command.op = static_cast<int>(ReplicaOp::Kill);
					command.localGuid = replica.localGuid;
					g_commands.push_back(command);

					replica.deathEmitted = true;
					replica.despawnAfterMs = serverNow + kDeathAnimGraceMs;
					g_statKills++;

					++it;
					continue;
				}

				if (replica.despawnAfterMs != 0 && serverNow < replica.despawnAfterMs)
				{
					++it;
					continue;
				}

				if (replica.localGuid != 0 || replica.spawnEmitted)
				{
					ReplicaCommand command;
					command.canonicalId = replica.canonicalId;
					command.op = static_cast<int>(ReplicaOp::Despawn);
					command.localGuid = replica.localGuid;
					g_commands.push_back(command);
					g_statDespawns++;
				}

				it = g_replicas.erase(it);
				continue;
			}

			if ((serverNow - replica.lastPacketMs) > kReplicaStaleMs)
			{
				if (replica.localGuid != 0 || replica.spawnEmitted)
				{
					ReplicaCommand command;
					command.canonicalId = replica.canonicalId;
					command.op = static_cast<int>(ReplicaOp::Despawn);
					command.localGuid = replica.localGuid;
					g_commands.push_back(command);
					g_statDespawns++;
				}

				it = g_replicas.erase(it);
				continue;
			}

			ReplicaCommand command;
			command.canonicalId = replica.canonicalId;
			command.localGuid = replica.localGuid;

			long long delayMs = g_interpDelayMs;

			if (replica.avgIntervalMs > 0)
			{
				const long long needed = replica.avgIntervalMs * 2 + 15;

				if (needed > delayMs)
					delayMs = needed;
			}

			if (delayMs > kMaxInterpDelayMs)
				delayMs = kMaxInterpDelayMs;

			const long long renderTime = serverNow - delayMs;

			if (!Interpolate(replica, renderTime, command))
			{
				++it;
				continue;
			}

			if (replica.localGuid == 0)
			{
				if (replica.dead)
				{
					it = g_replicas.erase(it);
					continue;
				}

				if (replica.unspawnable)
				{
					++it;
					continue;
				}

				const bool describable = !replica.typeCode.empty() && replica.typeCode != "-";

				if (!describable || replica.spawnAttempts >= kSpawnAttemptLimit)
				{
					if ((serverNow - replica.wantedAtMs) >= kWantIntervalMs)
					{
						replica.wantedAtMs = serverNow;
						replica.spawnAttempts = 0;
						wanted.push_back(replica.canonicalId);
						g_statWants++;
					}

					++it;
					continue;
				}

				command.op = static_cast<int>(ReplicaOp::Spawn);
				command.typeCode = replica.typeCode;
				command.appearance = replica.appearance;
				replica.spawnRequested = true;
				replica.spawnEmitted = true;
				replica.spawnAttempts++;
				g_statSpawns++;
			}
			else if (replica.dead && !replica.deathEmitted)
			{
				command.op = static_cast<int>(ReplicaOp::Kill);
				replica.deathEmitted = true;
				g_statKills++;
			}
			else if (replica.promote)
			{
				command.op = static_cast<int>(ReplicaOp::Promote);
				g_statPromotions++;
			}
			else
			{
				command.op = static_cast<int>(ReplicaOp::Drive);
			}

			g_commands.push_back(command);
			++it;
		}

		result = static_cast<int>(g_commands.size());

		if (!wanted.empty())
			sender = g_sender;
		}

		if (!wanted.empty() && sender)
		{
			std::vector<std::string> fields;
			int count = 0;

			for (size_t i = 0; i < wanted.size() && count < kWantPerPacket; ++i)
			{
				fields.push_back(std::to_string(wanted[i]));
				count++;
			}

			fields.insert(fields.begin(), std::to_string(count));

			if (fields.size() > 1)
				sender("NPCWANT", fields);
		}

		return result;
	}

	void NpcNet::MarkUnspawnable(int canonicalId)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		auto it = g_replicas.find(canonicalId);

		if (it != g_replicas.end())
		{
			it->second.unspawnable = true;
			g_statUnspawnable++;
		}
	}

	int NpcNet::PullDrops()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		return static_cast<int>(g_dropGuids.size());
	}

	int NpcNet::Drop(int index)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (index < 0 || index >= static_cast<int>(g_dropGuids.size()))
			return 0;

		return g_dropGuids[index];
	}

	void NpcNet::ClearDrops()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		g_dropGuids.clear();
	}

	int NpcNet::PullKills()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		return static_cast<int>(g_killGuids.size());
	}

	int NpcNet::KillGuid(int index)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (index < 0 || index >= static_cast<int>(g_killGuids.size()))
			return 0;

		return g_killGuids[index];
	}

	void NpcNet::ClearKills()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		g_killGuids.clear();
	}

	const ReplicaCommand* NpcNet::Command(int index)
	{
		static thread_local unsigned long long cachedGeneration = ~0ull;
		static thread_local int cachedIndex = -1;
		static thread_local const ReplicaCommand* cachedCommand = nullptr;

		if (cachedGeneration == g_commandsGeneration.load(std::memory_order_relaxed)
			&& cachedIndex == index)
		{
			return cachedCommand;
		}

		std::lock_guard<std::mutex> lock(g_mutex);

		cachedGeneration = g_commandsGeneration.load(std::memory_order_relaxed);
		cachedIndex = index;

		if (index < 0 || index >= static_cast<int>(g_commands.size()))
			cachedCommand = nullptr;
		else
			cachedCommand = &g_commands[index];

		return cachedCommand;
	}

	void NpcNet::Bind(int index, int localGuid)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (index < 0 || index >= static_cast<int>(g_commands.size()))
			return;

		const int canonicalId = g_commands[index].canonicalId;
		auto it = g_replicas.find(canonicalId);

		if (it == g_replicas.end())
			return;

		it->second.localGuid = localGuid;
		g_commands[index].localGuid = localGuid;
	}

	void NpcNet::Forget(int canonicalId)
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		g_replicas.erase(canonicalId);
	}

	void NpcNet::Take(int canonicalId, int localGuid)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (canonicalId <= 0 || localGuid == 0)
			return;

		g_replicas.erase(canonicalId);

		std::vector<std::string> fields;
		fields.push_back("1");
		fields.push_back(std::to_string(canonicalId));
		fields.push_back(std::to_string(localGuid));

		Send("NPCTAKE", fields);
	}

	int NpcNet::ReportHit(int canonicalId, int permille)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (canonicalId <= 0 || permille <= 0)
			return 0;

		if (static_cast<int>(g_pending.size()) >= kMaxPendingHits)
			return 0;

		PendingHit pending;
		pending.eventId = g_nextEventId++;
		pending.canonicalId = canonicalId;
		pending.permille = permille > 1000 ? 1000 : permille;
		pending.atServerMs = ServerNow();

		g_pending.push_back(pending);
		g_pendingCount.store(static_cast<int>(g_pending.size()), std::memory_order_relaxed);

		std::vector<std::string> fields;
		fields.push_back("1");
		fields.push_back(std::to_string(pending.canonicalId));
		fields.push_back(std::to_string(pending.permille));
		fields.push_back(std::to_string(pending.eventId));
		fields.push_back(std::to_string(pending.atServerMs));

		Send("NPCHIT", fields);
		g_statHitsSent++;

		return pending.eventId;
	}

	int NpcNet::PullHits()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		return static_cast<int>(g_hits.size());
	}

	const InboundHit* NpcNet::Hit(int index)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (index < 0 || index >= static_cast<int>(g_hits.size()))
			return nullptr;

		return &g_hits[index];
	}

	void NpcNet::AckHit(int index, int resultPermille, bool accepted)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (index < 0 || index >= static_cast<int>(g_hits.size()))
			return;

		const InboundHit& hit = g_hits[index];

		g_ackQueue.push_back(std::to_string(hit.attackerPlayerId));
		g_ackQueue.push_back(std::to_string(hit.eventId));
		g_ackQueue.push_back(std::to_string(resultPermille));
		g_ackQueue.push_back(accepted ? "1" : "0");
	}

	void NpcNet::ClearHits()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		g_hits.clear();
	}

	int NpcNet::PullAcks()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		return static_cast<int>(g_acks.size());
	}

	const HitAck* NpcNet::Ack(int index)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (index < 0 || index >= static_cast<int>(g_acks.size()))
			return nullptr;

		return &g_acks[index];
	}

	void NpcNet::ClearAcks()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		g_acks.clear();
	}

	void NpcNet::SetPlayerPaused(int playerId, bool paused)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		if (playerId > 0)
			g_pausedPlayers[playerId] = paused;
	}

	bool NpcNet::IsPlayerPaused(int playerId)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		auto it = g_pausedPlayers.find(playerId);

		return it != g_pausedPlayers.end() && it->second;
	}

	int NpcNet::ScaleMilli(int npcId)
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		auto it = g_scales.find(npcId);

		return it == g_scales.end() ? 1000 : it->second;
	}

	int NpcNet::ScaleGeneration()
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		return static_cast<int>(g_scaleGeneration);
	}

	long long NpcNet::ServerNowMs()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		return ServerNow();
	}

	int NpcNet::LatencyMs()
	{
		std::lock_guard<std::mutex> lock(g_mutex);
		return g_latencyMs;
	}

	std::string NpcNet::Report()
	{
		std::lock_guard<std::mutex> lock(g_mutex);

		return "owned=" + std::to_string(g_owned.size())
			+ " replicas=" + std::to_string(g_replicas.size())
			+ " snapshots=" + std::to_string(g_statSnapshotsSent)
			+ " adds=" + std::to_string(g_statAdds)
			+ " updates=" + std::to_string(g_statUpdates)
			+ " addBudgetHit=" + std::to_string(g_statAddBudgetHit)
			+ " updBudgetHit=" + std::to_string(g_statUpdBudgetHit)
			+ " removals=" + std::to_string(g_statRemovals)
			+ " spawns=" + std::to_string(g_statSpawns)
			+ " despawns=" + std::to_string(g_statDespawns)
			+ " kills=" + std::to_string(g_statKills)
			+ " promotions=" + std::to_string(g_statPromotions)
			+ " wants=" + std::to_string(g_statWants)
			+ " reregisters=" + std::to_string(g_statReregisters)
			+ " giveNacks=" + std::to_string(g_statGiveNacks)
			+ " unspawnable=" + std::to_string(g_statUnspawnable)
			+ " suspends=" + std::to_string(g_statSuspends)
			+ " suspended=" + (g_suspended ? "yes" : "no")
			+ " hitsSent=" + std::to_string(g_statHitsSent)
			+ " hitsIn=" + std::to_string(g_statHitsIn)
			+ " acksIn=" + std::to_string(g_statAcksIn)
			+ " extrapolated=" + std::to_string(g_statExtrapolated)
			+ " starved=" + std::to_string(g_statStarved)
			+ " pending=" + std::to_string(g_pending.size())
			+ " latencyMs=" + std::to_string(g_latencyMs)
			+ " clock=" + (g_clockReady ? "ready" : "cold");
	}

}
