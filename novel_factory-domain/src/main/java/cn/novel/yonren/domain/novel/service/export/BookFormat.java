package cn.novel.yonren.domain.novel.service.export;


public enum BookFormat {

    TXT("txt", "txt"),

    MD("md", "md"),

    DOCX("docx", "docx");

    private final String param;
    private final String ext;

    BookFormat(String param, String ext) {
        this.param = param;
        this.ext = ext;
    }

    public String getParam() {
        return param;
    }

    public String getExt() {
        return ext;
    }

    /**
     * URL 参数解析：非法值返回 null（控制器转 400），**不做默认值兜底**——
     * 格式打错应当显式报错，而不是静默导成另一种格式让用户拿到意外产物
     */
    public static BookFormat fromParam(String param) {
        if (param == null) {
            return null;
        }
        String trimmed = param.trim();
        for (BookFormat format : values()) {
            if (format.param.equalsIgnoreCase(trimmed)) {
                return format;
            }
        }
        return null;
    }
}
