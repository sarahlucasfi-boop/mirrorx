// MirrorX v2.0.0 — Telemetry and adaptation engine
// ==================================================
//
// Coleta telemetria dos clientes conectados (RTT, FPS recebido, perda
// estimada) e ajusta dinamicamente o codec (bitrate/qualidade/fps).
//
// Mantém uma janela deslizante de 10 amostras por cliente. Cada 2s:
//   - se RTT > 180ms OU FPS < 80% do target → reduz
//   - se RTT < 50ms E FPS >= 95% → sobe
//   - cooldown de 2s entre mudanças
//
// Também calcula a média agregada de todos os clientes para broadcast
// no HUD do APK.

using System;
using System.Collections.Generic;
using System.Linq;

namespace MirrorXServer;

public sealed class AdaptationEngine
{
    public int WindowSize { get; set; } = 10;
    public double RttUpThresholdMs { get; set; } = 180;
    public double RttDownThresholdMs { get; set; } = 50;
    public double FpsUpThreshold { get; set; } = 0.95;
    public double FpsDownThreshold { get; set; } = 0.80;
    public int CooldownMs { get; set; } = 2000;

    private DateTime _lastChange = DateTime.MinValue;
    private readonly Dictionary<string, Queue<Sample>> _byClient = new();

    public sealed record Sample(DateTime ts, double rttMs, double fps);

    public void RecordSample(string clientId, double rttMs, double fps)
    {
        if (!_byClient.TryGetValue(clientId, out var q))
        {
            q = new Queue<Sample>(WindowSize);
            _byClient[clientId] = q;
        }
        q.Enqueue(new Sample(DateTime.Now, rttMs, fps));
        while (q.Count > WindowSize) q.Dequeue();
    }

    public (double avgRtt, double avgFps, int clientCount) Aggregate()
    {
        if (_byClient.Count == 0) return (0, 0, 0);
        double sumRtt = 0, sumFps = 0, n = 0;
        foreach (var (_, q) in _byClient)
        {
            if (q.Count == 0) continue;
            sumRtt += q.Average(s => s.rttMs);
            sumFps += q.Average(s => s.fps);
            n++;
        }
        if (n == 0) return (0, 0, 0);
        return (sumRtt / n, sumFps / n, _byClient.Count);
    }

    /// <summary>Chamado a cada 2s. Decide se ajusta o codec.</summary>
    public bool Tick(FrameCodec codec, double fpsCaptured)
    {
        if (codec == null) return false;
        if ((DateTime.Now - _lastChange).TotalMilliseconds < CooldownMs) return false;

        var (rtt, fpsRec, n) = Aggregate();
        if (n == 0) return false;

        bool degrade = rtt > RttUpThresholdMs
                    || fpsRec < fpsCaptured * FpsDownThreshold;
        bool upgrade = rtt < RttDownThresholdMs
                    && fpsRec > fpsCaptured * FpsUpThreshold;

        if (degrade)
        {
            codec.Adapt(rtt, fpsRec, n);
            _lastChange = DateTime.Now;
            return true;
        }
        if (upgrade)
        {
            codec.Adapt(rtt, fpsRec, n);
            _lastChange = DateTime.Now;
            return true;
        }
        return false;
    }

    public void RemoveClient(string clientId)
    {
        _byClient.Remove(clientId);
    }
}

/// <summary>
/// Payload HUD enviado pelo servidor via WS para o APK exibir.
/// Formato JSON único para que ambas as versões (v1.x e v2.x) ignorem
/// quando não reconhecido.
/// </summary>
public static class HudBroadcaster
{
    public static string Build(string codecName, int width, int height,
        int targetFps, double capturedFps, int kbps, int quality,
        double avgRtt, int clients, long encodedFrames)
    {
        return System.Text.Json.JsonSerializer.Serialize(new
        {
            type = "hud_v2",
            codec = codecName,
            resolution = $"{width}x{height}",
            target_fps = targetFps,
            captured_fps = Math.Round(capturedFps, 1),
            bitrate_kbps = kbps,
            quality = quality,
            rtt_ms = Math.Round(avgRtt, 1),
            clients = clients,
            frames = encodedFrames,
            ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
        });
    }
}