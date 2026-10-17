package com.zero.ai.agentstudy.day17codeagent.analysis;

/** 符号类型。 */
public enum SymbolKind {
    CLASS, INTERFACE, ENUM, RECORD, METHOD, CONSTRUCTOR, FIELD;

    /** 是否为类型级符号。 */
    public boolean isType() {
        return this == CLASS || this == INTERFACE || this == ENUM || this == RECORD;
    }

    /** 中文名，给 RepoMap 用。 */
    public String display() {
        return switch (this) {
            case CLASS -> "class";
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            case METHOD -> "method";
            case CONSTRUCTOR -> "ctor";
            case FIELD -> "field";
        };
    }
}
