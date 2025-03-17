package org.wmg.memeroyai.tool;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author 朱文赵
 * @date 2024/9/24
 */
@NoArgsConstructor
@Data
public class ChatCompletionResultChoiceDTO {

    private Integer index;
    private ChatCompletionResultChoiceDeltaDTO delta;
    private String message;
    private String finish_reason;

}
