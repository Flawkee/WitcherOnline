#include "pch.h"
#include "PacketCodec.h"

#include <array>
#include <charconv>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <string_view>

namespace w3mp
{
	namespace
	{
		constexpr std::uint16_t kPacketMagic = 0xD731u;
		constexpr std::uint16_t kBatchMagic = 0xD732u;
		constexpr std::size_t kMaxPacketBytes = 1u << 20;
		constexpr std::size_t kMaxFields = 255;
		constexpr std::uint8_t kString = 0;
		constexpr std::uint8_t kInt32 = 1;
		constexpr std::uint8_t kInt64 = 2;
		constexpr std::uint8_t kFloat32 = 3;
		constexpr std::uint8_t kString16 = 4;

		constexpr std::array<PacketSpec, 67> kPackets = {{
			{1, "HELLO", PacketRoute::Control, true, false},
			{2, "HELLOACK", PacketRoute::Control, false, true},
			{3, "PING", PacketRoute::Realtime, true, false},
			{4, "PONG", PacketRoute::Realtime, false, true},
			{5, "ERROR", PacketRoute::Control, false, true},
			{6, "KICK", PacketRoute::Reliable, false, true},
			{10, "MOVE", PacketRoute::Realtime, true, true},
			{11, "UPDATE1A", PacketRoute::Realtime, true, true},
			{12, "UPDATE1B", PacketRoute::Realtime, true, true},
			{13, "UPDATE2A", PacketRoute::Realtime, true, true},
			{14, "UPDATE2B", PacketRoute::Realtime, true, true},
			{15, "UPDATE3", PacketRoute::Realtime, true, true},
			{16, "UPDATE4", PacketRoute::Realtime, true, true},
			{20, "PRESP", PacketRoute::Reliable, true, false},
			{21, "PCOOP", PacketRoute::Reliable, true, false},
			{22, "SCENE", PacketRoute::Reliable, true, true},
			{23, "QITEM", PacketRoute::Reliable, true, true},
			{24, "PJOIN", PacketRoute::Reliable, true, false},
			{25, "PLEAVE", PacketRoute::Reliable, true, false},
			{26, "PSTATE", PacketRoute::Reliable, true, false},
			{27, "PARTY", PacketRoute::Reliable, false, true},
			{28, "PINVITE", PacketRoute::Reliable, false, true},
			{29, "PSTATEF", PacketRoute::Reliable, false, true},
			{30, "PVIS", PacketRoute::Realtime, false, true},
			{31, "TPREQ", PacketRoute::Reliable, true, false},
			{32, "TPPOS", PacketRoute::Reliable, false, true},
			{33, "PSCALE", PacketRoute::Reliable, true, false},
			{34, "PSCALEACK", PacketRoute::Reliable, false, true},
			{40, "SAVEBEG", PacketRoute::Bulk, true, true},
			{41, "SAVECHK", PacketRoute::Bulk, true, true},
			{42, "SAVEEND", PacketRoute::Bulk, true, true},
			{43, "SAVENACK", PacketRoute::Bulk, true, true},
			{44, "SAVEACK", PacketRoute::Bulk, true, true},
			{45, "SAVEWANT", PacketRoute::Bulk, true, false},
			{46, "SAVENEED", PacketRoute::Bulk, false, true},
			{60, "NPCADD", PacketRoute::Reliable, true, false},
			{61, "NPCUPD", PacketRoute::Reliable, true, false},
			{62, "NPCDEL", PacketRoute::Reliable, true, false},
			{63, "NPCHIT", PacketRoute::Reliable, true, false},
			{64, "NPCACK", PacketRoute::Reliable, true, false},
			{65, "NPCTERM", PacketRoute::Reliable, true, false},
			{66, "NPCTAKE", PacketRoute::Reliable, true, false},
			{67, "NPCNOPE", PacketRoute::Reliable, true, false},
			{68, "NPCFREE", PacketRoute::Reliable, true, false},
			{69, "NPCWANT", PacketRoute::Reliable, true, false},
			{70, "NPCNEW", PacketRoute::Reliable, false, true},
			{71, "NPCMOV", PacketRoute::Reliable, false, true},
			{72, "NPCEND", PacketRoute::Reliable, false, true},
			{73, "NPCDEAD", PacketRoute::Reliable, false, true},
			{74, "NPCHITF", PacketRoute::Reliable, false, true},
			{75, "NPCACKF", PacketRoute::Reliable, false, true},
			{76, "NPCKILL", PacketRoute::Reliable, false, true},
			{77, "NPCGIVE", PacketRoute::Reliable, false, true},
			{78, "NPCDROP", PacketRoute::Reliable, false, true},
			{79, "NPCGONE", PacketRoute::Reliable, false, true},
			{80, "NPCSCALE", PacketRoute::Reliable, false, true},
			{81, "NPCREG", PacketRoute::Reliable, false, true},
			{82, "NPCBIND", PacketRoute::Reliable, true, false},
			{83, "NPCFAST", PacketRoute::Realtime, true, true},
			{84, "NPCEVT", PacketRoute::Reliable, true, false},
			{85, "NPCEVTF", PacketRoute::Reliable, false, true},
			{86, "NPCEACK", PacketRoute::Reliable, true, false},
			{90, "TSYNC", PacketRoute::Realtime, true, false},
			{91, "TSYNCR", PacketRoute::Realtime, false, true},
			{92, "PVFXS", PacketRoute::Reliable, true, true},
			{93, "PVFXI", PacketRoute::Reliable, true, true},
			{94, "ANNOUNCE", PacketRoute::Reliable, false, true}
		}};

		void Write16(std::vector<std::uint8_t>& output, std::uint16_t value)
		{
			output.push_back(static_cast<std::uint8_t>(value >> 8));
			output.push_back(static_cast<std::uint8_t>(value));
		}

		void Write32(std::vector<std::uint8_t>& output, std::uint32_t value)
		{
			output.push_back(static_cast<std::uint8_t>(value >> 24));
			output.push_back(static_cast<std::uint8_t>(value >> 16));
			output.push_back(static_cast<std::uint8_t>(value >> 8));
			output.push_back(static_cast<std::uint8_t>(value));
		}

		void WriteVar(std::vector<std::uint8_t>& output, std::uint64_t value)
		{
			while ((value & ~0x7full) != 0)
			{
				output.push_back(static_cast<std::uint8_t>((value & 0x7f) | 0x80));
				value >>= 7;
			}
			output.push_back(static_cast<std::uint8_t>(value));
		}

		bool Read16(const std::uint8_t*& cursor, const std::uint8_t* end, std::uint16_t& value)
		{
			if (end - cursor < 2)
				return false;
			value = static_cast<std::uint16_t>((cursor[0] << 8) | cursor[1]);
			cursor += 2;
			return true;
		}

		bool Read32(const std::uint8_t*& cursor, const std::uint8_t* end, std::uint32_t& value)
		{
			if (end - cursor < 4)
				return false;
			value = (static_cast<std::uint32_t>(cursor[0]) << 24)
				| (static_cast<std::uint32_t>(cursor[1]) << 16)
				| (static_cast<std::uint32_t>(cursor[2]) << 8)
				| static_cast<std::uint32_t>(cursor[3]);
			cursor += 4;
			return true;
		}

		bool ReadVar(const std::uint8_t*& cursor, const std::uint8_t* end, std::uint64_t& value)
		{
			value = 0;
			for (int shift = 0; shift < 64; shift += 7)
			{
				if (cursor >= end)
					return false;
				const std::uint8_t current = *cursor++;
				value |= static_cast<std::uint64_t>(current & 0x7f) << shift;
				if ((current & 0x80) == 0)
					return true;
			}
			return false;
		}

		std::vector<std::string> SplitEscaped(const std::string& text)
		{
			std::vector<std::string> parts;
			std::string current;
			bool escaped = false;
			for (char value : text)
			{
				if (escaped)
				{
					if (value == 't') current.push_back('\t');
					else if (value == 'n') current.push_back('\n');
					else if (value == 'r') current.push_back('\r');
					else current.push_back(value);
					escaped = false;
				}
				else if (value == '\\')
				{
					escaped = true;
				}
				else if (value == '\t')
				{
					parts.push_back(std::move(current));
					current.clear();
				}
				else
				{
					current.push_back(value);
				}
			}
			if (escaped)
				current.push_back('\\');
			parts.push_back(std::move(current));
			return parts;
		}

		void AppendEscaped(std::string& output, const std::string& value)
		{
			for (char c : value)
			{
				if (c == '\\') output += "\\\\";
				else if (c == '\t') output += "\\t";
				else if (c == '\n') output += "\\n";
				else if (c == '\r') output += "\\r";
				else output.push_back(c);
			}
		}

		bool FormatFloat(float value, std::string& output)
		{
			char buffer[64]{};
			auto formatted = std::to_chars(buffer, buffer + sizeof(buffer), value);
			if (formatted.ec != std::errc())
				return false;
			output.assign(buffer, formatted.ptr);
			return true;
		}

		void WriteField(std::vector<std::uint8_t>& output, const std::string& value)
		{
			std::int32_t intValue = 0;
			auto intResult = std::from_chars(value.data(), value.data() + value.size(), intValue);
			if (!value.empty() && intResult.ec == std::errc() && intResult.ptr == value.data() + value.size()
				&& std::to_string(intValue) == value)
			{
				output.push_back(kInt32);
				const std::uint32_t zigzag = (static_cast<std::uint32_t>(intValue) << 1)
					^ static_cast<std::uint32_t>(intValue >> 31);
				WriteVar(output, zigzag);
				return;
			}

			std::int64_t longValue = 0;
			auto longResult = std::from_chars(value.data(), value.data() + value.size(), longValue);
			if (!value.empty() && longResult.ec == std::errc() && longResult.ptr == value.data() + value.size()
				&& std::to_string(longValue) == value)
			{
				output.push_back(kInt64);
				const std::uint64_t zigzag = (static_cast<std::uint64_t>(longValue) << 1)
					^ static_cast<std::uint64_t>(longValue >> 63);
				WriteVar(output, zigzag);
				return;
			}

			if (value.find('.') != std::string::npos
				&& value.find_first_of("eE") == std::string::npos)
			{
				char* parsedEnd = nullptr;
				float floatValue = std::strtof(value.c_str(), &parsedEnd);
				std::string canonical;
				if (parsedEnd == value.c_str() + value.size() && std::isfinite(floatValue)
					&& FormatFloat(floatValue, canonical) && canonical == value)
				{
					std::uint32_t bits = 0;
					std::memcpy(&bits, &floatValue, sizeof(bits));
					output.push_back(kFloat32);
					Write32(output, bits);
					return;
				}
			}

			if (value.size() <= 254)
			{
				output.push_back(kString);
				output.push_back(static_cast<std::uint8_t>(value.size()));
			}
			else
			{
				output.push_back(kString16);
				Write16(output, static_cast<std::uint16_t>(value.size()));
			}
			output.insert(output.end(), value.begin(), value.end());
		}

		bool ReadField(
			const std::uint8_t*& cursor,
			const std::uint8_t* end,
			std::string& value)
		{
			if (cursor >= end)
				return false;
			const std::uint8_t type = *cursor++;
			if (type == kInt32)
			{
				std::uint64_t raw = 0;
				if (!ReadVar(cursor, end, raw)) return false;
				const std::uint32_t narrowed = static_cast<std::uint32_t>(raw);
				const std::int32_t decoded = static_cast<std::int32_t>((narrowed >> 1) ^ (0u - (narrowed & 1u)));
				value = std::to_string(decoded);
				return true;
			}
			if (type == kInt64)
			{
				std::uint64_t raw = 0;
				if (!ReadVar(cursor, end, raw)) return false;
				const std::int64_t decoded = static_cast<std::int64_t>((raw >> 1) ^ (0ull - (raw & 1ull)));
				value = std::to_string(decoded);
				return true;
			}
			if (type == kFloat32)
			{
				std::uint32_t raw = 0;
				if (!Read32(cursor, end, raw)) return false;
				float number = 0.0f;
				std::memcpy(&number, &raw, sizeof(number));
				return FormatFloat(number, value);
			}
			std::uint16_t length = 0;
			if (type == kString)
			{
				if (cursor >= end) return false;
				length = *cursor++;
			}
			else if (type == kString16)
			{
				if (!Read16(cursor, end, length)) return false;
			}
			else
			{
				return false;
			}
			if (end - cursor < length)
				return false;
			value.assign(reinterpret_cast<const char*>(cursor), length);
			cursor += length;
			return true;
		}
	}

	const PacketSpec* FindPacketSpec(const std::string& opcode)
	{
		for (const auto& spec : kPackets)
		{
			if (opcode == spec.opcode)
				return &spec;
		}
		return nullptr;
	}

	const PacketSpec* FindPacketSpec(std::uint16_t id)
	{
		for (const auto& spec : kPackets)
		{
			if (id == spec.id)
				return &spec;
		}
		return nullptr;
	}

	PacketRoute RouteForPacket(const std::string& opcode)
	{
		const PacketSpec* spec = FindPacketSpec(opcode);
		return spec == nullptr ? PacketRoute::Unknown : spec->route;
	}

	bool EncodePacketText(const std::string& packet, std::vector<std::uint8_t>& output)
	{
		auto parts = SplitEscaped(packet);
		if (parts.empty() || parts.size() - 1 > kMaxFields)
			return false;
		const PacketSpec* spec = FindPacketSpec(parts.front());
		if (spec == nullptr)
			return false;

		output.clear();
		output.reserve(packet.size() + 16);
		Write16(output, kPacketMagic);
		output.push_back(static_cast<std::uint8_t>(spec->id));
		output.push_back(static_cast<std::uint8_t>(parts.size() - 1));
		for (std::size_t i = 1; i < parts.size(); ++i)
		{
			if (parts[i].size() > (std::numeric_limits<std::uint16_t>::max)())
				return false;
			WriteField(output, parts[i]);
		}
		return output.size() <= kMaxPacketBytes;
	}

	bool DecodePacket(const std::uint8_t* data, std::size_t length, std::string& output)
	{
		if (data == nullptr || length < 4 || length > kMaxPacketBytes)
			return false;
		const std::uint8_t* cursor = data;
		const std::uint8_t* end = data + length;
		std::uint16_t magic = 0;
		if (!Read16(cursor, end, magic) || magic != kPacketMagic || end - cursor < 2)
			return false;
		const std::uint16_t opcodeId = *cursor++;
		const std::uint16_t fieldCount = *cursor++;
		const PacketSpec* spec = FindPacketSpec(opcodeId);
		if (spec == nullptr)
			return false;

		output.assign(spec->opcode);
		for (std::uint16_t i = 0; i < fieldCount; ++i)
		{
			std::string value;
			if (!ReadField(cursor, end, value))
				return false;
			output.push_back('\t');
			AppendEscaped(output, value);
		}
		return cursor == end;
	}

	bool DecodeDatagram(
		const std::uint8_t* data,
		std::size_t length,
		std::vector<std::string>& output)
	{
		output.clear();
		if (data == nullptr || length < 2)
			return false;
		const std::uint8_t* cursor = data;
		const std::uint8_t* end = data + length;
		std::uint16_t magic = 0;
		if (!Read16(cursor, end, magic))
			return false;
		if (magic == kPacketMagic)
		{
			std::string packet;
			if (!DecodePacket(data, length, packet))
				return false;
			output.push_back(std::move(packet));
			return true;
		}
		if (magic != kBatchMagic)
			return false;

		while (cursor < end)
		{
			std::uint16_t packetLength = 0;
			if (!Read16(cursor, end, packetLength) || packetLength < 4 || end - cursor < packetLength)
				return false;
			std::string packet;
			if (!DecodePacket(cursor, packetLength, packet))
				return false;
			output.push_back(std::move(packet));
			cursor += packetLength;
		}
		return !output.empty();
	}
}
