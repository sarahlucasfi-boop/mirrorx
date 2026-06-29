# MirrorX v2.0.2

**Transforme qualquer tablet Android em uma segunda tela sem fio para Windows.**

Sem cabos. Conexão direta via WiFi na rede local. Código aberto. 100% gratuito.

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Windows](https://img.shields.io/badge/Windows-10%2F11-blue)](https://github.com)
[![Android](https://img.shields.io/badge/Android-8%2B-green)](https://github.com)
[![Version](https://img.shields.io/badge/version-2.0.2-6366f1)](https://github.com)
[![100% Free](https://img.shields.io/badge/100%25-Free-brightgreen)](https://github.com)

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

Baixe `MirrorXServer.exe` do [Releases](https://github.com/sarahlucasfi-boop/mirrorx/releases) e execute.

Não precisa instalar nada — o exe já tem tudo embutido.

O servidor mostra o IP do PC na tela.

**Liberar porta 8080 no Firewall** (só uma vez):
```powershell
New-NetFirewallRule -DisplayName "MirrorX" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow
```

### 2. APK no Tablet

Baixe `MirrorX_v2.0.2.apk` do [Releases](https://github.com/sarahlucasfi-boop/mirrorx/releases), instale no tablet e abra.

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

MIT — livre para uso pessoal e comercial.
