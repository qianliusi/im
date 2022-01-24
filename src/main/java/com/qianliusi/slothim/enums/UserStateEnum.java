package com.qianliusi.slothim.enums;

/**
 * @author qianliusi
 */
public enum UserStateEnum implements EnumService<String> {
    idle("idle", "未配对"),
    matching("matching", "配对中"),
    matched("matched", "已配对"),
    ;

    private final String code;
    private final String desc;


    UserStateEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String desc() {
        return desc;
    }
}
