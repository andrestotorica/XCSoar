// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright The XCSoar Project

#include "System.hpp"
#include "system/Process.hpp"
#include "system/FileUtil.hpp"

#include <unistd.h>
#include <sys/stat.h>
#include <fmt/format.h>

uint_least8_t
OpenvarioGetBrightness() noexcept
{
  char line[4];
  int result = 10;

  if (File::ReadString(Path("/sys/class/backlight/lcd/brightness"), line, sizeof(line))) {
    result = atoi(line);
  }

  return result;
}

void
OpenvarioSetBrightness(uint_least8_t value) noexcept
{
  if (value < 1) { value = 1; }
  if (value > 10) { value = 10; }

  File::WriteExisting(Path("/sys/class/backlight/lcd/brightness"), fmt::format_int{value}.c_str());
}

SSHStatus
OpenvarioGetSSHStatus()
{
  if (Run("/bin/systemctl", "--quiet", "is-enabled", "dropbear.socket")) {
    return SSHStatus::ENABLED;
  } else if (Run("/bin/systemctl", "--quiet", "is-active", "dropbear.socket")) {
    return SSHStatus::TEMPORARY;
  } else {
    return SSHStatus::DISABLED;
  }
}

bool
OpenvarioEnableSSH(bool temporary)
{
  if (temporary) {
    return Run("/bin/systemctl", "disable", "dropbear.socket") && 
      Run("/bin/systemctl", "start", "dropbear.socket");
  }

  return Run("/bin/systemctl", "enable", "--now", "dropbear.socket");
}

bool
OpenvarioDisableSSH()
{
  return Run("/bin/systemctl", "disable", "--now", "dropbear.socket");
}