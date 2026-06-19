using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;
using MapFlags = Vortice.Direct3D11.MapFlags;

namespace MirrorXServer;

class DesktopDuplicator
{
    readonly ID3D11Device device;
    readonly ID3D11DeviceContext ctx;
    readonly IDXGIOutputDuplication dup;
    readonly ID3D11Texture2D staging;
    readonly int width, height;
    public Rectangle Bounds { get; }

    byte[]? cursorShape;
    OutduplPointerShapeInfo cursorInfo;
    int cursorX, cursorY;
    bool cursorVisible;

    static IDXGIAdapter GetAdapter(ID3D11Device d)
    {
        using var dxgiDev = d.QueryInterface<IDXGIDevice>();
        return dxgiDev.GetAdapter();
    }

    public static void ListOutputs()
    {
        D3D11.D3D11CreateDevice(
            null, DriverType.Hardware, DeviceCreationFlags.BgraSupport,
            new[] { FeatureLevel.Level_11_0 },
            out ID3D11Device d, out ID3D11DeviceContext c).CheckError();
        c.Dispose();

        using var adapter = GetAdapter(d);
        uint i = 0;
        while (true)
        {
            var result = adapter.EnumOutputs(i, out IDXGIOutput? o);
            if (result.Failure || o == null) break;
            var dc = o.Description.DesktopCoordinates;
            Console.WriteLine($"Output {i}: {o.Description.DeviceName} " +
                              $"{dc.Right - dc.Left}x{dc.Bottom - dc.Top} at ({dc.Left},{dc.Top})");
            o.Dispose();
            i++;
        }
        d.Dispose();
    }

    public DesktopDuplicator(int outputIndex)
    {
        D3D11.D3D11CreateDevice(
            null, DriverType.Hardware, DeviceCreationFlags.BgraSupport,
            new[] { FeatureLevel.Level_11_0 },
            out ID3D11Device dev, out ctx).CheckError();
        device = dev;

        using var adapter = GetAdapter(device);
        adapter.EnumOutputs((uint)outputIndex, out IDXGIOutput? output).CheckError();
        using var output1 = output!.QueryInterface<IDXGIOutput1>();

        var d = output.Description.DesktopCoordinates;
        Bounds = new Rectangle(d.Left, d.Top, d.Right - d.Left, d.Bottom - d.Top);
        width = Bounds.Width; height = Bounds.Height;

        dup = output1.DuplicateOutput(device);

        staging = device.CreateTexture2D(new Texture2DDescription
        {
            Width = (uint)width, Height = (uint)height,
            MipLevels = 1, ArraySize = 1,
            Format = Format.B8G8R8A8_UNorm,
            SampleDescription = new SampleDescription(1, 0),
            Usage = ResourceUsage.Staging,
            CPUAccessFlags = CpuAccessFlags.Read,
            BindFlags = BindFlags.None
        });
    }

    public Bitmap? CaptureFrame()
    {
        IDXGIResource? res = null;
        try
        {
            var r = dup.AcquireNextFrame(100u, out OutduplFrameInfo info, out res);
            if (r.Failure) return null;

            if (info.LastMouseUpdateTime > 0)
            {
                cursorVisible = info.PointerPosition.Visible;
                cursorX = info.PointerPosition.Position.X;
                cursorY = info.PointerPosition.Position.Y;
            }
            if (info.PointerShapeBufferSize > 0)
            {
                cursorShape = new byte[info.PointerShapeBufferSize];
                var h = GCHandle.Alloc(cursorShape, GCHandleType.Pinned);
                dup.GetFramePointerShape(
                    (uint)cursorShape.Length,
                    h.AddrOfPinnedObject(),
                    out _,
                    out cursorInfo);
                h.Free();
            }

            using var tex = res.QueryInterface<ID3D11Texture2D>();
            ctx.CopyResource(staging, tex);

            var map = ctx.Map(staging, 0u, MapMode.Read, MapFlags.None);
            var bmp = new Bitmap(width, height, PixelFormat.Format32bppArgb);
            var bd = bmp.LockBits(new Rectangle(0, 0, width, height),
                ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
            var row = new byte[width * 4];
            for (int y = 0; y < height; y++)
            {
                Marshal.Copy(IntPtr.Add(map.DataPointer, y * (int)map.RowPitch), row, 0, width * 4);
                Marshal.Copy(row, 0, IntPtr.Add(bd.Scan0, y * bd.Stride), width * 4);
            }
            bmp.UnlockBits(bd);
            ctx.Unmap(staging, 0u);

            if (cursorVisible && cursorShape != null) DrawCursor(bmp);
            return bmp;
        }
        finally
        {
            res?.Dispose();
            try { dup.ReleaseFrame(); } catch { }
        }
    }

    void DrawCursor(Bitmap bmp)
    {
        uint type = cursorInfo.Type;
        int cw = (int)cursorInfo.Width;
        int ch = type == (uint)PointerShapeType.Monochrome
            ? (int)cursorInfo.Height / 2
            : (int)cursorInfo.Height;
        int pitch = (int)cursorInfo.Pitch;

        int ox = cursorX - Bounds.Left;
        int oy = cursorY - Bounds.Top;

        for (int y = 0; y < ch; y++)
        for (int x = 0; x < cw; x++)
        {
            int sx = ox + x, sy = oy + y;
            if (sx < 0 || sy < 0 || sx >= width || sy >= height) continue;

            if (type == (uint)PointerShapeType.Color)
            {
                int i = y * pitch + x * 4;
                byte b = cursorShape![i], g = cursorShape[i + 1], r = cursorShape[i + 2], a = cursorShape[i + 3];
                if (a == 0) continue;
                var dst = bmp.GetPixel(sx, sy);
                bmp.SetPixel(sx, sy, Color.FromArgb(
                    (r * a + dst.R * (255 - a)) / 255,
                    (g * a + dst.G * (255 - a)) / 255,
                    (b * a + dst.B * (255 - a)) / 255));
            }
            else if (type == (uint)PointerShapeType.MaskedColor)
            {
                int i = y * pitch + x * 4;
                byte b = cursorShape![i], g = cursorShape[i + 1], r = cursorShape[i + 2], mask = cursorShape[i + 3];
                var dst = bmp.GetPixel(sx, sy);
                bmp.SetPixel(sx, sy, mask == 0
                    ? Color.FromArgb(r, g, b)
                    : Color.FromArgb(dst.R ^ r, dst.G ^ g, dst.B ^ b));
            }
            else // Monochrome
            {
                int bit = 7 - (x % 8);
                bool andBit = (cursorShape![y * pitch + x / 8] >> bit & 1) == 1;
                bool xorBit = (cursorShape[(y + ch) * pitch + x / 8] >> bit & 1) == 1;
                var dst = bmp.GetPixel(sx, sy);
                if (!andBit & !xorBit) bmp.SetPixel(sx, sy, Color.Black);
                else if (!andBit & xorBit) bmp.SetPixel(sx, sy, Color.White);
                else if (andBit & xorBit) bmp.SetPixel(sx, sy, Color.FromArgb(255 - dst.R, 255 - dst.G, 255 - dst.B));
            }
        }
    }
}