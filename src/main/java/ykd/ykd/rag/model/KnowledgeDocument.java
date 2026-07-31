package ykd.ykd.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocument {
    private Long id;
    private String userId;
    private String fileName;
    private String fileType;
    private String fileHash;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
