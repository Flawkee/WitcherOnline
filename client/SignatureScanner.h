#pragma once
#include "GameModule.h"
#include <cstdint>
#include <string>
#include <vector>

namespace w3mp {

	class SignaturePattern {
	public:
		static SignaturePattern Parse(const std::string& pattern);

		bool IsValid() const { return !bytes_.empty() && bytes_.size() == mask_.size(); }
		size_t Size() const { return bytes_.size(); }
		bool MatchesAt(const uint8_t* address) const;

	private:
		std::vector<uint8_t> bytes_;
		std::vector<bool> mask_;
	};

	class SignatureScanner {
	public:
		static uint8_t* Find(const ModuleRegion& region, const SignaturePattern& pattern);
		static uint8_t* Find(const ModuleRegion& region, const std::string& pattern);

		static std::vector<uint8_t*> FindAll(const ModuleRegion& region, const SignaturePattern& pattern, size_t limit = 0);

		static uint8_t* FindString(const ModuleRegion& region, const std::string& text);
		static uint8_t* FindRipReference(const ModuleRegion& region, const uint8_t* target);

		static uint8_t* ResolveRelative(uint8_t* instruction, int operandOffset, int instructionLength);
	};

}
