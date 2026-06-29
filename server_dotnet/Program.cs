using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows.Forms;
using Fleck;

namespace MirrorXServer;

class Program
{
    // 0 = monitor primario (modo ESPELHAR).
    // 1 (ou indice do VDD) = monitor virtual (modo ESTENDER).
    const int OUTPUT_INDEX = 0;

    // v1.8.1: abre 3 portas simultaneamente. APK novo usa 8080,
    // APK antigo / Python server legacy usam 9900, 7777 e' backup.
    static readonly int[] PORTS = { 8080, 9900, 7777 };
    const int TARGET_FPS = 30;
    static readonly string FIREWALL_RULE = "MirrorX Server";

    // v1.9.1: runtime-controllable scale/quality (no longer const)
    static long _jpegQuality = 45;
    static double _scaleFactor = 1.0;

    public static long JpegQuality
    {
        get => _jpegQuality;
        set => Interlocked.Exchange(ref _jpegQuality, Math.Clamp(value, 1, 100));
    }

    public static double ScaleFactor
    {
        get => _scaleFactor;
        set => _scaleFactor = Math.Clamp(value, 0.25, 1.0);
    }

    // v1.9.8 new variables
    public static double MouseSensitivity = 1.5;
    public static bool InvertScroll = false;

    public class ClientSession
    {
        public IWebSocketConnection Connection { get; set; }
        public string IpAddress { get; set; }
        public DateTime ConnectedAt { get; set; }
        public int LatencyMs { get; set; } = -1;
        public string Status { get; set; } = "Ativo";
        public DateTime LastPingSentAt { get; set; }
    }

    public static readonly List<ClientSession> sessions = new();
    static readonly MemoryStream frameMs = new MemoryStream(1024 * 1024);
    static int currentFps = 0;
    static int fpsCounter = 0;
    static DateTime lastFpsMeasure = DateTime.Now;

    static Rectangle monitorBounds;

    static ServerForm gui;

    // v1.8.5: metodo publico chamado pelo botao do Form
    public static void RequestFirewallRule()
    {
        Task.Run(() => {
            bool ok = AddFirewallRule();
            gui?.Invoke(() => gui.SetFirewall(
                ok ? "Regra ativa (TCP 8080,9900,7777)" : "Falha \u2014 execute como Admin",
                ok ? Color.FromArgb(34, 197, 94) : Color.FromArgb(245, 158, 11)));
        });
    }

    // v1.8.5: firewall sem Console (retorna bool)
    static bool AddFirewallRule()
    {
        string portsArg = string.Join(",", PORTS);
        try
        {
            var del = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo {
                FileName = "netsh",
                Arguments = $"advfirewall firewall delete rule name=\"{FIREWALL_RULE}\"",
                RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true
            });
            del?.WaitForExit(3000);

            var add = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo {
                FileName = "netsh",
                Arguments = $"advfirewall firewall add rule name=\"{FIREWALL_RULE}\" dir=in action=allow protocol=TCP localport={portsArg}",
                RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true
            });
            add?.WaitForExit(5000);
            return add?.ExitCode == 0;
        }
        catch { return false; }
    }

    // Retorna true se a regra ja' existe
    static bool FirewallRuleExists()
    {
        try {
            var p = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo {
                FileName = "netsh",
                Arguments = $"advfirewall firewall show rule name=\"{FIREWALL_RULE}\"",
                RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true
            });
            p?.WaitForExit(3000);
            string output = p?.StandardOutput.ReadToEnd() ?? "";
            return output.Contains("Enabled") || output.Contains("Habilitado");
        } catch { return false; }
    }

    // v1.9.0: UDP Discovery listener - APK broadcasts on port 9999 to auto-detect server
    static void StartUdpDiscovery(ServerForm form)
    {
        Task.Run(async () =>
        {
            try
            {
                using var udp = new UdpClient(9999);
                form.Log("UDP Discovery ativo na porta 9999");
                while (!form.StopRequested)
                {
                    var result = await udp.ReceiveAsync();
                    string msg = System.Text.Encoding.UTF8.GetString(result.Buffer);
                    form.Log($"UDP discovery de {result.RemoteEndPoint.Address}: {msg.Trim()}");

                    // Reply with server info: JSON {ip, ports, name}
                    string localIp = GetLocalIPAddress();
                    string portsStr = string.Join(",", PORTS);
                    string reply = JsonSerializer.Serialize(new { ip = localIp, ports = portsStr, name = Environment.MachineName });
                    byte[] replyBytes = System.Text.Encoding.UTF8.GetBytes(reply);
                    await udp.SendAsync(replyBytes, replyBytes.Length, result.RemoteEndPoint);
                }
            }
            catch (Exception ex)
            {
                form.Log($"UDP Discovery falhou: {ex.Message}", "warn");
            }
        });
    }

    static string GetLocalIPAddress()
    {
        try {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            socket.Connect("8.8.8.8", 80);
            var ep = socket.LocalEndPoint as IPEndPoint;
            return ep?.Address.ToString() ?? "0.0.0.0";
        }
        catch { return "0.0.0.0"; }
    }

    [STAThread]
    static void Main()
    {
        // v1.8.6: GUI + servidor em threads separadas
        gui = new ServerForm();
        gui.Show();
        gui.Log("Iniciando MirrorX Server v1.9.9...");

        // Inicia o servidor numa thread dedicada (MTA para DXGI)
        var serverThread = new Thread(() => RunServer(gui)) {
            IsBackground = true,
            Name = "MirrorX-Server"
        };
        serverThread.SetApartmentState(ApartmentState.MTA);
        serverThread.Start();

        // Loop do WinForms (bloqueia ate fechar a janela)
        Application.Run(gui);
    }

    static void RunServer(ServerForm form)
    {
        try
        {
            // v1.9.0: Start UDP discovery listener for APK auto-detect
            StartUdpDiscovery(form);

            // Tenta adicionar firewall automaticamente
            form.Log("Verificando Firewall do Windows...");
            bool fwExists = FirewallRuleExists();
            if (!fwExists) {
                form.Log("Regra nao encontrada \u2014 tentando adicionar...");
                bool ok = AddFirewallRule();
                if (ok) {
                    form.Log("Firewall: regra adicionada (TCP 8080, 9900, 7777)");
                    form.SetFirewall("Regra ativa (TCP 8080,9900,7777)", Color.FromArgb(34, 197, 94));
                } else {
                    form.Log("Firewall: falha \u2014 use o botao 'Adicionar Firewall' com Admin", "warn");
                    form.SetFirewall("Nao configurado (clique o botao)", Color.FromArgb(245, 158, 11));
                }
            } else {
                form.Log("Firewall: regra ja' existe");
                form.SetFirewall("Regra ativa (TCP 8080,9900,7777)", Color.FromArgb(34, 197, 94));
            }

            // Detecta IP
            string localIp = GetLocalIPAddress();
            form.SetIp($"{localIp} (Wi-Fi)");
            form.Log($"IP local detectado: {localIp}");

            // Inicializa DXGI capture
            DesktopDuplicator dup;
            try {
                DesktopDuplicator.ListOutputs();
                dup = new DesktopDuplicator(OUTPUT_INDEX);
                monitorBounds = dup.Bounds;
                form.Log($"Capturando monitor {OUTPUT_INDEX}: {monitorBounds.Width}x{monitorBounds.Height}");
            }
            catch (Exception ex) {
                form.Log($"FALHA ao inicializar captura DXGI: {ex.Message}", "bad");
                form.SetStatus("Erro no DXGI", Color.FromArgb(239, 68, 68));
                return;
            }

            // Start Ping Sender loop
            StartPingLoop(form);
            
            // Start Auto-FPS loop
            StartAutoFpsLoop(form);

            // Inicia os WebSocket servers (1 por porta)
            var servers = new List<WebSocketServer>();
            foreach (var p in PORTS) {
                try {
                    var s = new WebSocketServer($"ws://0.0.0.0:{p}");
                    s.Start(socket => {
                        socket.OnOpen = () => {
                            var session = new ClientSession {
                                Connection = socket,
                                IpAddress = socket.ConnectionInfo.ClientIpAddress,
                                ConnectedAt = DateTime.Now,
                                LastPingSentAt = DateTime.Now
                            };
                            lock (sessions) sessions.Add(session);
                            form.AddClient(session.IpAddress, session.ConnectedAt.ToString("HH:mm:ss"), "Calculando...", "Ativo");
                            form.UpdateClientCount(sessions.Count);
                            form.Log($"[+ Conectado] {session.IpAddress} na porta {p}");
                        };
                        socket.OnClose = () => {
                            string ip = socket.ConnectionInfo.ClientIpAddress;
                            lock (sessions) sessions.RemoveAll(x => x.Connection == socket);
                            form.RemoveClient(ip);
                            form.UpdateClientCount(sessions.Count);
                            form.Log($"[- Desconectado] {ip}");
                        };
                        socket.OnMessage = msg => HandleTouch(msg, socket);
                    });
                    servers.Add(s);
                    form.Log($"Escutando em ws://{localIp}:{p}");
                }
                catch (Exception ex) {
                    form.Log($"[Aviso] Porta {p} indisponível: {ex.Message}", "warn");
                }
            }
            if (servers.Count == 0) {
                throw new Exception("Nenhuma porta (8080, 9900, 7777) pôde ser aberta!");
            }

            form.SetStatus("Online", Color.FromArgb(34, 197, 94));
            form.Log("Pronto \u2014 aguardando conexoes...");

            // Loop de captura de frames
            var jpeg = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
            int frameDelay = 1000 / TARGET_FPS;

            // v1.9.0: reload encoder params when quality changes at runtime
            long lastQuality = _jpegQuality;
            var encParams = new EncoderParameters(1);
            encParams.Param[0] = new EncoderParameter(Encoder.Quality, _jpegQuality);

            while (!form.StopRequested)
            {
                if (sessions.Count == 0)
                {
                    Thread.Sleep(100);
                    continue;
                }

                var sw = System.Diagnostics.Stopwatch.StartNew();
                try
                {
                    if (_jpegQuality != lastQuality)
                    {
                        lastQuality = _jpegQuality;
                        encParams.Param[0] = new EncoderParameter(Encoder.Quality, _jpegQuality);
                    }

                    using var bmp = dup.CaptureFrame();
                    if (bmp != null)
                    {
                        Bitmap? resized = null;
                        Bitmap frameToEncode = bmp;
                        try
                        {
                            if (_scaleFactor < 1.0 && monitorBounds.Width > 0)
                            {
                                int newW = Math.Max(640, (int)(monitorBounds.Width * _scaleFactor));
                                int newH = Math.Max(360, (int)(monitorBounds.Height * _scaleFactor));
                                resized = new Bitmap(bmp, newW, newH);
                                frameToEncode = resized;
                            }
                        }
                        catch { resized?.Dispose(); frameToEncode = bmp; }

                        lock (frameMs)
                        {
                            frameMs.SetLength(0);
                            frameMs.Position = 0;
                            frameToEncode.Save(frameMs, jpeg, encParams);
                            byte[] buffer = frameMs.GetBuffer();
                            int length = (int)frameMs.Length;
                            resized?.Dispose();

                            var frame = new byte[11 + length];
                            frame[0] = 0x01;
                            frame[1] = (byte)((length >> 24) & 0xFF);
                            frame[2] = (byte)((length >> 16) & 0xFF);
                            frame[3] = (byte)((length >> 8) & 0xFF);
                            frame[4] = (byte)(length & 0xFF);
                            frame[5] = (byte)((dup.CursorX >> 8) & 0xFF);
                            frame[6] = (byte)(dup.CursorX & 0xFF);
                            frame[7] = (byte)((dup.CursorY >> 8) & 0xFF);
                            frame[8] = (byte)(dup.CursorY & 0xFF);
                            frame[9] = (byte)(dup.CursorVisible ? 1 : 0);
                            frame[10] = 0;
                            
                            Buffer.BlockCopy(buffer, 0, frame, 11, length);

                            List<IWebSocketConnection> snapshot;
                            lock (sessions) snapshot = sessions.Select(x => x.Connection).ToList();
                            
                            bool sent = false;
                            foreach (var c in snapshot) {
                                try {
                                    if (c.IsAvailable) {
                                        c.Send(frame);
                                        sent = true;
                                    }
                                } catch { }
                            }
                            
                            if (sent) {
                                fpsCounter++;
                            }
                        }
                        
                        if ((DateTime.Now - lastFpsMeasure).TotalSeconds >= 1.0) {
                            currentFps = fpsCounter;
                            fpsCounter = 0;
                            lastFpsMeasure = DateTime.Now;
                        }
                    }
                }
                catch { }
                int rest = frameDelay - (int)sw.ElapsedMilliseconds;
                if (rest > 0) Thread.Sleep(rest);
            }

            // Shutdown limpo
            form.SetStatus("Encerrando...", Color.FromArgb(245, 158, 11));
            form.Log("Desligando servidores...");
            foreach (var s in servers) {
                try { s.Dispose(); } catch { }
            }
            dup.Dispose();
            form.Log("Servidor finalizado com sucesso.");

            // Fecha a janela depois de 1s
            Task.Run(async () => {
                await Task.Delay(1000);
                form.Invoke(() => form.Close());
            });
        }
        catch (Exception ex) {
            form.SetStatus("CRASH", Color.FromArgb(239, 68, 68));
            form.Log($"ERRO FATAL: {ex.Message}", "bad");
            form.Log(ex.ToString(), "bad");
        }
    }

    // v1.9.0: enhanced HandleTouch — supports both legacy and hermes JSON packet formats
    // Legacy: {type:"down"|"up"|"click"|"scroll", x, y, buttons}
    // Hermes: {t:"s", v:N} scroll, {t:"m", x, y} move, {t:"c", b:N} click, {t:"d"|"u", b:N} down/up
    static void HandleTouch(string json, IWebSocketConnection socket)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            // Pong handling
            if (root.TryGetProperty("type", out var typeP) && typeP.GetString() == "pong") {
                string ip = socket.ConnectionInfo.ClientIpAddress;
                lock (sessions) {
                    var s = sessions.FirstOrDefault(x => x.Connection == socket);
                    if (s != null) {
                        s.LatencyMs = (int)(DateTime.Now - s.LastPingSentAt).TotalMilliseconds;
                        gui.UpdateClientPing(ip, s.LatencyMs);
                    }
                }
                return;
            }

            // Hermes compact format: {t: "s"|"m"|"c"|"d"|"u", ...}
            if (root.TryGetProperty("t", out var tProp))
            {
                string t = tProp.GetString() ?? "";
                switch (t)
                {
                    case "s": // scroll wheel
                        if (root.TryGetProperty("v", out var vProp))
                        {
                            int scrollDelta = vProp.GetInt32();
                            MouseScroll(scrollDelta);
                        }
                        break;

                    case "m": // relative delta mouse move
                        if (root.TryGetProperty("x", out var mxProp) && root.TryGetProperty("y", out var myProp))
                        {
                            int dx = mxProp.GetInt32();
                            int dy = myProp.GetInt32();
                            MoveMouseRelative(dx, dy);
                        }
                        break;

                    case "c": // mouse click — b: 0=left, 1=right, 2=middle
                        if (root.TryGetProperty("b", out var bProp))
                        {
                            int button = bProp.GetInt32();
                            switch (button)
                            {
                                case 0: MouseClick(LDOWN, LUP); break;
                                case 1: MouseClick(RDOWN, RUP); break;
                                case 2: MouseClick(MDOWN, MUP); break;
                            }
                        }
                        break;

                    case "d": // mouse down — b: 0=left, 1=right
                        if (root.TryGetProperty("b", out var bdProp))
                        {
                            int button = bdProp.GetInt32();
                            if (button == 0) Send(new MOUSEINPUT { dwFlags = LDOWN });
                            else if (button == 1) Send(new MOUSEINPUT { dwFlags = RDOWN });
                        }
                        break;

                    case "u": // mouse up — b: 0=left, 1=right
                        if (root.TryGetProperty("b", out var buProp))
                        {
                            int button = buProp.GetInt32();
                            if (button == 0) Send(new MOUSEINPUT { dwFlags = LUP });
                            else if (button == 1) Send(new MOUSEINPUT { dwFlags = RUP });
                        }
                        break;
                }
                return;
            }

            // Legacy format: {type: "down"|"up"|"click"|"scroll", x, y, buttons}
            if (root.TryGetProperty("type", out var typeProp))
            {
                string type = typeProp.GetString() ?? "";
                double x = root.TryGetProperty("x", out var xP) ? xP.GetDouble() : 0;
                double y = root.TryGetProperty("y", out var yP) ? yP.GetDouble() : 0;
                int px = monitorBounds.Left + (int)(x * monitorBounds.Width);
                int py = monitorBounds.Top + (int)(y * monitorBounds.Height);
                
                if (type == "scroll" && root.TryGetProperty("v", out var sv))
                {
                    MouseScroll(sv.GetInt32());
                    return;
                }

                MoveMouseAbsolute(px, py);

                bool isRightClick = false;
                if (root.TryGetProperty("button", out var btnProp) && btnProp.GetString() == "right") {
                    isRightClick = true;
                } else if (root.TryGetProperty("buttons", out var btnsProp) && (btnsProp.GetInt32() & 2) != 0) {
                    isRightClick = true;
                }

                if (type == "down") {
                    if (isRightClick) Send(new MOUSEINPUT { dwFlags = RDOWN });
                    else MouseButton(true);
                }
                else if (type == "up") {
                    if (isRightClick) Send(new MOUSEINPUT { dwFlags = RUP });
                    else MouseButton(false);
                }
                else if (type == "click") {
                    if (isRightClick) MouseClick(RDOWN, RUP);
                    else MouseClick(LDOWN, LUP);
                }
            }
        }
        catch { }
    }

    // ---------- Win32 SendInput ----------
    [DllImport("user32.dll")]
    static extern uint SendInput(uint n, INPUT[] inp, int size);

    [DllImport("user32.dll")]
    static extern int GetSystemMetrics(int i);

    const int SM_XVIRTUALSCREEN = 76, SM_YVIRTUALSCREEN = 77, SM_CXVIRTUALSCREEN = 78, SM_CYVIRTUALSCREEN = 79;
    const uint MOVE = 0x0001, ABSOLUTE = 0x8000, VIRTUALDESK = 0x4000, LDOWN = 0x0002, LUP = 0x0004;
    const uint WHEEL = 0x0800;  // v1.9.0: scroll wheel flag (MOUSEEVENTF_WHEEL)
    const uint RDOWN = 0x0008;  // v1.9.7: right button down
    const uint RUP   = 0x0010;  // v1.9.7: right button up
    const uint MDOWN = 0x0020;  // v1.9.7: middle button down
    const uint MUP   = 0x0040;  // v1.9.7: middle button up

    static void MoveMouseAbsolute(int x, int y)
    {
        int vx = GetSystemMetrics(SM_XVIRTUALSCREEN), vy = GetSystemMetrics(SM_YVIRTUALSCREEN);
        int vw = GetSystemMetrics(SM_CXVIRTUALSCREEN), vh = GetSystemMetrics(SM_CYVIRTUALSCREEN);
        int ax = (int)((x - vx) * 65535.0 / vw);
        int ay = (int)((y - vy) * 65535.0 / vh);
        Send(new MOUSEINPUT { dx = ax, dy = ay, dwFlags = MOVE | ABSOLUTE | VIRTUALDESK });
    }

    // v1.9.1: relative delta move for Full-Screen Touchpad (MOUSEEVENTF_MOVE without ABSOLUTE)
    static void MoveMouseRelative(int dx, int dy)
    {
        int sdx = (int)Math.Round(dx * MouseSensitivity);
        int sdy = (int)Math.Round(dy * MouseSensitivity);
        Send(new MOUSEINPUT { dx = sdx, dy = sdy, dwFlags = MOVE });
    }

    static void MouseButton(bool down) => Send(new MOUSEINPUT { dwFlags = down ? LDOWN : LUP });

    // v1.9.7: single-shot click (down + up) for left/right/middle
    static void MouseClick(uint downFlag, uint upFlag)
    {
        Send(new MOUSEINPUT { dwFlags = downFlag });
        Thread.Sleep(15);
        Send(new MOUSEINPUT { dwFlags = upFlag });
    }

    // v1.9.0: scroll wheel \u2014 mouseData is the scroll amount (positive=up, negative=down)
    static void MouseScroll(int delta)
    {
        int finalDelta = InvertScroll ? -delta : delta;
        Send(new MOUSEINPUT { dwFlags = WHEEL, mouseData = (uint)finalDelta });
    }

    static void Send(MOUSEINPUT mi) =>
        SendInput(1, new[] { new INPUT { type = 0, U = new InputUnion { mi = mi } } }, Marshal.SizeOf<INPUT>());

    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr ex; }

    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; }

    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public InputUnion U; }
    static void StartPingLoop(ServerForm form)
    {
        Task.Run(async () => {
            while (!form.StopRequested) {
                await Task.Delay(3000);
                List<ClientSession> active;
                lock (sessions) active = new List<ClientSession>(sessions);
                foreach (var s in active) {
                    try {
                        s.LastPingSentAt = DateTime.Now;
                        s.Connection.Send(JsonSerializer.Serialize(new { type = "ping" }));
                    } catch { }
                }
            }
        });
    }

    static void StartAutoFpsLoop(ServerForm form)
    {
        Task.Run(async () => {
            while (!form.StopRequested) {
                await Task.Delay(2000);
                if (form.AutoFpsEnabled) {
                    int targetFps = form.TargetFps;
                    double avgLatency = 0;
                    int activeCount = 0;
                    lock (sessions) {
                        foreach (var s in sessions) {
                            if (s.LatencyMs >= 0) {
                                avgLatency += s.LatencyMs;
                                activeCount++;
                            }
                        }
                    }
                    if (activeCount > 0) avgLatency /= activeCount;
                    else avgLatency = 10;

                    if (currentFps < targetFps) {
                        if (JpegQuality > 15) {
                            JpegQuality = Math.Max(15, JpegQuality - 5);
                            form.SetQualitySliderValue((int)JpegQuality);
                        } else if (ScaleFactor > 0.75) {
                            ScaleFactor = 0.75;
                            form.SetScaleSliderValue(75);
                        }
                    } else if (currentFps >= targetFps && avgLatency < 15) {
                        if (JpegQuality < 80) {
                            JpegQuality = Math.Min(80, JpegQuality + 2);
                            form.SetQualitySliderValue((int)JpegQuality);
                        }
                    }
                }
            }
        });
    }
}
