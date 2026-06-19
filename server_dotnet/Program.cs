using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text.Json;
using Fleck;

namespace MirrorXServer;

class Program
{
    // 0 = monitor primario (modo ESPELHAR).
    // 1 (ou indice do VDD) = monitor virtual (modo ESTENDER).
    const int OUTPUT_INDEX = 0;
    const int PORT = 8080;
    const long JPEG_QUALITY = 60;
    const int TARGET_FPS = 30;

    static readonly List<IWebSocketConnection> clients = new();
    static Rectangle monitorBounds;

    static void Main()
    {
        Console.WriteLine("MirrorX Server v1.7.0");
        Console.WriteLine("=====================");
        Console.WriteLine();

        // Lista os monitores disponiveis no console pra ajudar a descobrir o indice do VDD.
        DesktopDuplicator.ListOutputs();
        Console.WriteLine();

        var dup = new DesktopDuplicator(OUTPUT_INDEX);
        monitorBounds = dup.Bounds;
        Console.WriteLine($"Capturando monitor {OUTPUT_INDEX}: " +
                          $"{monitorBounds.Width}x{monitorBounds.Height} " +
                          $"em ({monitorBounds.Left},{monitorBounds.Top})");
        Console.WriteLine();

        var server = new WebSocketServer($"ws://0.0.0.0:{PORT}");
        server.Start(socket =>
        {
            socket.OnOpen = () =>
            {
                lock (clients) clients.Add(socket);
                Console.WriteLine($"[+] Cliente conectado: {socket.ConnectionInfo.ClientIpAddress}");
            };
            socket.OnClose = () =>
            {
                lock (clients) clients.Remove(socket);
                Console.WriteLine($"[-] Cliente desconectado: {socket.ConnectionInfo.ClientIpAddress}");
            };
            socket.OnMessage = HandleTouch;
        });

        string localIp = GetLocalIPAddress();
        Console.WriteLine($"Servidor em ws://{localIp}:{PORT}");
        Console.WriteLine($"Porta {PORT} — certifique-se de liberar no Firewall.");
        Console.WriteLine();
        Console.WriteLine("Aguardando conexoes... (Ctrl+C para sair)");
        Console.WriteLine();

        var encParams = new EncoderParameters(1);
        encParams.Param[0] = new EncoderParameter(Encoder.Quality, JPEG_QUALITY);
        var jpeg = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
        int frameDelay = 1000 / TARGET_FPS;

        while (true)
        {
            var sw = System.Diagnostics.Stopwatch.StartNew();
            using var bmp = dup.CaptureFrame();
            if (bmp != null)
            {
                using var ms = new MemoryStream();
                bmp.Save(ms, jpeg, encParams);
                var bytes = ms.ToArray();
                lock (clients)
                    foreach (var c in clients)
                        if (c.IsAvailable) c.Send(bytes);
            }
            int rest = frameDelay - (int)sw.ElapsedMilliseconds;
            if (rest > 0) Thread.Sleep(rest);
        }
    }

    static void HandleTouch(string json)
    {
        try
        {
            var t = JsonSerializer.Deserialize<TouchMsg>(json);
            if (t == null) return;
            int px = monitorBounds.Left + (int)(t.x * monitorBounds.Width);
            int py = monitorBounds.Top + (int)(t.y * monitorBounds.Height);
            MoveMouseAbsolute(px, py);
            if (t.type == "down") MouseButton(true);
            else if (t.type == "up") MouseButton(false);
        }
        catch (Exception e) { Console.WriteLine("Toque invalido: " + e.Message); }
    }

    record TouchMsg(string type, double x, double y);

    // ---------- Win32 SendInput ----------
    [DllImport("user32.dll")]
    static extern uint SendInput(uint n, INPUT[] inp, int size);

    [DllImport("user32.dll")]
    static extern int GetSystemMetrics(int i);

    const int SM_XVIRTUALSCREEN = 76, SM_YVIRTUALSCREEN = 77, SM_CXVIRTUALSCREEN = 78, SM_CYVIRTUALSCREEN = 79;
    const uint MOVE = 0x0001, ABSOLUTE = 0x8000, VIRTUALDESK = 0x4000, LDOWN = 0x0002, LUP = 0x0004;

    static void MoveMouseAbsolute(int x, int y)
    {
        int vx = GetSystemMetrics(SM_XVIRTUALSCREEN), vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
        int vw = GetSystemMetrics(SM_CXVIRTUALSCREEN), vh = GetSystemMetrics(SM_CYVIRTUALSCREEN);
        int ax = (int)((x - vx) * 65535.0 / vw);
        int ay = (int)((y - vy) * 65535.0 / vh);
        Send(new MOUSEINPUT { dx = ax, dy = ay, dwFlags = MOVE | ABSOLUTE | VIRTUALDESK });
    }

    static void MouseButton(bool down) => Send(new MOUSEINPUT { dwFlags = down ? LDOWN : LUP });

    static void Send(MOUSEINPUT mi) =>
        SendInput(1, new[] { new INPUT { type = 0, U = new InputUnion { mi = mi } } }, Marshal.SizeOf<INPUT>());

    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr ex; }

    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; }

    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public InputUnion U; }

    static string GetLocalIPAddress()
    {
        try
        {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            socket.Connect("8.8.8.8", 80);
            var ep = socket.LocalEndPoint as IPEndPoint;
            return ep?.Address.ToString() ?? "0.0.0.0";
        }
        catch { return "0.0.0.0"; }
    }
}
