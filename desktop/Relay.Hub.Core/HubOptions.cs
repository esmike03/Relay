using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Relay.Hub.Core;

public sealed class HubOptions
{
    public string[] ListenAddresses { get; set; } = [];
    public string[] AllowedCidrs { get; set; } = [];
    public int Port { get; set; } = 52744;
    public int MaxDevices { get; set; } = 64;
    public int MaxConnections { get; set; } = 256;
    public int MaxTunnels { get; set; } = 32;

    public static HubOptions Detect()
    {
        var addresses = NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && Cidr.IsPrivateAddress(a.Address)).ToArray();
        return new HubOptions
        {
            ListenAddresses = addresses.Select(a => a.Address.ToString()).Distinct().ToArray(),
            AllowedCidrs = addresses.Select(a => Cidr.FromAddress(a.Address, a.PrefixLength).ToString()).Distinct().ToArray()
        };
    }

    internal (IPAddress[] Addresses, Cidr[] Ranges) Validate(bool allowLoopback)
    {
        if (Port is < 1024 or > 65535) throw new ArgumentException("Hub port must be between 1024 and 65535.");
        if (MaxDevices is < 2 or > 64 || MaxConnections is < 4 or > 4096 || MaxTunnels is < 1 or > 512)
            throw new ArgumentException("Invalid device, connection, or tunnel limit.");
        if (ListenAddresses.Length is < 1 or > 32 || AllowedCidrs.Length is < 1 or > 64)
            throw new ArgumentException("Select at least one private IPv4 listener and approved subnet.");
        var addresses = ListenAddresses.Select(s => Cidr.ParseAddress(s)).Distinct().ToArray();
        var ranges = AllowedCidrs.Select(Cidr.Parse).ToArray();
        foreach (var address in addresses)
            if (!Cidr.IsPrivateAddress(address) && !(allowLoopback && IPAddress.IsLoopback(address)))
                throw new ArgumentException("Listeners must be private IPv4 addresses; wildcard/public listeners are disabled.");
        foreach (var range in ranges)
            if (!range.IsPrivate && !(allowLoopback && range.IsLoopback))
                throw new ArgumentException("Approved subnets must be contained within private IPv4 address ranges.");
        return (addresses, ranges);
    }
}

public sealed record DeviceSnapshot(string Fingerprint, string Name, string Alias, string AppVersion,
    string[] Addresses, bool Online, bool Approved, bool Revoked, DateTimeOffset LastSeen, string Status)
{
    public string[] Capabilities { get; init; } = [];
}

public sealed record DispatchParticipant(string Fingerprint, string Name, string State, string Reason);
public sealed record DispatchSnapshot(string Id, string Mode, bool Active, bool LocalTalking,
    bool RemoteTalking, IReadOnlyList<DispatchParticipant> Participants, string FloorOwner);
public sealed record EmergencySnapshot(string Id, string Fingerprint, string Name, string Message,
    DateTimeOffset CreatedAt, bool Resolved);

internal readonly record struct Cidr(uint Network, int Prefix)
{
    private uint Mask => Prefix == 0 ? 0 : uint.MaxValue << (32 - Prefix);
    public bool Contains(IPAddress address) => address.AddressFamily == AddressFamily.InterNetwork && (Value(address) & Mask) == Network;
    public bool IsPrivate => (Prefix >= 8 && Network >> 24 == 10) ||
        (Prefix >= 12 && Network >> 20 == 0xAC1) || (Prefix >= 16 && Network >> 16 == 0xC0A8);
    public bool IsLoopback => Prefix >= 8 && Network >> 24 == 127;
    public static bool IsPrivateAddress(IPAddress address) => address.AddressFamily == AddressFamily.InterNetwork && FromAddress(address, 32).IsPrivate;
    public static IPAddress ParseAddress(string value)
    {
        if (value is null || !System.Text.RegularExpressions.Regex.IsMatch(value, @"^\d{1,3}(\.\d{1,3}){3}$") ||
            !IPAddress.TryParse(value, out var address) || address.AddressFamily != AddressFamily.InterNetwork)
            throw new ArgumentException("Enter a numeric IPv4 address.");
        return address;
    }
    public static Cidr Parse(string value)
    {
        var parts = value.Split('/');
        if (parts.Length != 2 || !int.TryParse(parts[1], out var prefix) || prefix is < 0 or > 32)
            throw new ArgumentException("Enter a CIDR subnet, such as 192.168.1.0/24.");
        var range = FromAddress(ParseAddress(parts[0]), prefix);
        if (Value(ParseAddress(parts[0])) != range.Network)
            throw new ArgumentException("CIDR must use the network address, for example " + range + ".");
        return range;
    }
    public static Cidr FromAddress(IPAddress address, int prefix)
    {
        var mask = prefix == 0 ? 0 : uint.MaxValue << (32 - prefix);
        return new(Value(address) & mask, prefix);
    }
    private static uint Value(IPAddress address) => System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(address.GetAddressBytes());
    public override string ToString() => $"{Network >> 24}.{(Network >> 16) & 255}.{(Network >> 8) & 255}.{Network & 255}/{Prefix}";
}
