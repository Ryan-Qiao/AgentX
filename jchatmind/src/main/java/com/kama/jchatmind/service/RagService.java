package com.kama.jchatmind.service;

import com.kama.jchatmind.model.rag.RagSearchResponse;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    RagSearchResponse search(String kbId, String query);

    List<String> similaritySearch(String kbId, String title);
}
