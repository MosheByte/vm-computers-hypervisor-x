# HyperVisor X — 1.2.0

Security and robustness release after two independent end-to-end reviews. **Recommended for every
server.** Renamed to *HyperVisor X* (an unofficial successor to Delta2Force's VM Computers).

## Security
- Program paths, host window capture and the VirtualBox web-service setting can only be changed at
  the host machine or in the config file, never by operators over the network; UNC/SMB/URL paths are
  never executed and an override must be the expected program.
- QEMU: QMP, VNC and the GPIO serial port listen on a private loopback address guests cannot reach
  (unix socket for QMP on macOS) and VNC needs a random per-boot password.
- VirtualBox: the web service runs with a temporary random `VBoxAuthSimple` account; the previous
  setting is restored when the world closes (or on the next start after a crash), leftover
  `vboxwebsrv` processes are cleaned up, and guests cannot reach the host's loopback.
- Host window capture and files linked from the host's PC are operator-only.
- VM names and other text are stripped of control characters (no `.vmx` key injection).
- Every player-triggered start (PC, rack, laptop, focus screen) checks permissions and quotas:
  running VMs and RAM per player, `minPermissionForHostBackends` (default: operators).
- The server decides which screens a player may receive (distance, ownership, max 6 streams).
- Input, screen buttons, library and store requests are rate-limited; the store needs the tablet in
  hand, has a cooldown and at most two pods in flight.
- Downloads are pinned to SHA-256 (Tiny Core 16.2, FreeDOS 1.4, ReactOS via GitHub digests, embedded
  kernels from a pinned commit); redirects never downgrade https to http.

## Lifecycle & data
- VM disks live in the world save (`<world>/hypervisorx/vms/`); deleting a VM removes its files even
  when it is off; orphaned folders are cleaned at start; `/hvx gc <days> [confirm]`.
- Cancelling a boot (force off, delete, world close) no longer leaves a hypervisor running.
- Library disk images are copied per VM instead of being booted (and modified) in place.
- Suspend/resume: VMware keeps its `.vmx` untouched while suspended, VirtualBox skips reconfiguring a
  saved machine, QEMU suspends into an internal qcow2 snapshot and resumes with `-loadvm`.
- VMware without VMware Tools: "Shut down" now powers off after 10 s instead of hanging.
- Breaking a computer asks the guest to shut down first and only pulls the plug after a minute.
- Server racks keep their parts inside the item like cases; delivery crates belong to the buyer for
  ten minutes; pods never fall into the void or land where the buyer could not build.
- `/hvx reload` really re-reads the config file.

## Gameplay
- 39 crafting recipes: every computer, display, laptop and part can be crafted from vanilla materials,
  each tier upgrading the previous one.
- Bundles are always 10 % cheaper than their parts; currency rebalanced (emerald 6, diamond 16,
  netherite 64) and overpayment is returned as change.

## Interface & art
- Crisp small text at every GUI scale, readable section labels, responsive PC screen (320 px),
  short access labels with full tooltips, hint when the target field does not apply, coin prices,
  lighter product wells, opaque focus bar, keys released when the window loses focus.
- Inventory models for the curved ultrawide and monitor arms now show their panels; GPU models have
  a slot bracket; subtler retro turbo display; CRT glass with depth.

## Performance
- One shared `vmrun list` for all VMware VMs; VirtualBox state through the open session and
  unchanged screenshots skipped; bounded input queue; client decoder drops a backlog and asks for a
  keyframe; no per-upload allocations; the singleplayer stream path no longer caches raw tiles.

## Fixes
- RISC-V core: byte and half-word accesses may use the last bytes of RAM.
- Version ranges: Minecraft 1.21.1 exactly, NeoForge 21.1.200+, Fabric API 0.100+.
- Release jars no longer contain the developer smoke test.
- CI publishes the tested jars and pins the boot-test image by checksum.

# HyperVisor X — 1.1.0

Visual overhaul and a verified VMware path.

## Design
- Every block model rebuilt from real hardware references: beige AT mini-tower with CD-ROM, floppy,
  turbo display and key lock; 14" CRT with recessed tube; thin-bezel 24" monitor on a brushed stand;
  panoramic dual-glass gaming tower (vertical RGB intake fans, top radiator, PSU shroud, motherboard
  with VRM heatsinks and DIMM slots); 2U rack servers with hot-swap caddies and live drive LEDs;
  aluminium and rugged laptops; full-size 104-key RGB keyboard with per-key backlight; bookshelf
  speakers with woofer, tweeter and bass port; cargo crate and drop pod.
- The tower interior is built from the parts you install: graphics card (GT / GX / Titan models with
  their own fans), RGB RAM sticks per DIMM slot, AIO pump head with LCD, add-in card and SSD.
- New 32x32 item icons for every part (IHS-capped CPUs, heat-spreader RAM with light bar, triple-fan
  GPUs, 3.5"/2.5"/M.2 drives, USB stick, PCIe cards, blades) and ghost icons for empty typed slots.
- New UI design system: dark dashboard windows, rounded cards with section labels, icon buttons with
  tooltips, status pills, auto-fitting labels (nothing spills out of its box), a proper inventory
  grid, sidebar store navigation and a cart with per-line totals.
- Curved ultrawide: rear light bar, thinner bezels and no seams between the curve segments.

## Engines
- VMware Workstation Pro **26H1u1** verified end to end (install, detection, VM creation, VNC
  streaming, 1024x768 desktop in-game); status text no longer shows a stale resolution.

# HyperVisor X — 1.0.0

First release for Minecraft **1.21.1** on **NeoForge** and **Fabric** (Java 21).

## Highlights
- Five engines behind one abstraction: **VMware Workstation/Player/Fusion**, **VirtualBox 6.1/7.x**,
  **QEMU** with KVM / WHPX (works with Hyper-V and WSL2 enabled) / HVF, the **zero-install embedded
  RISC-V MicroVM** (boots Linux 6.1 in about a second), and **host window capture** (Windows, view only).
- Server-hosted VMs streamed to every player with a **delta-tile codec** (64x64 tiles, XOR deltas,
  deflate, optional RGB565, per-player bandwidth budget). Singleplayer uses an uncompressed in-memory path.
- Client textures are updated with **dirty-rectangle PBO uploads**, trilinear mipmaps, anisotropic
  filtering and a **sharp-bilinear upscaling shader**.
- **Focus mode**: every key (even Escape) goes to the VM; Right Ctrl (rebindable) gives control back.
- Hardware: retro PC, **RGB tempered-glass tower** that shows its installed parts, stackable
  **server rack cluster** (up to 4 VMs), 24" / CRT / **curved ultrawide** / **dual & triple arm** monitors,
  seamless **video walls up to 32x18**, **Pro & Rugged laptops** with battery, desk charging,
  picture-in-picture and Wi-Fi remote desktop, keyboard, mouse, speakers.
- Parts: 4 CPUs, 6 RAM sticks, 4 GPUs (720p to 4K, up to 3 displays), HDD/SSD/NVMe/enterprise drives,
  portable **USB flash drives** (VMDK, move files between PCs), **Redstone GPIO PCIe card**,
  Wi-Fi card, server blades.
- **Redstone GPIO**: guest programs read/drive redstone, read and move items in adjacent
  chests through COM1 / ttyS0 (embedded VM: OSC escape codes).
- **Positional VM audio** for QEMU guests (VNC audio extension), louder through desk speakers.
- **OrbitTech Store 2.0** with categories, search, bundles and a supply pod that lands from orbit.
- **OS library**: hypervisor health check, one-click downloads (Alpine with SHA-256 verification,
  Tiny Core, Debian netinst, FreeDOS, ReactOS, embedded Linux, embedded DOOM), Windows 10/11 presets,
  in-game file browser and native file dialog for importing `.iso/.img/.vmdk/.vdi/.qcow2/.vhd(x)`.
- Permissions per VM (private / others can watch / public) plus trusted players; `/hvx` admin
  command with `selftest` and `demo`.
- English and Hebrew translations.
