using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using Relay.Hub.Core;

namespace Relay.Desktop;

public sealed partial class MainViewModel
{
    private IDesktopAudio audio = null!;
    private Dispatcher uiDispatcher = null!;
    private bool dispatchInitialized;
    private int dispatchRefreshQueued;
    private string participantsStamp = "";
    private string emergenciesStamp = "";
    private readonly HashSet<string> seenEmergencies = new(StringComparer.Ordinal);
    private volatile bool talkPressed;
    private bool transmitting;
    private CancellationTokenSource? talkCancellation;
    private readonly SemaphoreSlim captureOperations = new(1, 1);
    private long talkLease;
    private int selectedInput = -1;
    private int selectedOutput = -1;
    private string emergencyMessage = "Emergency assistance required.";
    private string audioStatus = "Microphone off. Hold TALK only when you are ready to speak.";

    public event Action<EmergencyRow>? EmergencyReceived;
    public ObservableCollection<AudioDeviceChoice> InputDevices { get; } = new();
    public ObservableCollection<AudioDeviceChoice> OutputDevices { get; } = new();
    public ObservableCollection<DispatchParticipantRow> DispatchParticipants { get; } = new();
    public ObservableCollection<EmergencyRow> EmergencyAlerts { get; } = new();
    public bool IsDispatch => Page == "Dispatch";
    public bool DispatchActive => engine.Dispatch.Active;
    public string DispatchTitle => !DispatchActive ? "Ready when you are" : engine.Dispatch.Mode switch
    {
        "call" => "Device call", "broadcast" => "Talk to all", _ => "Emergency broadcast"
    };
    public string DispatchModeLabel => engine.Dispatch.Mode switch
    {
        "call" => "TWO-WAY PUSH TO TALK", "broadcast" => "ONE-WAY ANNOUNCEMENT", "emergency" => "PRIORITY ANNOUNCEMENT", _ => "DESKTOP DISPATCH"
    };
    public string DispatchHint => !DispatchActive
        ? "Choose a device for a two-way call, or send an announcement to available devices."
        : engine.Dispatch.Mode == "call"
            ? "The phone must answer. Hold TALK to speak; release to listen."
            : "Available devices receive your announcement. Their microphones remain off.";
    public int ConnectedRecipients => DispatchParticipants.Count(participant => participant.State == "Connected");
    public int PendingRecipients => DispatchParticipants.Count(participant => participant.State == "Invited");
    public int SkippedRecipients => DispatchParticipants.Count(participant => participant.State != "Connected" && participant.State != "Invited");
    public bool HasDispatchParticipants => DispatchParticipants.Count > 0;
    public bool NoDispatchParticipants => DispatchParticipants.Count == 0;
    public bool IsTransmitting { get => transmitting; private set { if (Set(ref transmitting, value)) { Notify(nameof(TalkLabel)); Notify(nameof(TalkBackground)); Notify(nameof(TalkForeground)); } } }
    public bool IsTalkPressed => talkPressed;
    public bool CanTalk => dispatchInitialized && DispatchActive && ConnectedRecipients > 0 && InputDevices.Count > 0 && !IsBusy && !engine.Dispatch.RemoteTalking;
    public bool CanChangeAudio => !DispatchActive && !IsBusy;
    public string TalkLabel => IsTransmitting ? "TRANSMITTING" : engine.Dispatch.RemoteTalking ? "LISTENING" : "HOLD TO TALK";
    public string TalkBackground => IsTransmitting ? "#C3ECDD" : "#E8DFF8";
    public string TalkForeground => IsTransmitting ? "#164B3A" : "#3D2D58";
    public string AudioStatus { get => audioStatus; private set => Set(ref audioStatus, value); }
    public string AudioAvailability => InputDevices.Count == 0
        ? "No microphone found. Connect a microphone and refresh the audio devices. You can still send an emergency text alert."
        : OutputDevices.Count == 0 ? "No playback device found. Connect a speaker or headset before starting a two-way call."
        : "A headset helps keep incoming speech clear. Windows microphone privacy settings apply.";
    public int SelectedInput { get => selectedInput; set { if (Set(ref selectedInput, value) && DispatchActive) ReleaseTalk(); } }
    public int SelectedOutput { get => selectedOutput; set { if (Set(ref selectedOutput, value) && dispatchInitialized) audio.SetPlaybackDevice(value); } }
    public string EmergencyMessage { get => emergencyMessage; set => Set(ref emergencyMessage, value); }
    public bool HasActiveEmergencies => EmergencyAlerts.Any(alert => !alert.Resolved);
    public int ActiveEmergencyCount => EmergencyAlerts.Count(alert => !alert.Resolved);
    public string EmergencyBannerTitle => ActiveEmergencyCount == 1 ? "1 active emergency alert" : $"{ActiveEmergencyCount} active emergency alerts";
    public string EmergencyBannerMessage => EmergencyAlerts.FirstOrDefault(alert => !alert.Resolved)?.Summary ?? "";
    public IEnumerable<EmergencyRow> ActiveEmergencies => EmergencyAlerts.Where(alert => !alert.Resolved);
    public string DeliveryNote => "Connected means the device joined. It does not confirm the message was heard. Offline, busy, or older devices may be skipped.";
    public DelegateCommand CallSelectedCommand { get; private set; } = null!;
    public DelegateCommand TalkToAllCommand { get; private set; } = null!;
    public DelegateCommand EmergencyBroadcastCommand { get; private set; } = null!;
    public DelegateCommand SendEmergencyAlertCommand { get; private set; } = null!;
    public DelegateCommand EndDispatchCommand { get; private set; } = null!;
    public DelegateCommand ResolveEmergencyCommand { get; private set; } = null!;
    public DelegateCommand RefreshAudioCommand { get; private set; } = null!;

    private void InitializeDispatch(IDesktopAudio adapter)
    {
        audio = adapter;
        uiDispatcher = Application.Current?.Dispatcher ?? Dispatcher.CurrentDispatcher;
        audio.Captured += OnAudioCaptured;
        audio.Failed += OnAudioFailed;
        engine.DispatchAudioReceived += OnDispatchAudioReceived;
        CallSelectedCommand = new DelegateCommand(_ => _ = RunAsync(() => StartDispatchAsync("call")), () => IsRunning && !IsBusy && !DispatchActive && SelectedDevice is { Approved: true, Revoked: false, Online: true });
        TalkToAllCommand = new DelegateCommand(_ => _ = RunAsync(() => StartDispatchAsync("broadcast")), () => IsRunning && !IsBusy && !DispatchActive);
        EmergencyBroadcastCommand = new DelegateCommand(_ => ConfirmEmergencyBroadcast(), () => IsRunning && !IsBusy);
        SendEmergencyAlertCommand = new DelegateCommand(_ => SendEmergencyAlert(), () => IsRunning && !IsBusy);
        EndDispatchCommand = new DelegateCommand(_ => EndDispatch(), () => DispatchActive && !IsBusy);
        ResolveEmergencyCommand = new DelegateCommand(parameter => ResolveEmergency(parameter?.ToString()), () => IsRunning);
        RefreshAudioCommand = new DelegateCommand(_ => RefreshAudioDevices(), () => CanChangeAudio);
        dispatchInitialized = true;
        RefreshAudioDevices();
    }

    private void QueueDispatchRefresh()
    {
        if (!dispatchInitialized || disposed || Interlocked.Exchange(ref dispatchRefreshQueued, 1) != 0) return;
        uiDispatcher.BeginInvoke(() =>
        {
            Interlocked.Exchange(ref dispatchRefreshQueued, 0);
            if (!disposed) RefreshDispatch();
        }, DispatcherPriority.Normal);
    }

    private void RefreshDispatch()
    {
        if (!dispatchInitialized || disposed) return;
        var dispatch = engine.Dispatch;
        if ((!dispatch.Active || !dispatch.LocalTalking || dispatch.RemoteTalking) && talkPressed) ReleaseTalk();
        string nextStamp = string.Join("|", dispatch.Participants.Select(participant => $"{participant.Fingerprint}:{participant.Name}:{participant.State}:{participant.Reason}"));
        if (nextStamp != participantsStamp)
        {
            participantsStamp = nextStamp;
            DispatchParticipants.Clear();
            foreach (var participant in dispatch.Participants) DispatchParticipants.Add(new(participant));
        }
        var emergencies = engine.Emergencies.OrderByDescending(alert => alert.CreatedAt).ToArray();
        string nextEmergencies = string.Join("|", emergencies.Select(alert => $"{alert.Id}:{alert.Resolved}:{alert.Message}"));
        if (nextEmergencies != emergenciesStamp)
        {
            emergenciesStamp = nextEmergencies;
            EmergencyAlerts.Clear();
            foreach (var alert in emergencies)
            {
                var row = new EmergencyRow(alert);
                EmergencyAlerts.Add(row);
                if (!alert.Resolved && seenEmergencies.Add(alert.Id)) EmergencyReceived?.Invoke(row);
            }
            Notify(nameof(HasActiveEmergencies)); Notify(nameof(ActiveEmergencyCount)); Notify(nameof(EmergencyBannerTitle)); Notify(nameof(EmergencyBannerMessage)); Notify(nameof(ActiveEmergencies));
        }
        Notify(nameof(DispatchActive)); Notify(nameof(DispatchTitle)); Notify(nameof(DispatchModeLabel)); Notify(nameof(DispatchHint));
        Notify(nameof(ConnectedRecipients)); Notify(nameof(PendingRecipients)); Notify(nameof(SkippedRecipients)); Notify(nameof(HasDispatchParticipants)); Notify(nameof(NoDispatchParticipants));
        Notify(nameof(CanTalk)); Notify(nameof(CanChangeAudio)); Notify(nameof(TalkLabel)); Notify(nameof(TalkBackground)); Notify(nameof(TalkForeground));
        RefreshCommands();
    }

    private async Task StartDispatchAsync(string mode)
    {
        ReleaseTalk();
        await audio.StopPlaybackAsync();
        audio.SetPlaybackDevice(SelectedOutput);
        IEnumerable<string>? targets = mode == "call" && SelectedDevice != null ? new[] { SelectedDevice.Fingerprint } : null;
        await engine.StartDispatchAsync(mode, targets, mode == "emergency" ? EmergencyMessage : "");
        Page = "Dispatch";
        AudioStatus = "Microphone off. Hold TALK after a device connects.";
        MarkDirty(); RefreshDispatch();
        ShowNotice(mode == "call" ? "Call invited. Waiting for the phone to answer." : "Announcement invited. Review each device’s connection status before speaking.");
    }

    private void ConfirmEmergencyBroadcast()
    {
        ReleaseTalk();
        if (MessageBox.Show("Send an emergency alert and start a priority announcement? This can interrupt calls on enrolled devices. Your microphone stays off until you hold TALK.", "Emergency broadcast", MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No) != MessageBoxResult.Yes) return;
        _ = RunAsync(async () =>
        {
            engine.EndDispatch("Emergency broadcast");
            engine.RaiseEmergency(CleanEmergencyMessage());
            await StartDispatchAsync("emergency");
            ShowNotice("Emergency alert sent. Hold TALK to send voice to connected recipients; review skipped devices.");
        });
    }

    private string CleanEmergencyMessage() => string.IsNullOrWhiteSpace(EmergencyMessage) ? "Emergency assistance required." : EmergencyMessage.Trim();

    private void SendEmergencyAlert()
    {
        try
        {
            engine.RaiseEmergency(CleanEmergencyMessage());
            ShowNotice("Emergency text alert sent to supported connected devices. Receipt does not confirm it was read.");
            Page = "Dispatch"; RefreshDispatch();
        }
        catch (Exception exception) { ShowNotice(exception.Message, true); }
    }

    private void ResolveEmergency(string? id)
    {
        if (string.IsNullOrWhiteSpace(id)) return;
        try { engine.ResolveEmergency(id); ShowNotice("Emergency marked resolved. This updates the alert; it does not end an active voice announcement."); RefreshDispatch(); }
        catch (Exception exception) { ShowNotice(exception.Message, true); }
    }

    private void EndDispatch()
    {
        ReleaseTalk();
        engine.EndDispatch();
        _ = audio.StopPlaybackAsync();
        AudioStatus = "Microphone off. Dispatch ended.";
        RefreshDispatch();
    }

    /// <summary>Called only for a held mouse/key. The hub must grant the floor before hardware capture starts.</summary>
    public async Task BeginTalkAsync()
    {
        if (talkPressed || !CanTalk) return;
        talkPressed = true; Notify(nameof(IsTalkPressed));
        long lease = Interlocked.Increment(ref talkLease);
        var cancellation = new CancellationTokenSource();
        talkCancellation = cancellation;
        var token = cancellation.Token;
        bool captureOperationOwned = false;
        try
        {
            if (!engine.SetDispatchTalking(true))
            {
                ReleaseTalk(); AudioStatus = "The other participant has the floor. Release TALK and try again when they finish."; return;
            }
            await captureOperations.WaitAsync(token);
            captureOperationOwned = true;
            if (!talkPressed || lease != Interlocked.Read(ref talkLease) || token.IsCancellationRequested || !engine.Dispatch.LocalTalking) return;
            await audio.StartCaptureAsync(SelectedInput, token);
            if (!talkPressed || lease != Interlocked.Read(ref talkLease) || token.IsCancellationRequested || !engine.Dispatch.LocalTalking)
            {
                await audio.StopCaptureAsync(); return;
            }
            IsTransmitting = true;
            AudioStatus = "Microphone on · transmitting while you hold TALK.";
        }
        catch (OperationCanceledException)
        {
            if (captureOperationOwned) await audio.StopCaptureAsync();
        }
        catch (Exception exception)
        {
            ReleaseTalk();
            AudioStatus = "Microphone unavailable. Check the selected input and Windows Settings → Privacy & security → Microphone.";
            ShowNotice(exception.Message, true);
        }
        finally
        {
            if (captureOperationOwned) captureOperations.Release();
            RefreshDispatch();
        }
    }

    public void ReleaseTalk()
    {
        if (!dispatchInitialized) return;
        talkPressed = false; Notify(nameof(IsTalkPressed));
        Interlocked.Increment(ref talkLease);
        talkCancellation?.Cancel(); talkCancellation?.Dispose(); talkCancellation = null;
        IsTransmitting = false;
        engine.SetDispatchTalking(false);
        _ = StopCaptureSafelyAsync();
        AudioStatus = "Microphone off. Release to listen; hold TALK to transmit.";
    }

    private async Task StopCaptureSafelyAsync()
    {
        try
        {
            await captureOperations.WaitAsync();
            try
            {
                // An old release must not close a newer hold that has already acquired the floor.
                if (!talkPressed) await audio.StopCaptureAsync();
            }
            finally { captureOperations.Release(); }
        }
        catch (Exception exception) { if (!disposed) _ = uiDispatcher.BeginInvoke(() => ShowNotice("Microphone could not close cleanly: " + exception.Message, true)); }
    }

    private void OnAudioCaptured(byte[] pcm)
    {
        if (disposed || !talkPressed || !engine.Dispatch.LocalTalking || pcm.Length != 640) return;
        engine.SendDispatchAudio(pcm);
    }

    private void OnDispatchAudioReceived(string fingerprint, byte[] pcm)
    {
        if (disposed) return;
        var dispatch = engine.Dispatch;
        if (!dispatch.Active || dispatch.Mode != "call" || dispatch.FloorOwner != fingerprint) return;
        audio.QueuePlayback(pcm);
    }

    private void OnAudioFailed(string message)
    {
        if (disposed) return;
        uiDispatcher.BeginInvoke(() =>
        {
            if (disposed) return;
            ReleaseTalk(); engine.EndDispatch("Desktop audio unavailable");
            _ = audio.StopPlaybackAsync();
            AudioStatus = message;
            ShowNotice(message + " Check the selected devices and Windows audio privacy settings.", true);
        });
    }

    private void RefreshAudioDevices()
    {
        audio.RefreshDevices();
        InputDevices.Clear(); OutputDevices.Clear();
        foreach (var input in audio.Inputs) InputDevices.Add(input);
        foreach (var output in audio.Outputs) OutputDevices.Add(output);
        if (!InputDevices.Any(input => input.Index == SelectedInput)) SelectedInput = InputDevices.FirstOrDefault()?.Index ?? -1;
        if (!OutputDevices.Any(output => output.Index == SelectedOutput)) SelectedOutput = OutputDevices.FirstOrDefault()?.Index ?? -1;
        Notify(nameof(AudioAvailability)); Notify(nameof(CanTalk)); RefreshCommands();
    }

    private async Task DisposeDispatchAsync()
    {
        ReleaseTalk();
        engine.EndDispatch("Desktop closed");
        engine.DispatchAudioReceived -= OnDispatchAudioReceived;
        audio.Captured -= OnAudioCaptured; audio.Failed -= OnAudioFailed;
        await captureOperations.WaitAsync();
        try { await audio.DisposeAsync(); }
        finally { captureOperations.Release(); }
        dispatchInitialized = false;
    }
}

public sealed class DispatchParticipantRow(DispatchParticipant participant)
{
    public string Name { get; } = participant.Name;
    public string Fingerprint { get; } = participant.Fingerprint;
    public string State { get; } = participant.State;
    public string Reason { get; } = participant.Reason;
    public string StateBrush => State == "Connected" ? "#26765A" : State == "Invited" ? "#795A9E" : "#8F601E";
}

public sealed class EmergencyRow(EmergencySnapshot alert)
{
    public string Id { get; } = alert.Id;
    public string Name { get; } = alert.Name;
    public string Fingerprint { get; } = alert.Fingerprint;
    public string Message { get; } = alert.Message;
    public bool Resolved { get; } = alert.Resolved;
    public string CreatedAt { get; } = alert.CreatedAt.ToLocalTime().ToString("MMM d · HH:mm:ss");
    public string Summary => Name + " · " + Message;
}
