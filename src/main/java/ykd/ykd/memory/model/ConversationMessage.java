package ykd.ykd.memory.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {
    private Long id;

    private String userId;

    private String role;

    private String content;

    private String messageType;

    private String modelName;

    private String createdAt;
}
