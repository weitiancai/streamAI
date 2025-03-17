package org.wmg.memeroyai.tool;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Optional;

@NoArgsConstructor
@Data
public class ChatCompletionResultDTO {

    private String id;
    private String object;
    private Integer created;
    private String model;
    private List<ChatCompletionResultChoiceDTO> choices;
    private String usage;
    private String warning;


    public Optional<String> extractAiRealResult() {
        if (CollectionUtils.isEmpty(this.choices)) {
            return Optional.empty();
        }
        return this.choices.stream()
                .findFirst()
                .map(ChatCompletionResultChoiceDTO::getDelta)
                .map(ChatCompletionResultChoiceDeltaDTO::getContent);
    }

}
