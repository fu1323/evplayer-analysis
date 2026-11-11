package xin.chunming;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;


public final class BjCloudTool {
    private static final String PREFIX = "bjcloudvod://";
    private static final int TRAILER_SIZE = 112;
    private static final int AES_BLOCK_SIZE = 16;
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final long WASM_OFFSET_PERIOD = 1L << 32;

    private BjCloudTool() {}

    public static void main(String[] args) {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(usage());
            return;
        }
        try {
            if (args.length == 2 && args[0].equals("url")) {
                System.out.println(decodeUrl(args[1]));
            } else if (args.length == 3 && args[0].equals("ev2")) {
                Path output = Path.of(args[2]);
                long written = decryptEv2(Path.of(args[1]), output);
                System.out.printf("已写入 %s（%d 字节）%n", output, written);
            } else {
                System.err.println(usage());
                System.exit(2);
            }
        } catch (FileAlreadyExistsException e) {
            System.err.println("输出已存在，请使用新的文件名：" + e.getFile());
            System.exit(1);
        } catch (IllegalArgumentException | IOException | GeneralSecurityException e) {
            System.err.println("处理失败：" + e.getMessage());
            System.exit(1);
        }
    }

    private static String usage() {
        return "java -jar BjCloudTool.jar url \"bjcloudvod://...\"\n"
                + "java -jar BjCloudTool.jar ev2 <输入.ev2> <输出.flv>";
    }

    public static String decodeUrl(String wrapped) {
        if (!wrapped.startsWith(PREFIX)) {
            throw new IllegalArgumentException("URL 需要以 " + PREFIX + " 开头");
        }
        String encoded = wrapped.substring(PREFIX.length()).replace('-', '+').replace('_', '/');
        byte[] bytes;
        try {
            // The JDK also accepts Base64 with omitted trailing '=' characters.
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 中的 Base64 内容无效", e);
        }
        if (bytes.length < 2) {
            throw new IllegalArgumentException("URL 缺少种子或正文");
        }
        int seed = Byte.toUnsignedInt(bytes[0]) % 8;
        StringBuilder result = new StringBuilder(bytes.length - 1);
        for (int i = 0; i < bytes.length - 1; i++) {
            int shift = (i % 4) * seed + (i % 3) + 1;
            result.append((char) (Byte.toUnsignedInt(bytes[i + 1]) - shift));
        }
        return result.toString();
    }

    public static long decryptEv2(Path input, Path output) throws IOException, GeneralSecurityException {
        Path target = output.toAbsolutePath();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        try (RandomAccessFile file = new RandomAccessFile(input.toFile(), "r")) {
            long bodyLength = file.length() - TRAILER_SIZE;
            if (bodyLength < 13) {
                throw new IOException("文件太短，无法包含 FLV 文件头和 112 字节的 EV2 尾部");
            }
            file.seek(bodyLength);
            byte[] trailer = new byte[TRAILER_SIZE];
            file.readFully(trailer);
            byte[] mask = recoverMask(bodyLength, trailer);

            file.seek(0);
            byte[] firstBytes = new byte[9];
            file.readFully(firstBytes);
            xorChunk(firstBytes, firstBytes.length, 0, mask);
            checkFlvHeader(firstBytes, bodyLength);

            Path temp = Files.createTempFile(target.getParent(), ".bjcloud-", ".part");
            try {
                file.seek(0);
                byte[] block = new byte[BUFFER_SIZE];
                try (OutputStream out = Files.newOutputStream(temp)) {
                    long offset = 0;
                    while (offset < bodyLength) {
                        int count = (int) Math.min(block.length, bodyLength - offset);
                        file.readFully(block, 0, count);
                        xorChunk(block, count, offset, mask);
                        out.write(block, 0, count);
                        offset += count;
                    }
                }
                if (file.length() != bodyLength + TRAILER_SIZE) {
                    throw new IOException("读取期间输入文件的长度发生变化");
                }
                publish(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
            return bodyLength;
        } catch (EOFException e) {
            throw new IOException("输入提前结束，文件可能在读取期间被截断", e);
        }
    }

    private static void publish(Path temp, Path target) throws IOException {
        // A hard link publishes a complete file and claims a new name in one step.
        try {
            Files.createLink(target, temp);
            return;
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (UnsupportedOperationException | FileSystemException e) {
            // Some removable or network filesystems only support ordinary copies.
        }
        OutputStream destination = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try (destination) {
            Files.copy(temp, destination);
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    private static byte[] recoverMask(long bodyLength, byte[] trailer) throws GeneralSecurityException {
        byte[] asciiLength = Long.toString(bodyLength).getBytes(StandardCharsets.US_ASCII);
        byte[] digest = MessageDigest.getInstance("MD5").digest(asciiLength);
        byte[] key = new byte[digest.length];
        for (int i = 0; i < digest.length; i++) {
            key[i] = digest[digest.length - 1 - i];
        }
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] decoded = aes.doFinal(trailer);
        // The original plugin strips a valid suffix and keeps all 112 bytes otherwise.
        int pad = Byte.toUnsignedInt(decoded[decoded.length - 1]);
        if (pad >= 1 && pad <= AES_BLOCK_SIZE) {
            int end = decoded.length - pad;
            for (int i = end; i < decoded.length; i++) {
                if (Byte.toUnsignedInt(decoded[i]) != pad) {
                    return decoded;
                }
            }
            return Arrays.copyOf(decoded, end);
        }
        return decoded;
    }

    static void xorChunk(byte[] data, int length, long fileOffset, byte[] mask) {
        // WASM adds the local index to a 32-bit byte offset before unsigned remainder.
        long position = fileOffset & 0xffff_ffffL;
        int maskIndex = (int) (position % mask.length);
        for (int i = 0; i < length; i++) {
            data[i] ^= mask[maskIndex];
            maskIndex++;
            position++;
            if (position == WASM_OFFSET_PERIOD) {
                position = 0;
                maskIndex = 0;
            } else if (maskIndex == mask.length) {
                maskIndex = 0;
            }
        }
    }

    private static void checkFlvHeader(byte[] header, long bodyLength) throws IOException {
        long dataOffset = 0;
        for (int i = 5; i < 9; i++) {
            dataOffset = (dataOffset << 8) | Byte.toUnsignedInt(header[i]);
        }
        if (header[0] != 'F' || header[1] != 'L' || header[2] != 'V' || header[3] != 1
                || dataOffset < 9 || dataOffset > bodyLength - 4) {
            throw new IOException("按 EV2 1.8.11 还原后的 FLV 文件头无效，请核对文件完整性与格式版本");
        }
    }
}
