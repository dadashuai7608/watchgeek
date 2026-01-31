package com.AdbService;

// ========== 基础导入 ==========
import android.content.Context;
import android.os.Build;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

// ========== Spongy Castle 相关导入（核心：包名改为 org.spongycastle） ==========
import org.spongycastle.asn1.x500.X500Name;
import org.spongycastle.asn1.x509.KeyUsage;
import org.spongycastle.asn1.x509.SubjectKeyIdentifier;
import org.spongycastle.cert.CertIOException;
import org.spongycastle.cert.X509CertificateHolder;
import org.spongycastle.cert.X509v3CertificateBuilder;
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter;
import org.spongycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.operator.ContentSigner;
import org.spongycastle.operator.OperatorCreationException;
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder;

// ========== 类定义 ==========
public class AdbConnectionManager extends AbsAdbConnectionManager {
    // 单例实例（线程安全）
    private static volatile AdbConnectionManager INSTANCE;

    // 证书/私钥文件名称
    private static final String CERT_FILE_NAME = "adb_cert.pem";
    private static final String PRIVATE_KEY_FILE_NAME = "adb_private.key";

    // Spongy Castle 提供器（全局初始化，包名兼容）
    private static final BouncyCastleProvider SC_PROVIDER = new BouncyCastleProvider();

    // ADB 认证核心数据
    private PrivateKey mPrivateKey;
    private Certificate mCertificate;

    // ============== 单例获取方法 ==============
    public static AdbConnectionManager getInstance(@NonNull Context context) throws Exception {
        if (INSTANCE == null) {
            synchronized (AdbConnectionManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new AdbConnectionManager(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    // ============== 私有构造方法（初始化密钥/证书） ==============
    private AdbConnectionManager(@NonNull Context context) throws Exception {
        // 适配设备 Android API 版本
        setApi(Build.VERSION.SDK_INT);

        // 第一步：从文件加载已持久化的密钥/证书
        mPrivateKey = readPrivateKeyFromFile(context);
        mCertificate = readCertificateFromFile(context);

        // 第二步：若加载失败，生成新的「标准 RSA 密钥对 + 完整 X.509 证书」
        if (mPrivateKey == null || mCertificate == null) {
            generateStandardKeyPairAndCert();
            // 持久化到文件，下次启动直接加载
            writePrivateKeyToFile(context, mPrivateKey);
            writeCertificateToFile(context, mCertificate);

            // 兜底校验：确保生成成功
            if (mPrivateKey == null || mCertificate == null) {
                throw new Exception("密钥/证书生成失败，无法完成 ADB 认证");
            }
        }
    }

    // ============== 核心：生成标准 RSA 密钥对 + 完整 X.509 v3 证书（适配 Spongy Castle 1.58.0.0） ==============
    private void generateStandardKeyPairAndCert() throws NoSuchAlgorithmException, OperatorCreationException, CertIOException, CertificateException {
        // 1. 生成 2048 位 RSA 密钥对（ADB 标准，指定 Spongy Castle 提供器）
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", SC_PROVIDER);
        keyPairGenerator.initialize(2048, new SecureRandom());
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        mPrivateKey = keyPair.getPrivate();
        PublicKey publicKey = keyPair.getPublic();

        // 2. 生成标准 X.509 v3 自签名证书（Spongy Castle 标准 API）
        // 2.1 证书基础信息
        Date startDate = new Date(); // 生效时间：当前时间
        Date endDate = new Date(System.currentTimeMillis() + 365L * 24 * 3600 * 1000); // 有效期：1 年
        BigInteger serialNumber = BigInteger.valueOf(System.currentTimeMillis()); // 唯一序列号（时间戳保证不重复）
        X500Name issuerName = new X500Name("CN=AdbService, O=AdbApp, C=CN"); // 签发者信息（自定义）
        X500Name subjectName = issuerName; // 自签名证书：签发者 = 使用者

        // 2.2 构建证书（Spongy Castle 1.58.0.0 支持的构造方法）
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuerName,
                serialNumber,
                startDate,
                endDate,
                subjectName,
                publicKey
        );

        // 2.3 初始化证书扩展工具（生成扩展字段必需）
        JcaX509ExtensionUtils extensionUtils = new JcaX509ExtensionUtils();

        // 2.4 添加必要的证书扩展（符合 X.509 v3 标准，ADB 认证必需）
        // 2.4.1 密钥用法：数字签名 + 密钥加密（ADB 核心需求）
        KeyUsage keyUsage = new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment);
        certBuilder.addExtension(org.spongycastle.asn1.x509.Extension.keyUsage, true, keyUsage);

        // 2.4.2 主体密钥标识符（唯一标识证书公钥，避免冲突）
        SubjectKeyIdentifier subjectKeyIdentifier = extensionUtils.createSubjectKeyIdentifier(publicKey);
        certBuilder.addExtension(org.spongycastle.asn1.x509.Extension.subjectKeyIdentifier, false, subjectKeyIdentifier);

        // 2.5 用私钥签名证书（签名算法：SHA512withRSA，ADB 完全支持）
        ContentSigner signer = new JcaContentSignerBuilder("SHA512withRSA")
                .setProvider(SC_PROVIDER)
                .build(mPrivateKey);

        // 2.6 生成最终证书（转换为 Java 标准 X509Certificate 类型，无兼容性问题）
        X509CertificateHolder certHolder = certBuilder.build(signer);
        mCertificate = new JcaX509CertificateConverter()
                .setProvider(SC_PROVIDER)
                .getCertificate(certHolder);
    }

    // ============== 实现 AbsAdbConnectionManager 抽象方法 ==============
    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        if (mPrivateKey == null) {
            try {
                generateStandardKeyPairAndCert();
            } catch (Exception e) {
                throw new RuntimeException("私钥为 null 且重新生成失败", e);
            }
        }
        return mPrivateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        if (mCertificate == null) {
            try {
                generateStandardKeyPairAndCert();
            } catch (Exception e) {
                throw new RuntimeException("证书为 null 且重新生成失败", e);
            }
        }
        return mCertificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return Build.MODEL + "_AdbService";
    }

    // ============== 密钥/证书文件读写（适配 Spongy Castle，无错误） ==============
    @Nullable
    private PrivateKey readPrivateKeyFromFile(@NonNull Context context)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        File privateKeyFile = new File(context.getFilesDir(), PRIVATE_KEY_FILE_NAME);
        if (!privateKeyFile.exists()) return null;

        byte[] fileBytes = new byte[(int) privateKeyFile.length()];
        try (InputStream is = new FileInputStream(privateKeyFile)) {
            is.read(fileBytes);
        }

        byte[] privKeyBytes;
        try {
            privKeyBytes = Base64.decode(new String(fileBytes, StandardCharsets.UTF_8), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            // 兼容旧格式，直接使用原始字节
            privKeyBytes = fileBytes;
        }

        KeyFactory keyFactory = KeyFactory.getInstance("RSA", SC_PROVIDER);
        EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privKeyBytes);
        return keyFactory.generatePrivate(privateKeySpec);
    }

    private void writePrivateKeyToFile(@NonNull Context context, @NonNull PrivateKey privateKey)
            throws IOException {
        File privateKeyFile = new File(context.getFilesDir(), PRIVATE_KEY_FILE_NAME);

        String base64PrivateKey = Base64.encodeToString(privateKey.getEncoded(), Base64.DEFAULT);
        try (OutputStream os = new FileOutputStream(privateKeyFile)) {
            os.write(base64PrivateKey.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Nullable
    private Certificate readCertificateFromFile(@NonNull Context context)
            throws IOException, CertificateException {
        File certFile = new File(context.getFilesDir(), CERT_FILE_NAME);
        if (!certFile.exists()) return null;

        try (InputStream is = new FileInputStream(certFile)) {
            byte[] fileBytes = new byte[(int) certFile.length()];
            is.read(fileBytes);

            byte[] certBytes = Base64.decode(fileBytes, Base64.DEFAULT);

            CertificateFactory cf = CertificateFactory.getInstance("X.509", SC_PROVIDER);
            return cf.generateCertificate(new ByteArrayInputStream(certBytes));
        }
    }

    private void writeCertificateToFile(@NonNull Context context, @NonNull Certificate certificate)
            throws IOException, CertificateEncodingException {
        File certFile = new File(context.getFilesDir(), CERT_FILE_NAME);

        String base64Cert = Base64.encodeToString(certificate.getEncoded(), Base64.DEFAULT);
        try (OutputStream os = new FileOutputStream(certFile)) {
            os.write(base64Cert.getBytes(StandardCharsets.UTF_8));
        }
    }
}