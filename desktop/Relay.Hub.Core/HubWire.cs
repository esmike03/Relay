using System.Buffers.Binary;
using System.Text.Json;

namespace Relay.Hub.Core;

public static class HubWire
{
    public const int MaxFrameSize = 65536;
    public static async Task<JsonElement> ReadAsync(Stream stream, CancellationToken cancellationToken = default)
    {
        var header = new byte[4];
        await stream.ReadExactlyAsync(header, cancellationToken);
        var size = BinaryPrimitives.ReadInt32BigEndian(header);
        if (size is < 2 or > MaxFrameSize) throw new IOException("Invalid hub frame size.");
        var data = new byte[size];
        await stream.ReadExactlyAsync(data, cancellationToken);
        using var doc = JsonDocument.Parse(data, new JsonDocumentOptions { MaxDepth = 16 });
        if (doc.RootElement.ValueKind != JsonValueKind.Object) throw new IOException("Hub frame must be an object.");
        return doc.RootElement.Clone();
    }
    public static async Task WriteAsync(Stream stream, object message, CancellationToken cancellationToken = default)
    {
        var data = JsonSerializer.SerializeToUtf8Bytes(message);
        if (data.Length > MaxFrameSize) throw new IOException("Hub frame too large.");
        var header = new byte[4];
        BinaryPrimitives.WriteInt32BigEndian(header, data.Length);
        await stream.WriteAsync(header, cancellationToken);
        await stream.WriteAsync(data, cancellationToken);
        await stream.FlushAsync(cancellationToken);
    }
    internal static string Text(this JsonElement message, string key, int limit = 256) =>
        message.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.String ?
            (value.GetString() ?? "")[..Math.Min(value.GetString()?.Length ?? 0, limit)] : "";
    internal static int Integer(this JsonElement message, string key, int fallback = 0) =>
        message.TryGetProperty(key, out var value) && value.TryGetInt32(out var result) ? result : fallback;
}
