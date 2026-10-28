<p align="center">
  <svg width="980" height="220" viewBox="0 0 980 220" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="HyperVisor X animated backdrop">
    <defs>
      <linearGradient id="bg" x1="0" x2="1" y1="0" y2="1">
        <stop offset="0%" stop-color="#0a1022"/>
        <stop offset="52%" stop-color="#101f42"/>
        <stop offset="100%" stop-color="#050b16"/>
      </linearGradient>
      <radialGradient id="glowA" cx="50%" cy="30%" r="60%">
        <stop offset="0%" stop-color="#67e8f9" stop-opacity="0.9"/>
        <stop offset="35%" stop-color="#3b82f6" stop-opacity="0.7"/>
        <stop offset="100%" stop-color="#3b82f6" stop-opacity="0"/>
      </radialGradient>
      <radialGradient id="glowB" cx="67%" cy="70%" r="55%">
        <stop offset="0%" stop-color="#c084fc" stop-opacity="0.9"/>
        <stop offset="30%" stop-color="#8b5cf6" stop-opacity="0.8"/>
        <stop offset="100%" stop-color="#8b5cf6" stop-opacity="0"/>
      </radialGradient>
      <linearGradient id="pulseStroke" x1="0" x2="1">
        <stop offset="0%" stop-color="#67e8f9"/>
        <stop offset="50%" stop-color="#c084fc"/>
        <stop offset="100%" stop-color="#34d399"/>
      </linearGradient>
    </defs>

    <rect width="980" height="220" rx="24" fill="url(#bg)"/>
    <rect x="18" y="18" width="944" height="184" rx="18" fill="none" stroke="rgba(148,163,184,0.4)"/>

    <circle cx="210" cy="110" r="120" fill="url(#glowA)">
      <animate attributeName="opacity" values="0.55;0.9;0.55" dur="4s" repeatCount="indefinite"/>
    </circle>
    <circle cx="760" cy="92" r="130" fill="url(#glowB)">
      <animate attributeName="opacity" values="0.45;0.8;0.45" dur="5s" repeatCount="indefinite"/>
    </circle>

    <g opacity="0.8">
      <path d="M120 170 C 270 35, 450 35, 620 170 S 880 120, 920 90" stroke="url(#pulseStroke)" stroke-width="2.5" fill="none" stroke-dasharray="8 10">
        <animate attributeName="stroke-dashoffset" values="0;100" dur="12s" repeatCount="indefinite"/>
      </path>
      <path d="M110 150 C 250 80, 420 80, 565 150 S 835 155, 915 115" stroke="rgba(103,232,249,0.5)" stroke-width="1.5" fill="none"/>
    </g>

    <g opacity="0.9">
      <circle cx="110" cy="125" r="4" fill="#67e8f9">
        <animate attributeName="r" values="3;7;3" dur="2.5s" repeatCount="indefinite"/>
      </circle>
      <circle cx="300" cy="70" r="4" fill="#c084fc">
        <animate attributeName="r" values="3;8;3" dur="3s" repeatCount="indefinite"/>
      </circle>
      <circle cx="510" cy="58" r="4" fill="#34d399">
        <animate attributeName="r" values="3;7;3" dur="2.4s" repeatCount="indefinite"/>
      </circle>
      <circle cx="770" cy="135" r="4" fill="#67e8f9">
        <animate attributeName="r" values="3;8;3" dur="3.2s" repeatCount="indefinite"/>
      </circle>
      <circle cx="860" cy="90" r="4" fill="#c084fc">
        <animate attributeName="r" values="3;7;3" dur="2.8s" repeatCount="indefinite"/>
      </circle>
    </g>

    <g transform="translate(0,0)">
      <text x="490" y="105" text-anchor="middle" fill="#f8fafc" font-family="Segoe UI, Arial, sans-serif" font-size="42" font-weight="700" letter-spacing="3">HYPERVISOR X</text>
      <text x="490" y="142" text-anchor="middle" fill="#cbd5e1" font-family="Segoe UI, Arial, sans-serif" font-size="16" letter-spacing="2">REAL OPERATING SYSTEMS ON REAL IN-GAME COMPUTERS</text>
    </g>
  </svg>
</p>

<p align="center">
  <a href="#features"><img alt="" src="https://img.shields.io/badge/World-Streaming-67E8F9?style=for-the-badge&logo=githubactions&logoColor=white" /></a>
  <a href="#features"><img alt="" src="https://img.shields.io/badge/VMs-Anywhere-8B5CF6?style=for-the-badge" /></a>
  <a href="#features"><img alt="" src="https://img.shields.io/badge/Redstone-GPIO-34D399?style=for-the-badge" /></a>
  <a href="#features"><img alt="" src="https://img.shields.io/badge/Audio-Positional-F59E0B?style=for-the-badge" /></a>
</p>

<p align="center">
  <img src="docs/screenshots/hvx-world.png" alt="Showroom built with /hvx demo: desk PC, 4x3 video wall, curved ultrawide and triple monitor arm all showing the same Linux machine" width="1000" />
</p>

<div align="center">
  <table>
    <tr>
      <td><b>🖥️</b> Real VMs</td>
      <td><b>🎧</b> Positional audio</td>
      <td><b>🧩</b> Redstone GPIO</td>
      <td><b>🛰️</b> OrbitTech Store</td>
    </tr>
  </table>
</div>

---

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A" alt="mc">
  <img src="https://img.shields.io/badge/loader-NeoForge%20%7C%20Fabric-5A3E9A" alt="loaders">
  <img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="license">
</p>

---

