using System.Collections.Concurrent;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;

namespace Relay.Hub.Core;

public sealed partial class HubEngine : IAsyncDisposable
{
    private readonly object gate = new();
    private readonly string directory;
    private readonly X509Certificate2 certificate;
    private readonly bool allowLoopback;
    private readonly Dictionary<string, DeviceRecord> devices = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Control> controls = new(StringComparer.Ordinal);
    private readonly Dictionary<string, EnrollmentToken> tokens = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Tunnel> tunnels = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Queue<DateTimeOffset>> attempts = new();
    private readonly Queue<string> logs = new();
    private readonly ConcurrentDictionary<long, Connection> connections = new();
    private readonly ConcurrentDictionary<long, Task> handlers = new();
    private readonly SemaphoreSlim handshakes = new(16);
    private readonly List<TcpListener> listeners = [];
    private readonly List<Task> acceptors = [];
    private CancellationTokenSource? running;
    private FileStream? processLock;
    private long connectionId;
    private Cidr[] activeRanges = [];
    private bool disposed;
    private string stateRevision = "";
    private DateTimeOffset lastAttemptPrune;

    public HubEngine(string dataDirectory) : this(dataDirectory, false) { }
    internal HubEngine(string dataDirectory, bool allowLoopbackForTests)
    {
        directory = Path.GetFullPath(dataDirectory);
        allowLoopback = allowLoopbackForTests;
        Directory.CreateDirectory(directory);
        using (new FileStream(Path.Combine(directory, ".identity.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None))
            certificate = HubIdentity.Load(directory);
        Fingerprint = HubIdentity.Fingerprint(certificate);
        Options = HubOptions.Detect();
        LoadState();
    }

    public HubOptions Options { get; }
    public string Fingerprint { get; }
    public bool IsRunning { get { lock (gate) return running is not null; } }
    public event Action? Changed;
    public IReadOnlyList<DeviceSnapshot> Devices
    {
        get
        {
            lock (gate) return devices.Values.OrderBy(d => d.Alias.Length == 0 ? d.Name : d.Alias)
                .Select(d => new DeviceSnapshot(d.Fingerprint, d.Name, d.Alias, d.AppVersion, [.. d.Addresses],
                    controls.ContainsKey(d.Fingerprint), d.Approved, d.Revoked,
                    controls.TryGetValue(d.Fingerprint, out var c) ? c.Connection.LastRead : d.LastSeen,
                    d.Revoked ? "Revoked" : !d.Approved ? "Pending approval" : controls.ContainsKey(d.Fingerprint) ? "Online" : "Offline") { Capabilities = [.. d.Capabilities] })
                .ToArray();
        }
    }
    public IReadOnlyList<string> Logs { get { lock (gate) return logs.Reverse().ToArray(); } }

    public Task StartAsync(CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        lock (gate)
        {
            if (running is not null) return Task.CompletedTask;
            var validated = Options.Validate(allowLoopback);
            try
            {
                processLock = AcquireProcessLock();
                activeRanges = validated.Ranges;
                var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                running = cts;
                foreach (var address in validated.Addresses)
                {
                    var listener = new TcpListener(address, Options.Port);
                    listener.Start(64);
                    listeners.Add(listener);
                    acceptors.Add(AcceptAsync(listener, cts.Token));
                }
                SaveStateLocked();
                LogLocked($"Hub listening on {string.Join(", ", validated.Addresses.Select(a => a + ":" + Options.Port))}.");
            }
            catch
            {
                foreach (var listener in listeners) listener.Stop();
                listeners.Clear();
                running?.Cancel(); running?.Dispose(); running = null;
                processLock?.Dispose(); processLock = null;
                throw;
            }
        }
        Notify();
        return Task.CompletedTask;
    }

    public async Task StopAsync()
    {
        EndDispatch("Hub stopped");
        Task[] tasks;
        lock (gate)
        {
            if (running is null) return;
            running.Cancel();
            foreach (var listener in listeners) listener.Stop();
            listeners.Clear();
            foreach (var connection in connections.Values) connection.Close();
            foreach (var tunnel in tunnels.Values) tunnel.Close();
            tasks = acceptors.Concat(handlers.Values).ToArray();
            acceptors.Clear();
        }
        try { await Task.WhenAll(tasks).WaitAsync(TimeSpan.FromSeconds(8)); }
        catch (Exception e) when (e is OperationCanceledException or IOException or SocketException or TimeoutException) { }
        lock (gate)
        {
            foreach (var device in devices.Values.Where(d => controls.ContainsKey(d.Fingerprint))) device.LastSeen = DateTimeOffset.UtcNow;
            controls.Clear(); tunnels.Clear(); tokens.Clear(); attempts.Clear();
            SaveStateLocked();
            running?.Dispose(); running = null;
            processLock?.Dispose(); processLock = null;
            LogLocked("Hub stopped. Direct peer calls remain independent of the hub.");
        }
        Notify();
    }

    public void SaveOptions()
    {
        Options.Validate(allowLoopback);
        lock (gate)
        {
            if (running is not null) throw new InvalidOperationException("Stop the hub before changing network settings.");
            SaveStateLocked();
        }
        Notify();
    }

    public string CreateEnrollmentUri(string host)
    {
        var address = Cidr.ParseAddress(host);
        lock (gate)
        {
            if (running is null) throw new InvalidOperationException("Start the hub before creating an enrollment code.");
            if (!listeners.Any(l => ((IPEndPoint)l.LocalEndpoint).Address.Equals(address)))
                throw new ArgumentException("Choose one of the running hub's listening addresses.");
            PruneTokensLocked();
            if (tokens.Count >= 64) throw new InvalidOperationException("Too many active enrollment codes. Wait for older codes to expire.");
            var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
            tokens[token] = new EnrollmentToken(DateTimeOffset.UtcNow.AddMinutes(10));
            LogLocked("Created a single-use enrollment code; expires in 10 minutes.");
            Notify();
            return $"relay://hub?v=1&host={address}&port={Options.Port}&fp={Fingerprint}&token={token}";
        }
    }

    public void Approve(string fingerprint)
    {
        Control? control;
        lock (gate)
        {
            var device = GetDeviceLocked(fingerprint);
            if (device.Revoked) throw new InvalidOperationException("This device must enroll again with a new code before approval.");
            device.Approved = true;
            controls.TryGetValue(fingerprint, out control);
            SaveStateLocked(); LogLocked($"Approved Relay device {fingerprint[..12]}.");
        }
        if (control is not null) _ = CompleteApprovalAsync(control);
        else _ = BroadcastDirectoryAsync();
        Notify();
    }

    public void Revoke(string fingerprint) => RemoveAccess(fingerprint, "revoked");
    public void Reject(string fingerprint) => RemoveAccess(fingerprint, "rejected");
    public void SetAlias(string fingerprint, string alias)
    {
        lock (gate)
        {
            GetDeviceLocked(fingerprint).Alias = SafeName(alias, 64);
            SaveStateLocked();
        }
        Notify();
    }

    private void RemoveAccess(string fingerprint, string code)
    {
        Connection[] close;
        Control? control;
        lock (gate)
        {
            var device = GetDeviceLocked(fingerprint);
            device.Approved = false; device.Revoked = true; device.LastSeen = DateTimeOffset.UtcNow;
            controls.TryGetValue(fingerprint, out control);
            close = connections.Values.Where(c => c.Fingerprint == fingerprint && c != control?.Connection).ToArray();
            foreach (var tunnel in tunnels.Values.Where(t => t.Source == fingerprint || t.Target == fingerprint).ToArray()) tunnel.Close();
            DropDispatchDeviceLocked(fingerprint, "Revoked", "Hub access revoked");
            SaveStateLocked(); LogLocked($"{(code == "rejected" ? "Rejected" : "Revoked")} hub access for {fingerprint[..12]}.");
        }
        foreach (var connection in close) connection.Close();
        if (control is not null) _ = RejectControlAsync(control, code);
        _ = BroadcastDirectoryAsync(); Notify();
    }

    private async Task RejectControlAsync(Control control, string code)
    {
        try { await control.Connection.SendAsync(new { type = "error", code, message = "Desktop manager revoked or rejected hub access. Scan a new enrollment code to request access again." }); }
        catch (Exception e) when (IsConnectionError(e)) { }
        finally { control.Connection.Close(); }
    }

    private async Task AcceptAsync(TcpListener listener, CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                var client = await listener.AcceptTcpClientAsync(cancellationToken);
                var remote = ((IPEndPoint)client.Client.RemoteEndPoint!).Address;
                bool allowed;
                lock (gate) allowed = activeRanges.Any(r => r.Contains(remote)) && connections.Count < Options.MaxConnections && AllowAttemptLocked(remote.ToString());
                if (!allowed) { client.Dispose(); continue; }
                client.NoDelay = true;
                var id = Interlocked.Increment(ref connectionId);
                var connection = new Connection(id, client, cancellationToken);
                connections[id] = connection;
                var task = HandleAsync(connection, cancellationToken);
                handlers[id] = task;
                _ = task.ContinueWith(_ => { handlers.TryRemove(id, out var ignored); }, CancellationToken.None, TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
            }
        }
        catch (Exception e) when (e is OperationCanceledException or ObjectDisposedException or SocketException) { }
    }

    private bool AllowAttemptLocked(string address)
    {
        var now = DateTimeOffset.UtcNow;
        if (!attempts.TryGetValue(address, out var queue))
        {
            if (attempts.Count >= 1024 && now - lastAttemptPrune >= TimeSpan.FromSeconds(1))
            {
                lastAttemptPrune = now;
                foreach (var key in attempts.Where(k => k.Value.Count == 0 || now - k.Value.Last() > TimeSpan.FromMinutes(1)).Select(k => k.Key).ToArray())
                    attempts.Remove(key);
            }
            // Never evict an active bucket: cycling source addresses cannot reset a limit.
            if (attempts.Count >= 1024) return false;
            attempts[address] = queue = new();
        }
        while (queue.TryPeek(out var old) && now - old > TimeSpan.FromMinutes(1)) queue.Dequeue();
        if (queue.Count >= 120) return false;
        queue.Enqueue(now);
        return true;
    }

    private async Task HandleAsync(Connection connection, CancellationToken cancellationToken)
    {
        try
        {
            if (!await handshakes.WaitAsync(0, cancellationToken)) return;
            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                timeout.CancelAfter(TimeSpan.FromSeconds(8));
                connection.Stream = new SslStream(connection.Client.GetStream(), false, (_, cert, _, _) => HubIdentity.ValidPeer(cert));
                await connection.Stream.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                {
                    ServerCertificate = certificate,
                    ClientCertificateRequired = true,
                    EnabledSslProtocols = SslProtocols.Tls12,
                    AllowRenegotiation = false,
                    AllowTlsResume = false,
                    CertificateRevocationCheckMode = X509RevocationMode.NoCheck,
                }, timeout.Token);
                if (!connection.Stream.IsMutuallyAuthenticated || connection.Stream.RemoteCertificate is null)
                    throw new AuthenticationException("A device certificate is required.");
                connection.Fingerprint = HubIdentity.Fingerprint(connection.Stream.RemoteCertificate);
            }
            finally { handshakes.Release(); }
            JsonElement first;
            using (var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken))
            {
                timeout.CancelAfter(TimeSpan.FromSeconds(8));
                first = await HubWire.ReadAsync(connection.Stream, timeout.Token);
            }
            if (first.Integer("version") != 1) { await connection.ErrorAsync("unsupported_version", "Unsupported hub protocol version."); return; }
            switch (first.Text("type"))
            {
                case "register": await RegisterAsync(connection, first, cancellationToken); break;
                case "open": await OpenAsync(connection, first, cancellationToken); break;
                case "attach": await AttachAsync(connection, first, cancellationToken); break;
                case "dispatch_attach": await AttachDispatchAsync(connection, first, cancellationToken); break;
                default: await connection.ErrorAsync("invalid_request", "Expected register, open, or attach."); break;
            }
        }
        catch (Exception e) when (IsConnectionError(e))
        {
            if (!cancellationToken.IsCancellationRequested && e is not EndOfStreamException)
                Log($"Connection {connection.Fingerprint[..Math.Min(12, connection.Fingerprint.Length)]} closed: {e.GetType().Name}.");
        }
        finally
        {
            connection.Close(); connections.TryRemove(connection.Id, out _);
        }
    }

    private async Task RegisterAsync(Connection connection, JsonElement message, CancellationToken cancellationToken)
    {
        var fp = connection.Fingerprint;
        string? error = null;
        Control? old = null;
        var control = new Control(connection) { Capabilities = ReadCapabilities(message) };
        bool approved = false;
        try
        {
            // Acquire before publishing the control: an immediate operator approval cannot
            // overtake this connection's initial pending/registered response.
            await control.Initialization.WaitAsync(connection.Cancellation);
            try
            {
                lock (gate)
                {
                    devices.TryGetValue(fp, out var record);
                    if (record is null || record.Revoked)
                    {
                        var tokenValue = message.Text("token", 128);
                        if (!tokens.TryGetValue(tokenValue, out var token)) error = record?.Revoked == true ? "revoked" : "invalid_token";
                        else if (token.Expires <= DateTimeOffset.UtcNow) { error = "token_expired"; tokens.Remove(tokenValue); }
                        else if (token.Fingerprint is not null) error = "invalid_token";
                        else if (devices.Count >= Options.MaxDevices && record is null) error = "capacity";
                        else
                        {
                            token.Fingerprint = fp;
                            record = new DeviceRecord { Fingerprint = fp };
                            devices[fp] = record;
                            LogLocked($"Enrollment requested by device {fp[..12]}; awaiting approval.");
                        }
                    }
                    if (error is null && record is not null)
                    {
                        record.Name = SafeName(message.Text("name", 64), 32);
                        if (record.Name.Length == 0) record.Name = "Relay device";
                        record.AppVersion = SafeName(message.Text("appVersion", 32), 32);
                        record.Capabilities = [.. control.Capabilities];
                        record.Port = message.Integer("port", 52743);
                        if (record.Port is < 1024 or > 65535) record.Port = 52743;
                        record.Addresses = ReadAddresses(message);
                        record.LastSeen = DateTimeOffset.UtcNow;
                        approved = record.Approved;
                        controls.TryGetValue(fp, out old);
                        controls[fp] = control;
                        SaveStateLocked();
                    }
                }
                if (error is not null) { await connection.ErrorAsync(error, "Enrollment was not accepted. Create and scan a fresh desktop enrollment code."); return; }
                old?.Connection.Close();
                if (approved) await SendRegisteredAsync(control);
                else await connection.SendAsync(new { type = "pending", message = "Approve this device in Relay Desktop Manager." });
            }
            finally { control.Initialization.Release(); }
            if (approved) { await BroadcastDirectoryAsync(); await SendActiveEmergenciesAsync(control); }
            Notify();
            var heartbeat = HeartbeatAsync(connection, cancellationToken);
            var budget = new Queue<DateTimeOffset>();
            try
            {
                while (!cancellationToken.IsCancellationRequested)
                {
                    using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                    timeout.CancelAfter(TimeSpan.FromSeconds(20));
                    var incoming = await HubWire.ReadAsync(connection.Stream!, timeout.Token);
                    connection.LastRead = DateTimeOffset.UtcNow;
                    var now = DateTimeOffset.UtcNow;
                    while (budget.TryPeek(out var oldTime) && now - oldTime >= TimeSpan.FromSeconds(1)) budget.Dequeue();
                    if (budget.Count >= 20) { await connection.ErrorAsync("rate_limit", "Too many control messages."); break; }
                    budget.Enqueue(now);
                    switch (incoming.Text("type"))
                    {
                        case "ping": await connection.SendAsync(new { type = "pong" }); break;
                        case "pong": break;
                        case "directory": await SendDirectoryAsync(control); break;
                        case "dispatch_decline": DeclineDispatch(control, incoming); break;
                        case "sos": await ReceiveSosAsync(control, incoming); break;
                        case "emergency_resolve":
                        case "emergency_resolved": await connection.ErrorAsync("unauthorized", "Only the desktop manager can resolve emergencies."); break;
                        default: await connection.ErrorAsync("invalid_request", "Unsupported management command."); return;
                    }
                }
            }
            finally { connection.Close(); await heartbeat; }
        }
        finally
        {
            lock (gate)
            {
                if (controls.TryGetValue(fp, out var current) && current == control)
                {
                    controls.Remove(fp);
                    if (devices.TryGetValue(fp, out var record)) record.LastSeen = DateTimeOffset.UtcNow;
                    foreach (var tunnel in tunnels.Values.Where(t => t.Source == fp || t.Target == fp).ToArray()) tunnel.Close();
                    DropDispatchDeviceLocked(fp, "Disconnected", "Device control connection lost");
                    SaveStateLocked();
                }
            }
            _ = BroadcastDirectoryAsync(); Notify();
        }
    }

    private async Task HeartbeatAsync(Connection connection, CancellationToken cancellationToken)
    {
        try
        {
            while (!connection.Closed)
            {
                await Task.Delay(TimeSpan.FromSeconds(5), connection.Cancellation);
                if (DateTimeOffset.UtcNow - connection.LastRead > TimeSpan.FromSeconds(20)) break;
                await connection.SendAsync(new { type = "ping" });
            }
        }
        catch (Exception e) when (IsConnectionError(e)) { }
        finally { connection.Close(); }
    }

    private async Task CompleteApprovalAsync(Control control)
    {
        try
        {
            await control.Initialization.WaitAsync(control.Connection.Cancellation);
            bool firstRegistration;
            try { firstRegistration = !control.Ready; if (firstRegistration) await SendRegisteredAsync(control); }
            finally { control.Initialization.Release(); }
            await BroadcastDirectoryAsync();
            if (firstRegistration) await SendActiveEmergenciesAsync(control);
        }
        catch (Exception e) when (IsConnectionError(e)) { control.Connection.Close(); }
    }

    private async Task SendRegisteredAsync(Control control)
    {
        lock (gate)
            if (!AuthorizedLocked(control.Connection.Fingerprint) || !controls.TryGetValue(control.Connection.Fingerprint, out var current) || current != control) return;
        await control.Connection.SendAsync(new { type = "registered", hubId = Fingerprint, capabilities = HubCapabilities });
        lock (gate)
            if (AuthorizedLocked(control.Connection.Fingerprint) && controls.TryGetValue(control.Connection.Fingerprint, out var current) && current == control)
                control.Ready = true;
    }

    private async Task BroadcastDirectoryAsync()
    {
        Control[] recipients;
        lock (gate) recipients = controls.Values.Where(c => c.Ready && AuthorizedLocked(c.Connection.Fingerprint)).ToArray();
        await Task.WhenAll(recipients.Select(SendDirectoryAsync));
        Notify();
    }

    private async Task SendDirectoryAsync(Control control)
    {
        object payload;
        lock (gate)
        {
            if (!control.Ready || !AuthorizedLocked(control.Connection.Fingerprint) || !controls.TryGetValue(control.Connection.Fingerprint, out var current) || current != control) return;
            payload = new
            {
                type = "directory",
                devices = devices.Values.Where(d => d.Approved && !d.Revoked).Select(d => new
                {
                    fingerprint = d.Fingerprint, name = d.Name, addresses = d.Addresses,
                    port = d.Port, appVersion = d.AppVersion, online = controls.TryGetValue(d.Fingerprint, out var online) && online.Ready,
                    capabilities = d.Capabilities.Where(c => c is "dispatch-v1" or "emergency-v1").ToArray(),
                    lastSeen = (controls.TryGetValue(d.Fingerprint, out var c) ? c.Connection.LastRead : d.LastSeen).ToUnixTimeMilliseconds()
                }).ToArray()
            };
        }
        try { await control.Connection.SendAsync(payload); }
        catch (Exception e) when (IsConnectionError(e)) { control.Connection.Close(); }
    }

    private async Task OpenAsync(Connection source, JsonElement message, CancellationToken cancellationToken)
    {
        var targetFp = message.Text("target", 128);
        var purpose = message.Text("purpose");
        Control? targetControl = null;
        Tunnel? tunnel = null;
        string? error = null;
        lock (gate)
        {
            if (!AuthorizedLocked(source.Fingerprint)) error = "unauthorized";
            else if (!IsFingerprint(targetFp) || targetFp == source.Fingerprint || purpose is not ("pair" or "call")) error = "invalid_request";
            else if (!AuthorizedLocked(targetFp) || !controls.TryGetValue(targetFp, out targetControl) || !targetControl.Ready) error = "peer_offline";
            else if (!controls.TryGetValue(source.Fingerprint, out var sourceControl) || !sourceControl.Ready) error = "unauthorized";
            else if (DispatchBusyLocked(source.Fingerprint) || DispatchBusyLocked(targetFp)) error = "busy";
            else if (tunnels.Count >= Options.MaxTunnels || tunnels.Values.Any(t => t.Source == source.Fingerprint || t.Target == source.Fingerprint || t.Source == targetFp || t.Target == targetFp)) error = "busy";
            else
            {
                var id = Guid.NewGuid().ToString("N");
                tunnel = new Tunnel(id, source, targetFp, cancellationToken);
                tunnels.Add(id, tunnel);
            }
        }
        if (error is not null) { await source.ErrorAsync(error, "Peer unavailable, unauthorized, or already using a relay session."); return; }
        try
        {
            await source.SendAsync(new { type = "waiting", id = tunnel!.Id });
            string sourceName;
            lock (gate) sourceName = devices[source.Fingerprint].Name;
            await targetControl!.Connection.SendAsync(new { type = "offer", id = tunnel.Id, from = source.Fingerprint, name = sourceName, purpose });
            var target = await tunnel.Attached.Task.WaitAsync(TimeSpan.FromSeconds(15), tunnel.Cancellation);
            lock (gate)
                if (!AuthorizedLocked(tunnel.Source) || !AuthorizedLocked(tunnel.Target)) throw new IOException("Relay access revoked.");
            await source.SendAsync(new { type = "ready", id = tunnel.Id, peer = target.Fingerprint, role = "client" });
            await target.SendAsync(new { type = "ready", id = tunnel.Id, peer = source.Fingerprint, role = "server" });
            Log($"Relay session connected {source.Fingerprint[..12]} → {target.Fingerprint[..12]} ({purpose}).");
            var forward = PumpAsync(source.Stream!, target.Stream!, tunnel.Cancellation);
            var reverse = PumpAsync(target.Stream!, source.Stream!, tunnel.Cancellation);
            await Task.WhenAny(forward, reverse);
            tunnel.Close();
            try { await Task.WhenAll(forward, reverse); } catch (Exception e) when (IsConnectionError(e)) { }
        }
        catch (TimeoutException)
        {
            try { await source.ErrorAsync("timeout", "Peer did not attach to the relay within 15 seconds."); } catch (Exception e) when (IsConnectionError(e)) { }
        }
        finally
        {
            tunnel!.Close();
            tunnel.Completed.TrySetResult();
            lock (gate) tunnels.Remove(tunnel.Id);
            Log($"Relay session ended {tunnel.Id[..8]}.");
        }
    }

    private async Task AttachAsync(Connection target, JsonElement message, CancellationToken cancellationToken)
    {
        var id = message.Text("id", 64);
        Tunnel? tunnel;
        string? error = null;
        lock (gate)
        {
            if (!AuthorizedLocked(target.Fingerprint)) { tunnel = null; error = "unauthorized"; }
            else if (!tunnels.TryGetValue(id, out tunnel) || tunnel.Target != target.Fingerprint || tunnel.Closed || tunnel.TargetConnection is not null) error = "invalid_tunnel";
            else
            {
                tunnel.TargetConnection = target;
                tunnel.Attached.TrySetResult(target);
            }
        }
        if (error is not null) { await target.ErrorAsync(error, "Relay offer is expired, already used, or belongs to another device."); return; }
        await tunnel!.Completed.Task.WaitAsync(cancellationToken);
    }

    private static async Task PumpAsync(Stream source, Stream destination, CancellationToken cancellationToken)
    {
        // One fixed buffer per direction; writes supply backpressure. Inner TLS bytes remain opaque.
        var buffer = new byte[16384];
        while (true)
        {
            var count = await source.ReadAsync(buffer, cancellationToken);
            if (count == 0) return;
            await destination.WriteAsync(buffer.AsMemory(0, count), cancellationToken);
            await destination.FlushAsync(cancellationToken);
        }
    }

    private bool AuthorizedLocked(string fp) => devices.TryGetValue(fp, out var d) && d.Approved && !d.Revoked;
    private DeviceRecord GetDeviceLocked(string fp) => devices.TryGetValue(fp, out var d) ? d : throw new ArgumentException("Unknown Relay device.");
    private static bool IsFingerprint(string fp) => fp.Length == 64 && fp.All(c => c is >= '0' and <= '9' or >= 'A' and <= 'F');
    private static string SafeName(string value, int limit) => new(value.Where(c => !char.IsControl(c)).Take(limit).ToArray());
    private static string[] ReadAddresses(JsonElement message)
    {
        if (!message.TryGetProperty("addresses", out var addresses) || addresses.ValueKind != JsonValueKind.Array) return [];
        return addresses.EnumerateArray().Take(16).Where(a => a.ValueKind == JsonValueKind.String)
            .Select(a => a.GetString() ?? "").Where(a => IPAddress.TryParse(a, out var ip) && Cidr.IsPrivateAddress(ip)).Distinct().ToArray();
    }
    private static bool IsConnectionError(Exception e) => e is IOException or SocketException or AuthenticationException or OperationCanceledException or ObjectDisposedException or JsonException or InvalidOperationException;
    private void PruneTokensLocked()
    {
        foreach (var key in tokens.Where(t => t.Value.Expires <= DateTimeOffset.UtcNow).Select(t => t.Key).ToArray()) tokens.Remove(key);
    }
    private void Log(string text) { lock (gate) LogLocked(text); Notify(); }
    private void LogLocked(string text)
    {
        logs.Enqueue($"{DateTimeOffset.Now:HH:mm:ss}  {text}");
        while (logs.Count > 200) logs.Dequeue();
    }
    private void Notify() { try { Changed?.Invoke(); } catch { /* UI observers cannot interrupt networking. */ } }

    private FileStream AcquireProcessLock()
    {
        try { return new FileStream(Path.Combine(directory, ".hub.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
        catch (IOException e) { throw new IOException("Another desktop manager or headless hub is using this data directory. Stop that instance before starting this one.", e); }
    }
    private void SaveStateLocked()
    {
        using var temporaryLock = processLock is null ? AcquireProcessLock() : null;
        var path = Path.Combine(directory, "hub.json");
        if (File.Exists(path))
        {
            var current = JsonSerializer.Deserialize<PersistedState>(File.ReadAllText(path), JsonOptions)
                ?? throw new IOException("Invalid hub state file.");
            if (current.Revision != stateRevision)
                throw new IOException("Hub state changed in another process. Close and reopen this manager before making changes.");
        }
        var temporary = path + ".tmp";
        var nextRevision = Guid.NewGuid().ToString("N");
        File.WriteAllText(temporary, JsonSerializer.Serialize(new PersistedState { Revision = nextRevision, Options = Options, Devices = devices.Values.ToList(), Emergencies = emergencyHistory.ToList() }, JsonOptions));
        File.Move(temporary, path, true);
        stateRevision = nextRevision;
    }
    private void LoadState()
    {
        var path = Path.Combine(directory, "hub.json");
        if (!File.Exists(path)) return;
        var state = JsonSerializer.Deserialize<PersistedState>(File.ReadAllText(path), JsonOptions) ?? throw new IOException("Invalid hub state file.");
        stateRevision = state.Revision;
        if (state.Options is not null)
        {
            Options.ListenAddresses = state.Options.ListenAddresses;
            Options.AllowedCidrs = state.Options.AllowedCidrs;
            Options.Port = state.Options.Port;
            Options.MaxConnections = state.Options.MaxConnections;
            Options.MaxDevices = state.Options.MaxDevices;
            Options.MaxTunnels = state.Options.MaxTunnels;
        }
        foreach (var d in state.Devices.Where(d => IsFingerprint(d.Fingerprint))) devices[d.Fingerprint] = d;
        foreach (var e in state.Emergencies.TakeLast(64)) emergencyHistory.Add(e);
    }
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true, PropertyNameCaseInsensitive = true };
    internal void ExpireTokensForTests() { lock (gate) foreach (var token in tokens.Values) token.Expires = DateTimeOffset.UtcNow.AddSeconds(-1); }
    internal bool AllowConnectionAttemptForTests(string sourceAddress) { lock (gate) return AllowAttemptLocked(sourceAddress); }
    internal int AttemptBucketCountForTests { get { lock (gate) return attempts.Count; } }

    public async ValueTask DisposeAsync()
    {
        if (disposed) return;
        await StopAsync(); disposed = true; certificate.Dispose(); handshakes.Dispose();
    }

    private sealed class PersistedState { public string Revision { get; set; } = ""; public HubOptions? Options { get; set; } public List<DeviceRecord> Devices { get; set; } = []; public List<EmergencySnapshot> Emergencies { get; set; } = []; }
    private sealed class DeviceRecord
    {
        public string Fingerprint { get; set; } = "";
        public string Name { get; set; } = "Relay device";
        public string Alias { get; set; } = "";
        public string AppVersion { get; set; } = "";
        public string[] Addresses { get; set; } = [];
        public string[] Capabilities { get; set; } = [];
        public int Port { get; set; } = 52743;
        public bool Approved { get; set; }
        public bool Revoked { get; set; }
        public DateTimeOffset LastSeen { get; set; }
        public DateTimeOffset LastSosAt { get; set; }
        public string LastSosId { get; set; } = "";
    }
    private sealed class EnrollmentToken(DateTimeOffset expires)
    {
        public DateTimeOffset Expires { get; set; } = expires;
        public string? Fingerprint { get; set; }
    }
    private sealed class Control(Connection connection)
    {
        public Connection Connection { get; } = connection;
        public SemaphoreSlim Initialization { get; } = new(1);
        public bool Ready { get; set; }
        public string[] Capabilities { get; init; } = [];
        public SemaphoreSlim Notifications { get; } = new(1);
        public HashSet<string> SentEmergencies { get; } = [];
        public HashSet<string> SentResolutions { get; } = [];
    }
    private sealed class Connection(long id, TcpClient client, CancellationToken cancellationToken)
    {
        private readonly SemaphoreSlim writer = new(1);
        private readonly CancellationTokenSource cancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        private int closed;
        public long Id { get; } = id;
        public TcpClient Client { get; } = client;
        public SslStream? Stream { get; set; }
        public string Fingerprint { get; set; } = "";
        public DateTimeOffset LastRead { get; set; } = DateTimeOffset.UtcNow;
        public bool Closed => Volatile.Read(ref closed) != 0;
        public CancellationToken Cancellation => cancellation.Token;
        public async Task SendAsync(object payload, CancellationToken extraCancellation = default)
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(Cancellation, extraCancellation);
            timeout.CancelAfter(TimeSpan.FromSeconds(5));
            await writer.WaitAsync(timeout.Token);
            try { await HubWire.WriteAsync(Stream!, payload, timeout.Token); }
            finally { writer.Release(); }
        }
        public Task ErrorAsync(string code, string message) => SendAsync(new { type = "error", code, message });
        public void Close()
        {
            if (Interlocked.Exchange(ref closed, 1) != 0) return;
            cancellation.Cancel();
            try { Client.Dispose(); } catch (SocketException) { }
            try { Stream?.Dispose(); } catch (IOException) { }
        }
    }
    private sealed class Tunnel(string id, Connection source, string target, CancellationToken cancellationToken)
    {
        private readonly CancellationTokenSource cancellation = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        private int closed;
        public string Id { get; } = id;
        public string Source { get; } = source.Fingerprint;
        public string Target { get; } = target;
        public Connection? TargetConnection { get; set; }
        public TaskCompletionSource<Connection> Attached { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource Completed { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public CancellationToken Cancellation => cancellation.Token;
        public bool Closed => Volatile.Read(ref closed) != 0;
        public void Close()
        {
            if (Interlocked.Exchange(ref closed, 1) != 0) return;
            cancellation.Cancel(); source.Close(); TargetConnection?.Close(); Completed.TrySetResult();
        }
    }
}
