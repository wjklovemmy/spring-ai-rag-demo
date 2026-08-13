package com.example.springairagdemo.security;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 知识库成员角色（数据权限等级）：
 * VIEWER(问答/检索) &lt; EDITOR(上传/删除文档) &lt; OWNER(成员授权/删除知识库)
 */
public enum KbRole {

    VIEWER(1, "查看者"),
    EDITOR(2, "编辑者"),
    OWNER(3, "所有者");

    private final int level;
    private final String label;

    KbRole(int level, String label) {
        this.level = level;
        this.label = label;
    }

    public int level() {
        return level;
    }

    public String label() {
        return label;
    }

    /** 当前角色是否满足所需最低角色 */
    public boolean satisfies(KbRole required) {
        return this.level >= required.level;
    }

    public static KbRole fromString(String role) {
        if (role == null) {
            return null;
        }
        for (KbRole r : values()) {
            if (r.name().equalsIgnoreCase(role)) {
                return r;
            }
        }
        return null;
    }
}
