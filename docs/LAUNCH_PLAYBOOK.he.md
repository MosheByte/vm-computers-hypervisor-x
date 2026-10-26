# מדריך השקה והפצה – VM Computers: HyperVisor X 1.0.0

המסמך הזה מיועד לך כמפרסם. כל הטקסטים באנגלית/סינית מוכנים להעתקה כמו שהם.
עדכן כתובות אם שינית שם משתמש או שם מאגר.

- מאגר: https://github.com/MosheBarami/vm-computers-hypervisor-x
- הורדות: https://github.com/MosheBarami/vm-computers-hypervisor-x/releases

---

## 1. הורדה והרצה תוך 2 דקות

1. התקן Minecraft **1.21.1** עם **NeoForge 21.1.x** (או Fabric Loader 0.16+ ו-Fabric API).
   הדרך הקלה: Prism Launcher → Add Instance → 1.21.1 → Version → Install NeoForge.
2. היכנס ל-Releases במאגר והורד **קובץ אחד**:
   - `hypervisorx-neoforge-1.21.1-1.0.0.jar` עבור NeoForge, או
   - `hypervisorx-fabric-1.21.1-1.0.0.jar` עבור Fabric.
   אל תוריד את קובצי `-sources`.
3. העתק את הקובץ לתיקייה `.minecraft/mods` (ב-Prism: לחצן ימני על ה-Instance → Folder → mods).
4. היכנס לעולם Creative עם Cheats והקלד `/hvx demo`.
   נבנה מולך חדר תצוגה: מחשב רטרו עם מסך, מארז RGB, מסך קעור, קיר מסכים 4x3,
   זרוע של 3 מסכים, לפטופ ושני ארונות שרתים. Linux אמיתי עולה תוך שנייה ומופיע על כל המסכים.
5. לחיצה ימנית על מסך (או מקש **V** כשמסתכלים עליו) = מצב פוקוס. כל המקשים עוברים למחשב.
   **Right Ctrl** מחזיר אותך למשחק.

### להפעלת Windows/Linux מלא
- **VMware Workstation Pro** (חינמי לשימוש אישי): פשוט להתקין, המוד מוצא את `vmrun` לבד.
- **QEMU**: להתקין מ-qemu.org ולהפעיל ב-Windows Features את *Windows Hypervisor Platform* (עובד יחד עם Hyper-V/WSL2).
- **VirtualBox 7**: להתקין – שירות ה-web מופעל ומנוהל אוטומטית.
- במשחק: פתח את המחשב → בחר Hypervisor → כפתור **Library** → לשונית *Download OS* (Alpine, Tiny Core, Debian, FreeDOS, ReactOS)
  או *Import from PC* לבחירת קובץ ISO מהמחשב → **Save** → **Power on** → חבר מסך בכבל HDMI.
- לבדיקה מהירה של מה מותקן: `/hvx backends` או `/hvx selftest`.

### בנייה מקוד (אופציונלי)
```bash
git clone https://github.com/MosheBarami/vm-computers-hypervisor-x
cd vm-computers-hypervisor-x
./gradlew build
```
הקבצים נוצרים ב-`neoforge/build/libs` וב-`fabric/build/libs` (נדרש JDK 21).

---

## 2. Modrinth ו-CurseForge

### צעדים
1. Modrinth: https://modrinth.com/dashboard/projects → *Create a project* → סוג **Mod**.
   CurseForge: https://authors.curseforge.com → *Create Project* → Minecraft → Mods.
2. העלה את שני ה-jars כגרסאות נפרדות (Loader: NeoForge / Fabric, Game version: 1.21.1).
   בגרסת Fabric סמן תלות **Fabric API (required)**.
3. License: **GPL-3.0-or-later**. Source: קישור למאגר. Issues: `/issues` של המאגר.
4. העלה את התמונות מ-`docs/screenshots` לגלריה (התמונה הראשית: `hvx-world.png`).
5. לאוטומציה: ב-GitHub → Settings → Secrets and variables → Actions הוסף
   `MODRINTH_TOKEN`, `CURSEFORGE_TOKEN` (secrets) ו-`MODRINTH_ID`, `CURSEFORGE_ID` (variables).
   מעכשיו כל תגית `v*` (למשל `git tag v1.0.1 && git push origin v1.0.1`) בונה, יוצרת Release ב-GitHub
   ומעלה אוטומטית לשתי החנויות. לגרסה שכבר קיימת: Actions → *Publish to Modrinth & CurseForge* →
   *Run workflow* → בחר את התגית ב-"Use workflow from" והקלד אותה (למשל `v1.0.0`).

### שם וכותרת
- **Name:** VM Computers: HyperVisor X
- **Summary (≤ 120 chars):** Build real working PCs, laptops, server racks & video walls that run Windows/Linux via VMware, VirtualBox, QEMU or built-in Linux.

### תגיות
- Modrinth categories: `technology`, `utility`, `decoration`, `management`, `storage` · Environment: Client + Server required.
- CurseForge categories: Technology, Redstone, Utility & QoL, Cosmetic.
- מילות מפתח בתיאור (חשוב לחיפוש): VM Computers, computer, PC, laptop, Windows in Minecraft, Linux, VMware, VirtualBox, QEMU, virtual machine, emulator, server rack, video wall, redstone computer.

### תיאור מוכן (Markdown – להדביק כמו שהוא)
```markdown
# VM Computers: HyperVisor X

**Real operating systems on real in-game computers.** The successor to the legendary *VM Computers* mod — rebuilt from scratch for Minecraft 1.21.1 on NeoForge and Fabric.

### Run anything
- **VMware** Workstation / Player / Fusion, **VirtualBox** 7, **QEMU** with KVM / WHPX / HVF (works with Hyper-V and WSL2 on)
- **No hypervisor? No problem.** A built-in RISC-V computer boots real Linux in about one second
- Mirror a window of your PC (Discord, VS Code, a browser) onto an in-game screen

### Build it
- Retro PCs and **RGB tempered-glass towers that show the parts you install**
- **Server rack clusters**: up to 32 cores, 64 GB RAM and 4 VMs at once
- 24", CRT, **curved 49" ultrawide**, dual & triple monitor arms, **seamless video walls up to 32x18 blocks**
- **Laptops you can use while walking**, with battery, charging and Wi-Fi remote desktop

### Play together
- VMs run on the server and stream to every player with a delta-tile codec — only visible screens use bandwidth
- Positional audio, permissions (private / watch / public), trusted players

### Connect to Minecraft
- **Redstone GPIO card**: Python, C#, Bash or PowerShell inside Windows/Linux reads and drives redstone and moves items between chests

### Plus
- OrbitTech Store 2.0 with bundles and supply pods from orbit
- One-click OS library (Alpine, Tiny Core, Debian, FreeDOS, ReactOS), Windows 10/11 presets, in-game file browser
- `/hvx demo` builds a complete showroom in one command

Open source (GPL-3.0). Original VM Computers idea by Delta2Force.
```

---

## 3. GitHub – Topics, Discussion והגעה לקהל של המאגר המקורי

### Topics (כבר הוגדרו אוטומטית; לעדכון ידני: About → ⚙ → Topics)
`minecraft-mod` `vm-computers` `virtualbox` `vmware` `qemu` `neoforge` `fabric` `virtual-machine` `minecraft` `riscv` `emulator` `redstone`

### Discussion פתיחה (Discussions → New → Announcements)
**Title:** HyperVisor X 1.0 is out — a modern successor to VM Computers
```
Hi everyone! HyperVisor X is a from-scratch successor to Delta2Force's VM Computers (now archived).

What's new: Minecraft 1.21.1 (NeoForge + Fabric), VMware / VirtualBox / QEMU support, a built-in
Linux computer that needs no install, laptops, server racks, curved monitors, video walls,
redstone GPIO and real multiplayer streaming.

Downloads: https://github.com/MosheBarami/vm-computers-hypervisor-x/releases
Bug reports and ideas are very welcome in Issues. Full credit to Delta2Force for the original mod.
```

### איך להגיע למי שעקב אחרי המאגר המקורי (בלי ספאם)
- המאגר `delta2force/MCVmComputers` **בארכיון** – אי אפשר לפתוח בו Issues. לכן:
  1. כתוב פוסט קצר בשרת ה-Discord של המוד המקורי (הקישור מופיע ב-README שלו) – בערוץ המתאים, בנימוס, עם קרדיט.
  2. ענה לשאלות קיימות על VM Computers ב-Reddit/CurseForge comments עם קישור, רק כשזה רלוונטי.
  3. אם יש Fork פעיל של המוד המקורי, אפשר לפתוח שם Issue ענייני אחד שמציע שיתוף פעולה.
- אל תתייג אנשים בהמוניהם ואל תפתח Issues בכמות – זה נחשב ספאם ופוגע בחשבון.

---

## 4. Planet Minecraft ו-MCMOD (mcmod.cn)

### Planet Minecraft
1. https://www.planetminecraft.com → Submit → **Mod** → Minecraft 1.21.1, Loader: NeoForge + Fabric.
2. Title: `VM Computers: HyperVisor X — real Windows & Linux PCs, laptops and video walls`
3. Tags: `computer, pc, laptop, windows, linux, vmware, virtualbox, qemu, technology, redstone`
4. הדבק את תיאור ה-Markdown מסעיף 2 (Planet Minecraft תומך ב-BBCode; להמרה אפשר להשתמש בקישור ל-Modrinth כ"Download").
5. קישור הורדה: Modrinth/CurseForge (לא קובץ ישיר).

### MCMOD (百科) – שליחת ערך
1. הרשמה ב-https://www.mcmod.cn → 添加模组 (הוספת מוד) – הערך עובר אישור של עורכי האתר.
2. מלא: 模组名称 `VM Computers: HyperVisor X`, 简称 `HVX`, 运行环境: 客户端+服务端, 版本: 1.21.1 NeoForge/Fabric, 开源: GitHub.
3. תקציר מוכן בסינית:
```
VM Computers: HyperVisor X（虚拟机电脑：超管X）是经典模组 VM Computers 的全新重制版。
在 Minecraft 1.21.1（NeoForge / Fabric）中组装真正能运行的电脑、笔记本、服务器机柜和巨型屏幕墙，
并在其中运行真实的 Windows 和 Linux 系统。

● 支持 VMware、VirtualBox、QEMU（KVM / WHPX / HVF，可与 Hyper-V、WSL2 共存）
● 内置 RISC-V 微型虚拟机：无需安装任何软件，约 1 秒启动真实 Linux
● RGB 玻璃侧透机箱可看到内部安装的显卡、内存与 CPU
● 49 寸曲面带鱼屏、双/三联显示器支架、最大 32×18 方块的无缝屏幕墙
● 可边走边用的笔记本电脑：电池、桌面充电、Wi-Fi 远程桌面
● 红石 GPIO 扩展卡：虚拟机内的 Python/C#/Bash 程序可读写红石信号并搬运箱子物品
● 多人联机：服务器端运行虚拟机，增量瓦片编码串流，只传输玩家看得见的屏幕
● OrbitTech 卫星商店 2.0、一键下载操作系统、游戏内文件浏览器
● 指令 /hvx demo 一键生成展示厅

开源协议：GPL-3.0。原作：Delta2Force 的 VM Computers。
```
4. B站 (Bilibili): תוכן על מחשבים במיינקראפט פופולרי מאוד. העלה את סרטון ה-45 שניות (סעיף 6) עם כותרת:
   `我的世界里装了一台真电脑！RGB主机+服务器机柜+4K屏幕墙运行真实Windows/Linux【HyperVisor X 模组】`

---

## 5. Reddit – פוסטים מוכנים

> קרא את חוקי כל סאב לפני פרסום. בדרך כלל: פוסט וידאו/תמונה מקורי + תגובה ראשונה עם קישורים. אל תפרסם את כולם באותה שעה.

### r/feedthebeast  (Flair: Mod Release / Mod showcase)
**Title:** I rebuilt VM Computers from scratch: real Windows/Linux PCs, laptops, server racks and video walls in 1.21.1 (NeoForge + Fabric)
```
VM Computers by Delta2Force let you run VirtualBox inside Minecraft back in 1.16. It was archived,
so I wrote a successor from the ground up:

- VMware, VirtualBox and QEMU (KVM/WHPX/HVF) backends, auto-detected
- No hypervisor installed? A built-in RISC-V machine boots real Linux in ~1 second
- VMs run server-side and stream to all players (delta tiles, only visible screens)
- Curved ultrawide, triple monitor arms, seamless video walls up to 32x18
- Laptops with battery + Wi-Fi remote desktop, server rack clusters
- Redstone GPIO card: Python in the VM can toggle redstone and move items
- /hvx demo builds the whole showroom from the screenshot

Open source (GPL-3.0). Feedback very welcome!
GitHub / downloads: https://github.com/MosheBarami/vm-computers-hypervisor-x
```

### r/Minecraft  (Flair: Mod; העלה סרטון/תמונה)
**Title:** I can now run real Linux on a 4x3 video wall in my Minecraft base (no software install needed)
```
Made with my mod HyperVisor X (1.21.1). The computer runs a tiny real Linux on a built-in RISC-V CPU,
or Windows through VMware/QEMU/VirtualBox if you have them. Links in the comments.
```

### r/pcmasterrace  (Flair: Meme/Joke או Build – בדוק חוקים)
**Title:** Built an RGB tempered-glass gaming PC in Minecraft. It actually boots.
```
The parts you install (GPU, RAM, CPU, NVMe) are visible through the glass and set the VM's specs:
an OrbitForce Titan unlocks 4K and 3 displays, more RAM sticks = more RAM in Windows.
It streams live to a curved 49" ultrawide. Open source mod for Minecraft 1.21.1.
```

### r/virtualization  (טון טכני)
**Title:** Streaming VMware/VirtualBox/QEMU framebuffers into Minecraft: delta tiles, PBO uploads and a Java RISC-V core
```
Write-up of a hobby project: a Minecraft mod that hosts VMs on the game server and renders them
on in-game monitors.

- QEMU: VNC with QEMU extended key events + VNC audio capture, QMP for control, WHPX so it coexists with Hyper-V
- VMware: vmrun + the per-VM VNC server, named-pipe serial port as a GPIO bus
- VirtualBox: VBoxManage + a persistent vboxwebsrv SOAP session, tile diffing on top of screenshots
- Encoder: 64x64 tiles, per-tile versions per viewer, XOR deltas + deflate, token-bucket bandwidth
- Client: dirty-run PBO uploads, trilinear + anisotropic, sharp-bilinear shader for magnification
- Zero-install fallback: mini-rv32ima ported to Java (~70 MIPS), boots Linux 6.1 in under a second

Code (GPL-3.0): https://github.com/MosheBarami/vm-computers-hypervisor-x
```

---

## 6. תסריט וידאו ויראלי – 45 שניות (YouTube Shorts / TikTok / Reels)

פורמט 9:16, 1080x1920, מוזיקה אנרגטית עם "drop" בשנייה 20. כתוביות גדולות בכל שוט.

| זמן | שוט (מצלמה) | מה קורה | כיתוב על המסך |
|---|---|---|---|
| 0–3 | קלוז-אפ על הטאבלט ביד | פותחים את OrbitTech Store, מוסיפים *Streamer Setup* | "Ordering a gaming PC… in Minecraft" |
| 3–6 | מבט לשמיים | קפסולת אספקה נופלת עם להבות ונוחתת | "delivery from orbit 🚀" |
| 6–11 | גוף ראשון ליד הארגז | מניחים מארז RGB, לחיצה ימנית עם GPU, RAM, NVMe – רואים אותם דרך הזכוכית | "every part is real" |
| 11–15 | טראק סביב ארון שרתים | מניחים 3 יחידות שרת, להבים נכנסים, נורות מהבהבות | "+ a 32-core server rack" |
| 15–19 | רחב | מניחים 12 פאנלים → הופכים לקיר אחד; כבל HDMI מהמחשב לקיר | "4K video wall" |
| 19–24 | Drop! מבט מלפנים | Power on → לוגו אתחול → Windows 11 עולה על הקיר ועל המסך הקעור | "it boots WINDOWS 11" |
| 24–30 | פוקוס (מסך מלא) | פותחים Minecraft בתוך ה-Windows ונכנסים לעולם | "Minecraft… inside Minecraft" |
| 30–35 | יציאה ב-Right Ctrl | מבט על החדר: הקיר מציג את המשחק הפנימי, שומעים את הסאונד מהרמקולים | "with real sound 🔊" |
| 35–41 | ברכבת (Create/רכבת מינקארט) | הדמות מחזיקה לפטופ, PiP בפינה ממשיך להציג את אותו מחשב | "and I can take it on the train" |
| 41–45 | זום-אאוט + לוגו | לוגו HVX + שם המוד | "VM Computers: HyperVisor X — link in bio" |

**טיפים לצילום**
- להקלטה: Replay Mod (Fabric) או OBS, 60 FPS, Shaders כמו Complementary כדי שה-RGB יבלוט.
- את שוט ה-Windows צלם עם VMware או QEMU-WHPX ורזולוציית GPU Titan (4K). `/hvx demo` נותן סט מוכן למקרה שאין זמן.
- תיאור לסרטון: `Real Windows inside Minecraft 🤯 RGB PC + server rack + 4K video wall | mod: VM Computers HyperVisor X (link in bio) #minecraft #minecraftmods #gamingpc #windows11 #pcbuild`

---

## 7. רשימת בדיקה לפני השקה
- [ ] בדקת ש-Release v1.0.0 מכיל את שני ה-jars (ללא sources) והורדת אחד לבדיקה.
- [ ] בדקת `/hvx demo` ו-`/hvx selftest` במשחק נקי.
- [ ] יצרת פרויקטים ב-Modrinth/CurseForge והגדרת tokens (אופציונלי, לאוטומציה).
- [ ] סרטון קצר + 4 צילומי מסך מוכנים.
- [ ] פרסום מדורג: Modrinth/CurseForge → r/feedthebeast → YouTube Shorts/TikTok → r/pcmasterrace → MCMOD/Bilibili.
