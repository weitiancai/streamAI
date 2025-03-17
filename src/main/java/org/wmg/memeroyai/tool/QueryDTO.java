package org.wmg.memeroyai.tool;

import org.springframework.web.bind.annotation.RequestParam;

/**
 * @author wmg
 * @date 2025/3/16
 */

public class QueryDTO {
    String sessionId;
    String question;

    public QueryDTO(String sessionId, String question) {
        this.sessionId = sessionId;
        this.question = question;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
