package com.qianliusi.slothim.enums;

/**
 * @author qianliusi
 */
public enum MsgTypeEnum implements EnumService<String> {
    token("token", "token消息"),
    match("match", "match消息"),
    matched("matched", "matched消息"),
    chat("chat", "chat消息"),
    join("join", "join消息"),
    leave("leave", "leave消息"),
    ;

    private final String code;
    private final String desc;


    MsgTypeEnum(String code, String desc) {
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
