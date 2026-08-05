#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace w3mp
{
	enum class PacketRoute
	{
		Unknown,
		Control,
		Realtime,
		Reliable,
		Bulk
	};

	struct PacketSpec
	{
		std::uint16_t id;
		const char* opcode;
		PacketRoute route;
		bool clientToServer;
		bool serverToClient;
	};

	const PacketSpec* FindPacketSpec(const std::string& opcode);
	const PacketSpec* FindPacketSpec(std::uint16_t id);
	PacketRoute RouteForPacket(const std::string& opcode);
	bool EncodePacketText(const std::string& packet, std::vector<std::uint8_t>& output);
	bool DecodePacket(const std::uint8_t* data, std::size_t length, std::string& output);
	bool DecodeDatagram(
		const std::uint8_t* data,
		std::size_t length,
		std::vector<std::string>& output);
}
