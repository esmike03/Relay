using System;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows;

namespace Relay.Desktop;

public partial class App : Application
{
    private Mutex? instanceMutex;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        try
        {
            string dataDirectory = ReadArgument(e.Args, "--data-dir")
                ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Relay", "Hub");
            dataDirectory = Path.GetFullPath(dataDirectory);
            string identity = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(dataDirectory.ToUpperInvariant())))[..20];
            instanceMutex = new Mutex(true, "Local\\Relay.Desktop." + identity, out bool firstInstance);
            if (!firstInstance)
            {
                MessageBox.Show("Relay Desktop Manager is already open for this profile. Open it from the system tray.", "Relay", MessageBoxButton.OK, MessageBoxImage.Information);
                Shutdown();
                return;
            }
            var window = new MainWindow(dataDirectory, e.Args.Contains("--smoke-test"), ReadArgument(e.Args, "--smoke-report"), e.Args.Contains("--smoke-enrollment"));
            if (e.Args.Contains("--smoke-compact")) { window.Width = window.MinWidth; window.Height = window.MinHeight; }
            MainWindow = window;
            window.Show();
        }
        catch (Exception exception)
        {
            MessageBox.Show("Relay could not open its local profile. " + exception.Message, "Relay", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }

    internal static string? ReadArgument(string[] arguments, string key)
    {
        int index = Array.IndexOf(arguments, key);
        return index >= 0 && index + 1 < arguments.Length ? arguments[index + 1] : null;
    }

    protected override void OnExit(ExitEventArgs e)
    {
        instanceMutex?.Dispose();
        base.OnExit(e);
    }
}
