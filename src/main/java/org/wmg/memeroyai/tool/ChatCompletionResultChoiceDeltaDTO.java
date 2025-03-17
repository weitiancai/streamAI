package org.wmg.memeroyai.tool;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author 朱文赵
 * @date 2024/9/24
 */
@NoArgsConstructor
@Data
public class ChatCompletionResultChoiceDeltaDTO {

    /** 真正的ai返回结果 */
    private String content;


}
