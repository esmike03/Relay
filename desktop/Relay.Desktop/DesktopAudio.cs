using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;
using NAudio.Wave;

namespace Relay.Desktop;

public sealed record AudioDeviceChoice(int Index, string Name);

/// <summary>Audio hardware boundary. Constructing this adapter never opens the microphone.</summary>
public interface IDesktopAudio : IAsyncDisposable
{
    event Action<byte[]>? Captured;
    event Action<string>? Failed;
    IReadOnlyList<AudioDeviceChoice> Inputs { get; }
    IReadOnlyList<AudioDeviceChoice> Outputs { get; }
    bool IsCapturing { get; }
    void RefreshDevices();
    Task StartCaptureAsync(int device, CancellationToken cancellationToken);
    Task StopCaptureAsync();
    void SetPlaybackDevice(int device);
    void QueuePlayback(byte[] pcm);
    Task StopPlaybackAsync();
}

/// <summary>Windows wave devices, PCM 16 kHz / 16-bit / mono. All queues and playback buffers are bounded.</summary>
public sealed class DesktopAudio : IDesktopAudio
{
    private static readonly WaveFormat Format = new(16000, 16, 1);
    private readonly SemaphoreSlim captureGate = new(1, 1);
    private readonly object outputGate = new();
    private readonly CancellationTokenSource lifetime = new();
    private readonly Channel<CaptureFrame> captured = Channel.CreateBounded<CaptureFrame>(new BoundedChannelOptions(8)
    {
        FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true, SingleWriter = false
    });
    private readonly Channel<PlaybackFrame> playback = Channel.CreateBounded<PlaybackFrame>(new BoundedChannelOptions(1)
    {
        FullMode = BoundedChannelFullMode.DropOldest, SingleReader = true, SingleWriter = false
    });
    private readonly Task capturePump;
    private readonly Task playbackPump;
    private WaveInEvent? input;
    private WaveOutEvent? output;
    private BufferedWaveProvider? provider;
    private int capturing;
    private long captureGeneration;
    private long playbackGeneration;
    private int playbackDevice = -1;
    private int playbackEnabled;
    private int openedPlaybackDevice = int.MinValue;
    private bool disposed;

    public DesktopAudio()
    {
        RefreshDevices();
        capturePump = Task.Run(PumpCaptureAsync);
        playbackPump = Task.Run(PumpPlaybackAsync);
    }

    public event Action<byte[]>? Captured;
    public event Action<string>? Failed;
    public IReadOnlyList<AudioDeviceChoice> Inputs { get; private set; } = Array.Empty<AudioDeviceChoice>();
    public IReadOnlyList<AudioDeviceChoice> Outputs { get; private set; } = Array.Empty<AudioDeviceChoice>();
    public bool IsCapturing => Volatile.Read(ref capturing) != 0;

    public void RefreshDevices()
    {
        try
        {
            var inputs = new List<AudioDeviceChoice>();
            var outputs = new List<AudioDeviceChoice>();
            if (WaveIn.DeviceCount > 0) inputs.Add(new(-1, "Windows default microphone"));
            for (int index = 0; index < WaveIn.DeviceCount; index++) inputs.Add(new(index, WaveIn.GetCapabilities(index).ProductName));
            if (WaveOut.DeviceCount > 0) outputs.Add(new(-1, "Windows default speaker / headset"));
            for (int index = 0; index < WaveOut.DeviceCount; index++) outputs.Add(new(index, WaveOut.GetCapabilities(index).ProductName));
            Inputs = inputs; Outputs = outputs;
        }
        catch (Exception exception)
        {
            Inputs = Array.Empty<AudioDeviceChoice>(); Outputs = Array.Empty<AudioDeviceChoice>();
            Failed?.Invoke("Windows audio devices could not be listed: " + exception.Message);
        }
    }

    public async Task StartCaptureAsync(int device, CancellationToken cancellationToken)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        await captureGate.WaitAsync(cancellationToken);
        try
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (input != null) return;
            await Task.Run(() =>
            {
                cancellationToken.ThrowIfCancellationRequested();
                long generation = Interlocked.Increment(ref captureGeneration);
                var microphone = new WaveInEvent { DeviceNumber = device, WaveFormat = Format, BufferMilliseconds = 20, NumberOfBuffers = 3 };
                byte[] partial = new byte[640];
                int partialLength = 0;
                microphone.DataAvailable += (_, args) =>
                {
                    if (!IsCapturing || generation != Interlocked.Read(ref captureGeneration)) return;
                    int offset = 0;
                    while (offset < args.BytesRecorded)
                    {
                        int count = Math.Min(640 - partialLength, args.BytesRecorded - offset);
                        Buffer.BlockCopy(args.Buffer, offset, partial, partialLength, count);
                        partialLength += count; offset += count;
                        if (partialLength != 640) continue;
                        captured.Writer.TryWrite(new(generation, partial));
                        partial = new byte[640]; partialLength = 0;
                    }
                };
                microphone.RecordingStopped += (_, args) =>
                {
                    if (args.Exception == null || generation != Interlocked.Read(ref captureGeneration)) return;
                    Volatile.Write(ref capturing, 0);
                    Failed?.Invoke("Microphone recording stopped: " + args.Exception.Message);
                };
                try
                {
                    Volatile.Write(ref capturing, 1);
                    microphone.StartRecording();
                    input = microphone;
                    if (cancellationToken.IsCancellationRequested)
                    {
                        Volatile.Write(ref capturing, 0);
                        Interlocked.Increment(ref captureGeneration);
                        input = null; microphone.StopRecording(); microphone.Dispose();
                        cancellationToken.ThrowIfCancellationRequested();
                    }
                }
                catch
                {
                    Volatile.Write(ref capturing, 0);
                    if (input == microphone) input = null;
                    microphone.Dispose();
                    throw;
                }
            }, cancellationToken);
        }
        finally { captureGate.Release(); }
    }

    public async Task StopCaptureAsync()
    {
        Volatile.Write(ref capturing, 0);
        Interlocked.Increment(ref captureGeneration);
        await captureGate.WaitAsync();
        try
        {
            var microphone = input; input = null;
            if (microphone != null) await Task.Run(() => { try { microphone.StopRecording(); } finally { microphone.Dispose(); } });
        }
        finally { captureGate.Release(); }
    }

    public void SetPlaybackDevice(int device)
    {
        Volatile.Write(ref playbackDevice, device);
        Volatile.Write(ref playbackEnabled, 1);
    }

    public void QueuePlayback(byte[] pcm)
    {
        long generation = Interlocked.Read(ref playbackGeneration);
        if (disposed || pcm.Length != 640 || Volatile.Read(ref playbackEnabled) == 0) return;
        playback.Writer.TryWrite(new(generation, (byte[])pcm.Clone()));
    }

    public Task StopPlaybackAsync()
    {
        Volatile.Write(ref playbackEnabled, 0);
        Interlocked.Increment(ref playbackGeneration);
        return Task.Run(() =>
        {
            lock (outputGate)
            {
                provider?.ClearBuffer();
                output?.Stop(); output?.Dispose(); output = null; provider = null;
                openedPlaybackDevice = int.MinValue;
            }
        });
    }

    private async Task PumpCaptureAsync()
    {
        try
        {
            await foreach (var frame in captured.Reader.ReadAllAsync(lifetime.Token))
                if (IsCapturing && frame.Generation == Interlocked.Read(ref captureGeneration))
                    try { Captured?.Invoke(frame.Pcm); }
                    catch (Exception exception) { Failed?.Invoke("Voice could not be sent: " + exception.Message); }
        }
        catch (OperationCanceledException) { }
    }

    private async Task PumpPlaybackAsync()
    {
        try
        {
            await foreach (var frame in playback.Reader.ReadAllAsync(lifetime.Token))
            {
                try
                {
                    lock (outputGate)
                    {
                        if (frame.Generation != Interlocked.Read(ref playbackGeneration) || Volatile.Read(ref playbackEnabled) == 0) continue;
                        byte[] pcm = frame.Pcm;
                        int device = Volatile.Read(ref playbackDevice);
                        if (output == null || openedPlaybackDevice != device)
                        {
                            output?.Stop(); output?.Dispose(); output = null;
                            provider = new BufferedWaveProvider(Format)
                            {
                                BufferDuration = TimeSpan.FromMilliseconds(500),
                                DiscardOnBufferOverflow = true,
                                ReadFully = true
                            };
                            output = new WaveOutEvent { DeviceNumber = device, DesiredLatency = 80, NumberOfBuffers = 3 };
                            long generation = frame.Generation;
                            output.PlaybackStopped += (_, args) =>
                            {
                                if (args.Exception != null && generation == Interlocked.Read(ref playbackGeneration) && Volatile.Read(ref playbackEnabled) != 0)
                                    Failed?.Invoke("Windows playback stopped: " + args.Exception.Message);
                            };
                            output.Init(provider); output.Play(); openedPlaybackDevice = device;
                        }
                        if (provider!.BufferedBytes + pcm.Length > provider.BufferLength) provider.ClearBuffer();
                        provider.AddSamples(pcm, 0, pcm.Length);
                    }
                }
                catch (Exception exception)
                {
                    lock (outputGate)
                    {
                        output?.Dispose(); output = null; provider = null; openedPlaybackDevice = int.MinValue;
                    }
                    Failed?.Invoke("Voice playback is unavailable: " + exception.Message);
                }
            }
        }
        catch (OperationCanceledException) { }
    }

    public async ValueTask DisposeAsync()
    {
        if (disposed) return;
        disposed = true;
        lifetime.Cancel(); captured.Writer.TryComplete(); playback.Writer.TryComplete();
        await StopCaptureAsync();
        try { await Task.WhenAll(capturePump, playbackPump).WaitAsync(TimeSpan.FromSeconds(3)); }
        catch (Exception exception) when (exception is OperationCanceledException or TimeoutException) { }
        await StopPlaybackAsync();
        lifetime.Dispose(); captureGate.Dispose();
    }

    private sealed record CaptureFrame(long Generation, byte[] Pcm);
    private sealed record PlaybackFrame(long Generation, byte[] Pcm);
}
