# Changelog

## 1.3.5
- Fixed mouse look in games running inside VMware VMs. The game ignored big pointer jumps, so mouse
  motion is now sent in small steps (max 24 px every 10 ms)
- VMware VMs use the gaming mouse mode

## 1.3.4
- Captured mouse sends relative motion now. Fixes the jittery / stuck / reversed camera in games
  inside the VM
- Zoom moves the camera closer to the screen instead of changing the FOV. Mouse wheel to zoom, it
  remembers the last value
- Small hint in the corner showing how to release the mouse
- Monitor power LED is hidden while you use it

## 1.3.3
- Using a screen doesn't take over the whole view anymore, the camera just turns to it.
  Ctrl+Alt+Enter for full window
- Mouse capture like in VMware/VirtualBox. Ctrl+Alt releases it, clicking the screen captures it
- The guest's cursor is drawn on the screen (it was missing with VMware Tools and on QEMU)
- Fixed tearing and flickering
- VMware: a VM that's already running gets attached instead of failing to start
- Smaller power LED

## 1.3.2
- Guest tools are on by default: VMware Tools, VirtualBox Guest Additions or virtio-win for QEMU.
  Wrench icon to turn it off
- Unattended Windows 10/11 install works on VirtualBox and QEMU too
- Button to install guest tools into an already installed Windows
- The mod builds the Windows answer disc itself, mkisofs isn't needed anymore

## 1.3.1
- Screens fill the whole panel, no black bars. Settings > Display > Screen picture for the old
  behaviour
- Curved ultrawide is smoother (64 segments)

## 1.3.0
- Using a computer: the camera moves in front of the monitor, the world stays visible and the mouse
  works directly on the in-world screen. Full window view is still there
- 3D motherboard view. Install and remove parts on the board, with animations, sounds and a POST beep
- Unattended Windows 10/11 install on VMware
- VMware: uses another VNC port if it's taken, stops VMs whose display doesn't respond
- VMware VMs run without 3D acceleration (VNC was black with it)
- VirtualBox: clearer error when the start fails because another program leaks handles

## 1.2.1
- Fixed VMware failing with "The operation was canceled" when the RAM wasn't a multiple of 4 MB
- Shows VMware's actual error when a start fails
- Text field placeholders don't overflow anymore

## 1.2.0
Security update, please update if you run a server. Also renamed the mod to HyperVisor X.

Security
- Program paths, window capture and VirtualBox web service settings can only be changed on the host
  or in the config. Network paths (UNC/SMB/URL) are never executed
- QEMU QMP/VNC/serial listen on a private loopback address, VNC has a random password
- VirtualBox web service uses a temporary random account, your old setting is restored after
- Window capture and files from the host PC are op only
- Control characters are stripped from VM names
- Permission and quota checks on every VM start
- The server decides which screens a player gets (distance, owner, max 6 streams)
- Rate limits on input, buttons, library and store
- All downloads are checked with SHA-256

Art / UI
- Same texel density everywhere, mipmap-safe atlases, break particles per model
- Glowing RGB, fans, keyboard and rack LEDs (NeoForge and Fabric)
- Reworked gaming tower, server rack, mouse, keyboard, speakers, rugged laptop, drop pod, crate and
  monitor arms
- Curved ultrawide and monitor arms aren't black anymore, CRT scanlines
- New 16x16 item icons, GUI icons and logo
- Lots of GUI fixes (labels, dropdowns, tooltips, scrollbars, small text scaling, RTL for Hebrew)

Input / streaming
- Absolute USB pointer for VMware guests
- Pipelined VNC requests, VMware went from ~19 to ~40 FPS
- Smooth scrolling in lists, touchpad scrolling fixed

VMs
- VM disks are stored in the world save, deleted VMs get cleaned up, new `/hvx gc`
- Cancelling a boot doesn't leave a hypervisor running
- Library images are copied per VM
- Suspend/resume fixes for VMware, VirtualBox and QEMU
- Breaking a computer shuts the guest down first
- Server racks keep their parts when broken
- `/hvx reload` re-reads the config

Gameplay
- 39 crafting recipes, every part is craftable
- Bundles are 10% cheaper, prices rebalanced (emerald 6, diamond 16, netherite 64), you get change back

Other
- Shared `vmrun list`, fewer VirtualBox calls, bounded input queue, the decoder drops a backlog and
  asks for a keyframe
- RISC-V core: byte/half-word access at the end of RAM
- Version ranges: MC 1.21.1, NeoForge 21.1.200+, Fabric API 0.100+
- Release jars don't include the dev smoke test anymore

## 1.1.0
- All block models redone based on real hardware
- The gaming tower shows the installed parts (GPU, RAM, AIO, cards, SSD)
- New item icons and a new UI style
- Curved ultrawide light bar, no seams
- Tested with VMware Workstation Pro 26H1u1

## 1.0.0
First release. Minecraft 1.21.1, NeoForge and Fabric.
- Engines: VMware, VirtualBox, QEMU (KVM/WHPX/HVF), a built-in RISC-V Linux VM, and window capture
  on Windows
- VMs run on the server and stream to the players
- PCs, gaming tower, server racks, monitors, CRT, curved ultrawide, monitor arms, video walls, laptops
- USB drives, redstone GPIO card, Wi-Fi card
- Sound from QEMU guests
- Store with drop pod delivery
- OS library with downloads and import
- Permissions per VM, `/hvx` command
- English and Hebrew
