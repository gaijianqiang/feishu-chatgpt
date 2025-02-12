package com.zjs.feishubot.util.chatgpt;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import com.zjs.feishubot.entity.Status;
import com.zjs.feishubot.entity.gpt.Answer;
import com.zjs.feishubot.entity.gpt.Content;
import com.zjs.feishubot.entity.gpt.ErrorCode;
import com.zjs.feishubot.entity.gpt.Message;
import com.zjs.feishubot.entity.gptRequestBody.CreateConversationBody;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Data
@Slf4j
public class ChatService {

    private String account;
    private String password;
    private String accessToken;
    private int level;
    private volatile Status status;
    private Semaphore semaphore = new Semaphore(1);


    private String proxyUrl;

    private static final String LOGIN_URL = "/chatgpt/login";
    private static final String CHAT_URL = "/chatgpt/backend-api/conversation";
    private static final String LIST_URL = "/chatgpt/backend-api/conversations?offset=0&limit=20";
    private static final String GEN_TITLE_URL = "/chatgpt/backend-api/conversation/gen_title/";


    public ChatService() {
        this.status = Status.FINISHED;
    }

    public ChatService(String account, String password, String accessToken, String proxyUrl) {
        this.accessToken = accessToken;
        this.account = account;
        this.password = password;
        this.proxyUrl = proxyUrl;
        this.status = Status.FINISHED;
    }

    public boolean build() {
        if (password == null || password.equals("")) {
            log.error("账号{}密码为空", account);
            return false;
        }
        log.info("账号{}开始登录", account);
        String loginUrl = proxyUrl + LOGIN_URL;
        HashMap<String, Object> paramMap = new HashMap<>();
        paramMap.put("username", account);
        paramMap.put("password", password);

        String params = JSONUtil.toJsonPrettyStr(paramMap);
        String result = HttpUtil.post(loginUrl, params);

        JSONObject jsonObject = new JSONObject(result);

        if (jsonObject.opt("errorMessage") != null) {
            log.error("账号{}登录失败：{}", account, jsonObject.opt("errorMessage"));
            return false;
        }
        accessToken = jsonObject.optString("accessToken");
        log.info("账号{}登录成功", account);
        return true;
    }

    public String getToken() {
        return "Bearer " + accessToken;
    }

    private void chat(String content, String model, AnswerProcess process, String parentMessageId, String conversationId) throws InterruptedException {
        semaphore.acquire();
        try {
            String createConversationUrl = proxyUrl + CHAT_URL;
            UUID uuid = UUID.randomUUID();
            String messageId = uuid.toString();

            String param = CreateConversationBody.of(messageId, content, parentMessageId, conversationId, model);
            post(param, createConversationUrl, process);
        } finally {
            semaphore.release();
        }
    }

    public void newChat(String content, String model, AnswerProcess process) throws InterruptedException {
        chat(content, model, process, "", "");
    }

    public void keepChat(String content, String model, String parentMessageId, String conversationId, AnswerProcess process) throws InterruptedException {
        chat(content, model, process, parentMessageId, conversationId);
    }

    public void genTitle(String conversationId) {
        String listUrl = proxyUrl + GEN_TITLE_URL + conversationId;
        HttpResponse response = HttpRequest.get(listUrl).header("Authorization", getToken()).execute();
        log.info(response.body());
    }


    public void getConversationList() {
        String listUrl = proxyUrl + LIST_URL;
        HttpResponse response = HttpRequest.get(listUrl).header("Authorization", getToken()).execute();
        System.out.println(response.body());
    }

    private void post(String param, String urlStr, AnswerProcess process) {
        URL url = null;
        Answer parse = null;
        try {
            url = new URL(urlStr);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", getToken());
            connection.setRequestProperty("Content-Type", "application/json");
            //设置请求体
            connection.setDoOutput(true);

            try (OutputStream output = connection.getOutputStream()) {
                output.write(param.getBytes(StandardCharsets.UTF_8));
            }

            // 获取并处理响应
            int status = connection.getResponseCode();
            Reader streamReader = null;
            if (status > 299) {
                streamReader = new InputStreamReader(connection.getErrorStream());
            } else {
                streamReader = new InputStreamReader(connection.getInputStream());
            }

            BufferedReader reader = new BufferedReader(streamReader);
            String line;

            int count = 0;
            while ((line = reader.readLine()) != null) {
                if (line.length() == 0) {
                    continue;
                }

                // log.info("{}", line);

                try {
                    count++;
                    parse = parse(line);
                    if (parse == null) {
                        continue;
                    }
                    //每5次回答 才处理一次 为了防止回答太快
                    if (parse.isSuccess() && !parse.isFinished() && count % 5 != 0) {
                        continue;
                    }

                    if (parse.isSuccess() && !parse.getMessage().getAuthor().getRole().equals("assistant")) {
                        continue;
                    }

                    //异步处理
                    Answer finalParse = parse;
                    new Thread(() -> {
                        try {
                            process.process(finalParse);
                        } catch (Exception e) {
                            log.error("处理ChatGpt响应出错", e);
                            log.error(finalParse.toString());
                        }
                    }).start();
                } catch (Exception e) {
                    log.error("解析ChatGpt响应出错", e);
                    log.error(line);
                }
            }

            reader.close();
            connection.disconnect();
        } catch (Exception e) {
            log.error("请求出错", e);
        }
    }


    private Answer parse(String body) {

        Answer answer = null;

        if (body.equals("data: [DONE]")) {
            return null;
        }
        if (body.startsWith("data:")) {

            body = body.substring(body.indexOf("{"));
            answer = JSONUtil.toBean(body, Answer.class);
            answer.setSuccess(true);
            if (answer.getMessage().getStatus().equals("finished_successfully")) {
                answer.setFinished(true);
            }
            Message message = answer.getMessage();
            Content content = message.getContent();
            List<String> parts = content.getParts();
            if (parts != null) {
                String part = parts.get(0);
                answer.setAnswer(part);
            }
            if (content.getText() != null) {
                answer.setAnswer(content.getText());
            }

        } else {
            answer = new Answer();
            answer.setSuccess(false);
            JSONObject jsonObject = new JSONObject(body);
            String detail = jsonObject.optString("detail");
            if (detail != null && detail.contains("Only one message")) {
                log.warn("账号{}忙碌中", account);
                answer.setErrorCode(ErrorCode.BUSY);
                answer.setError(detail);
                return answer;
            }
            if (detail != null && detail.contains("code")) {
                JSONObject error = jsonObject.optJSONObject("detail");
                String code = (String) error.opt("code");
                if (code.equals("invalid_jwt")) {
                    answer.setErrorCode(ErrorCode.INVALID_JWT);
                } else if (code.equals("invalid_api_key")) {
                    answer.setErrorCode(ErrorCode.INVALID_API_KEY);
                } else if (code.equals("model_cap_exceeded")) {
                    answer.setErrorCode(ErrorCode.CHAT_LIMIT);
                } else {
                    log.error(body);
                    log.warn("账号{} token失效", account);
                }
                answer.setError(error.get("message"));

                return answer;
            }
            log.error("未知错误：{}", body);
            log.error("账号{}未知错误：{}", account, body);
            answer.setError(body);
        }
        return answer;
    }
}
