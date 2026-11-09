using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace Relay.Hub.Core;

internal static class HubIdentity
{
    public static X509Certificate2 Load(string directory)
    {
        var publicPath = Path.Combine(directory, "identity.cer");
        var keyName = "RelayHub-" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(directory).ToUpperInvariant())))[..32];
        if (File.Exists(publicPath))
        {
            using var publicCert = X509CertificateLoader.LoadCertificateFromFile(publicPath);
            using var key = CngKey.Open(keyName, CngProvider.MicrosoftSoftwareKeyStorageProvider);
            using var rsa = new RSACng(key);
            return publicCert.CopyWithPrivateKey(rsa);
        }
        if (CngKey.Exists(keyName, CngProvider.MicrosoftSoftwareKeyStorageProvider))
            throw new InvalidOperationException("The hub public certificate is missing but its identity key exists. Restore identity.cer from backup rather than silently replacing the hub identity.");
        var creation = new CngKeyCreationParameters
        {
            Provider = CngProvider.MicrosoftSoftwareKeyStorageProvider,
            ExportPolicy = CngExportPolicies.None,
            KeyUsage = CngKeyUsages.Signing,
        };
        creation.Parameters.Add(new CngProperty("Length", BitConverter.GetBytes(2048), CngPropertyOptions.None));
        using var privateKey = CngKey.Create(CngAlgorithm.Rsa, keyName, creation);
        using var signing = new RSACng(privateKey);
        var request = new CertificateRequest("CN=Relay local desktop hub", signing, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new("1.3.6.1.5.5.7.3.1"), new("1.3.6.1.5.5.7.3.2") }, false));
        var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(10));
        File.WriteAllBytes(publicPath, cert.Export(X509ContentType.Cert));
        return cert;
    }

    public static string Fingerprint(X509Certificate cert) => Convert.ToHexString(SHA256.HashData(cert.GetRawCertData()));

    public static bool ValidPeer(X509Certificate? certificate)
    {
        if (certificate is null) return false;
        try
        {
            using var cert = X509CertificateLoader.LoadCertificate(certificate.GetRawCertData());
            using var rsa = cert.GetRSAPublicKey();
            return cert.NotBefore.ToUniversalTime() <= DateTime.UtcNow && cert.NotAfter.ToUniversalTime() > DateTime.UtcNow && rsa?.KeySize >= 2048;
        }
        catch (CryptographicException) { return false; }
    }
}
