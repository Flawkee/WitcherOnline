#pragma once
#include <cstdint>
#include <string>

namespace w3mp {

	struct ModuleRegion
	{
		uint8_t* base = nullptr;
		size_t size = 0;

		bool IsValid() const { return base != nullptr && size > 0; }
	};

	class GameModule {
	public:
		static bool Resolve();

		static const ModuleRegion& Image() { return image_; }
		static const ModuleRegion& Text() { return text_; }
		static const ModuleRegion& Self() { return self_; }
		static const std::string& Version() { return version_; }
		static const std::string& HostName() { return hostName_; }

		static bool IsResolved() { return image_.IsValid(); }
		static bool IsSelfHosted() { return selfHosted_; }
		static bool IsInsideSelf(const uint8_t* address);

	private:
		static ModuleRegion image_;
		static ModuleRegion text_;
		static ModuleRegion self_;
		static std::string version_;
		static std::string hostName_;
		static bool selfHosted_;

		static bool ResolveImage();
		static bool ResolveSelf();
		static bool ResolveTextSection();
		static void ResolveVersion();
	};

}
