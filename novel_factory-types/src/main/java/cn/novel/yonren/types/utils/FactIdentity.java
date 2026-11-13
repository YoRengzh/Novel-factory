package cn.novel.yonren.types.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 事实引用身份：绑定来源账本、章号、正文版本、主体、状态与引文；不含模型自由文本。 */
public final class FactIdentity {
    private FactIdentity() {}

    public static String of(String ledger, Integer chapterNo, String manuscriptHash,
                            String subject, String status, String evidence) {
        String key = ledger + "\n" + chapterNo + "\n" + manuscriptHash + "\n" + subject + "\n" + status + "\n" + evidence;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "fact-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}