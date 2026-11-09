using System.Text.Json;
using System.Threading.Channels;

namespace Relay.Hub.Core;

public sealed partial class HubEngine
{
    private static readonly string[] HubCapabilities = ["dispatch-v1", "emergency-v1"];
    private DispatchSession? dispatch;

    public event Action<string, byte[]>? DispatchAudioReceived;
    public DispatchSnapshot Dispatch
    {
        get
        {
            lock (gate)
            {
                if (dispatch is null) return new("", "", false, false, false, [], "");
                return new(dispatch.Id, dispatch.Mode, dispatch.Active,
                    dispatch.Active && dispatch.FloorOwner == Fingerprint,
                    dispatch.Active && dispatch.FloorOwner.Length != 0 && dispatch.FloorOwner != Fingerprint,
                    dispatch.Recipients.Values.Select(p => new DispatchParticipant(p.Fingerprint, p.Name, p.State, p.Reason)).ToArray(), dispatch.FloorOwner);
            }
        }
    }

    public async Task StartDispatchAsync(string mode, IEnumerable<string>? targets = null, string message = "")
    {
        if (mode is not ("call" or "broadcast" or "emergency")) throw new ArgumentException("Dispatch mode must be call, broadcast, or emergency.");
        var requested = targets?.Take(65).Distinct(StringComparer.Ordinal).ToArray();
        if (requested?.Length > 64) throw new ArgumentException("At most 64 dispatch targets are supported.");
        if (mode == "call" && requested?.Length != 1) throw new ArgumentException("Choose exactly one device for a desktop call.");
        if (mode == "emergency") EndDispatch("Preempted by emergency dispatch");
        DispatchSession session;
        List<(DispatchRecipient Recipient, Control Control)> invitations = [];
        lock (gate)
        {
            if (running is null) throw new InvalidOperationException("Start the hub before using dispatch.");
            if (dispatch?.Active == true) throw new InvalidOperationException("End the current desktop call or broadcast first.");
            session = new DispatchSession(Guid.NewGuid().ToString("N"), mode, SafeName(message, 240), running.Token);
            dispatch = session;
            var fingerprints = requested ?? devices.Values.Where(d => d.Approved && !d.Revoked).Select(d => d.Fingerprint).ToArray();
            foreach (var fp in fingerprints)
            {
                devices.TryGetValue(fp, out var device);
                var participant = new DispatchRecipient(fp, device?.Name ?? "Unknown device");
                session.Recipients[fp] = participant;
                string? skip = null;
                if (!AuthorizedLocked(fp)) skip = "Device is not approved";
                else if (!controls.TryGetValue(fp, out var control) || !control.Ready) skip = "Device is offline";
                else if (!control.Capabilities.Contains("dispatch-v1", StringComparer.Ordinal)) skip = "Device version does not support desktop voice";
                else if (mode != "emergency" && tunnels.Values.Any(t => t.Source == fp || t.Target == fp)) skip = "Device is busy in a relayed peer call";
                if (skip is not null) { participant.State = "Skipped"; participant.Reason = skip; continue; }
                if (mode == "emergency")
                    foreach (var tunnel in tunnels.Values.Where(t => t.Source == fp || t.Target == fp).ToArray()) tunnel.Close();
                participant.Expires = DateTimeOffset.UtcNow.AddSeconds(mode == "call" ? 20 : 10);
                invitations.Add((participant, controls[fp]));
            }
            session.Active = invitations.Count != 0;
            LogLocked($"Desktop {mode} dispatch invited {invitations.Count} device(s); {session.Recipients.Count - invitations.Count} skipped.");
        }
        Notify();
        await Task.WhenAll(invitations.Select(i => SendDispatchOfferAsync(session, i.Recipient, i.Control)));
    }

    public void EndDispatch(string reason = "Ended")
    {
        lock (gate)
        {
            if (dispatch?.Active != true) return;
            var session = dispatch;
            session.Active = false; session.FloorOwner = "";
            foreach (var recipient in session.Recipients.Values.Where(IsLiveRecipient))
            {
                recipient.State = "Ended"; recipient.Reason = SafeName(reason, 120);
                if (recipient.Connection is { } connection) recipient.Ending = SendDispatchEndAsync(connection, recipient.Reason);
                recipient.Cancellation?.Cancel(); recipient.Outbound.Writer.TryComplete();
            }
            session.Cancellation.Cancel();
            LogLocked($"Desktop {session.Mode} dispatch ended.");
        }
        Notify();
    }

    public bool SetDispatchTalking(bool on)
    {
        bool granted;
        lock (gate)
        {
            if (dispatch?.Active != true) return false;
            if (on)
            {
                if (!dispatch.Recipients.Values.Any(p => p.State == "Connected")) return false;
                if (dispatch.FloorOwner.Length != 0 && dispatch.FloorOwner != Fingerprint) return false;
                granted = true;
                if (dispatch.FloorOwner == Fingerprint) return true;
                dispatch.FloorOwner = Fingerprint;
            }
            else
            {
                granted = true;
                if (dispatch.FloorOwner != Fingerprint) return true;
                dispatch.FloorOwner = "";
            }
            QueueFloorLocked(dispatch);
        }
        Notify();
        return granted;
    }

    public void SendDispatchAudio(byte[] pcm)
    {
        ArgumentNullException.ThrowIfNull(pcm);
        if (pcm.Length != 640) throw new ArgumentException("Dispatch audio must be one 640-byte mono 16 kHz PCM16 frame.");
        lock (gate)
        {
            if (dispatch?.Active != true || dispatch.FloorOwner != Fingerprint) return;
            if (!dispatch.AudioRate.Take()) { EndDispatch("Desktop audio rate exceeded"); return; }
            var payload = new { type = "audio", data = Convert.ToBase64String(pcm) };
            foreach (var recipient in dispatch.Recipients.Values.Where(p => p.State == "Connected").ToArray())
                QueueDispatchLocked(dispatch, recipient, payload);
        }
    }

    private async Task SendDispatchOfferAsync(DispatchSession session, DispatchRecipient recipient, Control control)
    {
        try
        {
            lock (gate)
                if (dispatch != session || !session.Active || recipient.State != "Invited" || !CurrentCapableLocked(control, "dispatch-v1")) return;
            await control.Connection.SendAsync(new
            {
                type = "dispatch_offer", id = session.Id, mode = session.Mode,
                name = "Relay dispatch", message = session.Message, from = Fingerprint,
            });
            _ = ExpireDispatchOfferAsync(session, recipient);
        }
        catch (Exception e) when (IsConnectionError(e)) { DropDispatch(session, recipient, "Disconnected", "Offer could not be delivered"); }
    }

    private async Task ExpireDispatchOfferAsync(DispatchSession session, DispatchRecipient recipient)
    {
        try
        {
            var delay = recipient.Expires - DateTimeOffset.UtcNow;
            if (delay > TimeSpan.Zero) await Task.Delay(delay, session.Cancellation.Token);
            lock (gate)
            {
                if (dispatch != session || recipient.State != "Invited") return;
                DropDispatchRecipientLocked(session, recipient, "Timed out", "Device did not attach before the invitation expired");
            }
            Notify();
        }
        catch (OperationCanceledException) { }
    }

    private async Task AttachDispatchAsync(Connection connection, JsonElement first, CancellationToken cancellationToken)
    {
        DispatchSession? session;
        DispatchRecipient? recipient = null;
        string? error = null;
        lock (gate)
        {
            session = dispatch;
            if (!AuthorizedLocked(connection.Fingerprint) || !controls.TryGetValue(connection.Fingerprint, out var control) || !CurrentCapableLocked(control, "dispatch-v1")) error = "unauthorized";
            else if (session?.Active != true || first.Text("id", 64) != session.Id ||
                !session.Recipients.TryGetValue(connection.Fingerprint, out recipient) || recipient.State != "Invited" || recipient.Expires <= DateTimeOffset.UtcNow) error = "invalid_dispatch";
            else
            {
                recipient.Connection = connection;
                recipient.State = "Attaching";
                recipient.Cancellation = CancellationTokenSource.CreateLinkedTokenSource(connection.Cancellation, session.Cancellation.Token);
            }
        }
        if (error is not null) { await connection.ErrorAsync(error, "No eligible desktop dispatch invitation for this device."); return; }
        Task? writer = null, heartbeat = null;
        try
        {
            connection.Client.SendBufferSize = 8192;
            await connection.SendAsync(new { type = "dispatch_ready", id = session!.Id, mode = session.Mode, peer = Fingerprint });
            lock (gate)
            {
                if (dispatch != session || !session.Active || !AuthorizedLocked(connection.Fingerprint) || recipient!.State != "Attaching") return;
                // Initial floor is queued before exposing Connected, so no audio can overtake it.
                recipient.Outbound.Writer.TryWrite(new { type = "floor", owner = session.FloorOwner });
                recipient.State = "Connected";
            }
            Notify();
            writer = WriteDispatchAsync(session, recipient!);
            heartbeat = HeartbeatDispatchAsync(session, recipient!);
            var packets = new DispatchRate(256, 100);
            var audio = new DispatchRate(150, 70);
            while (!recipient!.Cancellation!.IsCancellationRequested)
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(recipient.Cancellation.Token);
                timeout.CancelAfter(TimeSpan.FromSeconds(20));
                var message = await HubWire.ReadAsync(connection.Stream!, timeout.Token);
                connection.LastRead = DateTimeOffset.UtcNow;
                if (!packets.Take()) throw new IOException("Dispatch packet rate exceeded.");
                switch (message.Text("type"))
                {
                    case "ping": lock (gate) QueueDispatchLocked(session, recipient, new { type = "pong" }); break;
                    case "pong": break;
                    case "talk": ReceiveDispatchTalk(session, recipient, message); break;
                    case "audio":
                        if (!audio.Take()) throw new IOException("Dispatch audio rate exceeded.");
                        ReceiveDispatchAudio(session, recipient, message); break;
                    case "end": return;
                    default: throw new IOException("Unsupported dispatch message.");
                }
            }
        }
        catch (Exception e) when (IsConnectionError(e) || e is FormatException)
        {
            DropDispatch(session!, recipient!, "Disconnected", "Dispatch connection closed or sent invalid traffic");
        }
        finally
        {
            DropDispatch(session!, recipient!, "Ended", "Device ended the dispatch session");
            recipient!.Cancellation?.Cancel(); recipient.Outbound.Writer.TryComplete();
            if (recipient.Ending is { } ending) await ending;
            if (writer is not null) await writer;
            if (heartbeat is not null) await heartbeat;
        }
    }

    private void ReceiveDispatchTalk(DispatchSession session, DispatchRecipient recipient, JsonElement message)
    {
        if (!message.TryGetProperty("on", out var on) || on.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) throw new IOException("Invalid talk state.");
        lock (gate)
        {
            if (dispatch != session || !session.Active || recipient.State != "Connected") return;
            if (session.Mode != "call") throw new IOException("Phone transmission is disabled for broadcasts.");
            if (on.GetBoolean())
            {
                if (session.FloorOwner.Length == 0 || session.FloorOwner == recipient.Fingerprint) session.FloorOwner = recipient.Fingerprint;
            }
            else if (session.FloorOwner == recipient.Fingerprint) session.FloorOwner = "";
            QueueFloorLocked(session);
        }
        Notify();
    }

    private void ReceiveDispatchAudio(DispatchSession session, DispatchRecipient recipient, JsonElement message)
    {
        var encoded = message.Text("data", 1024);
        var pcm = Convert.FromBase64String(encoded);
        if (pcm.Length != 640) throw new IOException("Invalid dispatch audio frame length.");
        lock (gate)
        {
            if (dispatch != session || !session.Active || recipient.State != "Connected") return;
            if (session.Mode != "call" || session.FloorOwner != recipient.Fingerprint) throw new IOException("Phone does not own the dispatch floor.");
        }
        // The first argument is the authenticated sending device fingerprint. Subscribers enqueue
        // playback without blocking the network reader; no voice is persisted by the hub.
        try { DispatchAudioReceived?.Invoke(recipient.Fingerprint, pcm); } catch { }
    }

    private async Task WriteDispatchAsync(DispatchSession session, DispatchRecipient recipient)
    {
        try
        {
            await foreach (var payload in recipient.Outbound.Reader.ReadAllAsync(recipient.Cancellation!.Token))
                await recipient.Connection!.SendAsync(payload, recipient.Cancellation.Token);
        }
        catch (Exception e) when (IsConnectionError(e)) { DropDispatch(session, recipient, "Disconnected", "Dispatch output failed"); }
    }

    private async Task HeartbeatDispatchAsync(DispatchSession session, DispatchRecipient recipient)
    {
        try
        {
            while (!recipient.Cancellation!.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(5), recipient.Cancellation.Token);
                lock (gate)
                {
                    if (DateTimeOffset.UtcNow - recipient.Connection!.LastRead > TimeSpan.FromSeconds(20))
                        DropDispatchRecipientLocked(session, recipient, "Disconnected", "Dispatch heartbeat timed out");
                    else QueueDispatchLocked(session, recipient, new { type = "ping" });
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    private void DeclineDispatch(Control control, JsonElement message)
    {
        lock (gate)
        {
            if (!CurrentCapableLocked(control, "dispatch-v1") || dispatch?.Active != true || dispatch.Id != message.Text("id", 64)) return;
            if (!dispatch.Recipients.TryGetValue(control.Connection.Fingerprint, out var recipient) || recipient.State != "Invited") return;
            DropDispatchRecipientLocked(dispatch, recipient, "Declined", SafeName(message.Text("reason", 120), 120));
        }
        Notify();
    }

    private bool CurrentCapableLocked(Control control, string capability) => control.Ready &&
        AuthorizedLocked(control.Connection.Fingerprint) && controls.TryGetValue(control.Connection.Fingerprint, out var current) && current == control &&
        control.Capabilities.Contains(capability, StringComparer.Ordinal);
    private bool DispatchBusyLocked(string fingerprint) => dispatch?.Active == true && dispatch.Recipients.TryGetValue(fingerprint, out var participant) && IsLiveRecipient(participant);
    private static bool IsLiveRecipient(DispatchRecipient recipient) => recipient.State is "Invited" or "Attaching" or "Connected";
    private void DropDispatchDeviceLocked(string fp, string state, string reason)
    {
        if (dispatch?.Active == true && dispatch.Recipients.TryGetValue(fp, out var recipient)) DropDispatchRecipientLocked(dispatch, recipient, state, reason);
    }
    private void DropDispatch(DispatchSession session, DispatchRecipient recipient, string state, string reason)
    {
        lock (gate) DropDispatchRecipientLocked(session, recipient, state, reason);
        Notify();
    }
    private void DropDispatchRecipientLocked(DispatchSession session, DispatchRecipient recipient, string state, string reason)
    {
        if (!IsLiveRecipient(recipient)) return;
        recipient.State = state; recipient.Reason = SafeName(reason, 120);
        recipient.Cancellation?.Cancel(); recipient.Outbound.Writer.TryComplete(); recipient.Connection?.Close();
        if (session.FloorOwner == recipient.Fingerprint) { session.FloorOwner = ""; QueueFloorLocked(session); }
        if (!session.Recipients.Values.Any(IsLiveRecipient))
        {
            session.Active = false; session.FloorOwner = ""; session.Cancellation.Cancel();
        }
    }
    private void QueueFloorLocked(DispatchSession session)
    {
        var payload = new { type = "floor", owner = session.FloorOwner };
        foreach (var recipient in session.Recipients.Values.Where(p => p.State == "Connected").ToArray()) QueueDispatchLocked(session, recipient, payload);
    }
    private void QueueDispatchLocked(DispatchSession session, DispatchRecipient recipient, object payload)
    {
        if (dispatch != session || !session.Active || recipient.State != "Connected") return;
        if (!recipient.Outbound.Writer.TryWrite(payload))
        {
            DropDispatchRecipientLocked(session, recipient, "Slow client", "Device output queue filled; other recipients continue");
            Notify();
        }
    }
    private static async Task SendDispatchEndAsync(Connection connection, string reason)
    {
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(1));
            await connection.SendAsync(new { type = "end", reason }, timeout.Token);
        }
        catch (Exception e) when (IsConnectionError(e)) { }
        finally { connection.Close(); }
    }
    private static string[] ReadCapabilities(JsonElement message)
    {
        if (!message.TryGetProperty("capabilities", out var values) || values.ValueKind != JsonValueKind.Array) return [];
        return values.EnumerateArray().Take(8).Where(v => v.ValueKind == JsonValueKind.String)
            .Select(v => v.GetString() ?? "").Where(v => v.Length is > 0 and <= 32 && v.All(c => c is >= 'a' and <= 'z' or >= '0' and <= '9' or '-')).Distinct(StringComparer.Ordinal).ToArray();
    }

    private sealed class DispatchSession(string id, string mode, string message, CancellationToken cancellationToken)
    {
        public string Id { get; } = id;
        public string Mode { get; } = mode;
        public string Message { get; } = message;
        public bool Active { get; set; }
        public string FloorOwner { get; set; } = "";
        public Dictionary<string, DispatchRecipient> Recipients { get; } = new(StringComparer.Ordinal);
        public CancellationTokenSource Cancellation { get; } = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        public DispatchRate AudioRate { get; } = new(150, 70);
    }
    private sealed class DispatchRecipient(string fingerprint, string name)
    {
        public string Fingerprint { get; } = fingerprint;
        public string Name { get; } = name;
        public string State { get; set; } = "Invited";
        public string Reason { get; set; } = "";
        public DateTimeOffset Expires { get; set; }
        public Connection? Connection { get; set; }
        public CancellationTokenSource? Cancellation { get; set; }
        public Task? Ending { get; set; }
        public Channel<object> Outbound { get; } = Channel.CreateBounded<object>(new BoundedChannelOptions(64) { SingleReader = true, SingleWriter = false, FullMode = BoundedChannelFullMode.Wait });
    }
    private sealed class DispatchRate
    {
        private readonly double capacity;
        private readonly double perSecond;
        private double available;
        private long last = Environment.TickCount64;
        public DispatchRate(double capacity, double perSecond) { this.capacity = capacity; this.perSecond = perSecond; available = capacity; }
        public bool Take()
        {
            var now = Environment.TickCount64;
            available = Math.Min(capacity, available + Math.Max(0, now - last) * perSecond / 1000.0); last = now;
            if (available < 1) return false;
            available--;
            return true;
        }
    }
}
