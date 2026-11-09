using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using Relay.Hub.Core;

var failures = new List<string>();
int passed = 0;
async Task Check(string name, Func<Task> test)
{
    try { await test(); Console.WriteLine("PASS " + name); passed++; }
    catch (Exception e) { failures.Add(name); Console.WriteLine($"FAIL {name}\n{e}"); }
}
void Assert(bool condition, string message) { if (!condition) throw new InvalidOperationException(message); }
var path = Path.Combine(Path.GetTempPath(), "RelayHubTests-" + Guid.NewGuid().ToString("N"));
await using var hub = new HubEngine(path, true);
var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
hub.Options.ListenAddresses = ["127.0.0.1"];
hub.Options.AllowedCidrs = ["127.0.0.0/8"];
hub.Options.Port = port;
await hub.StartAsync();
using var certA = Peer.CreateCertificate("A");
using var certB = Peer.CreateCertificate("B");
using var certC = Peer.CreateCertificate("C");
using var certD = Peer.CreateCertificate("D");
var fpA = Peer.Fingerprint(certA);
var fpB = Peer.Fingerprint(certB);
var fpC = Peer.Fingerprint(certC);
var fpD = Peer.Fingerprint(certD);
Peer? controlA = null, controlB = null, controlC = null, controlD = null;
string Token() => new Uri(hub.CreateEnrollmentUri("127.0.0.1")).Query.Split('&').Single(s => s.StartsWith("token=")).Split('=')[1];
async Task<Peer> Register(X509Certificate2 cert, string? token = null)
{
    var peer = await Peer.Connect(port, hub.Fingerprint, cert);
    await peer.Send(new { type = "register", version = 1, name = cert.GetNameInfo(X509NameType.SimpleName, false), appVersion = "test", addresses = new[] { "192.168.10.20" }, port = 52743, token });
    return peer;
}

try
{
    var firstToken = Token();
    await Check("Enrollment remains pending until operator approval", async () =>
    {
        controlA = await Register(certA, firstToken);
        Assert((await controlA.ReadType("pending")).GetProperty("type").GetString() == "pending", "Missing pending response.");
        Assert(hub.Devices.Single(d => d.Fingerprint == fpA) is { Approved: false, Online: true }, "Pending enrollment incorrectly approved.");
    });
    await Check("Pending device cannot open relay", async () =>
    {
        await using var peer = await Peer.Connect(port, hub.Fingerprint, certA);
        await peer.Send(new { type = "open", version = 1, target = fpB, purpose = "call" });
        Assert((await peer.ReadType("error")).GetProperty("code").GetString() == "unauthorized", "Pending relay accepted.");
    });
    await Check("Single-use enrollment token rejects different certificate replay", async () =>
    {
        await using var replay = await Register(certB, firstToken);
        Assert((await replay.ReadType("error")).GetProperty("code").GetString() == "invalid_token", "Token replay accepted.");
    });
    await Check("Pending enrollment reconnects without reusing token", async () =>
    {
        await controlA!.DisposeAsync();
        controlA = await Register(certA);
        await controlA.ReadType("pending");
        Assert(!hub.Devices.Single(d => d.Fingerprint == fpA).Approved, "Reconnect changed approval.");
    });
    await Check("Approval returns authenticated directory", async () =>
    {
        hub.Approve(fpA);
        await controlA!.ReadType("registered");
        var directory = await controlA.ReadType("directory");
        Assert(directory.GetProperty("devices").EnumerateArray().Any(d => d.GetProperty("fingerprint").GetString() == fpA), "Directory missing enrolled device.");
    });
    await Check("Expired enrollment token is rejected", async () =>
    {
        var token = Token(); hub.ExpireTokensForTests();
        await using var expired = await Register(certC, token);
        Assert((await expired.ReadType("error")).GetProperty("code").GetString() == "token_expired", "Expired token accepted.");
    });
    await Check("Second and third devices enroll independently", async () =>
    {
        controlB = await Register(certB, Token()); await controlB.ReadType("pending"); hub.Approve(fpB); await controlB.ReadType("registered"); await controlB.ReadType("directory");
        controlC = await Register(certC, Token()); await controlC.ReadType("pending"); hub.Approve(fpC); await controlC.ReadType("registered"); await controlC.ReadType("directory");
        Assert(hub.Devices.Count(d => d.Approved) == 3, "Enrollment count mismatch.");
    });
    await Check("Wrong pinned hub fingerprint fails TLS authentication", async () =>
    {
        try { await using var wrong = await Peer.Connect(port, new string('0', 64), certA); }
        catch (AuthenticationException) { return; }
        throw new InvalidOperationException("Wrong hub identity was accepted.");
    });
    await Check("Unknown certificate cannot relay", async () =>
    {
        using var unknown = Peer.CreateCertificate("Unknown");
        await using var peer = await Peer.Connect(port, hub.Fingerprint, unknown);
        await peer.Send(new { type = "open", version = 1, target = fpB, purpose = "call" });
        Assert((await peer.ReadType("error")).GetProperty("code").GetString() == "unauthorized", "Unknown certificate accepted.");
    });
    await Check("Wrong certificate cannot attach to another device's offer", async () =>
    {
        await using var source = await Peer.Connect(port, hub.Fingerprint, certA);
        await source.Send(new { type = "open", version = 1, target = fpB, purpose = "pair" });
        var waiting = await source.ReadType("waiting");
        var offer = await controlB!.ReadType("offer");
        var id = waiting.GetProperty("id").GetString();
        Assert(offer.GetProperty("id").GetString() == id, "Offer id mismatch.");
        await using var imposter = await Peer.Connect(port, hub.Fingerprint, certC);
        await imposter.Send(new { type = "attach", version = 1, id });
        Assert((await imposter.ReadType("error")).GetProperty("code").GetString() == "invalid_tunnel", "Wrong target certificate attached.");
        // Closing the initiator is detected when the valid target attaches; no arbitrary stream can hijack the offer.
        await using var target = await Peer.Connect(port, hub.Fingerprint, certB);
        await target.Send(new { type = "attach", version = 1, id });
        await source.ReadType("ready"); await target.ReadType("ready");
    });
    await Task.Delay(150);
    await Check("Independent device pairs relay concurrently and busy devices are protected", async () =>
    {
        controlD = await Register(certD, Token()); await controlD.ReadType("pending"); hub.Approve(fpD); await controlD.ReadType("registered"); await controlD.ReadType("directory");
        await using var sourceAB = await Peer.Connect(port, hub.Fingerprint, certA);
        await sourceAB.Send(new { type = "open", version = 1, target = fpB, purpose = "call" });
        var waitingAB = await sourceAB.ReadType("waiting"); await controlB!.ReadType("offer");
        await using var targetAB = await Peer.Connect(port, hub.Fingerprint, certB);
        await targetAB.Send(new { type = "attach", version = 1, id = waitingAB.GetProperty("id").GetString() });
        await sourceAB.ReadType("ready"); await targetAB.ReadType("ready");
        await using var sourceCD = await Peer.Connect(port, hub.Fingerprint, certC);
        await sourceCD.Send(new { type = "open", version = 1, target = fpD, purpose = "call" });
        var waitingCD = await sourceCD.ReadType("waiting"); await controlD.ReadType("offer");
        await using var targetCD = await Peer.Connect(port, hub.Fingerprint, certD);
        await targetCD.Send(new { type = "attach", version = 1, id = waitingCD.GetProperty("id").GetString() });
        await sourceCD.ReadType("ready"); await targetCD.ReadType("ready");
        await using var busy = await Peer.Connect(port, hub.Fingerprint, certA);
        await busy.Send(new { type = "open", version = 1, target = fpC, purpose = "call" });
        Assert((await busy.ReadType("error")).GetProperty("code").GetString() == "busy", "A device entered two relay sessions.");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
        await Task.WhenAll(sourceAB.Stream.WriteAsync(new byte[] { 1, 2, 3 }, timeout.Token).AsTask(), sourceCD.Stream.WriteAsync(new byte[] { 4, 5, 6 }, timeout.Token).AsTask());
        var ab = new byte[3]; var cd = new byte[3];
        await Task.WhenAll(targetAB.Stream.ReadExactlyAsync(ab, timeout.Token).AsTask(), targetCD.Stream.ReadExactlyAsync(cd, timeout.Token).AsTask());
        Assert(ab.SequenceEqual(new byte[] { 1, 2, 3 }) && cd.SequenceEqual(new byte[] { 4, 5, 6 }), "Independent tunnel bytes crossed.");
    });
    await Task.Delay(150);
    await Check("Concurrent reconnects receive registered before directories or offers", async () =>
    {
        for (var iteration = 0; iteration < 12; iteration++)
        {
            var broadcasts = Task.Run(() => { for (var n = 0; n < 4; n++) hub.Approve(fpD); });
            var reconnectA = Register(certA);
            var reconnectC = Register(certC);
            await Task.WhenAll(reconnectA, reconnectC);
            var nextA = await reconnectA; var nextC = await reconnectC;
            var firstA = await nextA.ReadMessage(); var firstC = await nextC.ReadMessage();
            Assert(firstA.GetProperty("type").GetString() == "registered" && firstC.GetProperty("type").GetString() == "registered", "A directory/offer overtook registration.");
            await nextA.ReadType("directory"); await nextC.ReadType("directory");
            if (controlA is not null) await controlA.DisposeAsync();
            if (controlC is not null) await controlC.DisposeAsync();
            controlA = nextA; controlC = nextC;
            await broadcasts;
        }
    });
    await Check("Immediate approval cannot send pending after registered", async () =>
    {
        using var certificate = Peer.CreateCertificate("Immediate approval");
        var fingerprint = Peer.Fingerprint(certificate);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var approval = Task.Run(async () =>
        {
            while (!hub.Devices.Any(d => d.Fingerprint == fingerprint)) await Task.Delay(1, timeout.Token);
            hub.Approve(fingerprint);
        });
        await using var peer = await Register(certificate, Token());
        var first = await peer.ReadMessage();
        Assert(first.GetProperty("type").GetString() == "pending", "New enrollment did not start pending.");
        var second = await peer.ReadMessage();
        Assert(second.GetProperty("type").GetString() == "registered", "Approval response out of order.");
        var third = await peer.ReadMessage();
        Assert(third.GetProperty("type").GetString() == "directory", "Pending was sent after registered.");
        await approval;
    });
    await Check("Opaque tunnel carries end-to-end mutual TLS and byte payload", async () =>
    {
        await using var source = await Peer.Connect(port, hub.Fingerprint, certA);
        await source.Send(new { type = "open", version = 1, target = fpB, purpose = "call" });
        var waiting = await source.ReadType("waiting");
        await controlB!.ReadType("offer");
        await using var target = await Peer.Connect(port, hub.Fingerprint, certB);
        await target.Send(new { type = "attach", version = 1, id = waiting.GetProperty("id").GetString() });
        var sourceReady = await source.ReadType("ready");
        var targetReady = await target.ReadType("ready");
        Assert(sourceReady.GetProperty("peer").GetString() == fpB && targetReady.GetProperty("peer").GetString() == fpA, "Ready identities mismatch.");
        using var innerA = new SslStream(source.Stream, true, (_, cert, _, _) => cert is not null && Peer.Fingerprint(cert) == fpB);
        using var innerB = new SslStream(target.Stream, true, (_, cert, _, _) => cert is not null && Peer.Fingerprint(cert) == fpA);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        await Task.WhenAll(innerA.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = "relay-peer", ClientCertificates = new X509CertificateCollection { certA }, EnabledSslProtocols = SslProtocols.Tls12, AllowTlsResume = false }, timeout.Token),
            innerB.AuthenticateAsServerAsync(new SslServerAuthenticationOptions { ServerCertificate = certB, ClientCertificateRequired = true, EnabledSslProtocols = SslProtocols.Tls12, AllowTlsResume = false }, timeout.Token));
        Assert(innerA.IsMutuallyAuthenticated && innerB.IsMutuallyAuthenticated, "Inner TLS missing client auth.");
        var payload = RandomNumberGenerator.GetBytes(128 * 1024);
        var received = new byte[payload.Length];
        await Task.WhenAll(innerA.WriteAsync(payload, timeout.Token).AsTask(), innerB.ReadExactlyAsync(received, timeout.Token).AsTask());
        Assert(payload.SequenceEqual(received), "Opaque bytes changed during relay.");
        var reverse = Encoding.UTF8.GetBytes("reverse encrypted reply");
        await innerB.WriteAsync(reverse, timeout.Token);
        var reply = new byte[reverse.Length]; await innerA.ReadExactlyAsync(reply, timeout.Token);
        Assert(reverse.SequenceEqual(reply), "Reverse tunnel failed.");
        hub.Revoke(fpB);
        bool closed;
        try { closed = await innerA.ReadAsync(new byte[1], timeout.Token) == 0; }
        catch (IOException) { closed = true; }
        Assert(closed, "Revocation left active encrypted tunnel open.");
    });
    await Check("Revocation notifies control and prevents reconnect", async () =>
    {
        var error = await controlB!.ReadType("error");
        Assert(error.GetProperty("code").GetString() == "revoked", "Missing revocation notice.");
        await using var denied = await Register(certB);
        Assert((await denied.ReadType("error")).GetProperty("code").GetString() == "revoked", "Revoked device reconnected.");
    });
    await Check("Alias and stable hub identity survive restart", async () =>
    {
        hub.SetAlias(fpA, "Warehouse radio");
        var fingerprint = hub.Fingerprint;
        await hub.StopAsync();
        await using var restored = new HubEngine(path, true);
        Assert(restored.Fingerprint == fingerprint, "Hub identity changed.");
        Assert(restored.Devices.Single(d => d.Fingerprint == fpA).Alias == "Warehouse radio", "Alias lost.");
        Assert(restored.Devices.Single(d => d.Fingerprint == fpB).Revoked, "Revocation lost.");
    });
    await Check("Production options reject loopback, wildcards, public IPs, and broad ranges", async () =>
    {
        await using var strict = new HubEngine(Path.Combine(path, "strict"));
        foreach (var address in new[] { "0.0.0.0", "127.0.0.1", "8.8.8.8" })
        {
            strict.Options.ListenAddresses = [address]; strict.Options.AllowedCidrs = ["192.168.1.0/24"];
            try { strict.SaveOptions(); } catch (ArgumentException) { continue; }
            throw new InvalidOperationException("Invalid listener accepted: " + address);
        }
        strict.Options.ListenAddresses = ["192.168.1.10"]; strict.Options.AllowedCidrs = ["0.0.0.0/0"];
        try { strict.SaveOptions(); } catch (ArgumentException) { return; }
        throw new InvalidOperationException("Public source range accepted.");
    });
    await Check("A second engine cannot own the same data directory", async () =>
    {
        await hub.StartAsync();
        await using var other = new HubEngine(path, true);
        try { await other.StartAsync(); } catch (IOException) { return; }
        throw new InvalidOperationException("Concurrent engine ownership accepted.");
    });
    await Check("Stopped/stale manager cannot overwrite another owner's state", async () =>
    {
        await using var stale = new HubEngine(path, true);
        bool rejected = false;
        try { stale.SetAlias(fpA, "Stale alias"); } catch (IOException) { rejected = true; }
        Assert(rejected, "Stopped manager wrote while another owner was active.");
        hub.SetAlias(fpA, "Current owner alias");
        await hub.StopAsync();
        rejected = false;
        try { stale.SaveOptions(); } catch (IOException) { rejected = true; }
        Assert(rejected, "A stale manager overwrote newer state after ownership released.");
        await using var inspect = new HubEngine(path, true);
        Assert(inspect.Devices.Single(d => d.Fingerprint == fpA).Alias == "Current owner alias", "Current owner state was lost.");
        await hub.StartAsync();
    });
    await Check("Malformed frame is rejected without affecting receiver", async () =>
    {
        await using var malformed = await Peer.Connect(port, hub.Fingerprint, certC);
        await malformed.Stream.WriteAsync(new byte[] { 0x7f, 0xff, 0xff, 0xff });
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
        bool closed;
        try { closed = await malformed.Stream.ReadAsync(new byte[1], timeout.Token) == 0; } catch (IOException) { closed = true; }
        Assert(closed && hub.IsRunning, "Malformed input left connection active or stopped hub.");
    });
    await Check("Source-address rate limiter is hard bounded without evicting active limits", () =>
    {
        var accepted = 0;
        for (var i = 0; i < 4096; i++)
            if (hub.AllowConnectionAttemptForTests($"10.200.{i / 256}.{i % 256}")) accepted++;
        Assert(hub.AttemptBucketCountForTests == 1024 && accepted <= 1024, "Source buckets exceeded hard cap.");
        var existingAccepted = 0;
        for (var i = 0; i < 200; i++) if (hub.AllowConnectionAttemptForTests("10.200.0.0")) existingAccepted++;
        Assert(existingAccepted == 119, "Cycling sources evicted/reset an existing source limit.");
        Assert(!hub.AllowConnectionAttemptForTests("10.250.0.1") && hub.AttemptBucketCountForTests == 1024, "New sources bypassed full limiter.");
        return Task.CompletedTask;
    });
    await DispatchChecks.Run(path, Check, Assert);
}
finally
{
    if (controlA is not null) await controlA.DisposeAsync();
    if (controlB is not null) await controlB.DisposeAsync();
    if (controlC is not null) await controlC.DisposeAsync();
    if (controlD is not null) await controlD.DisposeAsync();
    await hub.StopAsync();
}
Console.WriteLine($"\n{passed} passed; {failures.Count} failed. Test state: {path}");
certA.Dispose(); certB.Dispose(); certC.Dispose(); certD.Dispose();
await hub.DisposeAsync();
Peer.DeleteKeys();
foreach (var generatedDirectory in new[] { path, Path.Combine(path, "strict"), Path.Combine(path, "dispatch") })
{
    var keyName = "RelayHub-" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(generatedDirectory).ToUpperInvariant())))[..32];
    if (CngKey.Exists(keyName)) { using var key = CngKey.Open(keyName); key.Delete(); }
}
return failures.Count == 0 ? 0 : 1;

internal sealed class Peer(TcpClient client, SslStream stream) : IAsyncDisposable
{
    private static readonly List<string> keyNames = [];
    public SslStream Stream { get; } = stream;
    public static async Task<Peer> Connect(int port, string pinnedFingerprint, X509Certificate2 certificate, int receiveBuffer = 0)
    {
        var client = new TcpClient { NoDelay = true };
        if (receiveBuffer > 0) client.ReceiveBufferSize = receiveBuffer;
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
            await client.ConnectAsync(IPAddress.Loopback, port, timeout.Token);
            var stream = new SslStream(client.GetStream(), false, (_, cert, _, _) => cert is not null && Fingerprint(cert) == pinnedFingerprint);
            await stream.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
            {
                TargetHost = "relay-hub", ClientCertificates = new X509CertificateCollection { certificate },
                EnabledSslProtocols = SslProtocols.Tls12, AllowRenegotiation = false, AllowTlsResume = false,
                LocalCertificateSelectionCallback = (_, _, _, _, _) => certificate,
            }, timeout.Token);
            return new Peer(client, stream);
        }
        catch { client.Dispose(); throw; }
    }
    public Task Send(object message) => HubWire.WriteAsync(Stream, message);
    public async Task<JsonElement> ReadMessage()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        while (true)
        {
            var message = await HubWire.ReadAsync(Stream, timeout.Token);
            if (message.GetProperty("type").GetString() == "ping") { await Send(new { type = "pong" }); continue; }
            return message;
        }
    }
    public async Task<JsonElement> ReadType(string expected)
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        while (true)
        {
            var message = await HubWire.ReadAsync(Stream, timeout.Token);
            var type = message.GetProperty("type").GetString();
            if (type == "ping") { await Send(new { type = "pong" }); continue; }
            if (type == expected) return message;
            if (type == "error") throw new IOException("Unexpected hub error: " + message);
        }
    }
    public static string Fingerprint(X509Certificate cert) => Convert.ToHexString(SHA256.HashData(cert.GetRawCertData()));
    public static X509Certificate2 CreateCertificate(string name)
    {
        var keyName = "RelayHubTestPeer-" + Guid.NewGuid().ToString("N");
        var parameters = new CngKeyCreationParameters { Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider, ExportPolicy = CngExportPolicies.None, KeyUsage = CngKeyUsages.Signing };
        parameters.Parameters.Add(new CngProperty("Length", BitConverter.GetBytes(2048), CngPropertyOptions.None));
        using var key = CngKey.Create(CngAlgorithm.Rsa, keyName, parameters);
        keyNames.Add(keyName);
        using var rsa = new RSACng(key);
        var request = new CertificateRequest("CN=Relay test " + name, rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1"), new("1.3.6.1.5.5.7.3.2") }, false));
        return request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(2));
    }
    public static void DeleteKeys()
    {
        foreach (var name in keyNames) if (CngKey.Exists(name)) { using var key = CngKey.Open(name); key.Delete(); }
    }
    public ValueTask DisposeAsync() { client.Dispose(); Stream.Dispose(); return ValueTask.CompletedTask; }
}
