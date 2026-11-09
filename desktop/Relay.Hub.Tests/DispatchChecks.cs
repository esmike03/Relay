using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Relay.Hub.Core;

internal static class DispatchChecks
{
    public static async Task Run(string basePath, Func<string, Func<Task>, Task> check, Action<bool, string> assert)
    {
        var path = Path.Combine(basePath, "dispatch");
        var hub = new HubEngine(path, true);
        var listener = new TcpListener(IPAddress.Loopback, 0); listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
        hub.Options.ListenAddresses = ["127.0.0.1"]; hub.Options.AllowedCidrs = ["127.0.0.0/8"]; hub.Options.Port = port;
        await hub.StartAsync();
        using var certA = Peer.CreateCertificate("Dispatch A");
        using var certB = Peer.CreateCertificate("Dispatch B");
        using var certLegacy = Peer.CreateCertificate("Legacy");
        using var certUnknown = Peer.CreateCertificate("Unknown dispatch");
        var fpA = Peer.Fingerprint(certA); var fpB = Peer.Fingerprint(certB); var fpLegacy = Peer.Fingerprint(certLegacy);
        List<Peer> peers = [];
        Peer? controlA = null, controlB = null, legacy = null, call = null;
        string hubAlert = "", sosId = "";
        string Token() => new Uri(hub.CreateEnrollmentUri("127.0.0.1")).Query.Split('&').Single(p => p.StartsWith("token=")).Split('=')[1];
        async Task<Peer> Connect(X509Certificate2 cert, int receiveBuffer = 0)
        {
            var peer = await Peer.Connect(port, hub.Fingerprint, cert, receiveBuffer); peers.Add(peer); return peer;
        }
        async Task<Peer> Register(X509Certificate2 cert, string[] capabilities, bool first = true)
        {
            var peer = await Connect(cert);
            await peer.Send(new { type = "register", version = 1, name = cert.GetNameInfo(X509NameType.SimpleName, false), appVersion = "2.1", addresses = new[] { "192.168.1.20" }, port = 52743, capabilities, token = first ? Token() : null });
            if (first) { await peer.ReadType("pending"); hub.Approve(Peer.Fingerprint(cert)); }
            var registered = await peer.ReadType("registered");
            assert(registered.GetProperty("capabilities").EnumerateArray().Any(c => c.GetString() == "dispatch-v1"), "Hub omitted dispatch capability negotiation.");
            await peer.ReadType("directory");
            return peer;
        }
        async Task<Peer> Attach(X509Certificate2 cert, string id, int receiveBuffer = 0)
        {
            var peer = await Connect(cert, receiveBuffer);
            await peer.Send(new { type = "dispatch_attach", version = 1, id });
            var ready = await peer.ReadMessage();
            assert(ready.GetProperty("type").GetString() == "dispatch_ready" && ready.GetProperty("peer").GetString() == hub.Fingerprint, "Dispatch ready missing or out of order.");
            assert((await peer.ReadMessage()).GetProperty("type").GetString() == "floor", "Floor must precede dispatch audio.");
            return peer;
        }
        async Task Wait(Func<bool> predicate)
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(4));
            while (!predicate()) await Task.Delay(10, timeout.Token);
        }
        try
        {
            await check("Dispatch capabilities negotiate without enrolling legacy clients into voice", async () =>
            {
                controlA = await Register(certA, ["dispatch-v1", "emergency-v1"]);
                controlB = await Register(certB, ["dispatch-v1", "emergency-v1"]);
                legacy = await Register(certLegacy, []);
                assert(hub.Devices.Single(d => d.Fingerprint == fpA).Capabilities.Contains("dispatch-v1") && hub.Devices.Single(d => d.Fingerprint == fpLegacy).Capabilities.Length == 0, "Device capabilities were not stored accurately.");
                await hub.StartDispatchAsync("call", [fpA]);
                var offer = await controlA.ReadType("dispatch_offer");
                assert(offer.GetProperty("from").GetString() == hub.Fingerprint && offer.GetProperty("mode").GetString() == "call", "Offer identity/mode mismatch.");
                assert(hub.Dispatch.Participants.Single().State == "Invited" && !hub.SetDispatchTalking(true), "Desktop microphone was allowed before a phone accepted.");
            });
            await check("Dispatch attach requires an approved specifically invited certificate", async () =>
            {
                await using var unknown = await Connect(certUnknown);
                await unknown.Send(new { type = "dispatch_attach", version = 1, id = hub.Dispatch.Id });
                assert((await unknown.ReadType("error")).GetProperty("code").GetString() == "unauthorized", "Unknown certificate attached.");
                await using var wrong = await Connect(certB);
                await wrong.Send(new { type = "dispatch_attach", version = 1, id = hub.Dispatch.Id });
                assert((await wrong.ReadType("error")).GetProperty("code").GetString() == "invalid_dispatch", "Uninvited approved certificate attached.");
            });
            await check("Desktop call grants a single floor and transfers PCM in both directions", async () =>
            {
                call = await Attach(certA, hub.Dispatch.Id);
                await call.Send(new { type = "talk", on = true });
                assert((await call.ReadType("floor")).GetProperty("owner").GetString() == fpA, "Phone did not receive floor grant.");
                assert(!hub.SetDispatchTalking(true), "Desktop stole an occupied floor.");
                var received = new TaskCompletionSource<(string Sender, byte[] Pcm)>(TaskCreationOptions.RunContinuationsAsynchronously);
                void Audio(string sender, byte[] pcm) => received.TrySetResult((sender, pcm));
                hub.DispatchAudioReceived += Audio;
                var frame = Enumerable.Range(0, 640).Select(n => (byte)n).ToArray();
                await call.Send(new { type = "audio", data = Convert.ToBase64String(frame) });
                var inbound = await received.Task.WaitAsync(TimeSpan.FromSeconds(3));
                hub.DispatchAudioReceived -= Audio;
                assert(inbound.Sender == fpA && inbound.Pcm.SequenceEqual(frame), "Phone PCM or authenticated sender changed.");
                await call.Send(new { type = "talk", on = false }); await call.ReadType("floor");
                assert(hub.SetDispatchTalking(true), "Desktop did not acquire released floor.");
                assert((await call.ReadType("floor")).GetProperty("owner").GetString() == hub.Fingerprint, "Desktop floor grant missing.");
                hub.SendDispatchAudio(frame);
                assert(Convert.FromBase64String((await call.ReadType("audio")).GetProperty("data").GetString()!).SequenceEqual(frame), "Desktop PCM changed.");
                await call.Send(new { type = "talk", on = true });
                assert((await call.ReadType("floor")).GetProperty("owner").GetString() == hub.Fingerprint, "Phone stole occupied desktop floor.");
                hub.EndDispatch("Call test complete");
                await call.ReadType("end");
                assert(!hub.Dispatch.Active, "Call did not end.");
            });
            await check("Broadcast fans out audio and excludes legacy clients with a skip reason", async () =>
            {
                await hub.StartDispatchAsync("broadcast");
                var skipped = hub.Dispatch.Participants.Single(p => p.Fingerprint == fpLegacy);
                assert(skipped.State == "Skipped" && skipped.Reason.Contains("support"), "Legacy client not visibly skipped.");
                var offerA = await controlA!.ReadType("dispatch_offer"); await controlB!.ReadType("dispatch_offer");
                await using var a = await Attach(certA, offerA.GetProperty("id").GetString()!);
                await using var b = await Attach(certB, offerA.GetProperty("id").GetString()!);
                assert(hub.SetDispatchTalking(true), "Broadcast floor denied.");
                await a.ReadType("floor"); await b.ReadType("floor");
                var frame = new byte[640]; frame[0] = 42; hub.SendDispatchAudio(frame);
                assert(Convert.FromBase64String((await a.ReadType("audio")).GetProperty("data").GetString()!)[0] == 42, "Broadcast recipient A missed audio.");
                assert(Convert.FromBase64String((await b.ReadType("audio")).GetProperty("data").GetString()!)[0] == 42, "Broadcast recipient B missed audio.");
                // An audience member cannot capture/transmit or interfere with another recipient.
                await b.Send(new { type = "audio", data = Convert.ToBase64String(frame) });
                await Wait(() => hub.Dispatch.Participants.Single(p => p.Fingerprint == fpB).State == "Disconnected");
                frame[0] = 87; hub.SendDispatchAudio(frame);
                assert(Convert.FromBase64String((await a.ReadType("audio")).GetProperty("data").GetString()!)[0] == 87 && hub.Dispatch.Active, "Rejected audience audio interrupted healthy recipient.");
                hub.EndDispatch(); await a.ReadType("end");
            });
            await check("Phone decline/busy is surfaced without false delivery", async () =>
            {
                await hub.StartDispatchAsync("call", [fpA]);
                var offer = await controlA!.ReadType("dispatch_offer");
                await controlA.Send(new { type = "dispatch_decline", id = offer.GetProperty("id").GetString(), reason = "Busy in active call" });
                await Wait(() => !hub.Dispatch.Active);
                assert(hub.Dispatch.Participants.Single() is { State: "Declined", Reason: "Busy in active call" }, "Busy/decline outcome missing.");
                await hub.StartDispatchAsync("call", [fpLegacy]);
                assert(!hub.Dispatch.Active && hub.Dispatch.Participants.Single().State == "Skipped", "Unsupported target reported as delivered.");
            });
            await check("Phone audio without an authoritative floor is rejected", async () =>
            {
                await hub.StartDispatchAsync("call", [fpA]); await controlA!.ReadType("dispatch_offer");
                await using var a = await Attach(certA, hub.Dispatch.Id);
                await a.Send(new { type = "audio", data = Convert.ToBase64String(new byte[640]) });
                await Wait(() => !hub.Dispatch.Active);
                assert(hub.Dispatch.Participants.Single().State == "Disconnected", "Phone transmitted without owning the floor.");
            });
            await check("Dispatch phone audio flooding is bounded independently of control", async () =>
            {
                await hub.StartDispatchAsync("call", [fpA]); await controlA!.ReadType("dispatch_offer");
                await using var a = await Attach(certA, hub.Dispatch.Id);
                await a.Send(new { type = "talk", on = true }); await a.ReadType("floor");
                int delivered = 0;
                void Count(string _, byte[] __) => Interlocked.Increment(ref delivered);
                hub.DispatchAudioReceived += Count;
                try
                {
                    var audio = new { type = "audio", data = Convert.ToBase64String(new byte[640]) };
                    try { for (var i = 0; i < 400; i++) await a.Send(audio); } catch (IOException) { }
                    await Wait(() => !hub.Dispatch.Active);
                    assert(delivered < 400, "Unbounded audio flood reached desktop playback.");
                    await controlA.Send(new { type = "ping" }); await controlA.ReadType("pong");
                }
                finally { hub.DispatchAudioReceived -= Count; }
            });
            await check("A stalled broadcast recipient is dropped while healthy audio continues", async () =>
            {
                await hub.StartDispatchAsync("broadcast", [fpA, fpB]);
                await controlA!.ReadType("dispatch_offer"); await controlB!.ReadType("dispatch_offer");
                await using var a = await Attach(certA, hub.Dispatch.Id);
                await using var stalled = await Attach(certB, hub.Dispatch.Id, 1024);
                int heard = 0;
                var reader = Task.Run(async () =>
                {
                    try
                    {
                        while (true)
                        {
                            var frame = await a.ReadMessage(); var type = frame.GetProperty("type").GetString();
                            if (type == "audio") Interlocked.Increment(ref heard);
                            if (type == "end") return;
                        }
                    }
                    catch (IOException) { }
                });
                try
                {
                    assert(hub.SetDispatchTalking(true), "Stalled-recipient broadcast floor denied.");
                    for (var i = 0; i < 400 && hub.Dispatch.Participants.Single(p => p.Fingerprint == fpB).State == "Connected"; i++)
                    {
                        hub.SendDispatchAudio(new byte[640]); await Task.Delay(20);
                    }
                    assert(hub.Dispatch.Participants.Single(p => p.Fingerprint == fpB).State == "Slow client", "Stalled recipient was not bounded/dropped.");
                    var before = Volatile.Read(ref heard);
                    for (var i = 0; i < 10; i++) { hub.SendDispatchAudio(new byte[640]); await Task.Delay(20); }
                    await Wait(() => Volatile.Read(ref heard) > before);
                    assert(hub.Dispatch.Active && hub.Dispatch.Participants.Single(p => p.Fingerprint == fpA).State == "Connected", "Stalled recipient interrupted healthy broadcast.");
                }
                finally { hub.EndDispatch(); await reader; }
            });
            await check("Peer-call busy devices are skipped and emergency dispatch preempts the relay", async () =>
            {
                await using var source = await Connect(certA);
                await source.Send(new { type = "open", version = 1, target = fpB, purpose = "call" });
                var waiting = await source.ReadType("waiting"); await controlB!.ReadType("offer");
                await using var target = await Connect(certB);
                await target.Send(new { type = "attach", version = 1, id = waiting.GetProperty("id").GetString() });
                await source.ReadType("ready"); await target.ReadType("ready");
                await hub.StartDispatchAsync("call", [fpA]);
                assert(!hub.Dispatch.Active && hub.Dispatch.Participants.Single().Reason.Contains("busy"), "Busy peer-call target was invited normally.");
                hubAlert = hub.RaiseEmergency("Emergency drill");
                await hub.StartDispatchAsync("emergency");
                await controlA!.ReadType("dispatch_offer"); await controlB.ReadType("dispatch_offer");
                await using var a = await Attach(certA, hub.Dispatch.Id); await using var b = await Attach(certB, hub.Dispatch.Id);
                assert(hub.SetDispatchTalking(true), "Emergency voice floor denied."); await a.ReadType("floor"); await b.ReadType("floor");
                hub.SendDispatchAudio(new byte[640]); await a.ReadType("audio"); await b.ReadType("audio");
                hub.EndDispatch(); await a.ReadType("end"); await b.ReadType("end");
            });
            await check("Hub emergency alerts reach capable controls and resolve explicitly", async () =>
            {
                // ReadType(dispatch_offer) above intentionally consumed the first emergency alert.
                var second = hub.RaiseEmergency("Second drill");
                assert((await controlA!.ReadType("emergency")).GetProperty("id").GetString() == second, "Capable source missed emergency alert.");
                assert((await controlB!.ReadType("emergency")).GetProperty("from").GetString() == hub.Fingerprint, "Hub alert identity wrong.");
                hub.ResolveEmergency(second);
                assert((await controlA.ReadType("emergency_resolved")).GetProperty("by").GetString() == hub.Fingerprint, "Resolution identity wrong.");
                await controlB.ReadType("emergency_resolved");
                assert(hub.Emergencies.Single(e => e.Id == second).Resolved, "Resolution not stored.");
            });
            await check("Phone SOS derives source from TLS and fans out without starting microphone", async () =>
            {
                sosId = Guid.NewGuid().ToString("N");
                await controlA!.Send(new { type = "sos", id = sosId, message = "Need help", from = fpLegacy });
                assert((await controlA.ReadType("sos_ack")).GetProperty("id").GetString() == sosId, "SOS not acknowledged.");
                var source = await controlA.ReadType("emergency"); var remote = await controlB!.ReadType("emergency");
                assert(source.GetProperty("from").GetString() == fpA && remote.GetProperty("id").GetString() == sosId, "SOS source spoofed or not fanned out.");
                assert(!hub.Dispatch.Active && !hub.Dispatch.LocalTalking && hub.Emergencies.Single(e => e.Id == sosId).Fingerprint == fpA, "SOS started voice or lost identity.");
            });
            await check("SOS retries are idempotent and new requests are rate limited", async () =>
            {
                await controlA!.Send(new { type = "sos", id = sosId, message = "retry" }); await controlA.ReadType("sos_ack");
                await controlA.Send(new { type = "ping" });
                assert((await controlA.ReadMessage()).GetProperty("type").GetString() == "pong", "SOS retry notified twice.");
                await controlA.Send(new { type = "sos", id = Guid.NewGuid().ToString("N"), message = "Too soon" });
                assert((await controlA.ReadType("error")).GetProperty("code").GetString() == "sos_rate_limit", "SOS flooding allowed.");
                await controlA.Send(new { type = "ping" }); await controlA.ReadType("pong");
            });
            await check("Unprivileged resolution, legacy SOS, and cross-device SOS ids are rejected", async () =>
            {
                await controlA!.Send(new { type = "emergency_resolved", id = sosId });
                assert((await controlA.ReadType("error")).GetProperty("code").GetString() == "unauthorized" && !hub.Emergencies.Single(e => e.Id == sosId).Resolved, "Phone resolved emergency.");
                await controlB!.Send(new { type = "sos", id = sosId, message = "Spoof" });
                assert((await controlB.ReadType("error")).GetProperty("code").GetString() == "invalid_sos", "Different source reused SOS identity.");
                await legacy!.Send(new { type = "sos", id = Guid.NewGuid().ToString("N"), message = "Unsupported" });
                assert((await legacy.ReadType("error")).GetProperty("code").GetString() == "unauthorized", "Legacy client sent SOS without capability.");
                await using var unknown = await Connect(certUnknown);
                await unknown.Send(new { type = "sos", version = 1, id = Guid.NewGuid().ToString("N"), message = "Unauthenticated enrollment" });
                assert((await unknown.ReadType("error")).GetProperty("code").GetString() == "invalid_request", "Unenrolled certificate raised SOS.");
            });
            await check("Current capability downgrade prevents new dispatch and alert frames", async () =>
            {
                legacy = await Register(certLegacy, ["dispatch-v1", "emergency-v1"], false);
                // Consume active replay for this upgraded control before downgrading again.
                foreach (var alert in hub.Emergencies.Where(e => !e.Resolved)) await legacy.ReadType("emergency");
                legacy = await Register(certLegacy, [], false);
                var id = hub.RaiseEmergency("Legacy exclusion drill");
                await legacy.Send(new { type = "ping" });
                while (true)
                {
                    var frame = await legacy.ReadMessage(); var type = frame.GetProperty("type").GetString();
                    assert(type is not ("emergency" or "emergency_resolved" or "dispatch_offer"), "Downgraded client received unsupported extension frame.");
                    if (type == "pong") break;
                }
                hub.ResolveEmergency(id);
                assert(hub.Devices.Single(d => d.Fingerprint == fpLegacy).Capabilities.Length == 0, "Stale persisted capabilities survived downgrade.");
            });
            await check("Revocation terminates desktop dispatch immediately", async () =>
            {
                await hub.StartDispatchAsync("call", [fpB]); await controlB!.ReadType("dispatch_offer");
                await using var b = await Attach(certB, hub.Dispatch.Id);
                hub.Revoke(fpB); await Wait(() => !hub.Dispatch.Active);
                assert(hub.Dispatch.Participants.Single().State == "Revoked", "Revoked recipient remained active.");
                await using var denied = await Connect(certB);
                await denied.Send(new { type = "dispatch_attach", version = 1, id = hub.Dispatch.Id });
                assert((await denied.ReadType("error")).GetProperty("code").GetString() == "unauthorized", "Revoked dispatch certificate reattached.");
            });
            await check("Active emergency alerts persist and replay after registered on reconnect", async () =>
            {
                foreach (var peer in peers) await peer.DisposeAsync(); peers.Clear();
                await hub.DisposeAsync();
                hub = new HubEngine(path, true); await hub.StartAsync();
                assert(hub.Emergencies.Any(e => e.Id == sosId && !e.Resolved) && hub.Emergencies.Any(e => e.Id == hubAlert && !e.Resolved), "Active emergencies lost on restart.");
                controlA = await Register(certA, [], false);
                assert(hub.Devices.Single(d => d.Fingerprint == fpA).Capabilities.Length == 0, "Registration capability replacement failed.");
                controlA = await Register(certA, ["dispatch-v1", "emergency-v1"], false);
                var replayed = new HashSet<string>();
                foreach (var alert in hub.Emergencies.Where(e => !e.Resolved)) replayed.Add((await controlA.ReadType("emergency")).GetProperty("id").GetString()!);
                assert(replayed.Contains(sosId) && replayed.Contains(hubAlert), "Reconnect missed active alerts.");
            });
            await check("Emergency history is bounded without silently dropping active alerts", async () =>
            {
                foreach (var peer in peers) await peer.DisposeAsync(); peers.Clear();
                await Wait(() => hub.Devices.All(d => !d.Online));
                while (hub.Emergencies.Count(e => !e.Resolved) < 64) hub.RaiseEmergency("Capacity drill");
                bool refused = false;
                try { hub.RaiseEmergency("Overflow"); } catch (InvalidOperationException) { refused = true; }
                assert(refused && hub.Emergencies.Count == 64 && hub.Emergencies.All(e => !e.Resolved), "Active alerts were silently lost or storage exceeded limit.");
                hub.ResolveEmergency(hub.Emergencies[0].Id); hub.RaiseEmergency("Replace resolved record");
                assert(hub.Emergencies.Count == 64 && hub.Emergencies.All(e => !e.Resolved), "Resolved history did not make room safely.");
            });
            await check("Reconnect emergency snapshot clears resolved and evicted offline alerts", async () =>
            {
                var staleId = hub.Emergencies[0].Id;
                hub.ResolveEmergency(staleId);
                hub.RaiseEmergency("Evict resolved offline alert");
                assert(!hub.Emergencies.Any(e => e.Id == staleId), "Offline test did not evict resolved history.");
                controlA = await Register(certA, ["dispatch-v1", "emergency-v1"], false);
                var snapshot = await controlA.ReadMessage();
                assert(snapshot.GetProperty("type").GetString() == "emergency_snapshot" && snapshot.GetProperty("hubId").GetString() == hub.Fingerprint, "Authoritative emergency snapshot missing or misidentified.");
                var ids = snapshot.GetProperty("activeIds").EnumerateArray().Select(v => v.GetString()!).ToHashSet();
                assert(ids.Count == 64 && !ids.Contains(staleId) && ids.SetEquals(hub.Emergencies.Where(e => !e.Resolved).Select(e => e.Id)), "Snapshot retained resolved/evicted offline alert.");
                for (var i = 0; i < ids.Count; i++) await controlA.ReadType("emergency");
            });
            await check("Latest SOS retry stays idempotent after bounded history eviction", async () =>
            {
                // Resolve/evict any retained SOS if it was not already evicted by capacity drills.
                var prior = hub.Emergencies.FirstOrDefault(e => e.Id == sosId);
                if (prior is not null) { hub.ResolveEmergency(sosId); hub.RaiseEmergency("Evict resolved SOS"); }
                await controlA!.Send(new { type = "sos", id = sosId, message = "Retry after reconnect" });
                await controlA.ReadType("sos_ack");
                await controlA.Send(new { type = "ping" });
                assert((await controlA.ReadMessage()).GetProperty("type").GetString() == "pong" && !hub.Emergencies.Any(e => e.Id == sosId), "Evicted SOS retry retriggered an emergency.");
            });
        }
        finally
        {
            foreach (var peer in peers) await peer.DisposeAsync();
            await hub.DisposeAsync();
        }
    }
}
