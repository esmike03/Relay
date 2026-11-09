using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Forms = System.Windows.Forms;

namespace Relay.Desktop;

public partial class MainWindow : Window
{
    private readonly MainViewModel viewModel;
    private readonly Forms.NotifyIcon? tray;
    private readonly Icon? trayIcon;
    private readonly DispatcherTimer refresh;
    private bool quitting;
    private bool balloonShown;
    private readonly bool smokeTest;
    private readonly string? smokeReport;
    private readonly bool smokeEnrollment;
    private bool talkKeyHeld;

    public MainWindow(string dataDirectory, bool smokeTest = false, string? smokeReport = null, bool smokeEnrollment = false)
    {
        InitializeComponent();
        this.smokeTest = smokeTest;
        this.smokeReport = smokeReport;
        this.smokeEnrollment = smokeEnrollment;
        viewModel = new MainViewModel(dataDirectory);
        viewModel.EmergencyReceived += OnEmergencyReceived;
        DataContext = viewModel;
        refresh = new DispatcherTimer(TimeSpan.FromMilliseconds(600), DispatcherPriority.Background, (_, _) => viewModel.Refresh(), Dispatcher);
        refresh.Start();
        if (!smokeTest)
        {
            trayIcon = CreateTrayIcon();
            tray = new Forms.NotifyIcon
            {
                Icon = trayIcon,
                Text = "Relay Desktop Manager",
                Visible = true
            };
            var menu = new Forms.ContextMenuStrip();
            menu.Items.Add("Open Relay", null, (_, _) => Dispatcher.Invoke(ShowFromTray));
            menu.Items.Add("Quit and stop hub", null, (_, _) => Dispatcher.Invoke(() => _ = QuitAsync()));
            tray.ContextMenuStrip = menu;
            tray.DoubleClick += (_, _) => Dispatcher.Invoke(ShowFromTray);
        }
        Closing += OnClosing;
        Loaded += OnLoaded;
        SizeChanged += (_, _) => viewModel.IsCompact = ActualHeight < 700 || ActualWidth < 950;
        Deactivated += (_, _) => ReleaseTalkCapture();
        PreviewKeyUp += OnWindowKeyUp;
        PreviewKeyDown += (_, args) => { if (args.Key == Key.Escape && viewModel.IsTalkPressed) { ReleaseTalkCapture(); args.Handled = true; } };
        viewModel.PropertyChanged += (_, args) => { if (args.PropertyName == nameof(MainViewModel.Page)) ReleaseTalkCapture(); };
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (!smokeTest) return;
        try
        {
            await Task.Delay(750);
            bool repeatInvitationVerified = false;
            if (smokeEnrollment)
            {
                viewModel.ToggleHubCommand.Execute(null);
                while (viewModel.IsBusy) await Task.Delay(50);
                if (!viewModel.IsRunning) throw new InvalidOperationException(viewModel.Notice);
                if (!viewModel.CreateEnrollmentCommand.CanExecute(null)) throw new InvalidOperationException("Enrollment command should be enabled while the hub is running.");
                viewModel.CreateEnrollmentCommand.Execute(null);
                if (!viewModel.HasEnrollment || viewModel.QrImage == null) throw new InvalidOperationException(viewModel.Notice);
                string firstInvitation = viewModel.EnrollmentUri;
                if (!viewModel.CreateEnrollmentCommand.CanExecute(null)) throw new InvalidOperationException("Enrollment command became disabled after creating one invitation.");
                viewModel.CreateEnrollmentCommand.Execute(null);
                if (viewModel.EnrollmentUri == firstInvitation) throw new InvalidOperationException("Repeated generation did not create a fresh invitation.");
                repeatInvitationVerified = true;
            }
            await Dispatcher.InvokeAsync(() => { }, DispatcherPriority.ApplicationIdle);
            var pages = new List<object>();
            var popups = new List<object>();
            foreach (string page in new[] { "Devices", "Dispatch", "Enrollment", "Network", "Activity", "Help" })
            {
                viewModel.Page = page;
                await Dispatcher.InvokeAsync(UpdateLayout, DispatcherPriority.Render);
                string? screenshot = null;
                if (smokeReport != null)
                {
                    string reportPath = Path.GetFullPath(smokeReport);
                    Directory.CreateDirectory(Path.GetDirectoryName(reportPath)!);
                    screenshot = Path.Combine(Path.GetDirectoryName(reportPath)!, "relay-desktop-" + page.ToLowerInvariant() + ".png");
                    SaveVisual(screenshot);
                }
                pages.Add(new { page, rendered = IsLoaded && ActualWidth >= MinWidth && ActualHeight >= MinHeight, width = ActualWidth, height = ActualHeight, screenshot });
                ComboBox? combo = page == "Devices" ? DeviceFilter : page == "Enrollment" ? HubAdvertiseAddress : null;
                if (combo != null && combo.Items.Count > 0)
                {
                    combo.IsDropDownOpen = true;
                    await Dispatcher.InvokeAsync(UpdateLayout, DispatcherPriority.ApplicationIdle);
                    var colors = new List<string>();
                    foreach (object item in combo.Items)
                    {
                        if (combo.ItemContainerGenerator.ContainerFromItem(item) is not ComboBoxItem container) continue;
                        var text = FindVisual<TextBlock>(container);
                        if (text?.Foreground is not SolidColorBrush foreground) throw new InvalidOperationException("Dropdown item has no inspectable text color.");
                        if (foreground.Color.R > 100 || foreground.Color.G > 100 || foreground.Color.B > 100)
                            throw new InvalidOperationException("Dropdown text does not provide dark text on the light background.");
                        colors.Add(foreground.Color.ToString());
                    }
                    if (colors.Count == 0) throw new InvalidOperationException("Dropdown items did not render.");
                    string? popupScreenshot = null;
                    if (smokeReport != null && combo.Template.FindName("PART_Popup", combo) is Popup { Child: FrameworkElement popupContent })
                    {
                        popupScreenshot = Path.Combine(Path.GetDirectoryName(Path.GetFullPath(smokeReport))!, "relay-desktop-" + page.ToLowerInvariant() + "-dropdown.png");
                        SaveElement(popupContent, popupScreenshot);
                    }
                    popups.Add(new { page, darkTextVerified = true, colors, screenshot = popupScreenshot });
                    combo.IsDropDownOpen = false;
                    await Dispatcher.InvokeAsync(UpdateLayout, DispatcherPriority.ApplicationIdle);
                }
            }
            if (smokeReport != null)
            {
                await File.WriteAllTextAsync(smokeReport, JsonSerializer.Serialize(new
                {
                    success = true,
                    utc = DateTimeOffset.UtcNow,
                    profile = viewModel.DataDirectory,
                    hubRunning = viewModel.IsRunning,
                    enrollmentGenerated = viewModel.HasEnrollment,
                    repeatedInvitationGenerationVerified = repeatInvitationVerified,
                    enrolledDevices = viewModel.Devices.Count,
                    pages,
                    popups
                }, new JsonSerializerOptions { WriteIndented = true }));
            }
            await QuitAsync();
        }
        catch (Exception exception)
        {
            if (smokeReport != null)
                await File.WriteAllTextAsync(smokeReport, JsonSerializer.Serialize(new { success = false, error = exception.ToString() }));
            quitting = true;
            refresh.Stop();
            await viewModel.DisposeAsync();
            Application.Current.Shutdown(1);
        }
    }

    private void SaveVisual(string path)
    {
        SaveElement((FrameworkElement)Content, path);
    }

    private static void SaveElement(FrameworkElement visual, string path)
    {
        var image = new RenderTargetBitmap((int)visual.ActualWidth, (int)visual.ActualHeight, 96, 96, PixelFormats.Pbgra32);
        image.Render(visual);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(image));
        using var output = File.Create(path);
        encoder.Save(output);
    }

    private static T? FindVisual<T>(DependencyObject parent) where T : DependencyObject
    {
        if (parent is T target) return target;
        for (int index = 0; index < VisualTreeHelper.GetChildrenCount(parent); index++)
            if (FindVisual<T>(VisualTreeHelper.GetChild(parent, index)) is T child) return child;
        return null;
    }

    private void OnClosing(object? sender, CancelEventArgs e)
    {
        ReleaseTalkCapture();
        if (quitting) return;
        e.Cancel = true;
        Hide();
        if (!balloonShown && tray != null)
        {
            balloonShown = true;
            tray.ShowBalloonTip(3500, "Relay is in the system tray", "The hub keeps its current state. Choose Quit from the tray to stop it.", Forms.ToolTipIcon.Info);
        }
    }

    private void ShowFromTray()
    {
        Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
    }

    private async Task QuitAsync()
    {
        if (quitting) return;
        quitting = true;
        ReleaseTalkCapture();
        IsEnabled = false;
        refresh.Stop();
        try { await viewModel.DisposeAsync(); }
        finally
        {
            if (tray != null) { tray.Visible = false; tray.ContextMenuStrip?.Dispose(); tray.Dispose(); }
            trayIcon?.Dispose();
            Application.Current.Shutdown();
        }
    }

    private static Icon CreateTrayIcon()
    {
        using var bitmap = new Bitmap(32, 32);
        using var graphics = Graphics.FromImage(bitmap);
        graphics.SmoothingMode = SmoothingMode.AntiAlias;
        graphics.Clear(System.Drawing.Color.Transparent);
        using var background = new SolidBrush(System.Drawing.Color.FromArgb(245, 243, 249));
        using var ring = new System.Drawing.Pen(System.Drawing.Color.FromArgb(125, 94, 165), 2.6f);
        using var mint = new SolidBrush(System.Drawing.Color.FromArgb(38, 118, 90));
        graphics.FillEllipse(background, 1, 1, 30, 30);
        graphics.DrawEllipse(ring, 6, 6, 20, 20);
        graphics.FillEllipse(mint, 12, 12, 8, 8);
        nint handle = bitmap.GetHicon();
        try { using var source = System.Drawing.Icon.FromHandle(handle); return (System.Drawing.Icon)source.Clone(); }
        finally { DestroyIcon(handle); }
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyIcon(nint handle);

    private async void OnTalkMouseDown(object sender, MouseButtonEventArgs e)
    {
        e.Handled = true;
        TalkButton.Focus();
        if (!viewModel.CanTalk || !TalkButton.CaptureMouse()) return;
        await viewModel.BeginTalkAsync();
        if (!viewModel.IsTalkPressed && TalkButton.IsMouseCaptured) TalkButton.ReleaseMouseCapture();
    }

    private void OnTalkMouseUp(object sender, MouseButtonEventArgs e)
    {
        e.Handled = true;
        ReleaseTalkCapture();
    }

    private void OnTalkLostCapture(object sender, MouseEventArgs e) => viewModel.ReleaseTalk();
    private void OnTalkLostFocus(object sender, KeyboardFocusChangedEventArgs e) => ReleaseTalkCapture();

    private async void OnTalkKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Space) return;
        e.Handled = true;
        if (e.IsRepeat || talkKeyHeld || !viewModel.CanTalk) return;
        talkKeyHeld = true;
        await viewModel.BeginTalkAsync();
        if (!viewModel.IsTalkPressed) talkKeyHeld = false;
    }

    private void OnWindowKeyUp(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Space || !talkKeyHeld) return;
        e.Handled = true;
        ReleaseTalkCapture();
    }

    private void ReleaseTalkCapture()
    {
        talkKeyHeld = false;
        viewModel.ReleaseTalk();
        if (TalkButton.IsMouseCaptured) TalkButton.ReleaseMouseCapture();
    }

    private void OnEmergencyReceived(EmergencyRow emergency)
    {
        if (smokeTest || tray == null) return;
        tray.ShowBalloonTip(6000, "Relay emergency · " + emergency.Name,
            emergency.Message.Length > 180 ? emergency.Message[..180] + "…" : emergency.Message, Forms.ToolTipIcon.Warning);
    }
}
