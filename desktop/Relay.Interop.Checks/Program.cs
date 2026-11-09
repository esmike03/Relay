using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Collections.Concurrent;
using Relay.Hub.Core;

var root = Path.GetFullPath(args.Length > 0 ? args[0] : Path.Combine(AppContext.BaseDirectory, "../../../../../"));
var temporaryRoot = Path.Combine(Path.GetTempPath(), "Relay.Interop." + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(temporaryRoot);
try
{
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(70));
    var token = timeout.Token;
    var classes = Path.Combine(temporaryRoot, "classes");
    Directory.CreateDirectory(classes);
    await Execute("javac", ["-d", classes,
        Path.Combine(root, "app/src/main/java/app/relay/lan/TlsTunnel.java"),
        Path.Combine(root, "desktop/interop/RelayJavaPeer.java"),
        Path.Combine(root, "desktop/interop/RelayJavaDispatch.java")], token);
    var probe = new TcpListener(IPAddress.Loopback, 0); probe.Start();
    var port = ((IPEndPoint)probe.LocalEndpoint).Port; probe.Stop();
    await using var engine = new HubEngine(Path.Combine(temporaryRoot, "hub"), true);
    engine.Options.ListenAddresses = ["127.0.0.1"];
    engine.Options.AllowedCidrs = ["127.0.0.0/8"];
    engine.Options.Port = port;
    await engine.StartAsync(token);
    string TokenFromUri(string uri) => Uri.UnescapeDataString(uri.Split("token=")[1].Split('&')[0]);
    var enrollmentA = TokenFromUri(engine.CreateEnrollmentUri("127.0.0.1"));
    var enrollmentB = TokenFromUri(engine.CreateEnrollmentUri("127.0.0.1"));
    const string password = "RelayInteropOnly";
    string Certificate(string name)
    {
        using var rsa = RSA.Create(2048);
        var request = new CertificateRequest("CN=" + name, rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
        using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-1), DateTimeOffset.UtcNow.AddDays(1));
        var file = Path.Combine(temporaryRoot, name + ".pfx");
        File.WriteAllBytes(file, certificate.Export(X509ContentType.Pkcs12, password));
        return file;
    }
    var a = Certificate("peer-a"); var b = Certificate("peer-b");
    var run = Execute("java", ["-cp", classes, "RelayJavaPeer", "127.0.0.1", port.ToString(), engine.Fingerprint,
        enrollmentA, enrollmentB, a, b, password], token);
    while (!run.IsCompleted)
    {
        foreach (var device in engine.Devices.Where(d => !d.Approved && !d.Revoked)) engine.Approve(device.Fingerprint);
        await Task.Delay(50, token);
    }
    await run;
    await CheckDispatch(engine, classes, port, a, b, password, token);
    await engine.StopAsync();
    Console.WriteLine("PASS: Cross-runtime interoperability checks completed.");
}
finally
{
    var full = Path.GetFullPath(temporaryRoot);
    if (full.StartsWith(Path.GetFullPath(Path.GetTempPath()), StringComparison.OrdinalIgnoreCase)
        && Path.GetFileName(full).StartsWith("Relay.Interop.", StringComparison.Ordinal))
    {
        var hubDirectory = Path.Combine(full, "hub");
        var keyName = "RelayHub-" + Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(hubDirectory.ToUpperInvariant())))[..32];
        if (CngKey.Exists(keyName)) { using var key = CngKey.Open(keyName); key.Delete(); }
        Directory.Delete(full, true);
    }
}

static async Task CheckDispatch(HubEngine engine, string classes, int port, string a, string b, string password, CancellationToken token)
{
    var info = new ProcessStartInfo("java") { RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false };
    foreach (var value in new[] { "-cp", classes, "RelayJavaDispatch", "127.0.0.1", port.ToString(), engine.Fingerprint, a, b, password }) info.ArgumentList.Add(value);
    using var process = Process.Start(info) ?? throw new Exception("Cannot start Java dispatch check");
    using var killOnCancel = token.Register(() => { try { process.Kill(true); } catch (InvalidOperationException) { } });
    var signals = new ConcurrentDictionary<string, byte>();
    var errors = new ConcurrentQueue<string>();
    var stderr = process.StandardError.ReadToEndAsync(token);
    var observe = Task.Run(async () =>
    {
        while (await process.StandardOutput.ReadLineAsync(token) is { } line)
        {
            signals[line] = 0;
            if (line.StartsWith("FAIL_", StringComparison.Ordinal)) errors.Enqueue(line);
        }
    }, token);
    async Task Wait(Func<bool> condition, string label)
    {
        var limit = DateTime.UtcNow.AddSeconds(15);
        while (!condition())
        {
            if (!errors.IsEmpty) throw new Exception(string.Join("; ", errors));
            if (process.HasExited || DateTime.UtcNow > limit) throw new Exception("Java dispatch check timed out: " + label + "; signals=" + string.Join(",", signals.Keys));
            await Task.Delay(20, token);
        }
    }
    int received = 0;
    void Receive(string sender, byte[] frame)
    {
        var i = Interlocked.Increment(ref received) - 1;
        if (frame.Length != 640 || frame[0] != (byte)(i + 10)) errors.Enqueue("Invalid Java-to-hub PCM frame");
    }
    engine.DispatchAudioReceived += Receive;
    try
    {
        await Wait(() => signals.ContainsKey("DISPATCH_READY"), "registration");
        var target = engine.Devices.Single(d => d.Name == "Java dispatch A").Fingerprint;
        await engine.StartDispatchAsync("call", [target]);
        await Wait(() => engine.Dispatch.Participants.Count(p => p.State == "Connected") == 1, "call attachment");
        if (!engine.SetDispatchTalking(true)) throw new Exception("Hub did not acquire call floor");
        for (int i = 0; i < 100; i++) { engine.SendDispatchAudio(Enumerable.Repeat((byte)i, 640).ToArray()); await Task.Delay(5, token); }
        await Wait(() => signals.ContainsKey("RX_A_call"), "phone receives desktop call audio");
        engine.SetDispatchTalking(false);
        await Wait(() => Volatile.Read(ref received) == 100 && !engine.Dispatch.RemoteTalking, "desktop receives phone reply");
        engine.EndDispatch();
        await Wait(() => signals.ContainsKey("END_A_call"), "call end");
        Console.WriteLine("PASS: Java/.NET hub-to-device call, authoritative floor, 100 PCM frames in each direction");

        await engine.StartDispatchAsync("broadcast");
        await Wait(() => engine.Dispatch.Participants.Count(p => p.State == "Connected") == 2, "broadcast attachments");
        if (!engine.SetDispatchTalking(true)) throw new Exception("Hub did not acquire broadcast floor");
        for (int i = 0; i < 100; i++) { engine.SendDispatchAudio(Enumerable.Repeat((byte)i, 640).ToArray()); await Task.Delay(5, token); }
        await Wait(() => signals.ContainsKey("RX_A_broadcast") && signals.ContainsKey("RX_B_broadcast"), "both phones receive broadcast audio");
        engine.SetDispatchTalking(false); engine.EndDispatch();
        await Wait(() => signals.ContainsKey("END_A_broadcast") && signals.ContainsKey("END_B_broadcast"), "broadcast end");
        Console.WriteLine("PASS: Java/.NET Talk to all delivers 100 PCM frames to both authenticated devices");

        var hubAlert = engine.RaiseEmergency("Cross-runtime emergency test");
        await Wait(() => signals.ContainsKey("ALERT_A_" + hubAlert) && signals.ContainsKey("ALERT_B_" + hubAlert), "hub emergency fanout");
        await engine.StartDispatchAsync("emergency", message: "Cross-runtime emergency test");
        await Wait(() => engine.Dispatch.Participants.Count(p => p.State == "Connected") == 2, "emergency voice attachments");
        if (!engine.SetDispatchTalking(true)) throw new Exception("Hub did not acquire emergency floor");
        for (int i = 0; i < 100; i++) { engine.SendDispatchAudio(Enumerable.Repeat((byte)i, 640).ToArray()); await Task.Delay(5, token); }
        await Wait(() => signals.ContainsKey("RX_A_emergency") && signals.ContainsKey("RX_B_emergency"), "both phones receive emergency voice");
        engine.SetDispatchTalking(false); engine.EndDispatch();
        await Wait(() => signals.ContainsKey("END_A_emergency") && signals.ContainsKey("END_B_emergency"), "emergency voice end");
        engine.ResolveEmergency(hubAlert);
        await Wait(() => signals.ContainsKey("RESOLVED_A_" + hubAlert) && signals.ContainsKey("RESOLVED_B_" + hubAlert), "emergency resolution");
        await process.StandardInput.WriteLineAsync("sos"); await process.StandardInput.FlushAsync(token);
        await Wait(() => engine.Emergencies.Any(e => e.Fingerprint == target && e.Message == "Java SOS test"), "phone SOS reaches desktop");
        var sos = engine.Emergencies.Single(e => e.Fingerprint == target && e.Message == "Java SOS test");
        await Wait(() => signals.ContainsKey("SOS_ACK_A_" + sos.Id) && signals.ContainsKey("ALERT_A_" + sos.Id) && signals.ContainsKey("ALERT_B_" + sos.Id), "phone SOS acknowledgment and fanout");
        engine.ResolveEmergency(sos.Id);
        Console.WriteLine("PASS: Java/.NET emergency voice to both devices, phone SOS, authenticated identity, fanout, acknowledgment and resolution");
        await process.StandardInput.WriteLineAsync("quit"); await process.StandardInput.FlushAsync(token);
        await process.WaitForExitAsync(token); await observe;
        if (process.ExitCode != 0 || !errors.IsEmpty) throw new Exception("Java dispatch failed: " + await stderr + string.Join("; ", errors));
    }
    finally
    {
        engine.DispatchAudioReceived -= Receive;
        engine.EndDispatch();
        if (!process.HasExited) process.Kill(true);
    }
}

static async Task Execute(string executable, string[] arguments, CancellationToken cancellation)
{
    var info = new ProcessStartInfo(executable) { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false };
    foreach (var argument in arguments) info.ArgumentList.Add(argument);
    using var process = Process.Start(info) ?? throw new Exception("Cannot start " + executable);
    using var killOnCancel = cancellation.Register(() => { try { process.Kill(true); } catch (InvalidOperationException) { } });
    var stdout = process.StandardOutput.ReadToEndAsync(cancellation);
    var stderr = process.StandardError.ReadToEndAsync(cancellation);
    await process.WaitForExitAsync(cancellation);
    var output = await stdout; var errors = await stderr;
    if (output.Length > 0) Console.Write(output);
    if (process.ExitCode != 0) throw new Exception(executable + " failed: " + errors);
    if (errors.Length > 0) Console.Write(errors);
}
