using System.Net;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Windows.Threading;
using Relay.Desktop;
using Relay.Hub.Core;

internal static class Program
{
    [STAThread]
    private static int Main()
    {
        var dispatcher = Dispatcher.CurrentDispatcher;
        SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
        var task = Run();
        _ = task.ContinueWith(_ => dispatcher.BeginInvokeShutdown(DispatcherPriority.Background));
        Dispatcher.Run();
        try { task.GetAwaiter().GetResult(); return 0; }
        catch (Exception e) { Console.Error.WriteLine(e); return 1; }
    }

    private static async Task Run()
    {
        var directory = Path.Combine(Path.GetTempPath(), "RelayDesktopChecks-" + Guid.NewGuid().ToString("N"));
        var peerKey = "RelayDesktopChecksPeer-" + Guid.NewGuid().ToString("N");
        var probe = new TcpListener(IPAddress.Loopback, 0); probe.Start(); var port = ((IPEndPoint)probe.LocalEndpoint).Port; probe.Stop();
        var engine = new HubEngine(directory, true);
        engine.Options.ListenAddresses = ["127.0.0.1"]; engine.Options.AllowedCidrs = ["127.0.0.0/8"]; engine.Options.Port = port;
        var fake = new FakeAudio();
        await using var view = new MainViewModel(directory, fake, engine);
        void Assert(bool value, string message) { if (!value) throw new InvalidOperationException(message); }
        async Task Wait(Func<bool> condition)
        {
            var deadline = DateTime.UtcNow.AddSeconds(5);
            while (!condition()) { if (DateTime.UtcNow > deadline) throw new TimeoutException("Desktop state did not settle."); await Task.Delay(10); }
        }
        try
        {
            await view.BeginTalkAsync(); Assert(fake.Starts == 0, "Microphone opened before a connected session.");
            Console.WriteLine("PASS: Desktop never opens microphone without a connected eligible call");
            await engine.StartAsync();
            var keyOptions = new CngKeyCreationParameters { ExportPolicy = CngExportPolicies.None, KeyUsage = CngKeyUsages.Signing };
            keyOptions.Parameters.Add(new CngProperty("Length", BitConverter.GetBytes(2048), CngPropertyOptions.None));
            using var key = CngKey.Create(CngAlgorithm.Rsa, peerKey, keyOptions);
            using var rsa = new RSACng(key);
            var request = new CertificateRequest("CN=Relay desktop lifecycle test", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(1));
            var fingerprint = Convert.ToHexString(SHA256.HashData(certificate.RawData));
            var invite = engine.CreateEnrollmentUri("127.0.0.1").Split("token=")[1];
            await using var control = await Peer.Open(port, engine.Fingerprint, certificate);
            await control.Send(new { type = "register", version = 1, name = "Lifecycle phone", token = invite, capabilities = new[] { "dispatch-v1" } });
            await control.Until("pending"); engine.Approve(fingerprint); await control.Until("registered");
            await engine.StartDispatchAsync("call", [fingerprint]);
            var offer = await control.Until("dispatch_offer");
            await using var data = await Peer.Open(port, engine.Fingerprint, certificate);
            await data.Send(new { type = "dispatch_attach", version = 1, id = offer.GetProperty("id").GetString() });
            await data.Until("dispatch_ready"); await Wait(() => engine.Dispatch.Participants.Any(p => p.State == "Connected"));
            view.Page = "Dispatch"; view.Refresh();
            await view.BeginTalkAsync(); Assert(fake.IsCapturing && view.IsTransmitting && engine.Dispatch.LocalTalking, "Held microphone failed to acquire floor.");
            view.ReleaseTalk(); await Wait(() => !fake.IsCapturing);
            Assert(!view.IsTransmitting && !engine.Dispatch.LocalTalking, "Release retained microphone or floor.");
            Console.WriteLine("PASS: Desktop hold acquires floor and release closes capture");

            var delay = fake.DelayNext();
            var late = view.BeginTalkAsync(); await Wait(() => fake.Starts >= 2);
            view.ReleaseTalk(); delay.TrySetResult(); await late; await Wait(() => !fake.IsCapturing);
            Assert(!view.IsTransmitting && !view.IsTalkPressed, "Late hardware start revived released capture.");
            Console.WriteLine("PASS: Delayed microphone start cannot revive a released button");

            delay = fake.DelayNext(); var oldStartCount = fake.Starts;
            var old = view.BeginTalkAsync(); await Wait(() => fake.Starts > oldStartCount);
            view.ReleaseTalk(); var current = view.BeginTalkAsync();
            delay.TrySetResult(); await Task.WhenAll(old, current);
            Assert(fake.IsCapturing && view.IsTransmitting && view.IsTalkPressed, "A stale start closed the newer hold's microphone.");
            view.ReleaseTalk(); await Wait(() => !fake.IsCapturing);
            Console.WriteLine("PASS: Rapid release/repress isolates old and current capture attempts");

            await data.Send(new { type = "talk", on = true }); await Wait(() => engine.Dispatch.RemoteTalking); view.Refresh();
            int starts = fake.Starts; await view.BeginTalkAsync(); Assert(fake.Starts == starts, "Desktop captured while phone owns floor.");
            await data.Send(new { type = "talk", on = false }); await Wait(() => !engine.Dispatch.RemoteTalking); view.Refresh();
            Console.WriteLine("PASS: Remote floor blocks desktop microphone capture");
            await view.BeginTalkAsync(); view.Page = "Devices"; await Wait(() => !fake.IsCapturing);
            Assert(!engine.Dispatch.LocalTalking && !view.IsTalkPressed, "Navigation retained capture.");
            Console.WriteLine("PASS: Leaving dispatch releases microphone and floor");
            view.Page = "Dispatch"; await view.BeginTalkAsync(); view.EndDispatchCommand.Execute(null); await Wait(() => !fake.IsCapturing);
            Assert(!engine.Dispatch.Active && !view.IsTransmitting, "Ending dispatch retained microphone.");
            Console.WriteLine("PASS: Ending dispatch stops microphone capture");
            Console.WriteLine("7 desktop microphone lifecycle checks passed; synthetic audio adapter only.");
        }
        finally
        {
            await view.DisposeAsync();
            var hubKey = "RelayHub-" + Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(Path.GetFullPath(directory).ToUpperInvariant())))[..32];
            foreach (var name in new[] { hubKey, peerKey }) if (CngKey.Exists(name)) { using var cleanup = CngKey.Open(name); cleanup.Delete(); }
        }
    }
}

internal sealed class FakeAudio : IDesktopAudio
{
    public event Action<byte[]>? Captured { add { } remove { } }
    public event Action<string>? Failed { add { } remove { } }
    public IReadOnlyList<AudioDeviceChoice> Inputs => [new(-1, "Synthetic microphone")];
    public IReadOnlyList<AudioDeviceChoice> Outputs => [new(-1, "Synthetic speaker")];
    public bool IsCapturing { get; private set; }
    public int Starts { get; private set; }
    private TaskCompletionSource? next;
    public TaskCompletionSource DelayNext() => next = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public void RefreshDevices() { }
    public async Task StartCaptureAsync(int device, CancellationToken cancellation)
    {
        Starts++; var wait = next; next = null;
        if (wait != null) await wait.Task; // Simulate a hardware API that completes after cancellation.
        IsCapturing = true;
    }
    public Task StopCaptureAsync() { IsCapturing = false; return Task.CompletedTask; }
    public void SetPlaybackDevice(int device) { }
    public void QueuePlayback(byte[] pcm) { }
    public Task StopPlaybackAsync() => Task.CompletedTask;
    public ValueTask DisposeAsync() { IsCapturing = false; return ValueTask.CompletedTask; }
}

internal sealed class Peer(TcpClient client, SslStream stream) : IAsyncDisposable
{
    public static async Task<Peer> Open(int port, string fingerprint, X509Certificate2 cert)
    {
        var client = new TcpClient { NoDelay = true }; await client.ConnectAsync(IPAddress.Loopback, port);
        var stream = new SslStream(client.GetStream(), false, (_, remote, _, _) => remote != null && Convert.ToHexString(SHA256.HashData(remote.GetRawCertData())) == fingerprint);
        await stream.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = "relay", ClientCertificates = new X509CertificateCollection { cert }, LocalCertificateSelectionCallback = (_, _, _, _, _) => cert, EnabledSslProtocols = SslProtocols.Tls12 });
        return new(client, stream);
    }
    public Task Send(object value) => HubWire.WriteAsync(stream, value);
    public async Task<JsonElement> Until(string expected)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        while (true)
        {
            var frame = await HubWire.ReadAsync(stream, timeout.Token); var type = frame.GetProperty("type").GetString();
            if (type == "ping") await Send(new { type = "pong" });
            if (type == "error") throw new IOException(frame.ToString());
            if (type == expected) return frame;
        }
    }
    public ValueTask DisposeAsync() { stream.Dispose(); client.Dispose(); return ValueTask.CompletedTask; }
}
