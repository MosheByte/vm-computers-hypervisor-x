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
