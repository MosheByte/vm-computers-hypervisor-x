# VM Computers: HyperVisor X — 1.1.0

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

# VM Computers: HyperVisor X — 1.0.0

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
