# MirrorX v2.0.5A

**Transforme qualquer tablet Android em uma segunda tela sem fio para Windows.**

📦 **Download direto:** [GitHub Releases v2.0.5A](https://github.com/sarahlucasfi-boop/mirrorx/releases/tag/v2.0.5A)
📁 **Link alternativo (Google Drive):**
https://drive.google.com/file/d/1PnCZ0gTpx-9SzCtseZZq1te5kG_ODnMf/view?usp=sharing
https://drive.google.com/file/d/1rypCDRu5MTNv3sWABFL7iNWZi2VyagbA/view?usp=sharing
https://drive.google.com/file/d/1oCrzHV3blZ1eopYrR0tdihrdIwnBA3Ne/view?usp=sharing

Sem cabos. Conexão direta via WiFi na rede local. Código aberto. 100% gratuito.

[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-green.svg)](LICENSE)
[![Windows](https://img.shields.io/badge/Windows-10%2F11-blue)](https://github.com)
[![Android](https://img.shields.io/badge/Android-8%2B-green)](https://github.com)
[![Version](https://img.shields.io/badge/version-2.0.5A-6366f1)](https://github.com)
[![100% Free](https://img.shields.io/badge/100%25-Free-brightgreen)](https://github.com)

---

## 🆕 Novidades da v2.0.5A

- 🔵 **HUD azul removido** — sem overlay de informações no meio da tela
- 🔍 **Zoom com 2 dedos** — arraste dois dedos para dar zoom, duplo-tap reseta
- 🖱️ **Touch input normal** — toques funcionam mesmo com zoom ativo
- 🐛 **Correções de bugs** — renderização de vídeo e partial updates do servidor

---

## Por que MirrorX?

| Funcionalidade | MirrorX | AnyDesk | TeamViewer |
|---|---|---|---|
| Conexão local | ✅ Ilimitada | ✅ | ✅ |
| Conexão remota | ✅ Ilimitada | 1h/sessão | 5min/sessão |
| Resolução máxima | ✅ 4K | 1080p | 720p |
| FPS máximo | ✅ 60 | 30 | 25 |
| Código aberto | ✅ GPLv3 | ❌ Fechado | ❌ Fechado |
| **Preço** | ✅ **R$0 — Gratuito** | R$300+/ano | R$500+/ano |
| Sem cadastro | ✅ Sim | ❌ | ❌ |
| Sem limites | ✅ Sim | ❌ | ❌ |

**100% gratuito e open source** — sem premium, sem limitações, sem assinatura. Apenas baixe e use.

---

## Início Rápido

### 1. Servidor no PC

Baixe `MirrorX_v2.0.5A.exe` do [Releases](https://github.com/sarahlucasfi-boop/mirrorx/releases/tag/v2.0.5A) e execute.

Não precisa instalar nada — o exe já tem tudo embutido.

O servidor mostra o IP do PC na tela.

**Liberar porta 8080 no Firewall** (só uma vez):
```powershell
New-NetFirewallRule -DisplayName "MirrorX" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
```

### 2. APK no Tablet

Baixe `MirrorX_v2.0.5A.apk` do [Releases](https://github.com/sarahlucasfi-boop/mirrorx/releases/tag/v2.0.5A), instale no tablet e abra.

Digite o IP do PC e conecte.

### 3. Pronto

A tela do PC aparece no tablet. Toque na tela → mouse move no PC.

---

## Modo Estender

O tablet vira um segundo monitor real — dá pra arrastar janelas pra lá.

1. Instalar o VDD (Virtual Display Driver):
```powershell
winget install --id=VirtualDrivers.Virtual-Display-Driver -e
```
2. Reiniciar o PC
3. Win+I → Sistema → Tela → Estender monitores

---

## Arquitetura

```
[PC Windows]                              [Tablet Android]
┌──────────────────────┐                  ┌──────────────────────┐
│  MirrorXServer.exe   │                  │  MirrorX APK         │
│  (.NET 8 + DXGI)     │  WebSocket :8080 │  (Kotlin + OkHttp)   │
│                      │ ──frames JPEG──▶ │  → ImageView         │
│  Cursor compositado  │ ◀──JSON toques───│  ← onTouchListener   │
│  SendInput (mouse)   │                  │                      │
└──────────────────────┘                  └──────────────────────┘
```

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
./gradle-8.5/bin/gradle assembleRelease
```

---

## Licença

GPLv3 — livre para uso pessoal e comercial. 100% gratuito, sem restrições.
