package com.kama.jchatmind.model.request;

import lombok.Data;

import java.util.List;

@Data
public class DeleteChatSessionsRequest {
    private List<String> chatSessionIds;
}
