package com.zjs.feishubot.entity.gpt;

import java.util.HashMap;
import java.util.Map;

public class ErrorCode {
    public static final int INVALID_JWT = 1;
    public static final int INVALID_API_KEY = 3;

    public static final int CHAT_LIMIT = 4;


    public static final int BUSY = 2;

    public static final Map<Integer, String> map = new HashMap<>();

    static {
        map.put(INVALID_JWT, "无效的access token");
        map.put(BUSY, "账号繁忙中");
        map.put(INVALID_API_KEY, "无效的api key");
        map.put(CHAT_LIMIT, "4.0接口被限制了");
    }


}
