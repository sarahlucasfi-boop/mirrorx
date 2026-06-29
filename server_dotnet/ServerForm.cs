using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace MirrorXServer;

public class ServerForm : Form
{
    // v1.9.2 — Janela de configuração com fundo AZUL (diferenciação visual do APK)
    static readonly Color BG    = Color.FromArgb(10, 28, 80);      // azul escuro profundo
    static readonly Color CARD  = Color.FromArgb(18, 42, 110);     // azul médio (cards)
    static readonly Color CARD2 = Color.FromArgb(26, 56, 138);     // azul mais claro (inner)
    static readonly Color ACCENT= Color.FromArgb(80, 160, 255);    // azul elétrico
    static readonly Color GREEN = Color.FromArgb(34, 197, 94);
    static readonly Color RED   = Color.FromArgb(239, 68, 68);
    static readonly Color AMBER = Color.FromArgb(245, 158, 11);
    static readonly Color TEXT  = Color.FromArgb(230, 235, 255);   // branco-azulado
    static readonly Color DIM   = Color.FromArgb(150, 170, 210);   // cinza-azulado
    static readonly Color BORDER= Color.FromArgb(40, 80, 160);

    Label lblStatus;
    Label lblIp;
    Label lblPorts;
    Label lblClients;
    Label lblFirewall;
    ListView listClients;
    TextBox logBox;
    Button btnFirewall;
    Button btnStop;
    Label lblVersion;

    // runtime controls for scale and quality
    TrackBar trkScale;
    Label lblScaleVal;
    TrackBar trkQuality;
    Label lblQualityVal;

    // v1.9.8 new controls
    Button btnVelocidade;
    Button btnEquilibrado;
    Button btnQualidade;
    Button btnPersonalizado;
    
    CheckBox chkAutoFps;
    RadioButton rdoFps20;
    RadioButton rdoFps24;
    RadioButton rdoFps30;
    RadioButton rdoFps60;
    
    TrackBar trkSens;
    Label lblSensVal;
    CheckBox chkInvertScroll;
    CheckBox chkSystemTray;
    CheckBox chkStartWithWindows;
    CheckBox chkTransmitAudio;
    
    NotifyIcon trayIcon;
    ContextMenuStrip trayMenu;
    bool reallyClose = false;

    public bool StopRequested { get; private set; }

    public bool AutoFpsEnabled => chkAutoFps.InvokeRequired ? (bool)chkAutoFps.Invoke(new Func<bool>(() => chkAutoFps.Checked)) : chkAutoFps.Checked;
    
    public int TargetFps {
        get {
            if (rdoFps20.InvokeRequired) return (int)rdoFps20.Invoke(new Func<int>(() => GetTargetFpsInternal()));
            return GetTargetFpsInternal();
        }
    }
    
    private int GetTargetFpsInternal() {
        if (rdoFps20.Checked) return 20;
        if (rdoFps24.Checked) return 24;
        if (rdoFps60.Checked) return 60;
        return 30; // default
    }

    public ServerForm()
    {
        Text = "MirrorX Server v1.9.9";
        Size = new Size(580, 1120);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = BG;
        ForeColor = TEXT;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        DoubleBuffered = true;

        var y = 12;

        // Header
        var header = new Label {
            Text = "MirrorX Server",
            Font = new Font("Segoe UI", 20, FontStyle.Bold),
            ForeColor = ACCENT,
            Location = new Point(16, y),
            AutoSize = true
        };
        Controls.Add(header);
        y += 40;

        var sub = new Label {
            Text = "v1.9.9 \u2022 Touchpad Inteligente & Espelhamento",
            Font = new Font("Segoe UI", 9),
            ForeColor = DIM,
            Location = new Point(16, y),
            AutoSize = true
        };
        Controls.Add(sub);
        y += 28;

        // CARD 1 - Servidor
        y = AddCard("SERVIDOR", y, cardBody => {
            lblStatus  = MakeRow("Status",   "Iniciando...",      DIM,   cardBody, 0);
            lblIp      = MakeRow("IP Local", "Detectando...",     DIM,   cardBody, 1);
            lblPorts   = MakeRow("Portas",   "8080, 9900, 7777, 9999 (UDP)", DIM,   cardBody, 2);
            lblFirewall= MakeRow("Firewall", "Desconhecido",      DIM,   cardBody, 3);
        });
        y += 8;

        // CARD 2 - Clientes
        y = AddCard("CLIENTES CONECTADOS", y, cardBody => {
            lblClients = new Label {
                Text = "0 conectados",
                Font = new Font("Segoe UI Semibold", 14),
                ForeColor = GREEN,
                Location = new Point(12, 4),
                AutoSize = true
            };
            cardBody.Controls.Add(lblClients);

            listClients = new ListView {
                View = View.Details,
                FullRowSelect = true,
                GridLines = false,
                BorderStyle = BorderStyle.None,
                BackColor = CARD2,
                ForeColor = TEXT,
                Font = new Font("Consolas", 10),
                Location = new Point(12, 32),
                Size = new Size(cardBody.Width - 24, 90),
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            listClients.Columns.Add("IP Cliente", 140);
            listClients.Columns.Add("Conectado em", 140);
            listClients.Columns.Add("Latência (Ping)", 140);
            listClients.Columns.Add("Status", 100);
            cardBody.Controls.Add(listClients);
        });
        y += 8;

        // CARD 3 - CONFIGURAÇÃO DE VÍDEO
        y = AddCard("CONFIGURAÇÃO DE VÍDEO", y, cardBody => {
            // Perfis Rápidos
            var lblProfile = new Label {
                Text = "Perfis:",
                Font = new Font("Segoe UI", 10),
                ForeColor = DIM,
                Location = new Point(12, 12),
                AutoSize = true
            };
            cardBody.Controls.Add(lblProfile);

            btnVelocidade = ProfileButton("Velocidade", 100, 8);
            btnVelocidade.Click += (s, e) => {
                Program.ScaleFactor = 0.5;
                Program.JpegQuality = 30;
                SetScaleSliderValue(50);
                SetQualitySliderValue(30);
                HighlightProfileButton(btnVelocidade);
                Log("Perfil alterado para Velocidade (Escala 50%, Qualidade 30)");
            };
            cardBody.Controls.Add(btnVelocidade);

            btnEquilibrado = ProfileButton("Equilibrado", 215, 8);
            btnEquilibrado.Click += (s, e) => {
                Program.ScaleFactor = 0.75;
                Program.JpegQuality = 45;
                SetScaleSliderValue(75);
                SetQualitySliderValue(45);
                HighlightProfileButton(btnEquilibrado);
                Log("Perfil alterado para Equilibrado (Escala 75%, Qualidade 45)");
            };
            cardBody.Controls.Add(btnEquilibrado);

            btnQualidade = ProfileButton("Qualidade", 330, 8);
            btnQualidade.Click += (s, e) => {
                Program.ScaleFactor = 1.0;
                Program.JpegQuality = 80;
                SetScaleSliderValue(100);
                SetQualitySliderValue(80);
                HighlightProfileButton(btnQualidade);
                Log("Perfil alterado para Qualidade (Escala 100%, Qualidade 80)");
            };
            cardBody.Controls.Add(btnQualidade);

            btnPersonalizado = ProfileButton("Manual", 445, 8);
            btnPersonalizado.Click += (s, e) => {
                HighlightProfileButton(btnPersonalizado);
            };
            cardBody.Controls.Add(btnPersonalizado);

            // Escala
            var lblScaleLbl = new Label {
                Text = "Escala da Tela:",
                Font = new Font("Segoe UI", 10),
                ForeColor = DIM,
                Location = new Point(12, 54),
                AutoSize = true
            };
            cardBody.Controls.Add(lblScaleLbl);

            lblScaleVal = new Label {
                Text = "100%",
                Font = new Font("Segoe UI Semibold", 10),
                ForeColor = ACCENT,
                Location = new Point(125, 54),
                Width = 55
            };
            cardBody.Controls.Add(lblScaleVal);

            trkScale = new TrackBar {
                Minimum = 25, Maximum = 100, Value = 100, TickFrequency = 5,
                Location = new Point(180, 48),
                Size = new Size(cardBody.Width - 200, 36),
                BackColor = CARD2, ForeColor = ACCENT,
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            trkScale.ValueChanged += (s, e) => {
                double scale = trkScale.Value / 100.0;
                Program.ScaleFactor = scale;
                lblScaleVal.Text = $"{trkScale.Value}%";
                if (!chkAutoFps.Checked) HighlightProfileButton(btnPersonalizado);
            };
            cardBody.Controls.Add(trkScale);

            // Qualidade
            var lblQualityLbl = new Label {
                Text = "Qualidade JPEG:",
                Font = new Font("Segoe UI", 10),
                ForeColor = DIM,
                Location = new Point(12, 94),
                AutoSize = true
            };
            cardBody.Controls.Add(lblQualityLbl);

            lblQualityVal = new Label {
                Text = "45",
                Font = new Font("Segoe UI Semibold", 10),
                ForeColor = ACCENT,
                Location = new Point(125, 94),
                Width = 40
            };
            cardBody.Controls.Add(lblQualityVal);

            trkQuality = new TrackBar {
                Minimum = 1, Maximum = 100, Value = 45, TickFrequency = 5,
                Location = new Point(180, 88),
                Size = new Size(cardBody.Width - 200, 36),
                BackColor = CARD2, ForeColor = ACCENT,
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            trkQuality.ValueChanged += (s, e) => {
                Program.JpegQuality = trkQuality.Value;
                lblQualityVal.Text = $"{trkQuality.Value}";
                if (!chkAutoFps.Checked) HighlightProfileButton(btnPersonalizado);
            };
            cardBody.Controls.Add(trkQuality);

            // Auto FPS
            chkAutoFps = new CheckBox {
                Text = "Ativar Modo Auto-FPS (Ajuste dinâmico de Qualidade/Escala)",
                Font = new Font("Segoe UI Semibold", 9, FontStyle.Italic),
                ForeColor = ACCENT,
                Location = new Point(16, 130),
                Size = new Size(480, 24)
            };
            chkAutoFps.CheckedChanged += (s, e) => {
                bool auto = chkAutoFps.Checked;
                trkScale.Enabled = !auto;
                trkQuality.Enabled = !auto;
                btnVelocidade.Enabled = !auto;
                btnEquilibrado.Enabled = !auto;
                btnQualidade.Enabled = !auto;
                btnPersonalizado.Enabled = !auto;
                Log($"Modo Auto-FPS {(auto ? "ativado" : "desativado")}");
            };
            cardBody.Controls.Add(chkAutoFps);

            // FPS Alvo Options
            var lblTargetFps = new Label {
                Text = "FPS Alvo:",
                Font = new Font("Segoe UI", 9),
                ForeColor = DIM,
                Location = new Point(16, 160),
                AutoSize = true
            };
            cardBody.Controls.Add(lblTargetFps);

            rdoFps20 = new RadioButton { Text = "20 FPS", ForeColor = TEXT, Location = new Point(100, 158), Size = new Size(70, 24) };
            rdoFps24 = new RadioButton { Text = "24 FPS", ForeColor = TEXT, Location = new Point(180, 158), Size = new Size(70, 24) };
            rdoFps30 = new RadioButton { Text = "30 FPS", ForeColor = TEXT, Location = new Point(260, 158), Size = new Size(70, 24), Checked = true };
            rdoFps60 = new RadioButton { Text = "60 FPS", ForeColor = TEXT, Location = new Point(340, 158), Size = new Size(70, 24) };
            
            cardBody.Controls.Add(rdoFps20);
            cardBody.Controls.Add(rdoFps24);
            cardBody.Controls.Add(rdoFps30);
            cardBody.Controls.Add(rdoFps60);
        });
        y += 8;

        // CARD 4 - CONFIGURAÇÃO DO TOUCHPAD & SISTEMA
        y = AddCard("CONFIGURAÇÃO DO TOUCHPAD & SISTEMA", y, cardBody => {
            // Sensibilidade
            var lblSens = new Label {
                Text = "Sensibilidade:",
                Font = new Font("Segoe UI", 10),
                ForeColor = DIM,
                Location = new Point(12, 14),
                AutoSize = true
            };
            cardBody.Controls.Add(lblSens);

            lblSensVal = new Label {
                Text = "1.5x",
                Font = new Font("Segoe UI Semibold", 10),
                ForeColor = ACCENT,
                Location = new Point(115, 14),
                Width = 40
            };
            cardBody.Controls.Add(lblSensVal);

            trkSens = new TrackBar {
                Minimum = 5, Maximum = 40, Value = 15, TickFrequency = 5,
                Location = new Point(160, 8),
                Size = new Size(cardBody.Width - 180, 36),
                BackColor = CARD2, ForeColor = ACCENT,
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            trkSens.ValueChanged += (s, e) => {
                double sens = trkSens.Value / 10.0;
                Program.MouseSensitivity = sens;
                lblSensVal.Text = $"{sens:F1}x";
            };
            cardBody.Controls.Add(trkSens);

            // Checkboxes
            chkInvertScroll = new CheckBox {
                Text = "Inverter Rolagem (Scroll Natural)",
                Font = new Font("Segoe UI", 9.5f),
                ForeColor = TEXT,
                Location = new Point(16, 50),
                Size = new Size(480, 24)
            };
            chkInvertScroll.CheckedChanged += (s, e) => {
                Program.InvertScroll = chkInvertScroll.Checked;
            };
            cardBody.Controls.Add(chkInvertScroll);

            chkSystemTray = new CheckBox {
                Text = "Minimizar para a Bandeja do Sistema (System Tray) ao fechar",
                Font = new Font("Segoe UI", 9.5f),
                ForeColor = TEXT,
                Location = new Point(16, 80),
                Size = new Size(480, 24),
                Checked = true
            };
            cardBody.Controls.Add(chkSystemTray);

            chkStartWithWindows = new CheckBox {
                Text = "Iniciar automaticamente com o Windows",
                Font = new Font("Segoe UI", 9.5f),
                ForeColor = TEXT,
                Location = new Point(16, 110),
                Size = new Size(480, 24)
            };
            chkStartWithWindows.Checked = IsStartupEnabled();
            chkStartWithWindows.CheckedChanged += (s, e) => {
                SetStartup(chkStartWithWindows.Checked);
                Log($"Auto-iniciar com Windows {(chkStartWithWindows.Checked ? "ativado" : "desativado")}");
            };
            cardBody.Controls.Add(chkStartWithWindows);

            chkTransmitAudio = new CheckBox {
                Text = "Transmitir Áudio do Sistema para o Celular",
                Font = new Font("Segoe UI", 9.5f),
                ForeColor = TEXT,
                Location = new Point(16, 140),
                Size = new Size(480, 24)
            };
            chkTransmitAudio.CheckedChanged += (s, e) => {
                Log(chkTransmitAudio.Checked ? "Transmissão de áudio iniciada..." : "Transmissão de áudio interrompida.");
            };
            cardBody.Controls.Add(chkTransmitAudio);
        });
        y += 8;

        // CARD 5 - Log
        y = AddCard("EVENTOS", y, cardBody => {
            logBox = new TextBox {
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                BackColor = CARD2,
                ForeColor = TEXT,
                BorderStyle = BorderStyle.None,
                Font = new Font("Consolas", 9),
                Location = new Point(12, 4),
                Size = new Size(cardBody.Width - 24, 100),
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Bottom
            };
            cardBody.Controls.Add(logBox);
        });
        y += 8;

        // Botões
        var btnPanel = new Panel {
            Location = new Point(16, y),
            Size = new Size(ClientSize.Width - 32, 50),
            Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
        };
        Controls.Add(btnPanel);

        btnFirewall = DarkButton("\U0001f525 Adicionar Firewall", ACCENT, 12, 8, 1);
        btnFirewall.Click += (s, e) => {
            Log("Solicitando regra de firewall...");
            Program.RequestFirewallRule();
        };
        btnPanel.Controls.Add(btnFirewall);

        btnStop = DarkButton("\u23f9  Parar Servidor", RED, btnFirewall.Right + 12, 8, 1);
        btnStop.Click += (s, e) => {
            reallyClose = true;
            StopRequested = true;
            Log("Solicitando encerramento...");
            btnStop.Enabled = false;
            btnStop.Text = "\u23f9  Encerrando...";
            Application.Exit();
        };
        btnPanel.Controls.Add(btnStop);

        lblVersion = new Label {
            Text = "v1.9.9",
            Font = new Font("Segoe UI", 8),
            ForeColor = DIM,
            Anchor = AnchorStyles.Bottom | AnchorStyles.Right,
            AutoSize = true,
            Location = new Point(ClientSize.Width - 52, ClientSize.Height - 22)
        };
        Controls.Add(lblVersion);

        // System Tray Initialization
        trayMenu = new ContextMenuStrip();
        trayMenu.Items.Add("Abrir", null, (s, e) => ShowForm());
        trayMenu.Items.Add("Sair", null, (s, e) => ExitForm());

        trayIcon = new NotifyIcon {
            Text = "MirrorX Server v1.9.9",
            Icon = SystemIcons.Application,
            ContextMenuStrip = trayMenu,
            Visible = true
        };
        trayIcon.DoubleClick += (s, e) => ShowForm();

        FormClosing += (s, e) => {
            if (chkSystemTray.Checked && !reallyClose) {
                e.Cancel = true;
                Hide();
                trayIcon.ShowBalloonTip(2000, "MirrorX Server", "Servidor minimizado para a bandeja.", ToolTipIcon.Info);
            } else {
                trayIcon.Visible = false;
                if (!StopRequested) StopRequested = true;
            }
        };

        FormClosed += (s, e) => { 
            trayIcon.Visible = false;
            if (!StopRequested) StopRequested = true; 
        };
        
        HighlightProfileButton(btnEquilibrado); // Equilibrado por padrão
    }

    static Button ProfileButton(string text, int x, int y)
    {
        var b = new Button {
            Text = text,
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(26, 56, 138),
            ForeColor = Color.FromArgb(80, 160, 255),
            Font = new Font("Segoe UI Semibold", 8.5f, FontStyle.Bold),
            Size = new Size(100, 30),
            Location = new Point(x, y),
            Cursor = Cursors.Hand
        };
        b.FlatAppearance.BorderColor = Color.FromArgb(40, 80, 160);
        b.FlatAppearance.BorderSize = 1;
        b.FlatAppearance.MouseOverBackColor = Color.FromArgb(20, Color.FromArgb(80, 160, 255));
        return b;
    }

    int AddCard(string title, int startY, Action<Panel> bodyBuilder)
    {
        var card = new Panel {
            Location = new Point(16, startY),
            Size = new Size(ClientSize.Width - 32, 40),
            BackColor = CARD,
            Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
        };
        Controls.Add(card);

        var titleLbl = new Label {
            Text = "  " + title,
            Font = new Font("Segoe UI Semibold", 11),
            ForeColor = ACCENT,
            Location = new Point(0, 8),
            AutoSize = true
        };
        card.Controls.Add(titleLbl);

        var inner = new Panel {
            Location = new Point(0, 32),
            Size = new Size(card.Width, 20),
            BackColor = CARD2,
            Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
        };
        card.Controls.Add(inner);

        bodyBuilder(inner);

        // Resize card to fit inner content
        int maxBottom = 0;
        foreach (Control c in inner.Controls) {
            int bottom = c.Bottom;
            if (bottom > maxBottom) maxBottom = bottom;
        }
        inner.Height = Math.Max(20, maxBottom + 12);
        card.Height = 32 + inner.Height + 8;

        return startY + card.Height;
    }

    static Label MakeRow(string label, string value, Color valueColor, Panel parent, int index)
    {
        int rowY = 8 + index * 28;
        var lbl = new Label {
            Text = label + ":",
            Font = new Font("Segoe UI", 10),
            ForeColor = Color.FromArgb(150, 170, 210),
            Location = new Point(14, rowY),
            Width = 100
        };
        parent.Controls.Add(lbl);

        var val = new Label {
            Text = value,
            Font = new Font("Segoe UI Semibold", 10),
            ForeColor = valueColor,
            Location = new Point(120, rowY),
            AutoSize = true
        };
        parent.Controls.Add(val);
        return val;
    }

    static Button DarkButton(string text, Color accent, int x, int y, float scale = 1f)
    {
        var b = new Button {
            Text = text,
            FlatStyle = FlatStyle.Flat,
            BackColor = Color.FromArgb(26, 56, 138),
            ForeColor = accent,
            Font = new Font("Segoe UI Semibold", 10, FontStyle.Bold),
            Size = new Size(220, 42),
            Location = new Point(x, y),
            Cursor = Cursors.Hand
        };
        b.FlatAppearance.BorderColor = accent;
        b.FlatAppearance.BorderSize = 2;
        b.FlatAppearance.MouseOverBackColor = Color.FromArgb(40, accent);
        return b;
    }

    // ===== Public API (thread-safe via Invoke) =====

    public void SetStatus(string text, Color color)
    {
        try { BeginInvoke(() => { lblStatus.Text = text; lblStatus.ForeColor = color; }); } catch { }
    }

    public void SetIp(string ip) { try { BeginInvoke(() => lblIp.Text = ip); } catch { } }

    public void SetFirewall(string text, Color color)
    {
        try { BeginInvoke(() => { lblFirewall.Text = text; lblFirewall.ForeColor = color; }); } catch { }
    }

    public void UpdateClientCount(int count)
    {
        try { BeginInvoke(() => {
            lblClients.Text = count == 1 ? "1 conectado" : $"{count} conectados";
            lblClients.ForeColor = count > 0 ? GREEN : DIM;
        }); } catch { }
    }

    public void AddClient(string ip, string time, string ping, string status)
    {
        try { BeginInvoke(() => {
            var item = new ListViewItem(ip);
            item.UseItemStyleForSubItems = false;
            item.SubItems.Add(time);
            var pingSub = item.SubItems.Add(ping);
            pingSub.ForeColor = GREEN;
            var statusSub = item.SubItems.Add(status);
            statusSub.ForeColor = GREEN;
            listClients.Items.Insert(0, item);
            while (listClients.Items.Count > 50)
                listClients.Items.RemoveAt(50);
        }); } catch { }
    }

    public void RemoveClient(string ip)
    {
        try { BeginInvoke(() => {
            for (int i = 0; i < listClients.Items.Count; i++) {
                if (listClients.Items[i].Text == ip) {
                    listClients.Items.RemoveAt(i);
                    break;
                }
            }
        }); } catch { }
    }

    public void UpdateClientPing(string ip, int latencyMs)
    {
        try { BeginInvoke(() => {
            foreach (ListViewItem item in listClients.Items) {
                if (item.Text == ip) {
                    string pingText = $"{latencyMs} ms";
                    Color col = GREEN;
                    if (latencyMs < 20) {
                        pingText += " (Excelente)";
                        col = GREEN;
                    } else if (latencyMs < 60) {
                        pingText += " (Bom)";
                        col = AMBER;
                    } else {
                        pingText += " (Instável)";
                        col = RED;
                    }
                    item.SubItems[2].Text = pingText;
                    item.SubItems[2].ForeColor = col;
                    break;
                }
            }
        }); } catch { }
    }

    public void SetQualitySliderValue(int val)
    {
        try { BeginInvoke(() => {
            trkQuality.Value = Math.Clamp(val, 1, 100);
            lblQualityVal.Text = trkQuality.Value.ToString();
        }); } catch { }
    }

    public void SetScaleSliderValue(int val)
    {
        try { BeginInvoke(() => {
            trkScale.Value = Math.Clamp(val, 25, 100);
            lblScaleVal.Text = $"{trkScale.Value}%";
        }); } catch { }
    }

    private void HighlightProfileButton(Button selected)
    {
        Button[] profileButtons = { btnVelocidade, btnEquilibrado, btnQualidade, btnPersonalizado };
        foreach (var b in profileButtons) {
            if (b == selected) {
                b.BackColor = Color.FromArgb(40, 80, 160);
                b.ForeColor = Color.White;
                b.FlatAppearance.BorderColor = ACCENT;
            } else {
                b.BackColor = Color.FromArgb(26, 56, 138);
                b.ForeColor = ACCENT;
                b.FlatAppearance.BorderColor = Color.FromArgb(40, 80, 160);
            }
        }
    }

    private void ShowForm()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    private void ExitForm()
    {
        reallyClose = true;
        Close();
    }

    private bool IsStartupEnabled()
    {
        try {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", false);
            return key?.GetValue("MirrorXServer") != null;
        } catch { return false; }
    }

    private void SetStartup(bool start)
    {
        try {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", true);
            if (key != null) {
                if (start) {
                    key.SetValue("MirrorXServer", Application.ExecutablePath);
                } else {
                    key.DeleteValue("MirrorXServer", false);
                }
            }
        } catch { }
    }

    public void Log(string text, string kind = "ok")
    {
        try { BeginInvoke(() => {
            string stamp = DateTime.Now.ToString("HH:mm:ss");
            string prefix = kind == "bad" ? "[ERRO]" : kind == "warn" ? "[!]" : "[OK]";
            logBox.AppendText($"{stamp} {prefix} {text}\r\n");
            logBox.SelectionStart = logBox.TextLength;
            logBox.ScrollToCaret();
            while (logBox.Lines.Length > 200) {
                logBox.SelectionStart = 0;
                logBox.SelectionLength = logBox.GetFirstCharIndexFromLine(1);
                logBox.SelectedText = "";
            }
        }); } catch { }
    }
}
