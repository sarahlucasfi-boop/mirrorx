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

    public bool StopRequested { get; private set; }

    public ServerForm()
    {
        Text = "MirrorX Server v1.9.7";
        Size = new Size(520, 800);
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
            Text = "v1.9.7 \u2022 Right Click Gestos+Servidor",
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
            lblPorts   = MakeRow("Portas",   "8080, 9900, 7777", DIM,   cardBody, 2);
            lblFirewall= MakeRow("Firewall", "Desconhecido",      DIM,   cardBody, 3);
        });
        y += 8;

        // CARD 2 - Clientes
        y = AddCard("CLIENTES", y, cardBody => {
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
            listClients.Columns.Add("IP Cliente", 200);
            listClients.Columns.Add("Conectado em", 250);
            cardBody.Controls.Add(listClients);
        });
        y += 8;

        // CARD 3 - CONFIGURAÇÃO (v1.9.1: fundo azul, título acentuado, Scale=100%, Quality=45)
        y = AddCard("CONFIGURAÇÃO", y, cardBody => {
            // ── Escala ──────────────────────────────────────────────────
            var lblScaleLbl = new Label {
                Text = "Escala:",
                Font = new Font("Segoe UI", 10),
                ForeColor = DIM,
                Location = new Point(12, 10),
                AutoSize = true
            };
            cardBody.Controls.Add(lblScaleLbl);

            lblScaleVal = new Label {
                Text = "100%",
                Font = new Font("Segoe UI Semibold", 10),
                ForeColor = ACCENT,
                Location = new Point(75, 10),
                Width = 55
            };
            cardBody.Controls.Add(lblScaleVal);

            trkScale = new TrackBar {
                Minimum = 25, Maximum = 100, Value = 100, TickFrequency = 5,
                Location = new Point(138, 5),
                Size = new Size(cardBody.Width - 188, 36),
                BackColor = CARD2, ForeColor = ACCENT,
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            trkScale.ValueChanged += (s, e) => {
                double scale = trkScale.Value / 100.0;
                Program.ScaleFactor = scale;
                lblScaleVal.Text = $"{trkScale.Value}%";
            };
            cardBody.Controls.Add(trkScale);

            // ── Qualidade ────────────────────────────────────────────────
            var lblQualityLbl = new Label {
                Text = "Qualidade:",
                Font = new Font("Segoe UI", 10),
                ForeColor = DIM,
                Location = new Point(12, 50),
                AutoSize = true
            };
            cardBody.Controls.Add(lblQualityLbl);

            lblQualityVal = new Label {
                Text = "45",
                Font = new Font("Segoe UI Semibold", 10),
                ForeColor = ACCENT,
                Location = new Point(94, 50),
                Width = 40
            };
            cardBody.Controls.Add(lblQualityVal);

            trkQuality = new TrackBar {
                Minimum = 1, Maximum = 100, Value = 45, TickFrequency = 5,
                Location = new Point(138, 45),
                Size = new Size(cardBody.Width - 188, 36),
                BackColor = CARD2, ForeColor = ACCENT,
                Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right
            };
            trkQuality.ValueChanged += (s, e) => {
                Program.JpegQuality = trkQuality.Value;
                lblQualityVal.Text = $"{trkQuality.Value}";
            };
            cardBody.Controls.Add(trkQuality);
        });
        y += 8;

        // CARD 4 - Log
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
                Size = new Size(cardBody.Width - 24, 130),
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
            StopRequested = true;
            Log("Solicitando encerramento...");
            btnStop.Enabled = false;
            btnStop.Text = "\u23f9  Encerrando...";
        };
        btnPanel.Controls.Add(btnStop);

        lblVersion = new Label {
            Text = "v1.9.7",
            Font = new Font("Segoe UI", 8),
            ForeColor = DIM,
            Anchor = AnchorStyles.Bottom | AnchorStyles.Right,
            AutoSize = true,
            Location = new Point(ClientSize.Width - 52, ClientSize.Height - 22)
        };
        Controls.Add(lblVersion);

        FormClosed += (s, e) => { if (!StopRequested) StopRequested = true; };
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

    public void AddClient(string ip)
    {
        try { BeginInvoke(() => {
            var item = new ListViewItem(ip);
            item.SubItems.Add(DateTime.Now.ToString("HH:mm:ss"));
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
