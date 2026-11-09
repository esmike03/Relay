using System.Text;
using System.Text.Json;
using Relay.Hub.Core;

// Explicitly invoked receive-only field check. Never opens any desktop or phone microphone.
if (args.Length < 4) throw new ArgumentException("Arguments: hub-profile-directory phone-fingerprint announcement.wav report.json");
var profile = Path.GetFullPath(args[0]);
var fingerprint = args[1];
if (fingerprint.Length != 64) throw new ArgumentException("Supply the selected phone certificate fingerprint.");
var pcm = ReadPcm(args[2]);
var emergencyIds = new List<string>();
var checks = new List<object>();
using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(100));
var token = timeout.Token;
await using var hub = new HubEngine(profile);
try
{
    await hub.StartAsync(token);
    Console.WriteLine("WAITING_FOR_PHONE: hub ready on configured private interfaces.");
    await Wait(() => hub.Devices.Any(d => d.Fingerprint == fingerprint && d.Online && d.Approved && d.Capabilities.Contains("dispatch-v1")), "selected updated phone online");
    if (hub.Devices.Any(d => d.Fingerprint != fingerprint && d.Online && d.Approved && d.Capabilities.Contains("emergency-v1")))
        throw new InvalidOperationException("Other emergency-capable phones are online. Use an isolated test hub to avoid sending test alerts to them.");
    Console.WriteLine("PHONE_ONLINE: selected approved device connected.");
    await hub.StartDispatchAsync("broadcast", [fingerprint], "Relay receive-only test");
    await Wait(() => hub.Dispatch.Participants.Any(p => p.Fingerprint == fingerprint && p.State == "Connected"), "normal announcement attachment");
    Console.WriteLine("BROADCAST_ACTIVE: sending synthetic speech; phone microphone stays off.");
    await Speak();
    checks.Add(new { mode = "broadcast", connected = true, framesSent = (pcm.Length + 639) / 640 });
    await Task.Delay(4000, token);
    var emergency = hub.RaiseEmergency("Relay test emergency — no assistance required");
    emergencyIds.Add(emergency);
    await hub.StartDispatchAsync("emergency", [fingerprint], "Relay test emergency — no assistance required");
    await Wait(() => hub.Dispatch.Mode == "emergency" && hub.Dispatch.Participants.Any(p => p.State == "Connected"), "priority announcement attachment");
    Console.WriteLine("EMERGENCY_ACTIVE: priority announcement connected; no microphone requested.");
    await Speak();
    checks.Add(new { mode = "emergency", connected = true, framesSent = (pcm.Length + 639) / 640 });
    await Task.Delay(4000, token);
    hub.EndDispatch("Receive-only field test completed");
    foreach (var id in emergencyIds) hub.ResolveEmergency(id);
    Console.WriteLine("EMERGENCY_RESOLVED: test alert cleared.");
    await Task.Delay(3000, token);
    await File.WriteAllTextAsync(args[3], JsonSerializer.Serialize(new
    {
        success = true, utc = DateTimeOffset.UtcNow, phone = fingerprint[..12],
        microphoneRequested = false, checks,
        note = "Connection and transmission confirmed; Android logs/UI establish receiver behavior. Audibility requires a listener."
    }, new JsonSerializerOptions { WriteIndented = true }), token);
    Console.WriteLine("PASS: Real-phone announcement and emergency priority transmission completed.");
}
finally
{
    hub.EndDispatch("Field test stopped");
    foreach (var id in emergencyIds) if (hub.Emergencies.Any(e => e.Id == id && !e.Resolved)) hub.ResolveEmergency(id);
    await hub.StopAsync();
}

async Task Wait(Func<bool> condition, string label)
{
    var deadline = DateTime.UtcNow.AddSeconds(35);
    while (!condition())
    {
        if (DateTime.UtcNow > deadline) throw new TimeoutException("Timed out: " + label);
        await Task.Delay(100, token);
    }
}
async Task Speak()
{
    if (!hub.SetDispatchTalking(true)) throw new InvalidOperationException("Desktop floor not granted.");
    for (int offset = 0; offset < pcm.Length; offset += 640)
    {
        var recipient = hub.Dispatch.Participants.Single(p => p.Fingerprint == fingerprint);
        if (recipient.State != "Connected") throw new IOException("Phone left during audio: " + recipient.State + " / " + recipient.Reason);
        var frame = new byte[640]; Array.Copy(pcm, offset, frame, 0, Math.Min(640, pcm.Length - offset));
        hub.SendDispatchAudio(frame); await Task.Delay(20, token);
    }
    if (!hub.Dispatch.Active) throw new IOException("Phone disconnected before the announcement finished.");
    hub.SetDispatchTalking(false);
}
static byte[] ReadPcm(string file)
{
    using var reader = new BinaryReader(File.OpenRead(file));
    if (Encoding.ASCII.GetString(reader.ReadBytes(4)) != "RIFF") throw new IOException("Expected WAV file.");
    reader.ReadInt32();
    if (Encoding.ASCII.GetString(reader.ReadBytes(4)) != "WAVE") throw new IOException("Expected WAV data.");
    bool validFormat = false;
    while (reader.BaseStream.Position + 8 <= reader.BaseStream.Length)
    {
        var chunk = Encoding.ASCII.GetString(reader.ReadBytes(4)); var size = reader.ReadInt32();
        if (size < 0 || size > reader.BaseStream.Length - reader.BaseStream.Position) throw new IOException("Invalid WAV chunk.");
        var start = reader.BaseStream.Position;
        if (chunk == "fmt ")
        {
            var format = reader.ReadInt16(); var channels = reader.ReadInt16(); var sampleRate = reader.ReadInt32();
            reader.ReadInt32(); reader.ReadInt16(); var bits = reader.ReadInt16();
            validFormat = format == 1 && channels == 1 && sampleRate == 16000 && bits == 16;
        }
        else if (chunk == "data")
        {
            if (!validFormat) throw new IOException("WAV must be PCM16 mono 16 kHz.");
            return reader.ReadBytes(size);
        }
        reader.BaseStream.Position = start + size + (size % 2);
    }
    throw new IOException("WAV has no data chunk.");
}
