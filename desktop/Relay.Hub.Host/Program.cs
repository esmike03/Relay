using System.IO.Pipes;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Relay.Hub.Core;

var arguments = args.ToList();
string? Argument(string name)
{
    var index = arguments.IndexOf(name);
    return index >= 0 && index + 1 < arguments.Count ? arguments[index + 1] : null;
}
var dataPath = Path.GetFullPath(Argument("--data") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Relay", "Hub"));
var command = Argument("--command");
if (arguments.Contains("--help"))
{
    Console.WriteLine("Relay.Hub.Host [--data DIR]                          Run console / Windows service\n" +
        "Relay.Hub.Host --data DIR --configure --listen IP[,IP] --allow CIDR[,CIDR] [--port 52744]\n" +
        "Relay.Hub.Host --data DIR --command status|enroll|approve|revoke|reject|alias|stop [--host IP] [--fingerprint FP] [--alias NAME]\n" +
        "Relay.Hub.Host --data DIR --command emergencies|raise-emergency|resolve-emergency [--message TEXT] [--id ALERT_ID]\n" +
        "Enrollment/admin commands use an authenticated same-user Windows named pipe. Run with the same Windows account and data directory as the service.\n" +
        "Install as a Windows service explicitly using sc.exe after reviewing docs/DESKTOP-SETUP.md. This program does not install itself.");
    return 0;
}
try
{
    if (command is not null)
    {
        using var pipe = new NamedPipeClientStream(".", AdminPipe.Name(dataPath), PipeDirection.InOut, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        await pipe.ConnectAsync(timeout.Token);
        await HubWire.WriteAsync(pipe, new { command, host = Argument("--host"), fingerprint = Argument("--fingerprint"), alias = Argument("--alias"), message = Argument("--message"), id = Argument("--id") }, timeout.Token);
        var response = await HubWire.ReadAsync(pipe, timeout.Token);
        Console.WriteLine(JsonSerializer.Serialize(response, new JsonSerializerOptions { WriteIndented = true }));
        return response.TryGetProperty("ok", out var ok) && ok.GetBoolean() ? 0 : 1;
    }
    if (arguments.Contains("--configure"))
    {
        await using var engine = new HubEngine(dataPath);
        engine.Options.ListenAddresses = (Argument("--listen") ?? throw new ArgumentException("Supply --listen IPv4[,IPv4].")).Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        engine.Options.AllowedCidrs = (Argument("--allow") ?? throw new ArgumentException("Supply --allow CIDR[,CIDR].")).Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        if (Argument("--port") is { } port) engine.Options.Port = int.Parse(port);
        engine.SaveOptions();
        Console.WriteLine("Saved hub configuration. Hub certificate fingerprint: " + engine.Fingerprint);
        return 0;
    }
    var host = Microsoft.Extensions.Hosting.Host.CreateDefaultBuilder(args)
        .UseContentRoot(AppContext.BaseDirectory)
        .UseWindowsService(options => options.ServiceName = "RelayHub")
        .ConfigureServices(services =>
        {
            services.AddSingleton(new HubEngine(dataPath));
            services.AddHostedService<HubWorker>();
            services.AddSingleton(new AdminPipe(dataPath));
        }).Build();
    try { await host.RunAsync(); }
    finally
    {
        if (host is IAsyncDisposable asyncDisposable) await asyncDisposable.DisposeAsync();
        else host.Dispose();
    }
    return 0;
}
catch (Exception e)
{
    Console.Error.WriteLine("Relay hub: " + e.Message);
    return 1;
}

internal sealed class HubWorker(HubEngine engine, AdminPipe pipe, IHostApplicationLifetime lifetime) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        string lastLog = "";
        void Report()
        {
            var latest = engine.Logs.FirstOrDefault();
            if (latest is not null && latest != lastLog) { lastLog = latest; Console.WriteLine(latest); }
        }
        engine.Changed += Report;
        try
        {
            await engine.StartAsync(stoppingToken);
            await pipe.RunAsync(engine, lifetime, stoppingToken);
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { }
        finally { engine.Changed -= Report; await engine.StopAsync(); }
    }
}

internal sealed class AdminPipe(string directory)
{
    public static string Name(string directory) => "RelayHub-" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(directory).ToUpperInvariant())))[..32];
    public async Task RunAsync(HubEngine engine, IHostApplicationLifetime lifetime, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            await using var pipe = new NamedPipeServerStream(Name(directory), PipeDirection.InOut, 1,
                PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly, 4096, 4096);
            await pipe.WaitForConnectionAsync(cancellationToken);
            bool stop = false;
            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                timeout.CancelAfter(TimeSpan.FromSeconds(5));
                var request = await HubWire.ReadAsync(pipe, timeout.Token);
                string Text(string key) => request.TryGetProperty(key, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
                object response;
                switch (Text("command"))
                {
                    case "status": response = new
                    {
                        ok = true, running = engine.IsRunning, fingerprint = engine.Fingerprint, options = engine.Options,
                        // Bound the administrative frame even with 64 Unicode-heavy records.
                        devices = engine.Devices.Select(d => new { d.Fingerprint, d.Name, d.Alias, d.Online, d.Approved, d.Revoked, d.LastSeen, d.Status }),
                        logs = engine.Logs.Take(5)
                    }; break;
                    case "enroll": response = new { ok = true, uri = engine.CreateEnrollmentUri(Text("host")) }; break;
                    case "approve": engine.Approve(Text("fingerprint")); response = new { ok = true }; break;
                    case "revoke": engine.Revoke(Text("fingerprint")); response = new { ok = true }; break;
                    case "reject": engine.Reject(Text("fingerprint")); response = new { ok = true }; break;
                    case "alias": engine.SetAlias(Text("fingerprint"), Text("alias")); response = new { ok = true }; break;
                    case "emergencies": response = new { ok = true, activeIds = engine.Emergencies.Where(e => !e.Resolved).Select(e => e.Id), emergencies = engine.Emergencies.Take(16) }; break;
                    case "raise-emergency": response = new { ok = true, id = engine.RaiseEmergency(Text("message")) }; break;
                    case "resolve-emergency": engine.ResolveEmergency(Text("id")); response = new { ok = true }; break;
                    case "stop": response = new { ok = true }; stop = true; break;
                    default: response = new { ok = false, message = "Unknown command." }; break;
                }
                await HubWire.WriteAsync(pipe, response, timeout.Token);
            }
            catch (Exception e) when (e is IOException or OperationCanceledException or ArgumentException or InvalidOperationException or JsonException)
            {
                try { await HubWire.WriteAsync(pipe, new { ok = false, message = e.Message }, cancellationToken); }
                catch (Exception writeError) when (writeError is IOException or OperationCanceledException) { }
            }
            if (stop) lifetime.StopApplication();
        }
    }
}
