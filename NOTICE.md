# Notices and credits

**HyperVisor X**, Copyright (C) 2026 MosheByte and contributors.

Short version: it's GPL, same as the original mod. The proper wording:

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See `LICENSE`.

## Original work

HyperVisor X is a from-scratch successor to **VM Computers** (2020) by **Louis Grünenwald
(Delta2Force)** (<https://github.com/delta2force/MCVmComputers>), which is licensed under the
GNU GPL v3. The idea, the "order parts from a satellite" gameplay loop and the name are his.
There's no code or assets copied from the original in here. I still put this project under the same
GPL v3 so it stays compatible with the original project's community and gives something back to it.

## Third-party components

| Component | Where | License |
|---|---|---|
| mini-rv32ima RISC-V core by Charles Lohr | ported to Java in `common/.../backend/embedded/Rv32Cpu.java`; reference DTB in `hypervisorx/embedded/minirv32-64mb.dtb` | MIT / BSD / CC0 (author's choice) |
| Spleen 8x16 bitmap font by Frédéric Cambus | converted glyph table `hypervisorx/fonts/spleen-8x16.hvxf` | BSD 2-Clause (`hypervisorx/fonts/SPLEEN-LICENSE.txt`) |
| MultiLoader-Template by Jared | Gradle build layout | CC0 1.0 |

## Downloaded at runtime (not distributed with the mod)

* The Linux / DOOM kernel images for the embedded MicroVM come from
  <https://github.com/cnlohr/mini-rv32ima-images> (Linux kernel + BusyBox under the GPL v2). They're
  **not bundled** in the mod. The game downloads them the first time you use them, from a pinned
  commit, and checks their SHA-256.
* The DOOM image contains the **shareware** episode of DOOM (DOOM1.WAD), © id Software. id's
  shareware licence allows copying it unmodified and free of charge. It's not open source and it's
  not covered by HyperVisor X's GPL. HyperVisor X never sells it or includes it in its own files.
* Operating systems from the OS library get fetched from their official mirrors (Alpine Linux,
  Tiny Core Linux, Debian, FreeDOS, ReactOS) under their own licenses. Windows is never downloaded
  automatically; the presets just link to Microsoft's official download pages.

All textures, models and sound definitions in this repo come out of `tools/gen_assets.py` (sounds
reuse Minecraft's own sound files by reference).

"VMware", "VirtualBox", "QEMU", "Windows" and the other names are trademarks of their owners.
This project isn't affiliated with Mojang, Microsoft, Broadcom/VMware or Oracle.
