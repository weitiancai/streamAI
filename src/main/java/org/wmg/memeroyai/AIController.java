package org.wmg.memeroyai;

/**
 * @author wmg
 * @date 2025/3/16
 */
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.RequestBody;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.wmg.memeroyai.tool.ChatCompletionResultDTO;
import org.wmg.memeroyai.tool.ChatMemory;
import org.wmg.memeroyai.tool.QueryDTO;
import reactor.core.publisher.Flux;

import javax.management.Query;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@Slf4j
public class AIController {
    private final ChatMemory memory = new ChatMemory();
    private final OkHttpClient client = new OkHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

//    curl -X POST "http://10.199.137.235:31971/mudgate/api/model/aliyun/v1/chat/completions" \
//            -H "Content-Type: application/json" \
//            -d '{
//            "appId": "7bdf76730b274c29ccd98d78462fdaad",
//            "model": "deepseek-v3",
//            "stream": true,
//            "messages": [
//    {
//        "role": "user",
//            "content": "你好？"
//    }
//  ]
//}'

    SseEmitter sseEmitter;

    @PostMapping("/ask")
    public SseEmitter streamAsk(@org.springframework.web.bind.annotation.RequestBody QueryDTO queryDTO) throws Exception {
        String question = queryDTO.getQuestion();
        String sessionId = queryDTO.getSessionId();
        String decodedQuestion = URLDecoder.decode(question, StandardCharsets.UTF_8);
        memory.addHistory(sessionId, "user", decodedQuestion);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("appId", "7bdf76730b274c29ccd98d78462fdaad");
        requestBody.put("model", "deepseek-v3");
        requestBody.put("stream", true);
        requestBody.put("messages", memory.getHistory(sessionId));
        sseEmitter = new SseEmitter(5 * 60 * 1000L);

        doSendFluxMessage(JSONObject.toJSONString(requestBody));
        return sseEmitter;
    }

    /**
     * 正真的发送逻辑
     *
     */
    @SneakyThrows
    public void doSendFluxMessage(String requestJson) {
        // 拿到提示词后，调用大模型接口
        Flux<String> resultFlux = postAiChatCompletions(requestJson);

        resultFlux.takeUntil(this::isDone)
                .subscribe(
                        this::handleMessage,
                        this::thrownException,
                        this::handleOnComplete
                );
    }

    private void thrownException(Throwable throwable) {
        throw new UnsupportedOperationException();
    }

    StringBuilder responseTest = new StringBuilder();
    protected void handleMessage(String originResultText) {
        if (isDone(originResultText)) {
            log.info("收到结束标示，发送结束");
            return;
        }

        ChatCompletionResultDTO chatCompletionResult = JSONObject.parseObject(originResultText, ChatCompletionResultDTO.class);
        String message = chatCompletionResult.extractAiRealResult().orElse(null);
        // 收集起来，便于存储完整的聊天记录
        this.responseTest.append(message);
        try {
            sseEmitter.send(message);
        } catch (IOException e) {
            log.error("发送数据失败, 发送的消息: {}", message, e);
        }
    }

    protected boolean isDone(String message) {
        return "[DONE]".equals(message);
    }

    protected void sendOnComplete() {
        sseEmitter.complete();
    }

    protected void handleOnComplete() {
        log.info("完成正流式调用");
    }


    public static Flux<String> postAiChatCompletions(String jsonString) {
        WebClient webClient = WebClient.create();

        // 这里我们需要转为json字符串，因为我们剔除了WebFlux中json依赖（jackson），使用对象会报错，故直接转为json
        // 构建 POST 请求并指定返回类型为 Flux<String>
        return webClient.post()
                // 测试环境
                .uri("http://10.199.137.235:31971/mudgate/api/model/aliyun/v1/chat/completions")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .syncBody(jsonString)
                .retrieve()
                .bodyToFlux(String.class);
    }


    private @NotNull StreamingResponseBody streamReturnBody(Request request, String sessionId) {
        return outputStream -> {
            StringBuilder fullResponse = new StringBuilder();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorMsg = "API请求失败: " + response.code();
                    try {
                        outputStream.write(errorMsg.getBytes());
                    } catch (IOException e) {
                        // 双重防护：客户端可能已断开
                        log.error("客户端写入失败: {}", getSafeErrorMessage(e));
                    }
                    return;
                }

                try (InputStream inputStream = response.body().byteStream();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("[DONE]".equals(line.trim())) {
                            memory.addHistory(sessionId, "assistant", fullResponse.toString());
                            return;
                        }

                        if (line.startsWith("data:")) {
                            line = line.substring(5).trim();
                        } else if (line.isEmpty()) {
                            continue;
                        }

                        // 处理单条事件并更新 fullResponse
                        processSSEEvent(line, outputStream, fullResponse);
                    }
                } catch (IOException e) {
                    log.error("流读取失败: {}", getSafeErrorMessage(e));
                }
            } catch (IOException e) {
                log.error("API调用失败: {}", getSafeErrorMessage(e));
                try {
                    outputStream.write(("服务错误: " + getSafeErrorMessage(e)).getBytes());
                } catch (IOException ex) {
                    log.error("错误信息回传失败: {}", getSafeErrorMessage(ex));
                }
            } catch (Exception e) {
                log.error("未知异常: {}", getSafeErrorMessage(e));
            }
        };
    }

    // 安全获取异常描述（兼容空异常和空消息）
    private String getSafeErrorMessage(Throwable e) {
        if (e == null) return "Unknown error (exception is null)";

        Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(e);
        String message = (rootCause != null) ? rootCause.getMessage() : e.getMessage();

        return (message != null) ? message : "No error message available";
    }

    private void processSSEEvent(String eventData, OutputStream outputStream, StringBuilder fullResponse) {
        try (StringReader reader = new StringReader(eventData);
             JsonParser jsonParser = mapper.getFactory().createParser(reader)) {

            JsonToken token = jsonParser.nextToken();
            if (token == null) return;

            while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = jsonParser.getCurrentName();
                if ("choices".equals(fieldName)) {
                    jsonParser.nextToken(); // 进入数组
                    while (jsonParser.nextToken() != JsonToken.END_ARRAY) {
                        JsonToken deltaToken = jsonParser.nextToken();
                        if (deltaToken == JsonToken.START_OBJECT) {
                            while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
                                String deltaField = jsonParser.getCurrentName();
                                if ("content".equals(deltaField)) {
                                    jsonParser.nextToken();
                                    String content = jsonParser.getValueAsString();

                                    // 修复：检查 content 是否为空
                                    if (content != null && !content.isEmpty()) {
                                        // 逐字符推送并累积完整响应
                                        for (char c : content.toCharArray()) {
                                            outputStream.write(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                                            outputStream.flush();
                                            fullResponse.append(c);
                                        }
                                    } else {
                                        log.debug("content 字段为空或 null");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("SSE事件解析失败 | 原始数据: {}", eventData, e);
        }
    }


//    @PostMapping("/ask")
//    public StreamingResponseBody streamAsk(@org.springframework.web.bind.annotation.RequestBody QueryDTO queryDTO) throws Exception {
//    @PostMapping("/ask")
//    public StreamingResponseBody streamAsk(@org.springframework.web.bind.annotation.RequestBody QueryDTO queryDTO) throws Exception {
//        Request request = toRequest(queryDTO);
//
//        return outputStream -> {
//            try (Response response = client.newCall(request).execute()) {
//                if (!response.isSuccessful()) {
//                    outputStream.write(("API请求失败: " + response.code()).getBytes());
//                    return;
//                }
//
//                // 关键修改：直接操作原始字节流，禁用所有缓冲
//                InputStream inputStream = response.body().byteStream();
//                JsonFactory jsonFactory = new JsonFactory();
//                JsonParser jsonParser = jsonFactory.createParser(inputStream);
//
//                // 流式解析JSON事件
//                while (!jsonParser.isClosed()) {
//                    JsonToken token = jsonParser.nextToken();
//
//                    // 1. 定位到 "choices" 数组
//                    if (token == JsonToken.FIELD_NAME && "choices".equals(jsonParser.getCurrentName())) {
//                        jsonParser.nextToken(); // 进入数组
//
//                        // 2. 遍历 choices 数组
//                        while (jsonParser.nextToken() != JsonToken.END_ARRAY) {
//                            // 3. 定位到 "delta" 对象
//                            if (jsonParser.currentToken() == JsonToken.START_OBJECT) {
//                                while (jsonParser.nextToken() != JsonToken.END_OBJECT) {
//                                    // 4. 提取 "content" 字段值
//                                    if (jsonParser.currentToken() == JsonToken.FIELD_NAME && "content".equals(jsonParser.getCurrentName())) {
//                                        jsonParser.nextToken(); // 移动到值
//                                        String content = jsonParser.getValueAsString();
//
//                                        // 逐字符推送
//                                        for (char c : content.toCharArray()) {
//                                            outputStream.write(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
//                                            outputStream.flush(); // 强制实时发送
////                                            Thread.sleep(10); // 可选：控制输出速度（按需调整）
//                                        }
//                                    }
//                                }
//                            }
//                        }
//                    }
//                }
//            }
//        };
//    }

    private @NotNull Request toRequest(QueryDTO queryDTO) throws JsonProcessingException {
        String question = queryDTO.getQuestion();
        String sessionId = queryDTO.getSessionId();
        String decodedQuestion = URLDecoder.decode(question, StandardCharsets.UTF_8);
        memory.addHistory(sessionId, "user", decodedQuestion);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("appId", "7bdf76730b274c29ccd98d78462fdaad");
        requestBody.put("model", "deepseek-v3");
        requestBody.put("stream", true);
        requestBody.put("messages", memory.getHistory(sessionId));

        Request request = new Request.Builder()
                .url("http://10.199.137.235:31971/mudgate/api/model/aliyun/v1/chat/completions")
                .post(RequestBody.create(mapper.writeValueAsString(requestBody), MediaType.parse("application/json")))
                .build();
        return request;
    }


//    @PostMapping("/ask")
//    public StreamingResponseBody streamAsk(@org.springframework.web.bind.annotation.RequestBody QueryDTO queryDTO) throws Exception {
//        String question = queryDTO.getQuestion();
//        String sessionId = queryDTO.getSessionId();
//        String decodedQuestion = URLDecoder.decode(question, StandardCharsets.UTF_8);
//        memory.addHistory(sessionId, "user", decodedQuestion);
//
//        Map<String, Object> requestBody = new HashMap<>();
//        requestBody.put("appId", "7bdf76730b274c29ccd98d78462fdaad");
//        requestBody.put("model", "deepseek-v3");
//        requestBody.put("stream", true);
//        requestBody.put("messages", memory.getHistory(sessionId));
//
//        Request request = new Request.Builder()
//                .url("http://10.199.137.235:31971/mudgate/api/model/aliyun/v1/chat/completions")
//                .post(RequestBody.create(mapper.writeValueAsString(requestBody), MediaType.parse("application/json")))
//                .build();
//
//        return outputStream -> {
//            try (Response response = client.newCall(request).execute()) {
//                if (!response.isSuccessful()) {
//                    outputStream.write(("API请求失败: " + response.code()).getBytes());
//                    return;
//                }
//
//                try (InputStream inputStream = response.body().byteStream()) {
//                    StringBuilder buffer = new StringBuilder();
//                    int readByte;
//                    while ((readByte = inputStream.read()) != -1) { // 逐字节读取
//                        buffer.append((char) readByte);
//                        // 这里绕过缓冲就行
//
//
//                        // 检查是否读到换行符（假设上游API用 '\n' 分隔事件）
//                        if (readByte == '\n') {
//                            String line = buffer.toString().trim();
//                            buffer.setLength(0); // 清空缓冲区
//
//                            processLine(line, outputStream, sessionId); // 处理单行数据
//                        }
//                    }
//                }
//            } catch (IOException e) {
//                System.err.println("流式传输中断: " + e.getMessage());
//            }
//        };
//    }

    // 辅助方法：处理单行数据并逐字符推送
    private void processLine(String line, OutputStream outputStream, String sessionId) {
        if (line.equals("[DONE]")) {
            // 处理完成信号
            return;
        }

        if (line.startsWith("data:")) {
            line = line.substring(5).trim();
        }

        if (line.isEmpty()) return;

        try {
            Map<String, Object> responseMap = mapper.readValue(line, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
            if (!choices.isEmpty()) {
                Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                String content = (String) delta.get("content");

                if (content != null) {
                    // 关键修改：逐字符推送
                    for (char c : content.toCharArray()) {
                        outputStream.write(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                        outputStream.flush(); // 立即发送到客户端
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("解析失败: " + line);
        }
    }
//    public Flux<String> streamAsk(@org.springframework.web.bind.annotation.RequestBody QueryDTO queryDTO) throws Exception {
//        String question = queryDTO.getQuestion();
//        String sessionId = queryDTO.getSessionId();
//        // 解码URL参数
//        String decodedQuestion = URLDecoder.decode(question, StandardCharsets.UTF_8);
//        // 添加用户问题到历史
//        memory.addHistory(sessionId, "user", decodedQuestion);
//
//        // 构建API请求体
//        Map<String, Object> requestBody = new HashMap<>();
//        requestBody.put("appId", "7bdf76730b274c29ccd98d78462fdaad");
//        requestBody.put("model", "deepseek-v3");
//        requestBody.put("stream", true);
//        requestBody.put("messages", memory.getHistory(sessionId));
//
//        Request request = new Request.Builder()
//                .url("http://10.199.137.235:31971/mudgate/api/model/aliyun/v1/chat/completions")
//                .post(RequestBody.create(mapper.writeValueAsString(requestBody), JSON))
//                .build();
//
////        return returnLineResult(request, sessionId);
//
//
//        return Flux.create(emitter -> {
//            try (Response response = client.newCall(request).execute()) {
//                if (!response.isSuccessful()) {
//                    emitter.error(new RuntimeException("API请求失败: " + response.code()));
//                    return;
//                }
//
//                try (InputStream inputStream = response.body().byteStream();
//                     InputStreamReader reader = new InputStreamReader(inputStream);
//                     BufferedReader bufferedReader = new BufferedReader(reader)) {
//
//                    String line;
//                    while ((line = bufferedReader.readLine()) != null) {
//                        if (line.equals("[DONE]")) {
//                            memory.addHistory(sessionId, "assistant", emitter.toString());
//                            emitter.complete();
//                            break;
//                        }
//
//                        if (line.startsWith("data:")) {
//                            line = line.substring(5).trim();
//                        }
//
//                        if (line.isEmpty()) {
//                            continue;
//                        }
//
//                        try {
//                            Map<String, Object> responseMap = mapper.readValue(line, Map.class);
//                            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
//                            if (!choices.isEmpty()) {
//                                Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
//                                String content = (String) delta.get("content");
//
//                                if (content != null) {
//                                    for (char c : content.toCharArray()) {
//                                        emitter.next(String.valueOf(c));
//                                    }
//                                }
//                            }
//                        } catch (IOException e) {
//                            emitter.error(new RuntimeException("JSON 解析失败: " + e.getMessage()));
//                        }
//                    }
//                } catch (Exception e) {
//                    emitter.error(new RuntimeException("错误: " + e.getMessage()));
//                }
//            } catch (IOException e) {
//                emitter.error(new RuntimeException("API请求失败: " + e.getMessage()));
//            }
//        });
//    }

    private @NotNull StreamingResponseBody returnLineResult(Request request, String sessionId) {
        return outputStream -> {
            StringBuilder fullResponse = new StringBuilder();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    outputStream.write(("API请求失败: " + response.code()).getBytes());
                    return;
                }

                try (InputStream inputStream = response.body().byteStream();
                     InputStreamReader reader = new InputStreamReader(inputStream);
                     BufferedReader bufferedReader = new BufferedReader(reader)) {

                    String line;
                    while ((line = bufferedReader.readLine()) != null) {
                        // 检查是否结束
                        if (line.equals("[DONE]")) {
                            memory.addHistory(sessionId, "assistant", fullResponse.toString());
                            break;
                        }

                        // 去掉 "data:" 前缀
                        if (line.startsWith("data:")) {
                            line = line.substring(5).trim();
                        }

                        // 如果行是空的，跳过
                        if (line.isEmpty()) {
                            continue;
                        }

                        // 解析JSON响应
                        try {
                            Map<String, Object> responseMap = mapper.readValue(line, Map.class);
                            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
                            if (!choices.isEmpty()) {
                                Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                                String content = (String) delta.get("content");

                                if (content != null) {
                                    // 流式输出内容
                                    try {
                                        outputStream.write(content.getBytes());
                                        outputStream.flush();
                                        fullResponse.append(content);
                                    } catch (IOException e) {
                                        System.err.println("客户端已断开连接: " + e.getMessage());
                                        break;
                                    }
                                }
                            }
                        } catch (IOException e) {
                            System.err.println("JSON 解析失败: " + e.getMessage());
                            System.err.println("原始数据: " + line);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("错误: " + e.getMessage());
                }
            }
        };
    }

}