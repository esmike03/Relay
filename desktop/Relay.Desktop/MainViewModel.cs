using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using QRCoder;
using Relay.Hub.Core;

namespace Relay.Desktop;

public sealed partial class MainViewModel : INotifyPropertyChanged, IAsyncDisposable
{
    private readonly HubEngine engine;
    private int dirty = 1;
    private bool busy;
    private string page = "Devices";
    private string search = "";
    private string filter = "All devices";
    private string deviceStamp = "";
    private DeviceRow? selectedDevice;
    private string alias = "";
    private string notice = "Choose a network, start the hub, then enroll your first device.";
    private bool noticeIsError;
    private string enrollmentUri = "";
    private DateTimeOffset enrollmentExpires;
    private BitmapImage? qrImage;
    private string advertiseAddress = "";
    private string listenAddresses = "";
    private string allowedCidrs = "";
    private string port = "52744";
    private string diagnostics = "Run a local listener check after starting the hub. Reachability from a phone still depends on the router and firewall.";
    private volatile bool disposed;
    private bool compact;
    private string logStamp = "";

    public MainViewModel(string dataDirectory, IDesktopAudio? audioAdapter = null, HubEngine? hubEngine = null)
    {
        DataDirectory = dataDirectory;
        engine = hubEngine ?? new HubEngine(dataDirectory);
        engine.Changed += MarkDirty;
        ListenAddresses = string.Join(Environment.NewLine, engine.Options.ListenAddresses);
        AllowedCidrs = string.Join(Environment.NewLine, engine.Options.AllowedCidrs);
        Port = engine.Options.Port.ToString();
        RefreshInterfaces();
        AdvertiseAddress = InterfaceAddresses.FirstOrDefault(address => engine.Options.ListenAddresses.Contains(address.Address))?.Address
            ?? engine.Options.ListenAddresses.FirstOrDefault() ?? "";

        NavigateCommand = new DelegateCommand(parameter => Page = parameter?.ToString() ?? "Devices");
        ToggleHubCommand = new DelegateCommand(_ => _ = RunAsync(ToggleHubAsync), () => !IsBusy);
        SaveNetworkCommand = new DelegateCommand(_ => SaveNetwork(), () => CanEditNetwork);
        RefreshInterfacesCommand = new DelegateCommand(_ => RefreshInterfaces(), () => !IsBusy);
        DiagnosticsCommand = new DelegateCommand(_ => _ = RunAsync(CheckNetworkAsync), () => !IsBusy);
        CreateEnrollmentCommand = new DelegateCommand(_ => CreateEnrollment(), () => IsRunning && !IsBusy && !string.IsNullOrWhiteSpace(AdvertiseAddress));
        CopyEnrollmentCommand = new DelegateCommand(_ => CopyText(EnrollmentUri, "Enrollment link copied. Treat it as a temporary invitation."), () => HasEnrollment && enrollmentExpires > DateTimeOffset.UtcNow);
        CopyFingerprintCommand = new DelegateCommand(_ => CopyText(Fingerprint, "Hub fingerprint copied."));
        ApproveCommand = new DelegateCommand(_ => ChangeDevice("approve"), () => SelectedDevice is { Approved: false, Revoked: false } && !IsBusy);
        RevokeCommand = new DelegateCommand(_ => ChangeDevice("revoke"), () => SelectedDevice is { Approved: true, Revoked: false } && !IsBusy);
        RejectCommand = new DelegateCommand(_ => ChangeDevice("reject"), () => SelectedDevice is { Approved: false, Revoked: false } && !IsBusy);
        SaveAliasCommand = new DelegateCommand(_ => SaveAlias(), () => SelectedDevice != null && !IsBusy);
        CopyDeviceFingerprintCommand = new DelegateCommand(_ => CopyText(SelectedDevice?.Fingerprint ?? "", "Device fingerprint copied."), () => SelectedDevice != null);
        OpenProfileCommand = new DelegateCommand(_ =>
        {
            try { Process.Start(new ProcessStartInfo("explorer.exe", DataDirectory) { UseShellExecute = true }); }
            catch (Exception exception) { ShowNotice(exception.Message, true); }
        });
        InitializeDispatch(audioAdapter ?? new DesktopAudio());
        Refresh();
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    public string DataDirectory { get; }
    public string Fingerprint => engine.Fingerprint;
    public string FingerprintShort => Fingerprint.Length >= 16 ? Fingerprint[..8] + " · " + Fingerprint[^8..] : Fingerprint;
    public ObservableCollection<DeviceRow> Devices { get; } = new();
    public ObservableCollection<string> Activity { get; } = new();
    public ObservableCollection<InterfaceAddressRow> InterfaceAddresses { get; } = new();
    public string[] Filters { get; } = ["All devices", "Pending approval", "Approved", "Revoked"];
    public IEnumerable<DeviceRow> FilteredDevices => Devices.Where(device =>
        (Filter == "All devices" || Filter == "Pending approval" && !device.Approved && !device.Revoked || Filter == "Approved" && device.Approved && !device.Revoked || Filter == "Revoked" && device.Revoked)
        && (string.IsNullOrWhiteSpace(Search) || (device.DisplayName + " " + device.Name + " " + device.Fingerprint + " " + device.AddressText).Contains(Search, StringComparison.OrdinalIgnoreCase)));
    public int OnlineCount => Devices.Count(device => device.Online && device.Approved);
    public int ApprovedCount => Devices.Count(device => device.Approved && !device.Revoked);
    public int PendingCount => Devices.Count(device => !device.Approved && !device.Revoked);
    public bool HasDevices => Devices.Count != 0;
    public bool IsEmpty => Devices.Count == 0;
    public bool HasSelection => SelectedDevice != null;
    public bool NoSelection => SelectedDevice == null;
    public bool IsCompact { get => compact; set { if (Set(ref compact, value)) Notify(nameof(ShowEmptyIllustration)); } }
    public bool ShowEmptyIllustration => !IsCompact;
    public bool IsRunning => engine.IsRunning;
    public bool CanEditNetwork => !IsRunning && !IsBusy;
    public string HubStatus => IsRunning ? "Hub is running" : "Hub is stopped";
    public string HubStatusBrush => IsRunning ? "#26765A" : "#666076";
    public string StartStopLabel => IsBusy ? "Please wait…" : IsRunning ? "Stop hub" : "Start hub";
    public string ListenerSummary => IsRunning ? $"TCP {engine.Options.Port} · {engine.Options.ListenAddresses.Length} interface(s)" : "Local connections only · no cloud";
    public string BackgroundHint => "Closing this window keeps Relay in the system tray. Choose Quit to stop the hub.";
    public bool IsBusy { get => busy; private set { if (Set(ref busy, value)) { Notify(nameof(CanEditNetwork)); Notify(nameof(StartStopLabel)); RefreshCommands(); } } }
    public string Page { get => page; set { if (page != value) ReleaseTalk(); if (Set(ref page, value)) { Notify(nameof(IsDevices)); Notify(nameof(IsDispatch)); Notify(nameof(IsEnrollment)); Notify(nameof(IsNetwork)); Notify(nameof(IsActivity)); Notify(nameof(IsHelp)); Notify(nameof(PageSubtitle)); } } }
    public bool IsDevices => Page == "Devices";
    public bool IsEnrollment => Page == "Enrollment";
    public bool IsNetwork => Page == "Network";
    public bool IsActivity => Page == "Activity";
    public bool IsHelp => Page == "Help";
    public string PageSubtitle => Page switch
    {
        "Devices" => "Your Relay devices, together on one local network.",
        "Dispatch" => "Call one device, talk to everyone, or send an emergency alert.",
        "Enrollment" => "A private invitation. Approved on both sides.",
        "Network" => "Choose where Relay listens and who may connect.",
        "Activity" => "Connection events and enrollment decisions. Voice stays private.",
        _ => "Set up a reliable connection across your local subnets."
    };
    public string Search { get => search; set { if (Set(ref search, value)) Notify(nameof(FilteredDevices)); } }
    public string Filter { get => filter; set { if (Set(ref filter, value)) Notify(nameof(FilteredDevices)); } }
    public DeviceRow? SelectedDevice { get => selectedDevice; set { if (Set(ref selectedDevice, value)) { Alias = value?.Alias ?? ""; Notify(nameof(HasSelection)); Notify(nameof(NoSelection)); RefreshCommands(); } } }
    public string Alias { get => alias; set => Set(ref alias, value); }
    public string Notice { get => notice; private set => Set(ref notice, value); }
    public bool NoticeIsError { get => noticeIsError; private set { if (Set(ref noticeIsError, value)) Notify(nameof(NoticeBrush)); } }
    public string NoticeBrush => NoticeIsError ? "#B42340" : "#516275";
    public string EnrollmentUri { get => enrollmentUri; private set { if (Set(ref enrollmentUri, value)) { Notify(nameof(HasEnrollment)); Notify(nameof(NoEnrollment)); RefreshCommands(); } } }
    public bool HasEnrollment => !string.IsNullOrWhiteSpace(EnrollmentUri);
    public bool NoEnrollment => !HasEnrollment;
    public string EnrollmentExpiry => !HasEnrollment ? "Create an invitation when the phone is ready." : enrollmentExpires <= DateTimeOffset.UtcNow ? "This invitation expired. Generate another to enroll a device." : $"Single use · expires in {Math.Ceiling((enrollmentExpires - DateTimeOffset.UtcNow).TotalMinutes):0} min";
    public BitmapImage? QrImage { get => qrImage; private set => Set(ref qrImage, value); }
    public string AdvertiseAddress { get => advertiseAddress; set { if (Set(ref advertiseAddress, value)) RefreshCommands(); } }
    public string ListenAddresses { get => listenAddresses; set => Set(ref listenAddresses, value); }
    public string AllowedCidrs { get => allowedCidrs; set => Set(ref allowedCidrs, value); }
    public string Port { get => port; set => Set(ref port, value); }
    public string Diagnostics { get => diagnostics; private set => Set(ref diagnostics, value); }

    public DelegateCommand NavigateCommand { get; }
    public DelegateCommand ToggleHubCommand { get; }
    public DelegateCommand SaveNetworkCommand { get; }
    public DelegateCommand RefreshInterfacesCommand { get; }
    public DelegateCommand DiagnosticsCommand { get; }
    public DelegateCommand CreateEnrollmentCommand { get; }
    public DelegateCommand CopyEnrollmentCommand { get; }
    public DelegateCommand CopyFingerprintCommand { get; }
    public DelegateCommand ApproveCommand { get; }
    public DelegateCommand RevokeCommand { get; }
    public DelegateCommand RejectCommand { get; }
    public DelegateCommand SaveAliasCommand { get; }
    public DelegateCommand CopyDeviceFingerprintCommand { get; }
    public DelegateCommand OpenProfileCommand { get; }

    private void MarkDirty() { Interlocked.Exchange(ref dirty, 1); QueueDispatchRefresh(); }

    public void Refresh()
    {
        if (disposed) return;
        Notify(nameof(EnrollmentExpiry));
        RefreshDispatch();
        if (Interlocked.Exchange(ref dirty, 0) == 0) return;
        var snapshots = engine.Devices.OrderBy(device => device.Approved ? 1 : 0).ThenBy(device => device.Alias.Length == 0 ? device.Name : device.Alias).ToArray();
        string stamp = string.Join("|", snapshots.Select(device => $"{device.Fingerprint}:{device.Name}:{device.Alias}:{device.Approved}:{device.Revoked}:{device.Online}:{device.LastSeen:O}:{device.Status}:{string.Join(",", device.Addresses)}:{device.AppVersion}:{string.Join(",", device.Capabilities)}"));
        if (stamp != deviceStamp)
        {
            deviceStamp = stamp;
            string? selectedFingerprint = SelectedDevice?.Fingerprint;
            string pendingAlias = Alias;
            Devices.Clear();
            foreach (var snapshot in snapshots) Devices.Add(new DeviceRow(snapshot));
            SelectedDevice = Devices.FirstOrDefault(device => device.Fingerprint == selectedFingerprint);
            if (SelectedDevice != null) Alias = pendingAlias;
            Notify(nameof(FilteredDevices)); Notify(nameof(OnlineCount)); Notify(nameof(ApprovedCount)); Notify(nameof(PendingCount)); Notify(nameof(HasDevices)); Notify(nameof(IsEmpty));
        }
        var entries = engine.Logs.ToArray();
        string nextLogStamp = string.Join("\n", entries);
        if (nextLogStamp != logStamp)
        {
            logStamp = nextLogStamp;
            Activity.Clear();
            foreach (string entry in entries) Activity.Add(entry);
        }
        Notify(nameof(IsRunning)); Notify(nameof(CanEditNetwork)); Notify(nameof(HubStatus)); Notify(nameof(HubStatusBrush)); Notify(nameof(StartStopLabel)); Notify(nameof(ListenerSummary));
        RefreshCommands();
    }

    private async Task ToggleHubAsync()
    {
        if (IsRunning)
        {
            ReleaseTalk();
            engine.EndDispatch("Hub stopped");
            await audio.StopPlaybackAsync();
            await engine.StopAsync();
            EnrollmentUri = "";
            QrImage = null;
            ShowNotice("Hub stopped. Relayed calls and device presence are disconnected.");
        }
        else
        {
            if (!SaveNetwork()) return;
            await engine.StartAsync();
            ShowNotice("Hub started. Open Enrollment to invite a device.");
        }
        MarkDirty();
    }

    private async Task RunAsync(Func<Task> action)
    {
        if (IsBusy) return;
        IsBusy = true;
        try { await action(); }
        catch (Exception exception) { ShowNotice(exception.Message, true); }
        finally { IsBusy = false; MarkDirty(); Refresh(); }
    }

    private bool SaveNetwork()
    {
        try
        {
            if (engine.IsRunning) throw new InvalidOperationException("Stop the hub before changing network settings.");
            if (!int.TryParse(Port, out int number) || number < 1024 || number > 65535)
                throw new ArgumentException("Choose a port between 1024 and 65535.");
            string[] addresses = SplitLines(ListenAddresses);
            if (addresses.Length == 0) throw new ArgumentException("Enter at least one assigned private IPv4 address to listen on.");
            if (addresses.Any(address => !IPAddress.TryParse(address, out IPAddress? ip) || !IsPrivate(ip)))
                throw new ArgumentException("Listener addresses must be explicit private IPv4 addresses. Wildcards and public addresses are not accepted.");
            string[] cidrs = SplitLines(AllowedCidrs);
            if (cidrs.Length == 0) throw new ArgumentException("Enter at least one approved private IPv4 CIDR, for example 192.168.10.0/24.");
            var oldAddresses = engine.Options.ListenAddresses;
            var oldCidrs = engine.Options.AllowedCidrs;
            int oldPort = engine.Options.Port;
            try
            {
                engine.Options.ListenAddresses = addresses;
                engine.Options.AllowedCidrs = cidrs;
                engine.Options.Port = number;
                engine.SaveOptions();
            }
            catch
            {
                engine.Options.ListenAddresses = oldAddresses;
                engine.Options.AllowedCidrs = oldCidrs;
                engine.Options.Port = oldPort;
                throw;
            }
            ShowNotice("Network settings saved. Firewall and router configuration remain under your control.");
            MarkDirty();
            return true;
        }
        catch (Exception exception) { ShowNotice(exception.Message, true); return false; }
    }

    private void RefreshInterfaces()
    {
        try
        {
            var addresses = NetworkInterface.GetAllNetworkInterfaces()
                .Where(adapter => adapter.OperationalStatus == OperationalStatus.Up && adapter.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .SelectMany(adapter => adapter.GetIPProperties().UnicastAddresses
                    .Where(address => IsPrivate(address.Address))
                    .Select(address => new InterfaceAddressRow(address.Address.ToString(), adapter.Name, NetworkCidr(address.Address, address.PrefixLength))))
                .DistinctBy(address => address.Address).OrderBy(address => address.Address).ToArray();
            InterfaceAddresses.Clear();
            foreach (var address in addresses) InterfaceAddresses.Add(address);
        }
        catch (Exception exception) { ShowNotice("Could not list network interfaces: " + exception.Message, true); }
    }

    private void CreateEnrollment()
    {
        try
        {
            if (!IPAddress.TryParse(AdvertiseAddress, out IPAddress? ip) || !IsPrivate(ip) || !engine.Options.ListenAddresses.Contains(ip.ToString()))
                throw new ArgumentException("Choose an address on which the running hub is listening.");
            string invitation = engine.CreateEnrollmentUri(ip.ToString());
            using var generator = new QRCodeGenerator();
            using var code = generator.CreateQrCode(invitation, QRCodeGenerator.ECCLevel.M);
            using var renderer = new PngByteQRCode(code);
            using var stream = new MemoryStream(renderer.GetGraphic(6));
            var image = new BitmapImage();
            image.BeginInit(); image.CacheOption = BitmapCacheOption.OnLoad; image.StreamSource = stream; image.EndInit(); image.Freeze();
            QrImage = image;
            enrollmentExpires = DateTimeOffset.UtcNow.AddMinutes(10);
            EnrollmentUri = invitation;
            Notify(nameof(EnrollmentExpiry));
            ShowNotice("Invitation created. Scan it in Relay → Settings → Desktop manager, then approve the request here.");
        }
        catch (Exception exception) { ShowNotice(exception.Message, true); }
    }

    private void ChangeDevice(string action)
    {
        if (SelectedDevice == null) return;
        try
        {
            string fingerprint = SelectedDevice.Fingerprint;
            string name = SelectedDevice.DisplayName;
            switch (action)
            {
                case "approve": engine.Approve(fingerprint); ShowNotice($"{name} approved. Peer pairing still requires approval on both phones."); break;
                case "reject": engine.Reject(fingerprint); ShowNotice($"Enrollment rejected for {name}."); break;
                default: engine.Revoke(fingerprint); ShowNotice($"Hub access revoked for {name}. Its direct peer pairings are unchanged."); break;
            }
            MarkDirty(); Refresh();
        }
        catch (Exception exception) { ShowNotice(exception.Message, true); }
    }

    private void SaveAlias()
    {
        if (SelectedDevice == null) return;
        try
        {
            if (Alias.Length > 64) throw new ArgumentException("Use an alias of 64 characters or fewer.");
            engine.SetAlias(SelectedDevice.Fingerprint, Alias.Trim());
            ShowNotice("Local alias saved. The phone’s reported name is unchanged."); MarkDirty(); Refresh();
        }
        catch (Exception exception) { ShowNotice(exception.Message, true); }
    }

    private async Task CheckNetworkAsync()
    {
        RefreshInterfaces();
        var results = new List<string>();
        if (!IsRunning) results.Add("Hub is stopped. Start it before checking the listener.");
        foreach (string address in engine.Options.ListenAddresses)
        {
            bool assigned = InterfaceAddresses.Any(item => item.Address == address);
            results.Add(address + (assigned ? " is assigned to an active private interface." : " is not assigned to an active private interface."));
            if (!IsRunning || !assigned) continue;
            try
            {
                using var socket = new TcpClient(AddressFamily.InterNetwork);
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                await socket.ConnectAsync(address, engine.Options.Port, timeout.Token);
                results.Add($"TCP {engine.Options.Port} accepts connections on this PC.");
            }
            catch (Exception exception) { results.Add("Listener check failed: " + exception.Message); }
        }
        results.Add("This local test cannot prove access from another subnet. On each phone, use Desktop manager to connect and check its status. Permit phone-to-PC TCP traffic in the router and Windows Firewall.");
        Diagnostics = string.Join(Environment.NewLine + Environment.NewLine, results);
        ShowNotice("Local network check completed.");
    }

    private void CopyText(string value, string message)
    {
        if (string.IsNullOrWhiteSpace(value)) return;
        try { Clipboard.SetText(value); ShowNotice(message); }
        catch (Exception exception) { ShowNotice("Clipboard unavailable: " + exception.Message, true); }
    }

    private void ShowNotice(string value, bool error = false) { Notice = value; NoticeIsError = error; }
    private static string[] SplitLines(string value) => value.Split(['\r', '\n', ',', ';'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).Distinct().ToArray();
    private static bool IsPrivate(IPAddress address)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork) return false;
        byte[] bytes = address.GetAddressBytes();
        return bytes[0] == 10 || bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31 || bytes[0] == 192 && bytes[1] == 168;
    }
    private static string NetworkCidr(IPAddress address, int prefix)
    {
        byte[] bytes = address.GetAddressBytes();
        int remaining = prefix;
        for (int index = 0; index < bytes.Length; index++)
        {
            int mask = remaining >= 8 ? 255 : remaining <= 0 ? 0 : 255 << (8 - remaining) & 255;
            bytes[index] = (byte)(bytes[index] & mask); remaining -= 8;
        }
        return new IPAddress(bytes) + "/" + prefix;
    }
    private void RefreshCommands() => CommandManager.InvalidateRequerySuggested();
    private bool Set<T>(ref T storage, T value, [CallerMemberName] string? property = null)
    {
        if (EqualityComparer<T>.Default.Equals(storage, value)) return false;
        storage = value; Notify(property); return true;
    }
    private void Notify([CallerMemberName] string? property = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(property));

    public async ValueTask DisposeAsync()
    {
        if (disposed) return;
        disposed = true;
        engine.Changed -= MarkDirty;
        await DisposeDispatchAsync();
        await engine.DisposeAsync();
    }
}

public sealed class DeviceRow(DeviceSnapshot device)
{
    public string Fingerprint { get; } = device.Fingerprint;
    public string Name { get; } = device.Name;
    public string Alias { get; } = device.Alias;
    public string DisplayName => string.IsNullOrWhiteSpace(Alias) ? Name : Alias;
    public string AppVersion { get; } = device.AppVersion;
    public bool SupportsDispatch { get; } = device.Capabilities.Contains("dispatch-v1");
    public string CapabilitiesText => SupportsDispatch ? "Desktop calls and announcements supported" : "Update Relay to receive desktop calls";
    public bool Online { get; } = device.Online;
    public bool Approved { get; } = device.Approved;
    public bool Revoked { get; } = device.Revoked;
    public string Status { get; } = device.Status;
    public string AddressText { get; } = string.Join(", ", device.Addresses);
    public string Presence => Online ? "Online" : "Offline";
    public string PresenceBrush => Online ? "#26765A" : "#666076";
    public string Trust => Revoked ? "Revoked" : Approved ? "Approved" : "Pending";
    public string TrustBrush => Revoked ? "#B42340" : Approved ? "#26765A" : "#8F601E";
    public string LastSeen => device.LastSeen == default ? "Never" : device.LastSeen.ToLocalTime().ToString("MMM d, yyyy · HH:mm:ss");
    public string IdentityShort => Fingerprint.Length >= 12 ? Fingerprint[..12] : Fingerprint;
}

public sealed record InterfaceAddressRow(string Address, string Name, string Cidr)
{
    public string Display => Address + " · " + Name;
}

public sealed class DelegateCommand(Action<object?> execute, Func<bool>? canExecute = null) : ICommand
{
    public event EventHandler? CanExecuteChanged { add => CommandManager.RequerySuggested += value; remove => CommandManager.RequerySuggested -= value; }
    public bool CanExecute(object? parameter) => canExecute?.Invoke() ?? true;
    public void Execute(object? parameter) => execute(parameter);
}
