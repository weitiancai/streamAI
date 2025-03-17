package org.wmg.memeroyai;

/**
 * @author wmg
 * @date 2025/3/16
 */
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okhttp3.RequestBody;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.wmg.memeroyai.tool.ChatMemory;
import org.wmg.memeroyai.tool.QueryDTO;

import javax.management.Query;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
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
//            "content": "什么是Java多线程？"
//    }
//  ]
//}'


    @PostMapping("/ask")
    public StreamingResponseBody streamAsk(@org.springframework.web.bind.annotation.RequestBody QueryDTO queryDTO) throws Exception {
        String question = queryDTO.getQuestion();
        String sessionId = queryDTO.getSessionId();
        // 解码URL参数
        String decodedQuestion = URLDecoder.decode(question, StandardCharsets.UTF_8);
        // 添加用户问题到历史
        memory.addHistory(sessionId, "user", decodedQuestion);

        // 构建API请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("appId", "7bdf76730b274c29ccd98d78462fdaad");
        requestBody.put("model", "deepseek-v3");
        requestBody.put("stream", true);
        requestBody.put("messages", memory.getHistory(sessionId));

        Request request = new Request.Builder()
                .url("http://10.199.137.235:31971/mudgate/api/model/aliyun/v1/chat/completions")
                .post(RequestBody.create(mapper.writeValueAsString(requestBody), JSON))
                .build();

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