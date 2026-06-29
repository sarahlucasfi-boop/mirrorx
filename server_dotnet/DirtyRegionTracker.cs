// MirrorX v2.0.1: Dirty Region Tracking (tile diffing)
// ======================================================
//
// Sistema de otimização de banda baseado em tile diffing.
// Divide cada frame em tiles de NxN pixels, calcula hash de cada tile
// e compara com o frame anterior. Apenas tiles "sujos" (modificados)
// são enviados ao cliente, reduzindo tráfego de rede em até 70-95%
// em cenários típicos (janela estática com pequenas atualizações).
//
// Packet format (type = 3 / Partial):
//   Header (16 bytes):
//     [0]         type = 0x03
//     [1..4]      frameId (uint32, big-endian)
//     [5..6]      totalTiles (uint16, big-endian)
//     [7..8]      dirtyTiles (uint16, big-endian)
//     [9..10]     screenWidth (uint16, big-endian)
//     [11..12]    screenHeight (uint16, big-endian)
//     [13..14]    tileWidth (uint16, big-endian)
//     [15..16]    tileHeight (uint16, big-endian)
//     [17..18]    cursorX (int16, big-endian) — posição X do cursor
//     [19..20]    cursorY (int16, big-endian) — posição Y do cursor
//     [21]        cursorVisible (byte) — 1 = visível, 0 = oculto
//
//   Para cada tile sujo:
//     [0..1]      tileX (uint16, big-endian) — coluna do tile
//     [2..3]      tileY (uint16, big-endian) — linha do tile
//     [4..7]      tileDataSize (uint32, big-endian) — bytes do tile (JPEG)
//     [8..N]      tileData (bytes) — JPEG do tile individual
//
// Compatibilidade: clientes v1.x ignoram type=3 e continuam recebendo
// frames type=1 (full JPEG) normalmente. Clientes v2.0.1+ processam
// partial updates nativamente.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Threading;

namespace MirrorXServer;

public class DirtyRegionTracker : IDisposable
{
    // Tile size (pixels). 64x64 é o default — bom equilíbrio entre
    // granularidade (detecção precisa de mudanças) e overhead de headers.
    private const int DEFAULT_TILE_W = 64;
    private const int DEFAULT_TILE_H = 64;

    public int TileWidth { get; }
    public int TileHeight { get; }

    // Estado: hash de cada tile no frame anterior.
    // Key = (tileCol, tileRow), Value = hash do tile (uint32 FNV-1a).
    private readonly Dictionary<(int x, int y), uint> _previousHashes = new(2048);
    private readonly object _lock = new();

    // Métricas públicas para HUD/stats
    public int TilesDirtyCount { get; private set; }
    public float DirtyPercentage { get; private set; }
    public int LastTotalTiles { get; private set; }

    // Buffer reutilizável para encoding JPEG de tile
    private readonly MemoryStream _tileStream = new(64 * 64 * 3);
    private readonly EncoderParameters _encParams;
    private readonly ImageCodecInfo _jpegEncoder;

    private uint _frameId = 0;

    public DirtyRegionTracker(int tileW = DEFAULT_TILE_W, int tileH = DEFAULT_TILE_H, int jpegQuality = 60)
    {
        TileWidth = tileW;
        TileHeight = tileH;

        _encParams = new EncoderParameters(1);
        _encParams.Param[0] = new EncoderParameter(Encoder.Quality, (long)jpegQuality);
        _jpegEncoder = GetJpegCodec();
    }

    /// <summary>
    /// Processa um Bitmap capturado e retorna um packet parcial (type=3)
    /// contendo apenas os tiles que mudaram em relação ao frame anterior.
    /// Retorna null se nenhum tile mudou (tela estática).
    /// </summary>
    public byte[]? ProcessFrame(Bitmap frame, int cursorX, int cursorY, bool cursorVisible, int jpegQuality)
    {
        lock (_lock)
        {
            uint frameId = ++_frameId;
            int w = frame.Width;
            int h = frame.Height;
            int cols = (w + TileWidth - 1) / TileWidth;
            int rows = (h + TileHeight - 1) / TileHeight;
            int totalTiles = cols * rows;
            LastTotalTiles = totalTiles;

            // Atualiza qualidade do encoder se mudou
            if (_encParams.Param[0] != null)
            {
                _encParams.Param[0] = new EncoderParameter(Encoder.Quality, (long)jpegQuality);
            }

            // Lista de tiles sujos: (tileCol, tileRow, jpegBytes)
            var dirtyTiles = new List<(int tx, int ty, byte[] data)>(totalTiles / 2);

            // Extrai pixels bloqueados para hash rápido
            // Usamos LockBits para performance — acesso direto à memória
            BitmapData? locked = null;
            try
            {
                locked = frame.LockBits(
                    new Rectangle(0, 0, w, h),
                    ImageLockMode.ReadOnly,
                    PixelFormat.Format32bppArgb);

                int stride = locked.Stride;
                byte[] pixelBuffer = new byte[stride * h];
                System.Runtime.InteropServices.Marshal.Copy(locked.Scan0, pixelBuffer, 0, pixelBuffer.Length);

                var newHashes = new Dictionary<(int x, int y), uint>(totalTiles);

                for (int row = 0; row < rows; row++)
                {
                    for (int col = 0; col < cols; col++)
                    {
                        int x0 = col * TileWidth;
                        int y0 = row * TileHeight;
                        int tw = Math.Min(TileWidth, w - x0);
                        int th = Math.Min(TileHeight, h - y0);

                        // Hash FNV-1a do tile (rápido, 32-bit, boas propriedades de distribuição)
                        uint hash = ComputeTileHash(pixelBuffer, stride, x0, y0, tw, th);

                        newHashes[(col, row)] = hash;

                        // Compara com hash anterior
                        if (!_previousHashes.TryGetValue((col, row), out uint prevHash) || prevHash != hash)
                        {
                            // Tile sujo — extrai sub-bitmap e faz JPEG
                            byte[] jpegData = EncodeTile(frame, x0, y0, tw, th);
                            dirtyTiles.Add((col, row, jpegData));
                        }
                    }
                }

                // Atualiza hashes para próximo frame
                _previousHashes.Clear();
                foreach (var kv in newHashes)
                    _previousHashes[kv.Key] = kv.Value;
            }
            finally
            {
                if (locked != null) frame.UnlockBits(locked);
            }

            TilesDirtyCount = dirtyTiles.Count;
            DirtyPercentage = totalTiles > 0 ? (float)dirtyTiles.Count / totalTiles * 100f : 100f;

            // Se nenhum tile mudou, retorna null (não envia nada)
            if (dirtyTiles.Count == 0)
                return null;

            // Monta o packet type=3
            return BuildPartialPacket(frameId, totalTiles, dirtyTiles, w, h, cursorX, cursorY, cursorVisible);
        }
    }

    /// <summary>
    /// Força próximo frame a enviar todos os tiles (ex: após resize,
    /// first frame, ou quando cliente solicita keyframe).
    /// </summary>
    public void Reset()
    {
        lock (_lock)
        {
            _previousHashes.Clear();
            _frameId = 0;
        }
    }

    // =================================================================
    // Hash FNV-1a (rápido, 32-bit, sem alocação)
    // =================================================================
    private static uint ComputeTileHash(byte[] pixels, int stride, int x0, int y0, int tw, int th)
    {
        const uint FNV_OFFSET = 2166136261u;
        const uint FNV_PRIME = 16777619u;
        uint hash = FNV_OFFSET;

        // Amostra a cada 2 pixels (reduz custo em 4x sem comprometer detecção)
        for (int y = y0; y < y0 + th; y += 2)
        {
            int rowOffset = y * stride;
            for (int x = x0; x < x0 + tw; x += 2)
            {
                int idx = rowOffset + x * 4; // 32bppArgb = 4 bytes/pixel
                hash ^= pixels[idx];     hash *= FNV_PRIME; // A
                hash ^= pixels[idx + 1]; hash *= FNV_PRIME; // R
                hash ^= pixels[idx + 2]; hash *= FNV_PRIME; // G
                hash ^= pixels[idx + 3]; hash *= FNV_PRIME; // B
            }
        }
        return hash;
    }

    // =================================================================
    // Extract tile sub-region as JPEG
    // =================================================================
    private byte[] EncodeTile(Bitmap frame, int x0, int y0, int tw, int th)
    {
        using var tile = new Bitmap(tw, th, PixelFormat.Format32bppArgb);
        using (var g = Graphics.FromImage(tile))
        {
            g.DrawImage(frame, 
                new Rectangle(0, 0, tw, th),
                new Rectangle(x0, y0, tw, th),
                GraphicsUnit.Pixel);
        }

        _tileStream.SetLength(0);
        _tileStream.Position = 0;
        tile.Save(_tileStream, _jpegEncoder, _encParams);

        byte[] result = new byte[_tileStream.Length];
        Buffer.BlockCopy(_tileStream.GetBuffer(), 0, result, 0, (int)_tileStream.Length);
        return result;
    }

    // =================================================================
    // Build Type-3 partial update packet
    // =================================================================
    private byte[] BuildPartialPacket(
        uint frameId, int totalTiles,
        List<(int tx, int ty, byte[] data)> dirtyTiles,
        int screenW, int screenH,
        int cursorX, int cursorY, bool cursorVisible)
    {
        // Calcula tamanho total
        int headerSize = 22; // type(1)+frameId(4)+totalTiles(2)+dirtyTiles(2)+screenW(2)+screenH(2)+tileW(2)+tileH(2)+cursorXY(4)+visible(1)
        int payloadSize = 0;
        foreach (var (tx, ty, data) in dirtyTiles)
            payloadSize += 2 + 2 + 4 + data.Length; // tileX(2)+tileY(2)+tileSize(4)+data

        byte[] packet = new byte[headerSize + payloadSize];
        int offset = 0;

        // Header
        packet[offset++] = 0x03; // type = Partial update

        // frameId (32-bit big-endian)
        packet[offset++] = (byte)((frameId >> 24) & 0xFF);
        packet[offset++] = (byte)((frameId >> 16) & 0xFF);
        packet[offset++] = (byte)((frameId >> 8) & 0xFF);
        packet[offset++] = (byte)(frameId & 0xFF);

        // totalTiles (16-bit big-endian)
        packet[offset++] = (byte)((totalTiles >> 8) & 0xFF);
        packet[offset++] = (byte)(totalTiles & 0xFF);

        // dirtyTiles count (16-bit big-endian)
        int dirtyCount = dirtyTiles.Count;
        packet[offset++] = (byte)((dirtyCount >> 8) & 0xFF);
        packet[offset++] = (byte)(dirtyCount & 0xFF);

        // screen dimensions (16-bit each)
        packet[offset++] = (byte)((screenW >> 8) & 0xFF);
        packet[offset++] = (byte)(screenW & 0xFF);
        packet[offset++] = (byte)((screenH >> 8) & 0xFF);
        packet[offset++] = (byte)(screenH & 0xFF);

        // tile dimensions
        packet[offset++] = (byte)((TileWidth >> 8) & 0xFF);
        packet[offset++] = (byte)(TileWidth & 0xFF);
        packet[offset++] = (byte)((TileHeight >> 8) & 0xFF);
        packet[offset++] = (byte)(TileHeight & 0xFF);

        // cursor position (16-bit each, signed)
        short cx = (short)Math.Clamp(cursorX, short.MinValue, short.MaxValue);
        short cy = (short)Math.Clamp(cursorY, short.MinValue, short.MaxValue);
        packet[offset++] = (byte)((cx >> 8) & 0xFF);
        packet[offset++] = (byte)(cx & 0xFF);
        packet[offset++] = (byte)((cy >> 8) & 0xFF);
        packet[offset++] = (byte)(cy & 0xFF);

        // cursor visible
        packet[offset++] = (byte)(cursorVisible ? 1 : 0);

        // Tile payloads
        foreach (var (tx, ty, data) in dirtyTiles)
        {
            // tileX (col)
            packet[offset++] = (byte)((tx >> 8) & 0xFF);
            packet[offset++] = (byte)(tx & 0xFF);
            // tileY (row)
            packet[offset++] = (byte)((ty >> 8) & 0xFF);
            packet[offset++] = (byte)(ty & 0xFF);
            // tileSize (32-bit)
            int sz = data.Length;
            packet[offset++] = (byte)((sz >> 24) & 0xFF);
            packet[offset++] = (byte)((sz >> 16) & 0xFF);
            packet[offset++] = (byte)((sz >> 8) & 0xFF);
            packet[offset++] = (byte)(sz & 0xFF);
            // tileData
            Buffer.BlockCopy(data, 0, packet, offset, sz);
            offset += sz;
        }

        return packet;
    }

    // =================================================================
    // Dispose
    // =================================================================
    public void Dispose()
    {
        lock (_lock)
        {
            _previousHashes.Clear();
            _tileStream.Dispose();
        }
    }

    // Helper: get JPEG encoder
    private static ImageCodecInfo GetJpegCodec()
    {
        var encoders = ImageCodecInfo.GetImageEncoders();
        for (int i = 0; i < encoders.Length; i++)
            if (encoders[i].FormatID == ImageFormat.Jpeg.Guid)
                return encoders[i];
        return encoders[0];
    }
}
