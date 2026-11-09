using System.Text.Json;

namespace Relay.Hub.Core;

public sealed partial class HubEngine
{
    private readonly List<EmergencySnapshot> emergencyHistory = [];

    public IReadOnlyList<EmergencySnapshot> Emergencies
    {
        get { lock (gate) return emergencyHistory.OrderBy(e => e.Resolved).ThenByDescending(e => e.CreatedAt).ToArray(); }
    }

    public string RaiseEmergency(string message)
    {
        EmergencySnapshot alert;
        lock (gate)
        {
            if (running is null) throw new InvalidOperationException("Start the hub before raising an emergency alert.");
            alert = AddEmergencyLocked(Guid.NewGuid().ToString("N"), Fingerprint, "Relay dispatch", message);
            LogLocked($"Desktop emergency alert raised ({alert.Id[..8]}).");
        }
        _ = BroadcastEmergencyAsync(alert.Id, false);
        Notify();
        return alert.Id;
    }

    public void ResolveEmergency(string id)
    {
        lock (gate)
        {
            if (running is null) throw new InvalidOperationException("Start the hub before resolving an emergency alert.");
            var index = emergencyHistory.FindIndex(e => e.Id == id);
            if (index < 0) throw new ArgumentException("Unknown emergency alert.");
            if (emergencyHistory[index].Resolved) return;
            emergencyHistory[index] = emergencyHistory[index] with { Resolved = true };
            SaveStateLocked(); LogLocked($"Emergency alert resolved ({id[..Math.Min(8, id.Length)]}).");
        }
        _ = BroadcastEmergencyAsync(id, true);
        Notify();
    }

    private EmergencySnapshot AddEmergencyLocked(string id, string fingerprint, string name, string message)
    {
        if (emergencyHistory.Count >= 64)
        {
            var oldestResolved = emergencyHistory.Select((e, i) => (Alert: e, Index: i)).Where(x => x.Alert.Resolved).OrderBy(x => x.Alert.CreatedAt).FirstOrDefault();
            if (oldestResolved.Alert is null) throw new InvalidOperationException("There are 64 active emergency alerts. Resolve an existing alert before adding another.");
            emergencyHistory.RemoveAt(oldestResolved.Index);
        }
        var safeMessage = SafeName(message, 240).Trim();
        var alert = new EmergencySnapshot(id, fingerprint, name, safeMessage.Length == 0 ? "Emergency assistance requested" : safeMessage, DateTimeOffset.UtcNow, false);
        emergencyHistory.Add(alert);
        SaveStateLocked();
        return alert;
    }

    private async Task ReceiveSosAsync(Control control, JsonElement message)
    {
        var id = message.Text("id", 64);
        string? error = null;
        var added = false;
        lock (gate)
        {
            if (!CurrentCapableLocked(control, "emergency-v1")) error = "unauthorized";
            else if (id.Length != 32 || !id.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f')) error = "invalid_sos";
            else
            {
                var prior = emergencyHistory.FirstOrDefault(e => e.Id == id);
                if (prior is not null)
                {
                    if (prior.Fingerprint != control.Connection.Fingerprint) error = "invalid_sos";
                    // Same authenticated source and same id is acknowledged without notifying twice.
                }
                else if (devices[control.Connection.Fingerprint].LastSosId == id) { /* Latest id remains idempotent after history eviction. */ }
                else if (DateTimeOffset.UtcNow - devices[control.Connection.Fingerprint].LastSosAt < TimeSpan.FromSeconds(30)) error = "sos_rate_limit";
                else if (emergencyHistory.Count(e => !e.Resolved) >= 64) error = "emergency_capacity";
                else
                {
                    var device = devices[control.Connection.Fingerprint];
                    device.LastSosAt = DateTimeOffset.UtcNow; device.LastSosId = id;
                    AddEmergencyLocked(id, control.Connection.Fingerprint, device.Name, message.Text("message", 240));
                    added = true;
                    LogLocked($"SOS received from {control.Connection.Fingerprint[..12]} ({id[..8]}).");
                }
            }
        }
        if (error is not null)
        {
            await control.Connection.ErrorAsync(error, error == "sos_rate_limit" ? "Wait 30 seconds before sending a new SOS." : "SOS was not accepted.");
            return;
        }
        try { await control.Connection.SendAsync(new { type = "sos_ack", id }); }
        finally
        {
            if (added) { await BroadcastEmergencyAsync(id, false); Notify(); }
        }
    }

    private async Task BroadcastEmergencyAsync(string id, bool resolved)
    {
        Control[] recipients;
        lock (gate) recipients = controls.Values.Where(c => CurrentCapableLocked(c, "emergency-v1")).ToArray();
        await Task.WhenAll(recipients.Select(c => SendEmergencyAsync(c, id, resolved)));
    }

    private async Task SendActiveEmergenciesAsync(Control control)
    {
        try
        {
            await control.Notifications.WaitAsync(control.Connection.Cancellation);
            try
            {
                string[] ids;
                lock (gate)
                {
                    if (!CurrentCapableLocked(control, "emergency-v1")) return;
                    ids = emergencyHistory.Where(e => !e.Resolved).Select(e => e.Id).ToArray();
                }
                // This authoritative list also clears alerts resolved/evicted while a phone was
                // offline. Capture and updates share Notifications, so newer events follow it.
                await control.Connection.SendAsync(new { type = "emergency_snapshot", hubId = Fingerprint, activeIds = ids });
                foreach (var id in ids) await SendEmergencyLockedAsync(control, id, false);
            }
            finally { control.Notifications.Release(); }
        }
        catch (Exception e) when (IsConnectionError(e)) { control.Connection.Close(); }
    }

    private async Task SendEmergencyAsync(Control control, string id, bool resolved)
    {
        try
        {
            // Serialize alert transitions per control. A resolution cannot overtake an in-flight
            // active alert, and reconnect/create races cannot duplicate the same notification.
            await control.Notifications.WaitAsync(control.Connection.Cancellation);
            try { await SendEmergencyLockedAsync(control, id, resolved); }
            finally { control.Notifications.Release(); }
        }
        catch (Exception e) when (IsConnectionError(e)) { control.Connection.Close(); }
    }

    private async Task SendEmergencyLockedAsync(Control control, string id, bool resolved)
    {
        EmergencySnapshot? alert;
        lock (gate)
        {
            if (!CurrentCapableLocked(control, "emergency-v1")) return;
            alert = emergencyHistory.FirstOrDefault(e => e.Id == id);
            if (alert is null || alert.Resolved != resolved) return;
            if (resolved)
            {
                control.SentResolutions.RemoveWhere(old => !emergencyHistory.Any(e => e.Id == old));
                if (!control.SentResolutions.Add(id)) return;
                control.SentEmergencies.Remove(id);
            }
            else if (!control.SentEmergencies.Add(id)) return;
        }
        if (resolved)
            await control.Connection.SendAsync(new { type = "emergency_resolved", id, at = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), by = Fingerprint });
        else
            await control.Connection.SendAsync(new
            {
                type = "emergency", id = alert.Id, from = alert.Fingerprint, name = alert.Name,
                message = alert.Message, at = alert.CreatedAt.ToUnixTimeMilliseconds(), active = true
            });
    }
}
