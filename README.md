# MirrorX v1.7.0

**Transforme qualquer tablet Android em uma segunda tela sem fio para Windows.**

Sem cabos. Conexão direta via WiFi na rede local. Código aberto.

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Windows](https://img.shields.io/badge/Windows-10%2F11-blue)](https://github.com)
[![Version](https://img.shields.io/badge/version-1.7.0-6366f1)](https://github.com)

---

## Por que MirrorX?

| | MirrorX | SuperDisplay | Spacedesk |
|---|---|---|---|
| **Preço** | Grátis / R$10 Pro | $14.99 | Grátis |
| **Código Aberto** | ✅ MIT | ❌ | ❌ |
| **Captura de Tela** | DXGI nativo (GPU) | Proprietário | Proprietário |
| **Cursor no Tablet** | ✅ Compositado | ✅ | ✅ |
| **Toque → Mouse** | ✅ SendInput nativo | ✅ | ✅ |
| **Modo Estender** | ✅ (via VDD) | ✅ | ✅ |
| **Latência** | Baixa (JPEG direto) | Média | Alta |

---

## Início Rápido

### 1. Servidor no PC

Baixe `MirrorXServer.exe` do [Releases](https://github.com/sarahlucasfi-boop/mirrorx/releases) e execute.

Não precisa instalar .NET — o exe já tem tudo embutido.

O servidor mostra o IP do PC e os monitores disponíveis.

**Liberar porta 8080 no Firewall** (só uma vez):
```powershell
New-NetFirewallRule -DisplayName "MirrorX" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
```

### 2. APK no Tablet

Baixe `MirrorX_v1.7.0.apk` do [Releases](https://github.com/sarahlucasfi-boop/mirrorx/releases), instale no tablet e abra.

Digite o IP do PC e conecte. A tela do PC aparece no tablet.

### 3. Testar

Toque na tela do tablet → o mouse move no PC. Funciona como um touchpad/monitor.

---

## Free vs Pro

| Funcionalidade | Free | Pro (R$10) |
|---|---|---|
| Espelhamento de tela | ✅ | ✅ |
| Toque → Mouse | ✅ | ✅ |
| Cursor visível no tablet | ✅ | ✅ |
| Reconexão automática | ✅ | ✅ |
| FPS | Até 24 | 30 e 60 |
| Modo Estender (VDD) | ❌ | ✅ |

**Como desbloquear o Pro:**
1. No app, toque em 🔒30Hz ou 🔒60Hz
2. Digite o código: `MIRRORX-PRO-10`
3. Desbloqueado pra sempre

---

## Modo Estender (2º Monitor Real)

O tablet vira uma área de trabalho separada — dá pra arrastar janelas pra lá.

1. Instalar o VDD (Virtual Display Driver):
```powershell
winget install --id=VirtualDrivers.Virtual-Display-Driver -e
```
2. Reiniciar o PC
3. Win+I → Sistema → Tela → Estender monitores
4. Configurar o servidor pro monitor virtual (requer Pro)

---

## Arquitetura

```
[PC Windows]                              [Tablet Android]
┌──────────────────────┐                  ┌──────────────────────┐
│  MirrorXServer.exe   │                  │  MirrorX APK         │
│  (.NET 8 + DXGI)     │  WebSocket :8080 │  (Kotlin + OkHttp)   │
│                      │ ──frames JPEG──▶ │  → ImageView         │
│  Cursor compositado  │ ◀──JSON toques───│  ← onTouchListener   │
│  SendInput (mouse)   │                  │  ProManager (R$10)   │
└──────────────────────┘                  └──────────────────────┘
```

- **Protocolo:** WebSocket binário (JPEG do PC → tablet) + JSON (`{"type":"down/move/up","x","y"}`) do tablet → PC
- **Cursor:** Compositado no frame (resolve bug clássico do DXGI que entrega tela sem ponteiro)
- **Toque:** Coordenadas normalizadas (0.0 a 1.0), servidor mapeia pra posição real

---

## Stack Técnica

- **Servidor PC:** C# .NET 8, Vortice.Windows (DXGI), Fleck (WebSocket), System.Drawing (JPEG)
- **Cliente Android:** Kotlin, Jetpack Compose, OkHttp WebSocket
- **Protocolo:** JPEG frames + JSON touch events
- **Porta:** 8080

---

## Compilar do Código Fonte

### Servidor (C#)
```bash
cd server_dotnet
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```

### APK (Android)
```bash
cd android_source
# Requer: JDK 17, Android SDK 34
./gradlew assembleRelease
# Assinar com apksigner
```

---

## Licença

MIT — livre para uso pessoal e comercial. Funcionalidades Pro requerem código de desbloqueio.
