package com.kama.jchatmind.model.request;

import com.kama.jchatmind.model.dto.ChatMessageDTO;
import lombok.Builder;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Data
@Builder
public class CreateChatMessageRequest {
    private String agentId;
    @NotBlank
    private String sessionId;
    @NotNull
    private ChatMessageDTO.RoleType role;
    @NotBlank
    @Size(max = 100000)
    private String content;
    private ChatMessageDTO.MetaData metadata;
}
